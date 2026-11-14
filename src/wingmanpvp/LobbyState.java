package wingmanpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import wingman.net.In;
import wingman.net.Msg;
import wingman.net.Out;
import wingman.net.Msg.W;

public final class LobbyState {
   public static final int HOST_ID = 0;
   public static final int TEAM_A = 0;
   public static final int TEAM_B = 1;
   public static final int MAX_TEAM = 4;
   public static final int MAX_CHAT = 40;
   public static final int MAX_CHAT_LINE = 200;
   public static final int MAX_RULES = 200;
   public LobbyState.Phase phase = LobbyState.Phase.LOBBY;
   public float pointsLimit = 100.0F;
   public String rules = "";
   public List<LobbyState.Player> players = new ArrayList<>();
   public List<String> chat = new ArrayList<>();

   public LobbyState.Player player(int var1) {
      for (LobbyState.Player var3 : this.players) {
         if (var3.id == var1) {
            return var3;
         }
      }

      return null;
   }

   public List<LobbyState.Player> team(int var1) {
      ArrayList var2 = new ArrayList();

      for (LobbyState.Player var4 : this.players) {
         if (var4.team == var1) {
            var2.add(var4);
         }
      }

      return var2;
   }

   public LobbyState.Player captain(int var1) {
      for (LobbyState.Player var3 : this.players) {
         if (var3.team == var1 && var3.captain) {
            return var3;
         }
      }

      return null;
   }

   public float teamPoints(int var1) {
      float var2 = 0.0F;

      for (LobbyState.Player var4 : this.team(var1)) {
         var2 += var4.points();
      }

      return var2;
   }

   public byte[] encode() {
      W var1 = new W();

      try {
         Out var2 = var1.out;
         var2.writeByte(this.phase.ordinal());
         var2.writeFloat(this.pointsLimit);
         var2.writeUTF(FleetPack.cap(this.rules, 200));
         int var3 = Math.min(this.players.size(), 8);
         var2.writeByte(var3);

         for (int var4 = 0; var4 < var3; var4++) {
            LobbyState.Player var5 = this.players.get(var4);
            var2.writeInt(var5.id);
            var2.writeUTF(FleetPack.cap(var5.name, 40));
            var2.writeByte(var5.team);
            var2.writeBoolean(var5.captain);
            var2.writeBoolean(var5.ready);
            var2.writeBoolean(var5.connected);
            var2.writeShort(Math.max(0, Math.min(var5.ping, 30000)));
            var2.writeUTF(FleetPack.cap(var5.flagshipId, 100));
            writeShips(var2, var5.picks);
         }

         int var7 = Math.min(this.chat.size(), 40);
         var2.writeByte(var7);

         for (int var8 = this.chat.size() - var7; var8 < this.chat.size(); var8++) {
            var2.writeUTF(FleetPack.cap(this.chat.get(var8), 250));
         }
      } catch (IOException var6) {
         throw new IllegalStateException(var6);
      }

      return var1.done();
   }

   public static LobbyState decode(byte[] var0) throws IOException {
      return read(Msg.reader(var0));
   }

   static LobbyState read(In var0) throws IOException {
      LobbyState var1 = new LobbyState();
      int var2 = var0.readByte() & 255;
      if (var2 >= LobbyState.Phase.values().length) {
         throw new IOException("unknown phase");
      } else {
         var1.phase = LobbyState.Phase.values()[var2];
         var1.pointsLimit = var0.readFloat();
         if (!FleetPack.bad(var1.pointsLimit) && !(var1.pointsLimit < 0.0F) && !(var1.pointsLimit > 100000.0F)) {
            var1.rules = var0.readUTF();
            int var3 = var0.readByte() & 255;
            if (var3 > 8) {
               throw new IOException("too many players");
            } else {
               for (int var4 = 0; var4 < var3; var4++) {
                  LobbyState.Player var5 = new LobbyState.Player();
                  var5.id = var0.readInt();
                  var5.name = var0.readUTF();
                  var5.team = var0.readByte() & 255;
                  if (var5.team != 0 && var5.team != 1) {
                     throw new IOException("unknown team");
                  }

                  var5.captain = var0.readBoolean();
                  var5.ready = var0.readBoolean();
                  var5.connected = var0.readBoolean();
                  var5.ping = var0.readShort() & '\uffff';
                  var5.flagshipId = var0.readUTF();
                  var5.picks = readShips(var0);
                  var1.players.add(var5);
               }

               int var6 = var0.readByte() & 255;
               if (var6 > 40) {
                  throw new IOException("chat too long");
               } else {
                  for (int var7 = 0; var7 < var6; var7++) {
                     var1.chat.add(var0.readUTF());
                  }

                  return var1;
               }
            }
         } else {
            throw new IOException("impossible limit");
         }
      }
   }

   static void writeShips(Out var0, List<LobbyState.ShipInfo> var1) throws IOException {
      int var2 = Math.min(var1.size(), 60);
      var0.writeShort(var2);

      for (int var3 = 0; var3 < var2; var3++) {
         LobbyState.ShipInfo var4 = (LobbyState.ShipInfo)var1.get(var3);
         var0.writeUTF(FleetPack.cap(var4.memberId, 100));
         var0.writeUTF(FleetPack.cap(var4.hullId, 100));
         var0.writeUTF(FleetPack.cap(var4.hullName, 100));
         var0.writeUTF(FleetPack.cap(var4.shipName, 100));
         var0.writeUTF(FleetPack.cap(var4.officer, 100));
         var0.writeFloat(var4.points);
         var0.writeBoolean(var4.flagship);
      }
   }

   static List<LobbyState.ShipInfo> readShips(In var0) throws IOException {
      int var1 = var0.readShort() & '\uffff';
      if (var1 > 60) {
         throw new IOException("too many ships");
      } else {
         ArrayList var2 = new ArrayList(var1);

         for (int var3 = 0; var3 < var1; var3++) {
            LobbyState.ShipInfo var4 = new LobbyState.ShipInfo();
            var4.memberId = var0.readUTF();
            var4.hullId = var0.readUTF();
            var4.hullName = var0.readUTF();
            var4.shipName = var0.readUTF();
            var4.officer = var0.readUTF();
            var4.points = var0.readFloat();
            if (FleetPack.bad(var4.points) || var4.points < 0.0F || var4.points > 10000.0F) {
               throw new IOException("impossible points");
            }

            var4.flagship = var0.readBoolean();
            var2.add(var4);
         }

         return var2;
      }
   }

   public static enum Phase {
      LOBBY,
      STARTING,
      BATTLE,
      RESULTS;
   }

   public static final class Player {
      public int id;
      public String name = "";
      public int team;
      public boolean captain;
      public boolean ready;
      public boolean connected = true;
      public int ping;
      public List<LobbyState.ShipInfo> picks = new ArrayList<>();
      public String flagshipId = "";

      public float points() {
         float var1 = 0.0F;

         for (LobbyState.ShipInfo var3 : this.picks) {
            var1 += var3.points;
         }

         return var1;
      }
   }

   public static final class ShipInfo {
      public String memberId = "";
      public String hullId = "";
      public String hullName = "";
      public String shipName = "";
      public String officer = "";
      public float points;
      public boolean flagship;

      public ShipInfo() {
      }

      public ShipInfo(String var1, String var2, String var3, String var4, float var5, String var6, boolean var7) {
         this.memberId = var1;
         this.hullId = var2;
         this.hullName = var3;
         this.shipName = var4;
         this.points = var5;
         this.officer = var6;
         this.flagship = var7;
      }
   }
}
