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
    private boolean wasHeld, activity, readyAtStart, forceFire, directFire;
    private int loggedMismatch = -1;
    private long holdStart, lastFireLog;
    private final java.util.Map<WeaponAPI, Float> tracked = new java.util.IdentityHashMap<WeaponAPI, Float>();

    public RemotePilotAI(ShipAPI ship, InputState in) {
        this.ship = ship;
        this.in = in;
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

        // Weapon group: keep asking until the real ship has the group the player has in hand. Asking once is
        // not enough: if that one order is ignored, the ship stays on the old group and every later shot
        // comes from the wrong weapons.
        boolean valid = in.group >= 0 && in.group < groups.size();
        int selected = Groups.selectedIndex(ship, groups);
        if (valid && selected != in.group) ship.giveCommand(ShipCommand.SELECT_GROUP, null, in.group);

        trackCursor(groups, amount);
        directFire = false;
        reportGroup = in.group;
        liveGroups = groups.size();
        lastFire = in.has(InputState.FIRE);
        // A ship flown by an AI may have no weapon groups set up at all (the game makes them from the loadout for a
        // ship a player flies; a fighter flown by the AI has none): the group the joiner fires, as their own game
        // has it from the same loadout, is then fired here from the loadout, weapon by weapon.
        if (in.has(InputState.FIRE) && !valid && in.group >= 0) fireFromLoadout(in.group);
        if (in.has(InputState.FIRE) && valid) {
            if (selected == in.group) {
                ship.giveCommand(ShipCommand.FIRE, aim, in.group);
                fireSystemWeapons(groups.get(in.group));
            } else {
                // The ship hasn't switched (yet, or it won't for an AI-flown ship). The game's fire order would
                // shoot whatever group it still has selected, so fire the wanted group's weapons ourselves.
                directFire = true;
                fireDirectly(groups.get(in.group));
                if (in.group != loggedMismatch) {
                    loggedMismatch = in.group;
                    Dbg.info(RemotePilotAI.class, "Wingman fire: " + ship.getName() + " player has group " + in.group
                            + " but the ship has group " + selected + " selected; firing group " + in.group + " directly");
                }
            }
        }
        watchFire(groups, amount);

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
     * Weapons marked for use by a system or script (hint SYSTEM, such as UAF's Rafflesya bomb) are left out of the
     * game's fire order on a ship the local player isn't flying, though the player's own ship fires them with the
     * rest of the group. So when a joiner fires a group, those of its weapons that are ready are fired directly.
     */
    private void fireSystemWeapons(WeaponGroupAPI group) {
        for (WeaponAPI w : group.getWeaponsCopy()) {
            try {
                if (w.isDisabled() || w.getCooldownRemaining() > 0f || (w.usesAmmo() && w.getAmmo() <= 0)) continue;
                if (w.getSpec() == null || w.getSpec().getAIHints() == null || !w.getSpec().getAIHints().contains(WeaponAPI.AIHints.SYSTEM)) continue;
                w.setForceFireOneFrame(true);
            } catch (Throwable t) { /* that weapon stays with the fire order */ }
        }
    }

    /** For the host's report on fighters flown by joiners: the group fired last, how many groups the ship has. */
    public volatile int reportGroup = -1, liveGroups = -1, loadoutFires;
    public volatile boolean lastFire;

    /** Fires weapon group 'index' of the ship's loadout (its variant's groups, by slot), whatever groups it has now. */
    private void fireFromLoadout(int index) {
        try {
            java.util.List<com.fs.starfarer.api.loading.WeaponGroupSpec> specs = ship.getVariant().getWeaponGroups();
            if (specs == null || index >= specs.size()) return;
            java.util.List<String> slots = specs.get(index).getSlots();
            if (slots == null || slots.isEmpty()) return;
            boolean any = false;
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getSlot() == null || !slots.contains(w.getSlot().getId())) continue;
                if (w.isDisabled() || w.getCooldownRemaining() > 0f || (w.usesAmmo() && w.getAmmo() <= 0)) continue;
                w.setForceFireOneFrame(true);
                any = true;
            }
            if (any && loadoutFires++ == 0) Global.getLogger(RemotePilotAI.class).info("Wingman fire: " + ship.getHullSpec().getHullId()
                    + " has " + ship.getWeaponGroupsCopy().size() + " weapon groups of its own; firing group " + (index + 1) + " from its loadout");
        } catch (Throwable t) { /* nothing fires from that group */ }
    }

    /** Fires a group's weapons without going through the ship's own "selected group". */
    private void fireDirectly(WeaponGroupAPI group) {
        java.util.List<WeaponAPI> weapons = group.getWeaponsCopy();
        boolean alternating = group.getType() == com.fs.starfarer.api.loading.WeaponGroupType.ALTERNATING;
        if (!alternating) {
            for (WeaponAPI w : weapons) if (!w.isDisabled()) w.setForceFireOneFrame(true);
            return;
        }
        // Alternating groups fire one weapon at a time: carry on with one that is mid-shot, else the first ready.
        WeaponAPI pick = null;
        for (WeaponAPI w : weapons) if (!w.isDisabled() && w.isFiring()) { pick = w; break; }
        if (pick == null) for (WeaponAPI w : weapons) if (!w.isDisabled() && w.getCooldownRemaining() <= 0f) { pick = w; break; }
        if (pick != null) pick.setForceFireOneFrame(true);
    }

    /**
     * Checks that holding the fire button actually makes the real ship's guns fire, writes what happens to the
     * log, and steps in if it doesn't. The normal route is the game's own FIRE order above. If the button has
     * been held for a third of a second and not one weapon in the group has so much as started charging while
     * all of them were ready, the order isn't working for this ship, and from then on the weapons are told to
     * fire directly.
     */
    private void watchFire(java.util.List<WeaponGroupAPI> groups, float amount) {
        boolean held = in.has(InputState.FIRE);
        if (!held) { wasHeld = false; return; }
        long now = System.currentTimeMillis();
        if (in.group < 0 || in.group >= groups.size()) {
            if (!wasHeld && now - lastFireLog > 5000) {
                lastFireLog = now;
                Dbg.info(RemotePilotAI.class, "Wingman fire: " + ship.getName() + " fire button held but selected group is "
                        + in.group + " (ship has " + groups.size() + " groups): nothing to fire");
            }
            wasHeld = true;
            return;
        }
        java.util.List<WeaponAPI> weapons = groups.get(in.group).getWeaponsCopy();
        if (!wasHeld) { holdStart = now; activity = false; readyAtStart = false; }
        boolean anyReady = false;
        for (WeaponAPI w : weapons) {
            if (w.isDisabled()) continue;
            if (w.isFiring() || w.getChargeLevel() > 0f || w.getCooldownRemaining() > 0f) activity = true;
            else anyReady = true;
        }
        if (!wasHeld) readyAtStart = anyReady;
        if (!forceFire && readyAtStart && !activity && now - holdStart > 350) {
            forceFire = true;
            Dbg.info(RemotePilotAI.class, "Wingman fire: " + ship.getName() + " group " + in.group + " did not respond to the"
                    + " fire order (" + weapons.size() + " weapons, all idle after 0.35s); firing its weapons directly from now on");
        }
        if (forceFire && !directFire) fireDirectly(groups.get(in.group));
        if (!wasHeld && now - lastFireLog > 5000) {
            lastFireLog = now;
            Dbg.info(RemotePilotAI.class, "Wingman fire: " + ship.getName() + " fire button held, group " + in.group + " of "
                    + groups.size() + ", " + weapons.size() + " weapons, autofire " + groups.get(in.group).isAutofiring()
                    + ", ship has group " + Groups.selectedIndex(ship, groups) + " selected, flux " + Math.round(ship.getFluxTracker().getCurrFlux()) + (forceFire ? ", direct firing" : ""));
        }
        wasHeld = true;
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
