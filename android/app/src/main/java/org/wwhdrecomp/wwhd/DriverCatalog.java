package org.wwhdrecomp.wwhd;

import io.github.belmantegu.ds.core.MiniJson;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The online driver catalog (from the Diablo III port, itself from the Hades port's DriverCatalog): the releases of a few public Turnip
 * repositories on GitHub. Their API lists the packages and, for newer releases, each file's SHA-256, which is
 * checked after the download. Only .zip / .adpkg packages are offered; DriverStore checks what is inside
 * (meta.json, an arm64 library, safe names) before anything is kept. No android imports: the parsing is
 * tested on the JVM, the requests are plain HttpURLConnection (run them off the UI thread).
 */
final class DriverCatalog {
    private DriverCatalog() {}

    /** A repository: "owner/name", the title shown and the string resource of its one-line note. */
    static final class Source {
        final String repo, title, note;

        Source(String repo, String title, String note) {
            this.repo = repo;
            this.title = title;
            this.note = note;
        }
    }

    static final Source[] SOURCES = {
        new Source("MrPurple666/purple-turnip", "Mr. Purple · Turnip", "catalog_note_purple"),
        new Source("Weab-chan/freedreno_turnip-CI", "Weab-chan · Turnip CI", "catalog_note_weab"),
        new Source("whitebelyash/AdrenoToolsDrivers", "whitebelyash · Turnip", "catalog_note_white"),
        new Source("K11MCH1/AdrenoToolsDrivers", "KIMCHI · Turnip", "catalog_note_kimchi"),
    };

    /** A downloadable package of a release. */
    static final class Item {
        String repo = "", release = "", asset = "", url = "", digest = "", date = "";
        long size;

        /** The SHA-256 the release lists ("" when it lists none). */
        String sha256() { return digest.startsWith("sha256:") ? digest.substring(7) : ""; }
    }

    /** A failed request: kind is "limit" (GitHub's rate limit), "http", "offline", "checksum" or "format" (strings catalog_err_<kind>). */
    static final class Failure extends IOException {
        final String kind;
        final int code;

        Failure(String kind, int code) {
            super(kind + (code > 0 ? " (HTTP " + code + ")" : ""));
            this.kind = kind;
            this.code = code;
        }
    }

    interface Progress {
        void update(long done, long total);
    }

    /** The packages of a releases listing (GitHub's JSON array), newest first, at most `max`. */
    static List<Item> parse(String repo, String json, int max) throws Failure {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (RuntimeException e) {
            throw new Failure("format", 0);
        }
        if (!(root instanceof List)) throw new Failure("format", 0);
        List<Item> out = new ArrayList<>();
        for (Object r : (List<?>) root) {
            if (!(r instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> rel = (Map<String, Object>) r;
            Object assets = rel.get("assets");
            if (!(assets instanceof List)) continue;
            String name = MiniJson.str(rel, "name", "");
            String tag = MiniJson.str(rel, "tag_name", "");
            String date = MiniJson.str(rel, "published_at", "");
            for (Object a : (List<?>) assets) {
                if (!(a instanceof Map)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> asset = (Map<String, Object>) a;
                String file = MiniJson.str(asset, "name", ""), low = file.toLowerCase(Locale.ROOT);
                if (!low.endsWith(".zip") && !low.endsWith(".adpkg")) continue;
                Item it = new Item();
                it.repo = repo;
                it.release = name.isEmpty() ? tag : name;
                it.asset = file;
                it.url = MiniJson.str(asset, "browser_download_url", "");
                it.size = (long) MiniJson.num(asset, "size", 0);
                it.digest = MiniJson.str(asset, "digest", "");
                it.date = date.contains("T") ? date.substring(0, date.indexOf('T')) : date;
                if (it.url.startsWith("https://") && out.size() < max) out.add(it);
            }
        }
        return out;
    }

    /** The one line a package shows in the list: its name (with the release when the file name lacks it), size, date, a check when it has a checksum. */
    static String label(Item it) {
        String name = it.asset.startsWith(it.release) || it.release.isEmpty() ? it.asset : it.release + " — " + it.asset;
        return name + " \u00b7 " + String.format(Locale.ROOT, "%.1f MB", it.size / 1048576.0) + (it.date.isEmpty() ? "" : " · " + it.date)
                + (it.sha256().isEmpty() ? "" : "  ✓");
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://")) throw new Failure("format", 0);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", "SM3DWRecomp-driver-catalog");
        h.setRequestProperty("Accept", "application/vnd.github+json");
        return h;
    }

    /** The newest packages of a repository, newest first. Blocking. */
    static List<Item> fetch(Source s) throws IOException {
        HttpURLConnection h;
        int code;
        try {
            h = open("https://api.github.com/repos/" + s.repo + "/releases?per_page=20");
            code = h.getResponseCode();
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            throw new Failure("offline", 0);
        }
        try {
            if (code == 403 || code == 429) throw new Failure("limit", code);
            if (code != 200) throw new Failure("http", code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = new BufferedInputStream(h.getInputStream())) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (b.size() > (8 << 20)) throw new Failure("format", 0);
                    b.write(buf, 0, n);
                }
            }
            return parse(s.repo, b.toString("UTF-8"), 25);
        } finally {
            h.disconnect();
        }
    }

    /** True when the hex SHA-256 of the download matches the one listed ("" listed: nothing to compare). */
    static boolean checksumOk(String listedHex, byte[] digest) {
        if (listedHex == null || listedHex.isEmpty()) return true;
        StringBuilder hex = new StringBuilder();
        for (byte x : digest) hex.append(String.format(Locale.ROOT, "%02x", x));
        return hex.toString().equalsIgnoreCase(listedHex);
    }

    /** Downloads a package into `out`, checking the size limit and the SHA-256 when the release lists one. Blocking. */
    static File download(Item it, File out, Progress p) throws IOException {
        HttpURLConnection h;
        int code;
        try {
            h = open(it.url);
            h.setRequestProperty("Accept", "application/octet-stream");
            code = h.getResponseCode();
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            throw new Failure("offline", 0);
        }
        try {
            if (code != 200) throw new Failure("http", code);
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : it.size;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = new BufferedInputStream(h.getInputStream()); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    done += n;
                    if (done > DriverStore.MAX_ZIP_BYTES) throw new Failure("format", 0);
                    o.write(buf, 0, n);
                    md.update(buf, 0, n);
                    p.update(done, total);
                }
            }
            if (!checksumOk(it.sha256(), md.digest())) {
                if (!out.delete()) out.deleteOnExit();
                throw new Failure("checksum", 0);
            }
            return out;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            h.disconnect();
        }
    }
}
