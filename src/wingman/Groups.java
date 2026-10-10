package wingman;

import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

final class Groups {
    private Groups() {}

    /**
     * Which weapon group the ship has selected, as a position in its group list, or -1.
     * Groups are matched by the weapons inside them, because the game hands out fresh group objects each
     * time it is asked and two objects for the same group don't compare as equal.
     */
    static int selectedIndex(ShipAPI ship, List<WeaponGroupAPI> groups) {
        WeaponGroupAPI sel = ship.getSelectedGroupAPI();
        if (sel == null) return -1;
        int i = groups.indexOf(sel);
        if (i >= 0) return i;
        List<WeaponAPI> want = sel.getWeaponsCopy();
        for (i = 0; i < groups.size(); i++) {
            List<WeaponAPI> have = groups.get(i).getWeaponsCopy();
            if (have.size() != want.size() || have.isEmpty()) continue;
            boolean same = true;
            for (int k = 0; k < have.size(); k++) if (have.get(k) != want.get(k)) { same = false; break; }
            if (same) return i;
        }
        return -1;
    }
}
