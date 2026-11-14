package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate.DialogCallbacks;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import wingman.api.WingmanAPI;

public final class PvpDialog implements InteractionDialogPlugin, LobbyPanel.Owner {
   private InteractionDialogAPI dialog;
   private final SectorEntityToken station;
   private boolean lobbyShowing;
   private boolean joinShowing;
   private boolean waitingForBattle;
   private boolean exitingToTitle;
   private long shownVersion = -1L;

   public PvpDialog(SectorEntityToken var1) {
      this.station = var1;
   }

   public static void open(InteractionDialogAPI var0) {
      PvpDialog var1 = new PvpDialog(var0.getInteractionTarget());
      var0.setPlugin(var1);
      var1.init(var0);
   }

   public void init(InteractionDialogAPI var1) {
      this.dialog = var1;
      var1.getVisualPanel().fadeVisualOut();
      PvpSession var2 = PvpSession.get();
      if (var2 != null && var2.role == PvpSession.Role.HOST && var2.stage == PvpSession.Stage.RESULTS) {
         this.showResults();
      } else if (var2 != null) {
         this.showLobby();
      } else {
         this.menu();
      }
   }

   private void menu() {
      this.dialog.getTextPanel().clear();
      this.dialog.getTextPanel().addPara("Multiplayer PvP Battles", Misc.getHighlightColor());
      this.dialog
         .getTextPanel()
         .addPara(
            "Fight a friend's fleet, or a few friends' fleets, through The Wingman Project. One of you hosts, the others join; each brings ships from their own fleet, and every player flies their own flagship. Nothing that happens in the battle changes anyone's save."
         );
      this.dialog
         .getTextPanel()
         .addPara(
            "Joining: after the lobby, your game is saved and goes to the main menu, and joins the battle from there.",
            Misc.getGrayColor()
         );
      this.dialog.getOptionPanel().clearOptions();
      this.dialog.getOptionPanel().addOption("Host a match", PvpDialog.Opt.HOST);
      this.dialog.getOptionPanel().addOption("Join a match", PvpDialog.Opt.JOIN);
      this.dialog.getOptionPanel().addOption("Leave", PvpDialog.Opt.LEAVE);
      this.dialog.getOptionPanel().setShortcut(PvpDialog.Opt.LEAVE, 1, false, false, false, true);
   }

   public void optionSelected(String var1, Object var2) {
      if (var2 instanceof PvpDialog.Opt) {
         try {
            switch ((PvpDialog.Opt)var2) {
               case HOST:
                  this.host();
                  break;
               case JOIN:
                  this.showJoinForm();
                  break;
               case LEAVE:
                  this.leave();
                  break;
               case LOBBY:
                  this.showLobby();
                  break;
               case LEAVE_MATCH:
                  PvpSession.end("left");
                  this.menu();
                  break;
               case CANCEL:
                  this.cancelStart();
                  break;
               case START_NOW:
                  this.startBattle();
                  break;
               case REMATCH:
                  this.rematch();
                  break;
               case BACK_TO_LOBBY:
                  this.backToLobby();
            }
         } catch (Throwable var4) {
            Global.getLogger(PvpDialog.class).error("Wingman PvP: dialog", var4);
            this.dialog.getTextPanel().addPara("Something went wrong: " + var4 + " (see starsector.log).", Misc.getNegativeHighlightColor());
         }
      }
   }

   private void host() {
      CampaignFleetAPI var1 = Global.getSector().getPlayerFleet();
      List<String> var2 = new ArrayList<String>();
      PvpSession var3 = PvpSession.host(FleetBuild.summary(var1), FleetBuild.read(var1, WingmanAPI.localName()), FleetBuild.fleetPoints(var1), var2);
      if (var3 != null) {
         this.showLobby();
      } else {
         for (String var5 : var2) {
            this.dialog.getTextPanel().addPara(var5, Misc.getNegativeHighlightColor());
         }
      }
   }

   private void showLobby() {
      PvpSession var1 = PvpSession.get();
      if (var1 == null) {
         this.menu();
      } else {
         this.dialog.getOptionPanel().clearOptions();
         this.lobbyShowing = true;
         this.dialog.showCustomVisualDialog(LobbyPanel.width(), LobbyPanel.height(), new LobbyPanel(var1, this));
      }
   }

   @Override
   public void lobbyClosed(boolean var1) {
      this.lobbyShowing = false;
      PvpSession var2 = PvpSession.get();
      if (!var1 && var2 != null) {
         if (var2.role == PvpSession.Role.JOINER && var2.stage == PvpSession.Stage.TO_TITLE) {
            this.toTitle();
         } else if (var2.role == PvpSession.Role.HOST && var2.stage == PvpSession.Stage.TO_TITLE) {
            this.waitForJoiners();
         } else {
            this.hidden();
         }
      } else {
         String var3 = var2 == null ? null : var2.removed;
         PvpSession.end("left the lobby");
         this.menu();
         if (var3 != null) {
            this.dialog.getTextPanel().addPara(var3, Misc.getNegativeHighlightColor());
         }
      }
   }

   private void hidden() {
      this.dialog.getTextPanel().clear();
      this.dialog.getTextPanel().addPara("The lobby is still open; the others see you there.");
      this.dialog.getOptionPanel().clearOptions();
      this.dialog.getOptionPanel().addOption("Back to the lobby", PvpDialog.Opt.LOBBY);
      this.dialog.getOptionPanel().addOption("Leave the match", PvpDialog.Opt.LEAVE_MATCH);
   }

   private void showJoinForm() {
      this.joinShowing = true;
      this.dialog.getOptionPanel().clearOptions();
      this.dialog.showCustomVisualDialog(520.0F, 250.0F, new PvpDialog.JoinForm());
   }

   private void joinChosen(String var1, String var2) {
      CampaignFleetAPI var3 = Global.getSector().getPlayerFleet();
      PvpSession.join(var1, var2, FleetBuild.summary(var3), FleetBuild.read(var3, PvpSession.myName()));
      this.dialog.getTextPanel().clear();
      this.dialog.getTextPanel().addPara("Connecting to " + var1 + "...");
      this.dialog.getOptionPanel().clearOptions();
      this.dialog.getOptionPanel().addOption("Cancel", PvpDialog.Opt.LEAVE_MATCH);
   }

   private void toTitle() {
      this.exitingToTitle = true;
      this.dialog.getTextPanel().clear();
      this.dialog
         .getTextPanel()
         .addPara("The battle is starting. Saving and going to the main menu; you join the battle from there by yourself.", Misc.getHighlightColor());
      this.dialog.getOptionPanel().clearOptions();
      PvpCampaignScript.exitToTitleSoon();
      this.dialog.dismiss();
   }

   private void waitForJoiners() {
      this.waitingForBattle = true;
      this.dialog.getTextPanel().clear();
      this.dialog.getTextPanel().addPara("Every fleet is in. The others are on their way to the main menu; the battle starts when they are all there.");
      this.dialog.getOptionPanel().clearOptions();
      this.dialog.getOptionPanel().addOption("Start now without waiting", PvpDialog.Opt.START_NOW);
      this.dialog.getOptionPanel().addOption("Cancel the start", PvpDialog.Opt.CANCEL);
   }

   private void cancelStart() {
      PvpSession var1 = PvpSession.get();
      this.waitingForBattle = false;
      if (var1 != null && var1.role == PvpSession.Role.HOST) {
         PvpSession.end("start cancelled");
      }

      this.menu();
   }

   private void startBattle() {
      this.waitingForBattle = false;
      PvpSession var1 = PvpSession.get();
      if (var1 == null) {
         this.menu();
      } else {
         LinkedHashMap var2 = new LinkedHashMap();
         if (var1.match != null && var1.stage == PvpSession.Stage.RESULTS) {
            var2.putAll(var1.match.packs);
         } else {
            for (Entry var4 : var1.packs().entrySet()) {
               var2.put(var1.view.player((Integer)var4.getKey()).name, (FleetPack)var4.getValue());
            }
         }

         String[] var10 = var1.captainsBySide();
         int var11 = var1.view.player(0).team;
         String[] var5 = new String[]{"Team " + LobbyHost.teamName(var11), "Team " + LobbyHost.teamName(1 - var11)};
         List<String> var6 = new ArrayList<String>();
         Map<String, String> seats = var1.match != null && var1.stage == PvpSession.Stage.RESULTS ? var1.match.seats : var1.seatsByName();
         PvpBattle.Match var7 = PvpBattle.start(this.dialog, var1, var2, var1.sidesByName(), seats, var10, var5, true, var6);
         if (var7 == null) {
            this.dialog.getTextPanel().clear();

            for (String var13 : var6) {
               this.dialog.getTextPanel().addPara(var13, Misc.getNegativeHighlightColor());
            }

            this.dialog.getOptionPanel().clearOptions();
            this.dialog.getOptionPanel().addOption("Leave the match", PvpDialog.Opt.LEAVE_MATCH);
         } else {
            for (String var9 : var7.notes) {
               Global.getLogger(PvpDialog.class).info("Wingman PvP: " + var9);
            }
         }
      }
   }

   public void backFromEngagement(EngagementResultAPI var1) {
      PvpSession var2 = PvpSession.get();
      if (var2 != null && var2.match != null) {
         PvpBattle.restore(var2.match);
         var2.stage = PvpSession.Stage.RESULTS;
         if (var2.result == null) {
            var2.result = new BattleProto.Result();
            var2.result.teamNames = (String[])var2.match.teamNames.clone();
            WingmanAPI.send("wingman_pvp:battle", BattleProto.result(var2.result));
         }

         this.showResults();
      }
   }

   private void showResults() {
      PvpSession var1 = PvpSession.get();
      this.dialog.getTextPanel().clear();
      this.dialog.getTextPanel().addPara("Results", Misc.getHighlightColor());
      if (var1 != null && var1.result != null) {
         for (String var3 : BattleProto.describe(var1.result, WingmanAPI.localName())) {
            this.dialog.getTextPanel().addPara(var3);
         }
      }

      this.dialog.getTextPanel().addPara("Your fleet and cargo are exactly as before the battle.", Misc.getGrayColor());
      this.dialog.getOptionPanel().clearOptions();
      this.dialog
         .getOptionPanel()
         .addOption("Rematch with the same fleets", PvpDialog.Opt.REMATCH, "The others, still on their main menu, go into the new battle by themselves.");
      this.dialog.getOptionPanel().addOption("End the match", PvpDialog.Opt.LEAVE_MATCH);
   }

   private void rematch() {
      PvpSession var1 = PvpSession.get();
      if (var1 != null && var1.match != null) {
         var1.result = null;
         WingmanAPI.send("wingman_pvp:lobby", Proto.empty((byte)22));
         this.startBattle();
      } else {
         this.menu();
      }
   }

   private void backToLobby() {
      this.showLobby();
   }

   private void leave() {
      PvpSession.end("left");
      this.dialog.dismiss();
      PvpCampaignScript.reopenStation(this.station);
   }

   public void advance(float var1) {
      PvpSession var2 = PvpSession.get();
      if (var2 != null) {
         var2.advance();
         if (!this.lobbyShowing && !this.joinShowing && !this.exitingToTitle) {
            if (var2.role == PvpSession.Role.JOINER && var2.view == null) {
               String var3 = WingmanAPI.joinState();
               if (var3.startsWith("failed")) {
                  PvpSession.end("could not connect");
                  this.menu();
                  this.dialog.getTextPanel().addPara("Could not join: " + var3.substring(Math.min(var3.length(), 8)), Misc.getNegativeHighlightColor());
               } else if (var2.removed != null) {
                  String var4 = var2.removed;
                  PvpSession.end("refused");
                  this.menu();
                  this.dialog.getTextPanel().addPara(var4, Misc.getNegativeHighlightColor());
               }
            } else if (var2.role == PvpSession.Role.JOINER && var2.view != null && var2.stage == PvpSession.Stage.LOBBY && this.shownVersion < 0L) {
               this.shownVersion = 0L;
               this.showLobby();
            } else if (var2.role == PvpSession.Role.JOINER && var2.stage == PvpSession.Stage.TO_TITLE) {
               this.toTitle();
            }

            if (this.waitingForBattle && var2.readyForBattle()) {
               this.startBattle();
            }
         }
      }
   }

   public void optionMousedOver(String var1, Object var2) {
   }

   public Object getContext() {
      return null;
   }

   public Map<String, MemoryAPI> getMemoryMap() {
      return null;
   }

   private final class JoinForm implements CustomVisualDialogDelegate, CustomUIPanelPlugin {
      private DialogCallbacks callbacks;
      private TextFieldAPI address;
      private TextFieldAPI password;
      private boolean go;

      private JoinForm() {
      }

      public void init(CustomPanelAPI var1, DialogCallbacks var2) {
         this.callbacks = var2;
         float var3 = var1.getPosition().getWidth();
         String[] var4 = WingmanAPI.savedJoin();
         TooltipMakerAPI var5 = var1.createUIElement(var3, var1.getPosition().getHeight(), false);
         var5.addSectionHeading("Join a match", Alignment.MID, 0.0F);
         var5.addPara("The host's address (as on Wingman's join screen, host or host:port):", 8.0F);
         this.address = var5.addTextField(var3 - 10.0F, 28.0F, "graphics/fonts/orbitron12condensed.fnt", 4.0F);
         this.address.setText(var4[0]);
         var5.addPara("Join password (empty if the host set none):", 8.0F);
         this.password = var5.addTextField(var3 - 10.0F, 28.0F, "graphics/fonts/orbitron12condensed.fnt", 4.0F);
         this.password.setText(var4[2]);
         var5.addPara("You join as " + PvpSession.myName() + " (the name on Wingman's join screen).", Misc.getGrayColor(), 6.0F);
         var5.addButton("Connect", "connect", Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, CutStyle.ALL, 160.0F, 30.0F, 10.0F);
         var5.addButton("Cancel", "cancel", Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, CutStyle.ALL, 120.0F, 30.0F, 0.0F)
            .getPosition()
            .rightOfMid(var5.getPrev(), 10.0F);
         var1.addUIElement(var5).inTL(0.0F, 0.0F);
      }

      public CustomUIPanelPlugin getCustomPanelPlugin() {
         return this;
      }

      public float getNoiseAlpha() {
         return 0.0F;
      }

      public void advance(float var1) {
      }

      public void reportDismissed(int var1) {
         PvpDialog.this.joinShowing = false;
         if (this.go && !this.address.getText().trim().isEmpty()) {
            PvpDialog.this.joinChosen(this.address.getText().trim(), this.password.getText());
         } else {
            PvpDialog.this.menu();
         }
      }

      public void positionChanged(PositionAPI var1) {
      }

      public void renderBelow(float var1) {
      }

      public void render(float var1) {
      }

      public void processInput(List<InputEventAPI> var1) {
         for (InputEventAPI var3 : var1) {
            if (!var3.isConsumed() && var3.isKeyDownEvent() && var3.getEventValue() == 28) {
               var3.consume();
               this.buttonPressed("connect");
            }
         }
      }

      public void buttonPressed(Object var1) {
         if (this.callbacks != null) {
            this.go = "connect".equals(var1);
            DialogCallbacks var2 = this.callbacks;
            this.callbacks = null;
            var2.dismissDialog();
         }
      }
   }

   private static enum Opt {
      HOST,
      JOIN,
      LEAVE,
      LOBBY,
      LEAVE_MATCH,
      CANCEL,
      START_NOW,
      REMATCH,
      BACK_TO_LOBBY;
   }
}
