package org.wwhdrecomp.wwhd;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The diagnostics ZIP for a bug report (menu: About > Export diagnostics), modelled on the Diablo III port's
 * Diagnostics.java / ZipAssembler.java. Built in the app cache (crash-share/, the crashlogs FileProvider's folder)
 * and then shared with the share sheet or saved where the player chooses (ACTION_CREATE_DOCUMENT); nothing is
 * uploaded. Contents, only what helps debugging:
 *
 *   device.txt         phone, SoC, Android and API level, RAM total/available, ABIs, thermal status and headroom,
 *                      display size and refresh rates, app version/build, Vulkan feature levels
 *   perf_report.txt    the overlay's "Copy performance report" text (build, commit, GPU, driver choice, the last
 *                      render profiler report), from the running game (WwhdActivity.nativeDiagnosticReport)
 *   vulkan.txt         the renderer's start-up lines from the log (device, driver, extensions, rendering path, ...)
 *   wwhd.log, wwhd-previous.log   the runtime's log of this run and the previous one (captures/, the last 8 MB)
 *   crash-*.log        every crash report in captures/ (the newest 20)
 *   logcat.txt         this process's logcat (the last 20,000 lines)
 *   exit_info.txt      why the process last ended (ApplicationExitInfo), with the trace when there is one
 *   settings.txt       settings.ini, env.txt, the menu and controls preferences, the WWHD_* launch environment
 *   caches.txt         sizes and counts of the shader pack, SPIR-V and pipeline caches
 *
 * Game files, keys and save data are left out. User paths are redacted as the runtime does for its log
 * (crash_redact.h: /home/NAME, /Users/NAME, X:\Users\NAME become ~).
 */
final class Diagnostics {
    private Diagnostics() {}

    static final long LOG_TAIL = 8L << 20;
    static final long WHOLE_MAX = 1L << 20;
    static final int LOGCAT_LINES = 20000;
    static final int CRASH_LOGS = 20;

    /** sm3dw-diagnostico-AAAAMMDD-HHMM.zip */
    static String fileName(Date when) {
        return "sm3dw-diagnostico-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(when) + ".zip";
    }

    /** Builds the ZIP into {@code folder} (any thread but the UI one: logcat takes a moment). */
    static File build(Context context, File folder, String performanceReport) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        File[] old = folder.listFiles((dir, name) -> name.startsWith("sm3dw-diagnostico-") && name.endsWith(".zip"));
        if (old != null) for (File f : old) f.delete();
        File zip = new File(folder, fileName(new Date()));
        File external = context.getExternalFilesDir(null);
        File captures = external == null ? null : new File(external, "captures");
        try (ZipOutputStream out = new ZipOutputStream(new java.io.FileOutputStream(zip))) {
            text(out, "device.txt", device(context));
            text(out, "perf_report.txt", performanceReport == null ? "(the game is not running)\n" : redact(performanceReport));
            text(out, "vulkan.txt", vulkanLines(captures));
            tail(out, "wwhd.log", captures == null ? null : new File(captures, "wwhd.log"), LOG_TAIL);
            tail(out, "wwhd-previous.log", captures == null ? null : new File(captures, "wwhd-previous.log"), LOG_TAIL);
            if (captures != null) {
                File[] crashes = captures.listFiles((dir, name) -> name.startsWith("crash-") && name.endsWith(".log"));
                if (crashes != null) {
                    Arrays.sort(crashes, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (int i = 0; i < crashes.length && i < CRASH_LOGS; ++i)
                        tail(out, "crashes/" + crashes[i].getName(), crashes[i], LOG_TAIL);
                }
            }
            text(out, "logcat.txt", logcat());
            text(out, "exit_info.txt", exits(context));
            text(out, "settings.txt", settings(context, external));
            text(out, "caches.txt", caches(context, external));
        }
        return zip;
    }

    // ---- ZIP entries ----
    static void text(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** The last {@code max} bytes of a text file, redacted; absent or empty files add nothing. */
    static void tail(ZipOutputStream zip, String name, File file, long max) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) return;
        byte[] bytes;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - max);
            in.seek(start);
            bytes = new byte[(int) (in.length() - start)];
            in.readFully(bytes);
        }
        text(zip, name, redact(new String(bytes, StandardCharsets.UTF_8)));
    }

    static String whole(File file) {
        if (file == null || !file.isFile() || file.length() > WHOLE_MAX) return null;
        try {
            return redact(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    // ---- redaction (runtime/src/crash_redact.h) ----
    private static final Pattern HOME = Pattern.compile("(?i)(/home/|/Users/|[a-z]:[\\\\/]Users[\\\\/])[^/\\\\\\s\"']+");

    static String redact(String s) {
        return HOME.matcher(s).replaceAll("~");
    }

    // ---- the parts ----
    static String device(Context context) {
        StringBuilder s = new StringBuilder();
        s.append("app=").append(context.getPackageName()).append(' ').append(versionLine(context)).append('\n');
        s.append("manufacturer=").append(Build.MANUFACTURER).append("\nbrand=").append(Build.BRAND)
                .append("\nmodel=").append(Build.MODEL).append("\ndevice=").append(Build.DEVICE)
                .append("\nhardware=").append(Build.HARDWARE).append("\nboard=").append(Build.BOARD)
                .append("\nsoc=").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL)
                .append("\nandroid=").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(", security patch ").append(Build.VERSION.SECURITY_PATCH).append(")\nfingerprint=")
                .append(Build.FINGERPRINT).append('\n');
        s.append("abis=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.append("ram_total_mb=").append(mi.totalMem >> 20).append(" ram_available_mb=").append(mi.availMem >> 20)
                    .append(" low_memory_threshold_mb=").append(mi.threshold >> 20).append(" low_memory=")
                    .append(mi.lowMemory).append(" memory_class_mb=").append(am.getMemoryClass())
                    .append(" large_memory_class_mb=").append(am.getLargeMemoryClass()).append('\n');
        }
        s.append("cpus=").append(Runtime.getRuntime().availableProcessors()).append(" cpu_capacity=");
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); ++i)
            s.append(readLine("/sys/devices/system/cpu/cpu" + i + "/cpu_capacity")).append(' ');
        s.append("\ngpu_model=").append(readLine("/sys/class/kgsl/kgsl-3d0/gpu_model")).append('\n');
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            s.append("thermal_status=").append(pm.getCurrentThermalStatus())
                    .append(" (0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown)")
                    .append(" thermal_headroom_10s=").append(pm.getThermalHeadroom(10))
                    .append(" power_save=").append(pm.isPowerSaveMode())
                    .append(" sustained_performance_supported=").append(pm.isSustainedPerformanceModeSupported()).append('\n');
        }
        s.append("battery_temp_c=").append(tenths(readLine("/sys/class/power_supply/battery/temp"))).append('\n');
        s.append(PerformanceThermals.read(context)).append('\n');
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) for (Display d : dm.getDisplays()) {
            Display.Mode mode = d.getMode();
            s.append("display ").append(d.getDisplayId()).append(" \"").append(d.getName()).append("\": ")
                    .append(mode.getPhysicalWidth()).append('x').append(mode.getPhysicalHeight()).append('@')
                    .append(mode.getRefreshRate()).append(" Hz (mode ").append(mode.getModeId()).append(")\n");
            for (Display.Mode m : d.getSupportedModes())
                s.append("  mode ").append(m.getModeId()).append(": ").append(m.getPhysicalWidth()).append('x')
                        .append(m.getPhysicalHeight()).append('@').append(m.getRefreshRate()).append('\n');
        }
        for (android.content.pm.FeatureInfo f : context.getPackageManager().getSystemAvailableFeatures())
            if (f.name != null && f.name.startsWith("android.hardware.vulkan"))
                s.append(f.name).append("=0x").append(Integer.toHexString(f.version)).append('\n');
        return s.toString();
    }

    static String versionLine(Context context) {
        try {
            android.content.pm.PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return p.versionName + " (build " + p.getLongVersionCode() + ", installed "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(p.lastUpdateTime)) + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String readLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line = r.readLine();
            return line == null ? "?" : line.trim();
        } catch (IOException | RuntimeException e) {
            return "?";
        }
    }

    private static String tenths(String value) {
        try { return String.format(Locale.ROOT, "%.1f", Integer.parseInt(value) / 10.0); }
        catch (RuntimeException e) { return "?"; }
    }

    /** The renderer's start-up lines ([vulkan ...], [shader pack], [perf] heat guard...) from this run's log. */
    static String vulkanLines(File captures) {
        StringBuilder s = new StringBuilder();
        File log = captures == null ? null : new File(captures, "wwhd.log");
        if (log == null || !log.isFile()) return "(no captures/wwhd.log: start the game once)\n";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(log))) {
            String line;
            int n = 0, read = 0;
            while ((line = r.readLine()) != null && n < 400 && read++ < 20000) {
                if (line.startsWith("[vulkan") || line.startsWith("[shader pack]") || line.startsWith("[perf] heat guard")
                        || line.startsWith("[perf] performance hint") || line.startsWith("[log]")) {
                    s.append(redact(line)).append('\n');
                    ++n;
                }
            }
        } catch (IOException e) {
            s.append("unreadable: ").append(e).append('\n');
        }
        return s.toString();
    }

    /** This process's log (an app reads only its own): the last LOGCAT_LINES lines. */
    static String logcat() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + android.os.Process.myPid(),
                    "-t", String.valueOf(LOGCAT_LINES)).redirectErrorStream(true).start();
            StringBuilder s = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0 && s.length() < (16 << 20)) s.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return redact(s.toString());
        } catch (IOException | InterruptedException e) {
            return "logcat unavailable: " + e + "\n";
        }
    }

    /** Why the last runs of the process ended (Android 11+), with the ANR / native crash trace when kept. */
    static String exits(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        StringBuilder s = new StringBuilder();
        if (am == null) return s.toString();
        int traces = 0;
        for (ApplicationExitInfo i : am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8)) {
            s.append(new Date(i.getTimestamp())).append(" reason=").append(i.getReason()).append(" status=")
                    .append(i.getStatus()).append(" importance=").append(i.getImportance()).append(" pss_kb=")
                    .append(i.getPss()).append(" rss_kb=").append(i.getRss()).append(' ').append(i.getDescription()).append('\n');
            if (traces < 3 && (i.getReason() == ApplicationExitInfo.REASON_ANR || i.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE)) {
                try (InputStream in = i.getTraceInputStream()) {
                    if (in != null) {
                        ++traces;
                        byte[] buf = new byte[1 << 16];
                        int n = in.read(buf);
                        s.append("--- trace (first 64 KB) ---\n");
                        if (n > 0) s.append(redact(new String(buf, 0, n, StandardCharsets.UTF_8))).append('\n');
                        s.append("--- end of trace ---\n");
                    }
                } catch (IOException ignored) {
                    // no trace kept
                }
            }
        }
        s.append("(reason codes: 1 exit self, 2 signaled, 3 low memory, 4 crash, 5 native crash, 6 ANR, 10 user requested)\n");
        return s.toString();
    }

    static String settings(Context context, File external) {
        StringBuilder s = new StringBuilder();
        File config = external == null ? null : new File(external, "config");
        if (config != null) {
            File[] games = config.listFiles(File::isDirectory);
            if (games != null) for (File g : games) {
                String ini = whole(new File(g, "settings.ini"));
                if (ini != null) s.append("[").append(g.getName()).append("/settings.ini]\n").append(ini).append('\n');
                String controls = whole(new File(g, "controls.json"));
                if (controls != null) s.append("[").append(g.getName()).append("/controls.json]\n").append(controls).append('\n');
            }
        }
        String env = whole(external == null ? null : new File(external, "env.txt"));
        s.append("[env.txt]\n").append(env == null ? "(none)\n" : env).append('\n');
        for (String name : new String[] {MenuOptions.PREFS, "controls"}) {
            s.append("[preferences: ").append(name).append("]\n");
            Map<String, ?> all = new TreeMap<>(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll());
            for (Map.Entry<String, ?> e : all.entrySet()) s.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            s.append('\n');
        }
        s.append("[launch environment (WWHD_*, TU_DEBUG)]\n");
        for (Map.Entry<String, String> e : new TreeMap<>(System.getenv()).entrySet())
            if (e.getKey().startsWith("WWHD_") || e.getKey().startsWith("TU_") || e.getKey().startsWith("MESA_"))
                s.append(e.getKey()).append('=').append(redact(e.getValue())).append('\n');
        return s.toString();
    }

    /** Shader pack, SPIR-V, pipeline caches and GPU drivers: file sizes and counts (the files themselves stay). */
    static String caches(Context context, File external) {
        StringBuilder s = new StringBuilder();
        List<File> roots = new ArrayList<>();
        if (external != null) roots.add(new File(external, "config"));
        roots.add(context.getFilesDir());
        roots.add(context.getCacheDir());
        for (File root : roots) {
            long[] totals = new long[2];
            s.append("[").append(root.getName()).append("]\n");
            list(root, root, s, totals, 0);
            s.append(String.format(Locale.ROOT, "total: %d files, %.1f MB\n\n", totals[0], totals[1] / 1048576.0));
        }
        return s.toString();
    }

    private static void list(File root, File dir, StringBuilder s, long[] totals, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 6) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().equals("crash-share")) list(root, f, s, totals, depth + 1);
                continue;
            }
            ++totals[0];
            totals[1] += f.length();
            String rel = root.toPath().relativize(f.toPath()).toString();
            s.append(String.format(Locale.ROOT, "%12d  %s  %s\n", f.length(),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(f.lastModified())), rel));
        }
    }

    /** Copies the built ZIP to the document the player picked. */
    static void copy(Context context, File zip, android.net.Uri uri) throws IOException {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri);
             InputStream in = new java.io.FileInputStream(zip)) {
            if (out == null) throw new IOException("cannot write the file");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
