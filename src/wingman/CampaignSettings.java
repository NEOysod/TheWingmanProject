package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.net.HostSession;

/**
 * Wingman's settings page on the campaign map: Ctrl+Shift+O, as in a battle. The same page as on the title screen
 * (CO-OP panel, F3) and in battle; what is changed is saved as there, and the co-op session the campaign map keeps
 * open for friends (see CampaignLobby) takes it up when the page closes. Registered on each game load as a
 * transient listener: nothing of it goes into the save.
 */
final class CampaignSettings implements CampaignInputListener, CampaignUIRenderingListener {
    private final Settings.Menu menu = new Settings.Menu();
    private final Panel panel = new Panel();
    private boolean broken, wasPaused;

    static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new CampaignSettings(), true);
        } catch (Throwable t) {
            Global.getLogger(CampaignSettings.class).warn("Wingman: the settings page can't be opened on the campaign map (use the title screen or a battle)", t);
        }
    }

    public int getListenerInputPriority() { return 100; }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            if (!menu.open) {
                if (Global.getSector().getCampaignUI() != null && Global.getSector().getCampaignUI().isShowingDialog()) return;
                for (InputEventAPI e : events) { // Ctrl+Shift+O
                    if (e.isConsumed() || !e.isKeyDownEvent() || !e.isCtrlDown() || !e.isShiftDown() || e.getEventValue() != 24) continue;
                    menu.open(null);
                    e.consume();
                    // the campaign waits while the page is up, and runs on as it was when it closes
                    wasPaused = Global.getSector().isPaused();
                    Global.getSector().setPaused(true);
                }
                return;
            }
            menu.keys(events);
            // While the page is up, keys are its own: none moves the fleet or opens a screen underneath.
            for (InputEventAPI e : events) if (!e.isConsumed() && e.isKeyboardEvent()) e.consume();
            if (!menu.open) {
                applyToSession();
                if (!wasPaused) Global.getSector().setPaused(false);
            }
            menu.changed.clear();
        } catch (Throwable t) {
            menu.open = false;
            Global.getLogger(CampaignSettings.class).warn("Wingman: the settings page failed on the campaign map and was closed", t);
        }
    }

    /** The session kept open for friends follows what was changed (password, mod check, players, link...). */
    private static void applyToSession() {
        try {
            if (HostSession.active() != null) Hosting.open(WingmanConfig.load());
        } catch (Throwable t) {
            Global.getLogger(CampaignSettings.class).warn("Wingman: the co-op session couldn't take the new settings; they apply from the next battle", t);
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        if (broken || !menu.open) return;
        try {
            panel.render(menu.doc());
        } catch (Throwable t) {
            menu.open = false;
            Global.getLogger(CampaignSettings.class).warn("Wingman: the settings page could not be drawn on the campaign map and was closed", t);
        }
    }
}
