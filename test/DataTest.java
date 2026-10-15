import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import wingman.net.Aftermath;
import wingman.net.BattleReport;
import wingman.net.CampaignCopy;
import wingman.net.In;
import wingman.net.Out;
import wingman.net.Plain;
import wingman.net.Roster;

/**
 * Round trips of the lobby-era messages (campaign copy, after-action report, aftermath, roster), and the plain
 * data codec's refusals: only plain data in, only the JDK's own types out, damaged or hostile bytes rejected.
 */
public class DataTest {
    static int fails, checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static Object roundTrip(Object v) throws Exception {
        Out o = new Out();
        Plain.write(o, v);
        return Plain.read(new In(o.toByteArray()));
    }

    public static void main(String[] a) throws Exception {
        // ---- plain data ----
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("flag", Boolean.TRUE);
        m.put("int", 7);
        m.put("long", 1L << 40);
        m.put("float", 1.5f);
        m.put("double", 2.25);
        m.put("text", "héllo ☃");
        m.put("nothing", null);
        m.put("list", new ArrayList<Object>(Arrays.asList("a", 1, 2.5f)));
        m.put("set", new HashSet<Object>(Arrays.asList("x", "y")));
        m.put("ordered", new LinkedHashSet<Object>(Arrays.asList("z", "a")));
        Map<String, Object> inner = new HashMap<String, Object>();
        inner.put("deep", new ArrayList<Object>(Arrays.asList(new HashMap<String, Object>())));
        m.put("map", inner);
        check(Plain.isPlain(m), "plain: a map of plain values is plain");
        Object back = roundTrip(m);
        check(m.equals(back), "plain: round trip equal: " + back);
        check(back instanceof LinkedHashMap && ((Map<?, ?>) back).get("ordered") instanceof LinkedHashSet, "plain: order kept where it was");
        check(((Map<?, ?>) back).get("long") instanceof Long && ((Map<?, ?>) back).get("float") instanceof Float, "plain: number types kept");

        check(!Plain.isPlain(new Object()), "plain: an object of its own isn't plain");
        check(!Plain.isPlain(Arrays.asList("a", new StringBuilder())), "plain: a list holding one isn't either");
        Map<Object, Object> badKey = new HashMap<Object, Object>();
        badKey.put(3, "x");
        check(!Plain.isPlain(badKey), "plain: map keys must be text");
        List<Object> self = new ArrayList<Object>();
        self.add(self);
        check(!Plain.isPlain(self), "plain: a list holding itself is refused, not followed forever");
        List<Object> deep = new ArrayList<Object>(), at = deep;
        for (int i = 0; i < 20; i++) { List<Object> next = new ArrayList<Object>(); at.add(next); at = next; }
        check(!Plain.isPlain(deep), "plain: too deep is refused");
        List<Object> wide = new ArrayList<Object>();
        for (int i = 0; i < 300; i++) { List<Object> row = new ArrayList<Object>(); for (int j = 0; j < 300; j++) row.add(j); wide.add(row); }
        check(!Plain.isPlain(wide), "plain: too big in all is refused");

        // damaged bytes: refused, never a crash or a huge allocation
        Out o = new Out();
        Plain.write(o, m);
        byte[] good = o.toByteArray();
        int refused = 0;
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 3000; i++) {
            byte[] bad = good.clone();
            bad[r.nextInt(bad.length)] = (byte) r.nextInt(256);
            try { Plain.read(new In(bad)); } catch (java.io.IOException e) { refused++; } catch (Throwable t) { check(false, "plain: damaged bytes threw " + t); break; }
        }
        check(refused > 0, "plain: damaged bytes are refused (" + refused + " of 3000)");
        try { Plain.read(new In(new byte[] { 8, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F })); check(false, "plain: a huge count is refused"); }
        catch (java.io.IOException e) { check(true, ""); }

        // ---- campaign copy ----
        CampaignCopy c = new CampaignCopy();
        c.persistent.put("mod_state", m);
        Map<String, Object> mem = new LinkedHashMap<String, Object>();
        mem.put("$flag", Boolean.TRUE);
        mem.put("$count", 3f);
        c.memories.put("sector", mem);
        c.memories.put("enemyFleet", new LinkedHashMap<String, Object>());
        c.relations.put("pirates", -0.6f);
        c.relations.put("hegemony", 2f); // out of range: kept within -1..1
        c.enemyFaction = "pirates"; c.enemyFleetName = "Raiders"; c.enemyCommanderFirst = "Ann"; c.enemyCommanderLevel = 5;
        c.playerGoal = "ATTACK"; c.enemyGoal = "ESCAPE"; c.locationName = "Corvus"; c.locationTags.add("theme_core"); c.timestamp = 123456789L;
        c.skipped = 4; c.skippedKeys.add("saved data: big_object"); c.skippedKeys.add("$someObject");
        CampaignCopy c2 = CampaignCopy.decode(c.encode());
        check(c2.persistent.equals(c.persistent), "copy: saved data");
        check(c2.memories.get("sector").equals(mem) && c2.memories.containsKey("enemyFleet"), "copy: memories");
        check(c2.relations.get("pirates") == -0.6f && c2.relations.get("hegemony") == 1f, "copy: standings (and clamped)");
        check(c2.enemyFleetName.equals("Raiders") && c2.enemyCommanderLevel == 5 && c2.enemyGoal.equals("ESCAPE") && c2.locationTags.contains("theme_core")
                && c2.timestamp == 123456789L, "copy: enemy, goals, place, date");
        check(c2.skipped == 4 && c2.skippedKeys.contains("$someObject"), "copy: what was left out");
        check(c2.count() == c.count(), "copy: count");

        // ---- after-action report ----
        BattleReport br = new BattleReport();
        br.outcome = 0; br.seconds = 312.5f;
        br.sides[0].deployed = 6; br.sides[0].destroyed = 1; br.sides[0].lost.add("ISS Brave (Hammerhead)");
        br.sides[1].deployed = 9; br.sides[1].destroyed = 5; br.sides[1].disabled = 1; br.sides[1].retreated = 2; br.sides[1].fightersLost = 14;
        BattleReport.Pilot p = new BattleReport.Pilot();
        p.name = "Host"; p.slot = 1; p.ship = "ISS One (Onslaught)"; p.kills = 3; p.fighterKills = 7; p.damageDealt = 12345f; p.shieldDamageDealt = 999f; p.damageTaken = 4000f;
        br.pilots.add(p);
        BattleReport.Pilot q = new BattleReport.Pilot();
        q.name = "Wing"; q.slot = 2; q.status = BattleReport.WATCHED; q.damageDealt = 1e12f; // absurd: kept in range
        br.pilots.add(q);
        BattleReport br2 = BattleReport.decode(br.encode());
        check(br2.outcome == 0 && br2.seconds == 312.5f && br2.sides[1].fightersLost == 14 && br2.sides[0].lost.get(0).equals("ISS Brave (Hammerhead)"), "report: outcome and losses");
        check(br2.pilots.size() == 2 && br2.pilots.get(0).kills == 3 && br2.pilots.get(0).damageDealt == 12345f && br2.pilots.get(1).status == BattleReport.WATCHED
                && br2.pilots.get(1).damageDealt <= 9e6f, "report: players");

        // ---- aftermath ----
        Aftermath af = new Aftermath();
        af.credits = -1500f; af.shipsLost.add("ISS Brave (Hammerhead)"); af.shipsGained.add("Wolf"); af.cargoNames.add("Supplies"); af.cargoChanges.add(-12f);
        Aftermath af2 = Aftermath.decode(af.encode());
        check(af2.credits == -1500f && af2.shipsLost.size() == 1 && af2.shipsGained.get(0).equals("Wolf") && af2.cargoChanges.get(0) == -12f, "aftermath round trip");
        check(new Aftermath().isEmpty() && !af2.isEmpty(), "aftermath: empty when nothing changed");

        // ---- roster with the ship card ----
        Roster ro = new Roster();
        ro.hostName = "Host";
        Roster.Member mm = new Roster.Member();
        mm.id = "m1"; mm.shipName = "ISS One"; mm.hullName = "Onslaught"; mm.hullSize = "CAPITAL_SHIP"; mm.sprite = "graphics/ships/onslaught/onslaught_base.png";
        mm.weapons = "2x Heavy Mauler, Hellbore"; mm.hullmods = "Hardened Shields"; mm.personality = "Steady";
        mm.hullId = "station_x"; mm.modules.add("module_a|graphics/ships/a.png|120.0|-40.0|90.0"); mm.modules.add("module_b|graphics/ships/b.png|-60.0|0.0|180.0");
        mm.portrait = "graphics/portraits/portrait_mercenary01.png"; mm.skills.add("helmsmanship:2"); mm.skills.add("field_modulation:1");
        mm.gear.add("w|heavymauler|2"); mm.gear.add("f|broadsword_wing|1"); mm.fits.add("-1|WS0001|heavymauler"); mm.fits.add("0|WS0002|hellbore"); mm.dmods = "Degraded Engines, Faulty Grid";
        ro.members.add(mm);
        Roster ro2 = Roster.decode(ro.encode());
        Roster.Member m2 = ro2.members.get(0);
        check(m2.sprite.equals(mm.sprite) && m2.weapons.equals(mm.weapons) && m2.hullmods.equals(mm.hullmods) && m2.personality.equals("Steady"), "roster: ship card");
        check(m2.hullId.equals("station_x") && m2.modules.size() == 2 && m2.modules.get(1).startsWith("module_b|"), "roster: a multi-part ship's modules");
        check(m2.portrait.equals(mm.portrait) && m2.skills.equals(mm.skills) && m2.gear.equals(mm.gear) && m2.fits.equals(mm.fits) && m2.dmods.equals(mm.dmods), "roster: officer portrait and skills, weapons and wings for their pictures");
        check(ro2.refitMarket.isEmpty(), "roster: no refit by default");
        ro.refitMarket = "Jangala\n";
        check(Roster.decode(ro.encode()).refitMarket.equals("Jangala"), "roster: where friends may refit, cleaned");

        // ---- friend refit: what the refit screen shows ----
        wingman.net.RefitData rd = new wingman.net.RefitData();
        rd.market = "Jangala"; rd.memberId = "m1"; rd.hullId = "eagle"; rd.hullSize = "CRUISER";
        rd.opMax = 155; rd.opUsed = 148; rd.vents = 20; rd.caps = 10; rd.maxVents = 30; rd.maxCaps = 30; rd.maxSMods = 3;
        rd.fitSlots.add("WS0001"); rd.fitWeapons.add("heavymauler"); rd.fitSlots.add("WS0002"); rd.fitWeapons.add("hveldriver");
        rd.wings.add("talon_wing"); rd.wings.add("");
        rd.hullmods.add("hardenedshieldemitter"); rd.sMods.add("heavyarmor"); rd.permaMods.add("degraded_engines");
        rd.credits = 123456.5f; rd.storyPoints = 4;
        rd.cargo.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WEAPON, "lightac", 3));
        rd.cargo.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WING, "wasp_wing", 1));
        rd.hasStorage = true; rd.storage.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WEAPON, "lightac", 2));
        wingman.net.RefitData.Market om = new wingman.net.RefitData.Market();
        om.id = "open_market"; om.name = "Open Market"; om.tariff = 0.3f; om.stock.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WEAPON, "lightac", 5)); om.cantSell.add("w:hveldriver");
        wingman.net.RefitData.Market mil = new wingman.net.RefitData.Market();
        mil.id = "generic_military"; mil.name = "Military Market"; mil.usable = false; mil.reason = "Requires: Hegemony - favorable"; mil.stock.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WEAPON, "lightac", 9));
        wingman.net.RefitData.Market bm = new wingman.net.RefitData.Market();
        bm.id = "black_market"; bm.name = "Black Market"; bm.stock.add(new wingman.net.RefitData.Item(wingman.net.RefitData.WEAPON, "lightac", 4)); bm.cantBuy.add("w:lightac");
        rd.markets.add(om); rd.markets.add(mil); rd.markets.add(bm);
        rd.knownHullmods.add("heavyarmor"); rd.values.put("w:lightac", 100f); rd.values.put("w:hveldriver", 400f);
        wingman.net.RefitData rd2 = wingman.net.RefitData.decode(rd.encode());
        check(rd2.market.equals("Jangala") && rd2.memberId.equals("m1") && rd2.hullId.equals("eagle") && rd2.opMax == 155 && rd2.opUsed == 148
                && rd2.maxVents == 30 && rd2.maxSMods == 3 && rd2.fitWeapons.get(1).equals("hveldriver") && rd2.wings.get(1).isEmpty()
                && rd2.sMods.contains("heavyarmor") && rd2.permaMods.contains("degraded_engines"), "refit data: the ship");
        check(rd2.credits == 123456.5f && rd2.storyPoints == 4 && rd2.cargo.size() == 2 && rd2.cargo.get(1).kind == wingman.net.RefitData.WING
                && rd2.hasStorage && rd2.markets.size() == 3 && !rd2.markets.get(1).usable && rd2.markets.get(1).reason.startsWith("Requires")
                && rd2.knownHullmods.contains("heavyarmor") && rd2.values.get("w:lightac") == 100f, "refit data: what the host has");
        // 3 in cargo + 2 in storage + 5 on the open market; not the military market (closed), nor the black market (won't sell it)
        check(rd2.available("w:lightac") == 10, "refit data: what can be had counts only usable sources");
        check(rd2.buyPrice(rd2.markets.get(0), "w:lightac") == 130f && rd2.sellPrice(rd2.markets.get(0), "w:lightac") == 70f
                && rd2.markets.get(0).cantSell.contains("w:hveldriver"), "refit data: prices with tariff, and what a market won't take");

        // ---- friend refit: the request, and what it comes to ----
        rd.opCosts.put("w:heavymauler", 12); rd.opCosts.put("w:hveldriver", 10); rd.opCosts.put("w:lightac", 4); rd.opCosts.put("f:talon_wing", 4); rd.opCosts.put("f:wasp_wing", 6);
        rd.modCosts.put("hardenedshieldemitter", 15); rd.modCosts.put("heavyarmor", 15); rd.modCosts.put("unstable_injector", 15); rd.modCosts.put("itu", 30);
        rd.knownHullmods.add("unstable_injector"); rd.knownHullmods.add("itu"); rd.noBuildIn.add("itu");
        rd = wingman.net.RefitData.decode(rd.encode());
        check(rd.opCosts.get("w:hveldriver") == 10 && rd.modCosts.get("itu") == 30 && rd.noBuildIn.contains("itu"), "refit data: ordnance costs and what can't be built in");
        wingman.net.RefitRequest rq = new wingman.net.RefitRequest();
        rq.memberId = "m1"; rq.vents = 20; rq.caps = 10; rq.note = "more dakka\n";
        wingman.net.RefitRequest.Change ch1 = new wingman.net.RefitRequest.Change(); // the mauler out, sold at the open market; a light autocannon bought there
        ch1.place = "WS0001"; ch1.item = "lightac"; ch1.from = "open_market"; ch1.to = "open_market";
        wingman.net.RefitRequest.Change ch2 = new wingman.net.RefitRequest.Change(); // the second bay (empty) gets the wasp LPC from cargo
        ch2.wing = true; ch2.place = "1"; ch2.item = "wasp_wing"; ch2.from = "cargo";
        rq.changes.add(ch1); rq.changes.add(ch2);
        wingman.net.RefitRequest rq2 = wingman.net.RefitRequest.decode(rq.encode());
        check(rq2.memberId.equals("m1") && rq2.note.equals("more dakka") && rq2.changes.size() == 2 && rq2.changes.get(1).wing && rq2.change("1", true).item.equals("wasp_wing")
                && rq2.change("WS0001", false).to.equals("open_market"), "refit request round trip");
        wingman.net.RefitPlan pl = new wingman.net.RefitPlan(rd, rq2);
        // OP: 148 - 12 (mauler) + 4 (light AC) + 6 (wasps) = 146. Credits: buy 130, sell the mauler (no value given: 0).
        check(pl.opUsed == 146 && pl.buy == 130 && pl.sell == 0 && pl.net() == 130 && pl.allProblems().isEmpty() && !pl.isEmpty(), "refit plan: ordnance points and credits " + pl.opUsed + " " + pl.buy + " " + pl.allProblems());
        rq2.removeMods.add("hardenedshieldemitter"); rq2.addMods.add("unstable_injector"); rq2.buildIn.add("unstable_injector"); rq2.vents = 25;
        pl = new wingman.net.RefitPlan(rd, rq2);
        // and: -15 shields, +15 injector -15 (built in: free) +5 vents = 136; one story point; S-mods 1 + 1
        check(pl.opUsed == 136 && pl.storyPoints == 1 && pl.sMods == 2 && pl.allProblems().isEmpty(), "refit plan: hull mods, an S-mod, vents " + pl.opUsed + " " + pl.allProblems());
        wingman.net.RefitRequest bad = wingman.net.RefitRequest.decode(rq2.encode());
        bad.buildIn.add("itu"); bad.addMods.add("itu"); bad.removeMods.add("heavyarmor"); bad.vents = 31;
        wingman.net.RefitRequest.Change ch3 = new wingman.net.RefitRequest.Change(); ch3.place = "WS0002"; ch3.item = "lightac"; ch3.from = "generic_military"; bad.changes.add(ch3);
        wingman.net.RefitRequest.Change ch4 = new wingman.net.RefitRequest.Change(); ch4.place = "WS0003"; ch4.item = "lightac"; ch4.from = "black_market"; bad.changes.add(ch4);
        wingman.net.RefitRequest.Change ch5 = new wingman.net.RefitRequest.Change(); ch5.place = "WS0004"; ch5.item = "lightac"; ch5.from = "cargo"; bad.changes.add(ch5);
        wingman.net.RefitRequest.Change ch6 = new wingman.net.RefitRequest.Change(); ch6.place = "WS0005"; ch6.item = "lightac"; ch6.from = "cargo"; bad.changes.add(ch6);
        wingman.net.RefitRequest.Change ch7 = new wingman.net.RefitRequest.Change(); ch7.place = "WS0006"; ch7.item = "lightac"; ch7.from = "cargo"; bad.changes.add(ch7);
        wingman.net.RefitRequest.Change ch8 = new wingman.net.RefitRequest.Change(); ch8.place = "WS0007"; ch8.item = "lightac"; ch8.from = "cargo"; bad.changes.add(ch8);
        pl = new wingman.net.RefitPlan(rd, bad);
        String all = pl.allProblems().toString();
        check(all.contains("can't be built in") && all.contains("can't be taken off") && all.contains("Flux vents") && all.contains("Military Market is shut")
                && all.contains("won't sell") && all.contains("Not enough lightac in the host's cargo: 4 wanted, 3 there"), "refit plan: each problem named " + all);
        // a weapon taken out to cargo can go into another slot
        wingman.net.RefitRequest swap = new wingman.net.RefitRequest(); swap.memberId = "m1"; swap.vents = 20; swap.caps = 10;
        wingman.net.RefitRequest.Change s1 = new wingman.net.RefitRequest.Change(); s1.place = "WS0001"; s1.item = ""; s1.to = "cargo";
        wingman.net.RefitRequest.Change s2 = new wingman.net.RefitRequest.Change(); s2.place = "WS0009"; s2.item = "heavymauler"; s2.from = "cargo";
        swap.changes.add(s1); swap.changes.add(s2);
        pl = new wingman.net.RefitPlan(rd, swap);
        check(pl.allProblems().isEmpty() && pl.opUsed == 148, "refit plan: a weapon moved from one slot to another through cargo " + pl.allProblems());
        swap.changes.get(0).to = "black_market";
        pl = new wingman.net.RefitPlan(rd, swap);
        check(!pl.allProblems().isEmpty(), "refit plan: sold, it can't also go in elsewhere unless bought back");
        // costs too much
        rd.credits = 50f;
        check(new wingman.net.RefitPlan(rd, rq2).allProblems().toString().contains("Credits"), "refit plan: not enough credits");
        rd.credits = 123456.5f;
        // a plan made at this market, looked at away from any market: what came from (or went to) the market is
        // named as unavailable there, the rest is fine
        wingman.net.RefitData away = wingman.net.RefitData.decode(rd.encode());
        away.market = ""; away.markets.clear(); away.hasStorage = false; away.storage.clear();
        pl = new wingman.net.RefitPlan(away, rq);
        check(pl.unavailable.contains("wWS0001") && !pl.unavailable.contains("f1") && pl.unavailableWhy.size() >= 1, "refit plan away from a market: the market's items named " + pl.unavailable + " " + pl.unavailableWhy);
        // at a market without enough of it: those slots are named too
        wingman.net.RefitData short1 = wingman.net.RefitData.decode(rd.encode());
        short1.markets.get(0).stock.clear();
        pl = new wingman.net.RefitPlan(short1, rq);
        check(pl.unavailable.contains("wWS0001") && pl.allProblems().containsAll(pl.unavailableWhy), "refit plan at another market: what isn't there is named " + pl.unavailable);
        check(new wingman.net.RefitPlan(rd, rq).unavailable.isEmpty(), "refit plan: nothing unavailable where it was made");
        wingman.net.RefitResult res = wingman.net.RefitResult.decode(new wingman.net.RefitResult(wingman.net.RefitResult.REFUSED, "a\nb\u0007").encode());
        check(res.status == wingman.net.RefitResult.REFUSED && res.text.equals("a\nb"), "refit result round trip, lines kept, control characters dropped");

        // ---- who ships and fleets are (mimics and the like) ----
        wingman.net.Person cap = new wingman.net.Person();
        cap.id = "sotf_sirius_mimic"; cap.first = "Sirius"; cap.faction = "sotf_dreaminggestalt"; cap.personality = "reckless"; cap.rank = "spaceCommander";
        cap.tags.add("sierra_imprint"); cap.memory.put("$chatterChar", "sotf_sirius_mimic"); cap.memory.put("$fearless", Boolean.TRUE); cap.level = 7;
        wingman.net.ShipSpawn sp = new wingman.net.ShipSpawn();
        sp.netId = 4; sp.hullId = "uaf_ship"; sp.hasCaptain = true; sp.memberId = "m123"; sp.variantTags.add("uaf_mimic_ship"); sp.captain = cap; sp.fleetKey = 2;
        sp.permaMods.add("uaf_anomalous_hull"); sp.hullMods.add("uaf_anomalous_hull");
        wingman.net.ShipSpawn sp2 = wingman.net.ShipSpawn.decode(sp.encode());
        check(sp2.memberId.equals("m123") && sp2.variantTags.contains("uaf_mimic_ship") && sp2.fleetKey == 2 && sp2.permaMods.contains("uaf_anomalous_hull"), "spawn: member id, variant tags, fleet");
        check(sp2.captain != null && sp2.captain.id.equals("sotf_sirius_mimic") && sp2.captain.tags.contains("sierra_imprint")
                && "sotf_sirius_mimic".equals(sp2.captain.memory.get("$chatterChar")) && sp2.captain.level == 7 && sp2.captain.personality.equals("reckless"), "spawn: the captain's identity");
        wingman.net.ShipSpawn plain = wingman.net.ShipSpawn.decode(new wingman.net.ShipSpawn().encode());
        check(plain.captain == null && plain.fleetKey == -1 && plain.variantTags.isEmpty(), "spawn: none of it when there is none");
        wingman.net.FleetInfo fi = new wingman.net.FleetInfo();
        fi.key = 2; fi.owner = 1; fi.faction = "uaf_mimic"; fi.name = "Echoes"; fi.memory.put("$UAFmimicFleet", Boolean.TRUE);
        wingman.net.Person boss = new wingman.net.Person(); boss.first = "Crow"; boss.skillIds.add("uaf_mimic"); boss.skillLevels.add(1f); fi.commander = boss;
        wingman.net.FleetInfo fi2 = wingman.net.FleetInfo.decode(fi.encode());
        check(fi2.key == 2 && fi2.owner == 1 && !fi2.playerFleet && fi2.faction.equals("uaf_mimic") && Boolean.TRUE.equals(fi2.memory.get("$UAFmimicFleet"))
                && fi2.commander != null && fi2.commander.skillIds.contains("uaf_mimic"), "fleet: faction, flags and commander's skills");
        wingman.net.FleetInfo.Reserve rv = new wingman.net.FleetInfo.Reserve();
        rv.id = "m7"; rv.hullId = "condor"; rv.name = "ISS Nest"; rv.hullmods.add("expanded_deck_crew"); rv.permaMods.add("armaa_strikeCraft"); rv.sMods.add("armaa_strikeCraft");
        rv.wings.add("broadsword_wing"); rv.cr = 0.55f; rv.mothballed = true;
        fi.reserves.add(rv);
        wingman.net.FleetInfo fi3 = wingman.net.FleetInfo.decode(fi.encode());
        wingman.net.FleetInfo.Reserve rv2 = fi3.reserves.isEmpty() ? null : fi3.reserves.get(0);
        check(rv2 != null && rv2.id.equals("m7") && rv2.hullId.equals("condor") && rv2.name.equals("ISS Nest") && rv2.hullmods.contains("expanded_deck_crew")
                && rv2.sMods.contains("armaa_strikeCraft") && rv2.wings.get(0).equals("broadsword_wing") && rv2.cr == 0.55f && rv2.mothballed && !rv2.flagship,
                "fleet: the ships not in the battle travel with it");

        System.out.println(fails == 0 ? "DATA PASSED (" + checks + " checks)" : "DATA FAILED: " + fails + " of " + checks);
        if (fails > 0) System.exit(1);
    }
}
