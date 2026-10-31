package wingman.compat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import wingman.PuppetAI;
import wingman.api.WingmanAPI;
import wingman.net.ModChannel;
import wingman.net.Person;

public final class SotfFelTest {
    private static final class Ship {
        final MutableStat hullTaken = new MutableStat(1f);
        final Map<String, Object> custom = new HashMap<String, Object>();
        PersonAPI captain;
        String memberId;
        boolean fighter, alive = true, retreating;
        Object ai;
        final ShipAPI api;

        Ship(String memberId) {
            this.memberId = memberId;
            MutableShipStatsAPI stats = proxy(MutableShipStatsAPI.class, (p, m, a) -> {
                if (m.getName().equals("getHullDamageTakenMult")) return hullTaken;
                throw new AssertionError(m.getName());
            });
            FleetMemberAPI member = proxy(FleetMemberAPI.class, (p, m, a) -> {
                if (m.getName().equals("getId")) return this.memberId;
                throw new AssertionError(m.getName());
            });
            ShipHullSpecAPI hull = proxy(ShipHullSpecAPI.class, (p, m, a) -> "onslaught");
            api = proxy(ShipAPI.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "isFighter": return fighter;
                    case "getMutableStats": return stats;
                    case "getCaptain": return captain;
                    case "getFleetMember": return member;
                    case "getHullSpec": return hull;
                    case "getCustomData": return custom;
                    case "setCustomData": custom.put((String) a[0], a[1]); return null;
                    case "isAlive": return alive;
                    case "isRetreating": return retreating;
                    case "setShipAI": ai = a[0]; return null;
                    case "setCaptain": captain = (PersonAPI) a[0]; return null;
                    default: throw new AssertionError(m.getName());
                }
            });
        }
    }

    private static final class Engine {
        final Map<String, Object> custom = new HashMap<String, Object>();
        final List<ShipAPI> ships = new ArrayList<ShipAPI>();
        final List<Object> removed = new ArrayList<Object>();
        final List<ShipAPI> gone = new ArrayList<ShipAPI>();
        float time;
        boolean paused;
        final CombatEngineAPI api = proxy(CombatEngineAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getCustomData": return custom;
                case "getTotalElapsedTime": return time;
                case "getShips": return ships;
                case "isEntityInPlay": return !gone.contains(a[0]);
                case "isPaused": return paused;
                case "removePlugin": removed.add(a[0]); return null;
                default: throw new AssertionError(m.getName());
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getScriptClassLoader")) return SotfFelTest.class.getClassLoader();
            throw new AssertionError(m.getName());
        }));
        PersonAPI fel = person(SotfFel.FEL, "sotf_leviathansbane", "sotf_hellionshellhide");
        Global.setSector(proxy(SectorAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getMemoryWithoutUpdate": return proxy(MemoryAPI.class, (p2, m2, a2) -> {
                    if (m2.getName().equals("getInt") && SotfFel.NUM_INVASIONS.equals(a2[0])) return 3; // already counts this one
                    throw new AssertionError(m2.getName());
                });
                case "getImportantPeople": return proxy(ImportantPeopleAPI.class, (p2, m2, a2) -> {
                    if (m2.getName().equals("getPerson") && SotfFel.FEL.equals(a2[0])) return fel;
                    throw new AssertionError(m2.getName());
                });
                default: throw new AssertionError(m.getName());
            }
        }));

        hostFindsTheInfestedShip(fel);
        hostFindsFelAsCaptain(fel);
        hostStopsLooking();
        messagesAreChecked(fel);
        joinerWaitsForTheRightCopy(fel);
        shownKeepsTheCopyAPuppet(fel);
        System.out.println("SotfFelTest: all checks passed");
    }

    private static void hostFindsTheInfestedShip(PersonAPI fel) throws IOException {
        Engine e = new Engine();
        Ship other = new Ship("m_other"), target = new Ship("m_target"), wing = new Ship("m_wing");
        wing.fighter = true;
        wing.hullTaken.modifyMult(SotfFel.IMMUNITY_KEY, 0f);
        e.ships.addAll(Arrays.asList(other.api, wing.api, target.api));
        Map<ShipAPI, Integer> ids = new IdentityHashMap<ShipAPI, Integer>();
        ids.put(other.api, 4);
        ids.put(wing.api, 9);

        target.hullTaken.modifyMult(SotfFel.IMMUNITY_KEY, 0f);
        check(SotfFel.news(e.api, ids) == null, "nothing before SotF marks the invasion");
        e.custom.put(SotfFel.INVADED_KEY, true);
        check(SotfFel.news(e.api, ids) == null, "waits while the ship has no net id");
        ids.put(target.api, 7);
        byte[] msg = SotfFel.news(e.api, ids);
        check(msg != null, "names the immune ship");
        SotfFel.Infest m = SotfFel.decode(unwrap(msg));
        check(m.netId == 7 && m.memberId.equals("m_target"), "the infested ship, not the fighter or the other ship");
        check(m.invasions == 2, "invasions before this one");
        check(m.guilt == 0f, "guilt without SotF loaded");
        check(m.fel.skillIds.equals(Arrays.asList("sotf_leviathansbane", "sotf_hellionshellhide")), "Fel's skills for this battle");
        check(SotfFel.news(e.api, ids) == null, "told once a battle");
        check(Arrays.equals(SotfFel.state(e.api), msg), "a joiner synced later is told too");
        target.alive = false;
        check(SotfFel.state(e.api) == null, "not after the ship is lost");
    }

    private static void hostFindsFelAsCaptain(PersonAPI fel) throws IOException {
        Engine e = new Engine();
        Ship target = new Ship("m_target");
        target.captain = fel;
        e.ships.add(target.api);
        e.custom.put(SotfFel.INVADED_KEY, true);
        Map<ShipAPI, Integer> ids = new IdentityHashMap<ShipAPI, Integer>();
        ids.put(target.api, 12);
        byte[] msg = SotfFel.news(e.api, ids);
        check(msg != null && SotfFel.decode(unwrap(msg)).netId == 12, "Fel as captain names the ship once immunity is over");
    }

    private static void hostStopsLooking() {
        Engine e = new Engine();
        Ship target = new Ship("m_target");
        e.ships.add(target.api);
        e.custom.put(SotfFel.INVADED_KEY, true);
        Map<ShipAPI, Integer> ids = new IdentityHashMap<ShipAPI, Integer>();
        ids.put(target.api, 3);
        check(SotfFel.news(e.api, ids) == null, "no infested ship yet");
        e.time = SotfFel.LOOK_FOR + 1f;
        target.hullTaken.modifyMult(SotfFel.IMMUNITY_KEY, 0f);
        check(SotfFel.news(e.api, ids) == null, "stops looking after the window");
    }

    private static void messagesAreChecked(PersonAPI fel) throws IOException {
        SotfFel.Infest m = infest(5, "m", fel);
        SotfFel.Infest back = SotfFel.decode(SotfFel.encode(m));
        check(back.netId == 5 && back.guilt == 120f && back.invasions == 2, "round trip");
        m.fel.id = "someone_else";
        rejects(SotfFel.encode(m), "a person other than Fel");
        m.fel.id = SotfFel.FEL;
        m.guilt = Float.NaN;
        rejects(SotfFel.encode(m), "a guilt that is not a number");
        m.guilt = 1f;
        byte[] ok = SotfFel.encode(m);
        rejects(Arrays.copyOf(ok, ok.length - 3), "a cut-off message");
        ok[0] = 2;
        rejects(ok, "an unknown version");
    }

    private static void joinerWaitsForTheRightCopy(PersonAPI fel) throws IOException {
        Engine e = new Engine();
        byte[] data = SotfFel.encode(infest(7, "m_target", fel));
        SotfFel.wanted = null;
        SotfFel.heard(3, data);
        check(SotfFel.wantedId() == -1, "a joiner's word is ignored");
        SotfFel.heard(WingmanAPI.HOST, new byte[] { 1, 2 });
        check(SotfFel.wantedId() == -1, "a garbled message is ignored");
        SotfFel.heard(WingmanAPI.HOST, data);
        check(SotfFel.wantedId() == 7, "the host's word is kept");

        SotfFel.show(e.api, null);
        check(SotfFel.wantedId() == 7, "kept until the copy arrives");
        Ship wrong = new Ship("m_other");
        SotfFel.show(e.api, wrong.api);
        check(SotfFel.wantedId() == 7 && wrong.custom.isEmpty(), "not shown on a copy of another ship");
        Ship copy = new Ship("m_target");
        SotfFel.show(e.api, copy.api);
        check(SotfFel.wantedId() == -1 && copy.custom.containsKey(SotfFel.STATE), "taken by the right copy");

        SotfFel.heard(WingmanAPI.HOST, data);
        SotfFel.wanted.at -= SotfFel.WAIT_MS + 1;
        SotfFel.show(e.api, null);
        check(SotfFel.wantedId() == -1, "forgotten when the copy never comes");
    }

    private static void shownKeepsTheCopyAPuppet(PersonAPI fel) {
        Engine e = new Engine();
        Ship copy = new Ship("m");
        ShipAIPlugin felAI = proxy(ShipAIPlugin.class, (p, m, a) -> null);
        int[] frames = { 0 };
        boolean[] explode = { false };
        EveryFrameCombatPlugin inner = proxy(EveryFrameCombatPlugin.class, (p, m, a) -> {
            if (m.getName().equals("advance")) {
                if (explode[0]) throw new IllegalStateException("SotF failed");
                if (++frames[0] == 2) { copy.captain = fel; copy.ai = felAI; } // the hand-over
            }
            return null;
        });
        SotfFel.Shown shown = new SotfFel.Shown(inner, copy.api);
        shown.init(e.api);
        shown.advance(0.1f, null);
        check(copy.ai == null, "no AI change before the hand-over");
        shown.advance(0.1f, null);
        check(copy.ai instanceof PuppetAI, "the copy is a puppet again in the hand-over's frame");
        copy.alive = false;
        e.paused = true;
        shown.advance(0f, null);
        shown.advance(0f, null);
        check(e.removed.isEmpty(), "paused: the end has not begun");
        e.paused = false;
        shown.advance(0.25f, null);
        for (int i = 0; i < 4; i++) shown.advance(0.25f, null);
        check(e.removed.isEmpty(), "still fading for a second");
        shown.advance(0.1f, null);
        check(e.removed.contains(shown), "gone once SotF's would be");

        Engine e2 = new Engine();
        Ship copy2 = new Ship("m");
        SotfFel.Shown failing = new SotfFel.Shown(inner, copy2.api);
        failing.init(e2.api);
        explode[0] = true;
        failing.advance(0.1f, null);
        check(copy2.ai instanceof PuppetAI && e2.removed.isEmpty(), "a failure is held, the copy stays a puppet");
        failing.advance(0.1f, null);
        check(e2.removed.contains(failing), "and the plugin goes");

        Engine e3 = new Engine();
        Ship copy3 = new Ship("m");
        explode[0] = false;
        SotfFel.Shown left = new SotfFel.Shown(inner, copy3.api);
        left.init(e3.api);
        e3.gone.add(copy3.api);
        left.advance(0.1f, null);
        check(e3.removed.contains(left), "goes with a copy that left the battle");
    }

    private static SotfFel.Infest infest(int netId, String memberId, PersonAPI fel) {
        SotfFel.Infest m = new SotfFel.Infest();
        m.netId = netId;
        m.memberId = memberId;
        m.guilt = 120f;
        m.invasions = 2;
        m.fel = new Person();
        m.fel.id = fel.getId();
        return m;
    }

    /** Through the joiner's own receiving path: what the listener gets. */
    private static byte[] unwrap(byte[] modPayload) {
        check(ModChannel.received("host", WingmanAPI.HOST, modPayload), "a well-formed mod message");
        ModChannel.Message m = ModChannel.poll();
        check(m != null && SotfFel.CHANNEL.equals(m.channel), "sent on Fel's channel");
        return m.data;
    }

    private static void rejects(byte[] data, String what) {
        try {
            SotfFel.decode(data);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("accepted " + what);
    }

    private static PersonAPI person(String id, String... skills) {
        List<MutableCharacterStatsAPI.SkillLevelAPI> levels = new ArrayList<MutableCharacterStatsAPI.SkillLevelAPI>();
        for (String s : skills) {
            SkillSpecAPI spec = proxy(SkillSpecAPI.class, (p, m, a) -> s);
            levels.add(proxy(MutableCharacterStatsAPI.SkillLevelAPI.class, (p, m, a) -> m.getName().equals("getLevel") ? (Object) 2f : spec));
        }
        MutableCharacterStatsAPI stats = proxy(MutableCharacterStatsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getSkillsCopy")) return levels;
            if (m.getName().equals("getLevel")) return 8;
            return null;
        });
        return proxy(PersonAPI.class, (p, m, a) -> {
            if (m.getName().equals("getId")) return id;
            if (m.getName().equals("getStats")) return stats;
            return null;
        });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    default: return type.getSimpleName();
                }
            }
            return h.invoke(p, m, a);
        });
    }
}
