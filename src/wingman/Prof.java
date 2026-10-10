package wingman;

/**
 * Debug-only stopwatch: how long the co-op code itself takes each frame, part by part. Does nothing unless
 * "debugLog" is on. Call begin() at the top of a frame, mark(part) after each part, end() at the bottom.
 */
final class Prof {
    private final String[] names;
    private final long[] total;
    private long last, start, worst;
    private int frames;

    Prof(String... names) {
        this.names = names;
        this.total = new long[names.length];
    }

    void begin() {
        if (!Dbg.on) return;
        start = System.nanoTime();
        last = start;
    }

    /** Everything since the previous mark (or begin) is counted as this part. */
    void mark(int part) {
        if (!Dbg.on) return;
        long now = System.nanoTime();
        total[part] += now - last;
        last = now;
    }

    void end() {
        if (!Dbg.on) return;
        frames++;
        if (last - start > worst) worst = last - start;
    }

    /** Average milliseconds a frame since the last report, in total and by part; then starts counting afresh. */
    String report() {
        if (frames == 0) return "no frames timed";
        long sum = 0;
        for (long t : total) sum += t;
        StringBuilder sb = new StringBuilder();
        sb.append(ms(sum / frames)).append(" ms a frame over ").append(frames).append(" frames (slowest frame ").append(ms(worst)).append(" ms):");
        for (int i = 0; i < names.length; i++) {
            sb.append(i == 0 ? " " : ", ").append(names[i]).append(' ').append(ms(total[i] / frames));
            total[i] = 0;
        }
        frames = 0;
        worst = 0;
        return sb.toString();
    }

    private static String ms(long nanos) {
        long hundredths = Math.round(nanos / 10000.0);
        return (hundredths / 100) + "." + (hundredths % 100 < 10 ? "0" : "") + (hundredths % 100);
    }
}
