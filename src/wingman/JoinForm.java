package wingman;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.input.InputEventAPI;

/**
 * The two text boxes of the join screen (host address, player name) and the typing that goes into them.
 * Used on the title screen's Co-op panel and on the join screen inside the join mission.
 */
final class JoinForm {
    static final String FOOTER = "Enter: connect     Tab: next field     Ctrl+V: paste     Backspace: delete";

    final StringBuilder address = new StringBuilder(), name = new StringBuilder(), password = new StringBuilder();
    int field;
    String status = "";
    Color statusColor = Panel.DIM;
    private boolean loaded;

    /** Fill in what was typed last time. */
    void load(String defaultName) {
        if (loaded) return;
        loaded = true;
        SavedJoin saved = SavedJoin.load();
        address.setLength(0);
        name.setLength(0);
        address.append(saved.address);
        name.append(saved.name.isEmpty() ? defaultName : saved.name);
        password.setLength(0);
        password.append(saved.password);
        field = 0;
    }

    String name() {
        String n = name.toString().trim();
        return n.isEmpty() ? "Player" : n;
    }

    boolean valid() {
        if (address.toString().trim().isEmpty()) {
            status = "Type the host's address first.";
            statusColor = Panel.WARN;
            field = 0;
            return false;
        }
        return true;
    }

    void save() {
        SavedJoin saved = new SavedJoin();
        saved.address = address.toString().trim();
        saved.name = name();
        saved.password = password.toString().trim();
        saved.save();
    }

    void lines(List<String> lines, List<Color> colors, boolean busy) {
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        lines.add("Host address:   " + address + (field == 0 && blink && !busy ? "_" : ""));
        colors.add(field == 0 ? Panel.TEXT : Panel.DIM);
        lines.add("Your name:       " + name + (field == 1 && blink && !busy ? "_" : ""));
        colors.add(field == 1 ? Panel.TEXT : Panel.DIM);
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < password.length(); i++) dots.append('*');
        lines.add("Password:          " + (password.length() == 0 && field != 2 ? "(only if the host set one)" : dots.toString())
                + (field == 2 && blink && !busy ? "_" : ""));
        colors.add(field == 2 ? Panel.TEXT : Panel.DIM);
        lines.add(busy ? "Connecting to " + address + " ..." : status.isEmpty()
                ? "Type the address your host reads out (for Radmin it starts with 26). Add :port only if the host changed it." : status);
        colors.add(busy ? Panel.GOOD : status.isEmpty() ? Panel.DIM : statusColor);
    }

    /**
     * Typing. Every key press is taken while the form is open, so letters don't also steer a ship or pick a
     * menu entry. Escape is left alone unless takeEscape is set. Returns true when Enter was pressed.
     */
    boolean keys(List<InputEventAPI> events, boolean busy, boolean takeEscape) {
        boolean submit = false;
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyboardEvent()) continue;
            int key = e.getEventValue();
            if (key == 1 && !takeEscape) continue; // Escape
            if (!e.isKeyDownEvent()) { e.consume(); continue; }
            e.consume();
            if (busy || key == 1) continue;
            StringBuilder box = field == 0 ? address : field == 1 ? name : password;
            int max = field == 0 ? 64 : field == 1 ? 20 : 32;
            if (Panel.isEnter(key)) {
                submit = true;
            } else if (key == 15 || key == 208) { // Tab, down
                field = (field + 1) % 3;
            } else if (key == 200) { // up
                field = (field + 2) % 3;
            } else if (key == 14) { // Backspace
                if (box.length() > 0) box.setLength(box.length() - 1);
            } else if (key == 211) { // Delete: clear the box
                box.setLength(0);
            } else if (key == 47 && e.isCtrlDown()) { // Ctrl+V
                String clip = null;
                try { clip = org.lwjgl.Sys.getClipboard(); } catch (Throwable t) { /* no clipboard access: type it */ }
                for (int i = 0; clip != null && i < clip.length() && box.length() < max; i++) {
                    if (allowed(clip.charAt(i), field)) box.append(clip.charAt(i));
                }
            } else {
                char c = e.getEventChar();
                if (allowed(c, field) && box.length() < max) box.append(c);
            }
        }
        return submit;
    }

    private static boolean allowed(char c, int field) {
        if (field == 0) return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == ':' || c == '-';
        return c >= ' ' && c < 127;
    }
}
