package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The host's fleet orders and who holds each objective, as shown in the command screen. */
public final class Orders {
    public static final int TARGET_OBJECTIVE = 0, TARGET_SHIP = 1, TARGET_POINT = 2;

    public static final class Assignment {
        /** Name of the order type, e.g. CAPTURE, DEFEND, RALLY_TASK_FORCE. */
        public String type = "";
        public int targetKind;
        /** Objective index or ship network id, depending on targetKind. */
        public int targetId = -1;
        public float x, y;
        public List<Integer> members = new ArrayList<Integer>();

        /** Identifies the order regardless of which ships are on it. Points are rounded so tiny drift doesn't matter. */
        public String key() {
            if (targetKind == TARGET_POINT) return type + "|p|" + Math.round(x / 20f) + "," + Math.round(y / 20f);
            return type + "|" + targetKind + "|" + targetId;
        }
    }

    /** Owner of each objective, in the order they were sent: 0 players, 1 enemy, 100 nobody. */
    public int[] objectiveOwners = new int[0];
    public boolean fullAssault, fullRetreat;
    public List<Assignment> assignments = new ArrayList<Assignment>();

    /** An order given straight to one ship, with no target: RETREAT or SEARCH_AND_DESTROY. */
    public static final class ShipOrder {
        public int ship;
        public String type = "";
        public boolean direct;
    }
    public List<ShipOrder> shipOrders = new ArrayList<ShipOrder>();

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeByte(objectiveOwners.length);
            for (int o : objectiveOwners) w.out.writeByte(o);
            w.out.writeBoolean(fullAssault);
            w.out.writeBoolean(fullRetreat);
            w.out.writeShort(assignments.size());
            for (Assignment a : assignments) {
                w.out.writeUTF(a.type);
                w.out.writeByte(a.targetKind);
                w.out.writeInt(a.targetId);
                w.out.writeFloat(a.x);
                w.out.writeFloat(a.y);
                w.out.writeShort(a.members.size());
                for (int m : a.members) w.out.writeInt(m);
            }
            w.out.writeShort(shipOrders.size());
            for (ShipOrder s : shipOrders) {
                w.out.writeInt(s.ship);
                w.out.writeUTF(s.type);
                w.out.writeBoolean(s.direct);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static Orders decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        Orders o = new Orders();
        o.objectiveOwners = new int[in.readByte() & 0xFF];
        for (int i = 0; i < o.objectiveOwners.length; i++) o.objectiveOwners[i] = in.readByte() & 0xFF;
        o.fullAssault = in.readBoolean();
        o.fullRetreat = in.readBoolean();
        int n = in.readShort() & 0xFFFF;
        for (int i = 0; i < n; i++) {
            Assignment a = new Assignment();
            a.type = in.readUTF();
            a.targetKind = in.readByte();
            a.targetId = in.readInt();
            a.x = in.readFloat();
            a.y = in.readFloat();
            if (Snapshot.bad(a.x) || Snapshot.bad(a.y)) throw new IOException("order with an impossible point");
            int m = in.readShort() & 0xFFFF;
            for (int k = 0; k < m; k++) a.members.add(in.readInt());
            o.assignments.add(a);
        }
        int sn = in.readShort() & 0xFFFF;
        for (int i = 0; i < sn; i++) {
            ShipOrder s = new ShipOrder();
            s.ship = in.readInt();
            s.type = in.readUTF();
            s.direct = in.readBoolean();
            o.shipOrders.add(s);
        }
        return o;
    }
}
