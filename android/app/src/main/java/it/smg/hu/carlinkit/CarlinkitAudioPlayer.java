package it.smg.hu.carlinkit;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import it.smg.libs.carlinkit.AudioMessage;
import it.smg.libs.common.Log;

/**
 * Plays back the PCM that comes from the Carlinkit dongle through {@link AudioTrack}.
 *
 * The dongle delivers raw (uncompressed) PCM, so there is no decoding — writing to the
 * AudioTrack is enough. During validation with Android Auto the format was
 * <b>48000 Hz, 2 channels, 16 bit</b> ({@code decodeType=4}).
 *
 * <p><b>The protocol {@code volume} field is not a gain.</b> In the real capture it came in
 * as 0.00 while the PCM had normal amplitude (-15 to -23 dBFS). Applying it as a scale factor
 * would mute the audio. Volume is controlled by the head unit (amplifier).
 *
 * <p>The format can change at runtime — navigation uses 16 kHz mono, phone calls 8 kHz mono —
 * which is why the AudioTrack is recreated whenever the {@code decodeType} changes.
 *
 * {@code AudioTrack} has existed since API 3, so it is compatible with Android 4.0.3.
 */
public final class CarlinkitAudioPlayer {

    private static final String TAG = "CarlinkitAudio";

    /**
     * One AudioTrack per decodeType, instead of one that gets recreated on every change.
     *
     * Measured on 10/Aug: during a call the dongle alternates decodeType 3 (8000 Hz, the voice)
     * and 4 (48000 Hz, the media) message by message, dozens of times per second. Recreating
     * the track each time meant a stop/release/new/play cycle at that rate, which is expensive
     * on this SoC and drops the first samples after every switch. Keeping both alive costs two
     * small buffers.
     */
    private final java.util.HashMap<Integer, AudioTrack> tracks =
            new java.util.HashMap<Integer, AudioTrack>();
    private AudioTrack track;
    private volatile int currentDecodeType = -1;
    private volatile int activeTrackCount;
    private volatile boolean running;
    /** In the background the dongle stays connected, but we must not play audio. */
    private volatile boolean muted;
    private volatile long bytesWritten;

    private static final long AUDIO_WRITE_SLOW_MS = 20;
    private static final long WORKER_STOP_WAIT_MS = 500;
    private long audioWriteCount;
    private long audioWriteTotalMs;
    private long audioWriteMaxMs;
    private long audioWriteSlowCount;
    private long audioWritePartialCount;
    private long audioWriteErrorCount;
    private long audioInputCount;
    private long audioInputBytes;
    private long audioInputMaxGapMs;
    private long audioInputLastNs;
    private volatile int audioTrackBufferBytes;

    /**
     * How much audio is still buffered ahead of the speaker, in milliseconds.
     *
     * Why this exists: every other counter in this class stops at {@code AudioTrack.write()}, so a
     * session with clean delivery and audible stutter was indistinguishable from a session with
     * clean delivery and no stutter. Measured in the car on 03/09: 130 minutes of music with
     * in=117 per window (the theoretical maximum), qdrop=0, errors=0, and the driver still heard
     * short interruptions. Delivery was therefore not the problem, and nothing measured what
     * happened after the write.
     *
     * {@code AudioTrack.getUnderrunCount()} would answer this directly but needs API 24, and this
     * head unit reports Android 4.0.4 (API 15), so the fill is derived from
     * {@code getPlaybackHeadPosition()} instead, which exists since API 3.
     *
     * The number to compare against is the buffer depth: 65536 B at 48000 Hz stereo 16 bit is
     * 16384 frames, or 341 ms. Delivery gaps measured the same day reached 286 ms, 84% of it.
     */
    private long audioFillLastMs = -1;
    private long audioFillMinMs = -1;
    /** How many readings had to be thrown away in this window because of counter drift. */
    private int audioFillResyncCount;
    /** Returned by {@link #fillFrames} when the two counters cannot be compared. */
    private static final long FILL_DESYNC = Long.MIN_VALUE;
    /** Frames written per decodeType, in a one-element box because API 15 has no compute(). */
    private final java.util.Map<Integer, long[]> trackFrames =
            new java.util.HashMap<Integer, long[]>();
    private int currentFrameBytes;
    private int currentSampleRate;

    /** Runs on the USB read loop before the payload is copied into the worker queue. */
    private synchronized void recordAudioInput(int bytes) {
        long now = System.nanoTime();
        if (audioInputLastNs != 0) {
            long gapMs = (now - audioInputLastNs) / 1000000L;
            if (gapMs > audioInputMaxGapMs) {
                audioInputMaxGapMs = gapMs;
            }
        }
        audioInputLastNs = now;
        audioInputCount++;
        audioInputBytes += bytes;
    }

    /**
     * AudioTrack write timing since the previous call, then resets the interval counters.
     *
     * The output includes the current format and number of live tracks because the navigation
     * path can alternate media and voice formats. Since fbd66b6 those tracks are cached instead
     * of released; if the problem starts only after navigation, seeing two live tracks matters.
     *
     * @return interval stats, or null when no PCM was written in the interval
     */
    public synchronized String drainAudioStats() {
        long droppedTotal = audioQueueDropped.get();
        long dropped = droppedTotal - audioQueueDroppedLast;
        audioQueueDroppedLast = droppedTotal;
        int queueMax = audioQueueMaxDepth.getAndSet(audioQueue.size());
        String workerProblem = audioWorkerProblem;
        if (audioWriteCount == 0 && audioInputCount == 0 && dropped == 0 && queueMax == 0
                && workerProblem == null) {
            return null;
        }
        String s = "type=" + currentDecodeType
                + " tracks=" + activeTrackCount
                + " in=" + audioInputCount
                + " inBytes=" + audioInputBytes
                + " maxGap=" + audioInputMaxGapMs + "ms"
                + " buffer=" + audioTrackBufferBytes + "B"
                + " q=" + audioQueue.size() + "/" + AUDIO_QUEUE_CAPACITY
                + " qmax=" + queueMax
                + (dropped > 0 ? " qdrop=" + dropped : "")
                + (audioWriteCount == 0 ? " noWrites"
                    : " writes=" + audioWriteCount
                      + " max=" + audioWriteMaxMs + "ms"
                      + " slow=" + audioWriteSlowCount
                      + " avg=" + (audioWriteTotalMs / audioWriteCount) + "ms")
                + (audioWritePartialCount > 0 ? " partial=" + audioWritePartialCount : "")
                + (audioWriteErrorCount > 0 ? " errors=" + audioWriteErrorCount : "")
                + (audioFillLastMs < 0 ? ""
                    : " fill=" + audioFillLastMs + "ms min=" + audioFillMinMs + "ms")
                + (audioFillResyncCount > 0 ? " fillResync=" + audioFillResyncCount : "")
                + (workerProblem != null ? " worker=" + workerProblem : "");
        audioWriteCount = 0;
        audioWriteTotalMs = 0;
        audioWriteMaxMs = 0;
        audioWriteSlowCount = 0;
        audioWritePartialCount = 0;
        audioWriteErrorCount = 0;
        // The minimum restarts each window; keeping the session minimum would freeze on the first
        // dip and stop showing whether the fill recovers or keeps sliding down.
        audioFillMinMs = audioFillLastMs;
        audioFillResyncCount = 0;
        audioInputCount = 0;
        audioInputBytes = 0;
        audioInputMaxGapMs = 0;
        return s;
    }

    /**
     * PCM queue between the USB read loop and AudioTrack.
     *
     * Incoming payload arrays are reused by the driver, so each queued chunk owns a copy.
     * 16 chunks are roughly 320 ms at the observed 20 ms cadence. A high-water mark trims
     * back to 4 chunks (~80 ms) before the queue is full: real-time voice and navigation audio
     * must drop stale samples instead of playing them hundreds of milliseconds late.
     */
    private static final int AUDIO_QUEUE_CAPACITY = 16;
    private static final int AUDIO_QUEUE_HIGH_WATERMARK = 12;
    private static final int AUDIO_QUEUE_TARGET = 4;
    private final java.util.concurrent.BlockingQueue<AudioWork> audioQueue =
            new java.util.concurrent.ArrayBlockingQueue<AudioWork>(AUDIO_QUEUE_CAPACITY);
    private final Object lifecycleLock = new Object();
    private volatile Thread audioThread;
    private final java.util.concurrent.atomic.AtomicLong audioQueueDropped =
            new java.util.concurrent.atomic.AtomicLong();
    private long audioQueueDroppedLast;
    private final java.util.concurrent.atomic.AtomicInteger audioQueueMaxDepth =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile String audioWorkerProblem;

    private static final class AudioWork {
        static final int PCM = 1;
        static final int COMMAND = 2;
        static final int MUTE = 3;

        final int kind;
        final int decodeType;
        final int command;
        final byte[] pcm;
        final int pcmLength;
        final boolean muted;

        private AudioWork(int kind, int decodeType, int command, byte[] pcm, int pcmLength,
                boolean muted) {
            this.kind = kind;
            this.decodeType = decodeType;
            this.command = command;
            this.pcm = pcm;
            this.pcmLength = pcmLength;
            this.muted = muted;
        }

        static AudioWork pcm(int decodeType, byte[] pcm, int pcmLength) {
            return new AudioWork(PCM, decodeType, 0, pcm, pcmLength, false);
        }

        static AudioWork command(int command, int decodeType) {
            return new AudioWork(COMMAND, decodeType, command, null, 0, false);
        }

        static AudioWork mute(boolean muted) {
            return new AudioWork(MUTE, 0, 0, null, 0, muted);
        }
    }


    /** Reuses PCM arrays so decoupling the read loop does not create 50 allocations per second. */
    private final java.util.ArrayDeque<byte[]> pcmPool = new java.util.ArrayDeque<byte[]>();

    private byte[] acquirePcmBuffer(int length) {
        synchronized (pcmPool) {
            java.util.Iterator<byte[]> it = pcmPool.iterator();
            while (it.hasNext()) {
                byte[] candidate = it.next();
                if (candidate.length >= length) {
                    it.remove();
                    return candidate;
                }
            }
        }
        return new byte[length];
    }

    private void recyclePcmBuffer(byte[] buffer) {
        if (buffer == null) {
            return;
        }
        synchronized (pcmPool) {
            if (pcmPool.size() < AUDIO_QUEUE_CAPACITY) {
                pcmPool.addLast(buffer);
            }
        }
    }

    /** Clears queued work and returns its PCM arrays to the pool. */
    private void clearQueueAndRecycle() {
        AudioWork work;
        while ((work = audioQueue.poll()) != null) {
            if (work.kind == AudioWork.PCM) {
                recyclePcmBuffer(work.pcm);
            }
        }
    }
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            // stop() may still be waiting for a native AudioTrack.write to return. Never start a
            // second consumer against the same tracks: that would create concurrent writes and
            // release-with-write-in-flight. Give the old worker the same window stop() gives it.
            long deadline = System.currentTimeMillis() + WORKER_STOP_WAIT_MS;
            while (audioThread != null && audioThread.isAlive()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    audioWorkerProblem = "start-refused-prev-worker-alive";
                    CarlinkitFileLog.log(TAG, "audio start refused: previous worker still alive");
                    return;
                }
                try {
                    lifecycleLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            clearQueueAndRecycle();
            audioQueueDropped.set(0);
            audioQueueDroppedLast = 0;
            audioQueueMaxDepth.set(0);
            synchronized (this) {
                audioWriteCount = 0;
                audioWriteTotalMs = 0;
                audioWriteMaxMs = 0;
                audioWriteSlowCount = 0;
                audioWritePartialCount = 0;
                audioWriteErrorCount = 0;
                audioInputCount = 0;
                audioInputBytes = 0;
                audioInputMaxGapMs = 0;
                audioInputLastNs = 0;
            }
            audioTrackBufferBytes = 0;
            audioWorkerProblem = null;
            running = true;
            Thread t = new Thread(new AudioWriteLoop(), "CarlinkitAudioWrite");
            audioThread = t;
            t.start();
        }
    }

    /**
     * Mutes without destroying the AudioTrack. Used when the Activity goes off screen: the
     * USB connection is kept to avoid re-enumeration, but the audio has to stop so it does
     * not compete with the FM radio.
     */
    public void setMuted(boolean value) {
        muted = value;
        if (!running) {
            return;
        }
        offerControl(AudioWork.mute(value), value);
    }

    public void stop() {
        Thread t;
        synchronized (lifecycleLock) {
            if (!running && audioThread == null) {
                return;
            }
            running = false;
            clearQueueAndRecycle();
            t = audioThread;
            if (t != null) {
                t.interrupt();
            }
        }
        if (t != null) {
            try {
                t.join(WORKER_STOP_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (lifecycleLock) {
                if (audioThread == t && !t.isAlive()) {
                    audioThread = null;
                }
                lifecycleLock.notifyAll();
            }
            if (t.isAlive()) {
                CarlinkitFileLog.log(TAG, "audio worker still blocked "
                        + WORKER_STOP_WAIT_MS + "ms after stop");
            }
        }
        Log.i(TAG, "stopped (" + bytesWritten + " bytes played, "
                + audioQueueDropped.get() + " queued chunks dropped)");
    }

    private void releaseTrack() {
        for (AudioTrack t : tracks.values()) {
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    t.stop();
                }
                t.release();
            } catch (Throwable e) {
                Log.e(TAG, "error releasing an AudioTrack", e);
            }
        }
        tracks.clear();
        synchronized (this) {
            trackFrames.clear();
            audioFillLastMs = -1;
            audioFillMinMs = -1;
        }
        activeTrackCount = 0;
        track = null;
        currentDecodeType = -1;
    }

    /**
     * Ensures there is an AudioTrack compatible with the given format.
     *
     * @return true if there is a track ready to be written to
     */
    private boolean ensureTrack(int decodeType) {
        if (track != null && currentDecodeType == decodeType) {
            if (!muted && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play();
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        // Reuse the track for this format if it already exists. This is the whole point of the
        // map: switching format becomes a pointer swap instead of a stop/release/new/play cycle.
        AudioTrack existing = tracks.get(Integer.valueOf(decodeType));
        if (existing != null) {
            track = existing;
            currentDecodeType = decodeType;
            adoptFormat(decodeType);
            if (!muted) {
                try {
                    track.play();
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        AudioMessage.Format f = AudioMessage.formatOf(decodeType);
        if (f == null) {
            Log.e(TAG, "unknown decodeType: " + decodeType);
            return false;
        }

        int channelConfig = f.channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioTrack.getMinBufferSize(f.sampleRate, channelConfig, encoding);
        if (minBuf <= 0) {
            Log.e(TAG, "invalid getMinBufferSize for " + f);
            return false;
        }
        // A buffer larger than the minimum reduces underruns on the head unit, which has a modest CPU
        int bufSize = minBuf * 4;

        try {
            AudioTrack fresh = new AudioTrack(AudioManager.STREAM_MUSIC, f.sampleRate,
                    channelConfig, encoding, bufSize, AudioTrack.MODE_STREAM);
            if (fresh.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack did not initialize for " + f);
                try {
                    fresh.release();
                } catch (Throwable ignored) {
                }
                return false;
            }
            if (!muted) {
                fresh.play();
            }
            tracks.put(Integer.valueOf(decodeType), fresh);
            activeTrackCount = tracks.size();
            audioTrackBufferBytes = bufSize;
            track = fresh;
            currentDecodeType = decodeType;
            adoptFormat(decodeType);
            // In the file log too: the format the dongle SENDS is the best available clue to
            // the format it EXPECTS back from the microphone. On 10/Aug this line reported
            // 8000Hz during a call while the microphone was sending 16000Hz upstream, which is
            // why the far end heard nothing. The head unit exposes no logcat to check it any
            // other way. Logged once per format, not per switch.
            CarlinkitFileLog.log(TAG, "audio out: decodeType=" + decodeType + " -> " + f
                    + " buffer=" + bufSize + "B");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "failed to create the AudioTrack for " + f, t);
            return false;
        }
    }

    /**
     * Handles an audio message from the dongle without blocking the USB read loop.
     *
     * PCM payloads are copied because the driver reuses its read buffer immediately after this
     * callback returns. AudioTrack creation, switching, flushing and writes all belong to the
     * AudioWriteLoop; no AudioTrack method runs on the USB thread anymore.
     */
    public void onAudio(AudioMessage msg, byte[] payload) {
        if (!running || msg == null) {
            return;
        }
        switch (msg.kind) {
            case AudioMessage.KIND_COMMAND:
                // Commands keep FIFO order but do not purge PCM from other decodeTypes. A
                // NAVI_STOP followed by media packets must not erase 80-240 ms of music.
                offerControl(AudioWork.command(msg.command, msg.decodeType), false);
                break;
            case AudioMessage.KIND_PCM:
                if (payload == null || msg.pcmLength <= 0) {
                    return;
                }
                recordAudioInput(msg.pcmLength);
                if (muted) {
                    return;
                }
                byte[] copy = acquirePcmBuffer(msg.pcmLength);
                System.arraycopy(payload, msg.pcmOffset, copy, 0, msg.pcmLength);
                offerPcm(AudioWork.pcm(msg.decodeType, copy, msg.pcmLength));
                break;
            default:
                // volumeDuration: informational
                break;
        }
    }

    /** Keeps current audio: if full, drops the oldest queued PCM and retries once. */
    private void offerPcm(AudioWork work) {
        int trimmed = 0;
        if (audioQueue.size() >= AUDIO_QUEUE_HIGH_WATERMARK) {
            while (audioQueue.size() > AUDIO_QUEUE_TARGET && dropOldestPcm()) {
                trimmed++;
            }
            if (trimmed > 0) {
                audioQueueDropped.addAndGet(trimmed);
            }
        }
        if (audioQueue.offer(work)) {
            updateQueueMaxDepth();
            return;
        }
        if (dropOldestPcm()) {
            audioQueueDropped.incrementAndGet();
            if (audioQueue.offer(work)) {
                updateQueueMaxDepth();
                return;
            }
        }
        // The newest chunk also lost the race for the freed slot. It never reached the worker,
        // so its array must return to the pool here.
        recyclePcmBuffer(work.pcm);
        audioQueueDropped.incrementAndGet();
    }

    /** Controls may purge stale PCM when they stop a source or mute the head unit. */
    private void offerControl(AudioWork work, boolean purgePcm) {
        int purged = 0;
        if (purgePcm) {
            while (dropOldestPcm()) {
                purged++;
            }
        }
        if (purged > 0) {
            audioQueueDropped.addAndGet(purged);
        }
        if (audioQueue.offer(work)) {
            updateQueueMaxDepth();
            return;
        }
        // A queue full of controls is not useful state. Keep the newest command/mute event.
        int discarded = audioQueue.size();
        clearQueueAndRecycle();
        audioQueueDropped.addAndGet(discarded);
        if (!audioQueue.offer(work)) {
            audioWorkerProblem = "control-enqueue-failed";
            CarlinkitFileLog.log(TAG, "audio control enqueue failed after clearing queue");
        }
        updateQueueMaxDepth();
    }

    private boolean dropOldestPcm() {
        for (AudioWork w : audioQueue) {
            if (w.kind == AudioWork.PCM && audioQueue.remove(w)) {
                recyclePcmBuffer(w.pcm);
                return true;
            }
        }
        return false;
    }

    private void updateQueueMaxDepth() {
        int depth = audioQueue.size();
        int seen = audioQueueMaxDepth.get();
        while (depth > seen && !audioQueueMaxDepth.compareAndSet(seen, depth)) {
            seen = audioQueueMaxDepth.get();
        }
    }


    /** The only thread allowed to create, switch, flush, write or release AudioTracks. */
    private final class AudioWriteLoop implements Runnable {
        @Override
        public void run() {
            final Thread self = Thread.currentThread();
            try {
                while (running && audioThread == self) {
                    AudioWork work;
                    try {
                        work = audioQueue.poll(200,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        if (!running || audioThread != self) {
                            return;
                        }
                        continue;
                    }
                    if (work == null) {
                        continue;
                    }
                    switch (work.kind) {
                        case AudioWork.PCM:
                            try {
                                writePcm(work.decodeType, work.pcm, work.pcmLength);
                            } finally {
                                recyclePcmBuffer(work.pcm);
                            }
                            break;
                        case AudioWork.COMMAND:
                            handleCommand(work.command, work.decodeType);
                            break;
                        case AudioWork.MUTE:
                            applyMuted(work.muted);
                            break;
                        default:
                            break;
                    }
                }
            } catch (Throwable t) {
                audioWorkerProblem = "dead-" + t.getClass().getSimpleName();
                CarlinkitFileLog.log(TAG, "audio worker died: " + t.getClass().getName()
                        + ": " + t.getMessage());
                Log.e(TAG, "audio worker died", t);
                synchronized (lifecycleLock) {
                    if (audioThread == self) {
                        running = false;
                    }
                }
            } finally {
                // AudioTrack release stays on the same thread that writes to it. This avoids a
                // release racing with a blocked write when stop() times out waiting for us.
                releaseTrack();
                synchronized (lifecycleLock) {
                    if (audioThread == self) {
                        audioThread = null;
                        running = false;
                    }
                    lifecycleLock.notifyAll();
                }
            }
        }
    }

    private void writePcm(int decodeType, byte[] pcm, int pcmLength) {
        if (muted || pcm == null || pcmLength <= 0) {
            return;
        }
        if (!ensureTrack(decodeType)) {
            synchronized (this) {
                audioWriteErrorCount++;
            }
            return;
        }
        long t0 = System.nanoTime();
        try {
            int n = track.write(pcm, 0, pcmLength);
            long ms = (System.nanoTime() - t0) / 1000000L;
            recordWrite(ms, n, pcmLength, false);
        } catch (Throwable t) {
            long ms = (System.nanoTime() - t0) / 1000000L;
            recordWrite(ms, -1, pcmLength, true);
            Log.e(TAG, "error writing PCM", t);
        }
    }

    private synchronized void recordWrite(long ms, int n, int expected, boolean threw) {
        audioWriteCount++;
        audioWriteTotalMs += ms;
        if (ms > audioWriteMaxMs) {
            audioWriteMaxMs = ms;
        }
        if (ms >= AUDIO_WRITE_SLOW_MS) {
            audioWriteSlowCount++;
        }
        if (n > 0) {
            bytesWritten += n;
            recordFill(n);
            if (n != expected) {
                audioWritePartialCount++;
            }
        } else if (n < 0 || threw) {
            audioWriteErrorCount++;
            if (!threw) {
                Log.e(TAG, "AudioTrack.write returned " + n);
            }
        }
    }

    /**
     * Remembers the frame geometry of the format now playing, and makes sure it has a counter.
     *
     * Frame size and sample rate are cached instead of being looked up per write because the write
     * path runs 23 times a second per stream and {@code formatOf} allocates.
     */
    private void adoptFormat(int decodeType) {
        AudioMessage.Format f = AudioMessage.formatOf(decodeType);
        if (f == null) {
            currentFrameBytes = 0;
            currentSampleRate = 0;
            return;
        }
        currentFrameBytes = f.channels * (f.bitsPerSample / 8);
        currentSampleRate = f.sampleRate;
        Integer key = Integer.valueOf(decodeType);
        if (trackFrames.get(key) == null) {
            trackFrames.put(key, new long[1]);
        }
    }

    /**
     * Updates how far ahead of the speaker the buffer is, from the frames actually consumed.
     *
     * {@code getPlaybackHeadPosition()} counts frames rendered by this track since it was created
     * or last flushed, so the written-frames counter has to be reset at every flush site or the
     * difference drifts upwards forever. It is a 32-bit frame count, hence the unsigned handling:
     * it wraps after about 24 h of playback on a single track, well past any drive, but a wrap
     * without the mask would read as a hugely negative fill.
     */
    /**
     * Frames still buffered ahead of the speaker, or {@link #FILL_DESYNC} when the two
     * counters cannot be compared.
     *
     * Both a 32 bit wrap and a pair of counters that drifted apart show up as a negative
     * difference, and they need opposite treatment. A wrap needs the written count to have
     * reached the top of the range, which takes about 24 h of playback on a single track,
     * so it cannot happen early in a drive. Everything else negative is drift: a flush
     * resets {@code getPlaybackHeadPosition()} and our own count at slightly different
     * moments, and then the head briefly reads ahead of what we believe we wrote.
     *
     * Treating drift as a wrap is what ruined the measurement. In the logs of 16/09, 559
     * of 612 readings came out around 89419669 ms against a 341 ms buffer, and 89419669 ms
     * is 2^32 frames minus a little: almost every reading was a few frames of drift being
     * inflated by four billion.
     *
     * Static and free of side effects so the arithmetic can be tested on its own.
     */
    static long fillFrames(long written, long head) {
        long diff = (written & 0xFFFFFFFFL) - head;
        if (diff >= 0) {
            return diff;
        }
        if ((written & 0xFFFFFFFFL) > 0xF0000000L) {
            return diff + 0x100000000L;      // genuine wrap near the top of the range
        }
        return FILL_DESYNC;
    }

    private void recordFill(int bytes) {
        AudioTrack t = track;
        if (t == null || currentFrameBytes <= 0 || currentSampleRate <= 0) {
            return;
        }
        long[] box = trackFrames.get(Integer.valueOf(currentDecodeType));
        if (box == null) {
            return;
        }
        box[0] += bytes / currentFrameBytes;
        long head;
        try {
            head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (Throwable ignored) {
            return;
        }
        long frames = fillFrames(box[0], head);
        if (frames == FILL_DESYNC) {
            // Line the count up with the track and let the next writes rebuild the figure,
            // instead of reporting a number that is four billion frames wrong.
            box[0] = head;
            audioFillResyncCount++;
            return;
        }
        long fillMs = frames * 1000L / currentSampleRate;
        audioFillLastMs = fillMs;
        if (audioFillMinMs < 0 || fillMs < audioFillMinMs) {
            audioFillMinMs = fillMs;
        }
    }

    /** After a flush the track restarts its frame count from zero, so ours must too. */
    private synchronized void resetFrames(int decodeType) {
        long[] box = trackFrames.get(Integer.valueOf(decodeType));
        if (box != null) {
            box[0] = 0;
        }
    }

    private void applyMuted(boolean value) {
        for (AudioTrack t : tracks.values()) {
            try {
                if (value) {
                    t.pause();
                    t.flush();
                } else {
                    t.play();
                }
            } catch (Throwable ignored) {
            }
        }
        if (value) {
            // Muting flushes every track, so every frame counter restarts from zero as well.
            synchronized (this) {
                for (long[] box : trackFrames.values()) {
                    box[0] = 0;
                }
                audioFillLastMs = -1;
                audioFillMinMs = -1;
            }
        }
    }
    private void handleCommand(int command, int decodeType) {
        String name = AudioMessage.Command.name(command);
        Log.i(TAG, "AudioCommand " + name + " decodeType=" + decodeType + " (worker)");
        switch (command) {
            case AudioMessage.Command.OUTPUT_STOP:
            case AudioMessage.Command.MEDIA_STOP:
            case AudioMessage.Command.PHONECALL_STOP:
            case AudioMessage.Command.NAVI_STOP:
            case AudioMessage.Command.SIRI_STOP:
                // Flush only the format that stopped. Navigation and media can alternate in the
                // same queue; flushing the current pointer may erase the other source.
                AudioTrack stopped = tracks.get(Integer.valueOf(decodeType));
                if (stopped != null) {
                    try {
                        if (stopped.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                            stopped.pause();
                        }
                        stopped.flush();
                    } catch (Throwable ignored) {
                    }
                    resetFrames(decodeType);
                }
                break;
            default:
                break;
        }
    }

    public long bytesWritten() {
        return bytesWritten;
    }
}
