package io.github.belmantegu.ds.core;

import java.util.List;

/** What the engine cannot know by itself: visibility flags, texts that are not settings, live lists, why a row is muted. */
public interface OptionsEnv {
    /** A visibility flag named by OptionSpec.when ("home", "game", "feedback"...). */
    boolean flag(String name);

    /** The text of a row whose value is not in the settings (a driver's name, version, build), or null. */
    String external(String key);

    /** True when this restart-only setting differs from the running game's. */
    boolean restartPending(String key);

    /** The values of a dynamic chooser (OptionSpec.dynamic). */
    List<OptionSpec.Choice> choices(String key);

    /** Why a row cannot be used, as a string-resource name; "" for none-to-say, null when it can be used. */
    String disabledReason(String key);

    /** The second line of the i-th card of a cards control (a token, "@names" allowed). */
    String cardSub(String key, int index);
}