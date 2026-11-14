package wingman.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * PvP support for add-ons (Wingman PvP): which side each player is on and who the captains are. The host's game
 * keeps everyone's side (0 or 1); a joiner on side 1 sees the battle the other way round (view() swaps the two sides
 * of what the host sends), so their own side is always 0 where they are. Off (clear) outside a PvP match.
 *
 * (Recovered from the compiled 0.9.3-beta pvp-test1 build of 28 Oct 2026, whose source was lost: the code is exactly
 * that build's, but its comments and local names were not kept.)
 */
public final class Pvp {
   private static volatile boolean active;
   private static volatile Map<String, Integer> sides = Collections.emptyMap();
   private static volatile Set<String> captains = Collections.emptySet();
   private static volatile int localSide;
   private static volatile boolean localCaptain;

   private Pvp() {
   }

   public static synchronized void host(Map<String, Integer> var0, Set<String> var1) {
      HashMap var2 = new HashMap();

      for (Entry var4 : var0.entrySet()) {
         int var5 = var4.getValue() == null ? 0 : (Integer)var4.getValue();
         if (var5 != 0 && var5 != 1) {
            throw new IllegalArgumentException("side must be 0 or 1");
         }

         var2.put(key((String)var4.getKey()), var5);
      }

      HashSet var6 = new HashSet();

      for (String var8 : var1) {
         var6.add(key(var8));
      }

      sides = var2;
      captains = var6;
      localSide = 0;
      localCaptain = var6.contains(key(WingmanAPI.localName()));
      active = true;
   }

   public static synchronized void joiner(int var0, boolean var1) {
      if (var0 != 0 && var0 != 1) {
         throw new IllegalArgumentException("side must be 0 or 1");
      } else {
         sides = Collections.emptyMap();
         captains = Collections.emptySet();
         localSide = var0;
         localCaptain = var1;
         active = true;
      }
   }

   public static synchronized void setCaptain(String var0, String var1) {
      HashSet var2 = new HashSet<>(captains);
      if (var0 != null) {
         var2.remove(key(var0));
      }

      if (var1 != null) {
         var2.add(key(var1));
      }

      captains = var2;
      localCaptain = var2.contains(key(WingmanAPI.localName()));
   }

   public static synchronized void clear() {
      active = false;
      sides = Collections.emptyMap();
      captains = Collections.emptySet();
      localSide = 0;
      localCaptain = false;
   }

   public static boolean active() {
      return active;
   }

   public static int sideOf(String var0) {
      if (active && var0 != null) {
         Integer var1 = sides.get(key(var0));
         return var1 == null ? 0 : var1;
      } else {
         return 0;
      }
   }

   public static boolean isCaptain(String var0) {
      return active && var0 != null && captains.contains(key(var0));
   }

   public static int localSide() {
      return active ? localSide : 0;
   }

   public static boolean localCaptain() {
      return active ? localCaptain : WingmanAPI.isHosting();
   }

   public static int view(int var0) {
      if (active && localSide != 0) {
         return var0 == 0 ? 1 : (var0 == 1 ? 0 : var0);
      } else {
         return var0;
      }
   }

   private static String key(String var0) {
      return var0 == null ? "" : var0.trim().toLowerCase();
   }
}
