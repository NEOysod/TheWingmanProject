package wingmanpvp;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate.DialogCallbacks;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TextFieldAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import wingman.api.WingmanAPI;

public final class LobbyPanel implements CustomVisualDialogDelegate, CustomUIPanelPlugin {
   private static final String SMALL = "graphics/fonts/orbitron12condensed.fnt";
   private static final Color TEAM_A = new Color(90, 170, 255);
   private static final Color TEAM_B = new Color(255, 105, 95);
   private final PvpSession session;
   private final LobbyPanel.Owner owner;
   private CustomPanelAPI panel;
   private DialogCallbacks callbacks;
   private float w;
   private float h;
   private int shownVersion = -1;
   private boolean leaving;
   private TooltipMakerAPI teams;
   private TooltipMakerAPI fleet;
   private TooltipMakerAPI chatLog;
   private TooltipMakerAPI header;
   private TooltipMakerAPI bottom;
   private TextFieldAPI chatField;
   private TextFieldAPI limitField;
   private TextFieldAPI rulesField;
   private ButtonAPI readyButton;
   private ButtonAPI startButton;
   private static final float TOP = 52.0F;
   private static final float BOTTOM_BAR = 46.0F;

   public LobbyPanel(PvpSession var1, LobbyPanel.Owner var2) {
      this.session = var1;
      this.owner = var2;
   }

   public static float width() {
      return Math.min(1240.0F, Global.getSettings().getScreenWidth() - 80.0F);
   }

   public static float height() {
      return Math.min(740.0F, Global.getSettings().getScreenHeight() - 100.0F);
   }

   public void init(CustomPanelAPI var1, DialogCallbacks var2) {
      this.panel = var1;
      this.callbacks = var2;
      this.w = var1.getPosition().getWidth();
      this.h = var1.getPosition().getHeight();
      this.buildStatic();
      this.rebuild();
   }

   public CustomUIPanelPlugin getCustomPanelPlugin() {
      return this;
   }

   public float getNoiseAlpha() {
      return 0.0F;
   }

   public void advance(float var1) {
      try {
         this.advanceFrame();
      } catch (Throwable var3) {
         Global.getLogger(LobbyPanel.class).warn("Wingman PvP: lobby screen", var3);
      }
   }

   public void reportDismissed(int var1) {
      this.owner.lobbyClosed(this.leaving);
   }

   public void positionChanged(PositionAPI var1) {
   }

   public void renderBelow(float var1) {
   }

   public void render(float var1) {
   }

   public void processInput(List<InputEventAPI> var1) {
      for (InputEventAPI var3 : var1) {
         if (!var3.isConsumed() && var3.isKeyDownEvent() && var3.getEventValue() == 28 && this.chatField != null && this.chatField.hasFocus()) {
            var3.consume();
            this.sendChat();
         }
      }
   }

   public void advanceFrame() {
      if (this.session != PvpSession.get()) {
         this.close(false);
      } else if (this.session.stage == PvpSession.Stage.TO_TITLE && this.session.role == PvpSession.Role.JOINER) {
         this.close(false);
      } else if (this.session.role == PvpSession.Role.HOST && this.session.stage == PvpSession.Stage.TO_TITLE) {
         this.close(false);
      } else if (this.session.removed != null) {
         this.close(true);
      } else {
         if (this.session.version != this.shownVersion) {
            this.rebuild();
         }
      }
   }

   private void close(boolean var1) {
      if (this.callbacks != null) {
         this.leaving = var1;
         DialogCallbacks var2 = this.callbacks;
         this.callbacks = null;
         var2.dismissDialog();
      }
   }

   private float colW(int var1) {
      float var2 = 12.0F;
      float var3 = (this.w - 2.0F * var2) * 0.34F;
      float var4 = (this.w - 2.0F * var2) * 0.33F;
      float var5 = this.w - 2.0F * var2 - var3 - var4;
      return var1 == 0 ? var3 : (var1 == 1 ? var4 : var5);
   }

   private float colX(int var1) {
      float var2 = 12.0F;
      return var1 == 0 ? 0.0F : (var1 == 1 ? this.colW(0) + var2 : this.colW(0) + this.colW(1) + 2.0F * var2);
   }

   private Color base() {
      return Misc.getBasePlayerColor();
   }

   private Color dark() {
      return Misc.getDarkPlayerColor();
   }

   private Color bright() {
      return Misc.getBrightPlayerColor();
   }

   private void buildStatic() {
      boolean var1 = this.session.role == PvpSession.Role.HOST;
      float var2 = this.colX(2);
      float var3 = this.colW(2);
      TooltipMakerAPI var4 = this.panel.createUIElement(var3, 150.0F, false);
      var4.addSectionHeading("Match settings", Alignment.MID, 0.0F);
      if (var1) {
         var4.addPara("Points limit per team (captain's fleet):", 6.0F);
         this.limitField = var4.addTextField(var3 - 90.0F, 26.0F, "graphics/fonts/orbitron12condensed.fnt", 3.0F);
         this.limitField.setText(LobbyHost.fmt(this.session.view.pointsLimit));
         this.limitField.setMaxChars(6);
         var4.addButton("Set", "limit", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, 70.0F, 24.0F, 0.0F)
            .getPosition()
            .rightOfMid(this.limitField, 6.0F);
         var4.addPara("Agreed rules (everyone sees this line):", 6.0F);
         this.rulesField = var4.addTextField(var3 - 90.0F, 26.0F, "graphics/fonts/orbitron12condensed.fnt", 3.0F);
         this.rulesField.setText(this.session.view.rules);
         this.rulesField.setMaxChars(200);
         var4.addButton("Set", "rules", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, 70.0F, 24.0F, 0.0F)
            .getPosition()
            .rightOfMid(this.rulesField, 6.0F);
      }

      this.panel.addUIElement(var4).inTL(var2, 52.0F);
      TooltipMakerAPI var5 = this.panel.createUIElement(var3, 30.0F, false);
      this.chatField = var5.addTextField(var3 - 76.0F, 26.0F, "graphics/fonts/orbitron12condensed.fnt", 0.0F);
      this.chatField.setMaxChars(200);
      ButtonAPI var6 = var5.addButton("Send", "send", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, 70.0F, 26.0F, 0.0F);
      var6.getPosition().rightOfMid(this.chatField, 6.0F);
      this.panel.addUIElement(var5).inTL(var2, this.h - 46.0F - 36.0F);
      this.bottom = this.panel.createUIElement(this.w, 46.0F, false);
      float var7 = 0.0F;
      float var8 = 150.0F;
      this.readyButton = this.bottom.addButton("Ready", "ready", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, var8, 34.0F, 0.0F);
      this.readyButton.getPosition().inTL(var7, 6.0F);
      var7 += var8 + 10.0F;
      this.bottom.addButton("Switch team", "team", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, var8, 34.0F, 0.0F).getPosition().inTL(var7, 6.0F);
      var7 += var8 + 10.0F;
      if (var1) {
         this.startButton = this.bottom
            .addButton("Start the battle", "start", Misc.getPositiveHighlightColor(), this.dark(), Alignment.MID, CutStyle.ALL, 190.0F, 34.0F, 0.0F);
         this.startButton.getPosition().inTL(var7, 6.0F);
      }

      this.bottom
         .addButton("Leave", "leave", Misc.getNegativeHighlightColor(), this.dark(), Alignment.MID, CutStyle.ALL, 120.0F, 34.0F, 0.0F)
         .getPosition()
         .inTL(this.w - 120.0F, 6.0F);
      this.bottom
         .addButton("Hide", "hide", this.base(), this.dark(), Alignment.MID, CutStyle.ALL, 90.0F, 34.0F, 0.0F)
         .getPosition()
         .inTL(this.w - 220.0F, 6.0F);
      this.panel.addUIElement(this.bottom).inTL(0.0F, this.h - 46.0F);
   }

   private void rebuild() {
      this.shownVersion = this.session.version;
      if (this.header != null) {
         this.panel.removeComponent(this.header);
      }

      if (this.teams != null) {
         this.panel.removeComponent(this.teams);
      }

      if (this.fleet != null) {
         this.panel.removeComponent(this.fleet);
      }

      if (this.chatLog != null) {
         this.panel.removeComponent(this.chatLog);
      }

      LobbyState var1 = this.session.view;
      this.header = this.panel.createUIElement(this.w, 46.0F, false);
      this.header.setParaFontOrbitron();
      this.header.addPara("MULTIPLAYER PVP BATTLE", this.bright(), 0.0F);
      this.header.setParaFontDefault();
      this.header.addPara(this.status(), Misc.getGrayColor(), 3.0F);
      this.panel.addUIElement(this.header).inTL(0.0F, 0.0F);
      float var2 = this.h - 52.0F - 46.0F - 6.0F;
      this.teams = this.panel.createUIElement(this.colW(0), var2, true);
      if (var1 == null) {
         this.teams.addPara("Waiting for the host to answer...", 0.0F);
      } else {
         this.team(this.teams, var1, 0);
         this.team(this.teams, var1, 1);
         if (!var1.rules.isEmpty()) {
            this.teams.addSectionHeading("Agreed rules", Alignment.MID, 10.0F);
            this.teams.addPara(var1.rules, Misc.getHighlightColor(), 4.0F);
         }
      }

      this.panel.addUIElement(this.teams).inTL(this.colX(0), 52.0F);
      this.fleet = this.panel.createUIElement(this.colW(1), var2, true);
      this.fleetList(this.fleet, var1);
      this.panel.addUIElement(this.fleet).inTL(this.colX(1), 52.0F);
      float var3 = this.session.role == PvpSession.Role.HOST ? 160.0F : 40.0F;
      this.chatLog = this.panel.createUIElement(this.colW(2), var2 - var3 - 40.0F, true);
      if (this.session.role != PvpSession.Role.HOST && var1 != null) {
         this.chatLog.addPara("Points limit: %s per team", 0.0F, Misc.getHighlightColor(), new String[]{LobbyHost.fmt(var1.pointsLimit)});
      }

      this.chatLog.addSectionHeading("Chat", Alignment.MID, 4.0F);
      ArrayList var4 = new ArrayList();
      if (var1 != null) {
         var4.addAll(var1.chat);
      }

      for (String var6 : this.session.log) {
         var4.add("> " + var6);
      }

      int var9 = Math.max(0, var4.size() - 30);

      for (int var10 = var9; var10 < var4.size(); var10++) {
         String var7 = (String)var4.get(var10);
         Color var8 = var7.startsWith("* ") ? Misc.getGrayColor() : (var7.startsWith("> ") ? Misc.getHighlightColor() : Misc.getTextColor());
         this.chatLog.addPara(var7, var8, 2.0F);
      }

      this.panel.addUIElement(this.chatLog).inTL(this.colX(2), 52.0F + var3);
      LobbyState.Player var11 = this.session.me();
      if (this.readyButton != null) {
         this.readyButton.setText(var11 != null && var11.ready ? "Not ready" : "Ready");
      }

      if (this.startButton != null) {
         String var12 = this.session.host.whyNotStart();
         this.startButton.setEnabled(var12 == null && this.session.stage == PvpSession.Stage.LOBBY);
      }
   }

   private String status() {
      LobbyState var1 = this.session.view;
      if (var1 == null) {
         return this.session.log.isEmpty() ? "Connecting..." : this.session.log.get(this.session.log.size() - 1);
      } else if (this.session.stage == PvpSession.Stage.STARTING) {
         return "Starting: the fleets are on their way to the host...";
      } else if (this.session.role == PvpSession.Role.HOST) {
         String var4 = this.session.host.whyNotStart();
         return var4 == null ? "Everyone is ready: Start the battle when you are." : "Waiting: " + var4 + ".";
      } else {
         LobbyState.Player var2 = this.session.me();
         if (var2 == null) {
            return "Joining...";
         } else if (var2.ready) {
            return "You are ready. The host starts the battle.";
         } else {
            String var3 = whyNotReady(var1, var2);
            return var3 == null ? (var2.captain ? "Pick your team's ships, then press Ready." : "Press Ready when set.") : var3 + ".";
         }
      }
   }

   private static String whyNotReady(LobbyState var0, LobbyState.Player var1) {
      if (!var1.captain) {
         return var1.flagshipId.isEmpty() ? "Choose the ship you fly from your captain's fleet (Fly)" : null;
      } else if (var1.flagshipId.isEmpty()) {
         return "Choose the ship you fly (Fly)";
      } else {
         return var1.points() > var0.pointsLimit + 0.01F
            ? "Over the points limit (" + LobbyHost.fmt(var1.points()) + " / " + LobbyHost.fmt(var0.pointsLimit) + ")"
            : null;
      }
   }

   private void team(TooltipMakerAPI var1, LobbyState var2, int var3) {
      Color var4 = var3 == 0 ? TEAM_A : TEAM_B;
      List<LobbyState.Player> var5 = var2.team(var3);
      var1.addSectionHeading(
         "Team " + LobbyHost.teamName(var3) + "   " + var5.size() + "/" + 4 + "   " + LobbyHost.fmt(var2.teamPoints(var3)) + " points",
         var4,
         Misc.scaleAlpha(var4, 0.25F),
         Alignment.MID,
         var3 == 0 ? 0.0F : 12.0F
      );
      if (var5.isEmpty()) {
         var1.addPara("Nobody yet.", Misc.getGrayColor(), 4.0F);
      }

      boolean var6 = this.session.role == PvpSession.Role.HOST;

      for (LobbyState.Player var8 : var5) {
         String var9 = var8.captain ? "[Captain] " : "";
         String var10 = var8.ready ? "READY" : "not ready";
         String var11 = var8.captain ? LobbyHost.fmt(var8.points()) + " / " + LobbyHost.fmt(var2.pointsLimit) + " pts" : "flies a captain's ship";
         boolean var12 = var8.captain && var8.points() > var2.pointsLimit + 0.01F;
         var1.addPara(
            var9 + "%s" + (var8.id == 0 ? " (host)" : "") + "   %s   %s" + (var8.ping > 0 ? "   " + var8.ping + " ms" : ""),
            6.0F,
            new Color[]{
               var4, var8.ready ? Misc.getPositiveHighlightColor() : Misc.getGrayColor(), var12 ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor()
            },
            new String[]{var8.name, var10, var11}
         );
         String var13 = "";
         LobbyState.Player from = var8.captain ? var8 : var2.captain(var3); // (the ship is in the captain's fleet)

         for (LobbyState.ShipInfo var15 : from == null ? new ArrayList<LobbyState.ShipInfo>() : from.picks) {
            if (var15.memberId.equals(var8.flagshipId)) {
               var13 = var15.hullName + (var15.shipName.isEmpty() ? "" : ", " + var15.shipName);
            }
         }

         var1.addPara(
            "   Flies " + (var13.isEmpty() ? "(nothing picked)" : var13)
               + (var8.captain ? "; brings " + var8.picks.size() + " ship" + (var8.picks.size() == 1 ? "" : "s") + " for the team" : ""),
            Misc.getGrayColor(),
            1.0F
         );
         if (var6 && this.session.stage == PvpSession.Stage.LOBBY) {
            float var18 = 84.0F;
            ButtonAPI var19 = var1.addButton(
               "Captain", new Object[]{"captain", var8.id}, this.base(), this.dark(), Alignment.MID, CutStyle.ALL, var18, 20.0F, 3.0F
            );
            var19.setEnabled(!var8.captain);
            ButtonAPI var16 = var1.addButton("Move", new Object[]{"move", var8.id}, this.base(), this.dark(), Alignment.MID, CutStyle.ALL, var18, 20.0F, 0.0F);
            var16.getPosition().rightOfMid(var19, 4.0F);
            if (var8.id != 0) {
               ButtonAPI var17 = var1.addButton(
                  "Remove", new Object[]{"kick", var8.id}, Misc.getNegativeHighlightColor(), this.dark(), Alignment.MID, CutStyle.ALL, var18, 20.0F, 0.0F
               );
               var17.getPosition().rightOfMid(var16, 4.0F);
            }
         }
      }
   }

   private void fleetList(TooltipMakerAPI var1, LobbyState var2) {
      LobbyState.Player me = this.session.me();
      if (me != null && var2 != null && !me.captain) {
         this.captainsFleet(var1, var2, me);
         return;
      }

      LobbyState.Player var3 = me;
      float var4 = var2 == null ? 0.0F : var2.pointsLimit;
      float var5 = var3 == null ? 0.0F : var3.points();
      var1.addSectionHeading("Your fleet: the team's", Alignment.MID, 0.0F);
      var1.addPara(
         "Bringing %s of %s points",
         4.0F,
         var5 > var4 + 0.01F ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor(),
         new String[]{LobbyHost.fmt(var5), LobbyHost.fmt(var4)}
      );
      var1.addPara(
         "You are captain: only your fleet is used. Tick the ships the team brings; your teammates each pick one of them to fly. Fly picks yours. Mothballed and civilian ships stay home.",
         Misc.getGrayColor(),
         3.0F
      );
      boolean var6 = this.session.stage != PvpSession.Stage.LOBBY || var3 == null;
      float var7 = this.colW(1) - 10.0F;

      for (LobbyState.ShipInfo var9 : this.session.myFleet) {
         boolean var10 = picked(var3, var9.memberId);
         boolean var11 = var3 != null && var9.memberId.equals(var3.flagshipId);
         String var12 = (var11 ? "[FLY] " : "")
            + var9.hullName
            + "   "
            + LobbyHost.fmt(var9.points)
            + " pts\n"
            + (var9.shipName.isEmpty() ? "" : var9.shipName)
            + (var9.officer.isEmpty() ? "" : "  -  " + var9.officer);
         ButtonAPI var13 = var1.addAreaCheckbox(
            var12, new Object[]{"pick", var9.memberId}, this.base(), this.dark(), this.bright(), var7 - 66.0F, 40.0F, 5.0F, true
         );
         var13.setChecked(var10);
         var13.setEnabled(!var6);
         ButtonAPI var14 = var1.addButton(
            "Fly",
            new Object[]{"fly", var9.memberId},
            var11 ? Misc.getPositiveHighlightColor() : this.base(),
            this.dark(),
            Alignment.MID,
            CutStyle.ALL,
            58.0F,
            40.0F,
            0.0F
         );
         var14.getPosition().rightOfMid(var13, 6.0F);
         var14.setEnabled(!var6 && !var11);
      }

      if (this.session.myFleet.isEmpty()) {
         var1.addPara("No ship in your fleet can fight.", Misc.getNegativeHighlightColor(), 6.0F);
      }
   }

   /** Not a captain: the captain's ships, each with Fly (one player a ship). */
   private void captainsFleet(TooltipMakerAPI t, LobbyState s, LobbyState.Player me) {
      LobbyState.Player c = s.captain(me.team);
      t.addSectionHeading("Your captain's fleet", Alignment.MID, 0.0F);
      if (c == null) {
         t.addPara("Your team has no captain yet.", Misc.getGrayColor(), 4.0F);
         return;
      }

      t.addPara("Only captains' fleets are used. Pick the ship you fly from the ones %s brings.", 4.0F, Misc.getHighlightColor(), new String[]{c.name});
      boolean locked = this.session.stage != PvpSession.Stage.LOBBY;
      float w = this.colW(1) - 10.0F;

      for (LobbyState.ShipInfo ship : c.picks) {
         String flownBy = ship.memberId.equals(c.flagshipId) ? c.name : null;
         for (LobbyState.Player o : s.team(me.team)) {
            if (o != c && ship.memberId.equals(o.flagshipId)) {
               flownBy = o.name;
            }
         }

         boolean mine = me.name.equals(flownBy);
         String text = (mine ? "[FLY] " : "") + ship.hullName + "   " + LobbyHost.fmt(ship.points) + " pts\n"
            + (ship.shipName.isEmpty() ? "" : ship.shipName) + (ship.officer.isEmpty() ? "" : "  -  " + ship.officer)
            + (flownBy != null && !mine ? "   (" + flownBy + ")" : "");
         ButtonAPI b = t.addButton(text, new Object[]{"fly", ship.memberId}, mine ? Misc.getPositiveHighlightColor() : this.base(), this.dark(), Alignment.LMID, CutStyle.ALL, w, 40.0F, 5.0F);
         b.setEnabled(!locked && flownBy == null);
      }

      if (c.picks.isEmpty()) {
         t.addPara("Your captain hasn't picked any ships yet.", Misc.getGrayColor(), 6.0F);
      }
   }

   private static boolean picked(LobbyState.Player var0, String var1) {
      if (var0 == null) {
         return false;
      } else {
         for (LobbyState.ShipInfo var3 : var0.picks) {
            if (var3.memberId.equals(var1)) {
               return true;
            }
         }

         return false;
      }
   }

   public void buttonPressed(Object var1) {
      try {
         this.press(var1);
      } catch (Throwable var3) {
         Global.getLogger(LobbyPanel.class).warn("Wingman PvP: lobby button", var3);
         this.session.note("That didn't work: " + var3);
      }
   }

   private void press(Object var1) {
      boolean var2 = this.session.role == PvpSession.Role.HOST;
      LobbyState.Player var3 = this.session.me();
      if ("leave".equals(var1)) {
         this.close(true);
      } else if ("hide".equals(var1)) {
         this.close(false);
      } else if ("send".equals(var1)) {
         this.sendChat();
      } else if (var3 != null) {
         if ("ready".equals(var1)) {
            if (var2) {
               this.session.hostDid(this.session.host.setReady(var3.id, !var3.ready));
            } else {
               this.session.request(Proto.flag((byte)4, !var3.ready));
            }
         } else if ("team".equals(var1)) {
            if (var2) {
               this.session.hostDid(this.session.host.setTeam(var3.id, 1 - var3.team));
            } else {
               this.session.request(Proto.team(1 - var3.team));
            }
         } else if ("start".equals(var1) && var2) {
            this.session.hostStart();
         } else if ("limit".equals(var1) && var2) {
            try {
               this.session.hostDid(this.session.host.setLimit(Float.parseFloat(this.limitField.getText().trim())));
            } catch (NumberFormatException var10) {
               this.session.note("The points limit must be a number.");
            }
         } else if ("rules".equals(var1) && var2) {
            this.session.hostDid(this.session.host.setRules(this.rulesField.getText()));
         } else if (var1 instanceof Object[]) {
            Object[] var4 = (Object[])var1;
            String var5 = (String)var4[0];
            if ("fly".equals(var5) && !var3.captain) { // a ship of the captain's to fly
               String ship = (String)var4[1];
               if (var2) {
                  this.session.hostDid(this.session.host.setPicks(var3.id, ship, new ArrayList<String>()));
               } else {
                  this.session.request(Proto.picks(ship, new ArrayList<String>()));
               }
            } else if ("pick".equals(var5) || "fly".equals(var5)) {
               String var11 = (String)var4[1];
               ArrayList var7 = new ArrayList();

               for (LobbyState.ShipInfo var9 : var3.picks) {
                  var7.add(var9.memberId);
               }

               String var12 = var3.flagshipId;
               if ("fly".equals(var5)) {
                  var12 = var11;
                  if (!var7.contains(var11)) {
                     var7.add(var11);
                  }
               } else if (var7.contains(var11)) {
                  var7.remove(var11);
                  if (var11.equals(var12)) {
                     var12 = var7.isEmpty() ? "" : (String)var7.get(0);
                  }
               } else {
                  var7.add(var11);
                  if (var12.isEmpty()) {
                     var12 = var11;
                  }
               }

               if (var2) {
                  this.session.hostDid(this.session.host.setPicks(var3.id, var12, var7));
               } else {
                  this.session.request(Proto.picks(var12, var7));
               }
            } else if (var2) {
               int var6 = (Integer)var4[1];
               if ("captain".equals(var5)) {
                  this.session.hostDid(this.session.host.makeCaptain(var6));
               } else if ("move".equals(var5)) {
                  this.session.hostDid(this.session.host.moveToOtherTeam(var6));
               } else if ("kick".equals(var5)) {
                  WingmanAPI.sendTo(var6, "wingman_pvp:lobby", Proto.text((byte)23, "The host removed you from the match."));
                  this.session.hostDid(this.session.host.kick(var6));
               }
            }
         }
      }
   }

   private void sendChat() {
      if (this.chatField != null) {
         String var1 = this.chatField.getText().trim();
         if (!var1.isEmpty()) {
            this.chatField.setText("");
            LobbyState.Player var2 = this.session.me();
            if (this.session.role == PvpSession.Role.HOST) {
               if (var2 != null) {
                  this.session.hostDid(this.session.host.chat(var2.id, var1));
               }
            } else {
               this.session.request(Proto.text((byte)5, var1));
            }
         }
      }
   }

   public interface Owner {
      void lobbyClosed(boolean var1);
   }
}
