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
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;

/**
 * The player list on the campaign map (host setting playerList): a small box at the top right with who is
 * connected, in their colour, and the ship each picked for the next battle; a player whose link has gone quiet is
 * marked. Shown only while friends are connected and nothing (a dialog, a fleet or cargo screen) is open over the
 * map. Registered on each game load as a transient listener: nothing of it goes into the save.
 */
final class CampaignPlayerList implements CampaignUIRenderingListener {
    private static final float MAX_W = 300f;
    private static final int MAX_ROWS = 8;
    private WingmanConfig cfg;
    private long cfgAt;
    private int failures;
    private boolean broken;

    static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new CampaignPlayerList(), true);
        } catch (Throwable t) {
            Global.getLogger(CampaignPlayerList.class).warn("Wingman: the player list can't be shown on the campaign map", t);
        }
    }

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {
        bottom = Float.MAX_VALUE; // until it is drawn this frame
        settingsButton = null;
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            long now = System.currentTimeMillis();
            if (cfg == null || now - cfgAt > 5000) { cfg = WingmanConfig.load(); cfgAt = now; }
            HostSession s = HostSession.active();
            if (!cfg.playerList || s == null || s.getPlayers().isEmpty()) return;
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null || ui.isShowingDialog() || ui.getCurrentCoreTab() != null) return; // only over the map itself
            draw(rows(s, now));
            failures = 0;
        } catch (Throwable t) {
            Style.forget();
            if (++failures >= 60) {
                broken = true;
                Global.getLogger(CampaignPlayerList.class).error("Wingman: the player list keeps failing to draw on the campaign map and is switched off", t);
            }
        }
    }

    // ---- what it says: one row per player, read once a second ----

    /** A row: slot, name, ship ("" none), and whether their link has gone quiet. */
    private static final class Row {
        final int slot;
        final String name, ship;
        final boolean quiet;
        Row(int slot, String name, String ship, boolean quiet) { this.slot = slot; this.name = name; this.ship = ship; this.quiet = quiet; }
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
            l.add(new Row(1, cfg.playerName + " (you)", "host", false));
            Map<String, String> ships = new HashMap<String, String>();
            for (FleetMemberAPI m : CampaignLobby.fleet()) ships.put(m.getId(), m.getShipName() == null || m.getShipName().isEmpty() ? m.getHullSpec().getHullName() : m.getShipName());
            List<HostSession.Player> ps = new ArrayList<HostSession.Player>(s.getPlayers());
            Collections.sort(ps, new Comparator<HostSession.Player>() {
                public int compare(HostSession.Player a, HostSession.Player b) { return a.slot - b.slot; }
            });
            more = 0;
            for (int i = 0; i < ps.size(); i++) {
                HostSession.Player p = ps.get(i);
                if (l.size() >= MAX_ROWS) { more = ps.size() - i; break; }
                boolean quiet = p.conn.idleMillis() > 1500;
                String pick = WingmanAPI.reservedShip(p.name), ship = pick == null ? null : ships.get(pick);
                l.add(new Row(p.slot, p.name, ship == null ? "" : ship, quiet));
            }
            list = l;
        }
        return list;
    }

    // ---- drawing: a small window, top right ----

    private void draw(List<Row> rows) {
        String body = Style.body();
        float rowH = Math.max(Style.chipHeight(), Style.lineHeight(body)) + 6f;
        float headH = Style.lineHeight(Style.SMALL_FONT) + 14f;
        float h = headH + rows.size() * rowH + (more > 0 ? rowH : 0f) + 8f;
        float w = MAX_W;
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float x = sw - w - 10f, y = sh - h - 10f;
        bottom = y;
        Style.frame(x, y, w, h, new Color(6, 10, 16, 205), Style.EDGE, 6f);
        Style.fill(x + 1f, y + h - headH, w - 8f, headH - 1f, Style.BAR);
        Style.fill(x + 1f, y + h - headH, 3f, headH - 1f, Style.ACCENT);
        Style.text("CO-OP", Style.SMALL_FONT, Style.ACCENT, x + 10f, y + h - 5f, 80f);
        // SETTINGS, a button for the mouse (the same page as Ctrl+Shift+O)
        String st = "SETTINGS";
        float sbw = Style.width(st, Style.SMALL_FONT) + 14f, sbh = headH - 6f, sbx = x + w - 10f - sbw, sby = y + h - headH + 3f;
        boolean overS = Style.over(sbx, sby, sbw, sbh);
        Style.fill(sbx, sby, sbw, sbh, overS ? Style.alpha(Style.ACCENT, 60) : Style.CHIP);
        Style.outline(sbx, sby, sbw, sbh, overS ? Style.ACCENT : Style.CHIP_EDGE);
        Style.text(st, Style.SMALL_FONT, overS ? java.awt.Color.WHITE : Style.TEXT, sbx + 7f, y + h - 6f, sbw);
        settingsButton = new float[] { sbx, sby, sbw, sbh };
        Style.textRight((rows.size() + more) + " players", Style.SMALL_FONT, Style.DIM, sbx - 8f, y + h - 5f);
        float top = y + h - headH - 4f;
        for (Row r : rows) {
            Color c = PlayerTags.color(r.slot);
            float cw = Style.chip("P" + r.slot, c, x + 8f, top - (rowH - Style.chipHeight()) / 2f);
            float bh = Style.lineHeight(body), ty = top - (rowH - bh) / 2f;
            float right = x + w - 10f;
            String ship = r.quiet ? "link quiet" : r.ship.isEmpty() ? "no pick" : r.ship;
            float sx = Style.textRight(ship.length() > 22 ? ship.substring(0, 21) + "." : ship, body, r.quiet ? Panel.WARN : r.ship.isEmpty() ? Style.FAINT : Style.DIM, right, ty);
            Style.text(r.name, body, r.slot == 1 ? Style.TEXT : c, x + 8f + cw + 8f, ty, Math.max(30f, sx - (x + 8f + cw + 8f) - 8f));
            top -= rowH;
        }
        if (more > 0) Style.text("and " + more + " more", body, Style.DIM, x + 10f, top - 3f, w - 20f);
    }
}
