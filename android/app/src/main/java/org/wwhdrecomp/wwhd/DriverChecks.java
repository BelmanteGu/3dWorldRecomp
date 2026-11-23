package org.wwhdrecomp.wwhd;

import java.util.Locale;

/**
 * The checks on an imported GPU driver package (from the Diablo III port): a .zip with a
 * meta.json and the driver's .so (the AdrenoTools format, which Yuzu, Dolphin,
 * PPSSPP and Winlator share). Pure Java, no android imports; DriverStore does the file work.
 */
final class DriverChecks {
    static final int EM_AARCH64 = 0xB7;
    /** The biggest driver .so we accept (a Turnip build is about 10 MB). */
    static final long MAX_LIBRARY_BYTES = 150L << 20;

    /** The fields of meta.json the app uses. */
    static final class Meta {
        String name = "", libraryName = "", packageVersion = "", description = "";
        int schemaVersion, minApi;
    }

    private DriverChecks() {}

    /**
     * Reads a flat meta.json (string and number values) without org.json.
     * Missing fields keep their empty defaults; validate() rejects those.
     */
    static Meta parseMeta(String json) {
        Meta m = new Meta();
        m.name = stringField(json, "name");
        m.libraryName = stringField(json, "libraryName");
        m.packageVersion = stringField(json, "packageVersion");
        m.description = stringField(json, "description");
        m.schemaVersion = intField(json, "schemaVersion");
        m.minApi = intField(json, "minApi");
        return m;
    }

    // The raw text after `"key":`, or null.
    private static String valueStart(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        while (at >= 0) {
            int i = at + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                return json.substring(i);
            }
            at = json.indexOf(needle, at + 1);
        }
        return null;
    }

    static String stringField(String json, String key) {
        String v = valueStart(json, key);
        if (v == null || v.isEmpty() || v.charAt(0) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < v.length(); ++i) {
            char c = v.charAt(i);
            if (c == '"') return sb.toString();
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                sb.append(c);
            }
        }
        return "";  // unterminated
    }

    static int intField(String json, String key) {
        String v = valueStart(json, key);
        if (v == null) return 0;
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        if (end == 0 || end > 9) return 0;
        return Integer.parseInt(v.substring(0, end));
    }

    /** Null when the package is usable on this device, else the reason (an English sentence). */
    static String validate(Meta m, int sdkInt) {
        if (m.schemaVersion != 1) return "unsupported meta.json schemaVersion " + m.schemaVersion;
        if (m.name.isEmpty()) return "meta.json has no name";
        if (m.libraryName.isEmpty()) return "meta.json has no libraryName";
        if (!safeLibraryName(m.libraryName)) return "meta.json libraryName is not a plain file name";
        if (m.minApi > sdkInt) return "the driver needs Android API " + m.minApi + " (this phone: " + sdkInt + ")";
        return null;
    }

    /** A file name in the package's root: no path parts, ends with .so. */
    static boolean safeLibraryName(String n) {
        return safeEntryName(n) && n.indexOf('/') < 0 && n.endsWith(".so");
    }

    /** Zip-slip: an entry name that stays inside the target directory. */
    static boolean safeEntryName(String n) {
        if (n == null || n.isEmpty() || n.length() > 255) return false;
        if (n.startsWith("/") || n.indexOf('\\') >= 0 || n.indexOf('\0') >= 0 || n.indexOf(':') >= 0) return false;
        String[] parts = n.split("/", -1);
        for (int i = 0; i < parts.length; ++i) {
            // Only the last part may be empty (a directory entry "a/b/").
            if (parts[i].equals("..") || parts[i].equals(".") || (parts[i].isEmpty() && i != parts.length - 1)) return false;
        }
        return true;
    }

    /** True for a little-endian 64-bit ELF whose machine is AArch64. */
    static boolean isAarch64Elf(byte[] head, int length) {
        if (length < 20) return false;
        boolean magic = head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
        boolean class64 = head[4] == 2, little = head[5] == 1;
        int machine = (head[18] & 0xFF) | ((head[19] & 0xFF) << 8);
        return magic && class64 && little && machine == EM_AARCH64;
    }

    /** A folder name made of [a-z0-9._-] from the driver's name; never empty, never starts with a dot. */
    static String sanitizeId(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' ? c : '-');
            if (sb.length() >= 48) break;
        }
        String s = sb.toString().replaceAll("^[.-]+", "");
        return s.isEmpty() ? "driver" : s;
    }

    /** True when this phone has a Qualcomm SoC (the only place libadrenotools works). */
    static boolean isQualcomm(String socManufacturer, String hardware) {
        String soc = socManufacturer == null ? "" : socManufacturer.toLowerCase(Locale.ROOT);
        String hw = hardware == null ? "" : hardware.toLowerCase(Locale.ROOT);
        return soc.contains("qualcomm") || soc.equals("qti") || hw.contains("qcom");
    }
}
