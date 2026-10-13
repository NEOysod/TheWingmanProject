package wingman;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.net.BattleReport;
import wingman.net.HostSession;

/**
 * Host side of the after-action report: who flew what, the damage each player dealt and took, and their kills,
 * counted from the game's own damage reports. Only counts; it changes nothing in the battle.
 */
final class BattleStats implements DamageListener {
    /** A kill goes to the player who last hurt the ship within this long, if the killing blow was nobody's. */
    private static final float KILL_WINDOW = 20f;

    private CombatEngineAPI engine;
    private String hostName = "";
    private boolean listening, broken;
    private final Map<String, BattleReport.Pilot> pilots = new LinkedHashMap<String, BattleReport.Pilot>();
    private final Map<ShipAPI, String> lastHitBy = new WeakHashMap<ShipAPI, String>();
    private final Map<ShipAPI, Float> lastHitAt = new WeakHashMap<ShipAPI, Float>();
    private final Set<ShipAPI> dead = Collections.newSetFromMap(new WeakHashMap<ShipAPI, Boolean>());
    private final Map<String, ShipAPI> lastShip = new LinkedHashMap<String, ShipAPI>();
    private final int[] fightersLost = new int[2];
    private float lastTick = -1f;

    void start(CombatEngineAPI engine, String hostName) {
        this.engine = engine;
        this.hostName = hostName == null ? "" : hostName;
        if (listening || broken) return;
        try {
            engine.getListenerManager().addListener(this);
            listening = true;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(BattleStats.class).warn("Wingman: battle statistics are off for this battle (the after-action report will only show losses)", t);
        }
    }

    private BattleReport.Pilot pilot(String name) {
        BattleReport.Pilot p = pilots.get(name);
        if (p == null) { p = new BattleReport.Pilot(); p.name = name; pilots.put(name, p); }
        return p;
    }

    /** The player behind a ship (its carrier's or mothership's pilot for fighters and drones), or null for the AI. */
    private String who(ShipAPI s) {
        if (s == null) return null;
        try {
            ShipAPI src = s;
            if (s.isFighter() && s.getWing() != null && s.getWing().getSourceShip() != null) src = s.getWing().getSourceShip();
            else if (s.isDrone() && s.getDroneSource() != null) src = s.getDroneSource();
            if (src.getParentStation() != null) src = src.getParentStation();
            String name = wingman.api.WingmanAPI.pilotOf(src);
            if (name == null && src == engine.getPlayerShip()) name = hostName;
            return name;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ShipAPI ship(Object source) {
        if (source instanceof ShipAPI) return (ShipAPI) source;
        if (source instanceof DamagingProjectileAPI) return ((DamagingProjectileAPI) source).getSource();
        if (source instanceof BeamAPI) return ((BeamAPI) source).getSource();
        return null;
    }

    public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
        if (broken || !(target instanceof ShipAPI) || result == null) return;
        try {
            ShipAPI t = (ShipAPI) target;
            ShipAPI from = ship(source);
            float body = Math.max(0f, result.getDamageToHull()) + Math.max(0f, result.getTotalDamageToArmor());
            float shield = Math.max(0f, result.getDamageToShields());
            String by = from == null || from.getOwner() == t.getOwner() ? null : who(from);
            if (by != null) {
                BattleReport.Pilot p = pilot(by);
                p.damageDealt += body;
                p.shieldDamageDealt += shield;
                lastHitBy.put(t, by);
                lastHitAt.put(t, engine.getTotalElapsedTime(false));
            }
            if (!t.isFighter()) {
                String hurt = who(t);
                if (hurt != null) pilot(hurt).damageTaken += body;
            }
            if (t.getHitpoints() <= 0f || t.isHulk()) died(t, by);
        } catch (Throwable e) {
            broken = true;
            Global.getLogger(BattleStats.class).warn("Wingman: battle statistics stopped for this battle", e);
        }
    }

    private void died(ShipAPI s, String killer) {
        if (!dead.add(s)) return;
        if (s.isFighter() && s.getOwner() >= 0 && s.getOwner() < 2) fightersLost[s.getOwner()]++;
        if (s.getParentStation() != null) return; // a module of a station or ship: the ship itself counts
        if (killer == null) {
            String last = lastHitBy.get(s);
            Float at = lastHitAt.get(s);
            if (last != null && at != null && engine.getTotalElapsedTime(false) - at <= KILL_WINDOW) killer = last;
        }
        if (killer == null || !(s.getOwner() == 1 || s.getOwner() == 0)) return;
        BattleReport.Pilot p = pilot(killer);
        if (s.isFighter()) p.fighterKills++; else p.kills++;
    }

    /** Four times a second: deaths the damage reports missed, and which ship each player is in. */
    void tick() {
        if (engine == null) return;
        float now = engine.getTotalElapsedTime(false);
        if (now - lastTick < 0.25f) return;
        lastTick = now;
        try {
            for (ShipAPI s : engine.getShips()) {
                if (s.isHulk() || !s.isAlive()) { died(s, null); continue; }
                if (s.isFighter() || s.getParentStation() != null) continue;
                String name = who(s);
                if (name != null && (s == engine.getPlayerShip() || wingman.api.WingmanAPI.pilotOf(s) != null)) {
                    lastShip.put(name, s);
                    pilot(name);
                }
            }
        } catch (Throwable t) { /* counted as far as it got */ }
    }

    private static String label(ShipAPI s) {
        String name = s.getName();
        String hull = s.getHullSpec() == null ? "" : s.getHullSpec().getHullName();
        return name == null || name.isEmpty() ? hull : name + " (" + hull + ")";
    }

    private static String label(FleetMemberAPI m) {
        String name = m.getShipName();
        String hull = m.getHullSpec() == null ? "" : m.getHullSpec().getHullName();
        return name == null || name.isEmpty() ? hull : name + " (" + hull + ")";
    }

    /** The report, at the end of the battle. The players are the host and whoever is connected (or flew in it). */
    BattleReport build(HostSession session, Map<String, Integer> slots) {
        tick();
        BattleReport r = new BattleReport();
        try {
            int w = engine.getWinningSideId();
            r.outcome = w == 0 ? 0 : w == 1 ? 1 : 2;
        } catch (Throwable t) { r.outcome = 2; }
        r.seconds = engine.getTotalElapsedTime(false);
        for (int side = 0; side < 2; side++) {
            BattleReport.Side s = r.sides[side];
            s.fightersLost = fightersLost[side];
            try {
                CombatFleetManagerAPI fm = engine.getFleetManager(side);
                List<FleetMemberAPI> destroyed = fm.getDestroyedCopy(), disabled = fm.getDisabledCopy(), retreated = fm.getRetreatedCopy();
                s.destroyed = destroyed.size();
                s.disabled = disabled.size();
                s.retreated = retreated.size();
                s.deployed = fm.getDeployedCopy().size() + s.destroyed + s.disabled + s.retreated;
                List<FleetMemberAPI> lost = new ArrayList<FleetMemberAPI>(destroyed);
                lost.addAll(disabled);
                Collections.sort(lost, new Comparator<FleetMemberAPI>() {
                    public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                        return b.getHullSpec().getHullSize().ordinal() - a.getHullSpec().getHullSize().ordinal();
                    }
                });
                for (FleetMemberAPI m : lost) {
                    if (m.isFighterWing() || s.lost.size() >= BattleReport.MAX_LOST) continue;
                    s.lost.add(label(m));
                }
            } catch (Throwable t) { /* that side's numbers as far as they go */ }
        }
        // Everyone who was in it: the host, those connected, and anyone who flew and has left.
        List<String> names = new ArrayList<String>();
        names.add(hostName);
        for (HostSession.Player p : session.getPlayers()) if (!names.contains(p.name)) names.add(p.name);
        for (String n : pilots.keySet()) if (!names.contains(n)) names.add(n);
        for (String n : names) {
            BattleReport.Pilot p = pilot(n);
            Integer slot = slots.get(n);
            p.slot = n.equals(hostName) ? 1 : slot == null ? 0 : slot.intValue();
            boolean connected = n.equals(hostName);
            for (HostSession.Player hp : session.getPlayers()) if (hp.name.equals(n)) connected = true;
            ShipAPI s = lastShip.get(n);
            if (s == null) p.status = connected ? BattleReport.WATCHED : BattleReport.LEFT;
            else {
                p.ship = label(s);
                boolean inPlay;
                try { inPlay = engine.isEntityInPlay(s); } catch (Throwable t) { inPlay = true; }
                p.status = s.isHulk() || !s.isAlive() ? BattleReport.DESTROYED : !inPlay ? BattleReport.RETREATED : connected ? BattleReport.ALIVE : BattleReport.LEFT;
            }
            r.pilots.add(p);
        }
        return r;
    }

    /** One line for the host's log. */
    static String describe(BattleReport r) {
        StringBuilder sb = new StringBuilder("Wingman after-action report: ").append(r.outcome == 0 ? "won" : r.outcome == 1 ? "lost" : "undecided")
                .append(" after ").append(Math.round(r.seconds)).append(" s; losses ").append(r.sides[0].destroyed + r.sides[0].disabled)
                .append(" ours, ").append(r.sides[1].destroyed + r.sides[1].disabled).append(" theirs");
        for (BattleReport.Pilot p : r.pilots) {
            sb.append("; ").append(p.name).append(": ").append(p.kills).append(" kills, ").append(Math.round(p.damageDealt)).append(" damage dealt, ")
                    .append(Math.round(p.damageTaken)).append(" taken");
        }
        return sb.toString();
    }
}
