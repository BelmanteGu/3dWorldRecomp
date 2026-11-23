package org.wwhdrecomp.wwhd;

import java.io.File;

/** Same default as native XDG_CONFIG_HOME=<SDL external files>/config, then /wwhd. */
public final class AndroidUserDirectories {
    private AndroidUserDirectories() { }

    public static File config(File externalFiles) {
        if (externalFiles == null) throw new IllegalStateException("External app files unavailable");
        // the Super Mario 3D World build (package org.sm3dwrecomp.sm3dw) keeps its own folder, as the native side does
        String game = externalFiles.getPath().contains("sm3dw") ? "sm3dw" : "wwhd";
        return new File(externalFiles, "config/" + game);
    }

    public static File shaderPrebuild(File externalFiles) {
        return new File(config(externalFiles), "shader-prebuild");
    }
}
