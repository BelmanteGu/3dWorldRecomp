package org.wwhdrecomp.wwhd;

import static io.github.belmantegu.ds.core.OptionSpec.c;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.belmantegu.ds.core.OptionSpec;
import io.github.belmantegu.ds.core.OptionsEngine;
import io.github.belmantegu.ds.core.OptionsEnv;
import io.github.belmantegu.ds.core.TabSpec;
import io.github.belmantegu.ds.core.ValueStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Super Mario 3D World menu's options (the ds design system's OptionsEngine, as in the Diablo III port):
 * only what works in this game. The values live in the app's preferences ("menu"); they reach the game at
 * start as WWHD_* variables (applyLaunchEnv, before the native libraries load) and while it runs through
 * WwhdActivity.nativeSetOption (live). The language is the only restart-only row.
 */
final class MenuOptions {
    private MenuOptions() {}

    static final String PREFS = "menu", QUALITY = "quality";
    /** The rows a quality profile sets, and each profile's values in that order. */
    static final String[] PROFILE_ROWS = {"res", "shadows", "rt_mips", "aniso", "fxaa"};
    static final String[] PROFILES = {"potato", "balanced", "quality"};
    static final String[][] PROFILE_VALUES = {
        {"0.5", "0", "0", "0", "0"},
        {"0.75", "1", "0", "0", "0"},
        {"1", "1", "1", "0", "0"},
    };
    /** Wii U console language codes (cafe.language) of the European disc's languages. */
    static final String[][] LANGUAGES = {{"pt", "9", "Português"}, {"en", "1", "English"}, {"es", "5", "Español"},
                                         {"fr", "2", "Français"}, {"de", "3", "Deutsch"}, {"it", "4", "Italiano"}};

    /**
     * Build-variant defaults of the frame-rate rows (android/build_sm3dw_apk.sh: -PwwhdDefaultFps,
     * -PwwhdDefaultAutoRes, as the resources variant_rate / variant_autores): the Galaxy S23 build starts at 120 fps
     * with the automatic resolution keeping 120, the generic build at 60 with it off (and the automatic drop to 30 on, as
     * every build). Read once, before tabs().
     */
    static String variantRate = "60", variantAutoRes = "off";
    private static boolean variantRead;
    static void variant(Context c) {
        if (variantRead) return;
        variantRead = true;
        try {
            String r = c.getString(R.string.variant_rate), a = c.getString(R.string.variant_autores);
            if (r.equals("30") || r.equals("60") || r.equals("120")) variantRate = r;
            if (a.equals("off") || a.equals("30") || a.equals("60") || a.equals("120")) variantAutoRes = a;
        } catch (RuntimeException ignored) {}
    }

    static List<TabSpec> tabs() {
        List<TabSpec> tabs = new ArrayList<>();
        tabs.add(new TabSpec("video", "monitor",
                OptionSpec.choice(QUALITY, c("potato", "@q_potato"), c("balanced", "@q_balanced"), c("quality", "@q_quality"),
                        c("custom", "@custom")).def("quality")
                        .cards(new OptionSpec.Card("leaf", ""), new OptionSpec.Card("gauge", ""), new OptionSpec.Card("sparkles", "")),
                OptionSpec.choice("res", c("0.25", "0,25×"), c("0.5", "0,5×"), c("0.75", "0,75×"), c("1", "1×"), c("1.5", "1,5×"), c("2", "2×")).def("1"),
                // the logic stays at 60 steps a second at every rate: 30 draws and presents every other frame
                // (docs/30fps.md), 120 adds interpolated in-between pictures (CPU cost)
                OptionSpec.choice("rate", c("30", "@rate_30"), c("60", "60"), c("120", "120")).def(variantRate),
                // at 60 and 120: drop to 30 (every other frame) only while the game cannot keep 60 (docs/frameskip.md);
                // the old "always" is the rate 30 now (Store migrates it)
                OptionSpec.choice("frameskip", c("off", "@fs_off"), c("auto", "@fs_auto")).def("auto"),
                OptionSpec.choice("autores", c("off", "@ar_off"), c("30", "@ar_30"), c("60", "@ar_60"), c("120", "@ar_120")).def(variantAutoRes),
                OptionSpec.bool("shadows"),
                OptionSpec.bool("rt_mips"),
                OptionSpec.bool("aniso").def("0"),
                OptionSpec.bool("fxaa").def("0"),
                OptionSpec.action("reset", "rotate-ccw", "restore")));
        tabs.add(new TabSpec("screen", "layout-grid",
                OptionSpec.choice("drc", c("off", "@drc_off"), c("pip", "@drc_pip"), c("auto", "@drc_auto")).def("off"),
                OptionSpec.choice("filter", c("smooth", "@f_smooth"), c("sharp", "@f_sharp"), c("integer", "@f_integer")).def("sharp"),
                OptionSpec.choice("present", c("fifo", "@p_vsync"), c("mailbox", "@p_mailbox")).def("fifo"),
                OptionSpec.bool("fps"),
                OptionSpec.action("reset", "rotate-ccw", "restore")));
        tabs.add(new TabSpec("audio", "activity",
                OptionSpec.range("volume", 0, 100, 5, "%").def("100")));
        tabs.add(new TabSpec("controls", "gamepad-2",
                OptionSpec.bool("touch"),
                OptionSpec.bool("tvtouch").def("1"),
                OptionSpec.bool("rumble").def("1"),
                OptionSpec.bool("gyro").def("0"),
                // the GamePad microphone (docs/mic.md): on by default, RECORD_AUDIO asked for at the first game start (refused: off)
                OptionSpec.choice("mic", c("off", "@mic_off"), c("mic", "@mic_mic"), c("button", "@mic_button")).def("mic"),
                OptionSpec.range("mic_sens", 25, 400, 25, "%").def("100"),
                OptionSpec.action("editor", "layout-grid", "edit"),
                OptionSpec.range("touch_size", 60, 150, 5, "%").def("100"),
                OptionSpec.range("touch_opacity", 20, 100, 5, "%").def("85")));
        // GPU drivers (Turnip): the runtime loads the chosen one at the next start (DriverStore, android_driver.cpp)
        tabs.add(new TabSpec("driver", "cpu",
                OptionSpec.choice("driver").dynamic().restart().select().icon("cpu"),
                OptionSpec.action("driver_download", "download", "download"),
                OptionSpec.action("driver_import", "upload", "import"),
                OptionSpec.action("driver_delete", "trash-2", "delete"),
                OptionSpec.info("gpu")));
        OptionSpec.Choice[] langs = new OptionSpec.Choice[LANGUAGES.length];
        for (int i = 0; i < langs.length; ++i) langs[i] = c(LANGUAGES[i][0], LANGUAGES[i][2]);
        tabs.add(new TabSpec("about", "info",
                OptionSpec.choice("lang", langs).def("pt").restart().select().icon("languages"),
                OptionSpec.action("reimport", "folder-open", "import"),
                OptionSpec.action("diag", "archive", "save"),
                OptionSpec.info("version")));
        return tabs;
    }

    /** Spec defaults by key (a missing preference reads as its default). */
    static Map<String, String> defaults(List<TabSpec> tabs) {
        Map<String, String> out = new HashMap<>();
        for (TabSpec t : tabs) for (OptionSpec r : t.rows) if (!r.def.isEmpty()) out.put(r.key, r.def);
        return out;
    }

    /** The preferences behind the rows; "touch" is the touch overlay's own switch (TouchControls). */
    static final class Store implements ValueStore {
        final SharedPreferences prefs, controls;
        final Map<String, String> defaults;
        final DriverStore drivers;
        Store(Context c, Map<String, String> defaults) {
            drivers = new DriverStore(c);
            prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            controls = c.getSharedPreferences("controls", Context.MODE_PRIVATE);
            this.defaults = defaults;
            // frame skip "always" (before the rate 30 existed) is the rate 30 now, with the automatic drop kept
            if ("on".equals(prefs.getString("frameskip", null)))
                prefs.edit().putString("rate", "30").putString("frameskip", "auto").apply();
        }
        String raw(String key) {
            if (key.equals("touch")) return controls.getBoolean("touch_controls", true) ? "1" : "0";
            if (key.equals("driver")) return drivers.selected();
            String d = defaults.get(key);
            return prefs.getString(key, d == null ? "" : d);
        }
        @Override public String get(String key) {
            if (!key.equals(QUALITY)) return raw(key);
            for (int p = 0; p < PROFILES.length; ++p) {
                boolean match = true;
                for (int i = 0; i < PROFILE_ROWS.length && match; ++i) match = PROFILE_VALUES[p][i].equals(raw(PROFILE_ROWS[i]));
                if (match) return PROFILES[p];
            }
            return "custom";
        }
        @Override public void set(String key, String value) {
            if (key.equals(QUALITY)) {
                for (int p = 0; p < PROFILES.length; ++p)
                    if (PROFILES[p].equals(value)) {
                        SharedPreferences.Editor e = prefs.edit();
                        for (int i = 0; i < PROFILE_ROWS.length; ++i) e.putString(PROFILE_ROWS[i], PROFILE_VALUES[p][i]);
                        e.apply();
                    }
                return;  // "custom" is only ever a reading
            }
            if (key.equals("touch")) { controls.edit().putBoolean("touch_controls", "1".equals(value)).apply(); return; }
            if (key.equals("driver")) {
                try { drivers.select(value); } catch (java.io.IOException e) { android.util.Log.w("wwhd", "driver not chosen", e); }
                return;
            }
            prefs.edit().putString(key, value).apply();
        }
        @Override public boolean resetTab(String tabKey) { return false; }  // every row back to its spec default
    }

    static OptionsEngine model(Context c, Map<String, String> launchValues) {
        variant(c);
        List<TabSpec> tabs = tabs();
        Store store = new Store(c, defaults(tabs));
        return new OptionsEngine(tabs, store, new OptionsEnv() {
            @Override public boolean flag(String name) { return "game".equals(name); }
            @Override public String external(String key) {
                if ("version".equals(key)) return versionName(c);
                if ("gpu".equals(key)) {
                    String gpu = DriverStore.gpuModel();
                    return gpu.isEmpty() ? "?" : gpu;
                }
                return null;
            }
            @Override public boolean restartPending(String key) {
                String at = launchValues.get(key);
                return at != null && !at.equals(store.get(key));
            }
            @Override public List<OptionSpec.Choice> choices(String key) {
                if (!"driver".equals(key)) return Collections.emptyList();
                List<OptionSpec.Choice> out = new ArrayList<>();
                out.add(c("", "@driver_system"));
                for (DriverStore.Driver d : store.drivers.list()) out.add(c(d.id, d.label()));
                return out;
            }
            @Override public String disabledReason(String key) {
                if (!key.startsWith("driver")) return null;
                if (!store.drivers.supported()) return "why_no_driver_support";
                if ((key.equals("driver") || key.equals("driver_delete")) && store.drivers.list().isEmpty()) return "why_no_driver_imported";
                return null;
            }
            @Override public String cardSub(String key, int index) {
                return QUALITY.equals(key) && index < PROFILES.length ? "@q_" + PROFILES[index] + "_sub" : "";
            }
        });
    }

    /** The restart-only rows as the game started with them (restartPending compares with these). */
    static Map<String, String> launchSnapshot(Context c) {
        variant(c);
        Store s = new Store(c, defaults(tabs()));
        Map<String, String> out = new HashMap<>();
        out.put("lang", s.get("lang"));
        out.put("driver", s.get("driver"));
        return out;
    }

    static boolean restartNeeded(Context c, Map<String, String> launchValues) {
        variant(c);
        Store s = new Store(c, defaults(tabs()));
        for (Map.Entry<String, String> e : launchValues.entrySet()) if (!e.getValue().equals(s.get(e.getKey()))) return true;
        return false;
    }

    /** Before the native libraries load: the start values as the WWHD_* variables the game reads. */
    static void applyLaunchEnv(Context c) {
        variant(c);
        Store s = new Store(c, defaults(tabs()));
        try {
            set("WWHD_RES_SCALE", s.get("res"));
            set("WWHD_VK_SKIP_SHADOWS", "1".equals(s.get("shadows")) ? "0" : "1");
            if ("1".equals(s.get("rt_mips"))) android.system.Os.unsetenv("WWHD_NO_RT_MIPS"); else set("WWHD_NO_RT_MIPS", "1");
            set("WWHD_ANISO", s.get("aniso"));
            set("WWHD_FXAA", s.get("fxaa"));
            set("WWHD_FRAMESKIP", "auto".equals(s.get("frameskip")) ? "1" : "0");
            set("WWHD_FPS", "120".equals(s.get("rate")) ? "120" : "30".equals(s.get("rate")) ? "30" : "60");
            set("WWHD_AUTO_RES", "off".equals(s.get("autores")) ? "0" : s.get("autores"));
            set("WWHD_DRC_MODE", s.get("drc"));
            set("WWHD_SCALE_FILTER", s.get("filter"));
            set("WWHD_VK_PRESENT_MODE", s.get("present"));
            set("WWHD_PERF_OVERLAY", s.get("fps"));
            // the runtime's log file (captures/wwhd.log, the previous run's as wwhd-previous.log) for the diagnostics
            // export; env.txt or an explicit WWHD_LOG_FILE (e.g. 0) wins
            if (System.getenv("WWHD_LOG_FILE") == null) set("WWHD_LOG_FILE", "1");
            set("WWHD_VIEW_BUTTON", "0");  // no screen button over the game: the GamePad screen is a setting
            set("WWHD_RUMBLE", s.get("rumble"));
            set("WWHD_PHONE_GYRO", s.get("gyro"));  // applied by the runtime once the saved options are read
            // the microphone only with the permission (it can be taken back in the system settings: off then)
            String mic = s.get("mic");
            // the choice stays "mic": WwhdActivity asks for the permission again once the game is up
            if ("mic".equals(mic) && !micAllowed(c)) mic = "off";
            set("WWHD_MIC", mic);
            set("WWHD_MIC_SENS", s.get("mic_sens"));
            set("WWHD_AUDIO_VOLUME", String.format(Locale.ROOT, "%.2f", parse(s.get("volume"), 100) / 100f));
            for (String[] l : LANGUAGES) if (l[0].equals(s.get("lang"))) set("WWHD_LANGUAGE", l[1]);
        } catch (android.system.ErrnoException e) {
            android.util.Log.w("wwhd", "menu settings not passed to the game", e);
        }
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static void set(String name, String value) throws android.system.ErrnoException {
        android.system.Os.setenv(name, value, true);
    }

    static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
