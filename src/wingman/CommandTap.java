package wingman;

import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

/**
 * Joiner side: what the game itself tells the joiner's own ship to do, read from the ship's command queue
 * (ShipAPI.getCommands2, new in 0.98.5a). A ship's advance listeners run at the start of its advance, before the
 * ship carries out and clears that frame's commands, so every command the player's controls gave is seen here
 * once. That catches the fire key wherever it is bound and every press, however short, where watching the
 * mouse button and the ship's state can miss them. If the queue can't be read (an older game) or the ship isn't
 * advancing (paused), {@link #live()} is false and the controls are worked out as before.
 */
final class CommandTap implements AdvanceableListener {
    private final ShipAPI ship;
    private boolean fire, vent;
    private long seenAt;
    private boolean broken;

    private CommandTap(ShipAPI ship) { this.ship = ship; }

    private static CommandTap current;

    /** The tap on this ship, put on it if it isn't yet (and taken off the ship flown before). Null if it can't be. */
    static CommandTap on(ShipAPI ship) {
        if (ship == null) return null;
        if (current != null && current.ship == ship) return current.broken ? null : current;
        off();
        try {
            CommandTap t = new CommandTap(ship);
            ship.addListener(t);
            current = t;
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Off the ship it is on (leaving a battle, or flying another ship). */
    static void off() {
        CommandTap t = current;
        current = null;
        if (t == null) return;
        try { t.ship.removeListener(t); } catch (Throwable e) { /* the ship is gone */ }
    }

    @Override
    public void advance(float amount) {
        if (broken) return;
        try {
            List<ShipCommand> cmds = ship.getCommands2();
            seenAt = System.nanoTime();
            if (cmds == null) return;
            for (ShipCommand c : cmds) {
                if (c == ShipCommand.FIRE) fire = true;
                else if (c == ShipCommand.VENT_FLUX) vent = true;
            }
        } catch (Throwable t) {
            broken = true; // no command queue to read here: the controls are worked out as before
            Dbg.info(CommandTap.class, "Wingman controls: the game's commands can't be read (" + t + "), using the ship's state");
        }
    }

    /** Read this frame or very nearly (a quarter of a second), so what it saw stands for the controls. */
    boolean live() {
        return !broken && seenAt != 0 && System.nanoTime() - seenAt < 250_000_000L;
    }

    /** Has the game told the ship to fire since last asked? */
    boolean takeFire() { boolean f = fire; fire = false; return f; }

    /** Has the game told the ship to vent since last asked? */
    boolean takeVent() { boolean v = vent; vent = false; return v; }
}
