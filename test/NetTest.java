import java.util.*;
import wingman.net.*;

/** Headless check of the network layer: no game needed. Run via build script or: java -cp jars/Wingman.jar:test NetTest */
public class NetTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) fails++; }

    static Conn join(String name, int port) throws Exception {
        return join(name, port, MODS);
    }
    static ModList MODS = new ModList();
    static Conn join(String name, int port, ModList mods) throws Exception {
        return join(name, port, mods, "", "");
    }
    static Conn join(String name, int port, ModList mods, String game, String password) throws Exception {
        Conn c = Conn.open("127.0.0.1", port);
        Msg.W w = new Msg.W(); w.out.writeInt(Msg.PROTOCOL_VERSION); w.out.writeUTF(name); mods.write(w.out);
        w.out.writeUTF(game); w.out.writeUTF(password);
        c.send(Msg.HELLO, w.done());
        if (!name.equals("P2")) c.offerCompression(); // P2 never offers, so the host goes on sending to it plain
        return c;
    }
    static String rejectText(Conn c, HostSession h) throws Exception {
        Conn.Packet p = waitFor(c, Msg.REJECT, h);
        return p == null ? "" : Msg.readUtf(p.data);
    }
    /** A battle to send updates about: ships that move, turn, fire and take flux; missiles in flight. */
    static Snapshot battle(int ships, int missiles, long seed) {
        Snapshot cs = new Snapshot(); cs.hostTime = 1f; cs.piloted = new int[] {5}; cs.pilotedSeq = new int[] {9}; cs.pilotedAge = new float[] {0.1f}; cs.pilotedSysAmmo = new int[] {2};
        Random cr = new Random(seed);
        for (int i = 0; i < ships; i++) {
            Snapshot.ShipState st = new Snapshot.ShipState();
            st.netId = i * 3 + 1; st.x = cr.nextFloat() * 20000 - 10000; st.y = cr.nextFloat() * 20000 - 10000; st.vx = 80; st.vy = -40; st.facing = 359.9f; st.angVel = -12f;
            st.hull = 0.5f; st.cr = 0.7f; st.flux = 900.4f; st.hardFlux = 300f; st.flags = Snapshot.F_DEFENSE; st.shieldFacing = 123f; st.firing = 0x8000000000000001L;
            st.weaponAngles = new byte[i == 0 ? 80 : 12]; cr.nextBytes(st.weaponAngles); st.system = 2; st.cloak = 4; st.systemLevel = 0.5f;
            cs.ships.add(st);
        }
        for (int i = 0; i < missiles; i++) {
            Snapshot.MissileState m = new Snapshot.MissileState();
            m.id = 500000 + i * 7; m.x = i * 10; m.y = -i * 10; m.vx = 300; m.vy = -200; m.facing = 350f; m.angVel = 40f; m.flightTime = 1f;
            cs.missiles.add(m);
        }
        return cs;
    }

    static void advance(Snapshot cs, int tick, float dt) {
        int f = tick;
        cs.tick = tick; cs.hostTime += dt;
        for (Snapshot.ShipState st : cs.ships) {
            st.x += st.vx * dt; st.y += st.vy * dt; st.facing = (st.facing + 0.7f) % 360f; st.flux += 13.3f; st.hardFlux += 2f;
            if (f % 7 == 0) st.firing ^= 2L;
            if (f % 3 == 0) { st.weaponAngles = st.weaponAngles.clone(); st.weaponAngles[st.weaponAngles.length - 1]++; }
        }
        cs.ships.get(2).fluxStep = 50f; cs.ships.get(3).exact = true;
        for (Snapshot.MissileState m : cs.missiles) { m.x += m.vx * dt; m.y += m.vy * dt; m.flightTime += dt; m.facing = (m.facing + 2f) % 360f; }
    }

    static Snapshot bump(Snapshot cs) { cs.tick++; cs.hostTime += 1 / 60f; return cs; }

    static Snapshot copyOf(Snapshot s) {
        Snapshot c = new Snapshot(); c.tick = s.tick; c.hostTime = s.hostTime;
        for (Snapshot.ShipState x : s.ships) {
            Snapshot.ShipState y = new Snapshot.ShipState();
            y.netId = x.netId; y.x = x.x; y.y = x.y; y.vx = x.vx; y.vy = x.vy; y.facing = x.facing; y.angVel = x.angVel; y.hull = x.hull; y.cr = x.cr; y.flux = x.flux; y.hardFlux = x.hardFlux;
            y.flags = x.flags; y.shieldFacing = x.shieldFacing; y.firing = x.firing; y.system = x.system; y.cloak = x.cloak; y.systemLevel = x.systemLevel; y.cloakLevel = x.cloakLevel;
            y.weaponAngles = x.weaponAngles == null ? null : x.weaponAngles.clone(); y.shotTotals = x.shotTotals == null ? null : x.shotTotals.clone();
            y.fluxStep = x.fluxStep; y.exact = x.exact;
            c.ships.add(y);
        }
        for (Snapshot.MissileState x : s.missiles) {
            Snapshot.MissileState y = new Snapshot.MissileState();
            y.id = x.id; y.x = x.x; y.y = x.y; y.vx = x.vx; y.vy = x.vy; y.facing = x.facing; y.angVel = x.angVel; y.flags = x.flags; y.flightTime = x.flightTime;
            c.missiles.add(y);
        }
        return c;
    }

    /** True if what the joiner rebuilt is the host's state, to within what the packer is allowed to leave unsent. */
    static boolean alike(Snapshot cs, Snapshot g) {
        if (g.tick != cs.tick || g.ships.size() != cs.ships.size() || g.missiles.size() != cs.missiles.size() || g.hostTime != cs.hostTime) return false;
        for (int i = 0; i < cs.ships.size(); i++) {
            Snapshot.ShipState x = cs.ships.get(i), y = g.ships.get(i);
            float fluxBand = x.exact ? 0.51f : x.fluxStep + 0.6f;
            boolean ok = x.netId == y.netId && Math.abs(x.x - y.x) <= 0.29f && Math.abs(x.y - y.y) <= 0.29f && turnApart(x.facing, y.facing) < 0.06f
                    && Math.abs(x.flux - y.flux) <= fluxBand && (x.flux > 0f) == (y.flux > 0f)
                    && (x.firing | y.firing) == y.firing && Arrays.equals(x.weaponAngles, y.weaponAngles)
                    && x.flags == y.flags && x.system == y.system && x.cloak == y.cloak && Math.abs(x.hull - y.hull) < 0.0001f && Math.abs(x.vx - y.vx) < 0.32f
                    && turnApart(x.shieldFacing, y.shieldFacing) < 3f && Math.abs(x.angVel - y.angVel) < 0.26f;
            if (ok && x.exact) ok = Math.abs(x.x - y.x) <= 0.032f;
            if (!ok) return false;
        }
        for (int i = 0; i < cs.missiles.size(); i++) {
            Snapshot.MissileState x = cs.missiles.get(i), y = g.missiles.get(i);
            if (!(x.id == y.id && Math.abs(x.x - y.x) <= 0.29f && Math.abs(x.y - y.y) <= 0.29f && Math.abs(x.flightTime - y.flightTime) < 0.051f && turnApart(x.facing, y.facing) < 3f)) return false;
        }
        return true;
    }

    static float turnApart(float a, float b) { float d = Math.abs(a - b) % 360f; return d > 180f ? 360f - d : d; }
    static Conn.Packet waitFor(Conn c, byte type, HostSession h) throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (h != null) h.pump();
            Conn.Packet p = c.poll();
            if (p != null && p.type == type) return p;
            if (p == null) Thread.sleep(5);
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("channels")) Transport.force(Transport.CHANNELS);
        int port = 24900;
        // packing round trip, including awkward values and non-Latin text
        Msg.W pw = new Msg.W();
        pw.out.writeInt(-123456789); pw.out.writeShort(65535); pw.out.writeByte(-7); pw.out.writeLong(0x8000000000000001L);
        pw.out.writeFloat(-1234.5678f); pw.out.writeBoolean(true); pw.out.writeUTF("NEOysod \u0e22\u0e34\u0e19\u0e14\u0e35 \u2603"); pw.out.writeUTF("");
        In pr = Msg.reader(pw.done());
        check("packing round trip", pr.readInt() == -123456789 && (pr.readShort() & 0xFFFF) == 65535 && pr.readByte() == -7
                && pr.readLong() == 0x8000000000000001L && pr.readFloat() == -1234.5678f && pr.readBoolean()
                && pr.readUTF().equals("NEOysod \u0e22\u0e34\u0e19\u0e14\u0e35 \u2603") && pr.readUTF().isEmpty());
        boolean threw = false; try { pr.readInt(); } catch (java.io.IOException e) { threw = true; }
        check("reading past the end of a message is an error, not garbage", threw);
        MODS.add("lw_lazylib", "LazyLib", "3.0", true);
        MODS.add("nexerelin", "Nexerelin", "0.12.1b", false);
        MODS.add("shadow_ships", "Shadowyards", "1.2", false);
        HostSession host = HostSession.getOrStart(port, 4, 20, "Host", MODS, HostSession.MOD_CHECK_CONTENT);
        host.beginBattle();
        List<Conn> clients = new ArrayList<Conn>();
        for (int i = 1; i <= 3; i++) {
            Conn c = join("P" + i, port);
            clients.add(c);
            check("player " + i + " welcomed", waitFor(c, Msg.WELCOME, host) != null);
        }
        check("3 remote players + host = 4", host.getPlayers().size() == 3);
        Conn extra = join("P4", port);
        Conn.Packet rej = waitFor(extra, Msg.REJECT, host);
        check("5th player rejected when cap is 4: " + (rej == null ? "" : Msg.readUtf(rej.data)), rej != null);

        host = HostSession.getOrStart(port, 6, 20, "Host", MODS, HostSession.MOD_CHECK_CONTENT); // raise cap in settings, same session
        Conn p5 = join("P5", port);
        check("after raising cap to 6, another player gets in", waitFor(p5, Msg.WELCOME, host) != null);
        check("existing players kept when cap changes", host.getPlayers().size() == 4);
        java.util.Set<Integer> slotsSeen = new java.util.HashSet<Integer>(); for (HostSession.Player hp : host.getPlayers()) slotsSeen.add(hp.slot);
        check("joiners are numbered P2 onwards, each a different number: " + slotsSeen, slotsSeen.size() == 4 && slotsSeen.contains(2) && slotsSeen.contains(5) && !slotsSeen.contains(1));

        // version mismatch
        Conn bad = Conn.open("127.0.0.1", port);
        Msg.W w = new Msg.W(); w.out.writeInt(999); w.out.writeUTF("old"); MODS.write(w.out); bad.send(Msg.HELLO, w.done());
        check("wrong protocol version rejected", waitFor(bad, Msg.REJECT, host) != null);

        // mod list check
        ModList other = new ModList();
        other.add("nexerelin", "Nexerelin", "0.12.0", false);          // older version
        other.add("uaf", "United Aurora Federation", "0.75", false);    // host doesn't have it
        other.add("speedup", "SpeedUp", "1.0", true);                   // utility: ignored
        Conn mm = join("Mismatch", port, other);                        // also lacks Shadowyards and LazyLib (utility)
        Conn.Packet mr = waitFor(mm, Msg.REJECT, host);
        String why = mr == null ? "" : Msg.readUtf(mr.data);
        System.out.println("      " + why.replace("\n", "\n      "));
        check("different content mods are refused with the differences listed", why.contains("Version:  Nexerelin")
                && why.contains("Missing:  Shadowyards") && why.contains("Extra:  United Aurora") && !why.contains("LazyLib") && !why.contains("SpeedUp"));
        long mEnd = System.currentTimeMillis() + 2000; while (System.currentTimeMillis() < mEnd && !mm.isClosed()) Thread.sleep(20);
        check("refused player is disconnected and takes no slot", mm.isClosed() && host.getPlayers().size() == 4);
        ModList utilOnly = new ModList();
        utilOnly.add("nexerelin", "Nexerelin", "0.12.1b", false); utilOnly.add("shadow_ships", "Shadowyards", "1.2", false); utilOnly.add("speedup", "SpeedUp", "1.0", true);
        check("only utility mods differ: allowed in 'content' mode", ModList.differences(MODS, utilOnly, false).isEmpty());
        check("only utility mods differ: refused in 'all' mode", ModList.differences(MODS, utilOnly, true).size() == 2);

        // spawn round trip
        ShipSpawn sp = new ShipSpawn();
        sp.netId = 7; sp.owner = 1; sp.hullId = "onslaught"; sp.name = "TTS Test"; sp.vents = 30; sp.caps = 12;
        sp.cr = 0.7f; sp.x = -1200.5f; sp.y = 3400.25f; sp.facing = 271f;
        sp.hullMods.add("heavyarmor"); sp.sMods.add("heavyarmor"); sp.permaMods.add("comp_hull"); sp.hasCaptain = true; sp.captainFirst = "Ada"; sp.captainLevel = 6; sp.skillIds.add("helmsmanship"); sp.skillLevels.add(2f); sp.fleetSkillIds.add("coordinated_maneuvers"); sp.fleetSkillLevels.add(1f); sp.fleetSkillIds.add("flux_regulation"); sp.fleetSkillLevels.add(2f); sp.maxFlux = 17000f; sp.maxSpeed = 25f; sp.weaponSlots.add("WS 001"); sp.weaponIds.add("hellbore"); sp.weaponOrder.add("WS 001"); sp.weaponOrderIds.add("hellbore"); sp.wings.add("talon_wing"); sp.wings.add(""); ShipSpawn.Group grp = new ShipSpawn.Group(); grp.type = "ALTERNATING"; grp.autofire = true; grp.slots.add("WS 001"); sp.groups.add(grp); sp.wingId = "talon_wing"; sp.wingNet = 31; sp.carrierId = 4; sp.bay = 2;
        for (HostSession.Player p : host.getPlayers()) p.needsSync = false;
        host.broadcast(Msg.SPAWN, sp.encode());
        ShipSpawn got = ShipSpawn.decode(waitFor(clients.get(0), Msg.SPAWN, host).data);
        check("a ship's description carries its fleet commander's skills", got.fleetSkillIds.size() == 2 && got.fleetSkillIds.get(1).equals("flux_regulation") && got.fleetSkillLevels.get(1) == 2f && got.skillIds.get(0).equals("helmsmanship"));
        check("spawn round trip", got.netId == 7 && got.hullId.equals("onslaught") && got.weaponIds.get(0).equals("hellbore")
                && got.weaponOrderIds.get(0).equals("hellbore") && got.sMods.contains("heavyarmor") && got.permaMods.contains("comp_hull") && got.hasCaptain && got.captainFirst.equals("Ada") && got.captainLevel == 6 && got.skillIds.get(0).equals("helmsmanship") && got.skillLevels.get(0) == 2f && got.maxFlux == 17000f && got.maxSpeed == 25f && got.wings.size() == 2 && got.wings.get(0).equals("talon_wing") && got.wings.get(1).isEmpty() && got.groups.size() == 1 && got.groups.get(0).autofire && got.groups.get(0).type.equals("ALTERNATING") && got.groups.get(0).slots.get(0).equals("WS 001") && got.x == sp.x && got.cr == 0.7f && got.hullMods.contains("heavyarmor") && got.wingId.equals("talon_wing") && got.wingNet == 31 && got.carrierId == 4 && got.bay == 2);

        // snapshot of a big battle: 60 ships x 20 weapons
        Snapshot snap = new Snapshot(); snap.tick = 42; snap.paused = true; snap.hostFlagship = 3; snap.timeMult = 0.3333f; snap.piloted = new int[] {5, 9};
        snap.hostTime = 123.456f; snap.pilotedSeq = new int[] {65535, 7}; snap.pilotedAge = new float[] {0.13f, 9f}; snap.pilotedSysAmmo = new int[] {3, 999};
        Random r = new Random(1);
        for (int i = 0; i < 60; i++) {
            Snapshot.ShipState st = new Snapshot.ShipState();
            st.netId = i; st.x = r.nextFloat() * 9000; st.y = -r.nextFloat() * 9000; st.vx = 12.5f; st.facing = 359.5f;
            st.hull = 0.37f; st.cr = 0.615f; st.flux = 1234f; st.flags = Snapshot.F_DEFENSE | Snapshot.F_VENTING; st.firing = 0x8000000000000001L;
            st.weaponAngles = new byte[20]; st.weaponAngles[19] = Snapshot.packAngle(-90f);
            snap.ships.add(st);
        }
        for (int i = 0; i < 150; i++) {
            Snapshot.MissileState m = new Snapshot.MissileState();
            m.id = 1000 + i; m.x = i * 3.5f; m.y = -i; m.vx = 300f; m.vy = -150f; m.facing = 181f; m.angVel = -212.3f; m.flags = i == 149 ? Snapshot.M_FIZZLING : 0;
            snap.missiles.add(m);
        }
        byte[] enc = snap.encode();
        host.broadcastDroppable(Msg.SNAPSHOT, enc);
        boolean all = true; Snapshot s2 = null;
        for (int i = 0; i < 3; i++) { Conn.Packet p = waitFor(clients.get(i), Msg.SNAPSHOT, host); if (p == null) all = false; else s2 = Snapshot.decode(p.data); }
        check("snapshot reached all players (" + enc.length + " bytes for 60 ships + 150 missiles, ~" + (enc.length * 20 / 1024) + " KB/s per player at 20 Hz)", all);
        Snapshot.ShipState l = s2.ships.get(59);
        check("update carries the host's clock and which controls each piloted ship has acted on", s2.hostTime == 123.456f && s2.pilotedSeq[0] == 65535
                && s2.pilotedSeq[1] == 7 && Math.abs(s2.pilotedAge[0] - 0.13f) < 0.006f && s2.pilotedAge[1] >= 2.5f && s2.pilotedSysAmmo[0] == 3 && s2.pilotedSysAmmo[1] == 255);
        // the connection is compressed
        String zs = clients.get(0).compressionState();
        check("both directions of the link are compressed (" + zs + ")", zs.contains("sending compressed") && zs.contains("receiving compressed"));
        String zm = clients.get(1).compressionState();
        check("a side that never offered is sent a plain stream and everything still works (" + zm + ")", zm.contains("receiving plain"));
        {   // updates as changes, built on an update the player holds
            SnapshotCodec.Encoder en = new SnapshotCodec.Encoder(), enLate = new SnapshotCodec.Encoder();
            SnapshotCodec.Decoder first = new SnapshotCodec.Decoder(), late = new SnapshotCodec.Decoder();
            Snapshot cs = battle(40, 60, 3);
            boolean exact = true, lateOk = true, gapNull = false, healed = false; int deltaBytes = 0, deltas = 0, keyBytes = 0, oldBytes = 0;
            int base = -1, lateBase = -1;
            for (int f = 0; f < 300; f++) {
                advance(cs, f, 1 / 60f);
                if (f == 150) cs.ships.get(2).flux = 0f;                                // flux gone: must be told at once, band or no band
                if (f > 150 && f < 160) cs.ships.get(2).flux = 0f;
                Snapshot.MissileState gone = f == 50 ? cs.missiles.remove(0) : null; // one goes, a new one comes
                if (f == 60) { Snapshot.MissileState m = new Snapshot.MissileState(); m.id = 12; m.x = 5; m.vx = 10; cs.missiles.add(m); }
                byte[] wire = en.encode(cs, base);
                oldBytes += cs.encode().length;
                if (en.lastKey) keyBytes = wire.length; else { deltaBytes += wire.length; deltas++; }
                Snapshot rebuilt = f == 200 ? null : first.decode(wire);
                if (f == 201) { // update 200 never reached "first", so 201, built on it, cannot be read; one in full can
                    gapNull = rebuilt == null && first.result == SnapshotCodec.Decoder.NOTHING_TO_BUILD_ON;
                    rebuilt = first.decode(en.encode(cs, -1));
                    healed = rebuilt != null;
                }
                base = cs.tick;                                                         // (the host believes every one arrived)
                if (rebuilt != null && !alike(cs, rebuilt)) exact = false;
                if (rebuilt == null && f != 200) exact = false;
                if (f >= 105) {                                                        // "late" joins here, with a packer of its own
                    Snapshot fromLate = late.decode(enLate.encode(cs, lateBase));
                    lateBase = cs.tick;
                    if (fromLate == null || !alike(cs, fromLate)) lateOk = false;
                }
            }
            check("updates sent as changes rebuild the host's state every time, over 300 updates (changes avg " + (deltaBytes / deltas) + " bytes, in full " + keyBytes
                    + ", old format " + (oldBytes / 300) + ")", exact);
            check("changes are well under a third of the old size", deltaBytes / deltas < oldBytes / 300 / 3);
            check("a player who starts mid-battle, from an update in full, reads everything after it", lateOk);
            check("an update built on one that never arrived is refused, and one in full puts it right", gapNull && healed);
            {   // things that simply carry on cost almost nothing
                SnapshotCodec.Encoder pe = new SnapshotCodec.Encoder(); SnapshotCodec.Decoder pd = new SnapshotCodec.Decoder();
                Snapshot ps = new Snapshot();
                for (int i = 0; i < 100; i++) { Snapshot.MissileState m = new Snapshot.MissileState(); m.id = 1000 + i; m.x = i * 31.7f; m.y = -i * 3.3f; m.vx = 287.3f; m.vy = -113.9f; m.facing = 200f; ps.missiles.add(m); }
                int steady = 0, n = 0; boolean near = true; float t = 10f;
                for (int f = 0; f < 240; f++) {
                    float dt = 0.012f + (f % 5) * 0.003f; t += dt; ps.tick = f; ps.hostTime = t;
                    for (Snapshot.MissileState m : ps.missiles) { m.x += m.vx * dt; m.y += m.vy * dt; m.flightTime += dt; }
                    byte[] wire = pe.encode(ps, f - 1);
                    if (!pe.lastKey) { steady += wire.length; n++; }
                    Snapshot pg = pd.decode(wire);
                    for (int i = 0; i < 100; i++) {
                        Snapshot.MissileState x = ps.missiles.get(i), y = pg.missiles.get(i);
                        if (Math.abs(x.x - y.x) > 0.29f || Math.abs(x.y - y.y) > 0.29f || Math.abs(x.flightTime - y.flightTime) > 0.051f) near = false;
                    }
                }
                check("100 missiles flying straight for 240 uneven updates: " + (steady / n) + " bytes an update, never more than a quarter unit out", near && steady / n < 320);
            }
            {   // a road that loses updates, delivers some out of order, and reports back late (UDP)
                SnapshotCodec.Encoder ue = new SnapshotCodec.Encoder(); SnapshotCodec.Decoder ud = new SnapshotCodec.Decoder();
                Snapshot us = battle(30, 80, 9);
                Random lr = new Random(11);
                Map<Integer, Snapshot> truth = new HashMap<Integer, Snapshot>();
                List<byte[]> inFlight = new ArrayList<byte[]>(); List<Integer> arriveAt = new ArrayList<Integer>();
                List<int[]> acksInFlight = new ArrayList<int[]>();
                int hostAck = -1, read = 0, lost = 0, cantRead = 0, old = 0, keys = 0, bytes = 0; boolean right = true;
                int[] shotsSeen = new int[8]; byte[] totalsAtFirst = null, totalsAtLast = null;
                for (int f = 0; f < 900; f++) {
                    advance(us, f, 1 / 60f);
                    Snapshot.ShipState gunner = us.ships.get(5);
                    if (gunner.shotTotals == null) gunner.shotTotals = new byte[8];
                    gunner.shotTotals = gunner.shotTotals.clone();
                    if (f % 3 == 0) gunner.shotTotals[lr.nextInt(8)]++;
                    truth.put(us.tick, copyOf(us));
                    for (Iterator<int[]> it = acksInFlight.iterator(); it.hasNext();) { int[] a2 = it.next(); if (a2[0] <= f) { hostAck = Math.max(hostAck, a2[1]); it.remove(); } }
                    boolean inFull = !ue.has(hostAck);
                    byte[] wire = ue.encode(us, inFull ? -1 : hostAck);
                    bytes += wire.length;
                    if (ue.lastKey) { keys++; inFlight.add(wire); arriveAt.add(f); hostAck = us.tick; } // in full goes by TCP: it arrives
                    else if (lr.nextInt(100) < 25 || (f >= 400 && f < 430)) lost++;                  // a quarter lost, and one half-second blackout
                    else { inFlight.add(wire); arriveAt.add(f + lr.nextInt(4)); }                     // the rest up to 3 updates late, so out of order
                    for (int q = 0; q < inFlight.size(); q++) {
                        if (arriveAt.get(q) > f) continue;
                        Snapshot arrived = ud.decode(inFlight.remove(q)); arriveAt.remove(q); q--;
                        if (arrived == null) { if (ud.result == SnapshotCodec.Decoder.OLD) old++; else cantRead++; continue; }
                        read++;
                        if (!alike(truth.get(arrived.tick), arrived)) right = false;
                        Snapshot.ShipState g5 = null; for (Snapshot.ShipState x : arrived.ships) if (x.netId == gunner.netId) g5 = x;
                        if (g5.shots != null) for (int q2 = 0; q2 + 1 < g5.shots.length; q2 += 2) shotsSeen[g5.shots[q2]] += g5.shots[q2 + 1];
                        byte[] tt = null; for (Snapshot.ShipState x : truth.get(arrived.tick).ships) if (x.netId == gunner.netId) tt = x.shotTotals;
                        if (totalsAtFirst == null) totalsAtFirst = tt;
                        totalsAtLast = tt;
                        acksInFlight.add(new int[] { f + 2 + lr.nextInt(3), ud.ackTick() });       // word of it reaches the host 2-4 updates later
                    }
                }
                boolean shotsRight = true;
                for (int w2 = 0; w2 < 8; w2++) if (shotsSeen[w2] != ((totalsAtLast[w2] - totalsAtFirst[w2]) & 0xFF)) shotsRight = false;
                check("over a road that loses a quarter of all updates, shuffles the rest and blacks out for half a second: " + read + " read and every one right, "
                        + lost + " lost, " + old + " overtaken, " + cantRead + " unreadable, " + keys + " sent in full, avg " + (bytes / 900) + " bytes", right && read > 500 && cantRead < 20 && keys < 8);
                check("every shot fired is counted exactly once on the joiner, lost updates or not", shotsRight);
            }
            Snapshot.ShipState nan = new Snapshot.ShipState(); nan.netId = 999; nan.x = Float.NaN; cs.ships.add(nan);
            bump(cs);
            Snapshot noNan = first.decode(en.encode(cs, -1));
            check("a state with impossible coordinates is left out of the update", noNan != null && noNan.ships.size() == cs.ships.size() - 1);
            boolean refused = false; try { first.decode(new byte[] {1, 0, 0, 0, 0, 127, 0, 0}); } catch (java.io.IOException e) { refused = true; }
            check("a cut-off update is an error", refused);
            cs.ships.remove(nan);

            // over the wire by TCP: who gets which form
            for (HostSession.Player p : host.getPlayers()) p.needsSync = false;
            bump(cs); host.broadcastUpdate(cs);
            Conn.Packet k0 = waitFor(clients.get(0), Msg.SNAPSHOT_D, host), k1 = waitFor(clients.get(1), Msg.SNAPSHOT_D, host);
            check("each player's first update is one in full", k0 != null && k0.data[0] == 1 && k1 != null && k1.data[0] == 1);
            bump(cs); host.broadcastUpdate(cs);
            k0 = waitFor(clients.get(0), Msg.SNAPSHOT_D, host); k1 = waitFor(clients.get(1), Msg.SNAPSHOT_D, host);
            check("after that, players are sent only the changes", k0 != null && k0.data[0] == 0 && k1 != null && k1.data[0] == 0);
            clients.get(1).send(Msg.NEED_KEY, Msg.EMPTY);
            long kEnd = System.currentTimeMillis() + 2000; boolean asked = false;
            while (System.currentTimeMillis() < kEnd && !asked) { host.pump(); for (HostSession.Player p : host.getPlayers()) if (p.needKey) asked = true; Thread.sleep(10); }
            bump(cs); host.broadcastUpdate(cs);
            k1 = waitFor(clients.get(1), Msg.SNAPSHOT_D, host); k0 = waitFor(clients.get(0), Msg.SNAPSHOT_D, host);
            check("a player who asks gets the next update in full, the others still only the changes", k1 != null && k1.data[0] == 1 && k0 != null && k0.data[0] == 0);

            // over the wire by UDP: a real joiner link beside the others
            host = HostSession.getOrStart(port, 12, 20, "Host", MODS, HostSession.MOD_CHECK_CONTENT);
            ClientLink link = ClientLink.get();
            link.connect("127.0.0.1", port, "Udp", MODS, "", "");
            long uEnd = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < uEnd && !link.describeUdp().startsWith("UDP open")) { host.pump(); Thread.sleep(10); }
            check("the joiner's hello by UDP is answered (" + link.describeUdp() + ")", link.describeUdp().startsWith("UDP open"));
            for (HostSession.Player p : host.getPlayers()) p.needsSync = false;
            SnapshotCodec.Decoder wd = new SnapshotCodec.Decoder();
            int byTcp = 0, byUdp = 0, fullOnes = 0; boolean wireRight = true;
            for (int f = 0; f < 120; f++) {
                advance(cs, cs.tick + 1, 1 / 60f);
                host.pump();
                host.broadcastUpdate(cs);
                long until = System.currentTimeMillis() + 15;
                while (System.currentTimeMillis() < until) {
                    Conn.Packet tp = link.getConn().poll();
                    byte[] u = tp != null && tp.type == Msg.SNAPSHOT_D ? tp.data : link.pollUpdate();
                    if (u == null) { Thread.sleep(1); continue; }
                    if (tp != null) byTcp++; else byUdp++;
                    if (u[0] == 1) fullOnes++;
                    Snapshot arrived = wd.decode(u);
                    if (arrived != null) { link.ack(wd.ackEpoch(), wd.ackTick()); if (arrived.tick == cs.tick && !alike(cs, arrived)) wireRight = false; }
                }
            }
            check("updates then travel by UDP, built on what the joiner says it holds (" + byUdp + " by UDP, " + byTcp + " by TCP, " + fullOnes + " in full; " + host.describeUpdates() + ")",
                    byUdp > 80 && fullOnes <= 3 && wireRight && wd.missed < 5);
            InputState ui = new InputState(); ui.flags = InputState.ACCEL | InputState.FIRE; ui.seq = 77; ui.facing = 12f;
            HostSession.Player udpPlayer = null; for (HostSession.Player p : host.getPlayers()) if (p.name.equals("Udp")) udpPlayer = p;
            long iEnd = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < iEnd && udpPlayer.input.seq != 77) { link.sendControls(ui.encode(), wd.ackEpoch(), wd.ackTick()); host.pump(); Thread.sleep(10); }
            check("the joiner's controls reach the host by UDP (" + link.describeUdp() + ")", link.controlsByUdp() && udpPlayer.input.seq == 77 && udpPlayer.input.has(InputState.FIRE));
            ui.seq = 70; ui.flags = 0; link.sendControls(ui.encode(), wd.ackEpoch(), wd.ackTick()); Thread.sleep(100); host.pump();
            check("controls that arrive after newer ones are ignored", udpPlayer.input.seq == 77 && udpPlayer.input.has(InputState.FIRE));
            link.disconnect();
            long dEnd = System.currentTimeMillis() + 2000; while (System.currentTimeMillis() < dEnd && host.getPlayers().contains(udpPlayer)) { host.pump(); Thread.sleep(10); }
        }

        // side channels
        Snapshot shotSnap = new Snapshot(); Snapshot.ShipState shooter = new Snapshot.ShipState(); shooter.netId = 4; shooter.firing = 5L; shooter.flags = Snapshot.F_PHASED;
        shooter.shots = new byte[] {0, 3, 2, 1}; shotSnap.ships.add(shooter);
        Snapshot.ShipState quiet = new Snapshot.ShipState(); quiet.netId = 5; shotSnap.ships.add(quiet);
        Snapshot shotBack = Snapshot.decode(shotSnap.encode());
        check("shot counts travel with the firing bits, and only when there are any", shotBack.ships.get(0).shots != null && shotBack.ships.get(0).shots.length == 4
                && shotBack.ships.get(0).shots[1] == 3 && shotBack.ships.get(0).shots[2] == 2 && shotBack.ships.get(0).flags == Snapshot.F_PHASED
                && shotBack.ships.get(1).shots == null && shotBack.ships.get(1).netId == 5);
        ShipSpawn module = new ShipSpawn(); module.netId = 77; module.hullId = "uaf_m_zepporia_armor_1"; module.kind = ShipSpawn.MODULE; module.parentId = 12; module.slotId = "WS0031"; module.moduleIds.add(5); module.moduleIds.add(6);
        ShipSpawn moduleBack = ShipSpawn.decode(module.encode());
        check("a module names the ship and slot it belongs to", moduleBack.kind == ShipSpawn.MODULE && moduleBack.parentId == 12 && moduleBack.slotId.equals("WS0031") && moduleBack.netId == 77 && moduleBack.moduleIds.size() == 2 && moduleBack.moduleIds.get(1) == 6);
        List<Extras.Hurt> hurts = new ArrayList<Extras.Hurt>(); Extras.Hurt hurt = new Extras.Hurt(); hurt.target = 9; hurt.source = -1; hurt.type = 3; hurt.onShield = true; hurt.amount = 812.5f; hurt.emp = 40f; hurt.x = -3000.5f; hurt.y = 12f; hurts.add(hurt);
        Extras.Hurt hurtBack = Extras.decodeHurts(Extras.encodeHurts(hurts)).get(0);
        check("damage report round trip", hurtBack.target == 9 && hurtBack.source == -1 && hurtBack.type == 3 && hurtBack.onShield && hurtBack.amount == 812.5f && hurtBack.emp == 40f && hurtBack.x == -3000.5f && hurtBack.y == 12f);
        List<Extras.Stats> stats = new ArrayList<Extras.Stats>(); Extras.Stats stat = new Extras.Stats(); stat.ship = 300; stat.mask = (1L << 0) | (1L << 5) | (1L << 40); stat.values = new float[] {110f, 0.5f, 3f}; stats.add(stat);
        Extras.Stats statBack = Extras.decodeStats(Extras.encodeStats(stats)).get(0);
        check("ship stats round trip (only the ones that changed)", statBack.ship == 300 && statBack.mask == stat.mask && statBack.values.length == 3 && statBack.values[1] == 0.5f && statBack.values[2] == 3f);
        List<Extras.Note> notes = new ArrayList<Extras.Note>();
        notes.add(Extras.noteOf(7, "is_sticky", Boolean.TRUE)); notes.add(Extras.noteOf(7, "count", Integer.valueOf(-12))); notes.add(Extras.noteOf(-1, "big", Long.valueOf(1L << 40)));
        notes.add(Extras.noteOf(7, "level", Float.valueOf(0.25f))); notes.add(Extras.noteOf(7, "exact", Double.valueOf(Math.PI))); notes.add(Extras.noteOf(7, "mode", "armed")); notes.add(Extras.noteOf(7, "gone", null));
        List<Extras.Note> notesBack = Extras.decodeNotes(Extras.encodeNotes(notes));
        check("script values round trip, each as the sort it was", notesBack.size() == 7 && Boolean.TRUE.equals(notesBack.get(0).value(notesBack.get(0).wide))
                && Integer.valueOf(-12).equals(notesBack.get(1).value(notesBack.get(1).wide)) && Long.valueOf(1L << 40).equals(notesBack.get(2).value(notesBack.get(2).wide)) && notesBack.get(2).ship == -1
                && Float.valueOf(0.25f).equals(notesBack.get(3).value(notesBack.get(3).wide)) && Double.valueOf(Math.PI).equals(notesBack.get(4).value(notesBack.get(4).wide))
                && "armed".equals(notesBack.get(5).value(false)) && notesBack.get(6).value(false) == null && notesBack.get(6).kind == 0);
        check("script values that are not simple are left alone", Extras.noteOf(1, "list", new ArrayList<String>()) == null && Extras.noteOf(1, "k", new float[2]) == null);
        check("snapshot round trip", s2.timeMult == 0.3333f && s2.piloted.length == 2 && s2.piloted[1] == 9 && s2.tick == 42 && s2.paused && s2.ships.size() == 60 && Math.abs(l.hull - 0.37f) < 0.0001f && Math.abs(l.vx - 12.5f) < 0.001f && Math.abs(l.facing - 359.5f) < 0.01f && l.flux == 1234f && Math.abs(l.cr - 0.615f) < 0.003f && l.firing == 0x8000000000000001L
                && Math.abs(Snapshot.unpackAngle(l.weaponAngles[19]) - 270f) < 1.5f && s2.missiles.size() == 150
                && s2.missiles.get(149).id == 1149 && s2.missiles.get(149).flags == Snapshot.M_FIZZLING && s2.missiles.get(3).vx == 300f
                && Math.abs(s2.missiles.get(0).facing - 181f) < 1.5f && Math.abs(s2.missiles.get(0).angVel + 212.3f) < 0.06f && l.flags == (Snapshot.F_DEFENSE | Snapshot.F_VENTING));
        check("angle 359.9 wraps to 0", Snapshot.unpackAngle(Snapshot.packAngle(359.9f)) == 0f);

        // a quiet fighter: only the parts that are not at rest are sent, and absent parts read back as "at rest"
        Snapshot fs = new Snapshot(); fs.tick = 100000; fs.hostFlagship = -1;
        for (int i = 0; i < 58; i++) {
            Snapshot.ShipState f = new Snapshot.ShipState();
            f.netId = 300 + i; f.x = 1234.5f; f.y = -777.25f; f.vx = -310.4f; f.vy = 55f; f.facing = 123.4f; f.angVel = -88.8f;
            fs.ships.add(f);
        }
        fs.ships.get(3).system = 2; fs.ships.get(3).systemLevel = 1f; fs.ships.get(3).cloak = 3; fs.ships.get(3).cloakLevel = 0.4f; fs.ships.get(3).flags = Snapshot.F_PHASED;
        Snapshot.ShipState still = new Snapshot.ShipState(); still.netId = 70000; still.x = 5f; still.y = 6f; still.facing = 90f;
        fs.ships.add(still);
        Snapshot.MissileState straight = new Snapshot.MissileState(); straight.id = 123456; straight.x = 1f; straight.vx = 3999f; straight.facing = 45f; straight.flightTime = 12.34f;
        fs.missiles.add(straight);
        byte[] fenc = fs.encode();
        Snapshot fd = Snapshot.decode(fenc);
        Snapshot.ShipState f0 = fd.ships.get(0), st2 = fd.ships.get(58);
        check("compact states (" + fenc.length + " bytes for 58 fighters + 1 idle ship + 1 missile; the old format needed " + (17 + 59 * 60 + 2 + 24) + ")",
                fd.tick == 100000 && fd.hostFlagship == -1 && fd.ships.size() == 59 && f0.netId == 300 && f0.x == 1234.5f && f0.y == -777.25f
                && Math.abs(f0.vx + 310.4f) < 0.07f && f0.vy == 55f && Math.abs(f0.facing - 123.4f) < 0.01f && Math.abs(f0.angVel + 88.8f) < 0.06f
                && f0.hull == 1f && f0.cr == 1f && f0.flux == 0f && f0.firing == 0L && f0.weaponAngles == null
                && st2.netId == 70000 && st2.vx == 0f && st2.angVel == 0f && st2.facing == 90f
                && fd.missiles.size() == 1 && fd.missiles.get(0).id == 123456 && fd.missiles.get(0).vx == 3999f && fd.missiles.get(0).angVel == 0f && Math.abs(fd.missiles.get(0).flightTime - 12.34f) < 0.011f
                && fenc.length < 59 * 25 + 40);
        Snapshot.ShipState sy = fd.ships.get(3);
        check("ship systems and phased status round trip", sy.system == 2 && sy.systemLevel == 1f && sy.cloak == 3 && Math.abs(sy.cloakLevel - 0.4f) < 0.005f
                && sy.flags == Snapshot.F_PHASED && f0.system == 0 && f0.cloak == 0 && f0.cloakLevel == 0f);
        wingman.net.Out vo = new wingman.net.Out();
        long[] nums = { 0L, 127L, 128L, 16383L, 16384L, 0xFFFFFFFFL, Long.MAX_VALUE, -1L, 0x8000000000000001L };
        for (long v : nums) vo.writeVarLong(v);
        wingman.net.In vi = new wingman.net.In(vo.toByteArray());
        boolean vok = true;
        for (long v : nums) if (vi.readVarLong() != v) vok = false;
        check("compact numbers round trip", vok);

        // guard rails: garbage numbers are dropped, ranges are clamped
        Snapshot gs = new Snapshot();
        Snapshot.ShipState ok = new Snapshot.ShipState(); ok.netId = 1; ok.hull = 1.7f; ok.flux = -50f; ok.hardFlux = 900f;
        Snapshot.ShipState nan = new Snapshot.ShipState(); nan.netId = 2; nan.x = Float.NaN;
        Snapshot.ShipState inf = new Snapshot.ShipState(); inf.netId = 3; inf.vy = Float.POSITIVE_INFINITY;
        Snapshot.MissileState mbad = new Snapshot.MissileState(); mbad.id = 9; mbad.y = Float.NaN;
        gs.ships.add(ok); gs.ships.add(nan); gs.ships.add(inf); gs.missiles.add(mbad); gs.timeMult = Float.NaN;
        Snapshot gd = Snapshot.decode(gs.encode());
        check("guard rails: impossible ship and missile states dropped, hull/flux/time clamped", gd.ships.size() == 2 && gd.ships.get(0).netId == 1 && gd.ships.get(1).netId == 3 && gd.ships.get(1).vy <= 4096f
                && gd.ships.get(0).hull == 1f && gd.ships.get(0).flux == 0f && gd.ships.get(0).hardFlux == 0f && gd.missiles.isEmpty() && gd.timeMult == 0.05f);
        InputState good = new InputState(); good.flags = InputState.ACCEL; good.mouseX = 10f; good.facing = 90f;
        InputState held = new InputState(); held.decode(good.encode());
        good.sysUses = 259; good.sysOrders = 2;
        good.seq = 70000; byte[] first = good.encode(); InputState numbered = new InputState(); numbered.decode(first);
        good.seq = 70001; byte[] sameAgain = good.encode(); good.flags |= InputState.FIRE; byte[] changed = good.encode(); good.flags = InputState.ACCEL;
        check("controls are numbered, and the number alone does not make them 'changed'", numbered.seq == (70000 & 0xFFFF) && numbered.sysUses == 3 && numbered.sysOrders == 2
                && !InputState.differ(first, sameAgain) && InputState.differ(first, changed) && InputState.differ(first, null));
        InputState junk = new InputState(); junk.flags = InputState.FIRE; junk.mouseX = Float.NaN; junk.facing = 45f;
        held.decode(junk.encode());
        check("guard rails: controls with impossible numbers are ignored, last good ones kept", held.flags == InputState.ACCEL && held.mouseX == 10f && held.facing == 90f);

        // input reaches the right player
        InputState in = new InputState(); in.flags = InputState.ACCEL | InputState.FIRE | InputState.VENT | InputState.PULL_BACK | InputState.AUTOPILOT; in.mouseX = 55f; in.mouseY = -66f; in.group = 2; in.target = 41; in.autofire = 0b1010; in.facing = 271.5f;
        clients.get(1).send(Msg.INPUT, in.encode());
        long end = System.currentTimeMillis() + 2000; HostSession.Player p2 = host.getPlayers().get(1);
        while (System.currentTimeMillis() < end && p2.input.flags == 0) { host.pump(); Thread.sleep(5); }
        check("input reaches host for the right player", p2.name.equals("P2") && p2.input.has(InputState.FIRE) && p2.input.has(InputState.VENT) && p2.input.has(InputState.PULL_BACK) && p2.input.has(InputState.AUTOPILOT)
                && !p2.input.has(InputState.TURN_L) && p2.input.mouseY == -66f && p2.input.group == 2 && p2.input.target == 41 && p2.input.autofire == 10 && p2.input.facing == 271.5f && host.getPlayers().get(0).input.flags == 0);

        // ship claim (sent when a joiner transfers command) reaches the host with its sequence number
        clients.get(2).send(Msg.CLAIM, Msg.twoInts(5, 7));
        end = System.currentTimeMillis() + 2000; HostSession.Player p3 = host.getPlayers().get(2);
        while (System.currentTimeMillis() < end && !p3.hasClaim) { host.pump(); Thread.sleep(5); }
        check("ship claim reaches host", p3.hasClaim && p3.claim == 7 && p3.claimSeq == 5 && !host.getPlayers().get(0).hasClaim);

        // ready check: a joiner's report reaches the host, and the lobby list round-trips
        Msg.W rw = new Msg.W(); rw.out.writeBoolean(true); rw.out.writeInt(58); rw.out.writeInt(2);
        clients.get(1).send(Msg.READY, rw.done());
        end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end && !p2.ready) { host.pump(); Thread.sleep(5); }
        check("ready report reaches host", p2.ready && p2.built == 58 && p2.skipped == 2 && !host.getPlayers().get(0).ready && host.lobbyDirty);
        Lobby lob = new Lobby(); lob.readyCheck = true; lob.hostName = "Host"; lob.expected = 60;
        for (HostSession.Player hp : host.getPlayers()) { Lobby.Entry le = new Lobby.Entry(); le.name = hp.name; le.ready = hp.ready; le.built = hp.built; le.skipped = hp.skipped; lob.players.add(le); }
        lob.players.get(1).ship = "ISS Test (Onslaught)"; lob.maxShipSize = "CRUISER"; lob.players.get(1).slot = 3; lob.players.get(1).shipNetId = 77;
        host.broadcastAll(Msg.LOBBY, lob.encode());
        Lobby lg = Lobby.decode(waitFor(clients.get(0), Msg.LOBBY, host).data);
        check("lobby round trip: " + lg.describe(lg.players.get(1)), lg.readyCheck && lg.expected == 60 && lg.players.size() == 4
                && lg.players.get(1).ready && lg.isSynced(lg.players.get(1)) && !lg.isSynced(lg.players.get(0)) && !lg.allReady());
        check("the lobby says who flies what, and the size limit", lg.players.get(1).ship.equals("ISS Test (Onslaught)") && lg.players.get(0).ship.isEmpty()
                && lg.maxShipSize.equals("CRUISER") && lg.players.get(1).slot == 3 && lg.players.get(1).shipNetId == 77 && lg.players.get(0).shipNetId == -1 && lg.describe(lg.players.get(1)).startsWith("P3  ") && lg.describe(lg.players.get(1)).contains("flying ISS Test") && lg.describe(lg.players.get(0)).contains("no ship yet") == lg.isSynced(lg.players.get(0)));
        for (Lobby.Entry le : lg.players) { le.ready = true; le.built = 60; le.skipped = 0; }
        check("all ready only when everyone is loaded and ready", lg.allReady() && new Lobby().allReady());

        // battlefield description round trip
        BattleInfo bi = new BattleInfo(); bi.width = 18000f; bi.height = 14000f; bi.background = "graphics/backgrounds/background4.jpg";
        bi.hasGlowColor = true; bi.glowColor = 0x50FF8040; bi.renderStarfield = false; bi.hyperspace = true;
        bi.hasPlanet = true; bi.planetType = "terran"; bi.planetRadius = 312.5f; bi.planetYOffset = -512f; bi.planetBgWidth = 1400f; bi.planetBgHeight = 1400f;
        bi.objectiveTypes.add("nav_buoy"); bi.objectiveX.add(-3000f); bi.objectiveY.add(1500.5f);
        bi.objectiveTypes.add("sotf_objective_empl_t2"); bi.objectiveX.add(0f); bi.objectiveY.add(-2500f);
        BattleInfo bg = BattleInfo.decode(bi.encode());
        check("the host's objectives travel with the battlefield, mod kinds included", bg.objectiveTypes.size() == 2
                && bg.objectiveTypes.get(1).equals("sotf_objective_empl_t2") && bg.objectiveX.get(0) == -3000f && bg.objectiveY.get(0) == 1500.5f);
        BattleInfo badObj = new BattleInfo(); badObj.objectiveTypes.add("nav_buoy"); badObj.objectiveX.add(Float.NaN); badObj.objectiveY.add(0f);
        boolean objRefused = false; try { BattleInfo.decode(badObj.encode()); } catch (java.io.IOException e) { objRefused = true; }
        check("an objective at an impossible place is refused", objRefused);
        BattleInfo camp = new BattleInfo(); camp.hasCampaign = true; camp.credits = 123456789f; camp.storyPoints = 7; camp.playerLevel = 15;
        camp.playerFirst = "Ada"; camp.playerLast = "Lovelace"; camp.playerPortrait = "graphics/portraits/x.png";
        camp.skillIds.add("helmsmanship"); camp.skillLevels.add(2f); camp.cargoIds.add("alpha_core"); camp.cargoAmounts.add(3f);
        camp.addFlag("$sotf_metSierra", true); camp.addFlag("$someCount", 42); camp.addFlag("$someName", "Ludd"); camp.addFlag("$tooBig", new int[3]);
        BattleInfo cb = BattleInfo.decode(camp.encode());
        check("the host's campaign facts travel for the joiner's stand-in (credits, skills, cargo, flags)", cb.hasCampaign && cb.credits == 123456789f && cb.storyPoints == 7
                && cb.playerLevel == 15 && cb.playerLast.equals("Lovelace") && cb.skillIds.get(0).equals("helmsmanship") && cb.cargoAmounts.get(0) == 3f
                && cb.flagKeys.size() == 3 && Boolean.TRUE.equals(cb.flagValue(0)) && ((Double) cb.flagValue(1)) == 42.0 && "Ludd".equals(cb.flagValue(2)));
        BattleInfo badCamp = new BattleInfo(); badCamp.hasCampaign = true; badCamp.cargoIds.add("x"); badCamp.cargoAmounts.add(Float.NaN);
        boolean campRefused = false; try { BattleInfo.decode(badCamp.encode()); } catch (java.io.IOException e) { campRefused = true; }
        check("impossible campaign values are refused", campRefused);
        check("battlefield round trip", bg.width == 18000f && bg.height == 14000f && bg.background.endsWith("background4.jpg") && bg.hasGlowColor
                && bg.glowColor == 0x50FF8040 && !bg.hasBackgroundColor && !bg.renderStarfield && bg.hyperspace && bg.hasPlanet
                && bg.planetType.equals("terran") && bg.planetRadius == 312.5f && bg.planetYOffset == -512f && bg.planetBgWidth == 1400f);

        // armor / EMP state round trip
        DamageState ds = new DamageState(); DamageState.Ship dsh = new DamageState.Ship(); dsh.netId = 9; dsh.weaponsDisabled = 0x8000000000000005L;
        dsh.enginesDisabled = 0x80000002; dsh.ammo = new short[] {12, -1, 0, 3000}; dsh.flamedOut = true; dsh.gridWidth = 12; dsh.gridHeight = 20; dsh.armor = new byte[240]; dsh.armor[239] = (byte) 255; dsh.armor[7] = 100;
        ds.ships.add(dsh);
        DamageState.Ship dg = DamageState.decode(ds.encode()).ships.get(0);
        check("armor/EMP round trip (" + ds.encode().length + " bytes for one ship)", dg.netId == 9 && dg.weaponsDisabled == 0x8000000000000005L && dg.enginesDisabled == 0x80000002
                && dg.flamedOut && dg.ammo.length == 4 && dg.ammo[0] == 12 && dg.ammo[1] == -1 && dg.ammo[3] == 3000 && dg.gridWidth == 12 && dg.gridHeight == 20 && (dg.armor[239] & 0xFF) == 255 && dg.armor[7] == 100);
        // armor sent as changes only: two cells differ from what was sent before
        int fullBytes = ds.encode().length;
        DamageState.Ship next = DamageState.decode(ds.encode()).ships.get(0);
        next.armor[7] = 40; next.armor[100] = 3;
        boolean changedArmor = next.armorAgainst(dsh);
        DamageState d2 = new DamageState(); d2.ships.add(next);
        int cellBytes = d2.encode().length;
        DamageState.Ship got2 = DamageState.decode(d2.encode()).ships.get(0);
        boolean merged = got2.mergeOnto(dg);
        check("armor changes only (" + cellBytes + " bytes instead of " + fullBytes + ")", changedArmor && next.armorMode == DamageState.ARMOR_CELLS && cellBytes < fullBytes / 4
                && got2.armorMode == DamageState.ARMOR_CELLS && got2.cells.length == 2 && merged && java.util.Arrays.equals(got2.armor, next.armor) && got2.gridWidth == 12 && got2.gridHeight == 20);
        DamageState.Ship unchanged = DamageState.decode(ds.encode()).ships.get(0);
        unchanged.weaponsDisabled = 1L;
        boolean armorSame = !unchanged.armorAgainst(dsh);
        DamageState d3 = new DamageState(); d3.ships.add(unchanged);
        DamageState.Ship got3 = DamageState.decode(d3.encode()).ships.get(0);
        check("armor left out when unchanged (" + d3.encode().length + " bytes)", armorSame && got3.armorMode == DamageState.ARMOR_SAME && got3.weaponsDisabled == 1L
                && !unchanged.sameSystems(dsh) && got3.mergeOnto(dg) && java.util.Arrays.equals(got3.armor, dg.armor));
        DamageState.Ship lots = DamageState.decode(ds.encode()).ships.get(0);
        for (int i = 0; i < 120; i++) lots.armor[i] = 9;
        DamageState.Ship orphan = DamageState.decode(d2.encode()).ships.get(0);
        check("armor: whole grid when most changed, and changes with nothing to build on are set aside", lots.armorAgainst(dsh) && lots.armorMode == DamageState.ARMOR_FULL
                && !orphan.mergeOnto(null) && orphan.gridWidth == 0 && orphan.armor.length == 0);

        // fleet orders round trip
        Orders od = new Orders(); od.objectiveOwners = new int[] {0, 1, 100}; od.fullAssault = true;
        Orders.Assignment oa = new Orders.Assignment(); oa.type = "CAPTURE"; oa.targetKind = Orders.TARGET_OBJECTIVE; oa.targetId = 2; oa.members.add(7); oa.members.add(9); od.assignments.add(oa);
        Orders.Assignment ob = new Orders.Assignment(); ob.type = "RALLY_TASK_FORCE"; ob.targetKind = Orders.TARGET_POINT; ob.x = 1234.5f; ob.y = -777f; od.assignments.add(ob);
        Orders.ShipOrder os = new Orders.ShipOrder(); os.ship = 12; os.type = "RETREAT"; os.direct = true; od.shipOrders.add(os);
        Orders og = Orders.decode(od.encode());
        Orders.Assignment near = new Orders.Assignment(); near.type = "RALLY_TASK_FORCE"; near.targetKind = Orders.TARGET_POINT; near.x = 1236f; near.y = -775f;
        check("orders round trip: " + og.assignments.get(0).key() + " / " + og.assignments.get(1).key(), og.objectiveOwners[2] == 100 && og.shipOrders.size() == 1 && og.shipOrders.get(0).ship == 12 && og.shipOrders.get(0).type.equals("RETREAT") && og.shipOrders.get(0).direct && og.fullAssault && !og.fullRetreat
                && og.assignments.size() == 2 && og.assignments.get(0).members.get(1) == 9 && og.assignments.get(1).x == 1234.5f
                && og.assignments.get(1).key().equals(near.key()) && !og.assignments.get(0).key().equals(og.assignments.get(1).key()));

        // new battle -> everyone told to reset
        host.beginBattle();
        check("new battle sends reset and clears ready", waitFor(clients.get(2), Msg.RESET, host) != null && host.getPlayers().get(2).needsSync && !host.getPlayers().get(1).ready);

        // password, game version, kick, keep-alive
        host.setAccess("hunter2", "Starsector 0.98a-RC8");
        check("no password given: refused and told to type one", rejectText(join("Q1", port, MODS, "Starsector 0.98a-RC8", ""), host).contains("needs a password"));
        check("wrong password: refused", rejectText(join("Q2", port, MODS, "Starsector 0.98a-RC8", "letmein"), host).contains("Wrong password"));
        check("different game version: refused with both versions named", rejectText(join("Q3", port, MODS, "Starsector 0.98a-RC7", "hunter2"), host).contains("RC7"));
        int before = host.getPlayers().size();
        host.getOrStart(port, 8, 20, "Host", MODS, HostSession.MOD_CHECK_OFF);
        Conn q4 = join("Q4", port, MODS, "Starsector 0.98a-RC8", "hunter2");
        check("right password and version: let in", waitFor(q4, Msg.WELCOME, host) != null && host.getPlayers().size() == before + 1);
        HostSession.Player kicked = host.getPlayers().get(host.getPlayers().size() - 1);
        host.kick(kicked, "The host removed you from this game.");
        check("kicked player is told why", rejectText(q4, host).contains("removed"));
        end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end && host.getPlayers().size() > before) { host.pump(); Thread.sleep(10); }
        host.drainDeparted();
        check("kicked player is gone and cannot come straight back", host.getPlayers().size() == before
                && rejectText(join("Q4", port, MODS, "Starsector 0.98a-RC8", "hunter2"), host).contains("removed"));
        host.setAccess("", null);
        Thread.sleep(2600); // nobody says anything: the links must still look alive, thanks to the keep-alives
        host.pump();
        check("quiet links stay alive (keep-alive every second)", clients.get(1).idleMillis() < 2000 && host.getPlayers().get(1).conn.idleMillis() < 2000);
        boolean sawPing = false;
        for (Conn.Packet pk = clients.get(1).poll(); pk != null; pk = clients.get(1).poll()) if (pk.type == Msg.PING) sawPing = true;
        check("keep-alives never reach the game", !sawPing);

        // a burst of small messages arrives whole and in order (they share writes), and a reader that falls
        // far behind is told how many it lost
        Conn burstTo = clients.get(2);
        while (burstTo.poll() != null) { /* clear */ }
        HostSession.Player burstFrom = host.getPlayers().get(2);
        for (int i = 0; i < 3000; i++) burstFrom.conn.send(Msg.NOTICE, Msg.twoInts(i, i * 7));
        int burstGot = 0; boolean ordered = true;
        end = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < end && burstGot < 3000) {
            Conn.Packet pk = burstTo.poll();
            if (pk == null) { Thread.sleep(2); continue; }
            if (pk.type != Msg.NOTICE) continue;
            wingman.net.In burstIn = Msg.reader(pk.data);
            if (burstIn.readInt() != burstGot || burstIn.readInt() != burstGot * 7) ordered = false;
            burstGot++;
        }
        check("burst of 3000 small messages arrives complete and in order", burstGot == 3000 && ordered && burstTo.takeDropped() == 0);
        for (int i = 0; i < 6000; i++) burstFrom.conn.send(Msg.NOTICE, Msg.twoInts(i, 0));
        end = System.currentTimeMillis() + 4000;
        int lost = 0;
        while (System.currentTimeMillis() < end && lost == 0) { Thread.sleep(50); lost = burstTo.takeDropped(); }
        Thread.sleep(300);
        lost += burstTo.takeDropped();
        check("a reader that fell behind is told messages were lost (" + lost + ")", lost > 0);
        while (burstTo.poll() != null) { /* clear */ }
        InputState wild = new InputState(); wild.flags = InputState.ACCEL; wild.group = 99; wild.facing = -725f; wild.mouseX = 9.0e6f;
        InputState tamed = new InputState(); tamed.decode(wild.encode());
        check("out-of-range controls are brought into range", tamed.group == -1 && Math.abs(tamed.facing - 355f) < 0.01f && tamed.mouseX == 200000f && tamed.has(InputState.ACCEL));

        // disconnect is noticed, slot is freed
        clients.get(0).close("test");
        end = System.currentTimeMillis() + 3000; List<HostSession.Player> gone = new ArrayList<HostSession.Player>();
        while (System.currentTimeMillis() < end && gone.isEmpty()) { host.pump(); gone.addAll(host.drainDeparted()); Thread.sleep(10); }
        check("disconnect detected", gone.size() == 1 && gone.get(0).name.equals("P1") && host.getPlayers().size() == 3);

        // client link helper against a dead port fails cleanly
        ClientLink link = ClientLink.get();
        link.connect("127.0.0.1", 24999, "nobody", MODS);
        end = System.currentTimeMillis() + 7000;
        while (System.currentTimeMillis() < end && link.getState() == ClientLink.State.CONNECTING) Thread.sleep(20);
        check("connecting to a closed port fails cleanly: " + link.getError(), link.getState() == ClientLink.State.FAILED);
        link.connect("127.0.0.1", port, "Linker", MODS);
        end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end && link.getState() != ClientLink.State.CONNECTED) Thread.sleep(20);
        check("client link connects and is welcomed", link.getState() == ClientLink.State.CONNECTED && waitFor(link.getConn(), Msg.WELCOME, host) != null);

        // a dropped player coming back before the host has cleared the old link away takes its place quietly
        host.drainDeparted();
        int playersBefore = host.getPlayers().size();
        link.getConn().close("cable pulled");
        Thread.sleep(300);
        Conn again = join("linker", port);
        Thread.sleep(300);
        List<String> said = host.pump();
        List<HostSession.Player> swapped = host.drainDeparted();
        boolean toldBack = false;
        for (String line : said) if (line.contains("reconnected")) toldBack = true;
        check("reconnect replaces the dead link: " + said, swapped.size() == 1 && swapped.get(0).replaced && swapped.get(0).name.equals("Linker")
                && toldBack && host.getPlayers().size() == playersBefore && waitFor(again, Msg.WELCOME, host) != null);
        Conn twin = join("P2", port); // same name as a player whose link is alive: just another player
        check("a second player with a live player's name is not mistaken for a reconnect", waitFor(twin, Msg.WELCOME, host) != null
                && host.getPlayers().size() == playersBefore + 1 && host.drainDeparted().isEmpty());

        System.out.println("      network: " + Transport.describe());
        String traffic = NetStats.report(0f);
        System.out.println("      traffic: " + traffic);
        check("traffic meter counted both directions", traffic != null && traffic.contains("sent") && traffic.contains("position updates"));
        HostSession.stopActive();
        Thread.sleep(300);
        check("host shutdown closes clients", link.getState() == ClientLink.State.FAILED);
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails);
    }
}
