package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import wingman.net.In;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wingman.net.Conn;
import wingman.net.HostCombatFixture;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.client.HullGuard;
import wingman.client.WingmanClientPlugin;
import wingman.host.RemotePilotAI;

/** Exercises actual host lifecycle/claim code and client skipped-replica consumption. */
public final class CombatWreckLifecycleTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SettingsAPI previous = Global.getSettings();
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getFloat": return 1f;
                case "getInt": return 1;
                case "getBoolean": case "fileExistsInCommon": return false;
                case "getColor": return java.awt.Color.WHITE;
                case "pickShipAIPlugin": return null;
                default: throw new AssertionError("Unexpected settings call: " + m.getName());
            }
        }));
        try {
            death("wingless fighter drone", true, true, false, false, false, Msg.DESPAWN_NO_WRECK);
            death("ordinary drone", false, true, false, false, false, Msg.DESPAWN_NO_WRECK);
            death("station module", false, false, true, false, false, Msg.DESPAWN_NO_WRECK);
            death("ordinary ship", false, false, false, false, false, Msg.DESPAWN_DESTROYED);
            death("wreck cap", false, false, false, false, true, Msg.DESPAWN_NO_WRECK);
            death("ordinary removal", false, false, false, true, false, Msg.DESPAWN_REMOVED);
            assignment(false, false);
            assignment(true, false);
            assignment(false, true);
            hullSizeChange();
            System.out.println("Combat host lifecycle: " + checks + " checks passed.");
        } finally { Global.setSettings(previous); }
    }

    @SuppressWarnings("unchecked")
    private static void death(String name, boolean fighter, boolean drone, boolean module,
                              boolean gone, boolean cap, byte expected) throws Exception {
        ShipAPI ship = ship(name, true, fighter, drone, module);
        try (HostCombatFixture fixture = new HostCombatFixture()) {
            WingmanHostPlugin host = new WingmanHostPlugin();
            set(host, "engine", engine(gone ? Collections.<ShipAPI>emptyList() : Collections.singletonList(ship)));
            set(host, "session", fixture.session);
            map(host, "ids").put(ship, 42);
            map(host, "byId").put(42, ship);
            map(host, "spawnMsgs").put(42, new byte[] { 7 });
            if (cap) for (int i = 0; i < 150; i++) map(host, "wrecks").put(1000 + i, ship);
            int before = map(host, "wrecks").size();
            invoke(host, "trackShips", new Class<?>[0]);
            List<Conn.Packet> packets = fixture.drain();
            check(packets.size() == 1 && packets.get(0).type == Msg.DESPAWN, name + ": one DESPAWN");
            In in = Msg.reader(packets.get(0).data);
            check(in.readInt() == 42 && in.readByte() == expected, name + ": authoritative death reason");
            boolean wreck = expected == Msg.DESPAWN_DESTROYED;
            check(map(host, "wrecks").size() == before + (wreck ? 1 : 0), name + ": host wreck count");
            check(map(host, "wreckIds").containsKey(ship) == wreck, name + ": host reverse wreck identity");
            check(map(host, "spawnMsgs").isEmpty() && map(host, "byId").isEmpty()
                    && map(host, "ids").isEmpty(), name + ": live records removed");
            check(map(host, "wreckSpawnMsgs").containsKey(42) == wreck, name + ": late-join wreck description");

            // No wing ID: client deliberately regards this skipped replica as a non-fighter.
            WingmanClientPlugin client = new WingmanClientPlugin();
            map(client, "skippedIds").put(42, false);
            invoke(client, "handle", new Class<?>[] { Conn.Packet.class }, packets.get(0));
            check(map(client, "skippedIds").isEmpty(), name + ": skipped replica retired");
            Set<?> lost = (Set<?>) field(client, "lostWrecks");
            check(lost.contains(42) == wreck, name + ": no phantom skipped-client wreck");

            WingmanClientPlugin built = new WingmanClientPlugin();
            int[] effects = new int[2];
            set(built, "engine", proxy(CombatEngineAPI.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "getPlayerShip": return null;
                    case "applyDamage": effects[0]++; return null;
                    case "removeEntity": effects[1]++; return null;
                    default: throw new AssertionError("Unexpected built-client call: " + m.getName());
                }
            }));
            Class<?> type = Class.forName("wingman.client.WingmanClientPlugin$Puppet");
            java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object puppet = ctor.newInstance();
            HullGuard guard = new HullGuard();
            set(puppet, "netId", 42);
            set(puppet, "ship", ship);
            set(puppet, "guard", guard);
            map(built, "puppets").put(42, puppet);
            map(built, "byShip").put(ship, puppet);
            invoke(built, "handle", new Class<?>[] { Conn.Packet.class }, packets.get(0));
            check(map(built, "wrecks").containsKey(42) == wreck, name + ": built replica follows host wreck decision");
            check(effects[0] == (gone ? 0 : 1) && effects[1] == (gone ? 1 : 0), name + ": death visuals versus ordinary removal");
            check(guard.standAside == !gone, name + ": destruction guard allows reported death");
            if (cap) {
                for (int i = 0; i < 150; i++) {
                    ((Set<Integer>) field(client, "lostWrecks")).add(1000 + i);
                    ((Set<Integer>) field(built, "lostWrecks")).add(1000 + i);
                }
            }
            invoke(host, "sendCheck", new Class<?>[0]);
            List<Conn.Packet> checks = fixture.drain();
            check(checks.size() == 1 && checks.get(0).type == Msg.CHECK, name + ": actual host checksum emitted");
            for (int i = 0; i < 6; i++) {
                invoke(client, "handle", new Class<?>[] { Conn.Packet.class }, checks.get(0));
                invoke(built, "handle", new Class<?>[] { Conn.Packet.class }, checks.get(0));
            }
            check(((Integer) field(client, "resyncs")) == 0 && ((Integer) field(built, "resyncs")) == 0,
                    name + ": repeated actual CHECK packets trigger no cosmetic refresh");
        }
    }

    /**
     * A mod changes a hull's size during a battle (a mech taking off): the host says so, once, and the stored
     * description late joiners get says the size the ship has now, not the one it was announced with.
     */
    private static void hullSizeChange() throws Exception {
        ShipAPI.HullSize[] size = { ShipAPI.HullSize.FIGHTER };
        ShipAPI ship = proxy(ShipAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "isAlive": case "isStationModule": return true;
                case "isHulk": case "isDrone": case "isAlly": case "isStation": case "isShuttlePod": return false;
                case "isFighter": return size[0] == ShipAPI.HullSize.FIGHTER;
                case "getHullSize": return size[0];
                case "setHullSize": size[0] = (ShipAPI.HullSize) a[0]; return null;
                case "getWing": return size[0] == ShipAPI.HullSize.FIGHTER ? proxy(com.fs.starfarer.api.combat.FighterWingAPI.class, (q, mm, aa) -> {
                    throw new AssertionError("Unexpected wing call: " + mm.getName());
                }) : null;
                case "getOwner": return 0;
                case "getName": return "mech";
                case "getHullSpec": return null;
                default: throw new AssertionError("Unexpected ship call: " + m.getName());
            }
        });
        wingman.net.ShipSpawn described = new wingman.net.ShipSpawn();
        described.netId = 42;
        described.hullId = "mech";
        described.hullSize = ShipAPI.HullSize.FIGHTER.ordinal();
        try (HostCombatFixture fixture = new HostCombatFixture()) {
            WingmanHostPlugin host = new WingmanHostPlugin();
            set(host, "engine", engine(Collections.singletonList(ship)));
            set(host, "session", fixture.session);
            map(host, "ids").put(ship, 42);
            map(host, "byId").put(42, ship);
            map(host, "sides").put(42, 0);
            map(host, "sizes").put(42, ShipAPI.HullSize.FIGHTER.ordinal());
            map(host, "spawnMsgs").put(42, described.encode());

            invoke(host, "trackShips", new Class<?>[0]);
            check(fixture.drain().isEmpty(), "unchanged hull size: nothing sent");

            size[0] = ShipAPI.HullSize.FRIGATE; // it took off
            invoke(host, "trackShips", new Class<?>[0]);
            List<Conn.Packet> packets = fixture.drain();
            check(packets.size() == 1 && packets.get(0).type == Msg.HULL_SIZE, "hull size change: one message");
            In in = Msg.reader(packets.get(0).data);
            check(in.readInt() == 42 && in.readByte() == ShipAPI.HullSize.FRIGATE.ordinal(), "hull size change: which ship, what size");
            check(wingman.net.ShipSpawn.decode((byte[]) map(host, "spawnMsgs").get(42)).hullSize == ShipAPI.HullSize.FRIGATE.ordinal(),
                    "hull size change: the description a late joiner gets is updated");

            invoke(host, "trackShips", new Class<?>[0]);
            check(fixture.drain().isEmpty(), "hull size change: said once");

            // The joiner applies it to its replica, and to the books of a ship it could not build.
            WingmanClientPlugin client = new WingmanClientPlugin();
            Class<?> type = Class.forName("wingman.client.WingmanClientPlugin$Puppet");
            java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object puppet = ctor.newInstance();
            set(puppet, "netId", 42);
            set(puppet, "ship", ship);
            set(puppet, "owner", 0);
            map(client, "puppets").put(42, puppet);
            invoke(client, "handle", new Class<?>[] { Conn.Packet.class }, packets.get(0));
            check(ship.getHullSize() == ShipAPI.HullSize.FRIGATE, "joiner's replica takes the host's hull size");

            WingmanClientPlugin skipped = new WingmanClientPlugin();
            map(skipped, "skippedIds").put(43, false);
            map(host, "byId").clear();
            map(host, "ids").clear();
            map(host, "sizes").clear();
            map(host, "spawnMsgs").clear();
            map(host, "ids").put(ship, 43);
            map(host, "byId").put(43, ship);
            map(host, "sizes").put(43, ShipAPI.HullSize.FRIGATE.ordinal());
            size[0] = ShipAPI.HullSize.FIGHTER; // it landed: a fighter again
            invoke(host, "trackShips", new Class<?>[0]);
            List<Conn.Packet> second = fixture.drain();
            check(second.size() == 1 && second.get(0).type == Msg.HULL_SIZE, "hull size change for a skipped ship: sent");
            invoke(skipped, "handle", new Class<?>[] { Conn.Packet.class }, second.get(0));
            check(Boolean.TRUE.equals(map(skipped, "skippedIds").get(43)), "skipped replica counted as the fighter it now is");
        }
    }

    private static void assignment(boolean transfer, boolean refused) throws Exception {
        ShipAPI old = ship("old", false, false, false, false);
        ShipAPI target = ship("target", false, false, false, false);
        try (HostCombatFixture fixture = new HostCombatFixture()) {
            WingmanHostPlugin host = new WingmanHostPlugin();
            set(host, "engine", engine(Collections.<ShipAPI>emptyList()));
            set(host, "session", fixture.session);
            set(host, "cfg", new WingmanConfig());
            map(host, "ids").put(old, 42);
            map(host, "byId").put(42, old);
            if (transfer) { map(host, "ids").put(target, 43); map(host, "byId").put(43, target); }
            HostSession.Player player = fixture.player;
            player.ship = old;
            player.shipNetId = 42;
            player.claimSeq = 9;
            player.claim = transfer || refused ? 43 : -1;
            player.hasClaim = true;
            // Replay has queued RESET/world and cleared needsSync; assignment is still pending.
            fixture.conn.send(Msg.RESET, Msg.EMPTY);
            invoke(host, "settleAssignments", new Class<?>[] { List.class }, Collections.singletonList(player));
            List<Conn.Packet> packets = fixture.drain();
            check(packets.get(0).type == Msg.RESET, "world reset precedes assignment");
            int expected = refused ? 42 : transfer ? 43 : -1;
            int assignments = 0;
            for (Conn.Packet packet : packets) {
                if (packet.type != Msg.ASSIGN) continue;
                In in = Msg.reader(packet.data);
                check(in.readInt() == 9 && in.readInt() == expected, "every sync decision reflects adjudicated claim");
                assignments++;
            }
            check(assignments == 2, "claim reply plus final sync acknowledgement");
            check(!player.hasClaim && player.shipNetId == expected, "host finishes claim before sync acknowledgement");
            check(player.ship == (refused ? old : transfer ? target : null), "actual host ownership matches decision");
            if (transfer) check(player.savedAI instanceof RemotePilotAI, "validated transfer installs actual remote pilot");
        }
    }

    private static CombatEngineAPI engine(List<ShipAPI> ships) {
        return proxy(CombatEngineAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getShips": return ships;
                case "getPlayerShip": case "getCombatUI": case "getFleetManager": return null;
                case "isInCampaign": return false;
                default: throw new AssertionError("Unexpected engine call: " + m.getName());
            }
        });
    }

    private static ShipAPI ship(String name, boolean dead, boolean fighter, boolean drone, boolean module) {
        return proxy(ShipAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "isAlive": return !dead;
                case "isHulk": return dead;
                case "isFighter": return fighter;
                case "isDrone": return drone;
                case "isStationModule": return module;
                case "getOwner": case "getOriginalOwner": return 0;
                case "getName": return name;
                case "getHullSize": return ShipAPI.HullSize.FRIGATE;
                case "getFleetMember": case "getHullSpec": return null;
                case "isAlly": case "isStation": return false;
                case "resetDefaultAI": case "setShipAI": case "setHulkChanceOverride": case "setExplosionScale": return null;
                case "getLocation": return new org.lwjgl.util.vector.Vector2f();
                case "getMutableStats": return proxy(com.fs.starfarer.api.combat.MutableShipStatsAPI.class, (q, method, args) -> {
                    if (method.getName().equals("getHullDamageTakenMult") || method.getName().equals("getArmorDamageTakenMult"))
                        return new com.fs.starfarer.api.combat.MutableStat(1f);
                    throw new AssertionError("Unexpected stat call: " + method.getName());
                });
                default: throw new AssertionError("Unexpected ship call: " + m.getName());
            }
        });
    }

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

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object target, String name) throws Exception {
        return (Map<Object, Object>) field(target, name);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
