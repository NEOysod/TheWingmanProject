package wingman;

import com.fs.starfarer.api.Global;

/**
 * The host address and player name last typed into the join screen. Kept in the game's own "common" save
 * folder (saves/common), the one place a mod is allowed to write, so nobody has to edit a file by hand.
 */
final class SavedJoin {
    private static final String FILE = "wingman_join";

    String address = "";
    String name = "";
    String password = "";

    static SavedJoin load() {
        SavedJoin s = new SavedJoin();
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return s;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            if (text == null) return s;
            String[] lines = text.split("\n");
            if (lines.length > 0) s.address = clean(lines[0], 64);
            if (lines.length > 1) s.name = clean(lines[1], 20);
            if (lines.length > 2) s.password = clean(lines[2], 32);
        } catch (Throwable t) {
            Global.getLogger(SavedJoin.class).warn("Wingman: could not read the saved host address", t);
        }
        return s;
    }

    void save() {
        try {
            Global.getSettings().writeTextFileToCommon(FILE, clean(address, 64) + "\n" + clean(name, 20) + "\n" + clean(password, 32) + "\n");
        } catch (Throwable t) {
            Global.getLogger(SavedJoin.class).warn("Wingman: could not save the host address; it will have to be typed again next time", t);
        }
    }

    private static String clean(String s, int max) {
        s = s == null ? "" : s.replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** The host part of "host" or "host:port". */
    static String hostOf(String address) {
        int i = address.lastIndexOf(':');
        return (i > 0 ? address.substring(0, i) : address).trim();
    }

    /** The port part of "host:port", or the fallback if none (or nonsense) was given. */
    static int portOf(String address, int fallback) {
        int i = address.lastIndexOf(':');
        if (i <= 0) return fallback;
        try {
            int p = Integer.parseInt(address.substring(i + 1).trim());
            return p >= 1 && p <= 65535 ? p : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
