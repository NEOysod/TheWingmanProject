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

    /** A mission's spec (its id, its script) and a mission prepared from one: scrambled names, 0.98.5a. */
    private static final String MISSION_SPEC = "com.fs.starfarer.title.Object.N", MISSION = "com.fs.starfarer.title.Object.K";

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
            Class<?> specClass = Class.forName(MISSION_SPEC, false, cl);
            Class<?> missionClass = Class.forName(MISSION, false, cl);
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            MethodHandle list = l.findStatic(store, "new", MethodType.methodType(List.class));
            MethodHandle id = l.findVirtual(specClass, "\u00D500000", MethodType.methodType(String.class)); // the mission's id
            MethodHandle make = l.findConstructor(missionClass, MethodType.methodType(void.class, specClass));
            MethodHandle accept = l.findVirtual(TitleScreenState.class, "missionAccepted", MethodType.methodType(void.class, missionClass));
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
