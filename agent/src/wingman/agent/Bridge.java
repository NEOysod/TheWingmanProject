package wingman.agent;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * What the hooks ask Wingman. Wingman (the host side of a co-op battle) hands one to {@link Hooks#setBridge}
 * at the start of each battle it hosts; with none set, every hook behaves exactly like the call it replaced.
 */
public interface Bridge {
    /**
     * Is the battle this bridge was set for still the one being played, with co-op running? Asked before every
     * changed answer, so a bridge left behind by an earlier battle changes nothing.
     */
    boolean live();

    /**
     * A script running for {@code from} (a ship a joiner flies) wants "the player" moved into {@code to}.
     * Returns true if the joiner is being moved (from the next frame), false if Wingman won't (someone else
     * flies it, the host's size limit, and the like).
     */
    boolean moveRemote(ShipAPI from, ShipAPI to);

    /** Where the joiner flying this ship has their cursor, in map coordinates; null if unknown. */
    float[] cursor(ShipAPI ship);

    /**
     * Is the person flying this ship flying it by hand (not on autopilot)? What the game's
     * {@code isUIAutopilotOn()} answers for its own player (true: by hand; the name is the other way round).
     */
    boolean manual(ShipAPI ship);

    /** Is this key held down by the joiner flying this ship? */
    boolean keyDown(ShipAPI ship, int key);
}
