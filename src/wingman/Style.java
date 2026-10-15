package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.LabelAPI;

/**
 * Wingman's look, in one place: the colours, the fonts, and the pieces every screen is built from (a window with
 * a title bar, a section heading with its rule, a tab strip, a button, a row of key hints). Everything is drawn
 * by hand in screen units, so it works on the title screen, in battle and over the campaign's own screens.
 */
final class Style {
    private Style() {}

    // ---- colours ----
    static final Color ACCENT = new Color(120, 210, 255);
    static final Color TEXT = new Color(220, 225, 230);
    static final Color DIM = new Color(150, 160, 170);
    static final Color FAINT = new Color(105, 115, 128);
    static final Color GOOD = new Color(110, 220, 120);
    static final Color WARN = new Color(255, 200, 80);
    static final Color BAD = new Color(255, 110, 90);

    static final Color WINDOW = new Color(6, 10, 16, 240);
    static final Color BAR = new Color(120, 210, 255, 26);
    static final Color EDGE = new Color(120, 210, 255, 115);
    static final Color RULE = new Color(120, 210, 255, 60);
    static final Color WELL = new Color(255, 255, 255, 9);
    static final Color HOVER = new Color(120, 210, 255, 30);
    static final Color MARKED = new Color(120, 210, 255, 48);
    static final Color CHIP = new Color(255, 255, 255, 16);
    static final Color CHIP_EDGE = new Color(150, 160, 170, 110);

    // ---- fonts ----
    static final String TITLE_FONT = "graphics/fonts/orbitron20aabold.fnt";
    static final String BIG_FONT = "graphics/fonts/orbitron24aabold.fnt";
    static final String SMALL_FONT = "graphics/fonts/orbitron12condensed.fnt";
    private static String body;

    /** The game's normal text font. */
    static String body() {
        if (body == null) {
            try { body = Global.getSettings().getString("defaultFont"); } catch (Throwable t) { body = "graphics/fonts/insignia15LTaa.fnt"; }
        }
        return body;
    }

    // ---- sizes ----
    static final float BAR_H = 34f, PAD = 16f, TAB_H = 28f;

    // ---- text ----
    private static final Map<String, LabelAPI> labels = new HashMap<String, LabelAPI>();

    /** A label, made once and kept (wrapped to the width given). */
    static LabelAPI label(String text, String font, Color color, float width) {
        String key = font + "|" + color.getRGB() + "|" + Math.round(width) + "|" + text;
        LabelAPI l = labels.get(key);
        if (l == null) {
            if (labels.size() > 600) labels.clear();
            l = Global.getSettings().createLabel(text, font);
            l.setColor(color);
            l.autoSizeToWidth(Math.max(10f, width));
            labels.put(key, l);
        }
        return l;
    }

    /** Forget every label (after a drawing failure, in case a bad one caused it). */
    static void forget() { labels.clear(); }

    /** Text with its top edge at 'top', wrapped to 'width'; returns the y of its bottom edge. */
    static float text(String s, String font, Color c, float x, float top, float width) {
        if (s == null || s.isEmpty()) return top;
        LabelAPI l = label(s, font, c, width);
        float h = l.getPosition().getHeight();
        l.getPosition().setLocation(x, top - h);
        l.render(1f);
        return top - h;
    }

    /** One line of text, its top at 'top', right edge at 'right'. Returns its left edge. */
    static float textRight(String s, String font, Color c, float right, float top) {
        if (s == null || s.isEmpty()) return right;
        float w = width(s, font);
        text(s, font, c, right - w, top, w + 40f);
        return right - w;
    }

    /** How wide one line of text is. */
    static float width(String s, String font) {
        if (s == null || s.isEmpty()) return 0f;
        return label(s, font, TEXT, 4000f).computeTextWidth(s);
    }

    /** How tall the text is once wrapped to 'width'. */
    static float height(String s, String font, float width) {
        if (s == null || s.isEmpty()) return 0f;
        return label(s, font, TEXT, width).getPosition().getHeight();
    }

    /** One line's height in a font. */
    static float lineHeight(String font) { return height("Ag", font, 4000f); }

    // ---- shapes ----

    private static void begin() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void color(Color c) { GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f); }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }

    static void fill(float x, float y, float w, float h, Color c) {
        if (w <= 0f || h <= 0f) return;
        begin();
        color(c);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    static void outline(float x, float y, float w, float h, Color c) {
        begin();
        color(c);
        GL11.glLineWidth(1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x + 0.5f, y + 0.5f); GL11.glVertex2f(x + 0.5f, y + h - 0.5f); GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f); GL11.glVertex2f(x + w - 0.5f, y + 0.5f);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    /** A box with its corners cut: the frame of windows and buttons. */
    static void frame(float x, float y, float w, float h, Color fillColor, Color edge, float cut) {
        cut = Math.min(cut, Math.min(w, h) / 3f);
        begin();
        if (fillColor != null && fillColor.getAlpha() > 0) {
            color(fillColor);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex2f(x + w / 2f, y + h / 2f);
            corners(x, y, w, h, cut);
            GL11.glVertex2f(x, y + cut);
            GL11.glEnd();
        }
        if (edge != null && edge.getAlpha() > 0) {
            color(edge);
            GL11.glLineWidth(1f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            corners(x + 0.5f, y + 0.5f, w - 1f, h - 1f, cut);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    private static void corners(float x, float y, float w, float h, float cut) {
        GL11.glVertex2f(x, y + cut);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w - cut, y + h);
        GL11.glVertex2f(x + w, y + h - cut);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + cut, y);
    }

    // ---- pieces ----

    /**
     * A window: dark body, cut corners, a title bar with an accent mark, the title, and a note on the right of the
     * bar. Returns the top of the space under the bar.
     */
    static float window(float x, float y, float w, float h, String title, String note) { return window(x, y, w, h, title, note, 0f); }

    /** The same, keeping 'reserve' clear at the right end of the bar (for a close box). */
    static float window(float x, float y, float w, float h, String title, String note, float reserve) {
        frame(x, y, w, h, WINDOW, EDGE, 10f);
        return titleBar(x, y + h, w, title, note, reserve);
    }

    static float titleBar(float x, float top, float w, String title, String note) { return titleBar(x, top, w, title, note, 0f); }

    /** The title bar along the top edge of a box ('top'); returns the y under it. */
    static float titleBar(float x, float top, float w, String title, String note, float reserve) {
        float bar = BAR_H;
        fill(x + 1f, top - bar, w - 11f, bar - 1f, BAR);
        fill(x + 1f, top - bar, 3f, bar - 1f, ACCENT);
        fill(x + 1f, top - bar, w - 2f, 1f, alpha(ACCENT, 150));
        float th = lineHeight(TITLE_FONT);
        float right = x + w - PAD - reserve;
        if (note != null && !note.isEmpty()) {
            float nh = lineHeight(body());
            right = textRight(note, body(), DIM, right, top - (bar - nh) / 2f) - 12f;
        }
        text(title == null ? "" : title, TITLE_FONT, ACCENT, x + PAD, top - (bar - th) / 2f, Math.max(40f, right - x - PAD));
        return top - bar;
    }

    /** A section heading: its name in small capitals and a thin rule to the right edge. Returns the y under it. */
    static float section(String name, float x, float top, float w) {
        String s = name.toUpperCase();
        float h = lineHeight(SMALL_FONT);
        text(s, SMALL_FONT, alpha(ACCENT, 230), x, top, w);
        float tw = width(s, SMALL_FONT);
        if (tw + 10f < w) fill(x + tw + 10f, top - h / 2f, w - tw - 10f, 1f, RULE);
        return top - h - 6f;
    }

    /** A thin rule across. */
    static void rule(float x, float y, float w) { fill(x, y, w, 1f, RULE); }

    /** A coloured mark down the left of a line of notice text, with a faint band behind it. Returns the y under it. */
    static float callout(String s, Color c, float x, float top, float w) {
        float h = height(s, body(), w - 14f);
        fill(x, top - h - 6f, w, h + 6f, alpha(c, 18));
        fill(x, top - h - 6f, 3f, h + 6f, c);
        text(s, body(), c, x + 11f, top - 3f, w - 14f);
        return top - h - 6f;
    }

    /** A small box with a short word in it (a key, a tag). Returns its width. */
    static float chip(String s, Color c, float x, float top) {
        String t = s.toUpperCase();
        float tw = width(t, SMALL_FONT), th = lineHeight(SMALL_FONT);
        float w = tw + 10f, h = th + 6f;
        fill(x, top - h, w, h, CHIP);
        outline(x, top - h, w, h, alpha(c, 120));
        text(t, SMALL_FONT, c, x + 5f, top - 3f, tw + 20f);
        return w;
    }

    static float chipHeight() { return lineHeight(SMALL_FONT) + 6f; }

    /** A button; 'over' lights it up. */
    static void button(float x, float y, float w, float h, String text, Color c, boolean enabled, boolean over) {
        Color edge = !enabled ? new Color(120, 120, 120, 80) : over ? c : alpha(c, 150);
        Color back = !enabled ? alpha(c, 8) : over ? alpha(c, 60) : alpha(c, 22);
        frame(x, y, w, h, back, edge, 6f);
        String font = h >= 30f ? TITLE_FONT : SMALL_FONT;
        String t = font == SMALL_FONT ? text.toUpperCase() : text;
        float tw = Math.min(w - 8f, width(t, font)), th = lineHeight(font);
        text(t, font, enabled ? (over ? Color.WHITE : c) : DIM, x + (w - tw) / 2f, y + (h + th) / 2f, w);
    }

    /**
     * A tab strip: each name a tab, the active one lit with a bar under it. Returns the x ranges of the tabs
     * (left, right per tab) for clicks.
     */
    static float[] tabs(List<String> names, int active, float x, float top, float w) {
        float[] at = new float[names.size() * 2];
        float tx = x, th = lineHeight(SMALL_FONT);
        fill(x, top - TAB_H, w, 1f, RULE);
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i).toUpperCase();
            float tw = width(n, SMALL_FONT) + 28f;
            boolean on = i == active;
            if (on) {
                fill(tx, top - TAB_H, tw, TAB_H - 1f, alpha(ACCENT, 34));
                fill(tx, top - TAB_H, tw, 2f, ACCENT);
            }
            text(n, SMALL_FONT, on ? Color.WHITE : DIM, tx + 14f, top - (TAB_H - th) / 2f, tw);
            at[i * 2] = tx;
            at[i * 2 + 1] = tx + tw;
            tx += tw + 2f;
        }
        return at;
    }

    /** "Enter: join     Esc: close" as pairs of {key, what it does}; a part without ": " is plain text. */
    static List<String[]> keys(String footer) {
        List<String[]> out = new ArrayList<String[]>();
        if (footer == null) return out;
        for (String part : footer.trim().split(" {3,}")) {
            if (part.isEmpty()) continue;
            int c = part.indexOf(": ");
            if (c > 0 && c <= 22) out.add(new String[] { part.substring(0, c), part.substring(c + 2) });
            else out.add(new String[] { "", part });
        }
        return out;
    }

    /** How tall a row of key hints is when wrapped to 'w'. */
    static float keysHeight(List<String[]> keys, float w) { return layoutKeys(keys, 0f, 0f, w, false, null); }

    /** Draws the key hints from 'top' down, wrapping; returns the height used. */
    static float drawKeys(List<String[]> keys, float x, float top, float w) { return layoutKeys(keys, x, top, w, true, null); }

    /**
     * The same, each hint a button for the mouse: clicking it does what the key does. Where each is goes into
     * 'hits' as {x, y, w, h, key code, ctrl (1/0)}; hints whose key isn't one key (Up/Down) are drawn but not added.
     */
    static float drawKeys(List<String[]> keys, float x, float top, float w, List<float[]> hits) { return layoutKeys(keys, x, top, w, true, hits); }

    /** The key code (LWJGL) for a key's name as the hints write it ("Enter", "F3", "Ctrl+V", "R"), and ctrl; null if none. */
    static int[] keyCode(String name) {
        String n = name.trim().toUpperCase();
        int ctrl = 0;
        if (n.startsWith("CTRL+")) { ctrl = 1; n = n.substring(5); }
        if (n.contains("/") || n.contains("+") || n.contains(" ")) return null;
        if (n.equals("ENTER")) return new int[] { 28, ctrl };
        if (n.equals("ESC")) return new int[] { 1, ctrl };
        if (n.equals("TAB")) return new int[] { 15, ctrl };
        if (n.equals("BACKSPACE")) return new int[] { 14, ctrl };
        if (n.equals("DELETE")) return new int[] { 211, ctrl };
        if (n.equals("SPACE")) return new int[] { 57, ctrl };
        if (n.equals("LEFT")) return new int[] { 203, ctrl };
        if (n.equals("RIGHT")) return new int[] { 205, ctrl };
        if (n.equals("UP")) return new int[] { 200, ctrl };
        if (n.equals("DOWN")) return new int[] { 208, ctrl };
        if (n.matches("F[1-9]|F10")) { int k = Integer.parseInt(n.substring(1)); return new int[] { 58 + k, ctrl }; }
        if (n.length() == 1) {
            int i = "QWERTYUIOP".indexOf(n.charAt(0));
            if (i >= 0) return new int[] { 16 + i, ctrl };
            i = "ASDFGHJKL".indexOf(n.charAt(0));
            if (i >= 0) return new int[] { 30 + i, ctrl };
            i = "ZXCVBNM".indexOf(n.charAt(0));
            if (i >= 0) return new int[] { 44 + i, ctrl };
        }
        return null;
    }

    /** True if the mouse is over that box. */
    static boolean over(float x, float y, float w, float h) {
        try {
            float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float layoutKeys(List<String[]> keys, float x, float top, float w, boolean draw, List<float[]> hits) {
        if (keys.isEmpty()) return 0f;
        float rowH = Math.max(chipHeight(), lineHeight(body())) + 4f;
        float cx = x, cy = top, used = rowH;
        for (String[] k : keys) {
            float kw = k[0].isEmpty() ? 0f : width(k[0].toUpperCase(), SMALL_FONT) + 10f + 6f;
            float aw = width(k[1], body());
            float total = kw + aw;
            if (cx > x && cx + total > x + w) { cx = x; cy -= rowH; used += rowH; }
            if (draw) {
                float ch = chipHeight(), bh = lineHeight(body());
                int[] code = hits == null || k[0].isEmpty() ? null : keyCode(k[0]);
                boolean lit = code != null && over(cx - 4f, cy - rowH + 2f, total + 8f, rowH);
                if (lit) fill(cx - 4f, cy - rowH + 2f, total + 8f, rowH, HOVER);
                if (!k[0].isEmpty()) chip(k[0], lit ? Color.WHITE : TEXT, cx, cy - (rowH - 4f - ch) / 2f);
                text(k[1], body(), lit ? TEXT : DIM, cx + kw, cy - (rowH - 4f - bh) / 2f, aw + 40f);
                if (code != null) hits.add(new float[] { cx - 4f, cy - rowH + 2f, total + 8f, rowH, code[0], code[1] });
            }
            cx += total + 20f;
        }
        return used;
    }

    /** A bar showing used out of max (red past max). */
    static void meter(float x, float y, float w, float h, float used, float max) {
        fill(x, y, w, h, alpha(Color.WHITE, 14));
        if (max <= 0f) return;
        float f = Math.min(1f, used / max);
        Color c = used > max ? BAD : f > 0.9f ? WARN : ACCENT;
        fill(x, y, w * f, h, alpha(c, 200));
    }
}
