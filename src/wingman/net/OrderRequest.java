package wingman.net;

import java.io.IOException;

/**
 * PvP: a captain on a joiner's PC giving their side's fleet an order (Wingman API channel wingman:pvp-orders): an
 * assignment made or cancelled, a ship given to one, a ship's own order, or full assault. The host checks that the
 * sender is that side's captain and gives the order there.
 *
 * (Recovered from the compiled 0.9.3-beta pvp-test1 build of 28 Oct 2026, whose source was lost: the code is exactly
 * that build's, but its comments and local names were not kept.)
 */
public final class OrderRequest {
   public static final String CHANNEL = "wingman:pvp-orders";
   public static final int CREATE = 1;
   public static final int CANCEL = 2;
   public static final int MEMBER = 3;
   public static final int SHIP = 4;
   public static final int FULL_ASSAULT = 5;
   public int kind;
   public Orders.Assignment order = new Orders.Assignment();
   public int ship;
   public String shipOrder = "";
   public boolean on;

   public byte[] encode() {
      Msg.W var1 = new Msg.W();

      try {
         var1.out.writeByte(this.kind);
         var1.out.writeUTF(this.order.type);
         var1.out.writeByte(this.order.targetKind);
         var1.out.writeInt(this.order.targetId);
         var1.out.writeFloat(this.order.x);
         var1.out.writeFloat(this.order.y);
         int var2 = Math.min(this.order.members.size(), 200);
         var1.out.writeShort(var2);

         for (int var3 = 0; var3 < var2; var3++) {
            var1.out.writeInt(this.order.members.get(var3));
         }

         var1.out.writeInt(this.ship);
         var1.out.writeUTF(this.shipOrder);
         var1.out.writeBoolean(this.on);
      } catch (IOException var4) {
         throw new IllegalStateException(var4);
      }

      return var1.done();
   }

   public static OrderRequest decode(byte[] var0) throws IOException {
      In var1 = Msg.reader(var0);
      OrderRequest var2 = new OrderRequest();
      var2.kind = var1.readByte();
      if (var2.kind >= 1 && var2.kind <= 5) {
         var2.order.type = var1.readUTF();
         var2.order.targetKind = var1.readByte();
         if (var2.order.targetKind >= 0 && var2.order.targetKind <= 2) {
            var2.order.targetId = var1.readInt();
            var2.order.x = var1.readFloat();
            var2.order.y = var1.readFloat();
            if (!Snapshot.bad(var2.order.x) && !Snapshot.bad(var2.order.y)) {
               int var3 = var1.readShort() & '\uffff';
               if (var3 > 200) {
                  throw new IOException("too many ships");
               } else {
                  for (int var4 = 0; var4 < var3; var4++) {
                     var2.order.members.add(var1.readInt());
                  }

                  var2.ship = var1.readInt();
                  var2.shipOrder = var1.readUTF();
                  var2.on = var1.readBoolean();
                  return var2;
               }
            } else {
               throw new IOException("impossible point");
            }
         } else {
            throw new IOException("unknown target kind");
         }
      } else {
         throw new IOException("unknown order request");
      }
   }
}
