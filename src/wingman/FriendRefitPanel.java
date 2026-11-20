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
    /** Where the stack of buttons top right ends (screen y): the wish lists' button goes under this one. */
    static volatile float bottom = Float.MAX_VALUE;
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
            instance = p;
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
                else if (key == 20) {                                                          // T: trust this player's refits, or stop
                    FriendRefit.Pending pd = list.get(at);
                    act("perm:" + pd.player.slot + ":" + (pd.player.trustedRefits ? 1 : 2), list);
                    if (!requests().contains(pd)) break;
                }
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
        if (pd.acceptOnClose) { say("Already accepted: it is being done.", Panel.GOOD); return false; }
        if (!pd.acceptOnClose && !FriendRefit.docked()) { say("Dock at a market to accept it: the refit waits until then.", Panel.WARN); return false; }
        if (!pd.problems.isEmpty()) { say("It doesn't pass the checks (in red above), so it can't be accepted.", Panel.BAD); return false; }
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui != null && ui.getCurrentCoreTab() != null) { // this screen's copy of the ship would undo it: done once it closes
            FriendRefit.acceptOnClose(pd);
            say("Accepted: the refit screen closes for a moment while it is done" + (WingmanConfig.load().friendRefitReopen ? ", then opens again on " + pd.ship + "." : "."), Panel.GOOD);
            return false;
        }
        String why = FriendRefit.accept(pd);
        if (why == null) say("Accepted: " + pd.ship + " is refitted.", Panel.GOOD);
        else say(why, Panel.BAD);
        return true;
    }

    // ---- the page: queue tabs, the changes, cost and checks at the side, the buttons at the foot ----

    private final Widgets.Hits hits = new Widgets.Hits();

    private boolean overUs(float mx, float my) {
        if (open && hits.over(mx, my)) return true;
        return !open && button != null && mx >= button[0] && mx <= button[0] + button[2] && my >= button[1] && my <= button[1] + button[3];
    }

    /** Opens the page (from the CO-OP panel), on that player's refit if they have one waiting. */
    static void openFor(String name) {
        FriendRefitPanel p = instance;
        if (p == null) return;
        List<FriendRefit.Pending> list = requests();
        if (list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) if (name != null && list.get(i).player.name.equalsIgnoreCase(name)) p.at = i;
        p.open = true;
        p.message = "";
    }

    private static FriendRefitPanel instance;

    static boolean isOpen() { return instance != null && instance.open; }

    private void act(String id, List<FriendRefit.Pending> list) {
        Widgets.click();
        at = Math.max(0, Math.min(list.size() - 1, at));
        if (id.equals("accept")) accept(list.get(at));
        else if (id.equals("deny")) deny(list.get(at));
        else if (id.equals("prev")) { at = (at + list.size() - 1) % list.size(); message = ""; }
        else if (id.equals("next")) { at = (at + 1) % list.size(); message = ""; }
        else if (id.equals("close")) open = false;
        else if (id.startsWith("tab:")) { at = Integer.parseInt(id.substring(4)); message = ""; }
        else if (id.startsWith("perm:")) {
            FriendRefit.Pending pd = list.get(at);
            int k = Integer.parseInt(id.substring(id.lastIndexOf(':') + 1));
            CampaignPlayerList.setPermission(pd.player, k);
            say(k == 2 ? "You trust " + pd.player.name + "'s refits: done as they arrive (checks still run)." : k == 0
                    ? pd.player.name + "'s refits are look only now: this one still waits for you." : pd.player.name + "'s refits wait for your yes.", k == 2 ? Panel.WARN : Panel.GOOD);
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN) return;
        bottom = Math.min(Global.getSettings().getScreenHeight(), CampaignPlayerList.bottom); // no button: under the player list
        try {
            FriendRefit.applyWaiting(); // (also from the lobby's script, which may not run under a core screen)
            List<FriendRefit.Pending> list = requests();
            boolean down = Mouse.isButtonDown(0);
            if (list.isEmpty()) {
                if (open && !message.isEmpty()) { open = false; } // the last one decided
                button = null;
                hits.clear();
                wasDown = down;
                return;
            }
            if (open) { // the clicks on what was drawn last frame (polled while drawing, so it works over any screen)
                String id = hits.clicked();
                wasDown = down;
                if (id != null) act(id, list);
                list = requests();
                if (list.isEmpty() || !open) { hits.clear(); return; }
            }
            if (!open) {
                hits.clear();
                if (CampaignPlayerList.showing()) { button = null; wasDown = down; return; } // its REFITS button stands for this one
                drawButton(list.size());
                if (down && !wasDown && button != null && LobbyScreen.inside(button[0], button[1], button[2], button[3])) { open = true; message = ""; }
                wasDown = down;
                return;
            }
            at = Math.max(0, Math.min(list.size() - 1, at));
            draw(list, at);
        } catch (Throwable t) {
            open = false;
            broken = true;
            Style.forget();
            Global.getLogger(FriendRefitPanel.class).error("Wingman: the friend refit panel can't be drawn and is switched off (requests still time out when the host undocks)", t);
        }
    }

    void draw(List<FriendRefit.Pending> list, int at) {
        hits.clear();
        FriendRefit.Pending pd = list.get(at);
        String body = Style.body();
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float w = Math.min(1120f, sw - 60f), h = Math.min(720f, sh - 60f);
        float x = (sw - w) / 2f, y = (sh - h) / 2f;
        Style.fill(0f, 0f, sw, sh, Widgets.BACKDROP);
        hits.add(x, y, w, h, "");
        Style.frame(x, y, w, h, new Color(8, 18, 31, 248), Style.EDGE, 10f);
        float bar = 46f, top = y + h;
        Style.fill(x + 1f, top - bar, w - 11f, bar - 1f, Style.BAR);
        Style.fill(x + 1f, top - bar, 3f, bar - 1f, Style.ACCENT);
        float th = Style.lineHeight(Style.TITLE_FONT);
        Style.text("FRIEND REFITS", Style.TITLE_FONT, Style.ACCENT, x + 16f, top - (bar - th) / 2f, 240f);
        float tx = x + 16f + Style.width("FRIEND REFITS", Style.TITLE_FONT) + 18f;
        for (int i = 0; i < list.size(); i++) { // the refits waiting, as tabs
            FriendRefit.Pending q = list.get(i);
            String t = "P" + q.player.slot + "  " + q.player.name;
            float bw = Style.width(t.toUpperCase(), Style.SMALL_FONT) + 24f;
            if (tx + bw > x + w - 80f) break;
            Widgets.box(tx, top - bar + 8f, bw, bar - 16f, t, PlayerTags.color(q.player.slot), i == at);
            hits.add(tx, top - bar + 8f, bw, bar - 16f, "tab:" + i);
            tx += bw + 6f;
        }
        float cb = bar - 16f;
        Widgets.box(x + w - 16f - cb, top - bar + 8f, cb, cb, "X", Style.TEXT, false);
        hits.add(x + w - 16f - cb, top - bar + 8f, cb, cb, "close");

        float sideW = 360f, foot = 64f;
        float sx = x + w - sideW, bodyTop = top - bar, bodyBottom = y + foot;
        Style.fill(sx, bodyBottom, 1f, bodyTop - bodyBottom, Style.RULE);
        Style.fill(sx + 1f, bodyBottom, sideW - 2f, bodyTop - bodyBottom, new Color(10, 24, 40, 200));

        // who and which ship
        float lx = x + 18f, lw = sx - lx - 18f, ly = bodyTop - 16f;
        float ny = Style.text(pd.ship, Style.TITLE_FONT, Style.TEXT, lx, ly, lw);
        long ago = Math.max(0L, (System.currentTimeMillis() - pd.at) / 60000L);
        ny = Style.text(pd.player.name + " asks  -  " + (ago < 1 ? "just now" : ago + " min ago"), body, PlayerTags.color(pd.player.slot), lx, ny - 4f, lw) - 10f;
        if (!pd.request.note.isEmpty()) ny = Style.callout("\"" + pd.request.note + "\"", Style.ACCENT, lx, ny, lw) - 12f;
        ny = Widgets.head("Changes", lx, ny);
        List<String[]> rows = FriendRefit.rows(pd);
        float rh = 46f, colW = (lw - 150f - 28f) * 0.42f, toW = (lw - 150f - 28f) - colW;
        for (int i = 0; i < rows.size(); i++) {
            if (ny - rh < bodyBottom + 10f) { Style.text("and " + (rows.size() - i) + " more", body, Style.DIM, lx, ny, lw); break; }
            String[] r = rows.get(i);
            float ry = ny - rh;
            Style.fill(lx, ry, lw, rh - 4f, Widgets.CARD);
            Style.outline(lx, ry, lw, rh - 4f, Widgets.CARD_EDGE);
            float mid = ry + (rh - 4f) / 2f, lh = Style.lineHeight(body);
            Style.text(Widgets.fit(r[0], Style.SMALL_FONT, 140f), Style.SMALL_FONT, Style.DIM, lx + 10f, mid + Style.lineHeight(Style.SMALL_FONT) / 2f, 140f);
            float fx = lx + 150f;
            float ix = item(r[1], r[2], Style.DIM, fx, mid, colW, true);
            arrowRight(fx + colW + 6f, mid, 12f);
            float tx2 = fx + colW + 28f;
            boolean sub = r[5] != null && !r[5].isEmpty();
            item(r[3], r[4], Style.TEXT, tx2, sub ? mid + 7f : mid, toW - 8f, false);
            if (sub) Style.text(Widgets.fit(r[5], Style.SMALL_FONT, toW - 8f - (r[3] == null ? 0f : 34f)), Style.SMALL_FONT, Style.DIM, tx2 + (r[3] == null ? 0f : 34f), mid - 4f, toW);
            ny = ry;
        }
        if (rows.isEmpty()) Style.text("Nothing to change.", body, Style.DIM, lx, ny, lw);

        // the side: cost, checks, and how this player's refits are taken
        float px = sx + 18f, pw = sideW - 36f, py = bodyTop - 16f;
        py = Widgets.head("Cost", px, py);
        int net = pd.plan.net();
        py = field("Ordnance points", pd.op + " / " + pd.opMax, pd.op > pd.opMax ? Panel.BAD : Style.TEXT, px, py, pw);
        py = field("Credits", net > 0 ? LobbyScreen.grouped(net) : net < 0 ? "brings in " + LobbyScreen.grouped(-net) : "none", Style.TEXT, px, py, pw);
        py = field("Story points", pd.plan.storyPoints > 0 ? String.valueOf(pd.plan.storyPoints) : "none", Style.TEXT, px, py, pw) - 8f;
        if (FriendRefit.docked() && (net != pd.request.expectCredits || pd.plan.storyPoints != pd.request.expectStoryPoints))
            py = Style.callout(pd.player.name + " was shown " + pd.request.expectCredits + " credits and " + pd.request.expectStoryPoints + " story points: something changed since.", Panel.WARN, px, py, pw) - 8f;
        py = Widgets.head("Checks", px, py);
        if (!pd.acceptOnClose && !FriendRefit.docked()) py = Style.callout("Waiting for a market: dock at one to check this refit there and accept it.", Panel.WARN, px, py, pw) - 6f;
        else if (pd.acceptOnClose) py = Style.callout("Accepted: being done now.", Panel.GOOD, px, py, pw) - 6f;
        else if (pd.problems.isEmpty()) py = Style.callout("All checks pass.", Panel.GOOD, px, py, pw) - 6f;
        else for (String p : pd.problems) { if (py < bodyBottom + 140f) break; py = Style.callout(p, Panel.BAD, px, py, pw) - 6f; }
        if (!pd.request.addMods.isEmpty() && py > bodyBottom + 150f) py = Style.text("Hull mods' own rules (what they need, what they rule out) can't be checked outside the refit screen: you decide.", Style.SMALL_FONT, Style.DIM, px, py - 2f, pw) - 8f;
        float permTop = bodyBottom + 96f;
        Widgets.head(pd.player.name + "'s refits", px, permTop);
        int perm = CampaignPlayerList.permission(pd.player);
        Widgets.toggles(CampaignPlayerList.PERMS, perm, px, permTop - Style.lineHeight(Style.SMALL_FONT) - 5f - 30f, pw, 30f, Style.ACCENT, hits, "perm:" + pd.player.slot);
        Style.text(perm == 2 ? "Done as they arrive, until they reconnect (T)." : perm == 0 ? "They can't send refits; this one still waits." : "Each one waits for you (T trusts them).",
                Style.SMALL_FONT, Style.DIM, px, permTop - Style.lineHeight(Style.SMALL_FONT) - 5f - 30f - 6f, pw);

        // the foot: the message, < >, DENY, ACCEPT
        Style.fill(x + 1f, y + foot, w - 2f, 1f, Style.RULE);
        float by = y + 12f, bh = 40f, bx = x + 18f;
        if (list.size() > 1) {
            Style.button(bx, by, 44f, bh, "<", LobbyScreen.TITLE, true, Widgets.over(bx, by, 44f, bh));
            hits.add(bx, by, 44f, bh, "prev");
            Style.button(bx + 52f, by, 44f, bh, ">", LobbyScreen.TITLE, true, Widgets.over(bx + 52f, by, 44f, bh));
            hits.add(bx + 52f, by, 44f, bh, "next");
            bx += 108f;
        }
        boolean canAccept = pd.problems.isEmpty() && !pd.acceptOnClose && FriendRefit.docked();
        String acc = pd.acceptOnClose ? "ACCEPTED" : !FriendRefit.docked() ? "[A]  ACCEPT AT A MARKET" : "[A]  ACCEPT";
        float aw = Math.max(200f, Style.width(acc, Style.TITLE_FONT) + 40f), dw = 180f;
        float ax = x + w - 18f - aw, dx = ax - 10f - dw;
        Style.button(dx, by, dw, bh, pd.acceptOnClose ? "[D]  CANCEL" : "[D]  DENY", Panel.BAD, true, Widgets.over(dx, by, dw, bh));
        hits.add(dx, by, dw, bh, "deny");
        Style.button(ax, by, aw, bh, acc, Panel.GOOD, canAccept, canAccept && Widgets.over(ax, by, aw, bh));
        hits.add(ax, by, aw, bh, "accept");
        String m = message.isEmpty() ? "Left/Right: another refit     Esc: close" : message;
        Style.text(Widgets.fit(m, body, dx - bx - 16f), body, message.isEmpty() ? Style.FAINT : messageColor, bx, by + (bh + Style.lineHeight(body)) / 2f, dx - bx - 16f);
    }

    /** One side of a change: its picture (if any) and its name, centred on 'mid'. Returns the right edge. */
    private static float item(String key, String text, Color c, float x, float mid, float w, boolean faded) {
        float tx = x;
        if (key != null) {
            RefitScreen.iconBox(pic(key), x, mid - 14f, 28f, faded);
            tx += 34f;
        }
        String body = Style.body();
        Style.text(Widgets.fit(text, body, w - (tx - x)), body, c, tx, mid + Style.lineHeight(body) / 2f, w - (tx - x));
        return x + w;
    }

    private static float field(String name, String value, Color c, float x, float top, float w) {
        String body = Style.body();
        Style.text(name, body, Style.DIM, x, top, w * 0.6f);
        Style.textRight(value, body, c, x + w, top);
        return top - Style.lineHeight(body) - 6f;
    }

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
        GL11.glVertex2f(x, y - 6f); GL11.glVertex2f(x, y + 6f); GL11.glVertex2f(x + len, y);
        GL11.glEnd();
        GL11.glPopAttrib();
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
        bottom = y;
    }

}
