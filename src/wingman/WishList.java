package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.RefitData;
import wingman.net.WishMessage;

/**
 * Wish lists, host side: each player's wished-for weapons and wings (PlayerItems.WISHES, in the save), what the
 * docked market sells of them, buying them for the host (set aside in cargo for that player, PlayerItems.SET_ASIDE),
 * the host's own purchases of wished items noticed, and every list sent to the joiners (Msg.WISH_LIST).
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
final class WishList {
   private static final Map<Integer, byte[]> lastSent = new HashMap<>();
   private static long lastSentAt;
   private static long lastLooked;
   private static List<WishList.Offer> offers = new ArrayList<>();
   private static MarketAPI offersAt;

   private WishList() {
   }

   static void install() {
      lastSent.clear();
      offers = new ArrayList<>();
      offersAt = null;

      try {
         Global.getSector().addTransientListener(new WishList.Purchases());
      } catch (Throwable var1) {
         Global.getLogger(WishList.class).warn("Wingman: the host's purchases won't take items off friends' wish lists", var1);
      }
   }

   static void advance(HostSession var0) {
      if (var0 != null) {
         try {
            Iterator var1 = var0.getPlayers().iterator();

            while (true) {
               HostSession.Player var2;
               ArrayList var3;
               while (true) {
                  if (!var1.hasNext()) {
                     long var9 = System.currentTimeMillis();
                     if (var9 - lastSentAt >= 1000L) {
                        lastSentAt = var9;
                        var3 = new ArrayList();

                        for (HostSession.Player var13 : var0.getPlayers()) {
                           var3.add(var13.id);
                           byte[] var6 = listFor(var13.name).encode();
                           if (!Arrays.equals(var6, lastSent.get(var13.id))) {
                              lastSent.put(var13.id, var6);
                              var13.conn.send((byte)69, var6);
                           }
                        }

                        lastSent.keySet().retainAll(var3);
                     }

                     if (var9 - lastLooked >= 1000L) {
                        lastLooked = var9;
                        look();
                     }

                     return;
                  }

                  var2 = (HostSession.Player)var1.next();
                  synchronized (var2.wishes) {
                     if (!var2.wishes.isEmpty()) {
                        var3 = new ArrayList<>(var2.wishes);
                        var2.wishes.clear();
                        break;
                     }
                  }
               }

               for (Object var5 : var3) {
                  apply(var2, (byte[])var5);
               }
            }
         } catch (Throwable var8) {
            Global.getLogger(WishList.class).warn("Wingman: friends' wish lists stopped updating this frame", var8);
         }
      }
   }

   static WishMessage.List listFor(String var0) {
      WishMessage.List var1 = new WishMessage.List();
      var1.wanted.putAll(PlayerItems.WISHES.recorded(var0));
      CargoAPI var2 = null;

      try {
         var2 = Global.getSector().getPlayerFleet().getCargo();
      } catch (Throwable var5) {
      }

      for (RefitData.Item var4 : PlayerItems.SET_ASIDE.mine(var0, var2)) {
         var1.setAside.put(var4.key(), var4.count);
      }

      return var1;
   }

   private static void apply(HostSession.Player var0, byte[] var1) {
      WishMessage var2;
      try {
         var2 = WishMessage.decode(var1);
      } catch (IOException var7) {
         return;
      }

      if (WingmanConfig.load().friendRefit) {
         if (var2.op == 3) {
            PlayerItems.WISHES.clear(var0.name);
            lastSentAt = 0L;
         } else {
            int var3 = 0;

            for (Entry var5 : var2.items.entrySet()) {
               if (known((String)var5.getKey())) {
                  int var6 = Math.max(0, Math.min(99, (Integer)var5.getValue()));
                  if (var2.op == 1) {
                     var3 += PlayerItems.WISHES.add(var0.name, (String)var5.getKey(), var6);
                  } else {
                     PlayerItems.WISHES.add(var0.name, (String)var5.getKey(), -var6);
                  }
               }
            }

            if (var3 > 0) {
               CampaignLobby.say("Co-op: " + var0.name + " added " + var3 + " item" + (var3 == 1 ? "" : "s") + " to their wish list. Ctrl+Shift+L to see it.");
            }

            lastSentAt = 0L;
            lastLooked = 0L;
         }
      }
   }

   private static boolean known(String var0) {
      if (!PlayerItems.validKey(var0)) {
         return false;
      } else {
         try {
            String var1 = var0.substring(2);
            return var0.startsWith("f:") ? Global.getSettings().getFighterWingSpec(var1) != null : Global.getSettings().getWeaponSpec(var1) != null;
         } catch (Throwable var2) {
            return false;
         }
      }
   }

   private static void look() {
      MarketAPI var0 = FriendRefit.dockedMarket();
      ArrayList var1 = new ArrayList();
      if (var0 != null) {
         List<String> var2 = PlayerItems.WISHES.players();
         if (!var2.isEmpty()) {
            for (Object[] var4 : FriendRefit.submarkets(var0)) {
               if ((Boolean)var4[1]) {
                  SubmarketAPI var5 = (SubmarketAPI)var4[0];
                  CargoAPI var6 = var5.getCargo();
                  if (var6 != null) {
                     for (String var8 : var2) {
                        for (Entry var10 : PlayerItems.WISHES.recorded(var8).entrySet()) {
                           int var11 = PlayerItems.inCargo(var6, (String)var10.getKey());
                           if (var11 > 0 && FriendRefit.sells(var5, (String)var10.getKey())) {
                              WishList.Offer var12 = new WishList.Offer();
                              var12.player = var8;
                              var12.key = (String)var10.getKey();
                              var12.wanted = (Integer)var10.getValue();
                              var12.stock = var11;
                              var12.sub = var5.getSpecId();
                              var12.subName = var5.getNameOneLine() == null ? var12.sub : var5.getNameOneLine();
                              var12.price = (float)Math.round(FriendRefit.baseValue((String)var10.getKey()) * (1.0F + var5.getTariff()));
                              var1.add(var12);
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      Collections.sort(var1, new Comparator<WishList.Offer>() {
         public int compare(WishList.Offer var1, WishList.Offer var2) {
            return Float.compare(var1.price, var2.price);
         }
      });
      offers = var1;
      offersAt = var0;
   }

   static List<WishList.Offer> offers() {
      return (List<WishList.Offer>)(offersAt != null && FriendRefit.dockedMarket() == offersAt ? offers : new ArrayList<>());
   }

   static int[] canBuy(String var0, String var1) {
      HashMap var2 = new HashMap();
      HashMap var3 = new HashMap();
      int var4 = 0;
      int var5 = 0;

      for (WishList.Offer var7 : offers()) {
         String var8 = var7.player + "|" + var7.key;
         String var9 = var7.sub + "|" + var7.key;
         Integer var10 = (Integer)var2.get(var8);
         Integer var11 = (Integer)var3.get(var9);
         int var12 = var10 == null ? var7.wanted : var10;
         int var13 = var11 == null ? var7.stock : var11;
         int var14 = Math.min(var12, var13);
         if (var14 > 0) {
            var2.put(var8, var12 - var14);
            var3.put(var9, var13 - var14);
            if ((var0 == null || var0.equals(var7.player)) && (var1 == null || var1.equals(var7.key))) {
               var4 += var14;
               var5 += Math.round(var7.price) * var14;
            }
         }
      }

      return new int[]{var4, var5};
   }

   static WishList.Offer bestOffer(String var0, String var1) {
      for (WishList.Offer var3 : offers()) {
         if (var3.player.equals(var0) && var3.key.equals(var1)) {
            return var3;
         }
      }

      return null;
   }

   static int availableHere() {
      HashMap var0 = new HashMap();
      HashMap var1 = new HashMap();
      int var2 = 0;

      for (WishList.Offer var4 : offers()) {
         String var5 = var4.player + "|" + var4.key;
         String var6 = var4.sub + "|" + var4.key;
         Integer var7 = (Integer)var0.get(var5);
         Integer var8 = (Integer)var1.get(var6);
         int var9 = var7 == null ? var4.wanted : var7;
         int var10 = var8 == null ? var4.stock : var8;
         int var11 = Math.min(var9, var10);
         if (var11 > 0) {
            var2 += var11;
            var0.put(var5, var9 - var11);
            var1.put(var6, var10 - var11);
         }
      }

      return var2;
   }

   static int wishedCount() {
      int var0 = 0;

      for (String var2 : PlayerItems.WISHES.players()) {
         for (int var4 : PlayerItems.WISHES.recorded(var2).values()) {
            var0 += var4;
         }
      }

      return var0;
   }

   static String buyAvailable() {
      return buy(null, null);
   }

   static String buy(String var0, String var1) {
      MarketAPI var2 = FriendRefit.dockedMarket();
      if (var2 == null) {
         return "Dock at a market to buy what friends wish for.";
      } else {
         look();
         List<WishList.Offer> var3 = new ArrayList<WishList.Offer>();

         for (WishList.Offer var5 : offers()) {
            if ((var0 == null || var0.equals(var5.player)) && (var1 == null || var1.equals(var5.key))) {
               var3.add(var5);
            }
         }

         if (var3.isEmpty()) {
            return var1 == null ? "Nothing on the wish lists is for sale here." : name(var1) + " isn't for sale here any more.";
         } else {
            CampaignFleetAPI var18 = Global.getSector().getPlayerFleet();
            CoreUITradeMode var19 = FriendRefit.tradeMode(var2.getPrimaryEntity());
            LinkedHashMap var6 = new LinkedHashMap();
            Map<String, List<String>> var7 = new LinkedHashMap<String, List<String>>();
            int var8 = 0;
            int var9 = 0;
            int var10 = 0;

            for (WishList.Offer var12 : var3) {
               int var13 = PlayerItems.WISHES.recorded(var12.player).containsKey(var12.key) ? PlayerItems.WISHES.recorded(var12.player).get(var12.key) : 0;
               CargoAPI var14 = FriendRefit.cargoOf(var12.sub, var18, var2);

               for (int var15 = 0; var15 < var13 && PlayerItems.inCargo(var14, var12.key) >= 1; var15++) {
                  if (var18.getCargo().getCredits().get() < var12.price) {
                     var10++;
                     break;
                  }

                  FriendRefit.move(var14, var12.key, -1);
                  FriendRefit.move(var18.getCargo(), var12.key, 1);
                  var18.getCargo().getCredits().subtract(var12.price);
                  PlayerMarketTransaction var16 = FriendRefit.deal(var6, var2, var12.sub, var19);
                  FriendRefit.move(var16.getBought(), var12.key, 1);
                  var16.setCreditValue(var16.getCreditValue() - var12.price);
                  PlayerItems.WISHES.add(var12.player, var12.key, -1);
                  PlayerItems.SET_ASIDE.add(var12.player, var12.key, 1);
                  var8++;
                  var9 += Math.round(var12.price);
                  List<String> var17 = (List<String>)var7.get(var12.player);
                  if (var17 == null) {
                     var7.put(var12.player, var17 = new ArrayList<String>());
                  }

                  var17.add(name(var12.key));
               }
            }

            FriendRefit.report(var2, var6);
            tell(var7);
            lastSentAt = 0L;
            look();
            if (var8 == 0) {
               return var10 > 0 ? "Not enough credits for anything on the wish lists here." : "Nothing on the wish lists could be bought.";
            } else {
               Global.getLogger(WishList.class).info("Wingman: bought " + var8 + " wished item(s) for friends (" + var9 + " credits)");
               return "Bought "
                  + var8
                  + " item"
                  + (var8 == 1 ? "" : "s")
                  + " ("
                  + LobbyScreen.grouped((long)var9)
                  + " credits), set aside for "
                  + join(new ArrayList<>(var7.keySet()))
                  + "."
                  + (var10 > 0 ? " More cost more credits than you have." : "");
            }
         }
      }
   }

   static void free(String var0) {
      PlayerItems.SET_ASIDE.clear(var0);
      lastSentAt = 0L;
   }

   static void clear(String var0) {
      PlayerItems.WISHES.clear(var0);
      lastSentAt = 0L;
      lastLooked = 0L;
   }

   static void noteBought(PlayerMarketTransaction var0) {
      if (!FriendRefit.reporting && var0 != null) {
         try {
            List<String> var1 = PlayerItems.WISHES.players();
            if (var1.isEmpty()) {
               return;
            }

            Map<String, Integer> var2 = new LinkedHashMap<String, Integer>();

            for (CargoStackAPI var4 : var0.getBought().getStacksCopy()) {
               String var5 = FriendRefit.keyOf(var4);
               if (var5 != null && !(var4.getSize() < 1.0F)) {
                  Integer var6 = (Integer)var2.get(var5);
                  var2.put(var5, (var6 == null ? 0 : var6) + (int)var4.getSize());
               }
            }

            Map<String, List<String>> var13 = new LinkedHashMap<String, List<String>>();

            for (Entry var16 : var2.entrySet()) {
               int var18 = (Integer)var16.getValue();

               for (String var8 : var1) {
                  if (var18 <= 0) {
                     break;
                  }

                  Integer var9 = PlayerItems.WISHES.recorded(var8).get(var16.getKey());
                  int var10 = Math.min(var18, var9 == null ? 0 : var9);
                  if (var10 > 0) {
                     PlayerItems.WISHES.add(var8, (String)var16.getKey(), -var10);
                     PlayerItems.SET_ASIDE.add(var8, (String)var16.getKey(), var10);
                     var18 -= var10;
                     List<String> var11 = (List<String>)var13.get(var8);
                     if (var11 == null) {
                        var13.put(var8, var11 = new ArrayList<String>());
                     }

                     var11.add((var10 > 1 ? var10 + " x " : "") + name((String)var16.getKey()));
                  }
               }
            }

            if (var13.isEmpty()) {
               return;
            }

            for (Entry var17 : var13.entrySet()) {
               CampaignLobby.say("Co-op: off " + (String)var17.getKey() + "'s wish list and set aside for them: " + join((List<String>)var17.getValue()) + ".");
            }

            tell(var13);
            lastSentAt = 0L;
            lastLooked = 0L;
         } catch (Throwable var12) {
            Global.getLogger(WishList.class).warn("Wingman: a purchase couldn't be matched to friends' wish lists", var12);
         }
      }
   }

   private static void tell(Map<String, List<String>> var0) {
      HostSession var1 = HostSession.active();
      if (var1 != null) {
         for (HostSession.Player var3 : var1.getPlayers()) {
            List var4 = (List)var0.get(var3.name);
            if (var4 != null) {
               var3.conn.send((byte)19, Msg.utf("The host bought " + join(var4) + " from your wish list. It is set aside for you."));
            }
         }
      }
   }

   static String name(String var0) {
      String var1 = var0.substring(2);

      try {
         return var0.startsWith("f:")
            ? Global.getSettings().getFighterWingSpec(var1).getWingName() + " LPC"
            : Global.getSettings().getWeaponSpec(var1).getWeaponName();
      } catch (Throwable var3) {
         return var1;
      }
   }

   private static String join(List<String> var0) {
      StringBuilder var1 = new StringBuilder();

      for (int var2 = 0; var2 < var0.size(); var2++) {
         var1.append(var2 == 0 ? "" : (var2 == var0.size() - 1 ? " and " : ", ")).append((String)var0.get(var2));
      }

      return var1.toString();
   }

   static void forgetCampaign() {
      lastSent.clear();
      offers = new ArrayList<>();
      offersAt = null;
   }

   static final class Offer {
      String player;
      String key;
      String sub;
      String subName;
      int wanted;
      int stock;
      float price;
   }

   static final class Purchases extends BaseCampaignEventListener {
      Purchases() {
         super(false);
      }

      public void reportPlayerMarketTransaction(PlayerMarketTransaction var1) {
         WishList.noteBought(var1);
      }
   }
}
