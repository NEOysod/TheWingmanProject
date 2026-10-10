package wingman;

/**
 * The battle's message feed with a listener on it (see MessageTap). Built against a stand-in for the game's
 * feed class (com.fs.starfarer.class.C, which Java source can't name) and pointed at the real one by the build.
 *
 * The feed has two ways in. The game's own lines (a ship destroyed, deployed or retreating, an objective
 * taken, no command points) come through o00000(Object...), which passes them on to o00000(int, Object...).
 * Every script's line, from CombatUIAPI.addMessage, comes straight to o00000(int, Object...). So a line that
 * reaches the second without having passed through the first is a script's, and those are what the sink
 * sees; it may also hold one back. Nothing else about the feed changes.
 */
public class MessageSpy extends com.fs.starfarer.klass.C {
    /** Told of every script's line; returns false to keep it off this screen. */
    interface Sink { boolean line(int indent, Object[] parts); }

    private int gameLine;
    volatile Sink sink;
    /** Set while Wingman itself writes a line (Ui.say): those are never sent or held back. */
    static volatile boolean ownLine;

    public MessageSpy() { super(); }

    @Override
    public com.fs.starfarer.klass.O0OO.C o00000(Object... parts) {
        gameLine++;
        try {
            return super.o00000(parts);
        } finally {
            gameLine--;
        }
    }

    @Override
    public com.fs.starfarer.klass.O0OO.C o00000(int indent, Object... parts) {
        Sink s = sink;
        if (gameLine == 0 && !ownLine && s != null) {
            boolean show = true;
            try { show = s.line(indent, parts); } catch (Throwable t) { /* shown as usual */ }
            if (!show) return null; // the one caller of this way in (addMessage) drops the result
        }
        return super.o00000(indent, parts);
    }

    /** Show a line from the host's game, past the sink. */
    void showHostLine(int indent, Object[] parts) {
        super.o00000(indent, parts);
    }
}
