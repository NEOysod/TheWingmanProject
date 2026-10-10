package wingman;

import com.fs.starfarer.api.Global;

/**
 * Diagnostic logging, off unless "debugLog" is true in wingman_settings.json. The detailed lines (traffic meter,
 * targeting, fire control, fighter and missile counts) were what got the mod working and are what to send
 * along with a bug report; in normal play they are only noise in starsector.log. Warnings and errors are
 * always written.
 */
final class Dbg {
    static volatile boolean on;

    private Dbg() {}

    static void info(Class<?> where, String text) {
        if (on) Global.getLogger(where).info(text);
    }
}
