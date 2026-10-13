package wingman;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;

import wingman.net.HostSession;

/**
 * Campaign side of hosting: once the host is back on the campaign map, the battle is certainly over, however it
 * ended, so joiners are told to leave it; and the pre-battle lobby (CampaignLobby). Both are "transient": never
 * written into a save, so the mod can be added to or removed from a campaign freely.
 */
public class WingmanModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        // Which build this is, beyond the version: playtest zips of the same version differ.
        Global.getLogger(WingmanModPlugin.class).info("Wingman: build " + Build.ID);
        Global.getLogger(WingmanModPlugin.class).info(AgentGate.startupLine());
    }

    @Override
    public void onGameLoad(boolean newGame) {
        ObjectiveMirror.restore(); // a campaign's battles get every objective's own script
        try {
            Global.getSector().addTransientScript(new Watch());
            Global.getSector().addTransientScript(new CampaignLobby());
            CampaignSettings.install(); // Ctrl+Shift+O on the campaign map
        } catch (Throwable t) {
            Global.getLogger(WingmanModPlugin.class).warn("Wingman: could not watch the campaign for battles ending; "
                    + "joiners may have to leave a finished battle themselves", t);
        }
    }

    static final class Watch implements EveryFrameScript {
        private long lastLook;
        private boolean broken;

        public boolean isDone() { return false; }
        public boolean runWhilePaused() { return true; }

        public void advance(float amount) {
            if (broken) return;
            long now = System.currentTimeMillis();
            if (now - lastLook < 1000) return;
            lastLook = now;
            try {
                if (Global.getCurrentState() == GameState.CAMPAIGN) HostSession.endBattleIfOpen();
            } catch (Throwable t) {
                broken = true;
                Global.getLogger(WingmanModPlugin.class).warn("Wingman: campaign watch stopped", t);
            }
        }
    }
}
