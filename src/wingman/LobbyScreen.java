package wingman;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.LabelAPI;

import wingman.net.Aftermath;
import wingman.net.BattleReport;
import wingman.net.Roster;

/**
 * The co-op lobby as a screen of its own (joiner side, on the title screen with the game's menu moved aside, see
 * TitleMenu): the host's fleet with a card for the marked ship (picture, loadout, officer), who is here and who
 * flies what, and the after-action report of the last battle with its aftermath. Drawn by hand like the rest of
 * Wingman's title screen panels; keys are read by WingmanTitlePlugin.
 */
final class LobbyScreen {
    static final int FLEET = 0, REPORT = 1, REFIT = 2;
    int page = FLEET;

    static final Color TITLE = new Color(120, 210, 255), LINE = new Color(120, 210, 255, 90), MARK = new Color(120, 210, 255, 45);
    static final String BIG = "graphics/fonts/orbitron24aabold.fnt", HEAD = "graphics/fonts/orbitron20aabold.fnt";
    String body;
    /** The friend-refit screen, drawn as the lobby's third page. */
    final RefitScreen refit = new RefitScreen(this);
    /** Where the Refit button was drawn this frame (x, y, w, h), or null: the title plugin reads clicks on it. */
    float[] refitButton;

    /** What can be clicked, as drawn this frame: {x, y, w, h, kind, value, ctrl}. KEY: a key code; ROW: a ship in the fleet list; TAB: a page. */
    static final int KEY = 0, ROW = 1, TAB = 2;
    final List<float[]> hits = new java.util.ArrayList<float[]>();
    /** The fleet list's box, for the mouse wheel. */
    float[] listBox;

    /** What is under the mouse, as last drawn (the topmost), or null. */
    float[] hitAt(float mx, float my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            float[] h = hits.get(i);
            if (mx >= h[0] && mx <= h[0] + h[2] && my >= h[1] && my <= h[1] + h[3]) return h;
        }
        return null;
    }
    private final Map<String, LabelAPI> cache = new HashMap<String, LabelAPI>();
    private boolean broken;
    private int failures;
    /** How many fleet rows fit; the title plugin pages by this. */
    int rows = 12;

    /** Everything the screen shows, gathered by the title plugin each frame. */
    static final class View {
        /** Room left at the top right for the co-op screen's own buttons (see CoopScreen). */
        float reserveRight;
        Roster roster;
        String me = "", hostLine = "", note = "", players = "";
        /** Friend refit: the market the host is docked at ("" none), and the refit screen's state. */
        String refitMarket = "";
        boolean refitOpen;
        wingman.net.RefitData refitData;
        Color hostColor = Panel.TEXT, noteColor = Panel.DIM;
        int at;
        boolean hostInBattle;
    }

    LabelAPI label(String text, String font, Color color, float width) {
        String key = font + "|" + color.getRGB() + "|" + Math.round(width) + "|" + text;
        LabelAPI l = cache.get(key);
        if (l == null) {
            if (cache.size() > 400) cache.clear();
            l = Global.getSettings().createLabel(text, font);
            l.setColor(color);
            l.autoSizeToWidth(width);
            cache.put(key, l);
        }
        return l;
    }

    /** Draws text with its top edge at 'top'; returns the y just below it. */
    float text(String s, String font, Color c, float x, float top, float width) {
        if (s == null || s.isEmpty()) return top;
        LabelAPI l = label(s, font, c, width);
        float h = l.getPosition().getHeight();
        l.getPosition().setLocation(x, top - h);
        l.render(1f);
        return top - h;
    }

    static void fill(float x, float y, float w, float h, Color c) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    static void frame(float x, float y, float w, float h) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(LINE.getRed() / 255f, LINE.getGreen() / 255f, LINE.getBlue() / 255f, LINE.getAlpha() / 255f);
        GL11.glLineWidth(1f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    void render(View v) {
        if (broken) return;
        try {
            if (body == null) body = Global.getSettings().getString("defaultFont");
            refitButton = null;
            hits.clear();
            listBox = null;
            loadBudget = 4;
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float m = Math.max(24f, sw * 0.022f);
            fill(0f, 0f, sw, sh, new Color(4, 7, 12, 238));

            // Header: what this is and the pages, then a line on where the host is and who is here. LunaLib draws
            // its own buttons in the top-left corner of the title screen (over anything there): the header starts
            // to their right, and the content below them.
            float hx = m + lunaCorner();
            float top = sh - m;
            float titleH = Style.lineHeight(BIG);
            text("CO-OP LOBBY", BIG, TITLE, hx, top, 400f);
            List<String> tabs = new java.util.ArrayList<String>();
            int active;
            if (page == REFIT) { tabs.add("Fleet"); tabs.add("Last battle"); tabs.add("Refit"); active = 2; }
            else { tabs.add("Fleet"); tabs.add("Last battle"); active = page == FLEET ? 0 : 1; }
            float tabsW = 0f;
            for (String t : tabs) tabsW += Style.width(t.toUpperCase(), Style.SMALL_FONT) + 30f;
            float[] tat = Style.tabs(tabs, active, sw - m - v.reserveRight - tabsW, top + 2f, tabsW); // (right of them: MAIN MENU, DISCONNECT)
            int[] pages = { FLEET, REPORT, REFIT };
            for (int i = 0; i < tabs.size(); i++) hits.add(new float[] { tat[i * 2], top + 2f - Style.TAB_H, tat[i * 2 + 1] - tat[i * 2], Style.TAB_H, TAB, pages[i], 0 });
            float y = top - titleH - 10f;
            // the host and the players, on one line each, under the title
            if (!v.hostLine.isEmpty()) y = text(v.hostLine, body, v.hostColor, hx, y, sw - m - hx) - 2f;
            if (!v.players.isEmpty()) y = text(v.players, body, Panel.DIM, hx, y, sw - m - hx) - 2f;
            if (!v.refitMarket.isEmpty() && page != REFIT) {
                Roster.Member mine = v.roster == null ? null : v.roster.pickOf(v.me);
                y = Style.callout("Refit available at " + v.refitMarket + (mine == null ? ": pick a ship, then refit it here." : ": R, or the Refit button on your ship's card."),
                        Panel.GOOD, hx, y - 4f, Math.min(sw - m - hx, 760f)) - 2f;
            }
            if (v.hostInBattle && page != REFIT) { // join the host's battle, for the mouse (Enter)
                float bw = 220f, bh = 34f, bx = sw - m - bw, by = top - Style.TAB_H - 14f - bh;
                Style.button(bx, by, bw, bh, "JOIN THE BATTLE", Panel.GOOD, true, inside(bx, by, bw, bh));
                hits.add(new float[] { bx, by, bw, bh, KEY, 28, 0 });
                y = Math.min(y, by - 4f);
            }
            y -= 10f;
            if (lunaCorner() > 0f) y = Math.min(y, sh * 0.975f - LUNA_BUTTONS_H - 8f);
            fill(m, y, sw - 2 * m, 1f, LINE);
            float contentTop = y - 14f;

            // Footer: a note (if any) and the keys, as key chips.
            String keys = page == REFIT ? "Click: choose     Mouse wheel: scroll the lists     Esc: back"
                    : page == FLEET
                    ? "Up/Down: choose     PgUp/PgDn: page     F: pick this ship     Delete: no pick     " + (v.hostInBattle ? "Enter: join the battle" : "Enter: pick")
                            + (v.roster != null && v.roster.pickOf(v.me) != null && !v.hostInBattle ? "     R: refit" : "") + "     Tab: last battle     F1: how to     Esc: back"
                    : "Tab: the fleet     " + (v.hostInBattle ? "Enter: join the battle     " : "") + "Esc: back";
            List<String[]> k = Style.keys(keys);
            float keysH = Style.keysHeight(k, sw - 2 * m);
            float noteH = v.note.isEmpty() ? 0f : Style.height(v.note, body, sw - 2 * m - 14f) + 12f;
            float bottom = m + keysH + noteH + 14f;

            if (page == FLEET) fleet(v, m, contentTop, sw - 2 * m, contentTop - bottom);
            else if (page == REFIT) refit.render(v, m, contentTop, sw - 2 * m, contentTop - bottom);
            else report(m, contentTop, sw - 2 * m, contentTop - bottom);

            fill(m, bottom - 7f, sw - 2 * m, 1f, LINE);
            float fy = bottom - 14f;
            if (!v.note.isEmpty()) fy = Style.callout(v.note, v.noteColor == Panel.DIM ? Style.ACCENT : v.noteColor, m, fy, sw - 2 * m) - 6f;
            List<float[]> kh = new java.util.ArrayList<float[]>();
            Style.drawKeys(k, m, fy, sw - 2 * m, kh);
            for (float[] q : kh) hits.add(new float[] { q[0], q[1], q[2], q[3], KEY, q[4], q[5] });
            Tips.draw(); // last: the pop-up over a weapon, wing or hull mod is on top of everything
            failures = 0;
        } catch (Throwable t) {
            cache.clear();
            Style.forget();
            if (++failures >= 60) {
                broken = true;
                Global.getLogger(LobbyScreen.class).error("Wingman: the lobby screen keeps failing to draw and is switched off (the corner box is used)", t);
            }
        }
    }

    boolean isBroken() { return broken; }

    static boolean inside(float x, float y, float w, float h) {
        float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** LunaLib's "Mod Settings" and "Version Checker" buttons: 130 wide from x 5, from 2.5% below the top, 105 tall. */
    static final float LUNA_BUTTONS_W = 140f, LUNA_BUTTONS_H = 110f;
    private static Boolean luna;

    /** How far right of the margin the header starts: clear of LunaLib's buttons when LunaLib is on. */
    static float lunaCorner() {
        if (luna == null) {
            try { luna = Global.getSettings().getModManager().isModEnabled("lunalib"); } catch (Throwable t) { luna = Boolean.FALSE; }
        }
        return luna.booleanValue() ? LUNA_BUTTONS_W : 0f;
    }

    private static String sizeTag(String hullSize) {
        if ("FRIGATE".equals(hullSize)) return "FF";
        if ("DESTROYER".equals(hullSize)) return "DD";
        if ("CRUISER".equals(hullSize)) return "CA";
        if ("CAPITAL_SHIP".equals(hullSize)) return "BB";
        return "--";
    }

    private void fleet(View v, float x, float top, float w, float h) {
        Roster r = v.roster;
        float gap = 14f;
        float listW = w * 0.36f, cardW = w * 0.37f, sideW = w - listW - cardW - 2 * gap;
        float cardX = x + listW + gap, sideX = cardX + cardW + gap;
        int n = r == null ? 0 : r.members.size();
        float y = Style.window(x, top - h, listW, h, "HOST'S FLEET", n > 0 ? n + " ship" + (n == 1 ? "" : "s") : "") - 10f;
        float px = x + Style.PAD, pw = listW - 2 * Style.PAD;
        if (r == null || n == 0) {
            text(r == null ? "The host's fleet shows here when the host is in a campaign." : "The host's fleet isn't available (the host is not in a campaign).",
                    body, Panel.DIM, px, y, pw);
            Style.window(cardX, top - h, cardW, h, "SHIP", "");
        } else {
            float rowH = 28f;
            float floor = top - h + (n > 0 ? 34f : 10f);
            rows = Math.max(3, (int) ((y - floor) / rowH));
            int at = Math.max(0, Math.min(n - 1, v.at));
            listBox = new float[] { x, top - h, listW, h };
            int from = Math.max(0, Math.min(at - rows / 2, n - rows)), to = Math.min(n, from + rows);
            float bh = Style.lineHeight(body);
            for (int i = from; i < to; i++) {
                Roster.Member mem = r.members.get(i);
                boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
                boolean blocked = (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED | Roster.CANT_FLY)) != 0 || (!mem.pickedBy.isEmpty() && !mine);
                float ry = y - rowH;
                boolean hover = inside(x + 6f, ry, listW - 12f, rowH);
                if (i == at) { fill(x + 6f, ry, listW - 12f, rowH, MARK); fill(x + 6f, ry, 3f, rowH, TITLE); }
                else if (hover) fill(x + 6f, ry, listW - 12f, rowH, Style.HOVER);
                else if ((i - from) % 2 == 1) fill(x + 6f, ry, listW - 12f, rowH, new Color(255, 255, 255, 5));
                hits.add(new float[] { x + 6f, ry, listW - 12f, rowH, ROW, i, 0 });
                Color c = mine ? Panel.GOOD : blocked ? Panel.DIM : Panel.TEXT;
                String tag = (mem.flags & Roster.FLAGSHIP) != 0 ? "flagship" : (mem.flags & Roster.MOTHBALLED) != 0 ? "mothballed"
                        : (mem.flags & Roster.CANT_FLY) != 0 ? "can't fly" : mine ? "yours" : mem.pickedBy;
                float ch = Style.chipHeight();
                Style.chip(sizeTag(mem.hullSize), blocked ? Style.FAINT : Panel.DIM, px, ry + (rowH + ch) / 2f);
                float nameX = px + 38f;
                String right = mem.cr >= 0 ? mem.cr + "%" : "";
                float rx = x + listW - Style.PAD;
                if (!right.isEmpty()) rx = Style.textRight(right, body, mem.cr < 40 ? Panel.WARN : Panel.DIM, rx, ry + (rowH + bh) / 2f) - 10f;
                if (!tag.isEmpty()) {
                    String t = tag.toUpperCase();
                    float tw = Style.width(t, Style.SMALL_FONT) + 10f;
                    rx -= tw;
                    Style.chip(tag, mine ? Panel.GOOD : (mem.flags & Roster.FLAGSHIP) != 0 ? Style.ACCENT : Panel.WARN, rx, ry + (rowH + ch) / 2f);
                    rx -= 8f;
                }
                text(Roster.label(mem), body, c, nameX, ry + (rowH + bh) / 2f, Math.max(40f, rx - nameX));
                y -= rowH;
            }
            if (n > rows) {
                String more = (from + 1) + "-" + to + " of " + n;
                Style.rule(x + 1f, top - h + 30f, listW - 2f);
                text(more, body, Panel.DIM, px, top - h + 24f, pw);
            }
            card(r.members.get(at), v, cardX, top, cardW, h);
        }
        side(v, sideX, top, sideW, h);
    }

    private void card(Roster.Member mem, View v, float x, float top, float w, float h) {
        String size = "CAPITAL_SHIP".equals(mem.hullSize) ? "capital" : mem.hullSize.toLowerCase();
        float y = Style.window(x, top - h, w, h, Roster.label(mem), (size.isEmpty() ? "" : size) + (mem.cr >= 0 ? "   CR " + mem.cr + "%" : "")) - 10f;
        float px = x + Style.PAD, pw = w - 2 * Style.PAD;
        if (!mem.designation.isEmpty()) y = text(mem.designation, body, Panel.DIM, px, y, pw) - 4f;
        if (!mem.dmods.isEmpty()) y = Style.callout("D-mods: " + mem.dmods, Panel.BAD, px, y, pw) - 6f;
        // The ship's picture, fitted into a well, nose up as the game draws hulls.
        float box = Math.min(pw * 0.8f, Math.max(80f, h * 0.22f));
        fill(px, y - box, pw, box, Style.WELL);
        if (!mem.sprite.isEmpty()) {
            try {
                ShipPicture.draw(mem.hullId, mem.sprite, mem.modules, mem.fits, x + w / 2f, y - box / 2f, box - 8f);
            } catch (Throwable t) { /* no picture */ }
        }
        y -= box + 12f;

        // What happens with it, and the refit button, at the bottom of the card; the sections fill what is between.
        String state = (mem.flags & Roster.FLAGSHIP) != 0 ? "The host's flagship."
                : (mem.flags & Roster.MOTHBALLED) != 0 ? "Mothballed."
                : (mem.flags & Roster.CANT_FLY) != 0 ? "Can't be flown" + (mem.note.isEmpty() ? "." : ": " + mem.note + ".")
                : mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty() ? "Your ship for the next battle, if the host deploys it."
                : !mem.pickedBy.isEmpty() ? mem.pickedBy + " has picked it." : "Free to pick for the next battle.";
        Color c = state.startsWith("Your") ? Panel.GOOD : state.startsWith("Free") ? Style.ACCENT : Panel.WARN;
        boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
        boolean refitHere = mine && !v.hostInBattle && (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED)) == 0;
        boolean pickable = !mine && mem.pickedBy.isEmpty() && (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED | Roster.CANT_FLY)) == 0;
        boolean buttons = refitHere || pickable || mine;
        float stateH = Style.height(state, body, pw - 14f) + 6f;
        float floor = top - h + 12f + stateH + (buttons ? 44f : 0f) + 8f;

        y = Style.section("Officer", px, y, pw) - 2f;
        y = officer(mem, px, y, pw) - 12f;
        y = Style.section("Weapons and wings", px, y, pw) - 2f;
        y = gear(mem, px, y, pw, floor + 24f) - 10f;
        if (!mem.hullmods.isEmpty() && y - 40f > floor) {
            y = Style.section("Hull mods", px, y, pw) - 2f;
            modList(mem.hullmods, px, y, pw);
        }

        float sy = top - h + 12f + stateH;
        float bx = px, bh = 34f, by = sy + 10f;
        if (pickable) { // pick it (F)
            float bw = Math.min(pw * 0.5f, 220f);
            Style.button(bx, by, bw, bh, "PICK THIS SHIP", Panel.GOOD, true, inside(bx, by, bw, bh));
            hits.add(new float[] { bx, by, bw, bh, KEY, 33, 0 });
            bx += bw + 10f;
        }
        if (refitHere) {
            // Friend refit: only on the ship we picked. Docked, it can be sent; away from a market, planned and kept.
            boolean docked = !v.refitMarket.isEmpty();
            float bw = Math.min(pw * 0.55f, docked ? 180f : 220f);
            boolean over = inside(bx, by, bw, bh);
            Style.button(bx, by, bw, bh, docked ? "REFIT" : "PLAN A REFIT", docked ? Panel.GOOD : TITLE, true, over);
            refitButton = new float[] { bx, by, bw, bh };
            bx += bw + 10f;
        }
        if (mine) { // no pick (Delete)
            float bw = Math.min(px + pw - bx, 150f);
            if (bw > 60f) {
                Style.button(bx, by, bw, bh, "UNPICK", Panel.WARN, true, inside(bx, by, bw, bh));
                hits.add(new float[] { bx, by, bw, bh, KEY, 211, 0 });
            }
        }
        Style.callout(state, c, px, sy, pw);
    }

    /** Hull mods one after another, wrapping: S-mods (marked "(S)") in green, the rest in the usual colour. */
    private float modList(String mods, float x, float top, float w) {
        float lh = Style.lineHeight(body), cx = x, cy = top;
        String[] parts = mods.split(", ");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i] + (i + 1 < parts.length ? "," : "");
            boolean s = parts[i].endsWith(" (S)");
            float pw = Style.width(p, body);
            if (cx > x && cx + pw > x + w) { cx = x; cy -= lh + 2f; }
            Style.text(p, body, s ? Panel.GOOD : Panel.TEXT, cx, cy, pw + 20f);
            cx += pw + Style.width(" ", body) + 2f;
        }
        return cy - lh;
    }

    // ---- the officer and the loadout, with their pictures ----

    private static final Color ELITE = new Color(255, 200, 60), ICON_BACK = new Color(255, 255, 255, 18);
    private static final java.util.Set<String> loadTried = new java.util.HashSet<String>();
    /** Pictures that may still be loaded this frame (set each frame the lobby draws). */
    static int loadBudget = 4;

    /** A sprite by its path, loaded if the game hasn't yet (portraits load as they are first shown); null if none. */
    static SpriteAPI sprite(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            SpriteAPI s = Global.getSettings().getSprite(path);
            // (only a picture under graphics/ is ever loaded from what the host names, and each once, at most 600, a few a frame)
            boolean picture = path.startsWith("graphics/") && !path.contains("..") && (path.endsWith(".png") || path.endsWith(".jpg"));
            if ((s == null || s.getWidth() <= 0f) && picture && loadBudget > 0 && loadTried.size() < 600 && loadTried.add(path)) {
                loadBudget--; // a few new pictures a frame, so a long list doesn't stall the screen
                Global.getSettings().loadTexture(path);
                s = Global.getSettings().getSprite(path);
            }
            return s == null || s.getWidth() <= 0f ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Draw a sprite fitted into a square (bottom-left x, y), keeping its shape. */
    static void icon(SpriteAPI s, float x, float y, float size) {
        if (s == null) return;
        float w0 = s.getWidth(), h0 = s.getHeight();
        float k = Math.min(size / w0, size / h0);
        s.setSize(w0 * k, h0 * k);
        s.setAngle(0f);
        s.setAlphaMult(1f);
        s.setColor(Color.WHITE);
        s.renderAtCenter(x + size / 2f, y + size / 2f);
        s.setSize(w0, h0);
    }

    static void outline(float x, float y, float w, float h, Color c) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
        GL11.glLineWidth(2f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GL11.glLineWidth(1f);
        GL11.glPopAttrib();
    }

    /** The officer: portrait, name, level and personality, and their skills as icons (elite ones framed in gold). */
    private float officer(Roster.Member mem, float x, float top, float w) {
        if (mem.officer.isEmpty()) return text("No officer", body, Panel.DIM, x, top, w);
        float pic = 64f;
        SpriteAPI portrait = sprite(mem.portrait);
        if (portrait != null) { fill(x, top - pic, pic, pic, ICON_BACK); icon(portrait, x, top - pic, pic); outline(x, top - pic, pic, pic, LINE); }
        float tx = x + (portrait != null ? pic + 10f : 0f), tw = w - (tx - x);
        float y = text(mem.officer, body, Panel.TEXT, tx, top, tw);
        y = text((mem.officerLevel > 0 ? "Level " + mem.officerLevel : "") + (mem.personality.isEmpty() ? "" : (mem.officerLevel > 0 ? ", " : "") + mem.personality.toLowerCase()),
                body, Panel.DIM, tx, y - 2f, tw) - 4f;
        // Skill icons, wrapping within the space right of the portrait.
        float s = 26f, gap = 4f, sx = tx;
        int elite = 0;
        StringBuilder names = new StringBuilder();
        for (String sk : mem.skills) {
            int c = sk.lastIndexOf(':');
            String id = c < 0 ? sk : sk.substring(0, c);
            int level = 1;
            try { level = Integer.parseInt(sk.substring(c + 1)); } catch (Throwable t) { /* 1 */ }
            com.fs.starfarer.api.characters.SkillSpecAPI spec;
            try { spec = Global.getSettings().getSkillSpec(id); } catch (Throwable t) { spec = null; }
            if (sx + s > tx + tw) { sx = tx; y -= s + gap; }
            SpriteAPI ic = spec == null ? null : sprite(spec.getSpriteName());
            fill(sx, y - s, s, s, ICON_BACK);
            if (ic != null) icon(ic, sx, y - s, s);
            if (level >= 2) { outline(sx, y - s, s, s, ELITE); elite++; }
            sx += s + gap;
            names.append(names.length() == 0 ? "" : ", ").append(spec == null ? id : spec.getName()).append(level >= 2 ? " (elite)" : "");
        }
        if (!mem.skills.isEmpty()) y -= s + 4f;
        float bottom = Math.min(y, top - pic - 4f);
        if (names.length() > 0) bottom = text(names.toString(), body, Panel.DIM, x, bottom - 2f, w);
        return bottom;
    }

    /** Weapons and wings: each kind with its picture and how many, in two columns; what doesn't fit is counted. */
    private float gear(Roster.Member mem, float x, float top, float w, float floor) {
        if (mem.gear.isEmpty()) {
            if (mem.weapons.isEmpty()) return text("None", body, Panel.DIM, x, top, w);
            return text(mem.weapons, body, Panel.TEXT, x, top, w);
        }
        float y = top;
        float s = 28f, rowH = s + 4f, colW = (w - 8f) / 2f;
        int shown = 0;
        for (int i = 0; i < mem.gear.size(); i++) {
            int col = shown % 2;
            float rowTop = y - (shown / 2) * rowH;
            if (rowTop - rowH < floor && i < mem.gear.size() - 1) {
                text("and " + (mem.gear.size() - i) + " more", body, Panel.DIM, x + col * (colW + 8f), rowTop - 6f, colW);
                shown++;
                break;
            }
            String[] f = mem.gear.get(i).split("\\|");
            if (f.length < 3) continue;
            String name = f[1];
            SpriteAPI ic = null;
            try {
                if ("w".equals(f[0])) {
                    com.fs.starfarer.api.loading.WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(f[1]);
                    name = ws.getWeaponName();
                    ic = sprite(ws.getTurretSpriteName());
                    if (ic == null) ic = sprite(ws.getHardpointSpriteName());
                } else {
                    com.fs.starfarer.api.loading.FighterWingSpecAPI fs = Global.getSettings().getFighterWingSpec(f[1]);
                    name = fs.getWingName();
                    ic = sprite(fs.getVariant().getHullSpec().getSpriteName());
                }
            } catch (Throwable t) { /* its id, without a picture */ }
            float cx = x + col * (colW + 8f);
            if (inside(cx, rowTop - s, colW, s)) Tips.hover(("f".equals(f[0]) ? "f:" : "w:") + f[1], false, mem.hullSize, -1);
            fill(cx, rowTop - s, s, s, ICON_BACK);
            icon(ic, cx, rowTop - s, s);
            String count = f[2].equals("1") ? "" : f[2] + "x ";
            text(count + name, body, "f".equals(f[0]) ? Panel.GOOD : Panel.TEXT, cx + s + 6f, rowTop - 6f, colW - s - 6f);
            shown++;
        }
        return y - ((shown + 1) / 2) * rowH;
    }

    private void side(View v, float x, float top, float w, float h) {
        float gap = 14f, px = x + Style.PAD, pw = w - 2 * Style.PAD;
        BattleReport r = AfterAction.report;
        // Your ship
        Roster.Member mine = v.roster == null ? null : v.roster.pickOf(v.me);
        String yours = mine == null ? "Not picked yet. Pick one here, or choose in the battle." : Roster.label(mine);
        float yoursH = Style.BAR_H + 22f + Style.height(yours, body, pw - 14f) + 6f;
        Style.window(x, top - yoursH, w, yoursH, "YOUR SHIP", "");
        Style.callout(yours, mine == null ? Panel.WARN : Panel.GOOD, px, top - Style.BAR_H - 12f, pw);
        // Last battle (at the bottom)
        float lastH = r == null ? 0f : Style.BAR_H + 70f;
        if (r != null) {
            float ly = Style.window(x, top - h, w, lastH, "LAST BATTLE", minutes(r.seconds)) - 10f;
            ly = text(outcome(r).toUpperCase(), TITLE_FONT_BIG, outcomeColor(r), px, ly, pw) - 4f;
            boolean over = inside(x, top - h, w, lastH);
            text("Click for the full report.", body, over ? Panel.TEXT : Panel.DIM, px, ly, pw);
            hits.add(new float[] { x, top - h, w, lastH, TAB, REPORT, 0 });
        }
        // Players, between
        float pTop = top - yoursH - gap, pH = h - yoursH - gap - (r == null ? 0f : lastH + gap);
        float y = Style.window(x, pTop - pH, w, pH, "PLAYERS", v.roster == null ? "" : String.valueOf(1 + v.roster.players.size())) - 8f;
        if (v.roster != null) {
            y = player(1, v.roster.hostName + "  (host)", null, false, px, y, pw, pTop - pH);
            for (Roster.Player p : v.roster.players) {
                if (y < pTop - pH + 30f) break;
                y = player(p.slot, p.name, v.roster.pickOf(p.name), p.name.equalsIgnoreCase(v.me), px, y, pw, pTop - pH);
            }
        }
    }

    private static final String TITLE_FONT_BIG = "graphics/fonts/orbitron20aabold.fnt";

    /** One player: their tag in their colour, their name, and the ship they picked under it. */
    private float player(int slot, String name, Roster.Member pick, boolean me, float x, float top, float w, float floor) {
        Color pc = PlayerTags.color(slot);
        float cw = Style.chip("P" + slot, pc, x, top - 3f);
        float y = text(name + (me ? "  (you)" : ""), body, me ? Panel.GOOD : Panel.TEXT, x + cw + 8f, top - 2f, w - cw - 8f);
        y = text(slot == 1 ? "commands the fleet" : pick == null ? "no ship picked" : Roster.label(pick), body, pick == null ? Style.FAINT : Panel.DIM, x + cw + 8f, y, w - cw - 8f);
        return y - 8f;
    }

    private static String outcome(BattleReport r) { return r.outcome == 0 ? "Victory" : r.outcome == 1 ? "Defeat" : "No winner"; }
    private static Color outcomeColor(BattleReport r) { return r.outcome == 0 ? Panel.GOOD : r.outcome == 1 ? Panel.BAD : Panel.WARN; }
    private static String minutes(float s) {
        int t = Math.round(s);
        return t / 60 + ":" + (t % 60 < 10 ? "0" : "") + t % 60;
    }
    private static String num(float f) {
        if (f >= 10000f) return Math.round(f / 1000f) + "k";
        return String.valueOf(Math.round(f));
    }
    private static String status(int s) {
        switch (s) {
            case BattleReport.ALIVE: return "made it";
            case BattleReport.DESTROYED: return "ship lost";
            case BattleReport.RETREATED: return "retreated";
            case BattleReport.WATCHED: return "watched";
            default: return "left early";
        }
    }

    private void report(float x, float top, float w, float h) {
        BattleReport r = AfterAction.report;
        float y = Style.window(x, top - h, w, h, "LAST BATTLE", r == null ? "" : "battle time " + minutes(r.seconds) + ", as the host's game counted it") - 14f;
        float px = x + 20f, pw = w - 40f;
        if (r == null) {
            text("No battle yet. The report of each battle you play with the host shows here.", body, Panel.DIM, px, y, pw);
            return;
        }
        y = text(outcome(r).toUpperCase(), BIG, outcomeColor(r), px, y, pw) - 14f;

        // The players, one row each, as a table.
        y = Style.section("Pilots", px, y, pw) - 2f;
        float[] col = { 0f, 0.06f, 0.22f, 0.50f, 0.62f, 0.70f, 0.78f, 0.90f };
        String[] head = { "", "PLAYER", "SHIP", "", "KILLS", "FIGHTERS", "DAMAGE", "TAKEN" };
        float rowH = 26f, sh = Style.lineHeight(Style.SMALL_FONT), bh = Style.lineHeight(body);
        fill(px, y - rowH, pw, rowH, Style.BAR);
        for (int i = 0; i < head.length; i++) text(head[i], Style.SMALL_FONT, Style.DIM, px + 8f + col[i] * (pw - 16f), y - (rowH - sh) / 2f, (pw - 16f) * 0.2f);
        y -= rowH;
        int rowN = 0;
        for (BattleReport.Pilot p : r.pilots) {
            if (rowN++ % 2 == 1) fill(px, y - rowH, pw, rowH, new Color(255, 255, 255, 6));
            String[] cells = { "", p.name, p.ship.isEmpty() ? "-" : p.ship, status(p.status), String.valueOf(p.kills),
                    String.valueOf(p.fighterKills), num(p.damageDealt) + (p.shieldDamageDealt > 0 ? " (+" + num(p.shieldDamageDealt) + " shields)" : ""), num(p.damageTaken) };
            Color c = p.status == BattleReport.DESTROYED ? Panel.WARN : Panel.TEXT;
            if (p.slot > 0) Style.chip("P" + p.slot, PlayerTags.color(p.slot), px + 8f, y - (rowH - Style.chipHeight()) / 2f);
            for (int i = 1; i < cells.length; i++) {
                float cw = (i + 1 < col.length ? col[i + 1] - col[i] : 0.10f) * (pw - 16f) - 6f;
                text(cells[i], body, i == 3 ? (p.status == BattleReport.DESTROYED ? Panel.BAD : Panel.DIM) : c, px + 8f + col[i] * (pw - 16f), y - (rowH - bh) / 2f, Math.max(40f, cw));
            }
            y -= rowH;
        }
        y -= 16f;
        // Losses on both sides.
        float half = (pw - 24f) / 2f;
        float ly = y;
        for (int side = 0; side < 2; side++) {
            BattleReport.Side s = r.sides[side];
            float sx = px + side * (half + 24f);
            float yy = Style.section(side == 0 ? "Our side" : "Their side", sx, ly, half) - 2f;
            yy = text(s.deployed + " deployed, " + (s.destroyed + s.disabled) + " lost, " + s.retreated + " retreated" + (s.fightersLost > 0 ? ", " + s.fightersLost + " fighters down" : ""),
                    body, Panel.TEXT, sx, yy, half) - 4f;
            for (String lost : s.lost) yy = text("Lost:  " + lost, body, side == 0 ? Panel.WARN : Panel.DIM, sx, yy, half) - 1f;
            y = Math.min(y, yy);
        }
        y -= 16f;
        Aftermath a = AfterAction.aftermath;
        y = Style.section("Aftermath", px, y, pw) - 2f;
        if (a == null) {
            text("What the battle changed in the host's fleet (salvage, recovered ships, losses) shows here once the host is back on the map.",
                    body, Panel.DIM, px, y, pw);
            return;
        }
        y = text("Credits " + (a.credits >= 0 ? "+" : "-") + grouped(Math.abs(Math.round(a.credits))), body, a.credits >= 0 ? Panel.GOOD : Panel.WARN, px, y, pw) - 2f;
        if (!a.shipsGained.isEmpty()) y = text("Ships added: " + join(a.shipsGained), body, Panel.GOOD, px, y, pw) - 2f;
        if (!a.shipsLost.isEmpty()) y = text("Ships gone: " + join(a.shipsLost), body, Panel.WARN, px, y, pw) - 2f;
        if (!a.cargoNames.isEmpty()) {
            StringBuilder sb = new StringBuilder("Cargo: ");
            for (int i = 0; i < a.cargoNames.size(); i++) {
                float c = a.cargoChanges.get(i);
                sb.append(i == 0 ? "" : ",  ").append(c >= 0 ? "+" : "").append(Math.round(c)).append(' ').append(a.cargoNames.get(i));
            }
            text(sb.toString(), body, Panel.TEXT, px, y, pw);
        }
    }

    static String grouped(long n) {
        String s = String.valueOf(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(',');
            sb.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }
}
