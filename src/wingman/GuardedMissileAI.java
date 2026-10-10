package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipCommand;

/**
 * Runs a mod's own missile script on a joiner's copy of a missile, so that whatever the script shows (a flash
 * when a stage separates, a sound, a trail of flares) is shown here as the mod's author made it. The host
 * still decides where the missile is and what it is aimed at; the script only supplies the show.
 *
 * A script written for a normal battle may trip over something in this puppet one. Any error it throws is
 * caught here: the script is switched off for that one missile, which then simply follows the host.
 */
public class GuardedMissileAI implements MissileAIPlugin, GuidedMissileAI {
    private final MissileAPI missile;
    private final MissileAIPlugin inner;
    private boolean broken;
    private static int reported;

    public GuardedMissileAI(MissileAPI missile, MissileAIPlugin inner) {
        this.missile = missile;
        this.inner = inner;
    }

    public void advance(float amount) {
        if (!broken) {
            try {
                inner.advance(amount);
                return;
            } catch (Throwable t) {
                broken = true;
                try { Compat.get().odd("script:" + inner.getClass().getName()); } catch (Throwable ignored) { /* notes are optional */ }
                if (reported++ < 5) {
                    Global.getLogger(GuardedMissileAI.class).warn("Wingman: missile script " + inner.getClass().getName()
                            + " failed on this side and was switched off for one missile", t);
                }
            }
        }
        if (!missile.isFizzling() && !missile.isFading()) missile.giveCommand(ShipCommand.ACCELERATE);
    }

    public CombatEntityAPI getTarget() {
        try {
            return inner instanceof GuidedMissileAI ? ((GuidedMissileAI) inner).getTarget() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public void setTarget(CombatEntityAPI target) {
        try {
            if (inner instanceof GuidedMissileAI) ((GuidedMissileAI) inner).setTarget(target);
        } catch (Throwable t) { /* the host's position updates still apply */ }
    }
}
