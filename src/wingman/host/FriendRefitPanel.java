package wingman.host;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.LabelAPI;

import wingman.CampaignSettings;
import wingman.Panel;
import wingman.PlayerTags;
import wingman.Style;
import wingman.net.HostSession;
import wingman.net.RefitPlan;

/** Host-only review and per-connection refit permissions on the campaign map. */
public final class FriendRefitPanel implements CampaignInputListener, CampaignUIRenderingListener {
    private static final int REQUESTS = 0, PLAYERS = 1;
    private static final int SECTION = 0, LINE = 1, CALLOUT = 2;
    private static final List<String> TABS = Arrays.asList("REQUESTS", "PLAYERS");
    private static final String KEYS = "Left: previous     Right: next     Q: page back     E: page forward     Tab: switch tab     Esc: close";

    private final Panel panel = new Panel();
    private boolean open, broken, launcherShown, renderedOpen;
    private int tab = REQUESTS, page, renderedTab = -1, renderedPage = -1;
    private final boolean[] keyHeld = new boolean[256];
    private final Set<Integer> mouseHeld = new HashSet<Integer>();
    private float launcherX, launcherY, launcherW, launcherH;
    private int launcherCount = -1;
    private String launcherText = "";
    private FriendRefit.Pending selectedRequest, renderedRequest;
    private HostSession.Player selectedPlayer, renderedPlayer;
    private boolean renderedBypass;
    private String message = "";
    private Color messageColor = Panel.DIM;

    private static final class Line {
        final int kind;
        final String text;
        final Color color;
        final float height;
        Line(int kind, String text, Color color, float height) {
            this.kind = kind;
            this.text = text;
            this.color = color;
            this.height = height;
        }
    }

    private final List<Line> lines = new ArrayList<Line>();
    private final List<Integer> pageStarts = new ArrayList<Integer>();
    private LabelAPI measure;
    private Panel.Doc document;
    private int documentPage = -1;
    private float bodyWidth, bodyBudget, cachedWidth = -1f, cachedHeight = -1f;
    private int cachedTab = -1, cachedCount = -1, cachedIndex = -1, cachedOp, cachedOpMax;
    private FriendRefit.Pending cachedRequest;
    private HostSession.Player cachedPlayer;
    private RefitPlan cachedPlan;
    private List<String> cachedProblems;
    private boolean cachedDocked, cachedBypass;
    private String cachedName, cachedShip, cachedMessage;
    private Color cachedMessageColor;
    private long dockedAt;
    private boolean docked;

    public FriendRefitPanel() { panel.mouse = true; }

    public static void install() {
        try {
            Global.getSector().getListenerManager().addListener(new FriendRefitPanel(), true);
        } catch (Throwable t) {
            Global.getLogger(FriendRefitPanel.class).warn("Wingman: friend refit review can't be shown on the campaign map", t);
        }
    }

    public int getListenerInputPriority() { return 110; }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        if (broken || events == null || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            for (InputEventAPI event : events) {
                if (event.isConsumed()) continue;
                if (event.isMouseEvent()) {
                    boolean down = event.isMouseDownEvent(), up = event.isMouseUpEvent();
                    boolean leftDown = event.isLMBDownEvent();
                    float x = event.getX(), y = event.getY();
                    int button = down || up ? event.getEventValue() : -1;
                    boolean onLauncher = !open && launcherShown && !CampaignSettings.isOpen()
                            && x >= launcherX && x <= launcherX + launcherW && y >= launcherY && y <= launcherY + launcherH;
                    boolean take = open || ((down || up) && mouseHeld.contains(button)) || (leftDown && onLauncher);
                    if (!take) continue;
                    if (down) mouseHeld.add(button);
                    if (up) mouseHeld.remove(button);
                    event.consume();
                    if (leftDown) {
                        if (open && renderedOpen) click(x, y);
                        else if (onLauncher) show();
                    }
                    continue;
                }
                if (!event.isKeyboardEvent()) continue;
                boolean down = event.isKeyDownEvent(), up = event.isKeyUpEvent();
                boolean ctrl = event.isCtrlDown(), shift = event.isShiftDown(), repeat = event.isRepeat();
                int key = event.getEventValue();
                boolean held = key >= 0 && key < keyHeld.length && keyHeld[key];
                boolean shortcut = down && !repeat && ctrl && shift && key == Keyboard.KEY_R;
                if (!open && !held && (!shortcut || CampaignSettings.isOpen())) continue;
                if (key >= 0 && key < keyHeld.length) {
                    if (down) keyHeld[key] = true;
                    if (up) keyHeld[key] = false;
                }
                event.consume();
                if (!down || repeat) continue;
                if (!open) show();
                else key(key, ctrl, shift);
            }
        } catch (Throwable t) {
            open = false;
            renderedOpen = false;
            Global.getLogger(FriendRefitPanel.class).warn("Wingman: friend refit review failed and was closed", t);
        }
    }

    private void show() {
        open = true;
        renderedOpen = false;
        tab = FriendRefit.pending.isEmpty() ? PLAYERS : REQUESTS;
        page = 0;
        message = "";
    }

    private void click(float x, float y) {
        if (!panel.contains(x, y)) return;
        Panel.Hit hit = panel.hitAt(x, y);
        if (hit == null) return;
        if (hit.kind == Panel.Hit.TAB) {
            if (hit.value != tab && hit.value >= REQUESTS && hit.value <= PLAYERS) switchTab(hit.value);
        } else if (hit.kind == Panel.Hit.KEY) {
            key(hit.value, false, false);
        }
    }

    private void key(int key, boolean ctrl, boolean shift) {
        if (key == Keyboard.KEY_ESCAPE || (key == Keyboard.KEY_R && ctrl && shift)) {
            open = false;
            renderedOpen = false;
            return;
        }
        if (!renderedOpen || tab != renderedTab || page != renderedPage) return;
        if (key == Keyboard.KEY_TAB) {
            switchTab(tab == REQUESTS ? PLAYERS : REQUESTS);
            return;
        }
        if (key == Keyboard.KEY_Q || key == Keyboard.KEY_E) {
            int next = page + (key == Keyboard.KEY_Q ? -1 : 1);
            if (next >= 0 && next < pageStarts.size()) { page = next; documentPage = -1; }
            return;
        }
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT) {
            int direction = key == Keyboard.KEY_LEFT ? -1 : 1;
            if (tab == REQUESTS && selectedRequest == renderedRequest && isPending(renderedRequest)) {
                selectedRequest = nextRequest(renderedRequest, direction);
                page = 0;
                message = "";
            } else if (tab == PLAYERS && selectedPlayer == renderedPlayer && live(HostSession.active(), renderedPlayer)) {
                selectedPlayer = nextPlayer(renderedPlayer, direction);
                page = 0;
                message = "";
            }
            return;
        }
        if (tab == REQUESTS && (key == Keyboard.KEY_A || key == Keyboard.KEY_D)) {
            FriendRefit.Pending request = renderedRequest;
            if (selectedRequest != request || !isPending(request) || !live(HostSession.active(), request.player)) {
                say("That request or player is no longer current.", Panel.WARN);
                return;
            }
            if (key == Keyboard.KEY_A) {
                String why = FriendRefit.accept(request);
                say(why == null ? "Accepted. " + request.ship + " is refitted." : why, why == null ? Panel.GOOD : Panel.BAD);
            } else {
                FriendRefit.deny(request);
                say(isPending(request) ? "That request could not be denied; review the latest request."
                        : live(HostSession.active(), request.player) ? "Denied. " + request.player.name + " has been told."
                        : "The player disconnected; the request is no longer waiting.", Panel.WARN);
            }
        } else if (tab == PLAYERS && key == Keyboard.KEY_SPACE) {
            HostSession.Player player = renderedPlayer;
            if (selectedPlayer != player || !live(HostSession.active(), player)) {
                say("That player is no longer connected.", Panel.WARN);
                return;
            }
            if (FriendRefit.bypass(player) != renderedBypass) {
                say("Permission changed. Review the current setting before toggling it.", Panel.WARN);
                return;
            }
            FriendRefit.setBypass(player, !renderedBypass);
            if (!live(HostSession.active(), player)) say("That player is no longer connected.", Panel.WARN);
            else say(player.name + (renderedBypass ? " now requires confirmation." : " may refit without confirmation."), Panel.GOOD);
        }
    }

    private void switchTab(int next) {
        tab = next;
        page = 0;
        message = "";
    }

    private void say(String text, Color color) {
        message = text == null ? "" : text;
        messageColor = color;
        page = 0;
    }

    private static boolean isPending(FriendRefit.Pending request) {
        return request != null && request.player != null && FriendRefit.pending.get(request.player.id) == request;
    }

    private static boolean live(HostSession session, HostSession.Player player) {
        return session != null && player != null && !player.replaced && player.conn != null && !player.conn.isClosed()
                && session.getPlayers().contains(player);
    }

    private static int playerCount(HostSession session) {
        if (session == null) return 0;
        int count = 0;
        List<HostSession.Player> players = session.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            HostSession.Player p = players.get(i);
            if (!p.replaced && p.conn != null && !p.conn.isClosed()) count++;
        }
        return count;
    }

    private static HostSession.Player firstPlayer(HostSession session) {
        if (session == null) return null;
        List<HostSession.Player> players = session.getPlayers();
        for (int i = 0; i < players.size(); i++) if (live(session, players.get(i))) return players.get(i);
        return null;
    }

    private static int playerIndex(HostSession session, HostSession.Player selected) {
        if (session == null || selected == null) return -1;
        int index = 0;
        for (HostSession.Player player : session.getPlayers()) {
            if (!live(session, player)) continue;
            if (player == selected) return index;
            index++;
        }
        return -1;
    }

    private static HostSession.Player nextPlayer(HostSession.Player selected, int direction) {
        HostSession session = HostSession.active();
        if (!live(session, selected)) return selected;
        List<HostSession.Player> players = session.getPlayers();
        int start = players.indexOf(selected), size = players.size();
        for (int step = 1; step <= size; step++) {
            HostSession.Player p = players.get((start + direction * step + size) % size);
            if (live(session, p)) return p;
        }
        return selected;
    }

    private static int requestIndex(FriendRefit.Pending selected) {
        int index = 0;
        for (FriendRefit.Pending request : FriendRefit.pending.values()) {
            if (request == selected) return index;
            index++;
        }
        return -1;
    }

    private static FriendRefit.Pending nextRequest(FriendRefit.Pending selected, int direction) {
        FriendRefit.Pending previous = null, first = null, last = null;
        boolean found = false;
        for (FriendRefit.Pending request : FriendRefit.pending.values()) {
            if (first == null) first = request;
            if (found && direction > 0) return request;
            if (request == selected) {
                if (direction < 0 && previous != null) return previous;
                found = true;
            }
            previous = request;
            last = request;
        }
        return found ? (direction > 0 ? first : last) : selected;
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            HostSession session = HostSession.active();
            int players = playerCount(session), requests = FriendRefit.pending.size();
            if (!open) {
                renderedOpen = false;
                launcherShown = false;
                if (!CampaignSettings.isOpen() && (players > 0 || requests > 0)) drawLauncher(requests);
                return;
            }
            launcherShown = false;
            if (!isPending(selectedRequest)) selectedRequest = requests == 0 ? null : FriendRefit.pending.values().iterator().next();
            if (!live(session, selectedPlayer)) selectedPlayer = firstPlayer(session);
            int count = tab == REQUESTS ? requests : players;
            int index = tab == REQUESTS ? requestIndex(selectedRequest) : playerIndex(session, selectedPlayer);
            boolean bypass = selectedPlayer != null && FriendRefit.bypass(selectedPlayer);
            long now = System.currentTimeMillis();
            if (tab == REQUESTS && selectedRequest != null && (dockedAt == 0 || now - dockedAt >= 1000)) {
                docked = FriendRefit.docked();
                dockedAt = now;
            }
            float width = Math.min(600f, Global.getSettings().getScreenWidth() - 16f);
            float height = Global.getSettings().getScreenHeight();
            prepare(width, height, count, index, bypass);
            panel.renderCentred(width, height - 4f, document());
            renderedTab = tab;
            renderedPage = page;
            renderedRequest = selectedRequest;
            renderedPlayer = selectedPlayer;
            renderedBypass = bypass;
            renderedOpen = true;
        } catch (Throwable t) {
            open = false;
            renderedOpen = false;
            broken = true;
            Style.forget();
            Global.getLogger(FriendRefitPanel.class).error("Wingman: friend refit review can't be drawn and is switched off", t);
        }
    }

    private void drawLauncher(int count) {
        if (launcherCount != count) {
            launcherCount = count;
            launcherText = "FRIEND REFITS (" + count + ")   CTRL+SHIFT+R";
        }
        float screenW = Global.getSettings().getScreenWidth(), screenH = Global.getSettings().getScreenHeight();
        launcherW = Math.min(screenW - 16f, Style.width(launcherText, Style.SMALL_FONT) + 24f);
        launcherH = 28f;
        launcherX = screenW - launcherW - 8f;
        launcherY = Math.max(4f, Math.min(screenH, CampaignPlayerList.bottom) - launcherH - 10f);
        Style.button(launcherX, launcherY, launcherW, launcherH, launcherText, Style.ACCENT, true,
                Style.over(launcherX, launcherY, launcherW, launcherH));
        launcherShown = true;
    }

    private void prepare(float width, float height, int count, int index, boolean bypass) {
        FriendRefit.Pending request = tab == REQUESTS ? selectedRequest : null;
        HostSession.Player player = tab == PLAYERS ? selectedPlayer : null;
        RefitPlan plan = request == null ? null : request.plan;
        List<String> problems = request == null ? null : request.problems;
        String name = request != null ? request.player.name : player != null ? player.name : "";
        String ship = request == null ? "" : request.ship;
        int op = request == null ? 0 : request.op, opMax = request == null ? 0 : request.opMax;
        boolean atMarket = request != null && docked;
        if (cachedTab == tab && cachedRequest == request && cachedPlayer == player && cachedPlan == plan
                && cachedProblems == problems && cachedCount == count && cachedIndex == index
                && cachedWidth == width && cachedHeight == height && cachedDocked == atMarket
                && cachedBypass == bypass && cachedOp == op && cachedOpMax == opMax
                && same(cachedName, name) && same(cachedShip, ship) && same(cachedMessage, message)
                && cachedMessageColor == messageColor) return;
        cachedTab = tab; cachedRequest = request; cachedPlayer = player; cachedPlan = plan; cachedProblems = problems;
        cachedCount = count; cachedIndex = index; cachedWidth = width; cachedHeight = height;
        cachedDocked = atMarket; cachedBypass = bypass; cachedOp = op; cachedOpMax = opMax;
        cachedName = name; cachedShip = ship; cachedMessage = message; cachedMessageColor = messageColor;
        document = null;
        documentPage = -1;
        lines.clear();
        pageStarts.clear();
        bodyWidth = width - 2f * Style.PAD;
        float keysHeight = Style.keysHeight(Style.keys(KEYS), bodyWidth) + 12f;
        float buttonsHeight = request != null || player != null ? 52f : 0f;
        bodyBudget = Math.max(Style.lineHeight(Style.body()) + 5f,
                height - 8f - Style.BAR_H - Style.TAB_H - 4f - Style.PAD - Style.PAD * 0.75f - keysHeight - buttonsHeight);
        if (!message.isEmpty()) add(CALLOUT, message, messageColor);
        if (tab == REQUESTS) requestLines(request, atMarket);
        else playerLines(player, bypass);
        paginate();
        page = Math.max(0, Math.min(page, pageStarts.size() - 1));
    }

    private static boolean same(String a, String b) { return a == b || (a != null && a.equals(b)); }

    private void requestLines(FriendRefit.Pending request, boolean atMarket) {
        section("Request");
        if (request == null) {
            add(LINE, "No refit requests are waiting.", Panel.DIM);
            add(LINE, "Open PLAYERS to choose who needs confirmation, even when there are no requests.", Panel.TEXT);
            return;
        }
        add(LINE, request.player.name + " asks for a refit of " + request.ship + ".", PlayerTags.color(request.player.slot));
        if (request.request.note != null && !request.request.note.isEmpty()) add(CALLOUT, "Note: " + request.request.note, Style.ACCENT);
        if (request.plan == null) {
            add(CALLOUT, "Waiting for the refit checks.", Panel.WARN);
            return;
        }
        section("Cost");
        int net = request.plan.net();
        add(LINE, "Ordnance points: " + request.op + " / " + request.opMax, Panel.TEXT);
        add(LINE, "Credits: " + (net > 0 ? "costs " + Style.grouped(net) : net < 0 ? "brings in " + Style.grouped(-(long) net) : "none"), Panel.TEXT);
        add(LINE, "Story points: " + request.plan.storyPoints, Panel.TEXT);
        if (atMarket && (net != request.request.expectCredits || request.plan.storyPoints != request.request.expectStoryPoints)) {
            add(CALLOUT, request.player.name + " was shown " + Style.grouped(request.request.expectCredits) + " credits and "
                    + request.request.expectStoryPoints + " story points. The cost has changed.", Panel.WARN);
        }
        section("Checks");
        if (!atMarket) add(CALLOUT, "Dock at a market to refresh the checks and accept this refit.", Panel.WARN);
        if (request.problems.isEmpty()) {
            if (atMarket) add(CALLOUT, "All checks pass. They run again on acceptance.", Panel.GOOD);
        } else {
            for (String problem : request.problems) add(CALLOUT, problem, Panel.BAD);
        }
        if (!request.request.addMods.isEmpty()) {
            add(LINE, "Hull mods' own requirements and exclusions can't be checked outside the refit screen. You decide.", Panel.DIM);
        }
        section("Changes");
        List<String> changes = FriendRefit.describe(request);
        if (changes.isEmpty()) add(LINE, "No changes remain.", Panel.DIM);
        else for (String change : changes) add(LINE, change, Panel.TEXT);
    }

    private void playerLines(HostSession.Player player, boolean bypass) {
        section("Player permission");
        if (player == null) {
            add(LINE, "No players are connected.", Panel.DIM);
        } else {
            add(LINE, "P" + player.slot + "  " + player.name, PlayerTags.color(player.slot));
            add(CALLOUT, bypass ? "No confirmation: this player may refit without asking you."
                    : "Confirmation required: review this player's refit requests before they are applied.", bypass ? Panel.WARN : Panel.GOOD);
            if (!bypass) add(LINE, "Enabling this may apply that player's waiting request immediately.", Panel.WARN);
            add(LINE, "Use Left and Right to select a connected player.", Panel.DIM);
        }
        add(LINE, "Permission lasts only for the current connection. It clears on reconnect or campaign load.", Panel.TEXT);
    }

    private void section(String text) {
        lines.add(new Line(SECTION, text, Style.ACCENT, Style.lineHeight(Style.SMALL_FONT) + 6f));
    }

    private void add(int kind, String text, Color color) {
        if (text == null || text.isEmpty()) return;
        float width = bodyWidth - (kind == CALLOUT ? 14f : 0f);
        float extra = kind == CALLOUT ? 13f : 5f;
        float limit = Math.max(Style.lineHeight(Style.body()), bodyBudget - extra);
        String remaining = breakLongWords(text, Math.max(10f, width));
        while (!remaining.isEmpty()) {
            int end = remaining.length();
            if (Style.height(remaining, Style.body(), width) > limit) {
                int low = 1, high = end;
                while (low < high) {
                    int middle = (low + high + 1) / 2;
                    if (Style.height(remaining.substring(0, middle), Style.body(), width) <= limit) low = middle;
                    else high = middle - 1;
                }
                end = low;
                if (end < remaining.length() && end > 1 && Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
                int space = end;
                while (space > 0 && !Character.isWhitespace(remaining.charAt(space - 1))) space--;
                if (space > end / 2) end = space;
            }
            String part = remaining.substring(0, end).trim();
            if (!part.isEmpty()) lines.add(new Line(kind, part, color, Style.height(part, Style.body(), width) + extra));
            remaining = remaining.substring(end).trim();
        }
    }

    private String breakLongWords(String text, float width) {
        if (measure == null) measure = Global.getSettings().createLabel("Ag", Style.body());
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length();) {
            int end = at + 1;
            boolean whitespace = Character.isWhitespace(text.charAt(at));
            while (end < text.length() && Character.isWhitespace(text.charAt(end)) == whitespace) end++;
            String word = text.substring(at, end);
            if (whitespace || measure.computeTextWidth(word) <= width - 4f) out.append(word);
            else {
                int from = 0;
                while (from < word.length()) {
                    int low = from + 1, high = word.length();
                    while (low < high) {
                        int middle = (low + high + 1) / 2;
                        if (measure.computeTextWidth(word.substring(from, middle)) <= width - 4f) low = middle;
                        else high = middle - 1;
                    }
                    int to = low;
                    if (to < word.length() && to > from + 1 && Character.isHighSurrogate(word.charAt(to - 1))) to--;
                    out.append(word, from, to);
                    if (to < word.length()) out.append('\n');
                    from = to;
                }
            }
            at = end;
        }
        return out.toString();
    }

    private void paginate() {
        pageStarts.add(0);
        int start = 0;
        float used = 0f;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float height = line.height + (line.kind == SECTION && i > start ? 10f : 0f);
            if (i > start && used + height > bodyBudget) {
                pageStarts.add(i);
                start = i;
                used = 0f;
                height = line.height;
            }
            used += height;
        }
    }

    private Panel.Doc document() {
        if (document != null && documentPage == page) return document;
        String note = (cachedIndex < 0 ? "" : (cachedIndex + 1) + " / " + cachedCount + "   ")
                + "PAGE " + (page + 1) + " / " + pageStarts.size();
        Panel.Doc d = new Panel.Doc("FRIEND REFITS").note(note).tabs(TABS, tab).keys(KEYS);
        int start = pageStarts.get(page), end = page + 1 < pageStarts.size() ? pageStarts.get(page + 1) : lines.size();
        for (int i = start; i < end; i++) {
            Line line = lines.get(i);
            if (line.kind == SECTION) d.section(line.text);
            else if (line.kind == CALLOUT) d.callout(line.text, line.color);
            else d.line(line.text, line.color);
        }
        if (tab == REQUESTS && selectedRequest != null) {
            d.buttons(Panel.Doc.button("A: ACCEPT", Keyboard.KEY_A, Panel.GOOD),
                    Panel.Doc.button("D: DENY", Keyboard.KEY_D, Panel.BAD));
        } else if (tab == PLAYERS && selectedPlayer != null) {
            d.buttons(Panel.Doc.button("SPACE: TOGGLE", Keyboard.KEY_SPACE, Style.ACCENT));
        }
        document = d;
        documentPage = page;
        return d;
    }
}
