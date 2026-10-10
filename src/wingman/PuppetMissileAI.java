package wingman;

import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipCommand;

/**
 * Guidance for a missile on the joiner's screen: none. The host's copy of the missile does the steering and
 * its position, heading and turn rate are sent over; a local guidance system would only fight that, turning
 * the missile toward wherever it thinks the target is. The engine is kept burning so the flame still shows.
 */
public class PuppetMissileAI implements MissileAIPlugin {
    private final MissileAPI missile;

    public PuppetMissileAI(MissileAPI missile) { this.missile = missile; }

    public void advance(float amount) {
        if (!missile.isFizzling() && !missile.isFading()) missile.giveCommand(ShipCommand.ACCELERATE);
    }
}
