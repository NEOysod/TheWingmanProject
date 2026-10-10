package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

import wingman.net.Extras;
import wingman.net.HostSession;
import wingman.net.Msg;

/**
 * Host side of the three general-purpose channels: ship stats, the simple values scripts keep, and damage as
 * it happens. Each is checked a few times a second and only what changed is sent. Each can fail by itself
 * without taking the others or the rest of co-op with it.
 */
final class HostExtras implements DamageTakenModifier {
    private final Map<ShipAPI, Integer> ids;
    private final WingmanConfig cfg;
    private boolean statsBroken, notesBroken, hurtsBroken, listening;
    private long lastStats, lastNotes, lastHurts;
    private final Map<Integer, float[]> sentStats = new HashMap<Integer, float[]>();
    private final Map<Integer, Map<String, String>> sentNotes = new HashMap<Integer, Map<String, String>>();
    /** Damage gathered since the last report, by ship hit. */
    private final Map<Integer, Extras.Hurt> hurts = new HashMap<Integer, Extras.Hurt>();
    private final Map<Integer, float[]> biggest = new HashMap<Integer, float[]>();
    int statsSent, notesSent, hurtsSent, ordinary;
    private final Map<String, int[]> streaks = new HashMap<String, int[]>();
    private final java.util.Set<String> restless = new java.util.HashSet<String>();

    /** Ships that have taken damage of any kind since HostDamage last looked. */
    private final java.util.Set<Integer> hit = new java.util.HashSet<Integer>();
    /** Ships whose stats should be looked at on the next pass (a system or a state just changed). */
    private final java.util.Set<Integer> touched = new java.util.HashSet<Integer>();
    private int statRound, noteRound;
    int statLooks, statSkips;

    void touch(int id) { touched.add(id); }

    /** Null until the game is telling us about damage (and if it never does): then nobody can say which ships were hit. */
    java.util.Set<Integer> hitLately() { return listening && !listenBroken ? hit : null; }
    private boolean listenBroken;

    /** Fighters to treat as ships here (they have officers of their own). */
    private final java.util.Set<Integer> officered;

    HostExtras(Map<ShipAPI, Integer> ids, WingmanConfig cfg, java.util.Set<Integer> officered) {
        this.officered = officered;
        this.ids = ids;
        this.cfg = cfg;
    }

    /** A player has just been sent the whole battle: everything here goes out afresh too. */
    void resendAll() {
        sentStats.clear();
        sentNotes.clear();
    }

    void tick(CombatEngineAPI engine, HostSession session, Map<Integer, ShipAPI> byId) {
        if (session.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        // The game tells us of every piece of damage. That serves two things: knowing which ships to re-read
        // for armor (always), and the damage reports (if switched on).
        if (!listening && !listenBroken) {
            try { engine.getListenerManager().addListener(this); listening = true; }
            catch (Throwable t) { listenBroken = true; hurtsBroken = true; }
        }
        if (cfg.damageReports && !hurtsBroken) {
            try {
                if (now - lastHurts >= 250) { lastHurts = now; sendHurts(session); }
            } catch (Throwable t) {
                hurtsBroken = true;
                Global.getLogger(HostExtras.class).error("Wingman: reporting damage failed; damage reports are off for this battle", t);
            }
        }
        if (cfg.syncStats && !statsBroken && now - lastStats >= 250) {
            lastStats = now;
            try {
                sendStats(session, byId);
            } catch (Throwable t) {
                statsBroken = true;
                Global.getLogger(HostExtras.class).error("Wingman: sending ship stats failed; stats are not kept alike for this battle", t);
            }
        }
        if (cfg.syncScriptMemory && !notesBroken && now - lastNotes >= 500) {
            lastNotes = now;
            try {
                sendNotes(engine, session, byId);
            } catch (Throwable t) {
                notesBroken = true;
                Global.getLogger(HostExtras.class).error("Wingman: sending script values failed; they are not copied for this battle", t);
            }
        }
    }

    private void sendStats(HostSession session, Map<Integer, ShipAPI> byId) {
        List<Extras.Stats> out = new ArrayList<Extras.Stats>();
        float[] now = new float[ShipStats.COUNT];
        statRound++;
        java.util.Set<Integer> flown = new java.util.HashSet<Integer>();
        for (HostSession.Player p : session.getPlayers()) if (p.shipNetId >= 0) flown.add(p.shipNetId);
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s.isFighter() && !officered.contains(e.getKey())) continue; // fighters come and go by the dozen; theirs are left to each game
            float[] last = sentStats.get(e.getKey());
            // Stats move when something happens to a ship: a system running, a state changing. Those ships,
            // and the ones players fly, are read every pass (4 a second); a ship with nothing going on takes
            // its turn once a second.
            if (last != null && !touched.contains(e.getKey()) && !flown.contains(e.getKey()) && ((e.getKey() + statRound) & 3) != 0 && !busy(s)) {
                statSkips++;
                continue;
            }
            statLooks++;
            long mask = 0L;
            int changed = 0;
            for (int i = 0; i < ShipStats.COUNT; i++) {
                now[i] = ShipStats.read(s.getMutableStats(), i);
                if (now[i] != now[i]) continue;
                if (last != null && ShipStats.same(last[i], now[i])) continue;
                mask |= 1L << i;
                changed++;
            }
            if (changed == 0) continue;
            if (last == null) { last = new float[ShipStats.COUNT]; java.util.Arrays.fill(last, Float.NaN); sentStats.put(e.getKey(), last); }
            Extras.Stats st = new Extras.Stats();
            st.ship = e.getKey();
            st.mask = mask;
            st.values = new float[changed];
            for (int i = 0, k = 0; i < ShipStats.COUNT; i++) {
                if ((mask & (1L << i)) == 0) continue;
                st.values[k++] = now[i];
                last[i] = now[i];
            }
            out.add(st);
            statsSent += changed;
            if (out.size() >= 30) { session.broadcast(Msg.STATS, Extras.encodeStats(out)); out = new ArrayList<Extras.Stats>(); }
        }
        if (!out.isEmpty()) session.broadcast(Msg.STATS, Extras.encodeStats(out));
        touched.clear();
        if (sentStats.size() > byId.size() + 50) sentStats.keySet().retainAll(byId.keySet());
    }

    /** True while one of the ship's systems is doing something. */
    private static boolean busy(ShipAPI s) {
        try {
            com.fs.starfarer.api.combat.ShipSystemAPI a = s.getSystem(), b = s.getPhaseCloak();
            return (a != null && a.getState() != com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.IDLE)
                    || (b != null && b.getState() != com.fs.starfarer.api.combat.ShipSystemAPI.SystemState.IDLE);
        } catch (Throwable t) {
            return true;
        }
    }

    private void sendNotes(CombatEngineAPI engine, HostSession session, Map<Integer, ShipAPI> byId) {
        List<Extras.Note> out = new ArrayList<Extras.Note>();
        gather(-1, engine.getCustomData(), out);
        noteRound++;
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s.isFighter() && !officered.contains(e.getKey())) continue;
            if (((e.getKey() + noteRound) & 1) != 0) continue; // half the ships each pass: every ship once a second
            gather(e.getKey(), s.getCustomData(), out);
            if (out.size() >= 150) { session.broadcast(Msg.NOTES, Extras.encodeNotes(out)); notesSent += out.size(); out = new ArrayList<Extras.Note>(); }
        }
        if (!out.isEmpty()) { session.broadcast(Msg.NOTES, Extras.encodeNotes(out)); notesSent += out.size(); }
        if (sentNotes.size() > byId.size() + 50) {
            for (Iterator<Integer> it = sentNotes.keySet().iterator(); it.hasNext();) { int id = it.next(); if (id >= 0 && !byId.containsKey(id)) it.remove(); }
        }
    }

    /** Add to the list every simple value in a script's store that is new, changed or gone since last time. */
    private void gather(int ship, Map<String, Object> data, List<Extras.Note> out) {
        Map<String, String> last = sentNotes.get(ship);
        if ((data == null || data.isEmpty()) && (last == null || last.isEmpty())) return;
        if (last == null) { last = new HashMap<String, String>(); sentNotes.put(ship, last); }
        int kept = 0;
        Map<String, String> seen = new HashMap<String, String>();
        if (data != null) {
            for (Map.Entry<String, Object> e : new ArrayList<Map.Entry<String, Object>>(data.entrySet())) {
                String key = e.getKey();
                if (key == null || key.startsWith("wingman") || kept >= 40) continue;
                Extras.Note n = e.getValue() == null ? null : Extras.noteOf(ship, key, e.getValue());
                if (n == null) continue; // not a simple value: an object only this game's script can make sense of
                kept++;
                String sig = n.kind + (n.wide ? "w:" : ":") + e.getValue();
                seen.put(key, sig);
                if (sig.equals(last.get(key))) { streaks.remove(ship + "|" + key); continue; }
                // A value that is different every single time we look is a timer or a running figure, which
                // each game's own copy of the script keeps for itself. Copying it would only make the joiner's
                // jump about. After a few in a row it is left alone for good.
                String sk = ship + "|" + key;
                if (restless.contains(sk)) continue;
                int[] run = streaks.get(sk);
                if (run == null) { run = new int[1]; streaks.put(sk, run); }
                if (++run[0] >= 6) { restless.add(sk); streaks.remove(sk); continue; }
                out.add(n);
            }
        }
        for (String key : last.keySet()) {
            if (!seen.containsKey(key)) out.add(Extras.noteOf(ship, key, null)); // was there, is gone
        }
        last.clear();
        last.putAll(seen);
    }

    private void sendHurts(HostSession session) {
        if (hurts.isEmpty()) return;
        List<Extras.Hurt> out = new ArrayList<Extras.Hurt>(hurts.values());
        hurts.clear();
        biggest.clear();
        if (out.size() > 120) out = out.subList(0, 120);
        hurtsSent += out.size();
        session.broadcast(Msg.HURTS, Extras.encodeHurts(out));
    }

    /**
     * The game calls this for every piece of damage about to land on anything, with what is causing it, where,
     * and whether a shield is taking it. Nothing is changed here; it is only noted, per ship hit.
     */
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
        if (!(target instanceof ShipAPI)) return null;
        Integer tid;
        try {
            tid = ids.get(target);
            if (tid == null) return null;
            if (hit.size() < 2000) hit.add(tid);
        } catch (Throwable t) {
            listenBroken = true; // which ships were hit can no longer be told: every ship is read every time again
            return null;
        }
        if (hurtsBroken || !cfg.damageReports || damage == null) return null;
        try {
            // Only what a joiner's own game will not produce by itself is worth reporting. A shot, missile or
            // beam from a weapon is fired on the joiner's side too and lands there too; reporting those would
            // be saying the same thing twice, several times a second, for every ship in a fight. What is left
            // is damage with no weapon behind it: a script applying it directly, an explosion a script set off.
            if (param instanceof BeamAPI) { ordinary++; return null; }
            if (param instanceof DamagingProjectileAPI && ((DamagingProjectileAPI) param).getWeapon() != null) { ordinary++; return null; }
            float amount = damage.getDamage();
            if (damage.isDps()) amount *= damage.getDpsDuration();
            float emp = damage.getFluxComponent();
            if (damage.isDps()) emp *= damage.getDpsDuration();
            if (!(amount > 0f) && !(emp > 0f)) return null;
            Extras.Hurt h = hurts.get(tid);
            float[] big = biggest.get(tid);
            if (h == null) {
                if (hurts.size() >= 400) return null;
                h = new Extras.Hurt();
                h.target = tid;
                hurts.put(tid, h);
                big = new float[1];
                biggest.put(tid, big);
            }
            h.amount += Math.max(0f, amount);
            h.emp += Math.max(0f, emp);
            if (shieldHit) h.onShield = true;
            if (amount >= big[0]) { // the report carries the particulars of the heaviest blow
                big[0] = amount;
                h.type = damage.getType() == null ? 4 : damage.getType().ordinal();
                if (point != null) { h.x = point.x; h.y = point.y; } else { h.x = target.getLocation().x; h.y = target.getLocation().y; }
                ShipAPI from = param instanceof ShipAPI ? (ShipAPI) param
                        : param instanceof DamagingProjectileAPI ? ((DamagingProjectileAPI) param).getSource()
                        : param instanceof BeamAPI ? ((BeamAPI) param).getSource() : null;
                Integer sid = from == null ? null : ids.get(from);
                h.source = sid == null ? -1 : sid;
            }
        } catch (Throwable t) {
            hurtsBroken = true;
            Global.getLogger(HostExtras.class).error("Wingman: noting damage failed; damage reports are off for this battle", t);
        }
        return null;
    }
}
