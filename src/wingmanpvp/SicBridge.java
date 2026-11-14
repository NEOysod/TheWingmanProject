package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Second in Command (Lukas04) in PvP battles. That mod keeps a fleet's executive officers in an SCData object in the
 * fleet's memory ("$sc_stored_data"; the player's is kept on the player person too), and its skill hull mod reads it
 * from each ship's fleet when the ship is made. So a team fights with its captain's executive officers if the battle
 * fleet holds an SCData with them: the other team's fleet gets one made here (and is tagged so the mod doesn't give it
 * random NPC ones), and the host's fleet has the host's swapped for the captain's for the battle, put back after.
 * All by name, through method handles (the game's script sandbox refuses java.lang.reflect): without the mod
 * nothing happens, and any failure leaves the battle as it would have been.
 */
final class SicBridge {
   private SicBridge() {
   }

   static final String MOD_ID = "second_in_command";
   static final String KEY = "$sc_stored_data";
   static final String NO_NPC_SKILLS = "sc_do_not_generate_skills";

   static boolean on() {
      try {
         return Global.getSettings().getModManager().isModEnabled(MOD_ID);
      } catch (Throwable t) {
         return false;
      }
   }

   private static Class<?> cls(String n) throws ClassNotFoundException {
      ClassLoader l = SicBridge.class.getClassLoader();
      try { l = Global.getSettings().getScriptClassLoader(); } catch (Throwable t) { /* outside the game (tests) */ }
      return Class.forName(n, true, l);
   }

   /** For tests: are all of Second in Command's methods this needs there, as named? */
   static void checkNames() throws Throwable { new H(); }

   private static final MethodHandles.Lookup L = MethodHandles.lookup();

   /** The mod's methods this needs, looked up once (exact signatures, as Second in Command 2.1 has them). */
   private static final class H {
      final MethodHandle playerData, activeOfficers, assignedSlot, init, addOfficer, setInSlot, getInSlot, newData,
            aptitudeId, person, skillIds, addSkill, newOfficer, aptitudeSpec, skillSpec;

      H() throws Throwable {
         Class<?> utils = cls("second_in_command.SCUtils"), data = cls("second_in_command.SCData");
         Class<?> officer = cls("second_in_command.specs.SCOfficer"), store = cls("second_in_command.specs.SCSpecStore");
         Class<?> aptSpec = cls("second_in_command.specs.SCAptitudeSpec"), skSpec = cls("second_in_command.specs.SCSkillSpec");
         playerData = L.findStatic(utils, "getPlayerData", MethodType.methodType(data));
         activeOfficers = L.findVirtual(data, "getActiveOfficers", MethodType.methodType(List.class));
         assignedSlot = L.findVirtual(data, "getOfficersAssignedSlot", MethodType.methodType(Integer.class, officer));
         init = L.findVirtual(data, "init", MethodType.methodType(void.class));
         addOfficer = L.findVirtual(data, "addOfficerToFleet", MethodType.methodType(void.class, officer));
         setInSlot = L.findVirtual(data, "setOfficerInSlot", MethodType.methodType(void.class, int.class, officer));
         getInSlot = L.findVirtual(data, "getOfficerInSlot", MethodType.methodType(officer, int.class));
         newData = L.findConstructor(data, MethodType.methodType(void.class, CampaignFleetAPI.class));
         aptitudeId = L.findVirtual(officer, "getAptitudeId", MethodType.methodType(String.class));
         person = L.findVirtual(officer, "getPerson", MethodType.methodType(PersonAPI.class));
         skillIds = L.findVirtual(officer, "getActiveSkillIDs", MethodType.methodType(Set.class));
         addSkill = L.findVirtual(officer, "addSkill", MethodType.methodType(void.class, String.class));
         newOfficer = L.findConstructor(officer, MethodType.methodType(void.class, PersonAPI.class, String.class));
         aptitudeSpec = L.findStatic(store, "getAptitudeSpec", MethodType.methodType(aptSpec, String.class));
         skillSpec = L.findStatic(store, "getSkillSpec", MethodType.methodType(skSpec, String.class));
      }
   }

   private static H h;

   private static H h() throws Throwable {
      if (h == null) h = new H();
      return h;
   }

   /** The player's executive officers in their slots (in the campaign, at the captain's end). */
   static List<FleetPack.Exec> read() {
      List<FleetPack.Exec> out = new ArrayList<>();
      if (!on()) {
         return out;
      }
      try {
         H m = h();
         Object data = m.playerData.invoke();
         for (Object o : (List<?>) m.activeOfficers.invoke(data)) {
            Object slot = m.assignedSlot.invoke(data, o);
            if (!(slot instanceof Integer) || (Integer) slot < 0 || (Integer) slot >= FleetPack.MAX_EXECS) {
               continue;
            }
            FleetPack.Exec e = new FleetPack.Exec();
            e.slot = (Integer) slot;
            e.aptitudeId = String.valueOf(m.aptitudeId.invoke(o));
            try {
               e.name = ((PersonAPI) m.person.invoke(o)).getNameString();
            } catch (Throwable t) {
               e.name = "";
            }
            for (Object id : (Iterable<?>) m.skillIds.invoke(o)) {
               if (e.skills.size() < FleetPack.MAX_EXEC_SKILLS) {
                  e.skills.add(String.valueOf(id));
               }
            }
            out.add(e);
            if (out.size() >= FleetPack.MAX_EXECS) {
               break;
            }
         }
      } catch (Throwable t) {
         Global.getLogger(SicBridge.class).warn("Wingman PvP: couldn't read Second in Command's executive officers", t);
      }
      return out;
   }

   /** What the host's fleet had before the battle, to put back. */
   static final class Saved {
      CampaignFleetAPI fleet;
      Object made;
      Object fleetData;
      Object personData;
   }

   /**
    * Gives a battle fleet the captain's executive officers. For the host's own fleet (player) the host's are kept
    * aside and the returned Saved puts them back; for the other team's fleet it returns null (the fleet is removed
    * after the battle anyway).
    */
   static Saved apply(CampaignFleetAPI fleet, List<FleetPack.Exec> execs, boolean player, List<String> notes, String captain) {
      if (!on() || fleet == null) {
         return null;
      }
      Saved saved = null;
      try {
         H m = h();
         PersonAPI me = Global.getSector().getPlayerPerson();
         if (player) {
            saved = new Saved();
            saved.fleet = fleet;
            saved.fleetData = fleet.getMemoryWithoutUpdate().get(KEY);
            saved.personData = me.getMemoryWithoutUpdate().get(KEY);
         } else {
            fleet.addTag(NO_NPC_SKILLS);
            Object old = fleet.getMemoryWithoutUpdate().get(KEY);
            if (old instanceof com.fs.starfarer.api.EveryFrameScript) {
               fleet.removeScript((com.fs.starfarer.api.EveryFrameScript) old);
            }
         }
         Object data = m.newData.invoke(fleet);
         fleet.getMemoryWithoutUpdate().set(KEY, data);
         if (player) {
            me.getMemoryWithoutUpdate().set(KEY, data);
            saved.made = data;
         }
         m.init.invoke(data);
         if (player && data instanceof com.fs.starfarer.api.EveryFrameScript) {
            // The host's own SCData stays the fleet's script; this one only has to be found for the battle.
            fleet.removeScript((com.fs.starfarer.api.EveryFrameScript) data);
         }
         int n = 0;
         for (FleetPack.Exec e : execs) {
            if (m.aptitudeSpec.invoke(e.aptitudeId) == null) {
               notes.add("Second in Command: " + captain + "'s aptitude " + e.aptitudeId + " isn't in this game, left out.");
               continue;
            }
            PersonAPI p = Global.getFactory().createPerson();
            try {
               String[] nm = e.name.split(" ", 2);
               p.getName().setFirst(nm[0]);
               p.getName().setLast(nm.length > 1 ? nm[1] : "");
            } catch (Throwable t) {
               // a name doesn't matter in a battle
            }
            Object o = m.newOfficer.invoke(p, e.aptitudeId);
            for (String id : e.skills) {
               if (m.skillSpec.invoke(id) != null) {
                  m.addSkill.invoke(o, id);
               }
            }
            m.addOfficer.invoke(data, o);
            m.setInSlot.invoke(data, e.slot, o);
            n++;
         }
         if (n > 0) {
            notes.add("Second in Command: " + captain + "'s " + n + " executive officer" + (n == 1 ? "" : "s") + " in the battle.");
         }
      } catch (Throwable t) {
         Global.getLogger(SicBridge.class).warn("Wingman PvP: couldn't set Second in Command's executive officers for " + captain, t);
         notes.add("Second in Command: " + captain + "'s executive officers couldn't be set (see starsector.log).");
      }
      return saved;
   }

   /** The host's own executive officers back, as before the battle. */
   static void restore(Saved s) {
      if (s == null) {
         return;
      }
      try {
         if (s.made != null) {
            for (int i = 0; i < FleetPack.MAX_EXECS; i++) {
               try {
                  if (h().getInSlot.invoke(s.made, i) != null) {
                     h().setInSlot.invoke(s.made, i, null);
                  }
               } catch (Throwable t) {
                  break;
               }
            }
            if (s.made instanceof com.fs.starfarer.api.EveryFrameScript) {
               s.fleet.removeScript((com.fs.starfarer.api.EveryFrameScript) s.made);
            }
            if (s.made instanceof com.fs.starfarer.api.campaign.listeners.FleetEventListener) {
               s.fleet.removeEventListener((com.fs.starfarer.api.campaign.listeners.FleetEventListener) s.made);
            }
         }
      } catch (Throwable t) {
         Global.getLogger(SicBridge.class).warn("Wingman PvP: clearing the battle's executive officers", t);
      }
      try {
         put(s.fleet.getMemoryWithoutUpdate(), s.fleetData);
         put(Global.getSector().getPlayerPerson().getMemoryWithoutUpdate(), s.personData);
         for (com.fs.starfarer.api.fleet.FleetMemberAPI m : s.fleet.getFleetData().getMembersListCopy()) {
            m.updateStats();
         }
      } catch (Throwable t) {
         Global.getLogger(SicBridge.class).error("Wingman PvP: putting the host's executive officers back failed", t);
      }
   }

   private static void put(com.fs.starfarer.api.campaign.rules.MemoryAPI m, Object v) {
      if (v == null) {
         m.unset(KEY);
      } else {
         m.set(KEY, v);
      }
   }
}
