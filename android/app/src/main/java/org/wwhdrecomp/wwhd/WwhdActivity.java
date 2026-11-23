package org.wwhdrecomp.wwhd;

import android.os.Bundle;
import android.content.Intent;
import android.app.AlertDialog;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.content.ClipData;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;

// The game: SDL loads libmain.so and runs its SDL_main (runtime/src/main.cpp).
public class WwhdActivity extends SDLActivity {
    // ---- The Super Mario 3D World build's own menu (GameMenu: the ds design system, Mario theme) ----
    static native void nativeSetPaused(boolean paused);
    static native boolean nativeSetOption(String name, String value);
    /** The overlay's performance report (build, GPU, driver, last render profile) for the diagnostics ZIP. */
    static native String nativeDiagnosticReport();
    private static final int REQUEST_SAVE_DIAGNOSTICS = 0x5D1A;
    private File diagnosticsZip;  // the last built ZIP (app cache), for ACTION_CREATE_DOCUMENT
    private GameMenu menu;
    private TouchControls touch;
    private java.util.Map<String, String> menuLaunch;
    private boolean sm3dw() { return getPackageName().contains("sm3dw"); }
    public static String downloadCatalogue(String url, String destination, long limit) {
        return HttpsDownload.download(url, destination, limit);
    }

    private static android.os.Vibrator phoneVibrator;

    /**
     * The GamePad's rumble on the phone's own motor (called from the native input update, input_sdl.cpp,
     * on any thread): ms short one-shot with amplitude 1..255, refreshed by the runtime while the game
     * keeps asking; (0, 0) stops it.
     */
    public static void vibrate(int ms, int amplitude) {
        try {
            if (phoneVibrator == null) {
                android.content.Context c = getContext();
                if (c == null) return;
                phoneVibrator = (android.os.Vibrator) c.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            }
            android.os.Vibrator v = phoneVibrator;
            if (v == null || !v.hasVibrator()) return;
            if (ms <= 0 || amplitude <= 0) { v.cancel(); return; }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                int amp = v.hasAmplitudeControl() ? Math.max(1, Math.min(255, amplitude)) : android.os.VibrationEffect.DEFAULT_AMPLITUDE;
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp));
            } else {
                v.vibrate(ms);
            }
        } catch (RuntimeException e) {
            Log.w("wwhd", "vibration failed", e);
        }
    }

    // Public thermal/battery APIs, sampled by the overlay/report via JNI at most once a second.
    public static String performanceThermals() { return PerformanceThermals.read(mSingleton); }


    protected GamePadDisplay gamePadDisplay;
    private MemoryPressure memoryPressure;  // UI thread (lifecycle callbacks)
    private File onDeviceGame;
    private AndroidGame.Selection selection;
    private static AndroidGame.Selection sessionSelection;
    private static File sessionLibrary;

    @Override protected boolean retainNativeSessionOnRecreation() { return true; }

    @Override public void loadLibraries() {
        if (isReusingNativeSession()) {
            // A setup update must not change the loaded engine or assets mid-session.
            selection = sessionSelection;
            onDeviceGame = sessionLibrary;
            return;
        }
        selection = AndroidGame.selected(this);
        onDeviceGame = selection != null ? selection.library : null;
        if (onDeviceGame == null) {
            super.loadLibraries();
        } else {
            System.loadLibrary("SDL3");
            System.load(onDeviceGame.getAbsolutePath());
            Log.i("wwhd-game", "Loaded on-device game " + onDeviceGame.getAbsolutePath());
            if (selection.game != null) Log.i("wwhd-game", "Selected game assets " + selection.game.getAbsolutePath());
        }
        sessionSelection = selection;
        sessionLibrary = onDeviceGame;
    }

    @Override protected String getMainSharedObject() {
        return onDeviceGame != null ? onDeviceGame.getAbsolutePath() : super.getMainSharedObject();
    }

    protected File shaderUserDirectory() { return AndroidUserDirectories.config(getExternalFilesDir(null)); }

    @Override protected String[] getArguments() {
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (selection != null && selection.game != null) {
            args.add("--game"); args.add(selection.game.getAbsolutePath());
            args.add("--shader-prebuild-library"); args.add(selection.library.getAbsolutePath());
        }
        // Keep devel's external config folder; no implicit WWHD_USER_DIR override.
        return args.toArray(new String[0]);
    }

    @Override protected void onResume() {
        super.onResume();
        if (memoryPressure != null) memoryPressure.start();
        // Only start after SDL successfully loaded the native game library.
        if (!mBrokenLibraries) {
            if (gamePadDisplay == null) gamePadDisplay = new GamePadDisplay(this, mLayout, mSurface);
            gamePadDisplay.start();
        }
    }

    /** Render-thread notification; surface replacement always belongs to the UI thread. */
    public void recoverGamePadSurface(long generation) {
        runOnUiThread(() -> {
            if (gamePadDisplay != null) gamePadDisplay.recoverSurface(generation);
        });
    }

    @Override protected void onPause() {
        if (memoryPressure != null) memoryPressure.stop();
        if (gamePadDisplay != null) gamePadDisplay.stop();
        super.onPause();
    }
    // Low memory: the renderer frees what it can make again (MemoryPressure.java; runtime/src/platform/memory_trim.h)
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (memoryPressure != null) memoryPressure.onTrimMemory(level);
    }
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The game files go to Android/data/org.wwhdrecomp.wwhd/files/game (code, content, meta):
        // create the folders so they show up for copying over USB.
        File files = getExternalFilesDir(null);
        if (files != null) {
            new File(files, "game").mkdirs();
            new File(files, "save").mkdirs();
            Log.i("wwhd", "game folder: " + new File(files, "game"));
        }
        // the menu's settings reach the game as WWHD_* variables, read while the native libraries load
        if (sm3dw()) {
            MenuOptions.applyLaunchEnv(this);
            menuLaunch = MenuOptions.launchSnapshot(this);
        }
        super.onCreate(savedInstanceState);
        // low memory: the renderer frees what it can make again (onTrimMemory above, and a free-memory check)
        if (!mBrokenLibraries) memoryPressure = new MemoryPressure(this);
        // heat guard: fan-cooled handhelds have other limits than phones (#247 upstream)
        if (!mBrokenLibraries) {
            try {
                int heat = HeatGuardDevice.publish(android.os.Build.MANUFACTURER, android.os.Build.BRAND, android.os.Build.MODEL);
                Log.i("wwhd", "heat guard device: " + (heat == HeatGuardDevice.FAN_HANDHELD ? "handheld (fan)" : "phone"));
            } catch (UnsatisfiedLinkError e) {
                // an on-device game library from before device classes: the phone limits stay
                Log.w("wwhd", "heat guard device class not passed: " + e.getMessage());
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getPackageName().contains("sm3dw")) {
            // Super Mario 3D World already runs at 60 fps: ask a 120 Hz screen for 60 Hz instead of showing
            // every frame twice (less power and heat; frames are paced to a 59.94 Hz virtual vsync)
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredRefreshRate = 60f;
            getWindow().setAttributes(lp);
        }
        // on-screen controls over the game (the button under the view button shows or hides them)
        // (the Super Mario 3D World test build is played with a controller: no on-screen buttons)
        if (mLayout != null) {
            touch = new TouchControls(this);
            touch.setBlowShown("button".equals(new MenuOptions.Store(this, MenuOptions.defaults(MenuOptions.tabs())).get("mic")));
            mLayout.addView(touch, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (mLayout != null && sm3dw()) createMenu();
        // Blow set to the microphone without the permission (a new install, or taken back in the system settings):
        // ask now; refused, the option goes back to off (onRequestPermissionsResult)
        if ("mic".equals(new MenuOptions.Store(this, MenuOptions.defaults(MenuOptions.tabs())).get("mic")) && !MenuOptions.micAllowed(this))
            applyMic("mic");
        if (files != null && savedInstanceState == null) offerCrashLog(files);
    }

    private void createMenu() {
        M3dwGame.installDesign();
        io.github.belmantegu.ds.android.HomeUi ui = new io.github.belmantegu.ds.android.HomeUi(this);
        menu = new GameMenu(ui, menuLaunch, new GameMenu.Host() {
            @Override public void onContinue() { closeMenu(); }
            @Override public void onExit() { endApp(); }
            @Override public void onRestartGame() { restartApp(); }
            @Override public void onTouchToggle() { if (touch != null) touch.setShown(!touchShown()); }
            @Override public void onEditor() {
                if (touch == null) return;
                menu.close();  // the game stays paused while the buttons are moved
                touch.startEditing(() -> { try { nativeSetPaused(false); } catch (UnsatisfiedLinkError ignored) {} });
            }
            @Override public boolean touchShown() {
                return getSharedPreferences("controls", MODE_PRIVATE).getBoolean("touch_controls", true);
            }
            @Override public void onLiveChanged(String key) { pushLive(key); }
            @Override public void onDiagnostics() { exportDiagnostics(); }
            @Override public void onReimport() { confirmReimport(); }
            @Override public void onDriverAction(String key) { driverAction(key); }
            @Override public void say(String message) { android.widget.Toast.makeText(WwhdActivity.this, message, android.widget.Toast.LENGTH_SHORT).show(); }
        });
        mLayout.addView(menu, new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the pause button, top right (BACK and a pad's Guide button open the menu too)
        io.github.belmantegu.ds.android.IconButton pauseButton = new io.github.belmantegu.ds.android.IconButton(
                io.github.belmantegu.ds.android.DsKit.of(ui), "pause", getString(R.string.menu_paused));
        pauseButton.setOnClickListener(v -> openMenu());
        int size = ui.touch(5.2f);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(size, size);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.topMargin = ui.px(2.5f);
        lp.rightMargin = ui.px(2.5f);
        mLayout.addView(pauseButton, lp);
    }

    private void openMenu() {
        if (menu == null || menu.isOpen()) return;
        menu.open();
        menu.bringToFront();
        try { nativeSetPaused(true); } catch (UnsatisfiedLinkError ignored) {}
    }

    private void closeMenu() {
        if (menu == null) return;
        menu.close();
        try { nativeSetPaused(false); } catch (UnsatisfiedLinkError ignored) {}
    }

    /** A menu setting changed: the live ones go to the game now (the language waits for a restart). */
    private void pushLive(String key) {
        MenuOptions.Store s = new MenuOptions.Store(this, MenuOptions.defaults(MenuOptions.tabs()));
        if (key.equals("touch")) { if (touch != null) touch.setShown("1".equals(s.get("touch"))); return; }
        if (key.startsWith("touch_")) { if (touch != null) touch.loadLayout(); return; }
        if (key.equals("mic") || key.equals("reset")) applyMic(s.get("mic"));
        String[] all = {"res", "shadows", "rt_mips", "aniso", "fxaa", "frameskip", "rate", "autores", "drc", "filter", "present", "fps", "volume", "rumble", "gyro", "tvtouch", "mic_sens"};
        for (String k : all)
            if (key.equals(k) || key.equals(MenuOptions.QUALITY) || key.equals("reset")) {
                try { nativeSetOption(k, s.get(k)); } catch (UnsatisfiedLinkError ignored) {}
            }
    }

    // ---- Options > Controls > Blow (the GamePad microphone, docs/mic.md, issue #19) ----

    private static final int MIC_PERMISSION_REQUEST = 4974;

    /**
     * The blow option chosen in the menu: "mic" needs RECORD_AUDIO, asked for only now (never at the first start);
     * refused, the option goes back to off and the game keeps running without a microphone.
     */
    private void applyMic(String mode) {
        if ("mic".equals(mode) && !MenuOptions.micAllowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            return;  // onRequestPermissionsResult passes it on
        }
        if (touch != null) touch.setBlowShown("button".equals(mode));
        try { nativeSetOption("mic", mode); } catch (UnsatisfiedLinkError ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != MIC_PERMISSION_REQUEST) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); return; }
        final boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            new MenuOptions.Store(this, MenuOptions.defaults(MenuOptions.tabs())).set("mic", "off");
            android.widget.Toast.makeText(this, R.string.mic_denied, android.widget.Toast.LENGTH_LONG).show();
            if (menu != null) menu.refreshOptions();
        }
        applyMic(granted ? "mic" : "off");
    }

    // ---- Options > Driver: Turnip and other packages (DriverStore; the runtime loads the chosen one at the next start) ----

    private static final int DRIVER_ZIP_REQUEST = 4973;

    private void driverAction(String key) {
        DriverStore store = new DriverStore(this);
        switch (key) {
            case "driver_download":
                DriverDownload.start(this, d -> { if (menu != null) menu.refreshOptions(); });
                break;
            case "driver_import":
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"), DRIVER_ZIP_REQUEST);
                } catch (android.content.ActivityNotFoundException e) { Log.w("wwhd", "no document picker", e); }
                break;
            case "driver_delete": {
                final java.util.List<DriverStore.Driver> list = store.list();
                if (list.isEmpty()) return;
                String[] names = new String[list.size()];
                for (int i = 0; i < names.length; ++i) names[i] = list.get(i).label();
                io.github.belmantegu.ds.android.DsDialog.choose(this, getString(R.string.opt_driver_delete), names, -1, i -> {
                    DriverStore.Driver d = list.get(i);
                    if (menuLaunch != null && d.id.equals(menuLaunch.get("driver"))) {  // the game runs on it now
                        io.github.belmantegu.ds.android.DsDialog.alert(this, getString(R.string.opt_driver_delete),
                                getString(R.string.driver_delete_running, d.label()), null);
                        return;
                    }
                    io.github.belmantegu.ds.android.DsDialog.confirmDestructive(this, null, getString(R.string.confirm_driver_delete, d.label()),
                            getString(R.string.ds_cap_delete), () -> {
                                try { store.delete(d.id); }
                                catch (java.io.IOException e) { Log.w("wwhd", "driver delete", e); }
                                if (menu != null) menu.refreshOptions();
                            });
                });
                break;
            }
            default: break;
        }
    }

    private void importDriverZip(Uri uri) {
        final io.github.belmantegu.ds.android.DsDialog.Handle wait =
                io.github.belmantegu.ds.android.DsDialog.progress(this, getString(R.string.opt_driver_import), getString(R.string.busy_driver));
        wait.setProgress(-1);
        new Thread(() -> {
            String error = null;
            DriverStore.Driver added = null;
            try {
                DriverStore store = new DriverStore(this);
                added = store.importZip(uri);
                store.select(added.id);
            } catch (java.io.IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            final String problem = error;
            final DriverStore.Driver d = added;
            runOnUiThread(() -> {
                wait.dismiss();
                if (menu != null) menu.refreshOptions();
                if (problem != null) io.github.belmantegu.ds.android.DsDialog.alert(this, getString(R.string.catalog_failed), problem, null);
                else io.github.belmantegu.ds.android.DsDialog.alert(this, getString(R.string.catalog_installed),
                        getString(R.string.say_driver_imported, d.label()), null);
            });
        }, "driver-import").start();
    }

    /** Options > Idioma e sobre > Reimportar jogo: the import screen (GameSetupActivity, its own process) replaces the game. */
    private void confirmReimport() {
        io.github.belmantegu.ds.android.DsDialog.confirm(this, getString(R.string.reimport_title), getString(R.string.reimport_text),
                getString(R.string.opt_reimport), () -> {
                    startActivity(new Intent(this, GameSetupActivity.class).putExtra(GameSetupActivity.EXTRA_REIMPORT, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    endApp();
                });
    }

    private void restartApp() {
        Intent again = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (again != null) {
            again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(again);
        }
        endApp();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (menu != null) {
            int k = e.getKeyCode();
            if (menu.isOpen()) {
                if (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(e);
                if (k == android.view.KeyEvent.KEYCODE_BACK || k == android.view.KeyEvent.KEYCODE_BUTTON_MODE) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP) menu.back();
                    return true;
                }
                return menu.onKey(e);
            }
            boolean pad = (e.getSource() & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD;
            if (k == android.view.KeyEvent.KEYCODE_BUTTON_MODE || (k == android.view.KeyEvent.KEYCODE_BACK && !pad)) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) openMenu();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (menu != null && menu.isOpen() && menu.onMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    /** Menu: About > Export diagnostics (Diagnostics.java): built off the UI thread, then shared or saved. */
    private void exportDiagnostics() {
        android.widget.Toast.makeText(this, R.string.diag_building, android.widget.Toast.LENGTH_SHORT).show();
        String report;
        try { report = nativeDiagnosticReport(); }
        catch (UnsatisfiedLinkError | RuntimeException e) { report = "(no report from the game: " + e + ")\n"; }
        final String performanceReport = report;
        new Thread(() -> {
            try {
                File zip = Diagnostics.build(this, new File(getCacheDir(), "crash-share"), performanceReport);
                runOnUiThread(() -> offerDiagnostics(zip));
            } catch (Exception e) {
                Log.w("wwhd", "diagnostics", e);
                runOnUiThread(() -> android.widget.Toast.makeText(this, getString(R.string.diag_failed, String.valueOf(e.getMessage())),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }, "diagnostics").start();
    }

    private void offerDiagnostics(File zip) {
        diagnosticsZip = zip;
        String size = android.text.format.Formatter.formatShortFileSize(this, zip.length());
        new AlertDialog.Builder(this).setTitle(R.string.diag_title)
            .setMessage(getString(R.string.diag_message, zip.getName(), size))
            .setNeutralButton(R.string.diag_save, (dialog, which) -> {
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, zip.getName());
                try { startActivityForResult(save, REQUEST_SAVE_DIAGNOSTICS); }
                catch (android.content.ActivityNotFoundException e) { Log.w("wwhd", "no document picker", e); }
            })
            .setPositiveButton(R.string.diag_share, (dialog, which) -> {
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".crashlogs", zip);
                    Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, zip.getName())
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(ClipData.newRawUri(zip.getName(), uri));
                    startActivity(Intent.createChooser(share, getString(R.string.diag_share)));
                } catch (Exception e) { Log.w("wwhd", "Cannot share diagnostics", e); }
            }).show();
    }

    /** onActivityResult's part for the diagnostics document; true when the result was that one. */
    private boolean diagnosticsSaved(int request, int result, Intent data) {
        if (request != REQUEST_SAVE_DIAGNOSTICS) return false;
        if (result != RESULT_OK || data == null || data.getData() == null) return true;
        final File zip = diagnosticsZip;
        final Uri target = data.getData();
        if (zip == null || !zip.isFile()) return true;
        new Thread(() -> {
            try {
                Diagnostics.copy(this, zip, target);
                runOnUiThread(() -> android.widget.Toast.makeText(this, R.string.diag_saved, android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.w("wwhd", "Cannot save diagnostics", e);
                runOnUiThread(() -> android.widget.Toast.makeText(this, getString(R.string.diag_failed, String.valueOf(e.getMessage())),
                        android.widget.Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-save").start();
        return true;
    }

    private void offerCrashLog(File files) {
        File[] reports = new File(files, "captures").listFiles((dir, name) -> name.startsWith("crash-") && name.endsWith(".log"));
        if (reports == null) return;
        File latest = null;
        for (File report : reports)
            if (report.isFile() && (latest == null || report.lastModified() > latest.lastModified())) latest = report;
        if (latest == null) return;
        final File report = latest;
        final String identity = report.getName() + ":" + report.lastModified();
        if (identity.equals(getPreferences(MODE_PRIVATE).getString("offeredCrash", ""))) return;
        getPreferences(MODE_PRIVATE).edit().putString("offeredCrash", identity).apply();
        new AlertDialog.Builder(this).setTitle("Crash report available")
            .setMessage("Share the latest crash report? It contains diagnostic settings and logs.")
            .setNegativeButton("Later", null).setPositiveButton("Share", (dialog, which) -> {
                try {
                    File folder = new File(getCacheDir(), "crash-share");
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cannot create share folder");
                    File copy = new File(folder, "crash.log");
                    Files.copy(report.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".crashlogs", copy);
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(ClipData.newRawUri("Crash report", uri));
                    startActivity(Intent.createChooser(share, "Share crash report"));
                } catch (Exception e) { Log.w("wwhd", "Cannot share crash report", e); }
            }).show();
    }

    private static final int GPU_DRIVER_REQUEST = 4972;
    private static native String installGpuDriver(String path, boolean select);
    // afterFailure: the renderer could not start with the system driver (renderer.cpp), so the
    // installed driver is selected and the app ends; it runs from the next start
    private boolean driverAfterFailure;
    public void chooseGpuDriver(boolean afterFailure) {
        driverAfterFailure = afterFailure;
        runOnUiThread(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), GPU_DRIVER_REQUEST));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (diagnosticsSaved(request, result, data)) return;
        if (request == DRIVER_ZIP_REQUEST) {
            if (result == RESULT_OK && data != null && data.getData() != null) importDriverZip(data.getData());
            return;
        }
        if (request != GPU_DRIVER_REQUEST) return;
        final boolean afterFailure = driverAfterFailure;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (afterFailure) endApp();
            return;
        }
        final android.net.Uri uri = data.getData();
        // Copy the content URI into internal storage; no broad storage permissions needed.
        new Thread(() -> {
            File zip = null;
            String error = "";
            try {
                zip = File.createTempFile("gpu-driver-", ".zip", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(zip)) {
                    if (in == null) throw new java.io.IOException("Cannot read selected file");
                    byte[] bytes = new byte[65536]; long total = 0; int count;
                    while ((count = in.read(bytes)) != -1) {
                        total += count;
                        if (total > 512L * 1024 * 1024) throw new java.io.IOException("Driver ZIP exceeds 512 MiB");
                        out.write(bytes, 0, count);
                    }
                }
                error = installGpuDriver(zip.getAbsolutePath(), afterFailure);
            } catch (Exception e) { error = e.getMessage(); }
            finally { if (zip != null) zip.delete(); }
            final String problem = error;
            final boolean ok = problem == null || problem.isEmpty();
            runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(ok ? "Driver installed" : "Driver installation failed")
                .setMessage(!ok ? problem : afterFailure ? "The game uses it from the next start: open the game again."
                                                         : "Select the driver in Graphics, then restart the game.")
                .setCancelable(!afterFailure)
                .setPositiveButton("OK", afterFailure ? (dialog, which) -> endApp() : null).show());
        }, "gpu-driver-install").start();
    }

    private void endApp() {
        finishAndRemoveTask();
        System.exit(0);
    }

    // Frame interpolation at 120/240 fps (runtime/src/platform/display_rate.cpp, called from native
    // code on any thread). Phones with 90/120 Hz screens often run apps at 60 Hz unless the app asks
    // for more, so the game asks for a display mode of the same resolution with at least the
    // frame rate it draws, and reads back the rate the display actually runs at.

    // the display's current refresh rate (Hz; 0: no display)
    public static float displayRefreshRate() {
        Display d = mSingleton != null ? mSingleton.getDisplay() : null;
        return d != null ? d.getRefreshRate() : 0;
    }

    // the highest refresh rate the display offers at its current resolution
    public static float maxRefreshRate() {
        Display d = mSingleton != null ? mSingleton.getDisplay() : null;
        if (d == null) return 0;
        Display.Mode cur = d.getMode();
        float best = d.getRefreshRate();
        for (Display.Mode m : d.getSupportedModes())
            if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight())
                best = Math.max(best, m.getRefreshRate());
        return best;
    }

    // ask for the display mode (same resolution) with the lowest refresh rate of at least hz, or the
    // highest one if none reaches it; hz <= 0 leaves the choice to the system again
    public static void requestRefreshRate(final float hz) {
        final SDLActivity a = mSingleton;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Window w = a.getWindow();
                Display d = a.getDisplay();
                if (w == null || d == null) return;
                int id = 0;
                if (hz > 0) {
                    Display.Mode cur = d.getMode(), pick = null, highest = null;
                    for (Display.Mode m : d.getSupportedModes()) {
                        if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                        if (highest == null || m.getRefreshRate() > highest.getRefreshRate()) highest = m;
                        if (m.getRefreshRate() >= hz - 1 && (pick == null || m.getRefreshRate() < pick.getRefreshRate())) pick = m;
                    }
                    if (pick == null) pick = highest;
                    if (pick != null) id = pick.getModeId();
                }
                WindowManager.LayoutParams lp = w.getAttributes();
                if (lp.preferredDisplayModeId == id) return;
                lp.preferredDisplayModeId = id;
                // Super Mario 3D World asks 60 Hz at start (onCreate); 120 fps needs the faster mode to win
                if (a.getPackageName().contains("sm3dw")) lp.preferredRefreshRate = hz > 0 ? hz : 60f;
                w.setAttributes(lp);
                Log.i("wwhd", "display mode " + id + " requested for " + hz + " fps");
            }
        });
    }
}
