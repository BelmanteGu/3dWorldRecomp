package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen as data (pure Java): the texts already in the player's language, the logo asset and the
 * menu items. The generic HomeScreen draws it; the game only declares it and handles the taps.
 */
public final class HomeSpec {
    /** A menu item: a key the game knows it by, a Lucide icon, its label and an optional small hint (".zip"). */
    public static final class Item {
        public final String key, icon, label, hint;

        public Item(String key, String icon, String label, String hint) {
            this.key = key;
            this.icon = icon;
            this.label = label;
            this.hint = hint == null ? "" : hint;
        }
    }

    /** The line on top ("DIABLO III RECOMP"), the subtitle under the logo, the title shown when there is no logo art. */
    public String kicker = "", subtitle = "", title = "";
    /** The asset path of the logo bitmap ("launcher/logo.png"); "" for none. */
    public String logoAsset = "";
    /** The version line (bottom right), the pad hints' captions and the Details button's. */
    public String version = "", hintSelect = "", hintBack = "", details = "";
    /** True: the menu in a column on the right and the logo, the extras and the state on the left (the split layout); false: the menu down the left edge. */
    public boolean menuRight;
    public final List<Item> items = new ArrayList<>();

    public HomeSpec item(String key, String icon, String label, String hint) {
        items.add(new Item(key, icon, label, hint));
        return this;
    }
}