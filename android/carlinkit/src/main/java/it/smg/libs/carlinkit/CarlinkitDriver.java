package it.smg.libs.carlinkit;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * USB driver for the Carlinkit dongle.
 *
 * Uses only UsbDeviceConnection.bulkTransfer (available since API 12), therefore
 * compatible with the head unit's Android 4.0.3 (API 15).
 *
 * Reading runs on its own thread and delivers messages through {@link Listener}.
 * The heartbeat runs on a separate thread — the dongle ends the session without it.
 */
public final class CarlinkitDriver {

    private static final String TAG = "CarlinkitDriver";

    /** Read timeout. Kept short so the stop flag can be checked often. */
    private static final int READ_TIMEOUT_MS = 500;
    private static final int WRITE_TIMEOUT_MS = 2000;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    /** Consecutive heartbeat failures tolerated before declaring the link dead. */
    private static final int HEARTBEAT_MAX_FAILURES = 3;

    public interface Listener {
        /**
         * H.264 frame (Annex-B, 4-byte start code). The array is reused between
         * calls: consume or copy it before returning.
         *
         * @param isParameterSet true when this frame carried SPS/PPS
         */
        void onVideoFrame(VideoMessage msg, byte[] payload, boolean isParameterSet);

        /** Audio message: PCM, command (start/stop) or volumeDuration. */
        void onAudio(AudioMessage msg, byte[] payload);

        /** Phone connected. phoneType: 5=AndroidAuto, 3=CarPlay. */
        void onPhoneConnected(int phoneType);

        void onPhoneDisconnected();

        /** Command message (0x08) received from the dongle — connection states. */
        void onDongleCommand(int command);

        /** Any other message. payload may be null. */
        void onMessage(int type, byte[] payload);

        void onError(String message, Throwable cause);
    }

    public static final class Config {
        public int width = 800;
        public int height = 480;
        public int fps = 20;
        public int format = 5;
        public int packetMax = 49152;
        public int iBoxVersion = 2;
        public int phoneWorkMode = 2;
        public int mediaDelay = 300;
        public int dpi = 140;
        /** MANDATORY for Android phones; without it the dongle does not connect. */
        public boolean androidWorkMode = true;
        /** 0 = left-hand drive. */
        public int handDriveMode = 0;
        public String boxName = "HondaHRV";
        /** true = 5GHz. The head unit usually performs better on 5GHz when available. */
        public boolean wifi5g = true;
        /** true = dongle microphone; false = car microphone. */
        public boolean boxMic = false;
    }

    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private final Config config;
    private final Listener listener;

    private volatile boolean running;
    private volatile boolean phoneConnected;
    private volatile Thread readThread;
    private volatile Thread heartbeatThread;
    private volatile Thread connectThread;
    private final VideoMessage.ParameterSetCache paramCache =
            new VideoMessage.ParameterSetCache();

    private CarlinkitDriver(UsbDeviceConnection connection, UsbInterface iface,
                            UsbEndpoint epIn, UsbEndpoint epOut,
                            Config config, Listener listener) {
        this.connection = connection;
        this.iface = iface;
        this.epIn = epIn;
        this.epOut = epOut;
        this.config = config;
        this.listener = listener;
    }

    /**
     * Locates the vendor-specific interface and the bulk endpoints, claims it and returns
     * the driver ready to use. Interface 1 of the dongle is mass storage and must be ignored.
     *
     * @return the driver, or null if the device does not expose the expected interface.
     */
    public static CarlinkitDriver open(UsbDevice device, UsbDeviceConnection connection,
                                       Config config, Listener listener) {
        UsbInterface target = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == 255) { // vendor specific
                target = candidate;
                break;
            }
        }
        if (target == null) {
            Log.e(TAG, "vendor-specific interface not found");
            return null;
        }

        UsbEndpoint in = null, out = null;
        for (int i = 0; i < target.getEndpointCount(); i++) {
            UsbEndpoint ep = target.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                continue;
            }
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                in = ep;
            } else {
                out = ep;
            }
        }
        if (in == null || out == null) {
            Log.e(TAG, "bulk IN/OUT endpoints not found");
            return null;
        }
        if (!connection.claimInterface(target, true)) {
            Log.e(TAG, "claimInterface failed");
            return null;
        }
        return new CarlinkitDriver(connection, target, in, out, config, listener);
    }

    /** SPS/PPS cache, for reinjection when the Surface is recreated. */
    public VideoMessage.ParameterSetCache parameterSetCache() {
        return paramCache;
    }

    public boolean isPhoneConnected() {
        return phoneConnected;
    }

    /** Sends the init sequence and starts the read and heartbeat threads. */
    public synchronized void start() {
        if (running || stopped) {
            // `stopped` matters as much as `running`: stop() clears running early and then
            // works outside the monitor, so without this a start() slipping into that window
            // would raise a whole new session for stop() to tear down underneath.
            return;
        }
        // Leftovers from a previous session would go out BEFORE the init sequence, since the
        // queue is FIFO, contradicting the ordering this method depends on.
        sendQueue.clear();
        running = true;
        // The write thread starts FIRST so the init sequence has a consumer. The queue is
        // FIFO and nothing else is sending yet, so the 12 init messages keep the exact order
        // the dongle expects, and start() stops blocking its caller: it used to perform 12
        // synchronous USB writes on whichever thread opened the session.
        writeThread = new Thread(new WriteLoop(), "carlinkit-write");
        writeThread.start();
        sendInitSequence();

        readThread = new Thread(new ReadLoop(), "carlinkit-read");
        readThread.start();
        heartbeatThread = new Thread(new HeartbeatLoop(), "carlinkit-heartbeat");
        heartbeatThread.start();
        connectThread = new Thread(new ConnectLoop(), "carlinkit-connect");
        connectThread.start();
    }

    /**
     * Closes the session.
     *
     * The heavy part runs OUTSIDE the monitor on purpose. Joining four threads and writing the
     * closing messages can take seconds, and holding the lock through it would make a
     * concurrent start() wait just as long, on the UI thread, which is an ANR.
     */
    public void stop() {
        Thread write, read, beat, connect;
        synchronized (this) {
            if (!stopped) {
                stopped = true;
            } else {
                return;   // idempotent: releasing the interface twice is not harmless
            }
            running = false;
            write = writeThread;
            read = readThread;
            beat = heartbeatThread;
            connect = connectThread;
        }
        // Let the write thread finish before the closing messages, so they are not queued
        // behind traffic that no longer matters.
        joinQuietly(write);
        try {
            // Both in one critical section: the heartbeat also bypasses the queue, and letting
            // it slip between these two would break the closing order of the protocol.
            synchronized (writeLock) {
                sendNow(CarlinkitProtocol.Type.COMMAND,
                        int32(CarlinkitProtocol.Command.RELEASE_VIDEO_FOCUS));
                sendNow(CarlinkitProtocol.Type.CLOSE_DONGLE, null);
            }
        } catch (Throwable ignored) {
            // the dongle may already have been unplugged
        }
        joinQuietly(read);
        joinQuietly(beat);
        joinQuietly(connect);
        try {
            // Under writeLock: a join that expired can leave the heartbeat mid-bulkTransfer,
            // and releasing the interface underneath it is undefined behaviour in native code.
            synchronized (writeLock) {
                connection.releaseInterface(iface);
            }
        } catch (Throwable ignored) {
        }
        long dropped = sendDropped.get();
        long failed = sendFailed.get();
        if (dropped > 0 || failed > 0) {
            Log.i(TAG, "send queue: " + dropped + " dropped, " + failed + " failed");
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Outgoing message, queued so that no caller thread blocks on USB.
     */
    private static final class Outgoing {
        final int type;
        final byte[] payload;

        Outgoing(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * Send queue. Everything the app sends goes through here, and a single thread writes to
     * USB.
     *
     * The reason is a real risk, not tidiness. A write is two bulkTransfer calls with
     * WRITE_TIMEOUT_MS each, so up to 4s of blocking, and the write path used to be
     * synchronized and called from four different threads:
     *
     *   - the UI thread, for touch and steering keys that arrive as KeyEvents
     *   - the head unit's Binder thread, for the steering wheel service callback
     *   - the microphone thread, one chunk every 20ms during a call or the assistant
     *   - the heartbeat thread
     *
     * With a slow dongle the microphone held the lock and the head unit's Binder thread queued
     * behind it. Blocking a system service's IPC thread for seconds can wedge the unit's own
     * steering handling, and doing it on the UI thread is how Android declares an ANR. Neither
     * is acceptable, and moving the work to the main thread (a tempting one-line fix) only
     * swaps which thread hangs.
     *
     * Capacity is bounded because a wedged dongle would otherwise grow the queue without limit:
     * audio alone produces 50 messages per second.
     */
    private static final int SEND_QUEUE_CAPACITY = 128;
    private final java.util.concurrent.BlockingQueue<Outgoing> sendQueue =
            new java.util.concurrent.ArrayBlockingQueue<Outgoing>(SEND_QUEUE_CAPACITY);
    private volatile Thread writeThread;
    /** Atomic because send() runs on several threads and the write loop increments too. */
    private final java.util.concurrent.atomic.AtomicLong sendDropped =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sendFailed =
            new java.util.concurrent.atomic.AtomicLong();
    /** Guards stop() against running twice, which would release the interface twice. */
    private boolean stopped;

    /**
     * Queues a message. Returns false only when the queue could not accept it, which in
     * practice means the dongle stopped draining.
     *
     * NOTE ON THE RETURN VALUE: true means "accepted for sending", not "sent". No caller can
     * get the USB result without blocking, which is exactly what this queue exists to avoid.
     * Callers that count failures (the microphone does) still get a useful signal, because a
     * queue that stops draining is itself the symptom.
     */
    public boolean send(int type, byte[] payload) {
        Outgoing msg = new Outgoing(type, payload);
        if (sendQueue.offer(msg)) {
            return true;
        }
        // Queue full. Audio is the only stream dense enough to fill it, and stale audio is
        // worthless, so drop the oldest audio to make room for whatever is being sent now.
        // A command or a touch matters more than a 20ms chunk from the past.
        if (dropOldestAudio() && sendQueue.offer(msg)) {
            sendDropped.incrementAndGet();
            return true;
        }
        sendDropped.incrementAndGet();
        return false;
    }

    /** Removes the oldest AUDIO_DATA message from the queue. @return true if one was removed */
    private boolean dropOldestAudio() {
        for (Outgoing o : sendQueue) {
            if (o.type == CarlinkitProtocol.Type.AUDIO_DATA && sendQueue.remove(o)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The actual USB write.
     *
     * Guarded by its own lock rather than trusting that only one thread gets here. The write
     * thread is the normal caller, but two others bypass the queue on purpose: the heartbeat
     * (which must not wait behind up to 127 audio messages, since the dongle ends the session
     * without it) and stop(). And stop() can overlap with the write thread, because
     * joinQuietly waits 1500 ms while one WriteLoop iteration can take up to 4.2 s. Two
     * concurrent writers would interleave a header with someone else's payload and
     * desynchronize the dongle's stream.
     */
    private final Object writeLock = new Object();

    private boolean sendNow(int type, byte[] payload) {
        synchronized (writeLock) {
            int len = payload == null ? 0 : payload.length;
            byte[] header = MessageHeader.toBytes(type, len);
            int w = connection.bulkTransfer(epOut, header, header.length, WRITE_TIMEOUT_MS);
            if (w != header.length) {
                return false;
            }
            if (len > 0) {
                w = connection.bulkTransfer(epOut, payload, len, WRITE_TIMEOUT_MS);
                return w == len;
            }
            return true;
        }
    }

    /** Messages dropped because the queue was full (the dongle stopped draining). */
    public long sendDropped() {
        return sendDropped.get();
    }

    /** Messages that reached USB and failed there. */
    public long sendFailed() {
        return sendFailed.get();
    }

    private final class WriteLoop implements Runnable {
        @Override
        public void run() {
            while (running) {
                Outgoing msg;
                try {
                    // Poll with a timeout so the loop notices `running` going false even
                    // when nothing is being sent.
                    msg = sendQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (msg == null) {
                    continue;
                }
                try {
                    if (!sendNow(msg.type, msg.payload)) {
                        sendFailed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    // The dongle may have been unplugged mid-write. Disconnection is reported
                    // by the USB_DEVICE_DETACHED broadcast, not by the read loop, so all this
                    // thread has to do is survive and keep counting.
                    sendFailed.incrementAndGet();
                }
            }
        }
    }

    public boolean sendCommand(int command) {
        return send(CarlinkitProtocol.Type.COMMAND, int32(command));
    }

    /**
     * Sends a touch event. Coordinates normalized to 0..10000, as the dongle expects.
     *
     * @param action a value from {@link CarlinkitProtocol.TouchAction}
     */
    public boolean sendTouch(int action, float xRatio, float yRatio) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(action);
        bb.putInt((int) (10000 * xRatio));
        bb.putInt((int) (10000 * yRatio));
        bb.putInt(0);
        return send(CarlinkitProtocol.Type.TOUCH, bb.array());
    }

    /** SendFile (0x99): [len(name+NUL)][name+NUL][len(content)][content] */
    private boolean sendFile(String name, byte[] content) {
        byte[] nb = asciiBytes(name + "\0");
        ByteBuffer bb = ByteBuffer.allocate(4 + nb.length + 4 + content.length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(nb.length);
        bb.put(nb);
        bb.putInt(content.length);
        bb.put(content);
        return send(CarlinkitProtocol.Type.SEND_FILE, bb.array());
    }

    private boolean sendIntFile(String name, int value) {
        return sendFile(name, int32(value));
    }

    private void sendInitSequence() {
        // Order validated against the real dongle (firmware 2022.11.19.1218CHY).
        sendIntFile("/tmp/screen_dpi", config.dpi);

        // SendOpen: width, height, fps, format, packetMax, iBoxVersion, phoneWorkMode
        ByteBuffer open = ByteBuffer.allocate(28);
        open.order(ByteOrder.LITTLE_ENDIAN);
        open.putInt(config.width);
        open.putInt(config.height);
        open.putInt(config.fps);
        open.putInt(config.format);
        open.putInt(config.packetMax);
        open.putInt(config.iBoxVersion);
        open.putInt(config.phoneWorkMode);
        send(CarlinkitProtocol.Type.OPEN, open.array());

        sendIntFile("/tmp/night_mode", 0);
        sendIntFile("/tmp/hand_drive_mode", config.handDriveMode);
        sendIntFile("/tmp/charge_mode", 1);
        sendFile("/etc/box_name", asciiBytes(config.boxName + "\0"));
        // Without android_work_mode the dongle reports btDisconnected/deviceNotFound and
        // never sends video for Android phones.
        if (config.androidWorkMode) {
            sendIntFile("/etc/android_work_mode", 1);
        }

        // BoxSettings: ASCII JSON
        String json = "{\"mediaDelay\":" + config.mediaDelay
                + ",\"syncTime\":" + System.currentTimeMillis()
                + ",\"androidAutoSizeW\":" + config.width
                + ",\"androidAutoSizeH\":" + config.height + "}";
        send(CarlinkitProtocol.Type.BOX_SETTINGS, asciiBytes(json));

        sendCommand(CarlinkitProtocol.Command.WIFI_ENABLE);
        sendCommand(config.wifi5g
                ? CarlinkitProtocol.Command.WIFI_5G
                : CarlinkitProtocol.Command.WIFI_24G);
        sendCommand(config.boxMic
                ? CarlinkitProtocol.Command.BOX_MIC
                : CarlinkitProtocol.Command.MIC);
        sendCommand(CarlinkitProtocol.Command.AUDIO_TRANSFER_OFF);
    }

    /**
     * Asks the dongle to connect to the paired phone. Must be sent ~1s after the init;
     * the dongle answers with scanningDevice -> btConnected -> wifiConnected.
     * Resend periodically while no phone is connected.
     */
    public boolean requestPhoneConnection() {
        return sendCommand(CarlinkitProtocol.Command.WIFI_CONNECT);
    }

    /**
     * Requests a new keyframe. Needed when the Surface is recreated, since the dongle sends
     * only one IDR at the beginning of the stream.
     */
    /**
     * Sends microphone PCM to the dongle (voice assistant and phone calls).
     *
     * Payload format, as validated in the protocol:
     * {@code [decodeType=5 (16kHz mono)][volume=0.0f][audioType=3][PCM Int16 LE]}
     *
     * @param pcm buffer holding 16-bit little-endian PCM samples
     * @param len valid bytes in {@code pcm}
     */
    public boolean sendMicAudio(byte[] pcm, int len) {
        if (len <= 0) {
            return false;
        }
        byte[] payload = new byte[AudioMessage.HEADER_SIZE + len];
        // decodeType = 5 -> 16000 Hz, 1 channel
        payload[0] = 5;
        // volume float 0.0f and audioType = 3 (input); bytes 4..7 stay zeroed
        payload[8] = 3;
        System.arraycopy(pcm, 0, payload, AudioMessage.HEADER_SIZE, len);
        return send(CarlinkitProtocol.Type.AUDIO_DATA, payload);
    }

    public boolean requestKeyFrame() {
        return sendCommand(CarlinkitProtocol.Command.FRAME);
    }

    /** Translates a Honda steering wheel button and sends it to the dongle. */
    public boolean sendSteeringWheelKey(int hondaKeyType) {
        int cmd = SteeringWheelMapper.toCarlinkitCommand(hondaKeyType);
        return cmd != -1 && sendCommand(cmd);
    }

    private static byte[] int32(int value) {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(value);
        return bb.array();
    }

    /** US-ASCII without relying on StandardCharsets (missing on API 15). */
    private static byte[] asciiBytes(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    // ---------------------------------------------------------------- reading

    private final class ReadLoop implements Runnable {
        @Override
        public void run() {
            byte[] headerBuf = new byte[CarlinkitProtocol.HEADER_SIZE];
            // Reused payload buffer; grows if needed.
            byte[] payloadBuf = new byte[config.packetMax];

            while (running) {
                int read = connection.bulkTransfer(
                        epIn, headerBuf, headerBuf.length, READ_TIMEOUT_MS);
                if (read <= 0) {
                    continue; // timeout: normal, lets 'running' be re-evaluated
                }
                if (read != CarlinkitProtocol.HEADER_SIZE) {
                    Log.w(TAG, "partial header: " + read + " bytes");
                    continue;
                }

                MessageHeader header;
                try {
                    header = MessageHeader.fromBytes(headerBuf, 0, read);
                } catch (IllegalArgumentException e) {
                    // Stream desynchronization: discard and carry on.
                    Log.w(TAG, "invalid header: " + e.getMessage());
                    continue;
                }

                byte[] payload = null;
                if (header.hasPayload()) {
                    if (header.length > payloadBuf.length) {
                        payloadBuf = new byte[header.length];
                    }
                    int got = readFully(payloadBuf, header.length);
                    if (got != header.length) {
                        Log.w(TAG, "incomplete payload: " + got + "/" + header.length);
                        continue;
                    }
                    payload = payloadBuf;
                }
                dispatch(header, payload);
            }
        }

        /** bulkTransfer may return less than requested; read in a loop. */
        private int readFully(byte[] buf, int total) {
            int offset = 0;
            byte[] chunk = new byte[Math.min(total, 16384)];
            while (offset < total && running) {
                int want = Math.min(chunk.length, total - offset);
                int n = connection.bulkTransfer(epIn, chunk, want, READ_TIMEOUT_MS);
                if (n < 0) {
                    break;
                }
                System.arraycopy(chunk, 0, buf, offset, n);
                offset += n;
            }
            return offset;
        }

        private void dispatch(MessageHeader header, byte[] payload) {
            try {
                switch (header.type) {
                    case CarlinkitProtocol.Type.VIDEO_DATA: {
                        VideoMessage vm = VideoMessage.parse(payload, header.length);
                        if (vm != null) {
                            // SPS/PPS arrive only ONCE: cache them for later reinjection
                            boolean isParams = paramCache.offer(vm, payload);
                            listener.onVideoFrame(vm, payload, isParams);
                        }
                        break;
                    }
                    case CarlinkitProtocol.Type.AUDIO_DATA: {
                        AudioMessage am = AudioMessage.parse(payload, header.length);
                        if (am != null) {
                            listener.onAudio(am, payload);
                        }
                        break;
                    }
                    case CarlinkitProtocol.Type.PLUGGED: {
                        int phoneType = -1;
                        if (payload != null && header.length >= 4) {
                            ByteBuffer bb = ByteBuffer.wrap(payload, 0, 4);
                            bb.order(ByteOrder.LITTLE_ENDIAN);
                            phoneType = bb.getInt();
                        }
                        phoneConnected = true;
                        listener.onPhoneConnected(phoneType);
                        break;
                    }
                    case CarlinkitProtocol.Type.UNPLUGGED:
                        phoneConnected = false;
                        paramCache.clear();
                        listener.onPhoneDisconnected();
                        break;
                    case CarlinkitProtocol.Type.COMMAND: {
                        if (payload != null && header.length >= 4) {
                            ByteBuffer bb = ByteBuffer.wrap(payload, 0, 4);
                            bb.order(ByteOrder.LITTLE_ENDIAN);
                            listener.onDongleCommand(bb.getInt());
                        }
                        break;
                    }
                    default: {
                        byte[] copy = null;
                        if (payload != null) {
                            copy = new byte[header.length];
                            System.arraycopy(payload, 0, copy, 0, header.length);
                        }
                        listener.onMessage(header.type, copy);
                        break;
                    }
                }
            } catch (Exception e) {
                listener.onError("error while processing " + header, e);
            }
        }
    }

    /**
     * Resends wifiConnect while no phone is connected. The dongle does not connect on its
     * own: during validation, without this command it stayed in deviceNotFound indefinitely.
     */
    private final class ConnectLoop implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(1000);   // the dongle needs ~1s after the init
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (running) {
                if (!phoneConnected) {
                    requestPhoneConnection();
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private final class HeartbeatLoop implements Runnable {
        @Override
        public void run() {
            int consecutiveFailures = 0;
            while (running) {
                // sendNow, not the queue: the dongle ends the session without a heartbeat,
                // and queueing it would put it behind up to 127 audio messages during a
                // call. This thread is ours to block, unlike the UI and Binder threads the
                // queue exists to protect.
                boolean ok;
                try {
                    ok = sendNow(CarlinkitProtocol.Type.HEARTBEAT, null);
                } catch (Throwable t) {
                    // bulkTransfer on a connection that was closed or unplugged can throw, and
                    // an uncaught exception on any thread takes the whole process down. stop()
                    // creates exactly this window: it releases the interface after joins that
                    // may expire while this thread is mid-write.
                    ok = false;
                }
                if (!ok) {
                    // A single failure is not fatal: one expired bulkTransfer is enough to
                    // land here, and giving up on the first one used to kill the heartbeat for
                    // good, with the dongle ending the session seconds later and nothing in
                    // the log explaining why.
                    if (++consecutiveFailures >= HEARTBEAT_MAX_FAILURES) {
                        listener.onError("heartbeat failed " + consecutiveFailures
                                + " times in a row", null);
                        return;
                    }
                } else {
                    consecutiveFailures = 0;
                }
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
