package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * F1 on one of Wingman's pages on the campaign map (and the "?" on the CO-OP panel): every key there, and Wingman's
 * own keys that work anywhere, in one box over the page. It goes away with F1 or Esc, or when its page closes.
 */
final class KeyHelp {
    private KeyHelp() {}

    private static volatile String section;
    private static volatile long drawnAt;

    static void toggle(String what) {
        if (what.equals(section)) { section = null; return; }
        section = what;
        drawnAt = System.currentTimeMillis();
    }

    static void close() { section = null; }

    /** Open over this page (and drawn lately: a page that stopped drawing took it away). */
    static boolean isOpen(String what) {
        if (section != null && System.currentTimeMillis() - drawnAt > 600) section = null;
        return what.equals(section);
    }

    /** The keys of a page, as {key, what it does}. */
    static List<String[]> rows(String what) {
        List<String[]> r = new ArrayList<String[]>();
        if ("Officer links".equals(what)) {
            r.add(new String[] { "Left / Right / Up / Down", "Choose an officer" });
            r.add(new String[] { "Tab / Shift+Tab", "The next / previous friend" });
            r.add(new String[] { "Enter", "Link the officer to the friend" });
            r.add(new String[] { "Delete", "Unlink the friend's officer" });
            r.add(new String[] { "Wheel", "Scroll the officers (over the friends: along them)" });
            r.add(new String[] { "Esc  or  " + Keys.name("officerLinks"), "Close" });
        } else if ("Friend refits".equals(what)) {
            r.add(new String[] { "Left / Right", "The refit before / after" });
            r.add(new String[] { "A", "Accept (at a market)" });
            r.add(new String[] { "D", "Deny" });
            r.add(new String[] { "T", "Trust this friend's refits, or ask again" });
            r.add(new String[] { "Esc  or  " + Keys.name("friendRefits"), "Close" });
        } else if ("Wish lists".equals(what)) {
            r.add(new String[] { "Up / Down, PgUp / PgDn", "Scroll" });
            r.add(new String[] { "B", "Buy everything on the lists that this market has" });
            r.add(new String[] { "Esc  or  " + Keys.name("wishLists"), "Close" });
        } else if ("CO-OP panel".equals(what)) {
            r.add(new String[] { "Click a friend", "Their officer, how their refits are taken, a refit waiting" });
            r.add(new String[] { "Wheel", "Scroll the players (a big game)" });
            r.add(new String[] { "Click CHAT", "Open or fold the chat; click the line under it to type" });
        }
        r.add(new String[] { "", "" });
        r.add(new String[] { "", "WINGMAN'S KEYS (change them in Settings, Keys)" });
        for (Keys.Bind b : Keys.list()) r.add(new String[] { b.text(), b.label + "  (" + b.where + ")" });
        r.add(new String[] { "Ctrl+Shift+2 ... 9", "Remove that player, by P number  (Battle, host)" });
        return r;
    }

    /** Draws the box if it is open over this page. Call last in the page's drawing (inside Style.push). */
    static void draw(String what) {
        if (!isOpen(what)) return;
        drawnAt = System.currentTimeMillis();
        String body = Style.body();
        List<String[]> rows = rows(what);
        float sw = Style.sw(), sh = Style.sh();
        float w = Math.min(760f, sw - 40f), keyW = Math.min(240f, w * 0.36f);
        float lh = Math.max(Style.lineHeight(body), Style.chipHeight()) + 7f;
        float h = Style.BAR_H + 16f + rows.size() * lh + Style.lineHeight(Style.SMALL_FONT) + 24f;
        h = Math.min(h, sh - 40f);
        float x = (sw - w) / 2f, y = (sh - h) / 2f;
        Style.fill(0f, 0f, sw, sh, new Color(0, 0, 0, 120));
        float top = Style.window(x, y, w, h, "KEYS: " + what.toUpperCase(), "F1 closes") - 10f;
        for (String[] r : rows) {
            if (top - lh < y + 30f) break;
            if (r[0].isEmpty() && !r[1].isEmpty()) {
                Style.text(r[1], Style.SMALL_FONT, Style.ACCENT, x + Style.PAD, top - 4f, w - 2 * Style.PAD);
            } else if (!r[0].isEmpty()) {
                float cx = x + Style.PAD;
                for (String part : r[0].split("  or  ")) {
                    cx += Style.chip(part, Style.TEXT, cx, top - (lh - 7f - Style.chipHeight()) / 2f) + 6f;
                    if (cx > x + Style.PAD + keyW) break;
                }
                float tx = Math.max(x + Style.PAD + keyW, cx + 4f);
                Style.text(Widgets.fit(r[1], body, x + w - Style.PAD - tx), body, Style.DIM, tx, top - 2f, x + w - Style.PAD - tx);
            }
            top -= lh;
        }
        Style.text("F1 or Esc: close", Style.SMALL_FONT, Style.FAINT, x + Style.PAD, y + 22f, w - 2 * Style.PAD);
    }
}
