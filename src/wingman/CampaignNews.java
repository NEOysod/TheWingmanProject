package wingman;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;

import wingman.net.HostSession;

/**
 * What the host's campaign has shown on its message line and as intel notifications since the lobby last looked.
 * Filled by a hook on CampaignUIAPI.addMessage (wingman.patch.Patches), sent to joiners by CampaignLobby as
 * Msg.NEWS. Transient; only filled while a co-op session is open on the campaign map.
 */
public final class CampaignNews {
    private static final int MAX_QUEUED = 40;
    private static final List<String> queue = new ArrayList<String>();
    private static String lastText = "";
    private static long lastAt;

    private CampaignNews() {}

    /** A message the campaign just showed. Cheap and safe from a hook: it never throws. */
    public static synchronized void offer(String text) {
        try {
            if (text == null) return;
            text = text.trim();
            if (text.isEmpty() || text.startsWith("Co-op:")) return; // Wingman's own lines are for the host
            if (Global.getCurrentState() != GameState.CAMPAIGN || HostSession.active() == null) return;
            long now = System.currentTimeMillis();
            if (text.equals(lastText) && now - lastAt < 2000) return; // one message reaching the hook through two overloads
            lastText = text;
            lastAt = now;
            if (queue.size() >= MAX_QUEUED) queue.remove(0);
            queue.add(text);
        } catch (Throwable t) { /* a missed line is not worth the game */ }
    }

    /** Everything offered since the last call, oldest first. */
    static synchronized List<String> drain() {
        List<String> out = new ArrayList<String>(queue);
        queue.clear();
        return out;
    }
}
