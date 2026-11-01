package wingman.client;

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
import wingman.Panel;
import wingman.PlayerTags;
import wingman.Style;

/**
 * The co-op lobby as a screen of its own (joiner side, on the title screen with the game's menu moved aside, see
 * TitleMenu): the host's fleet, the friend's linked officer with the ship they board next, a card for the marked
 * ship (picture, loadout, officer), the host's campaign and events, and the last battle's report. Drawn by hand like the rest of
 * Wingman's title screen panels; keys are read by WingmanTitlePlugin.
 */
public final class LobbyScreen {
    public static final int FLEET = 0, REPORT = 1, REFIT = 2;
    public int page = FLEET;

    public static final Color LINE = new Color(120, 210, 255, 90), MARK = new Color(120, 210, 255, 45);
    static final String BIG = "graphics/fonts/orbitron24aabold.fnt", HEAD = "graphics/fonts/orbitron20aabold.fnt";
    String body;
    /** The friend-refit screen, drawn as the lobby's third page. */
    public final RefitScreen refit = new RefitScreen(this);
    /** Where the Refit button was drawn this frame (x, y, w, h), or null: the title plugin reads clicks on it. */
    public float[] refitButton;
    /** Skill picker on the selected, linked officer's card. */
    public float[] skillButton;
    /** Bionic Alteration's workshop for that officer, beside the skill picker. */
    public float[] bionicButton;

    /** What can be clicked, as drawn this frame: {x, y, w, h, kind, value, ctrl}. KEY: a key code; ROW: a ship in the fleet list; TAB: a page. */
    public static final int KEY = 0, ROW = 1, TAB = 2, SCROLL = 3, SELECT = 4;
    final List<float[]> hits = new java.util.ArrayList<float[]>();
    /** The fleet list's box, for the mouse wheel. */
    public float[] listBox;
    /** The fleet list's scrollbar track {x, y, w, h}, for clicks and drags; null when the list fits. */
    public float[] scrollTrack;

    /** What is under the mouse, as last drawn (the topmost), or null. */
    public float[] hitAt(float mx, float my) {
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
    public int rows = 12;

    /** Everything the screen shows, gathered by the title plugin each frame. */
    public static final class View {
        /** Room left at the top right for the co-op screen's own buttons (see CoopScreen). */
        public float reserveRight;
        public Roster roster;
        public String me = "", hostLine = "", note = "", players = "", date = "";
        /** The host's latest campaign notifications (Msg.NEWS), oldest first. */
        public List<String> news = java.util.Collections.emptyList();
        /** The host's campaign map (Msg.MAP), null until one came, and when it came (System.currentTimeMillis). */
        public wingman.net.CampaignMap map;
        public long mapAt;
        /** Friend refit: the market the host is docked at ("" none), and the refit screen's state. */
        public String refitMarket = "";
        public boolean refitOpen;
        public wingman.net.RefitData refitData;
        public Color hostColor = Panel.TEXT, noteColor = Panel.DIM;
        public int at;
        public boolean hostInBattle;
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

    public void render(View v) {
        if (broken) return;
        try {
            if (body == null) body = Global.getSettings().getString("defaultFont");
            refitButton = null;
            skillButton = null;
            bionicButton = null;
            hits.clear();
            listBox = null;
            scrollTrack = null;
            Style.loadBudget = 4;
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            float m = Math.max(24f, sw * 0.022f);
            fill(0f, 0f, sw, sh, new Color(4, 7, 12, 238));

            // Header: what this is and the pages, then a line on where the host is and who is here. LunaLib draws
            // its own buttons in the top-left corner of the title screen (over anything there): the header starts
            // to their right, and the content below them.
            float hx = m + lunaCorner();
            float top = sh - m;
            float titleH = Style.lineHeight(BIG);
            text("CO-OP LOBBY", BIG, Style.ACCENT, hx, top, 400f);
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
                y = Style.callout("Refit available at " + v.refitMarket + ": select a ship, then R or the Refit button on its card.",
                        Panel.GOOD, hx, y - 4f, Math.min(sw - m - hx, 760f)) - 2f;
            }
            if (v.hostInBattle && page != REFIT) { // join the host's battle, for the mouse (Enter)
                float bw = 220f, bh = 34f, bx = sw - m - bw, by = top - Style.TAB_H - 14f - bh;
                Style.button(bx, by, bw, bh, "JOIN THE BATTLE", Panel.GOOD, true, Style.inside(bx, by, bw, bh));
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
                    ? "Up/Down: choose     PgUp/PgDn: page     F: pick this ship     Delete: no pick     "
                            + (v.hostInBattle ? "Enter: join the battle" : "Enter: pick")
                            + (v.hostInBattle ? "" : "     R: refit this ship") + "     Tab: last battle     F1: how to     Esc: back"
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

    public boolean isBroken() { return broken; }

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
        float gap = 14f, rowH = 28f;
        float listW = w * 0.36f, midW = w * 0.33f, sideW = w - listW - midW - 2 * gap;
        float midX = x + listW + gap, sideX = midX + midW + gap;
        float listBottom = top - h;
        int n = r == null ? 0 : r.members.size();
        int at = Math.max(0, Math.min(Math.max(0, n - 1), v.at));
        float y = Style.window(x, listBottom, listW, h, "HOST'S FLEET", n > 0 ? n + " ship" + (n == 1 ? "" : "s") : "") - 10f;
        float px = x + Style.PAD, pw = listW - 2 * Style.PAD;
        if (n == 0) {
            text(r == null ? "The host's fleet shows here when the host is in a campaign." : "The host's fleet isn't available (the host is not in a campaign).",
                    body, Panel.DIM, px, y, pw);
        } else {
            float rowW = listW - 26f; // the scrollbar is at the right
            float floor = listBottom + 34f;
            rows = Math.max(3, (int) ((y - floor) / rowH));
            listBox = new float[] { x, listBottom, listW, h };
            int from = Math.max(0, Math.min(at - rows / 2, n - rows)), to = Math.min(n, from + rows);
            float bh = Style.lineHeight(body), rowsTop = y;
            for (int i = from; i < to; i++) {
                Roster.Member mem = r.members.get(i);
                boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
                boolean blocked = (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED | Roster.CANT_FLY)) != 0 || (!mem.pickedBy.isEmpty() && !mine);
                float ry = y - rowH;
                boolean hover = Style.inside(x + 6f, ry, rowW, rowH);
                if (i == at) { fill(x + 6f, ry, rowW, rowH, MARK); fill(x + 6f, ry, 3f, rowH, Style.ACCENT); }
                else if (hover) fill(x + 6f, ry, rowW, rowH, Style.HOVER);
                else if ((i - from) % 2 == 1) fill(x + 6f, ry, rowW, rowH, new Color(255, 255, 255, 5));
                hits.add(new float[] { x + 6f, ry, rowW, rowH, ROW, i, 0 });
                Color c = mine ? Panel.GOOD : blocked ? Panel.DIM : Panel.TEXT;
                String tag = (mem.flags & Roster.FLAGSHIP) != 0 ? "flagship" : (mem.flags & Roster.MOTHBALLED) != 0 ? "mothballed"
                        : (mem.flags & Roster.CANT_FLY) != 0 ? "can't fly" : mine ? "yours" : mem.pickedBy;
                float ch = Style.chipHeight();
                Style.chip(sizeTag(mem.hullSize), blocked ? Style.FAINT : Panel.DIM, px, ry + (rowH + ch) / 2f);
                float nameX = px + 38f;
                String right = mem.cr >= 0 ? mem.cr + "%" : "";
                float rx = x + 6f + rowW - 8f;
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
                Style.rule(x + 1f, listBottom + 30f, listW - 2f);
                text(more, body, Panel.DIM, px, listBottom + 24f, pw);
                // the scrollbar: a click on the track goes to that place in the fleet
                float trackH = rows * rowH, sx = x + listW - 16f;
                float thumbH = Math.max(18f, trackH * rows / n);
                float thumbTop = rowsTop - (trackH - thumbH) * from / (float) (n - rows);
                fill(sx, rowsTop - trackH, 6f, trackH, Style.WELL);
                fill(sx, thumbTop - thumbH, 6f, thumbH, new Color(120, 210, 255, 160));
                scrollTrack = new float[] { sx - 4f, rowsTop - trackH, 14f, trackH };
                hits.add(new float[] { scrollTrack[0], scrollTrack[1], scrollTrack[2], scrollTrack[3], SCROLL, 0, 0 });
            }
        }
        // Middle: the linked officer, then the marked ship.
        Roster.Member linked = null;
        if (r != null && !v.me.isEmpty()) {
            for (Roster.Member mem : r.members) if (mem.follows.equalsIgnoreCase(v.me)) { linked = mem; break; }
        }
        float panelH = officerPanel(v, linked, midX, top, midW);
        if (n == 0) {
            float sy = Style.window(midX, top - h, midW, h - panelH - gap, "SHIP", "") - 10f;
            text("The host's ships show here once their fleet is known.", body, Panel.DIM, midX + Style.PAD, sy, midW - 2 * Style.PAD);
        } else {
            card(r.members.get(at), linked, v, midX, top - panelH - gap, midW, h - panelH - gap);
        }
        side(v, sideX, top, sideW, h);
    }

    /**
     * The friend's linked officer: always shown, with the ship they are on and SELECT to mark that ship in the fleet
     * list. Returns the panel's height.
     */
    private float officerPanel(View v, Roster.Member linked, float x, float top, float w) {
        float px = x + Style.PAD, pw = w - 2 * Style.PAD;
        int perRow = Math.max(1, (int) ((pw - 74f) / 30f)); // skill icons right of the portrait
        int skillRows = linked == null ? 1 : Math.max(1, (linked.skills.size() + perRow - 1) / perRow);
        float blockH = Math.max(64f, 42f + skillRows * 30f), rowH = 26f;
        float total = Style.BAR_H + 14f + blockH + 10f + rowH + 12f;
        float bottom = top - total;
        Style.window(x, bottom, w, total, "YOUR OFFICER", "");
        float y = top - Style.BAR_H - 14f;
        if (linked == null) {
            text("No officer is linked to you yet. The host links one when you pick a ship.", body, Panel.DIM, px, y, pw);
        } else { // the officer's card can be longer than the panel: it is cut rather than drawn over the ship row
            Style.clip(px, bottom + rowH + 22f, pw, blockH);
            try { officer(linked, px, y, pw, false); } finally { Style.unclip(); }
        }
        // The ship they are on, and a button that marks it in the fleet list: SELECT only moves the cursor.
        float rowY = bottom + 12f, bw = 88f, bx = px + pw - bw;
        String s = linked == null ? "No ship yet." : Roster.label(linked);
        Style.callout(s, linked == null ? Panel.WARN : Panel.GOOD, px, rowY + rowH - 6f, pw - bw - 10f);
        boolean over = Style.inside(bx, rowY, bw, rowH);
        Style.button(bx, rowY, bw, rowH, "SELECT", Panel.GOOD, linked != null, linked != null && over);
        if (linked != null) hits.add(new float[] { bx, rowY, bw, rowH, SELECT, v.roster.members.indexOf(linked), 0 });
        return total;
    }

    /** The right column: the host's campaign map (Msg.MAP), and under it the date and the latest events. */
    private void campaign(View v, float x, float top, float w, float h) {
        float gap = 14f, px = x + Style.PAD, pw = w - 2 * Style.PAD;
        List<String> events = v.news.subList(Math.max(0, v.news.size() - 3), v.news.size());
        float evH = Style.BAR_H + 10f + Style.lineHeight(body) + 10f + 10f;
        for (String e : events) evH += Style.height(e, body, pw - 14f) + 12f;
        if (events.isEmpty()) evH += Style.lineHeight(body) + 6f;
        evH = Math.min(evH, h * 0.6f);
        float campH = h - evH - gap;
        long age = System.currentTimeMillis() - v.mapAt;
        String mapNote = v.map == null ? "" : age > 3000 ? v.map.location + "   no update" : v.map.location + (v.map.paused ? "   PAUSED" : "");
        Style.window(x, top - campH, w, campH, "CAMPAIGN", mapNote);
        if (v.map == null) text("The host's campaign map shows here once they share it.", body, Panel.DIM, px, top - Style.BAR_H - 10f, pw);
        else map(v.map, age, x + 4f, top - campH + 4f, w - 8f, campH - Style.BAR_H - 8f);
        float y = Style.window(x, top - h, w, evH, "EVENTS", "") ;
        y = text(v.date.isEmpty() ? "Date: not known yet" : "Date: " + v.date, body, Panel.TEXT, px, y - 10f, pw) - 10f;
        if (events.isEmpty()) text("Nothing has happened yet.", body, Panel.DIM, px, y, pw);
        for (int i = events.size() - 1; i >= 0 && y > top - h + 14f; i--) y = Style.callout(events.get(i), Style.ACCENT, px, y, pw) - 6f;
    }

    /**
     * The host's campaign map scaled into the box (x, y, w, h): bodies as discs, fleets as triangles pointing where they
     * go (up when still), moved on by their velocity for up to a second since the update (not while paused). Anything
     * that falls outside the box is left out.
     */
    private void map(wingman.net.CampaignMap m, long ageMs, float x, float y, float w, float h) {
        if (w < 20f || h < 20f) return;
        Style.clip(x, y, w, h); // names near the edge are cut off rather than spilling out of the window
        try { drawMap(m, ageMs, x, y, w, h); } finally { Style.unclip(); }
    }

    /** The map's background in hyperspace: a dark indigo. */
    private static final Color HYPERSPACE = new Color(26, 16, 58);

    private void drawMap(wingman.net.CampaignMap m, long ageMs, float x, float y, float w, float h) {
        float s = Math.min(w, h) / 2f / m.viewRadius, cx = x + w / 2f, cy = y + h / 2f;
        float t = m.paused ? 0f : Math.max(0f, Math.min(ageMs, 1000L)) / 1000f;
        if (m.hyperspace) {
            Style.fill(x, y, w, h, HYPERSPACE);
            Style.disc(cx, cy, m.viewRadius * s, null, Style.alpha(Style.ACCENT, 40));
        }
        for (wingman.net.CampaignMap.Body b : m.bodies) {
            float bx = cx + (b.x - m.viewX) * s, by = cy + (b.y - m.viewY) * s;
            Color c = b.color != 0 ? new Color(b.color) : null;
            float r;
            switch (b.kind) {
                case wingman.net.CampaignMap.STAR: r = Math.max(4f, b.radius * s); break;
                case wingman.net.CampaignMap.PLANET: r = Math.max(2f, b.radius * s); break;
                case wingman.net.CampaignMap.JUMP_POINT: r = 4f; break;
                default: r = 3f;
            }
            if (bx - r < x || bx + r > x + w || by - r < y || by + r > y + h) continue;
            switch (b.kind) {
                case wingman.net.CampaignMap.STAR: Style.disc(bx, by, r, new Color(255, 220, 140, 200), null); break;
                case wingman.net.CampaignMap.PLANET: Style.disc(bx, by, r, Style.alpha(c != null ? c : Style.DIM, 150), c); break;
                case wingman.net.CampaignMap.STATION: Style.fill(bx - 2.5f, by - 2.5f, 5f, 5f, c != null ? c : Style.DIM); break;
                case wingman.net.CampaignMap.JUMP_POINT: Style.disc(bx, by, r, null, Style.ACCENT); break;
                default: Style.disc(bx, by, r, Style.TEXT, null);
            }
            boolean named = b.kind == wingman.net.CampaignMap.SYSTEM || b.kind == wingman.net.CampaignMap.STAR || c != null;
            if (named && !b.name.isEmpty() && bx + r + 2f < x + w - 30f) {
                Style.text(b.name, Style.SMALL_FONT, c != null ? c : Style.DIM, bx + r + 2f, by + 6f, x + w - (bx + r + 2f));
            }
        }
        for (wingman.net.CampaignMap.Fleet f : m.fleets) {
            float fx = cx + (f.x + f.vx * t - m.viewX) * s, fy = cy + (f.y + f.vy * t - m.viewY) * s;
            float r = f.detail == wingman.net.CampaignMap.BLIP ? 2.5f : Math.min(7f, 2.5f + (float) Math.sqrt(f.ships) * 0.8f);
            if (fx - r < x || fx + r > x + w || fy - r < y || fy + r > y + h) continue;
            Color c = f.detail == wingman.net.CampaignMap.BLIP ? Style.FAINT
                    : f.detail == wingman.net.CampaignMap.SHAPE ? Style.DIM : f.color != 0 ? new Color(f.color) : Style.TEXT;
            Style.arrow(fx, fy, r + 1f, f.vx, f.vy, c, f.hostile ? Style.BAD : null);
        }
        float px = cx + (m.playerX + m.playerVx * t - m.viewX) * s, py = cy + (m.playerY + m.playerVy * t - m.viewY) * s;
        if (px > x && px < x + w && py > y && py < y + h) Style.arrow(px, py, 6f, m.playerVx, m.playerVy, Style.GOOD, Style.TEXT);
    }

    private void card(Roster.Member mem, Roster.Member linked, View v, float x, float top, float w, float h) {
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
        systemHelp(mem.hullId, px, y, pw);
        y -= box + 10f;
        ShipSystemInfo system = ShipSystemInfo.read(mem.hullId);
        y = text("Ship system: " + system.name, body, Style.TEXT, px, y, pw) - 12f;

        // What happens with it, and the refit button, at the bottom of the card; the sections fill what is between.
        String state = (mem.flags & Roster.FLAGSHIP) != 0 ? "The host's flagship."
                : (mem.flags & Roster.MOTHBALLED) != 0 ? "Mothballed."
                : (mem.flags & Roster.CANT_FLY) != 0 ? "Can't be flown" + (mem.note.isEmpty() ? "." : ": " + mem.note + ".")
                : mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty() ? "Your ship for the next battle, if the host deploys it."
                : !mem.pickedBy.isEmpty() ? mem.pickedBy + " has picked it." : "Free to pick for the next battle.";
        Color c = state.startsWith("Your") ? Panel.GOOD : state.startsWith("Free") ? Style.ACCENT : Panel.WARN;
        boolean mine = mem.pickedBy.equalsIgnoreCase(v.me) && !v.me.isEmpty();
        // Friend refit: any ship but the host's, picked or not. Docked, it can be sent; away from a market, planned.
        boolean refittable = !v.hostInBattle && (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED)) == 0;
        boolean pickable = !mine && mem.pickedBy.isEmpty() && (mem.flags & (Roster.FLAGSHIP | Roster.MOTHBALLED | Roster.CANT_FLY)) == 0;
        boolean buttons = refittable || pickable || mine;
        float stateH = Style.height(state, body, pw - 14f) + 6f;
        float floor = top - h + 12f + stateH + (buttons ? 44f : 0f) + 8f;

        y = Style.section("Officer", mem.follows.isEmpty() ? null : "follows " + mem.follows, px, y, pw) - 2f;
        if (sameOfficer(mem, linked)) y = text("Yours, as in YOUR OFFICER above.", body, Panel.DIM, px, y, pw) - 12f;
        else y = officer(mem, px, y, pw) - 12f;
        if (!v.hostInBattle && !v.me.isEmpty() && mem.follows.equalsIgnoreCase(v.me)) {
            float bh = 30f, gap = 8f;
            boolean available = !mem.officerId.isEmpty() && !mem.skillChoices.isEmpty();
            boolean bionics = !mem.officerId.isEmpty() && wingman.compat.ModCompat.bionicsEnabled();
            float bw = Math.min(pw * (bionics ? 0.55f : 1f), 180f);
            Style.button(px, y - bh, bw, bh, available ? "CHOOSE SKILL (K)" : "NO LEVEL-UP AVAILABLE",
                    Panel.GOOD, available, available && Style.inside(px, y - bh, bw, bh));
            if (available) skillButton = new float[] { px, y - bh, bw, bh };
            if (bionics) { // Bionic Alteration's workshop: the captain's bionics, changed without the host's help (B)
                float bx = px + bw + gap, bbw = Math.min(px + pw - bx, 150f);
                if (bbw > 60f) {
                    Style.button(bx, y - bh, bbw, bh, "BIONICS (B)", Panel.GOOD, true, Style.inside(bx, y - bh, bbw, bh));
                    bionicButton = new float[] { bx, y - bh, bbw, bh };
                }
            }
            y -= bh + 12f;
        }
        if (!mem.hullmods.isEmpty() && y - 40f > floor) {
            y = Style.section("Hull mods", px, y, pw) - 2f;
            modList(mem.hullmods, px, y, pw);
        }

        float sy = top - h + 12f + stateH;
        float bx = px, bh = 34f, by = sy + 10f;
        if (pickable) { // pick it (F)
            float bw = Math.min(pw * 0.5f, 220f);
            Style.button(bx, by, bw, bh, "PICK THIS SHIP", Panel.GOOD, true, Style.inside(bx, by, bw, bh));
            hits.add(new float[] { bx, by, bw, bh, KEY, 33, 0 });
            bx += bw + 10f;
        }
        if (refittable) {
            boolean docked = !v.refitMarket.isEmpty();
            float bw = Math.min(pw * 0.55f, docked ? 180f : 220f);
            boolean over = Style.inside(bx, by, bw, bh);
            Style.button(bx, by, bw, bh, docked ? "REFIT" : "PLAN A REFIT", docked ? Panel.GOOD : Style.ACCENT, true, over);
            refitButton = new float[] { bx, by, bw, bh };
            bx += bw + 10f;
        }
        if (mine) { // no pick (Delete)
            float bw = Math.min(px + pw - bx, 150f);
            if (bw > 60f) {
                Style.button(bx, by, bw, bh, "UNPICK", Panel.WARN, true, Style.inside(bx, by, bw, bh));
                hits.add(new float[] { bx, by, bw, bh, KEY, 211, 0 });
            }
        }
        Style.callout(state, c, px, sy, pw);
    }

    /** Translation of vanilla Fleet info-icon rendering via public SpriteAPI; lobby help is hover-only. */
    void systemHelp(String hullId, float x, float top, float width) {
        systemHelp(hullId, null, x, top, width);
    }

    public void systemHelp(String hullId, String systemId, float x, float top, float width) {
        float size = 32f, bx = x + width - size - 4f, by = top - size - 4f;
        boolean over = Style.inside(bx, by, size, size);
        SpriteAPI s = null;
        try { s = Style.sprite(Global.getSettings().getSpriteName("fleetScreen", "icon_more_info")); }
        catch (RuntimeException e) { /* text fallback if the UI asset is unavailable */ }
        if (s == null) {
            text("?", HEAD, Style.ACCENT, bx + 8f, by + 28f, size - 8f);
        } else {
            float w = s.getWidth(), h = s.getHeight(), angle = s.getAngle(), alpha = s.getAlphaMult();
            int src = s.getBlendSrc(), dest = s.getBlendDest();
            Color color = s.getColor();
            try {
                s.setSize(size, size);
                s.setAngle(0f);
                s.setNormalBlend();
                s.setAlphaMult(over ? 0.6f : 0.55f);
                s.setColor(Color.BLACK);
                s.render(bx + 1f, by + 1f);
                s.setColor(Global.getSettings().getColor("buttonBg"));
                s.render(bx, by);
                if (over) {
                    s.setAdditiveBlend();
                    s.setAlphaMult(0.5f);
                    s.render(bx, by);
                }
            } finally {
                s.setSize(w, h);
                s.setAngle(angle);
                s.setAlphaMult(alpha);
                s.setColor(color);
                s.setBlendFunc(src, dest);
            }
        }
        if (over) Tips.hover(systemId == null ? "s:" + hullId : "S:" + hullId + "\000" + systemId, false, "", -1);
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

    /** Whether the ship card would only repeat the officer the YOUR OFFICER panel already shows. */
    private static boolean sameOfficer(Roster.Member mem, Roster.Member linked) {
        return linked != null && (mem == linked || (!mem.officerId.isEmpty() && mem.officerId.equals(linked.officerId)));
    }

    /** The officer: portrait, name, level and personality, and their skills as icons (elite ones framed in gold). */
    private float officer(Roster.Member mem, float x, float top, float w) {
        return officer(mem, x, top, w, true);
    }

    /** The same, without the line listing the skills by name (for the linked-officer panel, which keeps to a height). */
    private float officer(Roster.Member mem, float x, float top, float w, boolean namesInText) {
        if (mem.officer.isEmpty()) return text("No officer", body, Panel.DIM, x, top, w);
        float pic = 64f;
        SpriteAPI portrait = Style.sprite(mem.portrait);
        if (portrait != null) { fill(x, top - pic, pic, pic, ICON_BACK); Style.icon(portrait, x, top - pic, pic); outline(x, top - pic, pic, pic, LINE); }
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
            SpriteAPI ic = spec == null ? null : Style.sprite(spec.getSpriteName());
            fill(sx, y - s, s, s, ICON_BACK);
            if (ic != null) Style.icon(ic, sx, y - s, s);
            if (level >= 2) { outline(sx, y - s, s, s, ELITE); elite++; }
            if (spec != null && Style.inside(sx, y - s, s, s)) Tips.hover("k:" + id + ":" + level, false, "", -1);
            sx += s + gap;
            names.append(names.length() == 0 ? "" : ", ").append(spec == null ? id : spec.getName()).append(level >= 2 ? " (elite)" : "");
        }
        if (!mem.skills.isEmpty()) y -= s + 4f;
        float bottom = Math.min(y, top - pic - 4f);
        if (namesInText && names.length() > 0) bottom = text(names.toString(), body, Panel.DIM, x, bottom - 2f, w);
        return bottom;
    }

    private void side(View v, float x, float top, float w, float h) {
        float gap = 14f, px = x + Style.PAD, pw = w - 2 * Style.PAD;
        BattleReport r = AfterAction.report;
        // Last battle (at the bottom)
        float lastH = r == null ? 0f : Style.BAR_H + 70f;
        if (r != null) {
            float ly = Style.window(x, top - h, w, lastH, "LAST BATTLE", minutes(r.seconds)) - 10f;
            ly = text(outcome(r).toUpperCase(), TITLE_FONT_BIG, outcomeColor(r), px, ly, pw) - 4f;
            boolean over = Style.inside(x, top - h, w, lastH);
            text("Click for the full report.", body, over ? Panel.TEXT : Panel.DIM, px, ly, pw);
            hits.add(new float[] { x, top - h, w, lastH, TAB, REPORT, 0 });
        }
        // The host's campaign map and events, above it
        campaign(v, x, top, w, h - (r == null ? 0f : lastH + gap));
    }

    private static final String TITLE_FONT_BIG = "graphics/fonts/orbitron20aabold.fnt";

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
        y = text("Credits " + (a.credits >= 0 ? "+" : "-") + Style.grouped(Math.abs(Math.round(a.credits))), body, a.credits >= 0 ? Panel.GOOD : Panel.WARN, px, y, pw) - 2f;
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

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }
}
