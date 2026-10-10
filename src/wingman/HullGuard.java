package wingman;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.HullDamageAboutToBeTakenListener;

/**
 * Sits on every ship on the joiner's screen and turns away all hull damage. Nothing on this machine may
 * hurt a ship: its hull is whatever the host says it is, and it dies only when the host says it died,
 * at which point the guard is told to stand aside.
 */
public class HullGuard implements HullDamageAboutToBeTakenListener {
    /** Set just before the host-ordered destruction is carried out. */
    public boolean standAside;

    public boolean notifyAboutToTakeHullDamage(Object param, ShipAPI ship, Vector2f point, float damageAmount) {
        return !standAside;
    }
}
