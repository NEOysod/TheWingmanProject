package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.LabelAPI;

/**
 * Wingman's windows: a title bar, then the content in sections (a heading and its rule, lines, labelled fields,
 * notices), then the keys that work, as key chips along the bottom. Used for the CO-OP panel, the settings, the
 * help pages, the ready check and alerts. See Style for the look.
 */
final class Panel {
    static final Color TEXT = Style.TEXT;
    static final Color GOOD = Style.GOOD;
    static final Color WARN = Style.WARN;
    static final Color BAD = Style.BAD;
    static final Color DIM = Style.DIM;

    private static final float WIDTH = 600f, PAD = Style.PAD, GAP = 5f;

    private boolean broken;
    /** Drawing failures in a row; one bad frame is skipped and logged, only a run of them switches panels off. */
    private int failures;
    private String lastFailure;

    /** Enter on the main keyboard or the numpad. */
    static boolean isEnter(int key) { return key == 28 || key == 156; }

    /** Where the last box was drawn (screen units: left, bottom, width, height), for the mouse. */
    float lastX, lastY, lastW, lastH;

    boolean contains(float mx, float my) { return lastW > 0f && mx >= lastX && mx <= lastX + lastW && my >= lastY && my <= lastY + lastH; }

    // ---- the mouse: what was drawn where, for clicks (the owner asks hitAt and acts on it) ----

    /** Something clickable in the window as last drawn. */
    static final class Hit {
        static final int KEY = 0, ROW = 1, TAB = 2, STEP = 3;
        final float x, y, w, h;
        /** KEY: a key code (as if pressed, with ctrl); ROW: a field or option row (in order); TAB: a tab; STEP: -1 or +1 on the marked option. */
        final int kind, value;
        final boolean ctrl;
        Hit(float x, float y, float w, float h, int kind, int value, boolean ctrl) { this.x = x; this.y = y; this.w = w; this.h = h; this.kind = kind; this.value = value; this.ctrl = ctrl; }
        boolean has(float mx, float my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }

    private final List<Hit> hits = new ArrayList<Hit>();
    /**
     * Whether whoever shows this window acts on clicks (Hit). Only then are there a close box and lit key hints:
     * in a battle, where the keys are used, nothing pretends to be clickable.
     */
    boolean mouse;

    /** What is under the mouse in the window as last drawn (the topmost), or null. */
    Hit hitAt(float mx, float my) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).has(mx, my)) return hits.get(i);
        return null;
    }

    /** Forget where things were (the window isn't shown any more). */
    void hide() { hits.clear(); lastW = 0f; }

    // ---- what a window holds ----

    /** One window's content, built each frame by whoever shows it. */
    static final class Doc {
        String title = "", note = "";
        /** Tabs under the title bar (empty: none), and which is lit. */
        final List<String> tabs = new ArrayList<String>();
        int tab = -1;
        final List<Block> blocks = new ArrayList<Block>();
        String keys = "";

        Doc(String title) { this.title = title == null ? "" : title; }

        Doc note(String n) { note = n == null ? "" : n; return this; }
        Doc keys(String k) { keys = k == null ? "" : k; return this; }
        Doc tabs(List<String> names, int active) { tabs.clear(); tabs.addAll(names); tab = active; return this; }

        /** A section heading. */
        Doc section(String name) { blocks.add(new Block(Block.SECTION, name, null, TEXT)); return this; }
        /** A line of text. */
        Doc line(String text, Color c) { if (text != null && !text.isEmpty()) blocks.add(new Block(Block.LINE, text, null, c == null ? TEXT : c)); return this; }
        /** A notice: a line with a coloured mark down its left. */
        Doc callout(String text, Color c) { if (text != null && !text.isEmpty()) blocks.add(new Block(Block.CALLOUT, text, null, c == null ? WARN : c)); return this; }
        /** A labelled value; 'selected' marks the row, 'input' draws the value in a box (typed fields). */
        private int rows;

        Doc field(String label, String value, boolean selected, boolean input) {
            Block b = new Block(Block.FIELD, label, value, TEXT);
            b.row = rows++;
            b.selected = selected;
            b.input = input;
            blocks.add(b);
            return this;
        }
        /** A settings row: label, value (with arrows when selected), a tag on the right, an "edited" mark. */
        Doc option(String label, String value, String tag, boolean selected, boolean edited, boolean typed) {
            Block b = new Block(Block.OPTION, label, value, TEXT);
            b.row = rows++;
            b.selected = selected;
            b.tag = tag;
            b.edited = edited;
            b.input = typed;
            blocks.add(b);
            return this;
        }
        Doc space() { blocks.add(new Block(Block.SPACE, "", null, TEXT)); return this; }

        /**
         * A row of buttons for the mouse, each doing what a key does: {label, key code, colour}, the first the main
         * one. Only shown where clicks are acted on (Panel.mouse); elsewhere the key hints say the same.
         */
        Doc buttons(Object[]... b) {
            Block bl = new Block(Block.BUTTONS, "", null, TEXT);
            for (Object[] o : b) if (o != null) bl.buttons.add(o);
            if (!bl.buttons.isEmpty()) blocks.add(bl);
            return this;
        }

        static Object[] button(String label, int key, Color c) { return new Object[] { label, key, c }; }
    }

    static final class Block {
        static final int SECTION = 0, LINE = 1, CALLOUT = 2, FIELD = 3, OPTION = 4, SPACE = 5, BUTTONS = 6;
        final List<Object[]> buttons = new ArrayList<Object[]>();
        final int kind;
        final String text, value;
        final Color color;
        boolean selected, input, edited;
        String tag;
        /** Which field or option row this is, in order (for clicks). */
        int row = -1;
        Block(int kind, String text, String value, Color color) { this.kind = kind; this.text = text; this.value = value; this.color = color; }
    }

    // ---- the older calls: plain lines and a footer ----

    /** The usual box, centred a little above the middle of the screen. */
    void render(String title, List<String> lines, List<Color> colors, String footer) {
        render(plain(title, lines, colors, footer));
    }

    /** A box hanging from a point: its top right corner at (right, top), growing downwards. */
    void renderBelow(float right, float top, float boxWidth, String title, List<String> lines, List<Color> colors, String footer) {
        renderBelow(right, top, boxWidth, plain(title, lines, colors, footer));
    }

    private static Doc plain(String title, List<String> lines, List<Color> colors, String footer) {
        Doc d = new Doc(title).keys(footer);
        for (int i = 0; i < lines.size(); i++) d.line(lines.get(i), i < colors.size() ? colors.get(i) : TEXT);
        return d;
    }

    // ---- drawing ----

    void render(Doc d) {
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        draw(d, WIDTH, sw / 2f + WIDTH / 2f, Float.NaN, sh);
    }

    /**
     * Hung from a point: its top right corner at (right, top), growing downwards. Used on the title screen, where
     * the middle is taken by the game's logo and menu, which are drawn over anything of ours.
     */
    void renderBelow(float right, float top, float boxWidth, Doc d) {
        draw(d, boxWidth, right, top, Global.getSettings().getScreenHeight());
    }

    private void draw(Doc d, float width, float right, float top, float sh) {
        if (broken) return;
        try {
            float inner = width - PAD * 2f;
            List<String[]> keys = Style.keys(d.keys);
            float keysH = keys.isEmpty() ? 0f : Style.keysHeight(keys, inner) + 12f;
            float tabsH = d.tabs.isEmpty() ? 0f : Style.TAB_H + 4f;
            float bodyH = 0f;
            for (int i = 0; i < d.blocks.size(); i++) bodyH += d.blocks.get(i).kind == Block.BUTTONS && !mouse ? 0f : height(d.blocks.get(i), inner, i == 0);
            float h = Style.BAR_H + tabsH + PAD + bodyH + PAD * 0.75f + keysH;
            float x = right - width, y;
            if (Float.isNaN(top)) y = sh * 0.6f - h / 2f;
            else y = top - h;
            x = Math.max(4f, x);
            y = Math.max(4f, y);
            lastX = x; lastY = y; lastW = width; lastH = h;
            hits.clear();

            float cy = Style.window(x, y, width, h, d.title, d.note, mouse ? Style.BAR_H - 2f : 0f);
            if (mouse) closeBox(x + width, y + h);
            if (!d.tabs.isEmpty()) {
                float[] at = Style.tabs(d.tabs, d.tab, x + 1f, cy, width - 2f);
                for (int i = 0; i < d.tabs.size(); i++) hits.add(new Hit(at[i * 2], cy - Style.TAB_H, at[i * 2 + 1] - at[i * 2], Style.TAB_H, Hit.TAB, i, false));
                cy -= tabsH;
            }
            cy -= PAD;
            float lx = x + PAD;
            float labelW = labelColumn(d, inner);
            for (int i = 0; i < d.blocks.size(); i++) cy = drawBlock(d.blocks.get(i), lx, cy, inner, labelW, i == 0, mouse ? hits : null);
            if (!keys.isEmpty()) {
                float ky = y + keysH - 4f;
                Style.rule(x + 1f, ky + 4f, width - 2f);
                List<float[]> kh = new ArrayList<float[]>();
                Style.drawKeys(keys, lx, ky - 2f, inner, mouse ? kh : null);
                for (float[] k : kh) hits.add(new Hit(k[0], k[1], k[2], k[3], Hit.KEY, (int) k[4], k[5] > 0f));
            }
            failures = 0;
        } catch (Throwable e) {
            // Drawing is a nicety: the same information also goes to the message feed, so never let it break co-op.
            failed("panel '" + d.title + "'", e);
        }
    }

    /** A small X at the top right of the title bar: Esc, for the mouse. */
    private void closeBox(float right, float top) {
        float s = Style.BAR_H - 12f, bx = right - s - 14f, by = top - Style.BAR_H + 6f;
        boolean over = Style.over(bx, by, s, s);
        Style.fill(bx, by, s, s, over ? Style.alpha(Style.BAD, 70) : Style.CHIP);
        Style.outline(bx, by, s, s, over ? Style.BAD : Style.CHIP_EDGE);
        float tw = Style.width("X", Style.SMALL_FONT), th = Style.lineHeight(Style.SMALL_FONT);
        Style.text("X", Style.SMALL_FONT, over ? Color.WHITE : DIM, bx + (s - tw) / 2f, by + (s + th) / 2f, s);
        hits.add(new Hit(bx, by, s, s, Hit.KEY, 1, false));
    }

    /** How wide the label column of fields and options is: the longest label, within reason. */
    private static float labelColumn(Doc d, float inner) {
        float w = 0f;
        for (Block b : d.blocks) if (b.kind == Block.FIELD || b.kind == Block.OPTION) w = Math.max(w, Style.width(b.text, Style.body()));
        return Math.min(inner * 0.55f, w + 28f);
    }

    private static float rowH() { return Math.max(Style.lineHeight(Style.body()) + 10f, Style.chipHeight() + 6f); }

    private static float height(Block b, float inner, boolean first) {
        switch (b.kind) {
            case Block.SECTION: return (first ? 0f : 10f) + Style.lineHeight(Style.SMALL_FONT) + 6f;
            case Block.CALLOUT: return Style.height(b.text, Style.body(), inner - 14f) + 6f + GAP + 2f;
            case Block.FIELD:
            case Block.OPTION: return rowH() + 2f;
            case Block.SPACE: return 8f;
            case Block.BUTTONS: return 12f + 34f + 6f + (b.buttons.size() >= 3 ? 28f + 8f : 0f);
            default: return Style.height(b.text, Style.body(), inner) + GAP;
        }
    }

    private static float drawBlock(Block b, float x, float top, float inner, float labelW, boolean first, List<Hit> hits) {
        String body = Style.body();
        switch (b.kind) {
            case Block.SECTION:
                return Style.section(b.text, x, top - (first ? 0f : 10f), inner);
            case Block.CALLOUT:
                return Style.callout(b.text, b.color, x, top - 2f, inner) - GAP;
            case Block.SPACE:
                return top - 8f;
            case Block.BUTTONS: {
                if (hits == null) return top; // keys only: the hints say it
                float bh = 34f, gap = 8f, y = top - 12f - bh, bx = x;
                int n = b.buttons.size();
                boolean twoRows = n >= 3; // the main one across, the others in a row under it
                float mainW = n == 1 || twoRows ? inner : Math.max(inner * 0.4f, Style.width(String.valueOf(b.buttons.get(0)[0]), Style.TITLE_FONT) + 30f);
                float restW = n <= 1 ? 0f : twoRows ? (inner - gap * (n - 2)) / (n - 1) : (inner - mainW - gap * (n - 1)) / (n - 1);
                for (int i = 0; i < n; i++) {
                    Object[] o = b.buttons.get(i);
                    float bw = i == 0 ? mainW : restW;
                    if (twoRows && i == 1) { bx = x; y -= 28f + 8f; }
                    float h2 = i == 0 ? bh : 28f, y2 = twoRows && i > 0 ? y + bh - 28f : y + (bh - h2) / 2f; // the others smaller, in small capitals
                    boolean over = Style.over(bx, y2, bw, h2);
                    Style.button(bx, y2, bw, h2, String.valueOf(o[0]), (Color) o[2], true, over);
                    hits.add(new Hit(bx, y2, bw, h2, Hit.KEY, (Integer) o[1], false));
                    bx += bw + gap;
                }
                return y - 6f;
            }
            case Block.FIELD:
            case Block.OPTION: {
                float rh = rowH(), y = top - rh;
                boolean hover = hits != null && Style.over(x - 6f, y, inner + 12f, rh);
                if (hover && !b.selected) Style.fill(x - 6f, y, inner + 12f, rh, Style.alpha(Style.ACCENT, 14));
                if (hits != null) hits.add(new Hit(x - 6f, y, inner + 12f, rh, Hit.ROW, b.row, false));
                if (b.selected) {
                    Style.fill(x - 6f, y, inner + 12f, rh, Style.alpha(Style.ACCENT, 26));
                    Style.fill(x - 6f, y, 3f, rh, Style.ACCENT);
                }
                float bh = Style.lineHeight(body), ty = y + (rh + bh) / 2f;
                Style.text(b.text, body, b.selected ? TEXT : DIM, x + 4f, ty, labelW - 8f);
                float vx = x + labelW, vw = inner - labelW;
                float right = x + inner - 2f;
                if (b.kind == Block.OPTION) {
                    float ch = Style.chipHeight(), cTop = y + (rh + ch) / 2f;
                    if (b.tag != null && !b.tag.isEmpty()) {
                        float cw = Style.width(b.tag.toUpperCase(), Style.SMALL_FONT) + 10f;
                        right -= cw;
                        Style.chip(b.tag, DIM, right, cTop);
                        right -= 8f;
                    }
                    if (b.edited) {
                        float cw = Style.width("EDITED", Style.SMALL_FONT) + 10f;
                        right -= cw;
                        Style.chip("edited", WARN, right, cTop);
                        right -= 8f;
                    }
                    vw = right - vx;
                }
                String v = b.value == null ? "" : b.value;
                if (b.input) {
                    Style.fill(vx - 6f, y + 3f, vw + 6f, rh - 6f, new Color(0, 0, 0, 90));
                    Style.outline(vx - 6f, y + 3f, vw + 6f, rh - 6f, b.selected ? Style.ACCENT : Style.alpha(Style.DIM, 90));
                    Style.text(v, body, b.selected ? TEXT : DIM, vx, ty, vw - 6f);
                } else if (b.kind == Block.OPTION && b.selected && hits == null) {
                    Style.text("<  " + v + "  >", body, Color.WHITE, vx, ty, vw);
                } else if (b.kind == Block.OPTION && b.selected) {
                    // arrows to click: the value one step back or on
                    float aw = Style.width("<", body) + 12f, ah = rh - 8f;
                    float vwid = Math.min(vw - 2 * aw - 12f, Style.width(v, body));
                    boolean ol = Style.over(vx - 6f, y + 4f, aw, ah), or = Style.over(vx + aw + vwid + 6f, y + 4f, aw, ah);
                    Style.fill(vx - 6f, y + 4f, aw, ah, ol ? Style.alpha(Style.ACCENT, 70) : Style.CHIP);
                    Style.text("<", body, Color.WHITE, vx, ty, aw);
                    Style.text(v, body, Color.WHITE, vx + aw, ty, Math.max(20f, vw - 2 * aw));
                    Style.fill(vx + aw + vwid + 6f, y + 4f, aw, ah, or ? Style.alpha(Style.ACCENT, 70) : Style.CHIP);
                    Style.text(">", body, Color.WHITE, vx + aw + vwid + 12f, ty, aw);
                    hits.add(new Hit(vx - 6f, y + 4f, aw, ah, Hit.STEP, -1, false));
                    hits.add(new Hit(vx + aw + vwid + 6f, y + 4f, aw, ah, Hit.STEP, 1, false));
                } else {
                    Style.text(v, body, b.selected || b.kind == Block.FIELD ? TEXT : Style.TEXT, vx, ty, vw);
                }
                return y - 2f;
            }
            default:
                return Style.text(b.text, body, b.color, x, top, inner) - GAP;
        }
    }

    private void failed(String what, Throwable e) {
        Style.forget(); // a bad label may be what failed: make them afresh
        String sig = String.valueOf(e);
        if (!sig.equals(lastFailure)) {
            lastFailure = sig;
            Global.getLogger(Panel.class).error("Wingman: drawing the " + what + " failed (skipped this frame)", e);
        }
        if (++failures >= 60) {
            broken = true;
            Global.getLogger(Panel.class).error("Wingman: drawing keeps failing, panels switched off for this session");
        }
    }

    /**
     * A large word across the screen, a little above the middle, on a dark band: for "PAUSED". The game's
     * biggest font is drawn at 3x, and the line under it at its normal size.
     */
    void banner(String word, String sub, Color color) {
        if (broken) return;
        try {
            LabelAPI big = Style.label(word, Style.BIG_FONT, color, 2000f);
            final float SCALE = 3f;
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float bw = big.computeTextWidth(word) * SCALE, bh = big.getPosition().getHeight() * SCALE;
            boolean hasSub = sub != null && !sub.isEmpty();
            float sub_h = hasSub ? Style.lineHeight(Style.body()) + GAP : 0f;
            float cy = sh * 0.68f, bandH = bh + sub_h + PAD * 2f;

            Style.fill(0f, cy - bandH / 2f, sw, bandH, new Color(6, 10, 16, 160));
            Style.fill(0f, cy + bandH / 2f - 1f, sw, 1f, Style.alpha(color, 120));
            Style.fill(0f, cy - bandH / 2f, sw, 1f, Style.alpha(color, 120));

            float top = cy + bandH / 2f - PAD;
            GL11.glPushMatrix();
            GL11.glTranslatef((sw - bw) / 2f, top - bh, 0f);
            GL11.glScalef(SCALE, SCALE, 1f);
            big.getPosition().setLocation(0f, 0f);
            big.render(1f);
            GL11.glPopMatrix();
            if (hasSub) {
                float w = Style.width(sub, Style.body());
                Style.text(sub, Style.body(), TEXT, (sw - w) / 2f, top - bh - GAP, w + 40f);
            }
        } catch (Throwable e) {
            failed("banner", e);
        }
    }

    /** The CO-OP button on the title screen: a name and a line under it. */
    void button(float x, float y, float w, float h, String text, String sub, boolean hover) {
        if (broken) return;
        try {
            Style.frame(x, y, w, h, hover ? new Color(14, 26, 38, 235) : new Color(6, 10, 16, 220), hover ? Style.ACCENT : Style.EDGE, 8f);
            Style.fill(x + 1f, y + 1f, 3f, h - 2f, hover ? Style.ACCENT : Style.alpha(Style.ACCENT, 140));
            float th = Style.lineHeight(Style.TITLE_FONT);
            boolean hasSub = sub != null && !sub.isEmpty();
            float uh = hasSub ? Style.lineHeight(Style.SMALL_FONT) + 3f : 0f;
            float top = y + h / 2f + (th + uh) / 2f;
            top = Style.text(text, Style.TITLE_FONT, hover ? Color.WHITE : Style.ACCENT, x + 14f, top, w - 20f) - 3f;
            if (hasSub) Style.text(sub.toUpperCase(), Style.SMALL_FONT, DIM, x + 14f, top, w - 20f);
        } catch (Throwable e) {
            failed("button", e);
        }
    }
}
