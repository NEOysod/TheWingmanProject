package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;

/**
 * The CO-OP panel on the campaign map (host setting playerList), top right: who is connected, in their colour, the
 * ship each picked, their linked officer and how their refits are taken. A click on a friend opens their row: the
 * officer (ASSIGN or CHANGE opens the officer links page on them), their refits (LOOK ONLY, ASK ME, TRUST) and any
 * refit of theirs waiting (REVIEW). REFITS in the header opens the friend refit page, SETTINGS the settings page.
 * Shown only while friends are connected and nothing (a dialog, a fleet or cargo screen) is open over the map.
 * Registered on each game load as a transient listener: nothing of it goes into the save.
 */
final class CampaignPlayerList implements CampaignUIRenderingListener, CampaignInputListener {
    private static final float W = 440f;
    private static final int MAX_ROWS = 8;
    static final String[] PERMS = { "Look only", "Ask me", "Trust" };
    private static final String[] PERM_HELP = {
        "They can plan refits here, but not send them.",
        "Each refit waits for you to accept it.",
        "Done as they arrive (every check still runs), until they reconnect." };
    private WingmanConfig cfg;
    private long cfgAt;
    private int failures;
    private boolean broken;
    /** The friend whose row is open (their slot), 0 none. */
    private int openSlot;
    private final Widgets.Hits hits = new Widgets.Hits();

    static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new CampaignPlayerList(), true);
        } catch (Throwable t) {
            Global.getLogger(CampaignPlayerList.class).warn("Wingman: the player list can't be shown on the campaign map", t);
        }
    }

    /** When the panel was last drawn (ms): the friend refit button stays away while it shows (it has REFITS). */
    static volatile long shownAt;

    static boolean showing() { return System.currentTimeMillis() - shownAt < 250; }

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {
        bottom = Float.MAX_VALUE; // until it is drawn this frame
        settingsButton = null;
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN || CampaignSettings.isOpen() || FriendRefitPanel.isOpen() || OfficerLinkPanel.isOpen()) { hits.clear(); return; }
        try {
            long now = System.currentTimeMillis();
            if (cfg == null || now - cfgAt > 5000) { cfg = WingmanConfig.load(); cfgAt = now; }
            HostSession s = HostSession.active();
            if (!cfg.playerList || s == null || s.getPlayers().isEmpty()) { hits.clear(); return; }
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null || ui.isShowingDialog() || ui.getCurrentCoreTab() != null) { hits.clear(); return; } // only over the map itself
            draw(rows(s, now), FriendRefit.pending.size());
            shownAt = now;
            failures = 0;
        } catch (Throwable t) {
            Style.forget();
            hits.clear();
            if (++failures >= 60) {
                broken = true;
                Global.getLogger(CampaignPlayerList.class).error("Wingman: the player list keeps failing to draw on the campaign map and is switched off", t);
            }
        }
    }

    // ---- clicks: on the panel they do what it shows, and none goes through to the map ----

    public int getListenerInputPriority() { return 95; }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || hits.empty() || !showing()) return;
        try {
            float scale = Global.getSettings().getScreenScaleMult();
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isMouseEvent() || e.isMouseMoveEvent()) continue;
                float mx = e.getX() / scale, my = e.getY() / scale;
                String id = hits.at(mx, my);
                if (id == null || id.equals("settings")) continue; // (the settings page takes its own button)
                boolean press = e.isLMBDownEvent();
                e.consume();
                if (press && !id.isEmpty()) act(id);
            }
        } catch (Throwable t) {
            Global.getLogger(CampaignPlayerList.class).warn("Wingman: a click on the CO-OP panel failed", t);
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}

    private void act(String id) {
        Widgets.click();
        String[] p = id.split(":", 3);
        HostSession.Player pl = p.length > 1 ? bySlot(p[1]) : null;
        if (p[0].equals("row")) {
            int slot = Integer.parseInt(p[1]);
            openSlot = openSlot == slot ? 0 : slot;
        } else if (p[0].equals("refits")) {
            FriendRefitPanel.openFor(null);
        } else if (p[0].equals("officer") && pl != null) {
            OfficerLinkPanel.openFor(pl.name);
        } else if (p[0].equals("review") && pl != null) {
            FriendRefitPanel.openFor(pl.name);
        } else if (p[0].equals("perm") && pl != null && p.length > 2) {
            setPermission(pl, Integer.parseInt(p[2]));
        }
        builtAt = 0; // shown as it now is
    }

    private static HostSession.Player bySlot(String slot) {
        HostSession s = HostSession.active();
        if (s == null) return null;
        for (HostSession.Player p : s.getPlayers()) if (String.valueOf(p.slot).equals(slot)) return p;
        return null;
    }

    /** How a friend's refits are taken: 0 look only, 1 ask, 2 trust. */
    static int permission(HostSession.Player p) { return p.refitsLookOnly ? 0 : p.trustedRefits ? 2 : 1; }

    static void setPermission(HostSession.Player p, int k) {
        p.refitsLookOnly = k == 0;
        p.trustedRefits = k == 2;
        String text = k == 0 ? p.name + "'s refits: look only. They can plan them but not send them."
                : k == 2 ? "You trust " + p.name + "'s refits: done as they arrive (checks still run), until they reconnect."
                : p.name + "'s refits wait for your yes.";
        CampaignLobby.say("Co-op: " + text);
        FriendRefit.permissionChanged(p);
    }

    // ---- what it says: one row per player, read once a second ----

    static final class Row {
        int slot;
        String name = "", ship = "";
        boolean quiet, host;
        /** Their linked officer ("" none), its portrait, level and personality. */
        String officer = "", portrait = "", officerNote = "";
        /** 0 look only, 1 ask, 2 trust. */
        int permission = 1;
        /** Their refit waiting ("" none). */
        String refitShip = "";
    }

    /** The SETTINGS button in the list's header as drawn ({x, y, w, h}), or null: CampaignSettings opens on a click. */
    static volatile float[] settingsButton;

    /** The bottom edge of the list as drawn this frame (screen units), for what goes under it; huge when not shown. */
    static volatile float bottom = Float.MAX_VALUE;

    private List<Row> list = new ArrayList<Row>();
    private int more;
    private long builtAt;

    private List<Row> rows(HostSession s, long now) {
        if (now - builtAt >= 1000) {
            builtAt = now;
            List<Row> l = new ArrayList<Row>();
            Row me = new Row();
            me.slot = 1; me.name = cfg.playerName + " (you)"; me.host = true; me.ship = "host";
            l.add(me);
            Map<String, String> ships = new HashMap<String, String>();
            for (FleetMemberAPI m : CampaignLobby.fleet()) ships.put(m.getId(), CampaignLobby.label(m));
            List<HostSession.Player> ps = new ArrayList<HostSession.Player>(s.getPlayers());
            Collections.sort(ps, new Comparator<HostSession.Player>() {
                public int compare(HostSession.Player a, HostSession.Player b) { return a.slot - b.slot; }
            });
            more = 0;
            for (int i = 0; i < ps.size(); i++) {
                HostSession.Player p = ps.get(i);
                if (l.size() >= MAX_ROWS) { more = ps.size() - i; break; }
                Row r = new Row();
                r.slot = p.slot;
                r.name = p.name;
                r.quiet = p.conn.idleMillis() > 1500;
                String pick = WingmanAPI.reservedShip(p.name), ship = pick == null ? null : ships.get(pick);
                r.ship = ship == null ? "" : ship;
                PersonAPI o = OfficerLinks.officerOf(p.name);
                if (o != null) {
                    r.officer = o.getNameString();
                    r.portrait = o.getPortraitSprite();
                    String pers = "";
                    try { pers = o.getPersonalityAPI().getDisplayName().toLowerCase(); } catch (Throwable t) { /* none */ }
                    r.officerNote = "Level " + o.getStats().getLevel() + (pers.isEmpty() ? "" : ", " + pers) + "  -  captains the ship " + p.name + " picks";
                }
                r.permission = permission(p);
                FriendRefit.Pending pd = FriendRefit.pending.get(p.id);
                if (pd != null) r.refitShip = pd.ship;
                l.add(r);
            }
            list = l;
        }
        return list;
    }

    // ---- drawing: a window, top right ----

    private static final float ROW_H = 46f;

    private float openHeight(Row r) {
        String body = Style.body();
        float lh = Style.lineHeight(body), sh = Style.lineHeight(Style.SMALL_FONT);
        float h = 6f + sh + 5f + 44f + 12f;                       // OFFICER: the portrait row
        h += sh + 5f + 30f + 6f + lh + 10f;                       // THEIR REFITS: the toggles and a line
        if (!r.refitShip.isEmpty()) h += 36f + 8f;                // a refit waiting
        return h + 4f;
    }

    void draw(List<Row> rows, int waiting) {
        hits.clear();
        String body = Style.body();
        float headH = Style.lineHeight(Style.SMALL_FONT) + 16f;
        float footH = Style.lineHeight(Style.SMALL_FONT) + 10f;
        float h = headH + footH + (more > 0 ? 24f : 0f) + 4f;
        for (Row r : rows) h += ROW_H + (r.slot == openSlot && !r.host ? openHeight(r) : 0f);
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float x = sw - W - 10f, y = sh - h - 10f;
        bottom = y;
        hits.add(x, y, W, h, ""); // the panel: clicks on it go no further
        Style.frame(x, y, W, h, new Color(6, 12, 20, 228), Style.EDGE, 6f);
        float hy = y + h - headH;
        Style.fill(x + 1f, hy, W - 8f, headH - 1f, Style.BAR);
        Style.fill(x + 1f, hy, 3f, headH - 1f, Style.ACCENT);
        float ty = y + h - (headH - Style.lineHeight(Style.SMALL_FONT)) / 2f;
        Style.text("CO-OP", Style.SMALL_FONT, Style.ACCENT, x + 12f, ty, 80f);
        // SETTINGS, then REFITS while any wait
        float bh = headH - 8f, by = hy + 4f, right = x + W - 10f;
        String st = "SETTINGS";
        float sbw = Style.width(st, Style.SMALL_FONT) + 16f;
        Widgets.box(right - sbw, by, sbw, bh, st, Style.TEXT, false);
        settingsButton = new float[] { right - sbw, by, sbw, bh };
        hits.add(right - sbw, by, sbw, bh, "settings");
        right -= sbw + 6f;
        if (waiting > 0) {
            String rt = "REFITS  " + waiting;
            float rbw = Style.width(rt, Style.SMALL_FONT) + 16f;
            boolean pulse = (System.currentTimeMillis() / 600) % 2 == 0;
            Widgets.box(right - rbw, by, rbw, bh, rt, Panel.WARN, pulse);
            hits.add(right - rbw, by, rbw, bh, "refits");
            right -= rbw + 6f;
        }
        Style.textRight((rows.size() + more) + " players", Style.SMALL_FONT, Style.DIM, right - 4f, ty);

        float top = hy;
        for (Row r : rows) {
            boolean open = r.slot == openSlot && !r.host;
            float ry = top - ROW_H;
            if (!r.host) {
                hits.add(x + 2f, ry, W - 4f, ROW_H, "row:" + r.slot);
                if (open) Style.fill(x + 2f, ry, W - 4f, ROW_H, Style.MARKED);
                else if (Widgets.over(x + 2f, ry, W - 4f, ROW_H)) Style.fill(x + 2f, ry, W - 4f, ROW_H, Style.HOVER);
            }
            Color c = PlayerTags.color(r.slot);
            float cw = Style.chip("P" + r.slot, c, x + 12f, top - (ROW_H - Style.chipHeight()) / 2f);
            float tx = x + 12f + cw + 10f;
            // right side: the officer's first name and how their refits are taken
            float rx = x + W - 12f;
            if (!r.host) {
                String tag = PERMS[r.permission].toUpperCase();
                Color tc = r.permission == 2 ? Panel.GOOD : r.permission == 0 ? Style.DIM : Style.ACCENT;
                float tw = Style.width(tag, Style.SMALL_FONT) + 10f;
                Style.outline(rx - tw, top - ROW_H / 2f - 9f, tw, 18f, Style.alpha(tc, 160));
                Style.text(tag, Style.SMALL_FONT, tc, rx - tw + 5f, top - ROW_H / 2f + 6f, tw);
                rx -= tw + 10f;
                if (!r.refitShip.isEmpty()) {
                    Style.fill(rx - 8f, top - ROW_H / 2f - 4f, 8f, 8f, Panel.WARN);
                    rx -= 16f;
                }
                if (!r.officer.isEmpty()) {
                    String first = r.officer.split(" ")[0];
                    rx = Style.textRight(first, body, Panel.GOOD, rx, top - (ROW_H - Style.lineHeight(body)) / 2f) - 10f;
                }
                if (r.quiet) rx = Style.textRight("link quiet", body, Panel.WARN, rx, top - (ROW_H - Style.lineHeight(body)) / 2f) - 10f;
            }
            float tw = Math.max(40f, rx - tx);
            float ny = Style.text(Widgets.fit(r.name, body, tw), body, r.host ? Style.TEXT : c, tx, top - 5f, tw);
            String ship = r.host ? "host: commands the fleet" : r.ship.isEmpty() ? "no ship picked" : r.ship;
            Style.text(Widgets.fit(ship, body, tw), body, r.ship.isEmpty() && !r.host ? Style.FAINT : Style.DIM, tx, ny - 1f, tw);
            top = ry;
            if (open) top = drawOpen(r, x, top);
            Style.fill(x + 8f, top, W - 16f, 1f, Style.alpha(Style.RULE, 40));
        }
        if (more > 0) { Style.text("and " + more + " more", body, Style.DIM, x + 12f, top - 4f, W - 24f); top -= 24f; }
        Style.text("Click a friend to manage them.", Style.SMALL_FONT, Style.FAINT, x + 12f, y + footH - 4f, W - 24f);
    }

    /** A friend's row, opened: their officer, how their refits are taken, a refit of theirs waiting. Returns its bottom. */
    private float drawOpen(Row r, float x, float top) {
        String body = Style.body();
        float h = openHeight(r);
        Style.fill(x + 2f, top - h, W - 4f, h, new Color(12, 29, 48, 230));
        float px = x + 50f, pw = W - 50f - 14f;
        float y = Widgets.head("Officer", px, top - 6f);
        Widgets.picture(r.portrait.isEmpty() ? null : r.portrait, px, y - 44f, 44f, null);
        String act = r.officer.isEmpty() ? "ASSIGN" : "CHANGE";
        float bw = Style.width(act, Style.SMALL_FONT) + 24f;
        Widgets.box(px + pw - bw, y - 36f, bw, 28f, act, Style.ACCENT, false);
        hits.add(px + pw - bw, y - 36f, bw, 28f, "officer:" + r.slot);
        float tw = pw - 44f - 10f - bw - 8f;
        float ny = Style.text(r.officer.isEmpty() ? "None linked" : r.officer, body, r.officer.isEmpty() ? Style.DIM : Style.TEXT, px + 54f, y - 2f, tw);
        Style.text(r.officer.isEmpty() ? "Their ship keeps the officer it has, if any." : r.officerNote, Style.SMALL_FONT, Style.DIM, px + 54f, ny - 2f, tw);
        y -= 44f + 12f;
        y = Widgets.head("Their refits", px, y);
        Widgets.toggles(PERMS, r.permission, px, y - 30f, pw, 30f, Style.ACCENT, hits, "perm:" + r.slot);
        y -= 30f + 6f;
        y = Style.text(PERM_HELP[r.permission], Style.SMALL_FONT, Style.DIM, px, y, pw) - 10f;
        if (!r.refitShip.isEmpty()) {
            float rh = 36f, ry = y - rh;
            boolean over = Widgets.over(px, ry, pw, rh);
            Style.fill(px, ry, pw, rh, Style.alpha(Panel.WARN, over ? 46 : 22));
            Style.outline(px, ry, pw, rh, Style.alpha(Panel.WARN, over ? 230 : 140));
            float lh = Style.lineHeight(body);
            Style.text(Widgets.fit("Refit waiting: " + r.refitShip, body, pw - 90f), body, Panel.WARN, px + 10f, ry + (rh + lh) / 2f, pw - 90f);
            Style.textRight("REVIEW", Style.SMALL_FONT, over ? Color.WHITE : Panel.WARN, px + pw - 10f, ry + (rh + Style.lineHeight(Style.SMALL_FONT)) / 2f);
            hits.add(px, ry, pw, rh, "review:" + r.slot);
        }
        return top - h;
    }
}
