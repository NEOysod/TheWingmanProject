package wingman;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

/** Geometry plus real offscreen OpenGL checks; no running Starsector instance needed. */
public final class RefitWeaponArcTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        geometry();
        Pbuffer buffer = new Pbuffer(128, 128, new PixelFormat(), null, null);
        try {
            buffer.makeCurrent();
            rendering();
        } finally {
            buffer.destroy();
        }
        System.out.println("Refit weapon arcs: " + checks + " checks passed (geometry and offscreen OpenGL).");
    }

    private static void geometry() {
        endpoint(0f, 20f, 40f);
        endpoint(90f, 10f, 30f);
        endpoint(-90f, 30f, 30f);
        endpoint(180f, 20f, 20f);
        endpoint(360f, 20f, 40f);
        float[] sector = RefitWeaponArc.points(0f, 0f, 10f, 0f, 90f);
        near(sector[0], (float) Math.sqrt(50), "forward sector starts right");
        near(sector[1], (float) Math.sqrt(50), "forward sector starts up");
        near(sector[sector.length - 2], -(float) Math.sqrt(50), "forward sector ends left");
        near(sector[sector.length - 1], (float) Math.sqrt(50), "forward sector ends up");
        float[] circle = RefitWeaponArc.points(0f, 0f, 10f, 0f, 360f);
        near(circle[0], circle[circle.length - 2], "full circle closes x");
        near(circle[1], circle[circle.length - 1], "full circle closes y");
        check(circle.length == 146, "full circle has five-degree segments");
        check(RefitWeaponArc.points(0f, 0f, 10f, 0f, 720f).length == circle.length, "oversized arc clamps");
        check(RefitWeaponArc.points(0f, 0f, 10f, 0f, -1f).length == 4, "negative arc clamps");
        float[] wrapped = RefitWeaponArc.points(0f, 0f, 10f, 350f, 40f);
        near(wrapped[8], (float) Math.cos(Math.toRadians(80)) * 10f, "wrapped sector center x");
        near(wrapped[9], (float) Math.sin(Math.toRadians(80)) * 10f, "wrapped sector center y");
        for (int i = 0; i < circle.length; i += 2) {
            near((float) Math.hypot(circle[i], circle[i + 1]), 10f, "perimeter radius");
        }
        check(RefitWeaponArc.points(0f, 0f, 0f, 0f, 90f).length == 0, "zero radius rejected");
        check(RefitWeaponArc.points(0f, 0f, -1f, 0f, 90f).length == 0, "negative radius rejected");
        check(RefitWeaponArc.points(0f, 0f, 10f, Float.NaN, 90f).length == 0, "NaN angle rejected");
        check(RefitWeaponArc.points(0f, 0f, 10f, 0f, Float.POSITIVE_INFINITY).length == 0, "infinite arc rejected");
        check(RefitWeaponArc.points(Float.NaN, 0f, 10f, 0f, 90f).length == 0, "NaN origin rejected");
    }

    private static void endpoint(float angle, float x, float y) {
        float[] p = RefitWeaponArc.points(20f, 30f, 10f, angle, 0f);
        near(p[0], x, "fixed mount x at " + angle);
        near(p[1], y, "fixed mount y at " + angle);
    }

    private static void rendering() {
        GL11.glViewport(0, 0, 128, 128);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, 128, 0, 128, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        clear();
        draw(90f, 0f, 0f, 128f, 128f, 1f);
        check(red(64, 84) > 0, "forward wedge filled");
        check(red(64, 44) == 0, "aft outside forward wedge");
        check(red(90, 64) == 0, "side outside forward wedge");

        clear();
        draw(360f, 0f, 0f, 128f, 128f, 1f);
        check(red(64, 84) > 0 && red(64, 44) > 0, "360-degree turret filled fore and aft");
        check(red(64, 54) < 100, "full circle has no radial border");

        clear();
        draw(0f, 0f, 0f, 128f, 128f, 1f);
        check(red(63, 84) > 0 || red(64, 84) > 0, "fixed mount shows direction ray");
        check(red(70, 84) == 0, "fixed mount has no sector fill");

        clear();
        draw(360f, 60f, 60f, 12f, 12f, 1f);
        check(red(64, 68) > 0, "inside ship picture clip");
        check(red(64, 84) == 0, "outside ship picture clipped");
        check(!GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), "disabled scissor state restored");

        clear();
        draw(360f, 30f, 30f, 6f, 6f, 2f);
        check(red(64, 68) > 0 && red(64, 84) == 0, "clip uses physical pixels at UI scale 2");

        clear();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(62, 62, 4, 4);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glLineWidth(1f);
        GL11.glColor4f(0.25f, 0.5f, 0.75f, 1f);
        draw(360f, 60f, 60f, 12f, 12f, 1f);
        check(red(64, 64) > 0, "inside parent clip filled");
        check(red(64, 68) == 0, "parent clip intersected");
        check(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), "enabled scissor state restored");
        IntBuffer box = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, box);
        check(box.get(0) == 62 && box.get(1) == 62 && box.get(2) == 4 && box.get(3) == 4, "scissor box restored");
        check(GL11.glIsEnabled(GL11.GL_TEXTURE_2D), "texture state restored");
        check(!GL11.glIsEnabled(GL11.GL_BLEND), "blend enable restored");
        check(GL11.glGetInteger(GL11.GL_BLEND_SRC) == GL11.GL_ONE, "blend source restored");
        check(GL11.glGetInteger(GL11.GL_BLEND_DST) == GL11.GL_ZERO, "blend destination restored");
        near(GL11.glGetFloat(GL11.GL_LINE_WIDTH), 1f, "line width restored");
        java.nio.FloatBuffer color = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        near(color.get(0), 0.25f, "current color restored");
        check(GL11.glGetError() == GL11.GL_NO_ERROR, "no OpenGL errors");
    }

    private static void draw(float arc, float x, float y, float w, float h, float scale) {
        RefitWeaponArc.draw(64f, 64f, 30f, 0f, arc, Color.WHITE, x, y, w, h, scale);
    }

    private static void clear() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static int red(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        return pixel.get(0) & 255;
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 0.001f, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
