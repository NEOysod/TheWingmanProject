package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * Pop-ups over weapons, fighter wings and hull mods, as the game's refit screen has: the name, what it is, its
 * numbers (range, damage, damage per second, flux, ammo; fighters, refit time; ordnance points) and the game's own
 * description. Read from this PC's game (the same mods as the host's), worked out once per item and kept.
 */
final class Tips {
    private Tips() {}

    /** One pop-up's content. */
    private static final class Tip {
        String title = "", kind = "", text = "";
        final List<String[]> stats = new ArrayList<String[]>();
    }

    private static final Map<String, Tip> made = new HashMap<String, Tip>();

    /** What is under the mouse this frame, and since when (a pop-up shows after a short wait, as the game's do). */
    private static String hoverKey = "", lastKey = "";
    private static boolean hoverMod;
    private static String hoverSize = "";
    private static int hoverOp = -1;
    private static long since;

    /** Say what the mouse is over this frame: an item key ("w:" weapon, "f:" wing) or a hull mod id. */
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
        t = mod ? hullMod(key, hullSize) : key.startsWith("f:") ? wing(key.substring(2)) : weapon(key.substring(2));
        if (t != null) made.put(id, t);
        return t;
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
        if (w.getMaxAmmo() > 0 && w.getMaxAmmo() < 100000) t.stats.add(new String[] { "Ammo", String.valueOf(w.getMaxAmmo()) + (w.getAmmoPerSecond() > 0f ? "  (reloads)" : "") });
        t.text = description(id, Description.Type.WEAPON);
        return t;
    }

    private static Tip wing(String id) {
        FighterWingSpecAPI f = Global.getSettings().getFighterWingSpec(id);
        Tip t = new Tip();
        t.title = f.getWingName();
        t.kind = "Fighter wing" + (f.getRoleDesc() == null || f.getRoleDesc().isEmpty() ? "" : ",  " + f.getRoleDesc());
        try { t.stats.add(new String[] { "Ordnance points", num(f.getOpCost(null)) }); } catch (Throwable e) { /* left out */ }
        t.stats.add(new String[] { "Fighters", String.valueOf(f.getNumFighters()) });
        t.stats.add(new String[] { "Refit time", num(f.getRefitTime()) + " s" });
        if (f.getAttackRunRange() > 0f) t.stats.add(new String[] { "Attack range", num(f.getAttackRunRange()) });
        try {
            String hull = f.getVariant().getHullSpec().getHullName();
            t.stats.add(new String[] { "Craft", hull });
            t.text = description(f.getVariant().getHullSpec().getBaseHullId(), Description.Type.SHIP);
        } catch (Throwable e) { /* no craft named */ }
        return t;
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
        if (text != null && text.contains("%s")) { // the parameters filled in from the hull mod's own code
            StringBuilder sb = new StringBuilder();
            int i = 0, at = 0, p;
            while ((p = text.indexOf("%s", at)) >= 0) {
                String v = "?";
                try { v = m.getEffect().getDescriptionParam(i, size); } catch (Throwable e) { /* unknown */ }
                sb.append(text, at, p).append(v == null ? "?" : v);
                at = p + 2;
                i++;
            }
            sb.append(text.substring(at));
            text = sb.toString();
        }
        t.text = text == null ? "" : text.replace("%%", "%");
        return t;
    }

    private static String description(String id, Description.Type type) {
        try {
            Description d = Global.getSettings().getDescription(id, type);
            String s = d == null ? "" : d.getText1();
            if (s == null) return "";
            s = s.trim();
            return s.length() > 700 ? s.substring(0, 700) + "..." : s;
        } catch (Throwable e) {
            return "";
        }
    }

    /** The pop-up by the mouse, kept on the screen. 'op': the ordnance points on this ship, if known (else the item's own). */
    private static void render(Tip t, int op) {
        String body = Style.body();
        float w = 400f, pad = 12f, inner = w - 2 * pad;
        float rowH = Style.lineHeight(body) + 3f;
        float h = pad + Style.lineHeight(Style.TITLE_FONT) + 4f + Style.height(t.kind, Style.SMALL_FONT, inner) + 8f
                + t.stats.size() * rowH + 6f
                + (t.text.isEmpty() ? 0f : Style.height(t.text, body, inner) + 10f) + pad;
        float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
        float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
        float x = mx + 20f, y = my - h - 10f;
        if (x + w > sw - 4f) x = mx - w - 20f;
        if (y < 4f) y = Math.min(sh - h - 4f, my + 20f);
        x = Math.max(4f, x);
        Style.frame(x, y, w, h, new Color(8, 13, 20, 255), Style.EDGE, 8f);
        float top = y + h - pad;
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
            Style.text(t.text, body, Style.TEXT, x + pad, top - 6f, inner);
        }
    }
}
