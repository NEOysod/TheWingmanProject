package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;

/**
 * The co-op screens on the title screen as screens of their own: the game's menu and logo are moved aside (see
 * TitleMenu) and a backdrop covers the title screen, so on a small screen nothing of ours sits behind the game's
 * buttons. A header across the top says where you are, with MAIN MENU (back to the game's own title screen) and,
 * while connected or connecting, a red DISCONNECT.
 */
final class CoopScreen {
    static final int MAIN_MENU = 1, DISCONNECT = 2;
    static final float BUTTON_W = 150f, BUTTON_H = 30f, GAP = 8f;
    static final Color BACKDROP = new Color(4, 7, 12, 238);

    /** Where the header's buttons were drawn last: x, y, w, h, action. */
    private final List<float[]> hits = new ArrayList<float[]>();

    static float margin() { return Math.max(24f, Global.getSettings().getScreenWidth() * 0.022f); }

    /** How wide the header's buttons are, with or without DISCONNECT. */
    static float buttonsWidth(boolean disconnect) { return disconnect ? 2 * BUTTON_W + GAP : BUTTON_W; }

    /** The header's buttons, their right edge at 'right' and their top at 'top'. */
    void buttons(float right, float top, boolean disconnect) {
        hits.clear();
        float y = top - BUTTON_H, x = right - BUTTON_W;
        if (disconnect) {
            Style.button(x, y, BUTTON_W, BUTTON_H, "DISCONNECT", Style.BAD, true, Style.over(x, y, BUTTON_W, BUTTON_H));
            hits.add(new float[] { x, y, BUTTON_W, BUTTON_H, DISCONNECT });
            x -= BUTTON_W + GAP;
        }
        Style.button(x, y, BUTTON_W, BUTTON_H, "MAIN MENU", Style.ACCENT, true, Style.over(x, y, BUTTON_W, BUTTON_H));
        hits.add(new float[] { x, y, BUTTON_W, BUTTON_H, MAIN_MENU });
    }

    /**
     * The backdrop and header of the co-op screen (the lobby draws its own, see LobbyScreen): the title, a line under
     * it, and the buttons. Returns how far down the header reaches: windows go below that.
     */
    float frame(String title, String line, Color lineColor, boolean disconnect) {
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float m = margin();
        LobbyScreen.fill(0f, 0f, sw, sh, BACKDROP);
        float hx = m + LobbyScreen.lunaCorner(), top = sh - m;
        Style.text(title, LobbyScreen.BIG, LobbyScreen.TITLE, hx, top, Math.max(200f, sw - hx - m - buttonsWidth(true) - 20f));
        float y = top - Style.lineHeight(LobbyScreen.BIG) - 10f;
        if (line != null && !line.isEmpty()) y = Style.text(line, Style.body(), lineColor, hx, y, sw - m - hx - buttonsWidth(disconnect) - 20f) - 2f;
        buttons(sw - m, top + 2f, disconnect);
        y = Math.min(y, top - BUTTON_H - 6f) - 8f;
        if (LobbyScreen.lunaCorner() > 0f) y = Math.min(y, sh * 0.975f - LobbyScreen.LUNA_BUTTONS_H - 8f);
        LobbyScreen.fill(m, y, sw - 2 * m, 1f, LobbyScreen.LINE);
        return y - 14f;
    }

    /** The button under the mouse (MAIN_MENU, DISCONNECT), or 0. */
    int hitAt(float mx, float my) {
        for (float[] h : hits) if (mx >= h[0] && mx <= h[0] + h[2] && my >= h[1] && my <= h[1] + h[3]) return (int) h[4];
        return 0;
    }

    void clear() { hits.clear(); }
}
