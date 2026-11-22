package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import com.fs.starfarer.title.TitleScreenState;
import com.fs.state.AppDriver;
import com.fs.state.AppState;

/**
 * Starts a mission straight from the title screen, the way the Missions screen's own "Play" button does:
 * it hands the title screen a freshly prepared mission, and the game takes it from there.
 *
 * This talks to the game's internals rather than its modding API. Those internals have scrambled names that
 * can change with any game update (these are from 0.98.5a). Every call into this class is therefore wrapped by
 * the caller: if a name no longer exists, the caller gets an error, says so, and the player starts the mission
 * from the Missions screen instead. Nothing else depends on it.
 *
 * The classes are found by name and called through java.lang.invoke: Java source can't spell them (the list of
 * missions comes from a method literally named "new", and the mission classes are in a package named like a
 * class beside it, com.fs.starfarer.title.Object).
 */
final class TitleLauncher {
    private TitleLauncher() {}

    /**
     * A mission's spec (its id, its script), a mission prepared from one, the spec store's list of them and the
     * spec's id: scrambled names, by game build, newest first: 0.98.5a-RC8, RC7, RC6, RC5. The first set whose
     * classes and methods are all there is used.
     */
    private static final String[][] MISSION_NAMES = {
        { "com.fs.starfarer.title.C.while", "com.fs.starfarer.title.C.for", "\u00d200000", "class" },
        { "com.fs.starfarer.title.C.Oo0" + repeat('O', 253), "com.fs.starfarer.title.C.K", "\u00d200000", "\u00d500000" },
        { "com.fs.starfarer.title.ooOO.Oo0O", "com.fs.starfarer.title.ooOO.o0oO", "new", "\u00d500000" },
        { "com.fs.starfarer.title.Object.N", "com.fs.starfarer.title.Object.K", "new", "\u00d500000" },
    };

    private static String repeat(char c, int n) { StringBuilder sb = new StringBuilder(n); for (int i = 0; i < n; i++) sb.append(c); return sb.toString(); }

    /** Set when the join mission was started from here: back on the title screen, the game shows its missions list. */
    static volatile boolean launched;

    /**
     * The title screen's main menu instead of its missions list (the game opens that list on coming back from a
     * mission, over the co-op screens), as the list's own Back does. False if not on the title screen.
     */
    static boolean backToMenu() {
        AppState state = AppDriver.getInstance().getCurrentState();
        if (!(state instanceof TitleScreenState)) return false;
        ((TitleScreenState) state).backToMenu();
        return true;
    }

    /** Returns null if the mission is now starting, otherwise why it could not be started. */
    static String start(String missionId) {
        AppState state = AppDriver.getInstance().getCurrentState();
        if (!(state instanceof TitleScreenState)) return "the game is not on the title screen";
        try {
            ClassLoader cl = TitleScreenState.class.getClassLoader();
            Class<?> store = Class.forName("com.fs.starfarer.loading.SpecStore", false, cl);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodHandle list = null, id = null, make = null, accept = null;
            Throwable missing = null;
            for (String[] names : MISSION_NAMES) {
                try {
                    Class<?> specClass = Class.forName(names[0], false, cl);
                    Class<?> missionClass = Class.forName(names[1], false, cl);
                    MethodHandle li = l.findStatic(store, names[2], MethodType.methodType(List.class));
                    MethodHandle i = l.findVirtual(specClass, names[3], MethodType.methodType(String.class)); // the mission's id
                    MethodHandle m = l.findConstructor(missionClass, MethodType.methodType(void.class, specClass));
                    MethodHandle a = l.findVirtual(TitleScreenState.class, "missionAccepted", MethodType.methodType(void.class, missionClass));
                    list = li; id = i; make = m; accept = a;
                    break;
                } catch (ClassNotFoundException e) { missing = e; }
                catch (NoSuchMethodException e) { missing = e; }
            }
            if (list == null) throw missing;
            List<?> missions = (List<?>) list.invoke();
            if (missions == null) return "the game's mission list is not available";
            for (Object spec : missions) {
                if (!missionId.equals((String) id.invoke(spec))) continue;
                // Preparing the mission runs its definition script (which asks the host about its battlefield);
                // accepting it makes the title screen switch to the battle on its next frame.
                accept.invoke(state, make.invoke(spec));
                launched = true;
                return null;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("the game's mission classes aren't as expected (another game version?)", t);
        }
        return "the mission '" + missionId + "' was not found";
    }
}
