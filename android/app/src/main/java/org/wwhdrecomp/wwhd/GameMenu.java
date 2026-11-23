package org.wwhdrecomp.wwhd;

import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import io.github.belmantegu.ds.android.HomeUi;
import io.github.belmantegu.ds.android.OptionsPanel;
import io.github.belmantegu.ds.android.SheetMenu;
import io.github.belmantegu.ds.core.DsMetrics;
import io.github.belmantegu.ds.core.GameTheme;
import io.github.belmantegu.ds.core.OptionsEngine;
import io.github.belmantegu.ds.core.SheetSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The in-game menu of the Super Mario 3D World build (the Diablo III port's GameMenu, the same ds components):
 * a view over the game, not a dialog, so the window keeps focus. The pause button, BACK and a pad's Guide
 * button open it; the game holds while it is open (WwhdActivity.nativeSetPaused). It shows the SheetMenu
 * (Continue, Options, the touch buttons, Restart to apply when needed, Exit); Options opens the OptionsPanel
 * and closing it returns to the sheet.
 */
final class GameMenu extends FrameLayout {
    interface Host {
        void onContinue();
        void onExit();
        void onRestartGame();
        void onTouchToggle();
        void onEditor();
        boolean touchShown();
        /** A setting changed (already saved): apply what applies live. */
        void onLiveChanged(String key);
        /** About > Export diagnostics: build the ZIP and offer to share or save it. */
        void onDiagnostics();
        /** About > Reimportar jogo: confirm, end the game and open the import screen. */
        void onReimport();
        /** Driver > download / import / delete (they need dialogs, the file picker and the network). */
        void onDriverAction(String key);
        void say(String message);
    }

    private final Host host;
    private final SheetMenu pause;
    private final OptionsPanel options;
    private final OptionsEngine model;
    private final Map<String, String> launch;
    private final android.view.View hintsPause, hintsOptions;
    private int lastStick;

    GameMenu(HomeUi ui, Map<String, String> launchValues, Host host) {
        super(ui.activity);
        this.host = host;
        this.launch = launchValues;
        setClickable(true);  // the touch buttons underneath get nothing while it is open
        android.content.Context c = ui.activity;
        pause = new SheetMenu(ui, c.getString(R.string.menu_paused), c.getString(R.string.menu_brand), "", "continue", this::pick);
        addView(pause, new LayoutParams(-1, -1));
        model = MenuOptions.model(c, launchValues);
        options = new OptionsPanel(ui, model, c.getString(R.string.version_line, MenuOptions.versionName(c)), new OptionsPanel.Host() {
            @Override public void onAction(String key) {
                if (key.equals("editor")) host.onEditor();
                else if (key.equals("diag")) host.onDiagnostics();
                else if (key.equals("reimport")) host.onReimport();
                else if (key.startsWith("driver_")) host.onDriverAction(key);
            }
            @Override public void onChanged(String key) { host.onLiveChanged(key); }
            @Override public void onClosed() { showPause(); }
            @Override public void say(String message) { host.say(message); }
            @Override public boolean restartNeeded() { return MenuOptions.restartNeeded(getContext(), launch); }
        });
        addView(options, new LayoutParams(-1, -1));
        hintsPause = hints(ui, c.getString(R.string.hint_continue));
        hintsOptions = hints(ui, c.getString(R.string.ds_hint_back));
        setVisibility(GONE);
    }

    private android.view.View hints(HomeUi ui, String backLabel) {
        LinearLayout pads = new LinearLayout(ui.activity);
        pads.addView(ui.padHint("A", ui.activity.getString(R.string.ds_hint_select), GameTheme.PAD_A));
        pads.addView(ui.padHint("B", backLabel, GameTheme.PAD_B));
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.rightMargin = ui.gap.right + ui.px(DsMetrics.CORNER);
        lp.topMargin = ui.gap.top + ui.px(DsMetrics.LINE_TOP);
        addView(pads, lp);
        return pads;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Re-reads the rows (a driver was installed or deleted). */
    void refreshOptions() { options.refresh(); }

    void open() {
        lastStick = 0;
        options.dismiss();
        showPause();
        setVisibility(VISIBLE);
    }

    void close() {
        options.dismiss();
        pause.hide();
        setVisibility(GONE);
    }

    private void showPause() {
        android.content.Context c = getContext();
        List<SheetSpec.Item> items = new ArrayList<>();
        items.add(new SheetSpec.Item("continue", "play", c.getString(R.string.menu_continue)));
        items.add(new SheetSpec.Item("options", "sliders-horizontal", c.getString(R.string.menu_options)));
        items.add(new SheetSpec.Item("touch", "gamepad-2",
                c.getString(host.touchShown() ? R.string.menu_touch_hide : R.string.menu_touch_show)));
        items.add(new SheetSpec.Item("editor", "layout-grid", c.getString(R.string.menu_editor)));
        if (MenuOptions.restartNeeded(c, launch)) items.add(new SheetSpec.Item("restart", "refresh-cw", c.getString(R.string.menu_restart)));
        items.add(new SheetSpec.Item("exit", "log-out", c.getString(R.string.menu_exit)));
        pause.setItems(items);
        OptionsEngine.Row res = model.find("res"), shadows = model.find("shadows");
        pause.setFooter(c.getString(R.string.pause_res, res != null ? model.valueText(res) : ""),
                c.getString(R.string.pause_shadows, shadows != null ? model.valueText(shadows) : ""));
        pause.show();
        hintsPause.setVisibility(VISIBLE);
        hintsOptions.setVisibility(GONE);
    }

    private void pick(String key) {
        switch (key) {
            case "continue": host.onContinue(); break;
            case "options":
                pause.hide();
                hintsPause.setVisibility(GONE);
                hintsOptions.setVisibility(VISIBLE);
                options.show();
                break;
            case "touch": host.onTouchToggle(); showPause(); break;
            case "editor": host.onEditor(); break;
            case "restart": host.onRestartGame(); break;
            case "exit": host.onExit(); break;
            default: break;
        }
    }

    /** BACK (the key or the gesture): one level up inside Options, otherwise resume. */
    void back() {
        if (options.isOpen()) options.onKey(KeyEvent.KEYCODE_BACK, false);
        else host.onContinue();
    }

    /** Keys while the menu is open: true = handled (nothing reaches the paused game). */
    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean repeat = e.getRepeatCount() > 0;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BUTTON_B && !options.isOpen()) { host.onContinue(); return true; }
        if (options.isOpen()) options.onKey(k, repeat);
        else pause.onKey(k, repeat);
        return true;
    }

    /** The pad's left stick and hat, as one step per push. */
    boolean onMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0 || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        if (options.isOpen()) return options.onMotion(e);
        float y = e.getAxisValue(MotionEvent.AXIS_Y), hat = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int dir = (y < -.6f || hat < -.5f) ? -1 : (y > .6f || hat > .5f) ? 1 : 0;
        if (dir != lastStick && dir != 0) pause.onKey(dir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, false);
        lastStick = dir;
        return true;
    }
}
