package wingman.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.HullModEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.util.vector.Vector2f;

import patchlib.api.context.AfterContext;
import patchlib.api.context.BeforeContext;
import patchlib.api.context.ExceptContext;
import patchlib.api.context.HookContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Before;
import patchlib.api.patch.Except;
import patchlib.api.patch.Patch;

import wingman.ClassFields;
import wingman.MessageTap;
import wingman.OfficerPotential;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.plugins.OfficerLevelupPlugin;
import wingman.host.CampaignNews;

/**
 * Wingman's PatchLib hooks (PatchLib finds them in this jar). They do what {@link Hooks} describes: frames set
 * which ship acts (before the method; put back after it, also when it throws), and the answers mods' code gets
 * while one acts. No hook lets anything it throws out: that would end the game.
 */
public final class Patches {
    private Patches() {}

    @Patch(target = @ClassMatch(subtype = OfficerLevelupPlugin.class))
    public static final class OfficerPotentialCap {
        @After(target = @MethodMatch(methodName = "getMaxLevel", parameterTypes = { PersonAPI.class }))
        public static void after(AfterContext ctx) {
            try {
                if (!ctx.isMostDerivedCall()) return;
                int original = ctx.getInferredReturnValue();
                ctx.setReturnValue(OfficerPotential.getMaxLevel((PersonAPI) ctx.getArg(0), original));
            } catch (Throwable t) { /* preserve original cap */ }
        }
    }

    private static final String BEFORE = "wingman.actingBefore";

    @Patch(target = @ClassMatch(subtype = OfficerLevelupPlugin.class))
    public static final class OfficerPotentialElite {
        @After(target = @MethodMatch(methodName = "getMaxEliteSkills", parameterTypes = { PersonAPI.class }))
        public static void after(AfterContext ctx) {
            try {
                if (!ctx.isMostDerivedCall()) return;
                int original = ctx.getInferredReturnValue();
                ctx.setReturnValue(OfficerPotential.getMaxEliteSkills((PersonAPI) ctx.getArg(0), original));
            } catch (Throwable t) { /* preserve original cap */ }
        }
    }

    /** The campaign shows a message (a plain line, or an intel notification): the host's joiners are told too. */
    @Patch(target = @ClassMatch(subtype = com.fs.starfarer.api.campaign.CampaignUIAPI.class))
    public static final class CampaignMessages {
        @After(target = @MethodMatch(methodName = "addMessage"))
        public static void after(AfterContext ctx) {
            try {
                if (!ctx.isMostDerivedCall() || ctx.getArgs().length < 1) return;
                Object first = ctx.getArg(0);
                if (first instanceof String) wingman.host.CampaignNews.offer((String) first);
                else if (first instanceof com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) {
                    wingman.host.CampaignNews.offer(((com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) first).getSmallDescriptionTitle());
                }
            } catch (Throwable t) { /* the message itself is unaffected */ }
        }
    }

    // ---- frames ----

    /** A frame of a ship starts: s (or the ship it belongs to) acts until exit, if a person on another PC flies it. */
    static void enter(HookContext ctx, ShipAPI s, boolean modMethod) {
        try {
            if (Hooks.bridge == null || s == null) return;
            enterAs(ctx, Hooks.actingFor(s), modMethod);
        } catch (Throwable t) { /* as it was */ }
    }

    private static void enterAs(HookContext ctx, ShipAPI now, boolean modMethod) {
        ctx.setLocal(BEFORE, new Object[] { Hooks.acting });
        if (now != null && ctx.isMostDerivedCall()) {
            if (modMethod) Hooks.frames++;
            else Hooks.contexts++;
        }
        Hooks.acting = now;
    }

    static void exit(HookContext ctx) {
        try {
            Object[] prev = ctx.getLocal(BEFORE);
            if (prev != null) Hooks.acting = prev[0];
        } catch (Throwable t) { /* nothing was changed */ }
    }

    private static boolean modMethod(HookContext ctx) {
        Object self = ctx.getSelf();
        return self != null && Scope.of(self.getClass()) == Scope.MOD;
    }

    private static ShipAPI shipOf(CombatEntityAPI e) { return e instanceof ShipAPI ? (ShipAPI) e : null; }

    /** The engine's own frame of each ship. */
    @Patch(target = @ClassMatch(subtype = ShipAPI.class))
    public static final class ShipFrame {
        @Before(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class }))
        public static void before(BeforeContext ctx) {
            try {
                Object self = ctx.getSelf();
                if (Hooks.bridge != null && Scope.of(self.getClass()) == Scope.ENGINE) enter(ctx, (ShipAPI) self, false);
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = HullModEffect.class))
    public static final class HullModFrame {
        @Before(target = @MethodMatch(methodName = "advanceInCombat", parameterTypes = { ShipAPI.class, float.class }))
        public static void before(BeforeContext ctx) {
            try { if (Hooks.bridge != null && modMethod(ctx)) enter(ctx, (ShipAPI) ctx.getArg(0), true); } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advanceInCombat", parameterTypes = { ShipAPI.class, float.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advanceInCombat", parameterTypes = { ShipAPI.class, float.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = ShipSystemStatsScript.class))
    public static final class SystemFrame {
        private static void enterStats(HookContext ctx) {
            try {
                if (Hooks.bridge != null && modMethod(ctx)) enter(ctx, shipOf(((MutableShipStatsAPI) ctx.getArg(0)).getEntity()), true);
            } catch (Throwable t) { /* as it was */ }
        }

        @Before(target = @MethodMatch(methodName = "apply", parameterCount = 4))
        public static void beforeApply(BeforeContext ctx) { enterStats(ctx); }

        @After(target = @MethodMatch(methodName = "apply", parameterCount = 4))
        public static void afterApply(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "apply", parameterCount = 4))
        public static void exceptApply(ExceptContext ctx) { exit(ctx); }

        @Before(target = @MethodMatch(methodName = "unapply", parameterCount = 2))
        public static void beforeUnapply(BeforeContext ctx) { enterStats(ctx); }

        @After(target = @MethodMatch(methodName = "unapply", parameterCount = 2))
        public static void afterUnapply(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "unapply", parameterCount = 2))
        public static void exceptUnapply(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = EveryFrameWeaponEffectPlugin.class))
    public static final class WeaponFrame {
        @Before(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, WeaponAPI.class }))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge != null && modMethod(ctx)) {
                    WeaponAPI w = (WeaponAPI) ctx.getArg(2);
                    if (w != null) enter(ctx, w.getShip(), true);
                }
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, WeaponAPI.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, WeaponAPI.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = OnFireEffectPlugin.class))
    public static final class FireFrame {
        @Before(target = @MethodMatch(methodName = "onFire", parameterTypes = { DamagingProjectileAPI.class, WeaponAPI.class, CombatEngineAPI.class }))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge != null && modMethod(ctx)) {
                    WeaponAPI w = (WeaponAPI) ctx.getArg(1);
                    if (w != null) enter(ctx, w.getShip(), true);
                }
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "onFire", parameterTypes = { DamagingProjectileAPI.class, WeaponAPI.class, CombatEngineAPI.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "onFire", parameterTypes = { DamagingProjectileAPI.class, WeaponAPI.class, CombatEngineAPI.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = OnHitEffectPlugin.class))
    public static final class HitFrame {
        @Before(target = @MethodMatch(methodName = "onHit", parameterCount = 6))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge != null && modMethod(ctx)) {
                    DamagingProjectileAPI p = (DamagingProjectileAPI) ctx.getArg(0);
                    if (p != null) enter(ctx, p.getSource(), true);
                }
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "onHit", parameterCount = 6))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "onHit", parameterCount = 6))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    @Patch(target = @ClassMatch(subtype = BeamEffectPlugin.class))
    public static final class BeamFrame {
        @Before(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, BeamAPI.class }))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge != null && modMethod(ctx)) {
                    BeamAPI b = (BeamAPI) ctx.getArg(2);
                    if (b != null) enter(ctx, b.getSource(), true);
                }
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, BeamAPI.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, CombatEngineAPI.class, BeamAPI.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    /**
     * Screen-wide plugins a script added while running for a joiner's ship, and that keep hold of that ship (see
     * Engine.addPlugin): their frames run for that ship too, as long as a joiner still flies it.
     */
    @Patch(target = @ClassMatch(subtype = EveryFrameCombatPlugin.class))
    public static final class PluginFrame {
        @Before(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, List.class }))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge == null || Hooks.owners.isEmpty()) return;
                Object owner = Hooks.owners.get(ctx.getSelf());
                if (owner != null) enter(ctx, (ShipAPI) owner, true); // otherwise not a joiner's plugin: as it was
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, List.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advance", parameterTypes = { float.class, List.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    /** MagicLib advances every ship's subsystems from one screen-wide plugin: give each its ship's frame. */
    @Patch(target = @ClassMatch(typeName = "org.magiclib.subsystems.MagicSubsystem"))
    public static final class SubsystemFrame {
        @Before(target = @MethodMatch(methodName = "advanceInternal", parameterTypes = { float.class }))
        public static void before(BeforeContext ctx) {
            try {
                if (Hooks.bridge == null) return;
                ShipAPI now;
                try { now = Hooks.actingFor((ShipAPI) ctx.getField("ship").get()); } catch (Throwable t) { now = null; }
                enterAs(ctx, now, true);
            } catch (Throwable t) { /* as it was */ }
        }

        @After(target = @MethodMatch(methodName = "advanceInternal", parameterTypes = { float.class }))
        public static void after(AfterContext ctx) { exit(ctx); }

        @Except(target = @MethodMatch(methodName = "advanceInternal", parameterTypes = { float.class }))
        public static void except(ExceptContext ctx) { exit(ctx); }
    }

    // ---- what mods' scripts ask the battle ----

    @Patch(target = @ClassMatch(subtype = CombatEngineAPI.class))
    public static final class Engine {
        @Before(target = @MethodMatch(methodName = "getPlayerShip", parameterCount = 0))
        public static void getPlayerShip(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                if (a != null && Scope.fromMod("getPlayerShip")) { Hooks.playerShipAnswers++; ctx.skipOriginal(a); }
            } catch (Throwable t) { /* the real answer */ }
        }

        @Before(target = @MethodMatch(methodName = "setPlayerShipExternal", parameterTypes = { ShipAPI.class }))
        public static void setPlayerShipExternal(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                Bridge b = Hooks.bridge;
                if (a != null && b != null) {
                    if (!Scope.fromMod("setPlayerShipExternal")) return;
                    Object ship = ctx.getArg(0);
                    if (ship != a && ship != null) {
                        Hooks.moves++;
                        boolean moved;
                        try { moved = b.moveRemote(a, ship); } catch (Throwable t) { moved = false; }
                        if (moved) Hooks.acting = ship; // the rest of this script runs for the ship the joiner is moving to
                    }
                    ctx.skipOriginal(null);
                    return;
                }
                if (Scope.fromMod("setPlayerShipExternal")) Hooks.lastSet = new Throwable("a script handed the player another ship");
            } catch (Throwable t) { /* as asked */ }
        }

        @Before(target = @MethodMatch(methodName = "maintainStatusForPlayerShip", parameterCount = 5))
        public static void maintainStatusForPlayerShip(BeforeContext ctx) {
            try {
                // the joiner's own game shows it on their screen
                if (Hooks.acting != null && Scope.fromMod("maintainStatusForPlayerShip")) { Hooks.statusDropped++; ctx.skipOriginal(null); }
            } catch (Throwable t) { /* shown */ }
        }

        /** The host's speed is everyone's: for someone else's ship, a copy reading as the real one, that changes nothing. */
        @After(target = @MethodMatch(methodName = "getTimeMult", parameterCount = 0))
        public static void getTimeMult(AfterContext ctx) {
            try {
                if (Hooks.acting == null || !Scope.fromMod("getTimeMult")) return;
                float now = 1f;
                try { now = ((MutableStat) ctx.getReturnValue()).getModifiedValue(); } catch (Throwable t) { /* 1 then */ }
                Hooks.timeDropped++;
                ctx.setReturnValue(new MutableStat(now));
            } catch (Throwable t) { /* the real one */ }
        }

        /**
         * "Is the player flying by hand?" (the game's {@code isUIAutopilotOn()}, true when they are). Scripts pair
         * it with "is this the player's ship?" to choose between the player's keys and their own AI logic: for
         * someone else's ship, it is that person's autopilot, not this PC's player's.
         */
        @Before(target = @MethodMatch(methodName = "isUIAutopilotOn", parameterCount = 0))
        public static void isUIAutopilotOn(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                Bridge b = Hooks.bridge;
                if (a == null || b == null || !Scope.fromMod("isUIAutopilotOn")) return;
                boolean manual;
                try { manual = b.manual(a); } catch (Throwable t) { manual = true; }
                ctx.skipOriginal(manual);
            } catch (Throwable t) { /* the real answer */ }
        }

        /**
         * A plugin a script adds while running for a joiner's ship, and that keeps hold of that ship (a "slow time
         * down while the player flies a fighter" script, say), runs for that ship (see PluginFrame). One that holds
         * no such ship is left alone: that is how a mod's battle-wide manager looks when it happens to be added from
         * the first ship whose frame runs, and it must stay the host's. Weak keys: a plugin removed from the battle
         * is forgotten with it.
         */
        @Before(target = @MethodMatch(methodName = "addPlugin", parameterTypes = { EveryFrameCombatPlugin.class }))
        public static void addPlugin(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                Object plugin = ctx.getArg(0);
                if (a == null || plugin == null || !Scope.fromMod("addPlugin")) return;
                if (holds(ctx, plugin, (ShipAPI) a)) { Hooks.owners.put(plugin, a); Hooks.pluginsOwned++; }
            } catch (Throwable t) { /* left alone */ }
        }
    }

    /** Per class: its own (not static) fields whose type could hold a ShipAPI. */
    private static final ClassValue<String[]> SHIP_FIELDS = new ClassValue<String[]>() {
        @Override
        protected String[] computeValue(Class<?> c) {
            List<String> out = new ArrayList<String>();
            List<String[]> fields = ClassFields.objectFields(c); // null for a loose script's class: none known
            if (fields != null) {
                for (String[] f : fields) {
                    try {
                        Class<?> t = Class.forName(f[1], false, c.getClassLoader());
                        if (t.isAssignableFrom(ShipAPI.class) || ShipAPI.class.isAssignableFrom(t)) out.add(f[0]);
                    } catch (Throwable e) { /* a type that isn't there can't hold a ship */ }
                }
            }
            return out.toArray(new String[0]);
        }
    };

    /** Does this object keep, in a field of its own, the acting ship (or one of its modules or fighters)? */
    static boolean holds(HookContext ctx, Object o, ShipAPI a) {
        ShipAPI mine = Hooks.actingFor(a);
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : SHIP_FIELDS.get(c)) {
                try {
                    Object v = ctx.getField(name, o).get();
                    if (v == a || (v instanceof ShipAPI && mine != null && Hooks.actingFor((ShipAPI) v) == mine)) return true;
                } catch (Throwable t) { /* can't look: next */ }
            }
        }
        return false;
    }

    // ---- this PC's own screen: the camera and interface sounds ----

    private static void screen(BeforeContext ctx, String method) {
        try {
            if (Hooks.acting != null && Scope.fromMod(method)) { Hooks.screenDropped++; ctx.skipOriginal(null); }
        } catch (Throwable t) { /* as asked */ }
    }

    @Patch(target = @ClassMatch(subtype = ViewportAPI.class))
    public static final class View {
        @Before(target = @MethodMatch(methodName = "set", parameterTypes = { float.class, float.class, float.class, float.class }))
        public static void set(BeforeContext ctx) { screen(ctx, "set"); }

        @Before(target = @MethodMatch(methodName = "setViewMult", parameterTypes = { float.class }))
        public static void setViewMult(BeforeContext ctx) { screen(ctx, "setViewMult"); }

        @Before(target = @MethodMatch(methodName = "setExternalControl", parameterTypes = { boolean.class }))
        public static void setExternalControl(BeforeContext ctx) { screen(ctx, "setExternalControl"); }

        @Before(target = @MethodMatch(methodName = "setCenter", parameterTypes = { Vector2f.class }))
        public static void setCenter(BeforeContext ctx) { screen(ctx, "setCenter"); }
    }

    @Patch(target = @ClassMatch(subtype = SoundPlayerAPI.class))
    public static final class Sound {
        /** Played silently for someone else's ship (a real sound, so a script that holds on to it still works). */
        @Before(target = @MethodMatch(methodName = "playUISound", parameterTypes = { String.class, float.class, float.class }))
        public static void playUISound(BeforeContext ctx) {
            try {
                if (Hooks.acting != null && Scope.fromMod("playUISound")) { Hooks.screenDropped++; ctx.setArg(2, 0f); }
            } catch (Throwable t) { /* heard */ }
        }

        @Before(target = @MethodMatch(methodName = "playUILoop", parameterTypes = { String.class, float.class, float.class }))
        public static void playUILoop(BeforeContext ctx) { screen(ctx, "playUILoop"); }
    }

    // ---- the mouse and keys ----

    private static float[] cursor(Object a) {
        Bridge b = Hooks.bridge;
        try { return b == null ? null : b.cursor(a); } catch (Throwable t) { return null; }
    }

    @Patch(target = @ClassMatch(type = Mouse.class))
    public static final class MouseAt {
        @Before(target = @MethodMatch(methodName = "getX", parameterCount = 0))
        public static void getX(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                if (a == null) return;
                float[] c = cursor(a);
                if (c == null || !Scope.fromMod("getX")) return;
                Hooks.mouseAnswers++;
                ctx.skipOriginal(Math.round(Global.getCombatEngine().getViewport().convertWorldXtoScreenX(c[0])));
            } catch (Throwable t) { /* this PC's mouse */ }
        }

        @Before(target = @MethodMatch(methodName = "getY", parameterCount = 0))
        public static void getY(BeforeContext ctx) {
            try {
                Object a = Hooks.acting;
                if (a == null) return;
                float[] c = cursor(a);
                if (c == null || !Scope.fromMod("getY")) return;
                ctx.skipOriginal(Math.round(Global.getCombatEngine().getViewport().convertWorldYtoScreenY(c[1])));
            } catch (Throwable t) { /* this PC's mouse */ }
        }
    }

    @Patch(target = @ClassMatch(type = Keyboard.class))
    public static final class Keys {
        @Before(target = @MethodMatch(methodName = "isKeyDown", parameterTypes = { int.class }))
        public static void isKeyDown(BeforeContext ctx) {
            try {
                Bridge b = Hooks.bridge;
                if (b == null) return;
                Object a = Hooks.acting;
                int key = (Integer) ctx.getArg(0);
                if (a != null) {
                    if (!Scope.fromMod("isKeyDown")) return;
                    Hooks.keyAnswers++;
                    boolean down;
                    try { down = b.keyDown(a, key); } catch (Throwable t) { down = false; }
                    ctx.skipOriginal(down); // never this PC's keys
                    return;
                }
                if (key > 0 && key < 256 && (Hooks.polledKeys[key >> 6] & (1L << (key & 63))) == 0 && Scope.fromMod("isKeyDown")) {
                    Hooks.polledKeys[key >> 6] |= 1L << (key & 63);
                }
            } catch (Throwable t) { /* this PC's keys */ }
        }
    }

    // ---- campaign lookups a joiner's stand-in can't answer (only while Wingman records: Hooks.recording) ----

    /** Our own contains() call, so its hook doesn't count the same lookup again. Game thread. */
    private static Thread looking;

    private static boolean absent(MemoryAPI m, String key) {
        Thread was = looking;
        looking = Thread.currentThread();
        try { return !m.contains(key); } finally { looking = was; }
    }

    private static void missIfAbsent(HookContext ctx, String method) {
        try {
            if (!Hooks.recording || looking == Thread.currentThread()) return;
            String key = (String) ctx.getArg(0);
            if (key != null && absent((MemoryAPI) ctx.getSelf(), key) && Scope.fromMod(method)) Hooks.miss(key);
        } catch (Throwable t) { /* not counted */ }
    }

    @Patch(target = @ClassMatch(subtype = MemoryAPI.class))
    public static final class Memory {
        @After(target = @MethodMatch(methodName = "get", parameterTypes = { String.class }))
        public static void get(AfterContext ctx) {
            try { if (Hooks.recording && ctx.getReturnValue() == null) missIfAbsent(ctx, "get"); } catch (Throwable t) { /* not counted */ }
        }

        @After(target = @MethodMatch(methodName = "contains", parameterTypes = { String.class }))
        public static void contains(AfterContext ctx) {
            try {
                if (!Hooks.recording || looking == Thread.currentThread() || Boolean.TRUE.equals(ctx.getReturnValue())) return;
                if (Scope.fromMod("contains")) Hooks.miss((String) ctx.getArg(0));
            } catch (Throwable t) { /* not counted */ }
        }

        @Before(target = @MethodMatch(methodName = "getString", parameterTypes = { String.class }))
        public static void getString(BeforeContext ctx) { missIfAbsent(ctx, "getString"); }

        @Before(target = @MethodMatch(methodName = "getFloat", parameterTypes = { String.class }))
        public static void getFloat(BeforeContext ctx) { missIfAbsent(ctx, "getFloat"); }

        @Before(target = @MethodMatch(methodName = "getBoolean", parameterTypes = { String.class }))
        public static void getBoolean(BeforeContext ctx) { missIfAbsent(ctx, "getBoolean"); }

        @Before(target = @MethodMatch(methodName = "getLong", parameterTypes = { String.class }))
        public static void getLong(BeforeContext ctx) { missIfAbsent(ctx, "getLong"); }

        @Before(target = @MethodMatch(methodName = "getInt", parameterTypes = { String.class }))
        public static void getInt(BeforeContext ctx) { missIfAbsent(ctx, "getInt"); }

        /** All three is(key, value). */
        @Before(target = @MethodMatch(methodName = "is", parameterCount = 2))
        public static void is(BeforeContext ctx) { missIfAbsent(ctx, "is"); }
    }

    /**
     * A script's line in the battle's message feed (see MessageTap): the host sends it to the joiners, and a joiner
     * holds its own scripts' lines back while the host's copies arrive. Only CombatUIAPI.addMessage produces these;
     * the game's own lines never pass through it.
     */
    @Patch(target = @ClassMatch(type = CombatState.class))
    public static final class MessageLines {
        @Before(target = @MethodMatch(methodName = "addMessage", parameterTypes = { int.class, Object[].class }))
        public static void before(BeforeContext ctx) {
            try {
                if (MessageTap.hold(ctx.getSelf(), ((Number) ctx.getArg(0)).intValue(), (Object[]) ctx.getArg(1)))
                    ctx.skipOriginal(null);
            } catch (Throwable t) { /* the line is shown */ }
        }
    }

    /**
     * The sector's saved data, as mods' scripts get it while recording: misses noted (see RecordingMap). Only mods'
     * calls get the wrapper, so the game never saves it.
     */
    @Patch(target = @ClassMatch(subtype = SectorAPI.class))
    public static final class Sector {
        @SuppressWarnings("unchecked")
        @After(target = @MethodMatch(methodName = "getPersistentData", parameterCount = 0))
        public static void getPersistentData(AfterContext ctx) {
            try {
                if (!Hooks.recording) return;
                Object m = ctx.getReturnValue();
                if (m instanceof Map && !(m instanceof RecordingMap) && Scope.fromMod("getPersistentData")) {
                    ctx.setReturnValue(new RecordingMap((Map<String, Object>) m));
                }
            } catch (Throwable t) { /* the real map */ }
        }
    }
}
