package io.github.belmantegu.ds.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small Lucide icon in a colour (the notice's, the dialog's title). Not clickable. */
public final class IconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String name;
    private int color;

    public IconView(Context context, String name, int color) {
        super(context);
        this.name = name;
        this.color = color;
    }

    public void set(String newName, int newColor) {
        if (!newName.equals(name) || newColor != color) {
            name = newName;
            color = newColor;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        Icons.draw(c, name, 0, 0, Math.min(getWidth(), getHeight()), color, paint);
    }
}