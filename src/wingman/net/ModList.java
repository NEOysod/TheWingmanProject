package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The mods a player has enabled, sent when joining so the host can refuse a mismatched game. */
public final class ModList {
    public static final class Mod {
        public String id = "", name = "", version = "";
        /** Utility mods (UI, tools) add no ships or weapons, so by default they may differ. */
        public boolean utility;
    }

    public final List<Mod> mods = new ArrayList<Mod>();

    public void add(String id, String name, String version, boolean utility) {
        Mod m = new Mod();
        m.id = id == null ? "" : id;
        m.name = name == null || name.isEmpty() ? m.id : name;
        m.version = version == null ? "" : version.trim();
        m.utility = utility;
        mods.add(m);
    }

    public void write(Out out) throws IOException {
        out.writeShort(mods.size());
        for (Mod m : mods) {
            out.writeUTF(m.id);
            out.writeUTF(m.name);
            out.writeUTF(m.version);
            out.writeBoolean(m.utility);
        }
    }

    public static ModList read(In in) throws IOException {
        ModList l = new ModList();
        int n = in.readShort() & 0xFFFF;
        for (int i = 0; i < n; i++) {
            String id = in.readUTF(), name = in.readUTF(), version = in.readUTF();
            l.add(id, name, version, in.readBoolean());
        }
        return l;
    }

    /**
     * Compares a joiner's mods with the host's. Returns one line per difference, empty if they match.
     * @param includeUtility false: mods either side marks as utility are ignored.
     */
    public static List<String> differences(ModList host, ModList joiner, boolean includeUtility) {
        Map<String, Mod> h = new LinkedHashMap<String, Mod>(), j = new LinkedHashMap<String, Mod>();
        for (Mod m : host.mods) h.put(m.id, m);
        for (Mod m : joiner.mods) j.put(m.id, m);
        List<String> out = new ArrayList<String>();
        for (Mod m : h.values()) {
            Mod o = j.get(m.id);
            if (!includeUtility && (m.utility || (o != null && o.utility))) continue;
            if (o == null) out.add("Missing:  " + m.name + " " + m.version + "  (the host has it, you don't)");
            else if (!m.version.equals(o.version)) {
                out.add("Version:  " + m.name + "  (host " + m.version + ", you " + o.version + ")");
            }
        }
        for (Mod m : j.values()) {
            if (h.containsKey(m.id) || (!includeUtility && m.utility)) continue;
            out.add("Extra:  " + m.name + " " + m.version + "  (you have it, the host doesn't)");
        }
        return out;
    }
}
