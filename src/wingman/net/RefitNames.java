package wingman.net;

import com.fs.starfarer.api.Global;

/**
 * Names from this game's specs, and the submarkets' as the host's data has them: for what the host and the joiner
 * show of a refit plan.
 */
public final class RefitNames implements RefitPlan.Names {
    private final RefitData d;

    public RefitNames(RefitData d) { this.d = d; }

    public String item(String key) {
        String id = key.length() > 2 ? key.substring(2) : key;
        try {
            return key.startsWith("f:") ? Global.getSettings().getFighterWingSpec(id).getWingName() + " LPC" : Global.getSettings().getWeaponSpec(id).getWeaponName();
        } catch (Throwable t) {
            return id;
        }
    }

    public String mod(String id) {
        try { return Global.getSettings().getHullModSpec(id).getDisplayName(); } catch (Throwable t) { return id; }
    }

    public String source(String id) {
        if (RefitRequest.CARGO.equals(id)) return "cargo";
        if (RefitRequest.STORAGE.equals(id)) return "storage";
        RefitData.Market m = d == null ? null : d.market(id);
        if (m != null) return m.name;
        // a submarket that isn't here (a plan made at another market): its id, made readable ("open_market": "Open market")
        String s = id.replace('_', ' ').trim();
        return s.isEmpty() ? "a market" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}