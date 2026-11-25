package wingman.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Updates packed once for many players (HostSession.plan): three UDP players with different delays and losses and
 * two TCP players, 900 updates. Every update each player reads must match the host's battle; the packing must be
 * shared most of the time; and it must cost little extra in bytes against packing for each player alone.
 */
public class LaneTest {
    static int fails;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) fails++;
    }

    static final class Joiner {
        HostSession.Player p;
        SnapshotCodec.Decoder dec = new SnapshotCodec.Decoder();
        int delay, ackDelay, lossPct;
        List<int[]> acks = new ArrayList<int[]>();      // {due tick, epoch, tick}
        List<Object[]> inFlight = new ArrayList<Object[]>(); // {due tick, bytes}
        int read, wrong, keys, bytes;
    }

    static Snapshot battle(Random r) {
        Snapshot s = new Snapshot();
        s.hostFlagship = -1;
        for (int i = 0; i < 25; i++) {
            Snapshot.ShipState st = new Snapshot.ShipState();
            st.netId = 10 + i; st.x = r.nextInt(4000) - 2000; st.y = r.nextInt(4000) - 2000;
            st.vx = r.nextInt(200) - 100; st.vy = r.nextInt(200) - 100; st.facing = r.nextInt(360); st.angVel = r.nextInt(40) - 20;
            st.hull = 1f; st.cr = 0.7f; st.fluxStep = 5f; st.weaponAngles = new byte[4];
            s.ships.add(st);
        }
        for (int i = 0; i < 40; i++) {
            Snapshot.MissileState m = new Snapshot.MissileState();
            m.id = 1000 + i; m.x = r.nextInt(4000) - 2000; m.y = r.nextInt(4000) - 2000; m.vx = r.nextInt(600) - 300; m.vy = r.nextInt(600) - 300;
            m.facing = r.nextInt(360);
            s.missiles.add(m);
        }
        return s;
    }

    static void advance(Snapshot s, Random r, float dt) {
        s.tick++;
        s.hostTime += dt;
        for (Snapshot.ShipState st : s.ships) {
            st.vx += (r.nextFloat() - 0.5f) * 20f; st.vy += (r.nextFloat() - 0.5f) * 20f; // manoeuvring
            st.x += st.vx * dt; st.y += st.vy * dt;
            st.facing = (st.facing + st.angVel * dt + 360f) % 360f;
            st.flux += r.nextInt(20);
        }
        for (Snapshot.MissileState m : s.missiles) { m.x += m.vx * dt; m.y += m.vy * dt; m.flightTime += dt; }
    }

    static boolean right(Snapshot truth, Snapshot got) {
        if (truth == null || got == null || got.tick != truth.tick) return false;
        Map<Integer, Snapshot.ShipState> gs = new HashMap<Integer, Snapshot.ShipState>();
        for (Snapshot.ShipState y : got.ships) gs.put(y.netId, y);
        for (Snapshot.ShipState x : truth.ships) {
            Snapshot.ShipState y = gs.get(x.netId);
            if (y == null || Math.abs(x.x - y.x) > 0.6f || Math.abs(x.y - y.y) > 0.6f) return false;
        }
        Map<Integer, Snapshot.MissileState> gm = new HashMap<Integer, Snapshot.MissileState>();
        for (Snapshot.MissileState y : got.missiles) gm.put(y.id, y);
        for (Snapshot.MissileState x : truth.missiles) {
            Snapshot.MissileState y = gm.get(x.id);
            if (y == null || Math.abs(x.x - y.x) > 0.6f || Math.abs(x.y - y.y) > 0.6f) return false;
        }
        return true;
    }

    /** Runs the battle; returns total bytes put on the wire, and fills in the stats. */
    static long run(boolean share, int[] packsOut, List<Joiner> out) {
        Random r = new Random(3), net = new Random(11);
        Snapshot s = battle(r);
        Map<Integer, Snapshot> truth = new HashMap<Integer, Snapshot>();
        List<Joiner> js = new ArrayList<Joiner>();
        int[][] links = { { 1, 2, 5 }, { 2, 3, 10 }, { 4, 5, 15 }, { 0, 0, 0 }, { 0, 0, 0 } }; // delay, ack delay, loss %
        for (int i = 0; i < links.length; i++) {
            Joiner j = new Joiner();
            j.p = new HostSession.Player(i + 2, null);
            j.p.udp = i < 3;
            j.delay = links[i][0]; j.ackDelay = links[i][1]; j.lossPct = links[i][2];
            js.add(j);
        }
        long total = 0;
        int packs = 0;
        for (int f = 0; f < 900; f++) {
            advance(s, r, 1 / 30f);
            truth.put(s.tick, NetTestCopy.copyOf(s));
            for (Joiner j : js) { // word of what was read reaches the host
                for (int q = 0; q < j.acks.size(); q++) {
                    int[] a = j.acks.get(q);
                    if (a[0] > f) continue;
                    j.p.ack(a[1], a[2]);
                    j.acks.remove(q--);
                }
            }
            List<HostSession.Player> going = new ArrayList<HostSession.Player>();
            for (Joiner j : js) going.add(j.p);
            List<List<HostSession.Player>> groups = HostSession.plan(s.tick, going, share);
            for (List<HostSession.Player> g : groups) {
                HostSession.Player first = g.get(0);
                byte[] data = first.encoder.encode(s, first.wantBase);
                boolean key = first.encoder.lastKey;
                packs++;
                for (HostSession.Player p : g) {
                    Joiner j = null;
                    for (Joiner x : js) if (x.p == p) j = x;
                    total += data.length;
                    j.bytes += data.length;
                    if (key || !p.udp) { // by TCP: arrives, in order
                        p.baseTick = s.tick;
                        p.needKey = false;
                        if (key) { j.keys++; p.sinceKey = 0; if (s.tick > p.ackTick) p.ackTick = s.tick; p.acked[s.tick & (SnapshotCodec.HISTORY - 1)] = s.tick; }
                        j.inFlight.add(new Object[] { f, data });
                    } else if (net.nextInt(100) >= j.lossPct) {
                        j.inFlight.add(new Object[] { f + j.delay + net.nextInt(2), data });
                    }
                }
            }
            for (Joiner j : js) { // the joiners read what has arrived
                for (int q = 0; q < j.inFlight.size(); q++) {
                    Object[] it = j.inFlight.get(q);
                    if ((Integer) it[0] > f) continue;
                    j.inFlight.remove(q--);
                    Snapshot got;
                    try { got = j.dec.decode((byte[]) it[1]); } catch (java.io.IOException e) { j.wrong++; continue; }
                    if (got == null) {
                        if (j.dec.result == SnapshotCodec.Decoder.NOTHING_TO_BUILD_ON) j.p.needKey = true; // NEED_KEY
                        continue;
                    }
                    j.read++;
                    if (!right(truth.get(got.tick), got)) j.wrong++;
                    if (j.p.udp) j.acks.add(new int[] { f + j.ackDelay, j.dec.ackEpoch(), j.dec.ackTick() });
                }
            }
        }
        packsOut[0] = packs;
        out.addAll(js);
        return total;
    }

    public static void main(String[] a) {
        List<Joiner> shared = new ArrayList<Joiner>(), alone = new ArrayList<Joiner>();
        int[] packsShared = new int[1], packsAlone = new int[1];
        long bytesShared = run(true, packsShared, shared);
        long bytesAlone = run(false, packsAlone, alone);
        int wrong = 0, read = 0, keys = 0;
        StringBuilder sb = new StringBuilder();
        for (Joiner j : shared) { wrong += j.wrong; read += j.read; keys += j.keys; sb.append(j.read).append("/").append(j.keys).append(" "); }
        check("shared packing: every update read by every player is right (" + read + " read, " + wrong + " wrong; read/in full per player: " + sb + ")",
                wrong == 0 && read > 3800 && keys <= 20);
        check("packed far fewer times than players x updates (" + packsShared[0] + " packs shared, " + packsAlone[0] + " alone, 4500 sends)",
                packsShared[0] < packsAlone[0] * 0.7);
        check("bytes cost little extra for it (" + bytesShared / 900 + " vs " + bytesAlone / 900 + " bytes an update, all players)",
                bytesShared <= bytesAlone * 1.03);
        // A link that backs up: that player gets their own limit and is packed for alone; the others still share.
        {
            Random r = new Random(5);
            Snapshot s = battle(r);
            List<HostSession.Player> ps = new ArrayList<HostSession.Player>();
            for (int i = 0; i < 3; i++) { HostSession.Player p = new HostSession.Player(i + 2, null); ps.add(p); }
            int bytesLimited = 0, sharedGroups = 0;
            for (int f = 0; f < 300; f++) {
                advance(s, r, 1 / 30f);
                if (f == 100) ps.get(2).budget.choked();
                List<List<HostSession.Player>> groups = HostSession.plan(s.tick, ps, true);
                for (List<HostSession.Player> g : groups) {
                    HostSession.Player first = g.get(0);
                    byte[] data = first.encoder.encode(s, first.wantBase);
                    for (HostSession.Player p : g) p.budget.offered(data.length, 1 / 30f);
                    if (HostSession.alone(first, true)) data = first.budget.fit(s, data, first.encoder, first.wantBase, 1 / 30f);
                    for (HostSession.Player p : g) { p.baseTick = s.tick; p.needKey = false; if (first.encoder.lastKey) p.sinceKey = 0; }
                    if (f > 150 && g.contains(ps.get(2))) { bytesLimited += data.length; if (g.size() != 1) sharedGroups = -999; }
                    if (f > 150 && g.size() == 2) sharedGroups++;
                }
            }
            int limit = ps.get(2).budget.autoLimit;
            check("a backed-up link: that player is limited (" + limit / 1024 + " KB/s, got " + bytesLimited * 30 / 149 / 1024 + " KB/s) and packed for alone, the other two still share ("
                    + sharedGroups + " shared packs)", limit > 0 && bytesLimited * 30 / 149 <= limit * 1.2f && sharedGroups >= 140);
        }
        System.out.println(fails == 0 ? "LANE PASSED" : fails + " FAILED");
    }
}
