package org.lwjgl.input;
/** Test stand-in for LWJGL's mouse (the real one needs a game window): at 0,0, nothing pressed. */
public class Mouse {
    public static int getX() { return 0; }
    public static int getY() { return 0; }
    public static boolean isButtonDown(int button) { return false; }
}
