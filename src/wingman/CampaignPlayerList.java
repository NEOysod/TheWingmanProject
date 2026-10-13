package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.LabelAPI;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;

/**
 * The player list on the campaign map (host setting playerList): a small box at the top right with who is
 * connected, in their colour, and the ship each picked for the next battle; a player whose link has gone quiet is
 * marked. Shown only while friends are connected and nothing (a dialog, a fleet or cargo screen) is open over the
 * map. Registered on each game load as a transient listener: nothing of it goes into the save.
 */
final class CampaignPlayerList implements CampaignUIRenderingListener {
    private static final String FONT = "graphics/fonts/orbitron12condensed.fnt";
    private static final float PAD = 6f, GAP = 2f, MAX_W = 260f;
    private static final int MAX_ROWS = 8;
    private final Map<String, LabelAPI> cache = new HashMap<String, LabelAPI>();
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
            cache.clear();
            if (++failures >= 60) {
                broken = true;
                Global.getLogger(CampaignPlayerList.class).error("Wingman: the player list keeps failing to draw on the campaign map and is switched off", t);
            }
        }
    }

    // ---- what it says: one line per player, read once a second ----

    private List<String> lines = new ArrayList<String>();
    private List<Color> colors = new ArrayList<Color>();
    private long builtAt;

    private Object[] rows(HostSession s, long now) {
        if (now - builtAt >= 1000) {
            builtAt = now;
            List<String> l = new ArrayList<String>();
            List<Color> c = new ArrayList<Color>();
            l.add("P1  " + cfg.playerName + " (you)");
            c.add(PlayerTags.color(1));
            Map<String, String> ships = new HashMap<String, String>();
            for (FleetMemberAPI m : CampaignLobby.fleet()) ships.put(m.getId(), m.getShipName() == null || m.getShipName().isEmpty() ? m.getHullSpec().getHullName() : m.getShipName());
            List<HostSession.Player> ps = new ArrayList<HostSession.Player>(s.getPlayers());
            Collections.sort(ps, new Comparator<HostSession.Player>() {
                public int compare(HostSession.Player a, HostSession.Player b) { return a.slot - b.slot; }
            });
            for (int i = 0; i < ps.size(); i++) {
                HostSession.Player p = ps.get(i);
                if (l.size() >= MAX_ROWS) { l.add("and " + (ps.size() - i) + " more"); c.add(Panel.DIM); break; }
                boolean quiet = p.conn.idleMillis() > 1500;
                String pick = WingmanAPI.reservedShip(p.name), ship = pick == null ? null : ships.get(pick);
                l.add("P" + p.slot + "  " + p.name + "   " + (ship == null ? "no pick" : ship) + (quiet ? "   (link quiet)" : ""));
                c.add(quiet ? Panel.WARN : PlayerTags.color(p.slot));
            }
            lines = l;
            colors = c;
        }
        return new Object[] { lines, colors };
    }

    // ---- drawing: a small box, top right ----

    private LabelAPI label(String text, Color color) {
        String key = color.getRGB() + "|" + text;
        LabelAPI l = cache.get(key);
        if (l == null) {
            if (cache.size() > 100) cache.clear();
            l = Global.getSettings().createLabel(text, FONT);
            l.setColor(color);
            l.autoSizeToWidth(MAX_W - 2 * PAD);
            cache.put(key, l);
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    private void draw(Object[] rows) {
        List<String> l = (List<String>) rows[0];
        List<Color> c = (List<Color>) rows[1];
        LabelAPI[] labels = new LabelAPI[l.size()];
        float w = 0f, h = PAD * 2f;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label(l.get(i), c.get(i));
            w = Math.max(w, labels[i].computeTextWidth(l.get(i)));
            h += labels[i].getPosition().getHeight() + (i > 0 ? GAP : 0f);
        }
        w = Math.min(MAX_W, w + 2 * PAD);
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float x = sw - w - 10f, y = sh - h - 10f;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.03f, 0.05f, 0.08f, 0.75f);
        quad(GL11.GL_QUADS, x, y, w, h);
        GL11.glColor4f(0.47f, 0.82f, 1f, 0.5f);
        GL11.glLineWidth(1f);
        quad(GL11.GL_LINE_LOOP, x, y, w, h);
        GL11.glPopAttrib();

        float top = y + h - PAD;
        for (LabelAPI lab : labels) {
            float lh = lab.getPosition().getHeight();
            lab.getPosition().setLocation(x + PAD, top - lh);
            lab.render(1f);
            top -= lh + GAP;
        }
    }

    private static void quad(int mode, float x, float y, float w, float h) {
        GL11.glBegin(mode);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }
}
