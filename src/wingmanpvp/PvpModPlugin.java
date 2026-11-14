package wingmanpvp;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import wingman.api.Pvp;
import wingman.api.WingmanAPI;

public final class PvpModPlugin extends BaseModPlugin {
   private static PvpBarEvent barEvent;

   public void onApplicationLoad() throws Exception {
      try {
         Pvp.active();
         WingmanAPI.hostBattlePhase();
      } catch (LinkageError var2) {
         throw new RuntimeException(
            "Wingman PvP needs a newer The Wingman Project, one with PvP support (the build that came with this version of Wingman PvP)."
         );
      }
   }

   public void onGameLoad(boolean var1) {
      Global.getSector().addTransientScript(new PvpCampaignScript());
      addBar();
   }

   public void beforeGameSave() {
      removeBar();
   }

   public void afterGameSave() {
      addBar();
   }

   private static void addBar() {
      try {
         removeBar();
         barEvent = new PvpBarEvent();
         PortsideBarData.getInstance().addEvent(barEvent);
      } catch (Throwable var1) {
         Global.getLogger(PvpModPlugin.class).warn("Wingman PvP: the bar option could not be added", var1);
      }
   }

   private static void removeBar() {
      try {
         if (barEvent != null) {
            PortsideBarData.getInstance().removeEvent(barEvent);
         }

         barEvent = null;
      } catch (Throwable var1) {
         Global.getLogger(PvpModPlugin.class).warn("Wingman PvP: the bar option could not be taken out before saving", var1);
      }
   }
}
