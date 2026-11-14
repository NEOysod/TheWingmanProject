package wingmanpvp;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEvent;
import com.fs.starfarer.api.util.Misc;
import java.util.Map;

public final class PvpBarEvent extends BaseBarEvent {
   public static final String OPTION = "wingman_pvp_open";

   public boolean isAlwaysShow() {
      return true;
   }

   public boolean shouldShowAtMarket(MarketAPI var1) {
      return var1 != null && !var1.isPlanetConditionMarketOnly();
   }

   public boolean shouldRemoveEvent() {
      return false;
   }

   public void addPromptAndOption(InteractionDialogAPI var1, Map<String, MemoryAPI> var2) {
      super.addPromptAndOption(var1, var2);
      var1.getTextPanel()
         .addPara(
            "In a corner, a few spacers crowd around a battered sim console: \"Fleet against fleet, captains. Real people, no fakes.\"", Misc.getGrayColor()
         );
      var1.getOptionPanel().addOption("Multiplayer PvP Battles", this);
   }

   public void optionSelected(String var1, Object var2) {
      if (var2 == this) {
         PvpDialog.open(this.dialog);
      }
   }

   public String getBarEventId() {
      return "wingman_pvp";
   }
}
