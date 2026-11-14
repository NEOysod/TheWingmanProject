package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.BattleObjectiveEffect;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.combat.entities.BattleObjective;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.lwjgl.util.vector.Vector2f;
import wingman.api.Pvp;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.OrderRequest;
import wingman.net.Orders;

/** Host side of the command screen: reads the objectives and the host's fleet orders and sends them out.
 *
 * (Its PvP version recovered from the compiled 0.9.3-beta pvp-test1 build of 28 Oct 2026, whose source was lost:
 * the code is exactly that build's; its comments and local names were not kept.)
 */
final class HostOrders {
   private List<BattleObjectiveAPI> objectives;
   private byte[] lastSent;
   private byte[] lastSent1;
   private long lastSentAt;
   private long lastSentAt1;
   private long lastBuiltAt;
   private boolean broken;
   private String[] lastTexts;
   private long lastTextsAt;
   private boolean textsBroken;

   byte[] objectivesMessage(CombatEngineAPI var1) {
      if (this.broken) {
         return null;
      } else {
         try {
            if (this.objectives == null) {
               this.objectives = new ArrayList<>(var1.getObjectives());
            }

            Msg.W var2 = new Msg.W();
            var2.out.writeByte(Math.min(this.objectives.size(), 255));

            for (int var3 = 0; var3 < this.objectives.size() && var3 < 255; var3++) {
               BattleObjectiveAPI var4 = this.objectives.get(var3);
               var2.out.writeUTF(var4.getType());
               var2.out.writeFloat(var4.getLocation().x);
               var2.out.writeFloat(var4.getLocation().y);
            }

            this.lastSent = null;
            this.lastTexts = null;
            return var2.done();
         } catch (Throwable var5) {
            this.fail(var5);
            return null;
         }
      }
   }

   void tick(CombatEngineAPI var1, HostSession var2, Map<ShipAPI, Integer> var3) {
      if (!this.broken && this.objectives != null && !var2.getPlayers().isEmpty()) {
         long var4 = System.currentTimeMillis();
         if (var4 - this.lastBuiltAt >= 300L) {
            this.lastBuiltAt = var4;

            try {
               this.sendTexts(var2);
               if (Pvp.active()) {
                  byte[] var14 = this.build(var1, var3, 0).encode();
                  byte[] var7 = this.build(var1, var3, 1).encode();
                  boolean var8 = !Arrays.equals(var14, this.lastSent) || var4 - this.lastSentAt >= 3000L;
                  boolean var9 = !Arrays.equals(var7, this.lastSent1) || var4 - this.lastSentAt1 >= 3000L;
                  if (var8) {
                     this.lastSent = var14;
                     this.lastSentAt = var4;
                  }

                  if (var9) {
                     this.lastSent1 = var7;
                     this.lastSentAt1 = var4;
                  }

                  for (HostSession.Player var11 : var2.getPlayers()) {
                     int var12 = Pvp.sideOf(var11.name);
                     if (var12 == 1 ? var9 : var8) {
                        var11.conn.send((byte)25, var12 == 1 ? var7 : var14);
                     }
                  }

                  return;
               }

               byte[] var6 = this.build(var1, var3, 0).encode();
               if (Arrays.equals(var6, this.lastSent) && var4 - this.lastSentAt < 3000L) {
                  return;
               }

               this.lastSent = var6;
               this.lastSentAt = var4;
               var2.broadcast((byte)25, var6);
            } catch (Throwable var13) {
               this.fail(var13);
            }
         }
      }
   }

   private Orders build(CombatEngineAPI var1, Map<ShipAPI, Integer> var2, int var3) {
      Orders var4 = new Orders();
      var4.objectiveOwners = new int[Math.min(this.objectives.size(), 255)];

      for (int var5 = 0; var5 < var4.objectiveOwners.length; var5++) {
         BattleObjectiveAPI var6 = this.objectives.get(var5);
         var4.objectiveOwners[var5] = var6.getOwner() & 127 | (var1.isEntityInPlay(var6) ? 0 : 128);
      }

      CombatTaskManagerAPI var14 = var1.getFleetManager(var3).getTaskManager(false);
      var4.fullAssault = var14.isFullAssault();
      var4.fullRetreat = var14.isInFullRetreat();
      Iterator var15 = var14.getAllAssignments().iterator();

      while (true) {
         AssignmentInfo var7;
         Orders.Assignment var9;
         while (true) {
            if (!var15.hasNext()) {
               CombatFleetManagerAPI var16 = var1.getFleetManager(var3);

               for (Entry var18 : var2.entrySet()) {
                  ShipAPI var19 = (ShipAPI)var18.getKey();
                  if (var19.getOwner() == var3 && !var19.isFighter()) {
                     AssignmentInfo var21 = var14.getAssignmentFor(var19);
                     if (var21 != null && var21.getTarget() == null && var21.getType() != null) {
                        Orders.ShipOrder var22 = new Orders.ShipOrder();
                        var22.ship = (Integer)var18.getValue();
                        var22.type = var21.getType().name();
                        DeployedFleetMemberAPI var23 = var16.getDeployedFleetMember(var19);
                        var22.direct = var23 != null && var23.isDirectRetreat();
                        var4.shipOrders.add(var22);
                     }
                  }
               }

               Collections.sort(var4.shipOrders, new Comparator<Orders.ShipOrder>() {
                  public int compare(Orders.ShipOrder var1, Orders.ShipOrder var2) {
                     return var1.ship < var2.ship ? -1 : (var1.ship > var2.ship ? 1 : 0);
                  }
               });
               return var4;
            }

            var7 = (AssignmentInfo)var15.next();
            AssignmentTargetAPI var8 = var7.getTarget();
            if (var8 != null && var7.getType() != null) {
               var9 = new Orders.Assignment();
               var9.type = var7.getType().name();
               if (var8 instanceof BattleObjectiveAPI) {
                  var9.targetKind = 0;
                  var9.targetId = this.objectives.indexOf(var8);
                  if (var9.targetId < 0) {
                     continue;
                  }
               } else {
                  if (var8 instanceof DeployedFleetMemberAPI) {
                     Integer var10 = (Integer)var2.get(((DeployedFleetMemberAPI)var8).getShip());
                     if (var10 == null) {
                        continue;
                     }

                     var9.targetKind = 1;
                     var9.targetId = var10;
                     break;
                  }

                  var9.targetKind = 2;
                  var9.x = var8.getLocation().x;
                  var9.y = var8.getLocation().y;
               }
               break;
            }
         }

         List<DeployedFleetMemberAPI> var20 = var7.getAssignedMembers();
         if (var20 != null) {
            for (DeployedFleetMemberAPI var12 : var20) {
               Integer var13 = (Integer)var2.get(var12.getShip());
               if (var13 != null) {
                  var9.members.add(var13);
               }
            }
         }

         var4.assignments.add(var9);
      }
   }

   void apply(CombatEngineAPI var1, int var2, OrderRequest var3, Map<Integer, ShipAPI> var4) {
      if (!this.broken && this.objectives != null) {
         CombatFleetManagerAPI var5 = var1.getFleetManager(var2);
         CombatTaskManagerAPI var6 = var5.getTaskManager(false);
         this.lastSent = null;
         this.lastSent1 = null;
         this.lastBuiltAt = 0L;
         if (var3.kind == 5) {
            var6.setFullAssault(var3.on);
         } else if (var3.kind == 4) {
            DeployedFleetMemberAPI var13 = member(var5, var2, (ShipAPI)var4.get(var3.ship));
            if (var13 != null) {
               if ("SEARCH_AND_DESTROY".equals(var3.shipOrder) || var3.shipOrder.isEmpty()) {
                  var6.orderSearchAndDestroy(var13, false);
               }
            }
         } else {
            CombatAssignmentType var7;
            try {
               var7 = CombatAssignmentType.valueOf(var3.order.type);
            } catch (IllegalArgumentException var12) {
               return;
            }

            if (var7 != CombatAssignmentType.RETREAT) {
               AssignmentInfo var8 = null;

               for (AssignmentInfo var10 : var6.getAllAssignments()) {
                  if (var10.getType() == var7 && this.sameTarget(var10.getTarget(), var3.order, var4)) {
                     var8 = var10;
                     break;
                  }
               }

               if (var3.kind == 2) {
                  if (var8 != null) {
                     var6.removeAssignment(var8);
                     var6.clearEmptyWaypoints();
                  }
               } else {
                  if (var8 == null) {
                     AssignmentTargetAPI var14 = this.target(var1, var5, var3.order, var4);
                     if (var14 == null) {
                        return;
                     }

                     var8 = var6.createAssignment(var7, var14, false);
                     if (var8 == null) {
                        return;
                     }
                  }

                  for (int var16 : var3.order.members) {
                     DeployedFleetMemberAPI var11 = member(var5, var2, (ShipAPI)var4.get(var16));
                     if (var11 != null) {
                        var6.giveAssignment(var11, var8, false);
                     }
                  }
               }
            }
         }
      }
   }

   private static DeployedFleetMemberAPI member(CombatFleetManagerAPI var0, int var1, ShipAPI var2) {
      return var2 != null && var2.getOwner() == var1 && !var2.isFighter() && var2.isAlive() ? var0.getDeployedFleetMember(var2) : null;
   }

   private AssignmentTargetAPI target(CombatEngineAPI var1, CombatFleetManagerAPI var2, Orders.Assignment var3, Map<Integer, ShipAPI> var4) {
      if (var3.targetKind != 0) {
         if (var3.targetKind == 1) {
            ShipAPI var5 = (ShipAPI)var4.get(var3.targetId);
            return var5 != null && var5.isAlive() ? var1.getFleetManager(var5.getOwner()).getDeployedFleetMember(var5) : null;
         } else {
            return var2.createWaypoint(new Vector2f(var3.x, var3.y), false);
         }
      } else {
         return var3.targetId >= 0 && var3.targetId < this.objectives.size() ? (AssignmentTargetAPI)this.objectives.get(var3.targetId) : null;
      }
   }

   private boolean sameTarget(AssignmentTargetAPI var1, Orders.Assignment var2, Map<Integer, ShipAPI> var3) {
      if (var1 == null) {
         return false;
      } else if (var2.targetKind == 0) {
         return var1 instanceof BattleObjectiveAPI && this.objectives.indexOf(var1) == var2.targetId;
      } else if (var2.targetKind == 1) {
         ShipAPI var4 = (ShipAPI)var3.get(var2.targetId);
         return var1 instanceof DeployedFleetMemberAPI && var4 != null && ((DeployedFleetMemberAPI)var1).getShip() == var4;
      } else {
         return !(var1 instanceof BattleObjectiveAPI) && !(var1 instanceof DeployedFleetMemberAPI)
            ? Math.round(var1.getLocation().x / 20.0F) == Math.round(var2.x / 20.0F) && Math.round(var1.getLocation().y / 20.0F) == Math.round(var2.y / 20.0F)
            : false;
      }
   }

   int objectiveIndex(BattleObjectiveAPI var1) {
      return this.objectives == null ? -1 : this.objectives.indexOf(var1);
   }

   private void sendTexts(HostSession var1) {
      if (!this.textsBroken) {
         long var2 = System.currentTimeMillis();
         if (this.lastTexts == null || var2 - this.lastTextsAt >= 1000L) {
            this.lastTextsAt = var2;

            try {
               int var4 = Math.min(this.objectives.size(), 255);
               if (this.lastTexts == null || this.lastTexts.length != var4) {
                  this.lastTexts = new String[var4];
               }

               for (int var5 = 0; var5 < var4; var5++) {
                  String var6 = null;

                  try {
                     BattleObjectiveAPI var7 = this.objectives.get(var5);
                     if (var7 instanceof BattleObjective) {
                        BattleObjectiveEffect var8 = ((BattleObjective)var7).getEffect();
                        if (var8 != null) {
                           var6 = var8.getLongDescription();
                        }
                     }
                  } catch (Throwable var9) {
                  }

                  if (var6 == null) {
                     var6 = "";
                  }

                  if (var6.length() > 4000) {
                     var6 = var6.substring(0, 4000);
                  }

                  if (!var6.equals(this.lastTexts[var5])) {
                     this.lastTexts[var5] = var6;
                     Msg.W var11 = new Msg.W();
                     var11.out.writeByte(var5);
                     var11.out.writeUTF(var6);
                     var1.broadcast((byte)48, var11.done());
                  }
               }
            } catch (Throwable var10) {
               this.textsBroken = true;
               Global.getLogger(HostOrders.class).warn("Wingman: objectives' tooltips are not sent this battle", var10);
            }
         }
      }
   }

   private void fail(Throwable var1) {
      this.broken = true;
      Global.getLogger(HostOrders.class).error("Wingman: sending fleet orders failed, order sync is off for this battle", var1);
   }
}
