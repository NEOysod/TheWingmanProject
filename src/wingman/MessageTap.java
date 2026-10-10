package wingman;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.net.In;
import wingman.net.Msg;

/**
 * Scripts' lines in the battle's message feed, sent from the host's game to joiners.
 *
 * The feed is a private field of the game's combat screen (CombatState.messageWidget). At the start of a
 * battle it is swapped for a MessageSpy, an exact copy with a listener on it, using java.lang.invoke (which
 * the script sandbox allows; java.lang.reflect it does not). The host sends each script's line (COMBAT_MSG);
 * a joiner shows the host's lines and holds back its own scripts' (their copies of the same scripts would
 * otherwise write the same lines a moment apart, and held-back objectives write none). The game's own lines
 * are made by each PC from what it sees, as before; Wingman's own notices are never sent or held back.
 *
 * A line is made of parts: text, colours, ships (an icon and name), objectives. Ships and objectives are sent
 * by the host's numbers and turned back into the joiner's copies; one with no copy is shown by name.
 */
final class MessageTap {
    private MessageTap() {}

    static final int MAX_PARTS = 40, MAX_TEXT = 1000;
    private static final byte COLOR = 1, TEXT = 2, SHIP = 3, OBJECTIVE = 4;

    private static boolean broken;
    private static MethodHandle getter, setter;

    /** Put a MessageSpy in as this battle's feed (or find the one already there). Null if it can't be done. */
    static MessageSpy install(CombatEngineAPI engine) {
        if (broken || engine == null) return null;
        try {
            Object ui = engine.getCombatUI();
            if (!(ui instanceof com.fs.starfarer.combat.CombatState)) return null;
            if (getter == null) {
                Class<?> feed = MessageSpy.class.getSuperclass();
                // MethodHandles.privateLookupIn (Java 9 on) is looked up by name so this builds for Java 8 too.
                MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                        MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
                MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(com.fs.starfarer.combat.CombatState.class, MethodHandles.lookup());
                getter = l.findGetter(com.fs.starfarer.combat.CombatState.class, "messageWidget", feed);
                setter = l.findSetter(com.fs.starfarer.combat.CombatState.class, "messageWidget", feed);
            }
            Object now = getter.invoke(ui);
            if (now instanceof MessageSpy) return (MessageSpy) now;
            MessageSpy spy = new MessageSpy();
            setter.invoke(ui, spy);
            Dbg.info(MessageTap.class, "Wingman messages: listening to the battle's message feed");
            return spy;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(MessageTap.class).warn("Wingman: the battle's message feed can't be followed on this PC; "
                    + "scripts' lines are not shared", t);
            return null;
        }
    }

    /** How the host names ships and objectives on the wire. */
    interface HostNames {
        Integer shipId(ShipAPI ship);
        int objectiveIndex(BattleObjectiveAPI objective);
    }

    /** One script's line as COMBAT_MSG: byte indent, byte count, then each part. Null if nothing to send. */
    static byte[] encode(CombatEngineAPI engine, int indent, Object[] parts, HostNames names) throws java.io.IOException {
        if (parts == null) return null;
        Msg.W w = new Msg.W();
        int n = Math.min(parts.length, MAX_PARTS), sent = 0;
        Msg.W body = new Msg.W();
        for (int i = 0; i < n; i++) {
            Object p = parts[i];
            if (p instanceof Color) {
                body.out.writeByte(COLOR);
                body.out.writeInt(((Color) p).getRGB());
            } else if (p instanceof String) {
                body.out.writeByte(TEXT);
                String s = (String) p;
                body.out.writeUTF(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s);
            } else if (p instanceof DeployedFleetMemberAPI || p instanceof ShipAPI || p instanceof FleetMemberAPI) {
                ShipAPI ship = null;
                String name = null;
                if (p instanceof ShipAPI) ship = (ShipAPI) p;
                else if (p instanceof DeployedFleetMemberAPI) ship = ((DeployedFleetMemberAPI) p).getShip();
                else {
                    FleetMemberAPI m = (FleetMemberAPI) p;
                    name = m.getShipName();
                    try { ship = engine.getFleetManager(m.getOwner()).getShipFor(m); } catch (Throwable t) { /* by name */ }
                }
                Integer id = ship == null ? null : names.shipId(ship);
                if (id != null) {
                    body.out.writeByte(SHIP);
                    body.out.writeInt(id);
                } else {
                    if (name == null && ship != null) name = ship.getName();
                    body.out.writeByte(TEXT);
                    body.out.writeUTF(name == null ? "" : name);
                }
            } else if (p instanceof BattleObjectiveAPI) {
                int k = names.objectiveIndex((BattleObjectiveAPI) p);
                if (k >= 0 && k < 255) {
                    body.out.writeByte(OBJECTIVE);
                    body.out.writeByte(k);
                } else {
                    body.out.writeByte(TEXT);
                    String dn = ((BattleObjectiveAPI) p).getDisplayName();
                    body.out.writeUTF(dn == null ? "" : dn);
                }
            } else {
                continue; // a kind of part the feed itself doesn't take
            }
            sent++;
        }
        if (sent == 0) return null;
        w.out.writeByte(Math.max(0, Math.min(indent, 127)));
        w.out.writeByte(sent);
        w.out.write(body.done());
        return w.done();
    }

    /** How a joiner finds its copies of the host's ships and objectives. */
    interface LocalNames {
        ShipAPI ship(int hostId);
        BattleObjectiveAPI objective(int hostIndex);
    }

    /** The parts of a COMBAT_MSG for this screen; null if the message is malformed. Index 0 is the indent. */
    static Object[] decode(byte[] data, LocalNames names) throws java.io.IOException {
        In in = Msg.reader(data);
        int indent = in.readByte() & 0x7F;
        int n = in.readByte() & 0xFF;
        if (n > MAX_PARTS) return null;
        java.util.List<Object> out = new java.util.ArrayList<Object>(n + 1);
        out.add(indent);
        for (int i = 0; i < n; i++) {
            byte kind = in.readByte();
            if (kind == COLOR) out.add(new Color(in.readInt(), true));
            else if (kind == TEXT) {
                String s = in.readUTF();
                if (s.length() > MAX_TEXT) return null;
                out.add(s);
            } else if (kind == SHIP) {
                ShipAPI s = names.ship(in.readInt());
                out.add(s != null ? (Object) s : "?");
            } else if (kind == OBJECTIVE) {
                BattleObjectiveAPI o = names.objective(in.readByte() & 0xFF);
                out.add(o != null ? (Object) o : "?");
            } else return null;
        }
        return out.toArray();
    }
}
