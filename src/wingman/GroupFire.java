package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * Runs on the host: fires the weapon group a joiner has in hand on the real ship, the way the game fires the
 * group in the hand of the player at the host's own keyboard.
 *
 * How the game does it (combat.systems.WeaponGroup, combat.entities.Ship):
 * - The FIRE order fires the ship's selected group (the order's group number is ignored), handing it the cursor.
 * - A group with its autofire switch on is fired by its weapons' own AI, and the fire button does nothing to it,
 *   except on the ship the local player flies: there the group in hand is under the button. The joiner's group
 *   in hand has its switch turned off here for that reason (see RemotePilotAI).
 * - A linked group fires every weapon whose arc covers the cursor, plus the weapons that always fire (turrets
 *   with a wide arc); if that is none of them, the one closest to covering it.
 * - An alternating group fires one weapon, its "active" one. On release the next weapon becomes active. While the
 *   button is held, the next weapon becomes active every (refire time / number of weapons) seconds; but only on
 *   the ship the local player flies. On any other ship the active weapon never changes while the button is held,
 *   so holding fire on a joiner's ship fired only the first gun. That turn-taking is done here.
 *
 * Charge-up works as in the game: a weapon with a charge-up (many bombs and missiles have one as a guard against
 * misfires) fires only if the button is held until it has charged; let go sooner and nothing fires. The host
 * gets the joiner's button as it is held, so it holds for as long as they did; nothing here extends a press.
 *
 * On top of that, two things the game doesn't need on a player's own ship:
 * - A fighter flown by the AI may ignore the fire order for some weapons altogether (UAF's Rafflesya bomb never
 *   starts charging). Such a weapon, if it shows no sign of charging, is fired by hand once the button has been
 *   held for its charge-up time: its shot is launched from the weapon, with its ammo and cooldown (see launch);
 *   held on, again each time it is ready. Let go before that and, as in the game, nothing fires.
 * - A watch on each hold of the button: if, after a third of a second, no weapon of the group has done anything,
 *   the weapons the game would have fired are told to fire directly for the rest of that hold.
 * And a fighter flown by the AI may have no weapon groups at all: its loadout's groups are fired, by slot.
 */
final class GroupFire {
    private final ShipAPI ship;

    GroupFire(ShipAPI ship) { this.ship = ship; }

    // The current hold of the fire button.
    private boolean held, stuck, activity, readyAtStart;
    private int heldGroup = -1, mismatchLogs, stuckLogs, pressLines, rotateLogs;
    private float holdTime, altElapsed, mismatchFor;

    // A fighter's ammo weapons the button is holding: weapon -> {seconds held, ammo at the press, charge seen (0/1), charge-up time}.
    private final Map<WeaponAPI, float[]> shots = new IdentityHashMap<WeaponAPI, float[]>();

    /** For the host's report on fighters flown by joiners. */
    public volatile int reportGroup = -1, liveGroups = -1, loadoutFires, pressesSeen, shotsLaunched, rotations;
    public volatile boolean lastFire;
    public volatile String lastPress = "none";

    /**
     * One frame. Called from the pilot's AI, before the ship carries out its orders for the frame, so a group
     * selected here is the one the fire order fires.
     */
    void advance(List<WeaponGroupAPI> groups, int group, boolean fire, Vector2f aim, float amount) {
        boolean valid = group >= 0 && group < groups.size();
        reportGroup = group;
        liveGroups = groups.size();
        lastFire = fire;

        // Group in hand: keep asking until the real ship has it selected. The order is carried out before the
        // fire order of the same frame, so the press fires the right group from the first frame.
        int selected = Groups.selectedIndex(ship, groups);
        if (valid && selected != group) ship.giveCommand(ShipCommand.SELECT_GROUP, null, group);
        WeaponGroupAPI g = valid ? groups.get(group) : null;

        boolean press = fire && group >= 0 && (!held || group != heldGroup);
        if (!fire || group != heldGroup) { holdTime = 0f; stuck = false; activity = false; altElapsed = 0f; mismatchFor = 0f; }
        if (press) onPress(g, group, aim, groups.size());
        held = fire;
        heldGroup = group;

        if (fire) {
            if (g == null) {
                if (group >= 0) fireFromLoadout(group, aim);
            } else {
                // If the ship keeps refusing to select the group (a mod may hold it), fire its weapons directly.
                mismatchFor = selected == group ? 0f : mismatchFor + amount;
                boolean direct = mismatchFor > 0.25f || stuck;
                if (mismatchFor > 0.25f && mismatchLogs++ < 4) Global.getLogger(GroupFire.class).info("Wingman fire: " + name()
                        + " won't select group " + (group + 1) + " (it has group " + (selected + 1) + "); firing that group's weapons directly");
                if (!direct) ship.giveCommand(ShipCommand.FIRE, aim, group);
                else for (WeaponAPI w : wouldFire(g, aim)) w.setForceFireOneFrame(true);
                if (g.getType() == WeaponGroupType.ALTERNATING) takeTurns(g, amount);
                watch(g, amount);
            }
        }
        followShots(fire, amount);
    }

    // ---- what the game fires -------------------------------------------------------------------------------

    /** The weapons of a group that the fire button fires, aimed at the given point (as WeaponGroup does it). */
    static List<WeaponAPI> wouldFire(WeaponGroupAPI g, Vector2f at) {
        List<WeaponAPI> out = new ArrayList<WeaponAPI>();
        if (g.getType() == WeaponGroupType.ALTERNATING) {
            WeaponAPI a = g.getActiveWeapon();
            if (a != null) out.add(a);
            return out;
        }
        return inArc(g.getWeaponsCopy(), at);
    }

    /** Linked: every weapon whose arc covers the point or that always fires; if none, the closest one. */
    static List<WeaponAPI> inArc(List<WeaponAPI> weapons, Vector2f at) {
        List<WeaponAPI> out = new ArrayList<WeaponAPI>();
        WeaponAPI closest = null;
        float best = Float.MAX_VALUE;
        for (WeaponAPI w : weapons) {
            float d;
            try { d = at == null ? 0f : w.distanceFromArc(at); } catch (Throwable t) { d = 0f; }
            boolean always = false;
            try { always = w.isAlwaysFire(); } catch (Throwable t) { /* not always */ }
            if (d == 0f || always) out.add(w);
            else if (d < best) { best = d; closest = w; }
        }
        if (out.isEmpty() && closest != null) out.add(closest);
        return out;
    }

    // ---- alternating groups: taking turns while the button is held ---------------------------------------

    private static boolean noHandle;
    private static MethodHandle setActive;

    /**
     * While the button is held, make the next weapon the group's active one every (time between its shots /
     * number of weapons), as the game does for the ship the local player flies: refire delay, charge-up and
     * the burst for a gun; burst, cooldown, charge-up and charge-down for a burst beam (a steady beam keeps
     * firing). Scaled by the ship's rate of fire for that kind of weapon.
     */
    private void takeTurns(WeaponGroupAPI g, float amount) {
        List<WeaponAPI> ws = g.getWeaponsCopy();
        if (ws.size() < 2) return;
        WeaponAPI active = g.getActiveWeapon();
        float cycle = cycle(active, ws.size());
        if (cycle <= 0f) return;
        altElapsed += amount;
        if (altElapsed <= cycle) return;
        altElapsed = 0f;
        int from = Math.max(0, ws.indexOf(active));
        int next = from;
        for (int i = 1; i <= ws.size(); i++) {
            int k = (from + i) % ws.size();
            WeaponAPI w = ws.get(k);
            if (!w.usesAmmo() || w.getAmmo() > 0) { next = k; break; }
        }
        if (next == from) return;
        if (!makeActive(g, next)) {
            // Without the game's own switch, fire the next one directly for this frame; the game keeps firing its
            // own active one meanwhile, so this is only an approximation.
            WeaponAPI w = ws.get(next);
            if (!w.isDisabled() && w.getCooldownRemaining() <= 0f) w.setForceFireOneFrame(true);
        }
        rotations++;
    }

    private float cycle(WeaponAPI w, int count) {
        if (w == null) return 0f;
        WeaponSpecAPI spec = w.getSpec();
        float t;
        if (w.isBeam()) {
            if (!(spec instanceof BeamWeaponSpecAPI) || !((BeamWeaponSpecAPI) spec).isBurstBeam()) return 0f;
            BeamWeaponSpecAPI b = (BeamWeaponSpecAPI) spec;
            t = b.getBurstDuration() + b.getBurstCooldown() + spec.getBeamChargeupTime() + spec.getBeamChargedownTime();
        } else if (spec instanceof ProjectileWeaponSpecAPI) {
            ProjectileWeaponSpecAPI p = (ProjectileWeaponSpecAPI) spec;
            t = (p.getBurstSize() - 1) * p.getBurstDelay() + p.getRefireDelay() + p.getChargeTime();
            if (p.getBurstSize() > 50) t = 3f;
        } else {
            t = w.getCooldown();
        }
        float rof = 1f;
        try {
            String type = w.getType() == null ? "" : w.getType().name();
            if ("BALLISTIC".equals(type)) rof = ship.getMutableStats().getBallisticRoFMult().getModifiedValue();
            else if ("ENERGY".equals(type)) rof = ship.getMutableStats().getEnergyRoFMult().getModifiedValue();
            else if ("MISSILE".equals(type)) rof = ship.getMutableStats().getMissileRoFMult().getModifiedValue();
        } catch (Throwable e) { rof = 1f; }
        if (rof <= 0f) rof = 1f;
        return t / count / rof;
    }

    /** WeaponGroup.setCurrentActive(int): public on the game's group class, though not part of the mod API. */
    private boolean makeActive(WeaponGroupAPI g, int index) {
        if (noHandle) return false;
        try {
            if (setActive == null) {
                setActive = MethodHandles.publicLookup().findVirtual(g.getClass(), "setCurrentActive",
                        MethodType.methodType(void.class, int.class));
            }
            setActive.invoke((Object) g, index);
            return true;
        } catch (Throwable t) {
            noHandle = true;
            Global.getLogger(GroupFire.class).warn("Wingman fire: can't switch an alternating group's active weapon here; "
                    + "held fire takes turns only approximately", t);
            return false;
        }
    }

    // ---- the watch on each hold ----------------------------------------------------------------------------

    private void watch(WeaponGroupAPI g, float amount) {
        try { if (ship.getFluxTracker().isOverloadedOrVenting() || ship.isPhased()) { activity = true; return; } } catch (Throwable t) { /* carry on */ }
        boolean first = holdTime == 0f;
        holdTime += amount;
        boolean anyReady = false;
        for (WeaponAPI w : g.getWeaponsCopy()) {
            if (w.isDisabled()) continue;
            if (w.isFiring() || w.getChargeLevel() > 0f || w.getCooldownRemaining() > 0f) activity = true;
            else if (!w.usesAmmo() || w.getAmmo() > 0) anyReady = true;
        }
        if (first) readyAtStart = anyReady;
        if (!stuck && readyAtStart && !activity && holdTime > 0.35f) {
            stuck = true;
            if (stuckLogs++ < 6) Global.getLogger(GroupFire.class).info("Wingman fire: " + name() + " group " + (heldGroup + 1)
                    + " did nothing for 0.35 s of fire button; firing its weapons directly until the button is let go");
        }
    }

    // ---- presses, and a fighter's weapons that ignore the button -------------------------------------

    private void onPress(WeaponGroupAPI g, int group, Vector2f aim, int groupCount) {
        pressesSeen++;
        shots.clear();
        List<WeaponAPI> want = g != null ? wouldFire(g, aim) : inArc(loadoutWeapons(group), aim);
        StringBuilder sb = new StringBuilder();
        for (WeaponAPI w : want) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(w.getId());
            if (w.usesAmmo()) sb.append(' ').append(w.getAmmo()).append('/').append(w.getMaxAmmo());
            if (w.getCooldownRemaining() > 0f) sb.append(" cooling");
            if (w.isDisabled()) sb.append(" disabled");
            if (!ship.isFighter()) continue;
            if (!w.usesAmmo() || w.isBeam() || w.getAmmo() <= 0 || w.isDisabled() || w.getCooldownRemaining() > 0f) continue;
            shots.put(w, new float[] {0f, w.getAmmo(), 0f, chargeUp(w)});
        }
        lastPress = "group " + (group + 1) + " [" + sb + "]";
        if (ship.isFighter() && pressLines++ < 6) Global.getLogger(GroupFire.class).info("Wingman fire: " + ship.getHullSpec().getHullId()
                + " fire pressed on " + lastPress + " (ship has " + groupCount + " groups)");
    }

    /** A weapon's charge-up time, in seconds (0 if it has none). */
    private static float chargeUp(WeaponAPI w) {
        try {
            WeaponSpecAPI spec = w.getSpec();
            if (spec instanceof ProjectileWeaponSpecAPI) return Math.max(0f, ((ProjectileWeaponSpecAPI) spec).getChargeTime());
            return Math.max(0f, spec.getChargeTime());
        } catch (Throwable t) { return 0f; }
    }

    /**
     * While the button is held: a fighter's ammo weapon that the game is charging or has fired is left to the game.
     * One that shows no sign of either by the time the button has been held for its charge-up (plus a tenth of a
     * second, for the order to have taken) is fired by hand. Let go before that: nothing, as in the game.
     */
    private void followShots(boolean fire, float amount) {
        if (shots.isEmpty()) return;
        if (!fire) { shots.clear(); return; }
        Iterator<Map.Entry<WeaponAPI, float[]>> it = shots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WeaponAPI, float[]> e = it.next();
            WeaponAPI w = e.getKey();
            float[] f = e.getValue();
            try {
                if (!ship.isAlive() || w.isDisabled() || w.getAmmo() <= 0) { it.remove(); continue; }
                // Firing or cooling down: wait. Held on, it is ready again later and fires again, as in the game.
                if (w.getCooldownRemaining() > 0f || w.getAmmo() < f[1]) { f[0] = 0f; f[1] = w.getAmmo(); f[2] = 0f; continue; }
                f[0] += amount;
                if (w.getChargeLevel() > 0f || w.isFiring()) f[2] = 1f; // the game has it in hand
                if (f[2] > 0f || f[0] < f[3] + 0.1f) continue;
                launch(w);
                f[0] = 0f; f[1] = w.getAmmo();
            } catch (Throwable t) { it.remove(); }
        }
    }

    /** Fires one shot of a weapon that wouldn't fire when told to: its projectile, ammo and cooldown. */
    private void launch(WeaponAPI w) {
        try {
            Vector2f at = null;
            try { at = w.getFirePoint(0); } catch (Throwable t) { /* none */ }
            if (at == null) at = w.getLocation();
            Object shot = Global.getCombatEngine().spawnProjectile(ship, w, w.getId(), new Vector2f(at.x, at.y), w.getCurrAngle(),
                    new Vector2f(ship.getVelocity().x, ship.getVelocity().y));
            if (shot == null) return;
            w.setAmmo(Math.max(0, w.getAmmo() - 1));
            w.setRemainingCooldownTo(w.getCooldown());
            if (shotsLaunched++ < 4) Global.getLogger(GroupFire.class).info("Wingman fire: " + w.getId() + " on " + ship.getHullSpec().getHullId()
                    + " did not fire when told to; launched its shot by hand");
        } catch (Throwable t) {
            Global.getLogger(GroupFire.class).warn("Wingman fire: could not launch " + w.getId() + " by hand", t);
        }
    }

    // ---- ships with no weapon groups: the loadout's groups ----------------------------------------------

    /** The weapons of weapon group 'index' of the ship's loadout, by slot. */
    private List<WeaponAPI> loadoutWeapons(int index) {
        List<WeaponAPI> out = new ArrayList<WeaponAPI>();
        try {
            List<WeaponGroupSpec> specs = ship.getVariant().getWeaponGroups();
            if (specs == null || index >= specs.size()) return out;
            List<String> slots = specs.get(index).getSlots();
            if (slots == null) return out;
            for (WeaponAPI w : ship.getAllWeapons()) if (w.getSlot() != null && slots.contains(w.getSlot().getId())) out.add(w);
        } catch (Throwable t) { /* none */ }
        return out;
    }

    /** A ship flown by the AI may have no weapon groups set up (a fighter has none): fire the loadout's group. */
    private void fireFromLoadout(int index, Vector2f aim) {
        boolean any = false;
        for (WeaponAPI w : inArc(loadoutWeapons(index), aim)) {
            if (w.isDisabled() || w.getCooldownRemaining() > 0f || (w.usesAmmo() && w.getAmmo() <= 0)) continue;
            w.setForceFireOneFrame(true);
            any = true;
        }
        if (any && loadoutFires++ == 0) Global.getLogger(GroupFire.class).info("Wingman fire: " + ship.getHullSpec().getHullId()
                + " has " + ship.getWeaponGroupsCopy().size() + " weapon groups of its own; firing group " + (index + 1) + " from its loadout");
    }

    private String name() {
        return ship.getName() != null ? ship.getName() : ship.getHullSpec().getHullId();
    }
}
