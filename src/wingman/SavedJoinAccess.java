package wingman;

import wingman.net.ModList;

/** For add-ons: Wingman's saved join details (address, name, password), its address parsing and the mod list. */
public final class SavedJoinAccess {
   private SavedJoinAccess() {
   }

   public static SavedJoinAccess.Values load() {
      SavedJoin var0 = SavedJoin.load();
      SavedJoinAccess.Values var1 = new SavedJoinAccess.Values();
      var1.address = var0.address;
      var1.name = var0.name;
      var1.password = var0.password;
      return var1;
   }

   public static void save(String var0, String var1, String var2) {
      SavedJoin var3 = new SavedJoin();
      var3.address = var0;
      var3.name = var1;
      var3.password = var2;
      var3.save();
   }

   public static String hostOf(String var0) {
      return SavedJoin.hostOf(var0);
   }

   public static int portOf(String var0, int var1) {
      return SavedJoin.portOf(var0, var1);
   }

   public static ModList mods() {
      return Mods.enabled();
   }

   public static String gameVersion() {
      return Mods.gameVersion();
   }

   public static final class Values {
      public String address = "";
      public String name = "";
      public String password = "";
   }
}
