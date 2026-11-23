package io.github.belmantegu.ds.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers of the FPS counter, read from the game's own frame log (no native code: rex/src/hooks.cpp
 * writes a [perf] line and a [pace] line to files/native.log every 2 s). Plain Java, tested on the JVM.
 *
 *   [perf] 59.8 fps (avg 16.7 ms, worst 21.0 ms), 1234 frames; game ..., swap ..., renderer ... ms
 *   [perf] 59.7 fps (avg 16.8 ms, worst 17.1 ms; game 16.5, swap 0.0, renderer 0.2 ms; t=36s gpu scene 4.00 / frame 4.41 ms, scale 1 (1920x1080))
 *   [pace] 120 frames: p50 16.6, p95 16.9, p99 18.2 ms; over 16.7: ...; memexport ... ms, GPU 9.4 ms a frame
 *
 * feed() takes any tail of the log and keeps the newest window; the "1% low" is 1000 / p99 (the frame
 * time the slowest 1% of the window took), the graph is the last HISTORY windows' fps.
 */
public final class FpsStats {
    public static final int HISTORY = 30;
    public static final int GOOD = 0, FAIR = 1, POOR = 2;

    private static final Pattern PERF = Pattern.compile("\\[perf\\]\\s+([0-9.]+)\\s+fps\\s+\\(avg\\s+([0-9.]+)\\s+ms,\\s+worst\\s+([0-9.]+)\\s+ms[;)][^\\n]*");
    private static final Pattern GPU = Pattern.compile("gpu scene [0-9.]+ / frame ([0-9.]+) ms");
    private static final Pattern SCALE = Pattern.compile("scale ([0-9.]+)");
    private static final Pattern PACE = Pattern.compile("\\[pace\\][^\\n]*?p99\\s+([0-9.]+)\\s+ms[^\\n]*?GPU\\s+([0-9.]+)\\s+ms");

    /** The newest window. */
    public float fps, avgMs, worstMs, p99Ms, gpuMs, scale;
    /** True once a [perf] line was read. */
    public boolean has;
    /** The last windows' fps, oldest first in the ring starting at `head`. */
    public final float[] history = new float[HISTORY];
    public int count, head;
    private String lastPerf = "";

    /** 1000 / p99, the "1% low" (0 when there is no [pace] line yet). */
    public float low() {
        return p99Ms > 0 ? 1000f / p99Ms : 0f;
    }

    /** Reads the newest [perf] (and the [pace] line after it) of `text`; true when it is a window not seen before. */
    public boolean feed(String text) {
        if (text == null || text.isEmpty()) return false;
        Matcher m = PERF.matcher(text);
        int at = -1;
        String line = null;
        float f = 0, a = 0, w = 0;
        while (m.find()) {
            at = m.end();
            line = m.group();
            f = Float.parseFloat(m.group(1));
            a = Float.parseFloat(m.group(2));
            w = Float.parseFloat(m.group(3));
        }
        if (line == null) return false;
        if (line.equals(lastPerf)) {  // the same window: its [pace] line may have arrived since
            Matcher again = PACE.matcher(text);
            if (again.find(at)) {
                float p99 = Float.parseFloat(again.group(1));
                boolean changed = p99 != p99Ms;
                p99Ms = p99;
                gpuMs = Float.parseFloat(again.group(2));
                return changed;
            }
            return false;
        }
        lastPerf = line;
        fps = f;
        avgMs = a;
        worstMs = w;
        has = true;
        // A game without a [pace] line reports the GPU and the render scale inside the
        // [perf] line ("; t=36s gpu scene 4.0 / frame 4.4 ms, scale 1 (1920x1080)"); a 2 s window holds ~120
        // frames, so its worst frame stands in for the 99th percentile.
        gpuMs = 0;
        Matcher g = GPU.matcher(line);
        if (g.find()) {
            gpuMs = Float.parseFloat(g.group(1));
            p99Ms = w;
        }
        Matcher sc = SCALE.matcher(line);
        scale = sc.find() ? Float.parseFloat(sc.group(1)) : 0;
        Matcher p = PACE.matcher(text);
        if (p.find(at)) {
            p99Ms = Float.parseFloat(p.group(1));
            gpuMs = Float.parseFloat(p.group(2));
        }
        history[(head + count) % HISTORY] = f;
        if (count < HISTORY) count++;
        else head = (head + 1) % HISTORY;
        return true;
    }

    /** The i-th sample, oldest (0) to newest (count - 1). */
    public float at(int i) {
        return history[(head + i) % HISTORY];
    }

    /**
     * The status dot: GOOD within 8% of the frame rate the game is aiming at, FAIR within 25%, else POOR.
     * `limit` is the app's frame limit (0 = auto: 60 in play, 30 in cutscenes, told apart by the reading; any
     * limit of 10 or more, such as 20, 24 or 45, is the target itself).
     */
    public static int level(float fps, int limit) {
        float nominal = limit >= 10 ? limit : (fps > 36f ? 60f : 30f);
        if (fps >= nominal * .92f) return GOOD;
        if (fps >= nominal * .75f) return FAIR;
        return POOR;
    }
}
