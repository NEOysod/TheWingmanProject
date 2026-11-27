package testmod;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;

/** Mods' code drawing effects: in a damage listener, in a ship AI, and outside either. */
public class Effects {
    /** A damage listener that draws a flash and an arc when its ship is hit. */
    public static class OnDamage implements DamageListener {
        public int heard;
        public void reportDamageApplied(Object source, CombatEntityAPI target, ApplyDamageResultAPI result) {
            heard++;
            Global.getCombatEngine().addHitParticle(new Vector2f(10f, 20f), new Vector2f(), 40f, 1f, 0.2f, Color.RED);
            Global.getCombatEngine().spawnEmpArcVisual(new Vector2f(0f, 0f), null, new Vector2f(100f, 0f), null, 10f, Color.BLUE, Color.WHITE);
        }
    }

    /** A ship AI of a mod's own that draws an explosion as it thinks. */
    public static class Ai implements ShipAIPlugin {
        public int thought;
        private final ShipwideAIFlags flags = new ShipwideAIFlags();
        public void setDoNotFireDelay(float amount) {}
        public void forceCircumstanceEvaluation() {}
        public void advance(float amount) {
            thought++;
            Global.getCombatEngine().spawnExplosion(new Vector2f(5f, 5f), new Vector2f(), Color.ORANGE, 80f, 0.5f);
        }
        public boolean needsRefit() { return false; }
        public ShipwideAIFlags getAIFlags() { return flags; }
        public void cancelCurrentManeuver() {}
        public ShipAIConfig getConfig() { return null; }
    }

    /** Something with advance(float) that is no ship AI: not a frame. */
    public static class NotAi {
        public void advance(float amount) {
            Global.getCombatEngine().addSmoothParticle(new Vector2f(), new Vector2f(), 10f, 1f, 0.1f, Color.GREEN);
        }
    }

    /** Effects drawn outside any such frame. */
    public static void plain() {
        Global.getCombatEngine().addHitParticle(new Vector2f(), new Vector2f(), 10f, 1f, 0.1f, Color.GREEN);
    }
}
