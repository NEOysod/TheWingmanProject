package wingman;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

import wingman.net.InputState;

/**
 * Runs on the host. Replaces a ship's AI and flies it from a remote player's inputs,
 * using the same commands the built-in AI uses.
 */
public class RemotePilotAI implements ShipAIPlugin {
    private static final long STALE_MS = 500;

    private final ShipAPI ship;
    private final InputState in;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final Vector2f aim = new Vector2f();
    private int prevFlags;
    private float defenseCooldown, systemCooldown;
    private int lastGroup = -1;
    private int trackedGroup = -1;
    private final java.util.Map<WeaponAPI, Float> tracked = new java.util.IdentityHashMap<WeaponAPI, Float>();

    /** Fires the group in the player's hand (see GroupFire); also read for the host's report on fighters. */
    public final GroupFire guns;

    public RemotePilotAI(ShipAPI ship, InputState in) {
        this.ship = ship;
        this.in = in;
        this.guns = new GroupFire(ship);
    }

    /** Set if flying by a player's controls failed: the ship then coasts and the host plugin takes it back. */
    public volatile boolean failed;

    /** Battle time of the last frame the game asked this AI to fly (see WingmanHostPlugin.keepPilots). */
    public volatile float lastFlown = -1f;
    /** When the host last put this pilot back after a script took the ship (battle time). */
    public float restoredAt = -1f;

    public void advance(float amount) {
        // Once a frame at most: the host may already have flown it this frame (see WingmanHostPlugin.keepPilots).
        try {
            float now = Global.getCombatEngine().getTotalElapsedTime(false);
            if (now == lastFlown && lastFlown > 0f) return;
            lastFlown = now;
        } catch (Throwable t) { /* no engine: not stale either */ }
        if (failed) { ship.giveCommand(ShipCommand.DECELERATE, null, 0); return; }
        try {
            fly(amount);
        } catch (Throwable t) {
            // The game calls this directly, so an error escaping from here would stop the game itself.
            failed = true;
            Global.getLogger(RemotePilotAI.class).error("Wingman: flying " + ship.getName() + " by a player's controls failed; "
                    + "the ship goes back to its own AI", t);
        }
    }

    private void fly(float amount) {
        // Note which of the player's controls the ship is acting on and for how long; their own game uses it
        // to line its copy of the ship up with this one (see Snapshot.pilotedSeq).
        if (in.seq != in.appliedSeq) { in.appliedSeq = in.seq; in.sinceApplied = 0f; }
        in.sinceApplied += amount;
        defenseCooldown -= amount;
        systemCooldown -= amount;

        if (System.currentTimeMillis() - in.receivedAt > STALE_MS) {
            // No fresh input (lag spike or the player is in a menu): coast to a stop rather than keep thrusting.
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            prevFlags = 0;
            tracked.clear();
            return;
        }

        if (in.has(InputState.ACCEL)) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        if (in.has(InputState.BACK)) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        if (in.has(InputState.DECEL)) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        steerToHeading();
        if (in.has(InputState.STRAFE_L)) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        if (in.has(InputState.STRAFE_R)) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);

        // Autofire switches: make each weapon group match what the player has set on their screen.
        java.util.List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        for (int i = 0; i < groups.size() && i < 16; i++) {
            WeaponGroupAPI g = groups.get(i);
            // The group in the player's hand is always under their direct control, as in the normal game:
            // its autofire switch only takes effect once they select a different group.
            boolean want = (in.autofire & (1 << i)) != 0 && i != in.group;
            if (g.isAutofiring() == want) continue;
            if (want) g.toggleOn(); else g.toggleOff();
        }

        // Carrier fighter command: regroup (pull back) or engage.
        boolean pullBack = in.has(InputState.PULL_BACK);
        if (ship.isPullBackFighters() != pullBack) ship.setPullBackFighters(pullBack);

        aim.set(in.mouseX, in.mouseY);
        lastAmount = amount;
        answerForSystem();
        reportUse(amount);
        // The ship's own idea of where its pilot is pointing. The game reads this for anything aimed with the
        // cursor that is not a gun: where a teleporter lands, which way an omni shield faces, and whatever a
        // mod's script asks the "mouse target" for. For a ship flown from another PC it is that player's cursor.
        try {
            Vector2f pointing = ship.getMouseTarget();
            if (pointing != null) pointing.set(in.mouseX, in.mouseY);
        } catch (Throwable t) { /* the guns still aim; only cursor-aimed systems lose their mark */ }

        // The group in hand and the fire button: see GroupFire. The guns of that group follow the cursor.
        trackCursor(groups, amount);
        guns.advance(groups, in.group, in.has(InputState.FIRE), aim, amount);

        // Shield / phase cloak: make the host ship match what the player has locally.
        ShieldAPI shield = ship.getShield();
        if (shield != null || ship.getPhaseCloak() != null) {
            boolean want = in.has(InputState.DEFENSE);
            boolean have = shield != null ? shield.isOn() : ship.isPhased();
            if (want != have && defenseCooldown <= 0f) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
                defenseCooldown = 0.3f;
            }
        }

        // Ship system: fire on the press; for toggle systems also switch off on release.
        ShipSystemAPI sys = ship.getSystem();
        if (sys != null && systemCooldown <= 0f) {
            boolean want = in.has(InputState.SYSTEM);
            boolean wanted = (prevFlags & InputState.SYSTEM) != 0;
            // Every use the player's game has counted is carried out here, one at a time. A use is "at the
            // cursor", as the game does for its own player: a teleporter, a mine, a system weapon or a mod's
            // targeted system then goes where this player is pointing.
            if (seenUses < 0) seenUses = in.sysUses;
            int fresh = (in.sysUses - seenUses) & 0xFF;
            seenUses = in.sysUses;
            if (fresh > 0 && fresh < 16) { pendingUses = Math.min(4, pendingUses + fresh); usesAge = 0f; }
            usesAge += amount;
            if (usesAge > 1.5f) pendingUses = 0; // could not be used in time (cooling down, no charge): let it go
            sinceUse += amount;
            boolean drones = sys instanceof com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI;
            if (drones && in.sysOrders > 0) {
                // Drones: each press steps to the next order (deploy, attack, recall). Step until it is the
                // order the player has set on their screen.
                pendingUses = 0;
                com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI.DroneOrders now =
                        ((com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI) sys).getDroneOrders();
                if (now != null && now.ordinal() != in.sysOrders - 1) useAtCursor(0.3f);
            } else if (pendingUses > 0) {
                pendingUses--;
                useAtCursor(0.1f);
            } else if (sinceUse > 0.5f && isToggle(sys) && sys.isOn() && !want) {
                // A system that stays on until switched off: off on the player's screen, still on here.
                useAtCursor(0.3f);
            }
        }

        if (in.has(InputState.VENT) && (prevFlags & InputState.VENT) == 0) {
            ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
        }
        prevFlags = in.flags;
    }

    private int seenUses = -1, pendingUses;
    private float usesAge, sinceUse = 10f;

    /**
     * Use the ship system exactly as the game does for its own player: the game's own controls hand the system
     * the cursor position with every use (read from its player-control code), and each system then makes of it
     * what it will. Most ignore it; a teleporter or a mine goes there; a fixed system weapon fires straight ahead
     * regardless.
     */
    private void useAtCursor(float wait) {
        ShipSystemAPI sys = ship.getSystem();
        if (Dbg.on && sys != null && useLogs < 40) { checkIn = 0.3f; stateBefore = String.valueOf(sys.getState()); ammoBefore = sys.getAmmo(); }
        ship.giveCommand(ShipCommand.USE_SYSTEM, new Vector2f(in.mouseX, in.mouseY), 0);
        systemCooldown = wait;
        sinceUse = 0f;
    }
    private float checkIn = -1f;
    private String stateBefore = "";
    private int ammoBefore, useLogs;

    /** Debug log: a moment after each use, what the system did with it. */
    private void reportUse(float amount) {
        if (checkIn < 0f) return;
        checkIn -= amount;
        if (checkIn > 0f) return;
        checkIn = -1f;
        ShipSystemAPI sys = ship.getSystem();
        if (sys == null) return;
        useLogs++;
        boolean took = !String.valueOf(sys.getState()).equals(stateBefore) || sys.getAmmo() < ammoBefore || sys.isActive();
        Dbg.info(RemotePilotAI.class, "Wingman system: " + ship.getName() + " " + sys.getId() + " used for its player: "
                + (took ? "started" : "DID NOT START") + " (was " + stateBefore + ", now " + sys.getState() + ", charges " + ammoBefore + " -> " + sys.getAmmo()
                + " of " + sys.getMaxAmmo() + ", cooldown " + sys.getCooldownRemaining() + ", flux " + Math.round(ship.getFluxTracker().getCurrFlux())
                + "/" + Math.round(ship.getFluxTracker().getMaxFlux()) + ")");
    }

    /**
     * What a ship system's script asks the ship's AI when the ship is not the local player's: which ship to use
     * the system on, and where. Answered with what the player flying it from another PC is pointing at.
     */
    private void answerForSystem() {
        try {
            flags.advance(lastAmount);
            Vector2f cursor = new Vector2f(in.mouseX, in.mouseY);
            flags.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 0.3f, cursor);
            ShipAPI marked = ship.getShipTarget();
            if (marked == null || marked.isHulk() || !marked.isAlive()) {
                // no ship targeted (R): the enemy nearest the cursor, as for the game's own player
                marked = com.fs.starfarer.api.util.Misc.findClosestShipEnemyOf(ship, cursor, ShipAPI.HullSize.FRIGATE, 4000f, true);
            }
            if (marked != null) flags.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, 0.3f, marked);
            else flags.unsetFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
            ShipwideAIFlags theirs = ship.getAIFlags();
            if (theirs != null && theirs != flags) { // the game keeps a separate set for this ship: answer there too
                theirs.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, 0.3f, cursor);
                if (marked != null) theirs.setFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, 0.3f, marked);
            }
        } catch (Throwable t) {
            // a system that needs a mark then picks its own, as it would for an AI ship
        }
    }
    private float lastAmount;

    /**
     * Turning. The player's ship turns on their own screen with no delay, and what arrives here is the heading
     * it has there. This turns the real ship toward that heading and eases off in time to stop on it. Relaying
     * the left/right keys instead goes wrong whenever the game steers for the player (strafe mode turns the ship
     * to face the cursor): by the time each key press arrives the ship has moved on, so it over-corrects one
     * way and then the other and never settles.
     */
    private void steerToHeading() {
        float diff = turn(ship.getFacing(), in.facing);
        float rate = ship.getAngularVelocity();               // degrees per second, positive = turning left
        float decel = Math.max(1f, ship.getTurnDeceleration());
        float coast = rate * Math.abs(rate) / (2f * decel);   // how far it will still turn if we let go now
        float remaining = diff - coast;
        if (remaining > 1f) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        else if (remaining < -1f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
    }

    /**
     * Keeps the weapons of the group in the player's hand pointed at their cursor all the time, the way the game
     * does for a ship you fly yourself. Without this the turrets only start turning at the moment of firing,
     * so the first shots (and every missile from a launcher that fires at once) leave in whatever direction
     * the turret happened to be resting.
     */
    private void trackCursor(java.util.List<WeaponGroupAPI> groups, float amount) {
        if (in.group < 0 || in.group >= groups.size()) { tracked.clear(); return; }
        WeaponGroupAPI group = groups.get(in.group);
        java.util.List<WeaponAPI> weapons = group.getWeaponsCopy();
        if (in.group != trackedGroup) { tracked.clear(); trackedGroup = in.group; }
        for (WeaponAPI w : weapons) {
            if (w.isDisabled()) continue;
            Vector2f loc = w.getLocation();
            float want = (float) Math.toDegrees(Math.atan2(aim.y - loc.y, aim.x - loc.x));
            // Never point a weapon outside what its mount allows. The limit is worked out here from the mount
            // itself (its resting direction and arc) on every frame, with no shortcut: a fixed torpedo tube has
            // only a few degrees of travel, and a launcher turned past that fires sideways out of the hull.
            float arc = w.getSlot() != null ? w.getSlot().getArc() : w.getArc();
            if (arc < 359f) {
                float centre = ship.getFacing() + (w.getSlot() != null ? w.getSlot().getAngle() : w.getArcFacing());
                float off = turn(centre, want);
                float half = Math.max(0f, arc / 2f);
                want = centre + Math.max(-half, Math.min(half, off));
            }
            Float mine = tracked.get(w);
            float cur = mine == null ? w.getCurrAngle() : mine;
            float step = Math.max(5f, w.getTurnRate()) * amount;
            float d = turn(cur, want);
            cur += Math.max(-step, Math.min(step, d));
            if (arc < 359f) { // and keep the running angle inside the arc too, as the ship itself turns
                float centre = ship.getFacing() + (w.getSlot() != null ? w.getSlot().getAngle() : w.getArcFacing());
                float half = Math.max(0f, arc / 2f);
                cur = centre + Math.max(-half, Math.min(half, turn(centre, cur)));
            }
            tracked.put(w, cur);
            w.setCurrAngle(cur);
        }
    }

    /** Shortest turn from one heading to another, in degrees (-180..180). */
    private static float turn(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static boolean isToggle(ShipSystemAPI sys) {
        try {
            return sys.getSpecAPI().isToggle();
        } catch (Throwable t) {
            return false;
        }
    }

    public void setDoNotFireDelay(float amount) {}
    public void forceCircumstanceEvaluation() {}
    public boolean needsRefit() { return false; }
    public ShipwideAIFlags getAIFlags() { return flags; }
    public void cancelCurrentManeuver() {}
    public ShipAIConfig getConfig() { return config; }
}
