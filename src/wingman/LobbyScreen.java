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
    private final Map<String, LabelAPI> cache = new HashMap<String, LabelAPI>();
    private boolean broken;
    private int failures;
    /** How many fleet rows fit; the title plugin pages by this. */
    int rows = 12;

    /** Everything the screen shows, gathered by the title plugin each frame. */
    static final class View {
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
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float m = Math.max(24f, sw * 0.025f);
            fill(0f, 0f, sw, sh, new Color(4, 7, 12, 236));

            // Header: what this is, where the host is, the two pages. LunaLib draws its own buttons in the
            // top-left corner of the title screen (over anything there): the header starts to their right, and
            // the content below them.
            float hx = m + lunaCorner();
            float top = sh - m;
            float y = text("CO-OP LOBBY", BIG, TITLE, hx, top, 400f);
            String tabs = page == REFIT ? "[ REFIT ]"
                    : (page == FLEET ? "[ FLEET ]" : "  FLEET  ") + "    " + (page == REPORT ? "[ LAST BATTLE ]" : "  LAST BATTLE  ");
            LabelAPI tl = label(tabs, HEAD, Panel.TEXT, 600f);
            text(tabs, HEAD, Panel.TEXT, sw - m - Math.min(600f, tl.computeTextWidth(tabs)) - 4f, top, 600f);
            y = text(v.hostLine, body, v.hostColor, hx, y - 6f, sw - m - hx) - 4f;
            y = text(v.players, body, Panel.DIM, hx, y, sw - m - hx) - 2f;
            if (!v.refitMarket.isEmpty() && page != REFIT) {
                Roster.Member mine = v.roster == null ? null : v.roster.pickOf(v.me);
                y = text("Refit available at " + v.refitMarket + (mine == null ? ": pick a ship, then refit it here." : ": R, or the Refit button on your ship's card."),
                        body, Panel.GOOD, hx, y, sw - m - hx) - 2f;
            }
            y -= 8f;
            if (lunaCorner() > 0f) y = Math.min(y, sh * 0.975f - LUNA_BUTTONS_H - 8f);
            fill(m, y, sw - 2 * m, 1f, LINE);
            float contentTop = y - 12f, footerH = 64f, bottom = m + footerH;

            if (page == FLEET) fleet(v, m, contentTop, sw - 2 * m, contentTop - bottom);
            else if (page == REFIT) refit.render(v, m, contentTop, sw - 2 * m, contentTop - bottom);
            else report(m, contentTop, sw - 2 * m, contentTop - bottom);

            fill(m, bottom - 6f, sw - 2 * m, 1f, LINE);
            String keys = page == REFIT ? "Up/Down: choose   Enter or click: change   Left/Right: vents, capacitors   Delete: hull mod off   B: build in   S: where the old one goes   Z: undo   Ctrl+Enter: send   Esc: back"
                    : page == FLEET
                    ? "Up/Down, PgUp/PgDn: choose     F: pick this ship     Delete: no pick     " + (v.hostInBattle ? "Enter: join the battle" : "Enter: pick")
                            + (v.refitMarket.isEmpty() ? "" : "     R: refit") + "     Tab: last battle     F1: how to     Esc: close"
                    : "Tab: the fleet     " + (v.hostInBattle ? "Enter: join the battle     " : "") + "Esc: close";
            if (!v.note.isEmpty()) text(v.note, body, v.noteColor, m, bottom - 12f, sw - 2 * m);
            text(keys, body, Panel.DIM, m, bottom - 36f, sw - 2 * m);
            failures = 0;
        } catch (Throwable t) {
            cache.clear();
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
    private static final float LUNA_BUTTONS_W = 140f, LUNA_BUTTONS_H = 110f;
    private static Boolean luna;

    /** How far right of the margin the header starts: clear of LunaLib's buttons when LunaLib is on. */
    private static float lunaCorner() {
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
        float listW = w * 0.40f, cardW = w * 0.34f, sideW = w - listW - cardW - 32f;
        float cardX = x + listW + 16f, sideX = cardX + cardW + 16f;
        frame(x, top - h, listW, h);
        frame(cardX, top - h, cardW, h);
        int n = r == null ? 0 : r.members.size();
        float y = text("HOST'S FLEET" + (n > 0 ? "  (" + n + ")" : ""), HEAD, TITLE, x + 12f, top - 10f, listW - 24f) - 8f;
        if (r == null || n == 0) {
            text(r == null ? "The host's fleet shows here when the host is in a campaign." : "The host's fleet isn't available (the host is not in a campaign).",
                    body, Panel.DIM, x + 12f, y, listW - 24f);
        } else {
            float rowH = 24f;
            rows = Math.max(3, (int) ((y - (top - h) - 10f) / rowH));
            int at = Math.max(0, Math.min(n - 1, v.at));
            int from = Math.max(0, Math.min(at - rows / 2, n - rows)), to = Math.min(n, from + rows);
            for (int i = from; i < to; i++) {
                Roster.Member mem = r.members.get(i);
                boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
                boolean blocked = (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED | Roster.CANT_FLY)) != 0 || (!mem.pickedBy.isEmpty() && !mine);
                if (i == at) fill(x + 4f, y - rowH + 3f, listW - 8f, rowH, MARK);
                Color c = mine ? Panel.GOOD : blocked ? Panel.DIM : Panel.TEXT;
                String tag = (mem.flags & Roster.FLAGSHIP) != 0 ? "flagship" : (mem.flags & Roster.MOTHBALLED) != 0 ? "mothballed"
                        : (mem.flags & Roster.CANT_FLY) != 0 ? "can't fly" : mine ? "YOURS" : mem.pickedBy;
                text(sizeTag(mem.hullSize), body, Panel.DIM, x + 12f, y - 3f, 40f);
                text(Roster.label(mem), body, c, x + 46f, y - 3f, listW * 0.62f);
                String right = (mem.cr >= 0 ? mem.cr + "%" : "") + (tag.isEmpty() ? "" : "  " + tag);
                LabelAPI rl = label(right, body, mine ? Panel.GOOD : Panel.DIM, listW * 0.3f);
                text(right, body, mine ? Panel.GOOD : Panel.DIM, x + listW - 12f - Math.min(listW * 0.3f, rl.computeTextWidth(right)), y - 3f, listW * 0.3f);
                y -= rowH;
            }
            if (n > rows) text((at + 1) + " of " + n, body, Panel.DIM, x + 12f, top - h + 24f, listW - 24f);
            card(r.members.get(at), v, cardX, top, cardW, h);
        }
        side(v, sideX, top, sideW, h);
    }

    private void card(Roster.Member mem, View v, float x, float top, float w, float h) {
        float y = text(Roster.label(mem), HEAD, TITLE, x + 12f, top - 10f, w - 24f) - 4f;
        String size = "CAPITAL_SHIP".equals(mem.hullSize) ? "capital" : mem.hullSize.toLowerCase();
        y = text((mem.designation.isEmpty() ? "" : mem.designation + ", ") + size + (mem.cr >= 0 ? ",  CR " + mem.cr + "%" : ""), body, Panel.DIM, x + 12f, y, w - 24f) - 2f;
        if (!mem.dmods.isEmpty()) y = text("D-mods: " + mem.dmods, body, Panel.WARN, x + 12f, y, w - 24f) - 2f;
        y -= 4f;
        // The ship's picture, fitted into a box, nose up as the game draws hulls.
        float box = Math.min(w - 24f, Math.max(80f, h * 0.30f));
        if (!mem.sprite.isEmpty()) {
            try {
                ShipPicture.draw(mem.hullId, mem.sprite, mem.modules, mem.fits, x + w / 2f, y - box / 2f, box);
            } catch (Throwable t) { /* no picture */ }
        }
        y -= box + 8f;
        y = officer(mem, x + 12f, y, w - 24f) - 8f;
        y = gear(mem, x + 12f, y, w - 24f, top - h + 110f) - 4f;
        if (!mem.hullmods.isEmpty()) {
            y = text("Hull mods", body, TITLE, x + 12f, y, w - 24f) - 2f;
            y = text(mem.hullmods, body, Panel.TEXT, x + 12f, y, w - 24f) - 6f;
        }
        String state = (mem.flags & Roster.FLAGSHIP) != 0 ? "The host's flagship."
                : (mem.flags & Roster.MOTHBALLED) != 0 ? "Mothballed."
                : (mem.flags & Roster.CANT_FLY) != 0 ? "Can't be flown" + (mem.note.isEmpty() ? "." : ": " + mem.note + ".")
                : mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty() ? "Your ship for the next battle, if the host deploys it."
                : !mem.pickedBy.isEmpty() ? mem.pickedBy + " has picked it." : "Free. F picks it for the next battle.";
        Color c = state.startsWith("Your") ? Panel.GOOD : state.startsWith("Free") ? Panel.TEXT : Panel.WARN;
        float stateTop = Math.max(y, top - h + 40f);
        boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
        if (mine && !v.refitMarket.isEmpty() && (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED)) == 0) {
            // Friend refit: only on the ship we picked, only while the host is docked at a market.
            // Below what the card shows, with the state line under it, both kept inside the card.
            float bw = Math.min(w - 24f, 220f), bh = 30f, bx = x + 12f;
            float by = Math.max(y - 4f, top - h + 40f + bh + 8f) - bh;
            boolean over = inside(bx, by, bw, bh);
            fill(bx, by, bw, bh, over ? new Color(120, 210, 255, 90) : MARK);
            outline(bx, by, bw, bh, over ? TITLE : LINE);
            text("REFIT  (R)", HEAD, TITLE, bx + 10f, by + bh - 5f, bw - 20f);
            refitButton = new float[] { bx, by, bw, bh };
            stateTop = by - 8f;
        }
        text(state, body, c, x + 12f, stateTop, w - 24f);
    }

    // ---- the officer and the loadout, with their pictures ----

    private static final Color ELITE = new Color(255, 200, 60), ICON_BACK = new Color(255, 255, 255, 18);
    private static final java.util.Set<String> loadTried = new java.util.HashSet<String>();

    /** A sprite by its path, loaded if the game hasn't yet (portraits load as they are first shown); null if none. */
    static SpriteAPI sprite(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            SpriteAPI s = Global.getSettings().getSprite(path);
            // (only a picture under graphics/ is ever loaded from what the host names, and each once, at most 200)
            boolean picture = path.startsWith("graphics/") && !path.contains("..") && (path.endsWith(".png") || path.endsWith(".jpg"));
            if ((s == null || s.getWidth() <= 0f) && picture && loadTried.size() < 200 && loadTried.add(path)) {
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
            if (mem.weapons.isEmpty()) return top;
            float y = text("Weapons and wings", body, TITLE, x, top, w) - 2f;
            return text(mem.weapons, body, Panel.TEXT, x, y, w);
        }
        float y = text("Weapons and wings", body, TITLE, x, top, w) - 4f;
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
            fill(cx, rowTop - s, s, s, ICON_BACK);
            icon(ic, cx, rowTop - s, s);
            String count = f[2].equals("1") ? "" : f[2] + "x ";
            text(count + name, body, "f".equals(f[0]) ? Panel.GOOD : Panel.TEXT, cx + s + 6f, rowTop - 6f, colW - s - 6f);
            shown++;
        }
        return y - ((shown + 1) / 2) * rowH;
    }

    private void side(View v, float x, float top, float w, float h) {
        frame(x, top - h, w, h);
        float y = text("YOUR SHIP", HEAD, TITLE, x + 12f, top - 10f, w - 24f) - 4f;
        Roster.Member mine = v.roster == null ? null : v.roster.pickOf(v.me);
        y = text(mine == null ? "Not picked yet. Pick one here, or choose in the battle." : Roster.label(mine), body, mine == null ? Panel.WARN : Panel.GOOD, x + 12f, y, w - 24f) - 14f;
        y = text("PLAYERS", HEAD, TITLE, x + 12f, y, w - 24f) - 4f;
        if (v.roster != null) {
            y = text("P1  " + v.roster.hostName + " (host)", body, Panel.TEXT, x + 12f, y, w - 24f) - 2f;
            for (Roster.Player p : v.roster.players) {
                Roster.Member pick = v.roster.pickOf(p.name);
                y = text("P" + p.slot + "  " + p.name + (pick == null ? "" : ":  " + Roster.label(pick)), body,
                        p.name.equalsIgnoreCase(v.me) ? Panel.GOOD : Panel.TEXT, x + 12f, y, w - 24f) - 2f;
            }
        }
        y -= 12f;
        BattleReport r = AfterAction.report;
        if (r != null) {
            y = text("LAST BATTLE", HEAD, TITLE, x + 12f, y, w - 24f) - 4f;
            y = text(outcome(r) + ", " + minutes(r.seconds) + ". Tab: the full report.", body, outcomeColor(r), x + 12f, y, w - 24f) - 2f;
        }
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
        frame(x, top - h, w, h);
        if (r == null) {
            text("No battle yet. The report of each battle you play with the host shows here.", body, Panel.DIM, x + 16f, top - 16f, w - 32f);
            return;
        }
        float y = text(outcome(r).toUpperCase(), BIG, outcomeColor(r), x + 16f, top - 12f, w - 32f);
        y = text("Battle time " + minutes(r.seconds) + ", as the host's game counted it.", body, Panel.DIM, x + 16f, y - 2f, w - 32f) - 12f;

        // The players, one row each.
        float[] col = { 0f, 0.07f, 0.22f, 0.50f, 0.62f, 0.70f, 0.78f, 0.88f };
        String[] head = { "", "Player", "Ship", "", "Kills", "Fighters", "Damage", "Taken" };
        for (int i = 0; i < head.length; i++) text(head[i], body, TITLE, x + 16f + col[i] * (w - 32f), y, (w - 32f) * 0.2f);
        y -= 22f;
        for (BattleReport.Pilot p : r.pilots) {
            String[] cells = { p.slot > 0 ? "P" + p.slot : "", p.name, p.ship.isEmpty() ? "-" : p.ship, status(p.status), String.valueOf(p.kills),
                    String.valueOf(p.fighterKills), num(p.damageDealt) + (p.shieldDamageDealt > 0 ? " (+" + num(p.shieldDamageDealt) + " shields)" : ""), num(p.damageTaken) };
            Color c = p.status == BattleReport.DESTROYED ? Panel.WARN : Panel.TEXT;
            for (int i = 0; i < cells.length; i++) {
                float cw = (i + 1 < col.length ? col[i + 1] - col[i] : 0.12f) * (w - 32f) - 6f;
                text(cells[i], body, i == 3 ? (p.status == BattleReport.DESTROYED ? Panel.BAD : Panel.DIM) : c, x + 16f + col[i] * (w - 32f), y, Math.max(40f, cw));
            }
            y -= 22f;
        }
        y -= 12f;
        // Losses on both sides.
        float half = (w - 48f) / 2f;
        float ly = y;
        for (int side = 0; side < 2; side++) {
            BattleReport.Side s = r.sides[side];
            float sx = x + 16f + side * (half + 16f);
            float yy = text(side == 0 ? "OUR SIDE" : "THEIR SIDE", HEAD, TITLE, sx, ly, half) - 4f;
            yy = text(s.deployed + " deployed, " + (s.destroyed + s.disabled) + " lost, " + s.retreated + " retreated" + (s.fightersLost > 0 ? ", " + s.fightersLost + " fighters down" : ""),
                    body, Panel.TEXT, sx, yy, half) - 2f;
            for (String lost : s.lost) yy = text("lost:  " + lost, body, side == 0 ? Panel.WARN : Panel.DIM, sx, yy, half) - 1f;
            y = Math.min(y, yy);
        }
        y -= 14f;
        Aftermath a = AfterAction.aftermath;
        y = text("AFTERMATH", HEAD, TITLE, x + 16f, y, w - 32f) - 4f;
        if (a == null) {
            text("What the battle changed in the host's fleet (salvage, recovered ships, losses) shows here once the host is back on the map.",
                    body, Panel.DIM, x + 16f, y, w - 32f);
            return;
        }
        y = text("Credits " + (a.credits >= 0 ? "+" : "") + Math.round(a.credits), body, a.credits >= 0 ? Panel.GOOD : Panel.WARN, x + 16f, y, w - 32f) - 2f;
        if (!a.shipsGained.isEmpty()) y = text("Ships added: " + join(a.shipsGained), body, Panel.GOOD, x + 16f, y, w - 32f) - 2f;
        if (!a.shipsLost.isEmpty()) y = text("Ships gone: " + join(a.shipsLost), body, Panel.WARN, x + 16f, y, w - 32f) - 2f;
        if (!a.cargoNames.isEmpty()) {
            StringBuilder sb = new StringBuilder("Cargo: ");
            for (int i = 0; i < a.cargoNames.size(); i++) {
                float c = a.cargoChanges.get(i);
                sb.append(i == 0 ? "" : ",  ").append(c >= 0 ? "+" : "").append(Math.round(c)).append(' ').append(a.cargoNames.get(i));
            }
            text(sb.toString(), body, Panel.TEXT, x + 16f, y, w - 32f);
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }
}
