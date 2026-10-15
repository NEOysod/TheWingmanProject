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
                if (n.equals("getWeaponSpec")) return fakeWeapon((String) a[0]);
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
                            if (n2.equals("getAIHints")) return java.util.EnumSet.noneOf(com.fs.starfarer.api.combat.WeaponAPI.AIHints.class);
                            return zero(m2.getReturnType());
                        }
                    }));
                    return l;
                }
                if (n.equals("getModManager")) return proxy(com.fs.starfarer.api.ModManagerAPI.class, new InvocationHandler() {
                    public Object invoke(Object p2, Method m2, Object[] a2) {
                        if (m2.getName().equals("getModSpec")) return proxy(com.fs.starfarer.api.ModSpecAPI.class, new InvocationHandler() {
                            public Object invoke(Object p3, Method m3, Object[] a3) { return m3.getName().equals("getVersion") ? "0.9-beta" : zero(m3.getReturnType()); }
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
            setUp();
            backdrop();
            try { s.draw.run(); } catch (Throwable t) { t.printStackTrace(); }
            save(s.name);
        }
    }
}
