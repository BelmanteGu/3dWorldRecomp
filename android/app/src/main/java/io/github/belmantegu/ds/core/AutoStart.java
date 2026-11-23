package io.github.belmantegu.ds.core;

/**
 * "Open game directly" (pure Java, JVM-tested): whether the launcher goes straight into the game. The rules keep the
 * player from ever being locked out of the home screen:
 *
 *  - only a cold start from the app's icon goes straight in; the home screen asked for from the game's pause menu
 *    (Home screen) or any other way in stays on the home screen;
 *  - only with the switch on and the game files in place (missing or damaged files: the home screen shows why);
 *  - an automatic start leaves a mark that the game clears once it has drawn frames for a while; a mark still there at
 *    the next start means the game died while starting: that start shows the home screen with a notice instead
 *    (and the one after tries again).
 */
public final class AutoStart {
    public enum Decision { HOME, START, HOME_AFTER_FAILURE }

    /** How long the game must run (in front, drawing) before an automatic start counts as a good one. */
    public static final long CONFIRM_MS = 15000;

    private AutoStart() {}

    /**
     * @param enabled       the switch (the manifest's app_autostart row)
     * @param ready         the game files are in place
     * @param coldLaunch    the launcher was opened from its icon (not from the game, not recreated)
     * @param homeRequested the game's pause menu asked for the home screen
     * @param lastFailed    the previous automatic start never confirmed
     */
    public static Decision decide(boolean enabled, boolean ready, boolean coldLaunch, boolean homeRequested, boolean lastFailed) {
        if (!enabled || !coldLaunch || homeRequested) return Decision.HOME;
        if (lastFailed) return Decision.HOME_AFTER_FAILURE;
        return ready ? Decision.START : Decision.HOME;
    }
}
