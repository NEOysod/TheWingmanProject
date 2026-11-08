package wingman.net;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Wish lists on the wire: a joiner's change to their list (Msg.WISH: items added or taken off, or the list cleared),
 * and, in List, what the host sends back (Msg.WISH_LIST: the player's list and what is set aside for them).
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
public final class WishMessage {
   public static final int ADD = 1;
   public static final int REMOVE = 2;
   public static final int CLEAR = 3;
   public static final int MAX_ITEMS = 60;
   public static final int MAX_COUNT = 99;
   public int op = 1;
   public final Map<String, Integer> items = new LinkedHashMap<>();

   public WishMessage() {
   }

   public WishMessage(int var1) {
      this.op = var1;
   }

   public WishMessage with(String var1, int var2) {
      if (this.items.size() < 60 || this.items.containsKey(var1)) {
         Integer var3 = this.items.get(var1);
         this.items.put(var1, Math.min(99, (var3 == null ? 0 : var3) + var2));
      }

      return this;
   }

   public byte[] encode() {
      Msg.W var1 = new Msg.W();

      try {
         var1.out.writeByte(this.op);
         writeItems(var1.out, this.items);
      } catch (IOException var3) {
         throw new IllegalStateException(var3);
      }

      return var1.done();
   }

   public static WishMessage decode(byte[] var0) throws IOException {
      In var1 = Msg.reader(var0);
      WishMessage var2 = new WishMessage(var1.readByte());
      if (var2.op >= 1 && var2.op <= 3) {
         readItems(var1, var2.items);
         return var2;
      } else {
         throw new IOException("unknown wish list change");
      }
   }

   public static boolean validKey(String var0) {
      return var0 != null && var0.length() > 2 && var0.length() <= 102 && (var0.startsWith("w:") || var0.startsWith("f:"));
   }

   private static void writeItems(Out var0, Map<String, Integer> var1) throws IOException {
      int var2 = Math.min(var1.size(), 60);
      var0.writeVarInt(var2);
      int var3 = 0;

      for (Entry var5 : var1.entrySet()) {
         if (var3++ >= var2) {
            break;
         }

         var0.writeUTF(((String)var5.getKey()).length() > 102 ? ((String)var5.getKey()).substring(0, 102) : (String)var5.getKey());
         var0.writeVarInt(Math.max(0, Math.min(100000, (Integer)var5.getValue())));
      }
   }

   private static void readItems(In var0, Map<String, Integer> var1) throws IOException {
      int var2 = var0.readVarInt();
      if (var2 >= 0 && var2 <= 60) {
         for (int var3 = 0; var3 < var2; var3++) {
            String var4 = var0.readUTF();
            int var5 = var0.readVarInt();
            if (!validKey(var4)) {
               throw new IOException("not an item");
            }

            if (var5 < 0 || var5 > 100000) {
               throw new IOException("impossible count");
            }

            var1.put(var4, var5);
         }
      } else {
         throw new IOException("too many wish list items");
      }
   }

   public static final class List {
      public final Map<String, Integer> wanted = new LinkedHashMap<>();
      public final Map<String, Integer> setAside = new LinkedHashMap<>();

      public byte[] encode() {
         Msg.W var1 = new Msg.W();

         try {
            WishMessage.writeItems(var1.out, this.wanted);
            WishMessage.writeItems(var1.out, this.setAside);
         } catch (IOException var3) {
            throw new IllegalStateException(var3);
         }

         return var1.done();
      }

      public static WishMessage.List decode(byte[] var0) throws IOException {
         In var1 = Msg.reader(var0);
         WishMessage.List var2 = new WishMessage.List();
         WishMessage.readItems(var1, var2.wanted);
         WishMessage.readItems(var1, var2.setAside);
         return var2;
      }
   }
}
