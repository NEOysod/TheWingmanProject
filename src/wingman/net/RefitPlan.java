package wingman.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Friend refit: what a request (RefitRequest) comes to against the ship and the host's means as described
 * (RefitData): ordnance points, credits paid and received, story points, S-mods; and everything wrong with it.
 * Worked out the same way on the joiner (to show while choosing) and on the host (which also checks what only the
 * game can: slots, the real ordnance cost; see wingman.FriendRefit). No game classes.
 *
 * Items taken out go where the request says before any go in, so a weapon moved out to cargo can be fitted
 * elsewhere, and one sold can be bought back.
 */
public final class RefitPlan {
    /** Names for messages; ids as they are when nothing better is known. */
    public interface Names {
        String item(String key);
        String mod(String id);
        String source(String id);
    }

    public static final Names IDS = new Names() {
        public String item(String key) { return key.length() > 2 ? key.substring(2) : key; }
        public String mod(String id) { return id; }
        public String source(String id) { return id; }
    };

    public final RefitData d;
    public final RefitRequest r;
    public int opUsed, buy, sell, storyPoints, sMods;
    public final List<String> problems = new ArrayList<String>();
    /**
     * The slots and bays ("w" or "f" and the place) whose new item, or the place the old one goes, the host can't
     * provide where it is now (not in that cargo, storage or market, or that market isn't here): the joiner is
     * shown these to pick something else.
     */
    public final Set<String> unavailable = new java.util.LinkedHashSet<String>();
    /** What is wrong with those, in words (also in problems). */
    public final List<String> unavailableWhy = new ArrayList<String>();
    /** Problems with the ordnance points alone (the host replaces them with the game's own count). */
    public final List<String> opProblems = new ArrayList<String>();
    private final Names names;

    public RefitPlan(RefitData d, RefitRequest r) { this(d, r, IDS); }

    public RefitPlan(RefitData d, RefitRequest r, Names names) {
        this.d = d;
        this.r = r;
        this.names = names == null ? IDS : names;
        work();
    }

    /** Credits the refit costs in all (negative: the host receives more than it pays). */
    public int net() { return buy - sell; }

    public boolean isEmpty() {
        if (r.vents != d.vents || r.caps != d.caps || !r.addMods.isEmpty() || !r.removeMods.isEmpty() || !r.buildIn.isEmpty()) return false;
        for (RefitRequest.Change c : r.changes) if (!sameAsBefore(c)) return false;
        return true;
    }

    /** The key of what is in that slot or bay now, or null if it is empty. */
    public String before(RefitRequest.Change c) {
        if (c.wing) {
            int bay = bay(c);
            if (bay < 0 || bay >= d.wings.size() || d.wings.get(bay).isEmpty()) return null;
            return RefitData.key(RefitData.WING, d.wings.get(bay));
        }
        String w = d.weaponIn(c.place);
        return w == null ? null : RefitData.key(RefitData.WEAPON, w);
    }

    public String after(RefitRequest.Change c) {
        return c.item.isEmpty() ? null : RefitData.key(c.wing ? RefitData.WING : RefitData.WEAPON, c.item);
    }

    public boolean sameAsBefore(RefitRequest.Change c) {
        String b = before(c), a = after(c);
        return b == null ? a == null : b.equals(a);
    }

    private static int bay(RefitRequest.Change c) {
        try { return Integer.parseInt(c.place); } catch (NumberFormatException e) { return -1; }
    }

    private int cost(String key) { Integer c = d.opCosts.get(key); return c == null ? 0 : c; }
    private int modCost(String id) { Integer c = d.modCosts.get(id); return c == null ? 0 : c; }

    private boolean isSource(String id) { return RefitRequest.CARGO.equals(id) || RefitRequest.STORAGE.equals(id) || d.market(id) != null; }

    private void work() {
        int op = 0;
        // Items each source must give (source|key -> count), and what the request puts into each first.
        Map<String, Integer> need = new LinkedHashMap<String, Integer>(), put = new LinkedHashMap<String, Integer>();
        Set<String> places = new HashSet<String>();
        Map<String, List<String>> takers = new LinkedHashMap<String, List<String>>(); // source|key -> the places taking it
        for (RefitRequest.Change c : r.changes) {
            if (!places.add((c.wing ? "f" : "w") + c.place)) { problems.add("A slot is changed twice."); continue; }
            if (c.wing && (bay(c) < 0 || bay(c) >= d.wings.size())) { problems.add("There is no fighter bay " + (bay(c) + 1) + "."); continue; }
            if (sameAsBefore(c)) continue;
            String old = before(c), now = after(c);
            String place = (c.wing ? "f" : "w") + c.place;
            if (old != null) {
                op -= cost(old);
                String to = c.to.isEmpty() ? RefitRequest.CARGO : c.to;
                RefitData.Market m = d.market(to);
                if (RefitRequest.STORAGE.equals(to) && !d.hasStorage) missing(place, "The host has no storage here for " + names.item(old) + ".");
                else if (m == null && !RefitRequest.CARGO.equals(to) && !RefitRequest.STORAGE.equals(to)) missing(place, names.item(old) + " was to be sold at a market that isn't here: choose where it goes.");
                else if (m != null && !m.usable) missing(place, names.item(old) + " can't be sold at " + m.name + ": " + m.reason);
                else if (m != null && m.cantSell.contains(old)) missing(place, m.name + " won't take " + names.item(old) + ".");
                else if (m != null) sell += d.sellPrice(m, old);
                add(put, to + "|" + old, 1);
            }
            if (now != null) {
                op += cost(now);
                if (!isSource(c.from)) { missing(place, names.item(now) + " isn't where the host is now (it came from " + names.source(c.from) + ")."); continue; }
                add(need, c.from + "|" + now, 1);
                List<String> t = takers.get(c.from + "|" + now);
                if (t == null) takers.put(c.from + "|" + now, t = new ArrayList<String>());
                t.add(place);
                RefitData.Market m = d.market(c.from);
                if (m != null) buy += d.buyPrice(m, now);
            }
        }
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int bar = e.getKey().indexOf('|');
            String from = e.getKey().substring(0, bar), key = e.getKey().substring(bar + 1);
            RefitData.Market m = d.market(from);
            int have = RefitRequest.CARGO.equals(from) ? RefitData.count(d.cargo, key)
                    : RefitRequest.STORAGE.equals(from) ? RefitData.count(d.storage, key) : RefitData.count(m.stock, key);
            Integer back = put.get(e.getKey());
            have += back == null ? 0 : back;
            String where = RefitRequest.CARGO.equals(from) ? "the host's cargo" : RefitRequest.STORAGE.equals(from) ? "storage" : m.name;
            boolean bad = true;
            if (m != null && !m.usable) problems.add(m.name + " is shut to the host: " + m.reason);
            else if (m != null && m.cantBuy.contains(key)) problems.add(m.name + " won't sell " + names.item(key) + ".");
            else if (RefitRequest.STORAGE.equals(from) && !d.hasStorage) problems.add("The host has no storage here.");
            else if (e.getValue() > have) problems.add("Not enough " + names.item(key) + " in " + where + ": " + e.getValue() + " wanted, " + have + " there.");
            else bad = false;
            if (bad && takers.containsKey(e.getKey())) { unavailable.addAll(takers.get(e.getKey())); unavailableWhy.add(problems.get(problems.size() - 1)); }
        }

        Set<String> fitted = new HashSet<String>(d.hullmods);
        for (String id : r.removeMods) {
            if (!fitted.remove(id)) { problems.add(names.mod(id) + " can't be taken off" + (d.sMods.contains(id) || d.permaMods.contains(id) ? " (it is permanent)." : ".")); continue; }
            op -= modCost(id);
        }
        for (String id : r.addMods) {
            if (!d.knownHullmods.contains(id)) problems.add("The host doesn't know " + names.mod(id) + ".");
            else if (d.hullmods.contains(id) && !r.removeMods.contains(id) || d.sMods.contains(id) || d.permaMods.contains(id)) problems.add(names.mod(id) + " is already on the ship.");
            else { fitted.add(id); op += modCost(id); }
        }
        for (String id : r.buildIn) {
            if (!fitted.contains(id)) problems.add(names.mod(id) + " must be fitted to be built in.");
            else if (d.noBuildIn.contains(id)) problems.add(names.mod(id) + " can't be built in.");
            else { op -= modCost(id); storyPoints++; } // an S-mod costs no ordnance points
        }
        if (new HashSet<String>(r.buildIn).size() != r.buildIn.size()) problems.add("A hull mod is built in twice.");

        if (r.vents < 0 || (d.maxVents > 0 && r.vents > d.maxVents)) problems.add("Flux vents: " + r.vents + ", at most " + d.maxVents + ".");
        if (r.caps < 0 || (d.maxCaps > 0 && r.caps > d.maxCaps)) problems.add("Flux capacitors: " + r.caps + ", at most " + d.maxCaps + ".");
        op += (r.vents - d.vents) + (r.caps - d.caps);
        opUsed = d.opUsed + op;
        if (opUsed > d.opMax) opProblems.add("Ordnance points: " + opUsed + " used, " + d.opMax + " available.");

        sMods = d.sMods.size() + storyPoints;
        if (storyPoints > 0 && sMods > d.maxSMods) problems.add("S-mods: " + sMods + ", at most " + d.maxSMods + " on this ship.");
        if (net() > d.credits) problems.add("Credits: " + net() + " needed, the host has " + Math.round(d.credits) + ".");
        if (storyPoints > d.storyPoints) problems.add("Story points: " + storyPoints + " needed, the host has " + d.storyPoints + ".");
    }

    private void missing(String place, String why) {
        problems.add(why);
        unavailable.add(place);
        unavailableWhy.add(why);
    }

    private static void add(Map<String, Integer> m, String k, int n) {
        Integer had = m.get(k);
        m.put(k, (had == null ? 0 : had) + n);
    }

    /** Everything wrong with it, the ordnance points included. */
    public List<String> allProblems() {
        List<String> l = new ArrayList<String>(problems);
        l.addAll(opProblems);
        return l;
    }
}
