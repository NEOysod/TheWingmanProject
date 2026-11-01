package wingman.client;

import com.fs.starfarer.api.Global;

import wingman.net.Aftermath;
import wingman.net.BattleReport;

/** The last after-action report and aftermath heard from the host (joiner side), for the lobby screen. */
public final class AfterAction {
    private AfterAction() {}

    static volatile BattleReport report;
    static volatile Aftermath aftermath;
    /** Set when a new report arrives; the lobby opens on it once. */
    public static volatile boolean unseen;

    public static void onReport(byte[] data) {
        try {
            report = BattleReport.decode(data);
            aftermath = null; // the next aftermath belongs to this battle
            unseen = true;
        } catch (Throwable t) {
            Global.getLogger(AfterAction.class).warn("Wingman: the host's after-action report could not be read", t);
        }
    }

    public static void onAftermath(byte[] data) {
        try {
            aftermath = Aftermath.decode(data); // shown on the report page; doesn't open the lobby by itself
        } catch (Throwable t) {
            Global.getLogger(AfterAction.class).warn("Wingman: the host's aftermath could not be read", t);
        }
    }
}
