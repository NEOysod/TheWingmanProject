package wingman.net;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes network addresses out of text before it is written to starsector.log, so a log can be shared (in a bug
 * report, in a public channel) without giving away anyone's IP address. Wingman's own log lines never name an
 * address on purpose; this is the safety net for text that comes from elsewhere (an error message from Java,
 * a line a player typed). Addresses are still shown on screen where a player needs them (the CO-OP panel).
 */
public final class Redact {
    private Redact() {}

    private static final String HIDDEN = "[address]";
    // IPv4, with an optional port.
    private static final Pattern V4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");
    // IPv6 (two or more colons among hex digits), optionally in brackets with a port, optionally with a zone.
    private static final Pattern V6 = Pattern.compile("\\[?(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[\\w.]+)?\\]?(?::\\d{1,5})?");
    /** Host names typed into the join form: hidden too, wherever they turn up. */
    private static volatile String typedHost = "";

    /** Note the name or address the player typed, so it is hidden as well (it may be a host name, not digits). */
    public static void alsoHide(String host) {
        typedHost = host == null ? "" : host.trim();
    }

    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        String h = typedHost;
        if (h.length() >= 3) s = s.replace(h, HIDDEN);
        s = V4.matcher(s).replaceAll(Matcher.quoteReplacement(HIDDEN));
        Matcher m = V6.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String g = m.group();
            // Only something that really looks like an address: at least two colons and a hex digit (not "a::b" in prose, not times like 12:30).
            int colons = 0, hex = 0;
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                if (c == ':') colons++;
                else if (Character.digit(c, 16) >= 0) hex++;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(colons >= 2 && hex >= 1 ? HIDDEN : g));
        }
        m.appendTail(out);
        return out.toString();
    }
}
