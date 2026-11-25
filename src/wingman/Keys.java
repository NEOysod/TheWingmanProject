package wingman;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Wingman's own keys that work outside its pages (opening a page, the chat, the host's battle controls), each one
 * changeable in the settings' KEYS page in case another mod uses the same. Kept as text ("Ctrl+Shift+L") with the
 * other settings changed in game, under "key.<id>". Keys inside a page that is open (arrows, Enter, Esc...) are the
 * page's own: a page takes every key while it is up, so they can't clash.
 */
final class Keys {
    private Keys() {}

    static final class Bind {
        final String id, label, where, def;
        int code;
        boolean ctrl, shift, alt;
        Bind(String id, String label, String where, String def) { this.id = id; this.label = label; this.where = where; this.def = def; set(def); }

        boolean set(String text) {
            int[] k = parse(text);
            if (k == null) return false;
            code = k[0]; ctrl = k[1] != 0; shift = k[2] != 0; alt = k[3] != 0;
            return true;
        }

        String text() { return format(code, ctrl, shift, alt); }
    }

    // ---- key names (LWJGL key codes, as the game's events give them) ----

    private static final Map<Integer, String> NAMES = new LinkedHashMap<Integer, String>();
    static {
        String row1 = "1234567890";
        for (int i = 0; i < 10; i++) NAMES.put(2 + i, String.valueOf(row1.charAt(i)));
        String[] letters = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P" };
        for (int i = 0; i < letters.length; i++) NAMES.put(16 + i, letters[i]);
        letters = new String[] { "A", "S", "D", "F", "G", "H", "J", "K", "L" };
        for (int i = 0; i < letters.length; i++) NAMES.put(30 + i, letters[i]);
        letters = new String[] { "Z", "X", "C", "V", "B", "N", "M" };
        for (int i = 0; i < letters.length; i++) NAMES.put(44 + i, letters[i]);
        for (int i = 0; i < 10; i++) NAMES.put(59 + i, "F" + (i + 1));
        NAMES.put(87, "F11"); NAMES.put(88, "F12");
        NAMES.put(12, "Minus"); NAMES.put(13, "Equals"); NAMES.put(26, "["); NAMES.put(27, "]"); NAMES.put(39, ";");
        NAMES.put(40, "'"); NAMES.put(41, "`"); NAMES.put(43, "\\"); NAMES.put(51, ","); NAMES.put(52, "."); NAMES.put(53, "/");
        NAMES.put(28, "Enter"); NAMES.put(57, "Space"); NAMES.put(15, "Tab"); NAMES.put(14, "Backspace");
        NAMES.put(199, "Home"); NAMES.put(207, "End"); NAMES.put(201, "PgUp"); NAMES.put(209, "PgDn"); NAMES.put(210, "Insert");
        NAMES.put(211, "Delete"); NAMES.put(200, "Up"); NAMES.put(208, "Down"); NAMES.put(203, "Left"); NAMES.put(205, "Right");
        for (int i = 0; i < 10; i++) NAMES.put(new int[] { 82, 79, 80, 81, 75, 76, 77, 71, 72, 73 }[i], "Num" + i);
    }

    static final Map<String, Bind> ALL = new LinkedHashMap<String, Bind>();
    static {
        add("settings", "Settings page", "Campaign map, battle", "Ctrl+Shift+O");
        add("chat", "Chat: start typing", "Campaign map (host), lobby (joiner)", "Ctrl+Shift+C");
        add("coopPanel", "CO-OP panel and chat to tabs", "Campaign map (host); again: back as they were", "Ctrl+Shift+P");
        add("officerLinks", "Officer links page", "Campaign map (host)", "Ctrl+Shift+L");
        add("friendRefits", "Friend refits page", "Campaign map (host)", "Ctrl+Shift+R");
        add("wishLists", "Wish lists page", "Campaign map", "Ctrl+Shift+W");
        add("tags", "Hide / show player tags", "Battle", "F3");
        add("takeBack", "Take back the targeted ship", "Battle (host)", "Ctrl+Shift+T");
        add("kickTargeted", "Remove the player in the targeted ship", "Battle (host)", "Ctrl+Shift+K");
    }

    private static void add(String id, String label, String where, String def) { ALL.put(id, new Bind(id, label, where, def)); }

    /** The settings name of a key. */
    static String setting(String id) { return "key." + id; }

    static Bind get(String id) { return ALL.get(id); }

    /** The key as it reads ("Ctrl+Shift+L"), for hints. */
    static String name(String id) { Bind b = ALL.get(id); return b == null ? "?" : b.text(); }

    /** True if this event is that key going down, with exactly its Ctrl, Shift and Alt (read before it is consumed). */
    static boolean is(String id, InputEventAPI e) {
        Bind b = ALL.get(id);
        if (b == null || e == null || e.isConsumed() || !e.isKeyDownEvent()) return false;
        return e.getEventValue() == b.code && e.isCtrlDown() == b.ctrl && e.isShiftDown() == b.shift && e.isAltDown() == b.alt;
    }

    /** The same, from what was already read off an event. */
    static boolean is(String id, int key, boolean ctrl, boolean shift, boolean alt) {
        Bind b = ALL.get(id);
        return b != null && key == b.code && ctrl == b.ctrl && shift == b.shift && alt == b.alt;
    }

    /** Puts every key as the settings say (called whenever the settings are read). */
    static void apply(Map<String, String> values) {
        for (Bind b : ALL.values()) {
            String v = values.get(setting(b.id));
            if (v == null || !b.set(v)) b.set(b.def);
        }
    }

    /** Another key already on this combination ("" none). */
    static String clash(String id, String text) {
        int[] k = parse(text);
        if (k == null) return "";
        for (Bind b : ALL.values()) {
            if (b.id.equals(id)) continue;
            if (b.code == k[0] && b.ctrl == (k[1] != 0) && b.shift == (k[2] != 0) && b.alt == (k[3] != 0)) return b.label;
        }
        return "";
    }

    /** Keys that can't be bound: modifiers alone, Esc (every page's close). */
    static boolean bindable(int code) {
        return code > 1 && NAMES.containsKey(code);
    }

    static String format(int code, boolean ctrl, boolean shift, boolean alt) {
        String n = NAMES.get(code);
        if (n == null) n = "Key" + code;
        return (ctrl ? "Ctrl+" : "") + (shift ? "Shift+" : "") + (alt ? "Alt+" : "") + n;
    }

    /** "Ctrl+Shift+L" to {code, ctrl, shift, alt}; null if it doesn't read as a key. */
    static int[] parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\+");
        int ctrl = 0, shift = 0, alt = 0;
        String key = null;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty() && i == parts.length - 1) continue;
            if (i < parts.length - 1 && p.equalsIgnoreCase("ctrl")) ctrl = 1;
            else if (i < parts.length - 1 && p.equalsIgnoreCase("shift")) shift = 1;
            else if (i < parts.length - 1 && p.equalsIgnoreCase("alt")) alt = 1;
            else if (i == parts.length - 1) key = p;
            else return null;
        }
        if (key == null) return null;
        for (Map.Entry<Integer, String> e : NAMES.entrySet()) if (e.getValue().equalsIgnoreCase(key)) return new int[] { e.getKey(), ctrl, shift, alt };
        if (key.toLowerCase().startsWith("key")) {
            try { int c = Integer.parseInt(key.substring(3)); if (c > 1 && c < 256) return new int[] { c, ctrl, shift, alt }; } catch (NumberFormatException ex) { /* no */ }
        }
        return null;
    }

    static List<Bind> list() { return new ArrayList<Bind>(ALL.values()); }
}
