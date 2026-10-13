package wingman;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.LabelAPI;

/**
 * "P1", "P2"... over the ship each player is flying, in that player's colour, just off the ship's top right
 * corner, so everyone can see at a glance who is who. P1 is the host; joiners are P2 onwards, in the order
 * they joined (a player who drops and comes back keeps their number).
 *
 * Drawn on the screen, not in the world, so the tag stays the same size at any zoom.
 */
final class PlayerTags {
    /** One colour per player, as many as a game can have (16), told apart from each other and from the HUD's. */
    private static final Color[] COLORS = {
        new Color(110, 255, 120),  // P1: green
        new Color(90, 170, 255),   // P2: blue
        new Color(255, 225, 70),   // P3: yellow
        new Color(240, 240, 240),  // P4: white
        new Color(255, 150, 50),   // P5: orange
        new Color(235, 110, 235),  // P6: magenta
        new Color(80, 235, 230),   // P7: cyan
        new Color(255, 105, 105),  // P8: red
        new Color(185, 140, 255),  // P9: violet
        new Color(190, 240, 90),   // P10: lime
        new Color(255, 180, 200),  // P11: pink
        new Color(150, 200, 255),  // P12: sky
        new Color(220, 190, 120),  // P13: sand
        new Color(70, 200, 150),   // P14: teal
        new Color(255, 215, 160),  // P15: peach
        new Color(170, 170, 255),  // P16: periwinkle
    };
    private static final String FONT = "graphics/fonts/orbitron20aabold.fnt";

    private final Map<Integer, LabelAPI> labels = new HashMap<Integer, LabelAPI>();
    /** The player's name beside the number, in a smaller font: slot + name -> label. */
    private final Map<String, LabelAPI> names = new HashMap<String, LabelAPI>();
    /**
     * The game draws a ship's officer portrait just off its top right corner, the same size at any zoom: the
     * tag starts this far (screen units) to the right of that corner, clear of the portrait.
     */
    private static final float PAST_PORTRAIT = 46f;
    /** F3 in a battle hides and shows the tags and outlines. Lasts until the game is closed. */
    static volatile boolean hidden;

    /** F3, with no Ctrl, Shift or Alt: toggle. Returns true if the key was taken. Safe to call with anything. */
    static boolean toggleKey(java.util.List<com.fs.starfarer.api.input.InputEventAPI> events, com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        boolean took = false;
        if (events == null) return false;
        for (com.fs.starfarer.api.input.InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyDownEvent() || e.getEventValue() != 61 || e.isCtrlDown() || e.isShiftDown() || e.isAltDown()) continue;
            e.consume();
            hidden = !hidden;
            took = true;
            Ui.say(engine, hidden ? "Co-op: player tags hidden (F3 shows them)." : "Co-op: player tags shown (F3 hides them).");
        }
        return took;
    }
    private boolean broken;

    static Color color(int slot) {
        return slot >= 1 && slot <= COLORS.length ? COLORS[slot - 1] : COLORS[COLORS.length - 1];
    }

    /**
     * A thin diamond (a playing card's) around the ship, its points just outside the ship's reach, in the
     * player's colour and half see-through so it marks the ship without hiding anything.
     */
    private static void diamond(ViewportAPI vp, ShipAPI ship, float r, Color c) {
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float vw = vp.getVisibleWidth(), vh = vp.getVisibleHeight();
        if (vw <= 0f || vh <= 0f) return;
        float cx = (ship.getLocation().x - vp.getLLX()) / vw * sw, cy = (ship.getLocation().y - vp.getLLY()) / vh * sh;
        float rx = r * 1.15f / vw * sw, ry = r * 1.15f / vh * sh;
        if (cx + rx < 0f || cy + ry < 0f || cx - rx > sw || cy - ry > sh) return;
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT | org.lwjgl.opengl.GL11.GL_CURRENT_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT | 0x00000004 /* GL_LINE_BIT */);
        try {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
            org.lwjgl.opengl.GL11.glEnable(0x0B20 /* GL_LINE_SMOOTH */);
            org.lwjgl.opengl.GL11.glLineWidth(1f);
            org.lwjgl.opengl.GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.55f);
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINE_LOOP);
            org.lwjgl.opengl.GL11.glVertex2f(cx, cy + ry);
            org.lwjgl.opengl.GL11.glVertex2f(cx + rx, cy);
            org.lwjgl.opengl.GL11.glVertex2f(cx, cy - ry);
            org.lwjgl.opengl.GL11.glVertex2f(cx - rx, cy);
            org.lwjgl.opengl.GL11.glEnd();
        } finally {
            org.lwjgl.opengl.GL11.glPopAttrib();
        }
    }

    /** Draw one player's tag by their ship. Never throws; if drawing ever fails, tags switch off quietly. */
    void draw(ViewportAPI vp, ShipAPI ship, int slot, boolean outline) { draw(vp, ship, slot, outline, null); }

    /** The same, with the player's name after the number. */
    void draw(ViewportAPI vp, ShipAPI ship, int slot, boolean outline, String name) {
        if (broken || hidden || vp == null || ship == null || slot < 1) return;
        try {
            if (!Global.getCombatEngine().isEntityInPlay(ship) || ship.isHulk()) return;
            float r = ship.getCollisionRadius();
            if (outline) diamond(vp, ship, r, color(slot));
            float wx = ship.getLocation().x + r * 0.75f, wy = ship.getLocation().y + r * 0.75f; // just outside the ship, top right
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float vw = vp.getVisibleWidth(), vh = vp.getVisibleHeight();
            if (vw <= 0f || vh <= 0f) return;
            // World to screen, in the same units the interface is drawn in.
            float sx = (wx - vp.getLLX()) / vw * sw + PAST_PORTRAIT, sy = (wy - vp.getLLY()) / vh * sh;
            if (sx < -40f || sy < -40f || sx > sw + 40f || sy > sh + 40f) return;
            LabelAPI l = labels.get(slot);
            if (l == null) {
                l = Global.getSettings().createLabel("P" + slot, FONT);
                l.setColor(color(slot));
                labels.put(slot, l);
            }
            l.getPosition().setLocation(sx, sy);
            l.render(1f);
            if (name != null && !name.isEmpty()) {
                String key = slot + "|" + name;
                LabelAPI n = names.get(key);
                if (n == null) {
                    if (names.size() > 32) names.clear();
                    n = Global.getSettings().createLabel(name.length() > 24 ? name.substring(0, 24) : name, Global.getSettings().getString("defaultFont"));
                    Color c = color(slot);
                    n.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 210));
                    names.put(key, n);
                }
                // after the number, its bottom on the number's baseline
                float nh = n.getPosition().getHeight(), lh = l.getPosition().getHeight();
                n.getPosition().setLocation(sx + l.computeTextWidth("P" + slot) + 6f, sy + Math.max(0f, (lh - nh) * 0.5f) - 1f);
                n.render(1f);
            }
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(PlayerTags.class).warn("Wingman: player tags could not be drawn and are switched off", t);
        }
    }
}
