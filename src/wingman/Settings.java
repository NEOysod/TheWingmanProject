package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * The in-game settings page. What is changed here is kept in the game's own "common" save folder (the one
 * place a mod may write) and laid over wingman_settings.json every time the settings are read, so nobody has to
 * edit a file. A setting put back to the file's value is simply forgotten here.
 *
 * The same page is shown from the CO-OP panel on the title screen (F3) and inside a battle (Ctrl+Shift+O),
 * where the settings that can change mid-battle take effect at once.
 */
final class Settings {
    private static final String FILE = "wingman_settings";

    static final int HOST = 1, JOINER = 2, BOTH = 3;
    private static final String[] YES_NO = { "true", "false" };

    static final class Item {
        final String key, label, help;
        final String[] options; // null: typed in
        final int who, page;
        final boolean live;     // takes effect in a battle already under way

        Item(int page, String key, String label, int who, boolean live, String[] options, String help) {
            this.page = page; this.key = key; this.label = label; this.who = who; this.live = live; this.options = options; this.help = help;
        }
    }

    static final String[] PAGES = { "HOSTING AND JOINING", "SMOOTHNESS", "KEEPING THE GAMES ALIKE", "TROUBLESHOOTING" };
    static final List<Item> ITEMS = new ArrayList<Item>();
    static {
        ITEMS.add(new Item(0, "hostEnabled", "Friends can join my battles", HOST, false, YES_NO,
                "On: every battle you start can be joined. Off: the mod does nothing in your own battles."));
        ITEMS.add(new Item(0, "password", "Join password", HOST, true, null,
                "Type it here; friends type the same into their CO-OP panel. Empty: anyone who can reach this PC may join."));
        ITEMS.add(new Item(0, "maxPlayers", "Players, host included", HOST, false, new String[] { "2", "3", "4", "5", "6", "8" },
                "4 is what the mod is built and tuned for. More players means more bandwidth."));
        ITEMS.add(new Item(0, "maxShipSize", "Largest ship a joiner may fly", HOST, true, new String[] { "FRIGATE", "DESTROYER", "CRUISER", "CAPITAL_SHIP" },
                "CAPITAL_SHIP means no limit."));
        ITEMS.add(new Item(0, "readyCheck", "Ready check before a battle", HOST, false, YES_NO,
                "On: the battle is held paused until everyone has loaded and pressed ready."));
        ITEMS.add(new Item(0, "modCheck", "Mod list check", HOST, false, new String[] { "content", "all", "off" },
                "content: mods must match, except utility mods. all: every mod must match. off: no check (expect trouble)."));
        ITEMS.add(new Item(0, "autoJoin", "Join the host's battles by myself", JOINER, false, YES_NO,
                "On: while you are connected and on the title screen, you are taken into each battle the host starts, after a 3-second countdown (Esc skips one)."));
        ITEMS.add(new Item(0, "rememberShip", "Put me back in my last ship", JOINER, true, YES_NO,
                "On: at the start of a battle you are seated in the ship you flew last time, if it is there and free. You can still choose another."));
        ITEMS.add(new Item(0, "playerTags", "Player tags over ships", BOTH, true, YES_NO,
                "On: P1 (the host, green), P2 (blue), P3 (yellow), P4 (white) next to the ship each player flies."));
        ITEMS.add(new Item(0, "playerOutlines", "Outline around players' ships", BOTH, true, YES_NO,
                "On: a thin diamond in the player's colour around each player's ship (shown with the tags)."));
        ITEMS.add(new Item(0, "announceAddresses", "Show my addresses in battle", HOST, false, YES_NO,
                "On: when a battle starts, your addresses are shown in the message feed. Off: only in the CO-OP panel and the log (safer on a stream)."));
        ITEMS.add(new Item(0, "port", "Port", BOTH, false, null,
                "Type a number. Must be the same for everyone; the host needs this TCP port reachable. Normally 24816."));

        ITEMS.add(new Item(1, "snapshotRate", "Updates per second", HOST, true, new String[] { "10", "15", "20", "30", "45", "60" },
                "How often the host sends positions. 60 is smoothest and uses the most bandwidth; 20-30 suits a weak connection."));
        ITEMS.add(new Item(1, "sendEverything", "Send everything, every update", HOST, true, YES_NO,
                "On: smoothest. Off: things simply carrying on as before are skipped; about a third of the bandwidth."));
        ITEMS.add(new Item(1, "compactUpdates", "Send updates as changes only", HOST, true, YES_NO,
                "On: each update carries only what changed since the one before; about a third of the size, nothing lost."));
        ITEMS.add(new Item(1, "compressLink", "Compress the connection", BOTH, false, YES_NO,
                "On: everything sent is compressed, if the other side can read it. Used from the next time you connect."));
        ITEMS.add(new Item(1, "useUdp", "Updates and controls by UDP", BOTH, false, YES_NO,
                "On: a lost packet costs one update, not a freeze. Falls back to TCP by itself where UDP can't get through. Needed on both; used from the next time you connect."));
        ITEMS.add(new Item(1, "trimUnseen", "Send unseen enemies less often", HOST, true, YES_NO,
                "On: enemies nobody on your side can see are reported about once a second. Saves bandwidth in big battles."));
        ITEMS.add(new Item(1, "predictOwnShip", "My ship answers my controls at once", JOINER, true, YES_NO,
                "On: your ship moves straight away and is quietly kept in step with the host. Off: it waits for the host's answer."));
        ITEMS.add(new Item(1, "syncTolerance", "Difference from the host left alone", JOINER, true, new String[] { "0", "0.5", "1", "1.5", "2", "3" },
                "1 is normal. Raise it if ships, fighters or missiles shake; lower it to follow the host more exactly."));

        ITEMS.add(new Item(2, "scriptedHits", "Hits by shots with a script", JOINER, true, new String[] { "match", "host", "local" },
                "match: your game's own hits count, and the host's are played out only where yours are missing. "
                + "host: only the host's hits are shown. local: each PC judges for itself."));
        ITEMS.add(new Item(2, "matchWindowMs", "Same-hit window (ms)", JOINER, true, new String[] { "30", "50", "100", "150", "200", "300" },
                "With \"match\": how far apart in time your hit and the host's may be and still count as the same one. 30 is about two frames."));
        ITEMS.add(new Item(2, "shotCounts", "Weapons fire as often as the host's", JOINER, true, YES_NO,
                "On: each weapon on a copy of a ship fires exactly as many times as the real one did."));
        ITEMS.add(new Item(2, "syncStats", "Ship stats follow the host", BOTH, true, YES_NO,
                "Speed, turn rate, flux, ranges, rate of fire. The host sends them, the joiner applies them: needed on both."));
        ITEMS.add(new Item(2, "syncScriptMemory", "Script memory follows the host", BOTH, true, YES_NO,
                "The simple values mods' scripts keep on a ship are copied from the host. Needed on both."));
        ITEMS.add(new Item(2, "damageReports", "Damage reports", BOTH, true, YES_NO,
                "The host reports damage that no weapon accounts for; a joiner plays out what its own game missed. Needed on both."));

        ITEMS.add(new Item(3, "debugLog", "Detailed log for bug reports", BOTH, true, YES_NO,
                "On: co-op diagnostics are written to starsector.log. Turn it on (on both PCs) before reproducing a problem."));
    }

    private Settings() {}

    /** What has been changed in the settings page: setting name to value. */
    static Map<String, String> loadOverrides() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return out;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            if (text == null) return out;
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                if (find(key) == null) continue; // only names we know: the file is the player's, but still
                String value = line.substring(eq + 1).replace('\r', ' ').trim();
                out.put(key, value.length() > 64 ? value.substring(0, 64) : value);
            }
        } catch (Throwable t) {
            Global.getLogger(Settings.class).warn("Wingman: could not read the settings changed in game; wingman_settings.json is used as it is", t);
        }
        return out;
    }

    static boolean save(Map<String, String> overrides) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue().replace('\n', ' ').replace('\r', ' ')).append('\n');
            }
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
            return true;
        } catch (Throwable t) {
            Global.getLogger(Settings.class).warn("Wingman: could not save the settings; they last until the game is closed", t);
            return false;
        }
    }

    static Item find(String key) {
        for (Item it : ITEMS) if (it.key.equals(key)) return it;
        return null;
    }

    /** The page itself: which row is picked, the keys that change it, and the lines to draw. */
    static final class Menu {
        boolean open;
        private int page, row;
        private WingmanConfig cfg, file;
        private Map<String, String> overrides = new LinkedHashMap<String, String>();
        private boolean inBattle, saveFailed;
        /** Settings changed since the owner last looked; the owner empties it. */
        final List<String> changed = new ArrayList<String>();

        /**
         * @param live the settings object a running battle is reading, which is then changed in place;
         *             null on the title screen, where the next battle reads everything afresh.
         */
        void open(WingmanConfig live) {
            inBattle = live != null;
            cfg = live != null ? live : WingmanConfig.load();
            file = WingmanConfig.fromFile();
            overrides = loadOverrides();
            open = true;
            saveFailed = false;
        }

        private List<Item> rows() {
            List<Item> out = new ArrayList<Item>();
            for (Item it : ITEMS) if (it.page == page) out.add(it);
            return out;
        }

        private void put(Item it, String value) {
            String before = cfg.get(it.key);
            cfg.set(it.key, value);
            cfg.tidy(false);
            String now = cfg.get(it.key);
            // The same as the file says: nothing to remember. (A half-typed port is remembered as typed.)
            if (it.options != null && now != null && now.equals(file.get(it.key))) overrides.remove(it.key);
            else if (it.options != null) overrides.put(it.key, now);
            else if (value.equals(file.get(it.key))) overrides.remove(it.key);
            else overrides.put(it.key, value);
            saveFailed = !save(overrides);
            if (now != null && !now.equals(before)) changed.add(it.key);
            if ("debugLog".equals(it.key)) Dbg.on = cfg.debugLog;
        }

        /** What the row shows: for a typed setting, what has been typed so far (which may not be valid yet). */
        private String shown(Item it) {
            if (it.options == null && overrides.containsKey(it.key)) return overrides.get(it.key);
            String v = cfg.get(it.key);
            return v == null ? "" : v;
        }

        private void step(Item it, int dir) {
            if (it.options == null) return;
            String cur = cfg.get(it.key);
            int at = -1;
            for (int i = 0; i < it.options.length; i++) if (it.options[i].equalsIgnoreCase(cur)) at = i;
            if (at < 0) { // a value from the file that is not one of the steps: go to the nearest step that way
                at = dir > 0 ? -1 : it.options.length;
                try {
                    float f = Float.parseFloat(cur);
                    for (int i = 0; i < it.options.length; i++) {
                        if (Float.parseFloat(it.options[i]) < f) { if (dir > 0) at = i; }
                        else if (dir < 0 && at == it.options.length) at = i;
                    }
                } catch (Throwable t) { /* start from the end */ }
            }
            at = (at + dir + it.options.length) % it.options.length;
            put(it, it.options[at]);
        }

        /** Takes every key while the page is up. */
        void keys(List<InputEventAPI> events) {
            if (!open || events == null) return;
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyboardEvent()) continue;
                e.consume();
                if (!e.isKeyDownEvent()) continue;
                int key = e.getEventValue();
                List<Item> rows = rows();
                if (row >= rows.size()) row = Math.max(0, rows.size() - 1);
                Item it = rows.isEmpty() ? null : rows.get(row);
                if (key == 1) { open = false; return; }                                              // Escape
                if (key == 15) { page = (page + (e.isShiftDown() ? PAGES.length - 1 : 1)) % PAGES.length; row = 0; continue; } // Tab
                if (key == 200) { row = (row + rows.size() - 1) % Math.max(1, rows.size()); continue; } // up
                if (key == 208) { row = (row + 1) % Math.max(1, rows.size()); continue; }             // down
                if (it == null) continue;
                if (key == 211) { // Delete: back to what the file says
                    overrides.remove(it.key);
                    String before = cfg.get(it.key);
                    cfg.set(it.key, file.get(it.key));
                    cfg.tidy(false);
                    saveFailed = !save(overrides);
                    if (before != null && !before.equals(cfg.get(it.key))) changed.add(it.key);
                    if ("debugLog".equals(it.key)) Dbg.on = cfg.debugLog;
                    continue;
                }
                if (it.options != null) {
                    if (key == 203) step(it, -1);
                    else if (key == 205 || key == 57 || Panel.isEnter(key)) step(it, 1); // right, space, enter
                    continue;
                }
                String text = shown(it);
                if (key == 14) { // Backspace
                    if (text.length() > 0) put(it, text.substring(0, text.length() - 1));
                } else {
                    char c = e.getEventChar();
                    boolean ok = "port".equals(it.key) ? (c >= '0' && c <= '9') : (c >= ' ' && c < 127 && c != '=');
                    int max = "port".equals(it.key) ? 5 : 32;
                    if (ok && text.length() < max) put(it, text + c);
                }
            }
        }

        String title() { return "SETTINGS: " + PAGES[page] + "   (" + (page + 1) + "/" + PAGES.length + ")"; }

        String footer() {
            return "Up/Down: pick     Left/Right: change     Delete: back to default     Tab: next page     Esc: done";
        }

        void lines(List<String> lines, List<Color> colors) {
            List<Item> rows = rows();
            if (row >= rows.size()) row = Math.max(0, rows.size() - 1);
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            for (int i = 0; i < rows.size(); i++) {
                Item it = rows.get(i);
                String v = shown(it);
                if ("true".equals(v)) v = "on"; else if ("false".equals(v)) v = "off";
                if ("password".equals(it.key) && v.isEmpty() && i != row) v = "(none)";
                if (it.options == null && i == row && blink) v += "_";
                String who = it.who == HOST ? "host" : it.who == JOINER ? "joiner" : "both";
                lines.add((i == row ? ">  " : "    ") + it.label + ":   " + v + "     [" + who + "]"
                        + (overrides.containsKey(it.key) ? " *" : ""));
                colors.add(i == row ? Panel.TEXT : Panel.DIM);
            }
            if (!rows.isEmpty()) {
                Item it = rows.get(row);
                lines.add(it.help);
                colors.add(Panel.GOOD);
                String when;
                if ("port".equals(it.key)) {
                    int typed = -1;
                    try { typed = Integer.parseInt(shown(it)); } catch (Throwable t) { /* not a number yet */ }
                    when = typed < 1024 || typed > 65535 ? "Not a usable port yet (1024-65535): 24816 is used until it is."
                            : "Used from the next battle you host or join.";
                } else if (!inBattle) {
                    when = it.who == HOST ? "Matters when you host. Used from your next battle."
                            : it.who == JOINER ? "Matters when you join. Used from your next battle."
                            : "Used from your next battle.";
                } else {
                    when = it.live ? "Takes effect now." : "Takes effect from the next battle.";
                }
                lines.add(when + (overrides.containsKey(it.key) ? "   (* = changed here; wingman_settings.json says " + display(file.get(it.key)) + ")" : ""));
                colors.add(Panel.DIM);
            }
            if (saveFailed) {
                lines.add("Could not save: these settings last until the game is closed. See starsector.log.");
                colors.add(Panel.BAD);
            }
        }

        private static String display(String v) {
            if (v == null) return "?";
            return "true".equals(v) ? "on" : "false".equals(v) ? "off" : v.isEmpty() ? "nothing" : v;
        }
    }
}
