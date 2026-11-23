package org.wwhdrecomp.wwhd;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The game files of the Super Mario 3D World build: where they live (files/game: code, content, meta, read by
 * the runtime as they are on the disc), whether they are there, and which game this APK was built from. The
 * APK holds only the recompiled code (libmain.so), made from one exact RedCarpet.rpx; the player's own dump
 * must contain that same file (its SHA-256, R.string.game_rpx_sha256, set by android/build_sm3dw_apk.sh), or
 * the code would not match the data.
 */
final class M3dwGame {
    private M3dwGame() {}

    static final String RPX = "RedCarpet.rpx";

    /** A build of the game: its title id, the SHA-256 of its RedCarpet.rpx and the words that name it. */
    static final class Build {
        final String titleId, rpxSha256, label;

        Build(String titleId, String rpxSha256, String label) {
            this.titleId = titleId;
            this.rpxSha256 = rpxSha256;
            this.label = label;
        }
    }

    /** The builds tools/recomp/builds.py knows (the messages can then name what the player has). */
    static final Build[] KNOWN = {
        new Build("0005000010145d00", "a25432104adaefd980f9e24559b59a3d79781e68980fd37df6de206fff744937", "Europa (EUR), Rev 1"),
        new Build("0005000010145c00", "813d906365860eb6ef4644e8380983db382cbaefd951f9a89bd3d2c84eb4aaad", "EUA (USA), versão 1"),
    };

    /** The other regions' title ids, for the message only (Japan). */
    static String regionOf(String titleId) {
        for (Build b : KNOWN) if (b.titleId.equalsIgnoreCase(titleId)) return b.label;
        if ("0005000010106100".equalsIgnoreCase(titleId)) return "Japão (JPN)";
        return null;
    }

    /** The SHA-256 of the RedCarpet.rpx this APK's code was made from ("" when the build did not say). */
    static String expectedSha256(Context c) {
        int id = c.getResources().getIdentifier("game_rpx_sha256", "string", c.getPackageName());
        if (id == 0) id = c.getResources().getIdentifier("game_rpx_sha256", "string", "org.wwhdrecomp.wwhd");
        return id == 0 ? "" : c.getString(id).trim().toLowerCase(Locale.ROOT);
    }

    /** The build this APK expects (KNOWN by its SHA-256), or null when unknown. */
    static Build expected(Context c) {
        String sha = expectedSha256(c);
        for (Build b : KNOWN) if (b.rpxSha256.equals(sha)) return b;
        return null;
    }

    /** The ds design system in the Mario theme with the app's fonts (the menu, the import screen). */
    static void installDesign() {
        io.github.belmantegu.ds.android.DsConfig.install(io.github.belmantegu.ds.core.GameTheme.mario(),
                new io.github.belmantegu.ds.android.DsConfig.Fonts() {
                    @Override public android.graphics.Typeface title(Context c) {
                        return android.graphics.Typeface.create(c.getResources().getFont(R.font.baloo2), 800, false);
                    }
                    @Override public android.graphics.Typeface body(Context c) {
                        return c.getResources().getFont(R.font.atkinson_regular);
                    }
                });
    }

    static File gameDir(Context c) { return new File(c.getExternalFilesDir(null), "game"); }

    /** The import's working folder, renamed to game/ when complete. */
    static File stagingDir(Context c) { return new File(c.getExternalFilesDir(null), "game.import"); }

    /** True when files/game has the code, the content and meta/meta.xml (copied by adb or imported). */
    static boolean ready(Context c) {
        File g = gameDir(c);
        File[] content = new File(g, "content").listFiles();
        return new File(g, "code/" + RPX).isFile() && new File(g, "meta/meta.xml").isFile() && content != null && content.length > 0;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static boolean deleteTree(File f) {
        if (f == null) return true;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete() || !f.exists();
    }
}
