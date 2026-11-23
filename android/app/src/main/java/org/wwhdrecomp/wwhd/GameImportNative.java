package org.wwhdrecomp.wwhd;

/**
 * The extractor (libm3dwimport.so, tools/wudextract/game_import_jni.cpp): reads a Wii U disc image
 * (.wux/.wud, with the key files' contents) or a Cemu archive (.wua) through a path such as
 * /proc/self/fd/N. Results are {code, message, format, title id, files, bytes}; code 0 is success, the
 * others are wwhd_extract.cpp's (3 disc key, 5/6 common key, 7 unreadable or not a Wii U image,
 * 8 damaged, 9 cannot write, 10 no game title) and 11 cancelled. Blocking: call off the UI thread.
 */
final class GameImportNative {
    private GameImportNative() {}

    static { System.loadLibrary("m3dwimport"); }

    interface Progress {
        /** phase 1: checking an archive's SHA-256; 2: extracting. Bytes. */
        void update(int phase, long done, long total);
    }

    static native String[] inspect(String path, byte[][] keyFiles, String wantTitle);

    static native String[] extract(String path, byte[][] keyFiles, String wantTitle, String outDir, String[] only,
                                   boolean verify, Progress progress);

    static native void setCancelled(boolean cancelled);
}
