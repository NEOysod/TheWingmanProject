package wingman;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import com.fs.starfarer.api.combat.ShipAPI;

import wingman.api.WingmanAPI;

/**
 * Writes on each ship who flies it, for other mods (see WingmanAPI.PILOT_KEY / REMOTE_KEY): the name of the
 * person at its helm, and whether that person is on another PC. Each frame the plugin says who flies what;
 * ships no longer flown by a person lose the marks.
 */
final class PilotMarks {
    private final Map<ShipAPI, String> marked = new IdentityHashMap<ShipAPI, String>();
    private final Map<ShipAPI, Boolean> now = new IdentityHashMap<ShipAPI, Boolean>();
    private final Map<ShipAPI, String> names = new IdentityHashMap<ShipAPI, String>();

    /** This frame, this ship is flown by this person (remote: on another PC). */
    void flown(ShipAPI ship, String name, boolean remote) {
        if (ship == null || name == null || now.containsKey(ship)) return;
        now.put(ship, remote);
        names.put(ship, name);
    }

    /** Write the marks for this frame and clear those no longer true. */
    void apply() {
        for (Iterator<Map.Entry<ShipAPI, String>> it = marked.entrySet().iterator(); it.hasNext();) {
            ShipAPI s = it.next().getKey();
            if (now.containsKey(s)) continue;
            s.removeCustomData(WingmanAPI.PILOT_KEY);
            s.removeCustomData(WingmanAPI.REMOTE_KEY);
            it.remove();
        }
        for (Map.Entry<ShipAPI, Boolean> e : now.entrySet()) {
            ShipAPI s = e.getKey();
            String name = names.get(s);
            Map<String, Object> data = s.getCustomData();
            if (!name.equals(data.get(WingmanAPI.PILOT_KEY))) s.setCustomData(WingmanAPI.PILOT_KEY, name);
            boolean remote = e.getValue();
            if (remote != data.containsKey(WingmanAPI.REMOTE_KEY)) {
                if (remote) s.setCustomData(WingmanAPI.REMOTE_KEY, Boolean.TRUE);
                else s.removeCustomData(WingmanAPI.REMOTE_KEY);
            }
            marked.put(s, name);
        }
        now.clear();
        names.clear();
    }

    /** Take every mark off (co-op stopping). */
    void clear() {
        apply();
        for (ShipAPI s : marked.keySet()) { s.removeCustomData(WingmanAPI.PILOT_KEY); s.removeCustomData(WingmanAPI.REMOTE_KEY); }
        marked.clear();
    }
}
