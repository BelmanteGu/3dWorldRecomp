package io.github.belmantegu.ds.android;

import io.github.belmantegu.ds.core.*;

import io.github.belmantegu.ds.core.DsMetrics;
import io.github.belmantegu.ds.core.OptionsEngine;

import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the design-system control of an Options row from its kind and builds it: the Profile row is
 * ChoiceCards, a switch is Toggle, a range is SliderRow, a short chooser is Segmented (a long or open
 * one, the driver, is SelectBox), an action is GhostButton with a caption, an info line is plain text.
 * A control only draws and reports taps through the Sink; OptionsEngine keeps the values.
 */
public final class OptionsControls {
    private OptionsControls() {}

    /** What a control asks of the panel (which steps the model and repaints). */
    public interface Sink {
        /** Steps the row's chooser / switch by d (a tap on an action: d = 1). */
        void step(OptionsEngine.Row r, int d);

        /** Sets a range row's value. */
        void range(OptionsEngine.Row r, int value);

        /** Moves the focus to the row (a tap that changes nothing). */
        void focus(OptionsEngine.Row r);

        /** A model token ("@name" or a literal) as text. */
        String text(String token);
    }

    /** A row's control: its view, and update() to repaint from the model. */
    public interface Control {
        View view();

        void update();
    }

    public static Control make(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        switch (r.kind) {
            case BOOL: return toggle(k, model, r, sink);
            case RANGE: return slider(k, model, r, sink);
            case ACTION: return ghost(k, model, r, sink);
            case INFO: return info(k, model, r, sink);
            default: break;
        }
        if (r.spec.control.equals("cards")) return cards(k, model, r, sink);
        if (r.spec.control.equals("select")) return select(k, model, r, sink);
        List<String> tokens = model.choiceTokens(r);
        return tokens.size() >= 2 && tokens.size() <= 4 ? segmented(k, model, r, sink) : select(k, model, r, sink);
    }

    private static Control toggle(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final Toggle t = new Toggle(k);
        t.setOnClickListener(v -> sink.step(r, 1));
        return new Control() {
            @Override public View view() { return t; }
            @Override public void update() {
                t.setOn(model.boolValue(r));
                t.setMuted(model.disabled(r));
            }
        };
    }

    private static Control slider(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final SliderRow s = new SliderRow(k);
        s.setMax((r.max - r.min) / r.step);
        s.setOnChange(p -> sink.range(r, r.min + p * r.step));
        return new Control() {
            @Override public View view() { return s; }
            @Override public void update() {
                s.setProgress((model.intValue(r) - r.min) / r.step);
                s.setValue(sink.text(model.valueText(r)));
            }
        };
    }

    private static Control ghost(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final GhostButton g = new GhostButton(k);
        g.setOnClickListener(v -> sink.step(r, 1));
        return new Control() {
            @Override public View view() { return g; }
            @Override public void update() {
                String token = model.valueText(r);
                g.set(r.spec.icon.isEmpty() ? "chevron-right" : r.spec.icon, sink.text(token));
                g.setMuted(model.disabled(r));
            }
        };
    }

    private static Control info(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final TextView v = k.label("", DsMetrics.T_BODY, k.t.fg);
        v.setTypeface(k.body, android.graphics.Typeface.BOLD);
        return new Control() {
            @Override public View view() { return v; }
            @Override public void update() { v.setText(sink.text(model.valueText(r))); }
        };
    }

    private static Control cards(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final ChoiceCards c = new ChoiceCards(k);
        c.setOnPick(i -> pick(model, r, sink, i));
        return new Control() {
            @Override public View view() { return c; }
            @Override public void update() {
                List<ChoiceCards.Card> items = new ArrayList<>();
                int n = Math.min(r.spec.cards.size(), model.choiceTokens(r).size());  // a choice past the cards (custom) has no card
                List<String> tokens = model.choiceTokens(r);
                for (int i = 0; i < n; ++i) {
                    items.add(new ChoiceCards.Card(r.spec.cards.get(i).icon, sink.text(tokens.get(i)), sink.text(model.env().cardSub(r.key, i))));
                }
                int at = model.choiceIndex(r);
                c.set(items, at < n ? at : -1);
                c.setMuted(model.disabled(r));
            }
        };
    }

    private static Control segmented(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final Segmented s = new Segmented(k);
        s.setOnPick(i -> pick(model, r, sink, i));
        return new Control() {
            @Override public View view() { return s; }
            @Override public void update() {
                List<String> labels = new ArrayList<>();
                for (String t : model.choiceTokens(r)) labels.add(sink.text(t));
                s.set(labels, model.choiceIndex(r));
                s.setMuted(model.disabled(r));
            }
        };
    }

    private static Control select(DsKit k, OptionsEngine model, OptionsEngine.Row r, Sink sink) {
        final SelectBox b = new SelectBox(k);
        b.setIcon(r.spec.icon.isEmpty() ? null : r.spec.icon);
        b.setOnClickListener(v -> sink.step(r, 1));
        return new Control() {
            @Override public View view() { return b; }
            @Override public void update() {
                b.setValue(sink.text(model.valueText(r)));
                b.setMuted(model.disabled(r));
            }
        };
    }

    /** A tap on the i-th value: step there (the same wrap-around steps the pad uses). */
    private static void pick(OptionsEngine model, OptionsEngine.Row r, Sink sink, int i) {
        if (model.disabled(r)) {
            sink.step(r, 1);  // changes nothing; the panel says why
            return;
        }
        int d = i - model.choiceIndex(r);
        if (d == 0) sink.focus(r);
        else sink.step(r, d);
    }
}
