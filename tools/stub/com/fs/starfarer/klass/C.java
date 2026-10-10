package com.fs.starfarer.klass;
// Compile-time stand-in for the game's combat message feed, com.fs.starfarer.class.C ("class" is a Java keyword,
// so the real package can't be written in source). tools/PatchPackage.java renames "klass" to "class" in the
// compiled wingman.MessageSpy. Only the methods MessageSpy overrides.
public class C {
    public C() {}
    /** The game's own lines (ship destroyed, objective taken...): indent 0. */
    public com.fs.starfarer.klass.O0OO.C o00000(java.lang.Object... parts) { return null; }
    /** Every line; CombatUIAPI.addMessage(indent, parts) comes straight here. */
    public com.fs.starfarer.klass.O0OO.C o00000(int indent, java.lang.Object... parts) { return null; }
}
