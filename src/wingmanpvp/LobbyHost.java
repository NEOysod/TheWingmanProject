package wingmanpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import wingman.net.In;

public final class LobbyHost {
   private final LobbyState state = new LobbyState();
   private final Map<Integer, List<LobbyState.ShipInfo>> fleets = new HashMap<>();
   private final Map<Integer, Proto.FleetParts> incoming = new HashMap<>();
   private final Map<Integer, FleetPack> packs = new LinkedHashMap<>();
   private final List<String> notes = new ArrayList<>();

   public LobbyHost(String var1, List<LobbyState.ShipInfo> var2, float var3) {
      this.state.pointsLimit = Math.max(1.0F, var3);
      this.addPlayer(0, var1, var2);
   }

   public LobbyState state() {
      return this.state;
   }

   public List<String> takeNotes() {
      ArrayList var1 = new ArrayList<>(this.notes);
      this.notes.clear();
      return var1;
   }

   public boolean join(int var1, String var2, List<LobbyState.ShipInfo> var3) {
      if (this.state.player(var1) != null) {
         this.fleets.put(var1, var3);
         return false;
      } else if (this.state.phase != LobbyState.Phase.LOBBY) {
         this.notes.add(var2 + " can't join: the match has started");
         return false;
      } else if (this.state.players.size() >= 8) {
         this.notes.add(var2 + " can't join: both teams are full");
         return false;
      } else {
         this.addPlayer(var1, var2, var3);
         this.say(var2 + " joined.");
         return true;
      }
   }

   private void addPlayer(int var1, String var2, List<LobbyState.ShipInfo> var3) {
      LobbyState.Player var4 = new LobbyState.Player();
      var4.id = var1;
      var4.name = FleetPack.cap(var2, 40);
      int var5 = this.state.team(0).size();
      int var6 = this.state.team(1).size();
      var4.team = var5 <= var6 ? 0 : 1;
      this.fleets.put(var1, (List<LobbyState.ShipInfo>)(var3 == null ? new ArrayList<>() : var3));
      if (this.state.captain(var4.team) == null) {
         this.becomeCaptain(var4);
      } else {
         this.becomeCrew(var4);
      }

      this.state.players.add(var4);
   }

   // Only captains' fleets are used: a captain brings ships from their own fleet (within the points limit, which is
   // the team's); every other player picks the ship they fly from their captain's, one player a ship.

   /** A captain starts with their own flagship brought and flown. */
   private void becomeCaptain(LobbyState.Player p) {
      p.captain = true;
      p.picks = new ArrayList<LobbyState.ShipInfo>();
      p.flagshipId = "";
      p.ready = false;
      List<LobbyState.ShipInfo> own = this.fleets.get(p.id);
      if (own != null) {
         for (LobbyState.ShipInfo s : own) {
            if (s.flagship) {
               p.picks.add(s);
               p.flagshipId = s.memberId;
               break;
            }
         }
      }
   }

   /** Not a captain: brings nothing, flies a ship of the captain's (to be picked). */
   private void becomeCrew(LobbyState.Player p) {
      p.captain = false;
      p.picks = new ArrayList<LobbyState.ShipInfo>();
      p.flagshipId = "";
      p.ready = false;
   }

   /** The captain's ship with this id, or null. */
   private static LobbyState.ShipInfo shipOf(LobbyState.Player captain, String memberId) {
      if (captain != null && memberId != null) {
         for (LobbyState.ShipInfo s : captain.picks) {
            if (s.memberId.equals(memberId)) {
               return s;
            }
         }
      }
      return null;
   }

   /** Teammates whose ship the captain no longer brings (or someone else flies) pick again. */
   private void checkSeats(int team) {
      LobbyState.Player c = this.state.captain(team);
      java.util.Set<String> taken = new java.util.HashSet<String>();
      if (c != null && !c.flagshipId.isEmpty()) {
         taken.add(c.flagshipId);
      }

      for (LobbyState.Player p : this.state.team(team)) {
         if (p.captain || p.flagshipId.isEmpty()) {
            continue;
         }
         if (shipOf(c, p.flagshipId) == null || !taken.add(p.flagshipId)) {
            p.flagshipId = "";
            p.ready = false;
            this.say(p.name + "'s ship isn't in the team's fleet any more: pick another.");
         }
      }
   }

   public boolean leave(int var1) {
      LobbyState.Player var2 = this.state.player(var1);
      if (var2 != null && var1 != 0) {
         this.state.players.remove(var2);
         this.fleets.remove(var1);
         this.incoming.remove(var1);
         this.packs.remove(var1);
         if (var2.captain) {
            this.promote(var2.team);
         }

         this.checkSeats(var2.team);
         this.say(var2.name + " left.");
         if (this.state.phase == LobbyState.Phase.STARTING) {
            this.abortStart(var2.name + " left while the match was starting.");
         }

         return true;
      } else {
         return false;
      }
   }

   private void promote(int var1) {
      if (this.state.captain(var1) == null) {
         List var2 = this.state.team(var1);
         if (!var2.isEmpty()) {
            this.becomeCaptain((LobbyState.Player)var2.get(0));
            this.checkSeats(var1);
            this.say(((LobbyState.Player)var2.get(0)).name + " is now captain of team " + teamName(var1) + ".");
         }
      }
   }

   public boolean onMessage(int var1, String var2, byte[] var3) {
      Proto.Message var4 = Proto.read(var3);
      if (var4 == null) {
         this.notes.add(var2 + " sent a message from another version of Wingman PvP");
         return false;
      } else {
         try {
            if (var4.type == 1) {
               return this.join(var1, var2, Proto.readHello(var4.body));
            } else if (this.state.player(var1) == null) {
               return false;
            } else {
               switch (var4.type) {
                  case 2:
                     return this.setTeam(var1, var4.body.readByte() & 255);
                  case 3:
                     List var5 = Proto.readPicks(var4.body);
                     return this.setPicks(var1, (String)var5.get(0), var5.subList(1, var5.size()));
                  case 4:
                     return this.setReady(var1, var4.body.readBoolean());
                  case 5:
                     return this.chat(var1, var4.body.readUTF());
                  case 6:
                     return this.fleetPart(var1, var4.body);
                  default:
                     return false;
               }
            }
         } catch (IOException var6) {
            this.notes.add(var2 + " sent a damaged message (" + var6.getMessage() + ")");
            return false;
         }
      }
   }

   public boolean setTeam(int var1, int var2) {
      LobbyState.Player var3 = this.state.player(var1);
      if (var3 != null && this.state.phase == LobbyState.Phase.LOBBY) {
         if ((var2 == 0 || var2 == 1) && var3.team != var2) {
            if (this.state.team(var2).size() >= 4) {
               return false;
            } else {
               int var4 = var3.team;
               boolean var5 = var3.captain;
               var3.captain = false;
               var3.team = var2;
               if (this.state.captain(var2) == null) {
                  this.becomeCaptain(var3);
               } else {
                  this.becomeCrew(var3);
               }

               if (var5) {
                  this.promote(var4);
               }

               this.checkSeats(var4);

               this.say(var3.name + " moved to team " + teamName(var2) + ".");
               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public boolean setPicks(int var1, String var2, List<String> var3) {
      LobbyState.Player var4 = this.state.player(var1);
      if (var4 != null && this.state.phase == LobbyState.Phase.LOBBY && !var4.captain) {
         // not a captain: the ship they fly, from the captain's
         LobbyState.Player c = this.state.captain(var4.team);
         if (var2 == null || var2.isEmpty()) {
            var4.flagshipId = "";
            var4.ready = false;
            return true;
         }
         if (shipOf(c, var2) == null) {
            this.notes.add(var4.name + " picked a ship their captain doesn't bring");
            return false;
         }
         if (var2.equals(c.flagshipId)) {
            return false;
         }
         for (LobbyState.Player o : this.state.team(var4.team)) {
            if (o != var4 && var2.equals(o.flagshipId)) {
               return false;
            }
         }
         var4.picks = new ArrayList<LobbyState.ShipInfo>();
         var4.flagshipId = var2;
         var4.ready = false;
         return true;
      } else if (var4 != null && this.state.phase == LobbyState.Phase.LOBBY) {
         List<LobbyState.ShipInfo> var5 = this.fleets.get(var1);
         if (var5 == null) {
            return false;
         } else {
            ArrayList var6 = new ArrayList();
            LobbyState.ShipInfo var7 = null;

            for (LobbyState.ShipInfo var9 : var5) {
               if (var3.contains(var9.memberId)) {
                  if (var9.memberId.equals(var2)) {
                     var7 = var9;
                  } else {
                     var6.add(var9);
                  }
               }
            }

            if (var7 != null) {
               var6.add(0, var7);
            }

            var4.picks = var6;
            var4.flagshipId = var7 == null ? "" : var7.memberId;
            var4.ready = false;
            this.checkSeats(var4.team);
            return true;
         }
      } else {
         return false;
      }
   }

   public boolean setReady(int var1, boolean var2) {
      LobbyState.Player var3 = this.state.player(var1);
      if (var3 != null && this.state.phase == LobbyState.Phase.LOBBY && var3.ready != var2) {
         if (var2) {
            String var4 = this.whyNotReady(var3);
            if (var4 != null) {
               this.notes.add(var3.name + " can't be ready: " + var4);
               return false;
            }
         }

         var3.ready = var2;
         return true;
      } else {
         return false;
      }
   }

   public String whyNotReady(LobbyState.Player var1) {
      if (!var1.captain) {
         return var1.flagshipId.isEmpty() ? "no ship picked from the captain's fleet" : null;
      } else if (var1.flagshipId.isEmpty()) {
         return "no flagship picked";
      } else {
         return var1.points() > this.state.pointsLimit + 0.01F
            ? "over the points limit (" + fmt(var1.points()) + " / " + fmt(this.state.pointsLimit) + ")"
            : null;
      }
   }

   public boolean chat(int var1, String var2) {
      LobbyState.Player var3 = this.state.player(var1);
      if (var3 == null) {
         return false;
      } else {
         var2 = var2 == null ? "" : var2.trim();
         if (var2.isEmpty()) {
            return false;
         } else {
            this.state.chat.add(var3.name + ": " + FleetPack.cap(var2, 200));
            this.trimChat();
            return true;
         }
      }
   }

   public boolean setLimit(float var1) {
      if (this.state.phase == LobbyState.Phase.LOBBY && !FleetPack.bad(var1)) {
         var1 = Math.max(1.0F, Math.min(10000.0F, var1));
         if (var1 == this.state.pointsLimit) {
            return false;
         } else {
            this.state.pointsLimit = var1;

            for (LobbyState.Player var3 : this.state.players) {
               if (var3.ready && this.whyNotReady(var3) != null) {
                  var3.ready = false;
               }
            }

            this.say("Points limit is now " + fmt(var1) + " per team (the captain's fleet).");
            return true;
         }
      } else {
         return false;
      }
   }

   public boolean setRules(String var1) {
      var1 = FleetPack.cap(var1 == null ? "" : var1.trim(), 200);
      if (var1.equals(this.state.rules)) {
         return false;
      } else {
         this.state.rules = var1;

         for (LobbyState.Player var3 : this.state.players) {
            var3.ready = false;
         }

         return true;
      }
   }

   public boolean makeCaptain(int var1) {
      LobbyState.Player var2 = this.state.player(var1);
      if (var2 != null && !var2.captain && this.state.phase == LobbyState.Phase.LOBBY) {
         LobbyState.Player var3 = this.state.captain(var2.team);
         if (var3 != null) {
            this.becomeCrew(var3);
         }

         this.becomeCaptain(var2);
         this.checkSeats(var2.team);
         this.say(var2.name + " is now captain of team " + teamName(var2.team) + ".");
         return true;
      } else {
         return false;
      }
   }

   public boolean moveToOtherTeam(int var1) {
      LobbyState.Player var2 = this.state.player(var1);
      return var2 != null && this.setTeam(var1, 1 - var2.team);
   }

   public boolean kick(int var1) {
      LobbyState.Player var2 = this.state.player(var1);
      if (var2 != null && var1 != 0) {
         boolean var3 = this.leave(var1);
         if (var3) {
            this.say(var2.name + " was removed by the host.");
         }

         return var3;
      } else {
         return false;
      }
   }

   public String whyNotStart() {
      if (this.state.phase != LobbyState.Phase.LOBBY) {
         return "already starting";
      } else if (!this.state.team(0).isEmpty() && !this.state.team(1).isEmpty()) {
         for (LobbyState.Player var2 : this.state.players) {
            String var3 = this.whyNotReady(var2);
            if (var3 != null) {
               return var2.name + ": " + var3;
            }

            if (!var2.ready) {
               return var2.name + " is not ready";
            }
         }

         return null;
      } else {
         return "each team needs a player";
      }
   }

   public boolean start(FleetPack var1) {
      if (this.whyNotStart() != null) {
         return false;
      } else {
         this.state.phase = LobbyState.Phase.STARTING;
         this.incoming.clear();
         this.packs.clear();
         LobbyState.Player h = this.state.player(0);
         String var2 = h != null && h.captain ? this.check(h, var1) : null; // (only captains bring a fleet)
         if (var2 != null) {
            this.abortStart(var2);
            return true;
         } else {
            if (h != null && h.captain) {
               this.packs.put(0, var1);
            }

            this.say("Starting: collecting the captains' fleets...");
            return true;
         }
      }
   }

   private boolean fleetPart(int var1, In var2) throws IOException {
      LobbyState.Player from = this.state.player(var1);
      if (this.state.phase == LobbyState.Phase.STARTING && !this.packs.containsKey(var1) && from != null && from.captain) {
         Proto.FleetParts var3 = this.incoming.get(var1);
         if (var3 == null) {
            var3 = new Proto.FleetParts();
            this.incoming.put(var1, var3);
         }

         byte[] var4 = var3.add(var2);
         if (var4 == null) {
            return false;
         } else {
            this.incoming.remove(var1);
            LobbyState.Player var5 = this.state.player(var1);
            FleetPack var7 = null;

            String var6;
            try {
               var7 = FleetPack.decode(var4);
               var6 = this.check(var5, var7);
            } catch (IOException var9) {
               var6 = var5.name + "'s fleet could not be read (" + var9.getMessage() + ").";
            }

            if (var6 != null) {
               this.abortStart(var6);
               return true;
            } else {
               var7.owner = var5.name;
               this.packs.put(var1, var7);
               return this.allFleetsIn();
            }
         }
      } else {
         return false;
      }
   }

   String check(LobbyState.Player var1, FleetPack var2) {
      if (var1 != null && var2 != null) {
         if (var2.ships.size() != var1.picks.size()) {
            return var1.name + "'s fleet doesn't match their picks.";
         } else {
            for (LobbyState.ShipInfo var4 : var1.picks) {
               FleetPack.Ship var5 = var2.find(var4.memberId);
               if (var5 == null || !var5.loadout.hullId.equals(var4.hullId)) {
                  return var1.name + "'s fleet doesn't match their picks.";
               }
            }

            return var2.points() > this.state.pointsLimit + 0.01F ? var1.name + "'s fleet is over the points limit." : null;
         }
      } else {
         return "a fleet is missing.";
      }
   }

   public boolean allFleetsIn() {
      int captains = 0;
      for (LobbyState.Player p : this.state.players) {
         if (p.captain) {
            captains++;
         }
      }

      return this.state.phase == LobbyState.Phase.STARTING && this.packs.size() == captains;
   }

   public Map<Integer, FleetPack> packs() {
      return this.packs;
   }

   public void abortStart(String var1) {
      this.state.phase = LobbyState.Phase.LOBBY;
      this.incoming.clear();
      this.packs.clear();

      for (LobbyState.Player var3 : this.state.players) {
         var3.ready = false;
      }

      this.say("Start cancelled: " + var1);
   }

   public void setPhase(LobbyState.Phase var1) {
      this.state.phase = var1;
   }

   public void setPing(int var1, int var2) {
      LobbyState.Player var3 = this.state.player(var1);
      if (var3 != null) {
         var3.ping = var2;
      }
   }

   public void say(String var1) {
      this.state.chat.add("* " + var1);
      this.trimChat();
   }

   private void trimChat() {
      while (this.state.chat.size() > 40) {
         this.state.chat.remove(0);
      }
   }

   public static String teamName(int var0) {
      return var0 == 0 ? "A" : "B";
   }

   public static String fmt(float var0) {
      return var0 == (float)Math.round(var0) ? String.valueOf(Math.round(var0)) : String.format(Locale.ROOT, "%.1f", var0);
   }
}
