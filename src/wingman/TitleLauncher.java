package wingman;

import java.util.List;

import com.fs.starfarer.loading.SpecStore;
import com.fs.starfarer.title.TitleScreenState;
import com.fs.starfarer.title.Object.L;
import com.fs.starfarer.title.Object.Oo0O;
import com.fs.state.AppDriver;
import com.fs.state.AppState;

/**
 * Starts a mission straight from the title screen, the way the Missions screen's own "Play" button does:
 * it hands the title screen a freshly prepared mission, and the game takes it from there.
 *
 * This is the one class in the mod that talks to the game's internals rather than its modding API. Those
 * internals have scrambled names that can change with any game update (these are from 0.98a-RC8). Every call
 * into this class is therefore wrapped by the caller: if a name no longer exists, the caller gets an error,
 * says so, and the player starts the mission from the Missions screen instead. Nothing else depends on it.
 *
 * Build note: the game's list of missions is returned by a method literally named "new", which Java source
 * cannot spell. This file is compiled against a stand-in named coopNew() and the build renames that one
 * reference in the compiled class (see build.sh / build.bat).
 */
final class TitleLauncher {
    private TitleLauncher() {}

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
        List<Oo0O> missions = SpecStore.coopNew();
        if (missions == null) return "the game's mission list is not available";
        for (Oo0O spec : missions) {
            if (!missionId.equals(spec.\u00D500000())) continue; // the mission's id
            // Preparing the mission runs its definition script (which asks the host about its battlefield);
            // accepting it makes the title screen switch to the battle on its next frame.
            ((TitleScreenState) state).missionAccepted(new L(spec));
            launched = true;
            return null;
        }
        return "the mission '" + missionId + "' was not found";
    }
}
