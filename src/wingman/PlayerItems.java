package wingman;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;

import wingman.net.RefitData;

/**
 * Items kept per player in the host's save: what each wishes for (WISHES) and what the host has bought and set
 * aside in cargo for them (SET_ASIDE). Kept in the sector's persistent data as plain maps (player name -> item key
 * -> count), so the save loads with or without Wingman. Item keys are RefitData's ("w:" weapon, "f:" fighter wing).
 */
final class PlayerItems {
    static final PlayerItems WISHES = new PlayerItems("wingman_wish_lists");
    static final PlayerItems SET_ASIDE = new PlayerItems("wingman_refit_set_aside");
    /** Most kinds of item per player, and most of one item on a wish list. */
    static final int MAX_KINDS = 60, MAX_COUNT = 99;

    private final String key;

    private PlayerItems(String key) { this.key = key; }

    /** The whole store (player -> items), made if asked and missing; null without a sector. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> store(boolean make) {
        try {
            Map<String, Object> data = Global.getSector().getPersistentData();
            Object o = data.get(key);
            if (o instanceof Map) return (Map<String, Object>) o;
            if (!make) return null;
            Map<String, Object> m = new HashMap<String, Object>();
            data.put(key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One player's items, made if asked and missing. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> of(String player, boolean make) {
        Map<String, Object> all = store(make);
        if (all == null) return null;
        Object o = all.get(player);
        if (o instanceof Map) return (Map<String, Object>) o;
        if (!make) return null;
        Map<String, Object> m = new HashMap<String, Object>();
        all.put(player, m);
        return m;
    }

    private static int count(Object o) { return o instanceof Number ? Math.max(0, ((Number) o).intValue()) : 0; }

    static boolean validKey(String k) {
        return k != null && k.length() > 2 && k.length() <= 102 && (k.startsWith("w:") || k.startsWith("f:"));
    }

    /** Adds (or with a negative n, takes away) some of an item for a player; returns how many it actually changed by. */
    int add(String player, String item, int n) {
        if (player == null || !validKey(item) || n == 0) return 0;
        Map<String, Object> m = of(player, n > 0);
        if (m == null) return 0;
        int had = count(m.get(item));
        if (n > 0 && had == 0 && m.size() >= MAX_KINDS) return 0;
        int now = Math.max(0, Math.min(this == WISHES ? MAX_COUNT : Integer.MAX_VALUE / 2, had + n));
        if (now == 0) m.remove(item);
        else m.put(item, now);
        if (m.isEmpty()) {
            Map<String, Object> all = store(false);
            if (all != null) all.remove(player);
        }
        return now - had;
    }

    /** Everything kept for a player, gone. */
    void clear(String player) {
        Map<String, Object> all = store(false);
        if (all != null) all.remove(player);
    }

    /** A player's items as kept: item key -> count (only good keys and counts). */
    Map<String, Integer> recorded(String player) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        Map<String, Object> m = of(player, false);
        if (m == null) return out;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            int n = count(e.getValue());
            if (n > 0 && validKey(e.getKey())) out.put(e.getKey(), n);
        }
        return out;
    }

    /** The players with anything kept, by name. */
    List<String> players() {
        List<String> out = new ArrayList<String>();
        Map<String, Object> all = store(false);
        if (all == null) return out;
        for (String p : new ArrayList<String>(all.keySet())) if (p != null && !recorded(p).isEmpty()) out.add(p);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** What is kept for this player and is really in the cargo (all of it without a cargo to check). */
    List<RefitData.Item> mine(String player, CargoAPI cargo) {
        List<RefitData.Item> out = new ArrayList<RefitData.Item>();
        for (Map.Entry<String, Integer> e : recorded(player).entrySet()) {
            int n = cargo == null ? e.getValue() : Math.min(e.getValue(), inCargo(cargo, e.getKey()));
            if (n > 0) out.add(item(e.getKey(), n));
        }
        return out;
    }

    /** What is kept for everyone else and is in the cargo (after this player's own share of it). */
    List<RefitData.Item> others(String player, CargoAPI cargo) {
        Map<String, Integer> theirs = new LinkedHashMap<String, Integer>();
        for (String p : players()) {
            if (p.equals(player)) continue;
            for (Map.Entry<String, Integer> e : recorded(p).entrySet()) {
                Integer had = theirs.get(e.getKey());
                theirs.put(e.getKey(), (had == null ? 0 : had) + e.getValue());
            }
        }
        Map<String, Integer> mine = recorded(player);
        List<RefitData.Item> out = new ArrayList<RefitData.Item>();
        for (Map.Entry<String, Integer> e : theirs.entrySet()) {
            Integer own = mine.get(e.getKey());
            int n = Math.min(e.getValue(), Math.max(0, inCargo(cargo, e.getKey()) - (own == null ? 0 : own)));
            if (n > 0) out.add(item(e.getKey(), n));
        }
        return out;
    }

    static RefitData.Item item(String key, int n) {
        return new RefitData.Item(key.startsWith("f:") ? RefitData.WING : RefitData.WEAPON, key.substring(2), n);
    }

    /** How many of an item the cargo holds. */
    static int inCargo(CargoAPI cargo, String key) {
        if (cargo == null) return 0;
        String id = key.substring(2);
        try {
            return key.startsWith("f:") ? cargo.getNumFighters(id) : cargo.getNumWeapons(id);
        } catch (Throwable t) {
            return 0;
        }
    }
}
