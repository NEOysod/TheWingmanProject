package wingman.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;

/** Selected lobby hull's system, resolved from this client's installed game data. */
public final class ShipSystemInfo {
    final String name, description;

    private ShipSystemInfo(String name, String description) {
        this.name = name;
        this.description = description;
    }

    static ShipSystemInfo read(String hullId) {
        String name = "Unavailable";
        try {
            if (hullId == null || hullId.isEmpty()) return unavailable(name);
            ShipHullSpecAPI hull = Global.getSettings().getHullSpec(hullId);
            if (hull == null) return unavailable(name);
            return readSystem(hull.getShipSystemId());
        } catch (RuntimeException e) {
            return unavailable(name);
        }
    }

    static ShipSystemInfo readSystem(String id) {
        String name = "Unavailable";
        try {
            if (id == null || id.trim().isEmpty()) return new ShipSystemInfo("None", "This hull has no ship system.");
            name = id;
            ShipSystemSpecAPI spec = Global.getSettings().getShipSystemSpec(id);
            if (spec == null) return unavailable(name);
            if (spec.getName() != null && !spec.getName().trim().isEmpty()) name = spec.getName();
            return new ShipSystemInfo(name, description(spec.getId()));
        } catch (RuntimeException e) {
            return unavailable(name);
        }
    }

    private static ShipSystemInfo unavailable(String name) {
        return new ShipSystemInfo(name, "Ship system data unavailable. Check that installed mods match the host's.");
    }

    private static String description(String id) {
        try {
            Description d = Global.getSettings().getDescription(id, Description.Type.SHIP_SYSTEM);
            if (d != null && d.hasText1()) {
                String text = d.getText1().trim();
                if (!text.isEmpty()) return text.length() > 700 ? text.substring(0, 700) + "..." : text;
            }
        } catch (RuntimeException e) { /* keep the system name even without its description */ }
        return "No description available.";
    }
}
