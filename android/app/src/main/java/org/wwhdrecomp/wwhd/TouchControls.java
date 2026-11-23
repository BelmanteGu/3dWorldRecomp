package org.wwhdrecomp.wwhd;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.input.InputManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.util.ArrayList;
import java.util.List;

// On-screen controls over the game: two sticks, A B X Y, L R ZL ZR, + -, a D-pad, and a small button
// under the view button (top left) that shows or hides them (remembered). They are a virtual SDL gamepad
// (runtime/src/platform/touch_pad.cpp), so the game reads them like a controller. Touches that land
// on no control go on to the game as before (the GamePad picture, the view button), the way
// SDLSurface passes them. While a game controller is connected they are hidden, and the next touch
// brings the floating buttons back temporarily.
public class TouchControls extends View implements InputManager.InputDeviceListener {
    private static native void nativeSetPad(int buttons, int face, float lx, float ly, float rx, float ry, float lt, float rt);
    private static native void nativeDetachPad();
    private static native int nativeTouchState();
    private static native void nativeControllerConnected(boolean connected);
    private static native void nativeScreenDpi(float dpi);
    private static native void nativeRevealButtons();
    private static native void nativeShowControls(boolean shown);
    private static native void nativeSetBlow(boolean down);  // the GamePad microphone (mic.cpp)

    // SDL_GamepadButton numbers
    private static final int BACK = 4, START = 6,
            LEFT_SHOULDER = 9, RIGHT_SHOULDER = 10, DPAD_UP = 11, DPAD_DOWN = 12, DPAD_LEFT = 13, DPAD_RIGHT = 14;
    private static final int KIND_BUTTON = 0, KIND_STICK = 1, KIND_TRIGGER = 2, KIND_TOGGLE = 3, KIND_FACE = 4, KIND_BLOW = 5;
    // KIND_FACE codes: the Wii U button drawn on it (touch_face.h sends the pad button the controls
    // mapping binds to it, so the drawn A is the Wii U's A with either face-button preset)
    private static final int FACE_A = 0, FACE_B = 1, FACE_X = 2, FACE_Y = 3;

    private static final class Control {
        final int kind, code;  // code: button number, stick 0/1, trigger 0/1
        final String label;
        final int group;       // moved together in the editor (GROUPS)
        final float ox, oy, fr;  // offset from the group's anchor and radius, both in short-side units
        float cx, cy, r;
        boolean pressed;
        float sx, sy;  // stick position -1..1
        Control(int kind, int code, String label, int group, float ox, float oy, float fr) {
            this.kind = kind; this.code = code; this.label = label; this.group = group; this.ox = ox; this.oy = oy; this.fr = fr;
        }
    }
    // The groups the editor moves, and their default anchors as fractions of the width / height: left stick,
    // right stick, A B X Y, D-pad, ZL, L, R, ZR, minus, plus, blow (only with Options > Controls > Blow: Button). Offsets inside a group are in short-side units,
    // so the A B X Y diamond and the D-pad cross are as wide as they are tall on any screen.
    private static final float[][] GROUPS = {{0.14f, 0.70f}, {0.68f, 0.80f}, {0.86f, 0.58f}, {0.14f, 0.36f},
            {0.19f, 0.15f}, {0.30f, 0.15f}, {0.70f, 0.15f}, {0.81f, 0.15f}, {0.42f, 0.92f}, {0.58f, 0.92f},
            {0.93f, 0.33f}};
    private final float[][] anchor = new float[GROUPS.length][2];
    private float scale = 1f, opacity = 1f;
    private boolean editing;
    private Runnable editDone;
    private int dragGroup = -1;
    private float dragX, dragY;
    private final android.graphics.RectF doneBox = new android.graphics.RectF(), resetBox = new android.graphics.RectF();

    private final List<Control> controls = new ArrayList<>();
    private final Control toggle;
    private final SparseArray<Control> owner = new SparseArray<>();  // pointer -> control (null: the game's)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;
    private final InputManager inputs;
    private ControllerHintPolicy hintPolicy;
    private Runnable refresh;
    private boolean shown;      // the player's choice (the show/hide button), remembered
    private boolean controller; // a game controller is connected and no touch came since
    private boolean buttonsVisible = true, revealGesture, settingsOpen;
    private boolean blowShown, lastBlow;  // the blow button (Options > Controls > Blow: Button) and its last state sent
    private boolean toggleTo;   // what the show/hide button sets when released (shown before the touch: hide)
    private int lastButtons = -1, lastTouchDevice;
    private float lastLx, lastLy, lastRx, lastRy, lastLt, lastRt;

    public TouchControls(Context context) {
        super(context);
        prefs = context.getSharedPreferences("controls", Context.MODE_PRIVATE);
        shown = prefs.getBoolean("touch_controls", true);
        hintPolicy = new ControllerHintPolicy(prefs.getBoolean("settings_shortcut_hint_shown", false));
        inputs = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        if (inputs != null) {
            inputs.registerInputDeviceListener(this, null);
            for (int id : inputs.getInputDeviceIds()) controller |= isController(InputDevice.getDevice(id));
            SDLControllerManager.requestDeviceRefresh();
        }
        controls.add(new Control(KIND_STICK, 0, "", 0, 0, 0, 0.13f));
        controls.add(new Control(KIND_STICK, 1, "", 1, 0, 0, 0.11f));
        // A B X Y where a Wii U GamePad has them: a diamond, the same distance across and down
        final float d = 0.115f;
        controls.add(new Control(KIND_FACE, FACE_A, "A", 2, d, 0, 0.055f));
        controls.add(new Control(KIND_FACE, FACE_B, "B", 2, 0, d, 0.055f));
        controls.add(new Control(KIND_FACE, FACE_X, "X", 2, 0, -d, 0.055f));
        controls.add(new Control(KIND_FACE, FACE_Y, "Y", 2, -d, 0, 0.055f));
        final float e = 0.085f;
        controls.add(new Control(KIND_BUTTON, DPAD_UP, "▲", 3, 0, -e, 0.04f));
        controls.add(new Control(KIND_BUTTON, DPAD_DOWN, "▼", 3, 0, e, 0.04f));
        controls.add(new Control(KIND_BUTTON, DPAD_LEFT, "◀", 3, -e, 0, 0.04f));
        controls.add(new Control(KIND_BUTTON, DPAD_RIGHT, "▶", 3, e, 0, 0.04f));
        controls.add(new Control(KIND_TRIGGER, 0, "ZL", 4, 0, 0, 0.05f));
        controls.add(new Control(KIND_BUTTON, LEFT_SHOULDER, "L", 5, 0, 0, 0.05f));
        controls.add(new Control(KIND_BUTTON, RIGHT_SHOULDER, "R", 6, 0, 0, 0.05f));
        controls.add(new Control(KIND_TRIGGER, 1, "ZR", 7, 0, 0, 0.05f));
        controls.add(new Control(KIND_BUTTON, BACK, "−", 8, 0, 0, 0.04f));
        controls.add(new Control(KIND_BUTTON, START, "+", 9, 0, 0, 0.04f));
        // hold to blow into the GamePad microphone (a synthetic blow, runtime/src/mic.cpp)
        controls.add(new Control(KIND_BLOW, 0, "≈", 10, 0, 0, 0.05f));
        // the old show/hide button: kept as an object (the touch code knows it) but never drawn or hit; the
        // in-game menu shows and hides the buttons now
        toggle = new Control(KIND_TOGGLE, 0, "", 0, 0, 0, 0f);
        loadLayout();
        refresh = new Runnable() {
            @Override public void run() {
                syncTouchState();
                maybeShowHint();
                invalidate();
                postDelayed(this, 100);
            }
        };
        post(refresh);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        edge.setStyle(Paint.Style.STROKE);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        if (inputs != null) inputs.unregisterInputDeviceListener(this);
        try { nativeDetachPad(); } catch (UnsatisfiedLinkError ignored) {}
        super.onDetachedFromWindow();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (inputs != null) inputs.registerInputDeviceListener(this, null);
        removeCallbacks(refresh);
        post(refresh);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        placeAll();
        edge.setStrokeWidth(Math.max(2f, Math.min(w, h) * 0.004f));
    }

    private void placeAll() {
        final int w = getWidth(), h = getHeight();
        final float s = Math.min(w, h) * scale;
        for (Control c : controls) {
            c.cx = anchor[c.group][0] * w + c.ox * s;
            c.cy = anchor[c.group][1] * h + c.oy * s;
            c.r = c.fr * s;
        }
    }

    /** The saved layout, size and opacity (the editor and Options > Controls change them). */
    public void loadLayout() {
        for (int g = 0; g < GROUPS.length; ++g) {
            anchor[g][0] = prefs.getFloat("group_" + g + "_x", GROUPS[g][0]);
            anchor[g][1] = prefs.getFloat("group_" + g + "_y", GROUPS[g][1]);
        }
        SharedPreferences menu = getContext().getSharedPreferences(MenuOptions.PREFS, Context.MODE_PRIVATE);
        scale = MenuOptions.parse(menu.getString("touch_size", "100"), 100) / 100f;
        opacity = MenuOptions.parse(menu.getString("touch_opacity", "85"), 85) / 100f;
        placeAll();
        invalidate();
    }

    private void saveLayout() {
        SharedPreferences.Editor ed = prefs.edit();
        for (int g = 0; g < GROUPS.length; ++g) ed.putFloat("group_" + g + "_x", anchor[g][0]).putFloat("group_" + g + "_y", anchor[g][1]);
        ed.apply();
    }

    /** The layout editor (the pause menu's "Edit touch buttons"): drag a group to move it; Done or Reset. */
    public void startEditing(Runnable done) {
        editing = true;
        editDone = done;
        for (Control c : controls) { c.pressed = false; c.sx = 0; c.sy = 0; }
        sendBlow(false);
        owner.clear();
        try { nativeDetachPad(); } catch (UnsatisfiedLinkError ignored) {}
        lastButtons = -1;
        bringToFront();
        invalidate();
    }

    private void finishEditing() {
        editing = false;
        dragGroup = -1;
        saveLayout();
        invalidate();
        if (editDone != null) editDone.run();
    }

    private void drawEditBar(Canvas canvas) {
        final float s = Math.min(getWidth(), getHeight()), h = s * 0.1f, w = s * 0.42f, gap = s * 0.04f, top = s * 0.04f;
        final float mid = getWidth() / 2f;
        resetBox.set(mid - gap / 2 - w, top, mid - gap / 2, top + h);
        doneBox.set(mid + gap / 2, top, mid + gap / 2 + w, top + h);
        fill.setColor(0xE6121729);
        canvas.drawRoundRect(resetBox, h / 2, h / 2, fill);
        fill.setColor(0xFFE8382E);
        canvas.drawRoundRect(doneBox, h / 2, h / 2, fill);
        text.setTextSize(h * 0.42f);
        text.setColor(0xFFFFFFFF);
        canvas.drawText(getContext().getString(R.string.touch_reset), resetBox.centerX(), resetBox.centerY() - (text.descent() + text.ascent()) / 2, text);
        canvas.drawText(getContext().getString(R.string.touch_done), doneBox.centerX(), doneBox.centerY() - (text.descent() + text.ascent()) / 2, text);
        text.setTextSize(s * 0.035f);
        text.setColor(0xFFFFFFFF);
        canvas.drawText(getContext().getString(R.string.touch_drag_hint), mid, top + h + s * 0.06f, text);
    }

    private boolean onEditTouch(MotionEvent event) {
        final float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (doneBox.contains(x, y)) { finishEditing(); return true; }
                if (resetBox.contains(x, y)) {
                    for (int g = 0; g < GROUPS.length; ++g) { anchor[g][0] = GROUPS[g][0]; anchor[g][1] = GROUPS[g][1]; }
                    placeAll(); invalidate(); return true;
                }
                dragGroup = -1;
                float best = Float.MAX_VALUE;
                for (Control c : controls) {
                    if (!usable(c)) continue;
                    float dx = x - c.cx, dy = y - c.cy, dd = (dx * dx + dy * dy) / (c.r * c.r);
                    if (dd <= 2.25f && dd < best) { best = dd; dragGroup = c.group; }
                }
                dragX = x; dragY = y;
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragGroup >= 0) {
                    anchor[dragGroup][0] = Math.max(0.02f, Math.min(0.98f, anchor[dragGroup][0] + (x - dragX) / getWidth()));
                    anchor[dragGroup][1] = Math.max(0.03f, Math.min(0.97f, anchor[dragGroup][1] + (y - dragY) / getHeight()));
                    dragX = x; dragY = y;
                    placeAll(); invalidate();
                }
                return true;
            default:
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragGroup = -1;
                invalidate();
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editing) {
            canvas.drawColor(0x66000000);
            for (Control c : controls) if (usable(c)) drawControl(canvas, c);
            drawEditBar(canvas);
            return;
        }
        if (active()) for (Control c : controls) if (usable(c)) drawControl(canvas, c);
    }

    private int alpha(int argb) {  // the opacity setting applied to a colour
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.15f, Math.min(1f, opacity)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // White buttons with dark letters (the in-game menu's look, android/.../GameMenu): faces are discs, the
    // shoulders and triggers pills, the sticks a white ring with a white knob. Pressed: fully opaque.
    private static final int INK = 0xFF1F2434, WHITE_IDLE = 0xD8FFFFFF, WHITE_PRESSED = 0xFFFFFFFF, SHADOW = 0x40000000;
    private android.graphics.Typeface face;
    private final android.graphics.RectF pill = new android.graphics.RectF();

    private void drawControl(Canvas canvas, Control c) {
        if (face == null) {
            try { face = getResources().getFont(R.font.baloo2); } catch (RuntimeException e) { face = android.graphics.Typeface.DEFAULT_BOLD; }
            face = android.graphics.Typeface.create(face, 800, false);
            text.setTypeface(face);
            text.setFakeBoldText(false);
        }
        final boolean on = c.pressed || (editing && c.group == dragGroup);
        final float lift = c.r * 0.06f;
        if (c.kind == KIND_STICK) {
            edge.setColor(alpha(on ? WHITE_PRESSED : WHITE_IDLE));
            edge.setStrokeWidth(c.r * 0.07f);
            fill.setColor(alpha(0x26FFFFFF));
            canvas.drawCircle(c.cx, c.cy, c.r, fill);
            canvas.drawCircle(c.cx, c.cy, c.r, edge);
            float kx = c.cx + c.sx * c.r * 0.6f, ky = c.cy + c.sy * c.r * 0.6f;
            fill.setColor(alpha(SHADOW));
            canvas.drawCircle(kx, ky + lift, c.r * 0.42f, fill);
            fill.setColor(alpha(c.pressed ? WHITE_PRESSED : WHITE_IDLE));
            canvas.drawCircle(kx, ky, c.r * 0.42f, fill);
            return;
        }
        final boolean wide = c.kind == KIND_TRIGGER || c.code == LEFT_SHOULDER || c.code == RIGHT_SHOULDER;
        fill.setColor(alpha(SHADOW));
        if (wide) {
            pill.set(c.cx - c.r * 1.5f, c.cy - c.r * 0.72f + lift, c.cx + c.r * 1.5f, c.cy + c.r * 0.72f + lift);
            canvas.drawRoundRect(pill, c.r * 0.72f, c.r * 0.72f, fill);
            pill.offset(0, -lift);
            fill.setColor(alpha(on ? WHITE_PRESSED : WHITE_IDLE));
            canvas.drawRoundRect(pill, c.r * 0.72f, c.r * 0.72f, fill);
        } else {
            canvas.drawCircle(c.cx, c.cy + lift, c.r, fill);
            fill.setColor(alpha(on ? WHITE_PRESSED : WHITE_IDLE));
            canvas.drawCircle(c.cx, c.cy, c.r, fill);
        }
        text.setColor(alpha(INK));
        text.setTextSize(c.r * (c.label.length() > 1 ? 0.8f : 1.05f));
        canvas.drawText(c.label, c.cx, c.cy - (text.descent() + text.ascent()) / 2, text);
    }

    private Control hit(float x, float y) {
        if (!active()) return null;
        Control best = null;
        float bestD = Float.MAX_VALUE;
        for (Control c : controls) {
            if (!usable(c)) continue;
            float reach = c.kind == KIND_STICK ? 1.5f : 1.3f;
            float dx = x - c.cx, dy = y - c.cy, dd = (dx * dx + dy * dy) / (c.r * c.r);
            if (dd <= reach * reach && dd < bestD) { best = c; bestD = dd; }
        }
        return best;
    }

    private static boolean near(Control c, float x, float y, float reach) {
        float dx = x - c.cx, dy = y - c.cy;
        return dx * dx + dy * dy <= c.r * c.r * reach * reach;
    }

    private static void moveStick(Control c, float x, float y) {
        float dx = (x - c.cx) / c.r, dy = (y - c.cy) / c.r, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > 1f) { dx /= len; dy /= len; }
        c.sx = dx; c.sy = dy;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        lastTouchDevice = event.getDeviceId();
        if (action == MotionEvent.ACTION_DOWN && event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER)
            return false;  // mouse and stylus: SDLSurface underneath as before
        if (editing) return onEditTouch(event);
        syncTouchState();
        if (settingsOpen) return false;
        if (revealGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) revealGesture = false;
            return true;
        }
        final float corner = Math.min(getWidth(), getHeight()) * 0.24f;
        if (!buttonsVisible && action == MotionEvent.ACTION_DOWN && event.getX() < corner && event.getY() < corner) {
            nativeRevealButtons();
            syncTouchState();
            revealGesture = true;
            invalidate();
            return true;
        }
        final int index = event.getActionIndex();
        final boolean wasActive = active();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int id = event.getPointerId(index);
                final float x = event.getX(index), y = event.getY(index);
                Control c = hit(x, y);
                owner.put(id, c);
                if (c == null && action == MotionEvent.ACTION_DOWN) {
                    // The first finger on no control: decline the gesture so the view under it takes it
                    // (SDLSurface, or on a fold the primary pane, which is smaller than this view and
                    // maps the touch to its own size; forward() can only use this view's size).
                    owner.remove(id);
                    update();
                    invalidate();
                    return false;
                } else if (c == null) {
                    forward(event, index, action);
                } else if (c == toggle) {
                    c.pressed = true;
                    toggleTo = !wasActive;  // hidden for a controller: the button brings them back
                } else {
                    c.pressed = true;
                    if (c.kind == KIND_STICK) moveStick(c, x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    final int id = event.getPointerId(i);
                    if (owner.indexOfKey(id) < 0) continue;
                    Control c = owner.get(id);
                    if (c == null) forward(event, i, MotionEvent.ACTION_MOVE);
                    else if (c.kind == KIND_STICK) moveStick(c, event.getX(i), event.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean all = action == MotionEvent.ACTION_CANCEL;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (!all && i != index) continue;
                    final int id = event.getPointerId(i);
                    if (owner.indexOfKey(id) < 0) continue;
                    Control c = owner.get(id);
                    owner.remove(id);
                    if (c == null) { forward(event, i, all ? MotionEvent.ACTION_CANCEL : action); continue; }
                    c.pressed = false;
                    if (c.kind == KIND_STICK) { c.sx = 0; c.sy = 0; }
                    if (c == toggle && !all) setShown(toggleTo);
                }
                break;
            }
            default:
                return true;
        }
        update();
        invalidate();
        return true;
    }

    // a touch for the game: the same call SDLSurface makes (coordinates 0..1 of the view)
    private void forward(MotionEvent event, int i, int action) {
        float x = getWidth() > 1 ? event.getX(i) / (getWidth() - 1) : 0.5f;
        float y = getHeight() > 1 ? event.getY(i) / (getHeight() - 1) : 0.5f;
        float p = Math.min(event.getPressure(i), 1.0f);
        SDLActivity.onNativeTouch(event.getDeviceId(), event.getPointerId(i), action,
                Math.max(0f, Math.min(1f, x)), Math.max(0f, Math.min(1f, y)), p);
    }

    /** The show/hide button and the in-game menu (GameMenu) both switch the buttons here. */
    public void setShown(boolean on) {
        shown = on;
        prefs.edit().putBoolean("touch_controls", on).apply();
        for (Control c : controls) { c.pressed = false; c.sx = 0; c.sy = 0; }
        sendBlow(false);
        for (int i = owner.size() - 1; i >= 0; i--) if (owner.valueAt(i) != null && owner.valueAt(i) != toggle) owner.removeAt(i);
        nativeShowControls(on);
        if (!on) { nativeDetachPad(); lastButtons = -1; }
        invalidate();
    }

    /** Options > Controls > Blow: the blow button only with "Button". */
    public void setBlowShown(boolean on) {
        blowShown = on;
        for (Control c : controls) if (c.kind == KIND_BLOW) c.pressed = false;
        sendBlow(false);
        invalidate();
    }

    private boolean usable(Control c) { return c.kind != KIND_BLOW || blowShown; }

    private void sendBlow(boolean down) {
        if (down == lastBlow) return;
        try { nativeSetBlow(down); lastBlow = down; } catch (UnsatisfiedLinkError ignored) { /* not loaded yet */ }
    }

    // shown, and not hidden for a connected controller
    private boolean active() { return shown && buttonsVisible && !settingsOpen; }

    private void syncTouchState() {
        try {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            float dpi = Math.min(metrics.xdpi, metrics.ydpi);
            if (!Float.isFinite(dpi) || dpi < 72 || dpi > 1000) dpi = metrics.densityDpi;
            nativeScreenDpi(dpi);
            nativeControllerConnected(controller);
            final int state = nativeTouchState();
            final boolean visible = (state & 1) != 0, controlsShown = (state & 2) != 0, open = (state & 4) != 0;
            if (buttonsVisible == visible && shown == controlsShown && settingsOpen == open) return;
            buttonsVisible = visible; shown = controlsShown; settingsOpen = open;
            for (Control c : controls) { c.pressed = false; c.sx = 0; c.sy = 0; }
            sendBlow(false);
            for (int i = owner.size() - 1; i >= 0; --i) {
                if (owner.valueAt(i) != null) owner.removeAt(i);
                else if (settingsOpen) {
                    SDLActivity.onNativeTouch(lastTouchDevice, owner.keyAt(i), MotionEvent.ACTION_CANCEL, 0, 0, 0);
                    owner.removeAt(i);
                }
            }
            nativeDetachPad(); lastButtons = -1;
            if (active()) update();
        } catch (UnsatisfiedLinkError ignored) { /* SDL has not loaded the native library yet. */ }
    }

    private void maybeShowHint() {
        if (!(getParent() instanceof android.widget.RelativeLayout) || !hintPolicy.claim(controller)) return;
        final android.widget.RelativeLayout parent = (android.widget.RelativeLayout) getParent();
        final android.widget.TextView hint = new android.widget.TextView(getContext());
        boolean home = false;
        if (inputs != null) for (int id : inputs.getInputDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (isController(d)) home |= d.hasKeys(android.view.KeyEvent.KEYCODE_BUTTON_MODE)[0];
        }
        hint.setText(getContext().getString(home ? R.string.controller_settings_hint_home : R.string.controller_settings_hint));
        hint.setTextSize(18); hint.setTextColor(0xFFFFFFFF); hint.setBackgroundColor(0xEE123340);
        hint.setGravity(android.view.Gravity.CENTER);
        final int pad = (int)(16 * getResources().getDisplayMetrics().density);
        hint.setPadding(pad, pad, pad, pad);
        android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.setMargins(pad, pad, pad, pad);
        parent.addView(hint, lp);
        hint.setOnClickListener(v -> parent.removeView(hint));
        hint.postDelayed(() -> parent.removeView(hint), 8000);
        prefs.edit().putBoolean("settings_shortcut_hint_shown", true).apply();
    }

    private static boolean isController(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        // Built-in parts can call themselves gamepads (the Redmi 10C's fingerprint sensor, uinput-silead, does):
        // only a pad plugged in or paired (Bluetooth, USB) hides the touch buttons.
        if (android.os.Build.VERSION.SDK_INT >= 29 && !d.isExternal()) return false;
        final int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override public void onInputDeviceAdded(int id) {
        SDLControllerManager.requestDeviceRefresh();
        if (controller || !isController(InputDevice.getDevice(id))) return;
        controller = true;
        for (Control c : controls) { c.pressed = false; c.sx = 0; c.sy = 0; }
        sendBlow(false);
        for (int i = owner.size() - 1; i >= 0; i--) if (owner.valueAt(i) != null && owner.valueAt(i) != toggle) owner.removeAt(i);
        if (shown) { nativeDetachPad(); lastButtons = -1; }
        invalidate();
    }
    @Override public void onInputDeviceRemoved(int id) {
        SDLControllerManager.requestDeviceRefresh();
        if (!controller || inputs == null) return;
        boolean any = false;
        for (int other : inputs.getInputDeviceIds()) any |= other != id && isController(InputDevice.getDevice(other));
        if (!any) { controller = false; invalidate(); }
    }
    @Override public void onInputDeviceChanged(int id) {
        SDLControllerManager.requestDeviceRefresh();
        controller = false;
        if (inputs != null) for (int other : inputs.getInputDeviceIds()) controller |= isController(InputDevice.getDevice(other));
        invalidate();
    }

    private void update() {
        boolean blow = false;
        if (active()) for (Control c : controls) blow |= c.kind == KIND_BLOW && c.pressed;
        sendBlow(blow);
        if (!active()) return;
        int buttons = 0, face = 0;
        float lx = 0, ly = 0, rx = 0, ry = 0, lt = 0, rt = 0;
        for (Control c : controls) {
            if (c.kind == KIND_BUTTON && c.pressed) buttons |= 1 << c.code;
            else if (c.kind == KIND_FACE && c.pressed) face |= 1 << c.code;
            else if (c.kind == KIND_TRIGGER && c.pressed) { if (c.code == 0) lt = 1; else rt = 1; }
            else if (c.kind == KIND_STICK) { if (c.code == 0) { lx = c.sx; ly = c.sy; } else { rx = c.sx; ry = c.sy; } }
        }
        buttons |= face << 16;  // (only for the change check below)
        if (buttons == lastButtons && lx == lastLx && ly == lastLy && rx == lastRx && ry == lastRy && lt == lastLt && rt == lastRt)
            return;
        lastButtons = buttons; lastLx = lx; lastLy = ly; lastRx = rx; lastRy = ry; lastLt = lt; lastRt = rt;
        try {
            nativeSetPad(buttons & 0xFFFF, face, lx, ly, rx, ry, lt, rt);
        } catch (UnsatisfiedLinkError e) {
            // libmain.so not loaded yet (very first touch): the next change sends it
            lastButtons = -1;
        }
    }
}
