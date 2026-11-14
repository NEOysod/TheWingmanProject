package wingmanpvp;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.lwjgl.opengl.GL11;
import wingman.api.Pvp;
import wingman.api.WingmanAPI;

public class PvpBattlePlugin extends BaseEveryFrameCombatPlugin {
   private static final int KEY_SPACE = 57;
   private static final int KEY_ENTER = 28;
   private static final Color TEXT = new Color(220, 230, 240);
   private static final Color DIM = new Color(150, 165, 180);
   private static final Color BLUE = new Color(90, 170, 255);
   private static final Color RED = new Color(255, 100, 90);
   private static final Color GOLD = new Color(255, 205, 90);
   private static PvpRules rules;
   private static CombatEngineAPI rulesFor;
   private static final String[] captains = new String[]{"", ""};
   private static final Map<String, String> actingFor = new HashMap<>();
   private static boolean resultSent;
   private static float battleSeconds;
   private CombatEngineAPI engine;
   private long lastReal;
   private long lastStateAt;
   private boolean admiralQuiet;
   private boolean atTitleSent;
   private String lastStateKey = "";
   private static final Map<String, LabelAPI> labels = new HashMap<>();

   public void init(CombatEngineAPI var1) {
      this.engine = var1;
      this.lastReal = System.nanoTime();
   }

   private static boolean hosting() {
      PvpSession var0 = PvpSession.get();
      return var0 != null && var0.role == PvpSession.Role.HOST && var0.stage == PvpSession.Stage.BATTLE && Pvp.active();
   }

   private static boolean joining() {
      PvpSession var0 = PvpSession.get();
      return var0 != null && var0.role == PvpSession.Role.JOINER && Pvp.active();
   }

   static void request(String var0, byte var1) {
      if (rules != null && hosting()) {
         int var2 = Pvp.sideOf(var0);
         boolean var3 = Pvp.isCaptain(var0);
         if (var1 == 1 && var3) {
            rules.captainPause(var2, var0);
         } else if (var1 == 2 && var3) {
            rules.captainResume(var2, var0);
         } else if (var1 == 3) {
            rules.back(var0);
         }
      }
   }

   static void dropped(String var0) {
      if (rules != null && hosting()) {
         rules.drop(var0);
         if (Pvp.isCaptain(var0)) {
            int var1 = Pvp.sideOf(var0);
            String var2 = null;

            for (int var4 : WingmanAPI.players()) {
               String var5 = WingmanAPI.playerName(var4);
               if (var5 != null && !var5.equalsIgnoreCase(var0) && Pvp.sideOf(var5) == var1) {
                  var2 = var5;
                  break;
               }
            }

            if (var1 == 0 && var2 == null && !WingmanAPI.localName().equalsIgnoreCase(var0)) {
               var2 = WingmanAPI.localName();
            }

            Pvp.setCaptain(var0, var2);
            captains[var1] = var2 == null ? "" : var2;
            rules.setCaptainHere(var1, var2 != null);
            if (var2 != null) {
               actingFor.put(var2.toLowerCase(), var0);
               line(var2 + " commands " + teamName(var1) + " until " + var0 + " is back.");
            }
         }
      }
   }

   static void rejoined(String var0) {
      if (rules != null && hosting()) {
         for (Entry var2 : new HashMap<>(actingFor).entrySet()) {
            if (((String)var2.getValue()).equalsIgnoreCase(var0)) {
               int var3 = Pvp.sideOf(var0);
               Pvp.setCaptain((String)var2.getKey(), var0);
               captains[var3] = var0;
               rules.setCaptainHere(var3, true);
               actingFor.remove(var2.getKey());
               line(var0 + " commands " + teamName(var3) + " again.");
            }
         }

         line(var0 + " reconnected. They press Enter once they are in their ship and ready.");
      }
   }

   private static String teamName(int var0) {
      PvpSession var1 = PvpSession.get();
      return var1 != null && var1.match != null ? var1.match.teamNames[var0] : (var0 == 0 ? "Team A" : "Team B");
   }

   private static void line(String var0) {
      WingmanAPI.send("wingman_pvp:battle", BattleProto.line(var0));
      CombatEngineAPI var1 = Global.getCombatEngine();

      try {
         if (var1 != null && var1.getCombatUI() != null) {
            var1.getCombatUI().addMessage(1, new Object[]{GOLD, "PvP: " + var0});
         }
      } catch (Throwable var3) {
      }
   }

   public void processInputPreCoreControls(float var1, List<InputEventAPI> var2) {
      if (this.engine != null && var2 != null && Global.getCurrentState() == GameState.COMBAT) {
         try {
            if (hosting() && rules != null && rulesFor == this.engine && WingmanAPI.hostBattlePhase() == 2) {
               for (InputEventAPI var10 : var2) {
                  if (!var10.isConsumed() && var10.isKeyDownEvent() && var10.getEventValue() == 57) {
                     var10.consume();
                     String var11 = WingmanAPI.localName();
                     if (Pvp.isCaptain(var11)) {
                        rules.captainPause(0, var11);
                     } else {
                        line("Only " + (captains[0].isEmpty() ? "the captain" : captains[0]) + " can pause for your side.");
                     }
                  }
               }
            } else if (joining() && !this.engine.isSimulation()) {
               for (InputEventAPI var4 : var2) {
                  if (!var4.isConsumed() && var4.isKeyDownEvent()) {
                     int var5 = var4.getEventValue();
                     BattleProto.BattleState var6 = PvpSession.get().battleState;
                     if (var5 == 57) {
                        var4.consume();
                        if (Pvp.localCaptain()) {
                           int var7 = var6 != null && var6.pausedBy == 2 ? 2 : 1;
                           WingmanAPI.send("wingman_pvp:battle", BattleProto.simple((byte)var7));
                        }
                     } else if (var5 == 28 && var6 != null && var6.pausedBy == 2 && droppedHere(var6)) {
                        var4.consume();
                        WingmanAPI.send("wingman_pvp:battle", BattleProto.simple((byte)3));
                     }
                  }
               }
            }
         } catch (Throwable var8) {
            Global.getLogger(PvpBattlePlugin.class).warn("Wingman PvP: keys", var8);
         }
      }
   }

   private static boolean droppedHere(BattleProto.BattleState var0) {
      String var1 = PvpSession.myName();

      for (String var3 : var0.dropped) {
         if (var3.equalsIgnoreCase(var1)) {
            return true;
         }
      }

      return false;
   }

   public void advance(float var1, List<InputEventAPI> var2) {
      if (this.engine != null) {
         long var3 = System.nanoTime();
         float var5 = Math.min(0.5F, (float)(var3 - this.lastReal) / 1.0E9F);
         this.lastReal = var3;

         try {
            if (Global.getCurrentState() == GameState.TITLE) {
               this.title();
               return;
            }

            if (Global.getCurrentState() != GameState.COMBAT) {
               return;
            }

            if (hosting()) {
               this.host(var1, var5);
            } else if (joining()) {
               this.joiner();
            }
         } catch (Throwable var7) {
            Global.getLogger(PvpBattlePlugin.class).error("Wingman PvP: battle rules failed this frame", var7);
         }
      }
   }

   private void host(float var1, float var2) {
      int var3 = WingmanAPI.hostBattlePhase();
      if (rulesFor != this.engine) {
         rules = new PvpRules();
         rulesFor = this.engine;
         resultSent = false;
         battleSeconds = 0.0F;
         actingFor.clear();
         String[] var4 = PvpSession.get().captainsBySide();
         captains[0] = var4[0];
         captains[1] = var4[1];
         this.admiralQuiet = false;
      }

      this.holdTime();
      this.noRetreat();
      this.quietAdmiral();
      if (var3 == 2) {
         boolean var8 = !this.engine.isPaused();
         if (var8) {
            battleSeconds += var1;
         }

         rules.tick(var8 ? var1 : 0.0F, var2);
         boolean var5 = rules.paused();
         if (var5 != this.engine.isPaused()) {
            this.engine.setPaused(var5);
         }

         for (String var7 : rules.takeEvents()) {
            line(var7);
         }

         this.sendState();
         if (!resultSent && battleSeconds > 3.0F && this.engine.isCombatOver()) {
            resultSent = true;
            BattleProto.Result var9 = this.results();
            PvpSession var10 = PvpSession.get();
            var10.result = var9;
            WingmanAPI.send("wingman_pvp:battle", BattleProto.result(var9));
         }
      }
   }

   private void holdTime() {
      if (this.engine.getTimeMult().getModifiedValue() != 1.0F) {
         this.engine.getTimeMult().unmodify();
      }
   }

   private void noRetreat() {
      for (int var1 = 0; var1 < 2; var1++) {
         CombatFleetManagerAPI var2 = this.engine.getFleetManager(var1);
         if (!var2.getTaskManager(false).isPreventFullRetreat()) {
            var2.getTaskManager(false).setPreventFullRetreat(true);
         }
      }

      for (ShipAPI var6 : this.engine.getShips()) {
         if (var6.isRetreating() && !var6.isFighter() && var6.isAlive()) {
            CombatFleetManagerAPI var3 = this.engine.getFleetManager(var6.getOwner());
            DeployedFleetMemberAPI var4 = var3.getDeployedFleetMember(var6);
            if (var4 != null) {
               var3.getTaskManager(false).orderSearchAndDestroy(var4, false);
            }

            var6.setRetreating(false, false);
         }
      }
   }

   private void quietAdmiral() {
      if (!this.admiralQuiet) {
         try {
            if (this.engine.getFleetManager(1).getAdmiralAI() == null) {
               return;
            }

            this.engine.getFleetManager(1).getAdmiralAI().setNoOrders(true);
            this.admiralQuiet = true;
         } catch (Throwable var2) {
            this.admiralQuiet = true;
            Global.getLogger(PvpBattlePlugin.class).warn("Wingman PvP: the other side's admiral could not be told to leave orders to its captain", var2);
         }
      }
   }

   private void sendState() {
      BattleProto.BattleState var1 = BattleProto.BattleState.of(rules, captains);
      String var2 = var1.pausedBy
         + "|"
         + (int)Math.ceil((double)var1.countdown)
         + "|"
         + (int)var1.pausedFor
         + "|"
         + (int)var1.charges[0]
         + "|"
         + (int)var1.charges[1]
         + "|"
         + var1.voted[0]
         + var1.voted[1]
         + "|"
         + captains[0]
         + "|"
         + captains[1]
         + "|"
         + var1.dropped;
      long var3 = System.currentTimeMillis();
      if (!var2.equals(this.lastStateKey) || var3 - this.lastStateAt >= 2000L) {
         this.lastStateKey = var2;
         this.lastStateAt = var3;
         PvpSession.get().battleState = var1;
         WingmanAPI.send("wingman_pvp:battle", BattleProto.state(var1));
      }
   }

   private BattleProto.Result results() {
      PvpSession var1 = PvpSession.get();
      PvpBattle.Match var2 = var1.match;
      BattleProto.Result var3 = new BattleProto.Result();
      var3.seconds = battleSeconds;
      var3.teamNames = (String[])var2.teamNames.clone();
      HashMap var4 = new HashMap();

      for (Entry var6 : var2.sideOf.entrySet()) {
         BattleProto.PlayerResult var7 = new BattleProto.PlayerResult();
         var7.name = (String)var6.getKey();
         var7.side = (Integer)var6.getValue();
         var7.captain = var2.captains.contains(var6.getKey());
         var4.put((String)var6.getKey(), var7);
         var3.players.add(var7);
      }

      boolean[] var14 = new boolean[]{false, false};

      for (int var15 = 0; var15 < 2; var15++) {
         CombatFleetManagerAPI var16 = this.engine.getFleetManager(var15);
         List<FleetMemberAPI> var8 = new ArrayList<FleetMemberAPI>(var16.getDestroyedCopy());
         var8.addAll(var16.getDisabledCopy());
         List<FleetMemberAPI> var9 = new ArrayList<FleetMemberAPI>(var16.getDeployedCopy());
         var9.addAll(var16.getReservesCopy());

         for (FleetMemberAPI var11 : var9) {
            BattleProto.PlayerResult var12 = (BattleProto.PlayerResult)var4.get(var2.ownerOf.get(var11.getId()));
            if (var12 != null) {
               ShipAPI var13 = var16.getShipFor(var11);
               if (var13 == null || var13.isAlive() && !var13.isHulk()) {
                  var14[var15] = true;
                  var12.shipsLeft.add(var2.nameOf.get(var11.getId()));
                  var12.pointsBrought = var12.pointsBrought + points(var2, var11);
               } else {
                  var8.add(var11);
               }
            }
         }

         for (FleetMemberAPI var18 : var8) {
            BattleProto.PlayerResult var19 = (BattleProto.PlayerResult)var4.get(var2.ownerOf.get(var18.getId()));
            if (var19 != null && !var19.shipsLost.contains(var2.nameOf.get(var18.getId()))) {
               var19.shipsLost.add(var2.nameOf.get(var18.getId()));
               var19.pointsBrought = var19.pointsBrought + points(var2, var18);
               var19.pointsLost = var19.pointsLost + points(var2, var18);
            }
         }
      }

      var3.winner = var14[0] && !var14[1] ? 0 : (var14[1] && !var14[0] ? 1 : -1);
      return var3;
   }

   private static float points(PvpBattle.Match var0, FleetMemberAPI var1) {
      Float var2 = var0.pointsOf.get(var1.getId());
      return var2 == null ? 0.0F : var2;
   }

   private void joiner() {
      PvpSession var1 = PvpSession.get();

      for (String var3 : var1.feed) {
         try {
            this.engine.getCombatUI().addMessage(1, new Object[]{GOLD, "PvP: " + var3});
         } catch (Throwable var5) {
         }
      }

      var1.feed.clear();
   }

   private void title() {
      PvpSession var1 = PvpSession.get();
      if (var1 != null && var1.role == PvpSession.Role.JOINER) {
         WingmanAPI.deliver();
         if (var1.stage == PvpSession.Stage.TO_TITLE && !this.atTitleSent && "connected".equals(WingmanAPI.joinState())) {
            this.atTitleSent = true;
            WingmanAPI.send("wingman_pvp:battle", BattleProto.simple((byte)4));
         }
      }
   }

   public void renderInUICoords(ViewportAPI var1) {
      if (this.engine != null) {
         try {
            if (Global.getCurrentState() == GameState.TITLE) {
               this.drawResults();
               return;
            }

            BattleProto.BattleState var2 = null;
            boolean var3;
            int var4;
            if (hosting() && rules != null) {
               var2 = PvpSession.get().battleState;
               var3 = Pvp.isCaptain(WingmanAPI.localName());
               var4 = 0;
            } else {
               if (!joining()) {
                  return;
               }

               var2 = PvpSession.get().battleState;
               var3 = Pvp.localCaptain();
               var4 = Pvp.localSide();
            }

            if (var2 == null) {
               return;
            }

            float var5 = Global.getSettings().getScreenWidth();
            float var6 = Global.getSettings().getScreenHeight();
            if (var3) {
               float var7 = var2.charges[var4];
               String var8 = "Pauses: "
                  + (int)Math.floor((double)var7)
                  + (var7 < 3.0F ? "  (next in " + Math.round((1.0F - (var7 - (float)Math.floor((double)var7))) * 60.0F) + " s)" : "")
                  + "   Space to pause";
               text(var8, DIM, var5 / 2.0F - 160.0F, var6 - 40.0F, 320.0F, false);
            }

            if (!var2.paused()) {
               return;
            }

            fill(0.0F, var6 * 0.62F, var5, 110.0F, new Color(6, 10, 16, 170));
            if (var2.countdown > 0.0F) {
               text(String.valueOf((int)Math.ceil((double)var2.countdown)), GOLD, var5 / 2.0F - 100.0F, var6 * 0.62F + 100.0F, 200.0F, true);
               return;
            }

            String var13;
            String var14;
            if (var2.pausedBy == 2) {
               var13 = "PAUSED: " + join(var2.dropped) + " disconnected";
               boolean var15 = droppedHere(var2);
               var14 = var15
                  ? "Press Enter when you are in your ship and ready."
                  : "Waiting for them, or for every captain to press Space to go on"
                     + (!var2.voted[0] && !var2.voted[1] ? "" : " (" + (var2.voted[0] ? var2.captains[0] : var2.captains[1]) + " is ready)")
                     + ".";
            } else {
               String var9 = var2.captains[var2.pausedBy];
               var13 = "PAUSED by " + (var9.isEmpty() ? "a captain" : var9) + "  (" + Math.max(0, Math.round(60.0F - var2.pausedFor)) + " s)";
               boolean var10 = var3 && var2.pausedBy == var4;
               var14 = var10 ? "Space to resume." : "It resumes when they press Space, or when the time runs out.";
            }

            text(var13, var2.pausedBy == 2 ? RED : GOLD, var5 / 2.0F - 400.0F, var6 * 0.62F + 92.0F, 800.0F, true);
            text(var14, TEXT, var5 / 2.0F - 400.0F, var6 * 0.62F + 40.0F, 800.0F, false);
         } catch (Throwable var11) {
         }
      }
   }

   private void drawResults() {
      PvpSession var1 = PvpSession.get();
      if (var1 != null && var1.role == PvpSession.Role.JOINER) {
         float var2 = Global.getSettings().getScreenWidth();
         float var3 = Global.getSettings().getScreenHeight();
         List<String> var4 = new ArrayList<String>();
         Color var5 = GOLD;
         if (var1.result != null) {
            var4 = BattleProto.describe(var1.result, PvpSession.myName());
            var4.add("");
            var4.add("Load your save to go back to the campaign (it was saved as you left; the battle changed nothing in it). Waiting here takes you into a rematch if the host starts one.");
         } else if (var1.removed != null) {
            var4.add(var1.removed);
            var5 = RED;
         } else {
            if (var1.stage != PvpSession.Stage.TO_TITLE && var1.stage != PvpSession.Stage.BATTLE) {
               return;
            }

            var4.add("PvP match: waiting for the host to start the battle. You go in by yourself when it does.");
         }

         float var6 = Math.min(760.0F, var2 - 40.0F);
         float var7 = (var2 - var6) / 2.0F;
         float var8 = var3 - 30.0F;
         float var9 = 30.0F + 20.0F * (float)var4.size();
         fill(var7 - 10.0F, var8 - var9, var6 + 20.0F, var9 + 10.0F, new Color(6, 10, 16, 200));
         float var10 = var8 - 4.0F;

         for (int var11 = 0; var11 < var4.size(); var11++) {
            var10 = text((String)var4.get(var11), var11 == 0 ? var5 : TEXT, var7, var10, var6, false) - 2.0F;
         }
      }
   }

   private static String join(List<String> var0) {
      StringBuilder var1 = new StringBuilder();

      for (String var3 : var0) {
         var1.append(var1.length() == 0 ? "" : ", ").append(var3);
      }

      return var1.toString();
   }

   private static float text(String var0, Color var1, float var2, float var3, float var4, boolean var5) {
      String var6 = var5 ? "graphics/fonts/orbitron24aabold.fnt" : "graphics/fonts/orbitron12condensed.fnt";
      String var7 = var6 + var1.getRGB() + "|" + Math.round(var4) + "|" + var0;
      LabelAPI var8 = labels.get(var7);
      if (var8 == null) {
         if (labels.size() > 200) {
            labels.clear();
         }

         var8 = Global.getSettings().createLabel(var0, var6);
         var8.setColor(var1);
         var8.autoSizeToWidth(var4);
         labels.put(var7, var8);
      }

      float var9 = var8.getPosition().getHeight();
      float var10 = Math.min(var4, var8.computeTextWidth(var0));
      var8.getPosition().setLocation(var2 + (var5 ? (var4 - var10) / 2.0F : 0.0F), var3 - var9);
      var8.render(1.0F);
      return var3 - var9;
   }

   private static void fill(float var0, float var1, float var2, float var3, Color var4) {
      GL11.glPushAttrib(24577);
      GL11.glDisable(3553);
      GL11.glEnable(3042);
      GL11.glBlendFunc(770, 771);
      GL11.glColor4f((float)var4.getRed() / 255.0F, (float)var4.getGreen() / 255.0F, (float)var4.getBlue() / 255.0F, (float)var4.getAlpha() / 255.0F);
      GL11.glBegin(7);
      GL11.glVertex2f(var0, var1);
      GL11.glVertex2f(var0, var1 + var3);
      GL11.glVertex2f(var0 + var2, var1 + var3);
      GL11.glVertex2f(var0 + var2, var1);
      GL11.glEnd();
      GL11.glPopAttrib();
   }
}
