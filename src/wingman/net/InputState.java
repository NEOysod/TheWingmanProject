package wingman.net;

import java.io.IOException;

/** What a remote player is pressing right now. Sent client -> host many times a second. */
public final class InputState {
    public static final int ACCEL = 1, BACK = 2, DECEL = 4, TURN_L = 8, TURN_R = 16,
            STRAFE_L = 32, STRAFE_R = 64, FIRE = 128, DEFENSE = 256, SYSTEM = 512, VENT = 1024, PULL_BACK = 2048, AUTOPILOT = 4096,
            /** The player has given their own ship the retreat order. */
            RETREAT = 8192,
            /** The player is at their edge of the map and has confirmed: leave the battle now. */
            RETREAT_NOW = 16384,
            /** The player has switched on Hold Fire for their ship: its autofire groups stay quiet. */
            HOLD_FIRE = 32768;

    public int flags;
    public float mouseX, mouseY;
    public int group;
    /** Network id of the ship the player has targeted (R), or -1. */
    public int target = -1;
    /** Bit i set = weapon group i is on autofire. */
    public int autofire;
    /** The heading the player's ship has on their own screen; the host turns the real ship to match. */
    public float facing;
    /**
     * How many times the player has used their ship system so far (the low 8 bits travel). Counted, not
     * "is it on right now": a system that fires and is over in an instant, or is used twice in a row, would
     * otherwise never be noticed on the host.
     */
    public int sysUses;
    /** For a drone system: 1 + the drone order the player has set (recall, deploy, attack). 0 = not a drone system. */
    public int sysOrders;
    /**
     * Other keys the player is holding that mods' scripts for their ship ask about (a hullmod's ability on a
     * hotkey, say), as key codes; at most MAX_KEYS. See Hooks.isKeyDown in the agent.
     */
    public int[] keys = NO_KEYS;
    public static final int MAX_KEYS = 8;
    private static final int[] NO_KEYS = new int[0];

    public boolean keyHeld(int key) {
        for (int k : keys) if (k == key) return true;
        return false;
    }

    /** A running number the player's game gives each set of controls it sends (the low 16 bits travel). */
    public int seq;
    /** Host-side: the number of the controls the ship last acted on, and for how long (battle seconds) it has. */
    public int appliedSeq = -1;
    public float sinceApplied;
    /** Host-side: when this input arrived (ms). Stale input is ignored so a frozen client can't hold a key down. */
    public long receivedAt;

    public boolean has(int bit) { return (flags & bit) != 0; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeShort(flags);
            w.out.writeFloat(mouseX);
            w.out.writeFloat(mouseY);
            w.out.writeByte(group);
            w.out.writeInt(target);
            w.out.writeShort(autofire);
            w.out.writeFloat(facing);
            w.out.writeByte(sysUses);
            w.out.writeByte(sysOrders);
            int n = Math.min(keys.length, MAX_KEYS);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) w.out.writeByte(keys[i]);
            w.out.writeShort(seq); // last, so that everything before it can be compared as "the same controls"
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    /** True if two encoded sets of controls differ in anything but their number. */
    public static boolean differ(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return true;
        for (int i = 0; i < a.length - 2; i++) if (a[i] != b[i]) return true;
        return false;
    }

    /**
     * For controls that came by UDP, which may arrive out of order or twice: taken only if their number is
     * later than the one held (or what is held has gone stale). Returns true if taken.
     */
    public boolean decodeIfNewer(byte[] data) throws IOException {
        if (data.length < 2) return false;
        int newSeq = ((data[data.length - 2] & 0xFF) << 8) | (data[data.length - 1] & 0xFF);
        boolean stale = System.currentTimeMillis() - receivedAt > 1000;
        if (!stale && (short) (newSeq - seq) <= 0) return false;
        decode(data);
        return true;
    }

    public void decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int newFlags = in.readShort() & 0xFFFF;
        float mx = in.readFloat(), my = in.readFloat();
        int newGroup = in.readByte();
        int newTarget = in.readInt();
        int newAutofire = in.readShort() & 0xFFFF;
        float newFacing = in.readFloat();
        int newUses = in.readByte() & 0xFF, newOrders = in.readByte() & 0xFF;
        int nKeys = in.readByte() & 0xFF;
        if (nKeys > MAX_KEYS) return; // impossible: ignored whole, like the other guard rails below
        int[] newKeys = nKeys == 0 ? NO_KEYS : new int[nKeys];
        for (int i = 0; i < nKeys; i++) newKeys[i] = in.readByte() & 0xFF;
        int newSeq = in.readShort() & 0xFFFF;
        // Guard rail: controls with impossible numbers in them are ignored whole; the last good ones stand
        // until they go stale, at which point the ship coasts to a stop.
        if (Snapshot.bad(mx) || Snapshot.bad(my) || Snapshot.bad(newFacing)) return;
        // ...and numbers that are merely out of range are brought back into it.
        if (newGroup < -1 || newGroup > 31) newGroup = -1;
        newFacing = newFacing % 360f;
        if (newFacing < 0f) newFacing += 360f;
        mx = Snapshot.clamp(mx, -200000f, 200000f);
        my = Snapshot.clamp(my, -200000f, 200000f);
        flags = newFlags;
        mouseX = mx;
        mouseY = my;
        group = newGroup;
        target = newTarget;
        autofire = newAutofire;
        facing = newFacing;
        sysUses = newUses;
        sysOrders = newOrders > 3 ? 0 : newOrders;
        keys = newKeys;
        seq = newSeq;
        receivedAt = System.currentTimeMillis();
    }
}
