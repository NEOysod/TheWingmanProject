package wingman.client;

import java.awt.Color;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Firing sector for the joiner's selected or hovered refit mount, in the ship picture's coordinates. */
public final class RefitWeaponArc {
    private static final IntBuffer SCISSOR = BufferUtils.createIntBuffer(16);

    /** Outer edge, counterclockwise. The picture faces up; slot angles are relative to a ship facing right. */
    static float[] points(float cx, float cy, float radius, float angle, float arc) {
        if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(radius) || radius <= 0f
                || !Float.isFinite(angle) || !Float.isFinite(arc)) return new float[0];
        float span = Math.max(0f, Math.min(360f, arc));
        int steps = Math.max(1, (int) Math.ceil(span / 5f));
        float[] points = new float[(steps + 1) * 2];
        double start = angle % 360f + 90.0 - span / 2.0;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(start + span * i / steps);
            points[i * 2] = cx + (float) Math.cos(a) * radius;
            points[i * 2 + 1] = cy + (float) Math.sin(a) * radius;
        }
        return points;
    }

    static void draw(float cx, float cy, float radius, float angle, float arc, Color color,
                     float x, float y, float w, float h, float scale) {
        float[] points = points(cx, cy, radius, angle, arc);
        if (points.length == 0 || !Float.isFinite(scale) || scale <= 0f || w <= 0f || h <= 0f) return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_LINE_BIT | GL11.GL_SCISSOR_BIT);
        try {
            int left = (int) Math.floor(x * scale), bottom = (int) Math.floor(y * scale);
            int right = (int) Math.ceil((x + w) * scale), top = (int) Math.ceil((y + h) * scale);
            if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                SCISSOR.clear();
                GL11.glGetInteger(GL11.GL_SCISSOR_BOX, SCISSOR);
                left = Math.max(left, SCISSOR.get(0)); bottom = Math.max(bottom, SCISSOR.get(1));
                right = Math.min(right, SCISSOR.get(0) + SCISSOR.get(2));
                top = Math.min(top, SCISSOR.get(1) + SCISSOR.get(3));
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, 0.14f);
            if (arc > 0f) {
                GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                GL11.glVertex2f(cx, cy);
                for (int i = 0; i < points.length; i += 2) GL11.glVertex2f(points[i], points[i + 1]);
                GL11.glEnd();
            }
            GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f, 0.8f);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            if (arc < 360f) GL11.glVertex2f(cx, cy);
            for (int i = 0; i < points.length; i += 2) GL11.glVertex2f(points[i], points[i + 1]);
            if (arc < 360f) GL11.glVertex2f(cx, cy);
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
        }
    }
}
