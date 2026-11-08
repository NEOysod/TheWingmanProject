package wingman;

import com.fs.starfarer.api.Global;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import wingman.net.RefitRequest;

/**
 * Friend refit, joiner side: up to 3 saved refits per ship, kept in a small file of the game's common folder
 * (wingman_saved_refits), to load again later, here or at another market (items then come from anywhere).
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
final class SavedRefits {
   static final String FILE = "wingman_saved_refits";
   static final int SLOTS = 3;
   static final int MAX_SHIPS = 60;
   private static Map<String, String> lines;

   private SavedRefits() {
   }

   private static Map<String, String> lines() {
      if (lines != null) {
         return lines;
      } else {
         lines = new LinkedHashMap<>();

         try {
            if (Global.getSettings().fileExistsInCommon("wingman_saved_refits")) {
               String var0 = Global.getSettings().readTextFileFromCommon("wingman_saved_refits");

               for (String var4 : var0 == null ? new String[0] : var0.split("\n")) {
                  int var5 = var4.indexOf(61);
                  if (var5 > 0 && var5 <= 220) {
                     lines.put(var4.substring(0, var5).trim(), var4.substring(var5 + 1).trim());
                  }
               }
            }
         } catch (Throwable var6) {
            Global.getLogger(SavedRefits.class).warn("Wingman: saved refits could not be read; starting without them", var6);
         }

         return lines;
      }
   }

   static SavedRefits.Saved get(String var0, int var1) {
      String var2 = lines().get(var0 + "|" + var1);
      if (var2 == null) {
         return null;
      } else {
         try {
            int var3 = var2.indexOf(124);
            long var4 = Long.parseLong(var2.substring(0, var3));
            byte[] var6 = Drafts.unhex(var2.substring(var3 + 1));
            if (var6 != null && var6.length <= 65536) {
               RefitRequest var7 = RefitRequest.decode(var6);
               return var0.equals(var7.memberId) ? new SavedRefits.Saved(var7, var4) : null;
            } else {
               return null;
            }
         } catch (Throwable var8) {
            return null;
         }
      }
   }

   static int count(String var0) {
      int var1 = 0;

      for (int var2 = 1; var2 <= 3; var2++) {
         if (lines().containsKey(var0 + "|" + var2)) {
            var1++;
         }
      }

      return var1;
   }

   static int freeSlot(String var0) {
      for (int var1 = 1; var1 <= 3; var1++) {
         if (!lines().containsKey(var0 + "|" + var1)) {
            return var1;
         }
      }

      return 0;
   }

   static void put(String var0, int var1, RefitRequest var2) {
      if (var1 >= 1 && var1 <= 3 && var2 != null) {
         byte[] var3;
         try {
            var3 = var2.encode();
         } catch (Throwable var9) {
            return;
         }

         String var4 = var0 + "|" + var1;
         lines().remove(var4);
         lines().put(var4, System.currentTimeMillis() + "|" + Drafts.hex(var3));
         ArrayList var5 = new ArrayList();

         for (String var7 : lines().keySet()) {
            String var8 = var7.substring(0, var7.lastIndexOf(124));
            if (!var5.contains(var8)) {
               var5.add(var8);
            }
         }

         while (var5.size() > 60) {
            String var10 = (String)var5.remove(0);

            for (int var11 = 1; var11 <= 3; var11++) {
               lines().remove(var10 + "|" + var11);
            }
         }

         write();
      }
   }

   static void delete(String var0, int var1) {
      if (lines().remove(var0 + "|" + var1) != null) {
         write();
      }
   }

   private static void write() {
      try {
         StringBuilder var0 = new StringBuilder();

         for (Entry var2 : lines().entrySet()) {
            var0.append(((String)var2.getKey()).replace('=', '_').replace('\n', '_')).append('=').append((String)var2.getValue()).append('\n');
         }

         Global.getSettings().writeTextFileToCommon("wingman_saved_refits", var0.toString());
      } catch (Throwable var3) {
         Global.getLogger(SavedRefits.class).warn("Wingman: saved refits could not be written; they last until the game is closed", var3);
      }
   }

   static boolean same(RefitRequest var0, RefitRequest var1) {
      try {
         return Arrays.equals(var0.encode(), var1.encode());
      } catch (Throwable var3) {
         return false;
      }
   }

   static final class Saved {
      final RefitRequest request;
      final long at;

      Saved(RefitRequest var1, long var2) {
         this.request = var1;
         this.at = var2;
      }
   }
}
