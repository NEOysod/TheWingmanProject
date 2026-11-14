package wingmanpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import wingman.net.In;
import wingman.net.Out;
import wingman.net.Msg.W;

public final class BattleProto {
   public static final String CHANNEL = "wingman_pvp:battle";
   public static final byte PAUSE = 1;
   public static final byte GO_ON = 2;
   public static final byte BACK = 3;
   public static final byte AT_TITLE = 4;
   public static final byte STATE = 20;
   public static final byte RESULT = 21;
   public static final byte LINE = 22;
   public static final byte OVER = 23;

   private BattleProto() {
   }

   static W start(byte var0) {
      return Proto.start(var0);
   }

   public static byte[] simple(byte var0) {
      return Proto.empty(var0);
   }

   public static byte[] line(String var0) {
      W var1 = start((byte)22);

      try {
         var1.out.writeUTF(FleetPack.cap(var0, 300));
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static byte[] state(BattleProto.BattleState var0) {
      W var1 = start((byte)20);

      try {
         Out var2 = var1.out;
         var2.writeByte(var0.pausedBy);
         var2.writeFloat(var0.countdown);
         var2.writeFloat(var0.pausedFor);

         for (int var3 = 0; var3 < 2; var3++) {
            var2.writeFloat(var0.charges[var3]);
            var2.writeBoolean(var0.voted[var3]);
            var2.writeUTF(FleetPack.cap(var0.captains[var3], 40));
         }

         int var6 = Math.min(var0.dropped.size(), 8);
         var2.writeByte(var6);

         for (int var4 = 0; var4 < var6; var4++) {
            var2.writeUTF(FleetPack.cap(var0.dropped.get(var4), 40));
         }
      } catch (IOException var5) {
         throw new IllegalStateException(var5);
      }

      return var1.done();
   }

   public static BattleProto.BattleState readState(In var0) throws IOException {
      BattleProto.BattleState var1 = new BattleProto.BattleState();
      var1.pausedBy = var0.readByte();
      if (var1.pausedBy >= -1 && var1.pausedBy <= 2) {
         var1.countdown = var0.readFloat();
         var1.pausedFor = var0.readFloat();
         if (!FleetPack.bad(var1.countdown) && !FleetPack.bad(var1.pausedFor)) {
            for (int var2 = 0; var2 < 2; var2++) {
               var1.charges[var2] = var0.readFloat();
               if (FleetPack.bad(var1.charges[var2])) {
                  throw new IOException("bad charges");
               }

               var1.voted[var2] = var0.readBoolean();
               var1.captains[var2] = var0.readUTF();
            }

            int var4 = var0.readByte() & 255;
            if (var4 > 8) {
               throw new IOException("too many dropped");
            } else {
               for (int var3 = 0; var3 < var4; var3++) {
                  var1.dropped.add(var0.readUTF());
               }

               return var1;
            }
         } else {
            throw new IOException("bad times");
         }
      } else {
         throw new IOException("bad pause");
      }
   }

   public static byte[] result(BattleProto.Result var0) {
      W var1 = start((byte)21);

      try {
         Out var2 = var1.out;
         var2.writeByte(var0.winner);
         var2.writeFloat(var0.seconds);
         var2.writeUTF(FleetPack.cap(var0.teamNames[0], 40));
         var2.writeUTF(FleetPack.cap(var0.teamNames[1], 40));
         int var3 = Math.min(var0.players.size(), 8);
         var2.writeByte(var3);

         for (int var4 = 0; var4 < var3; var4++) {
            BattleProto.PlayerResult var5 = var0.players.get(var4);
            var2.writeUTF(FleetPack.cap(var5.name, 40));
            var2.writeByte(var5.side);
            var2.writeBoolean(var5.captain);
            var2.writeFloat(var5.pointsBrought);
            var2.writeFloat(var5.pointsLost);
            writeNames(var2, var5.shipsLost);
            writeNames(var2, var5.shipsLeft);
         }
      } catch (IOException var6) {
         throw new IllegalStateException(var6);
      }

      return var1.done();
   }

   public static BattleProto.Result readResult(In var0) throws IOException {
      BattleProto.Result var1 = new BattleProto.Result();
      var1.winner = var0.readByte();
      if (var1.winner >= -1 && var1.winner <= 1) {
         var1.seconds = var0.readFloat();
         var1.teamNames[0] = var0.readUTF();
         var1.teamNames[1] = var0.readUTF();
         int var2 = var0.readByte() & 255;
         if (var2 > 8) {
            throw new IOException("too many players");
         } else {
            for (int var3 = 0; var3 < var2; var3++) {
               BattleProto.PlayerResult var4 = new BattleProto.PlayerResult();
               var4.name = var0.readUTF();
               var4.side = var0.readByte();
               if (var4.side != 0 && var4.side != 1) {
                  throw new IOException("bad side");
               }

               var4.captain = var0.readBoolean();
               var4.pointsBrought = var0.readFloat();
               var4.pointsLost = var0.readFloat();
               if (FleetPack.bad(var4.pointsBrought) || FleetPack.bad(var4.pointsLost)) {
                  throw new IOException("bad points");
               }

               var4.shipsLost = readNames(var0);
               var4.shipsLeft = readNames(var0);
               var1.players.add(var4);
            }

            return var1;
         }
      } else {
         throw new IOException("bad winner");
      }
   }

   private static void writeNames(Out var0, List<String> var1) throws IOException {
      int var2 = Math.min(var1.size(), 60);
      var0.writeByte(var2);

      for (int var3 = 0; var3 < var2; var3++) {
         var0.writeUTF(FleetPack.cap((String)var1.get(var3), 100));
      }
   }

   private static List<String> readNames(In var0) throws IOException {
      int var1 = var0.readByte() & 255;
      if (var1 > 60) {
         throw new IOException("too many ships");
      } else {
         ArrayList var2 = new ArrayList();

         for (int var3 = 0; var3 < var1; var3++) {
            var2.add(var0.readUTF());
         }

         return var2;
      }
   }

   public static List<String> describe(BattleProto.Result var0, String var1) {
      ArrayList var2 = new ArrayList();
      if (var0.winner < 0) {
         var2.add("No result: the match was left before the end.");
      } else {
         var2.add(var0.teamNames[var0.winner] + " wins." + (var1 == null ? "" : mine(var0, var1)));
      }

      var2.add("Battle time " + clock(var0.seconds) + ".");

      for (int var3 = 0; var3 < 2; var3++) {
         var2.add(var0.teamNames[var3] + ": lost " + LobbyHost.fmt(var0.lost(var3)) + " of " + LobbyHost.fmt(var0.brought(var3)) + " points.");

         for (BattleProto.PlayerResult var5 : var0.players) {
            if (var5.side == var3) {
               StringBuilder var6 = new StringBuilder("  ").append(var5.name).append(var5.captain ? " (captain)" : "").append(": ");
               var6.append(var5.shipsLeft.size()).append(" left, ").append(var5.shipsLost.size()).append(" lost");
               if (!var5.shipsLost.isEmpty()) {
                  var6.append(" (").append(list(var5.shipsLost)).append(")");
               }

               var2.add(var6.toString());
            }
         }
      }

      return var2;
   }

   private static String mine(BattleProto.Result var0, String var1) {
      for (BattleProto.PlayerResult var3 : var0.players) {
         if (var3.name.equalsIgnoreCase(var1)) {
            return var3.side == var0.winner ? " You won!" : " You lost.";
         }
      }

      return "";
   }

   private static String list(List<String> var0) {
      StringBuilder var1 = new StringBuilder();

      for (int var2 = 0; var2 < var0.size() && var2 < 6; var2++) {
         var1.append(var2 == 0 ? "" : ", ").append((String)var0.get(var2));
      }

      if (var0.size() > 6) {
         var1.append(", +").append(var0.size() - 6);
      }

      return var1.toString();
   }

   static String clock(float var0) {
      int var1 = Math.round(var0);
      return var1 / 60 + ":" + (var1 % 60 < 10 ? "0" : "") + var1 % 60;
   }

   public static final class BattleState {
      public int pausedBy = -1;
      public float countdown;
      public float pausedFor;
      public float[] charges = new float[]{0.0F, 0.0F};
      public boolean[] voted = new boolean[]{false, false};
      public String[] captains = new String[]{"", ""};
      public List<String> dropped = new ArrayList<>();

      public boolean paused() {
         return this.pausedBy != -1 || this.countdown > 0.0F;
      }

      static BattleProto.BattleState of(PvpRules var0, String[] var1) {
         BattleProto.BattleState var2 = new BattleProto.BattleState();
         var2.pausedBy = var0.pausedBy();
         var2.countdown = var0.countdown();
         var2.pausedFor = var0.pausedFor();
         var2.charges = new float[]{var0.charges(0), var0.charges(1)};
         var2.voted = new boolean[]{var0.voted(0), var0.voted(1)};
         var2.captains = new String[]{var1[0] == null ? "" : var1[0], var1[1] == null ? "" : var1[1]};
         var2.dropped.addAll(var0.dropped());
         return var2;
      }
   }

   public static final class PlayerResult {
      public String name = "";
      public int side;
      public boolean captain;
      public List<String> shipsLost = new ArrayList<>();
      public List<String> shipsLeft = new ArrayList<>();
      public float pointsBrought;
      public float pointsLost;
   }

   public static final class Result {
      public int winner = -1;
      public float seconds;
      public String[] teamNames = new String[]{"Team A", "Team B"};
      public List<BattleProto.PlayerResult> players = new ArrayList<>();

      public float lost(int var1) {
         float var2 = 0.0F;

         for (BattleProto.PlayerResult var4 : this.players) {
            if (var4.side == var1) {
               var2 += var4.pointsLost;
            }
         }

         return var2;
      }

      public float brought(int var1) {
         float var2 = 0.0F;

         for (BattleProto.PlayerResult var4 : this.players) {
            if (var4.side == var1) {
               var2 += var4.pointsBrought;
            }
         }

         return var2;
      }
   }
}
