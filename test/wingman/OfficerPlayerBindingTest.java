package wingman;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.net.Conn;
import wingman.net.HostSession;

/** Strict API doubles exercise real binding, reservation lookup, and lobby eligibility code. */
public final class OfficerPlayerBindingTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        com.fs.starfarer.api.Global.setSettings(proxy(com.fs.starfarer.api.SettingsAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getFloat": return 1f;
                case "getInt": return 1;
                case "getBoolean": return false;
                case "getColor": return java.awt.Color.WHITE;
                default: throw unexpected(m.getName());
            }
        }));
        check(OfficerPlayerBinding.identity(" Alice ").equals("alice"), "case-insensitive stable name");
        check(OfficerPlayerBinding.identity(null).isEmpty(), "missing name rejected");
        acquisition();
        following();
        conflicts();
        exclusions();
        if (args.length > 0) {
            org.json.JSONObject settings = new org.json.JSONObject(new String(Files.readAllBytes(
                    Paths.get(args[0], "data/config/settings.json")), StandardCharsets.UTF_8));
            check(settings.getJSONObject("plugins").getString("wingman_officer_binding")
                    .equals(OfficerPlayerCombatPlugin.class.getName()), "combat observer registered");
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(Paths.get(args[0], "jars/Wingman.jar").toFile())) {
                for (String name : new String[] { "CampaignLobby", "OfficerPlayerBinding", "OfficerPlayerCombatPlugin" }) {
                    check(jar.getJarEntry("wingman/" + name + ".class") != null, "packaged " + name);
                }
            }
        }
        WingmanAPI.reserveShip("Alice", null);
        WingmanAPI.reserveShip("Bob", null);
        System.out.println("Officer player binding: " + checks + " checks passed (combat detection, captain swaps, safety and packaging).");
    }

    private static void acquisition() throws Exception {
        World w = new World();
        w.observe();
        check(w.a.tags.contains("wingman_player:alice"), "actual remote pilot binds skilled captain");
        check(w.one.captain == w.a.person && w.two.captain == w.b.person && w.syncs == 0, "combat never moves captains");
        w.observe();
        check(w.a.tags.size() == 1, "binding idempotent");
        w.b.skill = true;
        w.players.get(0).ship = w.two.ship;
        w.observe();
        check(w.b.tags.isEmpty(), "same player cannot acquire second officer in combat");
        w.players = Arrays.asList(player(99, "ALICE", w.one.ship));
        w.observe();
        check(w.a.tags.size() == 1, "reconnect with new transient ID retains identity");
        w.players = Arrays.asList(player(100, "Bob", w.one.ship));
        w.observe();
        check(w.a.tags.contains("wingman_player:alice"), "different pilot cannot steal existing binding");
        w = new World(); w.a.skill = false; w.observe();
        check(w.a.tags.contains("wingman_player:alice") && w.a.skill, "linking grants Officer Potential to an officer without it");
        w = new World(); w.a.player = true; w.observe();
        check(w.a.tags.isEmpty(), "host character cannot bind");
        w = new World(); w.a.generic = true; w.observe();
        check(w.a.tags.isEmpty(), "default captain cannot bind");
        w = new World(); w.a.core = true; w.observe();
        check(w.a.tags.isEmpty(), "AI core cannot bind");
        w = new World(); w.roster.clear(); w.observe();
        check(w.a.tags.isEmpty(), "non-roster captains cannot bind");
        w = new World(); w.members.remove(w.one.member); w.observe();
        check(w.a.tags.isEmpty(), "non-fleet ships cannot bind");
        w = new World(); w.one.owner = 1; w.observe();
        check(w.a.tags.isEmpty(), "enemy ships cannot bind");
        w = new World(); w.one.alive = false; w.observe();
        check(w.a.tags.isEmpty(), "dead ships cannot bind");
        w = new World(); w.inPlay = false; w.observe();
        check(w.a.tags.isEmpty(), "removed ships cannot bind");
        w = new World(); w.one.combatCaptain = w.b.person; w.observe();
        check(w.a.tags.isEmpty(), "temporary combat captain mismatch cannot bind");
        w = new World(); w.players.get(0).ship = null; w.observe();
        check(w.a.tags.isEmpty(), "spectators cannot bind");
        w = new World(); w.one.noMember = true; w.observe();
        check(w.a.tags.isEmpty(), "fighters without fleet member cannot bind");
        w = new World(); w.flagship = w.one.member; w.observe();
        check(w.a.tags.isEmpty(), "flagship cannot bind");
    }

    private static void following() throws Exception {
        World w = new World(); w.observe(); w.reserve("two"); w.follow();
        check(w.two.captain == w.a.person && w.one.captain == w.b.person, "occupied destination swaps both captains");
        check(w.roster.size() == 2 && w.a.tags.contains("wingman_player:alice"), "swap retains roster and tag");
        check(w.syncs == 1, "swap invalidates fleet cache");
        w.follow();
        check(w.syncs == 1, "unchanged reservation performs no writes");
        w.players = Arrays.asList(player(200, "alice", null)); w.reserve("three"); w.follow();
        check(w.three.captain == w.a.person && w.two.captain == null, "reconnected player moves officer to empty ship");
        w.a.skill = false; w.reserve("one"); w.follow();
        check(w.one.captain == w.a.person && w.a.skill, "a lost skill is restored and the officer still follows");
        check(w.a.tags.contains("wingman_player:alice"), "following keeps the saved identity");
        w.a.skill = true; w.reserve(null); w.follow();
        check(w.one.captain == w.a.person, "clearing pick does not unassign officer");
        w.reserve("missing"); w.follow();
        check(w.one.captain == w.a.person, "lost ship reservation ignored");
        w.reserve("one"); w.players = new ArrayList<HostSession.Player>(); w.follow();
        check(w.one.captain == w.a.person, "disconnected player reservation ignored");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.one.captain = null; w.reserve("two"); w.follow();
        check(w.two.captain == w.a.person, "unassigned roster officer follows pick");
        check(w.roster.size() == 2 && !w.hasCaptain(w.b.person), "displaced officer remains safely unassigned in roster");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.roster.remove(w.a.data); w.reserve("two"); w.follow();
        check(w.two.captain == w.b.person, "dismissed officer not restored");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.a.tags.clear(); w.reserve("two"); w.follow();
        check(w.one.captain == w.a.person, "manual tag removal unbinds");
        w = new World(); w.reserve("one"); w.follow();
        check(w.a.tags.contains("wingman_player:alice"), "map pick of skilled officer's ship binds without combat");
        check(w.one.captain == w.a.person && w.syncs == 0, "binding by pick moves no captains");
        w.reserve("two"); w.follow();
        check(w.two.captain == w.a.person && w.one.captain == w.b.person, "officer bound by pick follows next pick");
        check(w.b.tags.isEmpty(), "bound player's later pick does not bind second officer");
        w = new World(); w.reserve("two"); w.follow();
        check(w.b.tags.contains("wingman_player:alice") && w.b.skill && w.a.tags.isEmpty(), "map pick of an ordinary officer's ship binds it and grants the skill");
        w = new World(); w.a.tags.add("wingman_player:bob"); w.reserve("one"); w.follow();
        check(w.a.tags.size() == 1, "map pick cannot steal another player's officer");
        w = new World(); w.flagship = w.one.member; w.reserve("one"); w.follow();
        check(w.a.tags.isEmpty(), "flagship pick cannot bind");
    }

    private static void conflicts() throws Exception {
        World w = new World(); w.players.add(player(2, "ALICE", w.two.ship)); w.observe();
        check(w.a.tags.isEmpty(), "duplicate connected names prevent acquisition");
        w.a.tags.add("wingman_player:alice"); w.reserve("two"); w.follow();
        check(w.one.captain == w.a.person, "duplicate connected names prevent following");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.b.tags.add("wingman_player:alice"); w.reserve("three"); w.follow();
        check(w.three.captain == null, "duplicate bound officers fail closed");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.a.tags.add("wingman_player:bob"); w.reserve("two"); w.follow(); w.observe();
        check(w.one.captain == w.a.person && w.a.tags.size() == 2, "conflicting tags neither move nor rebind");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.three.captain = w.a.person; w.reserve("two"); w.follow();
        check(w.two.captain == w.b.person, "duplicate source assignment fails closed");
        w = new World(); w.a.tags.add("wingman_player:alice"); w.b.tags.add("wingman_player:bob");
        w.b.skill = true; w.players.add(player(2, "Bob", null));
        w.reserve("three"); WingmanAPI.reserveShip("Bob", "three"); w.follow();
        check(w.three.captain == null, "conflicting target reservations fail closed");
        WingmanAPI.reserveShip("Bob", "one"); w.reserve("two"); w.follow();
        check(w.one.captain == w.b.person && w.two.captain == w.a.person, "two bound officers exchange ships without oscillation");
        int syncs = w.syncs; w.follow();
        check(w.syncs == syncs, "multi-player result stable on next frame");
        WingmanAPI.reserveShip("Bob", null);
    }

    private static void exclusions() throws Exception {
        World w = new World(); w.observe(); w.reserve("two"); w.two.mothballed = true; w.follow();
        check(w.one.captain == w.a.person, "mothballed target rejected");
        w.two.mothballed = false; w.two.station = true; w.follow();
        check(w.one.captain == w.a.person, "station target rejected");
        w.two.station = false; w.two.automated = true; w.follow();
        check(w.one.captain == w.a.person, "automated target rejected");
        w.two.automated = false; w.cfg.maxShipSize = "FRIGATE"; w.follow();
        check(w.one.captain == w.a.person, "host hull-size limit retained");
        w.cfg.maxShipSize = "CAPITAL_SHIP"; w.flagship = w.two.member; w.follow();
        check(w.one.captain == w.a.person, "flagship target rejected");
        w.flagship = w.one.member; w.reserve("three"); w.follow();
        check(w.one.captain == w.a.person, "officer commanding flagship not moved");
        w.flagship = w.flag.member; w.reserve("two"); w.b.player = true; w.follow();
        check(w.one.captain == w.a.person, "host character on non-flagship target protected");
        w.b.player = false; w.b.locked = true; w.follow();
        check(w.one.captain == w.a.person, "unremovable destination captain protected");
        w.b.locked = false; w.b.core = true; w.follow();
        check(w.one.captain == w.a.person, "AI-core destination captain protected");
        w.b.core = false; w.a.locked = true; w.follow();
        check(w.one.captain == w.a.person, "unremovable bound officer not moved");
        w = new World(); w.a.locked = true; w.observe();
        check(w.a.tags.isEmpty(), "unremovable officer cannot acquire binding");
    }

    private static final class Person {
        boolean skill, player, generic, core, locked;
        final com.fs.starfarer.api.campaign.rules.MemoryAPI memory = proxy(
                com.fs.starfarer.api.campaign.rules.MemoryAPI.class, (p, m, a) -> {
            if (m.getName().equals("is")) return locked && a[0].equals("$captain_unremovable") && a[1].equals(true);
            throw unexpected(m.getName());
        });
        final Set<String> tags = new HashSet<String>();
        final MutableCharacterStatsAPI stats = proxy(MutableCharacterStatsAPI.class, (p, m, a) -> {
            if (m.getName().equals("hasSkill")) return skill && a[0].equals(OfficerPotential.ID);
            if (m.getName().equals("setSkillLevel") && a[0].equals(OfficerPotential.ID)) { skill = ((Float) a[1]) > 0f; return null; } // linking grants it
            throw unexpected(m.getName());
        });
        final PersonAPI person = proxy(PersonAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "isPlayer": return player;
                case "isDefault": return generic;
                case "isAICore": return core;
                case "getStats": return stats;
                case "getMemoryWithoutUpdate": return memory;
                case "getTags": return tags;
                case "addTag": tags.add((String) a[0]); return null;
                default: throw unexpected(m.getName());
            }
        });
        final OfficerDataAPI data = proxy(OfficerDataAPI.class, (p, m, a) -> {
            if (m.getName().equals("getPerson")) return person;
            throw unexpected(m.getName());
        });
    }

    private static final class Member {
        String id;
        PersonAPI captain, combatCaptain;
        boolean mothballed, station, automated, noMember, alive = true;
        int owner;
        final ShipHullSpecAPI hull = proxy(ShipHullSpecAPI.class, (p, m, a) -> {
            if (m.getName().equals("getHullSize")) return ShipAPI.HullSize.CRUISER;
            if (m.getName().equals("hasTag")) return false;
            throw unexpected(m.getName());
        });
        final ShipVariantAPI variant = proxy(ShipVariantAPI.class, (p, m, a) -> {
            if (m.getName().equals("hasHullMod")) return automated && a[0].equals("automated");
            if (m.getName().equals("hasTag")) return false;
            if (m.getName().equals("getHullSpec")) return hull;
            throw unexpected(m.getName());
        });
        final FleetMemberAPI member = proxy(FleetMemberAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getId": return id;
                case "getCaptain": return captain;
                case "setCaptain": captain = (PersonAPI) a[0]; return null;
                case "isMothballed": return mothballed;
                case "isStation": return station;
                case "getVariant": return variant;
                case "getHullSpec": return hull;
                default: throw unexpected(m.getName());
            }
        });
        final ShipAPI ship = proxy(ShipAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getOwner": return owner;
                case "isAlive": return alive;
                case "getFleetMember": return noMember ? null : member;
                case "getCaptain": return combatCaptain == null ? captain : combatCaptain;
                default: throw unexpected(m.getName());
            }
        });
        Member(String id, PersonAPI captain) { this.id = id; this.captain = captain; }
    }

    private static final class World {
        final Person a = new Person(), b = new Person(), host = new Person();
        final Member one = new Member("one", a.person), two = new Member("two", b.person),
                three = new Member("three", null), flag = new Member("flag", host.person);
        final List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>(Arrays.asList(one.member, two.member, three.member, flag.member));
        final List<OfficerDataAPI> roster = new ArrayList<OfficerDataAPI>(Arrays.asList(a.data, b.data));
        List<HostSession.Player> players = new ArrayList<HostSession.Player>();
        FleetMemberAPI flagship = flag.member;
        final WingmanConfig cfg = new WingmanConfig();
        boolean inPlay = true;
        int syncs;
        final FleetDataAPI data = proxy(FleetDataAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getOfficersCopy": return new ArrayList<OfficerDataAPI>(roster);
                case "getMembersListCopy": return new ArrayList<FleetMemberAPI>(members);
                case "setSyncNeeded": syncs++; return null;
                default: throw unexpected(m.getName());
            }
        });
        final CampaignFleetAPI fleet = proxy(CampaignFleetAPI.class, (p, m, a) -> {
            if (m.getName().equals("getFleetData")) return data;
            if (m.getName().equals("getFlagship")) return flagship;
            throw unexpected(m.getName());
        });
        final CombatEngineAPI engine = proxy(CombatEngineAPI.class, (p, m, a) -> {
            if (m.getName().equals("isEntityInPlay")) return inPlay;
            throw unexpected(m.getName());
        });
        World() throws Exception {
            a.skill = true; host.player = true; cfg.maxShipSize = "CAPITAL_SHIP";
            players.add(player(1, "Alice", one.ship));
            WingmanAPI.reserveShip("Alice", null); WingmanAPI.reserveShip("Bob", null);
        }
        void observe() { OfficerPlayerBinding.observe(fleet, players, engine); }
        void follow() { OfficerPlayerBinding.follow(fleet, players, cfg); }
        void reserve(String id) { WingmanAPI.reserveShip("Alice", id); }
        boolean hasCaptain(PersonAPI person) {
            return one.captain == person || two.captain == person || three.captain == person || flag.captain == person;
        }
    }

    private static HostSession.Player player(int id, String name, Object ship) throws Exception {
        Constructor<HostSession.Player> constructor = HostSession.Player.class.getDeclaredConstructor(int.class, Conn.class);
        constructor.setAccessible(true);
        HostSession.Player player = constructor.newInstance(id, null);
        player.name = name; player.ship = ship;
        return player;
    }

    private static AssertionError unexpected(String name) { return new AssertionError("Unexpected API call: " + name); }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("toString")) return type.getSimpleName();
            }
            return handler.invoke(p, m, a);
        }));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
