package wingman;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.Orders;

/** Host side of the command screen: reads the objectives and the host's fleet orders and sends them out. */
final class HostOrders {
    private List<BattleObjectiveAPI> objectives;
    private byte[] lastSent;
    private long lastSentAt, lastBuiltAt;
    private boolean broken;

    /** Where the objectives are and what kind each is. Sent to a player once, with the rest of the battle. */
    byte[] objectivesMessage(CombatEngineAPI engine) {
        if (broken) return null;
        try {
            if (objectives == null) objectives = new ArrayList<BattleObjectiveAPI>(engine.getObjectives());
            Msg.W w = new Msg.W();
            w.out.writeByte(Math.min(objectives.size(), 255));
            for (int i = 0; i < objectives.size() && i < 255; i++) {
                BattleObjectiveAPI o = objectives.get(i);
                w.out.writeUTF(o.getType());
                w.out.writeFloat(o.getLocation().x);
                w.out.writeFloat(o.getLocation().y);
            }
            lastSent = null; // make sure the orders follow straight after
            lastTexts = null; // and the objectives' tooltip texts, for whoever just joined
            return w.done();
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    void tick(CombatEngineAPI engine, HostSession session, Map<ShipAPI, Integer> ids) {
        if (broken || objectives == null || session.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastBuiltAt < 300) return;
        lastBuiltAt = now;
        try {
            sendTexts(session);
            byte[] enc = build(engine, ids).encode();
            if (Arrays.equals(enc, lastSent) && now - lastSentAt < 3000) return;
            lastSent = enc;
            lastSentAt = now;
            session.broadcast(Msg.ORDERS, enc);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private Orders build(CombatEngineAPI engine, Map<ShipAPI, Integer> ids) {
        Orders o = new Orders();
        o.objectiveOwners = new int[Math.min(objectives.size(), 255)];
        for (int i = 0; i < o.objectiveOwners.length; i++) {
            BattleObjectiveAPI ob = objectives.get(i);
            o.objectiveOwners[i] = (ob.getOwner() & 0x7F) | (engine.isEntityInPlay(ob) ? 0 : Orders.GONE);
        }

        CombatTaskManagerAPI tm = engine.getFleetManager(0).getTaskManager(false);
        o.fullAssault = tm.isFullAssault();
        o.fullRetreat = tm.isInFullRetreat();
        for (AssignmentInfo info : tm.getAllAssignments()) {
            AssignmentTargetAPI t = info.getTarget();
            if (t == null || info.getType() == null) continue;
            Orders.Assignment a = new Orders.Assignment();
            a.type = info.getType().name();
            if (t instanceof BattleObjectiveAPI) {
                a.targetKind = Orders.TARGET_OBJECTIVE;
                a.targetId = objectives.indexOf(t);
                if (a.targetId < 0) continue;
            } else if (t instanceof DeployedFleetMemberAPI) {
                Integer id = ids.get(((DeployedFleetMemberAPI) t).getShip());
                if (id == null) continue;
                a.targetKind = Orders.TARGET_SHIP;
                a.targetId = id;
            } else {
                a.targetKind = Orders.TARGET_POINT;
                a.x = t.getLocation().x;
                a.y = t.getLocation().y;
            }
            List<DeployedFleetMemberAPI> members = info.getAssignedMembers();
            if (members != null) {
                for (DeployedFleetMemberAPI m : members) {
                    Integer id = ids.get(m.getShip());
                    if (id != null) a.members.add(id);
                }
            }
            o.assignments.add(a);
        }

        // Orders given straight to a ship with no target (retreat, search and destroy).
        CombatFleetManagerAPI fm = engine.getFleetManager(0);
        for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
            ShipAPI s = e.getKey();
            if (s.getOwner() != 0 || s.isFighter()) continue;
            AssignmentInfo info = tm.getAssignmentFor(s);
            if (info == null || info.getTarget() != null || info.getType() == null) continue;
            Orders.ShipOrder so = new Orders.ShipOrder();
            so.ship = e.getValue();
            so.type = info.getType().name();
            DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(s);
            so.direct = dfm != null && dfm.isDirectRetreat();
            o.shipOrders.add(so);
        }
        java.util.Collections.sort(o.shipOrders, new java.util.Comparator<Orders.ShipOrder>() {
            public int compare(Orders.ShipOrder a, Orders.ShipOrder b) { return a.ship < b.ship ? -1 : a.ship > b.ship ? 1 : 0; }
        });
        return o;
    }

    /** Where this objective is in the list joiners were sent; -1 if it isn't in it. */
    int objectiveIndex(BattleObjectiveAPI o) {
        return objectives == null ? -1 : objectives.indexOf(o);
    }

    private String[] lastTexts;
    private long lastTextsAt;
    private boolean textsBroken;

    /**
     * Each objective's tooltip text, as its own script writes it, sent when it changes. A joiner shows it on
     * objectives whose script is held back there (see ObjectiveMirror), so the tooltip reads as on the host.
     */
    private void sendTexts(HostSession session) {
        if (textsBroken) return;
        long now = System.currentTimeMillis();
        if (lastTexts != null && now - lastTextsAt < 1000) return;
        lastTextsAt = now;
        try {
            int n = Math.min(objectives.size(), 255);
            if (lastTexts == null || lastTexts.length != n) lastTexts = new String[n];
            for (int i = 0; i < n; i++) {
                String text = null;
                try {
                    BattleObjectiveAPI o = objectives.get(i);
                    if (o instanceof com.fs.starfarer.combat.entities.BattleObjective) {
                        com.fs.starfarer.api.combat.BattleObjectiveEffect e = ((com.fs.starfarer.combat.entities.BattleObjective) o).getEffect();
                        if (e != null) text = e.getLongDescription();
                    }
                } catch (Throwable t) { /* a script that can't describe itself right now */ }
                if (text == null) text = "";
                if (text.length() > 4000) text = text.substring(0, 4000);
                if (text.equals(lastTexts[i])) continue;
                lastTexts[i] = text;
                Msg.W w = new Msg.W();
                w.out.writeByte(i);
                w.out.writeUTF(text);
                session.broadcast(Msg.OBJECTIVE_TEXT, w.done());
            }
        } catch (Throwable t) {
            textsBroken = true;
            Global.getLogger(HostOrders.class).warn("Wingman: objectives' tooltips are not sent this battle", t);
        }
    }

    private void fail(Throwable t) {
        broken = true;
        Global.getLogger(HostOrders.class).error("Wingman: sending fleet orders failed, order sync is off for this battle", t);
    }
}
