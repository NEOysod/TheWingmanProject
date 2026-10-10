package wingman;

import java.awt.Color;
import wingman.net.In;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;

import wingman.net.ClientLink;
import wingman.net.Conn;
import wingman.net.DamageState;
import wingman.net.InputState;
import wingman.net.Lobby;
import wingman.net.Msg;
import wingman.net.ShipSpawn;
import wingman.net.Snapshot;

/**
 * Added only by the "Wingman: Join Game" mission. The local battle is an empty stage: every ship, fighter and
 * missile on it is a puppet rebuilt from the host's description and moved to wherever the host says it is.
 * Anything the local game creates by itself (fighters from carriers, missiles from launchers) is removed,
 * so what you see is only what the host has.
 */
public class WingmanClientPlugin extends BaseEveryFrameCombatPlugin {
    private static final String NO_DAMAGE = "wingman_puppet";
    private static final float HULL_TRICKLE = 0.0001f;
    private static final float INPUT_RATE = 60f, INPUT_FLOOR = 15f;
    private byte[] lastControls;
    private long lastInputSentNanos, lastAsteroidSweep;
    private int inputsSent;
    private final Prof prof = new Prof("messages", "strays", "ships", "missiles", "wrecks", "hits", "rescue+damage", "orders+controls");

    private static final class Puppet {
        int netId;
        int owner;
        boolean fighter;
        ShipAPI ship;
        WeaponAPI[] weapons = new WeaponAPI[0]; // indexed by the host's weapon order
        Snapshot.ShipState target;
        float targetAt;          // battle time when that state arrived
        byte[] angles;           // each weapon's angle relative to the hull, as last sent by the host
        float systemOff, cloakOff, phaseOff;
        float goneAt;            // when the host said it was gone (kept a little longer as the launcher of its last missiles) // how long our copy's systems have been in a different stage from the host's
        int transferState = -1; // -1 unknown, 0 allowed, 1 blocked
        DamageState.Ship damage; // latest armor / EMP state from the host
        ShipSpawn info;          // how to build this ship again if the local game loses it
        HullGuard guard;
        int rebuilds;
        boolean module;         // part of a multi-part ship: it goes where its ship goes
        boolean drone;          // a drone some ship's system launched
        boolean statsDue;       // new figures have arrived and are yet to be applied
        float[] hostStats;      // its stats as they stand on the host (NaN: not told yet); see ShipStats
        int statTurn;
        int[] ahead;            // per weapon: shots fired here minus shots fired on the host
        boolean[] counted;      // per weapon: the host has sent a shot count for it at least once
        float[] lastCooldown, lastShotAt, aheadSince;
        boolean jumped;         // the host's ship has just moved in a way no engine could (a teleport): go straight there
        boolean blinking;       // the host's teleporter system on this ship is running (its look is drawn here: see teleportLook)
        float blinkSeen = -100f; // when it last was
        boolean probing;        // diagnostics: leave this puppet completely alone for a frame
    }

    private static final class MissilePuppet {
        MissileAPI missile;
        Snapshot.MissileState target;
        float targetAt;
        boolean flamedOut;
        boolean scripted;        // its own script runs here, for the show it puts on
        boolean tended;          // its launcher has a mod's script that looks after its shots (a fuse, a special ending)
        float hostMaxFlight;     // how long the host's copy can fly: a fuse script reads this
        float lifeFrac;          // how far through its flight it was when last seen
        PendingMissile launch;   // the host's description of the launch
        boolean builtHere;       // a plain copy built from that description, not a missile this game made itself
        float builtAt;
        CollisionClass collision; // what it could hit before we switched its collisions off (see takeOver)
        float hitX, hitY, hitBy;  // a hit the host reported, being played out: where, and the deadline for it
        boolean hitShield;
        ShipAPI hitTarget;
        float goneFor;           // how long our copy has been gone or burnt out while the host's flies on
        int revived;
        boolean endWatched;      // the host's script ended it: we are watching whether ours does the same
        float releaseBy = -1f;   // the host's copy has released its next stage: ours gets this long to do the same
        int wantTarget = -2;     // ship the host says it is aimed at, still to be passed to its script (-2: nothing to pass)
    }

    private CombatEngineAPI engine;
    private boolean ready, disabled;
    private WingmanConfig cfg;
    private final Settings.Menu settings = new Settings.Menu();
    private final PlayerTags tags = new PlayerTags();
    private ClientLink link;
    private ClientLink.State lastState = ClientLink.State.IDLE;
    private ShipAPI placeholder;
    private final Map<Integer, Puppet> puppets = new HashMap<Integer, Puppet>();
    private final Map<ShipAPI, Puppet> byShip = new IdentityHashMap<ShipAPI, Puppet>();
    private final Map<Integer, MissilePuppet> missiles = new HashMap<Integer, MissilePuppet>();
    private final Map<MissileAPI, MissilePuppet> byMissile = new IdentityHashMap<MissileAPI, MissilePuppet>();
    /** The host's fighter wings, by the host's number for each, so fighters are grouped here as they are there. */
    private final Map<Integer, FighterWingAPI> hostWings = new HashMap<Integer, FighterWingAPI>();
    private boolean wingsBroken;
    /** The join screen: shown instead of connecting blindly when there is no live connection to a host. */
    private boolean joinScreen;
    private final JoinForm form = new JoinForm();
    /** Script-run missiles whose host copy has released its next stage; see MISSILE_DESPAWN. */
    private final List<MissilePuppet> releasing = new ArrayList<MissilePuppet>();
    private int straysRemoved, missilesUnbuilt;
    /** Battle time: stands still while paused and runs fast or slow with the battle, like the host's. */
    private float clock;
    private int ownId = -1;
    private int claimSeq;
    private ShipAPI lastPlayerShip;
    private int hostFlagship = -1;
    private long lastInputNanos, lastSnapshotAt;
    private boolean stallWarned, welcomed, hintShown;
    private final InputState input = new InputState();
    private final List<String> missing = new ArrayList<String>();

    // Ready check and pop-ups
    private Lobby lobby;
    private boolean iAmReady, enterPressed, rejected;
    private int skippedShips;
    /** Host ships and fighters that could not be built here (missing content, or over the cap): id -> is a fighter. */
    private final Map<Integer, Boolean> skippedIds = new HashMap<Integer, Boolean>();
    /** Hulls a mod's script failed to build here (logged once each). */
    private final java.util.Set<String> spawnErrors = new java.util.HashSet<String>();
    private final SystemWatch watch = SystemWatch.get();
    private boolean halted;
    private final List<MissilePuppet> recentBuilt = new ArrayList<MissilePuppet>();
    private boolean leaving, everUpdated, stallAlerted, helmHintsShown, watchNoticeShown;
    private long leaveAtMillis, retreatNowUntil, lastResyncAt;
    private boolean retreatOffered;
    /** What Enter does on the alert that is showing: 0 dismiss it, 1 leave the battle, 2 leave and rejoin. */
    private int alertAction;
    private int checkMisses, wreckMisses, resyncs, reboardId = -1, capDrops;
    private boolean wreckResyncUsed;
    /** Getting back in after the link to the host dropped: see step(). */
    private boolean reconnecting;
    private int reconnectTries, rejoinShip = -1;
    private long nextReconnectAt;
    private String rejoinHull = "";
    private static final int RECONNECT_TRIES = 10;
    private static final int MAX_MISSILES = 2000, MAX_FIGHTERS = 800;
    private int sentBuilt = -1, sentSkipped = -1;
    private boolean sentReady;
    private long lastReadyMillis;
    private final Panel panel = new Panel();
    private final ClientEnvironment environment = new ClientEnvironment();
    private final ClientOrders orders = new ClientOrders(new ClientOrders.Ships() {
        public List<Integer> friendlyShips() {
            List<Integer> out = new ArrayList<Integer>();
            for (Puppet pp : puppets.values()) if (pp.owner == 0 && !pp.fighter && !pp.module && !pp.drone) out.add(pp.netId);
            return out;
        }
        public ShipAPI ship(int netId) { Puppet pp = puppets.get(netId); return pp == null ? null : pp.ship; }
        public Integer id(ShipAPI ship) { Puppet pp = ship == null ? null : byShip.get(ship); return pp == null ? null : pp.netId; }
        public int side(int netId) { Puppet pp = puppets.get(netId); return pp == null ? 0 : pp.owner; }
        public boolean isOwn(int netId) { return netId == ownId; }
    });

    // Targeting
    private static final int KEY_R = 19;
    private int targetKeyFrames;
    private Puppet forcedTarget;
    private boolean fallbackLogged, reapplyLogged, sampleLogged;
    private ShipAPI lastLoggedTarget;
    private long lastDebugMillis;
    private long lastDamageNanos;
    private int messageErrors;
    private long lastRescueMillis;
    private static final String TIME_KEY = "wingman_host_time";
    private float hostTimeMult = 1f;
    private float loggedTimeMult = 1f;
    private boolean localTimeNoted;
    private boolean wasFiring;
    private long lastFireLog;
    private boolean autopilot;
    private Puppet autopilotShip;
    private boolean damageBroken;
    private String alertTitle;
    private final List<String> alertLines = new ArrayList<String>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        engine.getCustomData().put(WingmanHostPlugin.CLIENT_FLAG, Boolean.TRUE);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (disabled || leaving || halted || engine == null) return;
        try {
            if (!ready) {
                if (!prepare()) return;
            }
            step(amount);
        } catch (Throwable t) {
            Global.getLogger(WingmanClientPlugin.class).error("Wingman client error, co-op stopped", t);
            Ui.say(engine, "Co-op: client error, stopped (see starsector.log): " + t);
            if (ready) {
                // Stop following the host, but stay able to show why and to offer the way out. The connection
                // is untouched, so the battle can simply be joined again.
                halted = true;
                try {
                    alertWith("CO-OP STOPPED", "Something went wrong on this PC and the co-op view of this battle has stopped.\n"
                            + "You are still connected. Leave and join again to carry on. (Details are in starsector.log.)", 1);
                } catch (Throwable ignored) { disabled = true; }
            } else {
                disabled = true;
            }
        }
    }

    private MessageSpy spy;
    private long spyCheckedAt;

    /**
     * Follow the battle's message feed (see MessageTap): while the host's lines are coming, this PC's own
     * scripts' lines are held back, since the host's copies of the same scripts write them for everyone.
     */
    private void tapMessages() {
        spyCheckedAt = System.currentTimeMillis();
        MessageSpy s = MessageTap.install(engine);
        if (s == null || s == spy) return;
        spy = s;
        s.sink = new MessageSpy.Sink() {
            public boolean line(int indent, Object[] parts) {
                return !(welcomed && cfg != null && cfg.hostMessages && link != null && link.getState() == ClientLink.State.CONNECTED);
            }
        };
    }

    private int badLines, landingFailed;

    /** A script's line from the host's game: shown here as written there. */
    private void showHostLine(byte[] data) {
        if (cfg == null || !cfg.hostMessages) return;
        if (System.currentTimeMillis() - spyCheckedAt > 3000) tapMessages();
        try {
            Object[] d = MessageTap.decode(data, new MessageTap.LocalNames() {
                public ShipAPI ship(int hostId) {
                    Puppet pp = puppets.get(hostId);
                    return pp == null ? null : pp.ship;
                }
                public com.fs.starfarer.api.combat.BattleObjectiveAPI objective(int i) { return orders.placedAt(i); }
            });
            if (d == null) { if (badLines++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: a malformed message line from the host was skipped"); return; }
            int indent = (Integer) d[0];
            Object[] parts = new Object[d.length - 1];
            System.arraycopy(d, 1, parts, 0, parts.length);
            if (spy != null) spy.showHostLine(indent, parts);
            else if (engine.getCombatUI() != null) engine.getCombatUI().addMessage(indent, parts);
        } catch (Throwable t) {
            if (badLines++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: a message line from the host could not be shown", t);
        }
    }

    /** Wait until our stand-in ship is on the field, clear the stage, then connect. */
    private boolean prepare() {
        ShipAPI ps = engine.getPlayerShip();
        if (ps == null || ps.isShuttlePod() || engine.getTotalElapsedTime(false) < 0.5f) return false;
        placeholder = ps;
        lastPlayerShip = ps;
        engine.setDoNotEndCombat(true);
        for (ShipAPI s : new ArrayList<ShipAPI>(engine.getShips())) {
            if (s != placeholder && s.getOwner() != 0) engine.removeEntity(s);
        }
        placeholder.setCollisionClass(CollisionClass.NONE);
        makeInvulnerable(placeholder);
        try { placeholder.addListener(new HullGuard()); } catch (Throwable t) { /* damage cut still applies */ }
        ObjectiveMirror.restore(); // the objectives are made: held-back kinds get their own scripts back for later battles
        tapMessages();
        orders.start(engine);
        environment.start(engine);

        cfg = WingmanConfig.load();
        wingman.net.Conn.compression = cfg.compressLink;
        wingman.net.Conn.udp = cfg.useUdp;
        slack = cfg.syncTolerance;
        link = ClientLink.get();
        if (link.getState() == ClientLink.State.CONNECTED) {
            Conn c = link.getConn();
            while (c.poll() != null) { /* discard leftovers from the previous battle */ }
            c.send(Msg.SYNC_REQ, Msg.EMPTY);
            claim(c, -1);
            welcomed = true;
            Ui.say(engine, "Co-op: still connected, asking the host for the current battle...");
        } else {
            // No host yet: ask for the address on screen. The last one used is already filled in.
            SavedJoin saved = SavedJoin.load();
            form.load(cfg.playerName);
            String why = JoinSetup.rejectReason != null ? "The host refused the connection: " + JoinSetup.rejectReason.replace('\n', ' ')
                    : saved.address.isEmpty() ? "" : link.getError();
            openJoinScreen(why == null ? "" : why, Panel.WARN);
        }
        lastSnapshotAt = System.currentTimeMillis();
        ready = true;
        return true;
    }

    private void step(float amount) throws Exception {
        clock += amount;
        ClientLink.State state = link.getState();
        if (state != lastState) {
            if (state == ClientLink.State.CONNECTED && joinScreen) {
                joinScreen = false;
                Ui.say(engine, "Co-op: connected to the host.");
            }
            if (state == ClientLink.State.FAILED && !welcomed && !rejected) {
                // Could not get through: back to the join screen with the reason, to correct the address and retry.
                String why = JoinSetup.rejectReason != null ? "The host refused the connection: " + JoinSetup.rejectReason.replace('\n', ' ')
                        : form.address.length() == 0 ? "" : link.getError();
                openJoinScreen(why, Panel.BAD);
            } else if (state == ClientLink.State.FAILED && welcomed && !rejected) {
                // We were in a game and the link dropped. Hold everything where it is and try to get back in,
                // every few seconds for about half a minute. The battle on screen stays as it was.
                if (!reconnecting) {
                    reconnecting = true;
                    reconnectTries = 0;
                    Puppet mine = puppets.get(ownId);
                    if (mine != null && mine.info != null && engine.getPlayerShip() == mine.ship) {
                        rejoinShip = ownId;
                        rejoinHull = mine.info.hullId;
                    }
                    engine.setPaused(true);
                    lobby = null;
                    Ui.say(engine, "Co-op: connection to the host lost (" + link.getError() + "). Trying to reconnect...");
                    Global.getLogger(WingmanClientPlugin.class).warn("Wingman: connection lost (" + wingman.net.Redact.scrub(link.getError()) + "); reconnecting");
                    showReconnect(true);
                }
                nextReconnectAt = System.currentTimeMillis() + 3000;
            } // (any other failure is a refusal, which has already explained itself)
            lastState = state;
        }
        if (reconnecting) {
            long nowMs = System.currentTimeMillis();
            if (rejected) {
                reconnecting = false; // the host said no, and said why
                rejoinShip = -1;
            } else if (state == ClientLink.State.CONNECTED) {
                reconnecting = false;
                if ("CONNECTION LOST".equals(alertTitle)) { alertTitle = null; alertLines.clear(); alertAction = 0; }
                lastSnapshotAt = nowMs;
                stallWarned = false;
                stallAlerted = false;
                resyncs = 0;
                Ui.say(engine, "Co-op: reconnected. Loading the battle from the host again...");
                Global.getLogger(WingmanClientPlugin.class).info("Wingman: reconnected after " + reconnectTries + " attempt(s)");
            } else if (state == ClientLink.State.FAILED && nowMs >= nextReconnectAt) {
                if (reconnectTries >= RECONNECT_TRIES) {
                    reconnecting = false;
                    rejoinShip = -1;
                    alertWith("CONNECTION LOST", "Could not reconnect to the host (" + link.getError() + ").\n"
                            + "Leave the battle; you can join again from the CO-OP panel once the host can be reached.", 1);
                } else {
                    reconnectTries++;
                    String password = "";
                    try { password = SavedJoin.load().password; } catch (Throwable t) { /* an open game needs none */ }
                    link.connect(cfg.hostAddress, cfg.port, cfg.playerName, Mods.enabled(), Mods.gameVersion(), password);
                    showReconnect(false);
                }
            }
        }
        keepPlaceholderOutOfTheWay();
        if (state != ClientLink.State.CONNECTED) return;

        prof.begin();
        frame++;
        localDamage.advance(amount);
        if (!damageListening && cfg != null && cfg.damageReports) {
            damageListening = true;
            try { engine.getListenerManager().addListener(localDamage); }
            catch (Throwable th) { hurtsBroken = true; }
        }
        if (!heldModules.isEmpty() && clock - heldSince > 10f) { // their ship never came
            for (Integer id : new ArrayList<Integer>(heldModules.keySet())) giveUpModule(id);
        }
        Conn conn = link.getConn();
        Conn.Packet p;
        while ((p = conn.poll()) != null) {
            try {
                handle(p);
            } catch (Exception e) {
                // Guard rail: one message that can't be read or applied is skipped, not fatal. A steady stream
                // of them means something is really wrong, and then co-op stops with the usual notice.
                if (++messageErrors <= 5) {
                    Global.getLogger(WingmanClientPlugin.class).warn("Wingman: skipped a message of type " + p.type + " (" + p.data.length + " bytes)", e);
                }
                if (messageErrors > 40) throw e;
            }
        }

        // A long freeze on this PC can overflow the queue of messages from the host, and the ones thrown away
        // may have mattered (a ship appearing, a ship going). Don't wait to notice the damage: ask again now.
        // Position updates that came by UDP, beside the connection.
        byte[] byUdp;
        int udpRead = 0;
        while (udpRead++ < 400 && (byUdp = link.pollUpdate()) != null) {
            try {
                handle(Conn.packet(Msg.SNAPSHOT_D, byUdp));
            } catch (Exception e) {
                if (++messageErrors <= 5) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: skipped an update that came by UDP (" + byUdp.length + " bytes)", e);
            }
        }
        int lostMessages = conn.takeDropped();
        if (lostMessages > 0) requestResync("this PC fell behind and " + lostMessages + " messages from the host were lost", 5000);

        prof.mark(0);
        followHostTime();
        removeLocalStrays();
        prof.mark(1);
        takeRememberedShip();
        watchPlayerShip(conn);
        sendSubsystems(conn);
        reportReady(conn);

        long nowNanos = System.nanoTime();
        Puppet own = puppets.get(ownId);
        if (own != null && engine.getPlayerShip() != own.ship) own = null;
        watchAutopilot(own);
        if (!autopilot) { matchSystemReadiness(own, amount); confirmSystemUse(own); }
        // On autopilot our ship is flown by the host's AI, so show it exactly like any other host-flown ship.
        Puppet handsOn = autopilot ? null : own;
        for (Puppet pp : puppets.values()) apply(pp, pp == handsOn, amount);
        prof.mark(2);
        for (MissilePuppet mp : missiles.values()) applyMissile(mp, amount);
        reviveMissiles(amount);
        prof.mark(3);
        followWrecks(amount);
        prof.mark(4);
        finishStrikes();
        ghostScriptedShots();
        prof.mark(5);
        for (Iterator<MissilePuppet> it = releasing.iterator(); it.hasNext();) {
            MissilePuppet mp = it.next();
            boolean gone = !engine.isEntityInPlay(mp.missile); // its script released the stage and removed it
            if (!gone && clock < mp.releaseBy) continue;
            it.remove();
            byMissile.remove(mp.missile);
            if (!gone) engine.removeEntity(mp.missile);
            if (mp.endWatched && mp.launch != null) { // did our copy of the script end it as the host's did?
                if (gone) { scriptEndsLocal++; compat.good("missile:" + mp.launch.projId); }
                else {
                    scriptEndsPlain++;
                    compat.odd("missile:" + mp.launch.projId);
                    if (plainEndWarned.add(mp.launch.projId)) Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: the host's script ended a '"
                            + mp.launch.projId + "' missile; this PC's copy of the script did not do the same within 0.6 s, so the missile was taken away quietly");
                }
            }
        }
        if (Dbg.on) targeting(own); // diagnostics only
        rescueLostShips();
        if (nowNanos - lastDamageNanos > 500000000L) { // re-assert twice a second in case the local game drifted
            lastDamageNanos = nowNanos;
            for (Puppet pp : puppets.values()) if (pp.damage != null) applyDamage(pp, false);
        }
        if (Dbg.on) probeTargetable(own);
        prof.mark(6);
        orders.tick();
        // Bandwidth meter: detailed every 30 seconds with debug logging; otherwise a short line every two minutes,
        // so a playtest's log says what the mod cost and how evenly updates arrived.
        if (!Dbg.on) {
            String brief = wingman.net.NetStats.report(120f);
            if (brief != null) {
                Global.getLogger(WingmanClientPlugin.class).info("Wingman traffic (joiner): " + brief
                        + (lateN > 0 ? "; updates arrived on average " + Math.round(1000f * lateSum / lateN) + " ms later than the promptest ones" : ""));
                lateSum = 0f;
                lateN = 0;
            }
        }
        String traffic = Dbg.on ? wingman.net.NetStats.report(30f) : null; // bandwidth meter, one line every 30 seconds
        if (traffic != null) {
            Dbg.info(WingmanClientPlugin.class, "Wingman traffic (joiner): " + traffic);
            Dbg.info(WingmanClientPlugin.class, "Wingman time (joiner, " + puppets.size() + " ships and fighters, " + missiles.size() + " missiles, "
                    + wrecks.size() + " wrecks; controls sent " + inputsSent + " times): " + prof.report());
            inputsSent = 0;
            if (ownErrN > 0) Dbg.info(WingmanClientPlugin.class, "Wingman own ship: on average " + Math.round(ownErrSum / ownErrN)
                    + " units from the real one on the host once the delay is taken out, at most " + Math.round(ownWorst) + ", put straight back "
                    + ownSnaps + " times; compared " + reconChecks + " times, " + reconMisses + " could not be compared; ship system used "
                    + sysUsesTotal + " times so far (" + sysConfirmed + " seen to happen on the host, " + sysResent + " sent a second time, "
                    + sysUnconfirmed + " never happened there)");
            if (lateN > 0) Dbg.info(WingmanClientPlugin.class, "Wingman timing: updates arrived on average " + Math.round(1000f * lateSum / lateN)
                    + " ms later than the promptest ones (over " + lateN + " updates)");
            lateSum = 0f; lateN = 0; reconChecks = 0; reconMisses = 0;
            ownErrSum = 0f; ownErrN = 0; ownWorst = 0f; ownSnaps = 0;
        }

        // Our controls are looked at every frame and sent at once when anything about them has changed (a key,
        // the mouse, the heading), up to 60 times a second. While nothing changes they are repeated 15 times a second.
        // (Both timers allow a few milliseconds' slack: frames don't land exactly on the tick, and waiting for
        // the next frame each time would turn 15 a second into 10.)
        // By UDP a set of controls can be lost on the way and is not sent again by the network, so a change is
        // repeated in the next two frames (the host takes each numbered set once).
        if (udpRepeats > 0 && lastControls != null && link.controlsByUdp()) {
            udpRepeats--;
            link.sendControls(lastControls, decoder.ackEpoch(), decoder.ackTick());
        }
        boolean floorDue = nowNanos - lastInputSentNanos >= (long) (1000000000L / INPUT_FLOOR) - 6000000L;
        if (floorDue || nowNanos - lastInputSentNanos >= (long) (1000000000L / INPUT_RATE) - 3000000L) {
            lastInputNanos = nowNanos;
            captureInput(own);
            byte[] controls = input.encode();
            if (floorDue || InputState.differ(controls, lastControls)) {
                // Numbered, and the moment noted, so the host's answer can be matched to it (see reconcile()).
                input.seq = ++ownSeq;
                controls = input.encode();
                seqNumber[ownSeq & 255] = ownSeq;
                seqClock[ownSeq & 255] = clock;
                if (link.sendControls(controls, decoder.ackEpoch(), decoder.ackTick())) {
                    if (!floorDue) udpRepeats = 2;
                    lastControls = controls;
                    lastInputSentNanos = nowNanos;
                    inputsSent++;
                }
            }
        }
        prof.mark(7);
        prof.end();

        long nowMs = System.currentTimeMillis();
        if (welcomed && !stallWarned && nowMs - lastSnapshotAt > 4000) {
            stallWarned = true;
            Ui.say(engine, "Co-op: no updates from the host (battle over, or the host is not in a battle yet).");
        }
        // Twenty seconds of silence on a link that is still alive: either the host left the battle some way that
        // could not be announced, or the host's game is barely running (a game window in the background can
        // slow right down, which is what happens with two copies on one PC). Offer the way out, but never on
        // plain Enter: that is also the ready key, and a player pressing it for that must not be thrown out.
        if (welcomed && everUpdated && !stallAlerted && alertTitle == null && nowMs - lastSnapshotAt > 20000) {
            stallAlerted = true;
            alertWith("NO UPDATES FROM THE HOST", "Nothing has arrived from the host's battle for twenty seconds, though the connection is up.\n"
                    + "The host may have left the battle, or the host's game may be running very slowly (for example in a\n"
                    + "background window). You stay connected either way.", 3);
        }
        if (leaveAtMillis > 0 && nowMs >= leaveAtMillis) { leaveBattle(false); return; }
        watch.tick();
        if (!watchNoticeShown && !watch.newlyBlocked.isEmpty()) {
            watchNoticeShown = true;
            Ui.say(engine, "Co-op: the game closed last time while these mod ship systems were first in use, so they are no longer "
                    + "mirrored on this PC: " + watch.newlyBlocked + ". To switch them back on, open the CO-OP panel on the title screen and press F5.");
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: ship systems no longer mirrored here after a possible crash: " + watch.newlyBlocked);
        }
        // At our own edge of the map (the bottom), flying a ship: offer to retreat with it.
        retreatOffered = own != null && !autopilot && own.owner == 0 && alertTitle == null
                && own.ship.getLocation().y < -engine.getMapHeight() / 2f + 600f;
    }

    /**
     * Ask the host for the whole battle again, and sit back down in our ship when it arrives. Limited to a few
     * times a battle and not more often than minGapMs, so a fault that a refresh doesn't cure can't loop.
     */
    private void requestResync(String why, long minGapMs) {
        long nowMs = System.currentTimeMillis();
        if (resyncs >= 5 || nowMs - lastResyncAt < minGapMs) return;
        resyncs++;
        lastResyncAt = nowMs;
        reboardId = ownId;
        Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: " + why + "; asking the host for everything again (" + resyncs + " of 5)");
        Ui.say(engine, "Co-op: refreshing your copy of the battle from the host.");
        link.getConn().send(Msg.SYNC_REQ, Msg.EMPTY);
    }

    /** The notice shown while trying to get back in. Once dismissed it stays away, unless forced. */
    private void showReconnect(boolean force) {
        if (!force && !"CONNECTION LOST".equals(alertTitle)) return;
        alertWith("CONNECTION LOST", "The connection to the host was lost. Trying to reconnect"
                + (reconnectTries == 0 ? "..." : " (attempt " + reconnectTries + " of " + RECONNECT_TRIES + ")...") + "\n"
                + "If it works you are put back in the battle, and in your ship if it is still free.", 1);
    }

    private void alertWith(String title, String text, int action) {
        alertLines.clear();
        alert(title, text);
        alertAction = action;
    }

    /**
     * Leave this battle; the connection to the host stays. Used when the host's battle is over, when the
     * player chooses to go, and (with rejoin) to come straight back in on a correctly sized map.
     */
    private void leaveBattle(boolean rejoin) {
        leaving = true;
        leaveAtMillis = 0;
        watch.objectivesSurvived();
        watch.battleEnded();
        CampaignStandIn.remove();
        compat.save();
        WingmanTitlePlugin.rejoinRequested = rejoin;
        // Back on the title screen: if the host's battle is over, wait for the next; if we walked out of one
        // still going, don't walk straight back in.
        WingmanTitlePlugin.hostBattleOver = hostEnded;
        WingmanTitlePlugin.leftOnPurpose = !hostEnded && !rejoin;
        if (rejoin) WingmanTitlePlugin.lastAutoRejoin = System.currentTimeMillis();
        JoinSetup.forget(); // the next join asks the host about its battlefield afresh
        try {
            engine.setDoNotEndCombat(false);
            if (engine.isPaused()) engine.setPaused(false);
            engine.endCombat(0.1f);
        } catch (Throwable t) {
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not end the battle here; leave it from the game's own menu", t);
            Ui.say(engine, "Co-op: could not leave automatically. Use the game's menu (Esc) to leave the battle.");
        }
    }

    private void claim(Conn conn, int netId) {
        claimSeq++;
        conn.send(Msg.CLAIM, Msg.twoInts(claimSeq, netId));
    }

    /**
     * Ship selection is the game's own transfer-command order. Whenever the ship under our control changes
     * (we boarded a puppet, or left one), tell the host which ship we now want; it answers with ASSIGN.
     */
    private float fighterSince = -100f;
    private int fighterBounces;

    private void watchPlayerShip(Conn conn) {
        ShipAPI ps = engine.getPlayerShip();
        if (ps == lastPlayerShip) return;
        ShipAPI was = lastPlayerShip;
        lastPlayerShip = ps;
        // The game hands the ship we left to its own AI; puppets must stay inert.
        for (Puppet pp : puppets.values()) if (pp.ship != ps) pp.ship.setShipAI(new PuppetAI());
        if (ps != placeholder) placeholder.setShipAI(new PuppetAI());
        Puppet now = ps == null ? null : byShip.get(ps);
        // Taken out of a fighter a script handed us, back into the ship we flew, within a few seconds: note why it
        // might have happened (a carrier script ends the hand-over when the carrier's system stops), for the log.
        Puppet left = was == null ? null : byShip.get(was);
        if (left != null && left.fighter && now != null && !now.fighter && clock - fighterSince < 5f && fighterBounces++ < 5) {
            String sys = "none";
            try {
                ShipSystemAPI cs = ps.getSystem();
                if (cs != null) sys = cs.getId() + " " + cs.getState() + ", cooldown left " + Math.round(cs.getCooldownRemaining() * 10f) / 10f
                        + (now.target == null ? "" : ", host's stage " + STAGES[Math.max(0, Math.min(STAGES.length - 1, now.target.system))]);
            } catch (Throwable t) { /* just the fact then */ }
            Global.getLogger(WingmanClientPlugin.class).info("Wingman: put back into " + ps.getHullSpec().getHullId() + " from its fighter after "
                    + Math.round((clock - fighterSince) * 1000f) + " ms (fighter alive " + was.isAlive() + "; carrier system: " + sys + ")");
        }
        if (now != null && now.fighter) fighterSince = clock;
        // A fighter only when a mod's script handed us one from the ship we flew (UAF's Isenoria does).
        boolean ok = now != null && (!now.fighter || WingmanHostPlugin.linked(ps, was));
        claim(conn, ok ? now.netId : -1);
    }

    /**
     * MagicLib subsystems of the ship we fly that the player just switched on with their key: tell the host,
     * where the real ship's subsystems wait for exactly that (see Subsystems).
     */
    private void sendSubsystems(Conn conn) {
        if (cfg == null || !cfg.joinerSubsystems) return;
        Puppet own = ownId < 0 ? null : puppets.get(ownId);
        if (own == null || engine.getPlayerShip() != own.ship) return;
        for (String cls : Subsystems.pressed(own.ship)) {
            Msg.W w = new Msg.W();
            try {
                w.out.writeInt(ownId);
                w.out.writeUTF(cls.length() > 200 ? cls.substring(0, 200) : cls);
            } catch (java.io.IOException ex) { continue; }
            conn.send(Msg.SUBSYSTEM, w.done());
            Dbg.info(WingmanClientPlugin.class, "Wingman subsystems: used " + cls + " on our ship; the host is told");
        }
    }

    /** Local fighters and missiles that the host never told us about would be lies on screen: drop them. */
    private final Map<ShipAPI, Boolean> ghosted = new IdentityHashMap<ShipAPI, Boolean>();

    /** Which stats have needed correcting so far, for the log. */
    private String statNames() {
        if (statFixedMask == 0L) return "";
        StringBuilder sb = new StringBuilder(" (");
        int n = 0;
        for (int i = 0; i < ShipStats.COUNT; i++) {
            if ((statFixedMask & (1L << i)) == 0) continue;
            if (n++ > 0) sb.append(", ");
            if (n > 10) { sb.append("..."); break; }
            sb.append(ShipStats.name(i));
        }
        return sb.append(")").toString();
    }

    private void ghostDrone(ShipAPI drone) {
        try {
            if (!ghosted.containsKey(drone)) {
                if (ghosted.size() > 500) ghosted.clear();
                ghosted.put(drone, Boolean.TRUE);
                drone.setShipAI(new PuppetAI());
                drone.setCollisionClass(CollisionClass.NONE);
                makeInvulnerable(drone);
            }
            drone.setExtraAlphaMult(0f);
            drone.setApplyExtraAlphaToEngines(true);
            List<WeaponAPI> guns = drone.getAllWeapons();
            for (int i = 0; i < guns.size(); i++) guns.get(i).setForceNoFireOneFrame(true);
        } catch (Throwable t) { /* it stays visible; harmless */ }
    }

    // ---- Ships this game's own scripts make ----
    // A mod's script can put a ship on the field by itself: an objective's turret or reinforcements (Secrets
    // of the Frontier), a summoned escort. It runs here as on the host, so the same ship appears here too, a
    // moment earlier or later than the host's word of its own. Like a missile launched here, the one made
    // here is the one to keep when it matches, because the script that made it keeps hold of it (to repair
    // it, to switch it to the other side when the objective changes hands). Until then it is made harmless
    // at once, and if the host never reports a matching ship it is taken off the field.
    private static final float LOCAL_SHIP_GRACE = 3f, FRESH_WINDOW = 3f;
    private final Map<ShipAPI, Float> localShips = new HashMap<ShipAPI, Float>();
    /** Ships built here from the host's description in the last few seconds, which a local one may stand in for. */
    private final Map<Puppet, Float> freshlyBuilt = new HashMap<Puppet, Float>();
    private int localShipsAdopted, localShipsRemoved;
    private boolean localShipsBroken;

    /** One ship here that is not one of ours. Returns true if it is to be taken off the field now. */
    private boolean localShip(ShipAPI s) {
        if (s == placeholder || s.isHulk() || localShipsBroken) return false;
        try {
            Float seen = localShips.get(s);
            if (seen == null) {
                s.setShipAI(new PuppetAI()); // harmless at once: no AI, no damage
                makeInvulnerable(s);
                localShips.put(s, clock);
                if (localShips.size() > 200) localShips.clear(); // guard rail: something is making ships by the hundred
                Puppet p = freshMatch(s);
                if (p != null && swapIn(p, s)) return false;
                return false;
            }
            if (clock - seen < LOCAL_SHIP_GRACE) return false;
            localShips.remove(s);
            localShipsRemoved++;
            Dbg.info(WingmanClientPlugin.class, "Wingman local ship: " + s.getHullSpec().getHullId() + " (side " + s.getOwner()
                    + ") was made by a script here but the host has no such ship; removed");
            for (ShipAPI m : s.getChildModulesCopy()) {
                try { m.setShipAI(new PuppetAI()); engine.removeEntity(m); } catch (Throwable t) { /* goes with its ship */ }
            }
            return true;
        } catch (Throwable t) {
            localShipsBroken = true;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: matching ships made here to the host's switched off", t);
            return false;
        }
    }

    /** Same side, same hull, same guns in the same slots: the ship the host described. */
    private static boolean sameShip(ShipSpawn sp, ShipAPI s) {
        if (s.getOwner() != sp.owner || s.getHullSpec() == null || !sp.hullId.equals(s.getHullSpec().getHullId())) return false;
        if (!sp.moduleIds.isEmpty() || !s.getChildModulesCopy().isEmpty()) return false; // multi-part ships: built from the description
        Map<String, String> here = new HashMap<String, String>();
        for (WeaponAPI w : s.getAllWeapons()) if (w.getSlot() != null) here.put(w.getSlot().getId(), w.getId());
        for (int i = 0; i < sp.weaponOrder.size() && i < sp.weaponOrderIds.size(); i++) {
            String slot = sp.weaponOrder.get(i), want = sp.weaponOrderIds.get(i), have = here.get(slot);
            if (slot.isEmpty()) continue;
            if (want.isEmpty() ? have != null : !want.equals(have)) return false;
        }
        return true;
    }

    /** A ship made here, waiting, that is the one the host has just described. */
    private ShipAPI takeLocalShip(ShipSpawn sp) {
        if (localShipsBroken || sp.kind != ShipSpawn.SHIP || !sp.wingId.isEmpty()) return null;
        try {
            ShipAPI best = null;
            float bestD = Float.MAX_VALUE;
            for (Map.Entry<ShipAPI, Float> e : localShips.entrySet()) {
                ShipAPI s = e.getKey();
                if (clock - e.getValue() > LOCAL_SHIP_GRACE || !engine.isEntityInPlay(s) || s.isHulk() || !sameShip(sp, s)) continue;
                float dx = s.getLocation().x - sp.x, dy = s.getLocation().y - sp.y, d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = s; }
            }
            if (best == null) return null;
            localShips.remove(best);
            moveTo(best, sp.x, sp.y, sp.facing);
            localShipsAdopted++;
            Dbg.info(WingmanClientPlugin.class, "Wingman local ship: " + sp.hullId + " made by a script here stands in for the host's (net " + sp.netId + ")");
            return best;
        } catch (Throwable t) {
            localShipsBroken = true;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: matching ships made here to the host's switched off", t);
            return null;
        }
    }

    /** A ship built here from the host's description moments ago that this newly made one should replace. */
    private Puppet freshMatch(ShipAPI s) {
        Puppet best = null;
        for (Iterator<Map.Entry<Puppet, Float>> it = freshlyBuilt.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Puppet, Float> e = it.next();
            Puppet p = e.getKey();
            if (clock - e.getValue() > FRESH_WINDOW || puppets.get(p.netId) != p) { it.remove(); continue; }
            if (best == null && p.info != null && p.netId != ownId && sameShip(p.info, s)) best = p;
        }
        return best;
    }

    /** Put a ship made here in the place of the copy built for the host's ship, keeping everything learned. */
    private boolean swapIn(Puppet old, ShipAPI s) {
        if (engine.getPlayerShip() == old.ship) return false; // never pull a ship out from under the player
        freshlyBuilt.remove(old);
        localShips.remove(s);
        float x = old.ship.getLocation().x, y = old.ship.getLocation().y, f = old.ship.getFacing();
        puppets.remove(old.netId);
        byShip.remove(old.ship);
        moveTo(s, x, y, f);
        Puppet fresh = register(old.info, s);
        fresh.target = old.target;
        fresh.targetAt = old.targetAt;
        fresh.angles = old.angles;
        fresh.damage = old.damage;
        if (fresh.damage != null) applyDamage(fresh, true);
        try { old.ship.setShipAI(new PuppetAI()); } catch (Throwable t) { /* removed regardless */ }
        if (engine.isEntityInPlay(old.ship)) engine.removeEntity(old.ship);
        localShipsAdopted++;
        Dbg.info(WingmanClientPlugin.class, "Wingman local ship: " + old.info.hullId + " made by a script here replaces the copy built for the host's (net " + old.netId + ")");
        return true;
    }

    private static void moveTo(ShipAPI s, float x, float y, float facing) {
        s.getLocation().set(x, y);
        s.setFacing(facing);
        // A station a script pinned in place stays pinned, but where the host has it.
        try { if (s.getFixedLocation() != null) s.setFixedLocation(new Vector2f(x, y)); } catch (Throwable t) { /* not pinned */ }
    }

    private void removeLocalStrays() {
        ShipAPI ps = engine.getPlayerShip();
        List<CombatEntityAPI> strays = null;
        for (ShipAPI s : engine.getShips()) {
            if (s == ps || s.isShuttlePod() || byShip.containsKey(s)) continue;
            if (s.isDrone()) {
                // A drone launched by a ship system on one of our copies. It is ours alone (the host has its
                // own), and harmless while its ship is here; once that ship is gone it must not linger.
                ShipAPI from = null;
                try { from = s.getDroneSource(); } catch (Throwable t) { /* not that kind of drone */ }
                if (from != null && byShip.containsKey(from) && engine.isEntityInPlay(from) && !from.isHulk()) {
                    // The host's drones are shown as copies of their own (see spawn). This one, which the
                    // system on our copy of the ship launched by itself, is kept out of sight and out of the
                    // way: taking it off the field would only make the system launch another.
                    ghostDrone(s);
                    continue;
                }
                if (from == null) continue;
            } else if (!s.isFighter()) {
                if (localShip(s)) {
                    if (strays == null) strays = new ArrayList<CombatEntityAPI>();
                    strays.add(s);
                    straysRemoved++;
                }
                continue;
            }
            if (strays == null) strays = new ArrayList<CombatEntityAPI>();
            strays.add(s);
            straysRemoved++;
        }
        matchMissiles(); // missiles made here are matched to the host's rather than removed on sight
        // Asteroids the game adds by itself turn up rarely and do no harm for a moment: looked for four times a second.
        long sweepNow = System.nanoTime();
        if (sweepNow - lastAsteroidSweep >= 250000000L) {
            lastAsteroidSweep = sweepNow;
            for (CombatEntityAPI a : engine.getAsteroids()) {
                if (environment.isMine(a)) continue;
                if (strays == null) strays = new ArrayList<CombatEntityAPI>();
                strays.add(a);
            }
        }
        if (strays == null) return;
        // Switch every stray fighter's AI off before removing any of them: a fighter whose wing leader has just
        // been taken away crashes the game if its own AI gets to run again first.
        for (CombatEntityAPI e : strays) {
            if (e instanceof ShipAPI) {
                try { ((ShipAPI) e).setShipAI(new PuppetAI()); } catch (Throwable t) { /* removed below regardless */ }
            }
        }
        for (CombatEntityAPI e : strays) engine.removeEntity(e);
    }

    private final wingman.net.SnapshotCodec.Decoder decoder = new wingman.net.SnapshotCodec.Decoder();
    private boolean hostEnded;
    private long askedForKeyAt;
    private int udpRepeats;
    private int keysAsked, updatesUnreadable;

    /**
     * An update sent as changes since the one before. If that one never reached us (we have just arrived, or
     * messages were lost while the game was frozen), this one can't be read: ask the host for one in full.
     */
    private Snapshot readUpdate(byte[] data) {
        Snapshot snap = null;
        try {
            snap = decoder.decode(data);
        } catch (Throwable t) {
            if (updatesUnreadable++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: a position update could not be read; asking the host for a full one", t);
        }
        if (snap != null) {
            link.ack(decoder.ackEpoch(), decoder.ackTick()); // the host builds the next one on what we say we hold
            return snap;
        }
        // Older than one already read (they can overtake each other by UDP): nothing to do.
        if (decoder.result == wingman.net.SnapshotCodec.Decoder.OLD) return null;
        long now = System.currentTimeMillis();
        Conn conn = link.getConn();
        if (now - askedForKeyAt >= 200 && conn != null) {
            askedForKeyAt = now;
            keysAsked++;
            conn.send(Msg.NEED_KEY, Msg.EMPTY);
        }
        return null;
    }

    private void handle(Conn.Packet p) throws Exception {
        switch (p.type) {
            case Msg.WELCOME: {
                In in = Msg.reader(p.data);
                in.readInt();
                int max = in.readInt();
                in.readInt();
                String host = in.readUTF();
                welcomed = true;
                Ui.say(engine, "Co-op: joined " + host + "'s game (up to " + max + " players).");
                break;
            }
            case Msg.REJECT:
                Ui.say(engine, "Co-op: the host refused the connection. " + Msg.readUtf(p.data));
                rejected = true;
                alert("CONNECTION REFUSED", Msg.readUtf(p.data));
                alertLines.add("Fix the difference, then restart the game and this mission.");
                break;
            case Msg.BATTLE_INFO:
                // Remembered, so that hopping out and back in (below) builds the right map at once.
                try { JoinSetup.remember(wingman.net.BattleInfo.decode(p.data)); } catch (Throwable t) { /* asked for again on rejoining */ }
                environment.onBattleInfo(p.data);
                if (environment.mapMismatch != null) {
                    // Offer to hop out and straight back in on a map of the right size, unless that was just
                    // tried (then the plain notice, so this can never go round in circles).
                    boolean justTried = System.currentTimeMillis() - WingmanTitlePlugin.lastAutoRejoin < 30000;
                    alertWith("BATTLEFIELD DIFFERS", environment.mapMismatch, justTried ? 0 : 2);
                }
                break;
            case Msg.NEBULA:
                environment.onNebula(p.data);
                break;
            case Msg.ASTEROIDS:
                environment.onAsteroids(p.data);
                break;
            case Msg.DAMAGE:
                for (DamageState.Ship d : DamageState.decode(p.data).ships) {
                    Puppet pp = puppets.get(d.netId);
                    if (pp == null) continue;
                    d.mergeOnto(pp.damage); // armor may arrive as just the cells that changed
                    pp.damage = d;
                    applyDamage(pp, true);
                }
                break;
            case Msg.OBJECTIVES:
                orders.onObjectives(p.data);
                break;
            case Msg.LANDING: {
                // A ship of the host's lands on a carrier, or takes off again (Arma Armatura's strikecraft refit).
                In in = Msg.reader(p.data);
                int id = in.readInt(), carrierId = in.readInt();
                byte what = in.readByte();
                Puppet pp = puppets.get(id);
                if (pp == null) break;
                try {
                    if (what == 1) {
                        Puppet c = carrierId < 0 ? null : puppets.get(carrierId);
                        if (c != null && !pp.ship.isLanding() && !pp.ship.isFinishedLanding()) pp.ship.beginLandingAnimation(c.ship);
                    } else if (what == 2 && (pp.ship.isLanding() || pp.ship.isFinishedLanding())) {
                        pp.ship.setAnimatedLaunch();
                    }
                } catch (Throwable t) {
                    if (landingFailed++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: a landing on a carrier could not be shown here", t);
                }
                break;
            }
            case Msg.COMBAT_MSG:
                showHostLine(p.data);
                break;
            case Msg.OBJECTIVE_TEXT:
                orders.onObjectiveText(p.data);
                break;
            case Msg.STATS:
                if (cfg == null || !cfg.syncStats) break;
                for (wingman.net.Extras.Stats st : wingman.net.Extras.decodeStats(p.data)) {
                    Puppet pp = puppets.get(st.ship);
                    if (pp == null) continue;
                    if (pp.hostStats == null) { pp.hostStats = new float[ShipStats.COUNT]; java.util.Arrays.fill(pp.hostStats, Float.NaN); }
                    for (int i = 0, k = 0; i < 64 && k < st.values.length; i++) {
                        if ((st.mask & (1L << i)) == 0) continue;
                        float v = st.values[k++];
                        if (i < ShipStats.COUNT && !Snapshot.bad(v)) pp.hostStats[i] = v;
                    }
                    pp.statsDue = true;
                }
                break;
            case Msg.NOTES:
                if (cfg == null || !cfg.syncScriptMemory) break;
                for (wingman.net.Extras.Note n : wingman.net.Extras.decodeNotes(p.data)) {
                    if (n.key.startsWith("wingman")) continue;
                    Object v = n.value(n.wide);
                    try {
                        if (n.ship < 0) {
                            if (v == null) engine.getCustomData().remove(n.key); else engine.getCustomData().put(n.key, v);
                        } else {
                            Puppet pp = puppets.get(n.ship);
                            if (pp == null) continue;
                            if (v == null) pp.ship.removeCustomData(n.key); else pp.ship.setCustomData(n.key, v);
                        }
                        notesApplied++;
                    } catch (Throwable th) { /* that one value is skipped */ }
                }
                break;
            case Msg.HURTS:
                if (cfg == null || !cfg.damageReports || hurtsBroken) break;
                try {
                    for (wingman.net.Extras.Hurt h : wingman.net.Extras.decodeHurts(p.data)) {
                        Puppet pp = puppets.get(h.target);
                        if (pp == null || pp.ship.isHulk() || !engine.isEntityInPlay(pp.ship)) continue;
                        hurtsSeen++;
                        // What this game has itself thrown at the ship lately accounts for part of it or all of
                        // it. Only a clear shortfall is played out: damage here that this game never produced
                        // (a script on the host, an explosion we did not have).
                        float missing = h.amount - localDamage.lately(pp.ship);
                        if (h.amount < 1f || missing < 40f + 0.5f * h.amount) continue;
                        Puppet from = puppets.get(h.source);
                        DamageType type = DamageType.values()[Math.max(0, Math.min(DamageType.values().length - 1, h.type))];
                        localDamage.replaying = true;
                        try {
                            engine.applyDamage(pp.ship, new Vector2f(h.x, h.y), missing, type, h.emp, !h.onShield, false,
                                    from == null ? null : from.ship, false);
                        } finally {
                            localDamage.replaying = false;
                        }
                        hurtsPlayed++;
                    }
                } catch (Throwable th) {
                    hurtsBroken = true;
                    Global.getLogger(WingmanClientPlugin.class).error("Wingman: playing out damage the host reported failed; off for this battle", th);
                }
                break;
            case Msg.ORDERS:
                orders.onOrders(wingman.net.Orders.decode(p.data));
                break;
            case Msg.LOBBY:
                lobby = Lobby.decode(p.data);
                if (!lobby.readyCheck) iAmReady = false;
                break;
            case Msg.ALERT: {
                In in = Msg.reader(p.data);
                String title = in.readUTF();
                alert(title, in.readUTF());
                break;
            }
            case Msg.NOTICE:
                Ui.say(engine, "Co-op: " + Msg.readUtf(p.data));
                break;
            case Msg.RESET:
                hostPaused = false;
                Subsystems.reset();
                toPlaceholder();
                for (Puppet pp : puppets.values()) engine.removeEntity(pp.ship);
                for (MissilePuppet mp : missiles.values()) engine.removeEntity(mp.missile);
                puppets.clear();
                byShip.clear();
                hostWings.clear();
                departed.clear();
                for (MissilePuppet mp : releasing) engine.removeEntity(mp.missile);
                releasing.clear();
                recentBuilt.clear();
                for (Wreck w : wrecks.values()) if (engine.isEntityInPlay(w.ship)) engine.removeEntity(w.ship);
                wrecks.clear();
                lostWrecks.clear();
                heldModules.clear();
                ghosted.clear();
                localDamage.clear();
                launchedBy.clear();
                unconfirmed.clear();
                silenced.clear();
                gapCount = 0;
                gapNext = 0;
                paceWall = 0;
                reconReady = false;
                ackFresh = false;
                wreckMisses = 0;
                pendingSplits.clear();
                splitDue.clear();
                replays.clear();
                replayShots.clear();
                ghostShots.clear();
                ownHits.clear();
                heldHits.clear();
                compat.save();
                for (MissilePuppet mp : striking) engine.removeEntity(mp.missile);
                striking.clear();
                untouchable.clear();
                pending.clear();
                for (MissileAPI m : localMissiles.keySet()) engine.removeEntity(m);
                localMissiles.clear();
                missiles.clear();
                byMissile.clear();
                ownId = -1;
                hostFlagship = -1;
                missing.clear();
                skippedShips = 0;
                skippedIds.clear();
                checkMisses = 0;
                stallAlerted = false;
                for (ShipAPI s : new ArrayList<ShipAPI>(engine.getShips())) { // drones our copies launched
                    try { if (s.isDrone() && s.getDroneSource() != null) engine.removeEntity(s); } catch (Throwable t) { /* stays */ }
                }
                orders.reset();
                environment.reset();
                localShips.clear();
                freshlyBuilt.clear();
                iAmReady = false;
                sentBuilt = -1;
                lobby = null;
                break;
            case Msg.SIDE: {
                // A ship changed sides on the host (boarded, hacked, taken over by a script).
                In in = Msg.reader(p.data);
                int id = in.readInt(), side = in.readByte();
                if (side != 0 && side != 1 && side != 100) break;
                Puppet pp = puppets.get(id);
                if (pp == null) break;
                pp.owner = side;
                if (pp.info != null) pp.info.owner = side;
                setSide(pp.ship, side);
                Dbg.info(WingmanClientPlugin.class, "Wingman: " + describe(pp) + " is now on side " + side + " (as on the host)");
                break;
            }
            case Msg.SPAWN:
                spawn(ShipSpawn.decode(p.data));
                if (!hintShown && !puppets.isEmpty()) {
                    hintShown = true;
                    Ui.say(engine, "Co-op: target a friendly ship and use Transfer Command to take control of it.");
                }
                break;
            case Msg.DESPAWN: {
                In in = Msg.reader(p.data);
                int id = in.readInt();
                byte how = in.readByte();
                Boolean neverBuilt = skippedIds.remove(id);
                Puppet pp = puppets.remove(id);
                if (pp == null && (how == Msg.DESPAWN_DESTROYED || how == Msg.DESPAWN_WRECKED)) {
                    // A ship we could not build has been destroyed: the host now has a wreck we don't.
                    if (neverBuilt != null && !neverBuilt) lostWrecks.add(id);
                    break;
                }
                if (pp == null) { // not a live ship: a wreck or a piece of one that is gone on the host
                    Wreck wreck = wrecks.remove(id);
                    if (wreck != null && engine.isEntityInPlay(wreck.ship)) engine.removeEntity(wreck.ship);
                    lostWrecks.remove(id);
                    for (int i = pendingSplits.size() - 1; i >= 0; i--) {
                        if (pendingSplits.get(i)[1] == id) { pendingSplits.remove(i); splitDue.remove(i); }
                    }
                    break;
                }
                byShip.remove(pp.ship);
                if (pp.weapons.length > 0) { // its missiles may still be on their way to us
                    pp.goneAt = clock;
                    departed.put(id, pp);
                    if (departed.size() > 60) departed.remove(departed.keySet().iterator().next());
                }
                if (engine.getPlayerShip() == pp.ship) toPlaceholder();
                if (how == Msg.DESPAWN_DESTROYED || how == Msg.DESPAWN_WRECKED || how == Msg.DESPAWN_NO_WRECK) {
                    pp.guard.standAside = true;
                    if (!pp.fighter && how != Msg.DESPAWN_NO_WRECK) {
                        // The host has a wreck where this ship was, so there is one here too, and it stays
                        // under the host's direction (see followWrecks).
                        try { pp.ship.setHulkChanceOverride(1f); } catch (Throwable t) { /* the game decides */ }
                        if (how == Msg.DESPAWN_WRECKED) { // it died before we got here: no fireworks now
                            try { pp.ship.setExplosionScale(0f); } catch (Throwable t) { /* it goes off once */ }
                        }
                        if (wrecks.size() < 200) {
                            Wreck wreck = new Wreck();
                            wreck.ship = pp.ship;
                            wreck.guard = pp.guard;
                            wreck.info = pp.info;
                            wrecks.put(id, wreck);
                        } else {
                            lostWrecks.add(id);
                        }
                    }
                    destroy(pp.ship);
                } else {
                    if (pp.fighter) { // landed on its carrier: it leaves its wing as well as the field
                        try { if (pp.ship.getWing() != null) pp.ship.getWing().removeMember(pp.ship); } catch (Throwable t) { /* gone either way */ }
                    }
                    engine.removeEntity(pp.ship);
                }
                break;
            }
            case Msg.ASSIGN: {
                In in = Msg.reader(p.data);
                int seq = in.readInt();
                int id = in.readInt();
                if (id != ownId && id >= 0) {
                    Ui.say(engine, "Co-op: you have the helm.");
                    if (!helmHintsShown) {
                        helmHintsShown = true;
                        Ui.say(engine, "Co-op: Transfer Command again to change ship. U hands the ship to the host's AI and back.");
                        Ui.say(engine, "Co-op: to leave with your ship, give it the Retreat order, or fly to the bottom edge and press Enter.");
                    }
                }
                ownId = id;
                if (id >= 0) { pickId = id; rememberShip(puppets.get(id)); }
                // Back after a dropped connection, and the battle has been loaded again: sit down in the ship we
                // were flying, if it is still there. The game then sees us change ship, and the host is asked
                // for it in the usual way (and may say no, if someone else took it meanwhile).
                if (rejoinShip >= 0 && id < 0) {
                    Puppet back = puppets.get(rejoinShip);
                    if (back != null && !back.fighter && back.owner == 0 && back.info != null && back.info.hullId.equals(rejoinHull)
                            && engine.getPlayerShip() == placeholder) {
                        engine.setPlayerShipExternal(back.ship);
                    }
                    rejoinShip = -1;
                }
                // After a refresh of the whole battle, sit back down in the ship we were flying.
                if (reboardId >= 0 && id == reboardId && puppets.containsKey(id) && engine.getPlayerShip() == placeholder) {
                    engine.setPlayerShipExternal(puppets.get(id).ship);
                    lastPlayerShip = puppets.get(id).ship;
                }
                reboardId = -1;
                // If the host has seen our latest request and still says the ship we sit in isn't ours, step out.
                Puppet in2 = byShip.get(engine.getPlayerShip());
                if (seq == claimSeq && in2 != null && in2.netId != ownId) toPlaceholder();
                break;
            }
            case Msg.MISSILE_SPAWN:
                spawnMissile(Msg.reader(p.data));
                break;
            case Msg.MISSILE_DESPAWN: {
                In in = Msg.reader(p.data);
                int gone = in.readInt();
                MissilePuppet mp = missiles.remove(gone);
                byte how = in.readByte();
                for (Iterator<PendingMissile> it = pending.iterator(); it.hasNext();) if (it.next().id == gone) it.remove();
                if (mp == null) break;
                boolean endedByScript = how == Msg.DESPAWN_SCRIPTED;
                if (endedByScript) scriptEndsSeen++;
                if ((how == Msg.DESPAWN_REMOVED || endedByScript) && (mp.scripted || mp.tended) && !mp.flamedOut && engine.isEntityInPlay(mp.missile)) {
                    // The host's copy has just released its next stage, or been ended by its script. Ours is a
                    // moment away from doing the same under its own copy of that script, flash and sound
                    // included, so give it that moment before removing it.
                    mp.releaseBy = clock + (endedByScript ? 0.6f : 0.35f);
                    mp.endWatched = endedByScript;
                    mp.target = null;
                    releasing.add(mp);
                    break;
                }
                byMissile.remove(mp.missile);
                if (!engine.isEntityInPlay(mp.missile)) { // it already ended on our screen
                    if (endedByScript && mp.launch != null) { scriptEndsLocal++; compat.good("missile:" + mp.launch.projId); }
                    break;
                }
                if (endedByScript && mp.launch != null) { // no script here to end it: the plain explosion will have to do
                    scriptEndsPlain++;
                    compat.odd("missile:" + mp.launch.projId);
                    if (plainEndWarned.add(mp.launch.projId)) Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: the host's script ended a '"
                            + mp.launch.projId + "' missile, but this copy has no script of its own to do the same; shown as a plain explosion");
                }
                if (how == Msg.DESPAWN_DESTROYED || endedByScript) {
                    mp.missile.setHitpoints(1f);
                    engine.applyDamage(mp.missile, mp.missile.getLocation(), 100000f, DamageType.FRAGMENTATION, 0f,
                            false, false, null);
                } else {
                    engine.removeEntity(mp.missile);
                }
                break;
            }
            case Msg.MISSILE_HIT: {
                In in = Msg.reader(p.data);
                int id = in.readInt(), targetId = in.readInt();
                float x = in.readFloat(), y = in.readFloat(), facing = in.readFloat(), vx = in.readFloat(), vy = in.readFloat();
                boolean onShield = in.readBoolean();
                for (Iterator<PendingMissile> it = pending.iterator(); it.hasNext();) if (it.next().id == id) it.remove();
                MissilePuppet mp = missiles.remove(id);
                if (mp == null) break;
                if (!engine.isEntityInPlay(mp.missile)) { byMissile.remove(mp.missile); break; }
                if (Snapshot.bad(x) || Snapshot.bad(y) || Snapshot.bad(vx) || Snapshot.bad(vy) || Snapshot.bad(facing)) {
                    byMissile.remove(mp.missile);
                    engine.removeEntity(mp.missile);
                    break;
                }
                strike(mp, targetId, x, y, facing, vx, vy, onShield);
                break;
            }
            case Msg.WRECK_SPLIT: {
                In in = Msg.reader(p.data);
                int[] split = { in.readInt(), in.readInt(), 0 };
                Wreck from = wrecks.get(split[0]);
                if (from != null) from.split = true;
                // Tried now, and if the game finds no line to break along, again every couple of seconds for half a minute.
                if (pendingSplits.size() < 300) { pendingSplits.add(split); splitDue.add(new float[] { clock, clock + 30f }); }
                else lostWrecks.add(split[1]);
                break;
            }
            case Msg.SHOT_HIT:
                replayShot(Msg.reader(p.data));
                break;
            case Msg.BATTLE_END:
                hostPaused = false;
                Global.getLogger(WingmanClientPlugin.class).info("Wingman: the host says its battle is over");
                hostEnded = true;
                JoinSetup.battleOver();
                if (leaveAtMillis == 0) {
                    alertWith("BATTLE OVER", "The host's battle has ended. Leaving in a moment.\n"
                            + (cfg != null && cfg.autoJoin ? "You stay connected, and join the host's next battle when it begins."
                                    : "You stay connected: join the next battle from the CO-OP panel on the title screen."), 1);
                    leaveAtMillis = System.currentTimeMillis() + 3500;
                }
                break;
            case Msg.CHECK: {
                // The host says how many ships and fighters there should be. Spawns and removals reach us in
                // order on the same link, so at this moment our copy should agree exactly. If it keeps
                // disagreeing, something went wrong building or removing one: ask for everything afresh.
                In in = Msg.reader(p.data);
                int ships = in.readInt(), fighters = in.readInt(), sum = in.readInt();
                int hostWrecks = in.readInt(), hostWreckSum = in.readInt();
                // Wrecks the same way, counting the ones we know of but could not show. A difference there is
                // only looks, so it has to last longer, and only ever causes one refresh a battle.
                int myWrecks = wrecks.size() + lostWrecks.size() + pendingSplits.size(), myWreckSum = 0;
                for (Integer w : wrecks.keySet()) myWreckSum += w;
                for (Integer w : lostWrecks) myWreckSum += w;
                for (int[] w : pendingSplits) myWreckSum += w[1];
                if (hostWrecks == myWrecks && hostWreckSum == myWreckSum) wreckMisses = 0;
                else if (++wreckMisses >= 5 && !wreckResyncUsed) {
                    wreckResyncUsed = true;
                    wreckMisses = 0;
                    requestResync("the wrecks here have drifted from the host's (" + myWrecks + " here / " + hostWrecks + " on the host)", 20000);
                    break;
                }
                int myShips = 0, myFighters = 0, mySum = 0;
                for (Puppet pp : puppets.values()) { if (pp.fighter) myFighters++; else myShips++; mySum += pp.netId; }
                for (Map.Entry<Integer, Boolean> e : skippedIds.entrySet()) { if (e.getValue()) myFighters++; else myShips++; mySum += e.getKey(); }
                if (ships == myShips && fighters == myFighters && sum == mySum) { checkMisses = 0; break; }
                if (++checkMisses < 3) break;
                checkMisses = 0;
                requestResync("this copy of the battle has drifted (ships " + myShips + " here / " + ships + " on the host, fighters "
                        + myFighters + " / " + fighters + ")", 20000);
                break;
            }
            case Msg.MISSILE_TARGET: {
                In in = Msg.reader(p.data);
                int mid = in.readInt(), aim = in.readInt();
                MissilePuppet mp = missiles.get(mid);
                if (mp != null && mp.scripted) mp.wantTarget = aim;
                for (PendingMissile q : pending) if (q.id == mid) q.wantTarget = aim;
                break;
            }
            case Msg.SNAPSHOT_D:
            case Msg.SNAPSHOT: {
                Snapshot snap = p.type == Msg.SNAPSHOT ? Snapshot.decode(p.data) : readUpdate(p.data);
                if (snap == null) break; // built on an update we never got; one in full has been asked for
                // When was this true on the host? Updates don't arrive evenly: one is held up a frame, the next
                // is not. The host's clock in each one shows which were held up (their clock is older than the
                // moment of arrival suggests, going by the promptest of the last few), and those are treated
                // as that much older, so a fast thing is carried exactly as far forward as it should be.
                keepPace(snap);
                float gap = clock - snap.hostTime;
                if (gapCount > 0 && Math.abs(gap - gapMin) > 1f) gapCount = 0; // the clocks jumped apart: start over
                gaps[gapNext] = gap;
                gapNext = (gapNext + 1) % gaps.length;
                if (gapCount < gaps.length) gapCount++;
                gapMin = Float.MAX_VALUE;
                for (int gi = 0; gi < gapCount; gi++) if (gaps[gi] < gapMin) gapMin = gaps[gi];
                float late = Math.max(0f, Math.min(0.1f, gap - gapMin));
                lateSum += late;
                lateN++;
                float now = clock - late;
                for (int pi = 0; pi < snap.piloted.length; pi++) {
                    if (snap.piloted[pi] != ownId || snap.pilotedAge[pi] >= 2.5f) continue;
                    ackSeq = snap.pilotedSeq[pi];
                    ackAge = snap.pilotedAge[pi];
                    ackFresh = true;
                    hostSysAmmo = snap.pilotedSysAmmo[pi];
                }
                lastSnapshotAt = System.currentTimeMillis();
                stallWarned = false;
                everUpdated = true;
                stallAlerted = false;
                hostFlagship = snap.hostFlagship;
                hostTimeMult = snap.timeMult > 0.001f ? snap.timeMult : 1f;
                markTransferTargets(snap.piloted);
                for (Snapshot.ShipState st : snap.ships) {
                    Puppet pp = puppets.get(st.netId);
                    if (pp == null) {
                        Wreck wreck = wrecks.get(st.netId);
                        if (wreck != null) { wreck.target = st; wreck.targetAt = now; }
                        continue;
                    }
                    if (pp.target != null) {
                        // Is this where the ship could have flown to since the last update, or has it jumped?
                        float since = Math.max(0f, Math.min(1f, now - pp.targetAt));
                        float jx = st.x - (pp.target.x + pp.target.vx * since), jy = st.y - (pp.target.y + pp.target.vy * since);
                        float pace2 = Math.abs(st.vx) + Math.abs(st.vy) + Math.abs(pp.target.vx) + Math.abs(pp.target.vy);
                        float reach = 150f + pace2 * 0.3f;
                        if (jx * jx + jy * jy > reach * reach) pp.jumped = true;
                    }
                    pp.target = st;
                    pp.targetAt = now;
                    if (st.weaponAngles != null) pp.angles = st.weaponAngles;
                    if (st.shots != null) {
                        if (pp.ahead == null) {
                            int n = Math.min(64, pp.weapons.length);
                            pp.ahead = new int[n]; pp.counted = new boolean[n];
                            pp.lastCooldown = new float[n]; pp.lastShotAt = new float[n]; pp.aheadSince = new float[n];
                            for (int wi = 0; wi < n; wi++) if (pp.weapons[wi] != null) pp.lastCooldown[wi] = pp.weapons[wi].getCooldownRemaining();
                        }
                        for (int si = 0; si + 1 < st.shots.length; si += 2) {
                            int wi = st.shots[si] & 0xFF, count = st.shots[si + 1] & 0xFF;
                            if (wi >= pp.ahead.length) continue;
                            pp.counted[wi] = true;
                            pp.ahead[wi] = Math.max(-6, pp.ahead[wi] - count);
                            pp.aheadSince[wi] = clock;
                        }
                    }
                }
                for (Snapshot.MissileState ms : snap.missiles) {
                    MissilePuppet mp = missiles.get(ms.id);
                    if (mp == null) continue;
                    mp.target = ms;
                    mp.targetAt = now;
                }
                hostPaused = snap.paused;
                if (snap.paused != engine.isPaused()) engine.setPaused(snap.paused);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Tell the game which ships Transfer Command may not target: enemies, fighters, the host's own ship and
     * ships another player is flying. The game then refuses them itself, before any shuttle is launched.
     */
    /** The key that takes the marked ship in the ready check: F. */
    private static final int KEY_PICK = 33;
    private int pickId = -1;
    private String pickNote = "";

    /** The ships of our side that a player could in principle fly, in a steady order. */
    private List<Puppet> pickable() {
        List<Puppet> out = new ArrayList<Puppet>();
        for (Puppet pp : puppets.values()) {
            if (pp.owner != 0 || pp.fighter || pp.module || pp.drone || pp.info == null) continue;
            if (!engine.isEntityInPlay(pp.ship) || pp.ship.isHulk()) continue;
            out.add(pp);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Puppet>() {
            public int compare(Puppet a, Puppet b) {
                int size = b.ship.getHullSize().ordinal() - a.ship.getHullSize().ordinal(); // biggest first
                return size != 0 ? size : a.netId - b.netId;
            }
        });
        return out;
    }

    /** Why this ship is not ours to take ("the host's", "taken"...), or null if it is free. */
    private String cannotFly(Puppet pp) {
        if (pp.netId == hostFlagship) return "the host's";
        if (pp.transferState == 1) return "taken";
        try {
            if (lobby != null && pp.ship.getHullSize().ordinal() > ShipAPI.HullSize.valueOf(lobby.maxShipSize).ordinal()) return "over the host's size limit";
        } catch (Throwable t) { /* no limit known */ }
        return null;
    }

    private boolean rememberTried;
    private static final String SHIP_FILE = "wingman_ship";
    private static String rememberedShip;

    /** Note which ship we fly, so the next battle can put us straight back in it. */
    private void rememberShip(Puppet pp) {
        if (pp == null || pp.info == null) return;
        try {
            String line = pp.info.hullId + "\n" + (pp.info.name == null ? "" : pp.info.name.replace('\n', ' ')) + "\n";
            if (line.equals(rememberedShip)) return;
            rememberedShip = line;
            Global.getSettings().writeTextFileToCommon(SHIP_FILE, line);
        } catch (Throwable t) { /* asked again next battle, that is all */ }
    }

    /**
     * Once per battle, when everything is loaded and we have no ship: sit down in the ship we flew last time,
     * if it is in this battle and free. The host is asked for it in the usual way and may still say no.
     */
    private void takeRememberedShip() {
        if (rememberTried || cfg == null || !cfg.rememberShip || lobby == null) return;
        if (!(builtShips() + skippedShips >= lobby.expected && lobby.expected > 0)) return;
        rememberTried = true;
        if (ownId >= 0 || rejoinShip >= 0 || reboardId >= 0 || engine.getPlayerShip() != placeholder) return;
        try {
            if (rememberedShip == null) {
                rememberedShip = Global.getSettings().fileExistsInCommon(SHIP_FILE) ? Global.getSettings().readTextFileFromCommon(SHIP_FILE) : "";
                if (rememberedShip == null) rememberedShip = "";
            }
            String[] parts = rememberedShip.split("\n", -1);
            if (parts.length < 2 || parts[0].trim().isEmpty()) return;
            String hull = parts[0].trim(), name = parts[1].trim();
            Puppet found = null;
            int same = 0;
            for (Puppet pp : pickable()) {
                if (!hull.equals(pp.info.hullId) || cannotFly(pp) != null) continue;
                boolean named = name.equals(pp.info.name == null ? "" : pp.info.name.trim());
                if (named) { found = pp; same = 1; break; }
                // (the same class under another name counts only if it is the only one of its class)
                if (found == null) found = pp;
                same++;
            }
            if (found == null || same != 1) return;
            pickId = found.netId;
            engine.setPlayerShipExternal(found.ship);
            Ui.say(engine, "Co-op: you are back in the ship you flew last time. Choose another in the ready check, or with Transfer Command.");
        } catch (Throwable t) {
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not put you back in your last ship; pick one as usual", t);
        }
    }

    private void markTransferTargets(int[] piloted) {
        for (Puppet pp : puppets.values()) {
            if (pp.owner != 0) continue; // the game never lets you board an enemy, so leave their flags untouched
            boolean blocked = pp.fighter || pp.module || pp.drone || pp.netId == hostFlagship;
            for (int i = 0; !blocked && i < piloted.length; i++) {
                if (piloted[i] == pp.netId && pp.netId != ownId) blocked = true;
            }
            int state = blocked ? 1 : 0;
            if (state == pp.transferState) continue;
            pp.transferState = state;
            pp.ship.setInvalidTransferCommandTarget(blocked);
        }
    }

    private void alert(String title, String text) {
        alertAction = 0;
        alertTitle = title;
        for (String line : text.split("\n")) alertLines.add(line);
        while (alertLines.size() > 14) alertLines.remove(0);
    }

    private int builtShips() {
        int n = 0;
        for (Puppet pp : puppets.values()) if (!pp.fighter) n++;
        return n;
    }

    /**
     * During the host's ready check: tell the host how much of the battle we have built, and whether we
     * pressed ready. Ready only counts once every ship the host has is accounted for on our side.
     */
    private void reportReady(Conn conn) {
        if (lobby == null || !lobby.readyCheck) { enterPressed = false; return; }
        int built = builtShips();
        boolean synced = built + skippedShips >= lobby.expected && lobby.expected > 0;
        if (enterPressed) iAmReady = synced && !iAmReady;
        if (!synced) iAmReady = false;
        enterPressed = false;
        long now = System.currentTimeMillis();
        if (built == sentBuilt && skippedShips == sentSkipped && iAmReady == sentReady && now - lastReadyMillis < 1000) return;
        sentBuilt = built;
        sentSkipped = skippedShips;
        sentReady = iAmReady;
        lastReadyMillis = now;
        Msg.W w = new Msg.W();
        try {
            w.out.writeBoolean(iAmReady);
            w.out.writeInt(built);
            w.out.writeInt(skippedShips);
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        conn.send(Msg.READY, w.done());
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (disabled || !ready || events == null) return;
        if (joinScreen) {
            if (form.keys(events, link.getState() == ClientLink.State.CONNECTING, false)) submitJoin();
            return;
        }
        try {
            if (!settings.open && cfg != null && cfg.playerTags) PlayerTags.toggleKey(events, engine);
            if (!settings.open) {
                for (InputEventAPI e : events) { // Ctrl+Shift+O: the settings page
                    if (e.isConsumed() || !e.isKeyDownEvent() || !e.isCtrlDown() || !e.isShiftDown() || e.getEventValue() != 24) continue;
                    settings.open(cfg);
                    e.consume();
                }
            }
            if (settings.open) {
                settings.keys(events);
                for (String key : settings.changed) {
                    if ("syncTolerance".equals(key)) slack = cfg.syncTolerance;
                    Global.getLogger(WingmanClientPlugin.class).info("Wingman: setting changed in battle: " + key
                            + ("password".equals(key) ? "" : " = " + cfg.get(key)));
                }
                settings.changed.clear();
                return;
            }
        } catch (Throwable t) {
            settings.open = false;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: the settings page failed", t);
        }
        watchTargetKey(events);
        boolean popup = alertTitle != null;
        boolean lobbyUp = lobby != null && lobby.readyCheck;
        if (!popup && !lobbyUp && !retreatOffered) return;
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyDownEvent()) continue;
            int key = e.getEventValue();
            if (popup && alertAction == 3 && Panel.isEnter(key)) { // stay unless Ctrl+Enter
                boolean leave = e.isCtrlDown();
                alertTitle = null;
                alertLines.clear();
                alertAction = 0;
                if (leave) leaveBattle(false);
                e.consume();
                continue;
            }
            if (popup && alertAction != 0 && key == 14) { // Backspace: not now
                alertTitle = null;
                alertLines.clear();
                e.consume();
                continue;
            }
            if (!popup && lobbyUp && (key == 200 || key == 208 || key == KEY_PICK)) { // the ship list: Up, Down, F
                try {
                    List<Puppet> ships = pickable();
                    if (!ships.isEmpty()) {
                        int at = 0;
                        for (int i = 0; i < ships.size(); i++) if (ships.get(i).netId == pickId) at = i;
                        if (key == 200) at = (at + ships.size() - 1) % ships.size();
                        else if (key == 208) at = (at + 1) % ships.size();
                        Puppet chosen = ships.get(at);
                        pickId = chosen.netId;
                        if (key == KEY_PICK) {
                            String why = cannotFly(chosen);
                            if (chosen.netId == ownId) toPlaceholder();           // F on your own ship: step out of it
                            else if (why != null) pickNote = "That ship is " + why + ".";
                            else { pickNote = ""; engine.setPlayerShipExternal(chosen.ship); } // the host is then asked for it, as with Transfer Command
                        } else pickNote = "";
                    }
                } catch (Throwable t) {
                    Global.getLogger(WingmanClientPlugin.class).warn("Wingman: choosing a ship from the list failed; use Transfer Command", t);
                }
                e.consume();
                continue;
            }
            if (!Panel.isEnter(key)) continue;
            if (popup) {
                int action = alertAction;
                alertTitle = null;
                alertLines.clear();
                alertAction = 0;
                if (action != 0) leaveBattle(action == 2);
            } else if (lobbyUp) {
                enterPressed = true;
            } else {
                retreatNowUntil = System.currentTimeMillis() + 1500;
                Ui.say(engine, "Co-op: retreating. The ship leaves the battle under its own power.");
            }
            e.consume();
        }
    }

    private void openJoinScreen(String status, Color color) {
        joinScreen = true;
        form.status = status == null ? "" : status;
        form.statusColor = color;
    }

    private void submitJoin() {
        if (!form.valid()) return;
        form.save();
        String address = form.address.toString().trim(), name = form.name();
        int port = SavedJoin.portOf(address, cfg.port);
        String host = SavedJoin.hostOf(address);
        cfg.hostAddress = host;
        cfg.port = port;
        cfg.playerName = name;
        rejected = false;
        JoinSetup.rejectReason = null;
        alertTitle = null;
        alertLines.clear();
        form.status = "";
        lastState = ClientLink.State.IDLE;
        link.connect(host, port, name, Mods.enabled(), Mods.gameVersion(), form.password.toString().trim());
        lastSnapshotAt = System.currentTimeMillis();
        Global.getLogger(WingmanClientPlugin.class).info("Wingman join screen: connecting to the host as " + name);
    }

    /**
     * U in Starsector hands your ship to the AI and takes it back. Here the AI that counts is the host's, so
     * while the game says autopilot is on we tell the host, which lets the real ship's AI fly it. Every change
     * is logged with what the game reports, because whether U toggles cleanly can only be seen in a real run.
     */
    private void watchAutopilot(Puppet own) {
        boolean now = false;
        try {
            now = own != null && engine.getCombatUI() != null && engine.getCombatUI().isAutopilotOn();
        } catch (Throwable t) { /* older API: no autopilot support */ }
        if (now == autopilot && own == autopilotShip) return;
        if (own != null) {
            ShipAPI s = own.ship;
            String ai = "none";
            try { if (s.getShipAI() != null) ai = s.getShipAI().getClass().getName(); } catch (Throwable t) { ai = "?"; }
            Dbg.info(WingmanClientPlugin.class, "Wingman autopilot: " + (now ? "ON" : "OFF") + " on " + describe(own)
                    + ", controls locked " + s.controlsLocked() + ", ship AI " + ai + ", retreating order "
                    + (engine.getFleetManager(0).getTaskManager(false).getAssignmentFor(s) != null
                        ? String.valueOf(engine.getFleetManager(0).getTaskManager(false).getAssignmentFor(s).getType()) : "none"));
        }
        autopilot = now;
        autopilotShip = own;
    }

    /**
     * The battle runs at the host's pace, strictly. Phase cloaks slow time for whoever is flying the phased
     * ship, and speed-up mods change it outright; on a joiner's machine either would make this copy of the
     * battle run at a different speed from the real one. So every frame we take whatever the local game and
     * other mods have set and scale it to land exactly on the host's value.
     */
    private void followHostTime() {
        try {
            com.fs.starfarer.api.combat.MutableStat time = engine.getTimeMult();
            time.unmodify(TIME_KEY);
            float local = time.getModifiedValue();
            float want = hostTimeMult * pace;
            if (local > 0.0001f && Math.abs(local - want) > 0.0005f) time.modifyMult(TIME_KEY, want / local);
            if (Math.abs(hostTimeMult - loggedTimeMult) > 0.01f) {
                loggedTimeMult = hostTimeMult;
                Dbg.info(WingmanClientPlugin.class, "Wingman time: following the host at x" + hostTimeMult);
            }
            if (!localTimeNoted && Math.abs(local - 1f) > 0.01f) {
                localTimeNoted = true;
                Dbg.info(WingmanClientPlugin.class, "Wingman time: something here set the battle speed to x" + local
                        + " (a phase cloak or a speed-up mod); it is overridden, only the host's speed counts");
            }
        } catch (Throwable t) {
            // leave time alone rather than risk the battle
        }
    }

    /** Notes that the target key was pressed, without taking the key away from the game. */
    private void watchTargetKey(List<InputEventAPI> events) {
        if (events == null) return;
        for (InputEventAPI e : events) {
            if (e.isKeyDownEvent() && e.getEventValue() == KEY_R && !e.isRepeat()) targetKeyFrames = 2;
        }
    }

    /**
     * The game normally handles targeting by itself. If it was asked to target (R) and ended up with nothing
     * although a ship is under the cursor, pick that ship ourselves and keep it picked. Also writes what
     * happened to the log, since targeting can't be checked any other way.
     */
    private void targeting(Puppet own) {
        if (own == null) { forcedTarget = null; targetKeyFrames = 0; return; }
        ShipAPI me = own.ship;

        // The game handles R by itself again now that puppets count as targetable (see makeInvulnerable).
        // An earlier build stepped in here when the game refused to target; that would now undo the player's
        // own "R to clear target", so nothing is forced any more. Target changes are still logged below.
        targetKeyFrames = 0;
        forcedTarget = null;

        ShipAPI t = me.getShipTarget();
        if (t != lastLoggedTarget) {
            lastLoggedTarget = t;
            Puppet tp = t == null ? null : byShip.get(t);
            Dbg.info(WingmanClientPlugin.class, "Wingman targeting: target is now "
                    + (t == null ? "nothing" : tp == null ? "a ship the host doesn't know" : describe(tp)));
        }

        long now = System.currentTimeMillis();
        if (now - lastDebugMillis > 30000) {
            lastDebugMillis = now;
            int friends = 0, foes = 0, wrongSide = 0, foesVisible = 0, foesTargetable = 0, friendsTargetable = 0;
            Puppet sample = null;
            for (Puppet pp : puppets.values()) {
                if (pp.fighter) continue;
                if (pp.owner == 0) friends++; else foes++;
                try { if (pp.owner == 0 && pp.ship.isTargetable()) friendsTargetable++; } catch (Throwable th) { /* diagnostics only */ }
                if (pp.ship.getOwner() != pp.owner) wrongSide++;
                if (pp.owner != 0) {
                    try {
                        if (engine.getFogOfWar(0).isVisible(pp.ship)) foesVisible++;
                        if (pp.ship.isTargetable()) foesTargetable++; else if (sample == null) sample = pp;
                    } catch (Throwable th) { /* diagnostics only */ }
                }
            }
            int fighters = 0;
            for (Puppet pp : puppets.values()) if (pp.fighter) fighters++;
            Dbg.info(WingmanClientPlugin.class, "Wingman fighters: showing " + fighters + " of the host's fighters in " + hostWings.size()
                    + " wings, " + missiles.size() + " missiles (" + adopted + " were this game's own launches confirmed by the host, " + builtHere
                    + " had to be built as copies, " + swapped + " copies were swapped for the real one, " + missilesRevived
                    + " that ended here but not on the host were put back; hits reported by the host: " + hitsNatural
                    + " made by the game, " + hitsForced + " carried out by hand; scripted shot hits: " + shotHitsNatural
                    + " made by the game, " + shotHitsForced + " by hand; ships built under a fleet commander's skills: " + underCommand + ", fighters with officers of their own: " + officeredFighters
                    + "; full updates asked for: " + keysAsked + " (" + decoder.missed + " updates could not be used, " + decoder.late + " arrived after a newer one); " + link.describeUdp() + "; link: " + (link.getConn() == null ? "none" : link.getConn().compressionState())
                    + "; of the host's scripted hits " + hitsMatched + " were also made here, "
                    + hitsCopied + " were copied from the host, and " + hitsOwnOnly + " made here were not reported by the host; wrecks followed: " + wrecks.size() + ", break-ups copied " + wreckSplitsDone
                    + ", not possible " + wreckSplitsFailed + "); " + straysRemoved + " fighters/missiles made by this game were cleared away, "
                    + missilesUnbuilt + " host missiles could not be built here, " + standIns + " were built with a borrowed launcher; ship systems switched to match the host " + systemsForced + " times, started the proper way " + systemsStarted + " times, stopped the proper way " + systemsStopped + " times, teleports drawn " + blinksDrawn + " times; modules shown " + modulesShown + ", drones shown " + dronesShown + "; stats corrected " + statFixes + " times" + statNames()
                    + "; script values copied " + notesApplied + "; damage reports " + hurtsSeen + " (" + hurtsPlayed + " played out here); shots made up " + shotsMadeUp
                    + " frames, held " + shotsHeld + " frames; launchers held back from firing ahead of the host " + launchesHeld + " frames, " + launchesRefuted
                    + " missiles fired here that the host never fired; missiles ended by a script on the host: "
                    + scriptEndsSeen + " (" + scriptEndsLocal + " ended the same way here by the script, " + scriptEndsPlain + " not; " + tendedTold + " copies were introduced to their launcher's script)");
            Dbg.info(WingmanClientPlugin.class, "Wingman status: " + friends + " friendly ships"
                    + " (" + friendsTargetable + " targetable), " + foes + " enemy ships (" + foesVisible + " visible through fog, " + foesTargetable + " targetable, "
                    + wrongSide + " on the wrong side), my side " + me.getOwner() + ", my target "
                    + (t == null ? "none" : byShip.get(t) == null ? "unknown" : describe(byShip.get(t))));
            if (sample != null && !sampleLogged) {
                sampleLogged = true;
                try {
                    ShipAPI e = sample.ship;
                    Dbg.info(WingmanClientPlugin.class, "Wingman status: untargetable enemy " + describe(sample)
                            + ": alive " + e.isAlive() + ", hulk " + e.isHulk() + ", phased " + e.isPhased() + ", collision "
                            + e.getCollisionClass() + ", alpha " + e.getAlphaMult() + "/" + e.getExtraAlphaMult()
                            + ", hull " + e.getHitpoints() + ", CR " + e.getCurrentCR() + ", ally flag " + e.isAlly()
                            + ", original side " + e.getOriginalOwner() + ", has fleet member " + (e.getFleetMember() != null)
                            + ", known to enemy fleet " + (engine.getFleetManager(1).getDeployedFleetMember(e) != null)
                            + ", known to our fleet " + (engine.getFleetManager(0).getDeployedFleetMember(e) != null));
                } catch (Throwable th) { /* diagnostics only */ }
            }
        }
    }

    // ---- Diagnostics: why does the game refuse to treat enemy puppets as targets? ----
    // Takes one enemy puppet and, one frame at a time, undoes each thing this mod does to puppets, asking the
    // game after each whether the ship is now targetable. The answers go to the log. Runs once per battle.
    private static final String[] PROBE_STEPS = {
        "nothing changed", "hull damage block removed", "armor damage block removed", "EMP block removed",
        "fighter bay slowdown removed", "the game's own AI restored", "mod left the ship alone for a frame",
        "transfer-target flag cleared", "all of the above together",
    };
    private int probeStep = PROBE_STEPS.length; // finished: it found the cause (see makeInvulnerable)
    private Puppet probe;
    private long probeAt;
    private final StringBuilder probeReport = new StringBuilder();

    private void probeChange(int step, boolean undo) {
        ShipAPI s = probe.ship;
        MutableShipStatsAPI st = s.getMutableStats();
        boolean all = step == 8;
        if (step == 1 || all) { if (undo) st.getHullDamageTakenMult().modifyMult(NO_DAMAGE, HULL_TRICKLE); else st.getHullDamageTakenMult().unmodify(NO_DAMAGE); }
        if (step == 2 || all) { if (undo) st.getArmorDamageTakenMult().modifyMult(NO_DAMAGE, 0f); else st.getArmorDamageTakenMult().unmodify(NO_DAMAGE); }
        if (step == 3 || all) { if (undo) st.getEmpDamageTakenMult().modifyMult(NO_DAMAGE, 0f); else st.getEmpDamageTakenMult().unmodify(NO_DAMAGE); }
        if (step == 4 || all) { if (undo) st.getFighterRefitTimeMult().modifyMult(NO_DAMAGE, 100000f); else st.getFighterRefitTimeMult().unmodify(NO_DAMAGE); }
        if (step == 5 || all) { if (undo) s.setShipAI(new PuppetAI()); else s.resetDefaultAI(); }
        if (step == 6 || all) probe.probing = !undo;
        if (step == 7 || all) { if (!undo) s.setInvalidTransferCommandTarget(false); }
    }

    private void probeTargetable(Puppet own) {
        if (probeStep >= PROBE_STEPS.length || own == null || engine.isPaused()) return;
        try {
            long now = System.currentTimeMillis();
            if (probeStep < 0) {
                if (probeAt == 0) { probeAt = now + 8000; return; }
                if (now < probeAt) return;
                for (Puppet pp : puppets.values()) {
                    if (pp.owner != 0 && !pp.fighter && engine.getFogOfWar(0).isVisible(pp.ship)) { probe = pp; break; }
                }
                if (probe == null) { probeAt = now + 3000; return; }
                probeStep = 0;
                probeChange(0, false);
                return;
            }
            if (!puppets.containsKey(probe.netId)) { probeStep = -1; probe = null; probeAt = now + 3000; probeReport.setLength(0); return; }
            // One frame has passed with this step's change in place: ask, undo, move on.
            probeReport.append(probeReport.length() == 0 ? "" : "; ").append(PROBE_STEPS[probeStep]).append(" -> ")
                    .append(probe.ship.isTargetable() ? "TARGETABLE" : "no");
            probeChange(probeStep, true);
            probeStep++;
            if (probeStep < PROBE_STEPS.length) {
                probeChange(probeStep, false);
            } else {
                probe.probing = false;
                Dbg.info(WingmanClientPlugin.class, "Wingman probe on " + describe(probe) + ": " + probeReport);
            }
        } catch (Throwable t) {
            probeStep = PROBE_STEPS.length;
            if (probe != null) probe.probing = false;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman probe failed", t);
        }
    }

    private static String describe(Puppet pp) {
        String name = pp.ship.getName();
        return (pp.owner == 0 ? "friendly " : pp.owner == 100 ? "neutral " : "enemy ") + (name == null || name.isEmpty()
                ? pp.ship.getHullSpec().getHullId() : name) + " (ship thinks it is side " + pp.ship.getOwner() + ")";
    }

    /** The ship nearest the mouse cursor, if the cursor is on or right next to it. Ships before fighters. */
    private Puppet puppetUnderCursor(ShipAPI me) {
        Vector2f m = me.getMouseTarget();
        if (m == null) return null;
        Puppet best = null;
        float bestScore = Float.MAX_VALUE;
        for (Puppet pp : puppets.values()) {
            if (pp.ship == me) continue;
            Vector2f loc = pp.ship.getLocation();
            float dx = loc.x - m.x, dy = loc.y - m.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > pp.ship.getCollisionRadius() + 100f) continue;
            float score = dist + (pp.fighter ? 300f : 0f);
            if (score < bestScore) { bestScore = score; best = pp; }
        }
        return best;
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (disabled || !ready) return;
        if (!joinScreen && cfg != null && cfg.playerTags) { // who is flying what: the host is P1, the rest as the host numbers them
            Puppet hostShip = puppets.get(hostFlagship);
            if (hostShip != null) tags.draw(viewport, hostShip.ship, 1, cfg.playerOutlines);
            if (lobby != null) {
                for (Lobby.Entry e : lobby.players) {
                    Puppet pp = e.shipNetId >= 0 ? puppets.get(e.shipNetId) : null;
                    if (pp != null) tags.draw(viewport, pp.ship, e.slot, cfg.playerOutlines);
                }
            }
        }
        List<String> lines = new ArrayList<String>();
        List<Color> colors = new ArrayList<Color>();
        if (joinScreen) {
            boolean busy = link.getState() == ClientLink.State.CONNECTING;
            form.lines(lines, colors, busy);
            panel.render("JOIN A CO-OP GAME", lines, colors, JoinForm.FOOTER);
            return;
        }
        if (settings.open) {
            try {
                settings.lines(lines, colors);
                panel.render(settings.title(), lines, colors, settings.footer());
            } catch (Throwable t) {
                settings.open = false;
                Global.getLogger(Panel.class).error("Wingman: the settings page could not be shown and was closed", t);
            }
            return;
        }
        if (alertTitle != null) {
            for (String line : alertLines) { lines.add(line); colors.add(Panel.WARN); }
            panel.render(alertTitle, lines, colors, alertAction == 0 ? "Enter: dismiss"
                    : alertAction == 1 ? "Enter: leave the battle     Backspace: stay"
                    : alertAction == 3 ? "Enter: keep waiting     Ctrl+Enter: leave the battle"
                    : "Enter: leave and rejoin with the right map     Backspace: stay");
            return;
        }
        if (retreatOffered && (lobby == null || !lobby.readyCheck)) {
            float w = 330f;
            panel.button((Global.getSettings().getScreenWidth() - w) / 2f, 150f, w, 46f, "RETREAT", "Enter: leave the battle with this ship", false);
        }
        if (hostPaused && (lobby == null || !lobby.readyCheck)) {
            panel.banner("PAUSED", "The host has paused the battle.", Panel.WARN);
        }
        if (lobby == null || !lobby.readyCheck) return;
        int built = builtShips();
        boolean synced = built + skippedShips >= lobby.expected && lobby.expected > 0;
        lines.add(lobby.hostName + " (host):  " + lobby.expected + " ships in the battle");
        colors.add(Panel.TEXT);
        for (Lobby.Entry e : lobby.players) {
            lines.add(lobby.describe(e));
            colors.add(!lobby.isSynced(e) ? Panel.WARN : e.skipped > 0 ? Panel.BAD : e.ready ? Panel.GOOD : Panel.TEXT);
        }
        // Choose a ship here, before the battle starts, rather than start as a spectator.
        if (synced) {
            try {
                List<Puppet> ships = pickable();
                if (!ships.isEmpty()) {
                    int at = 0;
                    for (int i = 0; i < ships.size(); i++) if (ships.get(i).netId == pickId) at = i;
                    pickId = ships.get(at).netId;
                    lines.add(ownId >= 0 ? "YOUR SHIP is marked below. Choose another if you like:" : "CHOOSE YOUR SHIP:");
                    colors.add(ownId >= 0 ? Panel.GOOD : Panel.WARN);
                    int from = Math.max(0, Math.min(at - 3, ships.size() - 7)), to = Math.min(ships.size(), from + 7);
                    for (int i = from; i < to; i++) {
                        Puppet pp = ships.get(i);
                        String why = pp.netId == ownId ? "YOURS" : cannotFly(pp);
                        String name = pp.ship.getName() == null ? "" : pp.ship.getName() + ",  ";
                        lines.add((i == at ? ">  " : "    ") + name + pp.ship.getHullSpec().getHullName() + "  ("
                                + String.valueOf(pp.ship.getHullSize()).toLowerCase().replace('_', ' ') + ")" + (why == null ? "" : "     [" + why + "]"));
                        colors.add(pp.netId == ownId ? Panel.GOOD : why != null ? Panel.DIM : Panel.TEXT);
                    }
                    if (ships.size() > 7) { lines.add("    ..." + (at + 1) + " of " + ships.size()); colors.add(Panel.DIM); }
                    if (!pickNote.isEmpty()) { lines.add(pickNote); colors.add(Panel.WARN); }
                }
            } catch (Throwable t) { /* the list is a convenience: Transfer Command still works */ }
        }
        String footer;
        String pick = "     Up/Down: choose a ship     F: take it";
        if (!synced) {
            footer = "Loading the battle: " + built + "/" + lobby.expected + " ships...";
        } else if (skippedShips > 0 && !iAmReady) {
            footer = skippedShips + " ships are missing on your side (mods differ from the host).  Enter: ready anyway" + pick;
        } else if (!iAmReady) {
            footer = "All " + lobby.expected + " ships loaded.  Enter: ready" + pick;
        } else {
            footer = "You are ready. Waiting for the host to start.  Enter: cancel" + pick;
        }
        panel.render("READY CHECK", lines, colors, footer);
    }

    /** The host's battle is paused (as of the last update): shown in large letters, since only the host can unpause. */
    private boolean hostPaused;

    /** Put the player back in the invisible stand-in ship. */
    private void toPlaceholder() {
        if (engine.getPlayerShip() != placeholder) engine.setPlayerShipExternal(placeholder);
    }

    /** Modules whose ship has not been described yet (the host always describes the modules first). */
    private final Map<Integer, ShipSpawn> heldModules = new java.util.LinkedHashMap<Integer, ShipSpawn>();
    private float heldSince;
    private int modulesShown, dronesShown;

    private boolean builtNow;

    private void spawn(ShipSpawn sp) {
        if (puppets.containsKey(sp.netId)) return;
        if (sp.kind == ShipSpawn.MODULE) {
            if (heldModules.isEmpty()) heldSince = clock;
            heldModules.put(sp.netId, sp);
            return;
        }
        boolean isFighter = !sp.wingId.isEmpty();
        ShipAPI ship = null;
        if (isFighter && fighterCount() >= MAX_FIGHTERS) {
            // Guard rail: more fighters than any real battle has. Whatever is making them on the host is not
            // going to bury this PC as well.
            if (capDrops++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: over " + MAX_FIGHTERS + " fighters; further ones are not shown");
        } else {
            ship = isFighter ? null : takeLocalShip(sp);
            if (ship == null) {
                try {
                    ship = isFighter ? spawnFighter(sp) : spawnShip(sp);
                } catch (RuntimeException e) {
                    // A mod's script failed while the ship was being built here (a hullmod reading something a
                    // joiner's game lacks). It is counted as a ship we can't show, like an unknown hull, so the
                    // battle check doesn't ask the host for everything again and again over it.
                    if (spawnErrors.add(sp.hullId)) {
                        Global.getLogger(WingmanClientPlugin.class).warn("Wingman: a mod's script failed while a " + sp.hullId
                                + " was being built on this PC; that ship is not shown here", e);
                    }
                    ship = null;
                }
                if (ship != null && !isFighter && sp.kind == ShipSpawn.SHIP && sp.moduleIds.isEmpty()) builtNow = true;
            }
        }
        if (ship == null) {
            if (!isFighter) skippedShips++;
            skippedIds.put(sp.netId, isFighter);
            for (Integer moduleId : sp.moduleIds) giveUpModule(moduleId); // no ship, so no modules either
            return;
        }
        Puppet made = register(sp, ship);
        if (builtNow) {
            freshlyBuilt.put(made, clock);
            builtNow = false;
        }
        if (sp.kind == ShipSpawn.DRONE) {
            made.drone = true;
            dronesShown++;
            try { ship.setCollisionClass(CollisionClass.FIGHTER); } catch (Throwable t) { /* it may bump its own ship */ }
        }
        // The modules the game built along with it are now matched, slot by slot, to the host's.
        if (!sp.moduleIds.isEmpty()) {
            List<ShipAPI> parts = null;
            try { parts = ship.getChildModulesCopy(); } catch (Throwable t) { /* none */ }
            for (Integer moduleId : sp.moduleIds) {
                ShipSpawn part = heldModules.remove(moduleId);
                if (part == null) continue;
                ShipAPI found = null;
                for (int i = 0; parts != null && i < parts.size(); i++) {
                    ShipAPI c = parts.get(i);
                    try {
                        if (!byShip.containsKey(c) && c.getStationSlot() != null && part.slotId.equals(c.getStationSlot().getId())) { found = c; break; }
                    } catch (Throwable t) { /* not this one */ }
                }
                if (found == null) {
                    skippedShips++;
                    skippedIds.put(moduleId, false);
                    reportMissing("module", part.hullId);
                    continue;
                }
                Puppet mp = register(part, found);
                mp.module = true;
                modulesShown++;
            }
        }
    }

    private void giveUpModule(int moduleId) {
        if (heldModules.remove(moduleId) == null) return;
        skippedShips++;
        skippedIds.put(moduleId, false);
    }

    /** Make a ship in this battle the stand-in for one of the host's: inert, unhurtable, and on our books. */
    private Puppet register(ShipSpawn sp, ShipAPI ship) {
        ship.setShipAI(new PuppetAI());
        makeInvulnerable(ship);
        HullGuard guard = new HullGuard();
        try { ship.addListener(guard); } catch (Throwable t) { /* the damage cut above still applies */ }
        setSide(ship, sp.owner);
        ship.setCRAtDeployment(sp.cr);
        ship.setCurrentCR(sp.cr);

        Puppet pp = new Puppet();
        pp.netId = sp.netId;
        pp.owner = sp.owner;
        pp.info = sp;
        pp.guard = guard;
        pp.fighter = !sp.wingId.isEmpty();
        pp.ship = ship;
        Map<String, WeaponAPI> bySlot = new HashMap<String, WeaponAPI>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() != null) bySlot.put(w.getSlot().getId(), w);
        }
        pp.weapons = new WeaponAPI[sp.weaponOrder.size()];
        for (int i = 0; i < pp.weapons.length; i++) pp.weapons[i] = bySlot.get(sp.weaponOrder.get(i));

        // Numbers check: with the same hull, hullmods and officer the ship should come out the same as the host's.
        // If it doesn't, something still differs (fleet-wide skills, a mod's own script). Max flux is corrected
        // on the spot because the flux bar is drawn against it; the rest is reported in the log.
        if (!pp.fighter && sp.maxFlux > 0f) {
            try {
                float flux = ship.getMaxFlux(), hull = ship.getMaxHitpoints(), speed = ship.getMaxSpeed();
                // Top speed is reported but not judged: it moves with the zero-flux boost and ship systems.
                boolean off = Math.abs(flux - sp.maxFlux) > sp.maxFlux * 0.01f || Math.abs(hull - sp.maxHull) > sp.maxHull * 0.01f;
                if (off) {
                    Dbg.info(WingmanClientPlugin.class, "Wingman stats: " + describe(pp) + " (" + sp.hullId + ") host/here: max flux "
                            + Math.round(sp.maxFlux) + "/" + Math.round(flux) + ", max hull " + Math.round(sp.maxHull) + "/" + Math.round(hull)
                            + ", top speed " + Math.round(sp.maxSpeed) + "/" + Math.round(speed));
                }
                if (Math.abs(flux - sp.maxFlux) > 1f) ship.getMutableStats().getFluxCapacity().modifyFlat("wingman_match", sp.maxFlux - flux);
            } catch (Throwable t) { /* diagnostics only */ }
        }

        // Loadout check: say so in the log if our copy ended up with different weapons from the host's ship.
        if (!pp.fighter) {
            int differ = 0;
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < sp.weaponOrder.size() && i < sp.weaponOrderIds.size(); i++) {
                String slot = sp.weaponOrder.get(i), want = sp.weaponOrderIds.get(i);
                if (slot.isEmpty()) continue;
                WeaponAPI have = bySlot.get(slot);
                String haveId = have == null ? "nothing" : have.getId();
                if (want.equals(haveId) || (want.isEmpty() && have == null)) continue;
                differ++;
                if (differ <= 6) detail.append(differ == 1 ? "" : "; ").append("slot ").append(slot).append(": host ")
                        .append(want.isEmpty() ? "nothing" : want).append(", here ").append(haveId);
            }
            if (differ > 0) {
                Dbg.info(WingmanClientPlugin.class, "Wingman loadout: " + describe(pp) + " (" + sp.hullId + ") differs from the host's in "
                        + differ + " weapon slot(s): " + detail);
            }
        }
        puppets.put(sp.netId, pp);
        byShip.put(ship, pp);
        return pp;
    }

    private int fighterCount() {
        int n = 0;
        for (Puppet pp : puppets.values()) if (pp.fighter) n++;
        return n;
    }

    private void reportMissing(String what, String id) {
        if (missing.contains(id)) return;
        missing.add(id);
        Ui.say(engine, "Co-op: you don't have the " + what + " '" + id + "' (different mods than the host?). "
                + "It will be invisible.");
    }

    /** The loadout of one of the host's ships (or of a module of one), rebuilt from its description. */
    private ShipVariantAPI buildVariant(ShipSpawn sp) {
        ShipHullSpecAPI spec;
        try {
            spec = Global.getSettings().getHullSpec(sp.hullId);
        } catch (Throwable t) {
            spec = null;
        }
        if (spec == null) { reportMissing("ship", sp.hullId); return null; }
        ShipVariantAPI v = Global.getSettings().createEmptyVariant("wingman_" + sp.netId, spec);
        v.setNumFluxVents(sp.vents);
        v.setNumFluxCapacitors(sp.caps);
        // Hullmods, each added the way the host has it: built in with a story point (which gives its bonus),
        // permanent, or ordinary. Their effects then come from the hullmods' own scripts, the same as on the host.
        for (String mod : sp.hullMods) {
            try {
                if (Global.getSettings().getHullModSpec(mod) == null) continue;
                if (sp.sMods.contains(mod)) v.addPermaMod(mod, true);
                else if (sp.permaMods.contains(mod)) v.addPermaMod(mod);
                else v.addMod(mod);
            } catch (Throwable t) { /* hullmod from a mod we don't have */ }
        }
        try {
            for (String mod : sp.sModdedBuiltIns) v.getSModdedBuiltIns().add(mod);
            for (String mod : sp.suppressedMods) v.addSuppressedMod(mod);
        } catch (Throwable t) { /* extras only */ }
        for (int i = 0; i < sp.weaponSlots.size() && i < sp.weaponIds.size(); i++) {
            try {
                if (Global.getSettings().getWeaponSpec(sp.weaponIds.get(i)) != null) {
                    v.addWeapon(sp.weaponSlots.get(i), sp.weaponIds.get(i));
                }
            } catch (Throwable t) { /* weapon from a mod we don't have */ }
        }
        // Everything else the host's ship carries. Some weapons aren't "fitted" in the usual sense: a hullmod or
        // a script puts them into a built-in or special slot when the ship is made (a mod's swappable main gun,
        // for instance). Whatever the reason, if the host has a weapon in a slot and our copy doesn't have
        // the same one there, put it there.
        for (int i = 0; i < sp.weaponOrder.size() && i < sp.weaponOrderIds.size(); i++) {
            String slot = sp.weaponOrder.get(i), id = sp.weaponOrderIds.get(i);
            if (slot.isEmpty() || id.isEmpty()) continue;
            try {
                if (id.equals(v.getWeaponId(slot))) continue;
                if (Global.getSettings().getWeaponSpec(id) == null) continue;
                v.clearSlot(slot);
                v.addWeapon(slot, id);
            } catch (Throwable t) { /* reported by the loadout check after the ship is built */ }
        }

        // Fighter wings, so our copy of a carrier has its fighter controls. The fighters themselves come from
        // the host; anything this copy launches is removed, and its bays are slowed to a crawl.
        try {
            for (int i = 0; i < sp.wings.size(); i++) {
                String wing = sp.wings.get(i);
                if (!wing.isEmpty() && Global.getSettings().getFighterWingSpec(wing) != null) v.setWingId(i, wing);
            }
        } catch (Throwable t) { /* a carrier without fighter controls still flies */ }
        try {
            if (sp.groups.isEmpty()) {
                v.autoGenerateWeaponGroups();
            } else {
                v.getWeaponGroups().clear();
                for (ShipSpawn.Group g : sp.groups) {
                    WeaponGroupSpec group = new WeaponGroupSpec(WeaponGroupType.valueOf(g.type));
                    group.setAutofireOnByDefault(g.autofire);
                    for (String slot : g.slots) group.addSlot(slot);
                    v.addWeaponGroup(group);
                }
            }
        } catch (Throwable t) {
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not copy weapon groups for " + sp.hullId, t);
        }

        return v;
    }

    private ShipAPI spawnShip(ShipSpawn sp) {
        ShipVariantAPI v = buildVariant(sp);
        if (v == null) return null;
        // A multi-part ship: its modules were described just before it. Each goes into its slot now, so the
        // game builds the whole ship in one piece, as it does on the host.
        for (Integer moduleId : sp.moduleIds) {
            ShipSpawn part = heldModules.get(moduleId);
            if (part == null || part.slotId.isEmpty()) continue;
            try {
                ShipVariantAPI pv = buildVariant(part);
                if (pv != null) v.setModuleVariant(part.slotId, pv);
            } catch (Throwable t) { /* the ship is built without that module; it is then counted as missing */ }
        }

        FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
        if (!sp.name.isEmpty()) member.setShipName(sp.name);
        // A ship with no crew counts as 0% combat readiness whatever else is set, so crew it first.
        member.getCrewComposition().setCrew(member.getNeededCrew());
        member.getRepairTracker().setCR(sp.cr);
        member.setOwner(sp.owner);
        // The officer and their skills, which are a large part of what a ship's numbers end up as.
        if (sp.hasCaptain) {
            try {
                member.setCaptain(officerOf(sp));
            } catch (Throwable t) {
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not recreate the officer for " + sp.hullId, t);
            }
        } // a new fleet member defaults to the player's side whichever fleet spawns it
        underCommander(member, sp);
        CampaignStandIn.enlist(member, sp.owner); // a fleet of its own side, for scripts that ask
        CombatFleetManagerAPI fm = engine.getFleetManager(sp.owner);
        fm.setSuppressDeploymentMessages(true);
        ShipAPI ship = fm.spawnFleetMember(member, new Vector2f(sp.x, sp.y), sp.facing, 0f);
        fm.setSuppressDeploymentMessages(false);
        // Arma Armatura sends a strikecraft that needs repairs to a carrier by itself. Here that is the host's
        // decision (a LANDING message says when): its own off switch is set for good on every copy.
        try { if (ship != null) ship.getCustomData().put(WingmanHostPlugin.ARMAA_NO_REFIT, 1e9f); } catch (Throwable t) { /* no such mod: nothing to do */ }
        return ship;
    }

    /**
     * The game can only create fighters a whole wing at a time. We spawn a wing, keep its leader as the puppet
     * for this one fighter and clear the rest away. The fighter is then moved into one shared wing with the
     * other fighters that fly together on the host, so a wing here has the same members as the wing there.
     */
    /** The officer the host's ship has, made afresh here with the same name, face and skills. */
    private static PersonAPI officerOf(ShipSpawn sp) {
        PersonAPI captain = Global.getFactory().createPerson();
        captain.setName(new FullName(sp.captainFirst, sp.captainLast, FullName.Gender.ANY));
        if (!sp.captainPortrait.isEmpty()) captain.setPortraitSprite(sp.captainPortrait);
        if (!sp.captainAiCore.isEmpty()) captain.setAICoreId(sp.captainAiCore);
        captain.getStats().setLevel(sp.captainLevel);
        for (int i = 0; i < sp.skillIds.size(); i++) {
            try {
                if (Global.getSettings().getSkillSpec(sp.skillIds.get(i)) == null) continue;
                captain.getStats().setSkillLevel(sp.skillIds.get(i), sp.skillLevels.get(i));
            } catch (Throwable t) { /* skill from a mod we don't have */ }
        }
        return captain;
    }

    /** Stand-ins for the host's fleet commanders, one per distinct set of skills. */
    private final Map<String, PersonAPI> commanders = new HashMap<String, PersonAPI>();
    private int commandersFailed, underCommand, officeredFighters;

    /**
     * Put the ship under a commander with the fleet-wide skills its fleet's commander has on the host, so
     * that those skills act on this copy as they do on the real ship. Must be done before the ship is made.
     */
    private void underCommander(FleetMemberAPI member, ShipSpawn sp) {
        if (sp.fleetSkillIds.isEmpty()) return;
        try {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < sp.fleetSkillIds.size(); i++) key.append(sp.fleetSkillIds.get(i)).append('=').append(sp.fleetSkillLevels.get(i)).append(';');
            PersonAPI commander = commanders.get(key.toString());
            if (commander == null) {
                commander = Global.getFactory().createPerson();
                for (int i = 0; i < sp.fleetSkillIds.size(); i++) {
                    try {
                        if (Global.getSettings().getSkillSpec(sp.fleetSkillIds.get(i)) == null) continue;
                        commander.getStats().setSkillLevel(sp.fleetSkillIds.get(i), sp.fleetSkillLevels.get(i));
                    } catch (Throwable t) { /* skill from a mod we don't have */ }
                }
                if (commanders.size() > 50) commanders.clear();
                commanders.put(key.toString(), commander);
                Dbg.info(WingmanClientPlugin.class, "Wingman skills: a fleet commander with " + sp.fleetSkillIds.size() + " skills stands over ships here as on the host: " + sp.fleetSkillIds);
            }
            member.setFleetCommanderForStats(commander, null);
            underCommand++;
        } catch (Throwable t) {
            if (commandersFailed++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not give " + sp.hullId
                    + " its fleet commander's skills; its stats are still corrected to the host's", t);
        }
    }

    private ShipAPI spawnFighter(ShipSpawn sp) {
        try {
            if (Global.getSettings().getFighterWingSpec(sp.wingId) == null) throw new IllegalStateException();
        } catch (Throwable t) {
            reportMissing("fighter wing", sp.wingId);
            return null;
        }
        CombatFleetManagerAPI fm = engine.getFleetManager(sp.owner);
        // The wing is asked for as a wing, in so many words. The game's shortcut (spawnShipOrWing) decides
        // "wing or ship?" by whether the name ends in "_wing". Vanilla wings do; many mod wings don't
        // ("uaf_wing_ai_naga"), and those were built as whatever ship the game made of the name instead.
        ShipAPI leader = null;
        PersonAPI officer = null;
        fm.setSuppressDeploymentMessages(true);
        try {
            FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, sp.wingId);
            member.setOwner(sp.owner);
            underCommander(member, sp);
            // A fighter with an officer of its own (some mods make them): the officer goes aboard, so their
            // skills act here and their face shows when the fighter is looked at.
            if (sp.hasCaptain) {
                try { officer = officerOf(sp); member.setCaptain(officer); }
                catch (Throwable t) { /* tried again on the fighter itself below */ }
            }
            leader = fm.spawnFleetMember(member, new Vector2f(sp.x, sp.y), sp.facing, 0f);
        } catch (Throwable t) {
            if (!missing.contains("wingfail:" + sp.wingId)) {
                missing.add("wingfail:" + sp.wingId);
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: the game could not create fighter wing '" + sp.wingId + "'", t);
            }
        }
        fm.setSuppressDeploymentMessages(false);
        if (leader == null) return null;
        // Check that what came out really is this fighter, and say so in the log once per wing type.
        String got = leader.getHullSpec() == null ? "?" : leader.getHullSpec().getHullId();
        boolean right = leader.isFighter() && leader.getWing() != null && (sp.hullId.isEmpty() || sp.hullId.equals(got));
        if (!missing.contains("wingseen:" + sp.wingId)) {
            missing.add("wingseen:" + sp.wingId);
            Dbg.info(WingmanClientPlugin.class, "Wingman fighters: wing '" + sp.wingId + "' is hull '" + sp.hullId + "' on the host, built here as '"
                    + got + "' (" + (right ? "match" : "WRONG, not shown") + ")");
        }
        if (!right) {
            try {
                leader.setShipAI(new PuppetAI());
                if (leader.getWing() != null) {
                    for (ShipAPI m : new ArrayList<ShipAPI>(leader.getWing().getWingMembers())) {
                        m.setShipAI(new PuppetAI());
                        engine.removeEntity(m);
                    }
                    fm.removeDeployed(leader.getWing(), false);
                } else {
                    fm.removeDeployed(leader, false);
                }
                engine.removeEntity(leader);
            } catch (Throwable t) { /* best effort */ }
            reportMissing("fighter", sp.wingId);
            return null;
        }
        // The rest of the wing must be made harmless at once, not on the next frame. Each wingman's own AI
        // steers by asking the wing leader's AI where to fly; our leader is a puppet with no such AI, and a
        // wingman left running for even one frame crashes the game when it asks.
        leader.setShipAI(new PuppetAI());
        try {
            FighterWingAPI wing = leader.getWing();
            if (wing != null) {
                for (ShipAPI m : new ArrayList<ShipAPI>(wing.getWingMembers())) {
                    if (m == leader) continue;
                    m.setShipAI(new PuppetAI());
                    try { wing.removeMember(m); } catch (Throwable t) { /* still inert and about to be removed */ }
                    engine.removeEntity(m);
                }
            }
        } catch (Throwable t) {
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not clear spare wing members for " + sp.wingId, t);
        }
        joinHostWing(leader, sp, fm);
        if (sp.hasCaptain) {
            try {
                if (officer == null) officer = officerOf(sp);
                if (leader.getCaptain() != officer) leader.setCaptain(officer);
                officeredFighters++;
            } catch (Throwable t) {
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not put the officer aboard fighter " + sp.wingId, t);
            }
        }
        return leader;
    }

    /**
     * Put a freshly made fighter into the wing it belongs to on the host. Every member of such a wing is a
     * puppet, so nothing in it ever runs the game's own fighter AI. The one-fighter wing it was created in is
     * emptied and struck from the fleet's deployed list (the game's own swarm launcher does exactly this);
     * left behind, each would linger as a phantom deployed wing.
     */
    private void joinHostWing(ShipAPI fighter, ShipSpawn sp, CombatFleetManagerAPI fm) {
        if (wingsBroken || sp.wingNet < 0) return;
        try {
            FighterWingAPI own = fighter.getWing();
            if (own == null) return;
            FighterWingAPI group = hostWings.get(sp.wingNet);
            if (group != null) { // only join a wing that still has a living member; otherwise start it afresh
                boolean alive = false;
                for (ShipAPI m : group.getWingMembers()) {
                    if (m != null && byShip.containsKey(m) && m.isAlive() && !m.isHulk()) { alive = true; break; }
                }
                if (!alive) group = null;
            }
            if (group == null || group == own) {
                hostWings.put(sp.wingNet, own);
                Puppet carrier = sp.carrierId < 0 ? null : puppets.get(sp.carrierId);
                if (carrier != null && cfg != null && cfg.fighterHangars) BayLink.attach(carrier.ship, sp.bay, own, fighter, fm);
                if (hostWings.size() > 400) { // long battle: forget wings that have no fighters left
                    for (Iterator<FighterWingAPI> it = hostWings.values().iterator(); it.hasNext();) {
                        FighterWingAPI w = it.next();
                        if (w != own && w.getWingMembers().isEmpty()) it.remove();
                    }
                }
                return;
            }
            own.removeMember(fighter);
            fm.removeDeployed(own, false);
            fighter.setWing(group);
            group.addMember(fighter);
        } catch (Throwable t) {
            wingsBroken = true;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: grouping fighters into the host's wings failed; "
                    + "from here on each fighter is shown as a wing of its own", t);
        }
    }

    /**
     * Ships the host has just reported destroyed or gone. A ship can die a moment after launching, and the
     * word that it is gone can overtake its last missiles; those still need to name it as their launcher.
     */
    private final Map<Integer, Puppet> departed = new java.util.LinkedHashMap<Integer, Puppet>();
    private int standIns;

    /** The ship that launched a missile: on the field, or gone within the last twenty seconds. */
    private Puppet launcherShip(int netId) {
        Puppet pp = puppets.get(netId);
        if (pp != null) return pp;
        pp = departed.get(netId);
        if (pp == null) return null;
        if (clock - pp.goneAt > 20f) { departed.remove(netId); return null; }
        return pp;
    }

    /** A missile the host has launched that has no counterpart on this side yet. */
    private static final class PendingMissile {
        int id, sourceId, weaponIndex, owner, wantTarget = -2;
        String weaponId, projId;
        boolean scripted;
        boolean standIn; // built with a borrowed launcher because its own is not on our side
        float x, y, facing, vx, vy, at;
        float flown, maxFlight, arming = -1f; // the host's lifetime numbers for it
    }
    private final List<PendingMissile> pending = new ArrayList<PendingMissile>();
    /** Missiles this game made by itself (a weapon fired, a script released a stage), and when each was first seen. */
    private final Map<MissileAPI, Float> localMissiles = new IdentityHashMap<MissileAPI, Float>();
    private int adopted, builtHere, swapped;
    /** Locally made missiles not yet paired with the host's: the collision class each had before it was switched off. */
    private final Map<MissileAPI, CollisionClass> untouchable = new IdentityHashMap<MissileAPI, CollisionClass>();
    private final Compat compat = Compat.get();
    private final Map<String, Boolean> shotScripted = new HashMap<String, Boolean>();
    /** Shots made here whose weapon has an on-hit script: they pass through everything; the frame each was last seen. */
    private final Map<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]> ghostShots =
            new IdentityHashMap<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]>();
    /** A shot fired here to play out a hit the host reported. */
    private static final class Replay {
        com.fs.starfarer.api.combat.DamagingProjectileAPI shot;
        ShipAPI target;
        float x, y, by;
        boolean onShield;
        String kind;
    }
    private final List<Replay> replays = new ArrayList<Replay>();
    private final Map<com.fs.starfarer.api.combat.DamagingProjectileAPI, Boolean> replayShots =
            new IdentityHashMap<com.fs.starfarer.api.combat.DamagingProjectileAPI, Boolean>();
    private int shotFrame, shotHitsNatural, shotHitsForced;

    /** A hit this game made by itself with a scripted shot (its on-hit script has already run here). */
    private static final class OwnHit {
        String kind;
        ShipAPI from;
        CombatEntityAPI target;
        float at;
    }
    /** A hit the host reported that this game may be just about to make itself: held back for a moment. */
    private static final class HeldHit {
        Puppet from;
        WeaponAPI gun;
        String weaponId, kind;
        int targetId;
        float x, y, facing, until;
        boolean onShield;
    }
    private final List<OwnHit> ownHits = new ArrayList<OwnHit>();
    private final List<HeldHit> heldHits = new ArrayList<HeldHit>();
    private int hitsMatched, hitsCopied, hitsOwnOnly;
    private boolean matchBroken;

    private static final float OWN_HIT_FIRST = 0.25f;

    private float matchWindow() { return cfg == null ? 0.1f : cfg.matchWindowMs / 1000f; }

    /** Make a note of a hit one of this game's own scripted shots has just made. */
    private void noteOwnHit(com.fs.starfarer.api.combat.DamagingProjectileAPI pr, String kind) {
        if (ownHits.size() >= 400) { ownHits.remove(0); hitsOwnOnly++; }
        OwnHit h = new OwnHit();
        h.kind = kind;
        h.from = pr.getSource();
        h.target = pr.getDamageTarget();
        h.at = clock;
        ownHits.add(h);
    }

    /**
     * Has this game itself just made the hit the host is reporting? The same kind of shot from the same ship,
     * on the same target, within the window. Each of our hits answers for one of the host's.
     */
    private boolean claimOwnHit(String kind, ShipAPI from, ShipAPI target) {
        // A hit of ours that came first is given longer than the window: the shots of the ship flown from this PC
        // land here before the host's word of the same hit can have made the trip. Pairing an older hit of ours
        // with a newer one of the host's does no harm, since each still answers for only one.
        float window = matchWindow() + OWN_HIT_FIRST;
        for (int i = 0; i < ownHits.size(); i++) {
            OwnHit h = ownHits.get(i);
            if (clock - h.at > window || !kind.equals(h.kind) || h.from != from) continue;
            // The host names only ships. Anything else it struck matches anything else we struck.
            if (target != null ? !sameShip(h.target, target) : h.target instanceof ShipAPI) continue;
            ownHits.remove(i);
            return true;
        }
        return false;
    }

    /** The same ship, or two parts of the same ship (a module and what it is fixed to). */
    private static boolean sameShip(CombatEntityAPI hit, ShipAPI want) {
        if (hit == want) return true;
        if (!(hit instanceof ShipAPI)) return false;
        ShipAPI a = (ShipAPI) hit;
        try {
            ShipAPI pa = a.getParentStation() != null ? a.getParentStation() : a;
            ShipAPI pw = want.getParentStation() != null ? want.getParentStation() : want;
            return pa == pw;
        } catch (Throwable t) { return false; }
    }

    /**
     * One of this game's own shots of that kind from that ship that could still make the hit: in flight, not
     * yet spent, and near enough to the host's point of impact to get there within the window.
     */
    private com.fs.starfarer.api.combat.DamagingProjectileAPI shotAboutToHit(String kind, ShipAPI from, float x, float y) {
        com.fs.starfarer.api.combat.DamagingProjectileAPI best = null;
        float bestD = Float.MAX_VALUE, window = matchWindow();
        for (Map.Entry<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]> e : ghostShots.entrySet()) {
            com.fs.starfarer.api.combat.DamagingProjectileAPI pr = e.getKey();
            int[] stamp = e.getValue();
            if (stamp[1] != 0 || stamp[0] != shotFrame || pr.getSource() != from || !kind.equals(pr.getProjectileSpecId())) continue;
            float dx = pr.getLocation().x - x, dy = pr.getLocation().y - y, d = dx * dx + dy * dy;
            float vx = pr.getVelocity().x, vy = pr.getVelocity().y;
            float reach = (float) Math.sqrt(vx * vx + vy * vy) * window + 120f;
            if (d > reach * reach || d >= bestD) continue;
            bestD = d;
            best = pr;
        }
        return best;
    }

    /**
     * Shots from guns with an on-hit script are the host's to judge, like missiles. The ones this game fires
     * are made to pass through everything and are taken away as they reach a ship, so they neither hit nor
     * set off their script by themselves. When the host reports that one struck, a fresh shot is fired from
     * the same gun a hair short of the host's point of impact (see SHOT_HIT), and that one makes the hit.
     */
    private void ghostScriptedShots() {
        if (cfg == null || !cfg.hostDecidesScriptedShots) return;
        boolean match = cfg.matchHits() && !matchBroken;
        // Going through every shot in flight is the costly part. Shots made to pass through things must be
        // caught the frame they appear; shots only being watched for a hit can be looked at every other frame
        // (one that hits and vanishes in between is still noticed, by its absence).
        if (!match || (frame & 1) == 0) scanScriptedShots(match);
        settleHeldHits();
        playReplays();
    }

    private void scanScriptedShots(boolean match) {
        shotFrame++;
        List<com.fs.starfarer.api.combat.DamagingProjectileAPI> all = engine.getProjectiles();
        List<com.fs.starfarer.api.combat.DamagingProjectileAPI> spent = null;
        for (int i = 0, n = all.size(); i < n; i++) {
            com.fs.starfarer.api.combat.DamagingProjectileAPI pr = all.get(i);
            if (pr instanceof MissileAPI || replayShots.containsKey(pr)) continue;
            String kind = pr.getProjectileSpecId();
            if (kind == null) continue;
            Boolean scripted = shotScripted.get(kind);
            if (scripted == null) {
                boolean has = false;
                try {
                    com.fs.starfarer.api.loading.ProjectileSpecAPI spec = pr.getProjectileSpec();
                    String cls = spec == null ? null : spec.getOnHitClassName();
                    has = cls != null && !cls.trim().isEmpty();
                } catch (Throwable t) { /* an ordinary shot */ }
                scripted = has;
                shotScripted.put(kind, scripted);
            }
            if (!scripted) continue;
            int[] stamp = ghostShots.get(pr);
            if (stamp == null) {
                // { frame last seen, 0 = watched and left to hit by itself / 1 = its hit is noted / 2 = made to pass through }
                ghostShots.put(pr, new int[] { shotFrame, match ? 0 : 2 });
                if (!match) try { pr.setCollisionClass(CollisionClass.NONE); } catch (Throwable t) { /* it may hit by itself */ }
                continue;
            }
            stamp[0] = shotFrame;
            if (stamp[1] != 2) { // left to hit by itself: note when it does, for comparing with the host's word
                if (stamp[1] == 0 && pr.didDamage()) { stamp[1] = 1; noteOwnHit(pr, kind); }
                continue;
            }
            // Reached a ship of the other side: this is where it would have hit. Take it away; if it really
            // hit on the host, the host's word brings the hit.
            float px = pr.getLocation().x, py = pr.getLocation().y;
            for (Puppet pp : puppets.values()) {
                if (pp.owner == pr.getOwner()) continue;
                ShipAPI ship = pp.ship;
                float dx = ship.getLocation().x - px, dy = ship.getLocation().y - py;
                ShieldAPI sh = ship.getShield();
                float r = sh != null && sh.isOn() ? sh.getRadius() : ship.getCollisionRadius() * 0.6f;
                if (dx * dx + dy * dy > r * r) continue;
                if (spent == null) spent = new ArrayList<com.fs.starfarer.api.combat.DamagingProjectileAPI>();
                spent.add(pr);
                break;
            }
        }
        if (spent != null) for (com.fs.starfarer.api.combat.DamagingProjectileAPI pr : spent) { ghostShots.remove(pr); engine.removeEntity(pr); }
        if (match || (ghostShots.size() > 64 && (shotFrame & 31) == 0)) { // forget the ones that have left the field
            for (Iterator<Map.Entry<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]>> it = ghostShots.entrySet().iterator(); it.hasNext();) {
                Map.Entry<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]> e = it.next();
                int[] stamp = e.getValue();
                if (stamp[0] == shotFrame) continue;
                // Gone between two frames: if it went by hitting something, that is a hit of ours all the same.
                try {
                    if (stamp[1] == 0 && e.getKey().didDamage()) noteOwnHit(e.getKey(), e.getKey().getProjectileSpecId());
                } catch (Throwable t) { /* that one goes unnoted */ }
                it.remove();
            }
        }
    }

    private void playReplays() {
        for (Iterator<Replay> it = replays.iterator(); it.hasNext();) {
            Replay r = it.next();
            if (!engine.isEntityInPlay(r.shot)) { // the game made the hit
                it.remove();
                replayShots.remove(r.shot);
                shotHitsNatural++;
                compat.good("shot:" + r.kind);
                continue;
            }
            if (clock < r.by) continue;
            it.remove();
            replayShots.remove(r.shot);
            shotHitsForced++;
            compat.odd("shot:" + r.kind);
            try { // carried out by hand at the host's exact point
                com.fs.starfarer.api.loading.ProjectileSpecAPI spec = r.shot.getProjectileSpec();
                com.fs.starfarer.api.combat.OnHitEffectPlugin script = spec == null ? null : spec.getOnHitEffect();
                Vector2f point = new Vector2f(r.x, r.y);
                r.shot.getLocation().set(r.x, r.y);
                // The host only names ships. If it struck something else (a missile, an asteroid), the script
                // is given whatever of that sort is at the spot here; with nothing there, there is nothing to hit.
                CombatEntityAPI struck = r.target != null ? r.target : nearestSmallThing(r.x, r.y, 90f);
                if (script != null && struck != null) script.onHit(r.shot, struck, point, r.onShield, new NoDamage(), engine);
            } catch (Throwable t) {
                compat.odd("script:" + r.kind);
                if (!hitScriptWarned) {
                    hitScriptWarned = true;
                    Global.getLogger(WingmanClientPlugin.class).warn("Wingman: an on-hit script failed when run for a hit the host reported (" + r.kind + ")", t);
                }
            }
            engine.removeEntity(r.shot);
        }
    }

    /**
     * Hits the host reported that were held back because a shot of ours looked about to make them. Each is
     * settled as soon as ours lands, or played out from the host's report once the window has passed.
     */
    private void settleHeldHits() {
        for (int i = ownHits.size() - 1; i >= 0; i--) { // ours that the host never reported: nothing to undo, just counted
            if (clock - ownHits.get(i).at > matchWindow() + OWN_HIT_FIRST + 0.05f) { ownHits.remove(i); hitsOwnOnly++; }
        }
        if (heldHits.isEmpty()) return;
        try {
            for (Iterator<HeldHit> it = heldHits.iterator(); it.hasNext();) {
                HeldHit h = it.next();
                Puppet target = puppets.get(h.targetId);
                if (claimOwnHit(h.kind, h.from.ship, target == null ? null : target.ship)) {
                    it.remove();
                    hitsMatched++;
                    compat.good("shot:" + h.kind);
                    continue;
                }
                if (clock < h.until) continue;
                it.remove();
                // Ours has not landed in time. Take it away so the hit is not seen twice, and show the host's.
                com.fs.starfarer.api.combat.DamagingProjectileAPI late = shotAboutToHit(h.kind, h.from.ship, h.x, h.y);
                if (late != null) { ghostShots.remove(late); engine.removeEntity(late); }
                hitsCopied++;
                fireReplay(h.from, h.gun, h.weaponId, h.kind, h.targetId, h.x, h.y, h.facing, h.onShield);
            }
        } catch (Throwable t) {
            matchBroken = true;
            heldHits.clear();
            ownHits.clear();
            Global.getLogger(WingmanClientPlugin.class).error("Wingman: comparing this game's scripted hits with the host's failed; "
                    + "for the rest of this battle only the host's hits are shown", t);
        }
    }

    private void replayShot(In in) throws Exception {
        int fromId = in.readInt(), weaponIndex = in.readShort();
        String weaponId = in.readUTF(), kind = in.readUTF();
        int targetId = in.readInt();
        float x = in.readFloat(), y = in.readFloat(), facing = in.readFloat();
        boolean onShield = in.readBoolean();
        if (cfg == null || !cfg.hostDecidesScriptedShots || replays.size() >= 150) return;
        if (Snapshot.bad(x) || Snapshot.bad(y) || Snapshot.bad(facing)) return;
        Puppet from = launcherShip(fromId);
        if (from == null) return;
        WeaponAPI gun = weaponIndex >= 0 && weaponIndex < from.weapons.length ? from.weapons[weaponIndex] : null;
        if (gun == null || !weaponId.equals(gun.getId())) {
            gun = null;
            for (WeaponAPI w : from.ship.getAllWeapons()) if (weaponId.equals(w.getId())) { gun = w; break; }
        }
        if (gun == null) return;
        if (cfg.matchHits() && !matchBroken) {
            try {
                // Did this game make the same hit itself a moment ago? Then its script has run here already.
                Puppet target = puppets.get(targetId);
                if (claimOwnHit(kind, from.ship, target == null ? null : target.ship)) {
                    hitsMatched++;
                    compat.good("shot:" + kind);
                    return;
                }
                // Is a shot of ours just about to? Then give it the length of the window to do so.
                if (matchWindow() > 0f && heldHits.size() < 300 && shotAboutToHit(kind, from.ship, x, y) != null) {
                    HeldHit h = new HeldHit();
                    h.from = from; h.gun = gun; h.weaponId = weaponId; h.kind = kind; h.targetId = targetId;
                    h.x = x; h.y = y; h.facing = facing; h.onShield = onShield;
                    h.until = clock + matchWindow();
                    heldHits.add(h);
                    return;
                }
                hitsCopied++; // neither: the host's hit is played out here as reported
            } catch (Throwable t) {
                matchBroken = true;
                Global.getLogger(WingmanClientPlugin.class).error("Wingman: comparing this game's scripted hits with the host's failed; "
                        + "for the rest of this battle only the host's hits are shown", t);
            }
        }
        fireReplay(from, gun, weaponId, kind, targetId, x, y, facing, onShield);
    }

    /** Fire a fresh shot a hair short of where the host's struck, so the game makes the hit and runs its script. */
    private void fireReplay(Puppet from, WeaponAPI gun, String weaponId, String kind, int targetId, float x, float y, float facing, boolean onShield) {
        if (replays.size() >= 150) return;
        float dx = (float) Math.cos(Math.toRadians(facing)), dy = (float) Math.sin(Math.toRadians(facing));
        CombatEntityAPI e;
        try {
            e = engine.spawnProjectile(from.ship, gun, weaponId, new Vector2f(x - dx * 8f, y - dy * 8f), facing, new Vector2f(0f, 0f));
        } catch (Throwable t) {
            compat.odd("shot:" + kind);
            return;
        }
        if (!(e instanceof com.fs.starfarer.api.combat.DamagingProjectileAPI) || e instanceof MissileAPI) {
            if (e != null) engine.removeEntity(e);
            return;
        }
        Replay r = new Replay();
        r.shot = (com.fs.starfarer.api.combat.DamagingProjectileAPI) e;
        Puppet target = puppets.get(targetId);
        r.target = target == null ? null : target.ship;
        r.x = x; r.y = y; r.onShield = onShield; r.kind = kind;
        r.by = clock + 0.1f;
        replays.add(r);
        replayShots.put(r.shot, Boolean.TRUE);
    }

    /** A destroyed ship's wreck, or a piece that has come off one. The host says where it is. */
    private static final class Wreck {
        ShipAPI ship;
        HullGuard guard;
        Snapshot.ShipState target;
        float targetAt;
        boolean sealed;
        ShipSpawn info;   // how to build the ship it was, should the wreck be lost here (whole wrecks only)
        boolean split;    // it has broken up, or is about to: no longer the whole ship
        boolean rebuilt;
        float goneAt;
    }
    private final Map<Integer, Wreck> wrecks = new HashMap<Integer, Wreck>();
    /** Wrecks and pieces the host has that could not be made here. Known about, so not counted as drift. */
    private final Set<Integer> lostWrecks = new HashSet<Integer>();
    /** Break-ups the host has reported and we have yet to carry out: { wreck, new piece, tries }; for each, { next try, give up } in battle time. */
    private final List<int[]> pendingSplits = new ArrayList<int[]>();
    private final List<float[]> splitDue = new ArrayList<float[]>();
    private int wreckSplitsDone, wreckSplitsFailed;
    private boolean wrecksBroken;

    /**
     * Wrecks follow the host. A wreck here never breaks up or is blown apart by itself (see makeInvulnerable
     * and the sealing below); it breaks when the host's does, and each piece then goes where the host's goes.
     */
    private void followWrecks(float amount) {
        if (wrecksBroken) return;
        try {
            for (int i = pendingSplits.size() - 1; i >= 0; i--) {
                int[] split = pendingSplits.get(i);
                float[] due = splitDue.get(i);
                if (clock < due[0]) continue;
                Wreck parent = wrecks.get(split[0]);
                // (not ready: its ship has not finished dying on our screen yet)
                boolean readyToBreak = parent != null && engine.isEntityInPlay(parent.ship) && parent.ship.isHulk();
                ShipAPI piece = null;
                if (readyToBreak) {
                    parent.split = true;
                    try { piece = parent.ship.splitShip(); } catch (Throwable t) { /* tried again later */ }
                    if (piece == null) split[2]++; // the game found no line to break along this time
                }
                if (piece == null) {
                    boolean hopeless = clock > due[1] || (parent == null && lostWrecks.contains(split[0]));
                    if (hopeless) {
                        pendingSplits.remove(i);
                        splitDue.remove(i);
                        wreckSplitsFailed++;
                        lostWrecks.add(split[1]);
                    } else {
                        due[0] = clock + (!readyToBreak ? 0.1f : split[2] < 6 ? 0f : 2f); // a quick burst, then every two seconds
                    }
                    continue;
                }
                pendingSplits.remove(i);
                splitDue.remove(i);
                wreckSplitsDone++;
                Wreck w = new Wreck();
                w.ship = piece;
                w.split = true;
                lostWrecks.remove(split[1]);
                wrecks.put(split[1], w);
            }
            for (Map.Entry<Integer, Wreck> entry : wrecks.entrySet()) {
                Wreck w = entry.getValue();
                ShipAPI ship = w.ship;
                if (!engine.isEntityInPlay(ship)) {
                    // Gone here, still there on the host (or we would have been told). A whole wreck can be put
                    // back, once: the ship is built again where the host's wreck is and quietly made a wreck.
                    if (w.info == null || w.split || w.rebuilt) continue;
                    if (System.currentTimeMillis() - lastSnapshotAt > 2000) continue; // the host has gone quiet: nothing to keep up with
                    if (w.goneAt == 0f) { w.goneAt = clock; continue; }
                    if (clock - w.goneAt < 1f) continue;
                    w.rebuilt = true;
                    rebuildWreck(entry.getKey(), w);
                    continue;
                }
                if (!ship.isHulk()) continue;
                if (!w.sealed) { // now that it is a wreck, nothing on this PC may damage or break it
                    w.sealed = true;
                    try {
                        MutableShipStatsAPI stats = ship.getMutableStats();
                        stats.getHullDamageTakenMult().modifyMult(NO_DAMAGE, 0f);
                        stats.getArmorDamageTakenMult().modifyMult(NO_DAMAGE, 0f);
                        stats.getBreakProb().modifyMult(NO_DAMAGE, 0f);
                        if (w.guard != null) w.guard.standAside = false; else ship.addListener(new HullGuard());
                    } catch (Throwable t) { /* it may then be shot apart here while the host's remains */ }
                }
                Snapshot.ShipState t = w.target;
                if (t == null) continue;
                float age = Math.max(0f, Math.min(5f, clock - w.targetAt));
                float tx = t.x + t.vx * age, ty = t.y + t.vy * age;
                Vector2f loc = ship.getLocation();
                float dx = tx - loc.x, dy = ty - loc.y;
                float k = Math.min(1f, amount * 8f);
                if (amount <= 0f || dx * dx + dy * dy > 400f * 400f) k = 1f;
                loc.set(loc.x + dx * k, loc.y + dy * k);
                ship.getVelocity().set(t.vx, t.vy);
                ship.setFacing(ship.getFacing() + shortestTurn(ship.getFacing(), t.facing + t.angVel * age) * k);
                ship.setAngularVelocity(t.angVel);
            }
        } catch (Throwable t) {
            wrecksBroken = true;
            Global.getLogger(WingmanClientPlugin.class).error("Wingman: following the host's wrecks failed; wrecks drift on their own for the rest of this battle", t);
        }
    }

    private int wrecksRebuilt;

    /** The missile or asteroid nearest a point, within reach of it; null if there is none. */
    private CombatEntityAPI nearestSmallThing(float x, float y, float reach) {
        CombatEntityAPI best = null;
        float bestD = reach * reach;
        for (MissileAPI m : engine.getMissiles()) {
            float dx = m.getLocation().x - x, dy = m.getLocation().y - y, d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = m; }
        }
        for (CombatEntityAPI a : engine.getAsteroids()) {
            float dx = a.getLocation().x - x, dy = a.getLocation().y - y, d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = a; }
        }
        return best;
    }

    private void rebuildWreck(int id, Wreck w) {
        if (puppets.containsKey(id)) return; // can't be: a wreck's number is never a live ship's
        if (w.target != null) { w.info.x = w.target.x; w.info.y = w.target.y; w.info.facing = w.target.facing; }
        int skippedBefore = skippedShips;
        spawn(w.info);
        Puppet pp = puppets.remove(id);
        if (pp == null) { // could not be built: it stays a wreck we know of and can't show
            skippedIds.remove(id);
            skippedShips = skippedBefore;
            return;
        }
        byShip.remove(pp.ship);
        pp.guard.standAside = true;
        try { pp.ship.setHulkChanceOverride(1f); } catch (Throwable t) { /* the game decides */ }
        try { pp.ship.setExplosionScale(0f); } catch (Throwable t) { /* it goes off once */ }
        destroy(pp.ship);
        w.ship = pp.ship;
        w.guard = pp.guard;
        w.sealed = false;
        w.goneAt = 0f;
        if (wrecksRebuilt++ < 5) Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: the wreck of " + w.info.hullId
                + " was lost here but is still there on the host; put back");
    }

    /** Missiles playing out a hit the host reported. */
    private final List<MissilePuppet> striking = new ArrayList<MissilePuppet>();
    private int hitsNatural, hitsForced;
    private boolean hitScriptWarned;

    /**
     * The host says this missile has struck something. Our copy is put a hair short of the host's point of
     * impact, flying at it, with its collisions switched back on, so that the game itself makes the hit on the
     * next frame: the proper explosion, the proper sound, and the weapon's own on-hit script, all at the place
     * and moment the host's happened. If the game has not made the hit a moment later (the two copies of the
     * target never line up to the pixel), it is carried out by hand at the host's exact point.
     */
    private void strike(MissilePuppet mp, int targetId, float x, float y, float facing, float vx, float vy, boolean onShield) {
        MissileAPI m = mp.missile;
        Puppet target = puppets.get(targetId);
        float dx = vx, dy = vy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f && target != null) { // hardly moving: head for the target itself
            dx = target.ship.getLocation().x - x;
            dy = target.ship.getLocation().y - y;
            len = (float) Math.sqrt(dx * dx + dy * dy);
        }
        if (len < 1f) { dx = (float) Math.cos(Math.toRadians(facing)); dy = (float) Math.sin(Math.toRadians(facing)); len = 1f; }
        dx /= len; dy /= len;
        float speed = Math.max(300f, (float) Math.sqrt(vx * vx + vy * vy));
        m.getLocation().set(x - dx * 6f, y - dy * 6f);
        m.getVelocity().set(dx * speed, dy * speed);
        m.setFacing(facing);
        try {
            m.setCollisionClass(mp.collision != null && mp.collision != CollisionClass.NONE ? mp.collision : CollisionClass.MISSILE_NO_FF);
            m.setArmingTime(0f);
        } catch (Throwable t) { /* the hit is then carried out by hand */ }
        mp.target = null; // no more steering from the host
        mp.hitX = x; mp.hitY = y; mp.hitShield = onShield;
        mp.hitTarget = target == null ? null : target.ship;
        mp.hitBy = clock + 0.1f;
        striking.add(mp);
    }

    /** Each frame: hits the game has made are done; ones it has not made in time are carried out by hand. */
    private void finishStrikes() {
        for (Iterator<MissilePuppet> it = striking.iterator(); it.hasNext();) {
            MissilePuppet mp = it.next();
            MissileAPI m = mp.missile;
            if (!engine.isEntityInPlay(m)) { // the game made the hit
                it.remove();
                byMissile.remove(m);
                hitsNatural++;
                if (mp.launch != null) compat.good("missile:" + mp.launch.projId);
                continue;
            }
            if (clock < mp.hitBy) continue;
            it.remove();
            byMissile.remove(m);
            hitsForced++;
            if (mp.launch != null) compat.odd("missile:" + mp.launch.projId);
            Vector2f point = new Vector2f(mp.hitX, mp.hitY);
            try {
                m.getLocation().set(point.x, point.y);
                com.fs.starfarer.api.loading.MissileSpecAPI spec = m.getSpec();
                com.fs.starfarer.api.combat.OnHitEffectPlugin script = spec == null ? null : spec.getOnHitEffect();
                // As for shots: the host only names ships. Something else (a missile, an asteroid, a ship not
                // shown here) is whatever of that sort is at the spot; with nothing there, on-hit scripts (which
                // expect a target) are not run.
                CombatEntityAPI struck = mp.hitTarget != null ? mp.hitTarget : nearestSmallThing(point.x, point.y, 90f);
                if (script != null && struck != null) {
                    try {
                        script.onHit(m, struck, point, mp.hitShield, new NoDamage(), engine);
                    } catch (Throwable t) {
                        if (!hitScriptWarned) {
                            hitScriptWarned = true;
                            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: an on-hit script failed when run for a hit the host reported: "
                                    + script.getClass().getName(), t);
                        }
                    }
                }
                com.fs.starfarer.api.loading.DamagingExplosionSpec blast = spec == null ? null : spec.getExplosionSpec();
                if (blast != null) engine.spawnDamagingExplosion(blast, m.getSource(), point);
                else if (spec != null && spec.getExplosionColor() != null) {
                    engine.spawnExplosion(point, new Vector2f(0f, 0f), spec.getExplosionColor(), Math.max(20f, spec.getExplosionRadius()), 0.6f);
                }
            } catch (Throwable t) {
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not show a missile hit the host reported", t);
            }
            engine.removeEntity(m);
        }
    }
    /** How long a missile made here waits for the host to confirm it, and how long a host missile waits for ours. */
    private static final float LOCAL_GRACE = 0.5f, PENDING_WAIT = 0.25f;

    /**
     * The host has launched a missile. On this side the same launch normally happens by itself a moment
     * earlier or later: our copy of the weapon fires, or our copy of a missile's script releases its next
     * stage. That locally made missile is the one to keep, because everything the mod's scripts did at launch
     * (its on-fire effect, the brain a script gave it, effects that follow it) belongs to it. So the host's
     * missile is first matched to one made here; only if none turns up in time is a plain copy built instead.
     */
    private void spawnMissile(In in) throws Exception {
        PendingMissile p = new PendingMissile();
        p.id = in.readInt();
        p.weaponId = in.readUTF();
        p.sourceId = in.readInt();
        p.weaponIndex = in.readShort();
        p.projId = in.readUTF();
        p.scripted = in.readBoolean();
        p.owner = in.readByte();
        p.x = in.readFloat(); p.y = in.readFloat(); p.facing = in.readFloat();
        p.vx = in.readFloat(); p.vy = in.readFloat();
        p.flown = in.readFloat(); p.maxFlight = in.readFloat(); p.arming = in.readFloat();
        p.at = clock;
        if (missiles.containsKey(p.id)) return;
        for (PendingMissile q : pending) if (q.id == p.id) return;
        if (missiles.size() + pending.size() >= MAX_MISSILES) { // guard rail, as for fighters
            if (capDrops++ == 0) Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: over " + MAX_MISSILES + " missiles; further ones are not shown");
            return;
        }
        try {
            if (Global.getSettings().getWeaponSpec(p.weaponId) == null) throw new IllegalStateException();
        } catch (Throwable t) {
            reportMissing("weapon", p.weaponId);
            return;
        }
        if (!adopt(p)) pending.add(p);
    }

    private static String makeOf(MissileAPI m) {
        try {
            String id = m.getProjectileSpecId();
            return id == null ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Is this locally made missile the same launch as that host missile? Returns how far apart they are, or -1. */
    private float sameLaunch(MissileAPI m, PendingMissile p, float px, float py) {
        if (byMissile.containsKey(m) || !engine.isEntityInPlay(m)) return -1f;
        if (!p.projId.isEmpty() && !p.projId.equals(makeOf(m))) return -1f;
        Puppet source = launcherShip(p.sourceId);
        if (m.getSource() != (source == null ? null : source.ship)) return -1f;
        float dx = m.getLocation().x - px, dy = m.getLocation().y - py;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        return d > 700f ? -1f : d;
    }

    private boolean adopt(PendingMissile p) {
        float age = clock - p.at;
        float px = p.x + p.vx * age, py = p.y + p.vy * age;
        MissileAPI best = null;
        float bestDist = 0f;
        for (MissileAPI m : localMissiles.keySet()) {
            float d = sameLaunch(m, p, px, py);
            if (d < 0f || (best != null && d >= bestDist)) continue;
            best = m;
            bestDist = d;
        }
        if (best == null) return false;
        localMissiles.remove(best);
        launchSettled(best, true);
        adopted++;
        settle(p, best, false);
        return true;
    }

    /** Each frame: confirm locally made missiles against the host's, build the ones nobody made, drop the unconfirmed. */
    private void matchMissiles() {
        // Newly seen local missiles. One that shows up after a plain copy was already built for the same launch
        // takes that copy's place: it is the one the mod's scripts actually worked on.
        for (int i = recentBuilt.size() - 1; i >= 0; i--) {
            MissilePuppet mp = recentBuilt.get(i);
            if (!mp.builtHere || clock - mp.builtAt > 1f) recentBuilt.remove(i);
        }
        List<MissileAPI> all = engine.getMissiles();
        List<MissilePuppet> swapOut = null; // applied after the loop: the game's list must not change while we walk it
        List<MissileAPI> swapIn = null;
        for (int mi = 0, count = all.size(); mi < count; mi++) {
            MissileAPI m = all.get(mi);
            if (byMissile.containsKey(m) || localMissiles.containsKey(m)) continue;
            MissilePuppet late = null;
            for (MissilePuppet mp : recentBuilt) { // only copies built in the last second can be swapped
                if (!mp.builtHere || clock - mp.builtAt > 1f || mp.launch == null) continue;
                if (!engine.isEntityInPlay(mp.missile)) continue;
                if (sameLaunchAs(m, mp)) { late = mp; break; }
            }
            if (late != null) {
                if (swapOut == null) { swapOut = new ArrayList<MissilePuppet>(); swapIn = new ArrayList<MissileAPI>(); }
                late.builtHere = false; // spoken for: not offered to another missile in this pass
                swapOut.add(late);
                swapIn.add(m);
                continue;
            }
            localMissiles.put(m, clock);
            noteLaunch(m);
            try { // not yet confirmed by the host: it must not hit anything in the meantime either
                untouchable.put(m, m.getCollisionClass());
                m.setCollisionClass(CollisionClass.NONE);
            } catch (Throwable t) { /* see takeOver */ }
        }
        for (int i = 0; swapOut != null && i < swapOut.size(); i++) {
            MissilePuppet late = swapOut.get(i);
            byMissile.remove(late.missile);
            engine.removeEntity(late.missile);
            swapped++;
            takeOver(late, swapIn.get(i), late.launch);
        }
        int builtThisFrame = 0;
        for (Iterator<PendingMissile> it = pending.iterator(); it.hasNext();) {
            PendingMissile p = it.next();
            if (adopt(p)) { it.remove(); continue; }
            if (clock - p.at < PENDING_WAIT) continue;
            if (++builtThisFrame > 30) continue; // guard rail: a flood of copies is spread over a few frames
            it.remove();
            build(p);
        }
        List<MissileAPI> drop = null;
        for (Map.Entry<MissileAPI, Float> e : localMissiles.entrySet()) {
            MissileAPI m = e.getKey();
            boolean gone = !engine.isEntityInPlay(m);
            if (!gone && clock - e.getValue() < LOCAL_GRACE) continue;
            if (drop == null) drop = new ArrayList<MissileAPI>();
            drop.add(m);
            if (!gone) { engine.removeEntity(m); straysRemoved++; } // the host never launched this one
        }
        if (drop != null) for (MissileAPI m : drop) { localMissiles.remove(m); untouchable.remove(m); launchSettled(m, false); }
    }

    /**
     * Guard rail against a launcher here firing a missile the host's never fired. A copy of a ship fires when
     * the host says its weapon is firing, but "is firing" lasts a little longer than one shot, and a launcher
     * that can fire again within that time lets a second missile go. That one has no counterpart on the host:
     * it flies out, is cleared away as a stray, and the ammo count jumps back. So each launcher is only
     * allowed a missile or two that the host has not yet confirmed, and one whose missile turned out to be a
     * stray stays quiet until the host's weapon has stopped firing.
     */
    private final Map<MissileAPI, WeaponAPI> launchedBy = new IdentityHashMap<MissileAPI, WeaponAPI>();
    private final Map<WeaponAPI, int[]> unconfirmed = new IdentityHashMap<WeaponAPI, int[]>();
    private final Map<WeaponAPI, Boolean> silenced = new IdentityHashMap<WeaponAPI, Boolean>();
    private int launchesHeld, launchesRefuted;

    private void noteLaunch(MissileAPI m) {
        try {
            WeaponAPI w = m.getWeapon();
            if (w == null) return;
            launchedBy.put(m, w);
            int[] n = unconfirmed.get(w);
            if (n == null) { n = new int[1]; unconfirmed.put(w, n); }
            n[0]++;
            if (launchedBy.size() > 4000) { launchedBy.clear(); unconfirmed.clear(); } // never expected; start clean
        } catch (Throwable t) { /* this launch is simply not counted */ }
    }

    private void launchSettled(MissileAPI m, boolean confirmed) {
        WeaponAPI w = launchedBy.remove(m);
        if (w == null) return;
        int[] n = unconfirmed.get(w);
        if (n != null && --n[0] <= 0) unconfirmed.remove(w);
        if (!confirmed) { silenced.put(w, Boolean.TRUE); launchesRefuted++; }
    }

    /** May this launcher on a copy of a ship fire now, given that the host says its weapon is firing? */
    private boolean mayLaunch(WeaponAPI w) {
        if (silenced.containsKey(w)) { launchesHeld++; return false; }
        int[] n = unconfirmed.get(w);
        if (n == null) return true;
        int allowed = 3;
        try { if (w.usesAmmo() && w.getMaxAmmo() <= 12) allowed = 1; } catch (Throwable t) { /* three */ }
        if (n[0] < allowed) return true;
        launchesHeld++;
        return false;
    }

    private boolean sameLaunchAs(MissileAPI m, MissilePuppet mp) {
        PendingMissile p = mp.launch;
        if (!p.projId.isEmpty() && !p.projId.equals(makeOf(m))) return false;
        Puppet source = launcherShip(p.sourceId);
        if (m.getSource() != (source == null ? null : source.ship)) return false;
        float dx = m.getLocation().x - mp.missile.getLocation().x, dy = m.getLocation().y - mp.missile.getLocation().y;
        return dx * dx + dy * dy < 700f * 700f;
    }

    /** Nobody on this side made the missile: build a copy of the host's, as if fired by the same weapon. */
    private void build(PendingMissile p) {
        // Some missiles (two-stage ones for a start) ask "which weapon launched me?" as they are created, and
        // the game stops with an error if the answer is "none".
        Puppet source = launcherShip(p.sourceId);
        WeaponAPI weapon = null;
        if (source != null) {
            if (p.weaponIndex >= 0 && p.weaponIndex < source.weapons.length) weapon = source.weapons[p.weaponIndex];
            // A second stage is a different missile from the one its launcher is loaded with, so the launcher
            // is found by its place on the ship first, by name second, and failing both any weapon will do.
            if (weapon == null) {
                List<WeaponAPI> all = source.ship.getAllWeapons();
                for (WeaponAPI w : all) {
                    if (p.weaponId.equals(w.getId())) { weapon = w; break; }
                }
                if (weapon == null && !all.isEmpty()) weapon = all.get(0);
            }
        }
        // Its launcher is nowhere on our side (it died before we joined, say), but some missiles cannot be made
        // without naming one. Borrow a launcher: the same weapon on another ship of that side if there is
        // one, otherwise any weapon of that side. The missile then flies as a plain copy of the host's.
        if (weapon == null) {
            WeaponAPI any = null;
            Puppet anyShip = null;
            for (Puppet pp : puppets.values()) {
                if (pp.owner != p.owner || pp.fighter) continue;
                for (WeaponAPI w : pp.ship.getAllWeapons()) {
                    if (p.weaponId.equals(w.getId())) { weapon = w; source = pp; break; }
                    if (any == null) { any = w; anyShip = pp; }
                }
                if (weapon != null) break;
            }
            if (weapon == null && any != null) { weapon = any; source = anyShip; }
            if (weapon != null) { p.standIn = true; standIns++; }
        }
        // Built by its exact make where the host named one: a warhead released by another missile is its own
        // kind of missile, and going by the launcher alone would rebuild the missile that carried it.
        float age = clock - p.at;
        Vector2f at = new Vector2f(p.x + p.vx * age, p.y + p.vy * age);
        CombatEntityAPI e = null;
        ShipAPI ship = source == null ? null : source.ship;
        Throwable failure = null;
        if (!p.projId.isEmpty()) {
            try {
                e = engine.spawnProjectile(ship, weapon, p.weaponId, p.projId, at, p.facing, new Vector2f(0f, 0f));
            } catch (Throwable t) {
                failure = t;
            }
            if (e != null && !(e instanceof MissileAPI)) { engine.removeEntity(e); e = null; }
        }
        if (e == null) {
            try {
                e = engine.spawnProjectile(ship, weapon, p.weaponId, at, p.facing, new Vector2f(0f, 0f));
            } catch (Throwable t) {
                failure = t;
            }
        }
        if (e == null && failure != null) {
            // No launcher to name (its ship is not on our side of the link) and this missile insists on one.
            missilesUnbuilt++;
            compat.odd("missile:" + p.projId);
            if (!missing.contains("missile:" + p.weaponId + p.projId)) {
                missing.add("missile:" + p.weaponId + p.projId);
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not build a '" + p.projId + "' missile of weapon '" + p.weaponId + "' here (launcher "
                        + (weapon == null ? "not found" : "found") + ", ship " + (source == null ? "not found" : "found") + "): " + failure);
            }
            return;
        }
        if (!(e instanceof MissileAPI)) { // the weapon fires something that isn't a missile here
            if (e != null) engine.removeEntity(e);
            return;
        }
        builtHere++;
        settle(p, (MissileAPI) e, true);
    }

    private void settle(PendingMissile p, MissileAPI missile, boolean built) {
        MissilePuppet mp = new MissilePuppet();
        mp.launch = p;
        mp.builtHere = built;
        mp.builtAt = clock;
        mp.wantTarget = p.wantTarget;
        if (built) recentBuilt.add(mp);
        missiles.put(p.id, mp);
        takeOver(mp, missile, p);
    }

    /** Make this missile the stand-in for the host's: the host says where it is, its own script (if any) does the rest. */
    private void takeOver(MissilePuppet mp, MissileAPI missile, PendingMissile p) {
        mp.missile = missile;
        missile.setOwner(p.owner);
        // On this side a missile never hits anything by itself. Whether it hits, what, and exactly where is the
        // host's to say (see MISSILE_HIT); until then it passes through everything.
        CollisionClass was = untouchable.remove(missile);
        try {
            mp.collision = was != null ? was : missile.getCollisionClass();
            missile.setCollisionClass(CollisionClass.NONE);
        } catch (Throwable t) { /* it may then go off early on our screen; the host's version is put back */ }
        // A missile with a script of its own (a mod's, or the game's splitting warheads) keeps that script
        // running here, so the flash, sound and debris the script makes are the real ones. It needs the ship
        // and launcher it was fired from; without them it is a plain puppet like any other missile.
        boolean own = false;
        if (p.scripted && !p.standIn && missile.getSource() != null && missile.getWeapon() != null) {
            try {
                MissileAIPlugin ai = missile.getUnwrappedMissileAI();
                if (ai == null) ai = missile.getMissileAI();
                if (ai != null && !(ai instanceof GuardedMissileAI)) {
                    String cn = ai.getClass().getName();
                    boolean plain = cn.startsWith("com.fs.starfarer.combat.ai.") && !cn.contains("Mirv");
                    if (plain) throw new IllegalStateException(); // nothing special is steering this copy
                    // The game's own missile brains are left exactly as they are; a mod's is wrapped so that an
                    // error inside it switches that one script off instead of stopping the game.
                    if (!cn.startsWith("com.fs.starfarer.combat.")) missile.setMissileAI(new GuardedMissileAI(missile, ai));
                    own = true;
                    if (!missing.contains("script:" + cn)) {
                        missing.add("script:" + cn);
                        Dbg.info(WingmanClientPlugin.class, "Wingman missiles: '" + p.projId + "' runs its own script here (" + cn + ")");
                    }
                }
            } catch (Throwable t) {
                own = false;
            }
        }
        mp.scripted = own;
        // A launcher with a mod's script on it usually looks after its own shots: it lists each one as it is
        // fired and decides how it ends (a timed fuse, sticking to a hull, a special burst). Such a missile is
        // "tended": its flight clock is kept exactly on the host's so the script's timing comes out the same,
        // and when the host's copy is ended by that script, ours is left to its own copy of the script.
        mp.hostMaxFlight = p.maxFlight;
        mp.tended = false;
        try {
            WeaponAPI launcher = p.standIn ? null : missile.getWeapon();
            Object tender = launcher == null ? null : launcher.getEffectPlugin();
            if (tender != null && !tender.getClass().getName().startsWith("com.fs.starfarer.")) {
                mp.tended = true;
                // A copy we built was never "fired", so the script has not been told about it. Tell it.
                if (mp.builtHere && tender instanceof com.fs.starfarer.api.combat.OnFireEffectPlugin) {
                    ((com.fs.starfarer.api.combat.OnFireEffectPlugin) tender).onFire(missile, launcher, engine);
                    tendedTold++;
                }
                if (!missing.contains("tended:" + p.projId)) {
                    missing.add("tended:" + p.projId);
                    Dbg.info(WingmanClientPlugin.class, "Wingman missiles: '" + p.projId + "' is looked after by its launcher's script ("
                            + tender.getClass().getName() + "); its ending is left to that script");
                }
            }
        } catch (Throwable t) {
            compat.odd("script:" + p.projId);
        }
        if (!own) {
            try {
                missile.setMissileAI(new PuppetMissileAI(missile)); // the host steers; see PuppetMissileAI
            } catch (Throwable t) { /* falls back to easing against the local guidance */ }
        }
        if (mp.builtHere) missile.getVelocity().set(p.vx, p.vy);
        // Lifetime is the host's: how long it can fly, how long it has flown, and when it arms.
        try {
            if (p.maxFlight > 0f && Math.abs(missile.getMaxFlightTime() - p.maxFlight) > 0.05f) missile.setMaxFlightTime(p.maxFlight);
            missile.setFlightTime(Math.max(0f, p.flown + (clock - p.at)));
            if (p.arming >= 0f && mp.builtHere) missile.setArmingTime(p.arming);
        } catch (Throwable t) { /* older game: its own clock runs */ }
        byMissile.put(missile, mp);
    }

    /**
     * Armor and EMP damage. Local hits can't cause either (see makeInvulnerable), so what the ship shows is
     * exactly what the host reports: armor cell by cell, and which weapons and engines are knocked out.
     */
    private void applyDamage(Puppet pp, boolean withArmor) {
        if (damageBroken) return;
        try {
            DamageState.Ship d = pp.damage;
            ShipAPI ship = pp.ship;
            for (int i = 0; i < pp.weapons.length && i < 64; i++) {
                WeaponAPI w = pp.weapons[i];
                if (w == null) continue;
                boolean want = (d.weaponsDisabled & (1L << i)) != 0;
                if (want && !w.isDisabled()) w.disable();
                else if (!want && w.isDisabled()) w.repair();
                // Ammo is the host's count. Our own shots only look like they use ammo; the real ones are fired there.
                if (i < d.ammo.length && d.ammo[i] >= 0 && w.usesAmmo() && w.getAmmo() != d.ammo[i]) w.setAmmo(d.ammo[i]);
            }
            ShipEngineControllerAPI ec = ship.getEngineController();
            List<ShipEngineControllerAPI.ShipEngineAPI> engines = ec == null ? null : ec.getShipEngines();
            if (engines != null) {
                if (d.flamedOut && !ec.isFlamedOut()) ec.forceFlameout(true);
                for (int i = 0; i < engines.size() && i < 32; i++) {
                    ShipEngineControllerAPI.ShipEngineAPI e = engines.get(i);
                    boolean want = d.flamedOut || (d.enginesDisabled & (1 << i)) != 0;
                    if (want && !e.isDisabled()) e.disable();
                    else if (!want && e.isDisabled()) e.repair();
                }
            }
            if (!withArmor) return;
            ArmorGridAPI grid = ship.getArmorGrid();
            float[][] cells = grid == null ? null : grid.getGrid();
            if (cells == null || cells.length != d.gridWidth || cells.length == 0 || cells[0].length != d.gridHeight) return;
            float max = grid.getMaxArmorInCell();
            boolean changed = false;
            for (int x = 0; x < d.gridWidth; x++) {
                for (int y = 0; y < d.gridHeight; y++) {
                    float want = (d.armor[x * d.gridHeight + y] & 0xFF) / 255f * max;
                    if (Math.abs(cells[x][y] - want) <= max / 255f) continue;
                    grid.setArmorValue(x, y, want);
                    changed = true;
                }
            }
            if (changed) {
                ship.syncWithArmorGridState();
                ship.syncWeaponDecalsWithArmorDamage();
            }
        } catch (Throwable t) {
            damageBroken = true;
            Global.getLogger(WingmanClientPlugin.class).error("Wingman: showing armor/EMP damage failed, that sync is off for this battle", t);
        }
    }

    /**
     * Guard rail: a ship the host still has must exist here, alive. If the local game has destroyed or dropped
     * one anyway (some script, an explosion that slipped through), it is built again from the host's
     * description and, if it was ours, we are put back at its helm. Checked twice a second.
     */
    private void rescueLostShips() {
        long now = System.currentTimeMillis();
        if (now - lastRescueMillis < 500) return;
        lastRescueMillis = now;
        List<Puppet> lost = null;
        for (Puppet pp : puppets.values()) {
            if (pp.info == null || pp.module) continue; // (a module can't be built by itself)
            if (!pp.ship.isHulk() && pp.ship.isAlive() && engine.isEntityInPlay(pp.ship)) continue;
            if (lost == null) lost = new ArrayList<Puppet>();
            if (lost.size() >= 8) break; // a few per pass; the rest on the next one
            lost.add(pp);
        }
        if (lost == null) return;
        for (Puppet old : lost) {
            if (old.rebuilds >= 3) continue; // something keeps killing it; don't fight forever
            boolean wasOurs = old.netId == ownId && (engine.getPlayerShip() == old.ship || engine.getPlayerShip() == placeholder);
            puppets.remove(old.netId);
            byShip.remove(old.ship);
            if (engine.getPlayerShip() == old.ship) toPlaceholder();
            if (engine.isEntityInPlay(old.ship)) engine.removeEntity(old.ship);
            if (old.target != null) { old.info.x = old.target.x; old.info.y = old.target.y; old.info.facing = old.target.facing; }
            spawn(old.info);
            Puppet fresh = puppets.get(old.netId);
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: " + old.info.hullId + " '" + old.info.name
                    + "' was lost or destroyed here but is alive on the host; " + (fresh != null ? "rebuilt" : "could not be rebuilt"));
            if (fresh == null) { // could not be rebuilt: keep the old record and stop trying
                old.rebuilds = 3;
                skippedIds.remove(old.netId); // it is still counted as a ship we have, not one we never built
                puppets.put(old.netId, old);
                byShip.put(old.ship, old);
                continue;
            }
            fresh.rebuilds = old.rebuilds + 1;
            fresh.target = old.target;
            fresh.targetAt = old.targetAt;
            fresh.angles = old.angles;
            fresh.damage = old.damage;
            if (fresh.damage != null) applyDamage(fresh, true);
            if (wasOurs) {
                engine.setPlayerShipExternal(fresh.ship);
                lastPlayerShip = fresh.ship; // same ship as far as the host is concerned: no new claim needed
            }
        }
    }

    /** Put a puppet on the side the host says it is on (0 = the players, 1 = the enemy). */
    private static void setSide(ShipAPI ship, int owner) {
        if (ship.getOwner() != owner) ship.setOwner(owner);
        if (ship.getOriginalOwner() != owner) ship.setOriginalOwner(owner);
        try {
            if (ship.getWing() != null && ship.getWing().getWingOwner() != owner) ship.getWing().setWingOwner(owner);
        } catch (Throwable t) { /* wing bookkeeping is a nicety */ }
    }

    /**
     * Local hits must never change anything: only the host decides damage. Armor and EMP damage are switched
     * off outright. Hull damage is cut to one ten-thousandth instead of zero, because the game treats a ship
     * that takes exactly zero hull damage as "not targetable": no hover info, no R targeting, and autofire
     * ignores it. What little gets through is overwritten by the host's hull value every frame anyway.
     */
    private static void makeInvulnerable(ShipAPI ship) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        stats.getHullDamageTakenMult().modifyMult(NO_DAMAGE, HULL_TRICKLE);
        stats.getArmorDamageTakenMult().modifyMult(NO_DAMAGE, 0f);
        stats.getEmpDamageTakenMult().modifyMult(NO_DAMAGE, 0f);
        try { // a wreck here breaks up when the host's does, never by itself
            stats.getBreakProb().modifyMult(NO_DAMAGE, 0f);
        } catch (Throwable t) { /* older game: wrecks may break up on their own */ }
        try {
            stats.getFighterRefitTimeMult().modifyMult(NO_DAMAGE, 100000f);
        } catch (Throwable t) { /* only reduces flicker at the launch bays */ }
    }

    private void destroy(ShipAPI ship) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        stats.getHullDamageTakenMult().unmodify(NO_DAMAGE);
        stats.getArmorDamageTakenMult().unmodify(NO_DAMAGE);
        engine.applyDamage(ship, ship.getLocation(), 10000000f, DamageType.HIGH_EXPLOSIVE, 0f, true, false, null);
    }

    private void keepPlaceholderOutOfTheWay() {
        if (placeholder == null) return;
        Puppet follow = puppets.get(hostFlagship);
        Vector2f loc = placeholder.getLocation();
        if (follow != null) { // ride along with the host so the camera shows the fight and ships are in reach
            loc.set(follow.ship.getLocation().x, follow.ship.getLocation().y);
        }
        placeholder.getVelocity().set(0f, 0f);
        placeholder.setAlphaMult(0f);
        placeholder.setHoldFireOneFrame(true);
        placeholder.blockCommandForOneFrame(ShipCommand.FIRE);
        placeholder.blockCommandForOneFrame(ShipCommand.ACCELERATE);
        placeholder.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        placeholder.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
        placeholder.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
    }

    private boolean systemsBroken;
    private static final ShipSystemAPI.SystemState[] STAGES = ShipSystemAPI.SystemState.values();

    private static boolean running(ShipSystemAPI.SystemState s) {
        return s == ShipSystemAPI.SystemState.IN || s == ShipSystemAPI.SystemState.ACTIVE;
    }

    /**
     * Bring one system on our copy of a ship into the stage the host's is in. Left alone while the two agree,
     * so the system runs through its stages by its own clock. A copy that is running while the host's is not
     * (or the reverse) is corrected at once; smaller differences (charging up here, already active there) are
     * given a quarter of a second to sort themselves out first. On the ship in our own hands the system answers
     * to our own keys, and is only corrected if the host still disagrees after most of a second, which is what
     * happens when the real ship could not do what we asked. Returns how long the two have disagreed.
     */
    private float followSystem(ShipAPI ship, ShipSystemAPI sys, int hostStage, float hostLevel, boolean own, float apart, float amount) {
        if (sys == null) return 0f;
        ShipSystemAPI.SystemState want = STAGES[Math.max(0, Math.min(STAGES.length - 1, hostStage))];
        ShipSystemAPI.SystemState have = sys.getState();
        if (have == want) return 0f;
        // Idle and cooling down both mean "at rest". Our copy runs its own cooldown, which need not end on the
        // same frame as the host's, and forcing one into the other only makes them trade places again.
        boolean restHave = have == ShipSystemAPI.SystemState.IDLE || have == ShipSystemAPI.SystemState.COOLDOWN;
        boolean restWant = want == ShipSystemAPI.SystemState.IDLE || want == ShipSystemAPI.SystemState.COOLDOWN;
        if (restHave && restWant) return 0f;
        apart += amount;
        boolean opposite = running(have) != running(want);
        float patience = own ? 0.8f : opposite ? 0f : 0.25f;
        if (apart < patience) return apart;
        float progress = want == ShipSystemAPI.SystemState.IN ? hostLevel
                : want == ShipSystemAPI.SystemState.OUT ? 1f - hostLevel : 0f;
        // A mod's system that has not proved itself here yet is noted before it is switched on; one that took
        // the game down before is left off (see SystemWatch).
        if (running(want) && !watch.allow(sys, cfg == null ? null : cfg.neverMirrorSystems)) return 0f;
        if (ship != null && restHave) {
            // Starting a ship system from rest. Pushing it straight into "running" skips whatever the system
            // does when it is really switched on, and some need that: a teleporter works out where it is going
            // at that moment, and without it the game itself fails a frame later. So the system is switched on
            // the proper way first. If it still has not started a moment later (this copy's flux or cooldown
            // stood in the way), it is pushed, so that whatever the host's ship is doing always shows here,
            // stage by stage. Only a teleporter is never pushed.
            if (!running(want)) return 0f; // the host's is already winding down: nothing worth starting here
            // A teleporter on someone else's ship is not run here at all: this copy can't know where the real
            // one is going, and would jump somewhere else first. It simply appears where the host's does.
            if (!own && teleports(sys)) return 0f;
            // Drones are the host's, like fighters: its drones are shown here as copies, so a drone system on
            // someone else's ship is not run here at all and launches nothing of its own.
            if (!own && sys instanceof com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI) return 0f;
            boolean tryNow = apart - amount < patience || (int) (apart * 2f) != (int) ((apart - amount) * 2f); // at once, then twice a second
            if (!own && tryNow) {
                try {
                    if (sys.getCooldownRemaining() > 0f) sys.setCooldownRemaining(0f);
                    if (sys.getMaxAmmo() > 0 && sys.getAmmo() < 1) sys.setAmmo(1);
                    ship.useSystem();
                    systemsStarted++;
                } catch (Throwable th) { /* it stays off here */ }
            }
            if (teleports(sys) || apart < patience + 0.3f) return apart;
        }
        if (running(have) && restWant) {
            // Stopping one that runs here while the host's has stopped: switched off the proper way first, so it
            // winds down and starts its real cooldown, as on the host. Some mods count on that cooldown: UAF's
            // Isenoria keeps its pilot in a Rafflesya only while Rafflesya Control is on or cooling down.
            boolean tryNow = apart - amount < patience || (int) (apart * 2f) != (int) ((apart - amount) * 2f);
            if (tryNow) {
                try { sys.deactivate(); systemsStopped++; } catch (Throwable th) { /* pushed below */ }
            }
            if (apart < patience + 0.3f) return apart;
        }
        sys.forceState(want, Math.max(0f, Math.min(1f, progress)));
        // A cooldown pushed in from outside may start with nothing left of it, which reads as idle at once. The
        // host's has only just begun: give this copy its full length, which then runs down by its own clock.
        if (want == ShipSystemAPI.SystemState.COOLDOWN) {
            try { if (sys.getCooldownRemaining() <= 0f && sys.getCooldown() > 0f) sys.setCooldownRemaining(sys.getCooldown()); }
            catch (Throwable th) { /* left as the game made it */ }
        }
        if (own) sysForcedAt = clock; // our doing, not the player's: not to be counted as a use
        systemsForced++;
        return 0f;
    }
    private int systemsForced, systemsStarted, systemsStopped;

    // ---- How a teleport looks on someone else's ship ----
    // A teleporter (vanilla Phase Skimmer, mods' blinks) is never run on a copy of someone else's ship: the copy
    // can't know where the real one is going, and the game fails if one is pushed into its stages. So none of its
    // look appeared either: the copy simply turned up where the host's was. It is drawn here instead, from the
    // system's own description: its shimmer while the host's system runs, its sound when it starts, and a fading
    // image left where the ship jumped from.
    private static final Object BLINK = new Object();
    private static final java.awt.Color BLINK_COLOR = new java.awt.Color(150, 110, 255, 170);
    private boolean blinkBroken;

    private void teleportLook(Puppet pp, Snapshot.ShipState t) {
        if (blinkBroken) return;
        try {
            ShipSystemAPI sys = pp.ship.getSystem();
            if (sys == null || !teleports(sys)) return;
            boolean on = t.system >= 1 && t.system <= 3; // charging up, on, or charging down on the host
            com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = sys.getSpecAPI();
            if (on) {
                if (!pp.blinking) {
                    String sound = spec == null ? null : spec.getUseSound();
                    if (sound != null && !sound.isEmpty()) Global.getSoundPlayer().playSound(sound, 1f, 1f, pp.ship.getLocation(), pp.ship.getVelocity());
                }
                java.awt.Color c = spec == null ? null : spec.getJitterEffectColor();
                if (c == null && spec != null) c = spec.getEffectColor1();
                if (c == null) c = BLINK_COLOR;
                int copies = spec == null || spec.getJitterCopies() <= 0 ? 5 : spec.getJitterCopies();
                float range = spec == null || spec.getJitterRange() <= 0f ? 10f : spec.getJitterRange();
                float level = t.system == 2 ? 1f : Math.max(0.2f, t.systemLevel);
                pp.ship.setJitter(BLINK, c, level, copies, range);
                pp.blinkSeen = clock;
            }
            pp.blinking = on;
        } catch (Throwable th) {
            blinkBroken = true;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: teleport effects on other ships can't be drawn here", th);
        }
    }

    /** The copy of a teleporting ship has just been moved to where the host's arrived: leave an image where it was. */
    private void teleportTrail(Puppet pp, float offX, float offY) {
        if (blinkBroken || offX * offX + offY * offY < 100f * 100f) return;
        try {
            ShipSystemAPI sys = pp.ship.getSystem();
            if (sys == null || !teleports(sys)) return; // some other jump (a script moved it): nothing to draw
            com.fs.starfarer.api.combat.ShipSystemSpecAPI spec = sys.getSpecAPI();
            java.awt.Color c = spec == null ? null : spec.getJitterEffectColor();
            if (c == null && spec != null) c = spec.getEffectColor1();
            if (c == null) c = BLINK_COLOR;
            // offset from where the ship now is, standing still, fading out over half a second
            pp.ship.addAfterimage(c, offX, offY, 0f, 0f, 5f, 0f, 0.1f, 0.5f, true, true, false);
            if (clock - pp.blinkSeen > 1f) { // its system's stages were missed (a short one): its sound at least
                String sound = spec == null ? null : spec.getUseSound();
                if (sound != null && !sound.isEmpty()) Global.getSoundPlayer().playSound(sound, 1f, 1f, pp.ship.getLocation(), pp.ship.getVelocity());
            }
            blinksDrawn++;
        } catch (Throwable th) {
            blinkBroken = true;
            Global.getLogger(WingmanClientPlugin.class).warn("Wingman: teleport effects on other ships can't be drawn here", th);
        }
    }
    private int blinksDrawn;
    private final LocalDamage localDamage = new LocalDamage();
    private boolean statsBroken, hurtsBroken, damageListening;
    private int frame, statFixes, notesApplied, hurtsSeen, hurtsPlayed, shotsMadeUp, shotsHeld;
    private long statFixedMask;
    private int scriptEndsSeen, scriptEndsLocal, scriptEndsPlain, tendedTold;
    private final Set<String> plainEndWarned = new HashSet<String>();
    /** Classes of ship system that teleport the ship: learned from the game's own teleporters as they are met. */
    private final Set<String> teleporters = new HashSet<String>(java.util.Arrays.asList("com.fs.starfarer.combat.systems.int"));

    /**
     * Does this system move the ship by teleporting it? Such a system works out where it is going at the
     * moment it is really switched on, so it must never be pushed into a stage (the game fails if it is).
     * Told from the system's own description (its "type"), with the game's class for it as a second opinion.
     */
    private boolean teleports(ShipSystemAPI sys) {
        try {
            String id = sys.getId();
            Boolean known = id == null ? null : teleportsById.get(id);
            if (known != null) return known;
            boolean yes = teleporters.contains(sys.getClass().getName());
            try {
                // The game's own kinds are PHASE_DISPLACER (Phase Skimmer) and PHASE_TELEPORTER; mods may use those or others.
                String type = sys.getSpecAPI().getSpecJson().optString("type", "").toUpperCase(java.util.Locale.ROOT);
                if (type.contains("DISPLACER") || type.contains("TELEPORTER")) yes = true;
            } catch (Throwable th) { /* the class name decides */ }
            if (yes) teleporters.add(sys.getClass().getName());
            if (id != null) teleportsById.put(id, yes);
            return yes;
        } catch (Throwable th) {
            return true; // can't tell: treat it as the kind that must not be pushed
        }
    }
    private final Map<String, Boolean> teleportsById = new HashMap<String, Boolean>();
    /**
     * How fast the host's battle is really running compared with ours, 1 = the same. A host whose frame rate is
     * low runs its battle slower than the clock (the game slows down rather than skip ahead); if ours kept
     * running at full speed, everything here would race ahead of the host between updates and be hauled back
     * at each one, the faster the thing the worse. So our battle is run at the host's real pace.
     */
    private float pace = 1f, loggedPace = 1f;
    private long paceWall;
    private float paceHost, paceClock, paceMult;
    private boolean paceBroken;

    private void keepPace(Snapshot snap) {
        if (paceBroken) return;
        try {
            long wall = System.nanoTime();
            boolean restart = paceWall == 0 || snap.paused || engine.isPaused() || Math.abs(hostTimeMult - paceMult) > 0.001f
                    || wall - paceWall > 3000000000L || snap.hostTime < paceHost;
            if (restart) { paceWall = wall; paceHost = snap.hostTime; paceClock = clock; paceMult = hostTimeMult; return; }
            if (wall - paceWall < 500000000L) return; // judged over half a second at a time
            float hostRan = snap.hostTime - paceHost, weRan = clock - paceClock;
            paceWall = wall; paceHost = snap.hostTime; paceClock = clock;
            if (weRan < 0.05f || hostRan < 0.02f) return;
            float ratio = hostRan / weRan;                 // below 1: the host's battle is running slower than ours
            if (Math.abs(ratio - 1f) < 0.03f) return;      // near enough; don't fidget
            pace = Math.max(0.25f, Math.min(1.1f, pace * (1f + (ratio - 1f) * 0.7f)));
            if (Math.abs(pace - loggedPace) > 0.1f) {
                loggedPace = pace;
                Dbg.info(WingmanClientPlugin.class, "Wingman pace: this battle is now run at " + Math.round(pace * 100f) + "% speed to keep time with the host's ("
                        + (pace < 1f ? "the host's battle is running slow, usually a low frame rate there" : "this PC's battle was falling behind the host's") + ")");
            }
        } catch (Throwable t) {
            paceBroken = true;
            pace = 1f;
        }
    }

    // How late each of the last few updates was (see SNAPSHOT).
    private final float[] gaps = new float[8];
    private int gapNext, gapCount, lateN;
    private float gapMin, lateSum;
    // Lining our own ship up with the real one (see reconcile()).
    private int ownSeq, ackSeq;
    private float ackAge;
    private boolean ackFresh, reconReady;
    private final float[] seqClock = new float[256];
    private final int[] seqNumber = new int[256];
    private final float[] pastT = new float[256], pastX = new float[256], pastY = new float[256];
    private int pastNext, pastCount;
    private float reconX, reconY;
    private Puppet reconShip;
    private int reconChecks, reconMisses;

    /**
     * Where the ship in our hands really is, compared like with like. The host says which of our controls the
     * real ship last acted on and for how long. Our copy acted on those same controls from the moment we sent
     * them, so the real ship's position is compared with where our copy was that long after sending them, not
     * with where it is now. What is left is the true difference between the two (a collision that went
     * differently, a speed that isn't quite the same), with the delay of the link taken out of it.
     * Returns false if there is nothing to compare yet.
     */
    private boolean reconcile(Puppet pp, Snapshot.ShipState t) {
        if (pp != reconShip) { reconShip = pp; pastCount = 0; pastNext = 0; reconReady = false; ackFresh = false; }
        Vector2f loc = pp.ship.getLocation();
        pastT[pastNext] = clock; pastX[pastNext] = loc.x; pastY[pastNext] = loc.y;
        pastNext = (pastNext + 1) & 255;
        if (pastCount < 256) pastCount++;
        if (!ackFresh) return reconReady;
        ackFresh = false;
        int slot = ackSeq & 255;
        if ((seqNumber[slot] & 0xFFFF) != ackSeq) { reconMisses++; return reconReady; }
        float when = seqClock[slot] + ackAge;
        // our copy's position at that moment, from the record of the last few seconds
        int newest = (pastNext - 1) & 255, oldest = (pastNext - pastCount) & 255;
        if (when < pastT[oldest]) { reconMisses++; return reconReady; }
        float x = pastX[newest], y = pastY[newest];
        for (int n = 0; n < pastCount - 1; n++) {
            int b = (newest - n) & 255, a = (b - 1) & 255;
            if (when > pastT[b]) break;       // later than the newest record: use the newest
            if (when < pastT[a]) continue;
            float span = pastT[b] - pastT[a], f = span <= 0f ? 1f : (when - pastT[a]) / span;
            x = pastX[a] + (pastX[b] - pastX[a]) * f;
            y = pastY[a] + (pastY[b] - pastY[a]) * f;
            break;
        }
        reconX = t.x - x;
        reconY = t.y - y;
        reconReady = true;
        reconChecks++;
        return true;
    }

    /** Our copy has been moved by this much to follow the real ship: the record of where it was moves with it. */
    private void shiftPast(float sx, float sy) {
        for (int i = 0; i < 256; i++) { pastX[i] += sx; pastY[i] += sy; }
        reconX -= sx;
        reconY -= sy;
    }
    /** "syncTolerance" from the settings: 1 is normal, 0 follows the host's figures exactly, more is looser. */
    private float slack = 1f;

    /**
     * How much of a gap to the host's figure to close this frame. Within the tolerance: next to nothing (the
     * gap just fades over a second or so), so small differences never show as shaking. Beyond it: the excess is
     * closed quickly. While paused nothing moves, so a gap beyond the tolerance is simply closed.
     */
    /** How long a copy is carried on along its acceleration before going on in a straight line, in seconds. */
    private static final float ACC_REACH = 0.25f;

    private static float closing(float err, float tol, float amount, float rate) {
        if (err <= 0.001f) return 0f;
        if (amount <= 0f) return err > tol ? 1f : 0f;
        float fade = Math.min(1f, amount);
        if (err <= tol) return fade;
        return Math.min(1f, fade + Math.min(1f, amount * rate) * ((err - tol) / err));
    }
    /** How far the ship in our hands is from the real one on the host: for the log. */
    private float ownWorst, ownErrSum;
    private int ownErrN, ownSnaps;

    private static float shortestTurn(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private void apply(Puppet pp, boolean own, float amount) {
        Snapshot.ShipState t = pp.target;
        if (t == null) return;
        ShipAPI ship = pp.ship;
        if (pp.probing) return;
        if (ship.getOwner() != pp.owner) setSide(ship, pp.owner);
        // The host only repeats a ship that is doing nothing new twice a second, so the last state can be up to
        // half a second old. Battle time, not wall-clock time: it stops with a pause and follows the battle speed.
        float age = Math.max(0f, Math.min(1f, clock - pp.targetAt));

        // Predict where the host's ship is by now, and ease toward it (snap if far off). A fighter turns and
        // darts far more sharply than a ship, so it is held much closer to the host's position. It is carried
        // on along the curve it is on (its acceleration, as the update format works it out), for a quarter of a
        // second at most; beyond that in a straight line.
        float aa = Math.min(age, ACC_REACH);
        float tx = t.x + (t.vx + 0.5f * t.ax * aa) * age, ty = t.y + (t.vy + 0.5f * t.ay * aa) * age;
        Vector2f loc = ship.getLocation();
        float dx = tx - loc.x, dy = ty - loc.y;
        float k = Math.min(1f, amount * (pp.fighter ? 30f : 12f));
        if (pp.module) {
            // carried by the ship it is part of: this game puts it where its slot is
        } else if (own && cfg != null && cfg.predictOwnShip) {
            // The ship in our hands moves under our own controls, here and now, by this game's own physics:
            // thrust, strafing and the ship system all answer at once. The real ship on the host does the same
            // a moment later, and what comes back from it is used only to keep the two together. A gap that
            // the delay alone explains is left alone; beyond that our copy is drawn toward the real one, gently
            // for a small gap and firmly for a large one (the real ship was blocked, shoved or stopped).
            float err = (float) Math.sqrt(dx * dx + dy * dy);
            float hostSpeed = (float) Math.sqrt(t.vx * t.vx + t.vy * t.vy);
            float slack = 15f + hostSpeed * 0.12f;
            Vector2f vel = ship.getVelocity();
            if (reconcile(pp, t)) {
                // The true difference is known: follow that, and only that.
                dx = reconX; dy = reconY;
                err = (float) Math.sqrt(dx * dx + dy * dy);
                slack = (3f + hostSpeed * 0.02f) * this.slack;
                if (err > 700f) {
                    loc.set(loc.x + dx, loc.y + dy);
                    vel.set(t.vx, t.vy);
                    shiftPast(dx, dy);
                    ownSnaps++;
                } else if (amount > 0f && err > slack) {
                    float over = err - slack;
                    float pull = Math.min(1f, amount * (4f + Math.min(8f, over / 25f))) * (over / err);
                    float sx = dx * pull, sy = dy * pull;
                    loc.set(loc.x + sx, loc.y + sy);
                    shiftPast(sx, sy);
                    if (over > 60f) {
                        float b = Math.min(1f, amount * 5f);
                        vel.set(vel.x + (t.vx - vel.x) * b, vel.y + (t.vy - vel.y) * b);
                    }
                }
            } else if (err > 700f) {
                loc.set(tx, ty);
                vel.set(t.vx, t.vy);
                ownSnaps++;
            } else if (amount > 0f && err > slack) {
                float over = err - slack;
                float pull = Math.min(1f, amount * (3f + Math.min(9f, over / 25f))) * (over / err);
                loc.set(loc.x + dx * pull, loc.y + dy * pull);
                if (over > 60f) { // well apart: our speed is not the real ship's, so stop adding to the gap
                    float b = Math.min(1f, amount * 5f);
                    vel.set(vel.x + (t.vx - vel.x) * b, vel.y + (t.vy - vel.y) * b);
                }
            }
            if (err > ownWorst) ownWorst = err;
            ownErrSum += err;
            ownErrN++;
        } else {
            // Every other ship and fighter is carried along by this game's own physics at the speed the host
            // gave, and its position is only corrected for what lies beyond a tolerance (see closing()).
            // Following the host's every figure exactly makes things shake, because updates never arrive
            // perfectly evenly: a fast fighter is then a little ahead one moment and a little behind the next.
            float err = (float) Math.sqrt(dx * dx + dy * dy);
            float speed = (float) Math.sqrt(t.vx * t.vx + t.vy * t.vy);
            float tol = pp.fighter ? 5f + speed * 0.06f : 3f + speed * 0.05f;
            k = err > 500f || pp.jumped ? 1f : closing(err, tol * slack, amount, pp.fighter ? 14f : 10f);
            boolean leapt = pp.jumped && k >= 1f;
            pp.jumped = false;
            float fromX = loc.x, fromY = loc.y;
            if (k > 0f) loc.set(loc.x + dx * k, loc.y + dy * k);
            if (leapt) teleportTrail(pp, fromX - loc.x, fromY - loc.y);
            Vector2f vel = ship.getVelocity();
            float vb = amount <= 0f ? 1f : Math.min(1f, amount * 25f); // speed changes are taken up over a few frames
            float wvx = t.vx + t.ax * aa, wvy = t.vy + t.ay * aa;
            vel.set(vel.x + (wvx - vel.x) * vb, vel.y + (wvy - vel.y) * vb);
        }
        float tf = t.facing + (t.angVel + 0.5f * t.angAcc * aa) * age;
        if (pp.module) {
            // turns with its ship
        } else if (!own) {
            float turn = shortestTurn(ship.getFacing(), tf);
            float turnTol = (1.5f + Math.abs(t.angVel) * 0.05f) * slack;
            float tk = closing(Math.abs(turn), turnTol, amount, pp.fighter ? 20f : 12f);
            if (tk > 0f) ship.setFacing(ship.getFacing() + turn * tk);
            ship.setAngularVelocity(t.angVel + t.angAcc * aa);
        } else {
            // Our own ship turns under our own hands and the host follows (see RemotePilotAI.steerToHeading).
            // Only if the two drift far apart, because the real ship can't turn right now (overloaded, engines
            // out, shoved by a collision), is our copy pulled back to where the real one is pointing.
            float apart = shortestTurn(ship.getFacing(), tf);
            if (Math.abs(apart) > 40f) ship.setFacing(ship.getFacing() + apart * Math.min(1f, amount * 4f));
        }

        // Its stats, kept equal to the real ship's. Corrected when new figures arrive; looked at ten times a
        // second while a system is running on it or it is the ship in our hands (that is when this game's own
        // scripts keep changing them); otherwise once a second is plenty.
        boolean lively = own || t.system != 0 || t.cloak != 0;
        if (pp.hostStats != null && cfg != null && cfg.syncStats && !statsBroken
                && (pp.statsDue || ((frame + pp.netId) % (lively ? 6 : 60)) == 0)) {
            pp.statsDue = false;
            try {
                MutableShipStatsAPI stats = ship.getMutableStats();
                for (int i = 0; i < ShipStats.COUNT; i++) {
                    if (ShipStats.force(stats, i, pp.hostStats[i])) { statFixes++; statFixedMask |= 1L << i; }
                }
            } catch (Throwable th) {
                statsBroken = true;
                Global.getLogger(WingmanClientPlugin.class).error("Wingman: keeping ship stats equal to the host's failed; off for this battle", th);
            }
        }
        ship.setHitpoints(Math.max(1f, t.hull * ship.getMaxHitpoints()));
        if (Math.abs(ship.getCurrentCR() - t.cr) > 0.004f) ship.setCurrentCR(t.cr); // the host's readiness, not ours
        FluxTrackerAPI flux = ship.getFluxTracker();
        // Guard rail: never more flux than this copy of the ship can hold, whatever arrives.
        float room = Math.max(1f, ship.getMaxFlux());
        flux.setCurrFlux(Math.min(t.flux, room));
        flux.setHardFlux(Math.min(t.hardFlux, Math.min(t.flux, room)));

        if (amount <= 0f) return;

        // Overload is the host's verdict for every ship, ours included. Venting we only copy for other ships.
        boolean overloaded = (t.flags & Snapshot.F_OVERLOADED) != 0;
        if (overloaded && !flux.isOverloaded()) flux.forceOverload(0f);
        else if (!overloaded && flux.isOverloaded()) flux.stopOverload();
        if (!own) {
            boolean venting = (t.flags & Snapshot.F_VENTING) != 0;
            if (venting && !flux.isVenting() && !flux.isOverloaded()) flux.ventFlux();
            else if (!venting && flux.isVenting()) flux.stopVenting();
        }

        // On the ship we fly, the shield and the weapon group in our hand answer to our own controls so they
        // feel instant. Every other gun on it (autofire groups) shows what the real ship on the host is doing.
        List<WeaponAPI> inHand = null;
        if (own) {
            try {
                if (ship.getSelectedGroupAPI() != null) inHand = ship.getSelectedGroupAPI().getWeaponsCopy();
            } catch (Throwable th) { /* treat as nothing in hand */ }
        }

        ShieldAPI shield = ship.getShield();
        if (own) {
            // leave the shield alone
        } else if (shield != null) {
            boolean want = (t.flags & Snapshot.F_DEFENSE) != 0;
            if (want && !shield.isOn()) shield.toggleOn();
            else if (!want && shield.isOn()) shield.toggleOff();
            if (want) shield.forceFacing(t.shieldFacing);
        }
        // The ship system and the right-click system (phase cloak, damper field and the like) are switched on
        // and off on this copy when the host's are. Their own scripts, vanilla or a mod's, then run here as
        // written: a phase ship fades out, a fortress shield changes colour, a mod's system does its thing.
        if (!systemsBroken) {
            try {
                pp.systemOff = followSystem(ship, ship.getSystem(), t.system, t.systemLevel, own, pp.systemOff, amount);
                if (!own && !pp.module) teleportLook(pp, t);
                pp.cloakOff = followSystem(null, ship.getPhaseCloak(), t.cloak, t.cloakLevel, own, pp.cloakOff, amount);
                // Backstop for "can it be hit": phased or not is the host's call for every ship but the one in our hands.
                boolean phased = (t.flags & Snapshot.F_PHASED) != 0;
                if (own || ship.getPhaseCloak() == null || ship.isPhased() == phased) pp.phaseOff = 0f;
                else if ((pp.phaseOff += amount) > 0.3f) { ship.setPhased(phased); pp.phaseOff = 0f; }
            } catch (Throwable th) {
                systemsBroken = true;
                Global.getLogger(WingmanClientPlugin.class).error("Wingman: following the host's ship systems failed; systems are not mirrored for the rest of this battle", th);
            }
        }
        byte[] angles = pp.angles;
        float hull = ship.getFacing();
        for (int i = 0; i < pp.weapons.length; i++) {
            WeaponAPI w = pp.weapons[i];
            if (w == null || (inHand != null && inHand.contains(w))) continue;
            if (angles != null && i < angles.length) w.setCurrAngle(hull + Snapshot.unpackAngle(angles[i]));
            boolean firing = i < 64 && ((t.firing >>> i) & 1L) != 0;
            if (!firing && !silenced.isEmpty()) silenced.remove(w); // the host's weapon has stopped: a clean slate
            // Shot for shot. "Is firing" lasts longer than one shot and says nothing of how many, so a copy
            // following it alone fires a little too often or not quite often enough. The host also counts its
            // shots; ours are counted the same way, and the weapon is made to fire while it is behind and held
            // while it is level and has just fired. (Beams have no shots to count and follow "is firing".)
            if (i < 64 && pp.ahead != null && i < pp.ahead.length && cfg != null && cfg.shotCounts) {
                float cd = w.getCooldownRemaining();
                if (cd > pp.lastCooldown[i] + 0.02f) { // a shot of ours
                    pp.ahead[i] = Math.min(6, pp.ahead[i] + 1);
                    pp.lastShotAt[i] = clock;
                    pp.aheadSince[i] = clock;
                }
                pp.lastCooldown[i] = cd;
                if (pp.ahead[i] != 0 && clock - pp.aheadSince[i] > 1.5f) pp.ahead[i] = 0; // could not be made up: let it go
                if (pp.counted[i] && !w.isBeam()) {
                    boolean before = firing;
                    firing = pp.ahead[i] < 0 || (firing && pp.ahead[i] == 0 && clock - pp.lastShotAt[i] > 0.12f);
                    if (firing && !before) shotsMadeUp++;
                    if (!firing && before) shotsHeld++;
                }
            }
            if (firing && (unconfirmed.isEmpty() && silenced.isEmpty() || mayLaunch(w))) w.setForceFireOneFrame(true);
            else w.setForceNoFireOneFrame(true);
        }
    }

    private void applyMissile(MissilePuppet mp, float amount) {
        if (mp.wantTarget != -2) { // aim its script at the ship the host's copy is aimed at
            Puppet aim = puppets.get(mp.wantTarget);
            if (mp.wantTarget < 0 || aim != null) {
                try {
                    MissileAIPlugin ai = mp.missile.getUnwrappedMissileAI();
                    if (!(ai instanceof GuidedMissileAI)) ai = mp.missile.getMissileAI();
                    if (ai instanceof GuidedMissileAI) ((GuidedMissileAI) ai).setTarget(aim == null ? null : aim.ship);
                } catch (Throwable th) { /* it keeps whatever it picked itself */ }
                mp.wantTarget = -2;
            }
        }
        Snapshot.MissileState t = mp.target;
        if (t == null) return;
        MissileAPI m = mp.missile;
        // A missile flying straight is not repeated by the host for up to a second: carry it on from where it was.
        float age = Math.max(0f, Math.min(2f, clock - mp.targetAt));
        float aa = Math.min(age, ACC_REACH); // along its acceleration for a moment, as for ships
        float tx = t.x + (t.vx + 0.5f * t.ax * aa) * age, ty = t.y + (t.vy + 0.5f * t.ay * aa) * age;
        Vector2f loc = m.getLocation();
        float dx = tx - loc.x, dy = ty - loc.y;
        // Carried by its own flight at the host's speed; position corrected only beyond a tolerance, as for ships.
        float err = (float) Math.sqrt(dx * dx + dy * dy);
        float speed = (float) Math.sqrt(t.vx * t.vx + t.vy * t.vy);
        float k = err > 300f ? 1f : closing(err, (6f + speed * 0.06f) * slack, amount, 12f);
        if (k > 0f) loc.set(loc.x + dx * k, loc.y + dy * k);
        m.getVelocity().set(t.vx + t.ax * aa, t.vy + t.ay * aa);
        // Heading: where the host had it, turned on by its turn rate for the time since. Then let it keep turning
        // at that rate until the next update, instead of jumping from one heading to the next.
        float heading = t.facing + (t.angVel + 0.5f * t.angAcc * aa) * age;
        float swing = shortestTurn(m.getFacing(), heading);
        float sk = closing(Math.abs(swing), (3f + Math.abs(t.angVel) * 0.05f) * slack, amount, 30f);
        if (sk > 0f) m.setFacing(m.getFacing() + swing * sk);
        m.setAngularVelocity(t.angVel + t.angAcc * aa);
        m.setHitpoints(100000f); // only the host decides when point defense kills it
        boolean hostOut = (t.flags & Snapshot.M_FIZZLING) != 0;
        if (hostOut && !mp.flamedOut) {
            mp.flamedOut = true;
            m.flameOut();
        }
        // Fuel runs by the host's clock. Our copy's own count is overwritten with the host's, and as long as the
        // host's missile is still burning ours is never allowed to reach the end of its fuel first.
        if (!hostOut && !lifetimeBroken) {
            try {
                float flown = t.flightTime + age;
                if (Math.abs(m.getFlightTime() - flown) > 0.1f) { m.setFlightTime(flown); lifetimesSet++; }
                if (mp.tended) {
                    // exactly the host's, so a fuse measured against it goes off when the host's does
                    if (mp.hostMaxFlight > 0f && Math.abs(m.getMaxFlightTime() - mp.hostMaxFlight) > 0.05f) m.setMaxFlightTime(mp.hostMaxFlight);
                } else if (m.getMaxFlightTime() < flown + 1.5f) {
                    m.setMaxFlightTime(flown + 3f);
                }
                mp.lifeFrac = m.getMaxFlightTime() > 0f ? flown / m.getMaxFlightTime() : 0f;
            } catch (Throwable th) {
                lifetimeBroken = true;
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman: could not set missile flight times; missiles keep their own clocks", th);
            }
        }
    }

    private boolean lifetimeBroken;
    private int lifetimesSet, missilesRevived;

    /**
     * The host has the last word on whether a missile exists. If our copy burned out or vanished by itself (ran
     * out of fuel by its own count, flew into something that isn't there on the host) while the host's is
     * still flying, it is put back. Half a second is allowed first: when a missile really did hit, the host's
     * word that it is gone arrives well within that.
     */
    private void reviveMissiles(float amount) {
        List<MissilePuppet> back = null;
        for (MissilePuppet mp : missiles.values()) {
            Snapshot.MissileState t = mp.target;
            if (t == null || mp.launch == null) continue;
            boolean hostOut = (t.flags & Snapshot.M_FIZZLING) != 0;
            boolean here = engine.isEntityInPlay(mp.missile);
            boolean wrong = !here || (!hostOut && !mp.flamedOut && (mp.missile.isFizzling() || mp.missile.isFading()));
            if (!wrong) { mp.goneFor = 0f; continue; }
            mp.goneFor += amount;
            if (mp.goneFor < 0.5f || mp.revived >= 2 || hostOut) continue;
            // Its own script has most likely just ended it, a moment before word of the same comes from the host.
            if (mp.tended && mp.lifeFrac > 0.6f && mp.goneFor < 2.5f) continue;
            if (back == null) back = new ArrayList<MissilePuppet>();
            if (back.size() >= 3) break; // guard rail: at most a few put back per frame
            back.add(mp);
        }
        if (back == null) return;
        for (MissilePuppet old : back) {
            PendingMissile p = old.launch;
            Snapshot.MissileState t = old.target;
            float age = Math.max(0f, Math.min(2f, clock - old.targetAt));
            p.x = t.x + t.vx * age; p.y = t.y + t.vy * age; p.vx = t.vx; p.vy = t.vy; p.facing = t.facing;
            p.flown = t.flightTime + age;
            p.at = clock;
            byMissile.remove(old.missile);
            if (engine.isEntityInPlay(old.missile)) engine.removeEntity(old.missile);
            missiles.remove(p.id);
            build(p);
            MissilePuppet fresh = missiles.get(p.id);
            if (fresh == null) { old.revived = 2; missiles.put(p.id, old); continue; } // could not be rebuilt: stop trying
            fresh.target = t;
            fresh.targetAt = old.targetAt;
            fresh.revived = old.revived + 1;
            fresh.wantTarget = old.wantTarget;
            // It already went off once on our screen; the host decides what it hits from here on.
            try { fresh.missile.setCollisionClass(CollisionClass.NONE); } catch (Throwable th) { /* it may go off again */ }
            missilesRevived++;
            compat.odd("missile:" + p.projId);
        }
    }

    // Counting our own uses of the ship system (see InputState.sysUses).
    private ShipAPI sysShip;
    private ShipSystemAPI.SystemState sysState;
    private int sysAmmo, sysUsesSent;
    private float sysCooldown, sysForcedAt = -10f, sysAmmoOffFor;
    private int hostSysAmmo = 255;

    /**
     * Has the player just used the ship system? There is no asking the game which key was pressed, but the
     * system itself shows it: a charge is gone, or it has left its resting state, or (for one that is over
     * in an instant and has no charges) its cooldown has started afresh. Each use is counted once and the
     * count is sent with the controls; the host carries out as many uses as it has not yet seen.
     */
    private void noteSystemUse(ShipAPI s) {
        input.sysUses = sysUsesSent;
        input.sysOrders = 0;
        try {
            ShipSystemAPI sys = s.getSystem();
            if (sys == null) { sysShip = null; return; }
            ShipSystemAPI.SystemState st = sys.getState();
            int ammo = sys.getAmmo();
            float cd = sys.getCooldownRemaining();
            if (sys instanceof com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI) {
                com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI.DroneOrders o =
                        ((com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI) sys).getDroneOrders();
                if (o != null) input.sysOrders = o.ordinal() + 1;
            } else if (sysShip == s && clock - sysForcedAt > 0.15f) {
                boolean wasResting = sysState == ShipSystemAPI.SystemState.IDLE || sysState == ShipSystemAPI.SystemState.COOLDOWN;
                boolean resting = st == ShipSystemAPI.SystemState.IDLE || st == ShipSystemAPI.SystemState.COOLDOWN;
                boolean charges = sys.getMaxAmmo() > 0 && sys.getMaxAmmo() < 1000;
                boolean used = charges ? ammo < sysAmmo && clock - sysAmmoSetAt > 0.15f
                        : (wasResting && !resting) || (wasResting && resting && cd > sysCooldown + 0.2f);
                if (used) {
                    sysUsesSent = (sysUsesSent + 1) & 0xFF;
                    input.sysUses = sysUsesSent;
                    sysUsesTotal++;
                    // ...and then watched until the host's ship is seen to have acted on it (see confirmSystemUse)
                    boolean stays = false;
                    try { stays = sys.getSpecAPI().isToggle(); } catch (Throwable th) { /* treated as a one-off */ }
                    if (!stays) { sysAwaiting = clock; sysAwaitAmmo = hostSysAmmo; sysRetries = 0; }
                    if (Dbg.on && sysLogs++ < 60) Dbg.info(WingmanClientPlugin.class, "Wingman system: used " + sys.getId() + " (use " + sysUsesTotal
                            + "; " + (charges ? "a charge went" : "it left its resting state") + ")");
                }
            }
            sysShip = s;
            sysState = st;
            sysAmmo = ammo;
            sysCooldown = cd;
        } catch (Throwable t) { /* this frame's look is skipped */ }
    }
    private float sysAmmoSetAt = -10f, sysAwaiting = -1f;
    private int sysUsesTotal, sysAwaitAmmo, sysRetries, sysLogs, sysConfirmed, sysResent, sysUnconfirmed;
    private final Set<String> sysWarned = new HashSet<String>();

    /**
     * Guard rail: every use of our ship system must be seen to happen on the real ship. The host reports its
     * system's stage and charges with every update; after a use here, one of them has to change. If neither
     * has after most of a second, the use is sent once more. If that does nothing either, it is written to
     * the log and counted in the compatibility report under the system's name, so a system that cannot be
     * started from another PC shows up by name instead of just "not working".
     */
    private void confirmSystemUse(Puppet own) {
        if (sysAwaiting < 0f) return;
        if (own == null || own.target == null || own.ship != sysShip) { sysAwaiting = -1f; return; }
        try {
            ShipSystemAPI sys = own.ship.getSystem();
            if (sys == null) { sysAwaiting = -1f; return; }
            boolean acted = own.target.system != ShipSystemAPI.SystemState.IDLE.ordinal()
                    || (hostSysAmmo < 254 && sysAwaitAmmo < 254 && hostSysAmmo < sysAwaitAmmo);
            if (acted) {
                sysConfirmed++;
                compat.good("system:" + sys.getId());
                sysAwaiting = -1f;
                return;
            }
            if (clock - sysAwaiting < 0.8f) return;
            if (sysRetries == 0) {
                sysRetries = 1;
                sysResent++;
                sysAwaiting = clock;
                sysUsesSent = (sysUsesSent + 1) & 0xFF;
                if (Dbg.on && sysLogs++ < 60) Dbg.info(WingmanClientPlugin.class, "Wingman system: " + sys.getId()
                        + " was used here but the host's ship has not acted on it; sent again");
                return;
            }
            sysAwaiting = -1f;
            sysUnconfirmed++;
            compat.odd("system:" + sys.getId());
            if (sysWarned.add(sys.getId())) {
                Global.getLogger(WingmanClientPlugin.class).warn("Wingman guard: ship system '" + sys.getId() + "' on " + own.ship.getHullSpec().getHullId()
                        + " was used here twice over but never started on the host's ship (host stage " + own.target.system + ", host charges "
                        + hostSysAmmo + ", flux here " + Math.round(own.ship.getFluxTracker().getCurrFlux()) + "). Please report this system.");
            }
        } catch (Throwable t) {
            sysAwaiting = -1f;
        }
    }

    /**
     * Keep our own ship's system as ready as the real one: the same charges, and not still cooling down here
     * when the host's is ready. Otherwise a press here could do nothing while the real ship could have acted.
     */
    private void matchSystemReadiness(Puppet own, float amount) {
        if (own == null || own.target == null) return;
        try {
            ShipSystemAPI sys = own.ship.getSystem();
            if (sys == null) return;
            if (hostSysAmmo < 254 && sys.getMaxAmmo() > 0 && sys.getAmmo() != hostSysAmmo) {
                sysAmmoOffFor += amount;
                if (sysAmmoOffFor > 1.5f) { // not just a use on its way to the host or back
                    sys.setAmmo(Math.min(hostSysAmmo, sys.getMaxAmmo()));
                    sysAmmoSetAt = clock;
                    sysAmmoOffFor = 0f;
                }
            } else {
                sysAmmoOffFor = 0f;
            }
            boolean hostReady = own.target.system == ShipSystemAPI.SystemState.IDLE.ordinal();
            if (hostReady && sys.getState() == ShipSystemAPI.SystemState.COOLDOWN) {
                sysCoolOffFor += amount;
                if (sysCoolOffFor > 1f) { sys.setCooldownRemaining(0f); sysCoolOffFor = 0f; }
            } else {
                sysCoolOffFor = 0f;
            }
        } catch (Throwable t) { /* it keeps its own count */ }
    }
    private float sysCoolOffFor;

    private void captureInput(Puppet own) {
        input.flags = 0;
        input.group = -1;
        input.target = -1;
        input.autofire = 0;
        if (own == null) return;
        if (autopilot) { input.flags = InputState.AUTOPILOT; return; } // hands off: the host's AI has the ship
        ShipAPI s = own.ship;
        int leave = 0;
        try { // the game's own Retreat order, given to the ship we are flying
            com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo order = engine.getFleetManager(0).getTaskManager(false).getAssignmentFor(s);
            if (order != null && order.getType() != null && "RETREAT".equals(order.getType().name()) && !s.isFighter()) leave |= InputState.RETREAT; // (a fighter has no retreat of its own)
        } catch (Throwable t) { /* no order */ }
        if (System.currentTimeMillis() < retreatNowUntil) leave |= InputState.RETREAT_NOW;
        ShipEngineControllerAPI ec = s.getEngineController();
        int f = 0;
        if (ec.isAccelerating()) f |= InputState.ACCEL;
        if (ec.isAcceleratingBackwards()) f |= InputState.BACK;
        if (ec.isDecelerating()) f |= InputState.DECEL;
        if (ec.isTurningLeft()) f |= InputState.TURN_L;
        if (ec.isTurningRight()) f |= InputState.TURN_R;
        if (ec.isStrafingLeft()) f |= InputState.STRAFE_L;
        if (ec.isStrafingRight()) f |= InputState.STRAFE_R;
        boolean commandUi = false;
        try {
            commandUi = engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI();
        } catch (Throwable t) { /* older API */ }
        boolean button = Mouse.isButtonDown(0);
        if (!commandUi && !engine.isPaused() && button) f |= InputState.FIRE;
        ShieldAPI shield = s.getShield();
        if (shield != null ? shield.isOn() : s.isPhased()) f |= InputState.DEFENSE;
        if (s.getSystem() != null && (s.getSystem().isActive() || s.getSystem().isOn())) f |= InputState.SYSTEM;
        noteSystemUse(s);
        if (s.getFluxTracker().isVenting()) f |= InputState.VENT;
        try {
            if (s.isPullBackFighters()) f |= InputState.PULL_BACK;
        } catch (Throwable t) { /* not a carrier */ }
        input.flags = f | leave;
        Vector2f m = s.getMouseTarget();
        if (m != null) { input.mouseX = m.x; input.mouseY = m.y; }
        Puppet targeted = s.getShipTarget() == null ? null : byShip.get(s.getShipTarget());
        if (targeted != null) input.target = targeted.netId;
        int mask = 0;
        try {
            List<WeaponGroupAPI> groups = s.getWeaponGroupsCopy();
            for (int i = 0; i < groups.size() && i < 16; i++) if (groups.get(i).isAutofiring()) mask |= 1 << i;
        } catch (Throwable t) { /* leave all off */ }
        input.autofire = mask;
        input.facing = s.getFacing();
        try {
            input.group = Groups.selectedIndex(s, s.getWeaponGroupsCopy());
        } catch (Throwable t) {
            input.group = -1;
        }
        // What we tell the host when the fire button goes down, so the two logs can be compared.
        boolean firing = (f & InputState.FIRE) != 0;
        if (firing && !wasFiring && System.currentTimeMillis() - lastFireLog > 5000) {
            lastFireLog = System.currentTimeMillis();
            Dbg.info(WingmanClientPlugin.class, "Wingman fire: button down, sending group " + input.group
                    + ", autofire switches " + Integer.toBinaryString(mask));
        }
        wasFiring = firing;
    }
}
