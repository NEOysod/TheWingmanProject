package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;

final class Ui {
    private Ui() {}

    /** Show a line in the combat message feed (and the log). Never throws. */
    static void say(CombatEngineAPI engine, String text) {
        try {
            Global.getLogger(Ui.class).info(text);
            if (engine != null && engine.getCombatUI() != null) {
                MessageSpy.ownLine = true; // Wingman's own notice: never sent to others or held back
                try {
                    engine.getCombatUI().addMessage(0, text);
                } finally {
                    MessageSpy.ownLine = false;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
