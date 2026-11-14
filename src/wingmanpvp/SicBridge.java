package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Second in Command (Lukas04) in PvP battles. That mod keeps a fleet's executive officers in an SCData object in the
 * fleet's memory ("$sc_stored_data"; the player's is kept on the player person too), and its skill hull mod reads it
 * from each ship's fleet when the ship is made. So a team fights with its captain's executive officers if the battle
 * fleet holds an SCData with them: the other team's fleet gets one made here (and is tagged so the mod doesn't give it
 * random NPC ones), and the host's fleet has the host's swapped for the captain's for the battle, put back after.
 * All by name: without the mod nothing happens, and any failure leaves the battle as it would have been.
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
      return Class.forName(n, true, Global.getSettings().getScriptClassLoader());
   }

   private static Object call(Object on, String name, Object... args) throws Exception {
      Class<?> c = on instanceof Class ? (Class<?>) on : on.getClass();
      for (Method m : c.getMethods()) {
         if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
            return m.invoke(on instanceof Class ? null : on, args);
         }
      }
      throw new NoSuchMethodException(c.getName() + "." + name);
   }

   /** The player's executive officers in their slots (in the campaign, at the captain's end). */
   static List<FleetPack.Exec> read() {
      List<FleetPack.Exec> out = new ArrayList<>();
      if (!on()) {
         return out;
      }
      try {
         Object data = call(cls("second_in_command.SCUtils"), "getPlayerData");
         for (Object o : (List<?>) call(data, "getActiveOfficers")) {
            Object slot = call(data, "getOfficersAssignedSlot", o);
            if (!(slot instanceof Integer) || (Integer) slot < 0 || (Integer) slot >= FleetPack.MAX_EXECS) {
               continue;
            }
            FleetPack.Exec e = new FleetPack.Exec();
            e.slot = (Integer) slot;
            e.aptitudeId = String.valueOf(call(o, "getAptitudeId"));
            try {
               e.name = ((PersonAPI) call(o, "getPerson")).getNameString();
            } catch (Throwable t) {
               e.name = "";
            }
            for (Object id : (Iterable<?>) call(o, "getActiveSkillIDs")) {
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
         Class<?> store = cls("second_in_command.specs.SCSpecStore");
         Class<?> dataCls = cls("second_in_command.SCData");
         Class<?> officerCls = cls("second_in_command.specs.SCOfficer");
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
         Object data = dataCls.getConstructor(CampaignFleetAPI.class).newInstance(fleet);
         fleet.getMemoryWithoutUpdate().set(KEY, data);
         if (player) {
            me.getMemoryWithoutUpdate().set(KEY, data);
            saved.made = data;
         }
         call(data, "init");
         if (player && data instanceof com.fs.starfarer.api.EveryFrameScript) {
            // The host's own SCData stays the fleet's script; this one only has to be found for the battle.
            fleet.removeScript((com.fs.starfarer.api.EveryFrameScript) data);
         }
         int n = 0;
         for (FleetPack.Exec e : execs) {
            if (call(store, "getAptitudeSpec", e.aptitudeId) == null) {
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
            Object o = officerCls.getConstructor(PersonAPI.class, String.class).newInstance(p, e.aptitudeId);
            for (String id : e.skills) {
               if (call(store, "getSkillSpec", id) != null) {
                  call(o, "addSkill", id);
               }
            }
            call(data, "addOfficerToFleet", o);
            call(data, "setOfficerInSlot", e.slot, o);
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
                  if (call(s.made, "getOfficerInSlot", i) != null) {
                     call(s.made, "setOfficerInSlot", i, null);
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
