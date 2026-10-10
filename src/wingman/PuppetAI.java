package wingman;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/** An AI that does nothing. Used on the joining player's machine, where ships only mirror the host. */
public class PuppetAI implements ShipAIPlugin {
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();

    public void setDoNotFireDelay(float amount) {}
    public void forceCircumstanceEvaluation() {}
    public void advance(float amount) {}
    public boolean needsRefit() { return false; }
    public ShipwideAIFlags getAIFlags() { return flags; }
    public void cancelCurrentManeuver() {}
    public ShipAIConfig getConfig() { return config; }
}
