package wingman;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * Preview only (never in the mod): draws Wingman's screens into PNG files with stand-ins for the game's fonts, so
 * their layout can be looked at without the game. Fonts are near enough, not the game's own.
 */
public class UiPreview {
    static int W = 1920, H = 1080;
    static float mouseX = -1, mouseY = -1;
    static BufferedImage img;
    static Graphics2D g;

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(UiPreview.class.getClassLoader(), new Class<?>[] { type }, h);
    }

    /**
     * A hull read from its .ship file (-Dpreview.hulls=dir holding {id}.ship): its weapon slots answer as the game's
     * own slot class does (isWeaponSlot only for the mountable types), its built-in weapons and mods are listed.
     * Null for any other hull, as before.
     */
    static com.fs.starfarer.api.combat.ShipHullSpecAPI hullSpec(final String id) {
        String dir = System.getProperty("preview.hulls");
        java.io.File f = dir == null || id == null ? null : new java.io.File(dir, id + ".ship");
        if (f == null || !f.isFile()) return null;
        try {
            final org.json.JSONObject j = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            final java.util.Map<String, com.fs.starfarer.api.loading.WeaponSlotAPI> slots = new java.util.LinkedHashMap<String, com.fs.starfarer.api.loading.WeaponSlotAPI>();
            org.json.JSONArray arr = j.getJSONArray("weaponSlots");
            for (int i = 0; i < arr.length(); i++) {
                final org.json.JSONObject o = arr.getJSONObject(i);
                final String type = o.optString("type"), mount = o.optString("mount"), size = o.optString("size");
                slots.put(o.getString("id"), proxy(com.fs.starfarer.api.loading.WeaponSlotAPI.class, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] x) {
                        String n = m.getName();
                        if (n.equals("getId")) return o.optString("id");
                        if (n.equals("isWeaponSlot")) return java.util.Arrays.asList("BALLISTIC", "ENERGY", "UNIVERSAL", "HYBRID", "SYNERGY", "COMPOSITE", "MISSILE").contains(type);
                        if (n.equals("isBuiltIn")) return type.equals("BUILT_IN");
                        if (n.equals("isDecorative")) return type.equals("DECORATIVE");
                        if (n.equals("isSystemSlot")) return type.equals("SYSTEM");
                        if (n.equals("isStationModule")) return type.equals("STATION_MODULE");
                        if (n.equals("isHidden")) return mount.equals("HIDDEN");
                        if (n.equals("isHardpoint")) return mount.equals("HARDPOINT");
                        if (n.equals("isTurret")) return mount.equals("TURRET");
                        if (n.equals("getSlotSize")) return com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.valueOf(size);
                        if (n.equals("getWeaponType")) { try { return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.valueOf(type); } catch (Exception e) { return null; } }
                        if (n.equals("getArc")) return (float) o.optDouble("arc", 0);
                        if (n.equals("getAngle")) return (float) o.optDouble("angle", 0);
                        if (n.equals("getLocation")) { org.json.JSONArray l = o.optJSONArray("locations"); return new org.lwjgl.util.vector.Vector2f(l == null ? 0f : (float) l.optDouble(0), l == null ? 0f : (float) l.optDouble(1)); }
                        if (n.equals("hashCode")) return System.identityHashCode(p);
                        if (n.equals("equals")) return p == x[0];
                        return zero(m.getReturnType());
                    }
                }));
            }
            final java.util.Map<String, String> builtIn = new java.util.LinkedHashMap<String, String>();
            org.json.JSONObject bw = j.optJSONObject("builtInWeapons");
            if (bw != null) for (java.util.Iterator<?> it = bw.keys(); it.hasNext();) { String k = (String) it.next(); builtIn.put(k, bw.getString(k)); }
            final java.util.List<String> mods = new java.util.ArrayList<String>();
            org.json.JSONArray bm = j.optJSONArray("builtInMods");
            for (int i = 0; bm != null && i < bm.length(); i++) mods.add(bm.getString(i));
            return proxy(com.fs.starfarer.api.combat.ShipHullSpecAPI.class, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] x) {
                    String n = m.getName();
                    if (n.equals("getHullId") || n.equals("getBaseHullId")) return id;
                    if (n.equals("getHullName")) return j.optString("hullName");
                    if (n.equals("getSpriteName")) return j.optString("spriteName");
                    if (n.equals("getAllWeaponSlotsCopy")) return new java.util.ArrayList<com.fs.starfarer.api.loading.WeaponSlotAPI>(slots.values());
                    if (n.equals("getWeaponSlotAPI")) return slots.get((String) x[0]);
                    if (n.equals("getBuiltInWeapons")) return new java.util.HashMap<String, String>(builtIn);
                    if (n.equals("getBuiltInMods")) return new java.util.ArrayList<String>(mods);
                    if (n.equals("getBuiltInWings")) return new java.util.ArrayList<String>();
                    if (n.equals("isBuiltInMod")) return mods.contains((String) x[0]);
                    if (n.equals("getHullSize")) return com.fs.starfarer.api.combat.ShipAPI.HullSize.valueOf(j.optString("hullSize", "DESTROYER"));
                    if (n.equals("hashCode")) return System.identityHashCode(p);
                    if (n.equals("equals")) return p == x[0];
                    return zero(m.getReturnType());
                }
            });
        } catch (Exception e) {
            System.out.println("preview: hull " + id + " not read: " + e);
            return null;
        }
    }

    static Object zero(Class<?> r) {
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == void.class) return null;
        return null;
    }

    static Font font(String f) {
        if (f.contains("orbitron24")) return new Font("DejaVu Sans", Font.BOLD, 20);
        if (f.contains("orbitron20")) return new Font("DejaVu Sans", Font.BOLD, 16);
        if (f.contains("orbitron12")) return new Font("DejaVu Sans Condensed", Font.BOLD, 11);
        return new Font("DejaVu Sans Condensed", Font.PLAIN, 14);
    }

    static FontMetrics fm(Font f) { return g.getFontMetrics(f); }

    /** The game's own pictures, when a copy of starsector-core is named (-Dpreview.graphics=dir): none otherwise. */
    static final String GFX = System.getProperty("preview.graphics", "");
    private static final java.util.Map<String, BufferedImage> pics = new java.util.HashMap<String, BufferedImage>();
    private static final java.util.Map<String, String[]> skillRows = new java.util.HashMap<String, String[]>();

    static BufferedImage pic(String path) {
        if (GFX.isEmpty() || path == null || path.isEmpty()) return null;
        if (pics.containsKey(path)) return pics.get(path);
        BufferedImage b = null;
        try {
            File f = new File(GFX, path);
            BufferedImage r = f.isFile() ? ImageIO.read(f) : null;
            if (r != null) { // as ARGB, so a tint (RescaleOp) has four bands whatever the file had
                b = new BufferedImage(r.getWidth(), r.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D gb = b.createGraphics(); gb.drawImage(r, 0, 0, null); gb.dispose();
            }
        } catch (Exception e) { b = null; }
        pics.put(path, b);
        return b;
    }

    /** A skill's name and icon from the game's skill_data.csv ({name, icon}), or null. */
    static String[] skillRow(String id) {
        if (GFX.isEmpty()) return null;
        if (skillRows.isEmpty()) {
            try {
                String all = new String(java.nio.file.Files.readAllBytes(new File(GFX, "data/characters/skills/skill_data.csv").toPath()), "UTF-8");
                List<String> c = new ArrayList<String>(); StringBuilder b = new StringBuilder(); boolean q = false, head = true;
                for (int i = 0; i <= all.length(); i++) { // quoted fields may hold commas and line breaks
                    char ch = i < all.length() ? all.charAt(i) : '\n';
                    if (ch == '"') { if (q && i + 1 < all.length() && all.charAt(i + 1) == '"') { b.append('"'); i++; } else q = !q; }
                    else if (ch == ',' && !q) { c.add(b.toString()); b.setLength(0); }
                    else if ((ch == '\n') && !q) {
                        c.add(b.toString().trim()); b.setLength(0);
                        if (!head && c.size() > 12 && !c.get(0).isEmpty()) skillRows.put(c.get(0), new String[] { c.get(1), c.get(12) });
                        head = false; c = new ArrayList<String>();
                    } else if (ch != '\r' || q) b.append(ch);
                }
            } catch (Exception e) { skillRows.put("", new String[0]); }
        }
        return skillRows.get(id);
    }

    /** Vanilla weapons' turret pictures, by the names the preview uses. */
    static String weaponSprite(String name) {
        String[][] m = { { "Heavy Autocannon", "heavy_autocannon" }, { "Heavy Mauler", "assault_chaingun" }, { "Light Mortar", "light_mortar" },
            { "Mark IX Autocannon", "heavy_assault_cannon" }, { "Arbalest Autocannon", "arbalest" }, { "Gauss Cannon", "gauss_cannon" },
            { "Heavy Needler", "flechette_heavy" }, { "Hypervelocity Driver", "hypervelocity_driver" }, { "Hellbore Cannon", "hellbore_cannon" },
            { "Mjolnir Cannon", "mjolnir" }, { "Hephaestus Assault Gun", "hephaestus" }, { "Devastator Cannon", "devastator" } };
        for (String[] x : m) if (x[0].equals(name)) return "graphics/weapons/" + x[1] + "_turret_base.png";
        return null;
    }

    /** A SpriteAPI drawing a picture into the preview (size, colour and alpha; no rotation). */
    static Object sprite(final String path) {
        final BufferedImage b = pic(path);
        if (b == null) return null;
        final float[] st = { b.getWidth(), b.getHeight(), 1f, 0f }; // width, height, alpha, angle
        final Color[] col = { Color.WHITE };
        return proxy(com.fs.starfarer.api.graphics.SpriteAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getWidth")) return st[0];
                if (n.equals("getHeight")) return st[1];
                if (n.equals("getTextureWidth") || n.equals("getTextureHeight")) return 1f;
                if (n.equals("setSize")) { st[0] = (Float) a[0]; st[1] = (Float) a[1]; return null; }
                if (n.equals("setWidth")) { st[0] = (Float) a[0]; return null; }
                if (n.equals("setHeight")) { st[1] = (Float) a[0]; return null; }
                if (n.equals("setAlphaMult")) { st[2] = (Float) a[0]; return null; }
                if (n.equals("getAlphaMult")) return st[2];
                if (n.equals("setAngle")) { st[3] = (Float) a[0]; return null; }
                if (n.equals("getAngle")) return st[3];
                if (n.equals("setColor")) { col[0] = (Color) a[0]; return null; }
                if (n.equals("getColor")) return col[0];
                if (n.equals("renderAtCenter")) { draw(b, (Float) a[0] - st[0] / 2f, (Float) a[1] - st[1] / 2f, st[0], st[1], st[2], col[0]); return null; }
                if (n.equals("render")) { draw(b, (Float) a[0], (Float) a[1], st[0], st[1], st[2], col[0]); return null; }
                return zero(m.getReturnType());
            }
        });
    }

    private static void draw(BufferedImage b, float x, float y, float w, float h, float alpha, Color c) {
        Point2D.Float tl = GL11.toImage(x, y + h), br = GL11.toImage(x + w, y);
        float a = alpha * c.getAlpha() / 255f;
        if (a <= 0f) return;
        BufferedImage src = b;
        if (c.getRed() != 255 || c.getGreen() != 255 || c.getBlue() != 255) {
            float[] k = { c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1f };
            src = new java.awt.image.RescaleOp(k, new float[4], null).filter(b, null);
        }
        java.awt.Composite old = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1f, a)));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, Math.round(tl.x), Math.round(tl.y), Math.round(br.x - tl.x), Math.round(br.y - tl.y), null);
        g.setComposite(old);
    }

    /** A label: wraps to its width, draws at its location (bottom left, y up). */
    static LabelAPI label(final String text0, final String fontName) {
        final Font font = font(fontName);
        final float[] pos = new float[4]; // x, y, w, h
        final Color[] color = { Color.WHITE };
        final String[] text = { text0 };
        final List<String> lines = new ArrayList<String>();
        final Runnable wrap = new Runnable() {
            float width = 100000f;
            public void run() { }
        };
        final float[] wrapW = { 100000f };
        final Runnable relayout = new Runnable() {
            public void run() {
                lines.clear();
                FontMetrics m = fm(font);
                for (String para : text[0].split("\n", -1)) {
                    String[] words = para.split(" ");
                    StringBuilder cur = new StringBuilder();
                    for (String w : words) {
                        String t = cur.length() == 0 ? w : cur + " " + w;
                        if (m.stringWidth(t) > wrapW[0] && cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(w); }
                        else cur = new StringBuilder(t);
                    }
                    lines.add(cur.toString());
                }
                float mw = 0;
                for (String l : lines) mw = Math.max(mw, m.stringWidth(l));
                pos[2] = Math.min(mw, wrapW[0]);
                pos[3] = lines.size() * m.getHeight();
            }
        };
        relayout.run();
        final PositionAPI[] position = new PositionAPI[1];
        position[0] = proxy(PositionAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getX")) return pos[0];
                if (n.equals("getY")) return pos[1];
                if (n.equals("getWidth")) return pos[2];
                if (n.equals("getHeight")) return pos[3];
                if (n.equals("setLocation")) { pos[0] = (Float) a[0]; pos[1] = (Float) a[1]; return position[0]; }
                if (n.equals("setSize")) { return position[0]; }
                if (m.getReturnType() == PositionAPI.class) return position[0];
                return zero(m.getReturnType());
            }
        });
        return proxy(LabelAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getPosition")) return position[0];
                if (n.equals("setColor")) { color[0] = (Color) a[0]; return null; }
                if (n.equals("getColor")) return color[0];
                if (n.equals("setText")) { text[0] = (String) a[0]; relayout.run(); return null; }
                if (n.equals("getText")) return text[0];
                if (n.equals("autoSizeToWidth")) { wrapW[0] = (Float) a[0]; relayout.run(); return position[0]; }
                if (n.equals("computeTextWidth")) return (float) fm(font).stringWidth((String) a[0]);
                if (n.equals("computeTextHeight")) return (float) fm(font).getHeight();
                if (n.equals("render")) {
                    FontMetrics fm = fm(font);
                    Point2D.Float tl = GL11.toImage(pos[0], pos[1] + pos[3]);
                    double sc = Math.abs(GL11.matrix.getScaleX());
                    Font f = sc == 1.0 ? font : font.deriveFont((float) (font.getSize2D() * sc));
                    g.setFont(f);
                    g.setColor(color[0]);
                    FontMetrics fs = g.getFontMetrics(f);
                    for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), tl.x, tl.y + fs.getAscent() + i * fs.getHeight());
                    return null;
                }
                return zero(m.getReturnType());
            }
        });
    }

    static Object fakeWeapon(final String id) {
        final Object stats = proxy(com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getDps")) return 160f; if (n.equals("getSustainedDps")) return 160f; if (n.equals("getDamagePerShot")) return 200f;
                if (n.equals("getFluxPerSecond")) return 130f; if (n.equals("getFluxPerDam")) return 0.8f; if (n.equals("getRoF")) return 0.8f;
                return zero(m.getReturnType());
            }
        });
        return proxy(com.fs.starfarer.api.loading.WeaponSpecAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getWeaponName") || n.equals("getWeaponId")) return id;
                if (n.equals("getTurretSpriteName") || n.equals("getHardpointSpriteName")) return weaponSprite(id);
                if (n.equals("getSize")) return com.fs.starfarer.api.combat.WeaponAPI.WeaponSize.MEDIUM;
                if (n.equals("getMountType") || n.equals("getType")) return com.fs.starfarer.api.combat.WeaponAPI.WeaponType.BALLISTIC;
                if (n.equals("getPrimaryRoleStr")) return "Anti armor";
                if (n.equals("getManufacturer")) return "Common";
                if (n.equals("getOrdnancePointCost")) return 12f;
                if (n.equals("getMaxRange")) return 700f;
                if (n.equals("getDerivedStats")) return stats;
                if (n.equals("getDamageType")) return com.fs.starfarer.api.combat.DamageType.HIGH_EXPLOSIVE;
                if (n.equals("getAIHints")) return java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.WeaponAPI.AIHints.class);
                return zero(m.getReturnType());
            }
        });
    }

    static void setUp() {
        img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        GL11.g = g;
        GL11.screenH = H;
        GL11.matrix = new java.awt.geom.AffineTransform();
        Global.setSettings(proxy(SettingsAPI.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getScreenWidth")) return (float) W;
                if (n.equals("getScreenHeight")) return (float) H;
                if (n.equals("getScreenScaleMult")) return 1f;
                if (n.equals("getMouseX")) return (int) mouseX;
                if (n.equals("getMouseY")) return (int) mouseY;
                if (n.equals("getString") && "defaultFont".equals(a[0])) return "graphics/fonts/insignia15LTaa.fnt";
                if (n.equals("createLabel")) return label((String) a[0], (String) a[1]);
                if (n.equals("getSprite") && a != null && a.length == 1) return sprite((String) a[0]);
                if (n.equals("loadTexture")) return null;
                if (n.equals("getWeaponSpec")) return fakeWeapon((String) a[0]);
                if (n.equals("getHullSpec")) return hullSpec((String) a[0]);
                if (n.equals("getSkillSpec")) {
                    final String id = (String) a[0];
                    return proxy(com.fs.starfarer.api.characters.SkillSpecAPI.class, new InvocationHandler() {
                        public Object invoke(Object p2, Method m2, Object[] a2) {
                            String n2 = m2.getName();
                            String[] row = skillRow(id);
                            if (n2.equals("getName") && row != null && row.length > 1) return row[0];
                            if (n2.equals("getSpriteName")) return row != null && row.length > 1 ? row[1] : null;
                            if (n2.equals("getName")) { String[] w = id.split("_"); StringBuilder b = new StringBuilder(); for (String x : w) b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)); return b.toString(); }
                            if (n2.equals("getGoverningAptitudeName")) return "Combat";
                            if (n2.equals("getDescription")) return "Improves how the ship fights in this area. More effective at the elite level.";
                            return zero(m2.getReturnType());
                        }
                    });
                }
                if (n.equals("getDescription")) {
                    com.fs.starfarer.api.loading.Description d = new com.fs.starfarer.api.loading.Description((String) a[0], (com.fs.starfarer.api.loading.Description.Type) a[1]);
                    d.setText1("A heavy, slow-firing cannon that trades rate of fire for raw punch. Its high-explosive shells crack armor plating that lighter guns barely scratch, though it struggles against shields.");
                    return d;
                }
                if (n.equals("getAllWeaponSpecs")) {
                    List<Object> l = new ArrayList<Object>();
                    String[] base = { "Antimatter Blaster", "Arbalest Autocannon", "Assault Chaingun", "Autopulse Laser", "Burst PD Laser", "Cryoblaster", "Devastator Cannon", "Gauss Cannon",
                        "Graviton Beam", "Heavy Blaster", "Heavy Mortar", "Hellbore Cannon", "High Intensity Laser", "Hephaestus Assault Gun", "Ion Cannon", "Ion Pulser", "Kinetic Blaster",
                        "Lightning Gun", "Mark IX Autocannon", "Mjolnir Cannon", "Multi-Needler", "Phase Lance", "Plasma Cannon", "Pulse Laser", "Railgun", "Storm Needler", "Tachyon Lance",
                        "Thumper", "Typhoon Reaper", "Squall MLRS", "Locust SRM", "Hurricane MIRV", "Sabot SRM", "Atropos Torpedo", "Gorgon SRM", "Harpoon MRM", "Hammer Barrage", "Pilum LRM",
                        "Salamander MRM", "Breach SRM", "Dual Flak", "Flak Cannon", "Heavy Machine Gun", "Light Dual Autocannon", "Light Needler", "Vulcan Cannon", "Shredder", "Hypervelocity Driver" };
                    for (final String w : base) l.add(proxy(com.fs.starfarer.api.loading.WeaponSpecAPI.class, new InvocationHandler() {
                        public Object invoke(Object p2, Method m2, Object[] a2) {
                            String n2 = m2.getName();
                            if (n2.equals("getWeaponId")) return w;
                            if (n2.equals("getWeaponName")) return w;
                            if (n2.equals("getTurretSpriteName") || n2.equals("getHardpointSpriteName")) return weaponSprite(w);
                            if (n2.equals("getAIHints")) return java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.WeaponAPI.AIHints.class);
                            return zero(m2.getReturnType());
                        }
                    }));
                    return l;
                }
                if (n.equals("getModManager")) return proxy(com.fs.starfarer.api.ModManagerAPI.class, new InvocationHandler() {
                    public Object invoke(Object p2, Method m2, Object[] a2) {
                        if (m2.getName().equals("getModSpec")) return proxy(com.fs.starfarer.api.ModSpecAPI.class, new InvocationHandler() {
                            public Object invoke(Object p3, Method m3, Object[] a3) { return m3.getName().equals("getVersion") ? "0.9.2-beta" : zero(m3.getReturnType()); }
                        });
                        return zero(m2.getReturnType());
                    }
                });
                if (m.getReturnType().isPrimitive()) return zero(m.getReturnType());
                throw new RuntimeException("preview: no " + n);
            }
        }));
    }

    /** A backdrop like the title screen's: dark space with a few stars. */
    static void backdrop() {
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(10, 16, 28), W, H, new Color(2, 3, 8)));
        g.fillRect(0, 0, W, H);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 300; i++) { g.setColor(new Color(255, 255, 255, 40 + r.nextInt(120))); int s = r.nextInt(3) == 0 ? 2 : 1; g.fillRect(r.nextInt(W), r.nextInt(H), s, s); }
    }

    static void save(String name) throws Exception {
        File out = new File(System.getProperty("preview.out", "."), name + ".png");
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    public static void main(String[] args) throws Exception {
        String only = args.length > 0 ? args[0] : "";
        for (Screens.Shot s : Screens.all()) {
            if (!only.isEmpty() && !s.name.contains(only)) continue;
            W = s.w; H = s.h;
            mouseX = s.mx; mouseY = s.my;
            String size = System.getProperty("preview.size", Boolean.getBoolean("preview.p720") ? "1280x720" : "");
            if (!size.isEmpty()) { // every screen at one screen size (the mouse kept at the same place relative to it)
                int nw = Integer.parseInt(size.split("x")[0]), nh = Integer.parseInt(size.split("x")[1]);
                mouseX = mouseX < 0 ? mouseX : mouseX * nw / W; mouseY = mouseY < 0 ? mouseY : mouseY * nh / H;
                W = nw; H = nh;
            }
            String ui = System.getProperty("preview.ui", "");
            if (!ui.isEmpty()) Style.ui = Float.parseFloat(ui);
            setUp();
            backdrop();
            try { s.draw.run(); } catch (Throwable t) { t.printStackTrace(); }
            save(s.name);
        }
    }
}
