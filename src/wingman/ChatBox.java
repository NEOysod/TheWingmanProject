package wingman;

import java.awt.Color;
import java.util.List;

import wingman.net.ChatLog;

/**
 * The co-op chat as a small box: under the CO-OP panel on the host's campaign map, under PLAYERS in a joiner's lobby.
 * Folded it is one line (CHAT, and how many lines are new); opened it shows the last lines and a line to type in.
 * Typing starts with a click on that line or the chat key (Keys "chat"); Enter sends, Esc stops typing. While typing,
 * every key is the chat's; otherwise none is.
 */
final class ChatBox {
    interface Sender { boolean send(String text); }

    /** The host's box (folded until opened: it stays out of the way) and the joiner's (open). */
    static final ChatBox host = new ChatBox(false), joiner = new ChatBox(true);

    boolean open, typing;
    final StringBuilder draft = new StringBuilder();
    Sender sender;
    private final Widgets.Hits hits = new Widgets.Hits();
    private long drawnAt;
    private String problem = "";

    static final float HEAD_H = 26f, INPUT_H = 28f, LINES_H = 150f;

    private ChatBox(boolean open) { this.open = open; }

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
        boolean overHead = Widgets.over(x, hy, w, HEAD_H);
        Style.fill(x + 1f, hy, w - 8f, HEAD_H - 1f, overHead ? Style.HOVER : Style.BAR);
        Style.fill(x + 1f, hy, 3f, HEAD_H - 1f, Style.ACCENT);
        float ty = top - (HEAD_H - Style.lineHeight(Style.SMALL_FONT)) / 2f;
        Style.text("CHAT", Style.SMALL_FONT, Style.ACCENT, x + 12f, ty, 60f);
        int unread = ChatLog.unread();
        if (opened) ChatLog.seen();
        String right = opened ? "FOLD" : open ? (unread > 0 ? unread + " NEW" : "CLICK TO CHAT") : unread > 0 ? unread + " NEW" : "OPEN";
        Style.textRight(right, Style.SMALL_FONT, !opened && unread > 0 ? Panel.WARN : Style.DIM, x + w - 12f, ty);
        if (!opened && (unread > 0 || open)) { // the newest line, faint, so a glance tells what it is about
            List<ChatLog.Line> last = ChatLog.last(1);
            if (!last.isEmpty()) {
                ChatLog.Line l = last.get(0);
                String t = l.name + ": " + l.text;
                float tw = w - 60f - Style.width(right, Style.SMALL_FONT) - 30f;
                Style.text(Widgets.fit(t, Style.SMALL_FONT, tw), Style.SMALL_FONT, Style.FAINT, x + 60f, ty, tw);
            }
        }
        if (open && !opened) { // no room to open it here: the newest line, and a click (or the chat key) asks for the room
            hits.add(x, hy, w, HEAD_H, "type");
            typing = false;
            return y;
        }
        hits.add(x, hy, w, HEAD_H, "fold");
        if (!opened) { typing = false; return y; }

        // the lines, newest at the bottom
        float areaTop = hy - 6f, inputY = y + 6f, areaBottom = inputY + INPUT_H + 6f;
        float cx = x + 10f, chipW = Style.width("P00", Style.SMALL_FONT) + 10f, tx = cx + chipW + 6f, tw = x + w - 10f - tx;
        List<ChatLog.Line> lines = ChatLog.last(40);
        float at = areaBottom;
        if (lines.isEmpty()) {
            Style.text("Nothing said yet. Only the players in this game see it.", Style.SMALL_FONT, Style.FAINT, cx, areaTop - 2f, w - 20f);
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
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

    /** What of the box is under the point ("" the box itself), or null. */
    String at(float mx, float my) { return shown() ? hits.at(mx, my) : null; }

    /** A click on it (by what at() said). */
    void click(String id) {
        if (id == null) { typing = false; return; }
        if (id.equals("fold")) { open = !open; typing = open && typing; Widgets.click(); }
        else if (id.equals("type")) { typing = true; problem = ""; }
    }

    /** Start typing (the chat key): opens it too. */
    void startTyping() { open = true; typing = true; problem = ""; }

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
