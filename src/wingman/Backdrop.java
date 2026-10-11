package wingman;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.terrain.Planet;
import com.fs.starfarer.loading.specs.PlanetSpec;

import wingman.net.BattleInfo;

/**
 * The planets behind a battle, as the host's battle really has them. Which planets a campaign battle shows, where,
 * how big and how they look is decided by the battle's creation plugin, which mods replace (Secrets of the
 * Frontier's draws every nearby planet and jump point, at its own scale). So the host reads its engine's planets
 * instead of guessing, with each one's look (the game's own spec diff: a named planet's own texture, colours,
 * tilt), and the joiner makes the same ones and gives them that look.
 *
 * Touches the game's own classes (CombatEngine, Planet, PlanetSpec): every step fails soft, and the old
 * guess (one planet by the vanilla rule) is used when the host couldn't read them.
 */
final class Backdrop {
    private Backdrop() {}

    private static boolean readBroken, applyBroken, lightBroken;
    private static MethodHandle lightGetter;

    // ---- host ----

    /** Fill in the host's planets and planet backdrop size. False if they couldn't be read. */
    static boolean read(CombatEngineAPI engine, BattleInfo b) {
        if (readBroken || !(engine instanceof CombatEngine)) return false;
        try {
            CombatEngine e = (CombatEngine) engine;
            List<?> list = e.getObjects().getList(Planet.class);
            b.planets.clear();
            if (list != null) {
                for (Object o : list) {
                    if (!(o instanceof Planet) || b.planets.size() >= BattleInfo.MAX_PLANETS) continue;
                    b.planets.add(describe((Planet) o));
                }
            }
            b.planetBgWidth = e.getPlanetBgWidth();
            b.planetBgHeight = e.getPlanetBgHeight();
            b.planetsKnown = true;
            Global.getLogger(Backdrop.class).info("Wingman backdrop: sending this battle's " + b.planets.size()
                    + " planets (backdrop size " + Math.round(b.planetBgWidth) + " x " + Math.round(b.planetBgHeight) + "), image " + b.background);
            // The old single-planet fields stay for the briefing line only.
            b.hasPlanet = !b.planets.isEmpty();
            if (b.hasPlanet) b.planetType = b.planets.get(0).type;
            return true;
        } catch (Throwable t) {
            readBroken = true;
            b.planets.clear();
            b.planetsKnown = false;
            Global.getLogger(Backdrop.class).warn("Wingman: could not read this battle's planets; joiners get the usual guess", t);
            return false;
        }
    }

    private static BattleInfo.Planet describe(Planet p) {
        BattleInfo.Planet d = new BattleInfo.Planet();
        d.type = p.getType() == null ? "" : p.getType();
        Vector2f loc = p.getLocation();
        d.x = loc.x;
        d.y = loc.y;
        d.radius = p.getRadius();
        d.gravity = p.getGravity();
        d.angle = p.getAngle();
        d.cloudAngle = p.getCloudAngle();
        d.tilt = p.getTilt();
        d.pitch = p.getPitch();
        d.scale = p.getScale();
        d.background = p.isBackgroundPlanet();
        Color light = lightOf(p);
        if (light != null) { d.hasLight = true; d.light = light.getRGB(); }
        try {
            PlanetSpec.PlanetSpecDiff diff = PlanetSpec.createDiff(d.type, p.getSpec());
            Map<String, Object> data = diff == null ? null : diff.data;
            if (data != null) {
                for (Map.Entry<String, Object> en : data.entrySet()) {
                    if (d.specKeys.size() >= BattleInfo.MAX_SPEC_VALUES) break;
                    BattleInfo.SpecValue v = encode(en.getValue());
                    if (v == null || en.getKey() == null) continue;
                    d.specKeys.add(en.getKey());
                    d.specValues.add(v);
                }
            }
        } catch (Throwable t) { /* plain look of its type then */ }
        return d;
    }

    private static BattleInfo.SpecValue encode(Object o) {
        BattleInfo.SpecValue v = new BattleInfo.SpecValue();
        if (o == null) v.kind = 0;
        else if (o instanceof String) { v.kind = 1; v.text = (String) o; if (v.text.length() > BattleInfo.MAX_TEXT) return null; }
        else if (o instanceof Float) { v.kind = 2; v.a = ((Float) o).floatValue(); }
        else if (o instanceof Boolean) { v.kind = 3; v.flag = ((Boolean) o).booleanValue(); }
        else if (o instanceof Color) { v.kind = 4; v.argb = ((Color) o).getRGB(); }
        else if (o instanceof Vector3f) { Vector3f p = (Vector3f) o; v.kind = 5; v.a = p.x; v.b = p.y; v.c = p.z; }
        else return null; // tags and the like: not part of the look
        return v;
    }

    /** The colour of the light the planet is lit with (its star's), when the battle set one. */
    private static Color lightOf(Planet p) {
        if (lightBroken) return null;
        try {
            if (lightGetter == null) {
                // MethodHandles.privateLookupIn (Java 9 on) is looked up by name so this builds for Java 8 too.
                MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                        MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
                MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(Planet.class, MethodHandles.lookup());
                lightGetter = l.findGetter(Planet.class, "lightColorOverride", Color.class);
            }
            return (Color) lightGetter.invoke(p);
        } catch (Throwable t) {
            lightBroken = true; // planets are lit the default way on joiners' screens then
            return null;
        }
    }

    // ---- joiner: the mission ----

    /**
     * Put the host's planets into the join mission. Returns false when the host's planets aren't known (an older
     * host, or it couldn't read them): the mission then uses the single guessed planet.
     */
    static boolean addPlanets(MissionDefinitionAPI api, BattleInfo b) {
        if (b == null || !b.planetsKnown) return false;
        if (b.planetBgWidth > 0f && b.planetBgHeight > 0f) api.setPlanetBgSize(b.planetBgWidth, b.planetBgHeight);
        for (BattleInfo.Planet p : b.planets) {
            try {
                api.addPlanet(p.x, p.y, p.radius, p.type, p.gravity, p.background);
            } catch (Throwable t) {
                Global.getLogger(Backdrop.class).warn("Wingman: could not add the host's planet " + p.type + " here", t);
            }
        }
        return true;
    }

    // ---- joiner: in the battle ----

    /** Give this battle's planets the host's look and turn, and the host's background image if it differs. */
    static void apply(CombatEngineAPI engine, BattleInfo b) {
        if (applyBroken || b == null || !(engine instanceof CombatEngine)) return;
        try {
            CombatEngine e = (CombatEngine) engine;
            backgroundImage(e, b.background);
            if (!b.planetsKnown) return;
            if (b.planetBgWidth > 0f && b.planetBgHeight > 0f) {
                e.setPlanetBgWidth(b.planetBgWidth);
                e.setPlanetBgHeight(b.planetBgHeight);
            }
            List<?> list = e.getObjects().getList(Planet.class);
            List<Planet> here = new ArrayList<Planet>();
            if (list != null) for (Object o : list) if (o instanceof Planet) here.add((Planet) o);
            int matched = 0, missing = 0;
            for (BattleInfo.Planet want : b.planets) {
                Planet p = take(here, want);
                if (p == null) { missing++; continue; }
                look(p, want);
                matched++;
            }
            Global.getLogger(Backdrop.class).info("Wingman backdrop: " + b.planets.size() + " planets on the host, " + matched
                    + " given the host's look" + (missing > 0 ? ", " + missing + " not found here" : ""));
        } catch (Throwable t) {
            applyBroken = true;
            Global.getLogger(Backdrop.class).warn("Wingman: could not give the planets the host's look", t);
        }
    }

    /** This battle's planet made for the host's one: same type and place, each used once. */
    private static Planet take(List<Planet> here, BattleInfo.Planet want) {
        for (int i = 0; i < here.size(); i++) {
            Planet p = here.get(i);
            Vector2f loc = p.getLocation();
            if (want.type.equals(p.getType()) && Math.abs(loc.x - want.x) < 1f && Math.abs(loc.y - want.y) < 1f) {
                here.remove(i);
                return p;
            }
        }
        return null;
    }

    private static void look(Planet p, BattleInfo.Planet want) {
        if (!want.specKeys.isEmpty()) {
            // A copy: a planet made from its type shares the type's own spec with every other planet of it.
            PlanetSpec spec = p.getSpec().clone();
            PlanetSpec.PlanetSpecDiff diff = new PlanetSpec.PlanetSpecDiff();
            diff.data = new java.util.HashMap<String, Object>();
            for (int i = 0; i < want.specKeys.size(); i++) {
                diff.data.put(want.specKeys.get(i), decode(want.specValues.get(i)));
            }
            // One field at a time, so one this game can't take (a mod's planet field it lacks) skips only itself.
            for (String key : want.specKeys) {
                try { PlanetSpec.setField(spec, diff, key); } catch (Throwable t) { /* that one stays as it is */ }
            }
            p.setSpec(spec); // also loads the textures it names
        }
        p.setTilt(want.tilt);
        p.setPitch(want.pitch);
        p.setAngle(want.angle);
        p.setCloudAngle(want.cloudAngle);
        p.setScale(want.scale);
        p.setBackgroundPlanet(want.background);
        if (want.hasLight) p.setLightColorOverride(new Color(want.light, true));
    }

    private static Object decode(BattleInfo.SpecValue v) {
        switch (v.kind) {
            case 1: return v.text;
            case 2: return Float.valueOf(v.a);
            case 3: return Boolean.valueOf(v.flag);
            case 4: return new Color(v.argb, true);
            case 5: { Vector3f p = new Vector3f(); p.x = v.a; p.y = v.b; p.z = v.c; return p; }
            default: return null;
        }
    }

    /** The host's background image, if this battle shows another (a mod may have changed it after the start). */
    private static void backgroundImage(CombatEngine e, String want) {
        if (want == null || want.isEmpty()) return;
        String now = e.getBackgroundSpriteName();
        if (want.equals(now)) return;
        CombatEngine.replaceBackground(want, false);
        Global.getLogger(Backdrop.class).info("Wingman backdrop: background image " + now + " replaced by the host's " + want);
    }
}
