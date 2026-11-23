package io.github.belmantegu.ds.core;

import java.util.ArrayList;
import java.util.List;

/** The in-game sheet as data (pure Java): its title, the line under it, the state pill's text and its items. */
public final class SheetSpec {
    /** An item: a key the game knows it by, a Lucide icon and its label. */
    public static final class Item {
        public final String key, icon, label;

        public Item(String key, String icon, String label) {
            this.key = key;
            this.icon = icon;
            this.label = label;
        }
    }

    public String title = "", subtitle = "", pill = "";
    public final List<Item> items = new ArrayList<>();

    public SheetSpec item(String key, String icon, String label) {
        items.add(new Item(key, icon, label));
        return this;
    }
}