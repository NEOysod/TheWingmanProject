package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.net.HostSession;

/**
 * The host's officer links on the campaign map: Ctrl+Shift+L, or ASSIGN/CHANGE on a friend in the CO-OP panel. The
 * connected friends are tabs along the top; the host's officers are cards (portrait, level, personality, skills,
 * who has them); the picked one's skills are listed at the side, with LINK and UNLINK. Keys: Tab and Shift+Tab a
 * friend, the arrows an officer (along and between the rows of cards), Enter links (or unlinks), Delete unlinks, Esc closes. A linked officer captains
 * whichever ship that friend picks (OfficerLinks). The campaign waits while it is open. Registered on each game load
 * as a transient listener.
 */
final class OfficerLinkPanel implements CampaignInputListener, CampaignUIRenderingListener {
    private static OfficerLinkPanel instance;
    private boolean open, broken, wasPaused;
    private int player, officer, scroll, tabFirst;
    private String message = "";
    private Color messageColor = Panel.DIM;
    private final Widgets.Hits hits = new Widgets.Hits();
    private static final int COLS = 3;

    static void install() {
        try {
            OfficerLinkPanel p = new OfficerLinkPanel();
            Global.getSector().getListenerManager().addListener(p, true);
            instance = p;
        } catch (Throwable t) {
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page can't be opened", t);
        }
    }

    static boolean isOpen() { return instance != null && instance.open; }

    /** Opens the page as it was last left (the CO-OP panel's OFFICER LINKS). */
    static void open() {
        if (instance != null) instance.show();
    }

    /** Opens the page on that friend (from the CO-OP panel). */
    static void openFor(String name) {
        OfficerLinkPanel p = instance;
        if (p == null) return;
        List<HostSession.Player> ps = players();
        for (int i = 0; i < ps.size(); i++) if (ps.get(i).name.equalsIgnoreCase(name)) p.player = i;
        PersonAPI linked = OfficerLinks.officerOf(name);
        List<PersonAPI> os = OfficerLinks.officers();
        if (linked != null && os.indexOf(linked) >= 0) p.officer = os.indexOf(linked);
        p.show();
    }

    public int getListenerInputPriority() { return 100; }

    private static List<HostSession.Player> players() {
        HostSession s = HostSession.active();
        List<HostSession.Player> l = s == null ? new ArrayList<HostSession.Player>() : new ArrayList<HostSession.Player>(s.getPlayers());
        java.util.Collections.sort(l, new java.util.Comparator<HostSession.Player>() {
            public int compare(HostSession.Player a, HostSession.Player b) { return a.slot - b.slot; }
        });
        return l;
    }

    private long seenAt;

    private void show() {
        if (open || Pages.anyOpen()) return;
        open = true;
        seenAt = System.currentTimeMillis();
        message = "";
        wasDown = true;
        hits.clear();
        wasPaused = Global.getSector().isPaused();
        Global.getSector().setPaused(true);
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || Global.getCurrentState() != GameState.CAMPAIGN) return;
        if (open && Pages.stale(seenAt)) { close(); Global.getLogger(OfficerLinkPanel.class).info("Wingman: the officer links page wasn't being drawn and was closed"); }
        try {
            float scale = Style.eventScale();
            for (InputEventAPI e : events) {
                if (e.isConsumed()) continue;
                if (open && e.isMouseEvent()) { // the page takes the mouse while it is up
                    if (e.isMouseScrollEvent()) {
                        String over = hits.at(e.getX() / scale, e.getY() / scale);
                        int step = e.getEventValue() > 0 ? -1 : 1;
                        if (over != null && over.startsWith("friend:")) tabFirst = Math.max(0, tabFirst + step); // over the friends: along them
                        else scroll = Math.max(0, scroll + step);
                    }
                    if (!e.isMouseMoveEvent()) e.consume(); // (clicks are read while drawing: see clicks())
                    continue;
                }
                if (!e.isKeyboardEvent()) continue;
                boolean down = e.isKeyDownEvent(), ctrl = e.isCtrlDown(), shift = e.isShiftDown(), alt = e.isAltDown();
                int key = e.getEventValue();
                boolean mine = Keys.is("officerLinks", key, ctrl, shift, alt);
                if (!open) {
                    if (!down || !mine) continue; // its key (Ctrl+Shift+L)
                    if (Global.getSector().getCampaignUI() != null && Global.getSector().getCampaignUI().isShowingDialog()) continue;
                    show();
                    e.consume();
                    continue;
                }
                e.consume(); // while it is up, keys are its own
                if (!down) continue;
                List<HostSession.Player> ps = players();
                List<PersonAPI> os = OfficerLinks.officers();
                if (key == 1 && KeyHelp.isOpen("Officer links")) KeyHelp.close();                // Esc: the key list first
                else if (key == 1 || mine) { close(); break; }                                   // Esc, its key
                else if (key == 59) KeyHelp.toggle("Officer links");                                         // F1: the keys
                else if (key == 203 && !os.isEmpty()) officer = Math.max(0, officer - 1);                       // Left: the officer before
                else if (key == 205 && !os.isEmpty()) officer = Math.min(os.size() - 1, officer + 1);           // Right: the officer after
                else if (key == 200 && !os.isEmpty()) officer = officer >= COLS ? officer - COLS : officer;     // Up: the row above
                else if (key == 208 && !os.isEmpty()) officer = Math.min(os.size() - 1, officer + COLS);       // Down: the row below
                else if (key == 15 && !ps.isEmpty()) { player = shift ? (player + ps.size() - 1) % ps.size() : (player + 1) % ps.size(); message = ""; } // Tab, Shift+Tab: friend
                else if (key == 211) act("unlink");                                                            // Delete
                else if (key == 28) act("link");                                                               // Enter
            }
        } catch (Throwable t) {
            broken = true;
            close();
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page failed and was closed", t);
        }
    }

    private void act(String id) {
        List<HostSession.Player> ps = players();
        List<PersonAPI> os = OfficerLinks.officers();
        if (!id.equals("link") && !id.equals("unlink") && !id.endsWith(":strip")) Widgets.click();
        if (id.equals("close")) { close(); return; }
        if (id.equals("friend:left")) { tabFirst = Math.max(0, tabFirst - 1); return; }
        if (id.equals("friend:right")) { tabFirst++; return; }
        if (id.equals("friend:strip")) return;
        if (id.startsWith("friend:")) { player = Integer.parseInt(id.substring(7)); message = ""; return; }
        if (id.startsWith("officer:")) { officer = Integer.parseInt(id.substring(8)); message = ""; return; }
        if (os.isEmpty()) return;
        PersonAPI o = os.get(Math.min(officer, os.size() - 1));
        if (id.equals("unlink")) {
            Widgets.click();
            if (OfficerLinks.linkedTo(o) != null) { OfficerLinks.unlink(o); say(o.getNameString() + " is nobody's now.", Panel.GOOD); }
            return;
        }
        if (id.equals("link")) {
            Widgets.click();
            if (ps.isEmpty()) { say("No friends connected: link an officer once a friend has joined.", Panel.WARN); return; }
            HostSession.Player p = ps.get(Math.min(player, ps.size() - 1));
            if (OfficerLinks.key(p.name).equals(OfficerLinks.linkedTo(o))) {
                OfficerLinks.unlink(o);
                say(o.getNameString() + " is no longer " + p.name + "'s.", Panel.GOOD);
            } else {
                OfficerLinks.link(p.name, o);
                say(o.getNameString() + " is " + p.name + "'s officer now: they captain the ship " + p.name + " picks.", Panel.GOOD);
            }
        }
    }

    private void close() {
        if (!open) return;
        open = false;
        hits.clear();
        try { Global.getSector().setPaused(wasPaused); } catch (Throwable t) { /* no sector */ }
    }

    private void say(String s, Color c) { message = s; messageColor = c; }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        boolean pushed = Style.push();
        try { renderScaled(viewport); if (isOpen()) KeyHelp.draw("Officer links"); } finally { Style.pop(pushed); }
    }

    private void renderScaled(ViewportAPI viewport) {
        if (broken || !open) { wasDown = false; return; }
        seenAt = System.currentTimeMillis();
        try {
            clicks();
            if (!open) return;
            draw(view());
        } catch (Throwable t) {
            broken = true;
            close();
            Style.forget();
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page failed and was closed", t);
        }
    }

    private boolean wasDown = true; // (true: the click that opened the page isn't taken as one on it)

    /**
     * A click, read here while drawing (the mouse button itself, against what was drawn last frame), as the friend
     * refit page does: the campaign's input events didn't bring this page its clicks while it is up.
     */
    private void clicks() {
        boolean down;
        try { down = org.lwjgl.input.Mouse.isButtonDown(0); } catch (Throwable t) { down = false; }
        boolean press = down && !wasDown;
        wasDown = down;
        if (!press || hits.empty()) return;
        String id = hits.at(Style.mx(), Style.my());
        if (id == null) { close(); return; } // a click off the page closes it
        if (!id.isEmpty()) act(id);
    }

    // ---- what it shows ----

    static final class Friend { int slot; String name = ""; }

    static final class Officer {
        String name = "", portrait = "", personality = "", status = "";
        int level;
        Color statusColor = Panel.DIM;
        /** Linked to the friend shown (true), to another (the note says who), captain of a ship (the note). */
        boolean mine;
        String moveNote = "";
        /** skill id -> elite. */
        List<String> skills = new ArrayList<String>();
        List<Boolean> elite = new ArrayList<Boolean>();
    }

    static final class View {
        List<Friend> friends = new ArrayList<Friend>();
        List<Officer> officers = new ArrayList<Officer>();
        int friend, picked;
        String message = "";
        Color messageColor = Panel.DIM;
    }

    private View view() {
        View v = new View();
        List<HostSession.Player> ps = players();
        for (HostSession.Player p : ps) { Friend f = new Friend(); f.slot = p.slot; f.name = p.name; v.friends.add(f); }
        player = ps.isEmpty() ? 0 : Math.min(player, ps.size() - 1);
        v.friend = player;
        String friendKey = ps.isEmpty() ? "" : OfficerLinks.key(ps.get(player).name), friendName = ps.isEmpty() ? "" : ps.get(player).name;
        List<FleetMemberAPI> fleet = CampaignLobby.fleet();
        for (PersonAPI o : OfficerLinks.officers()) {
            Officer x = new Officer();
            x.name = o.getNameString();
            x.portrait = o.getPortraitSprite() == null ? "" : o.getPortraitSprite();
            x.level = o.getStats().getLevel();
            try { x.personality = o.getPersonalityAPI().getDisplayName().toLowerCase(); } catch (Throwable t) { /* none */ }
            String link = OfficerLinks.linkedTo(o), ship = null;
            for (FleetMemberAPI m : fleet) if (m.getCaptain() == o) ship = CampaignLobby.label(m);
            String linkName = link == null ? null : nameOf(ps, link);
            if (link != null && link.equals(friendKey)) { x.mine = true; x.status = "Linked to " + friendName; x.statusColor = Panel.GOOD; }
            else if (link != null) { x.status = "Linked to " + linkName; x.statusColor = Panel.WARN; x.moveNote = "Moves over from " + linkName + "."; }
            else if (ship != null) { x.status = "Captain of " + ship; x.moveNote = "Leaves " + ship + " when " + friendName + " picks a ship."; }
            else x.status = "Unassigned";
            if (x.mine && ship != null) x.status += "  -  " + ship;
            try {
                for (MutableCharacterStatsAPI.SkillLevelAPI sl : o.getStats().getSkillsCopy()) {
                    if (sl.getLevel() <= 0f || sl.getSkill() == null || sl.getSkill().isAptitudeEffect()) continue;
                    x.skills.add(sl.getSkill().getId());
                    x.elite.add(sl.getLevel() >= 2f);
                }
            } catch (Throwable t) { /* none listed */ }
            v.officers.add(x);
        }
        officer = v.officers.isEmpty() ? 0 : Math.min(officer, v.officers.size() - 1);
        v.picked = officer;
        v.message = message;
        v.messageColor = messageColor;
        return v;
    }

    private static String nameOf(List<HostSession.Player> ps, String key) {
        for (HostSession.Player p : ps) if (OfficerLinks.key(p.name).equals(key)) return p.name;
        return key + " (not here)";
    }

    // ---- drawing ----

    void draw(View v) {
        hits.clear();
        String body = Style.body();
        float sw = Style.sw(), sh = Style.sh();
        float w = Math.min(1240f, sw - 60f), h = Math.min(720f, sh - 60f);
        float x = (sw - w) / 2f, y = (sh - h) / 2f;
        Style.fill(0f, 0f, sw, sh, Widgets.BACKDROP);
        hits.add(x, y, w, h, "");
        Style.frame(x, y, w, h, new Color(8, 18, 31, 248), Style.EDGE, 10f);
        // title bar: the friends as tabs, and a close box
        float bar = 46f, top = y + h;
        Style.fill(x + 1f, top - bar, w - 11f, bar - 1f, Style.BAR);
        Style.fill(x + 1f, top - bar, 3f, bar - 1f, Style.ACCENT);
        float th = Style.lineHeight(Style.TITLE_FONT);
        Style.text("OFFICER FOR", Style.TITLE_FONT, Style.ACCENT, x + 16f, top - (bar - th) / 2f, 200f);
        float tx = x + 16f + Style.width("OFFICER FOR", Style.TITLE_FONT) + 16f;
        if (v.friends.isEmpty()) Style.text("no friends connected", body, Panel.WARN, tx, top - (bar - Style.lineHeight(body)) / 2f, 300f);
        List<String> names = new ArrayList<String>();
        List<Color> colors = new ArrayList<Color>();
        for (Friend f : v.friends) { names.add("P" + f.slot + "  " + f.name); colors.add(PlayerTags.color(f.slot)); }
        float stripW = x + w - 16f - (bar - 16f) - 120f - tx;
        tabFirst = Widgets.tabStrip(names, colors, v.friend, tabFirst, tx, top - bar + 8f, stripW, bar - 16f, hits, "friend");
        float cb = bar - 16f;
        Widgets.box(x + w - 16f - cb, top - bar + 8f, cb, cb, "X", Style.TEXT, false);
        hits.add(x + w - 16f - cb, top - bar + 8f, cb, cb, "close");
        Style.textRight("Game paused", Style.SMALL_FONT, Style.FAINT, x + w - 16f - cb - 12f, top - (bar - Style.lineHeight(Style.SMALL_FONT)) / 2f);

        // the side: the picked officer
        float sideW = 380f, foot = 34f;
        float sx = x + w - sideW, bodyTop = top - bar, bodyBottom = y + foot;
        Style.fill(sx, bodyBottom, 1f, bodyTop - bodyBottom, Style.RULE);
        Style.fill(sx + 1f, bodyBottom, sideW - 2f, bodyTop - bodyBottom, new Color(10, 24, 40, 200));
        String friendName = v.friends.isEmpty() ? "your friend" : v.friends.get(v.friend).name;

        // the cards
        float gx = x + 18f, gw = sx - gx - 18f;
        float gy = Style.text("A linked officer captains whichever ship " + friendName + " picks, and " + friendName
                + " chooses its skills when it levels up. Linking one another friend has moves it over.", body, Style.DIM, gx, bodyTop - 14f, gw) - 12f;
        if (v.officers.isEmpty()) Style.text("You have no officers to link: hire some at a market's bar or comms.", body, Panel.WARN, gx, gy, gw);
        float cw = (gw - (COLS - 1) * 10f) / COLS, ch = 104f;
        int rowsFit = Math.max(1, (int) ((gy - bodyBottom - 8f + 10f) / (ch + 10f)));
        int rows = (v.officers.size() + COLS - 1) / COLS;
        scroll = Math.max(0, Math.min(scroll, rows - rowsFit));
        if (v.picked / COLS < scroll) scroll = v.picked / COLS;
        if (v.picked / COLS >= scroll + rowsFit) scroll = v.picked / COLS - rowsFit + 1;
        for (int i = scroll * COLS; i < v.officers.size() && i < (scroll + rowsFit) * COLS; i++) {
            Officer o = v.officers.get(i);
            int r = i / COLS - scroll, c = i % COLS;
            float cx = gx + c * (cw + 10f), cy = gy - (r + 1) * ch - r * 10f;
            Widgets.card(cx, cy, cw, ch, i == v.picked, o.mine ? Style.alpha(Panel.GOOD, 200) : null);
            hits.add(cx, cy, cw, ch, "officer:" + i);
            Widgets.picture(o.portrait, cx + 10f, cy + ch - 10f - 64f, 64f, null);
            float ox = cx + 84f, ow = cw - 94f;
            float ly = Style.text(Widgets.fit(o.name, body, ow), body, Style.TEXT, ox, cy + ch - 10f, ow);
            ly = Style.text("Level " + o.level + (o.personality.isEmpty() ? "" : ", " + o.personality), Style.SMALL_FONT, Style.DIM, ox, ly - 3f, ow) - 6f;
            for (int k = 0; k < o.skills.size() && k < 8; k++) { // a mark per skill, gold for elite
                float px = ox + k * 18f;
                Style.fill(px, ly - 12f, 12f, 12f, new Color(20, 50, 77));
                Style.outline(px, ly - 12f, 12f, 12f, o.elite.get(k) ? Widgets.ELITE : Style.alpha(Style.ACCENT, 160));
            }
            Style.text(Widgets.fit(o.status, Style.SMALL_FONT, ow), Style.SMALL_FONT, o.statusColor, ox, cy + 22f, ow);
        }
        if (rows > rowsFit) Style.textRight("scroll for more (" + (scroll + 1) + "-" + Math.min(rows, scroll + rowsFit) + " of " + rows + " rows)", Style.SMALL_FONT, Style.FAINT, sx - 18f, bodyBottom + 18f);

        if (!v.officers.isEmpty()) side(v.officers.get(v.picked), friendName, sx + 18f, bodyTop - 16f, sideW - 36f, bodyBottom + 10f, v);

        // footer
        Style.fill(x + 1f, y + foot, w - 2f, 1f, Style.RULE);
        if (!v.message.isEmpty()) Style.text(Widgets.fit(v.message, body, w - 40f), body, v.messageColor, x + 18f, y + foot - 8f, w - 36f);
        else Style.text("Click: choose     Arrows: officer     Tab / Shift+Tab: friend     Enter: link     Delete: unlink     F1: keys     Esc: close",
                Style.SMALL_FONT, Style.FAINT, x + 18f, y + foot - 11f, w - 36f);
    }

    private void side(Officer o, String friendName, float x, float top, float w, float floor, View v) {
        String body = Style.body();
        Widgets.picture(o.portrait, x, top - 96f, 96f, null);
        float ty = Style.text(o.name, Style.TITLE_FONT, Style.TEXT, x + 108f, top - 6f, w - 108f);
        ty = Style.text("Level " + o.level + (o.personality.isEmpty() ? "" : ", " + o.personality), body, Style.DIM, x + 108f, ty - 4f, w - 108f);
        Style.text(o.status, body, o.statusColor, x + 108f, ty - 4f, w - 108f);
        float y = Widgets.head("Skills", x, top - 96f - 14f);
        float buttonsTop = floor + 44f + 10f;
        float noteH = o.moveNote.isEmpty() || o.mine ? 0f : Style.height(o.moveNote, body, w - 14f) + 14f;
        for (int i = 0; i < o.skills.size(); i++) {
            if (y - 30f < buttonsTop + noteH) { Style.text("and " + (o.skills.size() - i) + " more", body, Style.DIM, x, y, w); break; }
            String id = o.skills.get(i);
            boolean el = o.elite.get(i);
            Widgets.picture(Widgets.skillIcon(id), x, y - 28f, 28f, el ? Widgets.ELITE : null);
            Style.text(Widgets.fit(Widgets.skillName(id), body, w - 100f), body, Style.TEXT, x + 38f, y - (28f - Style.lineHeight(body)) / 2f, w - 100f);
            if (el) Style.textRight("ELITE", Style.SMALL_FONT, Widgets.ELITE, x + w, y - (28f - Style.lineHeight(Style.SMALL_FONT)) / 2f);
            y -= 34f;
        }
        if (o.skills.isEmpty()) Style.text("No skills yet.", body, Style.DIM, x, y, w);
        if (noteH > 0f) Style.callout(o.moveNote, Panel.WARN, x, buttonsTop + noteH - 4f, w);
        // LINK / UNLINK
        boolean can = !v.friends.isEmpty();
        String link = o.mine ? "LINKED" : "LINK TO " + friendName.toUpperCase();
        float uw = 110f, lw = w - uw - 8f;
        boolean overL = can && !o.mine && Widgets.over(x, floor, lw, 44f);
        Style.button(x, floor, lw, 44f, Widgets.fit(link, Style.TITLE_FONT, lw - 16f), Panel.GOOD, can && !o.mine, overL);
        if (can && !o.mine) hits.add(x, floor, lw, 44f, "link");
        boolean linked = o.mine || !o.moveNote.isEmpty() && o.statusColor == Panel.WARN;
        Style.button(x + lw + 8f, floor, uw, 44f, "UNLINK", Panel.WARN, linked, linked && Widgets.over(x + lw + 8f, floor, uw, 44f));
        if (linked) hits.add(x + lw + 8f, floor, uw, 44f, "unlink");
    }
}
