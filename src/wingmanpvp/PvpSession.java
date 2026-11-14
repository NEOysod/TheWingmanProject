package wingmanpvp;

import com.fs.starfarer.api.Global;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wingman.api.Pvp;
import wingman.api.WingmanAPI;
import wingman.api.WingmanAPI.Listener;

public final class PvpSession {
   private static PvpSession current;
   public final PvpSession.Role role;
   public PvpSession.Stage stage = PvpSession.Stage.LOBBY;
   public LobbyHost host;
   public LobbyState view;
   public List<LobbyState.ShipInfo> myFleet = new ArrayList<>();
   public FleetPack myPack;
   public final List<String> log = new ArrayList<>();
   public int version;
   public String removed;
   public final Set<Integer> atTitle = new HashSet<>();
   public long toTitleSince;
   public PvpBattle.Match match;
   public BattleProto.Result result;
   public BattleProto.BattleState battleState;
   public final List<String> feed = new ArrayList<>();
   private long lastHello;
   private int myId = -1;
   private boolean helloAnswered;
   private final Listener lobbyListener = new Listener() {
      public void onMessage(String var1, int var2, byte[] var3) {
         PvpSession.this.onLobbyMessage(var1, var2, var3);
      }
   };
   private final Listener battleListener = new Listener() {
      public void onMessage(String var1, int var2, byte[] var3) {
         PvpSession.this.onBattleMessage(var1, var2, var3);
      }
   };
   private final Listener joinedListener = new Listener() {
      public void onMessage(String var1, int var2, byte[] var3) {
         PvpSession.this.onJoined(var1, var2);
      }
   };
   private final Listener leftListener = new Listener() {
      public void onMessage(String var1, int var2, byte[] var3) {
         PvpSession.this.onLeft(var1, var2);
      }
   };
   private long lastStateSent;

   public static PvpSession get() {
      return current;
   }

   public static boolean active() {
      return current != null;
   }

   private PvpSession(PvpSession.Role var1) {
      this.role = var1;
   }

   public static PvpSession host(List<LobbyState.ShipInfo> var0, FleetPack var1, float var2, List<String> var3) {
      end(null);
      WingmanAPI.setPlayersAtLeast(8);
      if (!WingmanAPI.openHost()) {
         WingmanAPI.setPlayersAtLeast(0);
         var3.add("Wingman could not open the host session. Check that hosting is on in Wingman's settings and that the port is free (see starsector.log).");
         return null;
      } else {
         PvpSession var4 = new PvpSession(PvpSession.Role.HOST);
         var4.myFleet = var0;
         var4.myPack = var1;
         var4.host = new LobbyHost(WingmanAPI.localName(), var0, var2);
         var4.view = var4.host.state();
         var4.listen();
         current = var4;
         var4.note("Hosting. Friends join from a bar: Multiplayer PvP Battles, then Join, with your address.");
         return var4;
      }
   }

   public static PvpSession join(String var0, String var1, List<LobbyState.ShipInfo> var2, FleetPack var3) {
      end(null);
      PvpSession var4 = new PvpSession(PvpSession.Role.JOINER);
      var4.myFleet = var2;
      var4.myPack = var3;
      var4.listen();
      current = var4;
      if (!"connected".equals(WingmanAPI.joinState())) {
         WingmanAPI.join(var0, null, var1);
      }

      return var4;
   }

   public static void end(String var0) {
      PvpSession var1 = current;
      current = null;
      Pvp.clear();
      if (var1 != null) {
         try {
            if (var1.role == PvpSession.Role.HOST) {
               WingmanAPI.send("wingman_pvp:battle", BattleProto.simple((byte)23));

               for (LobbyState.Player var3 : var1.view.players) {
                  if (var3.id != 0) {
                     WingmanAPI.sendTo(var3.id, "wingman_pvp:lobby", Proto.text((byte)23, "The host closed the match."));
                  }

                  WingmanAPI.reserveShip(var3.name, null);
               }

               WingmanAPI.setPlayersAtLeast(0);
            } else if (var1.view != null && var1.stage == PvpSession.Stage.LOBBY) {
               WingmanAPI.leave();
            }
         } catch (Throwable var4) {
            Global.getLogger(PvpSession.class).warn("Wingman PvP: leaving the match", var4);
         }

         var1.unlisten();
         if (var0 != null) {
            Global.getLogger(PvpSession.class).info("Wingman PvP: match ended: " + var0);
         }
      }
   }

   private void listen() {
      WingmanAPI.listen("wingman_pvp:lobby", this.lobbyListener);
      WingmanAPI.listen("wingman_pvp:battle", this.battleListener);
      if (this.role == PvpSession.Role.HOST) {
         WingmanAPI.listen("wingman:joined", this.joinedListener);
         WingmanAPI.listen("wingman:left", this.leftListener);
      }
   }

   private void unlisten() {
      WingmanAPI.unlisten("wingman_pvp:lobby", this.lobbyListener);
      WingmanAPI.unlisten("wingman_pvp:battle", this.battleListener);
      WingmanAPI.unlisten("wingman:joined", this.joinedListener);
      WingmanAPI.unlisten("wingman:left", this.leftListener);
   }

   public void advance() {
      for (String var2 : WingmanAPI.pump()) {
         this.note(var2);
      }

      long var3 = System.currentTimeMillis();
      if (this.role == PvpSession.Role.JOINER && !this.helloAnswered && "connected".equals(WingmanAPI.joinState()) && var3 - this.lastHello > 2000L) {
         this.lastHello = var3;
         WingmanAPI.send("wingman_pvp:lobby", Proto.hello(this.myFleet));
      }

      if (this.role == PvpSession.Role.HOST) {
         this.hostAdvance(var3);
      }
   }

   private void hostAdvance(long var1) {
      for (String var4 : this.host.takeNotes()) {
         this.note(var4);
      }

      if (var1 - this.lastStateSent > 3000L && this.stage == PvpSession.Stage.LOBBY) {
         this.broadcast();
      }
   }

   public void broadcast() {
      if (this.role == PvpSession.Role.HOST) {
         this.lastStateSent = System.currentTimeMillis();
         WingmanAPI.send("wingman_pvp:lobby", Proto.state(this.host.state()));
         this.changed();
      }
   }

   public void hostDid(boolean var1) {
      for (String var3 : this.host.takeNotes()) {
         this.note(var3);
      }

      if (var1) {
         this.broadcast();
      } else {
         this.changed();
      }
   }

   public void hostStart() {
      String var1 = this.host.whyNotStart();
      if (var1 != null) {
         this.note("Can't start: " + var1);
      } else {
         LobbyState.Player var2 = this.host.state().player(0);
         FleetPack var6 = null; // (only a captain brings a fleet)
         if (var2.captain) {
            ArrayList var3 = new ArrayList();

            for (LobbyState.ShipInfo var5 : var2.picks) {
               var3.add(var5.memberId);
            }

            var6 = this.myPack.only(var3);
            var6.owner = var2.name;
         }

         this.host.start(var6);
         if (this.host.state().phase == LobbyState.Phase.STARTING) {
            this.stage = PvpSession.Stage.STARTING;
            WingmanAPI.send("wingman_pvp:lobby", Proto.empty((byte)21));
         }

         this.broadcast();
         this.checkFleets();
      }
   }

   private void checkFleets() {
      if (this.role == PvpSession.Role.HOST && this.stage == PvpSession.Stage.STARTING) {
         if (this.host.state().phase != LobbyState.Phase.STARTING) {
            this.stage = PvpSession.Stage.LOBBY;
            this.broadcast();
         } else if (this.host.allFleetsIn()) {
            this.stage = PvpSession.Stage.TO_TITLE;
            this.toTitleSince = System.currentTimeMillis();
            this.atTitle.clear();
            this.host.setPhase(LobbyState.Phase.BATTLE);
            this.broadcast();
            WingmanAPI.send("wingman_pvp:lobby", Proto.empty((byte)22));
            this.note("Every fleet is in. Waiting for the others to reach their title screen...");
         }
      }
   }

   public boolean readyForBattle() {
      if (this.role == PvpSession.Role.HOST && this.stage == PvpSession.Stage.TO_TITLE) {
         int var1 = this.host.state().players.size() - 1;
         return this.atTitle.size() >= var1 || System.currentTimeMillis() - this.toTitleSince > 90000L;
      } else {
         return false;
      }
   }

   public void battleStarted(PvpBattle.Match var1) {
      this.match = var1;
      this.stage = PvpSession.Stage.BATTLE;
      this.result = null;
   }

   private void onLobbyMessage(String var1, int var2, byte[] var3) {
      if (current == this) {
         if (this.role == PvpSession.Role.HOST) {
            Proto.Message var9 = Proto.read(var3);
            boolean var5 = this.host.onMessage(var2, var1, var3);
            if (var9 != null && var9.type == 1) {
               if (this.host.state().player(var2) != null) {
                  WingmanAPI.sendTo(var2, "wingman_pvp:lobby", Proto.you(var2));
               } else {
                  WingmanAPI.sendTo(var2, "wingman_pvp:lobby", Proto.text((byte)23, "The match is full or has already started."));
               }
            }

            if (var5) {
               this.broadcast();
            } else {
               for (String var7 : this.host.takeNotes()) {
                  this.note(var7);
               }
            }

            this.checkFleets();
         } else if (var2 == 0) {
            Proto.Message var4 = Proto.read(var3);
            if (var4 == null) {
               this.note("The host runs another version of Wingman PvP.");
            } else {
               try {
                  switch (var4.type) {
                     case 20:
                        this.view = Proto.readState(var4.body);
                        if (this.stage == PvpSession.Stage.STARTING && this.view.phase == LobbyState.Phase.LOBBY) {
                           this.stage = PvpSession.Stage.LOBBY;
                        }

                        this.changed();
                        break;
                     case 21:
                        this.sendFleet();
                        break;
                     case 22:
                        this.goToTitle();
                        break;
                     case 23:
                        this.removed = var4.body.readUTF();
                        this.note(this.removed);
                        this.changed();
                     case 24:
                     default:
                        break;
                     case 25:
                        this.myId = var4.body.readInt();
                        this.helloAnswered = true;
                  }
               } catch (IOException var8) {
                  this.note("A damaged message from the host was ignored.");
               }
            }
         }
      }
   }

   private void sendFleet() {
      LobbyState.Player var1 = this.me();
      if (var1 != null && !var1.captain) {
         // only captains' fleets are used: this player flies the ship they picked from their captain's
         this.stage = PvpSession.Stage.STARTING;
         this.note("Your captain's fleet is used; you fly the ship you picked from it.");
         this.changed();
      } else if (var1 != null) {
         ArrayList var2 = new ArrayList();

         for (LobbyState.ShipInfo var4 : var1.picks) {
            var2.add(var4.memberId);
         }

         FleetPack var6 = this.myPack.only(var2);

         for (byte[] var5 : Proto.fleetParts(var6.encode())) {
            WingmanAPI.send("wingman_pvp:lobby", var5);
         }

         this.stage = PvpSession.Stage.STARTING;
         this.note("Your fleet was sent to the host.");
         this.changed();
      }
   }

   private void goToTitle() {
      LobbyState.Player var1 = this.me();
      if (var1 != null) {
         int var2 = this.view.player(0) == null ? 0 : this.view.player(0).team;
         Pvp.joiner(var1.team == var2 ? 0 : 1, var1.captain);
         this.stage = PvpSession.Stage.TO_TITLE;
         this.result = null;
         this.changed();
      }
   }

   private void onBattleMessage(String var1, int var2, byte[] var3) {
      if (current == this) {
         if (this.role != PvpSession.Role.JOINER || var2 == 0) {
            Proto.Message var4 = Proto.read(var3);
            if (var4 != null) {
               try {
                  if (this.role == PvpSession.Role.HOST) {
                     if (var4.type == 4) {
                        this.atTitle.add(var2);
                        this.changed();
                        return;
                     }

                     PvpBattlePlugin.request(var1, var4.type);
                     return;
                  }

                  switch (var4.type) {
                     case 20:
                        this.battleState = BattleProto.readState(var4.body);
                        break;
                     case 21:
                        this.result = BattleProto.readResult(var4.body);
                        this.stage = PvpSession.Stage.RESULTS;
                        break;
                     case 22:
                        this.feed.add(var4.body.readUTF());
                        break;
                     case 23:
                        this.removed = "The host closed the match.";
                        Pvp.clear();
                  }

                  this.changed();
               } catch (IOException var6) {
                  this.note("A damaged message from the host was ignored.");
               }
            }
         }
      }
   }

   private void onJoined(String var1, int var2) {
      if (current == this) {
         PvpBattlePlugin.rejoined(var1);
      }
   }

   private void onLeft(String var1, int var2) {
      if (current == this) {
         if (this.stage != PvpSession.Stage.LOBBY && this.stage != PvpSession.Stage.STARTING) {
            PvpBattlePlugin.dropped(var1);
         } else {
            if (this.host.leave(var2)) {
               this.broadcast();
            }

            if (this.stage == PvpSession.Stage.STARTING && this.host.state().phase == LobbyState.Phase.LOBBY) {
               this.stage = PvpSession.Stage.LOBBY;
            }
         }
      }
   }

   public void request(byte[] var1) {
      if (this.role != PvpSession.Role.HOST) {
         if (!WingmanAPI.send("wingman_pvp:lobby", var1)) {
            this.note("Not connected to the host.");
         }
      }
   }

   public LobbyState.Player me() {
      if (this.view == null) {
         return null;
      } else if (this.role == PvpSession.Role.HOST) {
         return this.view.player(0);
      } else if (this.myId >= 0) {
         return this.view.player(this.myId);
      } else {
         String var1 = myName();

         for (LobbyState.Player var3 : this.view.players) {
            if (var3.id != 0 && var3.name.equalsIgnoreCase(var1)) {
               return var3;
            }
         }

         return null;
      }
   }

   public static String myName() {
      if (WingmanAPI.isHosting()) {
         return WingmanAPI.localName();
      } else {
         String var0 = WingmanAPI.savedJoin()[1];
         return var0 != null && !var0.isEmpty() ? var0 : WingmanAPI.localName();
      }
   }

   public void note(String var1) {
      this.log.add(var1);

      while (this.log.size() > 30) {
         this.log.remove(0);
      }

      this.changed();
   }

   public void changed() {
      this.version++;
   }

   public Map<Integer, FleetPack> packs() {
      return (Map<Integer, FleetPack>)(this.host == null ? new LinkedHashMap<>() : this.host.packs());
   }

   public String[] captainsBySide() {
      String[] var1 = new String[]{"", ""};
      if (this.view == null) {
         return var1;
      } else {
         int var2 = this.view.player(0).team;

         for (LobbyState.Player var4 : this.view.players) {
            if (var4.captain) {
               var1[var4.team == var2 ? 0 : 1] = var4.name;
            }
         }

         return var1;
      }
   }

   /** Each player's ship by name: the member id, in their captain's fleet, of the ship they fly. */
   public Map<String, String> seatsByName() {
      HashMap<String, String> out = new HashMap<String, String>();
      if (this.view != null) {
         for (LobbyState.Player p : this.view.players) {
            if (!p.flagshipId.isEmpty()) {
               out.put(p.name, p.flagshipId);
            }
         }
      }

      return out;
   }

   public Map<String, Integer> sidesByName() {
      HashMap var1 = new HashMap();
      int var2 = this.view.player(0).team;

      for (LobbyState.Player var4 : this.view.players) {
         var1.put(var4.name, var4.team == var2 ? 0 : 1);
      }

      return var1;
   }

   public static enum Role {
      NONE,
      HOST,
      JOINER;
   }

   public static enum Stage {
      LOBBY,
      STARTING,
      TO_TITLE,
      BATTLE,
      RESULTS;
   }
}
