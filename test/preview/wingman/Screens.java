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
        Panel p = new Panel(); p.mouse = true;
        float below = new CoopScreen().frame("CO-OP", connected ? "Connected to NEOysod's game." : "Not connected. Join a friend's game, or host one by starting any battle.",
                connected ? Panel.GOOD : Panel.DIM, connected);
        p.renderCentred(640f, below, t.coopDoc(connected ? ClientLink.State.CONNECTED : ClientLink.State.IDLE, connected));
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
        for (int i = 0; i < big - 4; i++) { wingman.net.Roster.Player p = new wingman.net.Roster.Player(); p.name = BIG_NAMES[i % BIG_NAMES.length] + (i >= BIG_NAMES.length ? String.valueOf(i / BIG_NAMES.length + 1) : ""); p.slot = r.players.size() + 2; r.players.add(p); }
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
        v.reserveRight = CoopScreen.buttonsWidth(true) + 14f;
        l.render(v);
        new CoopScreen().buttons(UiPreview.W - CoopScreen.margin(), UiPreview.H - CoopScreen.margin() + 2f, true);
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
    static boolean whole, loadouts, groupsShot;
    /** Players in the game for the big-game shots (0: the usual four). */
    static int big;
    static final String[] BIG_NAMES = { "Vex", "Halcyon", "Brightwater", "Okonkwo", "Rook", "Tamsin", "Juniper", "Faraday", "Quill", "Marlowe", "Sable", "Ivo",
        "Petra", "Lumen", "Ostrava", "Wren", "Calder", "Nyx", "Bastion", "Ember", "Sorrel", "Thane", "Corvid", "Delphine", "Kestrel", "Lyra", "Moth", "Orrin" };

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
        if (loadouts) {
            for (String[][] g : new String[][][] {
                    { { "R ARM", "WS0007" }, { "HEAVY RIFLE", "Heavy Rifle" }, { "LASER RIFLE", "Laser Rifle" }, { "PLASMA FLAMER", "Plasma Flamer" }, { "MINIGUN", "Minigun" } },
                    { { "L ARM", "WS0008" }, { "BLADE", "Laser Blade" }, { "STAKE DRIVER", "Stake Driver" }, { "DISPERSAL GRENADE", "Dispersal Grenade" } } }) {
                wingman.net.RefitData.Loadout lo = new wingman.net.RefitData.Loadout();
                lo.name = g[0][0]; lo.slots.add(g[0][1]);
                for (int i = 1; i < g.length; i++) {
                    lo.mods.add(lo.name + ": " + g[i][0]); lo.labels.add(g[i][0]); lo.hulls.add("eagle");
                    lo.weapons.add(new ArrayList<String>(Arrays.asList(g[i][1])));
                }
                v.refitData.loadouts.add(lo);
                v.refitData.hullmods.add(lo.mods.get(0));
            }
            l.refit.setData(v.refitData);
            wingman.net.RefitRequest q = (wingman.net.RefitRequest) get(l.refit, "req");
            q.removeMods.add("R ARM: HEAVY RIFLE"); q.addMods.add("R ARM: PLASMA FLAMER");
            try {
                java.lang.reflect.Method rows = RefitScreen.class.getDeclaredMethod("rows"); rows.setAccessible(true);
                List<?> rs = (List<?>) rows.invoke(l.refit);
                for (int i = 0; i < rs.size(); i++) if ((Integer) get(rs.get(i), "kind") == 9) { set(l.refit, "rowAt", i); break; }
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (groupsShot) {
            v.refitData.groups = new ArrayList<wingman.net.RefitData.Group>();
            v.refitData.liveGroups = true;
            l.refit.setData(v.refitData);
            wingman.net.RefitRequest q = (wingman.net.RefitRequest) get(l.refit, "req");
            q.groups = new ArrayList<wingman.net.RefitData.Group>();
            wingman.net.RefitData.Group g1 = new wingman.net.RefitData.Group(); g1.slots.addAll(v.refitData.fitSlots.subList(0, Math.min(2, v.refitData.fitSlots.size())));
            q.groups.add(g1);
            try {
                java.lang.reflect.Method rows = RefitScreen.class.getDeclaredMethod("rows"); rows.setAccessible(true);
                List<?> rs = (List<?>) rows.invoke(l.refit);
                for (int i = 0; i < rs.size(); i++) if ((Integer) get(rs.get(i), "kind") == 8) { set(l.refit, "rowAt", i); break; }
            } catch (Exception e) { throw new RuntimeException(e); }
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
        FriendRefit.Pending pd2 = new FriendRefit.Pending();
        java.lang.reflect.Constructor<wingman.net.HostSession.Player> pc2 = wingman.net.HostSession.Player.class.getDeclaredConstructor(int.class, wingman.net.Conn.class);
        pc2.setAccessible(true);
        wingman.net.HostSession.Player cy = pc2.newInstance(3, null);
        cy.name = "Cy"; cy.slot = 3;
        pd2.player = cy; pd2.ship = "ISS Patience, Dominator"; pd2.request = new wingman.net.RefitRequest(); pd2.plan = pd.plan;
        pd.at = System.currentTimeMillis() - 120000L;
        new FriendRefitPanel().draw(Arrays.asList(pd, pd2), 0);
    }

    /** A few lines said, and everyone's round trip. */
    static void chat() {
        wingman.net.ChatLog.clear();
        wingman.net.ChatLog.add(2, "Kyrizion", "anyone got a spare heavy autocannon?");
        wingman.net.ChatLog.add(1, "NEOysod", "check the wish list, I'll grab one at the next market");
        wingman.net.ChatLog.add(3, "Cy", "gonna take the Dominator if nobody minds");
        wingman.net.ChatLog.add(4, "Mosk", "lag is bad tonight, sorry if I drift off");
        wingman.net.ChatLog.add(2, "Kyrizion", "ty!");
        java.util.Map<Integer, Integer> m = new java.util.LinkedHashMap<Integer, Integer>();
        m.put(2, 54); m.put(3, 213); m.put(4, 96);
        wingman.net.Pings.onMessage(wingman.net.Pings.encode(m));
    }

    static void playerList() throws Exception {
        CampaignPlayerList pl = new CampaignPlayerList();
        set(pl, "openSlot", 2);
        List<CampaignPlayerList.Row> rows = new ArrayList<CampaignPlayerList.Row>();
        Object[][] d = { { 1, "NEOysod (you)", "host", false, "", 1, "" }, { 2, "Kyrizion", "ISS Long Wait, Eagle", false, "Mira Okonkwo", 1, "ISS Long Wait, Eagle" },
                { 3, "Cy", "", false, "", 2, "" }, { 4, "Mosk", "ISS Patience, Dominator", true, "Ana Weiss", 0, "" } };
        for (Object[] x : d) {
            CampaignPlayerList.Row r = new CampaignPlayerList.Row();
            r.slot = (Integer) x[0]; r.name = (String) x[1]; r.ship = (String) x[2]; r.quiet = (Boolean) x[3]; r.host = r.slot == 1;
            r.officer = (String) x[4]; r.permission = (Integer) x[5]; r.refitShip = (String) x[6];
            r.ping = r.slot == 2 ? 54 : r.slot == 3 ? 213 : -1;
            if (!r.officer.isEmpty()) r.officerNote = "Level 5, aggressive  -  captains the ship " + r.name + " picks";
            rows.add(r);
        }
        for (int i = 0; i < big - 4; i++) {
            CampaignPlayerList.Row r = new CampaignPlayerList.Row();
            r.slot = 5 + i; r.name = BIG_NAMES[i % BIG_NAMES.length]; r.ship = i % 3 == 0 ? "" : "ISS Wanderer " + (i + 1) + ", Hammerhead"; r.permission = 1;
            rows.add(r);
        }
        if (whole) set(pl, "openSlot", 3);
        if (big > 0) set(pl, "scroll", 6);
        pl.draw(rows, 1);
    }

    static void officers() {
        OfficerLinkPanel p = new OfficerLinkPanel();
        OfficerLinkPanel.View v = new OfficerLinkPanel.View();
        String[][] f = { { "2", "Kyrizion" }, { "3", "Cy" }, { "4", "Mosk" } };
        for (String[] x : f) { OfficerLinkPanel.Friend fr = new OfficerLinkPanel.Friend(); fr.slot = Integer.parseInt(x[0]); fr.name = x[1]; v.friends.add(fr); }
        for (int i = 0; i < big - 4; i++) { OfficerLinkPanel.Friend fr = new OfficerLinkPanel.Friend(); fr.slot = 5 + i; fr.name = BIG_NAMES[i % BIG_NAMES.length]; v.friends.add(fr); }
        if (big > 0) v.friend = 14;
        Object[][] os = { { "Mira Okonkwo", 5, "aggressive", "Linked to Kyrizion  -  ISS Long Wait, Eagle", "mine", "helmsmanship+ combat_endurance impact_mitigation+ field_modulation target_analysis" },
                { "Tomas Reyes", 4, "steady", "Captain of ISS Hand of Fate, Conquest", "Leaves ISS Hand of Fate, Conquest when Kyrizion picks a ship.", "point_defense ordnance_expertise+ gunnery_implants damage_control" },
                { "Ana Weiss", 6, "cautious", "Linked to Cy", "Moves over from Cy.", "systems_expertise+ missile_specialization energy_weapon_mastery helmsmanship+ field_modulation polarized_armor+" },
                { "Ido Hartmann", 2, "reckless", "Unassigned", "", "combat_endurance target_analysis" },
                { "Sun Qian", 3, "steady", "Captain of ISS Lantern, Heron", "Leaves ISS Lantern, Heron when Kyrizion picks a ship.", "damage_control impact_mitigation+ point_defense" },
                { "Erik Laine", 1, "timid", "Unassigned", "", "gunnery_implants" } };
        for (Object[] o : os) {
            OfficerLinkPanel.Officer x = new OfficerLinkPanel.Officer();
            x.name = (String) o[0]; x.level = (Integer) o[1]; x.personality = (String) o[2]; x.status = (String) o[3];
            x.mine = "mine".equals(o[4]); x.moveNote = x.mine ? "" : (String) o[4];
            x.statusColor = x.mine ? Panel.GOOD : x.status.startsWith("Linked") ? Panel.WARN : Panel.DIM;
            for (String sk : ((String) o[5]).split(" ")) { x.skills.add(sk.replace("+", "")); x.elite.add(sk.endsWith("+")); }
            v.officers.add(x);
        }
        v.picked = whole ? 2 : 0;
        p.draw(v);
    }

    static void levelUp() {
        try {
            java.lang.reflect.Field f = OfficerChoice.class.getDeclaredField("skills"); f.setAccessible(true);
            f.set(null, new ArrayList<String>(Arrays.asList("ballistic_mastery", "systems_expertise", "point_defense", "damage_control")));
        } catch (Exception e) { throw new RuntimeException(e); }
        OfficerChoice.officer = "Mira Okonkwo"; OfficerChoice.toLevel = 6; OfficerChoice.chosen = 1;
        lobby(LobbyScreen.FLEET, true);
        OfficerChoice.forget();
    }

    static List<Shot> all() {
        List<Shot> out = new ArrayList<Shot>();
        out.add(new Shot("ready-check", 1920, 1080, -1, -1, new Runnable() { public void run() {
            new Panel().render("READY CHECK", Arrays.asList("Waiting for everyone to load the battle.", "P1  NEOysod (host):  ready", "P2  Kyrizion:  loading ships (41 of 63)", "P3  Cy:  ready"),
                    Arrays.asList(Panel.TEXT, Panel.GOOD, Panel.WARN, Panel.GOOD), "Enter: start anyway     Esc: cancel");
        } }));
        out.add(new Shot("coop-join", 1920, 1080, -1, -1, new Runnable() { public void run() { coop(false); } }));
        out.add(new Shot("coop-connected", 1920, 1080, -1, -1, new Runnable() { public void run() { coop(true); } }));
        out.add(new Shot("coop-join-1366", 1366, 768, -1, -1, new Runnable() { public void run() { coop(false); } }));
        out.add(new Shot("coop-connected-1366", 1366, 768, -1, -1, new Runnable() { public void run() { coop(true); } }));
        out.add(new Shot("settings", 1920, 1080, -1, -1, new Runnable() { public void run() { settings(3, 6); } }));
        out.add(new Shot("settings-trouble-1366", 1366, 768, -1, -1, new Runnable() { public void run() { settings(5, 1); } }));
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
        out.add(new Shot("refit-groups", 1920, 1080, -1, -1, new Runnable() { public void run() { groupsShot = true; refit(false); groupsShot = false; } }));
        out.add(new Shot("refit-loadout", 1920, 1080, -1, -1, new Runnable() { public void run() { loadouts = true; refit(false); loadouts = false; } }));
        out.add(new Shot("refit-planning", 1920, 1080, -1, -1, new Runnable() { public void run() { refit(true, false); } }));
        out.add(new Shot("friend-refit-host", 1920, 1080, -1, -1, new Runnable() { public void run() { try { friendRefit(); } catch (Exception e) { throw new RuntimeException(e); } } }));
        out.add(new Shot("player-list", 1920, 1080, -1, -1, new Runnable() { public void run() { try { playerList(); } catch (Exception e) { throw new RuntimeException(e); } } }));
        out.add(new Shot("player-list-cy", 1920, 1080, 1700, 1000, new Runnable() { public void run() { whole = true; try { playerList(); } catch (Exception e) { throw new RuntimeException(e); } whole = false; } }));
        out.add(new Shot("battle-popup", 1920, 1080, 960, 520, new Runnable() { public void run() {
            Panel p = new Panel();
            Panel.Doc d = new Panel.Doc("CONNECTION LOST").keys("Enter: leave the battle     Backspace: stay");
            d.line("Nothing has come from the host for twelve seconds.", Panel.WARN);
            d.line("Your game will try to reconnect by itself.", Panel.WARN);
            d.buttons(Panel.Doc.button("LEAVE THE BATTLE", 28, Panel.WARN), Panel.Doc.button("STAY", 14, Panel.TEXT));
            p.mouse = true;
            p.render(d);
        } }));
        out.add(new Shot("officers", 1920, 1080, -1, -1, new Runnable() { public void run() { officers(); } }));
        out.add(new Shot("big-officers", 1920, 1080, -1, -1, new Runnable() { public void run() { big = 32; officers(); big = 0; } }));
        out.add(new Shot("big-player-list", 1920, 1080, -1, -1, new Runnable() { public void run() { big = 32; try { playerList(); } catch (Exception e) { throw new RuntimeException(e); } big = 0; } }));
        out.add(new Shot("big-lobby", 1920, 1080, -1, -1, new Runnable() { public void run() { big = 32; lobby(LobbyScreen.FLEET, true); big = 0; } }));
        out.add(new Shot("big-lobby-1366", 1366, 768, -1, -1, new Runnable() { public void run() { big = 32; lobby(LobbyScreen.FLEET, true); big = 0; } }));
        out.add(new Shot("officers-1366", 1366, 768, -1, -1, new Runnable() { public void run() { whole = true; officers(); whole = false; } }));
        out.add(new Shot("settings-interface", 1920, 1080, -1, -1, new Runnable() { public void run() { settings(1, 0); } }));
        out.add(new Shot("settings-keys", 1920, 1080, -1, -1, new Runnable() { public void run() { settings(2, 1); } }));
        out.add(new Shot("chat-host-open", 1920, 1080, -1, -1, new Runnable() { public void run() {
            chat(); ChatBox.host.open = true; ChatBox.host.typing = true; ChatBox.host.draft.append("docking at Jangala, refits in a sec");
            try { playerList(); } catch (Exception e) { throw new RuntimeException(e); }
            ChatBox.host.open = false; ChatBox.host.typing = false; ChatBox.host.draft.setLength(0);
        } }));
        out.add(new Shot("chat-host-folded", 1920, 1080, -1, -1, new Runnable() { public void run() {
            chat(); try { playerList(); } catch (Exception e) { throw new RuntimeException(e); }
        } }));
        out.add(new Shot("chat-lobby", 1920, 1080, -1, -1, new Runnable() { public void run() { chat(); lobby(LobbyScreen.FLEET, false); } }));
        out.add(new Shot("chat-lobby-1366", 1366, 768, -1, -1, new Runnable() { public void run() { chat(); lobby(LobbyScreen.FLEET, false); } }));
        out.add(new Shot("ping-mine", 1920, 1080, -1, -1, new Runnable() { public void run() { PingReadout.draw("mine", 212, 540, null); } }));
        out.add(new Shot("ping-everyone", 1920, 1080, -1, -1, new Runnable() { public void run() {
            java.util.Map<Integer, Integer> m = new java.util.LinkedHashMap<Integer, Integer>();
            m.put(1, 0); m.put(2, 54); m.put(3, 213); m.put(4, 96); m.put(5, 260);
            PingReadout.draw("everyone", 54, 60, m);
        } }));
        out.add(new Shot("keyhelp-officers", 1920, 1080, -1, -1, new Runnable() { public void run() { officers(); KeyHelp.toggle("Officer links"); KeyHelp.draw("Officer links"); KeyHelp.close(); } }));
        out.add(new Shot("keyhelp-coop", 1280, 720, -1, -1, new Runnable() { public void run() {
            try { playerList(); } catch (Exception e) { throw new RuntimeException(e); }
            KeyHelp.toggle("CO-OP panel"); KeyHelp.draw("CO-OP panel"); KeyHelp.close();
        } }));
        out.add(new Shot("colorblind-lobby", 1920, 1080, -1, -1, new Runnable() { public void run() { PlayerTags.colorblind = true; big = 10; lobby(LobbyScreen.FLEET, true); big = 0; PlayerTags.colorblind = false; } }));
        out.add(new Shot("scale-125-player-list", 1920, 1080, -1, -1, new Runnable() { public void run() {
            Style.ui = 1.25f; chat(); ChatBox.host.open = true;
            try { playerList(); } catch (Exception e) { throw new RuntimeException(e); }
            ChatBox.host.open = false; Style.ui = 1f;
        } }));
        out.add(new Shot("scale-075-lobby", 1920, 1080, -1, -1, new Runnable() { public void run() { Style.ui = 0.75f; lobby(LobbyScreen.FLEET, false); Style.ui = 1f; } }));
        out.add(new Shot("lobby-levelup", 1920, 1080, -1, -1, new Runnable() { public void run() { levelUp(); } }));
        out.add(new Shot("lobby-levelup-1366", 1366, 768, -1, -1, new Runnable() { public void run() { levelUp(); } }));
        return out;
    }
}
