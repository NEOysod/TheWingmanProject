package wingman;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;

/**
 * How Wingman's panels were last left on this PC (minimized or not), kept in saves/common so they stay that way
 * from one game to the next. Not settings: nothing here changes what the mod does, only what is in view.
 */
final class UiState {
    private UiState() {}

    private static final String FILE = "wingman_ui_state.txt";
    private static Map<String, String> values;

    private static Map<String, String> values() {
        if (values != null) return values;
        values = new LinkedHashMap<String, String>();
        try {
            if (Global.getSettings().fileExistsInCommon(FILE)) {
                String text = Global.getSettings().readTextFileFromCommon(FILE);
                if (text != null) for (String line : text.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && eq < 64) values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (Throwable t) { /* as designed: open */ }
        return values;
    }

    static boolean get(String key, boolean otherwise) {
        String v = values().get(key);
        return v == null ? otherwise : "true".equals(v);
    }

    static void set(String key, boolean value) {
        Map<String, String> m = values();
        if (String.valueOf(value).equals(m.get(key))) return;
        m.put(key, String.valueOf(value));
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
        } catch (Throwable t) { /* kept until the game closes */ }
    }
}
