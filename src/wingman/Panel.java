package wingman;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.LabelAPI;

/** Draws a centred box with a title, some lines and a footer over the battle. Used for the ready check and alerts. */
final class Panel {
    static final Color TEXT = new Color(220, 225, 230);
    static final Color GOOD = new Color(110, 220, 120);
    static final Color WARN = new Color(255, 200, 80);
    static final Color BAD = new Color(255, 110, 90);
    static final Color DIM = new Color(150, 160, 170);
    private static final Color TITLE = new Color(120, 210, 255);

    private static final String TITLE_FONT = "graphics/fonts/orbitron20aabold.fnt";
    private static final float WIDTH = 560f, PAD = 18f, GAP = 6f;

    private final Map<String, LabelAPI> cache = new HashMap<String, LabelAPI>();
    private boolean broken;
    /** Drawing failures in a row; one bad frame is skipped and logged, only a run of them switches panels off. */
    private int failures;
    private String lastFailure;
    private String bodyFont;

    /** Enter on the main keyboard or the numpad. */
    static boolean isEnter(int key) { return key == 28 || key == 156; }

    private LabelAPI label(String text, String font, Color color) {
        String key = font + "|" + color.getRGB() + "|" + Math.round(width) + "|" + text;
        LabelAPI l = cache.get(key);
        if (l == null) {
            if (cache.size() > 200) cache.clear();
            l = Global.getSettings().createLabel(text, font);
            l.setColor(color);
            l.autoSizeToWidth(width - PAD * 2f);
            cache.put(key, l);
        }
        return l;
    }

    /** How wide the box being drawn is, and where: centred on the screen, or hung from a corner point. */
    private float width = WIDTH, anchorRight, anchorTop;
    private boolean anchored;

    /** The usual box, centred a little above the middle of the screen. */
    void render(String title, List<String> lines, List<Color> colors, String footer) {
        width = WIDTH;
        anchored = false;
        draw(title, lines, colors, footer);
    }

    /**
     * A box hanging from a point: its top right corner at (right, top), growing downwards. Used on the title
     * screen, where the middle is taken by the game's logo and menu, which are drawn over anything of ours.
     */
    void renderBelow(float right, float top, float boxWidth, String title, List<String> lines, List<Color> colors, String footer) {
        width = boxWidth;
        anchored = true;
        anchorRight = right;
        anchorTop = top;
        draw(title, lines, colors, footer);
        width = WIDTH;
    }

    private void draw(String title, List<String> lines, List<Color> colors, String footer) {
        if (broken) return;
        try {
            if (bodyFont == null) bodyFont = Global.getSettings().getString("defaultFont"); // the game's normal text font
            final String FONT = bodyFont;
            LabelAPI t = label(title, TITLE_FONT, TITLE);
            LabelAPI f = label(footer, FONT, DIM);
            LabelAPI[] body = new LabelAPI[lines.size()];
            float h = PAD * 2f + t.getPosition().getHeight() + GAP * 2f + f.getPosition().getHeight() + GAP * 2f;
            for (int i = 0; i < body.length; i++) {
                body[i] = label(lines.get(i), FONT, i < colors.size() ? colors.get(i) : TEXT);
                h += body[i].getPosition().getHeight() + GAP;
            }
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float x = (sw - width) / 2f, y = sh * 0.62f - h / 2f;
            if (anchored) {
                x = Math.max(4f, anchorRight - width);
                y = Math.max(4f, anchorTop - h);
            }

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.88f);
            quad(GL11.GL_QUADS, x, y, width, h);
            GL11.glColor4f(0.47f, 0.82f, 1f, 0.9f);
            GL11.glLineWidth(1f);
            quad(GL11.GL_LINE_LOOP, x, y, width, h);
            GL11.glPopAttrib();

            float cy = y + h - PAD;
            cy = draw(t, x + PAD, cy) - GAP * 2f;
            for (LabelAPI l : body) cy = draw(l, x + PAD, cy) - GAP;
            draw(f, x + PAD, y + PAD + f.getPosition().getHeight());
            failures = 0;
        } catch (Throwable e) {
            // Drawing is a nicety: the same information also goes to the message feed, so never let it break co-op.
            failed("panel '" + title + "'", e);
        }
    }

    private void failed(String what, Throwable e) {
        cache.clear(); // a bad label may be what failed: make them afresh
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
            if (bodyFont == null) bodyFont = Global.getSettings().getString("defaultFont");
            float savedWidth = width;
            width = 2000f; // labels sized to their text, not wrapped
            LabelAPI big = label(word, "graphics/fonts/orbitron24aabold.fnt", color);
            LabelAPI small = sub == null || sub.isEmpty() ? null : label(sub, bodyFont, TEXT);
            width = savedWidth;
            final float SCALE = 3f;
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float bw = big.computeTextWidth(word) * SCALE, bh = big.getPosition().getHeight() * SCALE;
            float sub_h = small == null ? 0f : small.getPosition().getHeight() + GAP;
            float cy = sh * 0.68f, bandH = bh + sub_h + PAD * 2f;

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.6f);
            quad(GL11.GL_QUADS, 0f, cy - bandH / 2f, sw, bandH);
            GL11.glPopAttrib();

            float top = cy + bandH / 2f - PAD;
            GL11.glPushMatrix();
            GL11.glTranslatef((sw - bw) / 2f, top - bh, 0f);
            GL11.glScalef(SCALE, SCALE, 1f);
            big.getPosition().setLocation(0f, 0f);
            big.render(1f);
            GL11.glPopMatrix();
            if (small != null) {
                float w = small.computeTextWidth(sub);
                draw(small, (sw - w) / 2f, top - bh - GAP);
            }
        } catch (Throwable e) {
            failed("banner", e);
        }
    }

    /** A small clickable-looking box with one or two lines of text, for the title screen. */
    void button(float x, float y, float w, float h, String text, String sub, boolean hover) {
        if (broken) return;
        try {
            if (bodyFont == null) bodyFont = Global.getSettings().getString("defaultFont");
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.85f);
            quad(GL11.GL_QUADS, x, y, w, h);
            if (hover) GL11.glColor4f(0.75f, 0.93f, 1f, 1f); else GL11.glColor4f(0.47f, 0.82f, 1f, 0.8f);
            GL11.glLineWidth(1f);
            quad(GL11.GL_LINE_LOOP, x, y, w, h);
            GL11.glPopAttrib();
            LabelAPI t = label(text, TITLE_FONT, hover ? TEXT : TITLE);
            LabelAPI u = sub == null || sub.isEmpty() ? null : label(sub, bodyFont, DIM);
            float th = t.getPosition().getHeight() + (u == null ? 0f : u.getPosition().getHeight() + 2f);
            float top = y + h / 2f + th / 2f;
            top = draw(t, x + 10f, top) - 2f;
            if (u != null) draw(u, x + 10f, top);
        } catch (Throwable e) {
            failed("button", e);
        }
    }

    private static void quad(int mode, float x, float y, float w, float h) {
        GL11.glBegin(mode);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    /** Draws a label with its top edge at 'top'; returns the y of its bottom edge. */
    private static float draw(LabelAPI l, float x, float top) {
        float h = l.getPosition().getHeight();
        l.getPosition().setLocation(x, top - h);
        l.render(1f);
        return top - h;
    }
}
