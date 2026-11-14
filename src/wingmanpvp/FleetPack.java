package wingmanpvp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import wingman.net.In;
import wingman.net.Msg;
import wingman.net.Out;
import wingman.net.Msg.W;

public final class FleetPack {
   public static final int MAX_SHIPS = 60;
   public static final int MAX_LIST = 120;
   public static final int MAX_MODULES = 12;
   public static final int MAX_DEPTH = 2;
   public static final int MAX_SKILLS = 120;
   public String owner = "";
   public List<FleetPack.Ship> ships = new ArrayList<>();

   public float points() {
      float var1 = 0.0F;

      for (FleetPack.Ship var3 : this.ships) {
         var1 += var3.points;
      }

      return var1;
   }

   public FleetPack.Ship find(String var1) {
      for (FleetPack.Ship var3 : this.ships) {
         if (var3.memberId.equals(var1)) {
            return var3;
         }
      }

      return null;
   }

   public FleetPack only(List<String> var1) {
      FleetPack var2 = new FleetPack();
      var2.owner = this.owner;

      for (String var4 : var1) {
         FleetPack.Ship var5 = this.find(var4);
         if (var5 != null && !var2.ships.contains(var5)) {
            var2.ships.add(var5);
         }
      }

      return var2;
   }

   public byte[] encode() {
      W var1 = new W();

      try {
         Out var2 = var1.out;
         var2.writeUTF(cap(this.owner, 40));
         int var3 = Math.min(this.ships.size(), 60);
         var2.writeShort(var3);

         for (int var4 = 0; var4 < var3; var4++) {
            writeShip(var2, this.ships.get(var4));
         }
      } catch (IOException var5) {
         throw new IllegalStateException(var5);
      }

      return var1.done();
   }

   public static FleetPack decode(byte[] var0) throws IOException {
      In var1 = Msg.reader(var0);
      FleetPack var2 = new FleetPack();
      var2.owner = var1.readUTF();
      int var3 = var1.readShort() & '\uffff';
      if (var3 > 60) {
         throw new IOException("too many ships");
      } else {
         for (int var4 = 0; var4 < var3; var4++) {
            var2.ships.add(readShip(var1));
         }

         return var2;
      }
   }

   static void writeShip(Out var0, FleetPack.Ship var1) throws IOException {
      var0.writeUTF(cap(var1.memberId, 100));
      var0.writeUTF(cap(var1.shipName, 100));
      var0.writeFloat(var1.points);
      var0.writeFloat(var1.cr);
      var0.writeFloat(var1.hull);
      var0.writeBoolean(var1.flagship);
      writeLoadout(var0, var1.loadout, 0);
      var0.writeBoolean(var1.officer != null);
      if (var1.officer != null) {
         writeOfficer(var0, var1.officer);
      }
   }

   static FleetPack.Ship readShip(In var0) throws IOException {
      FleetPack.Ship var1 = new FleetPack.Ship();
      var1.memberId = var0.readUTF();
      var1.shipName = var0.readUTF();
      var1.points = var0.readFloat();
      var1.cr = var0.readFloat();
      var1.hull = var0.readFloat();
      if (bad(var1.points) || var1.points < 0.0F || var1.points > 10000.0F) {
         throw new IOException("impossible points");
      } else if (bad(var1.cr) || var1.cr < 0.0F || var1.cr > 1.5F) {
         throw new IOException("impossible CR");
      } else if (!bad(var1.hull) && !(var1.hull < 0.0F) && !(var1.hull > 1.01F)) {
         var1.flagship = var0.readBoolean();
         var1.loadout = readLoadout(var0, 0);
         if (var0.readBoolean()) {
            var1.officer = readOfficer(var0);
         }

         return var1;
      } else {
         throw new IOException("impossible hull");
      }
   }

   static void writeLoadout(Out var0, FleetPack.Loadout var1, int var2) throws IOException {
      var0.writeUTF(cap(var1.hullId, 100));
      var0.writeUTF(cap(var1.displayName, 100));
      var0.writeShort(var1.vents);
      var0.writeShort(var1.caps);
      writeMap(var0, var1.weapons);
      writeList(var0, var1.wings);
      writeList(var0, var1.hullMods);
      writeList(var0, var1.sMods);
      writeList(var0, var1.permaMods);
      writeList(var0, var1.sModdedBuiltIns);
      int var3 = Math.min(var1.groups.size(), 20);
      var0.writeByte(var3);

      for (int var4 = 0; var4 < var3; var4++) {
         FleetPack.Group var5 = var1.groups.get(var4);
         var0.writeBoolean(var5.alternating);
         var0.writeBoolean(var5.autofire);
         writeList(var0, var5.slots);
      }

      int var8 = var2 >= 2 ? 0 : Math.min(var1.modules.size(), 12);
      var0.writeByte(var8);
      int var9 = 0;

      for (Entry var7 : var1.modules.entrySet()) {
         if (var9++ >= var8) {
            break;
         }

         var0.writeUTF(cap((String)var7.getKey(), 100));
         writeLoadout(var0, (FleetPack.Loadout)var7.getValue(), var2 + 1);
      }
   }

   static FleetPack.Loadout readLoadout(In var0, int var1) throws IOException {
      FleetPack.Loadout var2 = new FleetPack.Loadout();
      var2.hullId = var0.readUTF();
      var2.displayName = var0.readUTF();
      var2.vents = var0.readShort() & '\uffff';
      var2.caps = var0.readShort() & '\uffff';
      if (var2.vents <= 1000 && var2.caps <= 1000) {
         var2.weapons = readMap(var0);
         var2.wings = readList(var0);
         var2.hullMods = readList(var0);
         var2.sMods = readList(var0);
         var2.permaMods = readList(var0);
         var2.sModdedBuiltIns = readList(var0);
         int var3 = var0.readByte() & 255;
         if (var3 > 20) {
            throw new IOException("too many weapon groups");
         } else {
            for (int var4 = 0; var4 < var3; var4++) {
               FleetPack.Group var5 = new FleetPack.Group();
               var5.alternating = var0.readBoolean();
               var5.autofire = var0.readBoolean();
               var5.slots = readList(var0);
               var2.groups.add(var5);
            }

            int var7 = var0.readByte() & 255;
            if (var7 <= 12 && (var7 <= 0 || var1 < 2)) {
               for (int var8 = 0; var8 < var7; var8++) {
                  String var6 = var0.readUTF();
                  var2.modules.put(var6, readLoadout(var0, var1 + 1));
               }

               return var2;
            } else {
               throw new IOException("too many modules");
            }
         }
      } else {
         throw new IOException("impossible vents or capacitors");
      }
   }

   static void writeOfficer(Out var0, FleetPack.Officer var1) throws IOException {
      for (String var5 : new String[]{var1.first, var1.last, var1.portrait, var1.personality, var1.aiCore}) {
         var0.writeUTF(cap(var5, 200));
      }

      var0.writeShort(var1.level);
      int var6 = Math.min(Math.min(var1.skillIds.size(), var1.skillLevels.size()), 120);
      var0.writeShort(var6);

      for (int var7 = 0; var7 < var6; var7++) {
         var0.writeUTF(cap(var1.skillIds.get(var7), 100));
         var0.writeFloat(var1.skillLevels.get(var7));
      }
   }

   static FleetPack.Officer readOfficer(In var0) throws IOException {
      FleetPack.Officer var1 = new FleetPack.Officer();
      var1.first = var0.readUTF();
      var1.last = var0.readUTF();
      var1.portrait = var0.readUTF();
      var1.personality = var0.readUTF();
      var1.aiCore = var0.readUTF();
      var1.level = var0.readShort();
      if (var1.level >= 0 && var1.level <= 100) {
         int var2 = var0.readShort() & '\uffff';
         if (var2 > 120) {
            throw new IOException("too many skills");
         } else {
            for (int var3 = 0; var3 < var2; var3++) {
               String var4 = var0.readUTF();
               float var5 = var0.readFloat();
               if (bad(var5) || var5 < 0.0F || var5 > 10.0F) {
                  throw new IOException("impossible skill level");
               }

               var1.skillIds.add(var4);
               var1.skillLevels.add(var5);
            }

            return var1;
         }
      } else {
         throw new IOException("impossible officer level");
      }
   }

   private static void writeList(Out var0, List<String> var1) throws IOException {
      int var2 = Math.min(var1.size(), 120);
      var0.writeShort(var2);

      for (int var3 = 0; var3 < var2; var3++) {
         var0.writeUTF(cap((String)var1.get(var3), 100));
      }
   }

   private static List<String> readList(In var0) throws IOException {
      int var1 = var0.readShort() & '\uffff';
      if (var1 > 120) {
         throw new IOException("list too long");
      } else {
         ArrayList var2 = new ArrayList(var1);

         for (int var3 = 0; var3 < var1; var3++) {
            var2.add(var0.readUTF());
         }

         return var2;
      }
   }

   private static void writeMap(Out var0, Map<String, String> var1) throws IOException {
      int var2 = Math.min(var1.size(), 120);
      var0.writeShort(var2);
      int var3 = 0;

      for (Entry var5 : var1.entrySet()) {
         if (var3++ >= var2) {
            break;
         }

         var0.writeUTF(cap((String)var5.getKey(), 100));
         var0.writeUTF(cap((String)var5.getValue(), 100));
      }
   }

   private static Map<String, String> readMap(In var0) throws IOException {
      int var1 = var0.readShort() & '\uffff';
      if (var1 > 120) {
         throw new IOException("map too long");
      } else {
         LinkedHashMap var2 = new LinkedHashMap();

         for (int var3 = 0; var3 < var1; var3++) {
            var2.put(var0.readUTF(), var0.readUTF());
         }

         return var2;
      }
   }

   static String cap(String var0, int var1) {
      return var0 == null ? "" : (var0.length() > var1 ? var0.substring(0, var1) : var0);
   }

   static boolean bad(float var0) {
      return Float.isNaN(var0) || Float.isInfinite(var0);
   }

   public static final class Group {
      public boolean alternating;
      public boolean autofire;
      public List<String> slots = new ArrayList<>();
   }

   public static final class Loadout {
      public String hullId = "";
      public String displayName = "";
      public int vents;
      public int caps;
      public Map<String, String> weapons = new LinkedHashMap<>();
      public List<String> wings = new ArrayList<>();
      public List<String> hullMods = new ArrayList<>();
      public List<String> sMods = new ArrayList<>();
      public List<String> permaMods = new ArrayList<>();
      public List<String> sModdedBuiltIns = new ArrayList<>();
      public List<FleetPack.Group> groups = new ArrayList<>();
      public Map<String, FleetPack.Loadout> modules = new LinkedHashMap<>();
   }

   public static final class Officer {
      public String first = "";
      public String last = "";
      public String portrait = "";
      public String personality = "steady";
      public String aiCore = "";
      public int level = 1;
      public List<String> skillIds = new ArrayList<>();
      public List<Float> skillLevels = new ArrayList<>();
   }

   public static final class Ship {
      public String memberId = "";
      public String shipName = "";
      public float points;
      public float cr = 0.7F;
      public float hull = 1.0F;
      public boolean flagship;
      public FleetPack.Loadout loadout = new FleetPack.Loadout();
      public FleetPack.Officer officer;
   }
}
