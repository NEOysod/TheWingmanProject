package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's side of "steady controls" (host setting, off by default): a joiner's controls played back on the
 * host at the pace the joiner pressed them, a little late, instead of "whatever came last".
 *
 * Without it, two sets of controls that arrive in the same host frame (a bunched-up link) count as one: a tap
 * that began and ended between two host frames never happens, and a late set makes the ship lurch. With it:
 * <ul>
 * <li>Every set the joiner sends carries the joiner's clock and the two sets before it (redundant copies), so a
 * lost datagram is made good by the next one without waiting for a resend.</li>
 * <li>The host learns how late sets usually arrive (the quickest arrival is the base) and how much that varies:
 * the allowance is the 90th percentile of the last two seconds' lateness plus a few milliseconds, at most 80 ms.
 * Each set is played at its joiner time + base + allowance: a small, steady delay in place of an uneven one. A
 * rare late datagram (one in ten or fewer) doesn't raise the delay for everyone, it is just a little late.</li>
 * <li>The allowance changes gently: up by at most 4 ms a frame, down by at most 1 ms a frame, so a change in the
 * link never makes a burst of sets due at once (that is what "skipped to catch up" counted in 0.9.3).</li>
 * <li>At most one set a frame while on time, so a short tap lasts at least a frame; a little behind (more than two
 * frames), two a frame until caught up; far behind (the host hitched), it skips to the newest due set.</li>
 * </ul>
 * Edge presses (ship system, retreat) are counted in the controls, so nothing here can lose or double them.
 *
 * Bundle (Msg.INPUTS, Udp INPUTS): byte n (1-4), then n times: int joiner ms, byte length, the controls; newest first.
 */
public final class InputBuffer {
    /** Copies of earlier sets sent with each one. */
    public static final int COPIES = 2;
    /** Most extra delay allowed for jitter, and how long a measuring window is. */
    public static final int MAX_DELAY_MS = 80, WINDOW_MS = 2000;
    /** Lateness samples kept (two seconds of sets at up to 60 a second, roughly). */
    private static final int SAMPLES = 128;

    private static final class Item {
        int seq, t;
        byte[] data;
    }

    private final List<Item> queue = new ArrayList<Item>();
    private boolean any;
    /** The newest set played, or queued to be. */
    private int lastSeq;
    private long lastPlayedAt;
    // (host ms - joiner ms) for the quickest arrival, this window and the last; and the largest lateness over that
    private long baseNow = Long.MAX_VALUE, basePrev = Long.MAX_VALUE, windowAt;
    // Recent arrivals: when (host ms) and how far off (host ms - joiner ms), newest at sampleAt - 1
    private final long[] sampleTime = new long[SAMPLES], sampleOff = new long[SAMPLES];
    private int sampleAt, sampleCount;
    /** The allowance being moved toward its target, ms. */
    private float delay;
    /** For the log: sets that came only as a copy (their own datagram was lost), and catch-ups. */
    public int recovered, skipped, played;
    /** The delay being added right now, ms. */
    public int delayMs;

    public static byte[] bundle(List<byte[]> sets, List<Integer> times) {
        Msg.W w = new Msg.W();
        try {
            int n = Math.min(sets.size(), COPIES + 1);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) {
                w.out.writeInt(times.get(i));
                w.out.writeByte(sets.get(i).length);
                w.out.write(sets.get(i));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    private static int seqOf(byte[] d) { return ((d[d.length - 2] & 0xFF) << 8) | (d[d.length - 1] & 0xFF); }

    /** A bundle arrived (game thread). Malformed: IOException. */
    public void arrive(byte[] bundle, long now) throws IOException {
        In in = Msg.reader(bundle);
        int n = in.readByte();
        if (n < 1 || n > 4) throw new IOException("bad bundle");
        if (now - lastPlayedAt > 1000 && queue.isEmpty()) any = false; // a long silence (or a new battle): start over
        for (int i = 0; i < n; i++) {
            int t = in.readInt();
            int len = in.readByte() & 0xFF;
            if (len < 2 || len > 200) throw new IOException("bad controls length");
            byte[] d = new byte[len];
            in.readFully(d);
            int seq = seqOf(d);
            if (waiting(seq) || (any && (short) (seq - lastSeq) <= 0)) continue; // a copy of one we have, or played already
            if (i == 0) measure(now, t); // how late: only from a set's first arrival (a repeat comes later on purpose)
            if (i > 0) recovered++;
            Item it = new Item();
            it.seq = seq; it.t = t; it.data = d;
            int at = queue.size();
            while (at > 0 && (short) (queue.get(at - 1).seq - seq) > 0) at--;
            queue.add(at, it);
            if (queue.size() > 32) queue.remove(0); // a joiner flooding sets: the oldest go
        }
    }

    private boolean waiting(int seq) {
        for (Item it : queue) if (it.seq == seq) return true;
        return false;
    }

    private void measure(long now, int t) {
        if (now - windowAt > WINDOW_MS) { basePrev = baseNow; baseNow = Long.MAX_VALUE; windowAt = now; }
        long off = now - t;
        if (off < baseNow) baseNow = off;
        sampleTime[sampleAt] = now;
        sampleOff[sampleAt] = off;
        sampleAt = (sampleAt + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;
    }

    /** The allowance the link needs: 90th percentile of the last two seconds' lateness, plus a margin. */
    int target(long now) {
        long base = base();
        int n = 0;
        int[] late = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            if (now - sampleTime[i] > WINDOW_MS) continue;
            late[n++] = (int) Math.max(0, Math.min(10000, sampleOff[i] - base));
        }
        if (n == 0) return 0;
        java.util.Arrays.sort(late, 0, n);
        int p90 = late[Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1)];
        return Math.min(MAX_DELAY_MS, p90 + 4);
    }

    private long base() { return Math.min(baseNow, basePrev); }

    /** Once a host frame: put the set that is due into the player's controls. */
    public void playOut(long now, InputState into) throws IOException {
        if (queue.isEmpty()) return;
        long base = base();
        if (base == Long.MAX_VALUE) return;
        int want = target(now);
        if (want > delay) delay = Math.min(want, delay + 4f);
        else delay = Math.max(want, delay - 1f);
        delayMs = Math.round(delay);
        Item first = queue.get(0);
        long due = first.t + base + delayMs;
        if (due > now) return;
        long behind = now - due;
        int take = 1;
        if (behind > delayMs + 100) {
            // Far behind (the host hitched): catch up to the newest set that is due.
            while (take < queue.size() && queue.get(take).t + base + delayMs <= now) take++;
        } else if (behind > 34 && queue.size() > 1 && queue.get(1).t + base + delayMs <= now) {
            take = 2; // a little behind: two this frame (counted presses travel in every set, so none is lost)
        }
        Item it = queue.get(take - 1);
        skipped += take - 1;
        for (int i = 0; i < take; i++) queue.remove(0);
        into.decode(it.data);
        lastSeq = it.seq;
        any = true;
        lastPlayedAt = now;
        played++;
    }

    public void clear() { queue.clear(); any = false; sampleCount = 0; sampleAt = 0; delay = 0f; delayMs = 0; baseNow = Long.MAX_VALUE; basePrev = Long.MAX_VALUE; }
}
