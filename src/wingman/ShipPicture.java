package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * Draws a ship nose up for the lobby's ship card, with a multi-part ship's modules in their slots, turned as their
 * slots are. Each hull's picture is placed by the hull's own centre (from its .ship file, read once per hull; the
 * picture's middle if that can't be read), and each module by its slot and its hull's module anchor.
 */
final class ShipPicture {
    private ShipPicture() {}

    /** Hull id -> centre in its picture (pixels from the bottom left), or null if not known. */
    private static final Map<String, float[]> centres = new HashMap<String, float[]>();

    private static final class Part {
        SpriteAPI sprite;
        float x, y; // where the picture's middle goes, ship units: x right, y up (nose up)
        float angle, w, h;
    }

    private static float[] centre(String hullId) {
        if (centres.containsKey(hullId)) return centres.get(hullId);
        float[] c = null;
        try {
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            String base = spec != null && spec.getBaseHullId() != null ? spec.getBaseHullId() : hullId;
            org.json.JSONObject j = Global.getSettings().loadJSON("data/hulls/" + base + ".ship");
            org.json.JSONArray a = j.optJSONArray("center");
            if (a != null && a.length() >= 2) c = new float[] { Float.parseFloat(a.optString(0, "")), Float.parseFloat(a.optString(1, "")) };
        } catch (Throwable t) { /* the picture's middle then */ }
        centres.put(hullId, c);
        return c;
    }

    /**
     * One hull's picture: its centre goes to (cx, cy) in ship-local units (x forward, y to the left), turned by
     * angle (degrees, towards the left).
     */
    private static Part part(String hullId, String spriteName, float lx, float ly, float angle) {
        SpriteAPI s = Global.getSettings().getSprite(spriteName);
        if (s == null || s.getWidth() <= 0f || s.getHeight() <= 0f) return null;
        Part p = new Part();
        p.sprite = s;
        p.w = s.getWidth();
        p.h = s.getHeight();
        p.angle = angle;
        float[] c = centre(hullId);
        float cx = c == null ? p.w / 2f : c[0], cy = c == null ? p.h / 2f : c[1];
        // The picture's middle in the hull's own frame: forward is up in the picture, left is left.
        float fwd = p.h / 2f - cy, left = cx - p.w / 2f;
        double r = Math.toRadians(angle);
        float mx = lx + (float) (fwd * Math.cos(r) - left * Math.sin(r));
        float my = ly + (float) (fwd * Math.sin(r) + left * Math.cos(r));
        p.x = -my; // on screen, nose up: right is the ship's right (minus left), up is forward
        p.y = mx;
        return p;
    }

    /** Draw it fitted into a square of side box, centred on (x, y). */
    static void draw(String hullId, String sprite, List<String> modules, float x, float y, float box) {
        draw(hullId, sprite, modules, java.util.Collections.<String>emptyList(), x, y, box);
    }

    /**
     * The same, with the weapons fitted drawn in their slots ("i|slot|weaponId": i is -1 for the main hull, else
     * the module's place in the modules list), as the refit screen shows them.
     */
    static void draw(String hullId, String sprite, List<String> modules, List<String> fits, float x, float y, float box) {
        List<Part> parts = new ArrayList<Part>();
        Part main = part(hullId, sprite, 0f, 0f, 0f);
        if (main == null) return;
        parts.add(main);
        // Each hull's frame in ship units (centre forward, centre left, angle), for its weapons: the main hull's,
        // then each module's by its place in the list (null if it couldn't be placed).
        Map<Integer, float[]> frames = new HashMap<Integer, float[]>();
        Map<Integer, String> hullOf = new HashMap<Integer, String>();
        frames.put(-1, new float[] { 0f, 0f, 0f });
        hullOf.put(-1, hullId);
        int index = -1;
        for (String m : modules) {
            index++;
            try {
                String[] f = m.split("\\|");
                if (f.length < 5) continue;
                float lx = Float.parseFloat(f[2]), ly = Float.parseFloat(f[3]), angle = Float.parseFloat(f[4]);
                // The module hull's anchor (a point of it, from its centre) sits on the slot.
                float ax = 0f, ay = 0f;
                try {
                    Vector2f anchor = Global.getSettings().getHullSpec(f[0]).getModuleAnchor();
                    if (anchor != null) { ax = anchor.x; ay = anchor.y; }
                } catch (Throwable t) { /* centred on the slot */ }
                double r = Math.toRadians(angle);
                float cx = lx - (float) (ax * Math.cos(r) - ay * Math.sin(r));
                float cy = ly - (float) (ax * Math.sin(r) + ay * Math.cos(r));
                Part p = part(f[0], f[1], cx, cy, angle);
                if (p != null) { parts.add(p); frames.put(index, new float[] { cx, cy, angle }); hullOf.put(index, f[0]); }
            } catch (Throwable t) { /* that module is left out */ }
        }
        // Fit everything into the box.
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Part p : parts) {
            float rad = (float) Math.sqrt(p.w * p.w + p.h * p.h) / 2f;
            float hx = p == main ? p.w / 2f : rad, hy = p == main ? p.h / 2f : rad;
            minX = Math.min(minX, p.x - hx); maxX = Math.max(maxX, p.x + hx);
            minY = Math.min(minY, p.y - hy); maxY = Math.max(maxY, p.y + hy);
        }
        float k = Math.min(2f, Math.min(box / Math.max(1f, maxX - minX), box / Math.max(1f, maxY - minY)));
        float ox = x - (minX + maxX) / 2f * k, oy = y - (minY + maxY) / 2f * k;
        lastHull = hullId; lastOx = ox; lastOy = oy; lastK = k;
        for (Part p : parts) {
            SpriteAPI s = p.sprite;
            s.setSize(p.w * k, p.h * k);
            s.setAngle(p.angle);
            s.setAlphaMult(1f);
            s.renderAtCenter(ox + p.x * k, oy + p.y * k);
            s.setSize(p.w, p.h);
            s.setAngle(0f);
        }
        try { weapons(fits, frames, hullOf, ox, oy, k); } catch (Throwable t) { /* the hulls alone */ }
    }

    // ---- where the main hull's slots are on screen (for clicking them), as last drawn ----

    private static String lastHull;
    private static float lastOx, lastOy, lastK;

    /**
     * The screen point of each of these main-hull slots in the picture last drawn of that hull: slot id -> { x, y,
     * 1 if a hardpoint }. Empty if that hull wasn't the last drawn. Slots placed as the weapons are (see weapons()).
     */
    static Map<String, float[]> slotPoints(String hullId, List<String> slotIds) {
        Map<String, float[]> out = new HashMap<String, float[]>();
        if (hullId == null || !hullId.equals(lastHull)) return out;
        Map<String, float[]> files = slotsFromFiles(hullId);
        ShipHullSpecAPI hull = null;
        try { hull = Global.getSettings().getHullSpec(hullId); } catch (Throwable t) { /* files only */ }
        for (String id : slotIds) {
            float lx, ly;
            boolean hardpoint;
            float[] f = files.get(id);
            if (f != null) { lx = f[0]; ly = f[1]; hardpoint = f[3] == 1f; }
            else {
                com.fs.starfarer.api.loading.WeaponSlotAPI slot = hull == null ? null : hull.getWeaponSlotAPI(id);
                if (slot == null) continue;
                lx = slot.getLocation().x; ly = slot.getLocation().y; hardpoint = slot.isHardpoint();
            }
            out.put(id, new float[] { lastOx - ly * lastK, lastOy + lx * lastK, hardpoint ? 1f : 0f });
        }
        return out;
    }

    // ---- weapons in their slots ----

    /** Hull id -> its weapon slots as its files have them: slot id -> { forward, left, angle, mount (0 turret, 1 hardpoint, 2 hidden) }. */
    private static final Map<String, Map<String, float[]>> slotFiles = new HashMap<String, Map<String, float[]>>();

    private static float mount(String m) {
        return "HARDPOINT".equalsIgnoreCase(m) ? 1f : "HIDDEN".equalsIgnoreCase(m) ? 2f : 0f;
    }

    /**
     * A hull's weapon slots read from its .ship file (where each slot sits, which way it faces, turret or
     * hardpoint), with a skin's changes on top (its .skin file's weaponSlotChanges and removeWeaponSlots).
     * Read once per hull; empty if the files can't be read (the game's spec is used then).
     */
    private static Map<String, float[]> slotsFromFiles(String hullId) {
        Map<String, float[]> known = slotFiles.get(hullId);
        if (known != null) return known;
        Map<String, float[]> out = new HashMap<String, float[]>();
        try {
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            String base = spec != null && spec.getBaseHullId() != null ? spec.getBaseHullId() : hullId;
            org.json.JSONObject ship = Global.getSettings().loadJSON("data/hulls/" + base + ".ship");
            org.json.JSONArray slots = ship.optJSONArray("weaponSlots");
            for (int i = 0; slots != null && i < slots.length(); i++) {
                org.json.JSONObject sl = slots.optJSONObject(i);
                if (sl == null) continue;
                float[] at = pair(sl, "locations");
                if (at == null) continue;
                out.put(sl.optString("id", ""), new float[] { at[0], at[1], num(sl, "angle"), mount(sl.optString("mount", "TURRET")) });
            }
            if (!base.equals(hullId)) {
                org.json.JSONObject skin = null;
                try { skin = Global.getSettings().loadJSON("data/hulls/skins/" + hullId + ".skin"); } catch (Throwable t) { /* not a skin file of that name */ }
                if (skin != null) {
                    org.json.JSONArray removed = skin.optJSONArray("removeWeaponSlots");
                    for (int i = 0; removed != null && i < removed.length(); i++) out.remove(removed.optString(i, ""));
                    org.json.JSONObject changes = skin.optJSONObject("weaponSlotChanges");
                    java.util.Iterator<?> keys = changes == null ? null : changes.keys();
                    while (keys != null && keys.hasNext()) {
                        String id = String.valueOf(keys.next());
                        org.json.JSONObject ch = changes.optJSONObject(id);
                        float[] cur = out.get(id);
                        if (ch == null) continue;
                        if (cur == null) cur = new float[4];
                        float[] at = pair(ch, "locations");
                        if (at != null) { cur[0] = at[0]; cur[1] = at[1]; }
                        if (!ch.optString("angle", "").isEmpty()) cur[2] = num(ch, "angle");
                        if (!ch.optString("mount", "").isEmpty()) cur[3] = mount(ch.optString("mount", ""));
                        out.put(id, cur);
                    }
                }
            }
        } catch (Throwable t) { /* the game's spec then */ }
        slotFiles.put(hullId, out);
        return out;
    }

    private static float num(org.json.JSONObject j, String key) {
        try { return Float.parseFloat(j.optString(key, "0")); } catch (Throwable t) { return 0f; }
    }

    /** What the game draws for a weapon in one kind of mount: its sprites and the pivot in them. */
    private static final class Look {
        String under, base, gun;
        float[] centre; // pixels from the bottom left; null: the picture's middle
        boolean barrelBelow;
    }

    /** Weapon id + mount ("t" turret, "h" hardpoint) -> how it is drawn. */
    private static final Map<String, Look> looks = new HashMap<String, Look>();

    private static float[] pair(org.json.JSONObject j, String key) {
        org.json.JSONArray a = j == null ? null : j.optJSONArray(key);
        if (a == null || a.length() < 2) return null;
        try { return new float[] { Float.parseFloat(a.optString(0, "")), Float.parseFloat(a.optString(1, "")) }; } catch (Throwable t) { return null; }
    }

    /**
     * A weapon's look in a turret or hardpoint mount. The sprites come from the weapon's spec; the pivot and the
     * barrel picture only from its .wpn file (data/weapons/<id>.wpn, as weapons are usually named), read once.
     */
    private static Look look(String weaponId, boolean hardpoint) {
        String key = weaponId + (hardpoint ? "|h" : "|t");
        if (looks.containsKey(key)) return looks.get(key);
        Look l = null;
        try {
            com.fs.starfarer.api.loading.WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(weaponId);
            l = new Look();
            l.base = hardpoint ? spec.getHardpointSpriteName() : spec.getTurretSpriteName();
            l.under = hardpoint ? spec.getHardpointUnderSpriteName() : spec.getTurretUnderSpriteName();
            org.json.JSONObject j = null;
            try { j = Global.getSettings().loadJSON("data/weapons/" + weaponId + ".wpn"); } catch (Throwable t) { /* named otherwise */ }
            if (j != null) {
                l.centre = pair(j, hardpoint ? "hardpointCenter" : "turretCenter");
                String gun = j.optString(hardpoint ? "hardpointGunSprite" : "turretGunSprite", "");
                l.gun = gun.isEmpty() ? null : gun;
                org.json.JSONArray hints = j.optJSONArray("renderHints");
                if (hints != null) for (int i = 0; i < hints.length(); i++) if ("RENDER_BARREL_BELOW".equals(hints.optString(i, ""))) l.barrelBelow = true;
            }
            if (l.centre == null && hardpoint) {
                // Without the file: a hardpoint's pivot is usually near its back end.
                SpriteAPI b = l.base == null ? null : Global.getSettings().getSprite(l.base);
                if (b != null && b.getWidth() > 0f) l.centre = new float[] { b.getWidth() / 2f, b.getHeight() * 0.25f };
            }
        } catch (Throwable t) { l = null; }
        looks.put(key, l);
        return l;
    }

    /** One weapon sprite with its pivot at (px, py) on screen, pointing angle degrees left of the nose. */
    private static void weaponSprite(String name, float[] centre, float px, float py, float angle, float k) {
        if (name == null || name.isEmpty()) return;
        SpriteAPI s = Global.getSettings().getSprite(name);
        if (s == null || s.getWidth() <= 0f) return;
        float w = s.getWidth(), h = s.getHeight();
        float cx = centre == null ? w / 2f : centre[0], cy = centre == null ? h / 2f : centre[1];
        // The picture's middle from the pivot, in the weapon's frame (forward, left), turned with the weapon.
        float fwd = h / 2f - cy, left = cx - w / 2f;
        double r = Math.toRadians(angle);
        float f2 = (float) (fwd * Math.cos(r) - left * Math.sin(r)), l2 = (float) (fwd * Math.sin(r) + left * Math.cos(r));
        s.setSize(w * k, h * k);
        s.setAngle(angle);
        s.setAlphaMult(1f);
        s.renderAtCenter(px - l2 * k, py + f2 * k);
        s.setSize(w, h);
        s.setAngle(0f);
    }

    private static void weapons(List<String> fits, Map<Integer, float[]> frames, Map<Integer, String> hullOf, float ox, float oy, float k) {
        if (fits == null || fits.isEmpty()) return;
        // Drawn in layers, as the game does: everything's under-picture, then the rest.
        for (int pass = 0; pass < 2; pass++) {
            for (String fit : fits) {
                String[] f = fit.split("\\|");
                if (f.length < 3) continue;
                int i;
                try { i = Integer.parseInt(f[0]); } catch (NumberFormatException e) { continue; }
                float[] fr = frames.get(i);
                if (fr == null) continue;
                // Where the slot is, which way it faces and how it is mounted: from the hull's files, else the game's spec.
                float lx, ly, slotAngle;
                boolean hardpoint;
                float[] fromFile = slotsFromFiles(hullOf.get(i)).get(f[1]);
                if (fromFile != null) {
                    if (fromFile[3] == 2f) continue; // hidden
                    lx = fromFile[0]; ly = fromFile[1]; slotAngle = fromFile[2]; hardpoint = fromFile[3] == 1f;
                } else {
                    ShipHullSpecAPI hull = Global.getSettings().getHullSpec(hullOf.get(i));
                    com.fs.starfarer.api.loading.WeaponSlotAPI slot = hull == null ? null : hull.getWeaponSlotAPI(f[1]);
                    if (slot == null || slot.isHidden() || slot.isSystemSlot()) continue;
                    Vector2f loc = slot.getLocation();
                    lx = loc.x; ly = loc.y; slotAngle = slot.getAngle(); hardpoint = slot.isHardpoint();
                }
                Look l = look(f[2], hardpoint);
                if (l == null) continue;
                // The slot in ship units (forward, left), through its hull's place on the ship.
                double r = Math.toRadians(fr[2]);
                float sf = fr[0] + (float) (lx * Math.cos(r) - ly * Math.sin(r));
                float sl = fr[1] + (float) (lx * Math.sin(r) + ly * Math.cos(r));
                float px = ox - sl * k, py = oy + sf * k, angle = slotAngle + fr[2];
                if (pass == 0) { weaponSprite(l.under, l.centre, px, py, angle, k); continue; }
                if (l.barrelBelow) weaponSprite(l.gun, l.centre, px, py, angle, k);
                weaponSprite(l.base, l.centre, px, py, angle, k);
                if (!l.barrelBelow) weaponSprite(l.gun, l.centre, px, py, angle, k);
            }
        }
    }
}
