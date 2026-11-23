package io.github.belmantegu.ds.core;

/**
 * Where the option values live (the game's settings). The engine only reads and writes strings by
 * option key; what a value means, and any side effect of changing it (a preset moving the others), is
 * the game's. Pure Java.
 */
public interface ValueStore {
    /** The current value of an option as the spec's value string ("1" / "0" for a switch). */
    String get(String key);

    /** Sets a value (and saves it). */
    void set(String key, String value);

    /** "Restore defaults" of a tab done by the game; false lets the engine set every row to its default. */
    boolean resetTab(String tabKey);
}