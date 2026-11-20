package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Mouse;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * Small drawing pieces shared by the campaign-map pages (the CO-OP panel, officer links, friend refits) and the
 * lobby's level-up cards: what was drawn where for clicks, toggle rows, portraits and skill icons. Plain GL through
 * Style; nothing of the game's own UI.
 */
final class Widgets {
    private Widgets() {}

    static final Color CARD = new Color(14, 30, 48, 235);
    static final Color CARD_ON = new Color(18, 48, 74, 240);
    static final Color CARD_EDGE = new Color(34, 64, 90, 255);
    static final Color ELITE = new Color(240, 197, 90);
    static final Color BACKDROP = new Color(2, 6, 12, 140);

    /** What was drawn where this frame, for the next frame's clicks: the topmost thing under the mouse wins. */
    static final class Hits {
        private final List<float[]> rects = new ArrayList<float[]>();
        private final List<String> ids = new ArrayList<String>();
        private boolean wasDown;

        void clear() { rects.clear(); ids.clear(); }

        /** A place that can be clicked ("" for one that only keeps clicks off the map under it). */
        void add(float x, float y, float w, float h, String id) {
            rects.add(new float[] { x, y, w, h });
            ids.add(id == null ? "" : id);
        }

        /** What is under the point, "" for a part of the panel that does nothing, or null for nothing of ours. */
        String at(float mx, float my) {
            for (int i = rects.size() - 1; i >= 0; i--) {
                float[] r = rects.get(i);
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) return ids.get(i);
            }
            return null;
        }

        boolean over(float mx, float my) { return at(mx, my) != null; }

        boolean empty() { return rects.isEmpty(); }

        /** A new left click this frame on something of ours (as drawn last frame): its id, else null. */
        String clicked() {
            boolean down;
            try { down = Mouse.isButtonDown(0); } catch (Throwable t) { down = false; }
            String out = null;
            if (down && !wasDown) out = at(Global.getSettings().getMouseX(), Global.getSettings().getMouseY());
            wasDown = down;
            return out == null || out.isEmpty() ? null : out;
        }
    }

    static boolean over(float x, float y, float w, float h) { return Style.over(x, y, w, h); }

    static void click() {
        try { Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f); } catch (Throwable t) { /* silent is fine */ }
    }

    /** A small labelled box (a tab, a toggle, a button); lit when on, brighter under the mouse. */
    static void box(float x, float y, float w, float h, String text, Color c, boolean on) {
        boolean over = over(x, y, w, h);
        Style.fill(x, y, w, h, on ? Style.alpha(c, 70) : over ? Style.alpha(c, 34) : Style.alpha(c, 12));
        Style.outline(x, y, w, h, on ? c : over ? Style.alpha(c, 200) : Style.alpha(c, 90));
        String t = text.toUpperCase();
        float tw = Math.min(w - 6f, Style.width(t, Style.SMALL_FONT)), th = Style.lineHeight(Style.SMALL_FONT);
        Style.text(t, Style.SMALL_FONT, on || over ? Color.WHITE : Style.alpha(c, 230), x + (w - tw) / 2f, y + (h + th) / 2f, w);
    }

    /** A row of toggles across 'w', one lit; returns their x positions (left of each, then the right edge). */
    static float[] toggles(String[] names, int on, float x, float y, float w, float h, Color c, Hits hits, String id) {
        float[] at = new float[names.length + 1];
        float each = w / names.length;
        for (int i = 0; i < names.length; i++) {
            float bx = x + i * each;
            at[i] = bx;
            box(bx, y, each - (i < names.length - 1 ? 2f : 0f), h, names[i], c, i == on);
            if (hits != null) hits.add(bx, y, each - 2f, h, id + ":" + i);
        }
        at[names.length] = x + w;
        return at;
    }

    /** A card's back: lit when picked, edged in 'edge' (null: the usual). */
    static void card(float x, float y, float w, float h, boolean on, Color edge) {
        boolean over = over(x, y, w, h);
        Style.fill(x, y, w, h, on ? CARD_ON : over ? new Color(16, 38, 60, 235) : CARD);
        Style.outline(x, y, w, h, on ? Style.ACCENT : edge != null ? edge : over ? Style.alpha(Style.ACCENT, 140) : CARD_EDGE);
    }

    /** A picture in a square box (a portrait, an icon); just the box when it can't be loaded. */
    static void picture(String path, float x, float y, float s, Color edge) {
        Style.fill(x, y, s, s, new Color(20, 38, 58, 255));
        SpriteAPI sp = LobbyScreen.sprite(path);
        if (sp != null) LobbyScreen.icon(sp, x, y, s);
        Style.outline(x, y, s, s, edge == null ? CARD_EDGE : edge);
    }

    static String skillName(String id) {
        try { return Global.getSettings().getSkillSpec(id).getName(); } catch (Throwable t) { return id; }
    }

    static String skillIcon(String id) {
        try { return Global.getSettings().getSkillSpec(id).getSpriteName(); } catch (Throwable t) { return null; }
    }

    /** A skill's short description, its first sentence (none if the game has none). */
    static String skillText(String id) {
        try {
            String s = Global.getSettings().getSkillSpec(id).getDescription();
            if (s == null) return "";
            s = s.trim();
            int dot = s.indexOf(". ");
            return dot > 0 && dot < 160 ? s.substring(0, dot + 1) : s.length() > 160 ? s.substring(0, 157) + "..." : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** The skill's aptitude ("Combat", "Leadership"...), or "". */
    static String skillAptitude(String id) {
        try {
            String a = Global.getSettings().getSkillSpec(id).getGoverningAptitudeName();
            return a == null ? "" : a;
        } catch (Throwable t) {
            return "";
        }
    }

    /** A line cut to fit 'w' with "...". */
    static String fit(String s, String font, float w) {
        if (s == null) return "";
        if (Style.width(s, font) <= w) return s;
        String t = s;
        while (t.length() > 1 && Style.width(t + "...", font) > w) t = t.substring(0, t.length() - 1);
        return t.trim() + "...";
    }

    /** The small section name (OFFICER, THEIR REFITS) with nothing after it; returns the y under it. */
    static float head(String name, float x, float top) {
        Style.text(name.toUpperCase(), Style.SMALL_FONT, Style.alpha(Style.ACCENT, 230), x, top, 400f);
        return top - Style.lineHeight(Style.SMALL_FONT) - 5f;
    }
}
