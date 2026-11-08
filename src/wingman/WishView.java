package wingman;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import wingman.net.WishMessage;

/**
 * Wish lists, joiner side: our own list and what is set aside for us, as the host last sent them, and the changes
 * we make (sent to the host as Msg.WISH by the title plugin).
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
final class WishView {
   static final Map<String, Integer> wanted = new LinkedHashMap<>();
   static final Map<String, Integer> setAside = new LinkedHashMap<>();
   static boolean heard;
   private static final List<byte[]> outgoing = new ArrayList<>();

   private WishView() {
   }

   static void onList(byte[] var0) {
      try {
         WishMessage.List var1 = WishMessage.List.decode(var0);
         wanted.clear();
         wanted.putAll(var1.wanted);
         setAside.clear();
         setAside.putAll(var1.setAside);
         heard = true;
      } catch (IOException var2) {
      }
   }

   static void add(Map<String, Integer> var0) {
      if (!var0.isEmpty()) {
         WishMessage var1 = new WishMessage(1);

         for (Entry var3 : var0.entrySet()) {
            var1.with((String)var3.getKey(), (Integer)var3.getValue());
         }

         queue(var1.encode());

         for (Entry var6 : var0.entrySet()) {
            Integer var4 = wanted.get(var6.getKey());
            wanted.put((String)var6.getKey(), (var4 == null ? 0 : var4) + (Integer)var6.getValue());
         }
      }
   }

   static void remove(String var0) {
      Integer var1 = wanted.remove(var0);
      if (var1 != null) {
         queue(new WishMessage(2).with(var0, var1).encode());
      }
   }

   static void clear() {
      wanted.clear();
      queue(new WishMessage(3).encode());
   }

   private static void queue(byte[] var0) {
      if (outgoing.size() < 20) {
         outgoing.add(var0);
      }
   }

   static List<byte[]> takeOutgoing() {
      ArrayList var0 = new ArrayList<>(outgoing);
      outgoing.clear();
      return var0;
   }

   static int wantedCount() {
      int var0 = 0;

      for (int var2 : wanted.values()) {
         var0 += var2;
      }

      return var0;
   }

   static void forget() {
      wanted.clear();
      setAside.clear();
      outgoing.clear();
      heard = false;
   }
}
