package wingman;

import java.awt.Color;
import java.util.List;

import wingman.net.ChatLog;

/**
 * The co-op chat as a small box: under the CO-OP panel on the host's campaign map, under PLAYERS in a joiner's lobby.
 * The host's minimizes to a small CHAT tab (drawn by CampaignPlayerList); the joiner's folds to one line (CHAT, and
 * how many lines are new). Opened it shows the last lines and a line to type in.
 * Typing starts with a click on that line or the chat key (Keys "chat"); Enter sends, Esc stops typing. While typing,
 * every key is the chat's; otherwise none is.
 */
final class ChatBox {
    interface Sender { boolean send(String text); }

    /** The host's box (a tab until opened: it stays out of the way) and the joiner's (open), as last left on this PC. */
    static final ChatBox host = new ChatBox("chat.host.open", false, true), joiner = new ChatBox("chat.lobby.open", true, false);

    boolean open, typing;
    /** Its head has a minimize button (the host's), rather than folding on a click anywhere on it (the joiner's). */
    final boolean minimizable;
    private final String stateKey;
    final StringBuilder draft = new StringBuilder();
    Sender sender;
    private final Widgets.Hits hits = new Widgets.Hits();
    private long drawnAt;
    private String problem = "";
    /** Lines scrolled back from the newest (the wheel over the lines), and what the log was when last drawn. */
    private int back, seenVersion, seenSize;
    /** Where the lines were drawn ({x, y, w, h}), for the wheel; null when not open. */
    private float[] linesBox;

    static final float HEAD_H = 26f, INPUT_H = 28f, LINES_H = 150f;

    private ChatBox(String stateKey, boolean open, boolean minimizable) {
        this.stateKey = stateKey;
        this.minimizable = minimizable;
        boolean o = open;
        try { o = UiState.get(stateKey, open); } catch (Throwable t) { /* as designed */ }
        this.open = o;
    }

    /** Open or minimize it (remembered on this PC). */
    void setOpen(boolean o) {
        open = o;
        if (!o) typing = false;
        try { UiState.set(stateKey, o); } catch (Throwable t) { /* for now only */ }
    }

    /** How tall it is drawn (folded: one line; open: the lines too, at most 'room'). */
    float height(float room) {
        if (!showsOpen(room)) return HEAD_H;
        return HEAD_H + Math.min(LINES_H, room - HEAD_H - INPUT_H - 12f) + INPUT_H + 12f;
    }

    /** The least room it is shown open in: a line or two. */
    static final float MIN_OPEN = HEAD_H + 36f + INPUT_H + 12f;

    /** Open, and there is room to show it so (otherwise it shows folded, with the newest line). */
    boolean showsOpen(float room) { return open && room >= MIN_OPEN; }

    /** Shown lately (a key only types into a box that is on the screen). */
    boolean shown() { return System.currentTimeMillis() - drawnAt < 400; }

    /** Draws it with its top left corner at (x, top), at most 'room' tall. Returns its bottom. */
    float draw(float x, float top, float w, float room) {
        hits.clear();
        if (!shown()) typing = false; // (not on the screen a moment ago: whatever was being typed waits)
        drawnAt = System.currentTimeMillis();
        String body = Style.body();
        float h = height(room), y = top - h;
        boolean opened = showsOpen(room);
        hits.add(x, y, w, h, "");
        Style.frame(x, y, w, h, new Color(6, 12, 20, 228), Style.EDGE, 6f);
        // the head: CHAT, what is new, and the fold
        float hy = top - HEAD_H;
        boolean overHead = !minimizable && Widgets.over(x, hy, w, HEAD_H);
        Style.fill(x + 1f, hy, w - 8f, HEAD_H - 1f, overHead ? Style.HOVER : Style.BAR);
        Style.fill(x + 1f, hy, 3f, HEAD_H - 1f, Style.ACCENT);
        float ty = top - (HEAD_H - Style.lineHeight(Style.SMALL_FONT)) / 2f;
        Style.text("CHAT", Style.SMALL_FONT, Style.ACCENT, x + 12f, ty, 60f);
        int unread = ChatLog.unread();
        if (opened) ChatLog.seen();
        String right = opened ? (minimizable ? "" : "FOLD") : open ? (unread > 0 ? unread + " NEW" : "CLICK TO CHAT") : unread > 0 ? unread + " NEW" : "OPEN";
        float rightEdge = x + w - 12f;
        if (minimizable) { // a minimize button at the right of the head: the box becomes a small CHAT tab
            float bs = HEAD_H - 8f, bx = x + w - 8f - bs, by = hy + 4f;
            Widgets.minimizeBox(bx, by, bs);
            hits.add(bx, by, bs, bs, "fold");
            rightEdge = bx - 8f;
            if (opened) Style.textRight(Keys.name("chat") + ": type", Style.SMALL_FONT, Style.FAINT, rightEdge, ty);
        }
        Style.textRight(right, Style.SMALL_FONT, !opened && unread > 0 ? Panel.WARN : Style.DIM, rightEdge, ty);
        if (!opened && (unread > 0 || open)) { // the newest line, faint, so a glance tells what it is about
            List<ChatLog.Line> last = ChatLog.last(1);
            if (!last.isEmpty()) {
                ChatLog.Line l = last.get(0);
                String t = l.name + ": " + l.text;
                float tw = w - 60f - Style.width(right, Style.SMALL_FONT) - 30f;
                Style.text(Widgets.fit(t, Style.SMALL_FONT, tw), Style.SMALL_FONT, Style.FAINT, x + 60f, ty, tw);
            }
        }
        linesBox = null;
        if (open && !opened) { // no room to open it here: the newest line, and a click (or the chat key) asks for the room
            hits.add(x, hy, w, HEAD_H, "type");
            typing = false;
            return y;
        }
        if (!minimizable) hits.add(x, hy, w, HEAD_H, "fold");
        if (!opened) { typing = false; return y; }

        // the lines, newest at the bottom
        float areaTop = hy - 6f, inputY = y + 6f, areaBottom = inputY + INPUT_H + 6f;
        float cx = x + 10f, chipW = Style.width("P00", Style.SMALL_FONT) + 10f, tx = cx + chipW + 6f, tw = x + w - 10f - tx;
        List<ChatLog.Line> lines = ChatLog.last(ChatLog.KEPT);
        // scrolled back (the wheel): stays on the same lines as new ones arrive
        int v = ChatLog.version();
        if (back > 0 && v != seenVersion) back += Math.max(0, lines.size() - seenSize);
        seenVersion = v;
        seenSize = lines.size();
        back = Math.max(0, Math.min(back, lines.size() - 1));
        float at = areaBottom;
        if (lines.isEmpty()) {
            Style.text("Nothing said yet. Only the players in this game see it.", Style.SMALL_FONT, Style.FAINT, cx, areaTop - 2f, w - 20f);
        }
        linesBox = new float[] { x, areaBottom, w, areaTop - areaBottom };
        if (back > 0) { // a line along the bottom of the lines: back to the newest
            float bh = Style.lineHeight(Style.SMALL_FONT) + 4f;
            boolean over = Widgets.over(x + 8f, at, w - 16f, bh);
            Style.fill(x + 8f, at, w - 16f, bh, over ? Style.HOVER : Style.alpha(Style.ACCENT, 20));
            Style.text(back + " newer below   (wheel down, or click)", Style.SMALL_FONT, over ? Style.TEXT : Style.DIM, x + 16f, at + bh - 2f, w - 32f);
            hits.add(x + 8f, at, w - 16f, bh, "newest");
            at += bh + 4f;
        }
        for (int i = lines.size() - 1 - back; i >= 0; i--) {
            ChatLog.Line l = lines.get(i);
            String t = l.name + ":  " + l.text;
            float th = Style.height(t, body, tw);
            if (at + th > areaTop) break;
            Style.chip("P" + l.slot, PlayerTags.color(l.slot), cx, at + th + 1f);
            Style.text(t, body, Style.TEXT, tx, at + th, tw);
            at += th + 4f;
        }
        // the line to type in
        boolean overInput = Widgets.over(x + 8f, inputY, w - 16f, INPUT_H);
        Style.fill(x + 8f, inputY, w - 16f, INPUT_H, typing ? new Color(0, 0, 0, 140) : overInput ? Style.HOVER : new Color(0, 0, 0, 80));
        Style.outline(x + 8f, inputY, w - 16f, INPUT_H, typing ? Style.ACCENT : Style.alpha(Style.ACCENT, 90));
        boolean blink = (System.currentTimeMillis() / 450) % 2 == 0;
        String shown = typing ? draft + (blink ? "_" : " ") : draft.length() > 0 ? draft.toString()
                : !problem.isEmpty() ? problem : "Click here (or " + Keys.name("chat") + ") to say something";
        Color sc = typing || draft.length() > 0 ? Style.TEXT : !problem.isEmpty() ? Panel.WARN : Style.FAINT;
        float iw = w - 36f;
        // the end of what is typed stays in view (a hint is cut at its end)
        if (!typing && draft.length() == 0) shown = Widgets.fit(shown, body, iw);
        else if (Style.width(shown, body) > iw) {
            String s = shown;
            while (s.length() > 1 && Style.width("..." + s, body) > iw) s = s.substring(1);
            shown = "..." + s;
        }
        Style.text(shown, body, sc, x + 16f, inputY + (INPUT_H + Style.lineHeight(body)) / 2f, iw);
        hits.add(x + 8f, inputY, w - 16f, INPUT_H, "type");
        return y;
    }

    /** The mouse wheel at a point: over the lines, back through them (up) or towards the newest (down). True if taken. */
    boolean wheel(float mx, float my, int value) {
        float[] b = linesBox;
        if (!shown() || !open || b == null || mx < b[0] || mx > b[0] + b[2] || my < b[1] || my > b[1] + b[3]) return false;
        back = Math.max(0, back + (value > 0 ? 2 : -2));
        return true;
    }

    /** What of the box is under the point ("" the box itself), or null. */
    String at(float mx, float my) { return shown() ? hits.at(mx, my) : null; }

    /** A click on it (by what at() said). */
    void click(String id) {
        if (id == null) { typing = false; return; }
        if (id.equals("fold")) { setOpen(!open); Widgets.click(); }
        else if (id.equals("type")) { typing = true; problem = ""; }
        else if (id.equals("newest")) back = 0;
    }

    /** Start typing (the chat key): opens it too. */
    void startTyping() {
        if (!open) setOpen(true);
        typing = true;
        problem = "";
        drawnAt = System.currentTimeMillis(); // (it is drawn from the next frame: what is typed until then is kept)
    }

    /**
     * A key going down. True if the chat took it: always while typing; otherwise only the chat key. Read everything
     * off the event before handing it here, and consume it when this says so.
     */
    boolean key(int key, char ch, boolean ctrl, boolean shift, boolean alt) {
        if (!shown()) { typing = false; return false; }
        if (!typing) {
            if (shown() && Keys.is("chat", key, ctrl, shift, alt)) { startTyping(); return true; }
            return false;
        }
        if (key == 1) { typing = false; return true; }                        // Esc: stop typing (what is typed stays)
        if (Panel.isEnter(key)) {                                              // Enter: say it
            String text = ChatLog.clean(draft.toString());
            if (text.isEmpty()) { typing = false; return true; }
            boolean sent = false;
            try { sent = sender != null && sender.send(text); } catch (Throwable t) { sent = false; }
            if (sent) { draft.setLength(0); problem = ""; }
            else problem = "Not sent: not connected right now.";
            if (!sent) typing = false;
            return true;
        }
        if (key == 14) { if (draft.length() > 0) draft.setLength(draft.length() - 1); return true; } // Backspace
        if (ctrl || alt) return true;
        if (ch >= ' ' && ch != 127 && !Character.isISOControl(ch) && draft.length() < ChatLog.MAX_LINE) draft.append(ch);
        return true;
    }
}
