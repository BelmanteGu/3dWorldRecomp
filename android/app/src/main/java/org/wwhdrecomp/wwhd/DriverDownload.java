package org.wwhdrecomp.wwhd;

import android.app.Activity;

import io.github.belmantegu.ds.android.DsDialog;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Options > Driver > Baixar um driver (from the Diablo III port, in the design system's dialogs): pick a
 * repository, then a package of its latest releases; it is downloaded (HTTPS, at most DriverStore.MAX_ZIP_BYTES,
 * SHA-256 checked when the release lists one), installed by DriverStore (the same checks as an imported .zip)
 * and selected for the next start. The runtime's probe (driver_store.cpp) goes back to the system driver if
 * the game dies before 120 frames with it.
 */
final class DriverDownload {
    /** The driver that was installed and selected. */
    interface Done {
        void installed(DriverStore.Driver d);
    }

    private final Activity a;
    private final Done done;

    private DriverDownload(Activity a, Done done) {
        this.a = a;
        this.done = done;
    }

    static void start(Activity a, Done done) {
        new DriverDownload(a, done).sources();
    }

    /** A string resource by name (the APK's resources may sit under the namespace, not the application id). */
    static int string(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "string", a.getPackageName());
        return id != 0 ? id : a.getResources().getIdentifier(name, "string", "org.wwhdrecomp.wwhd");
    }

    private String failure(IOException e) {
        if (e instanceof DriverCatalog.Failure) {
            DriverCatalog.Failure f = (DriverCatalog.Failure) e;
            int id = string(a, "catalog_err_" + f.kind);
            if (id != 0) return a.getString(id) + (f.code > 0 ? " (HTTP " + f.code + ")" : "");
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void sources() {
        String[] names = new String[DriverCatalog.SOURCES.length];
        for (int i = 0; i < names.length; ++i) {
            DriverCatalog.Source s = DriverCatalog.SOURCES[i];
            int note = string(a, s.note);
            names[i] = s.title + (note == 0 ? "" : " — " + a.getString(note));
        }
        DsDialog.with(a).icon("download").title(a.getString(R.string.catalog_title)).message(a.getString(R.string.catalog_where))
                .choices(names, -1, i -> releases(DriverCatalog.SOURCES[i])).button(a.getString(android.R.string.cancel), null).show();
    }

    private void releases(DriverCatalog.Source src) {
        final DsDialog.Handle wait = DsDialog.progress(a, src.title, a.getString(R.string.catalog_asking));
        wait.setProgress(-1);
        new Thread(() -> {
            List<DriverCatalog.Item> items = null;
            String error = null;
            try {
                items = DriverCatalog.fetch(src);
            } catch (IOException e) {
                error = failure(e);
            }
            final List<DriverCatalog.Item> found = items;
            final String message = error;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (message != null) {
                    DsDialog.alert(a, src.title, message, null);
                } else if (found.isEmpty()) {
                    DsDialog.alert(a, src.title, a.getString(R.string.catalog_empty), null);
                } else {
                    String[] labels = new String[found.size()];
                    for (int i = 0; i < labels.length; ++i) labels[i] = DriverCatalog.label(found.get(i));
                    DsDialog.with(a).icon("download").title(src.title).wide().note(a.getString(R.string.catalog_pick))
                            .choices(labels, -1, i -> download(found.get(i))).button(a.getString(android.R.string.cancel), null).show();
                }
            });
        }, "driver-catalog").start();
    }

    private void download(DriverCatalog.Item it) {
        final DsDialog.Handle bar = DsDialog.progress(a, a.getString(R.string.catalog_downloading), it.asset);
        bar.setProgress(0);
        new Thread(() -> {
            DriverStore.Driver installed = null;
            String error = null;
            File f = new File(a.getCacheDir(), "driver-download.zip");
            try {
                DriverCatalog.download(it, f, (got, total) -> a.runOnUiThread(() -> {
                    bar.setProgress(total > 0 ? (float) got / total : -1);
                    bar.setMessage(String.format(Locale.ROOT, "%s\n%.1f / %.1f MB", it.asset, got / 1048576.0, total / 1048576.0));
                }));
                a.runOnUiThread(() -> {
                    bar.setProgress(-1);
                    bar.setMessage(a.getString(R.string.busy_driver));
                });
                installed = new DriverStore(a).importFile(f);
            } catch (IOException e) {
                error = failure(e);
            } finally {
                if (f.exists() && !f.delete()) f.deleteOnExit();
            }
            final DriverStore.Driver d = installed;
            final String message = error;
            a.runOnUiThread(() -> {
                bar.dismiss();
                if (message != null) {
                    DsDialog.alert(a, a.getString(R.string.catalog_failed), message, null);
                    return;
                }
                try {
                    new DriverStore(a).select(d.id);
                } catch (IOException e) {
                    DsDialog.alert(a, a.getString(R.string.catalog_failed), String.valueOf(e.getMessage()), null);
                    return;
                }
                done.installed(d);
                DsDialog.alert(a, a.getString(R.string.catalog_installed), a.getString(R.string.say_driver_imported, d.label()), null);
            });
        }, "driver-download").start();
    }
}
