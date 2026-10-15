package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;

/**
 * Friend refit, the host's side of deciding (see docs/FRIEND_REFIT_DESIGN.md): while a friend's refit waits, a
 * small FRIEND REFITS button sits at the top right (a click, or Ctrl+Shift+R, opens the panel). The panel shows
 * each request: who, which ship, every change, the credits and story points, and the checks' result (all of
 * them made again on the host's campaign each second). A accepts, D denies, Left/Right go through the requests.
 *
 * Accepted with a fleet, cargo or refit screen open, a refit is done the moment that screen closes (those screens
 * keep their own copy of the ship, which would undo a change made under them), and the refit screen can open again
 * on that ship (host setting friendRefitReopen). Registered on each game load as a transient listener.
 */
final class FriendRefitPanel implements CampaignInputListener, CampaignUIRenderingListener {
    private static final String SMALL = "graphics/fonts/orbitron12condensed.fnt";
    private final Panel panel = new Panel();
    private boolean open, broken, wasDown;
    private int at;
    private String message = "";
    private Color messageColor = Panel.DIM;
    private float[] button;
    private LabelAPI buttonLabel;
    private String buttonText = "";

    static void install() {
        try {
            FriendRefitPanel p = new FriendRefitPanel();
            Global.getSector().getListenerManager().addListener(p, true);
        } catch (Throwable t) {
            Global.getLogger(FriendRefitPanel.class).warn("Wingman: friends' refits can't be shown on the campaign map", t);
        }
    }

    private static List<FriendRefit.Pending> requests() { return new ArrayList<FriendRefit.Pending>(FriendRefit.pending.values()); }

    public int getListenerInputPriority() { return 90; }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            List<FriendRefit.Pending> list = requests();
            if (list.isEmpty()) { open = false; return; }
            float scale = Global.getSettings().getScreenScaleMult();
            for (InputEventAPI e : events) {
                if (e.isConsumed()) continue;
                // A click on the panel or its buttons must not also land on the dialog's options underneath.
                if (e.isMouseEvent() && !e.isMouseMoveEvent() && overUs(e.getX() / scale, e.getY() / scale)) { e.consume(); continue; }
                if (!e.isKeyboardEvent()) continue;
                // Everything about the key is read first: the game refuses any read of an event once it is consumed.
                boolean keyDown = e.isKeyDownEvent(), ctrl = e.isCtrlDown(), shift = e.isShiftDown();
                int key = e.getEventValue();
                if (!open) {
                    if (keyDown && ctrl && shift && key == 19) { open = true; message = ""; e.consume(); } // Ctrl+Shift+R
                    continue;
                }
                e.consume(); // while the panel is up, keys are its own
                if (!keyDown) continue;
                at = Math.max(0, Math.min(list.size() - 1, at));
                if (key == 1 || (key == 19 && ctrl)) open = false;                           // Esc, Ctrl+Shift+R
                else if (key == 203) at = (at + list.size() - 1) % list.size();                // Left
                else if (key == 205) at = (at + 1) % list.size();                              // Right
                else if (key == 32) { deny(list.get(at)); break; }                             // D: deny (the list changed)
                else if (key == 30) { if (accept(list.get(at))) break; }                       // A: accept
            }
        } catch (Throwable t) {
            open = false;
            Global.getLogger(FriendRefitPanel.class).warn("Wingman: the friend refit panel failed and was closed", t);
        }
    }

    private void say(String text, Color c) { message = text; messageColor = c; }

    private void deny(FriendRefit.Pending pd) {
        FriendRefit.deny(pd);
        say("Denied. " + pd.player.name + " has been told.", Panel.WARN);
    }

    /** Accept, now or (under a core screen) once it closes. True when the list of requests changed. */
    private boolean accept(FriendRefit.Pending pd) {
        if (pd.acceptOnClose) { say("Already accepted: it is done as soon as you close this screen.", Panel.GOOD); return false; }
        if (!pd.acceptOnClose && !FriendRefit.docked()) { say("Dock at a market to accept it: the refit waits until then.", Panel.WARN); return false; }
        if (!pd.problems.isEmpty()) { say("It doesn't pass the checks (in red above), so it can't be accepted.", Panel.BAD); return false; }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null && ui.getCurrentCoreTab() != null) { // this screen's copy of the ship would undo it: done once it closes
            FriendRefit.acceptOnClose(pd);
            say("Accepted. It is done as soon as you close this screen" + (WingmanConfig.load().friendRefitReopen ? ", which then opens again on " + pd.ship + "." : "."), Panel.GOOD);
            return false;
        }
        String why = FriendRefit.accept(pd);
        if (why == null) say("Accepted: " + pd.ship + " is refitted.", Panel.GOOD);
        else say(why, Panel.BAD);
        return true;
    }

    // ---- buttons: YES / NO under the panel, and the others; clicks on them (or the panel) go no further ----

    private static final int YES = 0, NO = 1, PREV = 2, NEXT = 3, CLOSE = 4;
    /** Where each button was drawn last frame: x, y, w, h, action. */
    private final List<float[]> buttons = new ArrayList<float[]>();
    private static final String BUTTON_FONT = "graphics/fonts/orbitron20aabold.fnt";
    private final java.util.Map<String, LabelAPI> labels = new java.util.HashMap<String, LabelAPI>();

    private boolean overUs(float mx, float my) {
        if (open && panel.contains(mx, my)) return true;
        if (open && strip != null && mx >= strip[0] && mx <= strip[0] + strip[2] && my >= strip[1] && my <= strip[1] + strip[3]) return true;
        for (float[] b : buttons) if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) return true;
        return !open && button != null && mx >= button[0] && mx <= button[0] + button[2] && my >= button[1] && my <= button[1] + button[3];
    }

    /** A click on a button (polled while drawing, so it works over any screen). */
    private void clicked(float mx, float my, List<FriendRefit.Pending> list) {
        for (float[] b : buttons) {
            if (mx < b[0] || mx > b[0] + b[2] || my < b[1] || my > b[1] + b[3]) continue;
            at = Math.max(0, Math.min(list.size() - 1, at));
            switch ((int) b[4]) {
                case YES: accept(list.get(at)); break;
                case NO: deny(list.get(at)); break;
                case PREV: at = (at + list.size() - 1) % list.size(); message = ""; break;
                case NEXT: at = (at + 1) % list.size(); message = ""; break;
                default: open = false;
            }
            try { Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f); } catch (Throwable t) { /* silent is fine */ }
            return;
        }
    }

    /** The row of buttons under the panel box. */
    private void drawButtons(FriendRefit.Pending pd, int count) {
        buttons.clear();
        List<Object[]> row = new ArrayList<Object[]>();
        boolean canAccept = pd.problems.isEmpty() && !pd.acceptOnClose && FriendRefit.docked();
        if (count > 1) row.add(new Object[] { "<", PREV, LobbyScreen.TITLE });
        row.add(new Object[] { pd.acceptOnClose ? "ACCEPTED" : "YES, ACCEPT", YES, canAccept ? Panel.GOOD : Panel.DIM });
        row.add(new Object[] { pd.acceptOnClose ? "NO, CANCEL" : "NO, DENY", NO, Panel.BAD });
        if (count > 1) row.add(new Object[] { ">", NEXT, LobbyScreen.TITLE });
        row.add(new Object[] { "CLOSE", CLOSE, Panel.DIM });
        float gap = 12f, h = 0f, total = 0f;
        float[] widths = new float[row.size()];
        for (int i = 0; i < row.size(); i++) {
            LabelAPI l = buttonLabel((String) row.get(i)[0], (Color) row.get(i)[2]);
            widths[i] = Math.max(l.computeTextWidth((String) row.get(i)[0]) + 36f, ((Integer) row.get(i)[1]) <= NO ? 170f : 44f);
            h = Math.max(h, 36f);
            total += widths[i] + (i > 0 ? gap : 0f);
        }
        float stripBottom = changeIcons(pd);
        float x = panel.lastX + (panel.lastW - total) / 2f, y = Math.max(4f, stripBottom - h - 10f);
        for (int i = 0; i < row.size(); i++) {
            String text = (String) row.get(i)[0];
            Color c = (Color) row.get(i)[2];
            boolean over = LobbyScreen.inside(x, y, widths[i], h);
            Style.fill(x - 2f, y - 2f, widths[i] + 4f, h + 4f, new Color(6, 10, 16, 220));
            Style.button(x, y, widths[i], h, text, c, true, over);
            buttons.add(new float[] { x, y, widths[i], h, (Integer) row.get(i)[1] });
            x += widths[i] + gap;
        }
    }

    /**
     * The changes as pictures, under the panel: for each slot or bay, what comes out (faded) and what goes in. Returns
     * the strip's bottom (the panel's, if there is nothing to show).
     */
    private float changeIcons(FriendRefit.Pending pd) {
        List<String[]> pairs = new ArrayList<String[]>();
        for (wingman.net.RefitRequest.Change c : pd.request.changes) {
            if (pd.plan.sameAsBefore(c)) continue;
            pairs.add(new String[] { pd.plan.before(c), pd.plan.after(c) });
        }
        for (String id : pd.request.addMods) pairs.add(new String[] { null, "m:" + id });
        strip = null;
        if (pairs.isEmpty()) return panel.lastY;
        float s = 32f, arrow = 16f, pairW = s * 2 + arrow + 14f, gap = 6f;
        int perRow = Math.max(1, (int) ((panel.lastW + gap) / (pairW + gap)));
        int rows = Math.min(3, (pairs.size() + perRow - 1) / perRow);
        float stripH = rows * (s + 8f) + 8f;
        float y0 = panel.lastY - 6f - stripH;
        strip = new float[] { panel.lastX, y0, panel.lastW, stripH };
        Style.frame(panel.lastX, y0, panel.lastW, stripH, Style.WINDOW, Style.EDGE, 8f);
        for (int i = 0; i < pairs.size() && i < rows * perRow; i++) {
            float x = panel.lastX + 8f + (i % perRow) * (pairW + gap), y = y0 + stripH - 8f - (i / perRow + 1) * (s + 8f) + 8f;
            String[] p = pairs.get(i);
            RefitScreen.iconBox(pic(p[0]), x, y, s, true);
            arrowRight(x + s + 3f, y + s / 2f, arrow - 6f);
            RefitScreen.iconBox(pic(p[1]), x + s + arrow, y, s, false);
        }
        return y0;
    }

    private float[] strip;

    private static com.fs.starfarer.api.graphics.SpriteAPI pic(String key) {
        if (key == null) return null;
        if (key.startsWith("m:")) return RefitScreen.picture(key.substring(2), true);
        return RefitScreen.picture(key, false);
    }

    private static void arrowRight(float x, float y, float len) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.47f, 0.82f, 1f, 0.9f);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(x, y - 5f); GL11.glVertex2f(x, y + 5f); GL11.glVertex2f(x + len, y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    private LabelAPI buttonLabel(String text, Color c) {
        String key = c.getRGB() + "|" + text;
        LabelAPI l = labels.get(key);
        if (l == null) {
            l = Global.getSettings().createLabel(text, BUTTON_FONT);
            l.setColor(c);
            labels.put(key, l);
        }
        return l;
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            FriendRefit.applyWaiting(); // (also from the lobby's script, which may not run under a core screen)
            List<FriendRefit.Pending> list = requests();
            boolean down = Mouse.isButtonDown(0);
            if (list.isEmpty()) {
                if (open && !message.isEmpty()) { open = false; } // the last one decided
                button = null;
                buttons.clear();
                wasDown = down;
                return;
            }
            if (open && down && !wasDown) { // the buttons drawn last frame
                clicked(Global.getSettings().getMouseX(), Global.getSettings().getMouseY(), list);
                wasDown = down;
                list = requests();
                if (list.isEmpty() || !open) { buttons.clear(); return; }
            }
            if (!open) {
                buttons.clear();
                drawButton(list.size());
                if (down && !wasDown && button != null && LobbyScreen.inside(button[0], button[1], button[2], button[3])) { open = true; message = ""; }
                wasDown = down;
                return;
            }
            wasDown = down;
            at = Math.max(0, Math.min(list.size() - 1, at));
            FriendRefit.Pending pd = list.get(at);
            panel.render(doc(pd, list.size()));
            drawButtons(pd, list.size());
        } catch (Throwable t) {
            open = false;
            broken = true;
            Global.getLogger(FriendRefitPanel.class).error("Wingman: the friend refit panel can't be drawn and is switched off (requests still time out when the host undocks)", t);
        }
    }

    /** One request as a window: who and what, the changes, the cost, and the checks. */
    private Panel.Doc doc(FriendRefit.Pending pd, int count) {
        Panel.Doc d = new Panel.Doc("FRIEND REFIT").note(count > 1 ? (at + 1) + " of " + count : "");
        d.section("Request");
        d.line(pd.player.name + " asks for a refit of " + pd.ship + ".", PlayerTags.color(pd.player.slot));
        if (!pd.request.note.isEmpty()) d.callout("\"" + pd.request.note + "\"", Style.ACCENT);
        List<String> changes = FriendRefit.describe(pd);
        if (!changes.isEmpty()) {
            d.section("Changes");
            for (String l : changes) d.line(l, Panel.TEXT);
        }
        d.section("Cost");
        int net = pd.plan.net();
        d.field("Ordnance points", pd.op + " / " + pd.opMax, false, false);
        d.field("Credits", net > 0 ? "costs " + LobbyScreen.grouped(net) : net < 0 ? "brings in " + LobbyScreen.grouped(-net) : "none", false, false);
        if (pd.plan.storyPoints > 0) d.field("Story points", String.valueOf(pd.plan.storyPoints), false, false);
        if (FriendRefit.docked() && (net != pd.request.expectCredits || pd.plan.storyPoints != pd.request.expectStoryPoints))
            d.callout(pd.player.name + " was shown " + pd.request.expectCredits + " credits and " + pd.request.expectStoryPoints + " story points: something changed since.", Panel.WARN);
        d.section("Checks");
        if (!pd.acceptOnClose && !FriendRefit.docked()) d.callout("Waiting for a market: dock at one to check this refit there and accept it.", Panel.WARN);
        else if (pd.acceptOnClose) d.callout("Accepted: done as soon as you close this screen (NO cancels it).", Panel.GOOD);
        else if (pd.problems.isEmpty()) d.callout("All checks pass.", Panel.GOOD);
        else for (String p : pd.problems) d.callout(p, Panel.BAD);
        if (!pd.request.addMods.isEmpty()) d.line("Hull mods' own rules (what they need, what they rule out) can't be checked outside the refit screen: you decide.", Panel.DIM);
        if (!message.isEmpty()) d.callout(message, messageColor);
        d.keys("A: accept     D: deny     " + (count > 1 ? "Left/Right: other requests     " : "") + "Esc: close");
        return d;
    }

    /** The small button, top right, while requests wait. */
    private void drawButton(int n) {
        String text = "FRIEND REFIT" + (n == 1 ? "" : "S") + "  (" + n + ")     CTRL+SHIFT+R";
        if (buttonLabel == null || !text.equals(buttonText)) {
            buttonLabel = Global.getSettings().createLabel(text, SMALL);
            buttonLabel.setColor(new Color(120, 210, 255));
            buttonText = text;
        }
        float w = buttonLabel.computeTextWidth(text) + 30f, h = buttonLabel.getPosition().getHeight() + 16f;
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float x = sw - w - 10f, y = Math.min(sh, CampaignPlayerList.bottom) - h - 10f; // under the player list, if it shows
        boolean over = LobbyScreen.inside(x, y, w, h);
        boolean pulse = (System.currentTimeMillis() / 600) % 2 == 0;
        Style.frame(x, y, w, h, over ? new Color(14, 26, 38, 240) : new Color(6, 10, 16, 225), over || pulse ? Style.ACCENT : Style.EDGE, 6f);
        Style.fill(x + 1f, y + 1f, 3f, h - 2f, Style.GOOD);
        buttonLabel.getPosition().setLocation(x + 16f, y + 8f);
        buttonLabel.render(1f);
        button = new float[] { x, y, w, h };
    }

}
