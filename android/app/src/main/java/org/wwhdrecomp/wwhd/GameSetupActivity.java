package org.wwhdrecomp.wwhd;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import io.github.belmantegu.ds.android.DsDialog;
import io.github.belmantegu.ds.android.DsKit;
import io.github.belmantegu.ds.android.HomeScreen;
import io.github.belmantegu.ds.android.HomeUi;
import io.github.belmantegu.ds.android.Notice;
import io.github.belmantegu.ds.core.DsMetrics;
import io.github.belmantegu.ds.core.HomeSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app's icon (launcher activity): the home in the ds design system (Mario theme), the Diablo III port's
 * split layout (LauncherActivity there): the game's logo on the left with the subtitle and the state card, the
 * menu in a column on the right, the player's key art behind (assets/launcher, local and git-ignored) under the
 * shade. "Jogar" starts the game (WwhdActivity); "Saves" exports or imports the save (ExportSaveActivity,
 * ImportSaveActivity); "Importar jogo" picks the player's dump
 * with the system file picker, a .wux/.wud plus the keys.txt (Cemu's key list), or a Cemu .wua; the import runs
 * in GameImportService (progress here and in a notification; it goes on in the background) and the game starts
 * when it is done. Runs in the ":import" process, apart from the game's.
 */
public class GameSetupActivity extends Activity {
    /** Set by the in-game menu (Options, Idioma e sobre, Reimportar jogo): show this screen even with a game. */
    static final String EXTRA_REIMPORT = "reimport";
    private static final int PICK_IMAGE = 1, PICK_KEYS = 2;
    private static final String[] ITEMS = {"play", "import", "saves", "help", "quit"};

    private HomeUi ui;
    private HomeScreen screen;
    private LauncherBackground background;
    private Notice toast;
    private Uri image;
    private String imageName = "";
    private int lastStick;
    private boolean started;  // the game was started: nothing more from here
    private final GameImportService.Listener listener = this::render;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getPackageName().contains("sm3dw")) {
            startGame();
            return;
        }
        if (portuguese()) return;  // recreated in Portuguese
        android.content.SharedPreferences pick = getSharedPreferences("import_pick", MODE_PRIVATE);
        String saved = savedInstanceState != null ? savedInstanceState.getString("image") : pick.getString("image", null);
        if (saved != null) {
            image = Uri.parse(saved);
            imageName = savedInstanceState != null ? savedInstanceState.getString("imageName", "") : pick.getString("imageName", "");
        }
        M3dwGame.installDesign();
        ui = new HomeUi(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.theme.bg);
        background = new LauncherBackground(this, ui);
        background.addTo(root, true);  // the key art and its shade fill the whole window
        FrameLayout stage = ui.stage(root);
        HomeSpec spec = new HomeSpec();
        spec.title = getString(R.string.menu_brand);
        spec.logoAsset = "launcher/logo.png";
        spec.menuRight = true;
        spec.subtitle = getString(R.string.first_subtitle);
        spec.version = getString(R.string.version_line, MenuOptions.versionName(this));
        spec.hintSelect = getString(R.string.ds_hint_select);
        spec.hintBack = getString(R.string.ds_hint_back);
        spec.details = getString(R.string.ds_details);
        spec.item("play", "play", getString(R.string.first_play), "")
                .item("import", "folder-open", getString(R.string.first_import), "")
                .item("saves", "archive", getString(R.string.home_saves), "")
                .item("help", "info", getString(R.string.first_help), "")
                .item("quit", "log-out", getString(R.string.menu_exit), "");
        screen = new HomeScreen(ui, stage, spec, new HomeScreen.Listener() {
            @Override public void onItem(int index) { activate(index); }
            @Override public void onDetails() { showHelp(); }
            @Override public void onPrimary() { primary(); }
        });
        toast = new Notice(DsKit.of(ui), ui, Notice.Variant.INFO).compact();
        toast.setAlpha(0);
        FrameLayout.LayoutParams toastLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        toastLp.bottomMargin = ui.gap.bottom + ui.px(DsMetrics.MARGIN);
        root.addView(toast, toastLp);
        setContentView(root);
        WindowInsetsController c = root.getWindowInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        screen.select(M3dwGame.ready(this) ? 0 : 1);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 0);  // the import's progress
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (image != null) out.putString("image", image.toString());
        out.putString("imageName", imageName);
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen != null) GameImportService.listen(listener);
        if (background != null) background.resume();
    }

    @Override protected void onPause() {
        GameImportService.unlisten(listener);
        if (background != null) background.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (background != null) background.release();
        super.onDestroy();
    }

    /**
     * The app speaks Portuguese, the game's language here, also on a phone set to another language: the app's own
     * locale (Android 13+), which the system's texts in the dialogs (Cancelar, OK) follow too. True when the
     * activity is about to be recreated for it.
     */
    private boolean portuguese() {
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
        if (lm == null || !lm.getApplicationLocales().isEmpty()) return false;
        lm.setApplicationLocales(new android.os.LocaleList(Locale.forLanguageTag("pt-BR")));
        return !getResources().getConfiguration().getLocales().get(0).getLanguage().equals("pt");
    }

    private void startGame() {
        if (started) return;
        started = true;
        startActivity(new Intent(this, WwhdActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(0, 0);
    }

    // ---- the state: busy > interrupted > missing > ready ----

    private void render(GameImportService.Status s) {
        if (screen == null || started) return;
        boolean ready = M3dwGame.ready(this);
        screen.setLocked(0, ready ? null : getString(R.string.first_locked));
        switch (s.state) {
            case RUNNING:
                screen.setBar(Notice.Variant.BUSY, s.step, progressText(s), null);
                screen.setProgress(null, s.fraction());
                return;
            case DONE:
                GameImportService.acknowledge();
                say(getString(R.string.import_done));
                screen.setBar(Notice.Variant.OK, getString(R.string.import_done), "", null);
                startGame();
                return;
            case FAILED:
                GameImportService.acknowledge();
                DsDialog.with(this).icon("triangle-alert").title(getString(R.string.import_failed_title)).message(s.error)
                        .button(getString(R.string.first_help), this::showHelp).button(getString(android.R.string.ok), null).show();
                break;
            case CANCELLED:
                GameImportService.acknowledge();
                say(getString(R.string.import_cancelled));
                break;
            default: break;
        }
        screen.setProgress(null, -1);
        if (GameImportService.pending(this)) {
            screen.setCard("triangle-alert", Notice.Variant.WARN, getString(R.string.first_paused_title),
                    getString(R.string.first_paused_text), "play", getString(R.string.first_resume));
        } else if (!ready) {
            screen.setCard("archive", Notice.Variant.WARN, getString(R.string.first_missing_title),
                    getString(R.string.first_missing_text), "folder-open", getString(R.string.first_import));
        } else {
            M3dwGame.Build b = M3dwGame.expected(this);
            screen.setBar(Notice.Variant.OK, getString(R.string.first_ready), b != null ? b.label : "", null);
        }
    }

    private String progressText(GameImportService.Status s) {
        if (s.total <= 0) return getString(R.string.first_wait);
        return String.format(Locale.ROOT, "%.2f / %.2f GB · %d%%", s.done / 1e9, s.total / 1e9, Math.round(s.fraction() * 100));
    }

    // ---- the menu ----

    private void activate(int index) {
        switch (ITEMS[index]) {
            case "play":
                if (busy()) say(getString(R.string.first_busy));
                else if (!M3dwGame.ready(this)) say(getString(R.string.first_locked));
                else startGame();
                break;
            case "import": askImport(); break;
            case "saves": askSaves(); break;
            case "help": showHelp(); break;
            case "quit": finishAndRemoveTask(); break;
            default: break;
        }
    }

    private boolean busy() { return GameImportService.status().state == GameImportService.State.RUNNING; }

    /** The card's button: resume an interrupted import, else start one. */
    private void primary() {
        if (GameImportService.pending(this) && !busy()) GameImportService.resume(this);
        else askImport();
    }

    private void askImport() {
        if (busy()) {
            DsDialog.confirmDestructive(this, getString(R.string.import_cancel_title), getString(R.string.import_cancel_text),
                    getString(R.string.import_cancel), () -> GameImportService.cancel(this));
            return;
        }
        if (GameImportService.pending(this)) {  // a new pick replaces the interrupted one
            GameImportService.forget(this);
            M3dwGame.deleteTree(M3dwGame.stagingDir(this));
        }
        String text = getString(M3dwGame.ready(this) ? R.string.first_pick_text_again : R.string.first_pick_text);
        DsDialog.with(this).icon("folder-open").title(getString(R.string.first_pick_title)).message(text)
                .button("folder-open", getString(R.string.first_pick_button), () -> pick(PICK_IMAGE, false))
                .button(getString(android.R.string.cancel), null).show();
    }

    private void pick(int request, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, request);
        } catch (android.content.ActivityNotFoundException e) {
            DsDialog.alert(this, getString(R.string.import_failed_title), getString(R.string.first_no_picker), null);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || screen == null) return;
        if (request == PICK_IMAGE && data.getData() != null) {
            image = keep(data.getData());
            imageName = displayName(image);
            // the system may kill this process while the keys.txt picker is open: keep the .wux for onCreate
            getSharedPreferences("import_pick", MODE_PRIVATE).edit().putString("image", image.toString())
                    .putString("imageName", imageName).apply();
            String low = imageName.toLowerCase(Locale.ROOT);
            if (low.endsWith(".wua")) {
                getSharedPreferences("import_pick", MODE_PRIVATE).edit().clear().apply();
                GameImportService.start(this, image, new ArrayList<>(), imageName);
            } else if (low.endsWith(".zip") || low.endsWith(".rar") || low.endsWith(".7z")) {
                DsDialog.alert(this, getString(R.string.import_failed_title), getString(R.string.import_err_packed), null);
            } else {
                DsDialog.with(this).icon("file-text").title(getString(R.string.first_keys_title))
                        .message(getString(R.string.first_keys_text, imageName))
                        .button("file-text", getString(R.string.first_keys_button), () -> pick(PICK_KEYS, true))
                        .button(getString(android.R.string.cancel), null).show();
            }
        } else if (request == PICK_KEYS && image != null) {
            List<Uri> keys = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); ++i) keys.add(keep(data.getClipData().getItemAt(i).getUri()));
            else if (data.getData() != null) keys.add(keep(data.getData()));
            if (keys.isEmpty()) return;
            getSharedPreferences("import_pick", MODE_PRIVATE).edit().clear().apply();
            GameImportService.start(this, image, keys, imageName);
        }
    }

    /** Keeps read access across restarts of the app (an interrupted import resumes with the same files). */
    private Uri keep(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // a provider without persistable grants: fine while the app runs
        }
        return uri;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the URI's last part
        }
        String s = uri.getLastPathSegment();
        return s == null ? "" : s;
    }

    /** Export (a zip in Downloads, then the share sheet) or import (a zip from Export or the PC version's save/). */
    private void askSaves() {
        if (busy()) { say(getString(R.string.first_busy)); return; }
        boolean has = SaveFiles.hasSave(this);
        DsDialog.Builder d = DsDialog.with(this).icon("archive").title(getString(R.string.home_saves))
                .message(getString(has ? R.string.home_saves_text : R.string.home_saves_none));
        if (has) d.button("upload", getString(R.string.save_export), () -> startActivity(new Intent(this, ExportSaveActivity.class)));
        d.button("download", getString(R.string.save_import), () -> startActivity(new Intent(this, ImportSaveActivity.class)))
                .button(getString(android.R.string.cancel), null).show();
    }

    private void showHelp() {
        M3dwGame.Build b = M3dwGame.expected(this);
        DsDialog.with(this).icon("info").title(getString(R.string.first_help_title)).wide()
                .scrollText(getString(R.string.first_help_text, b != null ? b.label : "?", M3dwGame.gameDir(this).getPath()))
                .button(getString(android.R.string.ok), null).show();
    }

    private void say(String message) {
        toast.set(message, "");
        toast.bringToFront();
        toast.animate().cancel();
        toast.setAlpha(1);
        toast.animate().alpha(0).setStartDelay(1800).setDuration(300).start();
    }

    // ---- pads and keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (screen == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: screen.select(screen.selected() - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: screen.select(screen.selected() + 1); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                activate(screen.selected());
                return true;
            case KeyEvent.KEYCODE_BUTTON_B: finish(); return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (screen != null && (e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            float y = e.getAxisValue(MotionEvent.AXIS_Y), hat = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int dir = (y < -.6f || hat < -.5f) ? -1 : (y > .6f || hat > .5f) ? 1 : 0;
            if (dir != lastStick && dir != 0) screen.select(screen.selected() + dir);
            lastStick = dir;
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}
