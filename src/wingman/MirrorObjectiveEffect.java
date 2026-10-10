package wingman;

import java.util.List;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.BattleObjectiveEffect;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The script a held-back objective runs on a joiner's PC instead of its own (see ObjectiveMirror): nothing.
 * The objective keeps its real name, icon, sprite and capture time, since those come from its kind; what it
 * does happens in the host's game, and its tooltip text is the host's, sent over as it changes.
 */
public class MirrorObjectiveEffect implements BattleObjectiveEffect {
    private volatile String text;
    private BattleObjectiveAPI objective;

    public MirrorObjectiveEffect() {}

    public void init(CombatEngineAPI engine, BattleObjectiveAPI objective) { this.objective = objective; }

    public void advance(float amount) { /* runs in the host's game */ }

    public List<ShipStatusItem> getStatusItemsFor(ShipAPI ship) { return null; }

    public String getLongDescription() {
        String t = text;
        return t != null ? t : "- what this objective does happens in the host's game\n";
    }

    public int getBonusDeploymentPoints() { return 0; }

    /** The host's tooltip text for this objective; null or empty for none. */
    void setText(String t) { text = t == null || t.isEmpty() ? null : t; }

    BattleObjectiveAPI objective() { return objective; }
}
