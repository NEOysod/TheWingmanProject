package wingman.host;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

import wingman.net.CampaignMap;

/**
 * Host side of Msg.MAP: a read-only look at where the host's fleet is. In a star system every planet, station and
 * jump point; in hyperspace only star systems and jump points near the host. Fleets only as far as the host's own
 * sensors see them, with as much detail as the host has. Nothing here changes the campaign.
 */
final class HostCampaignMap {
    /** How far around the host hyperspace is shown (the host's hyperspace map zoomed in a little). */
    static final float HYPER_VIEW = 6000f;

    private HostCampaignMap() {}

    /** Null when there is no player fleet or location to show. */
    static CampaignMap build() {
        final CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player == null) return null;
        LocationAPI loc = player.getContainingLocation();
        if (loc == null) return null;
        CampaignMap m = new CampaignMap();
        m.location = loc.isHyperspace() ? "Hyperspace" : loc.getName();
        m.hyperspace = loc.isHyperspace();
        m.paused = Global.getSector().isPaused();
        m.playerX = player.getLocation().x; m.playerY = player.getLocation().y;
        m.playerVx = player.getVelocity().x; m.playerVy = player.getVelocity().y;

        if (m.hyperspace) {
            m.viewX = m.playerX; m.viewY = m.playerY; m.viewRadius = HYPER_VIEW;
            for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
                if (near(sys.getLocation().x, sys.getLocation().y, m) && m.bodies.size() < CampaignMap.MAX_BODIES) {
                    CampaignMap.Body b = body(CampaignMap.SYSTEM, sys.getLocation().x, sys.getLocation().y, 150f, sys.getBaseName());
                    m.bodies.add(b);
                }
            }
            for (SectorEntityToken jp : loc.getJumpPoints()) {
                if (near(jp.getLocation().x, jp.getLocation().y, m)) add(m, CampaignMap.JUMP_POINT, jp);
            }
        } else {
            for (PlanetAPI p : loc.getPlanets()) add(m, p.isStar() ? CampaignMap.STAR : CampaignMap.PLANET, p);
            for (SectorEntityToken st : loc.getEntitiesWithTag(Tags.STATION)) add(m, CampaignMap.STATION, st);
            for (SectorEntityToken jp : loc.getJumpPoints()) add(m, CampaignMap.JUMP_POINT, jp);
            float r = 1500f;
            for (CampaignMap.Body b : m.bodies) r = Math.max(r, len(b.x, b.y) + b.radius);
            r = Math.max(r, len(m.playerX, m.playerY));
            m.viewRadius = r * 1.05f;
        }

        List<CampaignFleetAPI> seen = new ArrayList<CampaignFleetAPI>();
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (f == player || f.isPlayerFleet() || f.isStationMode()) continue; // stations already drawn as bodies
            if (f.getVisibilityLevelToPlayerFleet() == SectorEntityToken.VisibilityLevel.NONE) continue;
            if (m.hyperspace && !near(f.getLocation().x, f.getLocation().y, m)) continue;
            seen.add(f);
        }
        Collections.sort(seen, new Comparator<CampaignFleetAPI>() {
            public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
                return Float.compare(dist2(a, player), dist2(b, player));
            }
        });
        for (int i = 0; i < seen.size() && i < CampaignMap.MAX_FLEETS; i++) m.fleets.add(fleet(seen.get(i), player));
        return m;
    }

    private static CampaignMap.Fleet fleet(CampaignFleetAPI f, CampaignFleetAPI player) {
        CampaignMap.Fleet out = new CampaignMap.Fleet();
        SectorEntityToken.VisibilityLevel v = f.getVisibilityLevelToPlayerFleet();
        out.detail = v == SectorEntityToken.VisibilityLevel.COMPOSITION_AND_FACTION_DETAILS ? CampaignMap.KNOWN
                : v == SectorEntityToken.VisibilityLevel.COMPOSITION_DETAILS ? CampaignMap.SHAPE : CampaignMap.BLIP;
        out.x = f.getLocation().x; out.y = f.getLocation().y;
        out.vx = f.getVelocity().x; out.vy = f.getVelocity().y;
        if (out.detail != CampaignMap.BLIP) out.ships = f.getFleetData().getNumMembers();
        if (out.detail == CampaignMap.KNOWN) {
            out.hostile = f.isHostileTo(player);
            out.name = f.getNameWithFaction();
            if (f.getFaction() != null) out.color = rgb(f.getFaction().getBaseUIColor());
        }
        return out;
    }

    private static void add(CampaignMap m, byte kind, SectorEntityToken e) {
        if (m.bodies.size() >= CampaignMap.MAX_BODIES) return;
        CampaignMap.Body b = body(kind, e.getLocation().x, e.getLocation().y, e.getRadius(), e.getName());
        MarketAPI market = e.getMarket();
        if (market != null && !market.isPlanetConditionMarketOnly() && market.getFaction() != null) {
            b.color = rgb(market.getFaction().getBaseUIColor());
        }
        m.bodies.add(b);
    }

    private static CampaignMap.Body body(byte kind, float x, float y, float radius, String name) {
        CampaignMap.Body b = new CampaignMap.Body();
        b.kind = kind; b.x = x; b.y = y; b.radius = radius; b.name = name == null ? "" : name;
        return b;
    }

    private static boolean near(float x, float y, CampaignMap m) {
        float dx = x - m.viewX, dy = y - m.viewY;
        return dx * dx + dy * dy <= m.viewRadius * m.viewRadius;
    }

    private static float len(float x, float y) { return (float) Math.sqrt(x * x + y * y); }

    private static float dist2(SectorEntityToken a, SectorEntityToken b) {
        float dx = a.getLocation().x - b.getLocation().x, dy = a.getLocation().y - b.getLocation().y;
        return dx * dx + dy * dy;
    }

    /** 0 means "the default colour", so pure black is nudged to 1. */
    private static int rgb(Color c) {
        if (c == null) return 0;
        int v = c.getRGB() & 0xFFFFFF;
        return v == 0 ? 1 : v;
    }
}
