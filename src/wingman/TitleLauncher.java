package wingman;

import java.util.List;

import com.fs.state.AppDriver;
import com.fs.state.AppState;

import wingman.util.ReflectionUtils;

/**
 * Starts a mission straight from the title screen, the way the Missions screen's own "Play" button does:
 * it hands the title screen a freshly prepared mission, and the game takes it from there.
 *
 * This is the one class in the mod that talks to the game's internals rather than its modding API. None of
 * those classes is named here: they are looked up by name and reached through ReflectionUtils, so this file
 * compiles and loads on any game version. Every call into this class is wrapped by the caller: if a name no
 * longer exists, the caller gets an error, says so, and the player starts the mission from the Missions screen
 * instead. Nothing else depends on it.
 */
final class TitleLauncher {
    private TitleLauncher() {}

    /** Set when the join mission was started from here: back on the title screen, the game shows its missions list. */
    static volatile boolean launched;

    // The game's internals these reach into (0.98a-RC8). They can change with any game update; the caller
    // reports the failure and the player starts the mission from the Missions screen instead.
    /** {@code com.fs.starfarer.loading.SpecStore}, which holds the missions found while the game started. */
    private static final String SPEC_STORE = "com.fs.starfarer.loading.SpecStore";
    /** {@code SpecStore.new()} returns those missions; "new" cannot be spelled in Java source. */
    private static final String MISSION_LIST = "new";
    /** The mission spec's id getter (the Missions screen lists and starts missions by this id). */
    private static final String MISSION_ID = "\u00D500000";
    /** {@code com.fs.starfarer.title.TitleScreenState.STATE_ID}: the state this class acts on. */
    private static final String TITLE_STATE_ID = "Title Screen State";

    /**
     * The title screen's main menu instead of its missions list (the game opens that list on coming back from a
     * mission, over the co-op screens), as the list's own Back does. False if not on the title screen.
     */
    static boolean backToMenu() throws Throwable {
        AppState state = AppDriver.getInstance().getCurrentState();
        if (state == null || !TITLE_STATE_ID.equals(state.getID())) return false;
        ReflectionUtils.invoke("backToMenu", state);
        return true;
    }

    /** Returns null if the mission is now starting, otherwise why it could not be started. */
    static String start(String missionId) throws Throwable {
        AppState state = AppDriver.getInstance().getCurrentState();
        if (state == null || !TITLE_STATE_ID.equals(state.getID())) return "the game is not on the title screen";
        List<?> missions = missionList();
        if (missions == null) return "the game's mission list is not available";
        for (Object spec : missions) {
            if (!missionId.equals(ReflectionUtils.invoke(MISSION_ID, spec))) continue;
            // Preparing the mission runs its definition script (which asks the host about its battlefield);
            // accepting it makes the title screen switch to the battle on its next frame.
            ReflectionUtils.ReflectedMethod accepted = ReflectionUtils.getMethod("missionAccepted", state.getClass());
            Object mission = ReflectionUtils.instantiate(accepted.getParameterTypes()[0], spec);
            accepted.invoke(state, mission);
            launched = true;
            return null;
        }
        return "the mission '" + missionId + "' was not found";
    }

    private static List<?> missionList() throws Throwable {
        Class<?> store;
        try {
            store = Class.forName(SPEC_STORE, false, TitleLauncher.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(SPEC_STORE + " is not on this game version", missing);
        }
        return (List<?>) ReflectionUtils.invokeStatic(0, MISSION_LIST, store);
    }
}