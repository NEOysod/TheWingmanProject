import java.io.*;
import java.net.*;
import java.util.*;
import wingman.net.*;

/**
 * Hostile-input test: a fake player (and a fake host) that sends junk, oversized frames, compression bombs,
 * broken numbers and floods, against the real network code. Checks that nothing crashes, memory and threads
 * stay bounded, and an honest player keeps being served throughout.
 *
 * Run: java -Xmx512m -cp jars/Wingman.jar:test AttackTest
 */
public class AttackTest {
    static int fails = 0;
    static final List<String> findings = new ArrayList<String>();
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) { fails++; findings.add(what); } }
    static void info(String s) { System.out.println("      " + s); }

    static final int PORT = 24950;
    static ModList MODS = new ModList();
    static HostSession host;
    static Conn good;
    static final Random R = new Random(12345);

    // ---------- helpers ----------

    static byte[] frame(int type, byte[] payload) {
        byte[] b = new byte[5 + payload.length];
        int len = payload.length + 1;
        b[0] = (byte) (len >>> 24); b[1] = (byte) (len >>> 16); b[2] = (byte) (len >>> 8); b[3] = (byte) len; b[4] = (byte) type;
        System.arraycopy(payload, 0, b, 5, payload.length);
        return b;
    }
    static byte[] hello(String name, String password) throws IOException {
        Msg.W w = new Msg.W(); w.out.writeInt(Msg.PROTOCOL_VERSION); w.out.writeUTF(name); MODS.write(w.out);
        w.out.writeUTF(""); w.out.writeUTF(password);
        return w.done();
    }
    static Socket raw() throws IOException { Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000); s.setSoTimeout(3000); return s; }
    static Conn join(String name, String password) throws Exception {
        Conn c = Conn.open("127.0.0.1", PORT);
        c.send(Msg.HELLO, hello(name, password));
        return c;
    }
    static Conn.Packet waitFor(Conn c, byte type, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            host.pump();
            Conn.Packet p = c.poll();
            if (p != null && p.type == type) return p;
            if (p == null) Thread.sleep(3);
        }
        return null;
    }
    static void pumpFor(long ms) throws Exception { long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { host.pump(); Thread.sleep(5); } }
    static HostSession.Player find(String name) { for (HostSession.Player p : host.getPlayers()) if (p.name.equals(name)) return p; return null; }
    static int threads() { return Thread.getAllStackTraces().size(); }
    static long usedMb() { Runtime r = Runtime.getRuntime(); return (r.totalMemory() - r.freeMemory()) >> 20; }

    /** The honest player still gets what the host sends, and the host still answers. */
    static boolean goodStillServed() throws Exception {
        if (good.isClosed()) return false;
        HostSession.Player gp = find("Good");
        if (gp == null) return false;
        gp.needsSync = false;
        Lobby lob = new Lobby(); lob.hostName = "Host";
        byte[] mark = lob.encode();
        host.broadcast(Msg.LOBBY, mark);
        return waitFor(good, Msg.LOBBY, 3000) != null;
    }

    static boolean closedWithin(Socket s, long ms) {
        try {
            s.setSoTimeout((int) ms);
            InputStream in = s.getInputStream();
            long end = System.currentTimeMillis() + ms;
            byte[] b = new byte[4096];
            while (System.currentTimeMillis() < end) {
                host.pump();
                try { if (in.read(b) < 0) return true; } catch (SocketTimeoutException e) { /* keep going */ }
                s.setSoTimeout(20);
            }
            return false;
        } catch (IOException e) { return true; }
    }

    // ---------- main ----------

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("channels")) Transport.force(Transport.CHANNELS);
        MODS.add("lw_lazylib", "LazyLib", "3.0", true);
        MODS.add("nexerelin", "Nexerelin", "0.12.1b", false);
        host = HostSession.getOrStart(PORT, 4, 20, "Host", MODS, HostSession.MOD_CHECK_CONTENT);
        host.beginBattle();
        good = join("Good", "");
        check("honest player joins", waitFor(good, Msg.WELCOME, 3000) != null);
        int baseThreads = threads();
        info("threads at start: " + baseThreads + ", heap used " + usedMb() + " MB");

        rawJunk();
        frameSizes();
        compressionBomb();
        compressionBombAfterJoin();
        helloFuzz();
        nameSpoofing();
        passwordGuessing();
        connectionFlood(baseThreads);
        afterJoinJunk();
        messageFlood();
        udpJunk();
        decoderFuzz();
        badNumbers();

        System.gc(); Thread.sleep(300);
        info("threads at end: " + threads() + ", heap used " + usedMb() + " MB");
        System.out.println();
        System.out.println(fails == 0 ? "ATTACK TEST: ALL PASSED" : "ATTACK TEST: " + fails + " FAILED");
        for (String f : findings) System.out.println("  - " + f);
        System.exit(fails == 0 ? 0 : 1);
    }

    // 1. Random bytes straight at the port, before any hello.
    static void rawJunk() throws Exception {
        int closed = 0, n = 40;
        for (int i = 0; i < n; i++) {
            Socket s = raw();
            byte[] junk = new byte[1 + R.nextInt(3000)]; R.nextBytes(junk);
            s.getOutputStream().write(junk); s.getOutputStream().flush();
            if (closedWithin(s, 6000)) closed++;
            s.close();
        }
        check("random bytes before hello: every connection is closed (" + closed + "/" + n + ")", closed == n);
        check("...and the honest player is still served", goodStillServed());
    }

    // 2. Frame lengths that are zero, negative, enormous, or just over the limit for strangers.
    static void frameSizes() throws Exception {
        int[] lens = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 256 * 1024 + 2, 4 * 1024 * 1024 + 1};
        boolean all = true; long before = usedMb();
        for (int len : lens) {
            Socket s = raw();
            byte[] h = {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len, Msg.HELLO};
            s.getOutputStream().write(h); s.getOutputStream().flush();
            boolean c = closedWithin(s, 3000);
            if (!c) { all = false; info("length " + len + " not refused"); }
            s.close();
        }
        check("bad frame lengths (0, negative, 2 GB, just over the stranger limit) are refused at once", all);
        // A frame that announces 200 KB and then never sends it: must time out, not hold a slot for ever.
        Socket s = raw();
        byte[] h = frame(Msg.HELLO, new byte[200 * 1024]);
        s.getOutputStream().write(h, 0, 5 + 1000); s.getOutputStream().flush();
        boolean c = closedWithin(s, 9000);
        check("a half-sent hello (slow-drip) is cut off by the handshake timeout", c);
        s.close();
        check("memory did not jump from announced sizes (" + before + " -> " + usedMb() + " MB)", usedMb() - before < 64);
    }

    // 3. Switch compression on, then send a stream that inflates to gigabytes.
    static void compressionBomb() throws Exception {
        Socket s = raw();
        OutputStream out = s.getOutputStream();
        out.write(frame(Msg.ZIP_ON, new byte[0]));
        java.util.zip.Deflater d = new java.util.zip.Deflater(9);
        // frames of 250 KB of zeros, each claiming to be a hello: compresses about 1000:1
        byte[] one = frame(Msg.HELLO, new byte[250 * 1024]);
        byte[] buf = new byte[64 * 1024];
        long sentWire = 0, inflated = 0; long before = usedMb(); long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < 4000 && !s.isClosed(); i++) { // up to 1 GB inflated
                d.setInput(one); inflated += one.length;
                int n;
                while ((n = d.deflate(buf, 0, buf.length, java.util.zip.Deflater.SYNC_FLUSH)) > 0) { out.write(buf, 0, n); sentWire += n; }
                if (i % 50 == 0) host.pump();
            }
            out.flush();
        } catch (IOException e) { /* the host hung up: that's the point */ }
        boolean c = closedWithin(s, 6000);
        s.close();
        info("bomb: " + (sentWire >> 10) + " KB on the wire, up to " + (inflated >> 20) + " MB once inflated; heap " + before + " -> " + usedMb() + " MB, " + (System.currentTimeMillis() - t0) + " ms");
        check("compression switched on by a stranger before hello: refused, memory bounded", c && usedMb() - before < 128);
        check("...and the honest player is still served", goodStillServed());
    }

    // 3b. Same bomb from a player who was let in properly (so compression is allowed).
    static void compressionBombAfterJoin() throws Exception {
        Socket s = raw();
        OutputStream out = s.getOutputStream(); DataInputStream in = new DataInputStream(s.getInputStream());
        out.write(frame(Msg.HELLO, hello("Bomber", ""))); out.flush();
        boolean invited = false;
        final boolean[] stopPump = {false};
        Thread pumper = new Thread(new Runnable() { public void run() { while (!stopPump[0]) { host.pump(); try { Thread.sleep(5); } catch (InterruptedException e) { return; } } } });
        pumper.setDaemon(true); pumper.start();
        s.setSoTimeout(4000);
        try {
            for (int k = 0; k < 50 && !invited; k++) { int len = in.readInt(); int type = in.readByte(); byte[] d = new byte[len - 1]; in.readFully(d); if (type == Msg.ZIP_OK) invited = true; }
        } catch (IOException e) { info("reading host: " + e); }
        stopPump[0] = true; pumper.join();
        check("bomber gets in and is offered compression", invited);
        out.write(frame(Msg.ZIP_ON, new byte[0]));
        java.util.zip.Deflater d = new java.util.zip.Deflater(9);
        byte[] one = frame(Msg.INPUT, new byte[250 * 1024]);
        byte[] buf = new byte[64 * 1024];
        long sentWire = 0, inflated = 0; long before = usedMb(); long t0 = System.currentTimeMillis(); String stop = "sent it all";
        try {
            for (int i = 0; i < 4000; i++) { // up to 1 GB once inflated
                d.setInput(one); inflated += one.length;
                int n;
                while ((n = d.deflate(buf, 0, buf.length, java.util.zip.Deflater.SYNC_FLUSH)) > 0) { out.write(buf, 0, n); sentWire += n; }
                if (i % 20 == 0) host.pump();
            }
            out.flush();
        } catch (IOException e) { stop = "host hung up (" + e.getMessage() + ")"; }
        boolean c = closedWithin(s, 6000);
        s.close();
        info("bomb after joining: " + (sentWire >> 10) + " KB on the wire = " + (inflated >> 20) + " MB inflated before stopping; " + stop + "; heap " + before + " -> " + usedMb() + " MB, " + (System.currentTimeMillis() - t0) + " ms");
        check("compression bomb from a player: cut off, memory bounded", c && usedMb() - before < 128 && find("Bomber") == null);
        check("...and the honest player is still served", goodStillServed());
    }

    // 4. Hellos that are cut short, have random tails, huge mod lists, or nonsense versions.
    static void helloFuzz() throws Exception {
        byte[] valid = hello("Fuzz", "");
        int n = 150, players = host.getPlayers().size();
        List<Socket> socks = new ArrayList<Socket>();
        for (int i = 0; i < n; i++) {
            byte[] p;
            switch (i % 5) {
                case 0: p = Arrays.copyOf(valid, R.nextInt(valid.length)); break;                // cut short
                case 1: p = valid.clone(); for (int k = 0; k < 6; k++) p[R.nextInt(p.length)] = (byte) R.nextInt(); break; // flipped
                case 2: p = new byte[R.nextInt(2000)]; R.nextBytes(p); break;                       // random
                case 3: { Msg.W w = new Msg.W(); w.out.writeInt(Msg.PROTOCOL_VERSION); w.out.writeUTF("Mods"); w.out.writeShort(65535); p = w.done(); break; } // 65535 mods, none there
                default: { Msg.W w = new Msg.W(); w.out.writeInt(Msg.PROTOCOL_VERSION); w.out.writeShort(65535); p = w.done(); break; } // name says 64K chars
            }
            Socket s = raw(); s.getOutputStream().write(frame(Msg.HELLO, p)); s.getOutputStream().flush(); socks.add(s);
            if (i % 10 == 0) host.pump();
        }
        pumpFor(1500);
        int closed = 0; for (Socket s : socks) { if (closedWithin(s, 50)) closed++; s.close(); }
        info(closed + "/" + n + " broken hellos closed; players before " + players + ", after " + host.getPlayers().size());
        check("broken hellos never become players", host.getPlayers().size() == players);
        check("...and the honest player is still served", goodStillServed());
    }

    // 5. Names built to confuse: very long, with line breaks pretending to be the host, control characters.
    static void nameSpoofing() throws Exception {
        StringBuilder longName = new StringBuilder(); for (int i = 0; i < 20000; i++) longName.append('W');
        String[] names = {longName.toString(), "Bob\nHost: everyone disconnect now", "\u0000\u0007\u001b[31mred", "   ", "Good"};
        boolean longCut = true, breaksGone = true, ctrlGone = true;
        for (String nm : names) {
            Conn c = join(nm, "");
            Conn.Packet w = waitFor(c, Msg.WELCOME, 2000);
            HostSession.Player newest = null;
            for (HostSession.Player p : host.getPlayers()) if (p.conn == null || newest == null || p.id > newest.id) newest = p;
            if (w != null && newest != null) {
                String got = newest.name;
                if (got.length() > 40) longCut = false;
                if (got.indexOf('\n') >= 0 || got.indexOf('\r') >= 0) breaksGone = false;
                for (char ch : got.toCharArray()) if (ch < 32 || ch == 127) ctrlGone = false;
                info("name sent " + nm.length() + " chars -> shown as \"" + (got.length() > 50 ? got.substring(0, 50) + "...(" + got.length() + ")" : got.replace("\n", "\\n").replace("\u001b", "\\e").replace("\u0000", "\\0").replace("\u0007", "\\a")) + "\"");
            }
            c.send(Msg.BYE, Msg.EMPTY); pumpFor(200); c.close("test"); pumpFor(100);
        }
        // A second "Good" from the same PC while the real one is active must not replace it.
        check("a player named the same as one in the game does not push them out", find("Good") != null && !good.isClosed());
        check("very long names are cut short", longCut);
        check("line breaks in names are removed (no fake 'Host:' lines)", breaksGone);
        check("control characters in names are removed", ctrlGone);
        pumpFor(300);
    }

    // 6. Guessing the password.
    static void passwordGuessing() throws Exception {
        host.setAccess("hunter2-real", null);
        long t0 = System.currentTimeMillis(); int tries = 0, rejected = 0, lockedOut = 0;
        while (System.currentTimeMillis() - t0 < 3000) {
            Conn c = join("Guesser", "guess" + tries);
            tries++;
            Conn.Packet p = waitFor(c, Msg.REJECT, 1500);
            String why = p == null ? "" : Msg.readUtf(p.data);
            if (why.startsWith("Wrong password")) rejected++;
            if (why.startsWith("Too many")) lockedOut++;
            c.close("test");
        }
        double perSec = rejected / ((System.currentTimeMillis() - t0) / 1000.0);
        info(tries + " attempts in 3 s: " + rejected + " passwords actually checked, " + lockedOut + " turned away to wait (" + String.format("%.1f", perSec) + " guesses/second)");
        Conn right = join("Guesser", "hunter2-real");
        Conn.Packet rp = waitFor(right, Msg.WELCOME, 3000), rr = rp == null ? null : null;
        boolean gotIn = rp != null;
        check("password guessing is slowed down (" + Math.round(perSec) + "/s; under 5/s from one PC passes)", perSec < 5);
        info("correct password after the guessing " + (gotIn ? "let in" : "not let in (locked out)"));
        if (gotIn) { right.send(Msg.BYE, Msg.EMPTY); pumpFor(200); }
        right.close("test");
        host.setAccess("", null);
        pumpFor(300);
        check("...and the honest player is still served", goodStillServed());
    }

    // 7. Opening connections as fast as possible and holding them open without saying hello.
    static void connectionFlood(int baseThreads) throws Exception {
        final List<Socket> socks = Collections.synchronizedList(new ArrayList<Socket>());
        final int want = 1500;
        Thread opener = new Thread(new Runnable() { public void run() {
            for (int i = 0; i < want; i++) { try { Socket s = new Socket(); s.bind(new InetSocketAddress("127.0.0.5", 0)); s.connect(new InetSocketAddress("127.0.0.1", PORT), 1000); socks.add(s); } catch (IOException e) { /* refused: fine */ } }
        }});
        long t0 = System.currentTimeMillis();
        opener.start();
        int peak = 0;
        while (opener.isAlive()) { host.pump(); peak = Math.max(peak, threads()); Thread.sleep(5); }
        for (int i = 0; i < 100; i++) { host.pump(); peak = Math.max(peak, threads()); Thread.sleep(5); }
        int stillOpen = 0; for (Socket s : socks) if (!closedWithin(s, 1)) stillOpen++;
        info("flood from another PC (127.0.0.5): " + stillOpen + " of them still held open by the host");
        info(socks.size() + " connections opened in " + (System.currentTimeMillis() - t0) + " ms; threads " + baseThreads + " -> peak " + peak + ", heap " + usedMb() + " MB");
        // Can an honest player still get in while the flood is held open?
        Conn late = join("Late", "");
        boolean lateIn = waitFor(late, Msg.WELCOME, 4000) != null;
        check("thread count stays bounded under a connection flood (peak " + peak + ", started at " + baseThreads + ")", peak - baseThreads < 200);
        check("an honest player can still join during the flood", lateIn);
        check("...and the one already in is still served", goodStillServed());
        pumpFor(6000); // past the handshake timeout
        int after = threads();
        info("after the 5 s handshake timeout: threads " + after);
        check("silent connections are cleared after the handshake timeout", after - baseThreads < 30);
        for (Socket s : socks) try { s.close(); } catch (IOException ignored) {}
        late.send(Msg.BYE, Msg.EMPTY); pumpFor(300); late.close("test");
    }

    // 8. A player who got in properly, then sends junk of every message type.
    static void afterJoinJunk() throws Exception {
        Conn evil = join("Evil", "");
        check("attacker joins as a normal player", waitFor(evil, Msg.WELCOME, 3000) != null);
        HostSession.Player ep = find("Evil");
        long t0 = System.currentTimeMillis(); int sent = 0; Throwable crash = null;
        try {
            for (int type = 0; type < 128 && !evil.isClosed(); type++) {
                if (type == Msg.BYE) continue;
                for (int k = 0; k < 2 && !evil.isClosed(); k++) {
                    byte[] junk = new byte[R.nextInt(k == 0 ? 12 : 600)]; R.nextBytes(junk);
                    evil.send((byte) type, junk); sent++;
                }
                Thread.sleep(8); // stay under the message-rate limit: this is about content, not speed
                host.pump();
            }
            // controls full of NaN and infinity
            Msg.W w = new Msg.W(); w.out.writeShort(0xFFFF); w.out.writeFloat(Float.NaN); w.out.writeFloat(Float.POSITIVE_INFINITY);
            w.out.writeByte(99); w.out.writeInt(Integer.MIN_VALUE); w.out.writeShort(0xFFFF); w.out.writeFloat(Float.NaN); w.out.writeByte(255); w.out.writeByte(255); w.out.writeShort(60000);
            evil.send(Msg.INPUT, w.done());
            // claims for ships that don't exist
            for (int id : new int[] {-1, 0, Integer.MAX_VALUE, Integer.MIN_VALUE}) { Msg.W cw = new Msg.W(); cw.out.writeInt(1); cw.out.writeInt(id); evil.send(Msg.CLAIM, cw.done()); }
            // ready with absurd counts
            Msg.W rw = new Msg.W(); rw.out.writeBoolean(true); rw.out.writeInt(-5); rw.out.writeInt(Integer.MAX_VALUE); evil.send(Msg.READY, rw.done());
            pumpFor(600);
        } catch (Throwable t) { crash = t; }
        info(sent + " junk messages of 127 types in " + (System.currentTimeMillis() - t0) + " ms; attacker " + (evil.isClosed() || find("Evil") == null ? "dropped" : "still connected") + (ep != null ? ", bad messages counted " + ep.badMessages : ""));
        check("host survives junk of every message type without an error escaping", crash == null);
        if (ep != null && find("Evil") != null) {
            check("controls with NaN/infinity are ignored (mouse " + ep.input.mouseX + "," + ep.input.mouseY + " facing " + ep.input.facing + ")",
                    !Float.isNaN(ep.input.mouseX) && !Float.isInfinite(ep.input.mouseX) && !Float.isNaN(ep.input.facing));
        }
        check("...and the honest player is still served", goodStillServed());
        evil.close("test"); pumpFor(300);
    }

    // 9. A player who floods messages as fast as the link takes them.
    static void messageFlood() throws Exception {
        Conn flood = join("Flood", "");
        check("flooder joins", waitFor(flood, Msg.WELCOME, 3000) != null);
        long t0 = System.currentTimeMillis(); int sent = 0;
        byte[] ctl = new InputState().encode();
        while (!flood.isClosed() && System.currentTimeMillis() - t0 < 3000) {
            for (int i = 0; i < 200; i++) { flood.send(Msg.INPUT, ctl); sent++; }
            host.pump(); Thread.sleep(1);
        }
        pumpFor(300);
        boolean cut = flood.isClosed() || find("Flood") == null;
        info(sent + " messages sent in " + (System.currentTimeMillis() - t0) + " ms; flooder " + (cut ? "disconnected" : "still connected"));
        check("a player flooding messages is disconnected", cut);
        check("...and the honest player is still served", goodStillServed());
        flood.close("test"); pumpFor(200);
    }

    // 10. Datagrams at the UDP port: random, too short, too long, and with guessed numbers.
    static void udpJunk() throws Exception {
        DatagramSocket ds = new DatagramSocket();
        InetSocketAddress to = new InetSocketAddress("127.0.0.1", PORT);
        int sent = 0;
        for (int i = 0; i < 20000; i++) {
            byte[] b = new byte[R.nextInt(i % 10 == 0 ? 1500 : 40)]; R.nextBytes(b);
            if (b.length > 8 && i % 3 == 0) b[8] = (byte) (1 + R.nextInt(3)); // a real kind with a wrong token
            ds.send(new DatagramPacket(b, b.length, to)); sent++;
            if (i % 500 == 0) host.pump();
        }
        ds.close();
        pumpFor(300);
        HostSession.Player gp = find("Good");
        check(sent + " junk datagrams: host keeps going and the honest player's controls are untouched", gp != null && !Float.isNaN(gp.input.mouseX) && gp.input.flags == 0);
        check("...and the honest player is still served", goodStillServed());
    }

    // 11. What a hostile HOST could send a joiner: every decoder fed mutated and random data.
    interface Dec { void run(byte[] b) throws Exception; }
    static void decoderFuzz() throws Exception {
        Map<String, byte[]> samples = new LinkedHashMap<String, byte[]>();
        final Map<String, Dec> decs = new LinkedHashMap<String, Dec>();
        Snapshot snap = new Snapshot(); snap.tick = 5; snap.piloted = new int[] {1}; snap.pilotedSeq = new int[] {1}; snap.pilotedAge = new float[] {0.1f}; snap.pilotedSysAmmo = new int[] {1};
        for (int i = 0; i < 8; i++) { Snapshot.ShipState s = new Snapshot.ShipState(); s.netId = i; s.x = i * 100; s.weaponAngles = new byte[6]; s.firing = 3; s.shots = new byte[] {0, 1}; snap.ships.add(s); }
        for (int i = 0; i < 8; i++) { Snapshot.MissileState m = new Snapshot.MissileState(); m.id = 100 + i; m.x = i; snap.missiles.add(m); }
        samples.put("Snapshot", snap.encode()); decs.put("Snapshot", new Dec() { public void run(byte[] b) throws Exception { Snapshot.decode(b); } });
        SnapshotCodec.Encoder en = new SnapshotCodec.Encoder(); snap.ships.get(0).shotTotals = new byte[] {1, 2};
        byte[] key = en.encode(snap, -1); snap.tick = 6; snap.ships.get(1).x += 50; byte[] delta = en.encode(snap, 5);
        samples.put("SnapshotCodec (key)", key); samples.put("SnapshotCodec (change)", delta);
        final byte[] keyF = key;
        Dec codec = new Dec() { public void run(byte[] b) throws Exception { SnapshotCodec.Decoder d = new SnapshotCodec.Decoder(); d.decode(keyF); d.decode(b); } };
        decs.put("SnapshotCodec (key)", codec); decs.put("SnapshotCodec (change)", codec);
        ShipSpawn sp = new ShipSpawn(); sp.netId = 7; sp.hullId = "onslaught"; sp.hullMods.add("heavyarmor"); sp.weaponSlots.add("WS 001"); sp.weaponIds.add("hellbore");
        sp.hasCaptain = true; sp.skillIds.add("helmsmanship"); sp.skillLevels.add(2f); sp.wings.add("talon_wing"); ShipSpawn.Group g = new ShipSpawn.Group(); g.type = "LINKED"; g.slots.add("WS 001"); sp.groups.add(g);
        samples.put("ShipSpawn", sp.encode()); decs.put("ShipSpawn", new Dec() { public void run(byte[] b) throws Exception { ShipSpawn.decode(b); } });
        Lobby lob = new Lobby(); Lobby.Entry le = new Lobby.Entry(); le.name = "P"; lob.players.add(le);
        samples.put("Lobby", lob.encode()); decs.put("Lobby", new Dec() { public void run(byte[] b) throws Exception { Lobby.decode(b); } });
        BattleInfo bi = new BattleInfo(); bi.background = "x.jpg"; bi.hasPlanet = true; bi.planetType = "terran"; bi.objectiveTypes.add("sotf_objective_reinf"); bi.objectiveX.add(10f); bi.objectiveY.add(20f); bi.hasCampaign = true; bi.credits = 5f; bi.skillIds.add("s"); bi.skillLevels.add(1f); bi.cargoIds.add("c"); bi.cargoAmounts.add(2f); bi.addFlag("$f", 3); bi.addFlag("$g", "t");
        samples.put("BattleInfo", bi.encode()); decs.put("BattleInfo", new Dec() { public void run(byte[] b) throws Exception { BattleInfo.decode(b); } });
        DamageState dst = new DamageState(); DamageState.Ship dsh = new DamageState.Ship(); dsh.netId = 1; dsh.ammo = new short[] {1, 2}; dsh.gridWidth = 4; dsh.gridHeight = 5; dsh.armor = new byte[20]; dst.ships.add(dsh);
        samples.put("DamageState", dst.encode()); decs.put("DamageState", new Dec() { public void run(byte[] b) throws Exception { DamageState.decode(b); } });
        List<Extras.Hurt> hurts = new ArrayList<Extras.Hurt>(); Extras.Hurt hh = new Extras.Hurt(); hh.target = 1; hh.amount = 5; hurts.add(hh);
        samples.put("Hurts", Extras.encodeHurts(hurts)); decs.put("Hurts", new Dec() { public void run(byte[] b) throws Exception { Extras.decodeHurts(b); } });
        List<Extras.Stats> stats = new ArrayList<Extras.Stats>(); Extras.Stats st = new Extras.Stats(); st.ship = 1; st.mask = 5; st.values = new float[] {1, 2}; stats.add(st);
        samples.put("Stats", Extras.encodeStats(stats)); decs.put("Stats", new Dec() { public void run(byte[] b) throws Exception { Extras.decodeStats(b); } });
        List<Extras.Note> notes = new ArrayList<Extras.Note>(); Extras.Note nn = new Extras.Note(); nn.key = "k"; nn.text = "t"; notes.add(nn);
        samples.put("Notes", Extras.encodeNotes(notes)); decs.put("Notes", new Dec() { public void run(byte[] b) throws Exception { Extras.decodeNotes(b); } });
        Orders ord = new Orders(); Orders.Assignment as = new Orders.Assignment(); as.type = "DEFEND"; as.members.add(3); ord.assignments.add(as);
        samples.put("Orders", ord.encode()); decs.put("Orders", new Dec() { public void run(byte[] b) throws Exception { Orders.decode(b); } });
        Msg.W mw = new Msg.W(); MODS.write(mw.out); samples.put("ModList", mw.done()); decs.put("ModList", new Dec() { public void run(byte[] b) throws Exception { ModList.read(Msg.reader(b)); } });
        samples.put("Controls", new InputState().encode()); decs.put("Controls", new Dec() { public void run(byte[] b) throws Exception { new InputState().decode(b); } });

        int perDecoder = 30000;
        for (Map.Entry<String, byte[]> e : samples.entrySet()) {
            String name = e.getKey(); byte[] base = e.getValue(); Dec dec = decs.get(name);
            Map<String, Integer> other = new TreeMap<String, Integer>();
            int ok = 0, refused = 0; long slowest = 0; boolean oom = false;
            for (int i = 0; i < perDecoder; i++) {
                byte[] b = mutate(base, i);
                long t = System.nanoTime();
                try { dec.run(b); ok++; }
                catch (IOException x) { refused++; }
                catch (OutOfMemoryError x) { oom = true; }
                catch (Throwable x) {
                    StackTraceElement top = x.getStackTrace().length > 0 ? x.getStackTrace()[0] : null;
                    String k = x.getClass().getSimpleName() + (top == null ? "" : " at " + top.getClassName().replace("wingman.net.", "") + ":" + top.getLineNumber());
                    Integer c = other.get(k); other.put(k, c == null ? 1 : c + 1);
                }
                slowest = Math.max(slowest, System.nanoTime() - t);
            }
            info(String.format("%-24s %5d read, %5d refused, slowest %.1f ms%s", name, ok, refused, slowest / 1e6, other.isEmpty() ? "" : ", other errors " + other));
            check(name + ": no out-of-memory and nothing slow (" + String.format("%.1f", slowest / 1e6) + " ms worst)", !oom && slowest < 200_000_000L);
            check(name + ": broken data only ever gives a clean 'refused', never another kind of error", other.isEmpty());
        }
    }
    static byte[] mutate(byte[] base, int i) {
        byte[] b;
        switch (i % 6) {
            case 0: b = Arrays.copyOf(base, R.nextInt(base.length + 1)); break;
            case 1: b = base.clone(); for (int k = 1 + R.nextInt(6); k > 0; k--) if (b.length > 0) b[R.nextInt(b.length)] ^= 1 << R.nextInt(8); break;
            case 2: b = base.clone(); if (b.length > 4) { int at = R.nextInt(b.length - 3); byte v = R.nextBoolean() ? (byte) 0xFF : (byte) 0x7F; for (int k = 0; k < 4; k++) b[at + k] = v; } break;
            case 3: b = new byte[R.nextInt(300)]; R.nextBytes(b); break;
            case 4: b = Arrays.copyOf(base, base.length + 1 + R.nextInt(64)); for (int k = base.length; k < b.length; k++) b[k] = (byte) R.nextInt(); break;
            default: b = base.clone(); if (b.length > 4) { int at = R.nextInt(b.length - 3); int nan = Float.floatToRawIntBits(R.nextBoolean() ? Float.NaN : Float.POSITIVE_INFINITY); b[at] = (byte) (nan >>> 24); b[at + 1] = (byte) (nan >>> 16); b[at + 2] = (byte) (nan >>> 8); b[at + 3] = (byte) nan; } break;
        }
        return b;
    }

    // 12. Broken numbers (NaN, infinity, absurd sizes) sent on purpose in each message's number fields.
    static boolean badF(float v) { return Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 1.0e7f; }
    static void badNumbers() throws Exception {
        float[] evil = {Float.NaN, Float.POSITIVE_INFINITY, -3.0e38f};
        List<String> through = new ArrayList<String>();
        for (float v : evil) {
            String tag = Float.isNaN(v) ? "NaN" : Float.isInfinite(v) ? "infinity" : "-3e38";
            // full snapshot form
            Snapshot s = new Snapshot(); Snapshot.ShipState sh = new Snapshot.ShipState(); sh.x = v; sh.y = v; sh.vx = v; sh.facing = v; sh.angVel = v; sh.shieldFacing = v; s.ships.add(sh);
            Snapshot.MissileState m = new Snapshot.MissileState(); m.x = v; m.vx = v; m.facing = v; s.missiles.add(m); s.hostTime = v;
            try {
                Snapshot g = Snapshot.decode(s.encode());
                if (!g.ships.isEmpty()) { Snapshot.ShipState q = g.ships.get(0); if (badF(q.x) || badF(q.y) || badF(q.vx) || badF(q.facing) || badF(q.angVel) || badF(q.shieldFacing)) through.add("Snapshot ship (" + tag + ")"); }
                if (!g.missiles.isEmpty()) { Snapshot.MissileState q = g.missiles.get(0); if (badF(q.x) || badF(q.vx) || badF(q.facing)) through.add("Snapshot missile (" + tag + ")"); }
                if (badF(g.hostTime)) through.add("Snapshot time (" + tag + ")");
            } catch (IOException e) { /* refused: fine */ }
            // compact form
            try {
                SnapshotCodec.Encoder en = new SnapshotCodec.Encoder(); Snapshot g = new SnapshotCodec.Decoder().decode(en.encode(s, -1));
                if (g != null && !g.ships.isEmpty() && (badF(g.ships.get(0).x) || badF(g.ships.get(0).facing))) through.add("SnapshotCodec (" + tag + ")");
            } catch (IOException e) { }
            // a ship to spawn
            ShipSpawn sp = new ShipSpawn(); sp.hullId = "x"; sp.x = v; sp.y = v; sp.facing = v; sp.cr = v; sp.maxFlux = v; sp.maxHull = v; sp.maxSpeed = v;
            try { ShipSpawn q = ShipSpawn.decode(sp.encode()); if (badF(q.x) || badF(q.y) || badF(q.facing) || badF(q.cr) || badF(q.maxFlux) || badF(q.maxHull) || badF(q.maxSpeed)) through.add("ShipSpawn (" + tag + ")"); } catch (IOException e) { }
            // battlefield
            BattleInfo bi = new BattleInfo(); bi.width = v; bi.height = v; bi.hasPlanet = true; bi.planetType = "x"; bi.planetRadius = v; bi.planetYOffset = v; bi.planetBgWidth = v; bi.planetBgHeight = v;
            try { BattleInfo q = BattleInfo.decode(bi.encode()); if (badF(q.width) || badF(q.height) || badF(q.planetRadius) || badF(q.planetYOffset) || badF(q.planetBgWidth)) through.add("BattleInfo (" + tag + ")"); } catch (IOException e) { }
            // fleet orders
            Orders o = new Orders(); Orders.Assignment as = new Orders.Assignment(); as.type = "DEFEND"; as.targetKind = Orders.TARGET_POINT; as.x = v; as.y = v; o.assignments.add(as);
            try { Orders q = Orders.decode(o.encode()); if (!q.assignments.isEmpty() && (badF(q.assignments.get(0).x) || badF(q.assignments.get(0).y))) through.add("Orders (" + tag + ")"); } catch (IOException e) { }
            // damage reports and stats
            List<Extras.Hurt> hl = new ArrayList<Extras.Hurt>(); Extras.Hurt h = new Extras.Hurt(); h.amount = v; h.emp = v; h.x = v; h.y = v; hl.add(h);
            try { List<Extras.Hurt> q = Extras.decodeHurts(Extras.encodeHurts(hl)); if (!q.isEmpty() && (badF(q.get(0).amount) || badF(q.get(0).emp) || badF(q.get(0).x))) through.add("Hurts (" + tag + ")"); } catch (IOException e) { }
            List<Extras.Stats> sl = new ArrayList<Extras.Stats>(); Extras.Stats st = new Extras.Stats(); st.mask = 3; st.values = new float[] {v, v}; sl.add(st);
            try { List<Extras.Stats> q = Extras.decodeStats(Extras.encodeStats(sl)); if (!q.isEmpty() && q.get(0).values.length > 0 && (Float.isNaN(q.get(0).values[0]) || Float.isInfinite(q.get(0).values[0]))) through.add("Stats (" + tag + ")"); /* large finite stats are allowed here (mods use them) and range-checked where applied */ } catch (IOException e) { }
        }
        if (!through.isEmpty()) info("broken numbers that get through: " + through);
        check("NaN / infinity / absurd numbers are refused or corrected in every message", through.isEmpty());
    }
}
