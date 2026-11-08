package wingman;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.lwjgl.input.Mouse;
import wingman.net.HostSession;

/**
 * Wish lists, host side: the WISH LISTS button on the campaign map (under the friend refit one) and its window:
 * each player's list, what is for sale here and its price, BUY and BUY WHAT'S AVAILABLE, set-asides to free, lists
 * to clear, and folding by player.
 *
 * (Recovered from the compiled 0.9.3-beta wishlist-test3 build of 24 Oct 2026, whose source was lost: the code is
 * exactly that build's, but its comments and local names were not kept.)
 */
final class WishListPanel implements CampaignInputListener, CampaignUIRenderingListener {
   private static final String SMALL = "graphics/fonts/orbitron12condensed.fnt";
   private boolean open;
   private boolean broken;
   private boolean wasDown;
   private float scroll;
   private float maxScroll;
   private String message = "";
   private Color messageColor = Panel.DIM;
   private float[] button;
   private float[] window;
   private float[] listBox;
   private LabelAPI buttonLabel;
   private String buttonText = "";
   private final List<WishListPanel.Hit> hits = new ArrayList<>();
   private static final int BUY_ITEM = 0;
   private static final int BUY_ALL = 1;
   private static final int FREE = 2;
   private static final int CLEAR = 3;
   private static final int CLOSE = 4;
   private static final int FOLD = 5;
   private static final int FOLD_ALL = 6;
   private final Set<String> folded = new HashSet<>();
   private static final float ROW_ITEM = 46.0F;
   private static final float ROW_NAME = 36.0F;
   private static final float ROW_ASIDE = 24.0F;
   private static final float GAP = 14.0F;
   private static final Color ROW = new Color(255, 255, 255, 8);
   private static final Color ROW_OVER = new Color(120, 210, 255, 24);
   private static final Color NAME_BACK = new Color(120, 210, 255, 16);
   private static boolean shownCache = true;
   private static long shownAt;

   static void install() {
      try {
         Global.getSector().getListenerManager().addListener(new WishListPanel(), true);
      } catch (Throwable var1) {
         Global.getLogger(WishListPanel.class).warn("Wingman: friends' wish lists can't be shown on the campaign map", var1);
      }
   }

   private static List<String> friends() {
      LinkedHashSet var0 = new LinkedHashSet<>(PlayerItems.WISHES.players());
      var0.addAll(PlayerItems.SET_ASIDE.players());
      ArrayList var1 = new ArrayList(var0);
      Collections.sort(var1, String.CASE_INSENSITIVE_ORDER);
      return var1;
   }

   private static boolean shown() {
      long var0 = System.currentTimeMillis();
      if (var0 - shownAt > 5000L) {
         shownAt = var0;

         try {
            shownCache = WingmanConfig.load().friendRefit;
         } catch (Throwable var3) {
         }
      }

      return shownCache;
   }

   public int getListenerInputPriority() {
      return 89;
   }

   public void processCampaignInputPreCore(List<InputEventAPI> var1) {
      if (!this.broken && var1 != null && Global.getCurrentState() == GameState.CAMPAIGN) {
         try {
            if (friends().isEmpty() || !shown()) {
               this.open = false;
               return;
            }

            float var2 = Global.getSettings().getScreenScaleMult();

            for (InputEventAPI var4 : var1) {
               if (!var4.isConsumed()) {
                  if (this.open && var4.isMouseScrollEvent()) {
                     float var10 = (float)var4.getX() / var2;
                     float var11 = (float)var4.getY() / var2;
                     if (this.window != null && inside(this.window, var10, var11)) {
                        this.scrollBy(var4.getEventValue() > 0 ? -60.0F : 60.0F);
                        var4.consume();
                     }
                  } else if (var4.isMouseEvent() && !var4.isMouseMoveEvent() && this.overUs((float)var4.getX() / var2, (float)var4.getY() / var2)) {
                     var4.consume();
                  } else if (var4.isKeyboardEvent()) {
                     boolean var5 = var4.isKeyDownEvent();
                     boolean var6 = var4.isCtrlDown();
                     boolean var7 = var4.isShiftDown();
                     int var8 = var4.getEventValue();
                     if (!this.open) {
                        if (var5 && var6 && var7 && var8 == 38) {
                           this.open = true;
                           this.message = "";
                           this.scroll = 0.0F;
                           var4.consume();
                        }
                     } else {
                        var4.consume();
                        if (var5) {
                           if (var8 != 1 && (var8 != 38 || !var6)) {
                              if (var8 == 48) {
                                 this.act(1, null, null);
                              } else if (var8 == 200) {
                                 this.scrollBy(-46.0F);
                              } else if (var8 == 208) {
                                 this.scrollBy(46.0F);
                              } else if (var8 == 201) {
                                 this.scrollBy(-this.pageSize());
                              } else if (var8 == 209) {
                                 this.scrollBy(this.pageSize());
                              }
                           } else {
                              this.open = false;
                           }
                        }
                     }
                  }
               }
            }
         } catch (Throwable var9) {
            this.open = false;
            Global.getLogger(WishListPanel.class).warn("Wingman: the wish list window failed and was closed", var9);
         }
      }
   }

   private float pageSize() {
      return this.listBox == null ? 300.0F : Math.max(46.0F, this.listBox[3] - 46.0F);
   }

   private void scrollBy(float var1) {
      this.scroll = Math.max(0.0F, Math.min(this.maxScroll, this.scroll + var1));
   }

   private static boolean inside(float[] var0, float var1, float var2) {
      return var1 >= var0[0] && var1 <= var0[0] + var0[2] && var2 >= var0[1] && var2 <= var0[1] + var0[3];
   }

   private boolean overUs(float var1, float var2) {
      return this.open && this.window != null && inside(this.window, var1, var2) ? true : !this.open && this.button != null && inside(this.button, var1, var2);
   }

   private void act(int var1, String var2, String var3) {
      switch (var1) {
         case 0:
         case 1:
            String var5 = var1 == 1 ? WishList.buyAvailable() : WishList.buy(var2, var3);
            this.say(var5, var5.startsWith("Bought") ? Panel.GOOD : Panel.WARN);
            break;
         case 2:
            WishList.free(var2);
            this.say("What was set aside for " + var2 + " is ordinary cargo again.", Panel.WARN);
            break;
         case 3:
            WishList.clear(var2);
            this.say(var2 + "'s wish list is cleared. They see it too.", Panel.WARN);
            break;
         case 4:
         default:
            this.open = false;
            break;
         case 5:
            if (!this.folded.remove(var2)) {
               this.folded.add(var2);
            }
            break;
         case 6:
            List var4 = friends();
            if (this.folded.containsAll(var4)) {
               this.folded.removeAll(var4);
            } else {
               this.folded.addAll(var4);
            }
      }
   }

   private void say(String var1, Color var2) {
      this.message = var1;
      this.messageColor = var2;
   }

   private static void sound() {
      try {
         Global.getSoundPlayer().playUISound("ui_button_pressed", 1.0F, 1.0F);
      } catch (Throwable var1) {
      }
   }

   public void processCampaignInputPreFleetControl(List<InputEventAPI> var1) {
   }

   public void processCampaignInputPostCore(List<InputEventAPI> var1) {
   }

   public void renderInUICoordsBelowUI(ViewportAPI var1) {
   }

   public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI var1) {
   }

   public void renderInUICoordsAboveUIAndTooltips(ViewportAPI var1) {
      if (!this.broken && Global.getCurrentState() == GameState.CAMPAIGN) {
         try {
            List var2 = friends();
            boolean var3 = Mouse.isButtonDown(0);
            if (var2.isEmpty() || !shown()) {
               this.button = null;
               this.window = null;
               this.hits.clear();
               this.open = false;
               this.wasDown = var3;
               return;
            }

            float var4 = (float)Global.getSettings().getMouseX();
            float var5 = (float)Global.getSettings().getMouseY();
            if (this.open && var3 && !this.wasDown) {
               int var6 = this.hits.size() - 1;

               while (true) {
                  if (var6 >= 0) {
                     WishListPanel.Hit var7 = this.hits.get(var6);
                     if (!var7.has(var4, var5)) {
                        var6--;
                        continue;
                     }

                     sound();
                     this.act(var7.action, var7.player, var7.key);
                  }

                  var2 = friends();
                  if (var2.isEmpty() || !this.open) {
                     this.hits.clear();
                     this.window = null;
                     this.wasDown = var3;
                     return;
                  }
                  break;
               }
            }

            if (!this.open) {
               this.hits.clear();
               this.window = null;
               this.drawButton(WishList.wishedCount(), WishList.availableHere());
               if (var3 && !this.wasDown && this.button != null && LobbyScreen.inside(this.button[0], this.button[1], this.button[2], this.button[3])) {
                  this.open = true;
                  this.message = "";
                  this.scroll = 0.0F;
               }

               this.wasDown = var3;
               return;
            }

            this.wasDown = var3;
            this.drawWindow(var2);
         } catch (Throwable var8) {
            this.open = false;
            this.broken = true;
            Global.getLogger(WishListPanel.class).error("Wingman: the wish list window can't be drawn and is switched off (lists are still kept)", var8);
         }
      }
   }

   private List<WishListPanel.Row> rows(List<String> var1) {
      ArrayList var2 = new ArrayList();

      for (String var4 : var1) {
         if (!var2.isEmpty()) {
            var2.add(new WishListPanel.Row(3, var4, null, 0));
         }

         var2.add(new WishListPanel.Row(0, var4, null, 0));
         if (!this.folded.contains(var4)) {
            for (Entry var6 : PlayerItems.WISHES.recorded(var4).entrySet()) {
               var2.add(new WishListPanel.Row(1, var4, (String)var6.getKey(), (Integer)var6.getValue()));
            }

            Map<String, Integer> var8 = PlayerItems.SET_ASIDE.recorded(var4);
            if (!var8.isEmpty()) {
               var2.add(new WishListPanel.Row(4, var4, null, var8.size()));
               if (!this.folded.contains("aside:" + var4)) {
                  for (Entry var7 : var8.entrySet()) {
                     var2.add(new WishListPanel.Row(2, var4, (String)var7.getKey(), (Integer)var7.getValue()));
                  }
               }
            }
         }
      }

      return var2;
   }

   private void drawWindow(List<String> var1) {
      this.hits.clear();
      float var2 = Global.getSettings().getScreenWidth();
      float var3 = Global.getSettings().getScreenHeight();
      float var4 = Math.min(780.0F, var2 - 80.0F);
      float var5 = Math.min(600.0F, var3 - 140.0F);
      float var6 = (var2 - var4) / 2.0F;
      float var7 = (var3 - var5) / 2.0F;
      this.window = new float[]{var6, var7, var4, var5};
      String var8 = Style.body();
      MarketAPI var9 = FriendRefit.dockedMarket();
      String var10 = var9 == null ? null : (var9.getName() != null && !var9.getName().isEmpty() ? var9.getName() : "this market");
      int[] var11 = WishList.canBuy(null, null);
      String var12 = var10 == null ? "not docked" : (var11[0] > 0 ? var11[0] + " for sale at " + var10 : "nothing for sale at " + var10);
      float var13 = Style.window(var6, var7, var4, var5, "WISH LISTS", var12, 30.0F) - 10.0F;
      float var14 = 22.0F;
      float var15 = var6 + var4 - var14 - 10.0F;
      float var16 = var7 + var5 - 34.0F + (34.0F - var14) / 2.0F;
      boolean var17 = LobbyScreen.inside(var15, var16, var14, var14);
      Style.button(var15, var16, var14, var14, "X", Panel.DIM, true, var17);
      this.hits.add(new WishListPanel.Hit(var15, var16, var14, var14, 4, null, null));
      float var18 = 52.0F;
      float var19 = 16.0F;
      float var20 = var7 + var18;
      Style.rule(var6 + 1.0F, var20, var4 - 2.0F);
      float var21 = 34.0F;
      float var22 = var7 + (var18 - var21) / 2.0F;
      String var23 = "BUY WHAT'S AVAILABLE" + (var11[0] > 0 ? "  (" + var11[0] + ",  " + LobbyScreen.grouped((long)var11[1]) + " CR)" : "");
      float var24 = Math.max(260.0F, Style.width(var23, "graphics/fonts/orbitron20aabold.fnt") + 40.0F);
      boolean var25 = LobbyScreen.inside(var6 + var19, var22, var24, var21);
      Style.button(var6 + var19, var22, var24, var21, var23, var11[0] > 0 ? Panel.GOOD : Panel.DIM, var11[0] > 0, var25 && var11[0] > 0);
      if (var11[0] > 0) {
         this.hits.add(new WishListPanel.Hit(var6 + var19, var22, var24, var21, 1, null, null));
      }

      float var26 = 110.0F;
      float var27 = var6 + var4 - var19 - var26;
      Style.button(var27, var22, var26, var21, "CLOSE", Panel.DIM, true, LobbyScreen.inside(var27, var22, var26, var21));
      this.hits.add(new WishListPanel.Hit(var27, var22, var26, var21, 4, null, null));
      if (var1.size() > 1) {
         String var28 = this.folded.containsAll(var1) ? "EXPAND ALL" : "COLLAPSE ALL";
         float var29 = 150.0F;
         var27 -= var29 + 10.0F;
         Style.button(var27, var22, var29, var21, var28, Style.ACCENT, true, LobbyScreen.inside(var27, var22, var29, var21));
         this.hits.add(new WishListPanel.Hit(var27, var22, var29, var21, 6, null, null));
      }

      String var41 = !this.message.isEmpty()
         ? this.message
         : (
            var10 == null
               ? "Dock at a market to buy: each one is looked at for what friends wish for."
               : "Wheel or Up/Down to scroll.   B: buy what's available   Esc: close"
         );
      Style.text(
         var41,
         var8,
         !this.message.isEmpty() ? this.messageColor : Panel.DIM,
         var6 + var19 + var24 + 14.0F,
         var22 + var21 - (var21 - Style.lineHeight(var8)) / 2.0F,
         var27 - (var6 + var19 + var24 + 28.0F)
      );
      float var42 = var6 + var19;
      float var30 = var4 - 2.0F * var19 - 12.0F;
      float var31 = var13;
      float var32 = var20 + 8.0F;
      this.listBox = new float[]{var42, var32, var30, var13 - var32};
      List<WishListPanel.Row> var33 = this.rows(var1);
      float var34 = 0.0F;

      for (WishListPanel.Row var36 : var33) {
         var34 += var36.height();
      }

      this.maxScroll = Math.max(0.0F, var34 - (var13 - var32));
      this.scroll = Math.max(0.0F, Math.min(this.maxScroll, this.scroll));
      float var43 = var13 + this.scroll;
      HostSession var44 = HostSession.active();

      for (WishListPanel.Row var38 : var33) {
         float var39 = var38.height();
         float var40 = var43;
         var43 -= var39;
         if (!(var40 > var31 + 0.5F) && !(var43 < var32 - 0.5F)) {
            if (var38.kind == 0) {
               this.drawName(var38, var44, var42, var43, var30, var39, var8);
            } else if (var38.kind == 1) {
               this.drawItem(var38, var42, var43, var30, var39, var8, var10 != null);
            } else if (var38.kind == 2) {
               this.drawAside(var38, var42, var43, var30, var39, var8);
            } else if (var38.kind == 4) {
               this.drawAsideHead(var38, var42, var43, var30, var39);
            }
         }
      }

      if (this.maxScroll > 0.0F) {
         float var45 = var6 + var4 - var19 - 4.0F;
         float var46 = var31 - var32;
         LobbyScreen.fill(var45, var32, 4.0F, var46, new Color(255, 255, 255, 18));
         float var47 = Math.max(30.0F, var46 * var46 / var34);
         float var48 = var31 - var47 - (var46 - var47) * (this.scroll / this.maxScroll);
         LobbyScreen.fill(var45, var48, 4.0F, var47, Style.alpha(Style.ACCENT, 160));
         if (this.scroll < this.maxScroll) {
            Style.text(
               "more below",
               "graphics/fonts/orbitron12condensed.fnt",
               Style.DIM,
               var42 + var30 - Style.width("MORE BELOW", "graphics/fonts/orbitron12condensed.fnt") - 10.0F,
               var32 + Style.lineHeight("graphics/fonts/orbitron12condensed.fnt") + 2.0F,
               120.0F
            );
         }
      }
   }

   private void drawName(WishListPanel.Row var1, HostSession var2, float var3, float var4, float var5, float var6, String var7) {
      Color var8 = Style.ACCENT;
      boolean var9 = false;
      if (var2 != null) {
         for (HostSession.Player var11 : var2.getPlayers()) {
            if (var11.name.equals(var1.player)) {
               var8 = PlayerTags.color(var11.slot);
               var9 = true;
            }
         }
      }

      boolean var23 = this.folded.contains(var1.player);
      boolean var24 = LobbyScreen.inside(var3, var4 + 2.0F, var5, var6 - 4.0F);
      LobbyScreen.fill(var3, var4 + 2.0F, var5, var6 - 4.0F, var24 ? ROW_OVER : NAME_BACK);
      LobbyScreen.fill(var3, var4 + 2.0F, 3.0F, var6 - 4.0F, var8);
      this.hits.add(new WishListPanel.Hit(var3, var4 + 2.0F, var5, var6 - 4.0F, 5, var1.player, null));
      Map<String, Integer> var12 = PlayerItems.WISHES.recorded(var1.player);
      Map var13 = PlayerItems.SET_ASIDE.recorded(var1.player);
      int var14 = 0;

      for (int var16 : var12.values()) {
         var14 += var16;
      }

      String var25 = (var23 ? "[+]  " : "[-]  ") + var1.player + (var9 ? "" : "  (offline)");
      float var26 = Style.lineHeight("graphics/fonts/orbitron20aabold.fnt");
      Style.text(var25, "graphics/fonts/orbitron20aabold.fnt", var8, var3 + 12.0F, var4 + (var6 + var26) / 2.0F, var5 * 0.45F);
      int var17 = WishList.canBuy(var1.player, null)[0];
      Style.text(
         var14 + " wished" + (var13.isEmpty() ? "" : ",  " + var13.size() + " set aside") + (var23 && var17 > 0 ? ",  " + var17 + " for sale here" : ""),
         var7,
         var23 && var17 > 0 ? Panel.GOOD : Style.DIM,
         var3 + 20.0F + Style.width(var25, "graphics/fonts/orbitron20aabold.fnt"),
         var4 + (var6 + Style.lineHeight(var7)) / 2.0F,
         260.0F
      );
      float var18 = 24.0F;
      float var19 = var4 + (var6 - var18) / 2.0F;
      float var20 = var3 + var5 - 8.0F;
      if (!var12.isEmpty()) {
         float var21 = 110.0F;
         var20 -= var21;
         boolean var22 = LobbyScreen.inside(var20, var19, var21, var18);
         Style.button(var20, var19, var21, var18, "CLEAR LIST", Panel.BAD, true, var22);
         this.hits.add(new WishListPanel.Hit(var20, var19, var21, var18, 3, var1.player, null));
         var20 -= 8.0F;
      }

      if (!var13.isEmpty()) {
         float var29 = 140.0F;
         var20 -= var29;
         boolean var30 = LobbyScreen.inside(var20, var19, var29, var18);
         Style.button(var20, var19, var29, var18, "FREE SET-ASIDE", Panel.WARN, true, var30);
         this.hits.add(new WishListPanel.Hit(var20, var19, var29, var18, 2, var1.player, null));
      }
   }

   private void drawItem(WishListPanel.Row var1, float var2, float var3, float var4, float var5, String var6, boolean var7) {
      boolean var8 = LobbyScreen.inside(var2, var3 + 2.0F, var4, var5 - 4.0F);
      LobbyScreen.fill(var2 + 10.0F, var3 + 2.0F, var4 - 10.0F, var5 - 4.0F, var8 ? ROW_OVER : ROW);
      float var9 = var5 - 10.0F;
      RefitScreen.iconBox(RefitScreen.picture(var1.key, false), var2 + 16.0F, var3 + 5.0F, var9, false);
      float var10 = var2 + 16.0F + var9 + 10.0F;
      WishList.Offer var11 = WishList.bestOffer(var1.player, var1.key);
      int[] var12 = WishList.canBuy(var1.player, var1.key);
      float var13 = 0.0F;
      if (var12[0] > 0) {
         String var14 = "BUY " + var12[0] + "  -  " + LobbyScreen.grouped((long)var12[1]) + " CR";
         var13 = Math.max(150.0F, Style.width(var14.toUpperCase(), "graphics/fonts/orbitron12condensed.fnt") + 30.0F);
         float var15 = 28.0F;
         float var16 = var2 + var4 - 8.0F - var13;
         float var17 = var3 + (var5 - var15) / 2.0F;
         Style.button(var16, var17, var13, var15, var14, Panel.GOOD, true, LobbyScreen.inside(var16, var17, var13, var15));
         this.hits.add(new WishListPanel.Hit(var16, var17, var13, var15, 0, var1.player, var1.key));
      }

      float var18 = var4 - (var10 - var2) - var13 - 20.0F;
      Style.text(WishList.name(var1.key) + (var1.count > 1 ? "   x" + var1.count : ""), var6, Panel.TEXT, var10, var3 + var5 - 6.0F, var18);
      String var19 = var11 != null
         ? "For sale here: "
            + var11.subName
            + ",  "
            + LobbyScreen.grouped((long)Math.round(var11.price))
            + " cr each"
            + (var11.stock < var1.count ? "  (" + var11.stock + " in stock)" : "")
         : (var7 ? "Not for sale here" : "Looked for at each market you dock at");
      Style.text(var19, var6, var11 != null ? Panel.GOOD : Style.DIM, var10, var3 + var5 / 2.0F - 1.0F, var18);
   }

   private void drawAside(WishListPanel.Row var1, float var2, float var3, float var4, float var5, String var6) {
      Style.text(
         WishList.name(var1.key) + (var1.count > 1 ? "  x" + var1.count : ""),
         var6,
         Panel.GOOD,
         var2 + 40.0F,
         var3 + (var5 + Style.lineHeight(var6)) / 2.0F,
         var4 - 44.0F
      );
   }

   private void drawAsideHead(WishListPanel.Row var1, float var2, float var3, float var4, float var5) {
      boolean var6 = this.folded.contains("aside:" + var1.player);
      boolean var7 = LobbyScreen.inside(var2 + 10.0F, var3, var4 - 10.0F, var5);
      String var8 = (var6 ? "[+]  " : "[-]  ") + "SET ASIDE IN YOUR CARGO  (" + var1.count + ")";
      Style.text(
         var8,
         "graphics/fonts/orbitron12condensed.fnt",
         var7 ? Style.GOOD : Style.alpha(Style.GOOD, 200),
         var2 + 22.0F,
         var3 + (var5 + Style.lineHeight("graphics/fonts/orbitron12condensed.fnt")) / 2.0F,
         var4 - 30.0F
      );
      this.hits.add(new WishListPanel.Hit(var2 + 10.0F, var3, var4 - 10.0F, var5, 5, "aside:" + var1.player, null));
   }

   private void drawButton(int var1, int var2) {
      String var3 = var2 > 0 ? "WISH LISTS:  " + var2 + " FOR SALE HERE     CTRL+SHIFT+L" : "WISH LISTS  (" + var1 + ")     CTRL+SHIFT+L";
      if (this.buttonLabel == null || !var3.equals(this.buttonText)) {
         this.buttonLabel = Global.getSettings().createLabel(var3, "graphics/fonts/orbitron12condensed.fnt");
         this.buttonText = var3;
      }

      this.buttonLabel.setColor(var2 > 0 ? new Color(150, 255, 170) : new Color(120, 210, 255));
      float var4 = this.buttonLabel.computeTextWidth(var3) + 30.0F;
      float var5 = this.buttonLabel.getPosition().getHeight() + 16.0F;
      float var6 = Global.getSettings().getScreenWidth();
      float var7 = Global.getSettings().getScreenHeight();
      float var8 = var6 - var4 - 10.0F;
      float var9 = Math.min(var7, FriendRefitPanel.bottom) - var5 - 10.0F;
      boolean var10 = LobbyScreen.inside(var8, var9, var4, var5);
      boolean var11 = var2 > 0 && System.currentTimeMillis() / 500L % 2L == 0L;
      Style.frame(
         var8,
         var9,
         var4,
         var5,
         var10 ? new Color(14, 26, 38, 240) : (var11 ? new Color(16, 44, 24, 235) : new Color(6, 10, 16, 225)),
         !var10 && !var11 ? Style.EDGE : Style.GOOD,
         6.0F
      );
      Style.fill(var8 + 1.0F, var9 + 1.0F, 3.0F, var5 - 2.0F, var2 > 0 ? Style.GOOD : Style.ACCENT);
      this.buttonLabel.getPosition().setLocation(var8 + 16.0F, var9 + 8.0F);
      this.buttonLabel.render(1.0F);
      this.button = new float[]{var8, var9, var4, var5};
   }

   private static final class Hit {
      final float x;
      final float y;
      final float w;
      final float h;
      final int action;
      final String player;
      final String key;

      Hit(float var1, float var2, float var3, float var4, int var5, String var6, String var7) {
         this.x = var1;
         this.y = var2;
         this.w = var3;
         this.h = var4;
         this.action = var5;
         this.player = var6;
         this.key = var7;
      }

      boolean has(float var1, float var2) {
         return var1 >= this.x && var1 <= this.x + this.w && var2 >= this.y && var2 <= this.y + this.h;
      }
   }

   private static final class Row {
      final int kind;
      final String player;
      final String key;
      final int count;

      Row(int var1, String var2, String var3, int var4) {
         this.kind = var1;
         this.player = var2;
         this.key = var3;
         this.count = var4;
      }

      float height() {
         return this.kind == 0 ? 36.0F : (this.kind == 1 ? 46.0F : (this.kind != 2 && this.kind != 4 ? 14.0F : 24.0F));
      }
   }
}
