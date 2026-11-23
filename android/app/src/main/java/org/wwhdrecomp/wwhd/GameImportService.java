package org.wwhdrecomp.wwhd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports the game from the player's own dump (Super Mario 3D World build): a .wux/.wud with the key files
 * (a Cemu keys.txt) or a Cemu .wua, picked with the system's file picker (GameSetupActivity). A foreground
 * service in the ":import" process, so it goes on with the screen off or the app in the background, and an
 * interrupted import resumes (the files already written are kept; the job is redelivered and remembered).
 *
 * Steps: read the title (wrong game or region: a clear message before anything is written), check the free
 * space, extract code/ and compare RedCarpet.rpx with the one this APK was built from, extract the rest into
 * files/game.import, then swap it in as files/game. Keys are read into memory, passed to the extractor and
 * never stored or logged.
 */
public class GameImportService extends Service {
    static final String ACTION_START = "org.wwhdrecomp.wwhd.IMPORT_START", ACTION_CANCEL = "org.wwhdrecomp.wwhd.IMPORT_CANCEL";
    static final String EXTRA_IMAGE = "image", EXTRA_KEYS = "keys", EXTRA_NAME = "name";
    private static final String PREFS = "import", CHANNEL = "import";
    private static final int NOTIFICATION = 77;
    private static final long KEY_FILE_LIMIT = 4L << 20, SPACE_MARGIN = 128L << 20;

    // ---- the state the screen shows (same process) ----

    enum State { IDLE, RUNNING, DONE, FAILED, CANCELLED }

    /** A snapshot of the import, for the screen and the notification. */
    static final class Status {
        final State state;
        final String step, error;
        final long done, total;

        Status(State state, String step, long done, long total, String error) {
            this.state = state;
            this.step = step;
            this.done = done;
            this.total = total;
            this.error = error;
        }

        float fraction() { return total > 0 ? Math.min(1f, (float) done / total) : -1f; }
    }

    interface Listener {
        void changed(Status s);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Status status = new Status(State.IDLE, "", 0, 0, null);
    private static final List<Listener> LISTENERS = new ArrayList<>();

    static Status status() { return status; }

    static void listen(Listener l) {
        synchronized (LISTENERS) { LISTENERS.add(l); }
        l.changed(status);
    }

    static void unlisten(Listener l) {
        synchronized (LISTENERS) { LISTENERS.remove(l); }
    }

    /** The screen took note of a finished import (DONE, FAILED, CANCELLED): back to IDLE. */
    static void acknowledge() {
        if (status.state != State.RUNNING) status = new Status(State.IDLE, "", 0, 0, null);
    }

    private static void publish(Status s) {
        status = s;
        MAIN.post(() -> {
            List<Listener> copy;
            synchronized (LISTENERS) { copy = new ArrayList<>(LISTENERS); }
            for (Listener l : copy) l.changed(s);
        });
    }

    // ---- starting ----

    static void start(Context c, Uri image, List<Uri> keys, String name) {
        StringBuilder k = new StringBuilder();
        for (Uri u : keys) k.append(u).append('\n');
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(EXTRA_IMAGE, image.toString())
                .putString(EXTRA_KEYS, k.toString()).putString(EXTRA_NAME, name).commit();
        c.startForegroundService(new Intent(c, GameImportService.class).setAction(ACTION_START)
                .putExtra(EXTRA_IMAGE, image.toString()).putExtra(EXTRA_KEYS, k.toString()).putExtra(EXTRA_NAME, name));
    }

    /** An import that was started and neither finished nor was cancelled (the app was stopped meanwhile). */
    static boolean pending(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).contains(EXTRA_IMAGE);
    }

    /** Starts the pending import again (it resumes from the files already written). */
    static void resume(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String image = p.getString(EXTRA_IMAGE, null);
        if (image == null) return;
        c.startForegroundService(new Intent(c, GameImportService.class).setAction(ACTION_START).putExtra(EXTRA_IMAGE, image)
                .putExtra(EXTRA_KEYS, p.getString(EXTRA_KEYS, "")).putExtra(EXTRA_NAME, p.getString(EXTRA_NAME, "")));
    }

    static void forget(Context c) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().commit();
    }

    static void cancel(Context c) {
        c.startService(new Intent(c, GameImportService.class).setAction(ACTION_CANCEL));
    }

    // ---- the service ----

    private Thread worker;
    private volatile boolean cancelled, stopping;
    private PowerManager.WakeLock wake;
    private long lastNotified;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            GameImportNative.setCancelled(true);
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification(getString(R.string.import_notify_title), getString(R.string.import_step_prepare), -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (worker != null) return START_REDELIVER_INTENT;  // already importing
        String image = intent != null ? intent.getStringExtra(EXTRA_IMAGE) : null;
        if (image == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final Uri imageUri = Uri.parse(image);
        final List<Uri> keys = new ArrayList<>();
        for (String line : intent.getStringExtra(EXTRA_KEYS) == null ? new String[0] : intent.getStringExtra(EXTRA_KEYS).split("\n"))
            if (!line.trim().isEmpty()) keys.add(Uri.parse(line.trim()));
        final String name = intent.getStringExtra(EXTRA_NAME);
        cancelled = false;
        GameImportNative.setCancelled(false);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sm3dw:import");
        wake.acquire(4 * 3600 * 1000L);
        worker = new Thread(() -> {
            run(imageUri, keys, name == null ? "" : name);
            MAIN.post(this::finished);
        }, "game-import");
        worker.start();
        return START_REDELIVER_INTENT;
    }

    private void finished() {
        worker = null;
        if (wake != null && wake.isHeld()) wake.release();
        Status s = status;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (s.state == State.DONE || s.state == State.FAILED) {
            // a lasting notice for when the screen is not in front (tap: the import screen)
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIFICATION + 1, notification(getString(s.state == State.DONE ? R.string.import_notify_done : R.string.import_notify_failed),
                    s.state == State.DONE ? getString(R.string.import_notify_done_text) : s.error, -2));
        }
        stopSelf();
    }

    @Override public void onDestroy() {
        if (worker != null) {  // the system stopped us: the native loop ends, the next start resumes
            stopping = true;
            GameImportNative.setCancelled(true);
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    /** A failure the player can read (string resource already resolved). */
    private static final class Problem extends Exception {
        /** Keep what was extracted (no space, cannot write): the same file resumes after the fix. */
        final boolean keep;

        Problem(String message) { this(message, false); }

        Problem(String message, boolean keep) {
            super(message);
            this.keep = keep;
        }
    }

    private void run(Uri imageUri, List<Uri> keyUris, String name) {
        publish(new Status(State.RUNNING, getString(R.string.import_step_prepare), 0, -1, null));
        Log.i("sm3dw-import", "import of " + name + " (" + keyUris.size() + " key file(s))");
        File staging = M3dwGame.stagingDir(this);
        try (ParcelFileDescriptor pfd = open(imageUri)) {
            byte[][] keys = readKeys(keyUris);
            String path = "/proc/self/fd/" + pfd.getFd();
            M3dwGame.Build want = M3dwGame.expected(this);
            String[] info = GameImportNative.inspect(path, keys, want != null ? want.titleId : "");
            check(info, keyUris.isEmpty());
            String title = info[3];
            long bytes = Long.parseLong(info[5]);
            Log.i("sm3dw-import", "format " + info[2] + ", title " + title + ", " + info[4] + " files, " + bytes + " bytes");
            if (want != null && !want.titleId.equalsIgnoreCase(title)) {
                String region = M3dwGame.regionOf(title);
                throw new Problem(getString(R.string.import_err_title, region != null ? region : title, want.label));
            }

            // a staging folder of another file is started over; one of this file is resumed
            String identity = imageUri + "|" + pfd.getStatSize();
            File mark = new File(staging, ".source");
            if (staging.exists() && !identity.equals(readText(mark))) M3dwGame.deleteTree(staging);
            if (!staging.isDirectory() && !staging.mkdirs()) throw new Problem(getString(R.string.import_err_write));
            writeText(mark, identity);
            long have = sizeOf(staging), free = new StatFs(staging.getPath()).getAvailableBytes();
            if (bytes - have + SPACE_MARGIN > free)
                throw new Problem(getString(R.string.import_err_space, gb(bytes - have + SPACE_MARGIN), gb(free)), true);

            // code/ first: a dump of another build is refused before the 1.7 GB of content are written
            extract(path, keys, want, staging, new String[] {"code/*"}, false, R.string.import_step_code, keyUris.isEmpty());
            publish(new Status(State.RUNNING, getString(R.string.import_step_check), 0, -1, null));
            File rpx = new File(staging, "code/" + M3dwGame.RPX);
            if (!rpx.isFile()) throw new Problem(getString(R.string.import_err_norpx));
            String expected = M3dwGame.expectedSha256(this), actual = M3dwGame.sha256(rpx);
            if (!expected.isEmpty() && !expected.equals(actual)) {
                String label = null;
                for (M3dwGame.Build b : M3dwGame.KNOWN) if (b.rpxSha256.equals(actual)) label = b.label;
                M3dwGame.deleteTree(staging);
                throw new Problem(label != null ? getString(R.string.import_err_build_known, label, want != null ? want.label : expected.substring(0, 12))
                                                : getString(R.string.import_err_build));
            }
            extract(path, keys, want, staging, new String[0], "wua".equals(info[2]), R.string.import_step_extract, keyUris.isEmpty());
            publish(new Status(State.RUNNING, getString(R.string.import_step_finish), 0, -1, null));
            swap(staging);
            forget(this);
            Log.i("sm3dw-import", "import complete");
            publish(new Status(State.DONE, getString(R.string.import_done), 1, 1, null));
        } catch (Problem p) {
            if (cancelled) cancelledNow(staging);
            else if (stopping) publish(new Status(State.IDLE, "", 0, 0, null));  // the job stays: it resumes later
            else {
                forget(this);
                if (!p.keep) M3dwGame.deleteTree(staging);
                Log.w("sm3dw-import", "import failed: " + p.getMessage());
                publish(new Status(State.FAILED, "", 0, 0, p.getMessage()));
            }
        } catch (IOException | RuntimeException e) {
            if (cancelled) cancelledNow(staging);
            else {
                Log.w("sm3dw-import", "import failed", e);
                publish(new Status(State.FAILED, "", 0, 0, getString(R.string.import_err_read, String.valueOf(e.getMessage()))));
            }
        }
    }

    private void cancelledNow(File staging) {
        forget(this);
        M3dwGame.deleteTree(staging);  // an explicit cancel gives the space back
        publish(new Status(State.CANCELLED, "", 0, 0, null));
    }

    private ParcelFileDescriptor open(Uri uri) throws Problem {
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new Problem(getString(R.string.import_err_open));
            return pfd;
        } catch (IOException | SecurityException e) {
            throw new Problem(getString(R.string.import_err_open));
        }
    }

    private byte[][] readKeys(List<Uri> uris) throws Problem {
        byte[][] out = new byte[uris.size()][];
        for (int i = 0; i < uris.size(); ++i) {
            try (InputStream in = getContentResolver().openInputStream(uris.get(i))) {
                if (in == null) throw new IOException("null stream");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (b.size() + n > KEY_FILE_LIMIT) throw new Problem(getString(R.string.import_err_keyfile));
                    b.write(buf, 0, n);
                }
                out[i] = b.toByteArray();
            } catch (IOException | SecurityException e) {
                throw new Problem(getString(R.string.import_err_keyopen));
            }
        }
        return out;
    }

    /** The extractor's result code as the player's message. */
    private void check(String[] r, boolean noKeys) throws Problem {
        int code = Integer.parseInt(r[0]);
        if (code == 0) return;
        Log.w("sm3dw-import", "extractor code " + code + ": " + r[1]);
        switch (code) {
            case 3: throw new Problem(getString(noKeys ? R.string.import_err_needkeys : R.string.import_err_disckey));
            case 5:
            case 6: throw new Problem(getString(R.string.import_err_commonkey));
            case 8: throw new Problem(getString(R.string.import_err_damaged));
            case 9: throw new Problem(getString(R.string.import_err_write), true);
            case 10: throw new Problem(getString(R.string.import_err_notitle));
            case 11: throw new Problem("cancelled");
            default: throw new Problem(getString(R.string.import_err_format));
        }
    }

    private void extract(String path, byte[][] keys, M3dwGame.Build want, File out, String[] only, boolean verify, int stepRes,
                         boolean noKeys) throws Problem {
        final String step = getString(stepRes), verifying = getString(R.string.import_step_verify);
        publish(new Status(State.RUNNING, step, 0, -1, null));
        String[] r = GameImportNative.extract(path, keys, want != null ? want.titleId : "", out.getPath(), only, verify,
                (phase, done, total) -> {
                    Status s = new Status(State.RUNNING, phase == 1 ? verifying : step, done, total, null);
                    publish(s);
                    long now = System.currentTimeMillis();
                    if (now - lastNotified > 1000) {
                        lastNotified = now;
                        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                                notification(getString(R.string.import_notify_title), s.step, Math.round(s.fraction() * 100)));
                    }
                });
        check(r, noKeys);
    }

    /** files/game.import becomes files/game; the old game (a re-import) goes, its shader cache stays. */
    private void swap(File staging) throws Problem {
        File game = M3dwGame.gameDir(this), old = new File(game.getParentFile(), "game.old");
        M3dwGame.deleteTree(old);
        File cache = new File(game, "shadercache"), keep = new File(staging, "shadercache");
        if (cache.isDirectory() && !keep.exists() && !cache.renameTo(keep)) Log.w("sm3dw-import", "shader cache not carried over");
        new File(staging, ".source").delete();
        if (game.exists() && !game.renameTo(old)) throw new Problem(getString(R.string.import_err_write));
        if (!staging.renameTo(game)) {
            old.renameTo(game);
            throw new Problem(getString(R.string.import_err_write));
        }
        M3dwGame.deleteTree(old);
    }

    // ---- small helpers ----

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += sizeOf(k);
        return n;
    }

    private static String gb(long bytes) { return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9); }

    private static String readText(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    private static void writeText(File f, String s) throws Problem {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new Problem(e.getMessage()); }
    }

    /** percent: 0..100 a bar; -1 an indeterminate one; -2 none (a finished notice). */
    private Notification notification(String title, String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.import_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, GameSetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true);
        if (percent >= -1) {
            b.setOngoing(true).setProgress(100, Math.max(0, percent), percent < 0);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GameImportService.class).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.import_cancel), stop).build());
        } else {
            b.setAutoCancel(true).setSmallIcon(android.R.drawable.stat_sys_download_done);
        }
        return b.build();
    }
}
