package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Mouse;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import wingman.net.BattleInfo;
import wingman.net.ClientLink;
import wingman.net.Conn;
import wingman.net.HostSession;
import wingman.net.In;
import wingman.net.Msg;

/**
 * The "Co-op" button on the title screen, the panel it opens, and the how-to pages.
 *
 * The title screen is itself a small battle playing in the background, so a combat plugin runs there too and
 * may draw on it. The button, the panel and the pages are drawn by hand and the mouse and keyboard are read
 * directly; nothing is added to the game's own menu.
 *
 * The panel takes the host's address, the player's name and a password if the host set one, remembers them,
 * and connects straight away so that a wrong address or a mod mismatch is found out here. Enter again starts
 * the join mission (see TitleLauncher). The first time the mod is run, the how-to pages open by themselves.
 */
public class WingmanTitlePlugin extends BaseEveryFrameCombatPlugin {
    private static final float W = 150f, H = 46f, MARGIN = 8f;
    /** Bumped when the how-to pages change enough that everyone should see them again. */
    private static final String HELP_VERSION = "2";
    private static final String SEEN_FILE = "wingman_seen";

    /** Set by the join mission when it leaves a battle only to come straight back (wrong map size). */
    static volatile boolean rejoinRequested;
    static volatile long lastAutoRejoin;
    /** Set by the join mission as it leaves: the host's battle had ended / we left one that was still going. */
    static volatile boolean hostBattleOver, leftOnPurpose;
    /** When to join the host's battle by ourselves (0: not counting down), and "not this battle". */
    private long autoJoinAt;
    private boolean autoJoinSkip;
    private String withHost = "";

    private final Panel panel = new Panel();
    private final JoinForm form = new JoinForm();
    private final Settings.Menu settings = new Settings.Menu();
    private boolean open, wasDown, broken;
    private String hostLine = "";
    private String myAddresses;
    private ClientLink.State lastState = ClientLink.State.IDLE;
    private String connectedTo = "";
    /** The address box as it was when the current connection was made: Enter on the same address joins the battle. */
    private String connectedRaw;
    private boolean welcomed, launchFailed;
    /** -1: not showing. Otherwise the how-to page on screen. */
    private int helpPage = -1;
    private boolean firstRunChecked;
    /** -1: not showing. Otherwise the page of the compatibility report on screen. */
    private int reportPage = -1;
    private List<String> report;
    private static final int REPORT_LINES = 9;
    private long lastHostCheck, rejoinAt;
    private WingmanConfig cfg;

    // ---- the pre-battle lobby (see CampaignLobby): the host's fleet, and the ship we pick from it ----
    private wingman.net.Roster roster;
    private boolean lobbyOpen, lobbyShown, wasClickDown;
    private int lobbyAt;
    private String lobbyNote = "";
    private java.awt.Color lobbyNoteColor = Panel.DIM;
    private static final int LOBBY_ROWS = 9;
    /** The lobby as a screen of its own (the game's menu moved aside); the corner box if that can't be done. */
    private final LobbyScreen lobby = new LobbyScreen();
    /** Friend refit: the refit screen is open on this member ("" closed), and what the host last sent for it. */
    private String refitMember = "";
    private wingman.net.RefitData refitData;

    private static final String[] HELP_TITLES = {
        "THE WINGMAN PROJECT: WHAT THIS IS", "HOSTING A BATTLE", "JOINING A BATTLE", "IN THE BATTLE", "BEFORE YOU PLAY",
    };
    private static final String[][] HELP = {
        {
            "Co-op for battles. One player hosts a battle; friends join it and each fly a ship from the host's fleet.",
            "Only the battle is shared. The campaign, the fleet and everything won or lost belong to the host.",
            "This is a BETA. It has been played mostly by two people. Expect rough edges, and please report them.",
            "These pages are always available: open the CO-OP panel (top right of the title screen) and press F1.",
        },
        {
            "You don't set anything up. Every battle you start (mission, simulator, campaign) can be joined, and friends can connect as soon as you load a save.",
            "Friends need to reach your PC on TCP port 24816: same network, a virtual LAN (Radmin, Tailscale, ZeroTier), or a port forward.",
            "Your addresses are listed in the CO-OP panel. Read one out to your friends.",
            "Connected friends see your fleet and pick their ships; your message feed shows who picked what. Deploy those ships if you want them flown.",
            "After you deploy, everyone chooses a ship, then the ready check. Enter starts once everyone is ready; Ctrl+Enter starts regardless.",
            "Players are numbered: you are P1, joiners P2, P3, P4. Ctrl+Shift+2 removes P2, and so on. Ctrl+Shift+T takes back the ship you have targeted.",
            "Only you can give fleet orders, pause, or change the battle speed.",
        },
        {
            "Click CO-OP, type the host's address and your name, press Enter. You are connected.",
            "The LOBBY then fills the screen: the host's fleet (Up/Down, F picks the ship you want), who is here, and the last battle (Tab). F4 reopens it.",
            "When the host's battle begins you are taken into it (Enter goes at once). Deploy the one stand-in ship.",
            "Step 1: choose your ship (your lobby pick, if deployed, is already yours). Step 2: Enter for ready.",
            "In the battle, Transfer Command (see Settings > Controls) on a friendly ship changes ship.",
            "When the battle ends you are back in the lobby, still connected, with the host's report of the battle. Enter joins the next one.",
        },
        {
            "Transfer Command again changes ship. U gives your ship to the host's AI and takes it back.",
            "To leave with your ship: give it the Retreat order, or fly to the bottom edge of the map and press Enter.",
            "Your own controls respond at once. Everything else is the host's game arriving over the network, slightly late.",
            "If a player drops, the battle pauses with a notice. Their game tries to reconnect by itself and puts them back in.",
        },
        {
            "Everyone needs the same mods in the same versions as the host. A joiner who differs is refused and shown why.",
            "Without a join password, anyone who can reach the host's port can join. Set one in the settings: F3 in the CO-OP panel.",
            "Settings are in the CO-OP panel (F3), and on Ctrl+Shift+O in a battle or on the campaign map. No file needs editing.",
            "In a campaign battle, a ship lost while a friend flies it is lost for real. Choose who flies what.",
            "If something breaks: turn on the detailed log in the settings on both PCs, reproduce it, and send both starsector.log files.",
        },
    };

    @Override
    public void init(CombatEngineAPI engine) { TitleMenu.forget(); } // a fresh title screen shows its own menu

    private static boolean onTitle() {
        return Global.getCurrentState() == GameState.TITLE;
    }

    private float bx() { return Global.getSettings().getScreenWidth() - W - MARGIN; }
    private float by() { return Global.getSettings().getScreenHeight() - H - MARGIN; }

    /**
     * How wide the panel under the button may be. The game draws its logo and main menu over anything of ours,
     * so the panel hangs from the button in the top right corner and is kept narrow enough to stay clear of
     * the menu, which sits a fixed distance right of the screen's centre. On a screen too narrow for that it
     * takes its normal width and the menu may cover its lower left corner.
     */
    private float boxWidth() {
        float clear = Global.getSettings().getScreenWidth() / 2f - 520f - MARGIN; // from the menu's right edge to ours
        return clear >= 380f ? Math.min(560f, clear) : 560f;
    }

    private boolean overButton() {
        float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
        return mx >= bx() && mx <= bx() + W && my >= by() && my <= by() + H;
    }

    /** The name we last connected under (the host knows us by it). */
    private String connectedName;

    /** Our own keys for mods' LunaLib settings, for the host (see LunaCopy). */
    static void sendLunaKeys(Conn c) {
        try {
            byte[] keys = LunaCopy.encodeKeys();
            if (keys != null && c != null) c.send(Msg.LUNA_KEYS, keys);
        } catch (Throwable t) { /* the host then hears only the keys themselves */ }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (broken || !onTitle()) return;
        CampaignStandIn.removeIfLeft(); // back on the title screen however the battle was left
        LunaCopy.restoreIfLeft();
        wingman.api.WingmanAPI.deliver(); // other mods' messages, while we wait here between battles
        ObjectiveMirror.restoreIfLeft();
        try {
            if (!firstRunChecked) { // the very first time: show the how-to pages without being asked
                firstRunChecked = true;
                boolean seen = false;
                try {
                    seen = Global.getSettings().fileExistsInCommon(SEEN_FILE)
                            && HELP_VERSION.equals(Global.getSettings().readTextFileFromCommon(SEEN_FILE).trim());
                } catch (Throwable t) { /* show them */ }
                if (!seen) helpPage = 0;
            }
            long now = System.currentTimeMillis();
            // We are back on the title screen. If a battle we were hosting was left without being decided,
            // nobody has told the joiners yet: do it now.
            if (now - lastHostCheck > 1000) {
                lastHostCheck = now;
                HostSession.endBattleIfOpen();
            }
            // Coming back from a battle that had the wrong map: go straight in again.
            if (rejoinRequested && rejoinAt == 0) rejoinAt = now + 1200;
            if (rejoinAt != 0 && now >= rejoinAt) {
                rejoinAt = 0;
                rejoinRequested = false;
                if (ClientLink.get().getState() == ClientLink.State.CONNECTED) { join(); if (launchFailed) open = true; }
            }

            if (hostBattleOver) { // back from a battle that ended: wait for word of the next
                hostBattleOver = false;
                hostLine = "";
                autoJoinSkip = false;
                leftOnPurpose = false;
            }
            ClientLink link = ClientLink.get();
            ClientLink.State state = link.getState();
            // Joining the host's battle by ourselves, a few seconds after hearing it has begun.
            if (autoJoinAt != 0 && (state != ClientLink.State.CONNECTED || autoJoinSkip)) autoJoinAt = 0;
            if (autoJoinAt != 0 && now >= autoJoinAt && helpPage < 0 && reportPage < 0 && !settings.open) {
                autoJoinAt = 0;
                Global.getLogger(WingmanTitlePlugin.class).info("Wingman title screen: joining the host's battle automatically");
                join();
                if (launchFailed) open = true;
            }
            if (state != lastState) {
                if (state == ClientLink.State.FAILED && !connectedTo.isEmpty()) {
                    if (form.status.isEmpty() || form.statusColor != Panel.BAD) { form.status = link.getError(); form.statusColor = Panel.BAD; }
                    hostLine = "";
                }
                if (state == ClientLink.State.CONNECTED) { form.status = "Connected to " + connectedTo + "."; form.statusColor = Panel.GOOD; }
                if (state != ClientLink.State.CONNECTED) {
                    welcomed = false; withHost = ""; roster = null; lobbyOpen = false; lobbyShown = false; JoinSetup.lobbyPick = null;
                    refitMember = ""; refitData = null; if (lobby.page == LobbyScreen.REFIT) lobby.page = LobbyScreen.FLEET;
                }
                lastState = state;
            }
            if (state != ClientLink.State.CONNECTED) return;
            if (AfterAction.unseen && helpPage < 0) { // back from a battle (or told of one): the lobby opens on its report
                AfterAction.unseen = false;
                lobbyOpen = true;
                lobbyShown = true;
                open = true;
                closeRefit(null);
                lobby.page = LobbyScreen.REPORT;
            }
            // Nothing else reads the connection while we sit on the title screen, and a host in a battle keeps
            // talking. Read it all, keep the few things worth showing, let the rest go: the mission asks the
            // host for everything afresh when it starts.
            Conn c = link.getConn();
            Conn.Packet p;
            int read = 0;
            while (c != null && read++ < 2000 && (p = c.poll()) != null) {
                if (p.type == Msg.WELCOME) {
                    sendLunaKeys(c);
                    In in = Msg.reader(p.data);
                    in.readInt(); in.readInt(); in.readInt();
                    form.status = "Connected to " + in.readUTF() + "'s game.";
                    form.statusColor = Panel.GOOD;
                    welcomed = true;
                    if (!lobbyShown) { lobbyShown = true; lobbyOpen = true; open = true; } // the lobby is where joiners wait
                } else if (p.type == Msg.REJECT) {
                    form.status = "The host refused the connection: " + Msg.readUtf(p.data).replace('\n', ' ');
                    form.statusColor = Panel.BAD;
                    welcomed = false;
                    JoinSetup.rejectReason = null;
                } else if (p.type == Msg.REPORT) {
                    AfterAction.onReport(p.data);
                } else if (p.type == Msg.AFTERMATH) {
                    AfterAction.onAftermath(p.data);
                } else if (p.type == Msg.LUNA) {
                    LunaCopy.remember(p.data); // put in place with the stand-in campaign, as the join mission is set up
                } else if (p.type == Msg.CAMPAIGN) {
                    JoinSetup.rememberCampaign(p.data); // the stand-in campaign is set up from it with the battlefield
                } else if (p.type == Msg.BATTLE_INFO) {
                    BattleInfo b = BattleInfo.decode(p.data);
                    JoinSetup.remember(b); // the join mission builds its map from this
                    hostLine = "The host is in a battle right now (map " + Math.round(b.width) + " x " + Math.round(b.height) + ").";
                    if (cfg == null) cfg = WingmanConfig.load();
                    welcomed = true; // only a host that has let us in tells us about its battle
                    if (cfg.autoJoin && !autoJoinSkip && !leftOnPurpose && !launchFailed && autoJoinAt == 0) {
                        autoJoinAt = System.currentTimeMillis() + 3000;
                        open = true; // show the countdown
                        if (connectedRaw == null) { form.load(cfg.playerName); connectedRaw = form.address.toString().trim(); connectedTo = connectedRaw; }
                    }
                } else if (p.type == Msg.BATTLE_END) {
                    JoinSetup.battleOver();
                    hostLine = "";
                    autoJoinAt = 0;
                    autoJoinSkip = false;
                    leftOnPurpose = false;
                } else if (p.type == Msg.ROSTER) { // the host's fleet and who picked what (pre-battle lobby)
                    roster = wingman.net.Roster.decode(p.data);
                    welcomed = true;
                    if (cfg == null) cfg = WingmanConfig.load();
                    wingman.net.Roster.Member mine = roster.pickOf(HostSession.cleanName(cfg.playerName));
                    JoinSetup.lobbyPick = mine == null ? null : new String[] { mine.shipName, mine.hullName };
                    StringBuilder names = new StringBuilder(roster.hostName).append(" (host)");
                    for (wingman.net.Roster.Player rp : roster.players) names.append(", ").append(rp.name);
                    withHost = names.toString();
                    if (!refitMember.isEmpty()) { // friend refit: the screen stays only while the host is docked and the ship is ours
                        if (roster.refitMarket.isEmpty()) closeRefit("The host left the market, so the refit screen closed.");
                        else if (mine == null || !mine.id.equals(refitMember)) closeRefit("Your pick changed, so the refit screen closed.");
                    }
                    if (!lobbyShown && roster.hostState == 0) { // the first time on the campaign map: show the lobby by itself
                        lobbyShown = true;
                        lobbyOpen = true;
                        open = true;
                        if (mine != null) lobbyAt = roster.members.indexOf(mine);
                    }
                } else if (p.type == Msg.NOTICE) { // the host's answer to a pick (or a refit) it can't grant
                    lobbyNote = Msg.readUtf(p.data);
                    lobbyNoteColor = Panel.WARN;
                    if (lobbyNote.startsWith("Can't refit")) closeRefit(lobbyNote);
                } else if (p.type == Msg.REFIT_DATA) { // friend refit: what the refit screen shows
                    try {
                        wingman.net.RefitData d = wingman.net.RefitData.decode(p.data);
                        if (d.memberId.equals(refitMember)) refitData = d;
                    } catch (java.io.IOException e) { /* a garbled one is skipped: the next one replaces it */ }
                } else if (p.type == Msg.REFIT_RESULT) { // friend refit: what the host made of our request
                    try {
                        wingman.net.RefitResult r = wingman.net.RefitResult.decode(p.data);
                        lobby.refit.onResult(r);
                        if (refitMember.isEmpty() || r.status == wingman.net.RefitResult.ACCEPTED) {
                            note(r.text.replace('\n', ' '), r.status == wingman.net.RefitResult.ACCEPTED || r.status == wingman.net.RefitResult.RECEIVED ? Panel.GOOD : Panel.WARN);
                        }
                    } catch (java.io.IOException e) { /* skipped */ }
                } else if (p.type == Msg.LOBBY) { // who else is there
                    wingman.net.Lobby lobby = wingman.net.Lobby.decode(p.data);
                    StringBuilder names = new StringBuilder(lobby.hostName).append(" (host)");
                    for (wingman.net.Lobby.Entry e : lobby.players) names.append(", ").append(e.name);
                    withHost = names.toString();
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void fail(Throwable t) {
        broken = true;
        Global.getLogger(WingmanTitlePlugin.class).error("Wingman: the title screen Co-op button failed and is switched off; "
                + "the join screen inside the Wingman: Join Game mission still works", t);
    }

    private void closeHelp() {
        helpPage = -1;
        try { Global.getSettings().writeTextFileToCommon(SEEN_FILE, HELP_VERSION); } catch (Throwable t) { /* shown again next time */ }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (broken || !onTitle() || events == null) return;
        try {
            boolean over = overButton();
            for (InputEventAPI e : events) {
                if (e.isConsumed()) continue;
                // A click on our button must not also land on whatever the title screen has underneath it.
                if (e.isMouseEvent() && (e.isMouseDownEvent() || e.isMouseUpEvent()) && over) { e.consume(); continue; }
                if (e.isMouseScrollEvent() && lobbyOpen && open && lobby.page == LobbyScreen.REFIT) { // the wheel scrolls the refit screen's lists
                    int dir = e.getEventValue();
                    if (lobby.refit.scroll(Global.getSettings().getMouseX(), Global.getSettings().getMouseY(), dir)) e.consume();
                    continue;
                }
                if (!e.isKeyboardEvent()) continue;
                if (settings.open) break; // the settings page takes every key, below
                if (reportPage >= 0) { // so does the compatibility report
                    if (e.isKeyDownEvent()) {
                        int key = e.getEventValue();
                        int pages = Math.max(1, (report.size() + REPORT_LINES - 1) / REPORT_LINES);
                        if (key == 1) reportPage = -1;
                        else if (key == 203 || key == 14) reportPage = Math.max(0, reportPage - 1);
                        else if (key == 211) { Compat.get().reset(); report = Compat.get().report(cfg == null || cfg.hostDecidesScriptedShots); reportPage = 0; } // Delete: forget what was seen
                        else if (Panel.isEnter(key) || key == 205 || key == 57) { if (reportPage + 1 >= pages) reportPage = -1; else reportPage++; }
                    }
                    e.consume();
                    continue;
                }
                if (helpPage >= 0) { // the how-to pages take every key while they are up
                    if (e.isKeyDownEvent()) {
                        int key = e.getEventValue();
                        if (key == 1) closeHelp();
                        else if (key == 203 || key == 14) helpPage = Math.max(0, helpPage - 1); // left, backspace
                        else if (Panel.isEnter(key) || key == 205 || key == 57) { // enter, right, space
                            if (helpPage + 1 >= HELP.length) closeHelp(); else helpPage++;
                        }
                    }
                    e.consume();
                    continue;
                }
                if (lobbyOpen && open && ClientLink.get().getState() == ClientLink.State.CONNECTED) { // the lobby takes every key while it is up
                    if (e.isKeyDownEvent()) lobbyKey(e.getEventValue(), e.getEventChar(), e.isCtrlDown(), e.isShiftDown());
                    e.consume();
                    continue;
                }
                if (!open || !e.isKeyDownEvent()) continue;
                if (e.getEventValue() == 62 && ClientLink.get().getState() == ClientLink.State.CONNECTED) { lobbyOpen = true; e.consume(); continue; } // F4: the lobby
                if (e.getEventValue() == 1 && autoJoinAt != 0) {                      // Escape during the countdown: not this battle
                    autoJoinAt = 0;
                    autoJoinSkip = true;
                    form.status = "Not joining this battle by itself. Enter joins it after all.";
                    form.statusColor = Panel.WARN;
                    e.consume();
                }
                else if (e.getEventValue() == 1) { open = false; e.consume(); }       // Escape closes the panel
                else if (e.getEventValue() == 59) { helpPage = 0; e.consume(); }        // F1: how to use
                else if (e.getEventValue() == 61) { settings.open(null); e.consume(); } // F3: settings
                else if (e.getEventValue() == 60) {                                     // F2: compatibility report
                    if (cfg == null) cfg = WingmanConfig.load();
                    report = Compat.get().report(cfg.hostDecidesScriptedShots);
                    reportPage = 0;
                    Global.getLogger(WingmanTitlePlugin.class).info("Wingman compatibility report:");
                    for (String line : report) if (!line.isEmpty()) Global.getLogger(WingmanTitlePlugin.class).info("Wingman   " + line);
                    e.consume();
                }
                else if (e.getEventValue() == 63 && !SystemWatch.get().blockedIds().isEmpty()) { // F5: switch crashed-out systems back on
                    int n = SystemWatch.get().blockedIds().size();
                    SystemWatch.get().reset();
                    form.status = n + " ship system" + (n == 1 ? " is" : "s are") + " mirrored again. If the game crashes on one, it will be switched off again.";
                    form.statusColor = Panel.GOOD;
                    e.consume();
                }
            }
            if (settings.open) {
                try {
                    settings.keys(events);
                } catch (Throwable t) { // close the page, keep the CO-OP button
                    settings.open = false;
                    Global.getLogger(WingmanTitlePlugin.class).error("Wingman: the settings page failed and was closed", t);
                }
                settings.changed.clear();
                if (!settings.open) cfg = WingmanConfig.load(); // closed: the panel shows the new state of things
                return;
            }
            if (!open || helpPage >= 0 || reportPage >= 0 || (lobbyOpen && ClientLink.get().getState() == ClientLink.State.CONNECTED)) return;
            ClientLink link = ClientLink.get();
            if (!form.keys(events, link.getState() == ClientLink.State.CONNECTING, true)) return;
            String typed = form.address.toString().trim();
            if (typed.isEmpty()) { // no address: Enter just saves the name, which is also the name you host under
                form.save();
                cfg = WingmanConfig.load();
                form.status = "Name saved: you host and join as " + form.name() + ".";
                form.statusColor = Panel.GOOD;
                return;
            }
            if (link.getState() == ClientLink.State.CONNECTED && typed.equals(connectedRaw) && !welcomed) {
                // Connected, but the host has not let us in yet (its game may be busy, or slow in the background).
                form.status = "Waiting for the host to let you in... Your battle starts as soon as it does.";
                form.statusColor = Panel.WARN;
                return;
            }
            // Still connected to the same host, but under another name now: connect again, so the host hears it
            // (the name is only told on connecting; before, the old one stuck until the host started afresh).
            boolean renamed = link.getState() == ClientLink.State.CONNECTED && typed.equals(connectedRaw) && connectedName != null
                    && !connectedName.equals(form.name().trim());
            if (link.getState() == ClientLink.State.CONNECTED && typed.equals(connectedRaw) && !renamed) {
                form.save(); // the name may have been edited
                autoJoinAt = 0;
                leftOnPurpose = false;
                join();
                return;
            }
            connectedRaw = typed;
            welcomed = false;
            launchFailed = false;
            form.save();
            cfg = WingmanConfig.load(); // now carries what was just typed
            connectedTo = cfg.hostAddress + (cfg.port == 24816 ? "" : ":" + cfg.port);
            hostLine = "";
            form.status = "";
            lastState = ClientLink.State.IDLE;
            JoinSetup.rejectReason = null;
            link.connect(cfg.hostAddress, cfg.port, cfg.playerName, Mods.enabled(), Mods.gameVersion(), cfg.joinPassword);
            connectedName = cfg.playerName.trim();
            Global.getLogger(WingmanTitlePlugin.class).info("Wingman title screen: connecting to the host as " + cfg.playerName);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Keys on the lobby page: Up/Down choose, F (or Enter on the map) pick, Delete no pick, Enter join, Esc back. */
    private void lobbyKey(int key, char ch, boolean ctrl, boolean shift) {
        int n = roster == null ? 0 : roster.members.size();
        int page = TitleMenu.isHidden() ? lobby.rows : LOBBY_ROWS;
        if (lobby.page == LobbyScreen.REFIT) {                                         // the refit screen takes every key
            refitAction(lobby.refit.key(key, ch, ctrl, shift));
            return;
        }
        if (key == 19) { openRefit(); return; }                                         // R: refit the ship we picked
        if (key == 1) { lobbyOpen = false; return; }                                   // Esc: back to the panel
        if (key == 59) { helpPage = 0; return; }                                       // F1
        if (key == 15) { lobby.page = lobby.page == LobbyScreen.FLEET ? LobbyScreen.REPORT : LobbyScreen.FLEET; return; } // Tab
        if (n > 0 && key == 200) lobbyAt = (lobbyAt + n - 1) % n;
        else if (n > 0 && key == 208) lobbyAt = (lobbyAt + 1) % n;
        else if (key == 201) lobbyAt = Math.max(0, lobbyAt - page);                   // Page Up
        else if (key == 209) lobbyAt = Math.min(Math.max(0, n - 1), lobbyAt + page);  // Page Down
        else if (key == 211 && roster != null) sendPick("");                           // Delete: no pick
        else if (Panel.isEnter(key) && ((roster != null && roster.hostState == 1) || !hostLine.isEmpty() || autoJoinAt != 0)) { // the host is in a battle: join it
            autoJoinAt = 0;
            leftOnPurpose = false;
            join();
        }
        else if ((key == 33 || Panel.isEnter(key)) && n > 0) {                          // F (or Enter): pick this ship
            wingman.net.Roster.Member m = roster.members.get(Math.max(0, Math.min(n - 1, lobbyAt)));
            if ((m.flags & wingman.net.Roster.FLAGSHIP) != 0) note("That is the host's flagship.", Panel.WARN);
            else if ((m.flags & wingman.net.Roster.MOTHBALLED) != 0) note("That ship is mothballed.", Panel.WARN);
            else if ((m.flags & wingman.net.Roster.CANT_FLY) != 0) note("Can't pick that ship: " + m.note + ".", Panel.WARN);
            else if (!m.pickedBy.isEmpty() && !m.pickedBy.equalsIgnoreCase(HostSession.cleanName(cfg == null ? "" : cfg.playerName))) note(m.pickedBy + " has picked that one.", Panel.WARN);
            else sendPick(m.id);
        }
    }

    private void note(String text, java.awt.Color color) { lobbyNote = text; lobbyNoteColor = color; }

    /** Friend refit: open the refit screen on the ship we picked, and ask the host for what it shows. */
    private void openRefit() {
        String me = HostSession.cleanName(cfg == null ? "" : cfg.playerName);
        wingman.net.Roster.Member mine = roster == null ? null : roster.pickOf(me);
        if (roster == null || roster.refitMarket.isEmpty()) { note("Refitting is possible while the host is docked at a market.", Panel.WARN); return; }
        if (mine == null) { note("Pick a ship first: you can refit the ship you picked.", Panel.WARN); return; }
        if ((mine.flags & (wingman.net.Roster.FLAGSHIP | wingman.net.Roster.MOTHBALLED)) != 0) { note("That ship can't be refitted.", Panel.WARN); return; }
        if (lobby.isBroken() || !TitleMenu.hide()) { note("The refit screen needs the full-screen lobby, which can't be shown here.", Panel.BAD); return; }
        Conn c = ClientLink.get().getConn();
        if (c == null || ClientLink.get().getState() != ClientLink.State.CONNECTED) { note("Not connected.", Panel.BAD); return; }
        c.send(Msg.REFIT_ASK, Msg.utf(mine.id));
        refitMember = mine.id;
        refitData = null;
        lobby.page = LobbyScreen.REFIT;
        note("", Panel.DIM);
    }

    /** What the refit screen asked for: close it, or send the refit to the host. */
    private void refitAction(int action) {
        if (action == RefitScreen.CLOSE) closeRefit(null);
        else if (action == RefitScreen.SEND) {
            Conn c = ClientLink.get().getConn();
            if (c == null || ClientLink.get().getState() != ClientLink.State.CONNECTED) { note("Not connected.", Panel.BAD); return; }
            c.send(Msg.REFIT_REQUEST, lobby.refit.request().encode());
        }
    }

    /** Close the refit screen (telling the host), with a note why if it wasn't the player's doing. */
    private void closeRefit(String why) {
        if (refitMember.isEmpty()) return;
        refitMember = "";
        refitData = null;
        if (lobby.page == LobbyScreen.REFIT) lobby.page = LobbyScreen.FLEET;
        Conn c = ClientLink.get().getConn();
        if (c != null && ClientLink.get().getState() == ClientLink.State.CONNECTED) c.send(Msg.REFIT_ASK, Msg.utf(""));
        if (why != null) note(why, Panel.WARN);
    }

    private void sendPick(String memberId) {
        Conn c = ClientLink.get().getConn();
        if (c == null || ClientLink.get().getState() != ClientLink.State.CONNECTED) { note("Not connected.", Panel.BAD); return; }
        c.send(Msg.PICK, Msg.utf(memberId));
        note(memberId.isEmpty() ? "No ship picked: you will choose one in the battle." : "Asked the host for it...", Panel.DIM);
    }

    private static String size(String hullSize) {
        return "CAPITAL_SHIP".equals(hullSize) ? "capital" : hullSize.toLowerCase();
    }

    /** What the lobby screen shows this frame. */
    private LobbyScreen.View lobbyView() {
        LobbyScreen.View v = new LobbyScreen.View();
        v.roster = roster;
        v.me = HostSession.cleanName(cfg == null ? "" : cfg.playerName);
        v.at = lobbyAt;
        if (roster != null) lobbyAt = Math.max(0, Math.min(Math.max(0, roster.members.size() - 1), lobbyAt));
        v.hostInBattle = (roster != null && roster.hostState == 1) || !hostLine.isEmpty();
        String host = roster != null ? roster.hostName : "The host";
        if (autoJoinAt != 0) {
            long left = Math.max(1, (autoJoinAt - System.currentTimeMillis() + 999) / 1000);
            v.hostLine = host + "'s battle has begun. Joining it in " + left + "...   (Esc closes the lobby; Esc again: not this battle.)";
            v.hostColor = Panel.GOOD;
        } else if (v.hostInBattle) {
            v.hostLine = host + " is in a battle. Enter joins it.";
            v.hostColor = Panel.GOOD;
        } else if (roster != null) {
            v.hostLine = host + " is on the campaign map. You go into their next battle when it starts" + (cfg != null && cfg.autoJoin ? "." : " (Enter, once it has).");
        } else {
            v.hostLine = "Connected. The host is not in a battle yet.";
        }
        v.players = withHost.isEmpty() ? "" : "In this game: " + withHost;
        v.note = lobbyNote;
        v.noteColor = lobbyNoteColor;
        v.refitMarket = roster == null ? "" : roster.refitMarket;
        v.refitOpen = !refitMember.isEmpty();
        v.refitData = refitData;
        return v;
    }

    /** The lobby page: who is there, the host's fleet, and our pick. */
    private void renderLobby(List<String> lines, List<java.awt.Color> colors) {
        String me = HostSession.cleanName(cfg == null ? "" : cfg.playerName);
        lines.add(roster.hostName + " (host) is " + (roster.hostState == 1 ? "in a battle. Enter joins it." : "on the campaign map. You go into their next battle when it starts."));
        colors.add(roster.hostState == 1 ? Panel.GOOD : Panel.TEXT);
        if (autoJoinAt != 0) {
            long left = Math.max(1, (autoJoinAt - System.currentTimeMillis() + 999) / 1000);
            lines.add("Joining the host's battle in " + left + "...   Esc: back (then Esc again: not this one).");
            colors.add(Panel.GOOD);
        }
        StringBuilder who = new StringBuilder("Here:  P1 " + roster.hostName);
        for (wingman.net.Roster.Player rp : roster.players) who.append(",  P").append(rp.slot).append(' ').append(rp.name);
        lines.add(who.toString());
        colors.add(Panel.DIM);
        wingman.net.Roster.Member mine = roster.pickOf(me);
        lines.add(mine == null ? "You have not picked a ship. Pick one below, or choose in the battle."
                : "Your ship for the next battle:  " + wingman.net.Roster.label(mine) + "  (if the host deploys it)");
        colors.add(mine == null ? Panel.WARN : Panel.GOOD);
        int n = roster.members.size();
        if (n == 0) {
            lines.add("The host's fleet isn't available (the host is not in a campaign).");
            colors.add(Panel.DIM);
        }
        lobbyAt = Math.max(0, Math.min(Math.max(0, n - 1), lobbyAt));
        int from = Math.max(0, Math.min(lobbyAt - LOBBY_ROWS / 2, n - LOBBY_ROWS)), to = Math.min(n, from + LOBBY_ROWS);
        for (int i = from; i < to; i++) {
            wingman.net.Roster.Member m = roster.members.get(i);
            String tag = (m.flags & wingman.net.Roster.FLAGSHIP) != 0 ? "host's flagship"
                    : (m.flags & wingman.net.Roster.MOTHBALLED) != 0 ? "mothballed"
                    : (m.flags & wingman.net.Roster.CANT_FLY) != 0 ? "can't be flown"
                    : m.pickedBy.equalsIgnoreCase(me) ? "YOURS" : m.pickedBy;
            lines.add((i == lobbyAt ? ">  " : "    ") + wingman.net.Roster.label(m) + "   " + size(m.hullSize)
                    + (m.cr >= 0 ? ",  CR " + m.cr + "%" : "") + (tag.isEmpty() ? "" : "   [" + tag + "]"));
            boolean blocked = (m.flags & (wingman.net.Roster.FLAGSHIP | wingman.net.Roster.MOTHBALLED | wingman.net.Roster.CANT_FLY)) != 0
                    || (!m.pickedBy.isEmpty() && !m.pickedBy.equalsIgnoreCase(me));
            colors.add(m.pickedBy.equalsIgnoreCase(me) ? Panel.GOOD : blocked ? Panel.DIM : Panel.TEXT);
        }
        if (n > LOBBY_ROWS) { lines.add("    ..." + (lobbyAt + 1) + " of " + n + " ships"); colors.add(Panel.DIM); }
        if (n > 0) { // the marked ship in more detail
            wingman.net.Roster.Member m = roster.members.get(lobbyAt);
            String detail = (m.designation.isEmpty() ? "" : m.designation + ".  ")
                    + (m.officer.isEmpty() ? "No officer." : "Officer: " + m.officer + (m.officerLevel > 0 ? ", level " + m.officerLevel : "") + ".")
                    + ((m.flags & wingman.net.Roster.CANT_FLY) != 0 && !m.note.isEmpty() ? "  Can't be flown: " + m.note + "." : "");
            lines.add(detail);
            colors.add(Panel.DIM);
        }
        if (!lobbyNote.isEmpty()) { lines.add(lobbyNote); colors.add(lobbyNoteColor); }
    }

    /**
     * Start the join mission from here, as the Missions screen's Play button would. This reaches into the
     * game's internals (see TitleLauncher); if that fails for any reason the player is told to start the
     * mission from the Missions screen, which always works.
     */
    private void join() {
        String problem;
        try {
            problem = TitleLauncher.start("wingman_join");
        } catch (Throwable t) { // includes "no such method": the game's internals differ from the version this was built for
            problem = String.valueOf(t);
            Global.getLogger(WingmanTitlePlugin.class).warn("Wingman title screen: could not start the join mission from here", t);
        }
        if (problem == null) {
            open = false;
            Global.getLogger(WingmanTitlePlugin.class).info("Wingman title screen: starting the join mission");
            return;
        }
        launchFailed = true;
        form.status = "Could not start the battle from here (" + problem + "). Open Missions and start Wingman: Join Game instead.";
        form.statusColor = Panel.WARN;
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (broken || !onTitle()) return;
        try {
            ClientLink.State state = ClientLink.get().getState();
            boolean over = overButton();
            boolean down = Mouse.isButtonDown(0);
            if (down && !wasDown && over && helpPage < 0 && reportPage < 0 && !settings.open) {
                open = !open;
                if (open) {
                    cfg = WingmanConfig.load();
                    form.load(cfg.playerName);
                    if (myAddresses == null) myAddresses = WingmanHostPlugin.myAddresses();
                    // Still connected from before (back from a battle): Enter on the same address joins again.
                    if (connectedRaw == null && state == ClientLink.State.CONNECTED) {
                        connectedRaw = form.address.toString().trim();
                        connectedTo = connectedRaw;
                        welcomed = true;
                        form.status = "Still connected to " + connectedTo + ".";
                        form.statusColor = Panel.GOOD;
                    }
                    try { Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f); } catch (Throwable t) { /* silent is fine */ }
                }
            }
            wasDown = down;
            String sub = state == ClientLink.State.CONNECTED ? "connected" : state == ClientLink.State.CONNECTING ? "connecting..." : "join or host";
            panel.button(bx(), by(), W, H, "CO-OP", sub, over || open);

            // The lobby as a screen of its own, with the game's menu moved aside (other pages draw over it).
            boolean full = open && lobbyOpen && state == ClientLink.State.CONNECTED && !lobby.isBroken() && TitleMenu.hide();
            if (!full) TitleMenu.show();
            if (full) {
                float[] b = lobby.refitButton; // drawn last frame: a click on it opens the refit screen
                if (down && !wasClickDown && b != null && lobby.page == LobbyScreen.FLEET && LobbyScreen.inside(b[0], b[1], b[2], b[3])) openRefit();
                else if (down && !wasClickDown && lobby.page == LobbyScreen.REFIT) {
                    refitAction(lobby.refit.click(Global.getSettings().getMouseX(), Global.getSettings().getMouseY()));
                }
                lobby.render(lobbyView());
            }
            wasClickDown = down;

            List<String> lines = new ArrayList<String>();
            List<Color> colors = new ArrayList<Color>();
            if (reportPage >= 0 && report != null) {
                int pages = Math.max(1, (report.size() + REPORT_LINES - 1) / REPORT_LINES);
                int page = Math.min(reportPage, pages - 1);
                if (page == 0) {
                    lines.add("Scripted content from your mods. Unless marked \"each PC's own\", its timing comes from the host.");
                    colors.add(Panel.DIM);
                }
                for (int i = page * REPORT_LINES; i < report.size() && i < (page + 1) * REPORT_LINES; i++) {
                    String line = report.get(i);
                    if (line.isEmpty()) continue;
                    lines.add(line);
                    colors.add(line.startsWith("Worth a look") ? Panel.WARN : line.startsWith("Seen in play") || line.startsWith("Nothing seen") ? Panel.GOOD : Panel.TEXT);
                }
                panel.renderBelow(bx() + W, by() - 6f, boxWidth(), "MOD COMPATIBILITY   (" + (page + 1) + "/" + pages + ")", lines, colors,
                        (page + 1 >= pages ? "Enter: done" : "Enter: next") + (page > 0 ? "     Backspace: back" : "")
                        + "     Delete: forget what was seen     Esc: close");
                return;
            }
            if (settings.open) {
                try {
                    settings.lines(lines, colors);
                    panel.renderBelow(bx() + W, by() - 6f, boxWidth(), settings.title(), lines, colors, settings.footer());
                } catch (Throwable t) { // close the page, keep the CO-OP button
                    settings.open = false;
                    Global.getLogger(WingmanTitlePlugin.class).error("Wingman: the settings page could not be shown and was closed", t);
                }
                return;
            }
            if (helpPage >= 0) {
                String[] page = HELP[Math.min(helpPage, HELP.length - 1)];
                for (int i = 0; i < page.length; i++) {
                    lines.add(page[i]);
                    colors.add(helpPage == HELP.length - 1 || (helpPage == 0 && i == 2) ? Panel.WARN : Panel.TEXT);
                }
                boolean last = helpPage == HELP.length - 1;
                panel.renderBelow(bx() + W, by() - 6f, boxWidth(), HELP_TITLES[Math.min(helpPage, HELP.length - 1)] + "   (" + (helpPage + 1) + "/" + HELP.length + ")", lines, colors,
                        (last ? "Enter: done" : "Enter: next") + (helpPage > 0 ? "     Backspace: back" : "") + "     Esc: close");
                return;
            }
            if (!open) return;
            if (full) return;
            if (lobbyOpen && roster != null && state == ClientLink.State.CONNECTED) {
                renderLobby(lines, colors);
                panel.renderBelow(bx() + W, by() - 6f, boxWidth(), "LOBBY:  " + roster.hostName.toUpperCase() + "'S FLEET", lines, colors,
                        "Up/Down: choose     F: pick this ship     Delete: no pick     "
                        + (roster.hostState == 1 ? "Enter: join the battle" : "Enter: pick") + "     Esc: back");
                return;
            }

            form.lines(lines, colors, state == ClientLink.State.CONNECTING);
            if (!hostLine.isEmpty() && state == ClientLink.State.CONNECTED) { lines.add(hostLine); colors.add(Panel.GOOD); }
            boolean canJoin = state == ClientLink.State.CONNECTED && welcomed && form.address.toString().trim().equals(connectedRaw);
            boolean auto = cfg != null && cfg.autoJoin;
            if (autoJoinAt != 0) {
                long left = Math.max(1, (autoJoinAt - System.currentTimeMillis() + 999) / 1000);
                lines.add("Joining the host's battle in " + left + "...   Esc: not this one.   Enter: now.");
                colors.add(Panel.GOOD);
            } else if (canJoin && !launchFailed) {
                lines.add(hostLine.isEmpty() ? (auto ? "The host is not in a battle yet. You will be taken into the next one when it begins."
                                : "The host is not in a battle yet. Wait for them to start one, then press Enter to join it.")
                        : "Press Enter to join the battle.");
                colors.add(hostLine.isEmpty() ? Panel.WARN : Panel.TEXT);
            }
            if (!withHost.isEmpty() && state == ClientLink.State.CONNECTED) { lines.add("In this game: " + withHost); colors.add(Panel.DIM); }
            if (roster != null && state == ClientLink.State.CONNECTED) {
                wingman.net.Roster.Member mine = roster.pickOf(HostSession.cleanName(cfg == null ? "" : cfg.playerName));
                lines.add("F4: the lobby (the host's fleet)" + (mine == null ? ", to pick your ship before the battle." : ". Your pick: " + wingman.net.Roster.label(mine) + "."));
                colors.add(Panel.GOOD);
            }
            lines.add("To join:  type the host's address, Enter to connect, Enter again to join their battle.");
            colors.add(Panel.DIM);
            lines.add("To host:  just start any battle. Friends type "
                    + (myAddresses == null || myAddresses.isEmpty() ? "this PC's address" : "one of:  " + myAddresses) + ".");
            colors.add(Panel.DIM);
            if (cfg != null && cfg.hostEnabled && cfg.password.isEmpty()) {
                lines.add("Hosting without a password: anyone who can reach this PC can join. F3 to set a password.");
                colors.add(Panel.WARN);
            }
            List<String> off = SystemWatch.get().blockedIds();
            if (!off.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < off.size() && i < 6; i++) names.append(i == 0 ? "" : ", ").append(off.get(i));
                if (off.size() > 6) names.append(", and ").append(off.size() - 6).append(" more");
                lines.add("Switched off on this PC after a possible crash, so not shown when the host uses them: " + names
                        + ".  F5: switch them back on.");
                colors.add(Panel.WARN);
            }
            String version = Mods.ownVersion();
            panel.renderBelow(bx() + W, by() - 6f, boxWidth(), version.isEmpty() ? "CO-OP" : "CO-OP   " + version, lines, colors, (canJoin ? "Enter: join the battle" : form.address.length() == 0 ? "Enter: save name" : "Enter: connect")
                    + "     Tab: next field     Ctrl+V: paste     F1: how to use     F2: mod compatibility     F3: settings     Esc: close");
        } catch (Throwable t) {
            fail(t);
        }
    }
}
