package org.lwjgl.opengl;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Preview only (never in the mod): draws Wingman's immediate-mode calls into a Java2D image. */
public class GL11 {
    public static final int GL_QUADS = 0x7, GL_LINE_LOOP = 0x2, GL_LINE_STRIP = 0x3, GL_LINES = 0x1, GL_TRIANGLES = 0x4, GL_TRIANGLE_FAN = 0x6,
            GL_LINE_BIT = 0x4, GL_TEXTURE_2D = 0xDE1, GL_BLEND = 0xBE2, GL_SRC_ALPHA = 0x302, GL_ONE_MINUS_SRC_ALPHA = 0x303,
            GL_ENABLE_BIT = 0x2000, GL_CURRENT_BIT = 0x1, GL_COLOR_BUFFER_BIT = 0x4000;

    public static Graphics2D g;
    public static int screenH;
    /** The GL model matrix (y up). */
    public static AffineTransform matrix = new AffineTransform();
    private static final Deque<AffineTransform> stack = new ArrayDeque<AffineTransform>();
    private static Color color = Color.WHITE;
    private static float lineWidth = 1f;
    private static int mode;
    private static final List<float[]> verts = new ArrayList<float[]>();

    /** A GL point to image pixels. */
    public static Point2D.Float toImage(float x, float y) {
        Point2D.Float p = new Point2D.Float();
        matrix.transform(new Point2D.Float(x, y), p);
        return new Point2D.Float(p.x, screenH - p.y);
    }

    public static void glPushAttrib(int m) {}
    public static void glPopAttrib() {}
    public static void glDisable(int c) {}
    public static void glEnable(int c) {}
    public static void glBlendFunc(int s, int d) {}
    public static void glColor4f(float r, float gg, float b, float a) { color = new Color(clamp(r), clamp(gg), clamp(b), clamp(a)); }
    private static float clamp(float f) { return Math.max(0f, Math.min(1f, f)); }
    public static void glLineWidth(float w) { lineWidth = w; }
    public static void glPushMatrix() { stack.push(new AffineTransform(matrix)); }
    public static void glPopMatrix() { matrix = stack.isEmpty() ? new AffineTransform() : stack.pop(); }
    public static void glTranslatef(float x, float y, float z) { matrix.translate(x, y); }
    public static void glScalef(float x, float y, float z) { matrix.scale(x, y); }
    public static void glRotatef(float a, float x, float y, float z) { matrix.rotate(Math.toRadians(a)); }
    public static void glBegin(int m) { mode = m; verts.clear(); }
    public static void glVertex2f(float x, float y) { verts.add(new float[] { x, y }); }
    public static void glEnd() {
        if (g == null) return;
        g.setColor(color);
        switch (mode) {
            case GL_QUADS: for (int i = 0; i + 3 < verts.size(); i += 4) g.fill(path(i, 4, true)); break;
            case GL_TRIANGLES: for (int i = 0; i + 2 < verts.size(); i += 3) g.fill(path(i, 3, true)); break;
            case GL_TRIANGLE_FAN: if (verts.size() > 2) g.fill(path(1, verts.size() - 1, true)); break;
            case GL_LINE_LOOP: g.setStroke(new BasicStroke(lineWidth)); g.draw(path(0, verts.size(), true)); break;
            case GL_LINE_STRIP: g.setStroke(new BasicStroke(lineWidth)); g.draw(path(0, verts.size(), false)); break;
            case GL_LINES: g.setStroke(new BasicStroke(lineWidth)); for (int i = 0; i + 1 < verts.size(); i += 2) g.draw(path(i, 2, false)); break;
            default: break;
        }
    }

    private static Path2D path(int from, int n, boolean close) {
        Path2D.Float p = new Path2D.Float();
        for (int i = 0; i < n; i++) {
            float[] v = verts.get(from + i);
            Point2D.Float q = toImage(v[0], v[1]);
            if (i == 0) p.moveTo(q.x, q.y); else p.lineTo(q.x, q.y);
        }
        if (close) p.closePath();
        return p;
    }
}
