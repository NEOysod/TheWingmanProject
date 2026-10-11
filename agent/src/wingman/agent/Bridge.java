package wingman.agent;

/**
 * What the hooks ask Wingman. Wingman hands one to {@link Hooks#setBridge} at the start of each co-op battle (the
 * host's AgentLink, a joiner's JoinerAgentLink); with none set, every hook behaves exactly like the call it
 * replaced.
 *
 * The ships are passed as Object (they are ShipAPI): this interface names no game class, so it can be shared by
 * every copy of Hooks whichever class loader holds it (see Shared).
 */
public interface Bridge {
    /**
     * Is the battle this bridge was set for still the one being played, with co-op running? Asked before every
     * changed answer, so a bridge left behind by an earlier battle changes nothing.
     */
    boolean live();

    /**
     * A script running for {@code from} (a ship a person on another PC flies) wants "the player" moved into
     * {@code to}. Returns true if that person is being moved (from the next frame), false if not (on a joiner's
     * PC always: the host decides; on the host: someone else flies it, the size limit, and the like).
     */
    boolean moveRemote(Object from, Object to);

    /** Where the person flying this ship points, in map coordinates; null if unknown. */
    float[] cursor(Object ship);

    /**
     * Is the person flying this ship flying it by hand (not on autopilot)? What the game's
     * {@code isUIAutopilotOn()} answers for its own player (true: by hand; the name is the other way round).
     */
    boolean manual(Object ship);

    /** Is this key held down by the person flying this ship? */
    boolean keyDown(Object ship, int key);
}
