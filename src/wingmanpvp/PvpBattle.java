package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import wingman.api.Pvp;
import wingman.api.WingmanAPI;

public final class PvpBattle {
   private PvpBattle() {
   }

   public static PvpBattle.Match start(
      InteractionDialogAPI var0,
      PvpSession var1,
      Map<String, FleetPack> var2,
      Map<String, Integer> var3,
      Map<String, String> seats,
      String[] var4,
      String[] var5,
      boolean var6,
      List<String> var7
   ) {
      CampaignFleetAPI var8 = Global.getSector().getPlayerFleet();
      PvpBattle.Match var9 = new PvpBattle.Match();
      var9.sideOf.putAll(var3);

      for (String var13 : var4) {
         if (var13 != null && !var13.isEmpty()) {
            var9.captains.add(var13);
         }
      }

      var9.teamNames[0] = var5[0];
      var9.teamNames[1] = var5[1];
      var9.packs.putAll(var2);
      var9.seats.putAll(seats);
      Map<String, FleetMemberAPI> built = new HashMap<String, FleetMemberAPI>(); // captain|pack member id -> the copy
      String var25 = WingmanAPI.localName();
      List<FleetMemberAPI> var26 = new ArrayList<FleetMemberAPI>();
      List<FleetMemberAPI> var27 = new ArrayList<FleetMemberAPI>();
      FleetMemberAPI var28 = null;
      FleetMemberAPI var14 = null;
      Map<String, FleetMemberAPI> var15 = new HashMap<String, FleetMemberAPI>();

      for (Entry var17 : var2.entrySet()) {
         String var18 = (String)var17.getKey();
         Integer var19 = (Integer)var3.get(var18);
         if (var19 != null) {
            FleetPack var20 = (FleetPack)var17.getValue();

            for (FleetPack.Ship var22 : var20.ships) {
               FleetMemberAPI var23 = FleetBuild.buildShip(var18, var22, var9.notes);
               if (var23 != null) {
                  var9.ownerOf.put(var23.getId(), var18);
                  var9.nameOf.put(var23.getId(), var23.getShipName() + " (" + var23.getHullSpec().getHullName() + ")");
                  var9.pointsOf.put(var23.getId(), var23.getDeploymentPointsCost());
                  built.put(var18 + "|" + var22.memberId, var23);

                  (var19 == 0 ? var26 : var27).add(var23);
               }
            }
         }
      }

      // Everyone flies the ship they picked from their captain's fleet (a captain, their own flagship by default).
      for (Entry<String, Integer> e : var3.entrySet()) {
         String who = e.getKey();
         String captain = var4[e.getValue() == 0 ? 0 : 1];
         FleetPack pack = captain == null ? null : var2.get(captain);
         String seat = seats.get(who);
         if ((seat == null || seat.isEmpty()) && who.equals(captain) && pack != null) {
            seat = flagIdOf(pack);
         }

         FleetMemberAPI m = seat == null ? null : built.get(captain + "|" + seat);
         if (m != null && !var15.containsValue(m)) {
            var15.put(who, m);
            var9.ownerOf.put(m.getId(), who); // (its loss is the pilot's in the results)
         } else {
            var9.notes.add(who + " has no ship of their own: they can take one in the battle (Transfer Command).");
         }
      }

      var28 = (FleetMemberAPI)var15.get(var25);
      if (!var26.isEmpty() && !var27.isEmpty() && var28 != null) {
         var14 = (FleetMemberAPI)var15.get(var4[1]);
         if (var14 == null) {
            var14 = (FleetMemberAPI)var27.get(0);
         }

         CampaignFleetAPI var32 = Global.getFactory().createEmptyFleet("independent", var5[1], true);

         for (FleetMemberAPI var37 : sortedFlagshipsFirst(var27, var15)) {
            var32.getFleetData().addFleetMember(var37);
         }

         var32.getFleetData().setFlagship(var14);
         PersonAPI var35 = var14.getCaptain();
         if (var35 != null && !var35.isDefault()) {
            var32.setCommander(var35);
         }

         LocationAPI var38 = var8.getContainingLocation();
         var38.addEntity(var32);
         var32.setLocation(var8.getLocation().x, var8.getLocation().y);
         var9.enemy = var32;
         var9.stash = var8.getFleetData().getMembersListCopy();
         var9.stashFlag = var8.getFlagship();
         var9.cargoCopy = var8.getCargo().createCopy();

         for (FleetMemberAPI var44 : var9.stash) {
            var8.getFleetData().removeFleetMember(var44);
         }

         PersonAPI var41 = Global.getSector().getPlayerPerson();

         for (FleetMemberAPI var50 : sortedFlagshipsFirst(var26, var15)) {
            var8.getFleetData().addFleetMember(var50);
         }

         var28.setCaptain(var41);
         var8.getFleetData().setFlagship(var28);
         var8.getFleetData().setSyncNeeded();
         var8.getFleetData().syncIfNeeded();
         var8.getCargo().addCrew(Math.max(0, Math.round(var8.getFleetData().getMinCrew()) - var8.getCargo().getCrew()) + 1);

         // Second in Command: each team fights with its captain's executive officers.
         FleetPack enemyPack = var4[1] == null ? null : var2.get(var4[1]);
         SicBridge.apply(var32, enemyPack == null ? new ArrayList<FleetPack.Exec>() : enemyPack.execs, false, var9.notes, String.valueOf(var4[1]));
         if (var4[0] != null && !var4[0].equalsIgnoreCase(var25)) {
            FleetPack ownPack = var2.get(var4[0]);
            var9.sic = SicBridge.apply(var8, ownPack == null ? new ArrayList<FleetPack.Exec>() : ownPack.execs, true, var9.notes, var4[0]);
         }

         for (Entry var51 : var15.entrySet()) {
            if (!((String)var51.getKey()).equalsIgnoreCase(var25)) {
               WingmanAPI.reserveShip((String)var51.getKey(), ((FleetMemberAPI)var51.getValue()).getId());
            }
         }

         Pvp.host(var3, var9.captains);
         BattleCreationContext var47 = new BattleCreationContext(var8, FleetGoal.ATTACK, var32, FleetGoal.ATTACK);
         var47.aiRetreatAllowed = false;
         var47.fightToTheLast = true;
         var47.enemyDeployAll = true;
         var47.objectivesAllowed = var6;
         PvpSession.Stage var52 = var1.stage;
         var1.battleStarted(var9);

         try {
            var0.startBattle(var47);
            return var9;
         } catch (Throwable var24) {
            Global.getLogger(PvpBattle.class).error("Wingman PvP: the battle could not start", var24);
            var7.add("The battle could not start: " + var24);
            var1.stage = var52;
            restore(var9);
            return null;
         }
      } else {
         var7.add("The fleets could not be built (see starsector.log).");
         var7.addAll(var9.notes);
         return null;
      }
   }

   private static String flagIdOf(FleetPack var0) {
      return var0.ships.isEmpty() ? "" : var0.ships.get(0).memberId;
   }

   private static List<FleetMemberAPI> sortedFlagshipsFirst(List<FleetMemberAPI> var0, Map<String, FleetMemberAPI> var1) {
      ArrayList var2 = new ArrayList();

      for (FleetMemberAPI var4 : var0) {
         if (var1.containsValue(var4)) {
            var2.add(var4);
         }
      }

      for (FleetMemberAPI var6 : var0) {
         if (!var1.containsValue(var6)) {
            var2.add(var6);
         }
      }

      return var2;
   }

   public static void restore(PvpBattle.Match var0) {
      if (var0 != null && !var0.restored) {
         var0.restored = true;
         CampaignFleetAPI var1 = Global.getSector().getPlayerFleet();

         try {
            for (FleetMemberAPI var3 : var1.getFleetData().getMembersListCopy()) {
               var1.getFleetData().removeFleetMember(var3);
            }

            for (FleetMemberAPI var9 : var0.stash) {
               var1.getFleetData().addFleetMember(var9);
            }

            if (var0.stashFlag != null) {
               var1.getFleetData().setFlagship(var0.stashFlag);
            }

            if (var0.stashFlag != null) {
               var0.stashFlag.setCaptain(Global.getSector().getPlayerPerson());
            }

            var1.getFleetData().sortToMatchOrder(var0.stash);
            var1.getFleetData().setSyncNeeded();
            var1.getFleetData().syncIfNeeded();
            CargoAPI var7 = var1.getCargo();
            var7.clear();
            var7.addAll(var0.cargoCopy);
         } catch (Throwable var5) {
            Global.getLogger(PvpBattle.class).error("Wingman PvP: putting the host's fleet back failed", var5);
         }

         SicBridge.restore(var0.sic);
         var0.sic = null;

         try {
            if (var0.enemy != null) {
               var0.enemy.despawn();
            }
         } catch (Throwable var4) {
            Global.getLogger(PvpBattle.class).warn("Wingman PvP: removing the battle's fleet", var4);
         }

         for (String var10 : var0.ownerOf.values()) {
            WingmanAPI.reserveShip(var10, null);
         }
      }
   }

   public static final class Match {
      public final Map<String, String> ownerOf = new HashMap<>();
      public final Map<String, String> nameOf = new HashMap<>();
      public final Map<String, Float> pointsOf = new HashMap<>();
      public final Map<String, Integer> sideOf = new HashMap<>();
      public final Set<String> captains = new HashSet<>();
      public final String[] teamNames = new String[]{"Team A", "Team B"};
      public final List<String> notes = new ArrayList<>();
      public final Map<String, FleetPack> packs = new HashMap<>();
      /** Each player's ship: its member id in the captain's fleet (kept for a rematch). */
      public final Map<String, String> seats = new HashMap<>();
      List<FleetMemberAPI> stash;
      FleetMemberAPI stashFlag;
      CargoAPI cargoCopy;
      CampaignFleetAPI enemy;
      /** The host's own Second in Command executive officers, while the battle uses their captain's. */
      SicBridge.Saved sic;
      boolean restored;
   }
}
