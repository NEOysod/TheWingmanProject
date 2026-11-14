package org.lwjgl.input;
/** Test stand-in for LWJGL's keyboard (the real one needs a game window): no key is down. */
public class Keyboard {
    public static boolean isKeyDown(int key) { return false; }
}
