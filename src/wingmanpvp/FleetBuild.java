package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

final class FleetBuild {
   private FleetBuild() {
   }

   static FleetPack read(CampaignFleetAPI var0, String var1) {
      FleetPack var2 = new FleetPack();
      var2.owner = var1;
      if (var0 == null) {
         return var2;
      } else {
         for (FleetMemberAPI var5 : var0.getFleetData().getMembersListCopy()) {
            if (canBring(var5)) {
               FleetPack.Ship var6 = new FleetPack.Ship();
               var6.memberId = var5.getId();
               var6.shipName = var5.getShipName() == null ? "" : var5.getShipName();
               var6.points = var5.getDeploymentPointsCost();
               var6.cr = var5.getRepairTracker().getCR();
               var6.hull = var5.getStatus().getHullFraction();
               var6.flagship = var5.isFlagship();
               var6.loadout = loadout(var5.getVariant());
               PersonAPI var7 = var5.getCaptain();
               if (var7 != null && !var7.isDefault()) {
                  var6.officer = officer(var7);
               }

               var2.ships.add(var6);
               if (var2.ships.size() >= 60) {
                  break;
               }
            }
         }

         return var2;
      }
   }

   static boolean canBring(FleetMemberAPI var0) {
      return var0 != null && !var0.isFighterWing() && !var0.isMothballed() && !var0.isStation() && !var0.isCivilian();
   }

   static float fleetPoints(CampaignFleetAPI var0) {
      float var1 = 0.0F;
      if (var0 == null) {
         return var1;
      } else {
         for (FleetMemberAPI var3 : var0.getFleetData().getMembersListCopy()) {
            if (canBring(var3)) {
               var1 += var3.getDeploymentPointsCost();
            }
         }

         return var1;
      }
   }

   static FleetPack.Loadout loadout(ShipVariantAPI var0) {
      FleetPack.Loadout var1 = new FleetPack.Loadout();
      ShipHullSpecAPI var2 = var0.getHullSpec();
      var1.hullId = var2.getHullId();
      var1.displayName = var0.getDisplayName() == null ? "" : var0.getDisplayName();
      var1.vents = var0.getNumFluxVents();
      var1.caps = var0.getNumFluxCapacitors();

      for (String var4 : var0.getFittedWeaponSlots()) {
         if (!var2.isBuiltIn(var4)) {
            WeaponSlotAPI var5 = var2.getWeaponSlotAPI(var4);
            if (var5 == null || !var5.isBuiltIn() && !var5.isDecorative() && !var5.isSystemSlot()) {
               String var6 = var0.getWeaponId(var4);
               if (var6 != null && !var6.isEmpty()) {
                  var1.weapons.put(var4, var6);
               }
            }
         }
      }

      var1.wings.addAll(var0.getWings());

      for (String var13 : var0.getSMods()) {
         var1.sMods.add(var13);
      }

      for (String var14 : var0.getPermaMods()) {
         if (!var0.getSMods().contains(var14)) {
            var1.permaMods.add(var14);
         }
      }

      for (String var15 : var0.getSModdedBuiltIns()) {
         var1.sModdedBuiltIns.add(var15);
      }

      for (String var16 : var0.getHullMods()) {
         if (!var2.isBuiltInMod(var16) && !var0.getPermaMods().contains(var16)) {
            var1.hullMods.add(var16);
         }
      }

      for (WeaponGroupSpec var17 : var0.getWeaponGroups()) {
         FleetPack.Group var19 = new FleetPack.Group();
         var19.alternating = var17.getType() == WeaponGroupType.ALTERNATING;
         var19.autofire = var17.isAutofireOnByDefault();
         var19.slots.addAll(var17.getSlots());
         var1.groups.add(var19);
      }

      for (String var18 : var0.getModuleSlots()) {
         ShipVariantAPI var20 = var0.getModuleVariant(var18);
         if (var20 != null) {
            var1.modules.put(var18, loadout(var20));
         }
      }

      return var1;
   }

   static FleetPack.Officer officer(PersonAPI var0) {
      FleetPack.Officer var1 = new FleetPack.Officer();
      var1.first = var0.getName() == null ? "" : var0.getName().getFirst();
      var1.last = var0.getName() == null ? "" : var0.getName().getLast();
      var1.portrait = var0.getPortraitSprite() == null ? "" : var0.getPortraitSprite();
      var1.personality = var0.getPersonalityAPI() == null ? "steady" : var0.getPersonalityAPI().getId();
      var1.aiCore = var0.getAICoreId() == null ? "" : var0.getAICoreId();
      var1.level = var0.getStats().getLevel();

      for (SkillLevelAPI var3 : var0.getStats().getSkillsCopy()) {
         if (var3.getSkill() != null && !(var3.getLevel() <= 0.0F)) {
            var1.skillIds.add(var3.getSkill().getId());
            var1.skillLevels.add(var3.getLevel());
         }
      }

      return var1;
   }

   static List<FleetMemberAPI> build(FleetPack var0, List<String> var1) {
      ArrayList var2 = new ArrayList();

      for (FleetPack.Ship var4 : var0.ships) {
         FleetMemberAPI var5 = buildShip(var0.owner, var4, var1);
         if (var5 != null) {
            var2.add(var5);
         }
      }

      return var2;
   }

   static FleetMemberAPI buildShip(String var0, FleetPack.Ship var1, List<String> var2) {
      try {
         ShipVariantAPI var3 = variant(var1.loadout, var2, 0);
         if (var3 == null) {
            return null;
         } else {
            FleetMemberAPI var4 = Global.getFactory().createFleetMember(FleetMemberType.SHIP, var3);
            if (!var1.shipName.isEmpty()) {
               var4.setShipName(var1.shipName);
            }

            var4.getRepairTracker().setCR(Math.max(0.0F, Math.min(1.0F, var1.cr)));
            var4.getStatus().setHullFraction(Math.max(0.01F, Math.min(1.0F, var1.hull)));
            if (var1.officer != null) {
               var4.setCaptain(person(var1.officer));
            }

            return var4;
         }
      } catch (Throwable var5) {
         var2.add(var0 + "'s " + var1.shipName + " could not be built (" + var5 + ")");
         Global.getLogger(FleetBuild.class).warn("Wingman PvP: could not build " + var1.shipName + " for " + var0, var5);
         return null;
      }
   }

   static List<LobbyState.ShipInfo> summary(CampaignFleetAPI var0) {
      ArrayList var1 = new ArrayList();
      if (var0 == null) {
         return var1;
      } else {
         for (FleetMemberAPI var3 : var0.getFleetData().getMembersListCopy()) {
            if (canBring(var3)) {
               PersonAPI var4 = var3.getCaptain();
               String var5 = var4 != null && !var4.isDefault() && var4.getName() != null ? var4.getName().getFullName() : "";
               var1.add(
                  new LobbyState.ShipInfo(
                     var3.getId(),
                     var3.getHullSpec().getHullId(),
                     var3.getHullSpec().getHullName(),
                     var3.getShipName() == null ? "" : var3.getShipName(),
                     var3.getDeploymentPointsCost(),
                     var5,
                     var3.isFlagship()
                  )
               );
               if (var1.size() >= 60) {
                  break;
               }
            }
         }

         return var1;
      }
   }

   private static ShipVariantAPI variant(FleetPack.Loadout var0, List<String> var1, int var2) {
      ShipHullSpecAPI var3;
      try {
         var3 = Global.getSettings().getHullSpec(var0.hullId);
      } catch (Throwable var13) {
         var3 = null;
      }

      if (var3 == null) {
         var1.add("unknown hull " + var0.hullId + " left out");
         return null;
      } else {
         ShipVariantAPI var4 = Global.getSettings().createEmptyVariant(var0.hullId + "_wingman_pvp", var3);
         var4.setSource(VariantSource.REFIT);
         if (!var0.displayName.isEmpty()) {
            var4.setVariantDisplayName(var0.displayName);
         }

         var4.setNumFluxVents(var0.vents);
         var4.setNumFluxCapacitors(var0.caps);

         for (Entry var6 : var0.weapons.entrySet()) {
            if (var3.getWeaponSlotAPI((String)var6.getKey()) == null) {
               var1.add(var0.hullId + " has no slot " + (String)var6.getKey());
            } else {
               try {
                  if (Global.getSettings().getWeaponSpec((String)var6.getValue()) == null) {
                     throw new IllegalArgumentException();
                  }

                  var4.addWeapon((String)var6.getKey(), (String)var6.getValue());
               } catch (Throwable var12) {
                  var1.add("unknown weapon " + (String)var6.getValue() + " left out");
               }
            }
         }

         int var14 = var3.getBuiltInWings() == null ? 0 : var3.getBuiltInWings().size();

         for (int var15 = var14; var15 < var0.wings.size(); var15++) {
            String var7 = var0.wings.get(var15);
            if (var7 != null && !var7.isEmpty()) {
               try {
                  if (Global.getSettings().getFighterWingSpec(var7) == null) {
                     throw new IllegalArgumentException();
                  }

                  var4.setWingId(var15, var7);
               } catch (Throwable var11) {
                  var1.add("unknown fighter wing " + var7 + " left out");
               }
            }
         }

         for (String var22 : var0.hullMods) {
            addMod(var4, var22, var1, 0);
         }

         for (String var23 : var0.permaMods) {
            addMod(var4, var23, var1, 1);
         }

         for (String var24 : var0.sMods) {
            addMod(var4, var24, var1, 2);
         }

         for (String var25 : var0.sModdedBuiltIns) {
            if (var3.isBuiltInMod(var25)) {
               var4.getSModdedBuiltIns().add(var25);
            }
         }

         for (FleetPack.Group var26 : var0.groups) {
            WeaponGroupSpec var8 = new WeaponGroupSpec(var26.alternating ? WeaponGroupType.ALTERNATING : WeaponGroupType.LINKED);
            var8.setAutofireOnByDefault(var26.autofire);

            for (String var10 : var26.slots) {
               if (var4.getWeaponId(var10) != null || var3.isBuiltIn(var10)) {
                  var8.addSlot(var10);
               }
            }

            if (!var8.getSlots().isEmpty()) {
               var4.addWeaponGroup(var8);
            }
         }

         if (var2 < 2) {
            for (Entry var27 : var0.modules.entrySet()) {
               ShipVariantAPI var28 = variant((FleetPack.Loadout)var27.getValue(), var1, var2 + 1);
               if (var28 != null) {
                  var4.setModuleVariant((String)var27.getKey(), var28);
               }
            }
         }

         return var4;
      }
   }

   private static void addMod(ShipVariantAPI var0, String var1, List<String> var2, int var3) {
      try {
         if (Global.getSettings().getHullModSpec(var1) == null) {
            throw new IllegalArgumentException();
         }

         if (var3 == 0) {
            var0.addMod(var1);
         } else {
            var0.addPermaMod(var1, var3 == 2);
         }
      } catch (Throwable var5) {
         var2.add("unknown hull mod " + var1 + " left out");
      }
   }

   static PersonAPI person(FleetPack.Officer var0) {
      PersonAPI var1 = Global.getFactory().createPerson();
      var1.getName().setFirst(var0.first);
      var1.getName().setLast(var0.last);
      if (!var0.portrait.isEmpty()) {
         var1.setPortraitSprite(var0.portrait);
      }

      try {
         var1.setPersonality(var0.personality.isEmpty() ? "steady" : var0.personality);
      } catch (Throwable var5) {
         var1.setPersonality("steady");
      }

      if (!var0.aiCore.isEmpty()) {
         var1.setAICoreId(var0.aiCore);
      }

      var1.getStats().setLevel(var0.level);

      for (int var2 = 0; var2 < var0.skillIds.size() && var2 < var0.skillLevels.size(); var2++) {
         try {
            if (Global.getSettings().getSkillSpec(var0.skillIds.get(var2)) != null) {
               var1.getStats().setSkillLevel(var0.skillIds.get(var2), var0.skillLevels.get(var2));
            }
         } catch (Throwable var4) {
         }
      }

      return var1;
   }
}
