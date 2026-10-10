package wingman;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;

import wingman.net.HostSession;

/**
 * Campaign side of hosting, and all there is of it: once the host is back on the campaign map, the battle is
 * certainly over, however it ended, so joiners are told to leave it. The watcher is "transient": it is never
 * written into a save, so the mod can be added to or removed from a campaign freely.
 */
public class WingmanModPlugin extends BaseModPlugin {
    @Override
    public void onGameLoad(boolean newGame) {
        try {
            Global.getSector().addTransientScript(new Watch());
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
