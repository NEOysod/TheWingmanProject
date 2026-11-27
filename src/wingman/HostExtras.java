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
 * it happens. Each is checked several times a second (see HURTS_MS, STATS_MS, NOTES_MS) and only what changed is sent. Each can fail by itself
 * without taking the others or the rest of co-op with it.
 */
final class HostExtras implements DamageTakenModifier {
    private final Map<ShipAPI, Integer> ids;
    private final WingmanConfig cfg;
    private boolean statsBroken, notesBroken, hurtsBroken, listening;
    private long lastStats, lastNotes, lastHurts;
    /**
     * How often each goes (ms). Until 0.9.4, to save bandwidth: damage reports and stats 4 a second, script values 2
     * (each ship every other pass). Now: damage reports with every update; stats with every update for a ship whose
     * stats are changing (a system running, a state changing, flown, or changed within the last second), the rest
     * once a second in turns; script values 15 a second, every ship each pass.
     */
    static final long HURTS_MS = 33, STATS_MS = 33, NOTES_MS = 66;
    /** Stats passes between two looks at a ship with nothing going on (once a second at STATS_MS). */
    static final int STATS_IDLE_EVERY = 30;
    /**
     * A script value changing at every look for this many looks running is a timer, left alone (1.5 s at NOTES_MS).
     * Protocol 64 looked 15 times as often and kept 6 s: for those first 6 s a timer the joiner's copy of the script
     * keeps for itself was overwritten 15 times a second with one a round trip old, and jumped about.
     */
    static final int RESTLESS_LOOKS = 23;
    /** Ships whose stats changed lately, and until when they count as changing (System.currentTimeMillis()). */
    private final Map<Integer, Long> statsChanging = new HashMap<Integer, Long>();
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

    /** The host's missile numbers (-1: a missile joiners never get), for missiles' own values and values that are missiles. */
    private final Map<com.fs.starfarer.api.combat.MissileAPI, Integer> missileIds;

    HostExtras(Map<ShipAPI, Integer> ids, WingmanConfig cfg, java.util.Set<Integer> officered,
            Map<com.fs.starfarer.api.combat.MissileAPI, Integer> missileIds) {
        this.officered = officered;
        this.ids = ids;
        this.cfg = cfg;
        this.missileIds = missileIds;
    }

    /** A player has just been sent the whole battle: everything here goes out afresh too. */
    void resendAll() {
        sentStats.clear();
        sentNotes.clear();
        sentMore.clear();
        newNames.clear();
        newNames.addAll(statNames); // names first: a joiner that just came has none
    }

    // ---- stats beyond ShipStats' list (MoreStats): named on first use, then sent by number ----
    private final Map<String, Integer> statIndex = new HashMap<String, Integer>();
    private final List<Extras.StatName> statNames = new ArrayList<Extras.StatName>();
    /** Names given out since they were last sent. */
    private final List<Extras.StatName> newNames = new ArrayList<Extras.StatName>();
    /** Per ship: each named stat's value as last sent. */
    private final Map<Integer, Map<Integer, Float>> sentMore = new HashMap<Integer, Map<Integer, Float>>();
    private boolean moreBroken;
    int moreSent;

    private int statIndexOf(String name) {
        Integer i = statIndex.get(name);
        if (i != null) return i;
        i = statIndex.size();
        statIndex.put(name, i);
        Extras.StatName n = new Extras.StatName();
        n.index = i;
        n.name = name;
        statNames.add(n);
        newNames.add(n);
        return i;
    }

    /** Collects one ship's named stats that changed (see moreOf). */
    private final class MoreRun {
        final Map<Integer, Float> last;
        final java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        int[] idx = new int[16];
        float[] val = new float[16];
        int n;

        MoreRun(Map<Integer, Float> last) { this.last = last; }

        void put(int k, float v) {
            if (n == idx.length) { idx = java.util.Arrays.copyOf(idx, n * 2); val = java.util.Arrays.copyOf(val, n * 2); }
            idx[n] = k;
            val[n++] = v;
        }

        /** A stat's value now: noted as seen, and kept for sending if it changed. */
        void look(String name, float v) {
            if (v != v || Math.abs(v) > 1.0e7f) return;
            int k = statIndexOf(name);
            seen.add(k);
            Float was = last.get(k);
            if (was != null && ShipStats.same(was, v)) return;
            put(k, v);
            last.put(k, v);
        }

        /** A stat or bonus; a bonus also by its value at base 0 (name + "#0"), so the joiner gets it right at every base. */
        void look(String name, Object stat) {
            look(name, MoreStats.value(stat));
            if (stat instanceof com.fs.starfarer.api.combat.StatBonus) {
                try { look(wireName(at0Names, "", name), ((com.fs.starfarer.api.combat.StatBonus) stat).computeEffective(0f)); } catch (Throwable t) { /* at BASE only */ }
            }
        }
    }

    /** Wire names made once (looked at many times a second): named stats, named bonuses, and bonuses' "#0". */
    private final Map<String, String> statWireNames = new HashMap<String, String>(), bonusWireNames = new HashMap<String, String>(),
            at0Names = new HashMap<String, String>();

    private static String wireName(Map<String, String> cache, String prefix, String key) {
        String n = cache.get(key);
        if (n == null) {
            n = prefix.isEmpty() ? key + "#0" : prefix + key;
            if (cache.size() < 5000) cache.put(key, n);
        }
        return n;
    }

    /** "s:" + index + "#0": ShipStats' bonuses at base 0 (their values at BASE go in STATS). */
    private static final String[] FIXED_AT0 = new String[ShipStats.COUNT];
    static {
        for (int i = 0; i < ShipStats.COUNT; i++) FIXED_AT0[i] = "s:" + i + "#0";
    }

    /**
     * The ship's named stats that changed since last sent (and those gone, as NaN), or null if none did: every getter
     * in MoreStats, the mod's own named stats and bonuses, and ShipStats' bonuses at base 0.
     */
    private Extras.MoreStats moreOf(int id, ShipAPI s) {
        Map<Integer, Float> last = sentMore.get(id);
        if (last == null) { last = new HashMap<Integer, Float>(); sentMore.put(id, last); }
        com.fs.starfarer.api.combat.MutableShipStatsAPI st = s.getMutableStats();
        MoreRun run = new MoreRun(last);
        for (int i = 0; i < MoreStats.COUNT; i++) {
            Object stat;
            try { stat = MoreStats.get(st, i); } catch (Throwable t) { continue; } // a getter this game version lacks
            run.look(MoreStats.NAMES[i], stat);
        }
        for (int i = 0; i < ShipStats.COUNT; i++) {
            com.fs.starfarer.api.combat.StatBonus b = ShipStats.bonus(st, i);
            if (b != null) run.look(FIXED_AT0[i], b.computeEffective(0f));
        }
        com.fs.starfarer.api.util.DynamicStatsAPI dyn = st.getDynamic();
        if (dyn != null) {
            for (int pass = 0; pass < 2; pass++) {
                Map<String, ?> map = pass == 0 ? dyn.getStats() : dyn.getMods();
                if (map == null) continue;
                int taken = 0;
                for (Map.Entry<String, ?> e : map.entrySet()) {
                    String key = e.getKey();
                    if (key == null || key.length() > 100 || key.startsWith("wingman")) continue;
                    if (pass == 1 && key.equals(com.fs.starfarer.api.impl.campaign.ids.Stats.PHASE_CLOAK_SPEED_MOD)) continue; // in ShipStats' list
                    if (++taken > 200) break;
                    run.look(pass == 0 ? wireName(statWireNames, "d:", key) : wireName(bonusWireNames, "b:", key), e.getValue());
                }
            }
        }
        for (Iterator<Map.Entry<Integer, Float>> it = last.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Float> e = it.next();
            if (run.seen.contains(e.getKey())) continue;
            it.remove(); // gone on the host (a named stat a script removed): the joiner lets go of it too
            run.put(e.getKey(), Float.NaN);
        }
        if (run.n == 0) return null;
        Extras.MoreStats m = new Extras.MoreStats();
        m.ship = id;
        m.index = java.util.Arrays.copyOf(run.idx, run.n);
        m.values = java.util.Arrays.copyOf(run.val, run.n);
        return m;
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
                if (now - lastHurts >= HURTS_MS) { lastHurts = now; sendHurts(session); }
            } catch (Throwable t) {
                hurtsBroken = true;
                Global.getLogger(HostExtras.class).error("Wingman: reporting damage failed; damage reports are off for this battle", t);
            }
        }
        if (cfg.syncStats && !statsBroken && now - lastStats >= STATS_MS) {
            lastStats = now;
            try {
                sendStats(session, byId);
            } catch (Throwable t) {
                statsBroken = true;
                Global.getLogger(HostExtras.class).error("Wingman: sending ship stats failed; stats are not kept alike for this battle", t);
            }
        }
        if (cfg.syncScriptMemory && !notesBroken && now - lastNotes >= NOTES_MS) {
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
        List<Extras.MoreStats> more = new ArrayList<Extras.MoreStats>();
        float[] now = new float[ShipStats.COUNT];
        statRound++;
        java.util.Set<Integer> flown = new java.util.HashSet<Integer>();
        for (HostSession.Player p : session.getPlayers()) if (p.shipNetId >= 0) flown.add(p.shipNetId);
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s.isFighter() && !officered.contains(e.getKey())) continue; // fighters come and go by the dozen; theirs are left to each game
            float[] last = sentStats.get(e.getKey());
            // Stats move when something happens to a ship: a system running, a state changing. Those ships, the ones
            // players fly, and any whose stats changed in the last second are read every pass (as often as updates
            // go); a ship with nothing going on takes its turn once a second.
            Long changingUntil = statsChanging.get(e.getKey());
            boolean changing = changingUntil != null && changingUntil > System.currentTimeMillis();
            if (last != null && !changing && !touched.contains(e.getKey()) && !flown.contains(e.getKey())
                    && ((e.getKey() + statRound) % STATS_IDLE_EVERY) != 0 && !busy(s)) {
                statSkips++;
                continue;
            }
            statLooks++;
            if (!moreBroken) {
                try {
                    Extras.MoreStats m = moreOf(e.getKey(), s);
                    if (m != null) { more.add(m); moreSent += m.index.length; statsChanging.put(e.getKey(), System.currentTimeMillis() + 1000L); }
                } catch (Throwable t) {
                    moreBroken = true;
                    Global.getLogger(HostExtras.class).error("Wingman: sending the wider ship stats failed; only the main ones are kept alike for this battle", t);
                }
            }
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
            statsChanging.put(e.getKey(), System.currentTimeMillis() + 1000L);
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
        if (!newNames.isEmpty()) { // names before the values that use them (same connection, in order)
            session.broadcast(Msg.STAT_NAMES, Extras.encodeStatNames(newNames));
            newNames.clear();
        }
        for (int i = 0; i < more.size(); i += 30) session.broadcast(Msg.STATS_MORE, Extras.encodeMoreStats(more.subList(i, Math.min(more.size(), i + 30))));
        touched.clear();
        if (sentStats.size() > byId.size() + 50) sentStats.keySet().retainAll(byId.keySet());
        if (sentMore.size() > byId.size() + 50) sentMore.keySet().retainAll(byId.keySet());
        if (statsChanging.size() > byId.size() + 50) statsChanging.keySet().retainAll(byId.keySet());
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
        gather(-1, false, engine.getCustomData(), out);
        noteRound++;
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s.isFighter() && !officered.contains(e.getKey())) continue;
            gather(e.getKey(), false, s.getCustomData(), out);
            if (out.size() >= 150) { session.broadcast(Msg.NOTES, Extras.encodeNotes(out)); notesSent += out.size(); out = new ArrayList<Extras.Note>(); }
        }
        // Missiles' own values (a mod's missile script keeping its stage, its mark, its charge on the missile).
        java.util.Set<Integer> liveMissiles = new java.util.HashSet<Integer>();
        if (missileIds != null) {
            for (Map.Entry<com.fs.starfarer.api.combat.MissileAPI, Integer> e : missileIds.entrySet()) {
                int mid = e.getValue();
                if (mid < 0) continue;
                liveMissiles.add(mid);
                Map<String, Object> data;
                try { data = e.getKey().getCustomData(); } catch (Throwable t) { continue; }
                gather(mid, true, data, out);
                if (out.size() >= 150) { session.broadcast(Msg.NOTES, Extras.encodeNotes(out)); notesSent += out.size(); out = new ArrayList<Extras.Note>(); }
            }
        }
        if (!out.isEmpty()) { session.broadcast(Msg.NOTES, Extras.encodeNotes(out)); notesSent += out.size(); }
        if (sentNotes.size() > byId.size() + 50) {
            for (Iterator<Integer> it = sentNotes.keySet().iterator(); it.hasNext();) {
                int id = it.next();
                if (id >= 0 ? !byId.containsKey(id) : id <= -2 && !liveMissiles.contains(-2 - id)) it.remove();
            }
            for (Iterator<String> it = streaks.keySet().iterator(); it.hasNext();) { // runs of values on ships and missiles gone
                String k = it.next();
                int bar = k.indexOf('|');
                try {
                    int id = Integer.parseInt(k.substring(0, bar));
                    if (id >= 0 ? !byId.containsKey(id) : id <= -2 && !liveMissiles.contains(-2 - id)) it.remove();
                } catch (RuntimeException ex) { it.remove(); }
            }
        }
    }

    /**
     * Add to the list every value in a script's store that is new, changed or gone since last time: the simple
     * sorts (true/false, numbers, short text), and a ship, a missile or a point (sent as the ship's or missile's
     * number, or x and y), which scripts keep for a mark, a target, a place to go. Other objects stay each game's own.
     * Keyed by ship number, -1 for the battle, -2 - number for a missile.
     */
    private void gather(int owner, boolean missile, Map<String, Object> data, List<Extras.Note> out) {
        int ship = missile ? -2 - owner : owner;
        Map<String, String> last = sentNotes.get(ship);
        if ((data == null || data.isEmpty()) && (last == null || last.isEmpty())) return;
        if (last == null) { last = new HashMap<String, String>(); sentNotes.put(ship, last); }
        int kept = 0;
        Map<String, String> seen = new HashMap<String, String>();
        if (data != null) {
            for (Map.Entry<String, Object> e : new ArrayList<Map.Entry<String, Object>>(data.entrySet())) {
                String key = e.getKey();
                if (key == null || key.startsWith("wingman") || key.startsWith("nonAI_SAIF_") || kept >= 40) continue;
                Object v = e.getValue();
                Extras.Note n = v == null ? null : noteOf(missile ? owner : ship, key, v);
                if (n == null) continue; // an object only this game's script can make sense of
                n.onMissile = missile;
                kept++;
                String sig = n.kind + (n.wide ? "w:" : ":") + (n.kind >= Extras.SHIP_REF ? n.whole + "," + Math.round(n.number) + "," + Math.round(n.y) : String.valueOf(v));
                seen.put(key, sig);
                if (sig.equals(last.get(key))) { streaks.remove(ship + "|" + key); continue; }
                // A value that is different every single time we look is a timer or a running figure, which
                // each game's own copy of the script keeps for itself. Copying it would only make the joiner's
                // jump about. After a few in a row it is left alone for good.
                // (A missile's timer is the same script's on every missile of that kind: once one shows it, all do.)
                String sk = ship + "|" + key, rk = missile ? "m|" + key : sk;
                if (restless.contains(rk)) continue;
                int[] run = streaks.get(sk);
                if (run == null) { run = new int[1]; streaks.put(sk, run); }
                if (++run[0] >= RESTLESS_LOOKS) { restless.add(rk); streaks.remove(sk); continue; }
                out.add(n);
            }
        }
        for (String key : last.keySet()) {
            if (seen.containsKey(key)) continue;
            Extras.Note gone = Extras.noteOf(missile ? owner : ship, key, null); // was there, is gone
            if (gone != null) { gone.onMissile = missile; out.add(gone); }
        }
        last.clear();
        last.putAll(seen);
    }

    /** A value as a note: the simple sorts, or one of our ships or missiles by number, or a point; null otherwise. */
    private Extras.Note noteOf(int owner, String key, Object v) {
        if (v instanceof ShipAPI) {
            Integer id = ids.get(v);
            return id == null ? null : Extras.refNote(owner, key, Extras.SHIP_REF, id);
        }
        if (v instanceof com.fs.starfarer.api.combat.MissileAPI) {
            Integer id = missileIds == null ? null : missileIds.get(v);
            return id == null || id < 0 ? null : Extras.refNote(owner, key, Extras.MISSILE_REF, id);
        }
        if (v instanceof Vector2f) return Extras.pointNote(owner, key, ((Vector2f) v).x, ((Vector2f) v).y);
        return Extras.noteOf(owner, key, v);
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
