package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.net.In;
import wingman.net.Msg;
import wingman.net.Orders;

/**
 * Joiner side of the command screen. Objectives can't be created during a battle, so the join mission
 * starts with spare ones parked far off the map and this class moves them to where the host's are.
 * Fleet orders are copied from the host into the local command screen; anything the joiner adds
 * there is taken out again, because only the host's ships are real.
 */
final class ClientOrders {
    interface Ships {
        /** Network ids of the friendly ships (not fighters) on our screen. */
        List<Integer> friendlyShips();
        ShipAPI ship(int netId);
        Integer id(ShipAPI ship);
        int side(int netId);
        /** The ship this player is flying. The game treats orders on your own ship specially, so it is left out. */
        boolean isOwn(int netId);
    }

    private static final float PARK = -150000f;

    private final Ships ships;
    private CombatEngineAPI engine;
    private final Map<String, List<BattleObjectiveAPI>> spare = new HashMap<String, List<BattleObjectiveAPI>>();
    private final List<BattleObjectiveAPI> all = new ArrayList<BattleObjectiveAPI>();
    /** Where the mission put each objective. It makes the host's own ones in place, so the one nearest the host's spot is the one meant for it. */
    private final Map<BattleObjectiveAPI, float[]> home = new HashMap<BattleObjectiveAPI, float[]>();
    /** Objectives in the host's order; null where we had no spare of that kind. */
    private final List<BattleObjectiveAPI> placed = new ArrayList<BattleObjectiveAPI>();
    private Orders host;
    private Set<String> previousHostKeys = new HashSet<String>();
    private Set<Integer> previousShipOrders = new HashSet<Integer>();
    private long lastReconcile, lastBlockedNotice;
    private boolean broken, loggedState;

    ClientOrders(Ships ships) { this.ships = ships; }

    /** Take charge of the spare objectives the mission placed, and get them out of sight. */
    void start(CombatEngineAPI engine) {
        this.engine = engine;
        try {
            all.addAll(engine.getObjectives());
            for (BattleObjectiveAPI o : all) home.put(o, new float[] { o.getLocation().x, o.getLocation().y });
            park();
            engine.getFleetManager(0).getTaskManager(false).setPreventFullRetreat(true);
            Dbg.info(ClientOrders.class, "Wingman orders: " + all.size() + " spare objectives ready");
        } catch (Throwable t) {
            fail("setting up objectives", t);
        }
    }

    /** New battle on the host: forget everything. */
    void reset() {
        host = null;
        texts.clear();
        previousHostKeys = new HashSet<String>();
        previousShipOrders = new HashSet<Integer>();
        loggedState = false;
        if (broken || engine == null) return;
        try {
            park();
            CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
            for (AssignmentInfo info : new ArrayList<AssignmentInfo>(tm.getAllAssignments())) tm.removeAssignment(info);
            tm.clearEmptyWaypoints();
        } catch (Throwable t) {
            fail("clearing orders", t);
        }
    }

    private void park() {
        spare.clear();
        placed.clear();
        for (int i = 0; i < all.size(); i++) {
            BattleObjectiveAPI o = all.get(i);
            if (engine != null && !engine.isEntityInPlay(o)) continue; // taken off the field (see gone)
            o.getLocation().set(PARK - i * 1000f, PARK);
            o.setOwner(100);
            List<BattleObjectiveAPI> list = spare.get(o.getType());
            if (list == null) { list = new ArrayList<BattleObjectiveAPI>(); spare.put(o.getType(), list); }
            list.add(o);
        }
    }

    void onObjectives(byte[] data) {
        if (broken || engine == null) return;
        try {
            park();
            In in = Msg.reader(data);
            int n = in.readByte() & 0xFF, missing = 0;
            for (int i = 0; i < n; i++) {
                String type = in.readUTF();
                float x = in.readFloat(), y = in.readFloat();
                List<BattleObjectiveAPI> list = spare.get(type);
                if (list == null || list.isEmpty()) list = standInAt(x, y); // a stand-in made in its place (see JoinSetup.kindToMake)
                if (list == null || list.isEmpty()) { placed.add(null); missing++; continue; }
                int best = list.size() - 1;
                float bestD = Float.MAX_VALUE;
                for (int k = 0; k < list.size(); k++) {
                    float[] h = home.get(list.get(k));
                    if (h == null) continue;
                    float d = (h[0] - x) * (h[0] - x) + (h[1] - y) * (h[1] - y);
                    if (d < bestD) { bestD = d; best = k; }
                }
                BattleObjectiveAPI o = list.remove(best);
                o.getLocation().set(x, y);
                placed.add(o);
                applyText(placed.size() - 1);
            }
            Dbg.info(ClientOrders.class, "Wingman orders: host has " + n + " objectives, placed "
                    + (n - missing) + (missing > 0 ? " (" + missing + " are of a kind or number this mod has no spare for)" : ""));
        } catch (Throwable t) {
            fail("placing objectives", t);
        }
    }

    /** The unused objective of any kind that the mission made at this spot, as a one-item list; null if none. */
    private List<BattleObjectiveAPI> standInAt(float x, float y) {
        for (List<BattleObjectiveAPI> l : spare.values()) {
            for (int k = 0; k < l.size(); k++) {
                float[] h = home.get(l.get(k));
                if (h != null && Math.abs(h[0] - x) < 1f && Math.abs(h[1] - y) < 1f) {
                    List<BattleObjectiveAPI> one = new ArrayList<BattleObjectiveAPI>();
                    one.add(l.remove(k));
                    return one;
                }
            }
        }
        return null;
    }

    void onOrders(Orders o) { host = o; lastReconcile = 0; }

    /** Our copy of the host's objective number i; null if there is none. */
    BattleObjectiveAPI placedAt(int i) {
        return i >= 0 && i < placed.size() ? placed.get(i) : null;
    }

    /** The host's tooltip text for each objective, by the host's order. */
    private final Map<Integer, String> texts = new HashMap<Integer, String>();

    void onObjectiveText(byte[] data) {
        if (broken || engine == null) return;
        try {
            In in = Msg.reader(data);
            int i = in.readByte() & 0xFF;
            String text = in.readUTF();
            if (text.length() > 4000) return;
            texts.put(i, text);
            applyText(i);
        } catch (Throwable t) {
            fail("showing an objective's tooltip", t);
        }
    }

    private void applyText(int i) {
        if (i >= placed.size()) return;
        MirrorObjectiveEffect m = mirrorOf(placed.get(i));
        if (m != null) m.setText(texts.get(i));
    }

    /** The do-nothing script of a held-back objective (see ObjectiveMirror); null for one running its own. */
    private static MirrorObjectiveEffect mirrorOf(BattleObjectiveAPI o) {
        try {
            if (o instanceof com.fs.starfarer.combat.entities.BattleObjective) {
                Object e = ((com.fs.starfarer.combat.entities.BattleObjective) o).getEffect();
                if (e instanceof MirrorObjectiveEffect) return (MirrorObjectiveEffect) e;
            }
        } catch (Throwable t) { /* treated as its own */ }
        return null;
    }

    /**
     * The host's game took this objective off the field (a mod's objective that ends itself, such as derelict
     * ships captured): take ours away too. One whose script is held back here can't say so itself: the host's
     * line for it arrives as a battle message, or, with those off, a notice here says who took it.
     */
    private void gone(int i, BattleObjectiveAPI o, int owner) {
        placed.set(i, null);
        boolean mirror = mirrorOf(o) != null;
        try {
            if (engine.isEntityInPlay(o)) engine.removeEntity(o);
        } catch (Throwable t) { /* already gone */ }
        boolean hostSays = false;
        try { hostSays = WingmanConfig.load().hostMessages; } catch (Throwable t) { /* say it ourselves */ }
        if (mirror && !hostSays) { // with the host's lines coming, its script says what happened itself
            String name = o.getDisplayName() == null ? "An objective" : o.getDisplayName();
            Ui.say(engine, name + (owner == 0 ? " captured by your side" : owner == 1 ? " captured by the enemy" : " is gone")
                    + " (its effect runs in the host's game).");
        }
        Dbg.info(ClientOrders.class, "Wingman objectives: '" + o.getType() + "' is gone from the host's battle; removed here too");
    }

    void tick() {
        if (broken || engine == null || host == null) return;
        try {
            // Who holds each objective is the host's call; the local game would otherwise work it out for itself.
            for (int i = 0; i < placed.size() && i < host.objectiveOwners.length; i++) {
                BattleObjectiveAPI o = placed.get(i);
                if (o == null) continue;
                int owner = host.objectiveOwners[i] & 0x7F;
                if ((host.objectiveOwners[i] & Orders.GONE) != 0) { gone(i, o, owner); continue; }
                if (o.getOwner() != owner) o.setOwner(owner);
            }
            long now = System.currentTimeMillis();
            if (now - lastReconcile < 300) return;
            lastReconcile = now;
            reconcile(now);
        } catch (Throwable t) {
            fail("showing the host's orders", t);
        }
    }

    private String keyOf(AssignmentInfo info) {
        AssignmentTargetAPI t = info.getTarget();
        if (t == null || info.getType() == null) return null; // target-less orders are left alone
        Orders.Assignment a = new Orders.Assignment();
        a.type = info.getType().name();
        if (t instanceof BattleObjectiveAPI) {
            a.targetKind = Orders.TARGET_OBJECTIVE;
            a.targetId = placed.indexOf(t);
        } else if (t instanceof DeployedFleetMemberAPI) {
            Integer id = ships.id(((DeployedFleetMemberAPI) t).getShip());
            a.targetKind = Orders.TARGET_SHIP;
            a.targetId = id == null ? -2 : id;
        } else {
            a.targetKind = Orders.TARGET_POINT;
            a.x = t.getLocation().x;
            a.y = t.getLocation().y;
        }
        return a.key();
    }

    private void reconcile(long now) {
        CombatFleetManagerAPI fm = engine.getFleetManager(0);
        CombatTaskManagerAPI tm = fm.getTaskManager(false);

        Map<String, Orders.Assignment> want = new HashMap<String, Orders.Assignment>();
        for (Orders.Assignment a : host.assignments) want.put(a.key(), a);

        // Anything here that the host doesn't have goes: either the host cancelled it, or the joiner tried to add it.
        Map<String, AssignmentInfo> have = new HashMap<String, AssignmentInfo>();
        boolean joinerTried = false, removedAny = false;
        for (AssignmentInfo info : new ArrayList<AssignmentInfo>(tm.getAllAssignments())) {
            String key = keyOf(info);
            if (key == null) continue;
            if (want.containsKey(key) && !have.containsKey(key)) { have.put(key, info); continue; }
            tm.removeAssignment(info);
            removedAny = true;
            if (!previousHostKeys.contains(key)) joinerTried = true;
        }
        if (removedAny) tm.clearEmptyWaypoints();

        int created = 0, unresolved = 0;
        for (Orders.Assignment a : host.assignments) {
            AssignmentInfo info = have.get(a.key());
            if (info == null) {
                AssignmentTargetAPI target = resolve(a, fm);
                CombatAssignmentType type = null;
                try { type = CombatAssignmentType.valueOf(a.type); } catch (IllegalArgumentException e) { /* unknown here */ }
                if (target == null || type == null) { unresolved++; continue; }
                info = tm.createAssignment(type, target, false);
                if (info == null) { unresolved++; continue; }
                have.put(a.key(), info);
                created++;
            }
            List<DeployedFleetMemberAPI> current = info.getAssignedMembers();
            for (int id : a.members) {
                ShipAPI s = ships.ship(id);
                if (s == null || ships.isOwn(id)) continue;
                DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(s);
                if (dfm != null && (current == null || !current.contains(dfm))) tm.giveAssignment(dfm, info, false);
            }
        }
        if (tm.isFullAssault() != host.fullAssault) tm.setFullAssault(host.fullAssault);

        // Orders with no target, given straight to one ship. Same rule: the host's version wins.
        Map<Integer, Orders.ShipOrder> direct = new HashMap<Integer, Orders.ShipOrder>();
        for (Orders.ShipOrder so : host.shipOrders) direct.put(so.ship, so);
        int shipOrdersSet = 0;
        for (int id : ships.friendlyShips()) {
            if (ships.isOwn(id)) continue;
            ShipAPI s = ships.ship(id);
            DeployedFleetMemberAPI dfm = s == null ? null : fm.getDeployedFleetMember(s);
            if (dfm == null) continue;
            AssignmentInfo local = tm.getAssignmentFor(s);
            String localType = local != null && local.getTarget() == null && local.getType() != null
                    ? local.getType().name() : "";
            Orders.ShipOrder so = direct.get(id);
            String wantType = so == null ? "" : so.type;
            if (localType.equals(wantType)) continue;
            if ("RETREAT".equals(wantType)) {
                tm.orderRetreat(dfm, false, so.direct);
            } else if ("SEARCH_AND_DESTROY".equals(wantType)) {
                tm.orderSearchAndDestroy(dfm, false);
            } else if ("RETREAT".equals(localType)) {
                tm.orderSearchAndDestroy(dfm, false); // the only way to call off a retreat the host never ordered
                if (!previousShipOrders.contains(id)) joinerTried = true;
            } else {
                continue; // a local search-and-destroy with nothing from the host: same thing as no order
            }
            shipOrdersSet++;
        }
        previousShipOrders = new HashSet<Integer>(direct.keySet());
        if (shipOrdersSet > 0) {
            Dbg.info(ClientOrders.class, "Wingman orders: host has " + host.shipOrders.size()
                    + " retreat / search-and-destroy orders, applied " + shipOrdersSet + " here");
        }

        previousHostKeys = new HashSet<String>(want.keySet());
        if (joinerTried && now - lastBlockedNotice > 6000) {
            lastBlockedNotice = now;
            Ui.say(engine, "Co-op: only the host can give fleet orders. Showing the host's orders.");
        }
        if (!loggedState || created > 0) {
            loggedState = true;
            Dbg.info(ClientOrders.class, "Wingman orders: host has " + host.assignments.size()
                    + " orders, created " + created + " here, " + unresolved + " could not be shown");
        }
    }

    private AssignmentTargetAPI resolve(Orders.Assignment a, CombatFleetManagerAPI fm) {
        if (a.targetKind == Orders.TARGET_OBJECTIVE) {
            return a.targetId >= 0 && a.targetId < placed.size() ? placed.get(a.targetId) : null;
        }
        if (a.targetKind == Orders.TARGET_SHIP) {
            ShipAPI s = ships.ship(a.targetId);
            if (s == null) return null;
            return engine.getFleetManager(ships.side(a.targetId)).getDeployedFleetMember(s);
        }
        return fm.createWaypoint(new Vector2f(a.x, a.y), false);
    }

    private void fail(String doing, Throwable t) {
        broken = true;
        Global.getLogger(ClientOrders.class).error("Wingman: " + doing + " failed, order display is off for this battle", t);
    }
}
