package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import wingman.net.HostSession;
import wingman.net.InputState;
import wingman.net.Lobby;
import wingman.net.Msg;
import wingman.net.ShipSpawn;
import wingman.net.Snapshot;

/**
 * Loaded into every battle (see data/config/settings.json). If hosting is switched on in wingman_settings.json,
 * this battle becomes the shared one: the host's game is the only real simulation, and this plugin streams
 * ship state out and feeds remote players' inputs in.
 */
public class WingmanHostPlugin extends BaseEveryFrameCombatPlugin {
    static final String CLIENT_FLAG = "wingman_client";

    private CombatEngineAPI engine;
    private boolean started, disabled;
    private WingmanConfig cfg;
    private final Settings.Menu settings = new Settings.Menu();
    private final PlayerTags tags = new PlayerTags();
    private boolean codecBroken;
    private HostSession session;

    private int nextNetId = 1;
    private final Map<ShipAPI, Integer> ids = new HashMap<ShipAPI, Integer>();
    private final Map<Integer, ShipAPI> byId = new LinkedHashMap<Integer, ShipAPI>();
    private final Map<Integer, byte[]> spawnMsgs = new LinkedHashMap<Integer, byte[]>();
    private int nextMissileId = 1;
    private final Map<MissileAPI, Integer> missileIds = new HashMap<MissileAPI, Integer>();
    private final Map<Integer, MissileAPI> missilesById = new LinkedHashMap<Integer, MissileAPI>();
    private final Map<Integer, byte[]> missileSpawnMsgs = new LinkedHashMap<Integer, byte[]>();
    /** Missiles that were already burning out when last seen: they vanish quietly instead of exploding. */
    private final Set<Integer> expiring = new HashSet<Integer>();
    private long lastSnapshotNanos;
    private int tick;
    private final HostOrders orders = new HostOrders();

    /** Per ship: which weapons have fired since the last snapshot, and what each looked like a frame ago. */
    private static final class FireTrack {
        long fired;
        /** Per weapon: shots (strictly: times its cooldown started afresh) since the last snapshot. */
        byte[] shots = new byte[0];
        /** Per weapon: shots since the battle began (low 8 bits). */
        byte[] totals = new byte[0];
        float[] cooldown = new float[0];
        int[] ammo = new int[0];
        /** Per weapon: charge level last frame. */
        float[] charge = new float[0];
        /** Weapons heading for a shot right now (see wantsShot). */
        long wants;
    }

    /**
     * Whether a weapon is heading for a shot: charging up, firing, or in a burst. The game's "is firing" also
     * covers charging down: the cooldown after a shot, and a charge-up let go of before it was done. A joiner's
     * copy is made to fire while the host's weapon is "firing", so on that alone a charge the host's player
     * abandoned carried on charging on the joiner's screen and fired a shot that never happened.
     */
    private static boolean wantsShot(WeaponAPI w, float chargeBefore, float cooldown) {
        if (!w.isFiring()) return false;
        if (w.isInBurst()) return true;
        // Only a beam or a weapon with a charge-up can be let go of half-charged; for any other weapon the time
        // after a shot is just its cooldown, and the copy may as well stay "firing" through it (it can't fire).
        if (!w.isBeam() && chargeTime(w) <= 0f) return true;
        return w.getChargeLevel() >= chargeBefore - 0.0001f; // falling: charging down, no shot coming
    }

    private static float chargeTime(WeaponAPI w) {
        try { return w.getSpec().getChargeTime(); } catch (Throwable t) { return 0f; }
    }
    private final Map<Integer, FireTrack> fireTracks = new HashMap<Integer, FireTrack>();

    /**
     * What a joiner was last told about one ship or missile. From this the host can work out where the joiner
     * believes it is right now (it keeps moving at the speed it was last given), and only send an update when
     * the real thing has strayed from that, or changed in some other way. This is "dead reckoning".
     */
    private static final class Sent {
        long sightAskedAt;
        boolean outOfSight;
        float x, y, vx, vy, facing, angVel, time;
        int tick, hull, cr, flags, anglesTick, systems;
        float flux, hardFlux, shieldFacing, flight;
        boolean fired, hidden;
        byte[] angles;
    }
    private final Map<Integer, Sent> sentShips = new HashMap<Integer, Sent>();
    private final Map<Integer, Sent> sentMissiles = new HashMap<Integer, Sent>();
    /** Send everything in the next update: someone just joined, or an update could not be delivered. */
    private boolean fullNext = true;
    private float gameClock;
    private int sentStates, possibleStates;
    /** Every fighter wing on the field gets a number, so a joiner can group fighters the way they are grouped here. */
    private final Map<FighterWingAPI, Integer> wingIds = new IdentityHashMap<FighterWingAPI, Integer>();
    private int nextWingId = 1;
    private final Set<String> missileKinds = new HashSet<String>();
    /** Missiles steered by a mod's script (or that split into warheads): what each is aimed at, as joiners were last told. */
    private final Map<Integer, Integer> scriptedTargets = new HashMap<Integer, Integer>();
    private final Map<String, List<String>> makersOf = new HashMap<String, List<String>>();
    private final Map<String, String> hiddenMakers = new HashMap<String, String>();
    private final HostEnvironment environment = new HostEnvironment();
    private final HostDamage damage = new HostDamage();
    private HostExtras extras;
    /** Fighters that have an officer of their own (a mod's doing): synced as closely as ships. */
    private final Set<Integer> officered = new HashSet<Integer>();
    private int officeredSeen;
    private final Map<Integer, String> pilotState = new HashMap<Integer, String>();
    private final Set<Integer> onAutopilot = new HashSet<Integer>();

    // Ready check: 0 = waiting for the host's own deployment, 1 = holding the battle paused, 2 = battle running.
    private int readyPhase = 2;
    private Lobby lobby = new Lobby();
    private long lastLobbyMillis;
    private boolean enterPressed, forcePressed, waitNoted;
    private long waitingSince;
    private final Panel panel = new Panel();
    private String alertTitle;
    private final List<String> alertLines = new ArrayList<String>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (disabled || engine == null) return;
        try {
            if (!started) {
                if (!start()) return;
            }
            gameClock += amount; // battle time: stands still while paused, runs fast or slow with the battle
            step();
        } catch (Throwable t) {
            disabled = true;
            Global.getLogger(WingmanHostPlugin.class).error("Wingman host error, co-op disabled for this battle", t);
            Ui.say(engine, "Co-op: host error, disabled for this battle (see starsector.log): " + t);
            // Don't leave joiners staring at a frozen copy: hand their ships back and tell them it is over.
            try {
                if (session != null) {
                    for (HostSession.Player p : session.getPlayers()) releaseShip(p);
                    session.broadcastNotice("Co-op stopped on the host because of an error. The battle goes on without you.", null);
                    session.endBattle();
                }
            } catch (Throwable ignored) { /* nothing more to be done */ }
        }
    }

    private boolean start() throws Exception {
        if (Global.getCurrentState() != GameState.COMBAT) { disabled = true; return false; } // title screen battle
        if (engine.getCustomData().containsKey(CLIENT_FLAG)) { disabled = true; return false; } // we are the joiner
        cfg = WingmanConfig.load();
        if (!cfg.hostEnabled) { disabled = true; return false; }
        try {
            session = Hosting.open(cfg);
        } catch (java.io.IOException e) {
            disabled = true;
            Ui.say(engine, "Co-op: could not open port " + cfg.port + " (" + e.getMessage() + "). Hosting is off.");
            return false;
        }
        extras = new HostExtras(ids, cfg, officered);
        session.beginBattle();
        rate = cfg.snapshotRate;
        readyPhase = cfg.readyCheck ? 0 : 2;
        Subsystems.reset();
        lastHostShip = engine.getPlayerShip();
        tapMessages();
        started = true;
        Ui.say(engine, "Co-op: hosting on port " + cfg.port + " for up to " + cfg.maxPlayers + " players.");
        if (cfg.password.isEmpty()) Ui.say(engine, "Co-op: no join password is set, so anyone who can reach this PC on port " + cfg.port
                + " can join. Set a join password in the settings (Ctrl+Shift+O) to change that.");
        // Addresses are never written to the log, so a log can be shared without them. Players find them in the
        // CO-OP panel on the title screen; the host can also have them shown in the feed (never logged).
        if (cfg.announceAddresses) {
            String mine = myAddresses();
            if (!mine.isEmpty()) Ui.show(engine, "Co-op: friends type one of these into the join screen: " + mine);
        }
        Global.getLogger(WingmanHostPlugin.class).info("Wingman network: " + wingman.net.Transport.describe());
        return true;
    }

    private MessageSpy spy;
    private long spyCheckedAt, msgWindowStart;
    private int msgsInWindow, msgsDropped;

    /**
     * Send scripts' lines in the message feed to joiners (see MessageTap). Checked again every few seconds,
     * in case the game rebuilt its feed.
     */
    private void tapMessages() {
        spyCheckedAt = System.currentTimeMillis();
        if (!cfg.hostMessages) return;
        MessageSpy s = MessageTap.install(engine);
        if (s == null || s == spy) return;
        spy = s;
        s.sink = new MessageSpy.Sink() {
            public boolean line(int indent, Object[] parts) {
                sendLine(indent, parts);
                return true;
            }
        };
    }

    private void sendLine(int indent, Object[] parts) {
        if (session == null || session.getPlayers().isEmpty() || !cfg.hostMessages) return;
        long now = System.currentTimeMillis();
        if (now - msgWindowStart > 1000) { msgWindowStart = now; msgsInWindow = 0; }
        if (++msgsInWindow > 30) { // a script writing lines every frame: the rest of this second stays here
            if (msgsDropped++ == 0) Global.getLogger(WingmanHostPlugin.class).info("Wingman messages: over 30 lines a second; extra lines are not sent");
            return;
        }
        try {
            byte[] m = MessageTap.encode(engine, indent, parts, new MessageTap.HostNames() {
                public Integer shipId(ShipAPI ship) { return ids.get(ship); }
                public int objectiveIndex(com.fs.starfarer.api.combat.BattleObjectiveAPI o) { return orders.objectiveIndex(o); }
            });
            if (m != null) session.broadcast(Msg.COMBAT_MSG, m);
        } catch (Throwable t) {
            if (msgsDropped++ == 0) Global.getLogger(WingmanHostPlugin.class).warn("Wingman: a message line could not be sent", t);
        }
    }

    private final PilotMarks marks = new PilotMarks();

    /** Who flies what, written on the ships for other mods (see WingmanAPI). */
    private void markPilots() {
        ShipAPI mine = engine.getPlayerShip();
        if (mine != null && !session.getPlayers().isEmpty()) marks.flown(mine, cfg.playerName, false);
        for (HostSession.Player p : session.getPlayers()) {
            if (p.ship instanceof ShipAPI) marks.flown((ShipAPI) p.ship, p.name, true);
            // what this player is looking at, for the bandwidth limit's priorities (see Budget)
            ShipAPI looking = p.ship instanceof ShipAPI ? (ShipAPI) p.ship : mine;
            if (looking != null) { p.budget.focusX = looking.getLocation().x; p.budget.focusY = looking.getLocation().y; p.budget.hasFocus = true; }
        }
        marks.apply();
    }

    private final Prof prof = new Prof("network", "ships", "wrecks", "missiles", "shots", "firing", "players", "orders+map", "damage", "updates");

    private void step() {
        prof.begin();
        for (String line : session.pump()) Ui.say(engine, "Co-op: " + line);
        wingman.api.WingmanAPI.deliver();
        markPilots();
        if (System.currentTimeMillis() - spyCheckedAt > 3000) tapMessages();
        if (session.acceptError != null) {
            String err = session.acceptError;
            session.acceptError = null;
            throw new IllegalStateException("cannot accept connections: " + err);
        }

        // Players who left: give their ship back to its own AI, and stop the battle so nobody is caught out.
        for (HostSession.Player p : session.drainDeparted()) {
            releaseShip(p);
            if (p.replaced) continue; // they are already back on a new connection: nothing to stop for
            if (readyPhase != 2) continue; // before the battle starts the ready-check panel already shows it
            engine.setPaused(true);
            String text = p.name + " disconnected. Their ship is back under AI control. Their game will try to reconnect by itself.";
            alert("PLAYER DISCONNECTED", text);
            Msg.W w = new Msg.W();
            try {
                w.out.writeUTF("PLAYER DISCONNECTED");
                w.out.writeUTF(text);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            session.broadcastAll(Msg.ALERT, w.done());
        }

        // The battle is decided: tell joiners to leave it. They stay connected for the next one.
        // (Not in the simulator: there "over" only means the current opponents are gone, and more can be added.)
        if (!endSent && readyPhase == 2 && gameClock > 3f && !engine.isSimulation() && engine.isCombatOver()) {
            endSent = true;
            session.endBattle();
            Global.getLogger(WingmanHostPlugin.class).info("Wingman: battle over, joiners told to leave it"
                    + (seededCuts + unseededCuts > 0 ? " (wreck cuts sent with their dice seed: " + seededCuts + " of " + (seededCuts + unseededCuts) + ")" : ""));
            return;
        }
        if (endSent) return;

        // With nobody connected there is nobody to tell anything: the host's own game should not pay for co-op.
        // Ships are still counted now and then (the ready-check panel shows how many there are); everything
        // else waits until someone joins, at which point the next frame picks it all up.
        if (session.getPlayers().isEmpty()) {
            long idleNow = System.nanoTime();
            if (idleNow - lastIdleNanos > 500000000L) { lastIdleNanos = idleNow; trackShips(); trackWrecks(); }
            readyCheck();
            return;
        }

        prof.mark(0);
        trackShips();
        prof.mark(1);
        trackWrecks();
        prof.mark(2);
        trackMissiles();
        prof.mark(3);
        if (!shotsBroken && (shotTurn = !shotTurn)) { // every other frame: a hit is still reported within 1/30 s
            try {
                trackShots();
            } catch (Throwable t) { // only this part stops; the rest of co-op carries on
                shotsBroken = true;
                shots.clear();
                Global.getLogger(WingmanHostPlugin.class).error("Wingman: reporting scripted shot hits failed; joiners judge those hits themselves for the rest of this battle", t);
            }
        }
        prof.mark(4);
        trackFiring();
        prof.mark(5);

        // Anyone who just joined (or restarted their side) gets the full picture.
        for (HostSession.Player p : session.getPlayers()) {
            if (!p.needsSync) continue;
            p.conn.send(Msg.RESET, Msg.EMPTY);
            damage.resendAll();
            if (extras != null) extras.resendAll();
            p.conn.send(Msg.BATTLE_INFO, environment.battleInfo(engine));
            byte[] nebula = environment.nebula(engine);
            if (nebula != null) p.conn.send(Msg.NEBULA, nebula);
            for (byte[] spawn : spawnMsgs.values()) p.conn.send(Msg.SPAWN, spawn);
            // Ships destroyed before this player arrived: built, then quietly turned into wrecks, then broken
            // up the way they have broken up here.
            for (Map.Entry<Integer, byte[]> wreck : wreckSpawnMsgs.entrySet()) {
                if (!wrecks.containsKey(wreck.getKey())) continue;
                p.conn.send(Msg.SPAWN, wreck.getValue());
                int wid = wreck.getKey();
                p.conn.send(Msg.DESPAWN, new byte[] { (byte) (wid >>> 24), (byte) (wid >>> 16), (byte) (wid >>> 8), (byte) wid, Msg.DESPAWN_WRECKED });
            }
            for (long[] split : wreckSplits) {
                if (wrecks.containsKey((int) split[1])) p.conn.send(Msg.WRECK_SPLIT, splitMsg(split));
            }
            for (byte[] spawn : missileSpawnMsgs.values()) p.conn.send(Msg.MISSILE_SPAWN, spawn);
            for (Map.Entry<Integer, Integer> t : scriptedTargets.entrySet()) {
                if (missilesById.containsKey(t.getKey())) p.conn.send(Msg.MISSILE_TARGET, Msg.twoInts(t.getKey(), t.getValue()));
            }
            byte[] objectives = orders.objectivesMessage(engine);
            if (objectives != null) p.conn.send(Msg.OBJECTIVES, objectives);
            p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, p.shipNetId));
            p.needsSync = false;
            fullNext = true;
            environment.resendAsteroids();
        }

        guardHostShip();
        checkPilotedShips();
        seatReserved();
        handleClaims();
        runSubsystems();
        keepPilots();
        watchLandings();
        handleRetreats();
        syncTargets();
        sendCheck();
        prof.mark(6);
        orders.tick(engine, session, ids);
        environment.tick(engine, session);
        prof.mark(7);
        damage.tick(session, byId, extras == null ? null : extras.hitLately());
        if (extras != null) extras.tick(engine, session, byId);
        prof.mark(8);

        // Bandwidth meter: with debug logging, a detailed line every 30 seconds while anyone is connected;
        // otherwise a short one every two minutes, so a playtest's log says what the mod really cost.
        if (!Dbg.on && !session.getPlayers().isEmpty()) {
            String traffic = wingman.net.NetStats.report(120f);
            if (traffic != null) {
                int ships = 0, fighters = 0;
                for (ShipAPI s : byId.values()) { if (s.isFighter()) fighters++; else ships++; }
                long[] pc = session.packerCounts();
                Global.getLogger(WingmanHostPlugin.class).info("Wingman traffic (host, " + session.getPlayers().size() + " joined, " + ships
                        + " ships, " + fighters + " fighters, " + missilesById.size() + " missiles, " + rate + " of " + cfg.snapshotRate
                        + " updates/s, " + pc[2] + " ship/missile states left out as guessed right so far): " + traffic);
            }
        }
        if (Dbg.on) {
            String traffic = wingman.net.NetStats.report(30f);
            if (traffic != null) {
                int ships = 0, fighters = 0;
                for (ShipAPI s : byId.values()) { if (s.isFighter()) fighters++; else ships++; }
                Dbg.info(WingmanHostPlugin.class, "Wingman traffic (host, " + session.getPlayers().size() + " joined, " + ships
                        + " ships, " + fighters + " fighters, " + missilesById.size() + " missiles, " + cfg.snapshotRate + " updates/s, "
                        + (possibleStates == 0 ? 100 : Math.round(100f * sentStates / possibleStates)) + "% of ship/missile states needed sending, "
                        + trimmed + " held back as out of sight, " + halved + " fighter states left for the next update, " + scriptEnds + " missiles ended by a script so far; "
                        + (extras == null ? "" : extras.statsSent + " stat values, " + extras.notesSent + " script values and " + extras.hurtsSent + " damage reports sent so far (" + extras.ordinary + " ordinary weapon hits not reported)")
                        + "; values left unsent as near enough to where the joiner already has them: " + session.packerCounts()[0] + " against " + session.packerCounts()[1] + " sent, "
                        + session.packerCounts()[2] + " ship/missile states left out as guessed right"
                        + "; updates: " + session.describeUpdates()
                        + "; armor/ammo read for " + damage.looked + " ships and passed over for " + damage.passedOver
                        + (extras == null ? "" : "; stats read for " + extras.statLooks + " and passed over for " + extras.statSkips) + "): " + traffic);
                halved = 0;
                Dbg.info(WingmanHostPlugin.class, "Wingman time (host): " + prof.report());
                trimmed = 0;
                sentStates = 0;
                possibleStates = 0;
            }
        }
        readyCheck();

        long now = System.nanoTime();
        if (now - lastSnapshotNanos >= 1000000000L / rate) {
            lastSnapshotNanos = now;
            if (!session.getPlayers().isEmpty()) {
                boolean full = fullNext;
                fullNext = false;
                // If anyone's link was too backed up to take this update, the next one carries everything again,
                // and updates are sent less often for a while: fewer that arrive beat more that don't.
                Snapshot update = buildSnapshot(full);
                boolean taken;
                if (cfg.compactUpdates && !codecBroken) {
                    try {
                        // To each player as changes since the last update they hold; in full for one who holds none.
                        taken = session.broadcastUpdate(update);
                    } catch (Throwable t) {
                        codecBroken = true;
                        Global.getLogger(WingmanHostPlugin.class).error("Wingman: packing updates as changes failed; "
                                + "they are sent in the old, larger format for the rest of this battle", t);
                        taken = session.broadcastDroppable(Msg.SNAPSHOT, update.encode());
                    }
                } else {
                    taken = session.broadcastDroppable(Msg.SNAPSHOT, update.encode());
                }
                if (!taken) {
                    fullNext = true;
                    lastSkipNanos = now;
                    int slower = Math.max(Math.min(8, cfg.snapshotRate), rate * 2 / 3);
                    if (slower != rate) {
                        rate = slower;
                        Dbg.info(WingmanHostPlugin.class, "Wingman guard: link backed up, sending " + rate + " updates a second for now");
                    }
                } else if (rate < cfg.snapshotRate && now - lastSkipNanos > 5000000000L) {
                    lastSkipNanos = now; // five clean seconds: step back up
                    rate = Math.min(cfg.snapshotRate, rate + Math.max(1, cfg.snapshotRate / 5));
                }
            }
        }
        prof.mark(9);
        prof.end();
    }

    /** Updates a second right now: the configured rate, or less while a link is struggling. */
    private int rate = 20;
    private long lastSkipNanos, lastCheckNanos;
    private boolean endSent, lossWarned;

    /** Every three seconds: how many ships, fighters and wrecks there are, so a joiner can tell if its copy has drifted. */
    private void sendCheck() {
        long now = System.nanoTime();
        if (now - lastCheckNanos < 3000000000L) return;
        lastCheckNanos = now;
        int ships = 0, fighters = 0, sum = 0;
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            if (e.getValue().isFighter()) fighters++; else ships++;
            sum += e.getKey();
        }
        Msg.W w = new Msg.W();
        try {
            w.out.writeInt(ships);
            w.out.writeInt(fighters);
            w.out.writeInt(sum);
            int wreckSum = 0;
            for (Integer id : wrecks.keySet()) wreckSum += id;
            w.out.writeInt(wrecks.size());
            w.out.writeInt(wreckSum);
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        session.broadcast(Msg.CHECK, w.done());
    }

    /**
     * A joiner leaving the battle with the ship they fly, the way the game lets a player do it.
     * Retreat order on their own ship: the real ship gets the same order and they keep flying it out.
     * Enter at the bottom edge: the real ship is checked to be there, ordered to retreat directly and handed
     * to its own AI to finish leaving; the player goes back to watching.
     */
    private void handleRetreats() {
        for (HostSession.Player p : session.getPlayers()) {
            ShipAPI s = (ShipAPI) p.ship;
            int flags = p.input.flags, before = p.retreatFlags;
            p.retreatFlags = s == null ? 0 : flags & (InputState.RETREAT | InputState.RETREAT_NOW);
            if (s == null || !s.isAlive() || s.isHulk() || s.isFighter()) continue; // a fighter is never ordered to retreat
            try {
                com.fs.starfarer.api.combat.CombatFleetManagerAPI fm = engine.getFleetManager(s.getOwner());
                com.fs.starfarer.api.combat.DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(s);
                if (dfm == null) continue;
                String shipName = s.getName() == null ? s.getHullSpec().getHullId() : s.getName();
                if ((flags & InputState.RETREAT_NOW) != 0 && (before & InputState.RETREAT_NOW) == 0) {
                    if (s.getLocation().y > -engine.getMapHeight() / 2f + 900f) {
                        p.conn.send(Msg.NOTICE, Msg.utf("Fly to the bottom edge of the map to retreat."));
                        continue;
                    }
                    fm.getTaskManager(false).orderRetreat(dfm, false, true);
                    releaseShip(p);
                    p.shipNetId = -1;
                    p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, -1));
                    Ui.say(engine, "Co-op: " + p.name + " is retreating with " + shipName + ".");
                    session.broadcastNotice(p.name + " is retreating with " + shipName + ".", null);
                } else if ((flags & InputState.RETREAT) != 0 && (before & InputState.RETREAT) == 0 && !s.isRetreating()) {
                    fm.getTaskManager(false).orderRetreat(dfm, false, false);
                    Ui.say(engine, "Co-op: " + p.name + " ordered " + shipName + " to retreat.");
                }
            } catch (Throwable t) {
                Global.getLogger(WingmanHostPlugin.class).warn("Wingman: could not carry out a joiner's retreat", t);
            }
        }
    }

    /** The largest ship joiners may fly, from "maxShipSize" in the settings. */
    private boolean tooBig(ShipAPI s) {
        try {
            ShipAPI.HullSize max = ShipAPI.HullSize.valueOf(cfg.maxShipSize);
            return s.getHullSize().ordinal() > max.ordinal();
        } catch (Throwable t) {
            return false; // a setting that isn't a size means no limit
        }
    }

    /** Ctrl+Shift+number: remove that player (by their P number: Ctrl+Shift+2 removes P2). */
    private void kick(int number) {
        HostSession.Player p = null;
        for (HostSession.Player q : session.getPlayers()) if (q.slot == number) p = q;
        if (p == null) return;
        releaseShip(p);
        session.kick(p, "The host removed you from this game.");
        Ui.say(engine, "Co-op: removed " + p.name + ". They cannot rejoin until you restart the game.");
    }

    /** Ctrl+Shift+T: take the targeted ship back from the player flying it. */
    private void takeBack() {
        ShipAPI mine = engine.getPlayerShip();
        ShipAPI target = mine == null ? null : mine.getShipTarget();
        HostSession.Player p = target == null ? null : pilotOf(target);
        if (p == null) { Ui.say(engine, "Co-op: target a ship that a player is flying (R), then press Ctrl+Shift+T to take it back."); return; }
        releaseShip(p);
        p.shipNetId = -1;
        p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, -1));
        p.conn.send(Msg.NOTICE, Msg.utf("The host took that ship back."));
        Ui.say(engine, "Co-op: took " + (target.getName() == null ? "the ship" : target.getName()) + " back from " + p.name + ".");
    }

    private void alert(String title, String text) {
        alertTitle = title;
        alertLines.add(text);
        while (alertLines.size() > 6) alertLines.remove(0);
    }

    /**
     * Before the fighting starts the host holds the battle paused. Every joiner reports how many of the
     * host's ships they have built; once everyone has them all and has pressed ready, the host starts.
     */
    private void readyCheck() {
        if (readyPhase == 0) {
            // Wait until the host has finished their own deployment screen and the battle is actually running.
            if (engine.isPaused() || engine.getPlayerShip() == null || engine.getPlayerShip().isShuttlePod()) return;
            readyPhase = 1;
            session.lobbyDirty = true;
        }
        if (readyPhase == 1) {
            if (!engine.isPaused()) engine.setPaused(true);
            boolean go = forcePressed || (enterPressed && lobby.allReady());
            if (go) {
                readyPhase = 2;
                engine.setPaused(false);
                session.lobbyDirty = true;
                Ui.say(engine, "Co-op: battle started.");
            }
        }
        enterPressed = false;
        forcePressed = false;

        long now = System.currentTimeMillis();
        // Held up by someone who has not pressed ready? After a minute, point out that the host can just start.
        if (readyPhase == 1 && !lobby.players.isEmpty() && !lobby.allReady()) {
            if (waitingSince == 0) waitingSince = now;
            if (!waitNoted && now - waitingSince > 60000) {
                waitNoted = true;
                Ui.say(engine, "Co-op: still waiting after a minute. Ctrl+Enter starts the battle without waiting any longer.");
            }
        } else {
            waitingSince = 0;
            waitNoted = false;
        }
        if (!session.lobbyDirty && now - lastLobbyMillis < 1000) return;
        session.lobbyDirty = false;
        lastLobbyMillis = now;
        Lobby l = new Lobby();
        l.readyCheck = readyPhase == 1;
        l.hostName = cfg.playerName;
        l.maxShipSize = cfg.maxShipSize;
        for (ShipAPI s : byId.values()) if (!s.isFighter()) l.expected++;
        for (HostSession.Player p : session.getPlayers()) {
            Lobby.Entry e = new Lobby.Entry();
            e.name = p.name;
            e.ready = p.ready;
            e.built = p.built;
            e.skipped = p.skipped;
            e.slot = p.slot;
            e.shipNetId = p.shipNetId;
            try {
                ShipAPI flown = (ShipAPI) p.ship;
                if (flown != null) e.ship = (flown.getName() == null ? "" : flown.getName() + " (") + flown.getHullSpec().getHullName() + (flown.getName() == null ? "" : ")");
            } catch (Throwable t) { /* shown as no ship */ }
            l.players.add(e);
        }
        lobby = l;
        session.broadcastAll(Msg.LOBBY, l.encode());
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        if (disabled || !started || events == null) return;
        try {
            if (!settings.open && cfg.playerTags) PlayerTags.toggleKey(events, engine);
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
                    if ("password".equals(key)) session.setAccess(cfg.password, Mods.gameVersion());
                    else if ("snapshotRate".equals(key)) rate = Math.min(rate, cfg.snapshotRate);
                    else if ("compactUpdates".equals(key)) session.needKeyAll();
                    else if ("compressLink".equals(key)) wingman.net.Conn.compression = cfg.compressLink;
                    else if ("useUdp".equals(key)) wingman.net.Conn.udp = cfg.useUdp;
                    else if ("steadyControls".equals(key)) session.setSteadyControls(cfg.steadyControls);
                    else if ("bandwidthLimit".equals(key)) wingman.net.Budget.bytesPerSecond = Math.max(0, cfg.bandwidthLimit) * 1024;
                    Global.getLogger(WingmanHostPlugin.class).info("Wingman: setting changed in battle: " + key
                            + ("password".equals(key) ? "" : " = " + cfg.get(key)));
                }
                settings.changed.clear();
                return;
            }
        } catch (Throwable t) {
            settings.open = false;
            Global.getLogger(WingmanHostPlugin.class).warn("Wingman: the settings page failed", t);
        }
        if (!session.getPlayers().isEmpty()) {
            for (InputEventAPI e : events) { // host controls: Ctrl+Shift+number removes a player, Ctrl+Shift+T takes a ship back
                if (e.isConsumed() || !e.isKeyDownEvent() || !e.isCtrlDown() || !e.isShiftDown()) continue;
                int key = e.getEventValue();
                try {
                    if (key >= 2 && key <= 10) { kick(key - 1); e.consume(); }
                    else if (key == 20) { takeBack(); e.consume(); }
                } catch (Throwable t) {
                    Global.getLogger(WingmanHostPlugin.class).warn("Wingman: host control failed", t);
                }
            }
        }
        boolean popup = alertTitle != null;
        if (!popup && readyPhase != 1) return;
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isKeyDownEvent() || !Panel.isEnter(e.getEventValue())) continue;
            if (popup) {
                alertTitle = null;
                alertLines.clear();
            } else if (e.isCtrlDown()) {
                forcePressed = true;
            } else {
                enterPressed = true;
            }
            e.consume();
        }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (disabled || !started) return;
        if (cfg.playerTags && !session.getPlayers().isEmpty()) { // who is flying what: P1 is us
            tags.draw(viewport, engine.getPlayerShip(), 1, cfg.playerOutlines);
            for (HostSession.Player p : session.getPlayers()) if (p.ship != null) tags.draw(viewport, (ShipAPI) p.ship, p.slot, cfg.playerOutlines);
        }
        List<String> lines = new ArrayList<String>();
        List<Color> colors = new ArrayList<Color>();
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
            lines.add("The battle is paused. Unpause as usual when you want to carry on.");
            colors.add(Panel.TEXT);
            panel.render(alertTitle, lines, colors, "Enter: dismiss");
            return;
        }
        if (readyPhase != 1) return;
        lines.add("P1  " + lobby.hostName + " (host):  " + lobby.expected + " ships in the battle");
        colors.add(Panel.TEXT);
        for (Lobby.Entry e : lobby.players) {
            lines.add(lobby.describe(e));
            colors.add(!lobby.isSynced(e) ? Panel.WARN : e.skipped > 0 ? Panel.BAD : e.ready ? Panel.GOOD : Panel.TEXT);
        }
        String footer;
        if (lobby.players.isEmpty()) {
            lines.add("Nobody has joined yet. Port " + cfg.port + " is open.");
            colors.add(Panel.DIM);
            footer = "Enter: start without waiting";
        } else if (lobby.allReady()) {
            footer = "Everyone is ready.  Enter: start the battle     Ctrl+Shift+2...: remove that player";
        } else {
            if (waitNoted) {
                lines.add("Waited over a minute. Ctrl+Enter starts now; anyone still loading catches up as the battle runs.");
                colors.add(Panel.WARN);
            }
            footer = "Waiting for players...  Ctrl+Enter: start anyway     Ctrl+Shift+2...: remove that player";
        }
        panel.render("READY CHECK", lines, colors, footer);
    }

    private static boolean eligible(ShipAPI s) {
        // (Modules of multi-part ships and system drones count as ships in their own right: each has its own
        // hull, armor and guns, and a joiner's copy has to be told what becomes of them.)
        if (s == null || s.isHulk() || !s.isAlive() || s.isShuttlePod()) return false;
        // Neutral ships (side 100) too: a mod's objective can park ships there (derelicts waiting to be captured).
        if (s.getOwner() != 0 && s.getOwner() != 1 && s.getOwner() != 100) return false;
        // Fighters are sent too, as long as they belong to a wing; and drones a system launched, which have none
        // (a drone launcher on a fighter, like UAF's Rafflesya, makes fighter-sized drones without a wing).
        return !s.isFighter() || s.getWing() != null || s.isDrone();
    }

    /** Reused every frame for "which of the ones we know are still on the field?", instead of a new set each time. */
    private final Set<Object> present = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private long lastIdleNanos;
    private final byte[] angleScratch = new byte[255];
    private Snapshot.ShipState spare = new Snapshot.ShipState();

    /** Give a ship its number and tell joiners how to build it. */
    private int announce(ShipAPI s, List<Integer> moduleIds) {
        int id = nextNetId++;
        ids.put(s, id);
        byId.put(id, s);
        ShipSpawn sp = buildSpawn(s, id);
        if (moduleIds != null) sp.moduleIds.addAll(moduleIds);
        byte[] msg = sp.encode();
        spawnMsgs.put(id, msg);
        sides.put(id, sp.owner);
        session.broadcast(Msg.SPAWN, msg);
        return id;
    }

    /** The side each ship was last reported on. */
    private final Map<Integer, Integer> sides = new HashMap<Integer, Integer>();

    /**
     * A ship that changed sides mid-battle (a mod's boarding, hacking or mind control): joiners are told, and
     * the description a late joiner gets says the new side. A player flying it loses it if it left the
     * players' side (see checkPilotedShips).
     */
    private void checkSide(int id, ShipAPI s) {
        int now = s.getOwner();
        Integer was = sides.get(id);
        if (was == null || was == now || (now != 0 && now != 1 && now != 100)) return;
        sides.put(id, now);
        try {
            byte[] old = spawnMsgs.get(id);
            if (old != null) {
                ShipSpawn sp = ShipSpawn.decode(old);
                sp.owner = now;
                spawnMsgs.put(id, sp.encode());
            }
        } catch (Throwable t) { /* a late joiner sees the old side until the SIDE below reaches it */ }
        Msg.W w = new Msg.W();
        try { w.out.writeInt(id); w.out.writeByte(now); } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        session.broadcast(Msg.SIDE, w.done());
        Dbg.info(WingmanHostPlugin.class, "Wingman: ship " + id + " (" + s.getHullSpec().getHullId() + ") changed to side " + now);
    }

    private void trackShips() {
        List<ShipAPI> all = engine.getShips();
        present.clear();
        present.addAll(all);

        for (ShipAPI s : all) {
            if (!eligible(s) || ids.containsKey(s)) continue;
            List<Integer> moduleIds = null;
            try {
                if (s.isStationModule()) {
                    // A module is announced by way of the ship it belongs to, just before that ship (below).
                    ShipAPI whole = s.getParentStation();
                    if (whole != null && eligible(whole) && !ids.containsKey(whole)) continue;
                } else {
                    List<ShipAPI> parts = s.getChildModulesCopy();
                    if (parts != null && !parts.isEmpty()) {
                        moduleIds = new ArrayList<Integer>();
                        for (ShipAPI part : parts) {
                            if (!eligible(part)) continue;
                            Integer pid = ids.get(part);
                            if (pid == null) pid = announce(part, null);
                            moduleIds.add(pid);
                        }
                    }
                }
            } catch (Throwable t) { /* announced as a plain ship */ }
            announce(s, moduleIds);
        }

        for (Iterator<Map.Entry<Integer, ShipAPI>> it = byId.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, ShipAPI> e = it.next();
            ShipAPI s = e.getValue();
            boolean gone = !present.contains(s);
            boolean dead = !gone && (s.isHulk() || !s.isAlive());
            if (!gone && !dead) { checkSide(e.getKey(), s); continue; }
            // A destroyed ship leaves a wreck, and the wreck is still part of the battle (see below), unless
            // there are already more wrecks than are worth keeping track of.
            boolean part = false;
            try { part = s.isStationModule() || s.isDrone(); } catch (Throwable t) { /* an ordinary ship */ }
            boolean wreck = dead && !s.isFighter() && !part && wrecks.size() < MAX_WRECKS;
            Msg.W w = new Msg.W();
            try {
                w.out.writeInt(e.getKey());
                w.out.writeByte(!dead ? Msg.DESPAWN_REMOVED : wreck || s.isFighter() ? Msg.DESPAWN_DESTROYED : Msg.DESPAWN_NO_WRECK);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            session.broadcast(Msg.DESPAWN, w.done());
            byte[] description = spawnMsgs.remove(e.getKey());
            sides.remove(e.getKey());
            ids.remove(s);
            it.remove();
            // A destroyed ship leaves a wreck, and the wreck is still part of the battle: it drifts, it blocks
            // shots, it can break up. It keeps the ship's number and goes on being reported (see trackWrecks).
            if (wreck) {
                wrecks.put(e.getKey(), s);
                wreckIds.put(s, e.getKey());
                if (description != null) wreckSpawnMsgs.put(e.getKey(), description);
            }
        }
    }

    private static final int MAX_WRECKS = 150;
    private long lastPieceSearch;
    /** Wrecks and the pieces they have broken into, by number. A wreck keeps the number its ship had. */
    private final Map<Integer, ShipAPI> wrecks = new LinkedHashMap<Integer, ShipAPI>();
    private final Map<ShipAPI, Integer> wreckIds = new IdentityHashMap<ShipAPI, Integer>();
    /** For players who join later: how to build each ship that is now a wreck, and every break-up so far, in order. */
    private final Map<Integer, byte[]> wreckSpawnMsgs = new LinkedHashMap<Integer, byte[]>();
    /** Ships seen dead (they roll the wreck dice once, as they die); trimmed as they leave the field. */
    private final Set<ShipAPI> knownDead = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    /** A dead ship's first cut was worked out with this seed (see trackWrecks). */
    private final Map<ShipAPI, Long> deathSeeds = new IdentityHashMap<ShipAPI, Long>();
    /** Wrecks and pieces that have been cut at least once: their next cut is rolled when it is made. */
    private final Set<ShipAPI> cutBefore = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
    private int seededCuts, unseededCuts, diceLines;

    /** For the wreck dice log: a ship's outline, as segment counts (now / as built). */
    static String outline(ShipAPI s) {
        try {
            com.fs.starfarer.api.combat.BoundsAPI b = s.getExactBounds();
            return b == null ? "none" : b.getSegments().size() + "/" + b.getOrigSegments().size();
        } catch (Throwable t) { return "?"; }
    }

    /**
     * Whether a cut off this wreck is its ship's first, worked out when the ship died. A piece's cuts, and a
     * wreck's after its first, are worked out when they are made.
     */
    private boolean firstCut(ShipAPI parent) {
        if (cutBefore.contains(parent)) return false;
        try { if (parent.isPiece()) return false; } catch (Throwable t) { /* a whole wreck */ }
        return true;
    }

    private static final String WHOLE_KEY = "wingman_whole_wrecks";
    /** Ships given the "stay whole" modifier (see noteDeaths). */
    private final Map<ShipAPI, Boolean> wholeSet = new IdentityHashMap<ShipAPI, Boolean>();

    /** Ships (not fighters, not pieces) that have died since the last look; also keeps "Wrecks stay whole" applied. */
    private List<ShipAPI> noteDeaths() {
        List<ShipAPI> now = new ArrayList<ShipAPI>(2);
        List<ShipAPI> all = engine.getShips();
        boolean whole = cfg != null && cfg.wholeWrecks;
        for (int i = 0, n = all.size(); i < n; i++) {
            ShipAPI s = all.get(i);
            // "Wrecks stay whole": the game decides at a ship's death whether its wreck will break, by this stat.
            if (!s.isFighter() && s.isAlive() && whole != wholeSet.containsKey(s)) {
                try {
                    if (whole) { s.getMutableStats().getBreakProb().modifyMult(WHOLE_KEY, 0f); wholeSet.put(s, Boolean.TRUE); }
                    else { s.getMutableStats().getBreakProb().unmodify(WHOLE_KEY); wholeSet.remove(s); }
                } catch (Throwable t) { wholeSet.put(s, Boolean.TRUE); }
            }
            if (s.isFighter() || (s.isAlive() && !s.isHulk()) || knownDead.contains(s)) continue;
            boolean piece = false;
            try { piece = s.isPiece(); } catch (Throwable t) { /* not a piece */ }
            knownDead.add(s);
            if (!piece) now.add(s);
        }
        if (wholeSet.size() > 2000) wholeSet.keySet().retainAll(new java.util.HashSet<ShipAPI>(all));
        if (knownDead.size() > 2000) { knownDead.retainAll(new java.util.HashSet<ShipAPI>(all)); deathSeeds.keySet().retainAll(knownDead); cutBefore.retainAll(knownDead); }
        return now;
    }

    /** Break-ups so far: { wreck, piece, seed it was cut with (0: not known), its place among that seed's cuts }. */
    private final List<long[]> wreckSplits = new ArrayList<long[]>();

    private static byte[] splitMsg(long[] split) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeInt((int) split[0]);
            w.out.writeInt((int) split[1]);
            w.out.writeLong(split[2]);
            w.out.writeInt((int) split[3]);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }
    private final Map<Integer, Sent> sentWrecks = new HashMap<Integer, Sent>();

    /**
     * Wrecks that are gone are reported gone; a piece that has come off a wreck is given a number and joiners
     * are told which wreck it came from, so they break their copy of that wreck too, along the same line: the
     * seed the game's wreck dice had for that cut goes with it (see WreckDice). Where each wreck and piece is
     * goes out with the ship positions.
     */
    private void trackWrecks() {
        if (!wrecks.isEmpty()) {
            for (Iterator<Map.Entry<Integer, ShipAPI>> it = wrecks.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, ShipAPI> e = it.next();
                if (present.contains(e.getValue())) continue;
                session.broadcast(Msg.DESPAWN, new byte[] { (byte) (e.getKey() >>> 24), (byte) (e.getKey() >>> 16), (byte) (e.getKey() >>> 8),
                        (byte) (int) e.getKey(), Msg.DESPAWN_REMOVED });
                wreckIds.remove(e.getValue());
                wreckSpawnMsgs.remove(e.getKey());
                sentWrecks.remove(e.getKey());
                it.remove();
            }
        }
        if (wreckSplits.size() > 600) { // forget break-ups whose pieces are long gone
            for (Iterator<long[]> it = wreckSplits.iterator(); it.hasNext();) if (!wrecks.containsKey((int) it.next()[1])) it.remove();
        }
        // The wreck dice (see WreckDice). The game rolls them for a wreck's cuts at two moments: the first cut is
        // worked out the moment the ship dies (and made a little later), every later cut when it is made. So
        // after each frame in which the dice were rolled, the host looks at what happened in that frame: ships
        // that died and pieces cut off. If exactly one of those rolled them, the seed is that one's (a death's
        // seed waits for its first cut); if several did, which rolled first can't be told, and their cuts go
        // out without a seed (joiners cut those their own way, as before). Then the dice get a fresh seed.
        long frameSeed = 0L;
        WreckDice.Dice dice = WreckDice.dice();
        int frameDraws = 0;
        if (dice != null && dice.draws > 0) {
            frameSeed = dice.seed;
            frameDraws = dice.draws;
            dice.setSeed(WreckDice.freshSeed());
        }
        List<ShipAPI> diedNow = noteDeaths();
        if (frameSeed != 0L && diceLines < 40) {
            diceLines++;
            StringBuilder sb = new StringBuilder();
            for (ShipAPI d : diedNow) sb.append(' ').append(d.getHullSpec().getHullId()).append('(').append(outline(d)).append(')');
            Global.getLogger(WingmanHostPlugin.class).info("Wingman wreck dice: rolled " + frameDraws + " numbers on seed " + frameSeed
                    + "; died this frame:" + (sb.length() == 0 ? " none" : sb.toString()));
        }
        if (wrecks.isEmpty() || wrecks.size() >= MAX_WRECKS) {
            if (frameSeed != 0L && diedNow.size() == 1) deathSeeds.put(diedNow.get(0), frameSeed);
            return;
        }
        // Looking for new pieces means going through every ship on the field: four times a second is plenty,
        // unless the dice were just rolled.
        long nowNanos = System.nanoTime();
        if (frameSeed == 0L && nowNanos - lastPieceSearch < 250000000L) return;
        lastPieceSearch = nowNanos;
        List<long[]> found = new ArrayList<long[]>();
        List<ShipAPI> foundParents = new ArrayList<ShipAPI>();
        List<ShipAPI> all = engine.getShips();
        for (int i = 0, n = all.size(); i < n; i++) {
            ShipAPI s = all.get(i);
            if (!s.isHulk() || s.isFighter() || wreckIds.containsKey(s) || ids.containsKey(s)) continue;
            boolean piece = false;
            try { piece = s.isPiece(); } catch (Throwable t) { /* not a piece */ }
            if (!piece) continue;
            // Which wreck did it come off? The nearest one it is still touching.
            int parent = -1;
            float best = Float.MAX_VALUE;
            for (Map.Entry<Integer, ShipAPI> e : wrecks.entrySet()) {
                ShipAPI w = e.getValue();
                float dx = w.getLocation().x - s.getLocation().x, dy = w.getLocation().y - s.getLocation().y;
                float d = dx * dx + dy * dy, reach = w.getCollisionRadius() + s.getCollisionRadius() + 150f;
                if (d < best && d < reach * reach) { best = d; parent = e.getKey(); }
            }
            if (parent < 0) { wreckIds.put(s, -1); continue; } // nothing to say it belongs to; don't look at it again
            int id = nextNetId++;
            wrecks.put(id, s);
            wreckIds.put(s, id);
            found.add(new long[] { parent, id, 0L, 0L });
            foundParents.add(wrecks.get(parent));
            if (wrecks.size() >= MAX_WRECKS) break;
        }
        // Which seed each new piece was cut with: a first cut, the seed of its ship's death (if that was known);
        // a later cut, this frame's seed if it was the only roll of the frame.
        int rolls = diedNow.size();
        for (ShipAPI parentShip : foundParents) if (parentShip != null && !firstCut(parentShip)) rolls++;
        if (frameSeed != 0L && rolls == 1 && diedNow.size() == 1) deathSeeds.put(diedNow.get(0), frameSeed);
        for (int k = 0; k < found.size(); k++) {
            long[] split = found.get(k);
            ShipAPI parentShip = foundParents.get(k);
            if (parentShip != null && firstCut(parentShip)) {
                Long seed = deathSeeds.remove(parentShip);
                if (seed != null) split[2] = seed;
            } else if (frameSeed != 0L && rolls == 1) {
                split[2] = frameSeed;
            }
            if (parentShip != null) cutBefore.add(parentShip);
            if (split[2] != 0L) seededCuts++; else unseededCuts++;
            if (diceLines < 40) {
                diceLines++;
                Global.getLogger(WingmanHostPlugin.class).info("Wingman wreck dice: piece " + split[1] + " off wreck " + split[0]
                        + (parentShip == null ? "" : " (" + parentShip.getHullSpec().getHullId() + ", outline now " + outline(parentShip) + ")")
                        + (split[2] == 0L ? ": no seed (" + rolls + " rolls this frame" + (parentShip != null && !cutBefore.contains(parentShip) ? "" : "") + ")"
                        : ": seed " + split[2] + (frameSeed == split[2] ? " (" + frameDraws + " numbers)" : " (rolled when it died)")));
            }
            wreckSplits.add(split);
            session.broadcast(Msg.WRECK_SPLIT, splitMsg(split));
        }
        if (wreckIds.size() > MAX_WRECKS * 4) { // forget the ones marked "not ours" that have since left the field
            for (Iterator<Map.Entry<ShipAPI, Integer>> it = wreckIds.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ShipAPI, Integer> e = it.next();
                if (e.getValue() < 0 && !present.contains(e.getKey())) it.remove();
            }
        }
    }

    private ShipSpawn buildSpawn(ShipAPI s, int id) {
        ShipSpawn sp = new ShipSpawn();
        sp.netId = id;
        sp.owner = s.getOwner();
        sp.hullId = s.getHullSpec().getHullId();
        sp.name = s.getName() == null ? "" : s.getName();
        sp.x = s.getLocation().x;
        sp.y = s.getLocation().y;
        sp.facing = s.getFacing();
        sp.cr = s.getCurrentCR();
        try {
            if (s.isStationModule()) {
                sp.kind = ShipSpawn.MODULE;
                Integer whole = s.getParentStation() == null ? null : ids.get(s.getParentStation());
                sp.parentId = whole == null ? -1 : whole;
                sp.slotId = s.getStationSlot() == null ? "" : s.getStationSlot().getId();
            } else if (s.isDrone()) {
                sp.kind = ShipSpawn.DRONE;
                Integer from = s.getDroneSource() == null ? null : ids.get(s.getDroneSource());
                sp.parentId = from == null ? -1 : from;
            }
        } catch (Throwable t) { /* built as an ordinary ship */ }
        if (s.isFighter() && s.getWing() != null) {
            FighterWingAPI wing = s.getWing();
            sp.wingId = wing.getWingId();
            Integer wn = wingIds.get(wing);
            if (wn == null) {
                if (wingIds.size() > 2000) wingIds.clear(); // very long battle: old wings are long gone
                wn = nextWingId++;
                wingIds.put(wing, wn);
            }
            sp.wingNet = wn;
            try {
                ShipAPI carrier = wing.getSourceShip();
                Integer cid = carrier == null ? null : ids.get(carrier);
                if (cid != null) {
                    sp.carrierId = cid;
                    // Which hangar, counting every hangar the carrier has (empty ones too), as on a joiner's copy.
                    List<com.fs.starfarer.api.combat.FighterLaunchBayAPI> bays = carrier.getLaunchBaysCopy();
                    sp.bay = bays == null || wing.getSource() == null ? -1 : bays.indexOf(wing.getSource());
                }
            } catch (Throwable t) { /* a wing without a carrier is still shown */ }
        }
        ShipVariantAPI v = s.getVariant();
        sp.vents = v.getNumFluxVents();
        sp.caps = v.getNumFluxCapacitors();
        sp.hullMods.addAll(v.getHullMods());
        try {
            sp.sMods.addAll(v.getSMods());
            for (String m : v.getPermaMods()) if (!sp.sMods.contains(m)) sp.permaMods.add(m);
            sp.sModdedBuiltIns.addAll(v.getSModdedBuiltIns());
            sp.suppressedMods.addAll(v.getSuppressedMods());
        } catch (Throwable t) { /* plain hullmod list still goes */ }
        try {
            PersonAPI captain = s.getCaptain();
            // A fighter normally has no officer of its own (if it reports one, it is its carrier's). A mod may
            // give one its own, though; then it is sent like any ship's, and the fighter is kept as closely in
            // step as a ship is (stats, script values).
            boolean own = captain != null && !captain.isDefault();
            if (own && s.isFighter()) {
                ShipAPI carrier = null;
                try { carrier = s.getWing() == null ? null : s.getWing().getSourceShip(); } catch (Throwable t) { /* no carrier */ }
                if (carrier != null && carrier.getCaptain() == captain) own = false;
                if (own) {
                    officered.add(id);
                    if (officeredSeen++ < 10) Dbg.info(WingmanHostPlugin.class, "Wingman fighters: " + s.getHullSpec().getHullId()
                            + " has an officer of its own; it is synced like a ship");
                }
            }
            if (own) {
                sp.hasCaptain = true;
                if (captain.getName() != null) {
                    sp.captainFirst = captain.getName().getFirst() == null ? "" : captain.getName().getFirst();
                    sp.captainLast = captain.getName().getLast() == null ? "" : captain.getName().getLast();
                }
                sp.captainPortrait = captain.getPortraitSprite() == null ? "" : captain.getPortraitSprite();
                sp.captainAiCore = captain.getAICoreId() == null ? "" : captain.getAICoreId();
                sp.captainLevel = captain.getStats().getLevel();
                for (MutableCharacterStatsAPI.SkillLevelAPI sk : captain.getStats().getSkillsCopy()) {
                    if (sk.getLevel() <= 0f || sk.getSkill() == null) continue;
                    sp.skillIds.add(sk.getSkill().getId());
                    sp.skillLevels.add(sk.getLevel());
                }
            }
        } catch (Throwable t) { /* a ship without its officer still flies */ }
        try {
            // The commander of the fleet the ship belongs to (allied fleets on one side each have their own):
            // their fleet-wide skills apply to this ship, so the joiner's copy needs them too.
            PersonAPI commander = null;
            ShipAPI of = s;
            if (s.isFighter() && s.getWing() != null && s.getWing().getSourceShip() != null) of = s.getWing().getSourceShip();
            if (of.getFleetMember() != null) commander = of.getFleetMember().getFleetCommander();
            if (commander == null) commander = engine.getFleetManager(s.getOwner()).getFleetCommander();
            if (commander != null && !commander.isDefault()) {
                for (MutableCharacterStatsAPI.SkillLevelAPI sk : commander.getStats().getSkillsCopy()) {
                    if (sk.getLevel() <= 0f || sk.getSkill() == null || sp.fleetSkillIds.size() >= 80) continue;
                    sp.fleetSkillIds.add(sk.getSkill().getId());
                    sp.fleetSkillLevels.add(sk.getLevel());
                }
            }
        } catch (Throwable t) { /* without them the ship's stats are still corrected to ours */ }
        sp.maxFlux = s.getMaxFlux();
        sp.maxHull = s.getMaxHitpoints();
        sp.maxSpeed = s.getMaxSpeed();
        for (WeaponAPI w : s.getAllWeapons()) {
            WeaponSlotAPI slot = w.getSlot();
            if (slot == null) { sp.weaponOrder.add(""); sp.weaponOrderIds.add(""); continue; }
            sp.weaponOrder.add(slot.getId());
            sp.weaponOrderIds.add(w.getId() == null ? "" : w.getId());
            if (!slot.isBuiltIn() && !slot.isDecorative() && !slot.isSystemSlot()) {
                sp.weaponSlots.add(slot.getId());
                sp.weaponIds.add(w.getId());
            }
        }
        if (!s.isFighter()) {
            try {
                List<String> wings = v.getWings();
                for (int i = 0; wings != null && i < wings.size(); i++) sp.wings.add(wings.get(i) == null ? "" : wings.get(i));
            } catch (Throwable t) { /* carrier controls on the joiner's side are a nicety */ }
            // Autofire switches start as the ship's design has them. What the groups are doing right now is no
            // guide: while the AI flies a ship it has most groups on autofire, whatever the design says.
            List<WeaponGroupAPI> live = s.getWeaponGroupsCopy();
            List<com.fs.starfarer.api.loading.WeaponGroupSpec> design = null;
            try {
                design = v.getWeaponGroups();
                if (design == null || design.size() != live.size()) design = null;
            } catch (Throwable t) { design = null; }
            for (int gi = 0; gi < live.size(); gi++) {
                WeaponGroupAPI g = live.get(gi);
                ShipSpawn.Group sg = new ShipSpawn.Group();
                sg.type = g.getType() == null ? "LINKED" : g.getType().name();
                sg.autofire = design != null && design.get(gi).isAutofireOnByDefault();
                for (WeaponAPI w : g.getWeaponsCopy()) if (w.getSlot() != null) sg.slots.add(w.getSlot().getId());
                sp.groups.add(sg);
            }
        }
        return sp;
    }

    private static float turn(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Has it strayed from where a joiner, moving it on at the last speed it was told, believes it to be? */
    private boolean strayed(Sent last, float x, float y, float facing, float posLimit, float turnLimit) {
        float dt = gameClock - last.time;
        if (dt < 0f) return true;
        float ex = last.x + last.vx * dt - x, ey = last.y + last.vy * dt - y;
        if (ex * ex + ey * ey > posLimit * posLimit) return true;
        return Math.abs(turn(last.facing + last.angVel * dt, facing)) > turnLimit;
    }

    /** Speed as the joiner will read it back after packing, so both sides predict from the same number. */
    private static float asSent(float v, float scale) { return Snapshot.packSpeed(v, scale) / scale; }

    /** This PC's addresses, the Radmin / Hamachi style ones first, for the host to read out to friends. */
    static String myAddresses() {
        try {
            List<String> vpn = new ArrayList<String>(), lan = new ArrayList<String>();
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("26.") || ip.startsWith("25.")) vpn.add(ip); else lan.add(ip);
                }
            }
            vpn.addAll(lan);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vpn.size() && i < 4; i++) sb.append(i == 0 ? "" : ",  ").append(vpn.get(i));
            return sb.toString();
        } catch (Throwable t) {
            return ""; // the game may not let a mod list network cards; the host can still look the address up
        }
    }

    private boolean fogBroken;
    private int trimmed, halved, scriptEnds;

    /** True if nobody on the players' side can see it: it is in the fog of war, so on nobody's radar. */
    private boolean unseen(com.fs.starfarer.api.combat.CombatEntityAPI e, Sent last) {
        if (fogBroken || !cfg.trimUnseen) return false;
        // Whether something is in sight changes slowly: asked four times a second per thing, not for every update.
        long now = System.nanoTime();
        if (last.sightAskedAt != 0 && now - last.sightAskedAt < 250000000L) return last.outOfSight;
        last.sightAskedAt = now;
        try {
            com.fs.starfarer.api.combat.FogOfWarAPI fog = engine.getFogOfWar(0);
            return last.outOfSight = fog != null && !fog.isVisible(e);
        } catch (Throwable t) {
            fogBroken = true; // can't tell: treat everything as in view
            Global.getLogger(WingmanHostPlugin.class).warn("Wingman: the fog of war can't be read, so nothing is trimmed from updates", t);
            return false;
        }
    }

    private Snapshot buildSnapshot(boolean full) {
        boolean compact = cfg.compactUpdates && !codecBroken;
        Snapshot snap = new Snapshot();
        snap.tick = tick++;
        snap.paused = engine.isPaused();
        try {
            snap.timeMult = engine.getTimeMult().getModifiedValue();
        } catch (Throwable t) { /* stays at normal speed */ }
        Integer flag = ids.get(engine.getPlayerShip());
        snap.hostFlagship = flag == null ? -1 : flag;
        snap.hostTime = gameClock;
        List<Integer> flown = new ArrayList<Integer>();
        List<HostSession.Player> pilots = new ArrayList<HostSession.Player>();
        for (HostSession.Player p : session.getPlayers()) if (p.shipNetId >= 0) { flown.add(p.shipNetId); pilots.add(p); }
        snap.piloted = new int[Math.min(flown.size(), 255)];
        snap.pilotedSeq = new int[snap.piloted.length];
        snap.pilotedAge = new float[snap.piloted.length];
        snap.pilotedSysAmmo = new int[snap.piloted.length];
        for (int i = 0; i < snap.piloted.length; i++) {
            snap.piloted[i] = flown.get(i);
            snap.pilotedSeq[i] = pilots.get(i).input.appliedSeq & 0xFFFF;
            snap.pilotedAge[i] = pilots.get(i).input.appliedSeq < 0 ? 2.55f : pilots.get(i).input.sinceApplied;
            snap.pilotedSysAmmo[i] = 255;
            try {
                ShipAPI flownShip = (ShipAPI) pilots.get(i).ship;
                com.fs.starfarer.api.combat.ShipSystemAPI fs = flownShip == null ? null : flownShip.getSystem();
                if (fs != null) snap.pilotedSysAmmo[i] = fs.getMaxAmmo() <= 0 || fs.getMaxAmmo() > 250 ? 254 : Math.max(0, fs.getAmmo());
            } catch (Throwable t) { /* the joiner keeps its own count */ }
        }
        // Even a ship that is doing nothing new is repeated twice a second (a missile once a second), so a
        // joiner is never left guessing for long.
        int refresh = Math.max(2, cfg.snapshotRate / 2);
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            int id = e.getKey();
            Snapshot.ShipState st = spare; // reused until a ship actually needs sending
            st.clear();
            st.netId = id;
            st.x = s.getLocation().x;
            st.y = s.getLocation().y;
            st.vx = s.getVelocity().x;
            st.vy = s.getVelocity().y;
            st.facing = s.getFacing();
            st.angVel = s.getAngularVelocity();
            st.hull = s.getMaxHitpoints() <= 0 ? 1f : s.getHitpoints() / s.getMaxHitpoints();
            st.cr = s.getCurrentCR();
            FluxTrackerAPI flux = s.getFluxTracker();
            st.flux = flux.getCurrFlux();
            st.hardFlux = flux.getHardFlux();
            ShieldAPI shield = s.getShield();
            boolean defense = shield != null ? shield.isOn() : s.isPhased();
            if (defense) st.flags |= Snapshot.F_DEFENSE;
            if (flux.isOverloaded()) st.flags |= Snapshot.F_OVERLOADED;
            if (flux.isVenting()) st.flags |= Snapshot.F_VENTING;
            if (s.getSystem() != null && s.getSystem().isActive()) st.flags |= Snapshot.F_SYSTEM;
            if (s.isPhased()) st.flags |= Snapshot.F_PHASED;
            // Both systems, stage by stage, so a joiner's copy switches its own on and off at the same moments
            // and whatever script the system has runs there on the host's timing.
            try {
                if (s.getSystem() != null) { st.system = s.getSystem().getState().ordinal(); st.systemLevel = s.getSystem().getEffectLevel(); }
                if (s.getPhaseCloak() != null) { st.cloak = s.getPhaseCloak().getState().ordinal(); st.cloakLevel = s.getPhaseCloak().getEffectLevel(); }
            } catch (Throwable t) { /* a system that can't be read is simply not mirrored */ }
            int systems = st.system | (st.cloak << 3) | (Math.round(st.systemLevel * 40f) << 6) | (Math.round(st.cloakLevel * 40f) << 12);
            if (shield != null && shield.isOn()) st.shieldFacing = shield.getFacing();
            List<WeaponAPI> weapons = s.getAllWeapons();
            int n = Math.min(weapons.size(), 255);
            // Angles are measured from the hull, so a fixed gun on a turning ship counts as "not moved".
            // Worked out into a shared scratch buffer; a copy is only made for a ship whose angles get sent.
            byte[] angles = angleScratch;
            FireTrack ft = fireTracks.get(id);
            for (int i = 0; i < n; i++) {
                WeaponAPI w = weapons.get(i);
                angles[i] = Snapshot.packAngle(w.getCurrAngle() - st.facing);
                if (i < 64 && (ft != null ? (ft.wants & (1L << i)) != 0 : w.isFiring())) st.firing |= 1L << i;
            }
            if (ft != null) {
                if (compact) st.shotTotals = ft.totals.clone(); // running totals: these survive a lost update
                st.firing |= ft.fired;
                ft.fired = 0;
                int counted = 0;
                for (int i = 0; i < ft.shots.length; i++) if (ft.shots[i] != 0) counted++;
                if (counted > 0) {
                    st.shots = new byte[counted * 2];
                    for (int i = 0, k = 0; i < ft.shots.length; i++) {
                        if (ft.shots[i] == 0) continue;
                        st.shots[k++] = (byte) i;
                        st.shots[k++] = ft.shots[i];
                        st.firing |= 1L << i;
                        ft.shots[i] = 0;
                    }
                }
            }

            // How the packer may treat it: a ship a player flies goes exactly; for the rest, flux is only sent
            // again once the bar has moved by a hundredth.
            st.exact = flown.contains(id);
            st.fluxStep = Math.max(5f, s.getMaxFlux() * 0.01f);
            int hull = Math.round(Snapshot.clamp(st.hull, 0f, 1f) * 65535f), cr = Math.round(Snapshot.clamp(st.cr, 0f, 1f) * 200f);
            Sent last = sentShips.get(id);
            // A system starting or stopping, or a change of state, is when a ship's stats move: have them looked at now.
            if (extras != null && last != null && (systems != last.systems || st.flags != last.flags)) extras.touch(id);
            possibleStates++;
            // An enemy that nobody on the players' side can see (everyone shares one fog of war) can't be aimed
            // at or watched, so it is only reported once a second, unless it is firing or being hit. The moment
            // it comes into view it is sent in full again.
            boolean hidden = !full && last != null && s.getOwner() == 1 && unseen(s, last);
            if (hidden && st.firing == 0L && !last.fired && hull == last.hull && tick - last.tick < refresh * 2) {
                last.hidden = true;
                trimmed++;
                continue;
            }
            // Fighters are the bulk of what is on the field, and a joiner eases each one along between updates
            // anyway: they go out in every other update (half of them in each, so the load is even). One that
            // fires or is hit is not made to wait. Their weapon angles are tracked exactly as for ships.
            if (!full && !cfg.sendEverything && last != null && s.isFighter() && ((tick + id) & 1) == 1 && st.firing == 0L && !last.fired && hull == last.hull) {
                halved++;
                continue;
            }
            boolean aimed = last == null || last.angles == null || last.angles.length != n || tick - last.anglesTick >= refresh * 4
                    || (last.hidden && !hidden);
            for (int i = 0; !aimed && i < n; i++) {
                int d = (angles[i] - last.angles[i]) & 0xFF;
                if (d > 1 && d < 255) aimed = true;
            }
            boolean send = full || cfg.sendEverything || last == null || aimed || flown.contains(id) || tick - last.tick >= refresh
                    || st.firing != 0L || last.fired || hull != last.hull || cr != last.cr || st.flags != last.flags || systems != last.systems
                    || strayed(last, st.x, st.y, st.facing, 1.5f, 1f);
            if (!send) {
                float step = Math.max(5f, s.getMaxFlux() * 0.01f); // a flux bar moving by a hundredth is worth showing
                send = Math.abs(st.flux - last.flux) > step || Math.abs(st.hardFlux - last.hardFlux) > step
                        || (st.flux <= 0f) != (last.flux <= 0f)
                        || (defense && shield != null && Math.abs(turn(last.shieldFacing, st.shieldFacing)) > 3f);
            }
            if (!send) continue;
            if (last == null) { last = new Sent(); sentShips.put(id, last); }
            if (aimed || full) {
                byte[] copy = new byte[n];
                System.arraycopy(angles, 0, copy, 0, n);
                st.weaponAngles = copy; last.angles = copy; last.anglesTick = tick;
            }
            // The packer that works on changes compares angles itself, player by player (one of them may have
            // missed the update that carried the last change), so it is always given them: the angles as last
            // judged to have moved, so that a turret trembling by one step is not news every time.
            if (compact) st.weaponAngles = last.angles;
            last.x = st.x; last.y = st.y; last.facing = st.facing; last.time = gameClock; last.tick = tick;
            last.vx = asSent(st.vx, Snapshot.SPEED); last.vy = asSent(st.vy, Snapshot.SPEED); last.angVel = asSent(st.angVel, Snapshot.TURN);
            last.systems = systems;
            last.hull = hull; last.cr = cr; last.flags = st.flags; last.fired = st.firing != 0L; last.hidden = hidden;
            last.flux = st.flux; last.hardFlux = st.hardFlux; last.shieldFacing = st.shieldFacing;
            sentStates++;
            snap.ships.add(st);
            spare = new Snapshot.ShipState();
        }
        // Wrecks drift and tumble in straight lines, so they cost next to nothing: one is only sent when
        // something knocks it off course, and otherwise once every four seconds.
        for (Map.Entry<Integer, ShipAPI> e : wrecks.entrySet()) {
            ShipAPI s = e.getValue();
            int id = e.getKey();
            Sent last = sentWrecks.get(id);
            float x = s.getLocation().x, y = s.getLocation().y, facing = s.getFacing();
            if (!full && last != null && tick - last.tick < refresh * 8 && !strayed(last, x, y, facing, 3f, 2f)) continue;
            Snapshot.ShipState st = new Snapshot.ShipState();
            st.netId = id;
            st.x = x; st.y = y; st.facing = facing;
            st.vx = s.getVelocity().x; st.vy = s.getVelocity().y; st.angVel = s.getAngularVelocity();
            if (last == null) { last = new Sent(); sentWrecks.put(id, last); }
            last.x = x; last.y = y; last.facing = facing; last.time = gameClock; last.tick = tick;
            last.vx = asSent(st.vx, Snapshot.SPEED); last.vy = asSent(st.vy, Snapshot.SPEED); last.angVel = asSent(st.angVel, Snapshot.TURN);
            snap.ships.add(st);
        }
        for (Map.Entry<Integer, MissileAPI> e : missilesById.entrySet()) {
            MissileAPI m = e.getValue();
            int id = e.getKey();
            Snapshot.MissileState ms = new Snapshot.MissileState();
            ms.id = id;
            ms.x = m.getLocation().x;
            ms.y = m.getLocation().y;
            ms.vx = m.getVelocity().x;
            ms.vy = m.getVelocity().y;
            ms.facing = m.getFacing();
            ms.angVel = m.getAngularVelocity();
            if (m.isFizzling() || m.isFading()) ms.flags |= Snapshot.M_FIZZLING;
            try { ms.flightTime = m.getFlightTime(); } catch (Throwable t) { /* the joiner keeps its own count */ }
            Sent last = sentMissiles.get(id);
            possibleStates++;
            // An enemy missile out of everyone's sight: every two seconds is plenty until it comes into view.
            boolean hidden = !full && last != null && m.getOwner() == 1 && unseen(m, last);
            if (hidden && tick - last.tick < refresh * 4 && ms.flags == last.flags) { last.hidden = true; trimmed++; continue; }
            boolean revealed = last != null && last.hidden && !hidden;
            // A rocket flying straight needs no updates at all; one that is steering gets them as it turns.
            if (!full && !cfg.sendEverything && !revealed && last != null && tick - last.tick < refresh * 2 && ms.flags == last.flags
                    && !strayed(last, ms.x, ms.y, ms.facing, 3f, 4f)
                    // its flight clock normally just runs on; a script that winds it back or forward is news
                    && Math.abs(last.flight + (gameClock - last.time) - ms.flightTime) < 0.25f) continue;
            if (last == null) { last = new Sent(); sentMissiles.put(id, last); }
            last.x = ms.x; last.y = ms.y; last.facing = ms.facing; last.time = gameClock; last.tick = tick; last.flags = ms.flags; last.flight = ms.flightTime;
            last.hidden = hidden;
            last.vx = asSent(ms.vx, Snapshot.SPEED); last.vy = asSent(ms.vy, Snapshot.SPEED); last.angVel = asSent(ms.angVel, Snapshot.TURN);
            sentStates++;
            snap.missiles.add(ms);
        }
        if (sentShips.size() > byId.size() + 100) sentShips.keySet().retainAll(byId.keySet());
        if (sentMissiles.size() > missilesById.size() + 200) sentMissiles.keySet().retainAll(missilesById.keySet());
        return snap;
    }

    /**
     * Snapshots go out 20 times a second, but a gun that fires a single shot is only "firing" for an instant,
     * usually between two snapshots, so asking "is it firing right now?" at snapshot time misses most single
     * shots (cannons, torpedoes). Instead, every frame, note any weapon that is firing, whose cooldown just
     * restarted, or whose ammo just dropped. The next snapshot reports all of them and the list starts again.
     */
    private void trackFiring() {
        if (session.getPlayers().isEmpty()) return;
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            List<WeaponAPI> weapons = e.getValue().getAllWeapons();
            int n = Math.min(weapons.size(), 64);
            FireTrack ft = fireTracks.get(e.getKey());
            if (ft == null || ft.cooldown.length != n) {
                ft = new FireTrack();
                ft.cooldown = new float[n];
                ft.shots = new byte[n];
                ft.totals = new byte[n];
                ft.ammo = new int[n];
                ft.charge = new float[n];
                for (int i = 0; i < n; i++) { ft.cooldown[i] = weapons.get(i).getCooldownRemaining(); ft.ammo[i] = weapons.get(i).getAmmo(); ft.charge[i] = weapons.get(i).getChargeLevel(); }
                fireTracks.put(e.getKey(), ft);
            }
            for (int i = 0; i < n; i++) {
                WeaponAPI w = weapons.get(i);
                float cd = w.getCooldownRemaining();
                int ammo = w.getAmmo();
                boolean wants = i < ft.charge.length && wantsShot(w, ft.charge[i], cd);
                if (i < 64) { if (wants) ft.wants |= 1L << i; else ft.wants &= ~(1L << i); }
                if (i < ft.charge.length) ft.charge[i] = w.getChargeLevel();
                if (wants || cd > ft.cooldown[i] + 0.02f || ammo < ft.ammo[i]) ft.fired |= 1L << i;
                if (cd > ft.cooldown[i] + 0.02f && ft.shots[i] < 100) ft.shots[i]++;
                if (cd > ft.cooldown[i] + 0.02f) ft.totals[i]++;
                if (ammo != ft.ammo[i]) damage.touch(e.getKey()); // its ammo count is news for the armor/ammo report
                ft.cooldown[i] = cd;
                ft.ammo[i] = ammo;
            }
        }
        if (fireTracks.size() > byId.size() + 50) fireTracks.keySet().retainAll(byId.keySet());
    }

    private int missileRound;

    private void trackMissiles() {
        missileRound++;
        List<MissileAPI> all = engine.getMissiles();
        present.clear();
        present.addAll(all);

        List<MissileAPI> born = null; // missiles that appeared this frame
        for (MissileAPI m : all) {
            if (missileIds.containsKey(m)) continue;
            if (born == null) born = new ArrayList<MissileAPI>();
            born.add(m);
            String weaponId = weaponIdOf(m);
            if (weaponId == null) { missileIds.put(m, -1); continue; } // script-spawned, nothing to rebuild it from
            int id = nextMissileId++;
            missileIds.put(m, id);
            missilesById.put(id, m);
            Integer source = ids.get(m.getSource());
            int weaponIndex = -1; // which of the launching ship's weapons fired it, in the order joiners know them
            try {
                if (m.getSource() != null && m.getWeapon() != null) weaponIndex = m.getSource().getAllWeapons().indexOf(m.getWeapon());
            } catch (Throwable t) { /* the joiner will look the weapon up by name instead */ }
            Msg.W w = new Msg.W();
            try {
                w.out.writeInt(id);
                w.out.writeUTF(weaponId);
                w.out.writeInt(source == null ? -1 : source);
                w.out.writeShort(weaponIndex);
                // The exact make of missile (a warhead has one even when no weapon fires it), and whether the
                // joiner should run the missile's own script for the show it puts on.
                String proj = null;
                try { proj = m.getProjectileSpecId(); } catch (Throwable t) { /* built from the weapon instead */ }
                boolean scripted = runsOwnScript(m);
                if (scripted) scriptedTargets.put(id, -1);
                w.out.writeUTF(proj == null ? "" : proj);
                w.out.writeBoolean(scripted);
                w.out.writeByte(m.getOwner());
                w.out.writeFloat(m.getLocation().x);
                w.out.writeFloat(m.getLocation().y);
                w.out.writeFloat(m.getFacing());
                w.out.writeFloat(m.getVelocity().x);
                w.out.writeFloat(m.getVelocity().y);
                // Its lifetime: how long it has flown, how long it can, and when it arms. The host's numbers rule.
                float flown = 0f, most = 0f, arming = -1f;
                try { flown = m.getFlightTime(); most = m.getMaxFlightTime(); arming = m.getArmingTime(); } catch (Throwable t) { /* joiner uses its own */ }
                w.out.writeFloat(flown);
                w.out.writeFloat(most);
                w.out.writeFloat(arming);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            byte[] msg = w.done();
            missileSpawnMsgs.put(id, msg);
            session.broadcast(Msg.MISSILE_SPAWN, msg);
        }

        for (Iterator<Map.Entry<MissileAPI, Integer>> it = missileIds.entrySet().iterator(); it.hasNext();) {
            Map.Entry<MissileAPI, Integer> e = it.next();
            MissileAPI m = e.getKey();
            int id = e.getValue();
            if (present.contains(m)) {
                Integer told = id < 0 || ((missileRound + id) % 12) != 0 ? null : scriptedTargets.get(id); // looked at 5 times a second
                if (told != null) { // a script-steered missile: keep joiners' copies aimed at the same ship
                    int now = -1;
                    try {
                        Object ai = aiOf(m);
                        if (ai instanceof com.fs.starfarer.api.combat.GuidedMissileAI) {
                            Object t = ((com.fs.starfarer.api.combat.GuidedMissileAI) ai).getTarget();
                            Integer tid = t instanceof ShipAPI ? ids.get(t) : null;
                            if (tid != null) now = tid;
                        }
                    } catch (Throwable t) { /* no target to pass on */ }
                    if (now != told) {
                        scriptedTargets.put(id, now);
                        session.broadcast(Msg.MISSILE_TARGET, Msg.twoInts(id, now));
                    }
                }
                if (id >= 0 && (m.isFizzling() || m.isFading())) expiring.add(id);
                continue;
            }
            it.remove();
            if (id < 0) continue;
            // Did it strike something? Then joiners are told what and exactly where, so their copy of the
            // missile goes off against the same target at the same spot, on-hit script and all. On a joiner's
            // screen a missile never hits anything by itself; it waits for this.
            if (!expiring.contains(id)) {
                boolean hit = false;
                try { hit = m.didDamage(); } catch (Throwable t) { /* treated as shot down */ }
                if (hit) {
                    Msg.W hw = new Msg.W();
                    try {
                        Object struck = null;
                        try { struck = m.getDamageTarget(); } catch (Throwable t) { /* unknown target */ }
                        Integer tid = struck instanceof ShipAPI ? ids.get(struck) : null;
                        boolean onShield = false;
                        try {
                            if (struck instanceof ShipAPI) {
                                ShieldAPI sh = ((ShipAPI) struck).getShield();
                                onShield = sh != null && sh.isOn() && sh.isWithinArc(m.getLocation());
                            }
                        } catch (Throwable t) { /* hull hit */ }
                        hw.out.writeInt(id);
                        hw.out.writeInt(tid == null ? -1 : tid);
                        hw.out.writeFloat(m.getLocation().x);
                        hw.out.writeFloat(m.getLocation().y);
                        hw.out.writeFloat(m.getFacing());
                        hw.out.writeFloat(m.getVelocity().x);
                        hw.out.writeFloat(m.getVelocity().y);
                        hw.out.writeBoolean(onShield);
                    } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
                    session.broadcast(Msg.MISSILE_HIT, hw.done());
                    missilesById.remove(id);
                    missileSpawnMsgs.remove(id);
                    scriptedTargets.remove(id);
                    continue;
                }
            }
            Msg.W w = new Msg.W();
            try {
                w.out.writeInt(id);
                // A missile that vanishes at the very spot where new ones appear in the same frame has not been
                // shot down: it has released its next stage. Joiners take it away quietly instead of blowing it up.
                boolean quiet = expiring.remove(id);
                for (int i = 0; !quiet && born != null && i < born.size(); i++) {
                    MissileAPI b = born.get(i);
                    float dx = b.getLocation().x - m.getLocation().x, dy = b.getLocation().y - m.getLocation().y;
                    if (b.getSource() == m.getSource() && dx * dx + dy * dy < 80f * 80f) quiet = true;
                }
                // Gone with its hull intact, having hit nothing and with fuel left: nothing in the game does that
                // by itself. A script removed it, typically to set off its own effect (a timed fuse, a custom
                // burst). Joiners then let their copy of that script do the same, instead of a plain explosion.
                boolean byScript = false;
                if (!quiet) {
                    try { byScript = m.getHitpoints() > 0f && !m.didDamage() && !m.isFizzling() && !m.isFading(); }
                    catch (Throwable t) { /* treated as shot down */ }
                }
                if (byScript) scriptEnds++;
                w.out.writeByte(quiet ? Msg.DESPAWN_REMOVED : byScript ? Msg.DESPAWN_SCRIPTED : Msg.DESPAWN_DESTROYED);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            session.broadcast(Msg.MISSILE_DESPAWN, w.done());
            missilesById.remove(id);
            missileSpawnMsgs.remove(id);
            scriptedTargets.remove(id);
        }
    }

    /** Per kind of shot: does it carry an on-hit script? Looked up once. */
    private final Map<String, Boolean> shotScripted = new HashMap<String, Boolean>();
    /** Shots in flight that carry an on-hit script, and the frame each was last seen on. */
    private final Map<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]> shots =
            new IdentityHashMap<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]>();
    private int shotFrame, shotsThisSecond;
    private boolean shotsBroken, shotTurn;
    private long shotSecond;

    /**
     * Ordinary bullets are left to each PC: there are far too many to report one by one, and a hit a frame
     * early or late on a joiner's screen changes nothing. A shot whose weapon has an on-hit script is another
     * matter, because the script is the visible part (an arc, a blast, a mod's special effect). Those are
     * watched here, and when one strikes something joiners are told which gun, what it hit and exactly where,
     * so the effect plays there and then on their screens too. Capped at 120 a second.
     */
    private void trackShots() {
        shotFrame++;
        List<com.fs.starfarer.api.combat.DamagingProjectileAPI> all = engine.getProjectiles();
        for (int i = 0, n = all.size(); i < n; i++) {
            com.fs.starfarer.api.combat.DamagingProjectileAPI pr = all.get(i);
            if (pr instanceof MissileAPI) continue;
            String kind = pr.getProjectileSpecId();
            if (kind == null) continue;
            Boolean scripted = shotScripted.get(kind);
            if (scripted == null) {
                boolean has = false;
                try {
                    com.fs.starfarer.api.loading.ProjectileSpecAPI spec = pr.getProjectileSpec();
                    String cls = spec == null ? null : spec.getOnHitClassName();
                    has = cls != null && !cls.trim().isEmpty();
                } catch (Throwable t) { /* treated as an ordinary shot */ }
                scripted = has;
                shotScripted.put(kind, scripted);
            }
            if (!scripted) continue;
            int[] stamp = shots.get(pr);
            if (stamp == null) { shots.put(pr, stamp = new int[] { shotFrame, 0 }); } else stamp[0] = shotFrame;
            // Reported the moment it strikes, not when the spent shot has faded from the field a little later:
            // joiners compare the time of this hit with the time of their own.
            if (stamp[1] == 0 && pr.didDamage()) { stamp[1] = 1; reportShotHit(pr); }
        }
        if (shots.isEmpty()) return;
        for (Iterator<Map.Entry<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]>> it = shots.entrySet().iterator(); it.hasNext();) {
            Map.Entry<com.fs.starfarer.api.combat.DamagingProjectileAPI, int[]> e = it.next();
            if (e.getValue()[0] == shotFrame) continue;
            // (read before removing: this kind of map does not allow an entry to be read once it is removed)
            com.fs.starfarer.api.combat.DamagingProjectileAPI pr = e.getKey();
            boolean told = e.getValue()[1] != 0;
            it.remove(); // gone from the field this frame
            if (!told) reportShotHit(pr); // struck and gone between two frames
        }
    }

    private void reportShotHit(com.fs.starfarer.api.combat.DamagingProjectileAPI pr) {
        {
            long now = System.currentTimeMillis();
            if (now - shotSecond >= 1000) { shotSecond = now; shotsThisSecond = 0; }
            try {
                if (!pr.didDamage() || shotsThisSecond >= 120) return;
                WeaponAPI gun = pr.getWeapon();
                ShipAPI from = pr.getSource();
                Integer fromId = from == null ? null : ids.get(from);
                if (gun == null || fromId == null) return;
                Object struck = pr.getDamageTarget();
                Integer tid = struck instanceof ShipAPI ? ids.get(struck) : null;
                boolean onShield = false;
                if (struck instanceof ShipAPI) {
                    ShieldAPI sh = ((ShipAPI) struck).getShield();
                    onShield = sh != null && sh.isOn() && sh.isWithinArc(pr.getLocation());
                }
                Msg.W w = new Msg.W();
                w.out.writeInt(fromId);
                w.out.writeShort(from.getAllWeapons().indexOf(gun));
                w.out.writeUTF(gun.getId() == null ? "" : gun.getId());
                w.out.writeUTF(pr.getProjectileSpecId());
                w.out.writeInt(tid == null ? -1 : tid);
                w.out.writeFloat(pr.getLocation().x);
                w.out.writeFloat(pr.getLocation().y);
                w.out.writeFloat(pr.getFacing());
                w.out.writeBoolean(onShield);
                session.broadcastDroppable(Msg.SHOT_HIT, w.done());
                shotsThisSecond++;
            } catch (Throwable t) { /* one shot's effect is not shown */ }
        }
    }

    private static Object aiOf(MissileAPI m) {
        Object ai = m.getUnwrappedMissileAI();
        return ai != null ? ai : m.getMissileAI();
    }

    /**
     * True for a missile whose behaviour is more than "fly at the target": one steered by a mod's script, or
     * one that splits into warheads. Joiners run that same behaviour on their copy so its effects are seen.
     */
    private static boolean runsOwnScript(MissileAPI m) {
        try {
            Object ai = aiOf(m);
            if (ai == null) return false;
            String name = ai.getClass().getName();
            return !name.startsWith("com.fs.starfarer.combat.ai.") || name.contains("Mirv");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What kind of missile this is, as the name of the weapon that makes it. Asked of the missile itself first:
     * the second stage of a two-stage missile is launched "by" the same launcher as the first, but it is a
     * different missile, and the launcher's name would rebuild the first stage again.
     */
    private String weaponIdOf(MissileAPI m) {
        String own = null, launcher = null;
        try {
            if (m.getWeaponSpec() != null) own = m.getWeaponSpec().getWeaponId();
        } catch (Throwable t) { /* fall through */ }
        try {
            if (m.getWeapon() != null) launcher = m.getWeapon().getId();
        } catch (Throwable t) { /* fall through */ }
        String id = own != null ? own : launcher;
        // Cross-check against the missile's own make: of all weapons, which ones fire exactly this missile?
        // A warhead a script releases from another missile is found this way whatever launcher it is credited to.
        try {
            String proj = m.getProjectileSpecId();
            List<String> makers = proj == null ? null : makersOf.get(proj);
            if (proj != null && makers == null) {
                makers = new ArrayList<String>();
                for (com.fs.starfarer.api.loading.WeaponSpecAPI ws : Global.getSettings().getAllWeaponSpecs()) {
                    Object ps = ws.getProjectileSpec();
                    boolean same = ps != null && ps == m.getSpec();
                    if (!same && ps instanceof com.fs.starfarer.api.loading.MissileSpecAPI) {
                        com.fs.starfarer.api.loading.MissileSpecAPI ms = (com.fs.starfarer.api.loading.MissileSpecAPI) ps;
                        same = ms.getHullSpec() != null && proj.equals(ms.getHullSpec().getHullId());
                    }
                    if (same) makers.add(ws.getWeaponId());
                }
                makersOf.put(proj, makers);
            }
            if (makers != null && !makers.isEmpty()) {
                if (own != null && makers.contains(own)) id = own;
                else if (launcher != null && makers.contains(launcher)) id = launcher;
                else id = makers.get(0);
            }
        } catch (Throwable t) { /* keep the first answer */ }
        // Still nothing: a script made it out of thin air, crediting no weapon at all (a ship system that
        // throws flares, say). The weapon it names is then usually one of the hidden "system" weapons, which
        // the ordinary list leaves out, so look through those too for one that fires exactly this missile.
        if (id == null) {
            try {
                String proj = m.getProjectileSpecId();
                if (proj != null) {
                    if (!hiddenMakers.containsKey(proj)) {
                        String found = null;
                        for (com.fs.starfarer.api.loading.WeaponSpecAPI ws : Global.getSettings().getActuallyAllWeaponSpecs()) {
                            Object ps = ws.getProjectileSpec();
                            boolean same = ps != null && ps == m.getSpec();
                            if (!same && ps instanceof com.fs.starfarer.api.loading.MissileSpecAPI) {
                                com.fs.starfarer.api.loading.MissileSpecAPI ms = (com.fs.starfarer.api.loading.MissileSpecAPI) ps;
                                same = ms.getHullSpec() != null && proj.equals(ms.getHullSpec().getHullId());
                            }
                            if (same) { found = ws.getWeaponId(); break; }
                        }
                        hiddenMakers.put(proj, found);
                    }
                    id = hiddenMakers.get(proj);
                }
            } catch (Throwable t) { /* it stays unshown */ }
        }
        final String sent = id;
        try { // once per kind of missile, for the log: what it is and what steers it
            String kind = m.getProjectileSpecId() + "|" + id;
            if (missileKinds.add(kind) && missileKinds.size() <= 200) {
                Object ai = m.getUnwrappedMissileAI() != null ? m.getUnwrappedMissileAI() : m.getMissileAI();
                Dbg.info(WingmanHostPlugin.class, "Wingman missiles: new kind '" + m.getProjectileSpecId() + "', weapon spec '" + own
                        + "', launcher '" + launcher + "', sent to joiners as '" + sent + "', steered by " + (ai == null ? "nothing" : ai.getClass().getName())
                        + (id == null ? " (cannot be shown to joiners)" : ""));
            }
        } catch (Throwable t) { /* diagnostics only */ }
        return id;
    }

    /** The ship the host flies, as of last frame (see guardHostShip). */
    private ShipAPI lastHostShip;

    /**
     * Keep the host's own controls on the host's ship. A mod's script may hand "the player" a different ship:
     * UAF's Isenoria passes control to one of its fighters, and back. When a joiner's ship does that here, the
     * script runs on the host, where "the player" is the host, so the host would suddenly be flying a joiner's
     * fighter. A switch to a fighter that isn't from the host's own ship is undone (the game's own Transfer
     * Command never picks a fighter, so only a script does that). Switches made by a subsystem fired for a joiner
     * are undone where it is fired (runSubsystems). The host's own switches stay, Transfer Command included.
     */
    private void guardHostShip() {
        ShipAPI now = engine.getPlayerShip();
        if (now == lastHostShip) return;
        ShipAPI was = lastHostShip;
        lastHostShip = now;
        if (now == null || was == null || !was.isAlive() || was.isHulk()) return;
        if (!now.isFighter() || linked(now, was)) return;
        engine.setPlayerShipExternal(was);
        lastHostShip = was;
        Dbg.info(WingmanHostPlugin.class, "Wingman: a script handed the host a fighter not from the host's ship; the host stays in their own ship");
    }

    /** This fighter is from that ship (its carrier, or a hangar module of it). */
    static boolean linked(ShipAPI fighter, ShipAPI ship) {
        if (fighter == null || ship == null || !fighter.isFighter() || fighter.getWing() == null) return false;
        ShipAPI src = fighter.getWing().getSourceShip();
        return src != null && (src == ship || src.getParentStation() == ship);
    }

    /** Ships whose MagicLib subsystems are held for a joiner (see Subsystems), as of last frame. */
    private final java.util.Set<ShipAPI> heldShips = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());

    /** Joiners' MagicLib subsystems: fire the ones they switched on, and keep the AI logic off the rest. */
    private void runSubsystems() {
        if (!cfg.joinerSubsystems) {
            for (ShipAPI s : heldShips) Subsystems.release(s);
            heldShips.clear();
            for (HostSession.Player p : session.getPlayers()) p.subsystemPresses.clear();
            return;
        }
        java.util.Set<ShipAPI> flown = java.util.Collections.newSetFromMap(new IdentityHashMap<ShipAPI, Boolean>());
        for (HostSession.Player p : session.getPlayers()) {
            ShipAPI s = (ShipAPI) p.ship;
            for (Object[] press : p.subsystemPresses) {
                if (s == null || ((Integer) press[0]).intValue() != p.shipNetId) continue;
                java.util.Map<ShipAPI, java.util.Set<Object>> beforeSubs = Subsystems.snapshotAll(engine);
                ShipAPI before = engine.getPlayerShip();
                boolean fired = Subsystems.fire(s, (String) press[1]);
                ShipAPI after = engine.getPlayerShip();
                if (after != before && before != null) {
                    // It handed "the player" another ship (Isenoria's override passes control to a fighter): that
                    // is the joiner's business, done on their PC. The host stays put, and the switch-back
                    // abilities the script just gave that ship here are taken off again, so they can't later
                    // pull the host into it.
                    engine.setPlayerShipExternal(before);
                    lastHostShip = before;
                    Subsystems.dropAddedSince(after, beforeSubs);
                }
                Dbg.info(WingmanHostPlugin.class, "Wingman subsystems: " + p.name + " used " + press[1] + (fired ? "" : " (not ready here)"));
            }
            p.subsystemPresses.clear();
            if (s == null || onAutopilot.contains(p.id) || !s.isAlive()) continue;
            Subsystems.hold(s);
            flown.add(s);
        }
        for (ShipAPI s : heldShips) if (!flown.contains(s)) Subsystems.release(s); // nobody flies it now: its AI may use them
        heldShips.clear();
        heldShips.addAll(flown);
    }

    /** Arma Armatura's "don't go and refit" mark: a ship whose mark is in the future isn't sent to a carrier. */
    static final String ARMAA_NO_REFIT = "armaa_refitSuppressedUntil";
    private int fightersKept;

    /**
     * A joiner flies their ship by its AI slot (RemotePilotAI). Some mods put their own AI in that slot: Arma
     * Armatura sends a strikecraft that needs repairs to a carrier with a docking AI, and resets the AI when it
     * launches again. On the host a joiner's ship isn't "the player's ship", so such a mod treats it as AI-flown.
     * - Arma Armatura's refit: the mod's own off switch (a custom data mark it reads) is kept on for ships a
     *   joiner flies themselves; on autopilot (U) the mod may send it to a carrier, as it does for the player.
     * - Any mod: if the joiner's controls haven't flown their ship for half a second of battle time, and the ship
     *   isn't landing, landed or held still by a script, the joiner's controls are put back.
     */
    private float lastPilotCheck = -1f;
    private float fighterReportAt = -100f;
    private int fighterReports;

    /**
     * What is happening to a fighter a joiner flies, every 5 seconds (12 lines a battle at most), in the normal log:
     * carriers' scripts fight over such fighters (UAF's Isenoria), and only a log from a real game shows how.
     */
    private void fighterReport(HostSession.Player p, ShipAPI s, float now) {
        if (now - fighterReportAt < 5f || fighterReports >= 12) return;
        fighterReportAt = now;
        fighterReports++;
        try {
            String ai = "none";
            try { if (s.getShipAI() != null) ai = s.getShipAI().getClass().getName(); } catch (Throwable t) { ai = "?"; }
            int drones = 0;
            for (ShipAPI d : engine.getShips()) {
                try { if (d.isDrone() && d.getDroneSource() == s && d.isAlive()) drones++; } catch (Throwable t) { /* not a drone */ }
            }
            StringBuilder ammo = new StringBuilder();
            for (WeaponAPI w : s.getAllWeapons()) {
                if (!w.usesAmmo()) continue;
                if (ammo.length() > 0) ammo.append(", ");
                ammo.append(w.getId()).append(' ').append(w.getAmmo()).append('/').append(w.getMaxAmmo());
            }
            Object docked = s.getCustomData().get("uafDocked");
            com.fs.starfarer.api.combat.ShipSystemAPI sys = s.getSystem();
            Global.getLogger(WingmanHostPlugin.class).info("Wingman fighter report: " + p.name + " flies " + s.getHullSpec().getHullId()
                    + ": AI " + ai + ", controls locked " + s.controlsLocked() + ", landing " + (s.isLanding() || s.isFinishedLanding())
                    + ", docked " + docked + ", drones out " + drones + ", system " + (sys == null ? "none" : sys.getId() + " " + sys.getState())
                    + ", ammo [" + ammo + "], wing returning " + (s.getWing() != null && s.getWing().isReturning(s))
                    + (p.savedAI instanceof RemotePilotAI ? ", weapon groups " + ((RemotePilotAI) p.savedAI).guns.liveGroups + ", joiner's group "
                        + (((RemotePilotAI) p.savedAI).guns.reportGroup + 1) + (((RemotePilotAI) p.savedAI).guns.lastFire ? " firing" : "")
                        + ", fired from the loadout " + ((RemotePilotAI) p.savedAI).guns.loadoutFires + " times" + ", fire presses " + ((RemotePilotAI) p.savedAI).guns.pressesSeen + " (last: " + ((RemotePilotAI) p.savedAI).guns.lastPress + "), shots launched by hand " + ((RemotePilotAI) p.savedAI).guns.shotsLaunched : ""));
        } catch (Throwable t) { /* a report is not worth an error */ }
    }
    private int fightersFlownHere;

    private void keepPilots() {
        float now = engine.getTotalElapsedTime(false);
        float since = lastPilotCheck;
        lastPilotCheck = now;
        if (since < 0f) return;
        for (HostSession.Player p : session.getPlayers()) {
            ShipAPI s = (ShipAPI) p.ship;
            if (s == null || !s.isAlive() || s.isHulk() || onAutopilot.contains(p.id)) continue;
            try { s.getCustomData().put(ARMAA_NO_REFIT, now + 1f); } catch (Throwable t) { /* no custom data: nothing to switch off */ }
            if (!(p.savedAI instanceof RemotePilotAI)) continue;
            RemotePilotAI pilot = (RemotePilotAI) p.savedAI;
            if (pilot.failed || pilot.lastFlown < 0f) continue;
            // A fighter whose carrier's script took its controls before it moved last frame (UAF's Rafflesya dock
            // does, in whatever order the game moves the two) didn't answer the joiner at all then. This frame it
            // is flown from here, before anything moves: the joiner's commands go in first, whatever AI it has.
            if (s.isFighter()) {
                // A carrier's script may tell its fighter to hold fire (UAF's Rafflesya dock does while it means to
                // bring it home); the local player's own ship pays no attention to that, and a joiner's mustn't either.
                try { s.setHoldFire(p.input.has(InputState.HOLD_FIRE)); } catch (Throwable t) { /* no such switch */ }
                fighterReport(p, s, now);
            }
            boolean missed = s.isFighter() && pilot.lastFlown < since && !s.isLanding() && !s.isFinishedLanding();
            if (missed) {
                pilot.flyFromHost(engine.getElapsedInLastFrame());
                fightersFlownHere++;
            }
            // A fighter a joiner flies (Isenoria's Manual Override) is fought over every frame by its carrier's
            // scripts: UAF's Rafflesya dock hands it a docking AI, or none, as it would the local player's fighter.
            // So it gets the joiner's controls back at once, not after half a second (by then it has flown home).
            if (!missed && now - Math.max(pilot.lastFlown, s.isFighter() ? -1f : pilot.restoredAt) < (s.isFighter() ? 0.001f : 0.5f)) continue;
            if (s.isLanding() || s.isFinishedLanding() || s.controlsLocked() || s.isRetreating()) continue;
            pilot.restoredAt = now; // (a ship: given a moment before trying again)
            s.setShipAI(pilot);
            if (s.isFighter() && fightersKept++ > 0) continue; // (said once: it happens every frame while a script insists)
            Dbg.info(WingmanHostPlugin.class, "Wingman: a script took the controls of " + p.name + "'s ship; they are given back");
        }
    }

    /** Landing state of tracked ships: 1 landing or landed on a carrier. */
    private final Map<ShipAPI, Integer> landed = new IdentityHashMap<ShipAPI, Integer>();

    /**
     * Ships that land on a carrier and take off again (Arma Armatura's strikecraft refit at carriers): joiners are
     * told, so their copies play the same landing and launch. (Fighters land and vanish; those are just removed.)
     */
    private void watchLandings() {
        for (Map.Entry<ShipAPI, Integer> e : ids.entrySet()) {
            ShipAPI s = e.getKey();
            if (s.getWing() != null) continue; // a wing's fighters: not this
            boolean down = s.isLanding() || s.isFinishedLanding();
            boolean was = landed.containsKey(s);
            if (down == was) continue;
            int carrier = -1;
            if (down) {
                landed.put(s, 1);
                ShipAPI c = nearestCarrier(s);
                Integer cid = c == null ? null : ids.get(c);
                if (cid != null) carrier = cid;
            } else {
                landed.remove(s);
            }
            Msg.W w = new Msg.W();
            try {
                w.out.writeInt(e.getValue());
                w.out.writeInt(carrier);
                w.out.writeByte(down ? 1 : 2);
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            session.broadcast(Msg.LANDING, w.done());
            Dbg.info(WingmanHostPlugin.class, "Wingman: ship " + e.getValue() + (down ? " lands on carrier " + carrier : " takes off again"));
        }
        if (landed.size() > 200) landed.keySet().retainAll(ids.keySet());
    }

    private ShipAPI nearestCarrier(ShipAPI s) {
        ShipAPI best = null;
        float bestD = Float.MAX_VALUE;
        for (ShipAPI c : ids.keySet()) {
            if (c == s || c.getOwner() != s.getOwner() || !c.hasLaunchBays() || c.isFighter()) continue;
            float dx = c.getLocation().x - s.getLocation().x, dy = c.getLocation().y - s.getLocation().y;
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    /** A piloted ship that died, retreated, or was taken over by the host goes back to nobody. */
    private void checkPilotedShips() {
        ShipAPI hostShip = engine.getPlayerShip();
        for (HostSession.Player p : session.getPlayers()) {
            ShipAPI s = (ShipAPI) p.ship;
            // A fighter flown from the player's own carrier that is done (lost, landed, gone): back to the carrier.
            if (s != null && p.heldCarrier != null && fighterDone(s) && backToCarrier(p)) continue;
            boolean pilotFailed = s != null && p.savedAI instanceof RemotePilotAI && ((RemotePilotAI) p.savedAI).failed;
            if (pilotFailed) {
                p.conn.send(Msg.NOTICE, Msg.utf("Your controls stopped working on the host for that ship (an error, see the host's log). Take another ship."));
                Ui.say(engine, "Co-op: " + p.name + "'s controls failed on their ship; it is back with its own AI (see starsector.log).");
            }
            if (s == null || (!pilotFailed && ids.containsKey(s) && s != hostShip && s.getOwner() == 0)) continue;
            if (!pilotFailed && s != hostShip && ids.containsKey(s) && s.getOwner() != 0) {
                p.conn.send(Msg.NOTICE, Msg.utf("Your ship went over to the other side. Take another ship."));
            }
            if (s == hostShip) { p.ship = null; p.savedAI = null; } else releaseShip(p);
            p.shipNetId = -1;
            p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, -1));
        }
    }

    /** Players pick a ship with the normal transfer-command order on their side; here we grant or refuse it. */
    /** Players already seated in the ship kept for them (see WingmanAPI.reserveShip) this battle, by name. */
    private final Set<String> seated = new HashSet<String>();

    /**
     * A ship kept for a player by another mod (a campaign mod's "this is Ann's ship"): once they are in the
     * battle with nothing to fly, it is claimed for them, as if they had asked for it, and they are moved into
     * it. Once per battle and player.
     */
    private void seatReserved() {
        if (gameClock < 2f) return; // let the battle settle (deployment) first
        for (HostSession.Player p : session.getPlayers()) {
            if (p.ship != null || p.hasClaim || p.needsSync || p.heldCarrier != null) continue;
            String key = p.name.toLowerCase();
            if (seated.contains(key)) continue;
            String member = wingman.api.WingmanAPI.reservedShip(p.name);
            if (member == null) continue;
            for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
                ShipAPI s = e.getValue();
                if (s.getFleetMember() == null || !member.equals(s.getFleetMember().getId())) continue;
                if (!s.isAlive() || s.isHulk() || s.getOwner() != 0) break;
                seated.add(key);
                p.claim = e.getKey();
                p.hasClaim = true; // handleClaims checks it like any request, and moves them in
                Global.getLogger(WingmanHostPlugin.class).info("Wingman: seating " + p.name + " in the ship kept for them");
                break;
            }
        }
    }

    private void handleClaims() {
        ShipAPI hostShip = engine.getPlayerShip();
        for (HostSession.Player p : session.getPlayers()) {
            if (!p.hasClaim || p.needsSync) continue;
            p.hasClaim = false;
            ShipAPI s = p.claim < 0 ? null : byId.get(p.claim);
            if (p.claim < 0) {
                releaseShip(p);
                p.shipNetId = -1;
            } else if (s != null && s == p.ship) {
                // already theirs
            } else if (s == null || s.getOwner() != 0 || (s.isFighter() && !linked(s, (ShipAPI) p.ship)) || s.isStationModule() || s.isDrone()) {
                // (A fighter only from the ship they fly: a mod may hand its pilot one, as UAF's Isenoria does.)
                p.conn.send(Msg.NOTICE, Msg.utf("That ship can't be flown."));
            } else if (s == hostShip) {
                p.conn.send(Msg.NOTICE, Msg.utf("That is the host's ship."));
            } else if (tooBig(s)) {
                p.conn.send(Msg.NOTICE, Msg.utf("The host only lets joiners fly ships up to " + cfg.maxShipSize.toLowerCase().replace('_', ' ') + " size."));
            } else if (pilotOf(s) != null) {
                p.conn.send(Msg.NOTICE, Msg.utf(pilotOf(s).name + " is already flying that ship."));
            } else {
                ShipAPI from = (ShipAPI) p.ship;
                if (s.isFighter() && from != null && linked(s, from) && from.isAlive() && !onAutopilot.contains(p.id)) {
                    // Into a fighter from their own carrier: the carrier is held as they left it (see CarrierHoldAI).
                    releaseCarrier(p, null);
                    from.setShipAI(new CarrierHoldAI(from));
                    p.heldCarrier = from;
                    p.ship = null;
                    p.savedAI = null;
                } else {
                    releaseShip(p);
                }
                p.ship = s;
                p.shipNetId = p.claim;
                p.input.flags = 0;
                RemotePilotAI pilot = new RemotePilotAI(s, p.input);
                p.savedAI = pilot;
                s.setShipAI(pilot);
                onAutopilot.remove(p.id);
                String shipName = s.getName() == null ? s.getHullSpec().getHullId() : s.getName();
                Ui.say(engine, "Co-op: " + p.name + " is flying " + shipName + ".");
                if (!lossWarned && engine.isInCampaign()) {
                    lossWarned = true;
                    Ui.say(engine, "Co-op: this is a campaign battle. A ship lost while a friend flies it is lost for real. "
                            + "Ctrl+Shift+T on a targeted ship takes it back.");
                }
                session.broadcastNotice(p.name + " is flying " + shipName + ".", p);
            }
            p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, p.shipNetId));
            session.lobbyDirty = true; // who flies what is part of what the ready check shows
        }
    }

    /**
     * The real ship lives here, so the target a joiner picks on their screen has to be set on it here too:
     * guided missiles, autofire and the ship system all go by the ship's own target.
     */
    private void syncTargets() {
        long now = System.currentTimeMillis();
        for (HostSession.Player p : session.getPlayers()) {
            ShipAPI s = (ShipAPI) p.ship;
            if (s == null || now - p.input.receivedAt > 1000) continue;

            // Autopilot (the player pressed U): the real ship's own AI flies it until they press U again.
            boolean auto = p.input.has(InputState.AUTOPILOT);
            boolean wasAuto = onAutopilot.contains(p.id);
            if (auto && !wasAuto) {
                onAutopilot.add(p.id);
                s.resetDefaultAI();
                Ui.say(engine, "Co-op: " + p.name + " switched autopilot on.");
            } else if (!auto && wasAuto) {
                onAutopilot.remove(p.id);
                s.setShipAI(new RemotePilotAI(s, p.input));
                Ui.say(engine, "Co-op: " + p.name + " took the controls back.");
            }
            if (auto) continue; // the AI picks its own targets

            ShipAPI want = p.input.target < 0 ? null : byId.get(p.input.target);
            if (want == s) want = null;
            if (s.getShipTarget() != want) s.setShipTarget(want);
            // Written to the log so a test run shows whether the joiner's choices are arriving here.
            String state = (want == null ? "no target" : "target " + want.getName()) + ", autofire groups "
                    + Integer.toBinaryString(p.input.autofire);
            if (!state.equals(pilotState.get(p.id))) {
                pilotState.put(p.id, state);
                Dbg.info(WingmanHostPlugin.class, "Wingman pilot " + p.name + ": " + state);
            }
        }
    }

    private HostSession.Player pilotOf(ShipAPI s) {
        for (HostSession.Player p : session.getPlayers()) if (p.ship == s) return p;
        return null;
    }

    private void releaseShip(HostSession.Player p) {
        releaseCarrier(p, null);
        ShipAPI s = (ShipAPI) p.ship;
        if (s != null && s.isAlive() && !s.isHulk()) {
            // getShipAI() hands back a wrapper, not the original AI, so it can't simply be put back.
            s.resetDefaultAI();
        }
        p.ship = null;
        p.savedAI = null;
        onAutopilot.remove(p.id);
    }

    /** A fighter a player flies from their carrier is done with once it is lost, landing or gone from the battle. */
    private boolean fighterDone(ShipAPI f) {
        try {
            return !f.isAlive() || f.isHulk() || !engine.isEntityInPlay(f) || f.isLanding() || f.isFinishedLanding() || f.isRetreating();
        } catch (Throwable t) { return false; }
    }

    /**
     * Puts a player back in the carrier they flew a fighter from (as UAF's Isenoria does in single player when its
     * fighter's flight is over): the carrier gets their controls again and they are told it is theirs.
     * False if the carrier can't take them (lost, or someone else flies it); they are then released as usual.
     */
    private boolean backToCarrier(HostSession.Player p) {
        ShipAPI c = (ShipAPI) p.heldCarrier;
        Integer id = c == null ? null : ids.get(c);
        if (c == null || id == null || !c.isAlive() || c.isHulk() || c.getOwner() != 0 || pilotOf(c) != null) return false;
        ShipAPI f = (ShipAPI) p.ship;
        p.heldCarrier = null;
        try { if (f != null && f.isAlive() && !f.isHulk() && engine.isEntityInPlay(f)) f.resetDefaultAI(); } catch (Throwable t) { /* gone */ }
        p.ship = c;
        p.shipNetId = id;
        p.input.flags = 0;
        RemotePilotAI pilot = new RemotePilotAI(c, p.input);
        p.savedAI = pilot;
        c.setShipAI(pilot);
        onAutopilot.remove(p.id);
        p.conn.send(Msg.ASSIGN, Msg.twoInts(p.claimSeq, id));
        String shipName = c.getName() == null ? c.getHullSpec().getHullId() : c.getName();
        Ui.say(engine, "Co-op: " + p.name + " is back in " + shipName + ".");
        session.broadcastNotice(p.name + " is back in " + shipName + ".", p);
        session.lobbyDirty = true;
        return true;
    }

    /** The carrier held for a player (see CarrierHoldAI) goes back to its own AI, unless it is the ship they now fly. */
    private void releaseCarrier(HostSession.Player p, ShipAPI keep) {
        ShipAPI c = (ShipAPI) p.heldCarrier;
        p.heldCarrier = null;
        if (c == null || c == keep) return;
        try { if (c.isAlive() && !c.isHulk() && pilotOf(c) == null) c.resetDefaultAI(); } catch (Throwable t) { /* gone with the battle */ }
    }
}
