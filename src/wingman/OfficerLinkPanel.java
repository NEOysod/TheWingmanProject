package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.net.HostSession;

/**
 * The host's officer links on the campaign map: Ctrl+Shift+L. Left/Right picks a connected friend, Up/Down one of
 * the host's officers, Enter links them (or takes that link off), Delete takes an officer's link off. A linked officer
 * captains whichever ship that friend picks (OfficerLinks). Registered on each game load as a transient listener.
 */
final class OfficerLinkPanel implements CampaignInputListener, CampaignUIRenderingListener {
    private final Panel panel = new Panel();
    private boolean open, broken, wasPaused;
    private int player, officer;
    private String message = "";
    private Color messageColor = Panel.DIM;

    static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new OfficerLinkPanel(), true);
        } catch (Throwable t) {
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page can't be opened", t);
        }
    }

    public int getListenerInputPriority() { return 100; }

    private static List<HostSession.Player> players() {
        HostSession s = HostSession.active();
        return s == null ? new ArrayList<HostSession.Player>() : new ArrayList<HostSession.Player>(s.getPlayers());
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            for (InputEventAPI e : events) {
                if (e.isConsumed() || !e.isKeyboardEvent()) continue;
                boolean down = e.isKeyDownEvent(), ctrl = e.isCtrlDown(), shift = e.isShiftDown();
                int key = e.getEventValue();
                if (!open) {
                    if (!down || !ctrl || !shift || key != 38) continue; // Ctrl+Shift+L
                    if (Global.getSector().getCampaignUI() != null && Global.getSector().getCampaignUI().isShowingDialog()) continue;
                    open = true;
                    message = "";
                    wasPaused = Global.getSector().isPaused();
                    Global.getSector().setPaused(true);
                    e.consume();
                    continue;
                }
                e.consume(); // while it is up, keys are its own
                if (!down) continue;
                List<HostSession.Player> ps = players();
                List<PersonAPI> os = OfficerLinks.officers();
                if (key == 1 || (key == 38 && ctrl)) { close(); break; }                       // Esc, Ctrl+Shift+L
                else if (key == 203 && !ps.isEmpty()) player = (player + ps.size() - 1) % ps.size(); // Left
                else if (key == 205 && !ps.isEmpty()) player = (player + 1) % ps.size();             // Right
                else if (key == 200 && !os.isEmpty()) officer = (officer + os.size() - 1) % os.size(); // Up
                else if (key == 208 && !os.isEmpty()) officer = (officer + 1) % os.size();           // Down
                else if (key == 211 && !os.isEmpty()) {                                              // Delete
                    PersonAPI o = os.get(Math.min(officer, os.size() - 1));
                    if (OfficerLinks.linkedTo(o) != null) { OfficerLinks.unlink(o); say(o.getNameString() + " is nobody's now.", Panel.GOOD); }
                } else if (key == 28 && !os.isEmpty()) {                                             // Enter
                    if (ps.isEmpty()) { say("No friends connected: link an officer once a friend has joined.", Panel.WARN); continue; }
                    HostSession.Player p = ps.get(Math.min(player, ps.size() - 1));
                    PersonAPI o = os.get(Math.min(officer, os.size() - 1));
                    if (OfficerLinks.key(p.name).equals(OfficerLinks.linkedTo(o))) {
                        OfficerLinks.unlink(o);
                        say(o.getNameString() + " is no longer " + p.name + "'s.", Panel.GOOD);
                    } else {
                        OfficerLinks.link(p.name, o);
                        say(o.getNameString() + " is " + p.name + "'s officer now: they captain the ship " + p.name + " picks.", Panel.GOOD);
                    }
                }
            }
        } catch (Throwable t) {
            broken = true;
            close();
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page failed and was closed", t);
        }
    }

    private void close() {
        if (!open) return;
        open = false;
        try { Global.getSector().setPaused(wasPaused); } catch (Throwable t) { /* no sector */ }
    }

    private void say(String s, Color c) { message = s; messageColor = c; }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        if (broken || !open) return;
        try {
            List<String> lines = new ArrayList<String>();
            List<Color> colors = new ArrayList<Color>();
            List<HostSession.Player> ps = players();
            List<PersonAPI> os = OfficerLinks.officers();
            String who = ps.isEmpty() ? null : ps.get(Math.min(player, ps.size() - 1)).name;
            lines.add(who == null ? "No friends connected." : "Friend:  <  " + who + "  >" + (ps.size() > 1 ? "     (Left/Right: another friend)" : ""));
            colors.add(who == null ? Panel.WARN : Panel.TEXT);
            lines.add("");
            colors.add(Panel.DIM);
            if (os.isEmpty()) { lines.add("You have no officers to link."); colors.add(Panel.DIM); }
            for (int i = 0; i < os.size(); i++) {
                PersonAPI o = os.get(i);
                String link = OfficerLinks.linkedTo(o);
                String ship = "no ship";
                for (FleetMemberAPI m : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
                    if (m.getCaptain() == o) ship = CampaignLobby.label(m);
                }
                lines.add((i == officer ? ">  " : "    ") + o.getNameString() + ", level " + o.getStats().getLevel() + "  -  " + ship
                        + (link == null ? "" : "  -  " + link + "'s"));
                colors.add(link == null ? (i == officer ? Panel.TEXT : Panel.DIM) : Panel.GOOD);
            }
            if (!message.isEmpty()) { lines.add(""); colors.add(Panel.DIM); lines.add(message); colors.add(messageColor); }
            panel.render("OFFICER LINKS", lines, colors, "Up/Down: officer     Enter: link to " + (who == null ? "the friend" : who) + " (or unlink)     Delete: unlink     Esc: close");
        } catch (Throwable t) {
            broken = true;
            close();
            Global.getLogger(OfficerLinkPanel.class).warn("Wingman: the officer links page failed and was closed", t);
        }
    }
}
