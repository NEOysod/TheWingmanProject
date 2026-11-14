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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.vector.Vector2f;
import wingman.api.Pvp;
import wingman.api.WingmanAPI;
import wingman.net.In;
import wingman.net.Msg;
import wingman.net.OrderRequest;
import wingman.net.Orders;

/**
 * Joiner side of the command screen. Objectives can't be created during a battle, so the join mission
 * starts with spare ones parked far off the map and this class moves them to where the host's are.
 * Fleet orders are copied from the host into the local command screen; anything the joiner adds
 * there is taken out again, because only the host's ships are real.
 *
 * (Its PvP version recovered from the compiled 0.9.3-beta pvp-test1 build of 28 Oct 2026, whose source was lost:
 * the code is exactly that build's; its comments and local names were not kept.)
 */
final class ClientOrders {
   private static final float PARK = -150000.0F;
   private final ClientOrders.Ships ships;
   private CombatEngineAPI engine;
   private final Map<String, List<BattleObjectiveAPI>> spare = new HashMap<>();
   private final List<BattleObjectiveAPI> all = new ArrayList<>();
   private final Map<BattleObjectiveAPI, float[]> home = new HashMap<>();
   private final List<BattleObjectiveAPI> placed = new ArrayList<>();
   private Orders host;
   private Set<String> previousHostKeys = new HashSet<>();
   private Set<Integer> previousShipOrders = new HashSet<>();
   private long lastReconcile;
   private long lastBlockedNotice;
   private final Map<String, Long> sentOrders = new HashMap<>();
   private final Map<Integer, Long> sentShips = new HashMap<>();
   private final Map<String, Long> cancelled = new HashMap<>();
   private Set<String> previousLocalKeys = new HashSet<>();
   private final Map<Integer, String> appliedTo = new HashMap<>();
   private Boolean appliedAssault;
   private static final long PENDING_MS = 3000L;
   private boolean broken;
   private boolean loggedState;
   private final Map<Integer, String> texts = new HashMap<>();
   private static final String ASSAULT = "FULL_ASSAULT";

   ClientOrders(ClientOrders.Ships var1) {
      this.ships = var1;
   }

   void start(CombatEngineAPI var1) {
      this.engine = var1;

      try {
         this.all.addAll(var1.getObjectives());

         for (BattleObjectiveAPI var3 : this.all) {
            this.home.put(var3, new float[]{var3.getLocation().x, var3.getLocation().y});
         }

         this.park();
         var1.getFleetManager(0).getTaskManager(false).setPreventFullRetreat(true);
         Dbg.info(ClientOrders.class, "Wingman orders: " + this.all.size() + " spare objectives ready");
      } catch (Throwable var4) {
         this.fail("setting up objectives", var4);
      }
   }

   void reset() {
      this.host = null;
      this.texts.clear();
      this.previousHostKeys = new HashSet<>();
      this.previousShipOrders = new HashSet<>();
      this.previousLocalKeys = new HashSet<>();
      this.sentOrders.clear();
      this.sentShips.clear();
      this.cancelled.clear();
      this.appliedTo.clear();
      this.appliedAssault = null;
      this.loggedState = false;
      if (!this.broken && this.engine != null) {
         try {
            this.park();
            CombatTaskManagerAPI var1 = this.engine.getFleetManager(0).getTaskManager(false);

            for (AssignmentInfo var3 : new ArrayList<AssignmentInfo>(var1.getAllAssignments())) {
               var1.removeAssignment(var3);
            }

            var1.clearEmptyWaypoints();
         } catch (Throwable var4) {
            this.fail("clearing orders", var4);
         }
      }
   }

   private void park() {
      this.spare.clear();
      this.placed.clear();

      for (int var1 = 0; var1 < this.all.size(); var1++) {
         BattleObjectiveAPI var2 = this.all.get(var1);
         if (this.engine == null || this.engine.isEntityInPlay(var2)) {
            var2.getLocation().set(-150000.0F - (float)var1 * 1000.0F, -150000.0F);
            var2.setOwner(100);
            List<BattleObjectiveAPI> var3 = this.spare.get(var2.getType());
            if (var3 == null) {
               var3 = new ArrayList<BattleObjectiveAPI>();
               this.spare.put(var2.getType(), var3);
            }

            var3.add(var2);
         }
      }
   }

   void onObjectives(byte[] var1) {
      if (!this.broken && this.engine != null) {
         try {
            this.park();
            In var2 = Msg.reader(var1);
            int var3 = var2.readByte() & 255;
            int var4 = 0;

            for (int var5 = 0; var5 < var3; var5++) {
               String var6 = var2.readUTF();
               float var7 = var2.readFloat();
               float var8 = var2.readFloat();
               List var9 = this.spare.get(var6);
               if (var9 == null || var9.isEmpty()) {
                  var9 = this.standInAt(var7, var8);
               }

               if (var9 != null && !var9.isEmpty()) {
                  int var10 = var9.size() - 1;
                  float var11 = Float.MAX_VALUE;

                  for (int var12 = 0; var12 < var9.size(); var12++) {
                     float[] var13 = this.home.get(var9.get(var12));
                     if (var13 != null) {
                        float var14 = (var13[0] - var7) * (var13[0] - var7) + (var13[1] - var8) * (var13[1] - var8);
                        if (var14 < var11) {
                           var11 = var14;
                           var10 = var12;
                        }
                     }
                  }

                  BattleObjectiveAPI var16 = (BattleObjectiveAPI)var9.remove(var10);
                  var16.getLocation().set(var7, var8);
                  this.placed.add(var16);
                  this.applyText(this.placed.size() - 1);
               } else {
                  this.placed.add(null);
                  var4++;
               }
            }

            Dbg.info(
               ClientOrders.class,
               "Wingman orders: host has "
                  + var3
                  + " objectives, placed "
                  + (var3 - var4)
                  + (var4 > 0 ? " (" + var4 + " are of a kind or number this mod has no spare for)" : "")
            );
         } catch (Throwable var15) {
            this.fail("placing objectives", var15);
         }
      }
   }

   private List<BattleObjectiveAPI> standInAt(float var1, float var2) {
      for (List var4 : this.spare.values()) {
         for (int var5 = 0; var5 < var4.size(); var5++) {
            float[] var6 = this.home.get(var4.get(var5));
            if (var6 != null && Math.abs(var6[0] - var1) < 1.0F && Math.abs(var6[1] - var2) < 1.0F) {
               ArrayList var7 = new ArrayList();
               var7.add((BattleObjectiveAPI)var4.remove(var5));
               return var7;
            }
         }
      }

      return null;
   }

   void onOrders(Orders var1) {
      this.host = var1;
      this.lastReconcile = 0L;
   }

   BattleObjectiveAPI placedAt(int var1) {
      return var1 >= 0 && var1 < this.placed.size() ? this.placed.get(var1) : null;
   }

   void onObjectiveText(byte[] var1) {
      if (!this.broken && this.engine != null) {
         try {
            In var2 = Msg.reader(var1);
            int var3 = var2.readByte() & 255;
            String var4 = var2.readUTF();
            if (var4.length() > 4000) {
               return;
            }

            this.texts.put(var3, var4);
            this.applyText(var3);
         } catch (Throwable var5) {
            this.fail("showing an objective's tooltip", var5);
         }
      }
   }

   private void applyText(int var1) {
      if (var1 < this.placed.size()) {
         MirrorObjectiveEffect var2 = mirrorOf(this.placed.get(var1));
         if (var2 != null) {
            var2.setText(this.texts.get(var1));
         }
      }
   }

   private static MirrorObjectiveEffect mirrorOf(BattleObjectiveAPI var0) {
      try {
         if (var0 instanceof BattleObjective) {
            BattleObjectiveEffect var1 = ((BattleObjective)var0).getEffect();
            if (var1 instanceof MirrorObjectiveEffect) {
               return (MirrorObjectiveEffect)var1;
            }
         }
      } catch (Throwable var2) {
      }

      return null;
   }

   private void gone(int var1, BattleObjectiveAPI var2, int var3) {
      this.placed.set(var1, null);
      boolean var4 = mirrorOf(var2) != null;

      try {
         if (this.engine.isEntityInPlay(var2)) {
            this.engine.removeEntity(var2);
         }
      } catch (Throwable var8) {
      }

      boolean var5 = false;

      try {
         var5 = WingmanConfig.load().hostMessages;
      } catch (Throwable var7) {
      }

      if (var4 && !var5) {
         String var6 = var2.getDisplayName() == null ? "An objective" : var2.getDisplayName();
         Ui.say(
            this.engine,
            var6 + (var3 == 0 ? " captured by your side" : (var3 == 1 ? " captured by the enemy" : " is gone")) + " (its effect runs in the host's game)."
         );
      }

      Dbg.info(ClientOrders.class, "Wingman objectives: '" + var2.getType() + "' is gone from the host's battle; removed here too");
   }

   void tick() {
      if (!this.broken && this.engine != null && this.host != null) {
         try {
            for (int var1 = 0; var1 < this.placed.size() && var1 < this.host.objectiveOwners.length; var1++) {
               BattleObjectiveAPI var2 = this.placed.get(var1);
               if (var2 != null) {
                  int var3 = this.host.objectiveOwners[var1] & 127;
                  if ((this.host.objectiveOwners[var1] & 128) != 0) {
                     this.gone(var1, var2, var3);
                  } else if (var2.getOwner() != var3) {
                     var2.setOwner(var3);
                  }
               }
            }

            long var5 = System.currentTimeMillis();
            if (var5 - this.lastReconcile < 300L) {
               return;
            }

            this.lastReconcile = var5;
            this.reconcile(var5);
         } catch (Throwable var4) {
            this.fail("showing the host's orders", var4);
         }
      }
   }

   private String keyOf(AssignmentInfo var1) {
      AssignmentTargetAPI var2 = var1.getTarget();
      if (var2 != null && var1.getType() != null) {
         Orders.Assignment var3 = new Orders.Assignment();
         var3.type = var1.getType().name();
         if (var2 instanceof BattleObjectiveAPI) {
            var3.targetKind = 0;
            var3.targetId = this.placed.indexOf(var2);
         } else if (var2 instanceof DeployedFleetMemberAPI) {
            Integer var4 = this.ships.id(((DeployedFleetMemberAPI)var2).getShip());
            var3.targetKind = 1;
            var3.targetId = var4 == null ? -2 : var4;
         } else {
            var3.targetKind = 2;
            var3.x = var2.getLocation().x;
            var3.y = var2.getLocation().y;
         }

         return var3.key();
      } else {
         return null;
      }
   }

   private void reconcile(long var1) {
      CombatFleetManagerAPI var3 = this.engine.getFleetManager(0);
      CombatTaskManagerAPI var4 = var3.getTaskManager(false);
      HashMap var5 = new HashMap();

      for (Orders.Assignment var7 : this.host.assignments) {
         var5.put(var7.key(), var7);
      }

      boolean var23 = Pvp.active() && Pvp.localCaptain();
      if (var23) {
         this.captainChanges(var1, var3, var4, var5);
      }

      HashMap var24 = new HashMap();
      boolean var8 = false;
      boolean var9 = false;

      for (AssignmentInfo var11 : new ArrayList<AssignmentInfo>(var4.getAllAssignments())) {
         String var12 = this.keyOf(var11);
         if (var12 != null) {
            if (var5.containsKey(var12) && !var24.containsKey(var12)) {
               var24.put(var12, var11);
            } else {
               if (var23 && !this.previousHostKeys.contains(var12)) {
                  Long var13 = this.sentOrders.get(var12);
                  if (var13 == null) {
                     var13 = var1;
                     this.sentOrders.put(var12, var13);
                     this.request(1, this.describe(var11, var3), 0, "", false);
                  }

                  if (var1 - var13 < 3000L) {
                     continue;
                  }
               }

               var4.removeAssignment(var11);
               var9 = true;
               if (!this.previousHostKeys.contains(var12)) {
                  var8 = true;
               }
            }
         }
      }

      if (var9) {
         var4.clearEmptyWaypoints();
      }

      int var25 = 0;
      int var26 = 0;

      for (Orders.Assignment var29 : this.host.assignments) {
         AssignmentInfo var14 = (AssignmentInfo)var24.get(var29.key());
         if (var14 != null || !var23 || !pending(this.cancelled.get(var29.key()), var1)) {
            if (var14 == null) {
               AssignmentTargetAPI var15 = this.resolve(var29, var3);
               CombatAssignmentType var16 = null;

               try {
                  var16 = CombatAssignmentType.valueOf(var29.type);
               } catch (IllegalArgumentException var22) {
               }

               if (var15 == null || var16 == null) {
                  var26++;
                  continue;
               }

               var14 = var4.createAssignment(var16, var15, false);
               if (var14 == null) {
                  var26++;
                  continue;
               }

               var24.put(var29.key(), var14);
               var25++;
            }

            List var34 = var14.getAssignedMembers();

            for (int var17 : var29.members) {
               ShipAPI var18 = this.ships.ship(var17);
               if (var18 != null && !this.ships.isOwn(var17) && (!var23 || !pending(this.sentShips.get(var17), var1))) {
                  DeployedFleetMemberAPI var19 = var3.getDeployedFleetMember(var18);
                  if (var19 != null && (var34 == null || !var34.contains(var19))) {
                     var4.giveAssignment(var19, var14, false);
                  }
               }
            }
         }
      }

      if (var4.isFullAssault() != this.host.fullAssault && (!var23 || !pending(this.sentOrders.get("FULL_ASSAULT"), var1))) {
         var4.setFullAssault(this.host.fullAssault);
      }

      this.appliedAssault = var4.isFullAssault();
      HashMap var28 = new HashMap();

      for (Orders.ShipOrder var32 : this.host.shipOrders) {
         var28.put(var32.ship, var32);
      }

      int var31 = 0;

      for (int var35 : this.ships.friendlyShips()) {
         if (!this.ships.isOwn(var35) && (!var23 || !pending(this.sentShips.get(var35), var1))) {
            ShipAPI var37 = this.ships.ship(var35);
            DeployedFleetMemberAPI var38 = var37 == null ? null : var3.getDeployedFleetMember(var37);
            if (var38 != null) {
               AssignmentInfo var39 = var4.getAssignmentFor(var37);
               String var40 = var39 != null && var39.getTarget() == null && var39.getType() != null ? var39.getType().name() : "";
               Orders.ShipOrder var20 = (Orders.ShipOrder)var28.get(var35);
               String var21 = var20 == null ? "" : var20.type;
               if (!var40.equals(var21)) {
                  if ("RETREAT".equals(var21)) {
                     var4.orderRetreat(var38, false, var20.direct);
                  } else if ("SEARCH_AND_DESTROY".equals(var21)) {
                     var4.orderSearchAndDestroy(var38, false);
                  } else {
                     if (!"RETREAT".equals(var40)) {
                        continue;
                     }

                     var4.orderSearchAndDestroy(var38, false);
                     if (!this.previousShipOrders.contains(var35)) {
                        var8 = true;
                     }
                  }

                  var31++;
               }
            }
         }
      }

      this.previousShipOrders = new HashSet<>(var28.keySet());
      if (var31 > 0) {
         Dbg.info(
            ClientOrders.class, "Wingman orders: host has " + this.host.shipOrders.size() + " retreat / search-and-destroy orders, applied " + var31 + " here"
         );
      }

      this.previousHostKeys = new HashSet<>(var5.keySet());
      if (var23) {
         this.remember(var3, var4, var5);
      }

      if (var8 && !var23 && var1 - this.lastBlockedNotice > 6000L) {
         this.lastBlockedNotice = var1;
         Ui.say(this.engine, "Co-op: only the host can give fleet orders. Showing the host's orders.");
      }

      if (!this.loggedState || var25 > 0) {
         this.loggedState = true;
         Dbg.info(
            ClientOrders.class,
            "Wingman orders: host has " + this.host.assignments.size() + " orders, created " + var25 + " here, " + var26 + " could not be shown"
         );
      }
   }

   private static boolean pending(Long var0, long var1) {
      return var0 != null && var1 - var0 < 3000L;
   }

   private void captainChanges(long var1, CombatFleetManagerAPI var3, CombatTaskManagerAPI var4, Map<String, Orders.Assignment> var5) {
      HashSet var6 = new HashSet();

      for (AssignmentInfo var8 : var4.getAllAssignments()) {
         String var9 = this.keyOf(var8);
         if (var9 != null) {
            var6.add(var9);
         }
      }

      for (String var15 : this.previousLocalKeys) {
         if (var5.containsKey(var15) && !var6.contains(var15) && !pending(this.cancelled.get(var15), var1)) {
            this.cancelled.put(var15, var1);
            this.request(2, (Orders.Assignment)var5.get(var15), 0, "", false);
         }
      }

      for (int var16 : this.ships.friendlyShips()) {
         if (!this.ships.isOwn(var16) && !pending(this.sentShips.get(var16), var1)) {
            String var17 = this.appliedTo.get(var16);
            if (var17 != null) {
               ShipAPI var10 = this.ships.ship(var16);
               if (var10 != null && var3.getDeployedFleetMember(var10) != null) {
                  String var11 = this.orderKeyOf(var10, var4);
                  if (!var11.equals(var17)) {
                     if (var11.startsWith("SHIP:") || var11.isEmpty()) {
                        this.sentShips.put(var16, var1);
                        this.request(4, null, var16, var11.isEmpty() ? "" : var11.substring(5), false);
                     } else if (var5.containsKey(var11)) {
                        this.sentShips.put(var16, var1);
                        Orders.Assignment var12 = copyOf((Orders.Assignment)var5.get(var11));
                        var12.members.add(var16);
                        this.request(3, var12, 0, "", false);
                     }
                  }
               }
            }
         }
      }

      if (this.appliedAssault != null && var4.isFullAssault() != this.appliedAssault && !pending(this.sentOrders.get("FULL_ASSAULT"), var1)) {
         this.sentOrders.put("FULL_ASSAULT", var1);
         this.request(5, null, 0, "", var4.isFullAssault());
      }
   }

   private void remember(CombatFleetManagerAPI var1, CombatTaskManagerAPI var2, Map<String, Orders.Assignment> var3) {
      HashSet var4 = new HashSet();

      for (AssignmentInfo var6 : var2.getAllAssignments()) {
         String var7 = this.keyOf(var6);
         if (var7 != null && var3.containsKey(var7)) {
            var4.add(var7);
         }
      }

      this.previousLocalKeys = var4;
      long var10 = System.currentTimeMillis();

      for (int var8 : this.ships.friendlyShips()) {
         if (!pending(this.sentShips.get(var8), var10)) {
            ShipAPI var9 = this.ships.ship(var8);
            if (var9 != null) {
               this.appliedTo.put(var8, this.orderKeyOf(var9, var2));
            }
         }
      }
   }

   private String orderKeyOf(ShipAPI var1, CombatTaskManagerAPI var2) {
      AssignmentInfo var3 = var2.getAssignmentFor(var1);
      if (var3 == null || var3.getType() == null) {
         return "";
      } else if (var3.getTarget() == null) {
         return "SHIP:" + var3.getType().name();
      } else {
         String var4 = this.keyOf(var3);
         return var4 == null ? "" : var4;
      }
   }

   private Orders.Assignment describe(AssignmentInfo var1, CombatFleetManagerAPI var2) {
      Orders.Assignment var3 = new Orders.Assignment();
      AssignmentTargetAPI var4 = var1.getTarget();
      var3.type = var1.getType().name();
      if (var4 instanceof BattleObjectiveAPI) {
         var3.targetKind = 0;
         var3.targetId = this.placed.indexOf(var4);
      } else if (var4 instanceof DeployedFleetMemberAPI) {
         Integer var5 = this.ships.id(((DeployedFleetMemberAPI)var4).getShip());
         var3.targetKind = 1;
         var3.targetId = var5 == null ? -2 : var5;
      } else {
         var3.targetKind = 2;
         var3.x = var4.getLocation().x;
         var3.y = var4.getLocation().y;
      }

      List<DeployedFleetMemberAPI> var9 = var1.getAssignedMembers();
      if (var9 != null) {
         for (DeployedFleetMemberAPI var7 : var9) {
            Integer var8 = this.ships.id(var7.getShip());
            if (var8 != null && !this.ships.isOwn(var8)) {
               var3.members.add(var8);
            }
         }
      }

      return var3;
   }

   private static Orders.Assignment copyOf(Orders.Assignment var0) {
      Orders.Assignment var1 = new Orders.Assignment();
      var1.type = var0.type;
      var1.targetKind = var0.targetKind;
      var1.targetId = var0.targetId;
      var1.x = var0.x;
      var1.y = var0.y;
      return var1;
   }

   private void request(int var1, Orders.Assignment var2, int var3, String var4, boolean var5) {
      OrderRequest var6 = new OrderRequest();
      var6.kind = var1;
      if (var2 != null) {
         var6.order = var2;
      }

      var6.ship = var3;
      var6.shipOrder = var4;
      var6.on = var5;

      try {
         WingmanAPI.send("wingman:pvp-orders", var6.encode());
      } catch (Throwable var8) {
         Global.getLogger(ClientOrders.class).warn("Wingman PvP: an order could not be sent to the host", var8);
      }
   }

   private AssignmentTargetAPI resolve(Orders.Assignment var1, CombatFleetManagerAPI var2) {
      if (var1.targetKind != 0) {
         if (var1.targetKind == 1) {
            ShipAPI var3 = this.ships.ship(var1.targetId);
            return var3 == null ? null : this.engine.getFleetManager(this.ships.side(var1.targetId)).getDeployedFleetMember(var3);
         } else {
            return var2.createWaypoint(new Vector2f(var1.x, var1.y), false);
         }
      } else {
         return var1.targetId >= 0 && var1.targetId < this.placed.size() ? (AssignmentTargetAPI)this.placed.get(var1.targetId) : null;
      }
   }

   private void fail(String var1, Throwable var2) {
      this.broken = true;
      Global.getLogger(ClientOrders.class).error("Wingman: " + var1 + " failed, order display is off for this battle", var2);
   }

   private static final class OrderRequestKinds {
      static final int CREATE = 1;
      static final int CANCEL = 2;
      static final int MEMBER = 3;
      static final int SHIP = 4;
      static final int FULL_ASSAULT = 5;
   }

   interface Ships {
      List<Integer> friendlyShips();

      ShipAPI ship(int var1);

      Integer id(ShipAPI var1);

      int side(int var1);

      boolean isOwn(int var1);
   }
}
