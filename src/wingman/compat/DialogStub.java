package wingman.compat;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.campaign.CampaignEntityPickerListener;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoPickerListener;
import com.fs.starfarer.api.campaign.CommDirectoryAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.CustomDialogDelegate;
import com.fs.starfarer.api.campaign.CustomProductionPickerDelegate;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetMemberPickerListener;
import com.fs.starfarer.api.campaign.GroundRaidTargetPickerDelegate;
import com.fs.starfarer.api.campaign.IndustryPickerListener;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.VisualPanelAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;

/**
 * An interaction dialog that does nothing, for a mod's custom panel that only asks it for the target and
 * dismisses itself: Exotica's and Bionic Alteration's menus both take one.
 */
final class DialogStub implements InteractionDialogAPI {
    private final SectorEntityToken target;
    DialogStub(SectorEntityToken target) { this.target = target; }
    public void setTextWidth(float width) {}
    public void setTextHeight(float height) {}
    public void setXOffset(float xOffset) {}
    public void setYOffset(float yOffset) {}
    public void setPromptText(String promptText) {}
    public void hideTextPanel() {}
    public void showTextPanel() {}
    public float getTextWidth() { return 0f; }
    public float getTextHeight() { return 0f; }
    public float getXOffset() { return 0f; }
    public float getYOffset() { return 0f; }
    public String getPromptText() { return null; }
    public void flickerStatic(float in, float out) {}
    public OptionPanelAPI getOptionPanel() { return null; }
    public TextPanelAPI getTextPanel() { return null; }
    public VisualPanelAPI getVisualPanel() { return null; }
    public SectorEntityToken getInteractionTarget() { return target; }
    public InteractionDialogPlugin getPlugin() { return null; }
    public void setOptionOnEscape(String text, Object optionId) {}
    public void startBattle(BattleCreationContext context) {}
    public void dismiss() {}
    public void dismissAsCancel() {}
    public void showFleetMemberPickerDialog(String title, String okText, String cancelText, int rows, int cols, float iconSize,
            boolean canPickNotReady, boolean canPickMultiple, List<FleetMemberAPI> pool, FleetMemberPickerListener listener) {}
    public void showCustomDialog(float customPanelWidth, float customPanelHeight, CustomDialogDelegate delegate) {}
    public void hideVisualPanel() {}
    public void showCommDirectoryDialog(CommDirectoryAPI dir) {}
    public void setOptionOnConfirm(String text, Object optionId) {}
    public void setOpacity(float opacity) {}
    public void setBackgroundDimAmount(float backgroundDimAmount) {}
    public void setPlugin(InteractionDialogPlugin plugin) {}
    public void setInteractionTarget(SectorEntityToken interactionTarget) {}
    public void showCargoPickerDialog(String title, String okText, String cancelText, boolean small, float textPanelWidth,
            CargoAPI cargo, CargoPickerListener listener) {}
    public void showIndustryPicker(String title, String okText, MarketAPI market, List<Industry> industries, IndustryPickerListener listener) {}
    public void makeOptionOpenCore(String optionId, CoreUITabId tabId, CampaignUIAPI.CoreUITradeMode mode) {}
    public void makeOptionOpenCore(String optionId, CoreUITabId tabId, CampaignUIAPI.CoreUITradeMode mode, boolean onlyShowTargetTabShortcut) {}
    public void setOptionColor(Object optionId, Color color) {}
    public void makeStoryOption(Object optionId, int storyPoints, float bonusXPFraction, String soundId) {}
    public void addOptionSelectedText(Object optionId) {}
    public void addOptionSelectedText(Object optionId, boolean allowPrintingStoryOption) {}
    public void showFleetMemberRecoveryDialog(String title, List<FleetMemberAPI> pool, FleetMemberPickerListener listener) {}
    public void showFleetMemberRecoveryDialog(String title, List<FleetMemberAPI> pool, List<FleetMemberAPI> storyPool, FleetMemberPickerListener listener) {}
    public void showGroundRaidTargetPicker(String title, String okText, MarketAPI market, List<GroundRaidObjectivePlugin> data,
            GroundRaidTargetPickerDelegate listener) {}
    public void showVisualPanel() {}
    public void showCustomProductionPicker(CustomProductionPickerDelegate delegate) {}
    public void showCampaignEntityPicker(String title, String selectedText, String okText, FactionAPI factionForUIColors,
            List<SectorEntityToken> entities, CampaignEntityPickerListener listener) {}
    public boolean isCurrentOptionHadAConfirm() { return false; }
    public void showCustomVisualDialog(float customPanelWidth, float customPanelHeight, CustomVisualDialogDelegate delegate) {}
    public void showCargoPickerDialog(String title, String okText, String cancelText, boolean small, float textPanelWidth,
            float width, float height, CargoAPI cargo, CargoPickerListener listener) {}
}