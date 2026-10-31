package wingman;

import java.awt.Color;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.combat.CombatState;

import wingman.net.In;
import wingman.net.Msg;

/**
 * Scripts' lines in the battle's message feed, sent from the host's game to joiners.
 *
 * A script's line reaches the feed only through CombatUIAPI.addMessage, which the game's own lines never use;
 * Patches.MessageLines hooks that method and hands each line here. The host sends it (COMBAT_MSG); a joiner
 * shows the host's lines and holds back its own scripts' (their copies of the same scripts would otherwise
 * write the same lines a moment apart, and held-back objectives write none). The game's own lines are made by
 * each PC from what it sees, as before; Wingman's own notices are never sent or held back.
 *
 * A line is made of parts: text, colours, ships (an icon and name), objectives. Ships and objectives are sent
 * by the host's numbers and turned back into the joiner's copies; one with no copy is shown by name.
 */
public final class MessageTap {
    private MessageTap() {}

    static final int MAX_PARTS = 40, MAX_TEXT = 1000;
    private static final byte COLOR = 1, TEXT = 2, SHIP = 3, OBJECTIVE = 4;

    /** Told of every script's line; returns false to keep it off this screen. */
    public interface LineSink { boolean line(int indent, Object[] parts); }

    /** The battle's combat screen while its lines are followed; another screen's lines are left alone. */
    private static volatile Object feed;
    private static volatile LineSink sink;
    /** Set while Wingman itself writes a line (Ui.say): those are never sent or held back. */
    static volatile boolean ownLine;

    /** Follow this battle's scripts' lines, offering each to the sink; a line the sink refuses is not shown. */
    public static void install(CombatEngineAPI engine, LineSink sink) {
        if (engine == null) return;
        Object ui = engine.getCombatUI();
        if (!(ui instanceof CombatState)) return;
        if (ui != feed) Dbg.info(MessageTap.class, "Wingman messages: listening to the battle's message feed");
        feed = ui;
        MessageTap.sink = sink;
    }

    /** A script's line, from the addMessage hook: true holds it back. */
    public static boolean hold(Object self, int indent, Object[] parts) {
        LineSink s = sink;
        if (ownLine || s == null || self != feed) return false;
        try { return !s.line(indent, parts); } catch (Throwable t) { return false; }
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
    public interface LocalNames {
        ShipAPI ship(int hostId);
        BattleObjectiveAPI objective(int hostIndex);
    }

    /** Show a line the host's game wrote, at its indent. */
    public static void show(CombatEngineAPI engine, int indent, Object[] parts) {
        if (engine == null || engine.getCombatUI() == null) return;
        ownLine = true;
        try { engine.getCombatUI().addMessage(indent, parts); } finally { ownLine = false; }
    }

    /** The parts of a COMBAT_MSG for this screen; null if the message is malformed. Index 0 is the indent. */
    public static Object[] decode(byte[] data, LocalNames names) throws java.io.IOException {
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
