package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Statistics screen's numbers (pure Java, JVM-tested), from the frame windows the game logs (FpsStats: one
 * [perf] line, and a [pace] line when the game has one, every ~2 s). Nothing is invented: a session with no window
 * has no frame numbers.
 *
 *   average   the mean of the windows' fps
 *   1% low    the mean of the windows' 1000 / p99 frame time (p99 = the [pace] line's, or the window's worst frame
 *             when the game prints no [pace] line: ~120 frames a window, so the worst is about the 99th percentile)
 *   worst     the slowest window's fps
 *   GPU       the mean GPU frame time of the windows that report one; scale: the last window's render scale
 *   graph     the fps of the last GRAPH windows, oldest first
 *
 * A session is kept as a small JSON object; the history is a JSON list, newest first, at most HISTORY_MAX.
 */
public final class SessionStats {
    public static final int GRAPH = 60, HISTORY_MAX = 20;

    /** One window: fps, avg ms, worst ms, p99 ms, GPU ms, scale. */
    public static float[] window(FpsStats s) {
        return new float[] {s.fps, s.avgMs, s.worstMs, s.p99Ms, s.gpuMs, s.scale};
    }

    public static final class Session {
        public long startMs, seconds;
        public int windows;
        public float avg, low, worst, gpu, scale;
        public float[] graph = new float[0];
        /** How the player said the game ran ("" = not asked). */
        public String rating = "";

        public boolean hasFrames() { return windows > 0; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", (double) startMs);
            m.put("seconds", (double) seconds);
            m.put("windows", (double) windows);
            m.put("avg", r1(avg));
            m.put("low", r1(low));
            m.put("worst", r1(worst));
            m.put("gpu", r2(gpu));
            m.put("scale", r2(scale));
            List<Object> g = new ArrayList<>();
            for (float f : graph) g.add(r1(f));
            m.put("graph", g);
            if (!rating.isEmpty()) m.put("rating", rating);
            return m;
        }

        public static Session fromMap(Map<String, Object> m) {
            Session s = new Session();
            s.startMs = (long) MiniJson.num(m, "start", 0);
            s.seconds = (long) MiniJson.num(m, "seconds", 0);
            s.windows = (int) MiniJson.num(m, "windows", 0);
            s.avg = (float) MiniJson.num(m, "avg", 0);
            s.low = (float) MiniJson.num(m, "low", 0);
            s.worst = (float) MiniJson.num(m, "worst", 0);
            s.gpu = (float) MiniJson.num(m, "gpu", 0);
            s.scale = (float) MiniJson.num(m, "scale", 0);
            s.rating = MiniJson.str(m, "rating", "");
            Object g = m.get("graph");
            if (g instanceof List) {
                List<?> l = (List<?>) g;
                s.graph = new float[l.size()];
                for (int i = 0; i < l.size(); ++i) s.graph[i] = l.get(i) instanceof Number ? ((Number) l.get(i)).floatValue() : 0;
            }
            return s;
        }

        /** One line for the report: "2026-10-22 14:05, 12 min 30 s, 59.7 fps avg, 1% low 57.0, worst 55.1, GPU 4.4 ms, scale 100%". */
        public String line() {
            StringBuilder b = new StringBuilder();
            b.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(startMs)));
            b.append(", ").append(seconds / 60).append(" min ").append(seconds % 60).append(" s");
            if (hasFrames()) {
                b.append(String.format(Locale.US, ", %.1f fps avg, 1%% low %.1f, worst %.1f", avg, low, worst));
                if (gpu > 0) b.append(String.format(Locale.US, ", GPU %.2f ms", gpu));
                if (scale > 0) b.append(String.format(Locale.US, ", scale %.0f%%", scale * 100));
                b.append(", ").append(windows).append(" readings");
            } else {
                b.append(", no frame readings");
            }
            if (!rating.isEmpty()) b.append(", rated ").append(rating);
            return b.toString();
        }
    }

    private SessionStats() {}

    private static double r1(float v) { return Math.round(v * 10.0) / 10.0; }

    private static double r2(float v) { return Math.round(v * 100.0) / 100.0; }

    /** A session from its windows (see the class comment). */
    public static Session summarize(List<float[]> windows, long startMs, long seconds) {
        Session s = new Session();
        s.startMs = startMs;
        s.seconds = Math.max(0, seconds);
        s.windows = windows.size();
        if (windows.isEmpty()) return s;
        float fps = 0, low = 0, gpu = 0, worst = Float.MAX_VALUE;
        int lowN = 0, gpuN = 0;
        for (float[] w : windows) {
            fps += w[0];
            worst = Math.min(worst, w[0]);
            float p99 = w[3] > 0 ? w[3] : w[2];
            if (p99 > 0) {
                low += 1000f / p99;
                lowN++;
            }
            if (w[4] > 0) {
                gpu += w[4];
                gpuN++;
            }
        }
        s.avg = fps / windows.size();
        s.low = lowN > 0 ? low / lowN : 0;
        s.worst = worst;
        s.gpu = gpuN > 0 ? gpu / gpuN : 0;
        s.scale = windows.get(windows.size() - 1)[5];
        int n = Math.min(GRAPH, windows.size());
        s.graph = new float[n];
        for (int i = 0; i < n; ++i) s.graph[i] = windows.get(windows.size() - n + i)[0];
        return s;
    }

    public static List<Session> parseHistory(String json) {
        List<Session> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            Object o = MiniJson.parse(json);
            if (o instanceof List) {
                for (Object e : (List<?>) o) {
                    if (e instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) e;
                        out.add(Session.fromMap(m));
                    }
                }
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();  // a damaged save starts the history over
        }
        return out;
    }

    public static String historyJson(List<Session> sessions) {
        List<Object> l = new ArrayList<>();
        for (Session s : sessions) l.add(s.toMap());
        return MiniJson.write(l);
    }

    /** The history with `s` in front, at most HISTORY_MAX (a session too short to have a reading or a minute is not kept). */
    public static List<Session> push(List<Session> history, Session s) {
        List<Session> out = new ArrayList<>();
        if (s.hasFrames() || s.seconds >= 60) out.add(s);
        for (Session o : history) if (out.size() < HISTORY_MAX) out.add(o);
        return out;
    }

    /** "1 h 05 min" / "12 min 30 s" / "45 s". */
    public static String duration(long seconds) {
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d h %02d min", seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60);
        return seconds + " s";
    }
}
