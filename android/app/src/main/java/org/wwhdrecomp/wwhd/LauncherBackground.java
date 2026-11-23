package org.wwhdrecomp.wwhd;

import io.github.belmantegu.ds.android.*;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.IOException;

/**
 * The home screen's background (the Diablo III port's VideoBackground): a muted video loop or a
 * picture from the player's own art (assets/launcher, packed by
 * android/build_sm3dw_apk.sh from android/app/local-assets/launcher, gitignored), else the theme's ground
 * and the shade alone. The video is read through openFd, so it must be stored
 * uncompressed in the APK (noCompress mp4 in build.gradle).
 */
final class LauncherBackground {
    private final Activity activity;
    private final HomeUi ui;
    private TextureView video;
    private MediaPlayer player;

    LauncherBackground(Activity activity, HomeUi ui) {
        this.activity = activity;
        this.ui = ui;
    }

    /** `menuRight`: the split home (the menu in a column on the right), so that side is shaded too. */
    void addTo(FrameLayout root, boolean menuRight) {
        boolean hasVideo;
        try (AssetFileDescriptor ignored = activity.getAssets().openFd("launcher/bg.mp4")) {
            hasVideo = true;
        } catch (IOException e) {
            hasVideo = false;
        }
        Bitmap picture = hasVideo ? null : ui.loadAsset("launcher/background.jpg");
        if (hasVideo) {
            video = new TextureView(activity);
            video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { start(st); }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(); }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { release(); return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        } else if (picture == null) {
            // No art packed: the theme's ground, a little lighter towards the lower right.
            View ground = new View(activity);
            ground.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[] {ui.theme.bg, ui.theme.surface, ui.theme.bg}));
            root.addView(ground, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView bg = new ImageView(activity);
            bg.setImageBitmap(picture);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        }
        View light = new View(activity);
        light.setBackground(new Shade(menuRight));
        root.addView(light, new FrameLayout.LayoutParams(-1, -1));
    }

    private void start(SurfaceTexture st) {
        try (AssetFileDescriptor fd = activity.getAssets().openFd("launcher/bg.mp4")) {
            player = new MediaPlayer();
            player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            player.setSurface(new Surface(st));
            player.setLooping(true);
            player.setVolume(0, 0);
            player.setOnPreparedListener(mp -> { fit(); mp.start(); });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            release();
        }
    }

    // Center-crop the video in its view.
    private void fit() {
        if (player == null || video == null || video.getWidth() == 0) return;
        float vw = player.getVideoWidth(), vh = player.getVideoHeight();
        if (vw <= 0 || vh <= 0) return;
        float w = video.getWidth(), h = video.getHeight();
        float scale = Math.max(w / vw, h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * scale / w, vh * scale / h, w / 2, h / 2);
        video.setTransform(m);
    }

    void resume() {
        if (player != null) player.start();
    }

    void pause() {
        if (player != null && player.isPlaying()) player.pause();
    }

    void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /**
     * The shade over the video: one soft shadow from the left edge, where the
     * logo sits, fading out by 62% of the width (half of it in the split home),
     * a darker band along the bottom for the status bar and the version (the
     * game's own menu frames its art the same way) and, in the split home, a
     * harder one from the right edge under the menu. Built once per size, not
     * every video frame.
     */
    static final class Shade extends Drawable {
        private final Paint side = new Paint(Paint.ANTI_ALIAS_FLAG), foot = new Paint(Paint.ANTI_ALIAS_FLAG),
                right = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean menuRight;
        private int w = -1, h = -1;

        Shade(boolean menuRight) { this.menuRight = menuRight; }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (w != b.width() || h != b.height()) {
                w = b.width();
                h = b.height();
                side.setShader(menuRight
                        ? new LinearGradient(b.left, 0, b.left + w * .5f, 0, new int[] {0xB0000000, 0x50000000, 0x00000000}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP)
                        : new LinearGradient(b.left, 0, b.left + w * .62f, 0, new int[] {0xC0000000, 0x60000000, 0x00000000}, new float[] {0, .6f, 1}, Shader.TileMode.CLAMP));
                right.setShader(new LinearGradient(b.right, 0, b.right - w * .42f, 0,
                        new int[] {0xE0000000, 0xA0000000, 0x00000000}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP));
                foot.setShader(new LinearGradient(0, b.bottom - h * .3f, 0, b.bottom,
                        new int[] {0x00000000, 0x90000000}, null, Shader.TileMode.CLAMP));
            }
            c.drawRect(b, side);
            if (menuRight) c.drawRect(b, right);
            c.drawRect(b.left, b.bottom - h * .3f, b.right, b.bottom, foot);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
