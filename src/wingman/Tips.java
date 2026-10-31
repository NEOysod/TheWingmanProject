package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.CustomSkillDescription;
import com.fs.starfarer.api.characters.DescriptionSkillEffect;
import com.fs.starfarer.api.characters.LevelBasedEffect;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.combat.HullModEffect;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pop-ups over weapons, fighter wings, hull mods and ship systems, as the game's refit screen has: the name, what it is, its
 * numbers (range, damage, damage per second, flux, ammo; fighters, refit time; ordnance points) and the game's own
 * description. Read from this PC's game (the same mods as the host's), worked out once per item and kept.
 */
final class Tips {
    private Tips() {}

    /** One pop-up's content. */
    private static final class Tip {
        String head = "", title = "", kind = "", text = "", more = "";
        final List<String[]> stats = new ArrayList<String[]>();
        /** The game's own tooltip, drawn in place of the text above when it could be built (skills). */
        CustomPanelAPI panel;
        float panelH;
    }

    private static final Map<String, Tip> made = new HashMap<String, Tip>();

    /** What is under the mouse this frame, and since when (a pop-up shows after a short wait, as the game's do). */
    private static String hoverKey = "", lastKey = "";
    private static boolean hoverMod;
    private static String hoverSize = "";
    private static int hoverOp = -1;
    private static long since;

    /** Item key ("w:" weapon, "f:" wing, "s:" hull's ship system, "k:" skill with ":level") or a hull mod id. */
    static void hover(String key, boolean mod, String hullSize, int op) {
        if (key == null || key.isEmpty()) return;
        hoverKey = key;
        hoverMod = mod;
        hoverSize = hullSize == null ? "" : hullSize;
        hoverOp = op;
    }

    /** At the end of drawing a screen: the pop-up for what the mouse was over, if it stayed there long enough. */
    static void draw() {
        String key = hoverKey;
        hoverKey = "";
        long now = System.currentTimeMillis();
        if (key.isEmpty()) { lastKey = ""; return; }
        if (!key.equals(lastKey)) { lastKey = key; since = now; return; }
        if (now - since < 250) return;
        try {
            Tip t = tip(key, hoverMod, hoverSize);
            if (t != null) render(t, hoverOp);
        } catch (Throwable e) { /* no pop-up for that one */ }
    }

    private static Tip tip(String key, boolean mod, String hullSize) {
        String id = (mod ? "m|" + hullSize + "|" : "") + key;
        Tip t = made.get(id);
        if (t != null) return t;
        if (made.size() > 400) made.clear();
        t = mod ? hullMod(key, hullSize) : key.startsWith("s:") ? system(key.substring(2)) : key.startsWith("k:") ? skill(key.substring(2))
                : key.startsWith("f:") ? wing(key.substring(2)) : weapon(key.substring(2));
        if (t != null) made.put(id, t);
        return t;
    }

    private static Tip system(String hullId) {
        ShipSystemInfo info = ShipSystemInfo.read(hullId);
        Tip t = new Tip();
        t.title = info.name;
        t.kind = "Ship system";
        t.text = info.description;
        try { // above it the hull's design type, below it the hull's own description
            ShipHullSpecAPI hull = Global.getSettings().getHullSpec(hullId);
            String tech = hull.getManufacturer();
            if (tech != null && !tech.trim().isEmpty()) t.head = "Design type: " + tech.trim();
            t.more = description(hull.getDescriptionId(), Description.Type.SHIP, 600);
        } catch (Throwable e) { /* the system alone */ }
        return t;
    }
    /** An officer's skill: what it does, and what its elite level adds (marked if this officer hasn't it). */
    private static Tip skill(String key) {
        int c = key.lastIndexOf(':');
        String id = c < 0 ? key : key.substring(0, c);
        int level = 1;
        try { level = Integer.parseInt(key.substring(c + 1)); } catch (Throwable e) { /* 1 */ }
        SkillSpecAPI spec = Global.getSettings().getSkillSpec(id);
        Tip t = new Tip();
        t.title = spec.getName();
        String aptitude = spec.getGoverningAptitudeName();
        t.kind = (level >= 2 ? "Elite skill" : "Skill") + (aptitude == null || aptitude.isEmpty() ? "" : ",  " + aptitude);
        StringBuilder base = new StringBuilder(), elite = new StringBuilder();
        for (String scope : new String[] { spec.getScopeStr(), spec.getScopeStr2() })
            if (scope != null && !scope.trim().isEmpty()) base.append(base.length() == 0 ? "" : "\n").append(scope.trim());
        if (spec.getDescription() != null && !spec.getDescription().trim().isEmpty()) base.append(base.length() == 0 ? "" : "\n").append(spec.getDescription().trim());
        // The effects as the skill file lists them, by required level, each kind once.
        List<Object> effects = new ArrayList<Object>();
        List<Integer> required = new ArrayList<Integer>();
        java.util.Set<Class<?>> seen = new java.util.HashSet<Class<?>>();
        try {
            JSONArray groups = Global.getSettings().loadJSON("data/characters/skills/" + id + ".skill").getJSONArray("effectGroups");
            for (int g = 0; g < groups.length(); g++) {
                JSONObject group = groups.getJSONObject(g);
                int need = group.optInt("requiredSkillLevel", -1);
                JSONArray list = group.optJSONArray("effects");
                if (need < 1 || list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    try {
                        String script = list.getJSONObject(i).optString("script", "");
                        if (script.isEmpty()) continue;
                        Object effect = Global.getSettings().getScriptClassLoader().loadClass(script).newInstance();
                        if (!seen.add(effect.getClass())) continue;
                        effects.add(effect);
                        required.add(need);
                    } catch (Throwable x) { /* an effect that can't be made here */ }
                }
            }
        } catch (Throwable e) { /* the name and description alone */ }
        for (int i = 0; i < effects.size(); i++) {
            String d = plain(effects.get(i), required.get(i));
            StringBuilder to = required.get(i) >= 2 ? elite : base;
            if (d != null && !d.trim().isEmpty()) to.append(to.length() == 0 ? "" : "\n").append(d.trim());
        }
        t.text = base.toString();
        if (elite.length() > 0) t.more = (level >= 2 ? "Elite:\n" : "Elite (not taken):\n") + elite;
        try {
            skillPanel(t, spec, level, effects, required);
        } catch (Throwable e) { t.panel = null; /* the text pop-up */ }
        return t;
    }

    private static String plain(Object effect, int required) {
        try {
            if (effect instanceof LevelBasedEffect) return ((LevelBasedEffect) effect).getEffectDescription(required);
            if (effect instanceof DescriptionSkillEffect) return ((DescriptionSkillEffect) effect).getString();
        } catch (Throwable x) { /* an effect that can't describe itself here */ }
        return "";
    }

    private static final float TIP_W = 400f, TIP_PAD = 12f;

    /**
     * The skill as the game's character screen shows it, in a real tooltip: each effect writes itself (custom descriptions,
     * coloured numbers), for a stand-in officer with this one skill at this level.
     */
    private static void skillPanel(Tip t, SkillSpecAPI spec, int level, List<Object> effects, List<Integer> required) {
        float w = TIP_W - 2 * TIP_PAD;
        CustomPanelAPI panel = Global.getSettings().createCustom(w, 10f, null);
        TooltipMakerAPI info = panel.createUIElement(w, 10f, false);
        MutableCharacterStatsAPI stats = Global.getFactory().createPerson().getStats();
        stats.setSkillLevel(spec.getId(), level);
        info.setParaInsigniaLarge();
        info.addPara(spec.getName(), aptitudeColor(spec), 0f);
        info.setParaFontDefault();
        info.addPara(t.kind.toUpperCase(), Misc.getGrayColor(), 2f);
        for (String scope : new String[] { spec.getScopeStr(), spec.getScopeStr2() })
            if (scope != null && !scope.trim().isEmpty()) info.addPara(scope.trim(), 3f);
        if (spec.getDescription() != null && !spec.getDescription().trim().isEmpty()) info.addPara(spec.getDescription().trim(), 6f);
        boolean eliteTitle = false;
        float pad = 10f;
        for (int i = 0; i < effects.size(); i++) {
            Object effect = effects.get(i);
            int need = required.get(i);
            if (need >= 2 && !eliteTitle) {
                eliteTitle = true;
                info.addSpacer(8f);
                info.addTitle(level >= 2 ? "Elite" : "Elite (not taken)", level >= 2 ? Misc.getStoryOptionColor() : Misc.getGrayColor());
                pad = 4f;
            }
            if (effect instanceof CustomSkillDescription && ((CustomSkillDescription) effect).hasCustomDescription()) {
                info.addSpacer(pad);
                ((CustomSkillDescription) effect).createCustomDescription(stats, spec, info, w);
            } else if (effect instanceof DescriptionSkillEffect) {
                DescriptionSkillEffect d = (DescriptionSkillEffect) effect;
                LabelAPI label = info.addPara(d.getString(), d.getTextColor(), pad);
                if (d.getHighlights() != null) label.setHighlight(d.getHighlights());
                if (d.getHighlightColors() != null) label.setHighlightColors(d.getHighlightColors());
            } else {
                String d = plain(effect, need);
                if (d == null || d.trim().isEmpty()) continue;
                info.addPara(d.trim(), need <= level ? Misc.getHighlightColor() : Misc.getGrayColor(), pad);
            }
            pad = 2f;
        }
        float h = info.getHeightSoFar();
        info.getPosition().setSize(w, h);
        panel.addUIElement(info).inTL(0f, 0f);
        panel.getPosition().setSize(w, h);
        t.panel = panel;
        t.panelH = h;
    }

    private static Color aptitudeColor(SkillSpecAPI spec) {
        String a = spec.getGoverningAptitudeId();
        if (Skills.APT_COMBAT.equals(a)) return new Color(208, 124, 118);
        if (Skills.APT_INDUSTRY.equals(a)) return new Color(176, 160, 94);
        if (Skills.APT_LEADERSHIP.equals(a)) return new Color(89, 155, 80);
        if (Skills.APT_TECHNOLOGY.equals(a)) return new Color(115, 133, 208);
        Color c = spec.getGoverningAptitudeColor();
        return c == null ? Style.ACCENT : c.brighter();
    }

    private static String num(float f) {
        if (Math.abs(f - Math.round(f)) < 0.05f) return String.valueOf(Math.round(f));
        return String.valueOf(Math.round(f * 10f) / 10f);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    private static Tip weapon(String id) {
        WeaponSpecAPI w = Global.getSettings().getWeaponSpec(id);
        Tip t = new Tip();
        t.title = w.getWeaponName();
        String role = w.getPrimaryRoleStr();
        t.kind = cap(w.getSize().name()) + " " + w.getMountType().getDisplayName().toLowerCase() + (w.isBeam() ? " beam" : "")
                + (role == null || role.isEmpty() ? "" : ",  " + role) + (w.getManufacturer() == null || w.getManufacturer().isEmpty() ? "" : ",  " + w.getManufacturer());
        WeaponAPI.DerivedWeaponStatsAPI d = w.getDerivedStats();
        t.stats.add(new String[] { "Ordnance points", num(w.getOrdnancePointCost(null)) });
        t.stats.add(new String[] { "Range", num(w.getMaxRange()) });
        if (d != null) {
            if (d.getDamagePerShot() > 0f) t.stats.add(new String[] { "Damage", num(d.getDamagePerShot()) + (w.getDamageType() == null ? "" : "  " + w.getDamageType().getDisplayName().toLowerCase()) });
            if (!w.isNoDPSInTooltip() && d.getDps() > 0f) t.stats.add(new String[] { "Damage / second", num(d.getDps()) + (d.getSustainedDps() > 0f && Math.abs(d.getSustainedDps() - d.getDps()) > 1f ? "  (" + num(d.getSustainedDps()) + " sustained)" : "") });
            if (d.getEmpPerShot() > 0f) t.stats.add(new String[] { "EMP", num(d.getEmpPerShot()) });
            if (d.getFluxPerSecond() > 0f) t.stats.add(new String[] { "Flux / second", num(d.getFluxPerSecond()) });
            if (d.getFluxPerDam() > 0f) t.stats.add(new String[] { "Flux / damage", num(d.getFluxPerDam()) });
            if (d.getRoF() > 0f && !w.isBeam()) t.stats.add(new String[] { "Shots / second", num(d.getRoF()) });
        }
        if (w.getMaxAmmo() > 0 && w.getMaxAmmo() < 100000) t.stats.add(new String[] { "Ammo", String.valueOf(w.getMaxAmmo()) + reload(w) });
        // guidance, as the game's own tooltip gives it for missiles
        String[][] guided = { { "Guidance", w.getTrackingStr() }, { "Speed", w.getSpeedStr() }, { "Maneuverability", w.getTurnRateStr() } };
        for (String[] g : guided) if (g[1] != null && !g[1].trim().isEmpty()) t.stats.add(new String[] { g[0], g[1].trim() });
        t.text = description(id, Description.Type.WEAPON, 700);
        return t;
    }

    /** "  (reloads 50 every 20 seconds)": a reload brings reloadSize rounds (one, if unset) at ammo/second. */
    private static String reload(WeaponSpecAPI w) {
        if (w.getAmmoPerSecond() <= 0f) return "";
        float size = w.getReloadSize() > 0f ? w.getReloadSize() : 1f, every = size / w.getAmmoPerSecond();
        return "  (reloads " + num(size) + " every " + num(every) + (num(every).equals("1") ? " second)" : " seconds)");
    }
    private static Tip wing(String id) {
        FighterWingSpecAPI f = Global.getSettings().getFighterWingSpec(id);
        Tip t = new Tip();
        t.title = f.getWingName();
        t.kind = "Fighter wing" + (f.getRoleDesc() == null || f.getRoleDesc().isEmpty() ? "" : ",  " + f.getRoleDesc());
        try { t.stats.add(new String[] { "Ordnance points", num(f.getOpCost(null)) }); } catch (Throwable e) { /* left out */ }
        t.stats.add(new String[] { "Fighters", String.valueOf(f.getNumFighters()) });
        t.stats.add(new String[] { "Refit time", num(f.getRefitTime()) + " s" });
        if (f.getRange() > 0f) t.stats.add(new String[] { "Engagement range", num(f.getRange()) });
        if (f.getAttackRunRange() > 0f) t.stats.add(new String[] { "Attack range", num(f.getAttackRunRange()) });
        try { t.stats.add(new String[] { "Top speed", num(f.getVariant().getHullSpec().getEngineSpec().getMaxSpeed()) }); } catch (Throwable e) { /* left out */ }
        try {
            String hull = f.getVariant().getHullSpec().getHullName();
            t.stats.add(new String[] { "Craft", hull });
            t.text = description(f.getVariant().getHullSpec().getBaseHullId(), Description.Type.SHIP, 700);
        } catch (Throwable e) { /* no craft named */ }
        try { craft(t, f.getVariant()); } catch (Throwable e) { /* the wing's own numbers alone */ }
        return t;
    }

    /** A fighter's defense, ship system and guns, each gun with its range and damage. */
    private static void craft(Tip t, ShipVariantAPI v) {
        ShipHullSpecAPI hull = v.getHullSpec();
        ShieldAPI.ShieldType shield = hull.getShieldType();
        t.stats.add(new String[] { "Defense", shield == ShieldAPI.ShieldType.PHASE ? "Phase"
                : shield == ShieldAPI.ShieldType.FRONT || shield == ShieldAPI.ShieldType.OMNI ? "Shielded  (" + cap(shield.name()) + ")" : "None" });
        t.stats.add(new String[] { "Ship system", ShipSystemInfo.read(hull.getHullId()).name });
        // Fitted and built-in weapons, counted by kind, in the order they first appear.
        Map<String, Integer> guns = new java.util.LinkedHashMap<String, Integer>();
        java.util.Set<String> slots = new java.util.LinkedHashSet<String>(v.getFittedWeaponSlots());
        if (hull.getBuiltInWeapons() != null) slots.addAll(hull.getBuiltInWeapons().keySet());
        for (String slot : slots) {
            String w = v.getWeaponId(slot);
            if ((w == null || w.isEmpty()) && hull.getBuiltInWeapons() != null) w = hull.getBuiltInWeapons().get(slot);
            if (w == null || w.isEmpty()) continue;
            Integer n = guns.get(w);
            guns.put(w, n == null ? 1 : n + 1);
        }
        for (Map.Entry<String, Integer> g : guns.entrySet()) {
            try {
                WeaponSpecAPI w = Global.getSettings().getWeaponSpec(g.getKey());
                WeaponAPI.DerivedWeaponStatsAPI d = w.getDerivedStats();
                String damage = "";
                if (d != null && !w.isNoDPSInTooltip() && d.getDps() > 0f) damage = num(d.getDps()) + " dps";
                else if (d != null && d.getDamagePerShot() > 0f) damage = num(d.getDamagePerShot()) + " dmg";
                if (!damage.isEmpty() && w.getDamageType() != null) damage += " " + w.getDamageType().getDisplayName().toLowerCase();
                t.stats.add(new String[] { (g.getValue() > 1 ? g.getValue() + "x " : "") + w.getWeaponName(),
                        num(w.getMaxRange()) + " range" + (damage.isEmpty() ? "" : ",  " + damage) });
            } catch (Throwable e) { /* an unknown weapon is left out */ }
        }
    }

    private static Tip hullMod(String id, String hullSize) {
        HullModSpecAPI m = Global.getSettings().getHullModSpec(id);
        Tip t = new Tip();
        t.title = m.getDisplayName();
        HullSize size = HullSize.CRUISER;
        try { if (!hullSize.isEmpty()) size = HullSize.valueOf(hullSize); } catch (Throwable e) { /* a cruiser's numbers */ }
        t.kind = "Hull mod" + (m.getManufacturer() == null || m.getManufacturer().isEmpty() ? "" : ",  " + m.getManufacturer());
        t.stats.add(new String[] { "Ordnance points", String.valueOf(m.getCostFor(size)) + "  (" + cap(size.name()) + ")" });
        String text = "";
        try { text = m.getDescription(size); } catch (Throwable e) { /* none */ }
        final HullModEffect effect = m.getEffect();
        final HullSize forSize = size;
        t.text = filled(text, new Param() { public String at(int i) { return effect.getDescriptionParam(i, forSize); } });
        // What building it in adds, as the game's own pop-up has it (its S-mod section, from the spec's sModDesc).
        boolean sModEffect = false;
        try { sModEffect = effect.hasSModEffect(); } catch (Throwable e) { /* none known */ }
        if (sModEffect) {
            String sMod = "";
            try { sMod = m.getSModDescription(size); } catch (Throwable e) { /* none */ }
            boolean penalty = false;
            try { penalty = effect.isSModEffectAPenalty(); } catch (Throwable e) { /* a bonus */ }
            t.more = (penalty ? "S-mod penalty: " : "S-mod bonus: ")
                    + filled(sMod, new Param() { public String at(int i) { return effect.getSModDescriptionParam(i, forSize, null); } });
        } else {
            t.more = "S-mod bonus: none beyond the ordnance points saved.";
        }
        return t;
    }

    /** One of a description's parameters, from the hull mod's own code. */
    private interface Param { String at(int i); }

    /** A description with each "%s" filled in from its parameters ("?" where unknown), and "%%" as "%". */
    private static String filled(String text, Param param) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0, at = 0, p;
        while ((p = text.indexOf("%s", at)) >= 0) {
            String v = null;
            try { v = param.at(i); } catch (Throwable e) { /* unknown */ }
            sb.append(text, at, p).append(v == null ? "?" : v);
            at = p + 2;
            i++;
        }
        sb.append(text.substring(at));
        return sb.toString().replace("%%", "%");
    }

    private static String description(String id, Description.Type type, int max) {
        try {
            Description d = Global.getSettings().getDescription(id, type);
            String s = d == null ? "" : d.getText1();
            if (s == null) return "";
            s = s.trim();
            return s.length() > max ? s.substring(0, max) + "..." : s;
        } catch (Throwable e) {
            return "";
        }
    }

    /** The pop-up by the mouse, kept on the screen. 'op': the ordnance points on this ship, if known (else the item's own). */
    private static void render(Tip t, int op) {
        String body = Style.body();
        float w = TIP_W, pad = TIP_PAD, inner = w - 2 * pad;
        float rowH = Style.lineHeight(body) + 3f;
        float h = t.panel != null ? t.panelH + 2 * pad
                : pad + (t.head.isEmpty() ? 0f : Style.height(t.head, Style.SMALL_FONT, inner) + 4f) + Style.lineHeight(Style.TITLE_FONT) + 4f + Style.height(t.kind, Style.SMALL_FONT, inner) + 8f
                + t.stats.size() * rowH + 6f
                + (t.text.isEmpty() ? 0f : Style.height(t.text, body, inner) + 10f)
                + (t.more.isEmpty() ? 0f : Style.height(t.more, body, inner) + 10f) + pad;
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
        float x = mx + 20f, y = my - h - 10f;
        if (x + w > sw - 4f) x = mx - w - 20f;
        if (y < 4f) y = Math.min(sh - h - 4f, my + 20f);
        x = Math.max(4f, x);
        Style.frame(x, y, w, h, new Color(8, 13, 20, 255), Style.EDGE, 8f);
        if (t.panel != null) {
            try { // the game's tooltip, laid out once, moved to the frame
                t.panel.getPosition().setLocation(x + pad, y + pad);
                t.panel.render(1f);
            } catch (Throwable e) { t.panel = null; /* the text pop-up from the next frame */ }
            return;
        }
        float top = y + h - pad;
        if (!t.head.isEmpty()) top = Style.text(t.head, Style.SMALL_FONT, Style.TEXT, x + pad, top, inner) - 4f;
        top = Style.text(t.title, Style.TITLE_FONT, Style.ACCENT, x + pad, top, inner) - 4f;
        top = Style.text(t.kind.toUpperCase(), Style.SMALL_FONT, Style.DIM, x + pad, top, inner) - 8f;
        Style.rule(x + pad, top + 3f, inner);
        float labelW = inner * 0.42f;
        for (int i = 0; i < t.stats.size(); i++) {
            String[] s = t.stats.get(i);
            String value = s[1];
            if (i == 0 && op >= 0 && "Ordnance points".equals(s[0])) value = op + (value.contains("  (") ? value.substring(value.indexOf("  (")) : "");
            float cy = top - i * rowH - 3f;
            Style.text(s[0], body, Style.DIM, x + pad, cy, labelW);
            Style.text(value, body, Style.TEXT, x + pad + labelW, cy, inner - labelW);
        }
        top -= t.stats.size() * rowH + 6f;
        if (!t.text.isEmpty()) {
            Style.rule(x + pad, top + 2f, inner);
            top = Style.text(t.text, body, Style.TEXT, x + pad, top - 6f, inner) - 4f;
        }
        if (!t.more.isEmpty()) {
            Style.rule(x + pad, top + 2f, inner);
            Style.text(t.more, body, Style.DIM, x + pad, top - 6f, inner);
        }
    }
}
