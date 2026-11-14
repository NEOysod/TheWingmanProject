package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.lwjgl.opengl.GL11;
import wingman.net.RefitData;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;
import wingman.net.Roster;

/**
 * Friend refit, joiner side: the refit screen, drawn as the lobby's third page (LobbyScreen.REFIT) with the
 * lobby's own drawing helpers, made for the mouse. Left: the ship with the new loadout and a ring on each slot
 * (click one to choose it), the totals and anything that stops the refit. Middle: a tile for each weapon slot,
 * fighter bay, vents, capacitors and hull mod, with its picture. Right: for the marked tile, cards of what can go
 * in it (from the host's cargo, storage or a market, filtered by tabs), or buttons for what can be done with it.
 * Bottom: SEND TO HOST, START OVER, BACK. The mouse wheel scrolls the tiles and the cards. Keys still work.
 *
 * What it shows comes from the host (RefitData); the totals and problems are worked out as the host will
 * (RefitPlan), and the host checks everything again before asking its player. See docs/FRIEND_REFIT_DESIGN.md.
 */
final class RefitScreen {
   private final LobbyScreen ui;
   static final int NOTHING = 0;
   static final int CLOSE = 1;
   static final int SEND = 2;
   private RefitRequest req;
   private RefitData data;
   private int rowAt = -1;
   private int optAt;
   private int rowScroll;
   private int cardScroll;
   private boolean typingNote;
   private boolean typingSearch;
   private String search = "";
   private String tab = "";
   private String status = "";
   private Color statusColor = Panel.DIM;
   private long savedAt;
   private boolean savedView;
   private static final int SLOT = 0;
   private static final int BAY = 1;
   private static final int VENTS = 2;
   private static final int CAPS = 3;
   private static final int MOD = 4;
   private static final int ADD_MOD = 5;
   private static final int NOTE = 6;
   private static final int HEAD = 7;
   /** The weapon groups tile (and its editor on the right). */
   private static final int GROUPS = 8;
   /** Weapon groups editor: a weapon's box for a group, a group's fire mode, its autofire; and AUTO-ARRANGE. */
   private static final int H_GCELL = 8, H_GMODE = 9, H_GAUTO = 10, B_ARRANGE = 21;
   private float[] groupArea;
   private int groupScroll;
   private final Set<String> folded = new HashSet<>();
   private boolean availableOnly = true;
   private int shownMax = 20;
   private static final int PAGE = 20;
   private List<RefitScreen.Option> cached;
   private String cachedFor = "";
   private RefitData cachedData;
   private static final int H_ROW = 0;
   private static final int H_CARD = 1;
   private static final int H_TAB = 2;
   private static final int H_RING = 3;
   private static final int H_BUTTON = 4;
   private static final int B_SEND = 0;
   private static final int B_RESET = 1;
   private static final int B_BACK = 2;
   private static final int B_DEST = 3;
   private static final int B_UNDO = 4;
   private static final int B_MINUS5 = 5;
   private static final int B_MINUS = 6;
   private static final int B_PLUS = 7;
   private static final int B_PLUS5 = 8;
   private static final int B_OFF = 9;
   private static final int B_BUILD = 10;
   private static final int B_NOTE = 11;
   private static final int B_CLEAR = 12;
   private static final int B_SEARCH = 13;
   private static final int B_SEARCH_CLEAR = 14;
   private static final int B_MORE = 15;
   private static final int B_WHOLE = 16;
   private static final int B_SAVED = 17;
   private static final int B_SAVE = 18;
   private static final int B_WISH_MISSING = 19;
   private static final int B_SAVED_CLOSE = 20;
   private static final int SLOT_BASE = 100;
   private static final int SAVE_HERE = 0;
   private static final int LOAD = 1;
   private static final int DELETE = 2;
   private static final int WISH = 3;
   private static final int H_WISH_CARD = 5;
   private static final int H_WISH_LINE = 6;
   private static final int H_FOLD = 7;
   private int moreLeft;
   private int maxCardScroll;
   private final List<RefitScreen.Hit> hits = new ArrayList<>();
   private float[] tileArea;
   private float[] cardArea;
   private int tilesShown = 10;
   private static final Color CYAN = LobbyScreen.TITLE;
   private static final Color TILE = new Color(255, 255, 255, 10);
   private static final Color TILE_OVER = new Color(120, 210, 255, 30);
   private static final Color TILE_MARKED = new Color(120, 210, 255, 55);
   private static final Color ICON_BACK = new Color(255, 255, 255, 16);
   private static final Color BAR = new Color(4, 7, 12, 220);

   RefitScreen(LobbyScreen var1) {
      this.ui = var1;
   }

   void setData(RefitData var1) {
      if (var1 == null) {
         this.data = null;
      } else {
         if (this.req == null || !this.req.memberId.equals(var1.memberId)) {
            this.saveDraft();
            RefitRequest var2 = Drafts.get(var1.memberId);
            if (var2 != null) {
               this.reset(var1);
               this.req = var2;
               this.status = "Your saved plan for this ship. Anything not available here is listed on the left.";
               this.statusColor = Panel.DIM;
            } else {
               this.reset(var1);
            }
         }

         this.data = var1;
      }
   }

   void saveDraft() {
      if (this.req != null) {
         Drafts.put(
            this.req,
            this.data != null && this.data.memberId.equals(this.req.memberId) && new RefitPlan(this.data, this.req).isEmpty() && this.req.note.isEmpty()
         );
      }
   }

   private void reset(RefitData var1) {
      this.req = new RefitRequest();
      this.req.memberId = var1.memberId;
      this.req.vents = var1.vents;
      this.req.caps = var1.caps;
      this.rowAt = -1;
      this.optAt = 0;
      this.rowScroll = 0;
      this.cardScroll = 0;
      this.typingNote = false;
      this.tab = "";
   }

   void clear() {
      this.saveDraft();
      this.req = null;
      this.data = null;
      this.status = "";
      this.typingNote = false;
   }

   void onResult(RefitResult var1) {
      switch (var1.status) {
         case 0:
            this.statusColor = Panel.GOOD;
            break;
         case 1:
            this.statusColor = Panel.GOOD;
            if (this.req != null) {
               Drafts.forget(this.req.memberId);
            }

            this.req = null;
            this.data = null;
            break;
         case 2:
            this.statusColor = Panel.WARN;
            break;
         default:
            this.statusColor = Panel.BAD;
      }

      this.status = var1.text;
   }

   RefitRequest request() {
      RefitPlan var1 = this.plan();
      this.req.expectCredits = var1.net();
      this.req.expectStoryPoints = var1.storyPoints;
      this.status = "Sending...";
      this.statusColor = Panel.DIM;
      return this.req;
   }

   private RefitPlan plan() {
      return this.plan(this.req);
   }

   private RefitPlan plan(RefitRequest var1) {
      this.estimateCosts(var1);
      return new RefitPlan(this.data, var1, new FriendRefit.Names(this.data));
   }

   private void estimateCosts(RefitRequest var1) {
      if (this.data != null && var1 != null) {
         for (RefitRequest.Change var3 : var1.changes) {
            if (!var3.item.isEmpty()) {
               String var4 = RefitData.key(var3.wing ? 1 : 0, var3.item);
               if (!this.data.opCosts.containsKey(var4)) {
                  try {
                     float var5 = var3.wing
                        ? Global.getSettings().getFighterWingSpec(var3.item).getOpCost(null)
                        : Global.getSettings().getWeaponSpec(var3.item).getOrdnancePointCost(null);
                     this.data.opCosts.put(var4, Math.round(var5));
                  } catch (Throwable var7) {
                  }

                  if (!this.data.values.containsKey(var4)) {
                     try {
                        this.data
                           .values
                           .put(
                              var4,
                              var3.wing
                                 ? Global.getSettings().getFighterWingSpec(var3.item).getBaseValue()
                                 : Global.getSettings().getWeaponSpec(var3.item).getBaseValue()
                           );
                     } catch (Throwable var6) {
                     }
                  }
               }
            }
         }
      }
   }

   private RefitRequest forHere(RefitRequest var1) {
      RefitRequest var2;
      try {
         var2 = RefitRequest.decode(var1.encode());
      } catch (Throwable var6) {
         return null;
      }

      var2.memberId = this.data.memberId;

      for (RefitRequest.Change var4 : var2.changes) {
         if (!var4.item.isEmpty()) {
            var4.from = "any";
         }

         boolean var5 = "cargo".equals(var4.to)
            || "storage".equals(var4.to) && this.data.hasStorage
            || this.data.market(var4.to) != null && this.data.market(var4.to).usable;
         if (!var5) {
            var4.to = "cargo";
         }
      }

      return var2;
   }

   private void saveTo(int var1) {
      if (this.plan().isEmpty() && this.req.note.isEmpty()) {
         this.status = "Nothing is changed yet: there is nothing to save.";
         this.statusColor = Panel.WARN;
      } else {
         SavedRefits.put(this.data.memberId, var1, this.req);
         this.status = "Saved as refit " + var1 + " of this ship.";
         this.statusColor = Panel.GOOD;
      }
   }

   private void load(int var1) {
      SavedRefits.Saved var2 = SavedRefits.get(this.data.memberId, var1);
      RefitRequest var3 = var2 == null ? null : this.forHere(var2.request);
      if (var3 == null) {
         this.status = "That saved refit can't be read.";
         this.statusColor = Panel.BAD;
      } else {
         this.req = var3;
         this.tidy();
         int var4 = this.plan().wantedCount();
         this.status = "Loaded saved refit "
            + var1
            + "."
            + (
               var4 > 0
                  ? " " + var4 + " item" + (var4 == 1 ? " isn't" : "s aren't") + " to be had here: add " + (var4 == 1 ? "it" : "them") + " to your wish list."
                  : ""
            );
         this.statusColor = var4 > 0 ? Panel.WARN : Panel.GOOD;
         this.savedView = false;
      }
   }

   private void wishFor(RefitPlan var1) {
      if (var1.wanted.isEmpty()) {
         this.status = "Everything in it is to be had here.";
         this.statusColor = Panel.DIM;
      } else {
         WishView.add(var1.wanted);
         int var2 = var1.wantedCount();
         this.status = var2
            + " item"
            + (var2 == 1 ? "" : "s")
            + " added to your wish list. The host is told when they dock where "
            + (var2 == 1 ? "it is" : "they are")
            + " for sale.";
         this.statusColor = Panel.GOOD;
      }
   }

   private ShipHullSpecAPI hull() {
      try {
         return Global.getSettings().getHullSpec(this.data.hullId);
      } catch (Throwable var2) {
         return null;
      }
   }

   private static boolean changeable(WeaponSlotAPI var0) {
      return var0.isWeaponSlot() && !var0.isBuiltIn() && !var0.isDecorative() && !var0.isSystemSlot() && !var0.isHidden() && !var0.isStationModule();
   }

   private List<String> slots() {
      ArrayList var1 = new ArrayList();
      final ShipHullSpecAPI var2 = this.hull();
      if (var2 != null) {
         for (WeaponSlotAPI var4 : var2.getAllWeaponSlotsCopy()) {
            try {
               if (changeable(var4)) {
                  var1.add(var4.getId());
               }
            } catch (Throwable var6) {
            }
         }
      } else {
         var1.addAll(this.data.fitSlots);
      }

      Collections.sort(var1, new Comparator<String>() {
         public int compare(String var1, String var2x) {
            int var3 = RefitScreen.size(var2, var2x) - RefitScreen.size(var2, var1);
            return var3 != 0 ? var3 : var1.compareTo(var2x);
         }
      });
      return var1;
   }

   private static int size(ShipHullSpecAPI var0, String var1) {
      try {
         return var0.getWeaponSlotAPI(var1).getSlotSize().ordinal();
      } catch (Throwable var3) {
         return 0;
      }
   }

   private static String groupOf(List<RefitScreen.Row> var0, int var1) {
      for (int var2 = var1; var2 >= 0; var2--) {
         if (((RefitScreen.Row)var0.get(var2)).kind == 7) {
            return ((RefitScreen.Row)var0.get(var2)).id;
         }
      }

      return "";
   }

   private boolean hidden(List<RefitScreen.Row> var1, int var2) {
      return ((RefitScreen.Row)var1.get(var2)).kind != 7 && this.folded.contains("t:" + groupOf(var1, var2));
   }

   private float foldHead(String var1, String var2, String var3, float var4, float var5, float var6) {
      boolean var7 = this.folded.contains(var1);
      String var8 = (var7 ? "[+]  " : "[-]  ") + var2 + (var7 && var3 != null && !var3.isEmpty() ? "   -   " + var3 : "");
      float var9 = Style.section(var8, var4, var5, var6);
      this.hits.add(new RefitScreen.Hit(var4, var9, var6, var5 - var9, 7, 0, var1));
      return var9;
   }

   private boolean isFolded(String var1) {
      return this.folded.contains(var1);
   }

   private List<RefitScreen.Row> rows() {
      ArrayList var1 = new ArrayList();
      var1.add(new RefitScreen.Row(7, "WEAPONS"));

      for (String var3 : this.slots()) {
         var1.add(new RefitScreen.Row(0, var3));
      }

      if (!this.data.wings.isEmpty()) {
         var1.add(new RefitScreen.Row(7, "FIGHTER BAYS"));

         for (int var4 = 0; var4 < this.data.wings.size(); var4++) {
            var1.add(new RefitScreen.Row(1, String.valueOf(var4)));
         }
      }

      var1.add(new RefitScreen.Row(7, "WEAPON GROUPS"));
      var1.add(new RefitScreen.Row(GROUPS, ""));
      var1.add(new RefitScreen.Row(7, "FLUX"));
      var1.add(new RefitScreen.Row(2, ""));
      var1.add(new RefitScreen.Row(3, ""));
      var1.add(new RefitScreen.Row(7, "HULL MODS"));

      for (String var9 : this.data.sMods) {
         if (!hiddenMod(var9)) {
            var1.add(new RefitScreen.Row(4, var9));
         }
      }

      for (String var10 : this.data.permaMods) {
         if (!hiddenMod(var10)) {
            var1.add(new RefitScreen.Row(4, var10));
         }
      }

      for (String var11 : this.data.hullmods) {
         if (!hiddenMod(var11)) {
            var1.add(new RefitScreen.Row(4, var11));
         }
      }

      for (String var12 : this.req.addMods) {
         var1.add(new RefitScreen.Row(4, var12));
      }

      var1.add(new RefitScreen.Row(5, ""));
      var1.add(new RefitScreen.Row(7, "NOTE"));
      var1.add(new RefitScreen.Row(6, ""));
      return var1;
   }

   private int builtInWings() {
      try {
         ShipHullSpecAPI var1 = this.hull();
         return var1 == null ? 0 : var1.getBuiltInWings().size();
      } catch (Throwable var2) {
         return 0;
      }
   }

   private RefitScreen.Row marked(List<RefitScreen.Row> var1) {
      if (this.rowAt < 0 || this.rowAt >= var1.size() || ((RefitScreen.Row)var1.get(this.rowAt)).kind == 7) {
         this.rowAt = -1;

         for (int var2 = 0; var2 < var1.size(); var2++) {
            if (((RefitScreen.Row)var1.get(var2)).kind != 7) {
               this.rowAt = var2;
               break;
            }
         }
      }

      return this.rowAt < 0 ? null : (RefitScreen.Row)var1.get(this.rowAt);
   }

   private void mark(List<RefitScreen.Row> var1, int var2) {
      if (var2 >= 0 && var2 < var1.size() && ((RefitScreen.Row)var1.get(var2)).kind != 7) {
         this.folded.remove("t:" + groupOf(var1, var2));
         if (var2 != this.rowAt) {
            this.optAt = 0;
            this.cardScroll = 0;
            this.tab = "";
            int var3 = this.rowAt >= 0 && this.rowAt < var1.size() ? ((RefitScreen.Row)var1.get(this.rowAt)).kind : -1;
            if (var3 != ((RefitScreen.Row)var1.get(var2)).kind) {
               this.search = "";
               this.typingSearch = false;
            }
         }

         this.rowAt = var2;
         this.typingNote = ((RefitScreen.Row)var1.get(var2)).kind == 6;
      }
   }

   private List<RefitScreen.Option> options(RefitScreen.Row var1, FriendRefit.Names var2, boolean var3) {
      if (var3 && var1 != null) {
         String var4 = var1.kind
            + "|"
            + var1.id
            + "|"
            + this.search
            + "|"
            + this.tab
            + "|"
            + this.availableOnly
            + "|"
            + this.req.addMods
            + "|"
            + this.req.changes.size();
         if (this.cached == null || this.cachedData != this.data || !var4.equals(this.cachedFor)) {
            if (!var4.equals(this.cachedFor)) {
               this.shownMax = 20;
            }

            this.cached = this.build(var1, var2, true);
            this.cachedFor = var4;
            this.cachedData = this.data;
         }

         return this.cached;
      } else {
         return this.build(var1, var2, var3);
      }
   }

   private List<RefitScreen.Option> build(RefitScreen.Row var1, final FriendRefit.Names var2, boolean var3) {
      List<RefitScreen.Option> var4 = new ArrayList<RefitScreen.Option>();
      if (var1 == null) {
         return var4;
      } else {
         if (var1.kind != 0 && var1.kind != 1) {
            if (var1.kind == 5) {
               List<String> var13 = new ArrayList<String>();
               Iterator var15 = this.data.knownHullmods.iterator();

               label125:
               while (true) {
                  String var19;
                  while (true) {
                     if (!var15.hasNext()) {
                        Collections.sort(var13, new Comparator<String>() {
                           public int compare(String var1, String var2x) {
                              return var2.mod(var1).compareToIgnoreCase(var2.mod(var2x));
                           }
                        });

                        for (String var20 : var13) {
                           var4.add(new RefitScreen.Option(var20, "", 0, 0.0F, true));
                        }
                        break label125;
                     }

                     var19 = (String)var15.next();
                     if (!this.data.hullmods.contains(var19)
                        && !this.data.sMods.contains(var19)
                        && !this.data.permaMods.contains(var19)
                        && !this.req.addMods.contains(var19)) {
                        try {
                           HullModSpecAPI var21 = Global.getSettings().getHullModSpec(var19);
                           if (!var21.isHidden() && !var21.isHiddenEverywhere() && (this.hull() == null || !this.hull().getBuiltInMods().contains(var19))) {
                              break;
                           }
                        } catch (Throwable var11) {
                        }
                     }
                  }

                  var13.add(var19);
               }
            }
         } else {
            if (var1.kind == 1 && Integer.parseInt(var1.id) < this.builtInWings()) {
               return var4;
            }

            var4.add(new RefitScreen.Option(null, "", 0, 0.0F, true));
            WeaponSlotAPI var5 = null;
            if (var1.kind == 0) {
               try {
                  var5 = this.hull().getWeaponSlotAPI(var1.id);
               } catch (Throwable var10) {
               }
            }

            List<RefitScreen.Option> var6 = new ArrayList<RefitScreen.Option>();
            this.addFrom(var6, this.data.cargo, "cargo", null, var1, var5);
            if (this.data.hasStorage) {
               this.addFrom(var6, this.data.storage, "storage", null, var1, var5);
            }

            for (RefitData.Market var8 : this.data.markets) {
               this.addFrom(var6, var8.stock, var8.id, var8, var1, var5);
            }

            Collections.sort(var6, new Comparator<RefitScreen.Option>() {
               public int compare(RefitScreen.Option var1, RefitScreen.Option var2x) {
                  if (var1.usable != var2x.usable) {
                     return var1.usable ? -1 : 1;
                  } else {
                     int var3 = var2.item(var1.key).compareToIgnoreCase(var2.item(var2x.key));
                     return var3 != 0 ? var3 : Float.compare(var1.price, var2x.price);
                  }
               }
            });

            for (RefitScreen.Option var9 : var6) {
               if (!var3 || this.tab.isEmpty() || this.tab.equals(var9.from)) {
                  var4.add(var9);
               }
            }
         }

         if (var3 && !this.availableOnly && this.tab.isEmpty()) {
            this.addWholeGame(var4, var1, var2);
         }

         if (var3 && !this.search.trim().isEmpty()) {
            ArrayList var12 = new ArrayList();

            for (RefitScreen.Option var17 : var4) {
               if (var17.key == null || this.matches(var17, var1, var2)) {
                  var12.add(var17);
               }
            }

            return var12;
         } else {
            return var4;
         }
      }
   }

   private void addWholeGame(List<RefitScreen.Option> var1, RefitScreen.Row var2, FriendRefit.Names var3) {
      HashSet var4 = new HashSet();

      for (RefitScreen.Option var6 : var1) {
         if (var6.key != null) {
            var4.add(var6.key);
         }
      }

      ArrayList var14 = new ArrayList();
      String var15 = "hide_in_codex";

      try {
         if (var2.kind == 0) {
            WeaponSlotAPI var7 = null;

            try {
               var7 = this.hull().getWeaponSlotAPI(var2.id);
            } catch (Throwable var12) {
            }

            for (WeaponSpecAPI var9 : Global.getSettings().getAllWeaponSpecs()) {
               String var10 = RefitData.key(0, var9.getWeaponId());
               if (!var4.contains(var10) && !var9.hasTag(var15) && !var9.getAIHints().contains(AIHints.SYSTEM) && (var7 == null || var7.weaponFits(var9))) {
                  RefitScreen.Option var11 = new RefitScreen.Option(var10, "", 0, 0.0F, false);
                  var11.elsewhere = true;
                  var11.name = var9.getWeaponName();
                  var14.add(var11);
               }
            }
         } else if (var2.kind == 1) {
            for (FighterWingSpecAPI var18 : Global.getSettings().getAllFighterWingSpecs()) {
               String var20 = RefitData.key(1, var18.getId());
               if (!var4.contains(var20) && !var18.hasTag(var15)) {
                  RefitScreen.Option var22 = new RefitScreen.Option(var20, "", 0, 0.0F, false);
                  var22.elsewhere = true;
                  var22.name = var18.getWingName();
                  var14.add(var22);
               }
            }
         } else if (var2.kind == 5) {
            for (HullModSpecAPI var19 : Global.getSettings().getAllHullModSpecs()) {
               String var21 = var19.getId();
               if (!var4.contains(var21)
                  && !var19.isHidden()
                  && !var19.isHiddenEverywhere()
                  && !var19.hasTag(var15)
                  && !var19.hasTag("dmod")
                  && !this.data.hullmods.contains(var21)
                  && !this.data.sMods.contains(var21)
                  && !this.data.permaMods.contains(var21)
                  && (this.hull() == null || !this.hull().getBuiltInMods().contains(var21))) {
                  RefitScreen.Option var23 = new RefitScreen.Option(var21, "", 0, 0.0F, false);
                  var23.elsewhere = true;
                  var23.name = var19.getDisplayName();
                  var14.add(var23);
               }
            }
         }
      } catch (Throwable var13) {
      }

      Collections.sort(var14, new Comparator<RefitScreen.Option>() {
         public int compare(RefitScreen.Option var1, RefitScreen.Option var2) {
            return String.valueOf(var1.name).compareToIgnoreCase(String.valueOf(var2.name));
         }
      });
      var1.addAll(var14);
   }

   private boolean matches(RefitScreen.Option var1, RefitScreen.Row var2, FriendRefit.Names var3) {
      String var4 = (var1.name != null ? var1.name : (var2.kind == 5 ? var3.mod(var1.key) : var3.item(var1.key))) + " " + var1.key;
      var4 = var4.toLowerCase();

      for (String var8 : this.search.toLowerCase().trim().split("\\s+")) {
         if (!var4.contains(var8)) {
            return false;
         }
      }

      return true;
   }

   private void addFrom(List<RefitScreen.Option> var1, List<RefitData.Item> var2, String var3, RefitData.Market var4, RefitScreen.Row var5, WeaponSlotAPI var6) {
      Iterator var7 = var2.iterator();

      while (true) {
         RefitData.Item var8;
         while (true) {
            if (!var7.hasNext()) {
               return;
            }

            var8 = (RefitData.Item)var7.next();
            if (var8.kind == 1 == (var5.kind == 1) && var8.count > 0) {
               if (var6 == null) {
                  break;
               }

               try {
                  WeaponSpecAPI var9 = Global.getSettings().getWeaponSpec(var8.id);
                  if (!var6.weaponFits(var9)) {
                     continue;
                  }
                  break;
               } catch (Throwable var10) {
               }
            }
         }

         boolean var11 = var4 == null || var4.usable && !var4.cantBuy.contains(var8.key());
         var1.add(new RefitScreen.Option(var8.key(), var3, var8.count, var4 == null ? 0.0F : this.data.buyPrice(var4, var8.key()), var11));
      }
   }

   private List<String> sources(RefitScreen.Row var1, FriendRefit.Names var2) {
      LinkedHashSet var3 = new LinkedHashSet();

      for (RefitScreen.Option var5 : this.options(var1, var2, false)) {
         if (var5.key != null && !var5.from.isEmpty()) {
            var3.add(var5.from);
         }
      }

      return new ArrayList<>(var3);
   }

   private RefitRequest.Change changeFor(RefitScreen.Row var1) {
      boolean var2 = var1.kind == 1;
      RefitRequest.Change var3 = this.req.change(var1.id, var2);
      if (var3 == null) {
         var3 = new RefitRequest.Change();
         var3.wing = var2;
         var3.place = var1.id;
         String var4 = var2 ? this.data.wings.get(Integer.parseInt(var1.id)) : this.data.weaponIn(var1.id);
         var3.item = var4 == null ? "" : var4;
         var3.from = "";
         this.req.changes.add(var3);
      }

      return var3;
   }

   private void pick(RefitScreen.Row var1, RefitScreen.Option var2) {
      if (var1.kind == 5 && var2.elsewhere) {
         this.status = var2.name + " is unavailable: the host doesn't know that hull mod.";
         this.statusColor = Panel.WARN;
      } else if (var1.kind == 5) {
         if (var2.key != null && !this.req.addMods.contains(var2.key)) {
            this.req.addMods.add(var2.key);
         }
      } else if (var2.key == null || !var2.elsewhere && var2.usable) {
         RefitRequest.Change var5 = this.changeFor(var1);
         var5.item = var2.key == null ? "" : var2.key.substring(2);
         var5.from = var2.key == null ? "" : var2.from;
         this.tidy();
      } else {
         RefitRequest.Change var3 = this.changeFor(var1);
         var3.item = var2.key.substring(2);
         var3.from = "any";
         this.tidy();
         String var4 = var2.name != null ? var2.name : new FriendRefit.Names(this.data).item(var2.key);
         this.status = var4.replace(" LPC", "") + " isn't to be had here. Save this refit for later, or click WISH on its card to put it on your wish list.";
         this.statusColor = Style.ACCENT;
      }
   }

   private void tidy() {
      RefitPlan var1 = this.plan();

      for (int var2 = this.req.changes.size() - 1; var2 >= 0; var2--) {
         if (var1.sameAsBefore(this.req.changes.get(var2))) {
            this.req.changes.remove(var2);
         }
      }
   }

   private void undo(RefitScreen.Row var1) {
      if (var1 != null) {
         if (var1.kind == 0 || var1.kind == 1) {
            RefitRequest.Change var2 = this.req.change(var1.id, var1.kind == 1);
            if (var2 != null) {
               this.req.changes.remove(var2);
            }
         } else if (var1.kind == 2) {
            this.req.vents = this.data.vents;
         } else if (var1.kind == 3) {
            this.req.caps = this.data.caps;
         } else if (var1.kind == 4) {
            this.req.removeMods.remove(var1.id);
            this.req.addMods.remove(var1.id);
            this.req.buildIn.remove(var1.id);
         } else if (var1.kind == 6) {
            this.req.note = "";
         } else if (var1.kind == GROUPS) {
            this.req.groups = null;
         }
      }
   }

   private boolean changed(RefitScreen.Row var1) {
      if (var1 == null) {
         return false;
      } else if (var1.kind == 0 || var1.kind == 1) {
         return this.req.change(var1.id, var1.kind == 1) != null;
      } else if (var1.kind == 2) {
         return this.req.vents != this.data.vents;
      } else if (var1.kind == 3) {
         return this.req.caps != this.data.caps;
      } else if (var1.kind == 4) {
         return this.req.removeMods.contains(var1.id) || this.req.addMods.contains(var1.id) || this.req.buildIn.contains(var1.id);
      } else if (var1.kind == GROUPS) {
         return this.req.groups != null && this.data.groups != null && !RefitData.sameGroups(this.req.groups, this.data.groups);
      } else {
         return var1.kind == 6 ? !this.req.note.isEmpty() : false;
      }
   }

   private void cycleDestination(RefitScreen.Row var1) {
      if (var1 != null && (var1.kind == 0 || var1.kind == 1)) {
         RefitRequest.Change var2 = this.req.change(var1.id, var1.kind == 1);
         String var3 = var2 == null ? null : this.plan().before(var2);
         if (var2 != null && var3 != null) {
            ArrayList var4 = new ArrayList();
            var4.add("cargo");
            if (this.data.hasStorage) {
               var4.add("storage");
            }

            for (RefitData.Market var6 : this.data.markets) {
               if (var6.usable && !var6.cantSell.contains(var3)) {
                  var4.add(var6.id);
               }
            }

            int var7 = var4.indexOf(var2.to);
            var2.to = (String)var4.get((var7 + 1) % var4.size());
         }
      }
   }

   private void toggleBuildIn(RefitScreen.Row var1) {
      if (var1 != null && var1.kind == 4 && !this.data.sMods.contains(var1.id) && !this.data.permaMods.contains(var1.id)) {
         if (!this.req.buildIn.remove(var1.id)) {
            if (!this.req.removeMods.contains(var1.id)) {
               this.req.buildIn.add(var1.id);
            }
         }
      }
   }

   private void toggleRemove(RefitScreen.Row var1) {
      if (var1 != null && var1.kind == 4) {
         if (this.req.addMods.remove(var1.id)) {
            this.req.buildIn.remove(var1.id);
         } else if (this.data.hullmods.contains(var1.id)) {
            if (!this.req.removeMods.remove(var1.id)) {
               this.req.removeMods.add(var1.id);
               this.req.buildIn.remove(var1.id);
            }
         }
      }
   }

   private void adjust(RefitScreen.Row var1, int var2) {
      if (var1 != null) {
         if (var1.kind == 2) {
            this.req.vents = Math.max(0, Math.min(this.data.maxVents > 0 ? this.data.maxVents : 999, this.req.vents + var2));
         }

         if (var1.kind == 3) {
            this.req.caps = Math.max(0, Math.min(this.data.maxCaps > 0 ? this.data.maxCaps : 999, this.req.caps + var2));
         }
      }
   }

   private boolean docked() {
      return this.data != null && !this.data.market.isEmpty();
   }

   private int trySend() {
      RefitPlan var1 = this.plan();
      this.saveDraft();
      if (var1.isEmpty()) {
         this.status = "Nothing is changed yet.";
         this.statusColor = Panel.WARN;
         return 0;
      } else if (!this.docked()) {
         this.status = "The host isn't docked at a market. Your plan is kept: send it when they are, or SAVE it.";
         this.statusColor = Panel.WARN;
         return 0;
      } else if (!var1.allProblems().isEmpty()) {
         this.status = "Fix what is in red first.";
         this.statusColor = Panel.BAD;
         return 0;
      } else {
         return 2;
      }
   }

   int key(int var1, char var2, boolean var3, boolean var4) {
      if (this.data == null || this.req == null) {
         return var1 == 1 ? 1 : 0;
      } else if (this.typingSearch) {
         if (var1 == 1) {
            if (this.search.isEmpty()) {
               this.typingSearch = false;
            } else {
               this.search = "";
            }
         } else if (var1 == 28 || var1 == 156 || var1 == 15) {
            this.typingSearch = false;
         } else if (var1 == 14) {
            if (!this.search.isEmpty()) {
               this.search = this.search.substring(0, this.search.length() - 1);
            }
         } else if (var2 >= ' ' && var2 < 127 && this.search.length() < 32) {
            this.search = this.search + var2;
         }

         this.optAt = 0;
         this.cardScroll = 0;
         return 0;
      } else if (var3 && var1 == 33) {
         this.typingSearch = true;
         this.typingNote = false;
         return 0;
      } else if (this.typingNote) {
         if (var1 == 1 || var1 == 28 || var1 == 156) {
            this.typingNote = false;
         } else if (var1 == 14) {
            if (!this.req.note.isEmpty()) {
               this.req.note = this.req.note.substring(0, this.req.note.length() - 1);
            }
         } else if (var2 >= ' ' && var2 < 127 && this.req.note.length() < 160) {
            this.req.note = this.req.note + var2;
         }

         return 0;
      } else {
         List var5 = this.rows();
         RefitScreen.Row var6 = this.marked(var5);
         if (var3 && Panel.isEnter(var1)) {
            return this.trySend();
         } else if (var1 == 1) {
            return 1;
         } else if (var1 == 44) {
            if (var4) {
               this.reset(this.data);
            } else {
               this.undo(var6);
            }

            return 0;
         } else if (var1 == 31) {
            this.cycleDestination(var6);
            return 0;
         } else if (var1 == 48) {
            this.toggleBuildIn(var6);
            return 0;
         } else if (var1 == 211) {
            this.toggleRemove(var6);
            return 0;
         } else if (var1 != 200 && var1 != 208) {
            if (var6 != null && (var6.kind == 2 || var6.kind == 3)) {
               if (var1 == 203 || var1 == 205) {
                  this.adjust(var6, (var1 == 205 ? 1 : -1) * (var4 ? 5 : 1));
               }

               return 0;
            } else {
               List var8 = this.options(var6, new FriendRefit.Names(this.data), true);
               if (var8.isEmpty()) {
                  return 0;
               } else {
                  if (var1 == 203) {
                     this.optAt = (this.optAt + var8.size() - 1) % var8.size();
                  } else if (var1 == 205) {
                     this.optAt = (this.optAt + 1) % var8.size();
                  } else if (Panel.isEnter(var1)) {
                     this.pick(var6, (RefitScreen.Option)var8.get(Math.max(0, Math.min(var8.size() - 1, this.optAt))));
                  }

                  return 0;
               }
            }
         } else {
            int var7 = this.rowAt;

            do {
               var7 = (var7 + (var1 == 200 ? var5.size() - 1 : 1)) % var5.size();
            } while ((((RefitScreen.Row)var5.get(var7)).kind == 7 || this.hidden(var5, var7)) && var7 != this.rowAt);

            this.mark(var5, var7);
            return 0;
         }
      }
   }

   int click(float var1, float var2) {
      if (this.data != null && this.req != null) {
         List var3 = this.rows();

         for (int var4 = this.hits.size() - 1; var4 >= 0; var4--) {
            RefitScreen.Hit var5 = this.hits.get(var4);
            if (var5.has(var1, var2)) {
               sound();
               RefitScreen.Row var6 = this.marked(var3);
               switch (var5.type) {
                  case 0:
                     this.mark(var3, var5.index);
                     return 0;
                  case 1:
                     List var14 = this.options(var6, new FriendRefit.Names(this.data), true);
                     if (var5.index >= 0 && var5.index < var14.size()) {
                        this.optAt = var5.index;
                        this.pick(var6, (RefitScreen.Option)var14.get(var5.index));
                     }

                     return 0;
                  case 2:
                     this.tab = var5.id;
                     this.optAt = 0;
                     this.cardScroll = 0;
                     return 0;
                  case 3:
                     for (int var13 = 0; var13 < var3.size(); var13++) {
                        if (((RefitScreen.Row)var3.get(var13)).kind == 0 && ((RefitScreen.Row)var3.get(var13)).id.equals(var5.id)) {
                           this.mark(var3, var13);
                           this.keepVisible(var13);
                        }
                     }

                     return 0;
                  case 4:
                  default:
                     switch (var5.index) {
                        case 0:
                           return this.trySend();
                        case 1:
                           this.reset(this.data);
                           return 0;
                        case 2:
                           return 1;
                        case 3:
                           this.cycleDestination(var6);
                           return 0;
                        case 4:
                           this.undo(var6);
                           return 0;
                        case 5:
                           this.adjust(var6, -5);
                           return 0;
                        case 6:
                           this.adjust(var6, -1);
                           return 0;
                        case 7:
                           this.adjust(var6, 1);
                           return 0;
                        case 8:
                           this.adjust(var6, 5);
                           return 0;
                        case 9:
                           this.toggleRemove(var6);
                           return 0;
                        case 10:
                           this.toggleBuildIn(var6);
                           return 0;
                        case 11:
                           this.typingNote = true;
                           this.typingSearch = false;
                           return 0;
                        case 12:
                           this.req.note = "";
                           return 0;
                        case 13:
                           this.typingSearch = true;
                           this.typingNote = false;
                           return 0;
                        case 14:
                           this.search = "";
                           this.typingSearch = false;
                           this.optAt = 0;
                           this.cardScroll = 0;
                           return 0;
                        case 15:
                           this.shownMax += 20;
                           return 0;
                        case 16:
                           this.availableOnly = !this.availableOnly;
                           this.cardScroll = 0;
                           this.optAt = 0;
                           return 0;
                        case 17:
                           this.savedView = !this.savedView;
                           return 0;
                        case 18:
                           int var12 = SavedRefits.freeSlot(this.data.memberId);
                           if (var12 == 0) {
                              this.savedView = true;
                              this.status = "All 3 saved refits of this ship are taken: pick one to save over.";
                              this.statusColor = Panel.WARN;
                              return 0;
                           }

                           this.saveTo(var12);
                           return 0;
                        case 19:
                           this.wishFor(this.plan());
                           return 0;
                        case 20:
                           this.savedView = false;
                           return 0;
                        case B_ARRANGE:
                           this.autoArrange();
                           return 0;
                        default:
                           if (var5.index >= 100) {
                              int var11 = (var5.index - 100) / 10;
                              int var8 = (var5.index - 100) % 10;
                              SavedRefits.Saved var9 = SavedRefits.get(this.data.memberId, var11);
                              if (var8 == 0) {
                                 this.saveTo(var11);
                              } else if (var8 == 1) {
                                 this.load(var11);
                              } else if (var8 == 2) {
                                 SavedRefits.delete(this.data.memberId, var11);
                                 this.status = "Saved refit " + var11 + " deleted.";
                                 this.statusColor = Panel.DIM;
                              } else if (var8 == 3 && var9 != null) {
                                 RefitRequest var10 = this.forHere(var9.request);
                                 if (var10 != null) {
                                    this.wishFor(this.plan(var10));
                                 }
                              }
                           }

                           return 0;
                     }
                  case 5:
                     LinkedHashMap var7 = new LinkedHashMap();
                     var7.put(var5.id, 1);
                     WishView.add(var7);
                     this.status = new FriendRefit.Names(this.data).item(var5.id).replace(" LPC", "") + " added to your wish list.";
                     this.statusColor = Panel.GOOD;
                     return 0;
                  case 6:
                     WishView.remove(var5.id);
                     this.status = new FriendRefit.Names(this.data).item(var5.id).replace(" LPC", "") + " taken off your wish list.";
                     this.statusColor = Panel.DIM;
                     return 0;
                  case 7:
                     if (!this.folded.remove(var5.id)) {
                        this.folded.add(var5.id);
                     }

                     return 0;
                  case H_GCELL:
                     this.moveToGroup(var5.id, var5.index);
                     return 0;
                  case H_GMODE: {
                     List<RefitData.Group> g = this.editGroups();
                     if (g != null) { g.get(var5.index).alternating = !g.get(var5.index).alternating; this.tidyGroups(); }
                     return 0;
                  }
                  case H_GAUTO: {
                     List<RefitData.Group> g = this.editGroups();
                     if (g != null) { g.get(var5.index).autofire = !g.get(var5.index).autofire; this.tidyGroups(); }
                     return 0;
                  }
               }
            }
         }

         this.typingNote = false;
         this.typingSearch = false;
         return 0;
      } else {
         return 0;
      }
   }

   boolean scroll(float var1, float var2, int var3) {
      int var4 = var3 > 0 ? -1 : 1;
      if (this.groupArea != null && var1 >= this.groupArea[0] && var1 <= this.groupArea[0] + this.groupArea[2] && var2 >= this.groupArea[1] && var2 <= this.groupArea[1] + this.groupArea[3]) {
         this.groupScroll = Math.max(0, this.groupScroll + var4);
         return true;
      } else if (this.tileArea != null
         && var1 >= this.tileArea[0]
         && var1 <= this.tileArea[0] + this.tileArea[2]
         && var2 >= this.tileArea[1]
         && var2 <= this.tileArea[1] + this.tileArea[3]) {
         this.rowScroll = Math.max(0, this.rowScroll + var4);
         return true;
      } else if (this.cardArea != null
         && var1 >= this.cardArea[0]
         && var1 <= this.cardArea[0] + this.cardArea[2]
         && var2 >= this.cardArea[1]
         && var2 <= this.cardArea[1] + this.cardArea[3]) {
         if (var4 > 0 && this.cardScroll >= this.maxCardScroll && this.moreLeft > 0) {
            this.shownMax += 20;
         }

         this.cardScroll = Math.max(0, this.cardScroll + var4);
         return true;
      } else {
         return false;
      }
   }

   private String groupSummary(List<RefitScreen.Row> var1, int var2, RefitPlan var3) {
      int var4 = 0;
      int var5 = 0;
      int var6 = 0;

      for (int var7 = var2 + 1; var7 < var1.size() && ((RefitScreen.Row)var1.get(var7)).kind != 7; var7++) {
         RefitScreen.Row var8 = (RefitScreen.Row)var1.get(var7);
         if (var8.kind != 5) {
            var4++;
            if (this.changed(var8)) {
               var5++;
            }

            if ((var8.kind == 0 || var8.kind == 1) && var3.unavailable.contains((var8.kind == 1 ? "f" : "w") + var8.id)) {
               var6++;
            }
         }
      }

      return "(" + var4 + (var5 > 0 ? ",  " + var5 + " CHANGED" : "") + (var6 > 0 ? ",  " + var6 + " NOT HERE" : "") + ")";
   }

   private void keepVisible(int var1) {
      if (var1 < this.rowScroll) {
         this.rowScroll = var1;
      }

      if (var1 >= this.rowScroll + this.tilesShown) {
         this.rowScroll = var1 - this.tilesShown + 1;
      }
   }

   private static void sound() {
      try {
         Global.getSoundPlayer().playUISound("ui_button_pressed", 1.0F, 1.0F);
      } catch (Throwable var1) {
      }
   }

   void render(LobbyScreen.View var1, float var2, float var3, float var4, float var5) {
      String var6 = this.ui.body;
      this.hits.clear();
      this.tileArea = null;
      this.cardArea = null;
      this.groupArea = null;
      Roster.Member var7 = var1.roster == null ? null : var1.roster.pickOf(var1.me);
      this.setData(var1.refitData);
      String var8 = "REFIT" + (var7 == null ? "" : ":  " + Roster.label(var7).toUpperCase());
      String var9 = this.data == null
         ? ""
         : (this.data.market.isEmpty() ? "host not at a market" : "docked at " + this.data.market)
            + "     host has "
            + grouped((long)Math.round(this.data.credits))
            + " credits, "
            + this.data.storyPoints
            + " story point"
            + (this.data.storyPoints == 1 ? "" : "s");
      float var10 = Style.window(var2, var3 - var5, var4, var5, var8, var9) - 14.0F;
      if (this.data != null && this.req != null && var7 != null && this.data.memberId.equals(var7.id)) {
         FriendRefit.Names var11 = new FriendRefit.Names(this.data);
         this.estimateCosts(this.req);
         RefitPlan var12 = new RefitPlan(this.data, this.req, var11);
         long var13 = System.currentTimeMillis();
         if (var13 - this.savedAt > 3000L) {
            this.savedAt = var13;
            this.saveDraft();
         }

         List var15 = this.rows();
         RefitScreen.Row var16 = this.marked(var15);
         float var17 = 56.0F;
         float var18 = var3 - var5 + var17 + 14.0F;
         float var19 = 28.0F;
         float var20 = var4 - 40.0F - 2.0F * var19;
         float var21 = var20 * 0.31F;
         float var22 = var20 * 0.31F;
         float var23 = var20 - var21 - var22;
         float var24 = var2 + 20.0F;
         float var25 = var24 + var21 + var19;
         float var26 = var25 + var22 + var19;
         LobbyScreen.fill(var25 - var19 / 2.0F, var18, 1.0F, var10 - var18, Style.RULE);
         LobbyScreen.fill(var26 - var19 / 2.0F, var18, 1.0F, var10 - var18, Style.RULE);
         if (!this.docked()) {
            var10 = Style.callout(
                  "The host isn't docked at a market. Plan your refit with what the host carries; it is kept, and you can send it once the host docks. Items from a market are checked again there.",
                  Panel.WARN,
                  var24,
                  var10,
                  var4 - 40.0F
               )
               - 12.0F;
         }

         this.ship(var12, var7, var16, var15, var24, var10, var21, var18);
         this.tiles(var15, var12, var11, var25, Style.section("Loadout", var25, var10, var22) - 4.0F, var22, var18);
         if (this.savedView) {
            this.savedRefits(var26, var10, var23, var18);
         } else {
            this.side(var16, var12, var11, var26, var10, var23, var18);
         }

         LobbyScreen.fill(var2 + 1.0F, var3 - var5 + var17, var4 - 2.0F, 1.0F, Style.RULE);
         this.bottomBar(var12, var2 + 20.0F, var3 - var5 + 12.0F, var4 - 40.0F, var17 - 22.0F);
      } else {
         this.ui.text("Asking the host for your ship and what this market has...", var6, Panel.DIM, var2 + 16.0F, var10, var4 - 32.0F);
         this.button(var2 + var4 - 16.0F - 120.0F, var3 - var5 + 12.0F, 120.0F, 34.0F, "BACK", Panel.DIM, true, 2);
      }
   }

   private float button(float var1, float var2, float var3, float var4, String var5, Color var6, boolean var7, int var8) {
      boolean var9 = var7 && LobbyScreen.inside(var1, var2, var3, var4);
      Style.button(var1, var2, var3, var4, var5, var6, var7, var9);
      if (var7) {
         this.hits.add(new RefitScreen.Hit(var1, var2, var3, var4, 4, var8, ""));
      }

      return var1 + var3;
   }

   static SpriteAPI picture(String var0, boolean var1) {
      if (var0 == null) {
         return null;
      } else {
         try {
            if (var1) {
               return LobbyScreen.sprite(Global.getSettings().getHullModSpec(var0).getSpriteName());
            } else {
               String var2 = var0.substring(2);
               if (var0.startsWith("f:")) {
                  return LobbyScreen.sprite(Global.getSettings().getFighterWingSpec(var2).getVariant().getHullSpec().getSpriteName());
               } else {
                  WeaponSpecAPI var3 = Global.getSettings().getWeaponSpec(var2);
                  SpriteAPI var4 = LobbyScreen.sprite(var3.getTurretSpriteName());
                  return var4 != null ? var4 : LobbyScreen.sprite(var3.getHardpointSpriteName());
               }
            }
         } catch (Throwable var5) {
            return null;
         }
      }
   }

   static void iconBox(SpriteAPI var0, float var1, float var2, float var3, boolean var4) {
      LobbyScreen.fill(var1, var2, var3, var3, ICON_BACK);
      if (var0 != null) {
         var0.setAlphaMult(var4 ? 0.4F : 1.0F);
         LobbyScreen.icon(var0, var1 + 2.0F, var2 + 2.0F, var3 - 4.0F);
         var0.setAlphaMult(1.0F);
      }
   }

   private void ship(RefitPlan var1, Roster.Member var2, RefitScreen.Row var3, List<RefitScreen.Row> var4, float var5, float var6, float var7, float var8) {
      String var9 = this.ui.body;
      var6 = this.foldHead("l:ship", "Ship", this.data.hullId, var5, var6, var7) - 4.0F;
      float var10 = var6;
      if (!this.isFolded("l:ship")) {
         var10 = this.shipPicture(var2, var3, var5, var6, var7, var8);
      }

      var10 = this.stats(var5, var10, var7) - 8.0F;

      var10 = this.foldHead(
            "l:limits", "Limits", "OP " + var1.opUsed + " / " + this.data.opMax + (var1.opUsed > this.data.opMax ? "  OVER" : ""), var5, var10, var7
         )
         - 2.0F;
      if (!this.isFolded("l:limits")) {
         var10 = this.limit("Ordnance points", var1.opUsed, this.data.opMax, var5, var10, var7) - 4.0F;
         var10 = this.limit("Flux vents", this.req.vents, this.data.maxVents, var5, var10, var7) - 4.0F;
         var10 = this.limit("Flux capacitors", this.req.caps, this.data.maxCaps, var5, var10, var7) - 4.0F;
         var10 = this.limit("S-mods", var1.sMods, this.data.maxSMods, var5, var10, var7) - 12.0F;
      } else {
         var10 -= 6.0F;
      }

      int var11 = var1.net();
      String var12 = var1.buy == 0 && var1.sell == 0
         ? "none"
         : (var1.buy > 0 ? "pay " + grouped((long)var1.buy) : "")
            + (var1.buy > 0 && var1.sell > 0 ? ",  " : "")
            + (var1.sell > 0 ? "receive " + grouped((long)var1.sell) : "");
      var10 = this.foldHead("l:cost", "Cost", var12 + (var1.storyPoints > 0 ? ",  " + var1.storyPoints + " SP" : ""), var5, var10, var7) - 2.0F;
      if (!this.isFolded("l:cost")) {
         var10 = this.pair(
               "Credits",
               var12,
               "host has " + grouped((long)Math.round(this.data.credits)),
               (float)var11 > this.data.credits ? Panel.BAD : Panel.TEXT,
               var5,
               var10,
               var7
            )
            - 4.0F;
         Style.meter(var5, var10 - 3.0F, var7, 3.0F, (float)Math.max(0, var11), Math.max(1.0F, this.data.credits));
         var10 -= 8.0F;
         var10 = this.pair(
               "Story points",
               String.valueOf(var1.storyPoints),
               "host has " + this.data.storyPoints,
               var1.storyPoints > this.data.storyPoints ? Panel.BAD : Panel.TEXT,
               var5,
               var10,
               var7
            )
            - 12.0F;
      } else {
         var10 -= 6.0F;
      }

      List<String> var13 = new ArrayList<String>(var1.unavailable);
      if (!var13.isEmpty()) {
         var10 = this.foldHead(
               "l:nothere", this.docked() ? "Not available at " + this.data.market : "Waits for a market", String.valueOf(var13.size()), var5, var10, var7
            )
            - 2.0F;
         if (!this.isFolded("l:nothere")) {
            for (String var15 : var13) {
               if (var10 - 40.0F < var8) {
                  break;
               }

               boolean var16 = var15.startsWith("f");
               String var17 = var15.substring(1);
               RefitRequest.Change var18 = this.req.change(var17, var16);
               String var19 = var18 == null
                  ? var17
                  : (
                     var18.item.isEmpty()
                        ? "Removed: " + this.names(var1, var18)
                        : new FriendRefit.Names(this.data).item(RefitData.key(var16 ? 1 : 0, var18.item)).replace(" LPC", "")
                  );
               String var20 = (var16 ? "Bay " + (Integer.parseInt(var17) + 1) : slotKind(this.slotOf(var17)))
                  + ":  "
                  + var19
                  + (this.docked() ? "   (pick another)" : "");
               float var21 = var10;
               var10 = Style.callout(var20, this.docked() ? Panel.WARN : Style.ACCENT, var5, var10, var7) - 3.0F;
               int var22 = rowIndex(var4, var16 ? 1 : 0, var17);
               if (var22 >= 0) {
                  this.hits.add(new RefitScreen.Hit(var5, var10 + 3.0F, var7, var21 - var10 - 3.0F, 0, var22, ""));
               }
            }
         }

         if (!var1.wanted.isEmpty() && var10 - 40.0F >= var8) {
            var10 -= 4.0F;
            this.button(var5, var10 - 28.0F, Math.min(var7, 280.0F), 28.0F, "ADD MISSING TO WISH LIST (" + var1.wantedCount() + ")", Panel.GOOD, true, 19);
            var10 -= 34.0F;
         }

         var10 -= 8.0F;
      }

      var10 = this.wishListView(var5, var10, var7, var8);
      List var38 = var1.allProblems();
      var38.removeAll(var1.unavailableWhy);
      if (!var38.isEmpty()) {
         var10 = this.foldHead("l:fix", "To fix before sending", String.valueOf(var38.size()), var5, var10, var7) - 2.0F;
         if (!this.isFolded("l:fix")) {
            for (int var39 = 0; var39 < var38.size(); var39++) {
               if (var10 - 40.0F < var8) {
                  var10 = this.ui.text("and " + (var38.size() - var39) + " more", var9, Panel.BAD, var5, var10, var7) - 1.0F;
                  break;
               }

               var10 = Style.callout((String)var38.get(var39), Panel.BAD, var5, var10, var7) - 3.0F;
            }
         }
      }
   }

   private float shipPicture(Roster.Member var1, RefitScreen.Row var2, float var3, float var4, float var5, float var6) {
      String var7 = this.ui.body;
      float var8 = Math.min(var5, Math.max(100.0F, (var4 - var6) * (this.docked() ? 0.30F : 0.26F))); // (the stats go under it)
      float var9 = var3 + var5 / 2.0F;
      float var10 = var4 - var8 / 2.0F;
      LobbyScreen.fill(var3, var4 - var8, var5, var8, Style.WELL);
      List<String> var11 = this.slots();

      try {
         ArrayList var12 = new ArrayList();

         for (String var14 : var11) {
            RefitRequest.Change var15 = this.req.change(var14, false);
            String var16 = var15 != null ? var15.item : this.data.weaponIn(var14);
            if (var16 != null && !var16.isEmpty()) {
               var12.add("-1|" + var14 + "|" + var16);
            }
         }

         for (String var33 : this.data.fitSlots) {
            if (!var11.contains(var33)) {
               var12.add("-1|" + var33 + "|" + this.data.weaponIn(var33));
            }
         }

         for (String var34 : var1.fits) {
            if (!var34.startsWith("-1|")) {
               var12.add(var34);
            }
         }

         ShipPicture.draw(this.data.hullId, var1.sprite, var1.modules, var12, var9, var10, var8);
      } catch (Throwable var27) {
      }

      String var28 = null;

      try {
         Map var31 = ShipPicture.slotPoints(this.data.hullId, var11);
         ShipHullSpecAPI var35 = this.hull();

         for (String var37 : var11) {
            float[] var17 = (float[])var31.get(var37);
            if (var17 != null) {
               WeaponSlotAPI var18 = var35 == null ? null : var35.getWeaponSlotAPI(var37);
               float var19 = var18 == null ? 7.0F : 6.0F + 3.0F * (float)var18.getSlotSize().ordinal();
               boolean var20 = var2 != null && var2.kind == 0 && var2.id.equals(var37);
               boolean var21 = this.req.change(var37, false) != null;
               boolean var22 = LobbyScreen.inside(var17[0] - var19 - 3.0F, var17[1] - var19 - 3.0F, 2.0F * var19 + 6.0F, 2.0F * var19 + 6.0F);
               Color var23 = var20 ? Color.WHITE : (var21 ? Panel.GOOD : typeColor(var18));
               ring(var17[0], var17[1], var19, var23, !var20 && !var22 ? 1.5F : 2.5F, var20 ? 60 : (var22 ? 40 : 0));
               this.hits.add(new RefitScreen.Hit(var17[0] - var19 - 3.0F, var17[1] - var19 - 3.0F, 2.0F * var19 + 6.0F, 2.0F * var19 + 6.0F, 3, 0, var37));
               if (var22) {
                  RefitRequest.Change var24 = this.req.change(var37, false);
                  String var25 = var24 != null ? var24.item : this.data.weaponIn(var37);
                  var28 = slotKind(var18)
                     + ":  "
                     + (var25 != null && !var25.isEmpty() ? new FriendRefit.Names(this.data).item(RefitData.key(0, var25)) : "empty");
               }
            }
         }
      } catch (Throwable var26) {
      }

      float var32 = var4 - var8 - 6.0F;
      return this.line(var28 != null ? var28 : "Click a ring on the ship, or a tile, to change it.", var7, var28 != null ? Panel.TEXT : Panel.DIM, var3, var32, var5)
         - 10.0F;
   }

   private float wishListView(float var1, float var2, float var3, float var4) {
      if (WishView.wanted.isEmpty() && WishView.setAside.isEmpty()) {
         return var2;
      } else {
         FriendRefit.Names var5 = new FriendRefit.Names(this.data);
         var2 = this.foldHead(
               "l:wish",
               "Your wish list  (" + WishView.wantedCount() + ")",
               WishView.setAside.isEmpty() ? "" : WishView.setAside.size() + " set aside",
               var1,
               var2,
               var3
            )
            - 2.0F;
         if (this.isFolded("l:wish")) {
            return var2 - 6.0F;
         } else {
            int var6 = 0;

            for (Entry var8 : new ArrayList<>(WishView.wanted.entrySet())) {
               if (var2 - 40.0F < var4 || var6++ >= 6) {
                  var2 = this.ui.text("and " + (WishView.wanted.size() - var6 + 1) + " more", this.ui.body, Panel.DIM, var1, var2, var3) - 2.0F;
                  break;
               }

               float var9 = var2;
               boolean var10 = LobbyScreen.inside(var1, var2 - 22.0F, var3, 22.0F);
               var2 = Style.callout(
                     var5.item((String)var8.getKey()).replace(" LPC", "")
                        + ((Integer) var8.getValue() > 1 ? "  x" + var8.getValue() : "")
                        + (var10 ? "     (click: take it off)" : ""),
                     var10 ? Panel.WARN : Style.ACCENT,
                     var1,
                     var2,
                     var3
                  )
                  - 3.0F;
               this.hits.add(new RefitScreen.Hit(var1, var2 + 3.0F, var3, var9 - var2 - 3.0F, 6, 0, (String)var8.getKey()));
            }

            for (Entry var13 : WishView.setAside.entrySet()) {
               if (var2 - 40.0F < var4) {
                  break;
               }

               var2 = Style.callout(
                     var5.item((String)var13.getKey()).replace(" LPC", "")
                        + ((Integer) var13.getValue() > 1 ? "  x" + var13.getValue() : "")
                        + "  -  bought, set aside for you",
                     Panel.GOOD,
                     var1,
                     var2,
                     var3
                  )
                  - 3.0F;
            }

            return var2 - 8.0F;
         }
      }
   }

   private String names(RefitPlan var1, RefitRequest.Change var2) {
      String var3 = var1.before(var2);
      return var3 == null ? "" : new FriendRefit.Names(this.data).item(var3).replace(" LPC", "");
   }

   private WeaponSlotAPI slotOf(String var1) {
      try {
         return this.hull().getWeaponSlotAPI(var1);
      } catch (Throwable var3) {
         return null;
      }
   }

   static boolean hiddenMod(String var0) {
      try {
         HullModSpecAPI var1 = Global.getSettings().getHullModSpec(var0);
         return var1.isHidden() || var1.isHiddenEverywhere();
      } catch (Throwable var2) {
         return false;
      }
   }

   static boolean isDmod(String var0) {
      try {
         return Global.getSettings().getHullModSpec(var0).hasTag("dmod");
      } catch (Throwable var2) {
         return false;
      }
   }

   private static int rowIndex(List<RefitScreen.Row> var0, int var1, String var2) {
      for (int var3 = 0; var3 < var0.size(); var3++) {
         if (((RefitScreen.Row)var0.get(var3)).kind == var1 && ((RefitScreen.Row)var0.get(var3)).id.equals(var2)) {
            return var3;
         }
      }

      return -1;
   }

   private static Color typeColor(WeaponSlotAPI var0) {
      if (var0 == null) {
         return CYAN;
      } else {
         switch (var0.getWeaponType()) {
            case BALLISTIC:
               return new Color(255, 215, 80);
            case ENERGY:
               return new Color(110, 170, 255);
            case MISSILE:
               return new Color(130, 230, 110);
            case HYBRID:
               return new Color(255, 160, 60);
            case COMPOSITE:
               return new Color(200, 230, 90);
            case SYNERGY:
               return new Color(90, 230, 220);
            case UNIVERSAL:
               return new Color(230, 230, 230);
            default:
               return CYAN;
         }
      }
   }

   private static void ring(float var0, float var1, float var2, Color var3, float var4, int var5) {
      GL11.glPushAttrib(24581);
      GL11.glDisable(3553);
      GL11.glEnable(3042);
      GL11.glBlendFunc(770, 771);
      if (var5 > 0) {
         GL11.glColor4f((float)var3.getRed() / 255.0F, (float)var3.getGreen() / 255.0F, (float)var3.getBlue() / 255.0F, (float)var5 / 255.0F);
         GL11.glBegin(6);
         GL11.glVertex2f(var0, var1);

         for (int var6 = 0; var6 <= 20; var6++) {
            double var7 = (Math.PI * 2) * (double)var6 / 20.0;
            GL11.glVertex2f(var0 + (float)Math.cos(var7) * var2, var1 + (float)Math.sin(var7) * var2);
         }

         GL11.glEnd();
      }

      GL11.glColor4f((float)var3.getRed() / 255.0F, (float)var3.getGreen() / 255.0F, (float)var3.getBlue() / 255.0F, 0.95F);
      GL11.glLineWidth(var4);
      GL11.glBegin(2);

      for (int var9 = 0; var9 < 20; var9++) {
         double var10 = (Math.PI * 2) * (double)var9 / 20.0;
         GL11.glVertex2f(var0 + (float)Math.cos(var10) * var2, var1 + (float)Math.sin(var10) * var2);
      }

      GL11.glEnd();
      GL11.glLineWidth(1.0F);
      GL11.glPopAttrib();
   }

   private float limit(String var1, int var2, int var3, float var4, float var5, float var6) {
      Color var7 = var3 > 0 && var2 > var3 ? Panel.BAD : Panel.TEXT;
      float var8 = this.ui.text(var1, this.ui.body, Panel.DIM, var4, var5, var6 * 0.6F);
      Style.textRight(var2 + " / " + (var3 > 0 ? String.valueOf(var3) : "?"), this.ui.body, var7, var4 + var6, var5);
      Style.meter(var4, var8 - 4.0F, var6, 3.0F, (float)var2, (float)var3);
      return var8 - 6.0F;
   }

   private float pair(String var1, String var2, String var3, Color var4, float var5, float var6, float var7) {
      float var8 = this.ui.text(var1, this.ui.body, Panel.DIM, var5, var6, var7 * 0.4F);
      this.ui.text(var2, this.ui.body, var4, var5 + var7 * 0.4F, var6, var7 * 0.6F);
      Style.textRight(var3, this.ui.body, Style.FAINT, var5 + var7, var6);
      return var8;
   }

   private void tiles(List<RefitScreen.Row> var1, RefitPlan var2, FriendRefit.Names var3, float var4, float var5, float var6, float var7) {
      String var8 = this.ui.body;
      float var9 = 44.0F;
      float var10 = 26.0F;
      this.tileArea = new float[]{var4, var7, var6, var5 - var7};
      float var11 = var5 - var7;
      this.rowScroll = Math.max(0, Math.min(this.rowScroll, var1.size() - 1));

      while (this.rowScroll > 0 && this.hidden(var1, this.rowScroll)) {
         this.rowScroll--;
      }

      float var12 = 0.0F;

      int var13;
      for (var13 = this.rowScroll; var13 < var1.size(); var13++) {
         float var14 = ((RefitScreen.Row)var1.get(var13)).kind == 7 ? var10 : (this.hidden(var1, var13) ? 0.0F : var9 + 4.0F);
         if (var12 + var14 > var11) {
            break;
         }

         var12 += var14;
      }

      this.tilesShown = Math.max(1, var13 - this.rowScroll);
      if (var13 >= var1.size() && this.rowScroll > 0) {
         while (this.rowScroll > 0) {
            float var21 = ((RefitScreen.Row)var1.get(this.rowScroll - 1)).kind == 7 ? var10 : (this.hidden(var1, this.rowScroll - 1) ? 0.0F : var9 + 4.0F);
            if (var12 + var21 > var11) {
               break;
            }

            var12 += var21;
            this.rowScroll--;
         }
      }

      float var22 = var5;

      for (int var15 = this.rowScroll; var15 < var1.size(); var15++) {
         RefitScreen.Row var16 = (RefitScreen.Row)var1.get(var15);
         if (var16.kind == 7) {
            if (var22 - var10 < var7) {
               break;
            }

            boolean var17 = this.folded.contains("t:" + var16.id);
            String var18 = (var17 ? "[+]  " : "[-]  ") + var16.id + (var17 ? "   " + this.groupSummary(var1, var15, var2) : "");
            boolean var19 = LobbyScreen.inside(var4, var22 - var10, var6, var10);
            Style.text(
               var18,
               "graphics/fonts/orbitron12condensed.fnt",
               var19 ? CYAN : Style.alpha(CYAN, 200),
               var4,
               var22 - (var15 > this.rowScroll ? 8.0F : 2.0F),
               var6
            );
            this.hits.add(new RefitScreen.Hit(var4, var22 - var10, var6, var10, 7, 0, "t:" + var16.id));
            var22 -= var10;
         } else if (!this.hidden(var1, var15)) {
            if (var22 - var9 < var7) {
               break;
            }

            float var23 = var22 - var9;
            boolean var24 = var15 == this.rowAt;
            boolean var25 = LobbyScreen.inside(var4, var23, var6, var9);
            LobbyScreen.fill(var4, var23, var6, var9, var24 ? TILE_MARKED : (var25 ? TILE_OVER : TILE));
            boolean var20 = (var16.kind == 0 || var16.kind == 1) && var2.unavailable.contains((var16.kind == 1 ? "f" : "w") + var16.id);
            if (var24) {
               LobbyScreen.outline(var4, var23, var6, var9, CYAN);
               LobbyScreen.fill(var4, var23, 3.0F, var9, CYAN);
            } else if (var20) {
               LobbyScreen.fill(var4, var23, 3.0F, var9, this.docked() ? Panel.WARN : CYAN);
            } else if (this.changed(var16)) {
               LobbyScreen.fill(var4, var23, 3.0F, var9, Panel.GOOD);
            }

            this.tile(var16, var2, var3, var4, var23, var6, var9);
            this.hits.add(new RefitScreen.Hit(var4, var23, var6, var9, 0, var15, ""));
            var22 -= var9 + 4.0F;
         }
      }

      if (this.rowScroll > 0) {
         this.ui.text("more above (wheel)", var8, Panel.DIM, var4 + var6 - 130.0F, var5 + 16.0F, 130.0F);
      }

      if (var13 < var1.size()) {
         this.ui.text("more below (wheel)", var8, Panel.DIM, var4 + var6 - 130.0F, var7 + 2.0F, 130.0F);
      }
   }

   private void tile(RefitScreen.Row var1, RefitPlan var2, FriendRefit.Names var3, float var4, float var5, float var6, float var7) {
      String var8 = this.ui.body;
      String var10 = "";
      String var11 = null;
      boolean var12 = false;
      Color var13 = Panel.TEXT;
      String var9;
      switch (var1.kind) {
         case 0:
            RefitRequest.Change var21 = this.req.change(var1.id, false);
            String var26 = var21 != null ? var21.item : this.data.weaponIn(var1.id);
            var11 = var26 != null && !var26.isEmpty() ? RefitData.key(0, var26) : null;
            var9 = var11 == null ? "Empty" : var3.item(var11);
            WeaponSlotAPI var30 = null;

            try {
               var30 = this.hull().getWeaponSlotAPI(var1.id);
            } catch (Throwable var18) {
            }

            Integer var31 = var11 == null ? null : this.data.opCosts.get(var11);
            var10 = slotKind(var30) + (var31 == null ? "" : "   " + var31 + " OP");
            if (var21 != null) {
               var13 = Panel.GOOD;
               var10 = this.changeNote(var21, var2, var3);
            } else if (var11 == null) {
               var13 = Panel.DIM;
            }
            break;
         case 1:
            int var20 = Integer.parseInt(var1.id);
            RefitRequest.Change var25 = this.req.change(var1.id, true);
            String var29 = var25 != null ? var25.item : this.data.wings.get(var20);
            var11 = var29.isEmpty() ? null : RefitData.key(1, var29);
            var9 = var11 == null ? "Empty bay" : var3.item(var11).replace(" LPC", "");
            Integer var17 = var11 == null ? null : this.data.opCosts.get(var11);
            var10 = "Bay " + (var20 + 1) + (var20 < this.builtInWings() ? "  (built in)" : "") + (var17 == null ? "" : "   " + var17 + " OP");
            if (var25 != null) {
               var13 = Panel.GOOD;
               var10 = this.changeNote(var25, var2, var3);
            } else if (var11 == null || var20 < this.builtInWings()) {
               var13 = Panel.DIM;
            }
            break;
         case 2:
            var9 = "Flux vents   " + this.req.vents + " / " + this.data.maxVents;
            var10 = "1 OP each";
            if (this.req.vents != this.data.vents) {
               var13 = Panel.GOOD;
               var10 = "was " + this.data.vents;
            }
            break;
         case 3:
            var9 = "Flux capacitors   " + this.req.caps + " / " + this.data.maxCaps;
            var10 = "1 OP each";
            if (this.req.caps != this.data.caps) {
               var13 = Panel.GOOD;
               var10 = "was " + this.data.caps;
            }
            break;
         case 4:
            var11 = var1.id;
            var12 = true;
            var9 = var3.mod(var1.id);
            Integer var14 = this.data.modCosts.get(var1.id);
            if (this.data.sMods.contains(var1.id)) {
               var10 = "S-mod (built in)";
               var13 = Panel.GOOD;
            } else if (this.data.permaMods.contains(var1.id)) {
               boolean var15 = isDmod(var1.id);
               var10 = var15 ? "D-mod (permanent)" : "Permanent";
               var13 = var15 ? Panel.BAD : Panel.WARN;
            } else if (this.req.removeMods.contains(var1.id)) {
               var10 = "Taken off";
               var13 = Panel.WARN;
            } else {
               boolean var24 = this.req.addMods.contains(var1.id);
               boolean var16 = this.req.buildIn.contains(var1.id);
               var10 = (var24 ? "Added" : "Fitted") + (var16 ? ", built in: S-mod, 1 story point" : (var14 == null ? "" : "   " + var14 + " OP"));
               if (var24 || var16) {
                  var13 = Panel.GOOD;
               }
            }
            break;
         case 5:
            var9 = "+  Add a hull mod";
            var10 = "From those the host knows";
            var13 = CYAN;
            break;
         case GROUPS: {
            var9 = "Weapon groups";
            int n = 0;
            for (RefitData.Group g : this.viewGroups()) if (!g.slots.isEmpty()) n++;
            if (this.data.groups == null) { var10 = "The host's Wingman can't change them"; var13 = Panel.DIM; }
            else if (this.changed(var1)) { var10 = n + " group" + (n == 1 ? "" : "s") + ", rearranged"; var13 = Panel.GOOD; }
            else var10 = n + " group" + (n == 1 ? "" : "s") + ", as the host has them";
            break;
         }
         default:
            var9 = this.req.note.isEmpty() && !this.typingNote ? "Write a note to the host" : this.req.note + (this.typingNote ? "_" : "");
            var10 = "Click, then type";
            var13 = this.req.note.isEmpty() ? Panel.DIM : Panel.TEXT;
      }

      if (var11 != null && LobbyScreen.inside(var4, var5, var6, var7)) {
         Integer var22 = var12 ? this.data.modCosts.get(var11) : this.data.opCosts.get(var11);
         Tips.hover(var11, var12, this.data.hullSize, var22 == null ? -1 : var22);
      }

      float var23 = var7 - 8.0F;
      float var27 = var4 + 4.0F;
      if (var1.kind != 0 && var1.kind != 1 && var1.kind != 4) {
         var27 = var4 + 10.0F;
      } else {
         iconBox(picture(var11, var12), var4 + 4.0F, var5 + 4.0F, var23, var1.kind == 4 && this.req.removeMods.contains(var1.id));
         var27 = var4 + var23 + 12.0F;
      }

      this.line(var9, var8, var13, var27, var5 + var7 - 5.0F, var6 - (var27 - var4) - 6.0F);
      this.line(var10, var8, Panel.DIM, var27, var5 + var7 / 2.0F - 1.0F, var6 - (var27 - var4) - 6.0F);
   }

   private String changeNote(RefitRequest.Change var1, RefitPlan var2, FriendRefit.Names var3) {
      String var4 = var2.before(var1);
      String var5 = var2.from(var1);
      String var6 = var1.item.isEmpty() ? "" : ("any".equals(var5) ? "not to be had here" : "from " + var3.source(var5));
      if (var4 == null) {
         return "new, " + var6;
      } else {
         String var7 = var1.to.isEmpty() ? "cargo" : var1.to;
         return "was "
            + var3.item(var4).replace(" LPC", "")
            + (this.data.market(var7) != null ? ", sold at " : ", to ")
            + var3.source(var7)
            + (var6.isEmpty() ? "" : "; " + var6);
      }
   }

   private static String slotKind(WeaponSlotAPI var0) {
      if (var0 == null) {
         return "slot";
      } else {
         try {
            return var0.getSlotSize().name().charAt(0)
               + var0.getSlotSize().name().substring(1).toLowerCase()
               + " "
               + var0.getWeaponType().name().toLowerCase().replace('_', ' ')
               + (var0.isHardpoint() ? " hardpoint" : " turret");
         } catch (Throwable var2) {
            return "slot";
         }
      }
   }

   private void side(RefitScreen.Row var1, RefitPlan var2, FriendRefit.Names var3, float var4, float var5, float var6, float var7) {
      String var8 = this.ui.body;
      if (var1 != null) {
         switch (var1.kind) {
            case 0:
            case 1:
               boolean var29 = var1.kind == 1 && Integer.parseInt(var1.id) < this.builtInWings();
               WeaponSlotAPI var32 = null;
               if (var1.kind == 0) {
                  try {
                     var32 = this.hull().getWeaponSlotAPI(var1.id);
                  } catch (Throwable var16) {
                  }
               }

               float var26 = Style.section(var1.kind == 0 ? slotKind(var32) : "Fighter bay " + (Integer.parseInt(var1.id) + 1), var4, var5, var6) - 4.0F;
               if (var29) {
                  this.ui.text("This bay's wing is built into the hull.", var8, Panel.DIM, var4, var26, var6);
                  return;
               }

               RefitRequest.Change var39 = this.req.change(var1.id, var1.kind == 1);
               String var40 = var39 == null ? null : var2.before(var39);
               float var41 = var4;
               if (var40 != null) {
                  String var15 = var39.to.isEmpty() ? "cargo" : var39.to;
                  var41 = this.button(
                        var4,
                        var26 - 28.0F,
                        Math.min(var6 * 0.68F, 300.0F),
                        28.0F,
                        var3.item(var40).replace(" LPC", "") + " goes to:  " + var3.source(var15) + "  >",
                        Panel.TEXT,
                        true,
                        3
                     )
                     + 8.0F;
               }

               if (var39 != null) {
                  this.button(var41, var26 - 28.0F, 80.0F, 28.0F, "UNDO", Panel.WARN, true, 4);
               }

               if (var40 != null || var39 != null) {
                  var26 -= 38.0F;
               }

               this.tabsAndCards(var1, var3, var4, var26, var6, var7);
               return;
            case 2:
            case 3:
               float var24 = Style.section(var1.kind == 2 ? "Flux vents" : "Flux capacitors", var4, var5, var6) - 8.0F;
               int var28 = var1.kind == 2 ? this.req.vents : this.req.caps;
               int var31 = var1.kind == 2 ? this.data.maxVents : this.data.maxCaps;
               float var34 = this.button(var4, var24 - 40.0F, 54.0F, 40.0F, "-5", Panel.TEXT, var28 > 0, 5) + 6.0F;
               var34 = this.button(var34, var24 - 40.0F, 54.0F, 40.0F, "-", Panel.TEXT, var28 > 0, 6) + 10.0F;
               this.ui.text(var28 + " / " + var31, "graphics/fonts/orbitron24aabold.fnt", Panel.TEXT, var34, var24 - 6.0F, 120.0F);
               var34 += 110.0F;
               var34 = this.button(var34, var24 - 40.0F, 54.0F, 40.0F, "+", Panel.TEXT, var31 <= 0 || var28 < var31, 7) + 6.0F;
               var34 = this.button(var34, var24 - 40.0F, 54.0F, 40.0F, "+5", Panel.TEXT, var31 <= 0 || var28 < var31, 8) + 10.0F;
               if (this.changed(var1)) {
                  this.button(var34, var24 - 40.0F, 80.0F, 40.0F, "UNDO", Panel.WARN, true, 4);
               }

               var24 -= 56.0F;
               this.ui
                  .text(
                     "Each costs 1 ordnance point. The most this ship can have, with the host's skills: "
                        + var31
                        + ". It had "
                        + (var1.kind == 2 ? this.data.vents : this.data.caps)
                        + ".",
                     var8,
                     Panel.DIM,
                     var4,
                     var24,
                     var6
                  );
               return;
            case 4:
               float var20 = Style.section("Hull mod", var4, var5, var6) - 4.0F;
               var20 = this.ui.text(var3.mod(var1.id), "graphics/fonts/orbitron20aabold.fnt", CYAN, var4, var20, var6) - 8.0F;
               iconBox(picture(var1.id, true), var4, var20 - 64.0F, 64.0F, false);
               String var27 = "";

               try {
                  HullModSpecAPI var11 = Global.getSettings().getHullModSpec(var1.id);
                  Integer var12 = this.data.modCosts.get(var1.id);
                  var27 = (var12 == null ? "" : var12 + " ordnance points on this hull.")
                     + (this.data.noBuildIn.contains(var1.id) ? " Can't be built in." : "")
                     + (var11.getManufacturer() != null && !var11.getManufacturer().isEmpty() ? " " + var11.getManufacturer() + "." : "");
               } catch (Throwable var17) {
               }

               this.ui.text(var27, var8, Panel.DIM, var4 + 74.0F, var20, var6 - 74.0F);
               var20 -= 76.0F;
               if (this.data.sMods.contains(var1.id)) {
                  this.ui.text("Built in (an S-mod): it stays.", var8, Panel.TEXT, var4, var20, var6);
                  return;
               } else {
                  if (this.data.permaMods.contains(var1.id)) {
                     Style.callout(
                        isDmod(var1.id) ? "A D-mod: it stays. Restoring the ship is done by the host." : "Permanent: it stays.",
                        isDmod(var1.id) ? Panel.BAD : Panel.WARN,
                        var4,
                        var20,
                        var6
                     );
                     return;
                  }

                  boolean var30 = this.req.removeMods.contains(var1.id);
                  boolean var33 = this.req.addMods.contains(var1.id);
                  boolean var13 = this.req.buildIn.contains(var1.id);
                  float var14 = this.button(
                        var4, var20 - 34.0F, 150.0F, 34.0F, var33 ? "REMOVE" : (var30 ? "PUT BACK" : "TAKE OFF"), var30 ? Panel.GOOD : Panel.WARN, true, 9
                     )
                     + 8.0F;
                  if (!this.data.noBuildIn.contains(var1.id) && !var30) {
                     var14 = this.button(
                           var14, var20 - 34.0F, 200.0F, 34.0F, var13 ? "DON'T BUILD IN" : "BUILD IN (1 SP)", var13 ? Panel.WARN : Panel.GOOD, true, 10
                        )
                        + 8.0F;
                  }

                  if (this.changed(var1) && !var33) {
                     this.button(var14, var20 - 34.0F, 90.0F, 34.0F, "UNDO", Panel.WARN, true, 4);
                  }

                  var20 -= 48.0F;
                  this.ui
                     .text(
                        "Building in makes it an S-mod: permanent, free of ordnance points, 1 story point. Whether a hull mod suits this ship is for the host to judge.",
                        var8,
                        Panel.DIM,
                        var4,
                        var20,
                        var6
                     );
                  return;
               }
            case GROUPS:
               this.groupsPanel(var2, var3, var4, var5, var6, var7);
               return;
            case 5:
               float var19 = Style.section("Add a hull mod", var4, var5, var6) - 4.0F;
               this.tabsAndCards(var1, var3, var4, var19, var6, var7);
               return;
            default:
               float var9 = Style.section("Note to the host", var4, var5, var6) - 6.0F;
               boolean var10 = LobbyScreen.inside(var4, var9 - 60.0F, var6, 60.0F);
               LobbyScreen.fill(var4, var9 - 60.0F, var6, 60.0F, this.typingNote ? TILE_MARKED : (var10 ? TILE_OVER : TILE));
               LobbyScreen.outline(var4, var9 - 60.0F, var6, 60.0F, this.typingNote ? CYAN : LobbyScreen.LINE);
               this.ui
                  .text(
                     this.req.note.isEmpty() && !this.typingNote ? "Click here and type a short note." : this.req.note + (this.typingNote ? "_" : ""),
                     var8,
                     this.req.note.isEmpty() && !this.typingNote ? Panel.DIM : Panel.TEXT,
                     var4 + 8.0F,
                     var9 - 6.0F,
                     var6 - 16.0F
                  );
               this.hits.add(new RefitScreen.Hit(var4, var9 - 60.0F, var6, 60.0F, 4, 11, ""));
               var9 -= 70.0F;
               if (!this.req.note.isEmpty()) {
                  this.button(var4, var9 - 30.0F, 90.0F, 30.0F, "CLEAR", Panel.WARN, true, 12);
               }
         }
      }
   }

   private void tabsAndCards(RefitScreen.Row var1, FriendRefit.Names var2, float var3, float var4, float var5, float var6) {
      String var7 = this.ui.body;
      float var8 = this.searchBox(var1, var3, var4, var5) - 8.0F;
      List var9 = this.sources(var1, var2);
      if (var9.size() > 1 || !this.tab.isEmpty()) {
         float var10 = var3;
         List<String> var11 = new ArrayList<String>();
         var11.add("");
         var11.addAll(var9);

         for (String var13 : var11) {
            String var14 = var13.isEmpty() ? "All" : var2.source(var13);
            if (!var14.isEmpty()) {
               var14 = Character.toUpperCase(var14.charAt(0)) + var14.substring(1);
            }

            var14 = fit(var14, var7, var5 - 18.0F); // one line, cut only if wider than the whole column
            LabelAPI var15 = this.ui.label(var14, var7, Panel.TEXT, 4000.0F);
            float var16 = Math.min(var5, var15.computeTextWidth(var14) + 18.0F);
            if (var10 + var16 > var3 + var5 && var10 > var3) {
               var10 = var3;
               var8 -= 28.0F;
            }

            boolean var17 = this.tab.equals(var13);
            boolean var18 = LobbyScreen.inside(var10, var8 - 24.0F, var16, 24.0F);
            LobbyScreen.fill(var10, var8 - 24.0F, var16, 24.0F, var17 ? TILE_MARKED : (var18 ? TILE_OVER : TILE));
            LobbyScreen.outline(var10, var8 - 24.0F, var16, 24.0F, var17 ? CYAN : LobbyScreen.LINE);
            this.ui.text(var14, var7, var17 ? Color.WHITE : Panel.TEXT, var10 + 9.0F, var8 - 4.0F, var16 + 40.0F);
            this.hits.add(new RefitScreen.Hit(var10, var8 - 24.0F, var16, 24.0F, 2, 0, var13));
            var10 += var16 + 6.0F;
         }

         var8 -= 32.0F;
      }

      List var39 = this.options(var1, var2, true);
      if (var39.isEmpty() || var39.size() == 1 && ((RefitScreen.Option)var39.get(0)).key == null && var1.kind != 5) {
         this.ui
            .text(
               !this.search.trim().isEmpty()
                  ? "Nothing matches \"" + this.search.trim() + "\"."
                  : (
                     var1.kind == 5
                        ? "No other hull mods are known to the host."
                        : "Nothing the host has fits here." + (this.availableOnly ? " Untick AVAILABLE to see everything that fits, and wish for it." : "")
                  ),
               var7,
               Panel.DIM,
               var3,
               var8 - (var39.isEmpty() ? 0.0F : 66.0F),
               var5
            );
         if (var39.isEmpty()) {
            return;
         }
      }

      float var40 = 8.0F;
      float var41 = (var5 - var40) / 2.0F;
      boolean items = var1.kind == 0 || var1.kind == 1;
      float var42 = items ? 74.0F : 58.0F;
      int var43 = Math.min(var39.size(), this.shownMax);
      this.moreLeft = var39.size() - var43;
      byte var44 = 2;
      int var45 = Math.max(1, (int)((var8 - var6 + var40) / (var42 + var40)));
      int var46 = (var43 + var44 - 1) / var44 + (this.moreLeft > 0 ? 1 : 0);
      this.maxCardScroll = Math.max(0, var46 - var45);
      this.cardScroll = Math.max(0, Math.min(this.cardScroll, this.maxCardScroll));
      this.cardArea = new float[]{var3, var6, var5, var8 - var6};
      RefitRequest.Change var47 = var1.kind != 0 && var1.kind != 1 ? null : this.req.change(var1.id, var1.kind == 1);
      String var19 = var47 != null ? (var47.item.isEmpty() ? null : RefitData.key(var1.kind == 1 ? 1 : 0, var47.item) + "|" + var47.from) : "";

      for (int var20 = this.cardScroll * var44; var20 < var43 && var20 < (this.cardScroll + var45) * var44; var20++) {
         RefitScreen.Option var21 = (RefitScreen.Option)var39.get(var20);
         int var22 = (var20 - this.cardScroll * var44) % var44;
         int var23 = (var20 - this.cardScroll * var44) / var44;
         float var24 = var3 + (float)var22 * (var41 + var40);
         float var25 = var8 - (float)(var23 + 1) * (var42 + var40) + var40;
         boolean var26 = LobbyScreen.inside(var24, var25, var41, var42);
         boolean var27 = var21.key == null
            ? var47 != null && var47.item.isEmpty()
            : (var21.key + "|" + var21.from).equals(var19)
               || (var21.elsewhere || !var21.usable) && var47 != null && "any".equals(var47.from) && var19 != null && var19.startsWith(var21.key + "|");
         if (var26 && var21.key != null) {
            Integer var28 = var1.kind == 5 ? this.data.modCosts.get(var21.key) : this.data.opCosts.get(var21.key);
            Tips.hover(var21.key, var1.kind == 5, this.data.hullSize, var28 == null ? -1 : var28);
         }

         LobbyScreen.fill(var24, var25, var41, var42, var27 ? TILE_MARKED : (var26 && var21.usable ? TILE_OVER : TILE));
         LobbyScreen.outline(var24, var25, var41, var42, var27 ? Panel.GOOD : (var26 && var21.usable ? CYAN : LobbyScreen.LINE));
         Color var30 = var21.usable ? Panel.TEXT : Panel.DIM;
         String var29;
         String var51;
         if (var21.key == null) {
            var51 = var1.kind == 1 ? "Leave the bay empty" : "Leave it empty";
            var29 = "The one there now comes out";
            var30 = Panel.DIM;
         } else if (var21.elsewhere) {
            var51 = var21.name == null ? var21.key : var21.name.replace(" LPC", "");
            Integer var31 = WishView.wanted.get(var21.key);
            var29 = var31 == null ? "Not to be had here" : "On your wish list (" + var31 + ")";
            var30 = Style.FAINT;
         } else if (var1.kind == 5) {
            Integer var52 = this.data.modCosts.get(var21.key);
            var51 = var2.mod(var21.key);
            var29 = var52 == null ? "" : var52 + " OP";
         } else {
            Integer var53 = this.data.opCosts.get(var21.key);
            var51 = var2.item(var21.key).replace(" LPC", "");
            var29 = (var53 == null ? "" : var53 + " OP   ")
               + (var21.price > 0.0F ? grouped((long)Math.round(var21.price)) + " cr   " : "")
               + var2.source(var21.from)
               + " ("
               + var21.count
               + ")";
         }

         float var54 = Math.min(var42 - 10.0F, 52.0F);
         if (var21.key != null) {
            iconBox(picture(var21.key, var1.kind == 5), var24 + 5.0F, var25 + (var42 - var54) / 2.0F, var54, !var21.usable);
         }

         float var32 = var24 + (var21.key != null ? var54 + 12.0F : 10.0F);
         float var33 = var41 - (var32 - var24) - 6.0F;
         if (!var21.usable && !var21.elsewhere) {
            var33 -= Style.width("LOCKED", "graphics/fonts/orbitron12condensed.fnt") + 16.0F;
         }

         if (!var21.usable && !var21.elsewhere) {
            Style.chip("locked", Panel.WARN, var24 + var41 - Style.width("LOCKED", "graphics/fonts/orbitron12condensed.fnt") - 16.0F, var25 + var42 - 6.0F);
         }

         boolean var34 = var21.key != null && var1.kind != 5 && (var21.elsewhere || !var21.usable);
         float var35 = 58.0F;
         float lh = Style.lineHeight(var7);
         this.line(var51, var7, var30, var32, var25 + var42 - 6.0F, var33);
         if (items) {
            // name, then OP / price / source, then the stats: the WISH button sits under the second line, by the third
            this.line(var29, var7, Panel.DIM, var32, var25 + var42 - 8.0F - lh, var41 - (var32 - var24) - 6.0F);
            if (var21.key != null) this.line(quickStats(var21.key), var7, Style.FAINT, var32, var25 + var42 - 10.0F - 2.0F * lh, var41 - (var32 - var24) - 6.0F - (var34 ? var35 + 6.0F : 0.0F));
         } else {
            this.line(var29, var7, Panel.DIM, var32, var25 + var42 / 2.0F - 2.0F, var41 - (var32 - var24) - 6.0F - (var34 ? var35 + 6.0F : 0.0F));
         }
         this.hits.add(new RefitScreen.Hit(var24, var25, var41, var42, 1, var20, ""));
         if (var34) {
            float var36 = var24 + var41 - var35 - 5.0F;
            float var37 = var25 + 5.0F;
            boolean var38 = LobbyScreen.inside(var36, var37, var35, 22.0F);
            Style.button(var36, var37, var35, 22.0F, "WISH", Panel.GOOD, true, var38);
            this.hits.add(new RefitScreen.Hit(var36, var37, var35, 22.0F, 5, 0, var21.key));
         }
      }

      int var48 = (var43 + var44 - 1) / var44;
      if (this.moreLeft > 0 && var48 >= this.cardScroll && var48 < this.cardScroll + var45) {
         float var49 = var8 - (float)(var48 - this.cardScroll + 1) * (var42 + var40) + var40;
         boolean var50 = LobbyScreen.inside(var3, var49, var5, var42);
         Style.button(
            var3, var49 + 12.0F, var5, var42 - 24.0F, "Show " + Math.min(20, this.moreLeft) + " more  (" + this.moreLeft + " not shown)", CYAN, true, var50
         );
         this.hits.add(new RefitScreen.Hit(var3, var49, var5, var42, 4, 15, ""));
      }

      if (var46 > var45) {
         this.ui
            .text(
               Math.min(var43, (this.cardScroll + var45) * var44)
                  + " of "
                  + var39.size()
                  + (this.moreLeft > 0 ? " (the wheel, at the end, shows more)" : "  (wheel to scroll)"),
               var7,
               Panel.DIM,
               var3,
               var6 + 2.0F,
               var5
            );
      }
   }

   private float searchBox(RefitScreen.Row var1, float var2, float var3, float var4) {
      String var5 = this.ui.body;
      float var6 = 28.0F;
      float var7 = Style.lineHeight(var5);
      String var8 = "AVAILABLE";
      float var9 = Style.width(var8, "graphics/fonts/orbitron12condensed.fnt") + 34.0F;
      float var10 = var2 + var4 - var9;
      float var11 = 14.0F;
      float var12 = var3 - (var6 + var11) / 2.0F;
      boolean var13 = LobbyScreen.inside(var10, var3 - var6, var9, var6);
      LobbyScreen.fill(var10, var12, var11, var11, this.availableOnly ? Style.alpha(CYAN, 70) : new Color(0, 0, 0, 90));
      LobbyScreen.outline(var10, var12, var11, var11, !this.availableOnly && !var13 ? LobbyScreen.LINE : CYAN);
      if (this.availableOnly) {
         this.ui.text("x", var5, Color.WHITE, var10 + 3.0F, var12 + var11 - (var11 - var7) / 2.0F + 1.0F, var11);
      }

      Style.text(
         var8,
         "graphics/fonts/orbitron12condensed.fnt",
         !this.availableOnly && !var13 ? Style.DIM : CYAN,
         var10 + var11 + 8.0F,
         var3 - (var6 - Style.lineHeight("graphics/fonts/orbitron12condensed.fnt")) / 2.0F,
         var9
      );
      this.hits.add(new RefitScreen.Hit(var10, var3 - var6, var9, var6, 4, 16, ""));
      var4 -= var9 + 10.0F;
      boolean var14 = LobbyScreen.inside(var2, var3 - var6, var4, var6);
      LobbyScreen.fill(var2, var3 - var6, var4, var6, this.typingSearch ? new Color(0, 0, 0, 120) : (var14 ? TILE_OVER : new Color(0, 0, 0, 80)));
      LobbyScreen.outline(var2, var3 - var6, var4, var6, this.typingSearch ? CYAN : LobbyScreen.LINE);
      Style.text(
         "SEARCH",
         "graphics/fonts/orbitron12condensed.fnt",
         !this.typingSearch && this.search.isEmpty() ? Style.FAINT : CYAN,
         var2 + 9.0F,
         var3 - (var6 - Style.lineHeight("graphics/fonts/orbitron12condensed.fnt")) / 2.0F,
         70.0F
      );
      String var15 = var1.kind == 5 ? "hull mods" : (var1.kind == 1 ? "fighter wings" : "weapons");
      boolean var16 = System.currentTimeMillis() / 400L % 2L == 0L;
      String var17 = this.search.isEmpty() && !this.typingSearch
         ? "Type to find " + var15 + " by name  (Ctrl+F)"
         : this.search + (this.typingSearch && var16 ? "_" : "");
      this.ui
         .text(var17, var5, this.search.isEmpty() && !this.typingSearch ? Style.FAINT : Panel.TEXT, var2 + 70.0F, var3 - (var6 - var7) / 2.0F, var4 - 110.0F);
      this.hits.add(new RefitScreen.Hit(var2, var3 - var6, var4, var6, 4, 13, ""));
      if (!this.search.isEmpty()) {
         float var18 = 26.0F;
         boolean var19 = LobbyScreen.inside(var2 + var4 - var18 - 2.0F, var3 - var6 + 2.0F, var18, var6 - 4.0F);
         this.ui.text("x", var5, var19 ? Color.WHITE : Panel.DIM, var2 + var4 - var18 + 7.0F, var3 - (var6 - var7) / 2.0F, var18);
         this.hits.add(new RefitScreen.Hit(var2 + var4 - var18 - 2.0F, var3 - var6 + 2.0F, var18, var6 - 4.0F, 4, 14, ""));
      }

      return var3 - var6;
   }

   private void bottomBar(RefitPlan var1, float var2, float var3, float var4, float var5) {
      boolean var6 = this.docked() && !var1.isEmpty() && var1.allProblems().isEmpty();
      int var7 = SavedRefits.count(this.data.memberId);
      float var8 = this.button(var2, var3, 190.0F, var5, "SEND TO HOST", var6 ? Panel.GOOD : Panel.DIM, true, 0) + 10.0F;
      var8 = this.button(var8, var3, 100.0F, var5, "SAVE", Style.ACCENT, true, 18) + 10.0F;
      var8 = this.button(var8, var3, 230.0F, var5, "SAVED REFITS (" + var7 + "/" + 3 + ")", this.savedView ? Color.WHITE : Style.ACCENT, true, 17) + 10.0F;
      var8 = this.button(var8, var3, 150.0F, var5, "START OVER", Panel.WARN, !var1.isEmpty() || !this.req.note.isEmpty(), 1) + 10.0F;
      var8 = this.button(var8, var3, 110.0F, var5, "BACK", Panel.TEXT, true, 2) + 16.0F;
      String var9 = !this.status.isEmpty()
         ? this.status.replace('\n', ' ')
         : (
            !this.docked()
               ? "Planning: your plan is kept. SAVE it, or send it once the host docks at a market."
               : (
                  var1.isEmpty()
                     ? "Change something, then send it. The host checks it and accepts or denies it; nothing changes until then."
                     : (
                        !var1.wanted.isEmpty()
                           ? "Some of it isn't to be had here: SAVE it for later, and put what's missing on your wish list."
                           : (!var1.allProblems().isEmpty() ? "Fix what is in red first." : "Ready to send.")
                     )
               )
         );
      this.ui
         .text(var9, this.ui.body, !this.status.isEmpty() ? this.statusColor : (var6 ? Panel.GOOD : Panel.DIM), var8, var3 + var5 - 6.0F, var2 + var4 - var8);
   }

   private void savedRefits(float var1, float var2, float var3, float var4) {
      float var5 = Style.section("Saved refits of this ship", var1, var2, var3) - 6.0F;
      var5 = this.ui
            .text(
               "Up to 3 per ship, kept on this PC. Loading one finds each item wherever the host can get it here.", this.ui.body, Panel.DIM, var1, var5, var3
            )
         - 10.0F;
      SimpleDateFormat var6 = new SimpleDateFormat("d MMM HH:mm");

      for (int var7 = 1; var7 <= 3; var7++) {
         float var8 = 104.0F;
         if (var5 - var8 < var4) {
            break;
         }

         SavedRefits.Saved var9 = SavedRefits.get(this.data.memberId, var7);
         LobbyScreen.fill(var1, var5 - var8, var3, var8, TILE);
         LobbyScreen.outline(var1, var5 - var8, var3, var8, LobbyScreen.LINE);
         float var10 = var5 - 8.0F;
         if (var9 == null) {
            var10 = this.ui.text("Refit " + var7 + ":  empty", this.ui.body, Panel.DIM, var1 + 10.0F, var10, var3 - 20.0F) - 4.0F;
         } else {
            RefitRequest var11 = this.forHere(var9.request);
            RefitPlan var12 = var11 == null ? null : this.plan(var11);
            int var13 = var9.request.changes.size()
               + var9.request.addMods.size()
               + var9.request.removeMods.size()
               + var9.request.buildIn.size()
               + (var9.request.vents != this.data.vents ? 1 : 0)
               + (var9.request.caps != this.data.caps ? 1 : 0);
            String var14 = "Refit "
               + var7
               + (var9.request.note.isEmpty() ? "" : ":  \"" + var9.request.note + "\"")
               + "   (saved "
               + var6.format(new Date(var9.at))
               + ")";
            var10 = this.ui.text(var14, this.ui.body, Panel.TEXT, var1 + 10.0F, var10, var3 - 20.0F) - 2.0F;
            String var15 = var13 + " change" + (var13 == 1 ? "" : "s");
            if (var12 != null) {
               var15 = var15 + (var12.wanted.isEmpty() ? ",  everything to be had here" : ",  " + var12.wantedCount() + " not to be had here");
            }

            if (var12 != null && var12.wanted.isEmpty() && !var12.allProblems().isEmpty()) {
               var15 = var15 + ",  " + var12.allProblems().size() + " other problem" + (var12.allProblems().size() == 1 ? "" : "s");
            }

            var10 = this.ui.text(var15, this.ui.body, var12 != null && !var12.wanted.isEmpty() ? Panel.WARN : Panel.DIM, var1 + 10.0F, var10, var3 - 20.0F)
               - 4.0F;
         }

         float var20 = var5 - var8 + 8.0F;
         float var21 = (var3 - 20.0F - 18.0F) / 4.0F;
         float var22 = var1 + 10.0F;
         var22 = this.button(var22, var20, var21, 28.0F, "SAVE HERE", Style.ACCENT, true, 100 + var7 * 10 + 0) + 6.0F;
         var22 = this.button(var22, var20, var21, 28.0F, "LOAD", Panel.GOOD, var9 != null, 100 + var7 * 10 + 1) + 6.0F;
         var22 = this.button(var22, var20, var21, 28.0F, "DELETE", Panel.BAD, var9 != null, 100 + var7 * 10 + 2) + 6.0F;
         this.button(var22, var20, var21, 28.0F, "WISH MISSING", Panel.WARN, var9 != null, 100 + var7 * 10 + 3);
         var5 -= var8 + 8.0F;
      }

      if (var5 - 34.0F >= var4) {
         this.button(var1, var5 - 30.0F, 120.0F, 30.0F, "CLOSE", Panel.DIM, true, 20);
      }
   }

    // ---- the loadout as planned, for weapon groups and stats ----

    /** Slot to weapon id ("" for an emptied one) for the slots that can be changed, as the plan leaves them. */
    private Map<String, String> plannedWeapons() {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String s : data.fitSlots) m.put(s, data.weaponIn(s));
        for (RefitRequest.Change c : req.changes) if (!c.wing) m.put(c.place, c.item);
        return m;
    }

    private List<String> plannedWings() {
        List<String> l = new ArrayList<String>(data.wings);
        for (RefitRequest.Change c : req.changes) {
            if (!c.wing) continue;
            try { int b = Integer.parseInt(c.place); if (b >= 0 && b < l.size()) l.set(b, c.item); } catch (NumberFormatException e) { /* none */ }
        }
        return l;
    }

    /** The weapon in a slot once the plan is done, built-in ones too, or null. */
    private String plannedWeaponIn(String slot, Map<String, String> planned, ShipHullSpecAPI hull) {
        String w = planned.get(slot);
        if (w == null && hull != null) { try { w = hull.getBuiltInWeapons().get(slot); } catch (Throwable t) { /* none */ } }
        return w == null || w.isEmpty() ? null : w;
    }

    /** The slots whose weapon can go in a group once the plan is done: biggest first, as the game lists them. */
    private List<String> groupSlots() {
        List<String> out = new ArrayList<String>();
        ShipHullSpecAPI hull = hull();
        Map<String, String> planned = plannedWeapons();
        if (hull != null) {
            for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
                try {
                    if (FriendRefit.groupable(s) && plannedWeaponIn(s.getId(), planned, hull) != null) out.add(s.getId());
                } catch (Throwable t) { /* left out */ }
            }
        } else for (Map.Entry<String, String> e : planned.entrySet()) if (e.getValue() != null && !e.getValue().isEmpty()) out.add(e.getKey());
        final ShipHullSpecAPI h = hull;
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                int c = size(h, b) - size(h, a);
                return c != 0 ? c : a.compareTo(b);
            }
        });
        return out;
    }

    private static final int GROUPS_SHOWN = 7;

    /** The groups as they will be (the plan's, else the host's), seven of them, empty ones included. */
    private List<RefitData.Group> viewGroups() {
        List<RefitData.Group> out = new ArrayList<RefitData.Group>();
        List<RefitData.Group> src = req.groups != null ? req.groups : data.groups;
        if (src != null) for (RefitData.Group g : src) if (out.size() < GROUPS_SHOWN) out.add(g);
        while (out.size() < GROUPS_SHOWN) out.add(new RefitData.Group());
        return out;
    }

    /** The plan's own groups, made from the host's on the first change; null if the host can't take them. */
    private List<RefitData.Group> editGroups() {
        if (data.groups == null) { status = "The host's Wingman is older: weapon groups can't be sent to it."; statusColor = Panel.WARN; return null; }
        if (req.groups == null) {
            req.groups = new ArrayList<RefitData.Group>();
            for (RefitData.Group g : data.groups) if (req.groups.size() < GROUPS_SHOWN) req.groups.add(g.copy());
        }
        while (req.groups.size() < GROUPS_SHOWN) req.groups.add(new RefitData.Group());
        return req.groups;
    }

    /** Groups set back the way the host has them are no change. */
    private void tidyGroups() {
        if (req.groups != null && data.groups != null && RefitData.sameGroups(req.groups, data.groups)) req.groups = null;
    }

    /** Puts a slot's weapon in a group, or, if it is in that group already, in none (the game places it). */
    private void moveToGroup(String slot, int group) {
        List<RefitData.Group> gs = editGroups();
        if (gs == null || group < 0 || group >= gs.size()) return;
        boolean wasThere = gs.get(group).slots.contains(slot);
        for (RefitData.Group g : gs) g.slots.remove(slot);
        if (!wasThere) gs.get(group).slots.add(slot);
        tidyGroups();
    }

    /** The groups the game would make for the planned loadout. */
    private void autoArrange() {
        if (editGroups() == null) return;
        Map<String, String> planned = plannedWeapons();
        List<RefitData.Group> auto = LoadoutStats.autoGroups(data.hullId, planned);
        if (auto == null) { status = "The game's arrangement can't be worked out here."; statusColor = Panel.WARN; return; }
        req.groups = new ArrayList<RefitData.Group>();
        for (RefitData.Group g : auto) if (req.groups.size() < GROUPS_SHOWN) req.groups.add(g);
        while (req.groups.size() < GROUPS_SHOWN) req.groups.add(new RefitData.Group());
        tidyGroups();
    }

    // ---- stats: worked out again only when the loadout changes ----

    private String statsKey = "", beforeKey = "";
    private LoadoutStats.Values statsNow, statsBefore;

    private void workStats() {
        Map<String, String> planned = plannedWeapons();
        List<String> wings = plannedWings();
        List<String> mods = new ArrayList<String>(data.hullmods);
        mods.removeAll(req.removeMods);
        for (String id : req.addMods) if (!mods.contains(id)) mods.add(id);
        List<String> sMods = new ArrayList<String>(data.sMods);
        for (String id : req.buildIn) { mods.remove(id); if (!sMods.contains(id)) sMods.add(id); }
        String key = data.memberId + "|" + data.hullId + "|" + planned + "|" + wings + "|" + mods + "|" + sMods + "|" + data.permaMods + "|" + req.vents + "|" + req.caps;
        if (key.equals(statsKey) && statsNow != null) return;
        String was = data.memberId + "|" + data.hullId + "|" + data.fitSlots + data.fitWeapons + "|" + data.wings + "|" + data.hullmods + "|" + data.sMods + "|" + data.permaMods + "|" + data.vents + "|" + data.caps;
        if (statsBefore == null || !was.equals(beforeKey)) {
            Map<String, String> before = new java.util.LinkedHashMap<String, String>();
            for (String s : data.fitSlots) before.put(s, data.weaponIn(s));
            statsBefore = LoadoutStats.of(data.hullId, before, data.wings, data.hullmods, data.sMods, data.permaMods, data.vents, data.caps);
            beforeKey = was;
        }
        statsNow = LoadoutStats.of(data.hullId, planned, wings, mods, sMods, data.permaMods, req.vents, req.caps);
        statsKey = key;
    }


    /** The loadout's numbers, two to a line, each with how much the plan changes it. */
    private float stats(float x, float top, float w) {
        try { workStats(); } catch (Throwable t) { return top; }
        LoadoutStats.Values a = statsNow, b = statsBefore;
        if (a == null) return top;
        float bal0 = a.dissipation - a.weaponFlux;
        float y = foldHead("l:stats", "Stats", "flux " + (bal0 >= 0 ? "+" : "") + Math.round(bal0) + "/s" + (a.full ? "" : ",  hull mods not counted"), x, top, w) - 2f;
        if (isFolded("l:stats")) return y - 6f;
        List<Object[]> cells = new ArrayList<Object[]>();
        cells.add(stat("Flux capacity", a.capacity, b == null ? a.capacity : b.capacity, 1, 0));
        cells.add(stat("Dissipation", a.dissipation, b == null ? a.dissipation : b.dissipation, 1, 0));
        cells.add(stat("Weapon flux/s", a.weaponFlux, b == null ? a.weaponFlux : b.weaponFlux, -1, 0));
        float bal = a.dissipation - a.weaponFlux, balWas = b == null ? bal : b.dissipation - b.weaponFlux;
        Object[] balance = stat("Flux balance", bal, balWas, 1, 0);
        if (bal < 0f) balance[3] = Panel.BAD;
        cells.add(balance);
        switch (a.defense) {
            case FRONT:
            case OMNI:
                cells.add(new Object[] { "Shield", (a.defense == com.fs.starfarer.api.combat.ShieldAPI.ShieldType.OMNI ? "omni " : "front ") + Math.round(a.shieldArc), "", Panel.TEXT });
                cells.add(stat("Shield upkeep/s", a.shieldUpkeep, b == null ? a.shieldUpkeep : b.shieldUpkeep, -1, 0));
                cells.add(stat("Shield flux/dmg", a.shieldEfficiency, b == null ? a.shieldEfficiency : b.shieldEfficiency, -1, 2));
                break;
            case PHASE:
                cells.add(stat("Phase cost", a.phaseCost, b == null ? a.phaseCost : b.phaseCost, -1, 0));
                cells.add(stat("Phase upkeep/s", a.phaseUpkeep, b == null ? a.phaseUpkeep : b.phaseUpkeep, -1, 0));
                break;
            default:
                cells.add(new Object[] { "Shield", "none", "", Panel.DIM });
        }
        cells.add(stat("Weapon DPS", a.dps, b == null ? a.dps : b.dps, 1, 0));
        cells.add(stat("Hull", a.hull, b == null ? a.hull : b.hull, 1, 0));
        cells.add(stat("Armor", a.armor, b == null ? a.armor : b.armor, 1, 0));
        cells.add(stat("Top speed", a.speed, b == null ? a.speed : b.speed, 1, 0));
        String body = ui.body;
        float colGap = 14f, cw = (w - colGap) / 2f, lh = Style.lineHeight(body) + 2f;
        for (int i = 0; i < cells.size(); i++) {
            Object[] c = cells.get(i);
            float cx = x + (i % 2) * (cw + colGap), cy = y - (i / 2) * lh;
            String value = (String) c[1], delta = (String) c[2];
            float vw = Style.width(value, body), dw = delta.isEmpty() ? 0f : Style.width(delta, Style.SMALL_FONT) + 4f;
            line((String) c[0], body, Panel.DIM, cx, cy, Math.max(20f, cw - vw - dw - 6f));
            Style.textRight(value, body, (Color) c[3], cx + cw - dw, cy);
            if (!delta.isEmpty()) Style.textRight(delta, Style.SMALL_FONT, (Color) c[4], cx + cw, cy + 1f);
        }
        y -= ((cells.size() + 1) / 2) * lh + 2f;
        return line("Before officer and host skills", Style.SMALL_FONT, Style.FAINT, x, y, w);
    }

    /** A stat: its name, value, change from now ("" if none), the value's color and the change's (better green, worse red). */
    private static Object[] stat(String name, float now, float was, int better, int decimals) {
        String v = decimals == 0 ? grouped(Math.round(now)) : String.valueOf(Math.round(now * 100f) / 100f);
        float d = now - was;
        boolean same = decimals == 0 ? Math.round(now) == Math.round(was) : Math.abs(d) < 0.005f;
        String delta = same ? "" : (d > 0 ? "+" : "-") + (decimals == 0 ? grouped(Math.round(Math.abs(d))) : String.valueOf(Math.round(Math.abs(d) * 100f) / 100f));
        Color dc = same ? Panel.DIM : (d > 0) == (better > 0) ? Panel.GOOD : Panel.BAD;
        return new Object[] { name, v, delta, same ? Panel.TEXT : dc, dc };
    }

    /**
     * The weapon groups: a line for each weapon with a box for each group (click one to put the weapon there, or
     * again to leave it to the game), and over them each group's fire mode and autofire.
     */
    private void groupsPanel(RefitPlan plan, FriendRefit.Names names, float x, float top, float w, float floor) {
        String body = ui.body;
        float y = Style.section("Weapon groups", x, top, w) - 4f;
        if (data.groups == null) {
            Style.callout("The host's Wingman is an older version that doesn't send weapon groups. Once the host updates, they can be set here.", Panel.WARN, x, y, w);
            return;
        }
        float bx = button(x, y - 30f, 170f, 30f, "AUTO-ARRANGE", CYAN, true, B_ARRANGE) + 8f;
        if (changed(new Row(GROUPS, ""))) button(bx, y - 30f, 90f, 30f, "UNDO", Panel.WARN, true, B_UNDO);
        y -= 42f;
        List<RefitData.Group> gs = viewGroups();
        float cell = 36f, cg = 4f, gridW = GROUPS_SHOWN * cell + (GROUPS_SHOWN - 1) * cg;
        float nameW = Math.max(60f, w - gridW - 10f), gx = x + w - gridW;
        float lh = Style.lineHeight(body), rowH = Math.max(24f, lh + 8f);
        // the head: group numbers, fire mode, autofire
        line("Group", body, Panel.DIM, x, y - (rowH - lh) / 2f, nameW);
        for (int g = 0; g < GROUPS_SHOWN; g++) {
            float cx = gx + g * (cell + cg);
            String n = String.valueOf(g + 1);
            ui.text(n, body, gs.get(g).slots.isEmpty() ? Style.FAINT : CYAN, cx + (cell - Style.width(n, body)) / 2f, y - (rowH - lh) / 2f, cell + 20f);
        }
        y -= rowH;
        line("Fire mode", body, Panel.DIM, x, y - (rowH - lh) / 2f, nameW);
        for (int g = 0; g < GROUPS_SHOWN; g++) {
            float cx = gx + g * (cell + cg);
            boolean alt = gs.get(g).alternating, over = LobbyScreen.inside(cx, y - rowH + 2f, cell, rowH - 4f);
            LobbyScreen.fill(cx, y - rowH + 2f, cell, rowH - 4f, over ? TILE_OVER : TILE);
            LobbyScreen.outline(cx, y - rowH + 2f, cell, rowH - 4f, over ? CYAN : LobbyScreen.LINE);
            String t = alt ? "ALT" : "LNK";
            ui.text(t, body, alt ? Panel.WARN : Panel.TEXT, cx + (cell - Style.width(t, body)) / 2f, y - (rowH - lh) / 2f, cell + 20f);
            hits.add(new Hit(cx, y - rowH + 2f, cell, rowH - 4f, H_GMODE, g, ""));
        }
        y -= rowH;
        line("Autofire", body, Panel.DIM, x, y - (rowH - lh) / 2f, nameW);
        for (int g = 0; g < GROUPS_SHOWN; g++) {
            float cx = gx + g * (cell + cg);
            boolean on = gs.get(g).autofire, over = LobbyScreen.inside(cx, y - rowH + 2f, cell, rowH - 4f);
            LobbyScreen.fill(cx, y - rowH + 2f, cell, rowH - 4f, on ? Style.alpha(Panel.GOOD, 50) : over ? TILE_OVER : TILE);
            LobbyScreen.outline(cx, y - rowH + 2f, cell, rowH - 4f, over ? CYAN : on ? Panel.GOOD : LobbyScreen.LINE);
            String t = on ? "ON" : "off";
            ui.text(t, body, on ? Panel.GOOD : Style.FAINT, cx + (cell - Style.width(t, body)) / 2f, y - (rowH - lh) / 2f, cell + 20f);
            hits.add(new Hit(cx, y - rowH + 2f, cell, rowH - 4f, H_GAUTO, g, ""));
        }
        y -= rowH + 4f;
        LobbyScreen.fill(x, y, w, 1f, Style.RULE);
        y -= 6f;
        // the weapons
        List<String> slots = groupSlots();
        Map<String, String> planned = plannedWeapons();
        ShipHullSpecAPI hull = hull();
        String note = "Click a box to put a weapon in that group; click it again to leave the weapon to the game, which places any weapon in no group when the refit is done."
                + " LNK fires the group's weapons together, ALT one after another.";
        float noteH = Style.height(note, body, w) + lh + 12f;
        float listFloor = floor + noteH;
        int fit = Math.max(1, (int) ((y - listFloor) / rowH));
        groupScroll = Math.max(0, Math.min(groupScroll, Math.max(0, slots.size() - fit)));
        groupArea = new float[] { x, listFloor, w, y - listFloor };
        if (slots.isEmpty()) y = ui.text("No weapons fitted.", body, Panel.DIM, x, y, w) - 6f;
        LoadoutStats.Values st = statsNow;
        for (int i = groupScroll; i < slots.size() && i < groupScroll + fit; i++) {
            String slot = slots.get(i);
            String wpn = plannedWeaponIn(slot, planned, hull);
            String key = RefitData.key(RefitData.WEAPON, wpn);
            WeaponSlotAPI ws = slotOf(slot);
            boolean over = LobbyScreen.inside(x, y - rowH, nameW, rowH);
            if (over) {
                LobbyScreen.fill(x, y - rowH + 1f, nameW, rowH - 2f, TILE_OVER);
                Integer op = data.opCosts.get(key);
                Tips.hover(key, false, data.hullSize, op == null ? -1 : op);
            }
            LobbyScreen.fill(x + 3f, y - rowH / 2f - 3f, 6f, 6f, typeColor(ws));
            boolean builtIn = ws != null && ws.isBuiltIn();
            float[] fd = st == null ? null : st.perSlot.get(slot);
            String extra = fd == null ? "" : (fd[1] >= 0.5f ? "   " + Math.round(fd[1]) + " dps" : "") + (fd[0] >= 0.5f ? "   " + Math.round(fd[0]) + " flux/s" : "");
            line(names.item(key).replace(" LPC", "") + (builtIn ? " (built in)" : "") + extra, body, builtIn ? Panel.DIM : Panel.TEXT, x + 14f, y - (rowH - lh) / 2f, nameW - 16f);
            int in = -1;
            for (int g = 0; g < gs.size(); g++) if (gs.get(g).slots.contains(slot)) { in = g; break; }
            for (int g = 0; g < GROUPS_SHOWN; g++) {
                float cx = gx + g * (cell + cg);
                boolean on = g == in, o = LobbyScreen.inside(cx, y - rowH + 2f, cell, rowH - 4f);
                LobbyScreen.fill(cx, y - rowH + 2f, cell, rowH - 4f, on ? TILE_MARKED : o ? TILE_OVER : TILE);
                LobbyScreen.outline(cx, y - rowH + 2f, cell, rowH - 4f, on ? CYAN : o ? CYAN : LobbyScreen.LINE);
                if (on) { String n = String.valueOf(g + 1); ui.text(n, body, Color.WHITE, cx + (cell - Style.width(n, body)) / 2f, y - (rowH - lh) / 2f, cell + 20f); }
                hits.add(new Hit(cx, y - rowH + 2f, cell, rowH - 4f, H_GCELL, g, slot));
            }
            y -= rowH;
        }
        if (slots.size() > fit) line((groupScroll + 1) + "-" + Math.min(slots.size(), groupScroll + fit) + " of " + slots.size() + " weapons (wheel to scroll)", body, Panel.DIM, x, listFloor + noteH - 4f, w);
        ui.text(note, body, Style.FAINT, x, floor + Style.height(note, body, w) + 2f, w);
    }


    /** A weapon's range, damage and flux per second, or a wing's craft and refit time, for its card. */
    private static String quickStats(String key) {
        try {
            String id = key.substring(2);
            if (key.startsWith("f:")) {
                com.fs.starfarer.api.loading.FighterWingSpecAPI f = Global.getSettings().getFighterWingSpec(id);
                return f.getNumFighters() + " craft   refit " + Math.round(f.getRefitTime()) + " s" + (f.getRoleDesc() == null || f.getRoleDesc().isEmpty() ? "" : "   " + f.getRoleDesc());
            }
            WeaponSpecAPI w = Global.getSettings().getWeaponSpec(id);
            com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI d = w.getDerivedStats();
            // most wanted first: what is cut to fit the card is the end
            StringBuilder sb = new StringBuilder();
            if (d != null) {
                float dps = d.getSustainedDps() > 0f ? d.getSustainedDps() : d.getDps();
                if (!w.isNoDPSInTooltip() && dps > 0f) sb.append(Math.round(dps)).append(" dps   ");
                if (d.getFluxPerSecond() > 0f) sb.append(Math.round(d.getFluxPerSecond())).append(" flux/s   ");
            }
            sb.append(Math.round(w.getMaxRange())).append(" range");
            if (w.getDamageType() != null) {
                switch (w.getDamageType()) {
                    case HIGH_EXPLOSIVE: sb.append("   HE"); break;
                    case KINETIC: sb.append("   kinetic"); break;
                    case FRAGMENTATION: sb.append("   frag"); break;
                    case ENERGY: sb.append("   energy"); break;
                    default: break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }


    private static final Map<String, String> fitted = new java.util.HashMap<String, String>();

    /** The text cut to one line of that width, with "..." when it was cut (kept, worked out once). */
    static String fit(String s, String font, float w) {
        if (s == null || s.isEmpty()) return "";
        String key = font + "|" + Math.round(w) + "|" + s;
        String done = fitted.get(key);
        if (done != null) return done;
        if (fitted.size() > 2000) fitted.clear();
        com.fs.starfarer.api.ui.LabelAPI m = Style.label("Ag", font, Panel.TEXT, 4000f);
        String out = s;
        if (m.computeTextWidth(s) > w) {
            int lo = 0, hi = s.length();
            while (lo < hi) { // the longest start that fits with the dots
                int mid = (lo + hi + 1) / 2;
                if (m.computeTextWidth(s.substring(0, mid).trim() + "...") <= w) lo = mid; else hi = mid - 1;
            }
            out = s.substring(0, lo).trim() + "...";
        }
        fitted.put(key, out);
        return out;
    }

    /** One line of text, cut to fit. */
    private float line(String s, String font, Color c, float x, float top, float w) {
        return ui.text(fit(s, font, w), font, c, x, top, w + 40f);
    }


   private static String grouped(long var0) {
      String var2 = String.valueOf(Math.abs(var0));
      StringBuilder var3 = new StringBuilder();

      for (int var4 = 0; var4 < var2.length(); var4++) {
         if (var4 > 0 && (var2.length() - var4) % 3 == 0) {
            var3.append(',');
         }

         var3.append(var2.charAt(var4));
      }

      return (var0 < 0L ? "-" : "") + var3;
   }

   private static final class Hit {
      final float x;
      final float y;
      final float w;
      final float h;
      final int type;
      final int index;
      final String id;

      Hit(float var1, float var2, float var3, float var4, int var5, int var6, String var7) {
         this.x = var1;
         this.y = var2;
         this.w = var3;
         this.h = var4;
         this.type = var5;
         this.index = var6;
         this.id = var7;
      }

      boolean has(float var1, float var2) {
         return var1 >= this.x && var1 <= this.x + this.w && var2 >= this.y && var2 <= this.y + this.h;
      }
   }

   private static final class Option {
      final String key;
      final String from;
      final int count;
      final float price;
      final boolean usable;
      boolean elsewhere;
      String name;

      Option(String var1, String var2, int var3, float var4, boolean var5) {
         this.key = var1;
         this.from = var2;
         this.count = var3;
         this.price = var4;
         this.usable = var5;
      }
   }

   private static final class Row {
      final int kind;
      final String id;

      Row(int var1, String var2) {
         this.kind = var1;
         this.id = var2;
      }
   }
}
