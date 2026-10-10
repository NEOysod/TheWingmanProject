package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;

import wingman.net.ModList;

final class Mods {
    private Mods() {}

    /** The mods enabled in this copy of the game. */
    static ModList enabled() {
        ModList list = new ModList();
        for (ModSpecAPI m : Global.getSettings().getModManager().getEnabledModsCopy()) {
            list.add(m.getId(), m.getName(), m.getVersion(), m.isUtility());
        }
        return list;
    }

    /** This mod's own version, as written in its mod_info.json. */
    static String ownVersion() {
        try {
            ModSpecAPI me = Global.getSettings().getModManager().getModSpec(WingmanConfig.MOD_ID);
            return me == null || me.getVersion() == null ? "" : me.getVersion().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** This copy of the game, e.g. "Starsector 0.98a-RC8". Host and joiners must be on the same one. */
    static String gameVersion() {
        try {
            String v = Global.getSettings().getVersionString();
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
