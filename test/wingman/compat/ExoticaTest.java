package wingman.compat;

import java.io.IOException;
import java.util.Arrays;

import wingman.compat.Exotica.Cargo;
import wingman.compat.Exotica.ModsData;
import wingman.compat.Exotica.Recorder;
import wingman.compat.Exotica.Snapshot;
import wingman.compat.Exotica.Step;
import wingman.net.Out;

/** The Exotica messages and the step recorder: plain data, no game. */
public final class ExoticaTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static boolean rejects(byte[] b, int which) {
        try {
            switch (which) {
                case 0: Exotica.decodeAsk(b); break;
                case 1: Exotica.decodeShip(b); break;
                case 2: Exotica.decodeSet(b); break;
                default: Exotica.decodeResult(b);
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static ModsData mods(float bw, String upgrades, String exotics) throws IOException {
        return Exotica.parseMods("{\"baseBandwidth\":" + bw + (upgrades == null ? "" : ",\"upgrades\":" + upgrades) + (exotics == null ? "" : ",\"exotics\":" + exotics) + "}");
    }

    private static Snapshot snap(ModsData m, float credits, int sp, Cargo... c) { return Exotica.Snapshot.of(m, credits, sp, c); }

    private static Cargo cargo(String commodity, float q) {
        Cargo c = new Cargo();
        c.addCommodity(commodity, q);
        return c;
    }

    private static Cargo chip(String item, String data, float q) {
        Cargo c = new Cargo();
        c.addSpecial(item, data, q);
        return c;
    }

    public static void main(String[] args) throws Exception {
        // ask / result
        check("m1".equals(Exotica.decodeAsk(Exotica.encodeAsk("m1"))), "ask round trip");
        Exotica.ResultMsg r = Exotica.decodeResult(Exotica.encodeResult(new Exotica.ResultMsg("m1", true, "ok")));
        check(r.ok && "ok".equals(r.text) && "m1".equals(r.memberId), "result round trip");
        byte[] ask = Exotica.encodeAsk("m1");
        ask[0] = 2;
        check(rejects(ask, 0), "bad version");
        check(rejects(Arrays.copyOf(Exotica.encodeAsk("m1"), 2), 0), "truncated");
        try {
            char[] big = new char[65];
            Arrays.fill(big, 'x');
            Exotica.encodeAsk(new String(big));
            check(false, "long key encoded");
        } catch (IOException e) { check(true, "long key refused"); }

        // ship
        Exotica.ShipMsg s = new Exotica.ShipMsg();
        s.memberId = "m1"; s.hullId = "hound"; s.variantJson = Exotica.variantJson(Arrays.asList("a"), Arrays.asList("p"), Arrays.asList("p"));
        s.modsJson = "{\"baseBandwidth\":100}"; s.credits = 1234.5f; s.storyPoints = 7; s.marketFactionId = "hegemony"; s.relToPlayer = -0.25f;
        s.industries.add("population");
        s.fleet.addCommodity("metals", 10f);
        s.fleet.addSpecial("et_upgrade", "key,2", 3f);
        s.storage.addCommodity("rare_metals", 5f);
        Exotica.ShipMsg d = Exotica.decodeShip(Exotica.encodeShip(s));
        check(d.credits == 1234.5f && d.storyPoints == 7 && d.relToPlayer == -0.25f && "hegemony".equals(d.marketFactionId), "ship scalars");
        check(d.industries.size() == 1 && d.fleet.commodities.get("metals") == 10f && d.fleet.specials.get(0).data.equals("key,2") && d.storage.commodities.size() == 1, "ship cargo");
        Exotica.VariantData vd = Exotica.parseVariant(d.hullId, d.variantJson);
        check(vd.hullmods.size() == 1 && vd.perma.contains("p") && vd.smods.contains("p"), "variant round trip");
        try { Exotica.parseVariant("hound", "{not json"); check(false, "bad variant"); } catch (IOException e) { check(true, "bad variant refused"); }
        s.credits = Float.NaN;
        try { Exotica.encodeShip(s); check(false, "nan encoded"); } catch (IOException e) { check(true, "nan refused"); }
        s.credits = 1f;
        s.modsJson = "";
        for (int i = 0; i < 600; i++) s.fleet.addSpecial("et_upgrade", "k" + i + ",1", 1f);
        try { Exotica.encodeShip(s); check(false, "too many specials"); } catch (IOException e) { check(true, "specials cap"); }
        s.fleet.specials.clear();
        char[] huge = new char[60000];
        Arrays.fill(huge, 'y');
        s.modsJson = new String(huge);
        try { Exotica.encodeShip(s); check(false, "ship too large encoded"); } catch (IOException e) { check(e.getMessage().contains("large"), "ship too large"); }
        Out o = new Out();
        o.writeByte(1);
        o.writeUTF("m"); o.writeUTF("h"); o.writeUTF(""); o.writeUTF(""); o.writeFloat(Float.NaN);
        check(rejects(o.toByteArray(), 1), "nan decoded");

        // set
        Exotica.SetMsg sm = new Exotica.SetMsg();
        sm.memberId = "m1";
        sm.steps.add(new Step(Exotica.BANDWIDTH, "", "bandwidth", 0, ""));
        sm.steps.add(new Step(Exotica.UPGRADE, "up", "chip", 3, ""));
        sm.steps.add(new Step(Exotica.EXOTIC, "ex", "chipInstall", 0, "CORRUPTED"));
        Exotica.SetMsg sd = Exotica.decodeSet(Exotica.encodeSet(sm));
        check(sd.steps.equals(sm.steps), "set round trip");
        for (int i = 0; i < 62; i++) sm.steps.add(new Step(Exotica.UPGRADE, "u" + i, "free", 0, ""));
        check(sm.steps.size() == 65, "setup");
        try { Exotica.encodeSet(sm); check(false, "65 steps encoded"); } catch (IOException e) { check(true, "step cap"); }
        o = new Out();
        o.writeByte(1); o.writeUTF("m"); o.writeVarInt(65);
        check(rejects(o.toByteArray(), 2), "n over cap decoded");
        o = new Out();
        o.writeByte(1); o.writeUTF("m"); o.writeVarInt(1); o.writeByte(9); o.writeUTF(""); o.writeUTF(""); o.writeInt(0); o.writeUTF("");
        check(rejects(o.toByteArray(), 2), "unknown kind");

        // mods json
        ModsData md = Exotica.parseMods("{\"baseBandwidth\":120}");
        check(md.baseBandwidth == 120f && md.upgrades.isEmpty() && md.exotics.isEmpty(), "omitted maps");
        md = Exotica.parseMods("{\"baseBandwidth\":1,\"upgrades\":{\"a\":2},\"exotics\":{\"x\":{\"key\":\"x\",\"type\":\"CORRUPTED\"},\"y\":\"y\"}}");
        check(md.upgrades.get("a") == 2 && "CORRUPTED".equals(md.exotics.get("x")) && "NORMAL".equals(md.exotics.get("y")), "exotic object and string");
        md = Exotica.parseMods("{\"exotics\":[\"p\",{\"key\":\"q\",\"type\":\"PURE\"}]}");
        check("NORMAL".equals(md.exotics.get("p")) && "PURE".equals(md.exotics.get("q")), "exotic array");
        check(Exotica.parseMods("").upgrades.isEmpty(), "empty mods");

        // diff table
        ModsData none = mods(100f, null, null);
        Snapshot base = snap(none, 1000f, 5, cargo("metals", 10f), chip("et_upgrade", "u,2", 1f));
        check(Exotica.diff(base, base) == null, "no change, no step");
        Step st = Exotica.diff(base, snap(mods(105f, null, null), 900f, 5, cargo("metals", 10f), chip("et_upgrade", "u,2", 1f)));
        check(st.kind == Exotica.BANDWIDTH, "bandwidth");
        st = Exotica.diff(base, snap(mods(100f, "{\"u\":2}", null), 1000f, 5, cargo("metals", 10f), new Cargo()));
        check(st.kind == Exotica.UPGRADE && "chip".equals(st.method) && st.level == 2 && "u".equals(st.target), "upgrade by chip");
        st = Exotica.diff(base, snap(mods(100f, "{\"u\":1}", null), 800f, 5, cargo("metals", 10f), chip("et_upgrade", "u,2", 1f)));
        check("credits".equals(st.method), "upgrade by credits");
        st = Exotica.diff(base, snap(mods(100f, "{\"u\":1}", null), 1000f, 5, cargo("metals", 4f), chip("et_upgrade", "u,2", 1f)));
        check("resources".equals(st.method), "upgrade by resources");
        st = Exotica.diff(base, snap(mods(100f, "{\"u\":1}", null), 1000f, 5, cargo("metals", 10f), chip("et_upgrade", "u,2", 1f)));
        check("free".equals(st.method), "upgrade free");
        Snapshot withUp = snap(mods(100f, "{\"u\":2}", null), 1000f, 5);
        st = Exotica.diff(withUp, snap(none, 1000f, 5, chip("et_upgrade", "u,2", 1f)));
        check(st.kind == Exotica.UPGRADE && "recover".equals(st.method), "upgrade recover");
        st = Exotica.diff(base, snap(mods(100f, null, "{\"x\":{\"key\":\"x\",\"type\":\"NORMAL\"}}"), 1000f, 5));
        check(st.kind == Exotica.EXOTIC && "install".equals(st.method), "exotic install");
        st = Exotica.diff(snap(none, 1000f, 5, chip("et_exotic", "x,NORMAL", 1f)), snap(mods(100f, null, "{\"x\":{\"key\":\"x\",\"type\":\"NORMAL\"}}"), 1000f, 5));
        check("chipInstall".equals(st.method), "exotic chip install");
        st = Exotica.diff(base, snap(mods(100f, null, "{\"x\":{\"key\":\"x\",\"type\":\"CORRUPTED\"}}"), 1000f, 5));
        check("chipInstall".equals(st.method) && "CORRUPTED".equals(st.exoticType), "typed exotic");
        Snapshot withEx = snap(mods(100f, null, "{\"x\":{\"key\":\"x\",\"type\":\"NORMAL\"}}"), 1000f, 5);
        st = Exotica.diff(withEx, snap(none, 1000f, 5, chip("et_exotic", "x,NORMAL", 1f)));
        check("recover".equals(st.method), "exotic recover by chip");
        st = Exotica.diff(withEx, snap(none, 1000f, 4));
        check("recover".equals(st.method), "exotic recover by story point");
        st = Exotica.diff(withEx, snap(none, 1000f, 5));
        check("destroy".equals(st.method), "exotic destroy");

        // several changes in one look: bandwidth first
        Recorder rec = new Recorder(base);
        rec.update(snap(mods(105f, "{\"u\":1}", null), 700f, 5, cargo("metals", 10f), chip("et_upgrade", "u,2", 1f)));
        check(rec.steps.size() == 2 && rec.steps.get(0).kind == Exotica.BANDWIDTH && rec.steps.get(1).kind == Exotica.UPGRADE, "bandwidth first");
        check("free".equals(rec.steps.get(1).method), "charge evidence is used by the first step only");

        // a fall in level without removal is left out
        Recorder lv = new Recorder(snap(mods(100f, "{\"u\":3}", null), 1000f, 5));
        lv.update(snap(mods(100f, "{\"u\":2}", null), 1000f, 5));
        check(lv.steps.isEmpty() && lv.ignored == 1, "level fell ignored");

        // the cap
        Recorder cap = new Recorder(base);
        StringBuilder ups = new StringBuilder("{");
        for (int i = 0; i < 70; i++) ups.append(i == 0 ? "" : ",").append("\"u").append(String.format("%02d", i)).append("\":1");
        ups.append("}");
        cap.update(snap(mods(100f, ups.toString(), null), 1000f, 5));
        check(cap.steps.size() == Exotica.MAX_STEPS && cap.full, "step cap");

        System.out.println("ExoticaTest: " + checks + " checks passed");
    }
}
