package wingman;

import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.input.InputEventClass;
import com.fs.starfarer.api.input.InputEventType;

/**
 * A key press made by a click: a key hint, a close box or an arrow clicked with the mouse is turned into the key
 * it names and handed to the same code the keyboard goes through, so the mouse and the keys always do the same.
 * Only ever in Wingman's own lists of events, never given to the game.
 */
final class FakeKey implements InputEventAPI {
    private final int key;
    private final boolean ctrl;
    private boolean consumed;

    FakeKey(int key, boolean ctrl) { this.key = key; this.ctrl = ctrl; }

    public int getEventValue() { return key; }
    public int getX() { return 0; }
    public int getY() { return 0; }
    public int getDX() { return 0; }
    public int getDY() { return 0; }
    public InputEventClass getEventClass() { return InputEventClass.KEYBOARD_EVENT; }
    public void logEvent() {}
    public boolean isConsumed() { return consumed; }
    public void consume() { consumed = true; }
    public boolean isRepeat() { return false; }
    public InputEventType getEventType() { return InputEventType.KEY_DOWN; }
    public boolean isMouseEvent() { return false; }
    public boolean isKeyboardEvent() { return true; }
    public boolean isKeyUpEvent() { return false; }
    public boolean isKeyDownEvent() { return true; }
    public boolean isMouseUpEvent() { return false; }
    public boolean isMouseDownEvent() { return false; }
    public boolean isLMBDownEvent() { return false; }
    public boolean isLMBEvent() { return false; }
    public boolean isRMBEvent() { return false; }
    public boolean isLMBUpEvent() { return false; }
    public boolean isRMBDownEvent() { return false; }
    public boolean isRMBUpEvent() { return false; }
    public boolean isMouseMoveEvent() { return false; }
    public boolean isMouseScrollEvent() { return false; }
    public char getEventChar() { return key == 28 ? '\r' : key == 57 ? ' ' : (char) 0; }
    public boolean isAltDown() { return false; }
    public boolean isCtrlDown() { return ctrl; }
    public boolean isShiftDown() { return false; }
    public boolean isUnmodified() { return !ctrl; }
    public boolean isDoubleClick() { return false; }
    public boolean isModifierKey() { return false; }
    public boolean isControlDownEvent(String controlEnumName) { return false; }
    public boolean isControlUpEvent(String controlEnumName) { return false; }
    public boolean isControlActivated(String enumName) { return false; }
}
