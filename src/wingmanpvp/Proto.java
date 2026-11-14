package wingmanpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import wingman.net.In;
import wingman.net.Msg;
import wingman.net.Out;
import wingman.net.Msg.W;

public final class Proto {
   public static final String CHANNEL = "wingman_pvp:lobby";
   public static final int VERSION = 3; // 3: fleets carry Second in Command's executive officers; 2: 2: only captains' fleets (a player's pick is a ship of their captain's)
   public static final byte HELLO = 1;
   public static final byte TEAM = 2;
   public static final byte PICKS = 3;
   public static final byte READY = 4;
   public static final byte CHAT = 5;
   public static final byte FLEET = 6;
   public static final byte STATE = 20;
   public static final byte SEND_FLEET = 21;
   public static final byte GO = 22;
   public static final byte BYE = 23;
   public static final byte YOU = 25;
   public static final int PART_BYTES = 49152;
   public static final int MAX_PARTS = 16;

   private Proto() {
   }

   public static Proto.Message read(byte[] var0) {
      if (var0 != null && var0.length >= 2) {
         try {
            In var1 = Msg.reader(var0);
            Proto.Message var2 = new Proto.Message();
            var2.type = var1.readByte();
            if ((var1.readByte() & 255) != 1) {
               return null;
            } else {
               var2.body = var1;
               return var2;
            }
         } catch (IOException var3) {
            return null;
         }
      } else {
         return null;
      }
   }

   static W start(byte var0) {
      W var1 = new W();

      try {
         var1.out.writeByte(var0);
         var1.out.writeByte(1);
         return var1;
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }
   }

   public static byte[] hello(List<LobbyState.ShipInfo> var0) {
      W var1 = start((byte)1);

      try {
         LobbyState.writeShips(var1.out, var0);
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static List<LobbyState.ShipInfo> readHello(In var0) throws IOException {
      return LobbyState.readShips(var0);
   }

   public static byte[] team(int var0) {
      W var1 = start((byte)2);

      try {
         var1.out.writeByte(var0);
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static byte[] picks(String var0, List<String> var1) {
      W var2 = start((byte)3);

      try {
         Out var3 = var2.out;
         var3.writeUTF(FleetPack.cap(var0, 100));
         int var4 = Math.min(var1.size(), 60);
         var3.writeShort(var4);

         for (int var5 = 0; var5 < var4; var5++) {
            var3.writeUTF(FleetPack.cap((String)var1.get(var5), 100));
         }
      } catch (IOException var6) {
         throw new IllegalStateException(var6);
      }

      return var2.done();
   }

   public static List<String> readPicks(In var0) throws IOException {
      ArrayList var1 = new ArrayList();
      var1.add(var0.readUTF());
      int var2 = var0.readShort() & '\uffff';
      if (var2 > 60) {
         throw new IOException("too many picks");
      } else {
         for (int var3 = 0; var3 < var2; var3++) {
            var1.add(var0.readUTF());
         }

         return var1;
      }
   }

   public static byte[] flag(byte var0, boolean var1) {
      W var2 = start(var0);

      try {
         var2.out.writeBoolean(var1);
      } catch (IOException var4) {
         throw new IllegalStateException(var4);
      }

      return var2.done();
   }

   public static byte[] text(byte var0, String var1) {
      W var2 = start(var0);

      try {
         var2.out.writeUTF(FleetPack.cap(var1, 400));
      } catch (IOException var4) {
         throw new IllegalStateException(var4);
      }

      return var2.done();
   }

   public static byte[] empty(byte var0) {
      return start(var0).done();
   }

   public static byte[] you(int var0) {
      W var1 = start((byte)25);

      try {
         var1.out.writeInt(var0);
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static byte[] state(LobbyState var0) {
      W var1 = start((byte)20);

      try {
         var1.out.write(var0.encode());
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static LobbyState readState(In var0) throws IOException {
      return LobbyState.read(var0);
   }

   public static List<byte[]> fleetParts(byte[] var0) {
      int var1 = Math.max(1, (var0.length + 49152 - 1) / 49152);
      if (var1 > 16) {
         throw new IllegalArgumentException("fleet too big to send");
      } else {
         ArrayList var2 = new ArrayList();

         for (int var3 = 0; var3 < var1; var3++) {
            int var4 = var3 * 49152;
            int var5 = Math.min(49152, var0.length - var4);
            W var6 = start((byte)6);

            try {
               var6.out.writeByte(var3);
               var6.out.writeByte(var1);
               var6.out.writeInt(var5);
               var6.out.write(var0, var4, var5);
            } catch (IOException var8) {
               throw new IllegalStateException(var8);
            }

            var2.add(var6.done());
         }

         return var2;
      }
   }

   public static final class FleetParts {
      private final List<byte[]> parts = new ArrayList<>();
      private int total = -1;

      public byte[] add(In var1) throws IOException {
         int var2 = var1.readByte() & 255;
         int var3 = var1.readByte() & 255;
         int var4 = var1.readInt();
         if (var3 >= 1 && var3 <= 16 && var4 >= 0 && var4 <= 49152 && var4 <= var1.remaining()) {
            if (this.total < 0) {
               this.total = var3;
            }

            if (var3 == this.total && var2 == this.parts.size()) {
               byte[] var5 = new byte[var4];
               var1.readFully(var5);
               this.parts.add(var5);
               if (this.parts.size() < this.total) {
                  return null;
               } else {
                  int var6 = 0;

                  for (byte[] var8 : this.parts) {
                     var6 += var8.length;
                  }

                  byte[] var11 = new byte[var6];
                  int var12 = 0;

                  for (byte[] var10 : this.parts) {
                     System.arraycopy(var10, 0, var11, var12, var10.length);
                     var12 += var10.length;
                  }

                  return var11;
               }
            } else {
               throw new IOException("fleet part out of order");
            }
         } else {
            throw new IOException("bad fleet part");
         }
      }
   }

   public static final class Message {
      public byte type;
      public In body;
   }
}
