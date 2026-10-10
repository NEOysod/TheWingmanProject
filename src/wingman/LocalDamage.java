package wingman;

import java.util.IdentityHashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

/**
 * Joiner side: keeps a running note of how much damage this game has itself aimed at each ship lately (none of it
 * real; see makeInvulnerable). When the host reports what a ship actually took, the two are compared, and
 * what the host saw but this game did not is played out here, so that nothing lands on a ship unseen and
 * scripts that react to a ship being hit get to react.
 */
final class LocalDamage implements DamageTakenModifier {
    /** Per ship: { this stretch, the stretch before }. */
    private final Map<ShipAPI, float[]> seen = new IdentityHashMap<ShipAPI, float[]>();
    /** Set while the mod itself is playing out a hit, so that hit is not counted as this game's own. */
    boolean replaying;
    private float sinceRoll;

    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
        if (replaying || !(target instanceof ShipAPI) || damage == null) return null;
        try {
            float amount = damage.getDamage();
            if (damage.isDps()) amount *= damage.getDpsDuration();
            if (!(amount > 0f)) return null;
            float[] s = seen.get(target);
            if (s == null) {
                if (seen.size() > 3000) seen.clear();
                s = new float[2];
                seen.put((ShipAPI) target, s);
            }
            s[0] += amount;
        } catch (Throwable t) { /* this hit goes uncounted */ }
        return null;
    }

    /** Damage this game aimed at the ship over roughly the last half second. */
    float lately(ShipAPI ship) {
        float[] s = seen.get(ship);
        return s == null ? 0f : s[0] + s[1];
    }

    void advance(float amount) {
        sinceRoll += amount;
        if (sinceRoll < 0.3f) return;
        sinceRoll = 0f;
        for (float[] s : seen.values()) { s[1] = s[0]; s[0] = 0f; }
    }

    void clear() { seen.clear(); }
}
