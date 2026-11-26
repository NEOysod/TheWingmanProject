package wingman.net;

/**
 * How far behind the host's a shot from someone else's gun starts on a joiner's screen, and how it makes that up.
 * A copy's gun starts firing when the update saying so arrives. Copies are shown at the moment of the freshest
 * update, so what is late is only the part between the host's shot and that update (half the time between updates,
 * on average) and how late that update itself was. A shot that started that much behind also hits that much later
 * than the host's hit, whose damage arrives on time: the flash came after the hull bar had already dropped. So a
 * copy's shot leaves the barrel as before, then makes up the distance over its first tenth of a second, a little
 * each frame: never ahead of the barrel, and where the host's is once it is clear of the ship. Plain arithmetic:
 * test/MotionTest.
 */
public final class ShotLead {
    private ShotLead() {}

    /** Most a shot is moved on (seconds of its flight), and how long it takes to catch up. */
    public static final float MAX = 0.06f, OVER = 0.1f;

    /** The lead, in seconds of flight: how late the latest update was, and half the time between updates. */
    public static float lead(float late, float updateGap) {
        return Math.max(0f, Math.min(MAX, late + 0.5f * Math.max(0f, Math.min(updateGap, 0.1f))));
    }

    /** The share of the distance still to make up that is made up this frame. */
    public static float share(float timeLeft, float amount) {
        if (timeLeft <= 0f) return 1f;
        return Math.max(0f, Math.min(1f, amount / timeLeft));
    }
}
