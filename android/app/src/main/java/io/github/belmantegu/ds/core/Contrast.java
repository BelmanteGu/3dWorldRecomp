package io.github.belmantegu.ds.core;

/** WCAG contrast of two opaque colours (pure Java): the themes' text pairs are checked with it on the JVM. */
public final class Contrast {
    private Contrast() {}

    /** WCAG relative luminance of an opaque colour. */
    public static double luminance(int argb) {
        double[] c = {(argb >> 16 & 255) / 255.0, (argb >> 8 & 255) / 255.0, (argb & 255) / 255.0};
        for (int i = 0; i < 3; ++i) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** WCAG contrast ratio (1..21) of two opaque colours. */
    public static double ratio(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }
}