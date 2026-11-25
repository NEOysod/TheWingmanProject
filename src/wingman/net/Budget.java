package wingman.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bandwidth limit for one player's position updates (host setting, off by default), with a priority
 * accumulator deciding what goes when not everything fits (after Glenn Fiedler's "state synchronization" and
 * Halo: Reach's networking).
 *
 * Each update, every ship, fighter and missile in it gains priority: more for ships than missiles; more the
 * nearer it is to what the player is looking at (their ship, or the host's); double while it is firing; and much
 * more the further the player's copy, as it is being carried on (the packer's guess, the same on both ends), now is
 * from the real thing. Something the player doesn't have yet goes at once. If the update fits in what the limit allows now (a token bucket: the limit per second, a quarter
 * second's worth at most saved up), everything goes and nothing changes. If not, the entities with the most
 * priority go, as many as fit, and theirs drops to zero; the rest wait, gaining priority, so nothing waits for
 * ever. A ship a player flies always goes; anything left out for 20 updates in a row goes regardless (the packer
 * forgets what it has not sent for 30). What is left out is carried on by its last speed on the joiner's screen,
 * exactly as the packer carries anything that moved as expected.
 *
 * Updates in full (a joiner's first, or after a loss) are never cut: they must arrive whole to be built on.
 * They are counted against the limit, so the updates after them are smaller.
 */
public final class Budget {
    /** Bytes a second; 0: no limit. Set from the host's settings. */
    public static volatile int bytesPerSecond;
    /**
     * This player's own limit, set by the host when their link backs up (bytes a second; 0: none): instead of
     * sending everyone fewer updates a second (0.9.3), this player keeps getting 30 a second, with what matters
     * least to them (far away, flying as expected) left for later. Raised again by a quarter every five clean
     * seconds, and dropped once it is well above what the battle needs.
     */
    public int autoLimit;
    static final int AUTO_MIN = 6 * 1024;
    private float needEma = Float.NaN;
    private long offeredNanos, cleanSince;

    /** The limit in force for this player (bytes a second; 0: none). */
    public int limit() { int l = bytesPerSecond; return l > 0 ? l : autoLimit; }

    /** True if this player's updates may be cut down (and so are packed for them alone). */
    public boolean limited() { return limit() > 0; }

    /** An update of this many bytes was ready for this player (before any cutting): what the battle needs. */
    public void offered(int bytes) {
        long now = System.nanoTime();
        float dt = offeredNanos == 0 ? 0f : (now - offeredNanos) / 1e9f;
        offeredNanos = now;
        offered(bytes, dt);
    }

    /** As above, with the time since the last one given (for tests). */
    public void offered(int bytes, float dt) {
        if (dt < 0.005f || dt > 1f) return;
        float rate = bytes / dt;
        needEma = Float.isNaN(needEma) ? rate : needEma * 0.9f + rate * 0.1f;
    }

    /** This player's link was too backed up to take an update: limit it to a bit under what it was managing. */
    public void choked() {
        float need = Float.isNaN(needEma) ? 30 * 1024 : needEma;
        float from = autoLimit > 0 ? Math.min(autoLimit, need) : need;
        autoLimit = Math.max(AUTO_MIN, (int) (from * 0.7f));
        cleanSince = System.nanoTime();
    }

    /** An update went through: after five clean seconds, allow more again (or drop the limit once it is not needed). */
    public void clean() {
        if (autoLimit <= 0) return;
        long now = System.nanoTime();
        if (now - cleanSince < 5000000000L) return;
        cleanSince = now;
        autoLimit = (int) (autoLimit * 1.25f);
        if (!Float.isNaN(needEma) && autoLimit > needEma * 1.5f) autoLimit = 0;
    }
    static final int FORCE_AFTER = 20;

    private float tokens = Float.NaN;
    private long lastNanos;
    // per entity: priority, updates left out in a row
    private final Map<Integer, float[]> ships = new HashMap<Integer, float[]>(), missiles = new HashMap<Integer, float[]>();
    /** Where the player is looking (their ship, or the host's), set by the game each frame. */
    public volatile float focusX, focusY;
    public volatile boolean hasFocus;
    /** For the log: updates cut down, and entities left out of them. */
    public int cut, leftOut;

    /**
     * How much it matters that this goes now: its kind (base), how near it is to what the player is looking at,
     * whether it is firing, and above all how far the player's copy, as it is being carried on, now is from the
     * real thing ({@code off}, units; -1: the player has none): a ship flying straight on can wait, one that
     * turned can't.
     */
    private float weight(float off, float x, float y, float base, boolean firing) {
        if (off < 0f) return 1e6f;
        float near = 1f;
        if (hasFocus) {
            float dx = x - focusX, dy = y - focusY;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            near = 1f + 3f * Math.max(0f, 1f - d / 4000f);
        }
        return base * near * (firing ? 2f : 1f) * (1f + Math.min(off, 500f) / 20f);
    }

    /**
     * The update to send this player: {@code full} as encoded, or a smaller one (encoded again by the same
     * encoder, for the same tick, which replaces what it noted for that tick). Returns the bytes to send.
     */
    public byte[] fit(Snapshot s, byte[] full, SnapshotCodec.Encoder enc, int builtOn) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        return fit(s, full, enc, builtOn, dt);
    }

    /** As above, with the time since the last update given (for tests, which run faster than the clock). */
    public byte[] fit(Snapshot s, byte[] full, SnapshotCodec.Encoder enc, int builtOn, float dt) {
        int limit = limit();
        if (limit <= 0) { tokens = Float.NaN; return full; }
        float cap = limit * 0.25f;
        if (Float.isNaN(tokens)) tokens = cap;
        tokens = Math.min(cap, tokens + limit * dt);
        // everything gains priority; what is in this update is noted
        for (Snapshot.ShipState st : s.ships) {
            float off = enc.heldOff(builtOn, false, st.netId, s.hostTime, st.x, st.y);
            entry(ships, st.netId)[0] += weight(off, st.x, st.y, st.exact ? 100f : 3f, st.firing != 0L);
        }
        for (Snapshot.MissileState m : s.missiles) {
            float off = enc.heldOff(builtOn, true, m.id, s.hostTime, m.x, m.y);
            entry(missiles, m.id)[0] += weight(off, m.x, m.y, 1f, false);
        }
        if (enc.lastKey || full.length <= tokens) {
            tokens -= full.length;
            for (Snapshot.ShipState st : s.ships) { sent(ships, st.netId); left(ships, st.netId); }
            for (Snapshot.MissileState m : s.missiles) { sent(missiles, m.id); left(missiles, m.id); }
            tidy(s);
            return full;
        }
        int n = s.ships.size() + s.missiles.size();
        float each = Math.max(4f, (full.length - 24f) / Math.max(1, n));
        int room = (int) Math.max(0f, (tokens - 24f) / each);
        List<Object> order = new ArrayList<Object>(n);
        order.addAll(s.ships);
        order.addAll(s.missiles);
        Collections.sort(order, new Comparator<Object>() {
            public int compare(Object a, Object b) { return Float.compare(rank(b), rank(a)); }
        });
        Snapshot cutDown = headerOf(s);
        int taken = 0;
        for (Object o : order) {
            boolean must = o instanceof Snapshot.ShipState
                    ? ((Snapshot.ShipState) o).exact || ships.get(((Snapshot.ShipState) o).netId)[1] >= FORCE_AFTER
                    : missiles.get(((Snapshot.MissileState) o).id)[1] >= FORCE_AFTER;
            if (taken >= room && !must) continue;
            if (o instanceof Snapshot.ShipState) cutDown.ships.add((Snapshot.ShipState) o); else cutDown.missiles.add((Snapshot.MissileState) o);
            taken++;
        }
        byte[] smaller = enc.encode(cutDown, builtOn);
        tokens -= smaller.length;
        for (Snapshot.ShipState st : cutDown.ships) sent(ships, st.netId);
        for (Snapshot.MissileState m : cutDown.missiles) sent(missiles, m.id);
        for (Snapshot.ShipState st : s.ships) left(ships, st.netId);
        for (Snapshot.MissileState m : s.missiles) left(missiles, m.id);
        cut++;
        leftOut += n - taken;
        tidy(s);
        return smaller;
    }

    private float rank(Object o) {
        return o instanceof Snapshot.ShipState ? ships.get(((Snapshot.ShipState) o).netId)[0] : missiles.get(((Snapshot.MissileState) o).id)[0];
    }

    private static float[] entry(Map<Integer, float[]> m, int id) {
        float[] p = m.get(id);
        if (p == null) { p = new float[2]; m.put(id, p); }
        return p;
    }

    private static void sent(Map<Integer, float[]> m, int id) { float[] p = m.get(id); p[0] = 0f; p[1] = -1f; }

    /** After sent(): those not sent were left out once more. */
    private static void left(Map<Integer, float[]> m, int id) { float[] p = m.get(id); p[1] = p[1] < 0f ? 0f : p[1] + 1f; }

    /** Forget entities no longer in updates now and then. */
    private void tidy(Snapshot s) {
        if (ships.size() > s.ships.size() * 2 + 200 || missiles.size() > s.missiles.size() * 2 + 400) {
            Map<Integer, float[]> keepS = new HashMap<Integer, float[]>(), keepM = new HashMap<Integer, float[]>();
            for (Snapshot.ShipState st : s.ships) keepS.put(st.netId, ships.get(st.netId));
            for (Snapshot.MissileState m : s.missiles) keepM.put(m.id, missiles.get(m.id));
            ships.clear(); ships.putAll(keepS);
            missiles.clear(); missiles.putAll(keepM);
        }
    }

    static Snapshot headerOf(Snapshot s) {
        Snapshot c = new Snapshot();
        c.tick = s.tick; c.paused = s.paused; c.hostFlagship = s.hostFlagship; c.timeMult = s.timeMult;
        c.piloted = s.piloted; c.pilotedSeq = s.pilotedSeq; c.pilotedAge = s.pilotedAge; c.pilotedSysAmmo = s.pilotedSysAmmo;
        c.pilotedAimX = s.pilotedAimX; c.pilotedAimY = s.pilotedAimY; c.hasHostAim = s.hasHostAim; c.hostAimX = s.hostAimX; c.hostAimY = s.hostAimY;
        c.hostTime = s.hostTime;
        return c;
    }
}
