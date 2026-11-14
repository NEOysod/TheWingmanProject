package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;
import wingman.net.InputState;

/**
 * Host: a joiner raises their shield the moment they see a shot coming, but that reaches the host a round trip
 * later, so a shot their shield stopped on their screen can hit their hull here first. Such a hit is given back one
 * round trip later (hull and armor restored, the flux their shield would have taken added instead) if their shield
 * was up then, facing the hit. The same for a phase cloak. Host setting shieldGrace.
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
final class ShieldGrace implements DamageTakenModifier, DamageListener {
   static volatile boolean enabled = true;
   static int givenBack;
   static int judged;
   private static final String KEY = "wingman_shield_grace";
   private static final float SLACK = 0.05F;
   private static final float ARC_SLACK = 10.0F;
   private final ShipAPI ship;
   private RemotePilotAI pilot;
   private InputState in;
   private boolean defenseSeen;
   private float defenseSince = -1.0F;
   private final List<ShieldGrace.Hit> hits = new ArrayList<>();
   private ShieldGrace.Hit pending;
   private float hullBefore;

   private ShieldGrace(ShipAPI var1) {
      this.ship = var1;
   }

   static ShieldGrace of(ShipAPI var0) {
      Object var1 = var0.getCustomData().get("wingman_shield_grace");
      if (var1 instanceof ShieldGrace) {
         return (ShieldGrace)var1;
      } else {
         ShieldGrace var2 = new ShieldGrace(var0);
         var0.setCustomData("wingman_shield_grace", var2);
         var0.addListener(var2);
         return var2;
      }
   }

   private boolean active() {
      return enabled && this.pilot != null && !this.pilot.failed && now() - this.pilot.lastFlown < 0.25F && this.ship.isAlive() && !this.ship.isHulk();
   }

   private static float now() {
      return Global.getCombatEngine().getTotalElapsedTime(false);
   }

   public String modifyDamageTaken(Object var1, CombatEntityAPI var2, DamageAPI var3, Vector2f var4, boolean var5) {
      this.pending = null;
      if (var2 != this.ship || var5 || var4 == null || !this.active()) {
         return null;
      } else if (!(var1 instanceof DamagingProjectileAPI) && !(var1 instanceof BeamAPI)) {
         return null;
      } else {
         try {
            ShieldGrace.Hit var6 = new ShieldGrace.Hit();
            var6.time = now();
            Vector2f var7 = this.ship.getLocation();
            var6.angle = (float)Math.toDegrees(Math.atan2((double)(var4.y - var7.y), (double)(var4.x - var7.x)));
            FluxTrackerAPI var8 = this.ship.getFluxTracker();
            var6.eligible = !var8.isOverloaded() && !var8.isVenting();
            ShipSystemAPI var9 = this.ship.getPhaseCloak();
            if (this.ship.getShield() == null && var9 != null && var9.isCoolingDown()) {
               var6.eligible = false;
            }

            float var10 = var3.getDamage();

            try {
               var10 *= var3.getModifier().getModifiedValue();
            } catch (Throwable var17) {
            }

            if (var3.isDps()) {
               var10 *= var3.getDpsDuration();
            }

            ShieldAPI var11 = this.ship.getShield();
            if (var11 != null && var10 > 0.0F) {
               var6.fluxCost = var10
                  * var3.getType().getShieldMult()
                  * var11.getFluxPerPointOfDamage()
                  * this.ship.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
               var6.softFlux = var1 instanceof BeamAPI && !var3.isForceHardFlux();
            }

            ArmorGridAPI var12 = this.ship.getArmorGrid();
            int[] var13 = var12.getCellAtLocation(var4);
            if (var13 != null) {
               float[][] var14 = var12.getGrid();
               var6.x0 = Math.max(0, var13[0] - 2);
               var6.y0 = Math.max(0, var13[1] - 2);
               var6.w = Math.min(var14.length, var13[0] + 3) - var6.x0;
               var6.h = var14.length == 0 ? 0 : Math.min(var14[0].length, var13[1] + 3) - var6.y0;
               if (var6.w > 0 && var6.h > 0) {
                  var6.armorBefore = new float[var6.w * var6.h];

                  for (int var15 = 0; var15 < var6.w; var15++) {
                     for (int var16 = 0; var16 < var6.h; var16++) {
                        var6.armorBefore[var15 * var6.h + var16] = var14[var6.x0 + var15][var6.y0 + var16];
                     }
                  }
               }
            }

            this.hullBefore = this.ship.getHitpoints();
            this.pending = var6;
         } catch (Throwable var18) {
            this.pending = null;
         }

         return null;
      }
   }

   public void reportDamageApplied(Object var1, CombatEntityAPI var2, ApplyDamageResultAPI var3) {
      ShieldGrace.Hit var4 = this.pending;
      this.pending = null;
      if (var4 != null && var2 == this.ship) {
         try {
            var4.hull = Math.max(0.0F, this.hullBefore - this.ship.getHitpoints());
            if (var4.armorBefore != null) {
               float[][] var5 = this.ship.getArmorGrid().getGrid();
               var4.armorTaken = new float[var4.armorBefore.length];

               for (int var6 = 0; var6 < var4.w; var6++) {
                  for (int var7 = 0; var7 < var4.h; var7++) {
                     var4.armorTaken[var6 * var4.h + var7] = Math.max(0.0F, var4.armorBefore[var6 * var4.h + var7] - var5[var4.x0 + var6][var4.y0 + var7]);
                  }
               }

               var4.armorBefore = null;
            }

            boolean var11 = var4.hull > 0.0F;
            if (!var11 && var4.armorTaken != null) {
               for (float var9 : var4.armorTaken) {
                  if (var9 > 0.0F) {
                     var11 = true;
                     break;
                  }
               }
            }

            if (!var11) {
               return;
            }

            if (this.hits.size() >= 200) {
               this.hits.remove(0);
            }

            this.hits.add(var4);
         } catch (Throwable var10) {
         }
      }
   }

   void frame(RemotePilotAI var1, InputState var2) {
      this.pilot = var1;
      this.in = var2;
      float var3 = now();
      boolean var4 = var2.has(256);
      if (var4 && !this.defenseSeen) {
         this.defenseSince = var3;
      }

      this.defenseSeen = var4;
      if (!this.hits.isEmpty()) {
         if (!this.active()) {
            this.hits.clear();
         } else {
            boolean var5 = System.currentTimeMillis() - var2.receivedAt < 250L;
            float var6 = Math.min(1.02F, (float)var2.rttMs / 1000.0F);

            while (!this.hits.isEmpty()) {
               ShieldGrace.Hit var7 = this.hits.get(0);
               float var8 = var7.time + var6;
               if (var3 < var8 + 0.05F) {
                  break;
               }

               this.hits.remove(0);
               judged++;
               if (var5 && var7.eligible && var2.rttMs > 0 && !(var3 > var8 + 0.5F)) {
                  this.judge(var7, var8 + 0.05F);
               }
            }
         }
      }
   }

   private void judge(ShieldGrace.Hit var1, float var2) {
      if (this.defenseSeen && !(this.defenseSince < 0.0F) && !(this.defenseSince > var2)) {
         float var3 = var2 - this.defenseSince;
         ShieldAPI var4 = this.ship.getShield();
         if (var4 != null) {
            float var5 = var4.getUnfoldTime();
            float var6 = var4.getArc() * (var5 <= 0.0F ? 1.0F : Math.min(1.0F, var3 / var5));
            float var7 = var6 / 2.0F + 10.0F;
            boolean var8;
            if (var4.getType() == ShieldType.FRONT) {
               var8 = within(var1.angle, this.in.facing, var7);
            } else {
               Vector2f var9 = this.ship.getLocation();
               float var10 = (float)Math.toDegrees(Math.atan2((double)(this.in.mouseY - var9.y), (double)(this.in.mouseX - var9.x)));
               var8 = within(var1.angle, var10, var7) || var4.isOn() && within(var1.angle, var4.getFacing(), var7);
            }

            if (var8) {
               this.giveBack(var1, var1.fluxCost);
            }
         } else if (this.ship.getPhaseCloak() != null && !(var3 < 0.05F)) {
            this.giveBack(var1, 0.0F);
         }
      }
   }

   private static boolean within(float var0, float var1, float var2) {
      float var3 = ((var0 - var1) % 360.0F + 540.0F) % 360.0F - 180.0F;
      return Math.abs(var3) <= var2;
   }

   private void giveBack(ShieldGrace.Hit var1, float var2) {
      if (this.ship.isAlive() && !this.ship.isHulk() && !(this.ship.getHitpoints() <= 0.0F)) {
         float var3 = 1.0F;
         FluxTrackerAPI var4 = this.ship.getFluxTracker();
         if (var2 > 0.0F) {
            float var5 = var4.getMaxFlux() - var4.getCurrFlux() - 1.0F;
            if (var5 <= 0.0F) {
               return;
            }

            if (var2 > var5) {
               var3 = var5 / var2;
            }
         }

         this.ship.setHitpoints(Math.min(this.ship.getMaxHitpoints(), this.ship.getHitpoints() + var1.hull * var3));
         if (var1.armorTaken != null) {
            ArmorGridAPI var11 = this.ship.getArmorGrid();
            float[][] var6 = var11.getGrid();
            float var7 = var11.getMaxArmorInCell();

            for (int var8 = 0; var8 < var1.w; var8++) {
               for (int var9 = 0; var9 < var1.h; var9++) {
                  float var10 = var1.armorTaken[var8 * var1.h + var9] * var3;
                  if (var10 > 0.0F) {
                     var11.setArmorValue(var1.x0 + var8, var1.y0 + var9, Math.min(var7, var6[var1.x0 + var8][var1.y0 + var9] + var10));
                  }
               }
            }

            this.ship.syncWithArmorGridState();
         }

         if (var2 > 0.0F) {
            var4.increaseFlux(var2 * var3, !var1.softFlux);
         }

         givenBack++;
      }
   }

   private static final class Hit {
      float time;
      float angle;
      float hull;
      float fluxCost;
      boolean softFlux;
      boolean eligible;
      int x0;
      int y0;
      int w;
      int h;
      float[] armorBefore;
      float[] armorTaken;

      private Hit() {
      }
   }
}
