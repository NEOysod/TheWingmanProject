package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import wingman.net.Conn;
import wingman.net.HostCombatFixture;
import wingman.net.In;
import wingman.net.Msg;
import wingman.net.ShipSpawn;

/** Production packet handlers, inbox ordering and presentation transitions; no game renderer. */
public final class CombatControlResyncTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SettingsAPI previous = Global.getSettings();
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getFloat": return 1f;
                case "getInt": return 1;
                case "getBoolean": case "fileExistsInCommon": return false;
                case "getColor": return java.awt.Color.WHITE;
                case "writeTextFileToCommon": return null;
                default: throw new AssertionError("Unexpected settings call: " + m.getName());
            }
        }));
        try {
            refresh(false);
            refresh(true);
            deferredReadyAndSeatFailure();
            repeatedFallbackAndReplacement();
            missingOldReplicaDoesNotRelease();
            staleAndUnissuedRepliesAreAtomic();
            nativeTransferWinsQueuedReply();
            spectatorDuringRefresh();
            noShipAndMissingContent();
            lifecycleAndReusedId();
            reconnectRequiresFreshClaim();
            linkedFighterHandoffs();
            System.out.println("Combat control resync: " + checks + " checks passed.");
        } finally { Global.setSettings(previous); }
    }

    private static void refresh(boolean split) throws Exception {
        try (World w = new World()) {
            Body original = w.add(42, false, null);
            w.assign(7, 42);
            check(w.current == original.ship, "initial assignment seats actual replica");
            w.paused = true;
            w.handle(Msg.RESET, Msg.EMPTY);
            if (split) w.pump();
            check(w.current == w.placeholder.ship && w.id() == -1, "RESET invalidates old presentation and authorization");
            check(w.sequence() == 7 && w.claims().isEmpty(), "RESET frame sends no release");
            Body replacement = w.add(42, false, null);
            w.fixture.enqueue(Msg.ASSIGN, Msg.twoInts(7, 42));
            w.pump();
            check(w.current == replacement.ship && w.current != original.ship, "refresh seats replacement identity");
            check(w.id() == 42 && w.pending() == null, "refresh consumes applicable authorization");
            check(w.sequence() == 7 && w.claims().isEmpty(), "refresh sends neither release nor reclaim");
            check(w.paused, "refresh leaves engine pause unchanged");
            int seats = w.seats;
            w.assign(7, 42);
            check(w.seats == seats && w.claims().isEmpty(), "duplicate sync assignment is idempotent");
        }
    }

    private static void deferredReadyAndSeatFailure() throws Exception {
        try (World w = new World()) {
            w.assign(7, 42);
            Object pending = w.pending();
            check(pending != null && w.current == w.placeholder.ship, "assignment before replica waits safely");
            Body body = w.add(42, false, null);
            body.inPlay = false;
            w.apply();
            check(w.pending() == pending, "out-of-play replica remains pending");
            body.inPlay = true;
            w.acceptSeat = false;
            w.apply();
            check(w.pending() == pending && w.current == w.placeholder.ship, "failed setter retains pending assignment");
            w.assign(7, 42);
            check(w.pending() == pending, "duplicate does not restart failed-seat recovery");
            w.acceptSeat = true;
            w.apply();
            check(w.current == body.ship && w.pending() == null, "first safe callback restores without rescue timer");
            check(w.sequence() == 7 && w.claims().isEmpty(), "deferred readiness needs no host round trip");
        }
    }

    private static void repeatedFallbackAndReplacement() throws Exception {
        try (World w = new World()) {
            w.add(42, false, null);
            w.assign(7, 42);
            Body replacement = w.add(42, false, null);
            call(w.plugin, "seatWithoutClaim", new Class<?>[] { ShipAPI.class, String.class }, replacement.ship, "local replica rescue");
            for (int i = 0; i < 2; i++) {
                w.current = w.placeholder.ship;
                w.pump();
                check(w.current == replacement.ship, "later unexpected fallback restores replacement without sticky intent");
            }
            check(w.sequence() == 7 && w.claims().isEmpty(), "local replacement and repeated fallbacks send no claim");
        }
    }

    private static void missingOldReplicaDoesNotRelease() throws Exception {
        try (World w = new World()) {
            Body old = w.add(42, false, null);
            w.assign(7, 42);
            old.alive = false;
            old.inPlay = false;
            w.current = w.placeholder.ship;
            w.pump();
            check(w.pending() != null && w.id() == 42, "lost local object retains host authorization");
            check(w.claims().isEmpty() && w.sequence() == 7, "lost local object does not release real ship");
            Body replacement = w.add(42, false, null);
            w.apply();
            check(w.current == replacement.ship, "pending authorization follows net ID to new object");
        }
    }

    private static void staleAndUnissuedRepliesAreAtomic() throws Exception {
        try (World w = new World()) {
            w.assign(7, 42);
            Object pending = w.pending();
            set(w.plugin, "pickId", 13);
            w.assign(6, 77);
            w.assign(8, 77);
            check(w.id() == 42 && ((Integer) value(w.plugin, "pickId")) == 13, "old and unissued sequences cannot alter ownership/selection");
            check(w.pending() == pending && w.current == w.placeholder.ship, "inapplicable replies cannot consume pending restore");
            call(w.plugin, "claim", new Class<?>[] { Conn.class, int.class }, w.fixture.conn, 43);
            w.assign(7, 42);
            check(w.pending() == null && w.sequence() == 8, "newer intent cancels old recovery and ignores late reply");
            List<Conn.Packet> claims = w.claims();
            check(claims.size() == 1 && payload(claims.get(0), 8, 43), "new transfer sends exactly one intended request");
        }
    }

    private static void nativeTransferWinsQueuedReply() throws Exception {
        try (World w = new World()) {
            w.add(42, false, null);
            Body chosen = w.add(43, false, null);
            w.assign(7, 42);
            w.current = chosen.ship; // native Transfer Command already happened
            w.fixture.enqueue(Msg.ASSIGN, Msg.twoInts(7, 42));
            w.pump();
            check(w.current == chosen.ship && w.sequence() == 8, "production inbox processing observes native transfer first");
            check(((Integer) value(w.plugin, "claimedId")) == 43, "older reply leaves newer optimistic claim intact");
            w.current = w.placeholder.ship;
            w.pump();
            check(w.current == w.placeholder.ship, "old authorization cannot override newer undecided intent");
            List<Conn.Packet> claims = w.claims();
            check(claims.size() == 1 && payload(claims.get(0), 8, 43), "pre/post observations do not duplicate transfer request");
            w.assign(8, 43);
            check(w.current == chosen.ship && w.id() == 43, "current host decision restores chosen ship");
            check(w.claims().isEmpty(), "accepted transfer seating does not reclaim");
        }
    }

    private static void spectatorDuringRefresh() throws Exception {
        try (World w = new World()) {
            w.add(42, false, null);
            w.assign(7, 42);
            w.handle(Msg.RESET, Msg.EMPTY);
            call(w.plugin, "chooseSpectator", new Class<?>[] { Conn.class }, w.fixture.conn);
            call(w.plugin, "chooseSpectator", new Class<?>[] { Conn.class }, w.fixture.conn);
            w.add(42, false, null);
            w.assign(7, 42);
            w.pump();
            List<Conn.Packet> claims = w.claims();
            check(claims.size() == 1 && payload(claims.get(0), 8, -1), "explicit spectator during refresh releases exactly once");
            check(w.current == w.placeholder.ship && w.pending() == null, "stale pre-release assignment cannot reboard");
            w.assign(8, -1);
            w.pump();
            check(w.current == w.placeholder.ship && w.id() == -1 && w.claims().isEmpty(), "host release confirmation creates no second release");
        }
    }

    private static void noShipAndMissingContent() throws Exception {
        try (World w = new World()) {
            w.add(42, false, null);
            w.assign(7, 42);
            w.assign(7, -1);
            check(w.current == w.placeholder.ship && w.id() == -1, "applicable no-ship decision seats spectator");
            check(w.claims().isEmpty(), "host no-ship decision does not generate release request");
            map(w.plugin, "skippedIds").put(77, false);
            w.assign(7, 77);
            check(w.pending() == null && w.current == w.placeholder.ship, "known missing content cancels recovery");
            w.assign(7, 78);
            w.handle(Msg.DESPAWN, despawn(78));
            check(w.pending() == null && w.id() == -1, "despawn cancels missing-target authorization");
            w.add(78, false, null);
            w.apply();
            check(w.current == w.placeholder.ship, "removed entity cannot be restored later from saved ID");
        }
    }

    private static void lifecycleAndReusedId() throws Exception {
        try (World w = new World()) {
            w.assign(7, 42);
            w.handle(Msg.RESET, Msg.EMPTY);
            w.add(42, false, null);
            w.pump();
            check(w.id() == -1 && w.pending() == null && w.current == w.placeholder.ship, "reused ID after reset requires fresh authorization");
            w.assign(7, 42);
            call(w.plugin, "clearAuthorization", new Class<?>[] { String.class }, "disconnect");
            check(w.id() == -1 && w.pending() == null, "disconnect invalidates prior connection authorization");
        }
        for (String flag : new String[] { "leaving", "hostEnded", "halted", "disabled" }) {
            try (World w = new World()) {
                w.assign(7, 42);
                set(w.plugin, flag, true);
                w.add(42, false, null);
                w.apply();
                check(w.pending() == null && w.current == w.placeholder.ship, flag + " prevents deferred seating");
            }
        }
    }

    private static void reconnectRequiresFreshClaim() throws Exception {
        try (World w = new World()) {
            Body back = w.add(42, false, null);
            set(w.plugin, "rejoinShip", 42);
            set(w.plugin, "rejoinHull", "test_hull");
            w.fixture.enqueue(Msg.ASSIGN, Msg.twoInts(7, -1));
            w.fixture.enqueue(Msg.ASSIGN, Msg.twoInts(7, -1)); // claim reply and duplicate full-sync acknowledgement
            w.pump();
            List<Conn.Packet> claims = w.claims();
            check(w.current == back.ship && w.id() == -1, "reconnect selection does not invent host authorization");
            check(claims.size() == 1 && payload(claims.get(0), 8, 42), "reconnect submits a fresh validated claim");
            w.assign(8, 42);
            check(w.id() == 42 && w.current == back.ship, "fresh reconnect decision authorizes seat");
        }
    }

    private static void linkedFighterHandoffs() throws Exception {
        try (World w = new World()) {
            Body carrier = w.add(42, false, null);
            Body fighter = w.add(51, true, carrier.ship);
            w.assign(7, 42);
            w.current = fighter.ship;
            w.pump();
            List<Conn.Packet> claims = w.claims();
            check(claims.size() == 1 && payload(claims.get(0), 8, 51), "linked fighter handoff preserves claim rule");
            w.handle(Msg.ASSIGN, Msg.assignMoved(8, 51));
            check(w.current == fighter.ship && w.id() == 51, "applicable host-script fighter assignment seats correctly");
            set(w.plugin, "fighterSince", -100f); // skip unrelated diagnostic formatting
            w.current = carrier.ship;
            w.pump();
            claims = w.claims();
            check(claims.size() == 1 && payload(claims.get(0), 9, 42), "fighter return requests original carrier");
            w.handle(Msg.ASSIGN, Msg.assignMoved(9, 42));
            check(w.current == carrier.ship && w.id() == 42, "host-script carrier return remains authorized");
        }
    }

    private static byte[] despawn(int id) throws Exception {
        Msg.W w = new Msg.W();
        w.out.writeInt(id);
        w.out.writeByte(Msg.DESPAWN_NO_WRECK);
        return w.done();
    }
    private static boolean payload(Conn.Packet packet, int seq, int id) throws Exception {
        In in = Msg.reader(packet.data);
        return in.readInt() == seq && in.readInt() == id;
    }

    private static final class World implements AutoCloseable, InvocationHandler {
        final HostCombatFixture fixture = new HostCombatFixture();
        final WingmanClientPlugin plugin = new WingmanClientPlugin();
        final Map<ShipAPI, Body> bodies = new IdentityHashMap<ShipAPI, Body>();
        final Body placeholder = new Body(false, null);
        final CombatEngineAPI engine = proxy(CombatEngineAPI.class, this);
        ShipAPI current = placeholder.ship;
        boolean acceptSeat = true, paused;
        int seats;

        World() throws Exception {
            bodies.put(placeholder.ship, placeholder);
            set(plugin, "engine", engine);
            set(plugin, "placeholder", placeholder.ship);
            set(plugin, "lastPlayerShip", placeholder.ship);
            set(plugin, "claimSeq", 7);
        }
        Body add(int id, boolean fighter, ShipAPI carrier) throws Exception {
            Body body = new Body(fighter, carrier);
            bodies.put(body.ship, body);
            Class<?> type = Class.forName("wingman.WingmanClientPlugin$Puppet");
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object puppet = constructor.newInstance();
            ShipSpawn info = new ShipSpawn();
            info.hullId = "test_hull";
            info.name = "test ship";
            set(puppet, "netId", id);
            set(puppet, "ship", body.ship);
            set(puppet, "fighter", fighter);
            set(puppet, "info", info);
            Object old = map(plugin, "puppets").put(id, puppet);
            if (old != null) map(plugin, "byShip").remove(value(old, "ship"));
            map(plugin, "byShip").put(body.ship, puppet);
            return body;
        }
        void handle(byte type, byte[] data) throws Exception { call(plugin, "handle", new Class<?>[] { Conn.Packet.class }, Conn.packet(type, data)); }
        void assign(int seq, int id) throws Exception { handle(Msg.ASSIGN, Msg.twoInts(seq, id)); }
        void apply() throws Exception { call(plugin, "applyPendingSeat", new Class<?>[0]); }
        void pump() throws Exception { call(plugin, "receiveMessages", new Class<?>[] { Conn.class }, fixture.conn); apply(); }
        int id() throws Exception { return (Integer) value(plugin, "ownId"); }
        int sequence() throws Exception { return (Integer) value(plugin, "claimSeq"); }
        Object pending() throws Exception { return value(plugin, "pendingSeat"); }
        List<Conn.Packet> claims() throws Exception {
            List<Conn.Packet> result = new ArrayList<Conn.Packet>();
            for (Conn.Packet packet : fixture.drain()) if (packet.type == Msg.CLAIM) result.add(packet);
            return result;
        }
        @Override public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "getPlayerShip": return current;
                case "setPlayerShipExternal": if (acceptSeat) { current = (ShipAPI) args[0]; seats++; } return null;
                case "isEntityInPlay": return bodies.containsKey(args[0]) && bodies.get(args[0]).inPlay;
                case "removeEntity": bodies.get(args[0]).inPlay = false; return null;
                case "getShips": return Collections.emptyList();
                case "getCombatUI": return null;
                case "isPaused": return paused;
                default: throw new AssertionError("Unexpected engine call: " + method.getName());
            }
        }
        @Override public void close() { fixture.close(); }
    }

    private static final class Body implements InvocationHandler {
        final boolean fighter;
        final ShipAPI carrier;
        final ShipAPI ship = proxy(ShipAPI.class, this);
        boolean alive = true, inPlay = true;
        Body(boolean fighter, ShipAPI carrier) { this.fighter = fighter; this.carrier = carrier; }
        @Override public Object invoke(Object p, Method method, Object[] args) {
            switch (method.getName()) {
                case "isAlive": return alive;
                case "isHulk": return !alive;
                case "isFighter": return fighter;
                case "setShipAI": return null;
                case "getWing": return carrier == null ? null : proxy(FighterWingAPI.class, (q, m, a) -> {
                    if (m.getName().equals("getSourceShip")) return carrier;
                    throw new AssertionError("Unexpected wing call: " + m.getName());
                });
                default: throw new AssertionError("Unexpected ship call: " + method.getName());
            }
        }
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
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error) throw (Error) e.getCause();
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }
    @SuppressWarnings("unchecked") private static Map<Object, Object> map(Object target, String name) throws Exception {
        return (Map<Object, Object>) value(target, name);
    }
    private static Object value(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
