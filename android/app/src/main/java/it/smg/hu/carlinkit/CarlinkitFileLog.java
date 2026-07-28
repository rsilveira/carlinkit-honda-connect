package it.smg.hu.carlinkit;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * File log for diagnosing the Carlinkit mode.
 *
 * The Honda head unit does not expose adb: without a persistent log, a failure would show up
 * only as a black screen, with no clue at all.
 *
 * <p><b>Where it writes.</b> The mount path of the flash drive varies between head units and
 * is not known in advance. Instead of assuming a path, this class:
 * <ol>
 *   <li>reads {@code /proc/mounts} and builds the list of writable filesystems;</li>
 *   <li>picks the target preferring a <b>USB flash drive</b> (easy to read on a PC), falling
 *       back to the app external storage and finally to the internal one;</li>
 *   <li>writes the <b>full storage diagnostics</b> at the beginning of the log — that way the
 *       first test in the car reveals where the flash drive actually mounts.</li>
 * </ol>
 *
 * If a better target shows up later (a flash drive plugged in with the app already open),
 * {@link #reevaluateTarget(Context)} moves the writing there.
 *
 * <p><b>One file per process.</b> Since CarlinkitService runs in its own ":usb" process, this
 * class is initialised twice and each instance writes its own file:
 * {@code carlinkit-<timestamp>.log} in the UI process and
 * {@code carlinkit-<timestamp>-usb.log} in ":usb". They must not share a file: each process
 * has its own PrintWriter and its own buffer, so {@code synchronized} here means nothing
 * across the process boundary and the lines would interleave mid-write. See
 * {@link #processName()} for how the process is detected.
 */
public final class CarlinkitFileLog {

    private static final String DIR_NAME = "carlinkit";
    private static final String FILE_PREFIX = "carlinkit-";
    private static final String FILE_EXT = ".log";
    /** Suffix appended to the file name in the ":usb" service process. */
    private static final String USB_SUFFIX = "-usb";
    /** Process name suffix declared by {@code android:process=":usb"} in the manifest. */
    private static final String USB_PROCESS = ":usb";
    private static final int MAX_SIZE_BYTES = 512 * 1024;
    /** Keep at most this many log files; older ones are deleted on startup. */
    private static final int MAX_FILES = 15;
    /** Hard cap for the whole log directory. */
    private static final long MAX_DIR_BYTES = 4L * 1024 * 1024;
    /**
     * Pruning never touches a file younger than this. Both processes prune the same
     * directory at roughly the same time, and without this guard one of them could delete
     * the log the other has just opened and is still writing to.
     */
    private static final long MIN_AGE_MS = 60 * 1000L;

    /** Hints that a mount point is removable USB storage. */
    private static final String[] USB_HINTS = {
            "usb", "udisk", "sda", "sdb", "otg", "removable"
    };

    private static CarlinkitFileLog instance;

    /** Cached process name; it cannot change while the process lives. */
    private static volatile String processName;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private PrintWriter writer;
    private File file;
    private String targetKind = "?";
    private int prunedCount;

    private CarlinkitFileLog(Context ctx) {
        List<String> diag = new ArrayList<String>();
        File dir = chooseTarget(ctx, diag);
        try {
            if (dir != null) {
                // A new file is created on every app start, so old ones must be pruned or
                // they accumulate forever on the head unit's small data partition.
                pruneOldLogs(dir);
                file = new File(dir, FILE_PREFIX
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                        // Per-process suffix: the UI and the ":usb" service both log, and a
                        // single file written by two processes interleaves and truncates.
                        + fileSuffix()
                        + FILE_EXT);
                writer = new PrintWriter(new FileWriter(file, true));
            }
        } catch (Throwable t) {
            writer = null;
        }
        writeLine("=== log started ===");
        writeLine("process: " + processName() + (isUsbProcess() ? " [USB service]" : " [UI]"));
        writeLine("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " | Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        writeLine("log written to: " + (file != null ? file.getAbsolutePath() : "NONE")
                + " (" + targetKind + ")");
        if (prunedCount > 0) {
            writeLine("pruned " + prunedCount + " old log file(s)");
        }
        writeLine("--- storage diagnostics ---");
        for (String line : diag) {
            writeLine("  " + line);
        }
        writeLine("--- end of diagnostics ---");
    }

    /**
     * Chooses where to write and fills {@code diag} with everything that was found.
     * The diagnostics is what answers "where does the flash drive mount on this head unit".
     */
    private File chooseTarget(Context ctx, List<String> diag) {
        List<File> usbCandidates = new ArrayList<File>();
        List<File> otherCandidates = new ArrayList<File>();

        // 1) Real mount points, read from the kernel
        for (String[] mount : readMounts()) {
            String device = mount[0];
            String path = mount[1];
            String type = mount[2];
            File f = new File(path);
            boolean writable = f.isDirectory() && f.canWrite();
            boolean looksUsb = looksLikeUsb(device) || looksLikeUsb(path);
            diag.add("mount " + path + "  dev=" + device + " type=" + type
                    + (writable ? " [writable]" : " [read-only]")
                    + (looksUsb ? " [USB?]" : ""));
            if (!writable) {
                continue;
            }
            if (looksUsb) {
                usbCandidates.add(f);
            } else if (isInterestingFs(type)) {
                otherCandidates.add(f);
            }
        }

        // 2) Classic head unit / old Android paths, even if they do not show up in /proc/mounts
        String[] guesses = {
                "/mnt/usb", "/mnt/usbhost", "/mnt/usbhost1", "/mnt/usb_storage",
                "/mnt/udisk", "/mnt/usbdisk", "/storage/usb", "/storage/usbdisk",
                "/storage/UsbDriveA", "/mnt/media_rw/usb", "/mnt/sda", "/mnt/sda1",
                "/mnt/extsd", "/mnt/external_sd", "/mnt/sdcard/usbStorage"
        };
        for (String g : guesses) {
            File f = new File(g);
            if (f.isDirectory()) {
                boolean w = f.canWrite();
                diag.add("guess " + g + (w ? " [exists, writable]" : " [exists, not writable]"));
                if (w && !usbCandidates.contains(f)) {
                    usbCandidates.add(f);
                }
            }
        }

        // 3) Standard Android external storage
        File ext = Environment.getExternalStorageDirectory();
        diag.add("Environment.getExternalStorageDirectory() = "
                + (ext != null ? ext.getAbsolutePath()
                   + (ext.canWrite() ? " [writable]" : " [not writable]") : "null")
                + " state=" + Environment.getExternalStorageState());

        File appExt = null;
        try {
            appExt = ctx.getExternalFilesDir(null);
            diag.add("getExternalFilesDir() = "
                    + (appExt != null ? appExt.getAbsolutePath() : "null"));
        } catch (Throwable t) {
            diag.add("getExternalFilesDir() failed: " + t.getMessage());
        }

        // Preference: flash drive > app external > external root > internal
        for (File cand : usbCandidates) {
            File d = new File(cand, DIR_NAME);
            if (d.isDirectory() || d.mkdirs()) {
                targetKind = "USB flash drive";
                return d;
            }
        }
        if (appExt != null) {
            File d = new File(appExt, DIR_NAME);
            if (d.isDirectory() || d.mkdirs()) {
                targetKind = "app external storage";
                return d;
            }
        }
        if (ext != null && ext.canWrite()) {
            File d = new File(ext, DIR_NAME);
            if (d.isDirectory() || d.mkdirs()) {
                targetKind = "external storage root";
                return d;
            }
        }
        for (File cand : otherCandidates) {
            File d = new File(cand, DIR_NAME);
            if (d.isDirectory() || d.mkdirs()) {
                targetKind = "writable mount: " + cand.getAbsolutePath();
                return d;
            }
        }
        try {
            File d = new File(ctx.getFilesDir(), DIR_NAME);
            if (d.isDirectory() || d.mkdirs()) {
                targetKind = "INTERNAL storage (hard to extract)";
                return d;
            }
        } catch (Throwable ignored) {
        }
        targetKind = "no writable target";
        return null;
    }

    /**
     * Deletes the oldest log files, keeping the directory bounded by both file count and
     * total size. Runs before a new log is created.
     *
     * <p>Both name patterns are pruned together ({@code carlinkit-<ts>.log} from the UI and
     * {@code carlinkit-<ts>-usb.log} from the ":usb" service): the limits protect the
     * partition, so they must apply to the sum of the two, not to each pattern separately.
     */
    private void pruneOldLogs(File dir) {
        try {
            File[] logs = dir.listFiles(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return isLogFileName(name);
                }
            });
            if (logs == null || logs.length == 0) {
                return;
            }
            // Oldest first, by the timestamp embedded in the name (yyyyMMdd-HHmmss, which is
            // sortable as text). Comparing whole names would be wrong now that two suffixes
            // exist: "...-164509-usb.log" must not be ordered after "...-170000.log".
            java.util.Arrays.sort(logs, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    int c = timestampOf(a.getName()).compareTo(timestampOf(b.getName()));
                    return c != 0 ? c : a.getName().compareTo(b.getName());
                }
            });
            long total = 0;
            for (File f : logs) {
                total += f.length();
            }
            int removed = 0;
            long now = System.currentTimeMillis();
            // Leave room for the file about to be created
            for (int i = 0; i < logs.length
                    && (logs.length - removed >= MAX_FILES || total > MAX_DIR_BYTES); i++) {
                // The other process may have just opened its own log; do not delete it
                if (now - logs[i].lastModified() < MIN_AGE_MS) {
                    continue;
                }
                long len = logs[i].length();
                if (logs[i].delete()) {
                    total -= len;
                    removed++;
                }
            }
            if (removed > 0) {
                prunedCount = removed;
            }
        } catch (Throwable ignored) {
            // Housekeeping must never break logging
        }
    }

    /** Matches both {@code carlinkit-<ts>.log} and {@code carlinkit-<ts>-usb.log}. */
    private static boolean isLogFileName(String name) {
        return name != null && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_EXT);
    }

    /** The {@code yyyyMMdd-HHmmss} part of a log file name, or the whole name if absent. */
    private static String timestampOf(String name) {
        String s = name.substring(FILE_PREFIX.length(), name.length() - FILE_EXT.length());
        if (s.endsWith(USB_SUFFIX)) {
            s = s.substring(0, s.length() - USB_SUFFIX.length());
        }
        return s;
    }

    /** @return list of {device, mountPoint, fsType} */
    private static List<String[]> readMounts() {
        List<String[]> out = new ArrayList<String[]>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    out.add(new String[]{parts[0], parts[1], parts[2]});
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean looksLikeUsb(String s) {
        if (s == null) {
            return false;
        }
        String low = s.toLowerCase(Locale.US);
        for (String hint : USB_HINTS) {
            if (low.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Filters out pseudo-filesystems: only the ones that really hold files matter. */
    private static boolean isInterestingFs(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.US);
        return t.contains("vfat") || t.contains("fat") || t.contains("exfat")
                || t.contains("ntfs") || t.contains("ext") || t.contains("fuse")
                || t.contains("sdcardfs");
    }

    // ------------------------------------------------------- process detection

    /**
     * Name of the process this code is running in, e.g. {@code it.smg.hu.carlinkit} for the
     * UI and {@code it.smg.hu.carlinkit:usb} for {@link CarlinkitService}.
     *
     * <p>Read from {@code /proc/self/cmdline}. That file exists on every Android version
     * (this app targets API 15) and is always readable by the process itself, while
     * {@code ActivityManager.getRunningAppProcesses()} needs a Context, allocates, and on
     * these old head unit ROMs returns a stale or incomplete list — including, sometimes, no
     * entry at all for the process that is asking.
     *
     * <p>Lives here, and not in ODAApplication, because this class needs it before any
     * Context work is done and ODAApplication would otherwise duplicate the parsing.
     *
     * @return the process name, or {@code ""} if it could not be read (treated as the main
     *         process, which is the pre-existing behaviour)
     */
    public static String processName() {
        String cached = processName;
        if (cached == null) {
            cached = readProcessName();
            processName = cached;
        }
        return cached;
    }

    /** True in the service process declared as {@code android:process=":usb"}. */
    public static boolean isUsbProcess() {
        return processName().endsWith(USB_PROCESS);
    }

    /** Per-process log file name suffix: empty for the UI, {@code -usb} for the service. */
    private static String fileSuffix() {
        return isUsbProcess() ? USB_SUFFIX : "";
    }

    private static String readProcessName() {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n > 0) {
                // cmdline is a NUL-separated argv, NUL-padded: argv[0] is the process name
                int end = 0;
                while (end < n && buf[end] != 0) {
                    end++;
                }
                String s = new String(buf, 0, end).trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // Never fail because of this: "" means "assume the main process"
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ API

    public static synchronized void init(Context ctx) {
        if (instance == null) {
            instance = new CarlinkitFileLog(ctx);
        }
    }

    /**
     * Re-evaluates the target — useful when a flash drive is plugged in with the app already
     * open. If a better target is found (USB), writing moves there.
     */
    public static synchronized void reevaluateTarget(Context ctx) {
        if (instance == null) {
            init(ctx);
            return;
        }
        if ("USB flash drive".equals(instance.targetKind)) {
            return;   // already on the best target
        }
        instance.writeLine("re-evaluating the log target...");
        String oldPath = instance.file != null ? instance.file.getAbsolutePath() : "none";
        CarlinkitFileLog fresh = new CarlinkitFileLog(ctx);
        if ("USB flash drive".equals(fresh.targetKind)) {
            instance.writeLine("log moved to " + fresh.file.getAbsolutePath());
            instance.close0();
            fresh.writeLine("(continuation; previous log at " + oldPath + ")");
            instance = fresh;
        } else {
            fresh.close0();
            instance.writeLine("no better target found");
        }
    }

    public static synchronized void log(String tag, String message) {
        if (instance != null) {
            instance.writeLine(tag + ": " + message);
        }
    }

    public static synchronized void log(String tag, String message, Throwable t) {
        if (instance == null) {
            return;
        }
        instance.writeLine(tag + ": " + message);
        if (t != null) {
            instance.writeLine("  " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 8); i++) {
                instance.writeLine("    at " + st[i]);
            }
        }
    }

    /** Path of the current log, to be shown on the head unit screen. */
    public static synchronized String path() {
        return instance != null && instance.file != null
                ? instance.file.getAbsolutePath() : "(unavailable)";
    }

    /** Description of the target kind in use. */
    public static synchronized String targetKind() {
        return instance != null ? instance.targetKind : "?";
    }

    private synchronized void writeLine(String line) {
        if (writer == null) {
            return;
        }
        try {
            writer.println(timeFormat.format(new Date()) + " " + line);
            writer.flush();   // the app can be killed without warning
            if (file != null && file.length() > MAX_SIZE_BYTES) {
                writer.println("=== size limit reached (" + MAX_SIZE_BYTES / 1024 + " KB) ===");
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void close0() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    public static synchronized void close() {
        if (instance != null) {
            instance.writeLine("=== log closed ===");
            instance.close0();
        }
    }
}
