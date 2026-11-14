package wingmanpvp;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;

public final class PvpCampaignScript implements EveryFrameScript {
   private static boolean exitSoon;
   private static int exitDelay;
   private static SectorEntityToken reopen;

   static void exitToTitleSoon() {
      exitSoon = true;
      exitDelay = 3;
   }

   static void reopenStation(SectorEntityToken var0) {
      reopen = var0;
   }

   public boolean isDone() {
      return false;
   }

   public boolean runWhilePaused() {
      return true;
   }

   public void advance(float var1) {
      try {
         PvpSession var2 = PvpSession.get();
         if (var2 != null) {
            var2.advance();
         }

         if (Global.getSector().getCampaignUI().isShowingDialog()) {
            return;
         }

         if (reopen != null) {
            SectorEntityToken var3 = reopen;
            reopen = null;
            Global.getSector().getCampaignUI().showInteractionDialog(var3);
         }

         if (exitSoon && --exitDelay <= 0) {
            exitSoon = false;
            // Saved first, then to the main menu (the game's Save and exit). A game that can't be saved now goes
            // without saving, as before.
            boolean saved = false;
            try {
               saved = Global.getSector().getCampaignUI().cmdSaveAndExit();
            } catch (Throwable t) {
               Global.getLogger(PvpCampaignScript.class).warn("Wingman PvP: save and exit failed", t);
            }

            if (saved) {
               Global.getLogger(PvpCampaignScript.class).info("Wingman PvP: saved, to the main menu for the battle");
            } else {
               Global.getLogger(PvpCampaignScript.class).info("Wingman PvP: the game couldn't be saved now; to the main menu without saving, for the battle");
               Global.getSector().getCampaignUI().cmdExitWithoutSaving();
            }
         }
      } catch (Throwable var4) {
         Global.getLogger(PvpCampaignScript.class).warn("Wingman PvP: campaign script", var4);
      }
   }
}
