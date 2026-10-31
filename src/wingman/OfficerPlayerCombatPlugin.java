package wingman;

import java.util.List;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.net.HostSession;

/** Observes host-side control only; does not change captains or combat stats. */
public final class OfficerPlayerCombatPlugin extends BaseEveryFrameCombatPlugin {
    private CombatEngineAPI engine;
    private boolean broken;

    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        broken = false;
    }

    public void advance(float amount, List<InputEventAPI> events) {
        if (broken || engine == null || Global.getCurrentState() != GameState.COMBAT) return;
        if (!engine.isInCampaign() || engine.isSimulation() || engine.isInCampaignSim()) return;
        HostSession session = HostSession.active();
        if (session == null || Global.getSector() == null) return;
        try {
            OfficerPlayerBinding.observe(Global.getSector().getPlayerFleet(), session.getPlayers(), engine);
        } catch (RuntimeException ex) {
            broken = true;
            Global.getLogger(OfficerPlayerCombatPlugin.class).error("Wingman: officer binding stopped for this battle", ex);
        }
    }
}
