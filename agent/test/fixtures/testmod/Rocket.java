package testmod;

import java.util.HashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

/** An on-hit script that looks its strength up by the launcher's name, as UAF's Swaras rockets do. */
public class Rocket implements OnHitEffectPlugin {
    static final Map<String, Float> strength = new HashMap<String, Float>();
    static { strength.put("rocket_pod", 10f); }
    public float dealt;
    public String launcher = "rocket_pod";

    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
            ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        dealt += strength.get(launcher).floatValue(); // a launcher of another name: NullPointerException
    }
}
