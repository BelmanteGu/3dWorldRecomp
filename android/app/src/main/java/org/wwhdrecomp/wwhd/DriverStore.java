package org.wwhdrecomp.wwhd;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The installed GPU drivers, in the runtime's own layout (runtime/src/gfx/vulkan/driver_store.cpp, read by
 * android_driver.cpp when the game starts): files/gpu-drivers/&lt;32 hex digits&gt;/ with meta.json and the
 * driver's .so, the chosen id in files/gpu-drivers/selected (no newline), each driver's pipeline cache in
 * caches/&lt;id&gt;. The runtime probes a newly chosen driver for 120 frames and goes back to the system
 * driver if the game died first. This class (from the Diablo III port's DriverStore) installs the packages a
 * player imports or downloads (DriverCatalog), lists, chooses and deletes them from the menu, without the
 * native library: a choice applies at the next start. Internal storage only: no other app can write there.
 */
final class DriverStore {
    /** An installed driver. */
    static final class Driver {
        final String id, name, version;

        Driver(String id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        String label() { return version.isEmpty() || name.contains(version) ? name : name + " " + version; }
    }

    static final long MAX_ZIP_BYTES = 200L << 20;
    private static final String META = "meta.json";

    private final Context context;

    DriverStore(Context context) {
        this.context = context;
    }

    File root() { return new File(context.getFilesDir(), "gpu-drivers"); }

    /** True on a Qualcomm phone with this APK's libadrenotools hooks: Turnip drives Adreno GPUs only. */
    boolean supported() {
        String soc = Build.SOC_MANUFACTURER;
        boolean adreno = DriverChecks.isQualcomm(soc, Build.HARDWARE) || new File("/sys/class/kgsl/kgsl-3d0").exists();
        return adreno && new File(context.getApplicationInfo().nativeLibraryDir, "libmain_hook.so").isFile();
    }

    /** The GPU's name from the kernel ("Adreno740v2"), or "". */
    static String gpuModel() {
        try (InputStream in = new FileInputStream("/sys/class/kgsl/kgsl-3d0/gpu_model")) {
            return new String(readAll(in, 256), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    static boolean idOk(String id) { return id != null && id.matches("[0-9a-f]{32}"); }

    List<Driver> list() {
        List<Driver> out = new ArrayList<>();
        File[] dirs = root().listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            Driver driver = idOk(d.getName()) ? read(d) : null;  // "install-*": an import in progress
            if (driver != null) out.add(driver);
        }
        return out;
    }

    /** The driver with this id, or null (not installed, or "" for the system driver). */
    Driver find(String id) { return idOk(id) ? read(new File(root(), id)) : null; }

    /** The chosen driver's id, "" for the system driver. */
    String selected() {
        File f = new File(root(), "selected");
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            String id = new String(readAll(in, 64), StandardCharsets.UTF_8).trim();
            return idOk(id) ? id : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Chooses a driver for the next start ("" = the system driver); written as the runtime writes it. */
    void select(String id) throws IOException {
        if (!id.isEmpty() && find(id) == null) throw new IOException("driver not installed");
        File dir = root();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, "selected.tmp"), f = new File(dir, "selected");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(id.getBytes(StandardCharsets.US_ASCII));
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
        new File(dir, "probing").delete();  // a fresh choice gets a fresh probe
    }

    /** Deletes a driver and its pipeline cache; a chosen one gives way to the system driver. */
    void delete(String id) throws IOException {
        if (!idOk(id)) throw new IOException("invalid driver id");
        if (id.equals(selected())) select("");
        M3dwGame.deleteTree(new File(root(), id));
        M3dwGame.deleteTree(new File(root(), "caches/" + id));
    }

    private static Driver read(File dir) {
        File meta = new File(dir, META);
        if (!dir.isDirectory() || !meta.isFile()) return null;
        try {
            String text = new String(readAll(new FileInputStream(meta), 1 << 14), StandardCharsets.UTF_8);
            DriverChecks.Meta m = DriverChecks.parseMeta(text);
            if (DriverChecks.validate(m, Build.VERSION.SDK_INT) != null || !new File(dir, m.libraryName).isFile()) return null;
            String version = DriverChecks.stringField(text, "driverVersion");
            if (version.isEmpty()) version = m.packageVersion;
            return new Driver(dir.getName(), m.name, version);
        } catch (IOException e) {
            return null;
        }
    }

    /** Imports a driver .zip the player picked (copied to the cache first). Off the UI thread. */
    Driver importZip(Uri uri) throws IOException {
        File zip = File.createTempFile("driver", ".zip", context.getCacheDir());
        try {
            long total = 0;
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(zip)) {
                if (in == null) throw new IOException(context.getString(R.string.driver_err_open));
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) throw new IOException(context.getString(R.string.driver_err_big));
                    out.write(buf, 0, n);
                }
            }
            return importFile(zip);
        } finally {
            if (!zip.delete()) zip.deleteOnExit();
        }
    }

    /**
     * Installs a driver package already on disk (a picked .zip's copy, or a catalog download): meta.json (at the
     * root or in one folder) with the fields the runtime requires, an arm64 library, safe entry names. Only
     * meta.json and the library are kept (the runtime accepts nothing else but licence files), in a temporary
     * folder renamed to the id (the package's SHA-256: importing the same file again replaces it). Throws with a
     * message the player can read. Off the UI thread.
     */
    Driver importFile(File zip) throws IOException {
        File tmp = null;
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry metaEntry = null;
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (!DriverChecks.safeEntryName(name)) throw new IOException(context.getString(R.string.driver_err_format));
                boolean isMeta = name.equals(META) || name.endsWith("/" + META);
                if (isMeta && (metaEntry == null || depth(name) < depth(metaEntry.getName()))) metaEntry = entry;
            }
            if (metaEntry == null) throw new IOException(context.getString(R.string.driver_err_nometa));
            String folder = metaEntry.getName().substring(0, metaEntry.getName().length() - META.length());
            String metaText = new String(readAll(z.getInputStream(metaEntry), 1 << 14), StandardCharsets.UTF_8);
            DriverChecks.Meta meta = DriverChecks.parseMeta(metaText);
            String problem = DriverChecks.validate(meta, Build.VERSION.SDK_INT);
            if (problem == null && !metaText.contains("\"minApi\"")) problem = "meta.json has no minApi";
            if (problem != null) throw new IOException(context.getString(R.string.driver_err_meta, problem));
            ZipEntry lib = z.getEntry(folder + meta.libraryName);
            if (lib == null) throw new IOException(context.getString(R.string.driver_err_nolib, meta.libraryName));
            if (lib.getSize() > DriverChecks.MAX_LIBRARY_BYTES) throw new IOException(context.getString(R.string.driver_err_big));
            byte[] head = new byte[64];
            int got;
            try (InputStream in = z.getInputStream(lib)) {
                got = in.readNBytes(head, 0, head.length);
            }
            if (!DriverChecks.isAarch64Elf(head, got)) throw new IOException(context.getString(R.string.driver_err_arch, meta.libraryName));

            String id = hex(sha256(zip)).substring(0, 32);
            File root = root();
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
            tmp = new File(root, "install-" + id);
            M3dwGame.deleteTree(tmp);
            if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
            copy(z.getInputStream(metaEntry), new File(tmp, META));
            File so = new File(tmp, meta.libraryName);
            copy(z.getInputStream(lib), so);
            so.setReadOnly();  // Android 14 wants loaded code read-only
            File dest = new File(root, id);
            M3dwGame.deleteTree(dest);
            if (!tmp.renameTo(dest)) throw new IOException("cannot install into " + dest);
            tmp = null;
            String version = DriverChecks.stringField(metaText, "driverVersion");
            return new Driver(id, meta.name, version.isEmpty() ? meta.packageVersion : version);
        } catch (java.util.zip.ZipException e) {
            throw new IOException(context.getString(R.string.driver_err_format));
        } finally {
            if (tmp != null) M3dwGame.deleteTree(tmp);
        }
    }

    private static int depth(String name) {
        int n = 0;
        for (int i = 0; i < name.length(); ++i) if (name.charAt(i) == '/') n++;
        return n;
    }

    private static void copy(InputStream in, File to) throws IOException {
        try (InputStream src = in; OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            for (int n; (n = src.read(buf)) > 0; ) {
                total += n;
                if (total > DriverChecks.MAX_LIBRARY_BYTES) throw new IOException("the library is too big");
                out.write(buf, 0, n);
            }
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream src = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = src.read(buf)) > 0; ) {
                if (out.size() + n > limit) throw new IOException("file too big");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return sb.toString();
    }
}
