package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.fs.starfarer.api.Global;
import com.fs.state.AppDriver;
import com.fs.state.AppState;

/**
 * Hides and shows the game's own title screen menu and logo, so the co-op lobby can have the whole screen (the
 * game draws them over anything of ours). Uses the game's internals (0.98a-RC8: the title screen's private
 * "titleWidget", whose class has a scrambled name): if anything about them differs, hide() says no and the
 * lobby stays the smaller box in the corner.
 */
final class TitleMenu {
    private TitleMenu() {}

    /** The menu's class in 0.98a-RC8: "Oo" and 254 more capital O's. */
    private static final String WIDGET_CLASS;
    static {
        StringBuilder sb = new StringBuilder("com.fs.starfarer.title.Oo");
        for (int i = 0; i < 254; i++) sb.append('O');
        WIDGET_CLASS = sb.toString();
    }

    private static MethodHandle getter, hide, show;
    private static boolean broken, hidden;
    /** Back on the title screen from a battle: whether the menu is shown or hidden isn't known (see forget). */
    private static boolean unsure;

    private static boolean ready() {
        if (broken) return false;
        if (getter != null) return true;
        try {
            Class<?> title = com.fs.starfarer.title.TitleScreenState.class;
            Class<?> widget = Class.forName(WIDGET_CLASS, false, title.getClassLoader());
            // MethodHandles.privateLookupIn (Java 9 on) is looked up by name so this builds for Java 8 too.
            MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                    MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
            MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(title, MethodHandles.lookup());
            getter = l.findGetter(title, "titleWidget", widget);
            hide = l.findVirtual(widget, "hide", MethodType.methodType(void.class));
            show = l.findVirtual(widget, "show", MethodType.methodType(void.class));
            return true;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(TitleMenu.class).info("Wingman: the title menu can't be moved aside on this game version; the lobby is shown in the corner (" + t + ")");
            return false;
        }
    }

    private static Object widget() throws Throwable {
        AppState s = AppDriver.getInstance().getCurrentState();
        if (!(s instanceof com.fs.starfarer.title.TitleScreenState)) return null;
        return getter.invoke(s);
    }

    /** Hide the menu and logo; false if that can't be done (then nothing changed). */
    static boolean hide() {
        if (!ready()) return false;
        try {
            Object w = widget();
            if (w == null) return false;
            if (!hidden || unsure) hide.invoke(w);
            hidden = true;
            unsure = false;
            return true;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(TitleMenu.class).warn("Wingman: could not hide the title menu; the lobby is shown in the corner", t);
            return false;
        }
    }

    /** Show them again (when the lobby closes). */
    static void show() {
        if ((!hidden && !unsure) || getter == null) return;
        hidden = false;
        unsure = false;
        try {
            Object w = widget();
            if (w != null) show.invoke(w);
        } catch (Throwable t) {
            Global.getLogger(TitleMenu.class).warn("Wingman: could not show the title menu again", t);
        }
    }

    /**
     * Back on the title screen from a battle. The game may keep the same title screen, with its menu still hidden
     * from before the battle: the next show() shows it whatever we last did (before, it was taken as shown, and the
     * menu could stay hidden for good after a few battles).
     */
    static void forget() { unsure = true; }

    static boolean isHidden() { return hidden; }
}
