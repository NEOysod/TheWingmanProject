package wingman;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import wingman.net.ClientLink;

/** Preview only: the screens drawn by UiPreview, each from made-up but typical content. */
final class Screens {
    static final class Shot {
        final String name;
        final int w, h;
        final float mx, my;
        final Runnable draw;
        Shot(String name, int w, int h, float mx, float my, Runnable draw) { this.name = name; this.w = w; this.h = h; this.mx = mx; this.my = my; this.draw = draw; }
    }

    static void set(Object o, String field, Object v) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField(field); f.setAccessible(true); f.set(o, v); return; } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
            throw new RuntimeException("no field " + field);
        } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }
    static Object get(Object o, String field) {
        try { Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(o); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The title screen's CO-OP button and panel, as the title plugin draws them. */
    static void coop(boolean connected) {
        WingmanTitlePlugin t = new WingmanTitlePlugin();
        WingmanConfig cfg = new WingmanConfig();
        cfg.playerName = "Kyrizion";
        set(t, "cfg", cfg);
        JoinForm form = (JoinForm) get(t, "form");
        form.address.append("198.51.100.21");
        form.name.append("Kyrizion");
        form.field = 0;
        set(t, "myAddresses", "192.0.2.34,  203.0.113.9 (virtual LAN)");
        if (connected) {
            set(t, "hostLine", "NEOysod is in a battle: Battle of Corvus (4 ships each side).");
            set(t, "withHost", "NEOysod (host), Cy");
            form.status = "Connected to 198.51.100.21.";
            form.statusColor = Panel.GOOD;
            wingman.net.Roster r = new wingman.net.Roster();
            r.hostName = "NEOysod";
            set(t, "roster", r);
        }
        float sw = UiPreview.W, sh = UiPreview.H, W = 150f, H = 46f, M = 8f;
        Panel p = new Panel(); p.mouse = true;
        p.button(sw - W - M, sh - H - M, W, H, "CO-OP", connected ? "connected" : "join or host", true);
        float clear = sw / 2f - 520f - M;
        float bw = clear >= 380f ? Math.min(560f, clear) : 560f;
        p.renderBelow(sw - M, sh - H - M - 6f, bw, t.coopDoc(connected ? ClientLink.State.CONNECTED : ClientLink.State.IDLE, connected));
    }

    static Panel mousePanel() { Panel p = new Panel(); p.mouse = true; return p; }

    static void settings(int page, int row) {
        Settings.Menu m = new Settings.Menu();
        WingmanConfig cfg = new WingmanConfig();
        set(m, "cfg", cfg);
        set(m, "file", new WingmanConfig());
        LinkedHashMap<String, String> ov = new LinkedHashMap<String, String>();
        ov.put("bandwidthLimit", "48");
        set(m, "overrides", ov);
        set(m, "page", page);
        set(m, "row", row);
        m.open = true;
        if (page == 1) cfg.bandwidthLimit = 48;
        float sw = UiPreview.W, sh = UiPreview.H, M = 8f;
        mousePanel().renderBelow(sw - M, sh - 46f - M - 6f, 560f, m.doc());
    }


    static wingman.net.Roster.Member member(String ship, String hull, String size, int cr, String picked, int flags) {
        wingman.net.Roster.Member m = new wingman.net.Roster.Member();
        m.id = ship; m.shipName = ship; m.hullName = hull; m.hullSize = size; m.cr = cr; m.pickedBy = picked; m.flags = flags;
        m.designation = size.equals("CAPITAL_SHIP") ? "Battleship" : size.equals("CRUISER") ? "Heavy cruiser" : size.equals("DESTROYER") ? "Destroyer" : "Frigate";
        return m;
    }

    static wingman.net.Roster roster() {
        wingman.net.Roster r = new wingman.net.Roster();
        r.hostName = "NEOysod";
        String[][] ships = { {"ISS Child of Venus", "Onslaught", "CAPITAL_SHIP"}, {"ISS Hand of Fate", "Conquest", "CAPITAL_SHIP"}, {"ISS Long Wait", "Eagle", "CRUISER"},
            {"ISS Lantern", "Heron", "CRUISER"}, {"ISS Patience", "Dominator", "CRUISER"}, {"ISS Kettle", "Hammerhead", "DESTROYER"}, {"ISS Red Thread", "Sunder", "DESTROYER"},
            {"ISS Paper Moon", "Drover", "DESTROYER"}, {"ISS Small Mercy", "Wolf", "FRIGATE"}, {"ISS Quick Fox", "Tempest", "FRIGATE"}, {"ISS Dust", "Lasher", "FRIGATE"},
            {"ISS Errand", "Centurion", "FRIGATE"}, {"ISS Bramble", "Monitor", "FRIGATE"}, {"ISS Ledger", "Atlas", "CAPITAL_SHIP"} };
        for (int i = 0; i < ships.length; i++) {
            String[] s = ships[i];
            r.members.add(member(s[0], s[1], s[2], i == 7 ? 34 : 70 + (i * 7) % 30, i == 2 ? "Kyrizion" : i == 4 ? "Cy" : "", i == 0 ? wingman.net.Roster.FLAGSHIP : i == 13 ? wingman.net.Roster.MOTHBALLED : 0));
        }
        wingman.net.Roster.Member m = r.members.get(2);
        m.officer = "Mira Okonkwo"; m.officerLevel = 5; m.personality = "AGGRESSIVE";
        m.skills.addAll(Arrays.asList("helmsmanship:2", "combat_endurance:1", "impact_mitigation:2", "field_modulation:1", "target_analysis:1"));
        m.gear.addAll(Arrays.asList("w|Heavy Autocannon|2", "w|Heavy Mauler|1", "w|Harpoon MRM|2", "w|Light Mortar|2", "f|Talon Interceptor Wing|1"));
        m.hullmods = "Integrated Targeting Unit (S), Hardened Shields, Expanded Missile Racks, Auxiliary Thrusters";
        m.dmods = "Degraded Engines, Faulty Grid";
        m.sprite = "graphics/ships/eagle.png";
        for (String n : new String[] { "Kyrizion", "Cy", "Mosk" }) { wingman.net.Roster.Player p = new wingman.net.Roster.Player(); p.name = n; p.slot = r.players.size() + 2; r.players.add(p); }
        return r;
    }

    static void report() {
        wingman.net.BattleReport b = new wingman.net.BattleReport();
        b.outcome = 0; b.seconds = 417;
        String[][] ps = { {"1", "NEOysod", "ISS Hand of Fate"}, {"2", "Kyrizion", "ISS Long Wait"}, {"3", "Cy", "ISS Patience"}, {"4", "Mosk", ""} };
        for (String[] p : ps) {
            wingman.net.BattleReport.Pilot q = new wingman.net.BattleReport.Pilot();
            q.slot = Integer.parseInt(p[0]); q.name = p[1]; q.ship = p[2]; q.kills = q.slot * 2 % 5; q.fighterKills = q.slot * 3; q.damageDealt = 4200 * q.slot; q.shieldDamageDealt = q.slot == 2 ? 9800 : 0; q.damageTaken = 1500 * q.slot;
            q.status = q.slot == 3 ? wingman.net.BattleReport.DESTROYED : q.slot == 4 ? wingman.net.BattleReport.WATCHED : wingman.net.BattleReport.ALIVE;
            b.pilots.add(q);
        }
        b.sides[0].deployed = 9; b.sides[0].destroyed = 1; b.sides[0].retreated = 1; b.sides[0].lost.add("ISS Patience (Dominator)");
        b.sides[1].deployed = 14; b.sides[1].destroyed = 9; b.sides[1].disabled = 2; b.sides[1].fightersLost = 23; b.sides[1].lost.addAll(Arrays.asList("Hegemony Enforcer x3", "Hegemony Hammerhead x4", "Dominator"));
        AfterAction.report = b;
        wingman.net.Aftermath a = new wingman.net.Aftermath();
        a.credits = 48250; a.shipsGained.add("Enforcer (recovered)"); a.cargoNames.addAll(Arrays.asList("Supplies", "Fuel", "Heavy Machinery")); a.cargoChanges.addAll(Arrays.asList(120f, -40f, 35f));
        AfterAction.aftermath = a;
    }

    static void lobby(int page, boolean refit) {
        report();
        LobbyScreen l = new LobbyScreen();
        l.page = page;
        LobbyScreen.View v = new LobbyScreen.View();
        v.roster = roster();
        v.me = "Kyrizion";
        v.at = 2;
        v.hostLine = "NEOysod is on the campaign map. You go into their next battle when it starts.";
        v.hostColor = Panel.GOOD;
        v.players = "Here: NEOysod (host), Kyrizion, Cy, Mosk";
        if (refit) v.refitMarket = "Jangala";
        v.note = "Picked: ISS Long Wait.";
        v.noteColor = Panel.GOOD;
        l.render(v);
    }

    static wingman.net.RefitData refitData() {
        wingman.net.RefitData d = new wingman.net.RefitData();
        d.memberId = "ISS Long Wait"; d.hullId = "eagle"; d.hullSize = "CRUISER"; d.market = "jangala";
        d.opMax = 145; d.opUsed = 138; d.vents = 20; d.caps = 18; d.maxVents = 30; d.maxCaps = 30; d.maxSMods = 3;
        String[][] fit = { {"WS0001", "Heavy Mauler"}, {"WS0002", "Heavy Autocannon"}, {"WS0003", "Heavy Autocannon"}, {"WS0004", "Harpoon MRM"}, {"WS0005", "Harpoon MRM"}, {"WS0006", "Light Mortar"}, {"WS0007", "Light Mortar"}, {"WS0008", ""} };
        for (String[] w : fit) { d.fitSlots.add(w[0]); d.fitWeapons.add(w[1]); }
        d.hullmods.addAll(Arrays.asList("Hardened Shields", "Expanded Missile Racks", "Auxiliary Thrusters"));
        d.sMods.add("Integrated Targeting Unit");
        d.permaMods.addAll(Arrays.asList("Degraded Engines", "Faulty Grid"));
        d.credits = 182400; d.storyPoints = 4;
        d.cargo.add(new wingman.net.RefitData.Item(0, "Heavy Needler", 1));
        d.cargo.add(new wingman.net.RefitData.Item(0, "Hypervelocity Driver", 2));
        d.hasStorage = true;
        d.storage.add(new wingman.net.RefitData.Item(0, "Gauss Cannon", 1));
        wingman.net.RefitData.Market m = new wingman.net.RefitData.Market();
        m.id = "open_market"; m.name = "Open market"; m.tariff = 0.3f;
        m.stock.add(new wingman.net.RefitData.Item(0, "Heavy Mauler", 3));
        m.stock.add(new wingman.net.RefitData.Item(0, "Mark IX Autocannon", 2));
        m.stock.add(new wingman.net.RefitData.Item(0, "Arbalest Autocannon", 4));
        d.markets.add(m);
        wingman.net.RefitData.Market mil = new wingman.net.RefitData.Market();
        mil.id = "military"; mil.name = "Military market"; mil.usable = false; mil.reason = "needs a commission";
        mil.stock.add(new wingman.net.RefitData.Item(0, "Storm Needler", 1));
        d.markets.add(mil);
        for (String k : new String[] { "w:Heavy Needler", "w:Hypervelocity Driver", "w:Gauss Cannon", "w:Heavy Mauler", "w:Mark IX Autocannon", "w:Arbalest Autocannon", "w:Storm Needler", "w:Heavy Autocannon" }) {
            d.opCosts.put(k, 12 + k.length() % 9); d.values.put(k, 4000f + k.length() * 300);
        }
        d.knownHullmods.addAll(Arrays.asList("Hardened Shields", "Expanded Missile Racks", "Auxiliary Thrusters", "Heavy Armor", "Reinforced Bulkheads", "Flux Distributor"));
        for (String h : d.knownHullmods) d.modCosts.put(h, 10 + h.length() % 7);
        return d;
    }

    static void refit(boolean changed) { refit(changed, true); }

    static String searchFor = "";
    static boolean whole;

    static void refit(boolean changed, boolean docked) {
        wingman.net.Roster r = roster();
        LobbyScreen l = new LobbyScreen();
        l.page = LobbyScreen.REFIT;
        LobbyScreen.View v = new LobbyScreen.View();
        v.roster = r; v.me = "Kyrizion"; v.at = 2; v.refitMarket = "Jangala";
        v.hostLine = "NEOysod is docked at Jangala."; v.hostColor = Panel.GOOD; v.players = "Here: NEOysod (host), Kyrizion, Cy, Mosk";
        v.refitData = refitData();
        if (!docked) { v.refitData.market = ""; v.refitData.markets.clear(); v.refitData.hasStorage = false; v.refitData.storage.clear(); v.refitMarket = ""; v.hostLine = "NEOysod is on the campaign map."; }
        l.refit.setData(v.refitData);
        if (changed) {
            wingman.net.RefitRequest q = (wingman.net.RefitRequest) get(l.refit, "req");
            wingman.net.RefitRequest.Change c = new wingman.net.RefitRequest.Change();
            c.place = "WS0002"; c.item = "Mark IX Autocannon"; c.from = "open_market"; c.to = "cargo";
            q.changes.add(c);
            q.vents = 30; q.caps = 26;
            q.addMods.add("Heavy Armor");
        }
        if (!searchFor.isEmpty()) { set(l.refit, "search", searchFor); set(l.refit, "typingSearch", true); }
        if (whole) set(l.refit, "availableOnly", false);
        l.render(v);
    }

    static void friendRefit() throws Exception {
        java.lang.reflect.Constructor<wingman.net.HostSession.Player> pc = wingman.net.HostSession.Player.class.getDeclaredConstructor(int.class, wingman.net.Conn.class);
        pc.setAccessible(true);
        wingman.net.HostSession.Player pl = pc.newInstance(2, null);
        pl.name = "Kyrizion"; pl.slot = 2;
        FriendRefit.Pending pd = new FriendRefit.Pending();
        pd.player = pl; pd.ship = "ISS Long Wait";
        wingman.net.RefitData d = refitData();
        wingman.net.RefitRequest q = new wingman.net.RefitRequest();
        q.memberId = d.memberId; q.vents = 26; q.caps = 18; q.note = "swapping the autocannons for Mark IXs, ok?";
        wingman.net.RefitRequest.Change c = new wingman.net.RefitRequest.Change();
        c.place = "WS0002"; c.item = "Mark IX Autocannon"; c.from = "open_market"; c.to = "cargo";
        q.changes.add(c);
        q.expectCredits = 13000;
        pd.request = q;
        pd.plan = new wingman.net.RefitPlan(d, q, new FriendRefit.Names(d));
        pd.op = 141; pd.opMax = 145;
        FriendRefitPanel p = new FriendRefitPanel();
        java.lang.reflect.Method doc = FriendRefitPanel.class.getDeclaredMethod("doc", FriendRefit.Pending.class, int.class);
        doc.setAccessible(true);
        Panel panel = (Panel) get(p, "panel");
        panel.render((Panel.Doc) doc.invoke(p, pd, 2));
        java.lang.reflect.Method b = FriendRefitPanel.class.getDeclaredMethod("drawButtons", FriendRefit.Pending.class, int.class);
        b.setAccessible(true);
        b.invoke(p, pd, 2);
        java.lang.reflect.Method tb = FriendRefitPanel.class.getDeclaredMethod("drawButton", int.class);
        tb.setAccessible(true);
        tb.invoke(p, 2);
    }

    static void playerList() throws Exception {
        CampaignPlayerList pl = new CampaignPlayerList();
        Class<?> rc = Class.forName("wingman.CampaignPlayerList$Row");
        java.lang.reflect.Constructor<?> c = rc.getDeclaredConstructor(int.class, String.class, String.class, boolean.class);
        c.setAccessible(true);
        List<Object> rows = new ArrayList<Object>();
        rows.add(c.newInstance(1, "NEOysod (you)", "host", false));
        rows.add(c.newInstance(2, "Kyrizion", "ISS Long Wait", false));
        rows.add(c.newInstance(3, "Cy", "", false));
        rows.add(c.newInstance(4, "Mosk", "ISS Kettle", true));
        java.lang.reflect.Method d = CampaignPlayerList.class.getDeclaredMethod("draw", List.class);
        d.setAccessible(true);
        d.invoke(pl, rows);
    }

    static List<Shot> all() {
        List<Shot> out = new ArrayList<Shot>();
        out.add(new Shot("ready-check", 1920, 1080, -1, -1, new Runnable() { public void run() {
            new Panel().render("READY CHECK", Arrays.asList("Waiting for everyone to load the battle.", "P1  NEOysod (host):  ready", "P2  Kyrizion:  loading ships (41 of 63)", "P3  Cy:  ready"),
                    Arrays.asList(Panel.TEXT, Panel.GOOD, Panel.WARN, Panel.GOOD), "Enter: start anyway     Esc: cancel");
        } }));
        out.add(new Shot("coop-join", 1920, 1080, -1, -1, new Runnable() { public void run() { coop(false); } }));
        out.add(new Shot("coop-connected", 1920, 1080, -1, -1, new Runnable() { public void run() { coop(true); } }));
        out.add(new Shot("settings", 1920, 1080, -1, -1, new Runnable() { public void run() { settings(1, 6); } }));
        out.add(new Shot("help", 1920, 1080, -1, -1, new Runnable() { public void run() {
            mousePanel().renderBelow(1912f, 1020f, 432f, WingmanTitlePlugin.helpDoc(1));
        } }));
        out.add(new Shot("modreport", 1920, 1080, -1, -1, new Runnable() { public void run() {
            List<String> r = Arrays.asList("Ultimate Armada Fleet:  12 missile hit effects, 4 gun hit effects, 9 ship systems, 6 hullmods",
                    "Special Hullmod Upgrades:  14 hullmods", "MagicLib:  2 on-fire effects", "",
                    "Seen in play as a joiner: 38 kinds of missile, shot, script and ship system; 35 behaved as intended.",
                    "Worth a look:  ship system uaf_rama_charge: used 12 times as a joiner, did not start on the host 2 times",
                    "Worth a look:  script shu_overcharge: failed 1 time");
            mousePanel().renderBelow(1912f, 1020f, 432f, WingmanTitlePlugin.reportDoc(r, 0, 1, 14));
        } }));
        out.add(new Shot("lobby-fleet", 1920, 1080, 300, 700, new Runnable() { public void run() { lobby(LobbyScreen.FLEET, true); } }));
        out.add(new Shot("lobby-fleet-1366", 1366, 768, -1, -1, new Runnable() { public void run() { lobby(LobbyScreen.FLEET, false); } }));
        out.add(new Shot("lobby-report", 1920, 1080, -1, -1, new Runnable() { public void run() { lobby(LobbyScreen.REPORT, false); } }));
        out.add(new Shot("refit", 1920, 1080, 900, 800, new Runnable() { public void run() { refit(false); } }));
        out.add(new Shot("refit-changed", 1920, 1080, -1, -1, new Runnable() { public void run() { refit(true); } }));
        out.add(new Shot("refit-search", 1920, 1080, -1, -1, new Runnable() { public void run() {
            searchFor = "autoc"; refit(false); searchFor = "";
        } }));
        out.add(new Shot("refit-whole", 1920, 1080, -1, -1, new Runnable() { public void run() { whole = true; refit(false); whole = false; } }));
        out.add(new Shot("refit-tip", 1920, 1080, 1600, 770, new Runnable() { public void run() { refit(false); try { Thread.sleep(300); } catch (Exception e) { } refit(false); } }));
        out.add(new Shot("refit-planning", 1920, 1080, -1, -1, new Runnable() { public void run() { refit(true, false); } }));
        out.add(new Shot("friend-refit-host", 1920, 1080, -1, -1, new Runnable() { public void run() { try { friendRefit(); } catch (Exception e) { throw new RuntimeException(e); } } }));
        out.add(new Shot("player-list", 1920, 1080, -1, -1, new Runnable() { public void run() { try { playerList(); } catch (Exception e) { throw new RuntimeException(e); } } }));
        return out;
    }
}
