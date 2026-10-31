package wingman.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.loading.WingRole;

import wingman.compat.ModCompat;
import wingman.net.RefitData;
import wingman.net.RefitNames;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;
import wingman.net.Roster;
import wingman.Panel;
import wingman.RefitIcons;
import wingman.Style;

/**
 * Friend refit, joiner side: the refit screen, drawn as the lobby's third page (LobbyScreen.REFIT) with the
 * lobby's own drawing helpers, made for the mouse. Left: the ship with the new loadout and a ring on each slot
 * (click one to choose it), the totals and anything that stops the refit. Middle: a tile for each weapon slot,
 * fighter bay, vents, capacitors and hull mod, with its picture. Right: for the marked tile, cards of what can go
 * in it (from the host's cargo, storage or a market, filtered by tabs), or buttons for what can be done with it.
 * Bottom: SEND TO HOST, START OVER, BACK. The mouse wheel scrolls the tiles and the cards. Keys still work.
 *
 * What it shows comes from the host (RefitData); the totals and problems are worked out as the host will
 * (RefitPlan), and the host checks everything again before asking its player. See docs/FRIEND_REFIT_DESIGN.md.
 */
public final class RefitScreen {
    private final LobbyScreen ui;

    RefitScreen(LobbyScreen ui) { this.ui = ui; }

    /** What key() and click() ask of the title plugin. */
    public static final int NOTHING = 0, CLOSE = 1, SEND = 2, EXOTICA = 3;

    // ---- the edit in progress ----
    private RefitRequest req;
    private RefitData data;
    private int rowAt = -1, optAt, rowScroll, cardScroll;
    private boolean typingNote, typingSearch;
    /** What the cards are narrowed to (words in the name), "" all. Cleared when another kind of tile is marked. */
    private String search = "";
    /** The source the cards are filtered to ("" all of them). */
    private String tab = "";
    /** The last word from the host on a request, and the color to show it in. */
    private String status = "";
    private Color statusColor = Panel.DIM;

    /** New data from the host: another ship picks up its saved plan (or starts afresh); the same keeps what was chosen. */
    void setData(RefitData d) {
        if (d == null) { data = null; return; }
        if (req == null || !req.memberId.equals(d.memberId)) {
            saveDraft();
            RefitRequest saved = Drafts.get(d.memberId);
            if (saved != null) {
                reset(d);
                req = saved;
                status = "Your saved plan for this ship. Anything not available here is listed on the left.";
                statusColor = Panel.DIM;
            } else reset(d);
        }
        data = d;
    }

    /** Keep the plan for this ship (in memory and on disk) so it is there next time, after a restart too. */
    public void saveDraft() {
        if (req == null) return;
        Drafts.put(req, data != null && data.memberId.equals(req.memberId) && new RefitPlan(data, req).isEmpty() && req.note.isEmpty());
    }

    private long savedAt;

    private void reset(RefitData d) {
        req = new RefitRequest();
        req.memberId = d.memberId;
        req.vents = d.vents;
        req.caps = d.caps;
        rowAt = -1;
        optAt = 0;
        rowScroll = 0;
        cardScroll = 0;
        typingNote = false;
        tab = "";
    }

    void clear() {
        saveDraft();
        req = null;
        data = null;
        status = "";
        typingNote = false;
    }

    public void onResult(RefitResult r) {
        if (r.status == RefitResult.ACCEPTED) { // starts afresh on the new loadout, once the host has sent it
            statusColor = Panel.GOOD;
            if (req != null) Drafts.forget(req.memberId);
            req = null;
            data = null;
        } else statusColor = Panel.BAD;
        status = r.text;
    }

    /** The request to send, with the totals the joiner was shown. */
    public RefitRequest request() {
        RefitPlan p = plan();
        req.expectCredits = p.net();
        req.expectStoryPoints = p.storyPoints;
        status = "Sending...";
        statusColor = Panel.DIM;
        return req;
    }

    private RefitPlan plan() { return new RefitPlan(data, req, new RefitNames(data)); }

    // ---- rows (tiles): fitted items and what can be changed ----

    private static final int SLOT = 0, BAY = 1, MOD = 4, ADD_MOD = 5, NOTE = 6, HEAD = 7, BUILT_IN = 8, WEAPON_GROUPS = 9;

    private static final class Row {
        public final int kind;
        final String id;
        Row(int kind, String id) { this.kind = kind; this.id = id; }
    }

    private ShipHullSpecAPI hull() {
        try { return Global.getSettings().getHullSpec(data.hullId); } catch (Throwable t) { return null; }
    }

    private int slotSize(String place) {
        try { return WeaponAPI.WeaponSize.valueOf(data.slot(place).size).ordinal(); } catch (Throwable t) { return 0; }
    }

    private List<String> slots() { return slots(false); }

    private List<String> slots(boolean includeBuiltIns) {
        List<String> slots = new ArrayList<String>();
        for (RefitData.Slot s : data.slots) {
            if (s.place.indexOf('/') < 0 && (s.editable || (includeBuiltIns && s.builtIn))) slots.add(s.place);
        }
        Collections.sort(slots, new Comparator<String>() {
            public int compare(String a, String b) {
                int c = slotSize(b) - slotSize(a);
                return c != 0 ? c : a.compareTo(b);
            }
        });
        return slots;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row(HEAD, "WEAPONS"));
        for (String s : slots(true)) rows.add(new Row(builtInWeapon(s) == null ? SLOT : BUILT_IN, s));
        String module = null;
        for (String p : moduleWeaponSlots()) { // each module's weapons, under its name
            String owner = p.substring(0, p.indexOf('/'));
            if (!owner.equals(module)) rows.add(new Row(HEAD, moduleName(module = owner).toUpperCase()));
            rows.add(new Row(data.slot(p).builtIn ? BUILT_IN : SLOT, p));
        }
        if (data.hasGroups) { // a host on an older Wingman neither sends them nor takes them
            rows.add(new Row(HEAD, "WEAPON GROUPS"));
            rows.add(new Row(WEAPON_GROUPS, ""));
        }
        if (!data.wings.isEmpty()) {
            rows.add(new Row(HEAD, "FIGHTER BAYS"));
            for (int i = 0; i < data.wings.size(); i++) rows.add(new Row(BAY, String.valueOf(i)));
        }
        rows.add(new Row(HEAD, "HULL MODS"));
        // (hidden hull mods, the ones the game's refit screen doesn't show, aren't shown here either; but a fitted one
        // another mod uses as a loadout switch is, so it can be taken off)
        for (String id : data.sMods) if (!hiddenMod(id)) rows.add(new Row(MOD, id));
        for (String id : data.permaMods) if (!hiddenMod(id)) rows.add(new Row(MOD, id));
        for (String id : data.hullmods) if (!hiddenMod(id) || ModCompat.switchNote(id) != null) rows.add(new Row(MOD, id));
        for (String id : req.addMods) rows.add(new Row(MOD, id));
        rows.add(new Row(ADD_MOD, ""));
        rows.add(new Row(HEAD, "NOTE"));
        rows.add(new Row(NOTE, ""));
        return rows;
    }

    /** The weapon slots of the ship's modules that can be changed, as "module slot/slot", module by module, each biggest first. */
    private List<String> moduleWeaponSlots() {
        List<String> out = new ArrayList<String>();
        for (String module : data.moduleSlots) {
            final String prefix = module + "/";
            List<String> mine = new ArrayList<String>();
            for (RefitData.Slot s : data.slots) if (s.place.startsWith(prefix) && (s.editable || s.builtIn)) mine.add(s.place);
            Collections.sort(mine, new Comparator<String>() {
                public int compare(String a, String b) {
                    int c = slotSize(b) - slotSize(a);
                    return c != 0 ? c : a.compareTo(b);
                }
            });
            out.addAll(mine);
        }
        return out;
    }

    /** Where each module (by its place in data.moduleSlots) is in the roster's list of modules, which leaves out any it couldn't draw; -1 if not there. */
    private int[] moduleParts(List<String> modules) {
        int[] at = new int[data.moduleSlots.size()];
        int j = 0;
        for (int k = 0; k < at.length; k++) {
            at[k] = -1;
            String hullId = k < data.moduleHulls.size() ? data.moduleHulls.get(k) : null;
            for (int i = j; hullId != null && i < modules.size(); i++) {
                if (modules.get(i).startsWith(hullId + "|")) { at[k] = i; j = i + 1; break; }
            }
        }
        return at;
    }

    private String moduleName(String slot) {
        int i = data.moduleSlots.indexOf(slot);
        try { return Global.getSettings().getHullSpec(data.moduleHulls.get(i)).getHullName(); } catch (Throwable t) { return slot; }
    }

    private int builtInWings() {
        try { ShipHullSpecAPI h = hull(); return h == null ? 0 : h.getBuiltInWings().size(); } catch (Throwable t) { return 0; }
    }

    private String builtInWeapon(String slot) {
        RefitData.Slot s = data.slot(slot);
        return s == null || !s.builtIn ? null : s.builtInWeapon;
    }

    /** The marked row, the first slot if none is yet. */
    private Row marked(List<Row> rows) {
        if (rowAt < 0 || rowAt >= rows.size() || rows.get(rowAt).kind == HEAD) {
            rowAt = -1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind != HEAD) { rowAt = i; break; }
        }
        return rowAt < 0 ? null : rows.get(rowAt);
    }

    private void mark(List<Row> rows, int i) {
        if (i < 0 || i >= rows.size() || rows.get(i).kind == HEAD) return;
        if (i != rowAt) {
            optAt = 0; cardScroll = 0; tab = "";
            int before = rowAt >= 0 && rowAt < rows.size() ? rows.get(rowAt).kind : -1;
            if (before != rows.get(i).kind) { search = ""; typingSearch = false; } // weapons, wings and hull mods each search afresh
        }
        rowAt = i;
        typingNote = rows.get(i).kind == NOTE;
    }

    // ---- weapon groups ----

    /** The groups being edited: on the first edit, the ship's as they will be, padded to the game's seven. */
    private List<RefitData.Group> editGroups() {
        if (req.groups == null) {
            List<RefitData.Group> mine = new ArrayList<RefitData.Group>();
            for (RefitData.Group g : plan().groupsAfter()) mine.add(g.copy()); // before req.groups is set: the plan reads it
            req.groups = mine;
        }
        while (req.groups.size() < RefitPlan.MAX_GROUPS) req.groups.add(new RefitData.Group());
        return req.groups;
    }

    /** The groups shown: those being edited, else the ship's as they will be. */
    private List<RefitData.Group> shownGroups() { return req.groups != null ? req.groups : plan().groupsAfter(); }

    /** The weapon slots that will hold a weapon, in the weapon list's order. */
    private List<String> groupSlots() {
        java.util.Set<String> armed = plan().armedSlots();
        List<String> out = new ArrayList<String>();
        for (String s : slots(true)) if (armed.remove(s) || builtInWeapon(s) != null) out.add(s);
        out.addAll(armed); // grouped slots the hull list leaves out
        return out;
    }

    /** The weapon a slot will hold, or null. */
    private String weaponAfter(String slot) {
        String b = builtInWeapon(slot);
        if (b != null) return b;
        RefitRequest.Change c = req.change(slot, false);
        if (c != null) return c.item.isEmpty() ? null : c.item;
        return data.weaponIn(slot);
    }

    /** A weapon into a group (out of any other); clicked in its own group, out of all. */
    private void assignGroup(String slot, int group) {
        List<RefitData.Group> groups = editGroups();
        if (group < 0 || group >= groups.size()) return;
        boolean was = groups.get(group).slots.contains(slot);
        for (RefitData.Group g : groups) g.slots.remove(slot);
        if (!was) groups.get(group).slots.add(slot);
    }

    private void toggleGroup(int group, boolean mode) {
        List<RefitData.Group> groups = editGroups();
        if (group < 0 || group >= groups.size()) return;
        RefitData.Group g = groups.get(group);
        if (mode) g.alternating = !g.alternating;
        else g.autofire = !g.autofire;
    }

    // ---- options (cards): what can go in the marked row ----

    private static final class Option {
        public final String key, from;
        final int count;
        final float price;
        final boolean usable;
        /** From the whole game, not where the host is (shown with "Whole game" ticked): to look at, not to pick. */
        boolean elsewhere;
        String name;
        Option(String key, String from, int count, float price, boolean usable) { this.key = key; this.from = from; this.count = count; this.price = price; this.usable = usable; }
    }

    /**
     * "Available" ticked (the default): the cards show only what the host can get. Unticked: everything in the game
     * that fits, with what the host can't get greyed as "Unavailable".
     */
    private boolean availableOnly = true;
    /** How many cards are shown: 20 at first, 20 more each time the list is scrolled to its end or "more" is clicked. */
    private int shownMax = PAGE;
    private static final int PAGE = 20;
    /** The filtered cards, worked out again only when what they depend on changes (the whole game is a long list). */
    private List<Option> cached;
    private String cachedFor = "";
    private RefitData cachedData;

    private List<Option> options(Row row, RefitNames names, boolean filtered) {
        if (!filtered || row == null) return build(row, names, filtered);
        String key = row.kind + "|" + row.id + "|" + search + "|" + tab + "|" + availableOnly + "|" + req.addMods + "|" + req.changes.size() + "|" + filterKey(row);
        if (cached == null || cachedData != data || !key.equals(cachedFor)) {
            if (!key.equals(cachedFor)) shownMax = PAGE;
            cached = build(row, names, true);
            cachedFor = key;
            cachedData = data;
        }
        return cached;
    }

    private List<Option> build(Row row, RefitNames names, boolean filtered) {
        List<Option> out = new ArrayList<Option>();
        if (row == null) return out;
        if (row.kind == SLOT || row.kind == BAY) {
            if (row.kind == BAY && Integer.parseInt(row.id) < builtInWings()) return out;
            out.add(new Option(null, "", 0, 0f, true)); // leave it empty
            WeaponSlotAPI slot = null;
            if (row.kind == SLOT) { try { slot = slotOf(row.id); } catch (Throwable t) { /* no fit check */ } }
            List<Option> found = new ArrayList<Option>();
            addFrom(found, data.cargo, RefitRequest.CARGO, null, row, slot);
            if (data.hasStorage) addFrom(found, data.storage, RefitRequest.STORAGE, null, row, slot);
            for (RefitData.Market m : data.markets) addFrom(found, m.stock, m.id, m, row, slot);
            final RefitNames n = names;
            Collections.sort(found, new Comparator<Option>() {
                public int compare(Option a, Option b) {
                    if (a.usable != b.usable) return a.usable ? -1 : 1;
                    int c = n.item(a.key).compareToIgnoreCase(n.item(b.key));
                    return c != 0 ? c : Float.compare(a.price, b.price);
                }
            });
            for (Option o : found) if (!filtered || tab.isEmpty() || tab.equals(o.from)) out.add(o);
        } else if (row.kind == ADD_MOD) {
            final RefitNames n = names;
            List<String> ids = new ArrayList<String>();
            for (String id : data.knownHullmods) {
                if (data.hullmods.contains(id) || data.sMods.contains(id) || data.permaMods.contains(id) || req.addMods.contains(id)) continue;
                try {
                    HullModSpecAPI s = Global.getSettings().getHullModSpec(id);
                    if (s.isHidden() || s.isHiddenEverywhere()) continue;
                    if (hull() != null && hull().getBuiltInMods().contains(id)) continue;
                } catch (Throwable t) { continue; }
                ids.add(id);
            }
            Collections.sort(ids, new Comparator<String>() { public int compare(String a, String b) { return n.mod(a).compareToIgnoreCase(n.mod(b)); } });
            for (String id : ids) out.add(new Option(id, "", 0, 0f, true));
        }
        if (filtered && !availableOnly && tab.isEmpty()) addWholeGame(out, row, names); // (a source tab shows that source alone)
        if (filtered && filterable(row)) {
            List<Option> kept = new ArrayList<Option>();
            for (Option o : out) if (passes(o, row)) kept.add(o);
            out = kept;
        }
        if (filtered && !search.trim().isEmpty()) {
            List<Option> kept = new ArrayList<Option>();
            for (Option o : out) if (o.key == null || matches(o, row, names)) kept.add(o);
            return kept;
        }
        return out;
    }

    /**
     * Everything in this game (the joiner's copy, the same mods as the host's) that could go in the row and isn't
     * already listed, by name, after what the host can get: weapons that fit the slot, fighter wings, hull mods. Specs
     * the game keeps out of its codex are left out.
     */
    private void addWholeGame(List<Option> out, Row row, RefitNames names) {
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (Option o : out) if (o.key != null) have.add(o.key);
        List<Option> more = new ArrayList<Option>();
        String hide = com.fs.starfarer.api.impl.campaign.ids.Tags.HIDE_IN_CODEX;
        try {
            if (row.kind == SLOT) {
                WeaponSlotAPI slot = null;
                try { slot = slotOf(row.id); } catch (Throwable t) { /* no fit check */ }
                for (WeaponSpecAPI w : Global.getSettings().getAllWeaponSpecs()) {
                    String k = RefitData.key(RefitData.WEAPON, w.getWeaponId());
                    if (have.contains(k) || w.hasTag(hide) || w.getAIHints().contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.SYSTEM)) continue;
                    if (slot != null && !slot.weaponFits(w)) continue;
                    Option o = new Option(k, "", 0, 0f, false);
                    o.elsewhere = true;
                    o.name = w.getWeaponName();
                    more.add(o);
                }
            } else if (row.kind == BAY) {
                for (com.fs.starfarer.api.loading.FighterWingSpecAPI f : Global.getSettings().getAllFighterWingSpecs()) {
                    String k = RefitData.key(RefitData.WING, f.getId());
                    if (have.contains(k) || f.hasTag(hide)) continue;
                    Option o = new Option(k, "", 0, 0f, false);
                    o.elsewhere = true;
                    o.name = f.getWingName();
                    more.add(o);
                }
            } else if (row.kind == ADD_MOD) {
                for (HullModSpecAPI m : Global.getSettings().getAllHullModSpecs()) {
                    String id = m.getId();
                    if (have.contains(id) || m.isHidden() || m.isHiddenEverywhere() || m.hasTag(hide) || m.hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.HULLMOD_DMOD)) continue;
                    if (data.hullmods.contains(id) || data.sMods.contains(id) || data.permaMods.contains(id)) continue;
                    if (hull() != null && hull().getBuiltInMods().contains(id)) continue;
                    Option o = new Option(id, "", 0, 0f, false);
                    o.elsewhere = true;
                    o.name = m.getDisplayName();
                    more.add(o);
                }
            }
        } catch (Throwable t) { /* what was found so far */ }
        Collections.sort(more, new Comparator<Option>() { public int compare(Option a, Option b) { return String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)); } });
        out.addAll(more);
    }

    /** Every word of the search in the card's name (or its id), any case. */
    private boolean matches(Option o, Row row, RefitNames names) {
        String name = (o.name != null ? o.name : row.kind == ADD_MOD ? names.mod(o.key) : names.item(o.key)) + " " + o.key;
        name = name.toLowerCase();
        for (String w : search.toLowerCase().trim().split("\\s+")) if (!name.contains(w)) return false;
        return true;
    }

    private void addFrom(List<Option> out, List<RefitData.Item> items, String from, RefitData.Market m, Row row, WeaponSlotAPI slot) {
        for (RefitData.Item it : items) {
            if ((it.kind == RefitData.WING) != (row.kind == BAY) || it.count <= 0) continue;
            if (slot != null) {
                try {
                    WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(it.id);
                    if (!slot.weaponFits(spec)) continue;
                } catch (Throwable t) { continue; } // not a weapon this game knows
            }
            boolean usable = m == null || (m.usable && !m.cantBuy.contains(it.key()));
            out.add(new Option(it.key(), from, it.count, m == null ? 0f : data.buyPrice(m, it.key()), usable));
        }
    }

    /** The sources that have something for this row, for the tabs. */
    private List<String> sources(Row row, RefitNames names) {
        Set<String> s = new LinkedHashSet<String>();
        for (Option o : options(row, names, false)) if (o.key != null && !o.from.isEmpty()) s.add(o.from);
        return new ArrayList<String>(s);
    }

    // ---- filters over the weapon and wing cards: the game's own (mount or role, where it comes from) and those of
    //      Starficz's Refit Filters (damage type, projectile or beam, point defense, ammo, range) ----

    /**
     * By index: 0-2 mount (weapons) or role (wings), 3-5 where it comes from, 6-9 damage type, 10-13 kinds of weapon in
     * pairs, 14-16 ammo.
     */
    private static final String[] WEAPON_FILTERS = { "Ballistic", "Energy", "Missile", "Owned", "Buy legal", "Buy illegal",
            "Kinetic", "High explosive", "Energy", "Fragmentation", "Projectile", "Beam", "Point defense", "Not PD", "Reloading",
            "Unlimited", "Limited" };
    private static final String[] WING_FILTERS = { "Interceptors", "Fighters", "Bombers", "Owned", "Buy legal", "Buy illegal",
            "Kinetic", "High explosive", "Energy", "Fragmentation" };
    private static final int[][] GROUPS = { { 0, 1, 2 }, { 3, 4, 5 }, { 6, 7, 8, 9 }, { 10, 11 }, { 12, 13 }, { 14, 15, 16 } };
    /** The sliders, range and ordnance points: low end, high end, step. A handle at its end means no limit that way. */
    private static final String[] SLIDER_NAMES = { "RANGE", "ORDNANCE POINTS" };
    private static final float[][] WEAPON_SLIDERS = { { 0f, 1500f, 100f }, { 0f, 40f, 1f } }, WING_SLIDERS = { { 0f, 6000f, 500f }, { 0f, 40f, 1f } };
    private static final int RANGE = 0, OP = 1;
    private static final String BLACK_MARKET = "black_market"; // Submarkets.SUBMARKET_BLACK
    private final boolean[] weaponFilter = allOn(WEAPON_FILTERS.length), wingFilter = allOn(WING_FILTERS.length);
    private final float[][] weaponSlider = ends(WEAPON_SLIDERS), wingSlider = ends(WING_SLIDERS);
    /** "Fits within free OP" (weapons, wings): only what fits in the ordnance points left, the OP slider set aside. */
    private final boolean[] fitsFree = new boolean[2];
    /** The free ordnance points for this mount or bay (what's in it now counts as free), as last worked out. */
    private int freeOp;
    private boolean filterOpen, overFilters;
    /** The pop-up, its button and the sliders' tracks, as last drawn. */
    private float[] filterArea, filterButton;
    private final float[][] tracks = new float[2][];
    private int dragging = -1, dragSlider; // the handle held (0 low, 1 high) and on which slider

    private static float[][] ends(float[][] sliders) {
        float[][] out = new float[sliders.length][];
        for (int s = 0; s < sliders.length; s++) out[s] = new float[] { sliders[s][0], sliders[s][1] };
        return out;
    }

    private static boolean[] allOn(int n) {
        boolean[] b = new boolean[n];
        java.util.Arrays.fill(b, true);
        return b;
    }

    /** Weapon slots, and the bays that aren't built in, have filters. */
    private boolean filterable(Row row) {
        return row != null && (row.kind == SLOT || row.kind == BAY && Integer.parseInt(row.id) >= builtInWings());
    }

    private boolean[] filters(Row row) { return row.kind == BAY ? wingFilter : weaponFilter; }
    private float[] slider(Row row, int s) { return (row.kind == BAY ? wingSlider : weaponSlider)[s]; }
    private static float[] sliderEnds(Row row, int s) { return (row.kind == BAY ? WING_SLIDERS : WEAPON_SLIDERS)[s]; }
    private boolean fits(Row row) { return fitsFree[row.kind == BAY ? 1 : 0]; }
    private boolean moved(Row row, int s) {
        float[] r = slider(row, s), ends = sliderEnds(row, s);
        return r[0] > ends[0] || r[1] < ends[1];
    }

    /** The ordnance points free for this mount or bay: the ship's left over, plus what's in it now (it would come out). */
    private int freeOp(Row row) {
        boolean wing = row.kind == BAY;
        RefitRequest.Change ch = req.change(row.id, wing);
        String now = ch != null ? ch.item : wing ? (Integer.parseInt(row.id) < data.wings.size() ? data.wings.get(Integer.parseInt(row.id)) : null) : data.weaponIn(row.id);
        Integer cost = now == null || now.isEmpty() ? null : data.opCosts.get(RefitData.key(wing ? RefitData.WING : RefitData.WEAPON, now));
        return data.opMax - plan().opUsed + (cost == null ? 0 : cost);
    }

    /** Whether filter i means anything here: as in the game, a mount type the slot can't take is greyed out. */
    private boolean applies(Row row, int i) {
        if (row.kind == BAY || i > 2) return true;
        WeaponAPI.WeaponType t;
        try { t = slotOf(row.id).getWeaponType(); } catch (Throwable e) { return true; }
        switch (t) {
            case BALLISTIC: return i == 0;
            case ENERGY: return i == 1;
            case MISSILE: return i == 2;
            case HYBRID: return i != 2;
            case COMPOSITE: return i != 1;
            case SYNERGY: return i != 0;
            default: return true;
        }
    }

    /** Filter i is on (a greyed one counts as off, as in the game; -1, nothing to filter on, always passes). */
    private boolean on(Row row, int i) { return i < 0 || filters(row)[i] && applies(row, i); }

    /** How many filters are off, each moved slider and "fits within free OP" counting as one: the number on the button. */
    private int filtersOff(Row row) {
        int n = 0;
        boolean[] f = filters(row);
        for (int i = 0; i < f.length; i++) if (!f[i] && applies(row, i)) n++;
        if (moved(row, RANGE)) n++;
        if (fits(row)) n++;
        else if (moved(row, OP)) n++;
        return n;
    }

    private String filterKey(Row row) {
        if (!filterable(row)) return "";
        if (fits(row)) freeOp = freeOp(row);
        return java.util.Arrays.toString(filters(row)) + java.util.Arrays.toString(slider(row, RANGE)) + java.util.Arrays.toString(slider(row, OP))
                + (fits(row) ? "fits" + freeOp : "");
    }

    private static int damage(com.fs.starfarer.api.combat.DamageType t) {
        if (t == null) return -1;
        switch (t) {
            case KINETIC: return 6;
            case HIGH_EXPLOSIVE: return 7;
            case ENERGY: return 8;
            case FRAGMENTATION: return 9;
            default: return -1;
        }
    }

    private boolean inRange(float v, Row row, int s) {
        float[] r = slider(row, s), ends = sliderEnds(row, s);
        return !(r[0] > ends[0] && v < r[0]) && !(r[1] < ends[1] && v > r[1]);
    }

    /**
     * Whether a card gets past the filters. Wings go by role, by the damage of every gun they carry, and by their range.
     * What the host can't get ("Unavailable") has no source to filter on.
     */
    private boolean passes(Option o, Row row) {
        if (o.key == null) return true;
        if (!o.elsewhere) {
            boolean owned = RefitRequest.CARGO.equals(o.from) || RefitRequest.STORAGE.equals(o.from);
            if (!on(row, owned ? 3 : BLACK_MARKET.equals(o.from) ? 5 : 4)) return false;
        }
        Integer cost = data.opCosts.get(o.key); // unknown: shown
        if (cost != null && (fits(row) ? cost > freeOp : !inRange(cost, row, OP))) return false;
        String id = o.key.substring(2);
        try {
            if (row.kind == BAY) {
                FighterWingSpecAPI s = Global.getSettings().getFighterWingSpec(id);
                WingRole role = s.getRole();
                if (!on(row, role == WingRole.INTERCEPTOR ? 0 : role == WingRole.BOMBER ? 2 : 1)) return false;
                ShipVariantAPI v = s.getVariant();
                for (String slot : v.getFittedWeaponSlots()) {
                    WeaponSpecAPI w = v.getWeaponSpec(slot);
                    if (w != null && !on(row, damage(w.getDamageType()))) return false;
                }
                return inRange(s.getRange(), row, RANGE);
            }
            WeaponSpecAPI s = Global.getSettings().getWeaponSpec(id);
            boolean b = on(row, 0), e = on(row, 1), m = on(row, 2), mount;
            switch (s.getMountType()) {
                case BALLISTIC: mount = b; break;
                case ENERGY: mount = e; break;
                case MISSILE: mount = m; break;
                case SYNERGY: mount = e || m; break;
                case COMPOSITE: mount = b || m; break;
                case HYBRID: mount = b || e; break;
                case UNIVERSAL: mount = b || e || m; break;
                default: mount = true;
            }
            if (!mount || !on(row, damage(s.getDamageType())) || !on(row, s.isBeam() ? 11 : 10)) return false;
            boolean pd = s.getAIHints().contains(WeaponAPI.AIHints.PD) || s.getAIHints().contains(WeaponAPI.AIHints.PD_ALSO);
            return on(row, pd ? 12 : 13) && on(row, ammo(s)) && inRange(s.getMaxRange(), row, RANGE);
        } catch (Throwable t) {
            return true; // a spec this game doesn't know: shown
        }
    }

    /** The ammo filter a weapon falls under: no ammo (unlimited), ammo that comes back over time (reloading), or neither. */
    private static int ammo(WeaponSpecAPI s) {
        if (!s.usesAmmo()) return 15;
        return s.getAmmoPerSecond() > 0f ? 14 : 16;
    }

    /**
     * A click in the filters pop-up: filter i, -1 everything back on, -2 - s slider s's track (the nearer handle jumps
     * there and follows the mouse while the button is held), -4 "fits within free OP". The game's own filters are tick
     * boxes; Refit Filters' groups go its way: a click keeps only that one, Shift or Ctrl+click adds or takes it away,
     * and clicking the only one on turns the whole group back on.
     */
    private void clickFilter(Row row, int i, float mx) {
        optAt = 0;
        cardScroll = 0;
        boolean[] f = filters(row);
        if (i == -1) {
            java.util.Arrays.fill(f, true);
            for (int s = 0; s < SLIDER_NAMES.length; s++) System.arraycopy(sliderEnds(row, s), 0, slider(row, s), 0, 2);
            fitsFree[row.kind == BAY ? 1 : 0] = false;
        } else if (i == -4) {
            fitsFree[row.kind == BAY ? 1 : 0] = !fits(row);
        } else if (i <= -2) {
            dragSlider = -2 - i;
            float[] r = slider(row, dragSlider);
            float v = sliderAt(row, mx);
            dragging = v < r[0] ? 0 : v > r[1] ? 1 : v - r[0] <= r[1] - v ? 0 : 1;
            drag(row, mx);
        } else if (i < 6) {
            f[i] = !f[i];
        } else {
            int[] group = GROUPS[i < 10 ? 2 : i < 12 ? 3 : i < 14 ? 4 : 5];
            int lit = 0;
            for (int k : group) if (f[k]) lit++;
            boolean multi = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
            if (lit == 1 && f[i]) for (int k : group) f[k] = true;
            else if (multi) f[i] = !f[i];
            else for (int k : group) f[k] = k == i;
        }
    }

    /** The value under the mouse on the slider being dragged, to its step. */
    private float sliderAt(Row row, float mx) {
        float[] ends = sliderEnds(row, dragSlider), t = tracks[dragSlider];
        float at = Math.max(0f, Math.min(1f, (mx - t[0]) / t[2]));
        return Math.round(at * (ends[1] - ends[0]) / ends[2]) * ends[2] + ends[0];
    }

    private void drag(Row row, float mx) {
        float[] r = slider(row, dragSlider);
        float v = sliderAt(row, mx);
        if (dragging == 0) r[0] = Math.min(v, r[1]);
        else r[1] = Math.max(v, r[0]);
    }

    private static boolean in(float[] a, float mx, float my) {
        return a != null && mx >= a[0] && mx <= a[0] + a[2] && my >= a[1] && my <= a[1] + a[3];
    }

    // ---- changing things ----

    private RefitRequest.Change changeFor(Row row) {
        boolean wing = row.kind == BAY;
        RefitRequest.Change c = req.change(row.id, wing);
        if (c == null) {
            c = new RefitRequest.Change();
            c.wing = wing;
            c.place = row.id;
            String now = wing ? data.wings.get(Integer.parseInt(row.id)) : data.weaponIn(row.id);
            c.item = now == null ? "" : now;
            c.from = "";
            req.changes.add(c);
        }
        return c;
    }

    private void pick(Row row, Option o) {
        if (row.kind == BUILT_IN) return;
        if (o.elsewhere) {
            status = o.name + " is unavailable: the host can't get it here.";
            statusColor = Panel.WARN;
            return;
        }
        if (row.kind == ADD_MOD) {
            if (o.key != null && !req.addMods.contains(o.key)) req.addMods.add(o.key);
            return;
        }
        if (!o.usable) { status = "That one can't be bought there."; statusColor = Panel.WARN; return; }
        RefitRequest.Change c = changeFor(row);
        c.item = o.key == null ? "" : o.key.substring(2);
        c.from = o.key == null ? "" : o.from;
        tidy();
    }

    /** Changes that change nothing are dropped. */
    private void tidy() {
        RefitPlan p = plan();
        for (int i = req.changes.size() - 1; i >= 0; i--) if (p.sameAsBefore(req.changes.get(i))) req.changes.remove(i);
    }

    private void undo(Row row) {
        if (row == null) return;
        if (row.kind == SLOT || row.kind == BAY) {
            RefitRequest.Change c = req.change(row.id, row.kind == BAY);
            if (c != null) req.changes.remove(c);
        }
        else if (row.kind == MOD) { req.removeMods.remove(row.id); req.addMods.remove(row.id); req.buildIn.remove(row.id); }
        else if (row.kind == NOTE) req.note = "";
        else if (row.kind == WEAPON_GROUPS) req.groups = null;
    }

    private boolean changed(Row row) {
        if (row == null) return false;
        if (row.kind == SLOT || row.kind == BAY) return req.change(row.id, row.kind == BAY) != null;
        if (row.kind == MOD) return req.removeMods.contains(row.id) || req.addMods.contains(row.id) || req.buildIn.contains(row.id);
        if (row.kind == NOTE) return !req.note.isEmpty();
        if (row.kind == WEAPON_GROUPS) return plan().groupsChanged();
        return false;
    }

    /** Where the item taken out of a slot or bay goes next: cargo, storage, then each submarket that would buy it. */
    private void cycleDestination(Row row) {
        if (row == null || (row.kind != SLOT && row.kind != BAY)) return;
        RefitRequest.Change c = req.change(row.id, row.kind == BAY);
        String old = c == null ? null : plan().before(c);
        if (c == null || old == null) return;
        List<String> to = new ArrayList<String>();
        to.add(RefitRequest.CARGO);
        if (data.hasStorage) to.add(RefitRequest.STORAGE);
        for (RefitData.Market m : data.markets) if (m.usable && !m.cantSell.contains(old)) to.add(m.id);
        int i = to.indexOf(c.to);
        c.to = to.get((i + 1) % to.size());
    }

    private void toggleBuildIn(Row row) {
        if (row == null || row.kind != MOD || data.sMods.contains(row.id) || data.permaMods.contains(row.id)) return;
        if (req.buildIn.remove(row.id)) return;
        if (req.removeMods.contains(row.id)) return;
        req.buildIn.add(row.id);
    }

    private void toggleRemove(Row row) {
        if (row == null || row.kind != MOD) return;
        if (req.addMods.remove(row.id)) { req.buildIn.remove(row.id); return; }
        if (!data.hullmods.contains(row.id)) return; // S-mods, D-mods and built-ins stay
        if (!req.removeMods.remove(row.id)) { req.removeMods.add(row.id); req.buildIn.remove(row.id); }
    }

    /** The flux vents (else capacitors) up or down by that many; 0: back to what the ship has. */
    private void adjust(boolean vents, int by) {
        if (by == 0) { if (vents) req.vents = data.vents; else req.caps = data.caps; return; }
        if (vents) req.vents = Math.max(0, Math.min(data.maxVents > 0 ? data.maxVents : 999, req.vents + by));
        else req.caps = Math.max(0, Math.min(data.maxCaps > 0 ? data.maxCaps : 999, req.caps + by));
    }

    /** A loadout selector taken off with nothing else changed is that mod's free switch: it is sent at once. */
    private int switchNow() {
        return ModCompat.onlySwitchOffs(plan()) ? trySend() : NOTHING;
    }

    private boolean docked() { return data != null && !data.market.isEmpty(); }

    private int trySend() {
        RefitPlan p = plan();
        saveDraft();
        if (p.isEmpty()) { status = "Nothing is changed yet."; statusColor = Panel.WARN; return NOTHING; }
        if (!docked()) { status = "The host isn't docked at a market. Your plan is kept: send it when they are."; statusColor = Panel.WARN; return NOTHING; }
        if (!p.allProblems().isEmpty()) { status = "Fix what is in red first."; statusColor = Panel.BAD; return NOTHING; }
        return SEND;
    }

    // ---- keys (still there, though nothing needs them) ----

    public int key(int key, char ch, boolean ctrl, boolean shift) {
        if (data == null || req == null) return key == 1 ? CLOSE : NOTHING;
        if (filterOpen && key == 1) { filterOpen = false; return NOTHING; } // Esc: the filters first
        if (typingSearch) {
            if (key == 1) { if (search.isEmpty()) typingSearch = false; else search = ""; }   // Esc: clear, then stop
            else if (key == 28 || key == 156 || key == 15) typingSearch = false;            // Enter, Tab: done
            else if (key == 14) { if (!search.isEmpty()) search = search.substring(0, search.length() - 1); }
            else if (ch >= ' ' && ch < 127 && search.length() < 32) search += ch;
            optAt = 0;
            cardScroll = 0;
            return NOTHING;
        }
        if (ctrl && key == 33) { typingSearch = true; typingNote = false; return NOTHING; } // Ctrl+F: search the cards
        if (typingNote) {
            if (key == 1 || key == 28 || key == 156) typingNote = false;                      // Esc, Enter: done
            else if (key == 14) { if (!req.note.isEmpty()) req.note = req.note.substring(0, req.note.length() - 1); }
            else if (ch >= ' ' && ch < 127 && req.note.length() < RefitRequest.MAX_NOTE) req.note += ch;
            return NOTHING;
        }
        List<Row> rows = rows();
        Row row = marked(rows);
        if (ctrl && Panel.isEnter(key)) return trySend();
        if (key == 1) return CLOSE;
        if (key == 44) { if (shift) reset(data); else undo(row); return NOTHING; }      // Z: undo this row, Shift+Z: everything
        if (key == 31) { cycleDestination(row); return NOTHING; }                         // S: where the old one goes
        if (key == 48) { toggleBuildIn(row); return NOTHING; }                            // B: build in
        if (key == 211) { toggleRemove(row); return switchNow(); }                            // Delete: hull mod off (or back on)
        if (key == 200 || key == 208) {                                                   // Up/Down: the tiles
            int i = rowAt;
            do { i = (i + (key == 200 ? rows.size() - 1 : 1)) % rows.size(); } while (rows.get(i).kind == HEAD && i != rowAt);
            mark(rows, i);
            return NOTHING;
        }
        List<Option> opts = options(row, new RefitNames(data), true);
        if (opts.isEmpty()) return NOTHING;
        if (key == 203) optAt = (optAt + opts.size() - 1) % opts.size();                 // Left/Right: the cards
        else if (key == 205) optAt = (optAt + 1) % opts.size();
        else if (Panel.isEnter(key)) pick(row, opts.get(Math.max(0, Math.min(opts.size() - 1, optAt))));
        return NOTHING;
    }

    // ---- the mouse: what was drawn where, and the clicks on it ----

    private static final int H_ROW = 0, H_CARD = 1, H_TAB = 2, H_RING = 3, H_BUTTON = 4, H_FILTER = 5, H_GROUP = 6, H_GROUP_MODE = 7, H_GROUP_AUTO = 8, H_FLUX = 9;
    private static final int B_SEND = 0, B_RESET = 1, B_BACK = 2, B_DEST = 3, B_UNDO = 4,
            B_OFF = 9, B_BUILD = 10, B_NOTE = 11, B_CLEAR = 12, B_SEARCH = 13, B_SEARCH_CLEAR = 14, B_MORE = 15, B_WHOLE = 16, B_FILTERS = 17, B_EXOTICA = 18;
    /** Cards not shown yet, and how far the cards can scroll, as last drawn. */
    private int moreLeft, maxCardScroll;

    private static final class Hit {
        final float x, y, w, h;
        final int type, index;
        final String id;
        Hit(float x, float y, float w, float h, int type, int index, String id) { this.x = x; this.y = y; this.w = w; this.h = h; this.type = type; this.index = index; this.id = id; }
        boolean has(float mx, float my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }

    private final List<Hit> hits = new ArrayList<Hit>();
    private float[] tileArea, cardArea, groupArea;
    /** The first weapon shown in the group grid. */
    private int groupScroll;

    public int click(float mx, float my) {
        if (data == null || req == null) return NOTHING;
        if (filterOpen && !in(filterArea, mx, my) && !in(filterButton, mx, my)) {
            filterOpen = false; // a click away closes the filters (and does nothing else)
            return NOTHING;
        }
        boolean inPopup = filterOpen && in(filterArea, mx, my);
        List<Row> rows = rows();
        for (int i = hits.size() - 1; i >= 0; i--) { // the last drawn is on top
            Hit h = hits.get(i);
            if (!h.has(mx, my) || inPopup && h.type != H_FILTER) continue;
            sound();
            Row row = marked(rows);
            if (h.type == H_FILTER) { clickFilter(row, h.index, mx); return NOTHING; }
            switch (h.type) {
                case H_ROW: mark(rows, h.index); return NOTHING;
                case H_RING:
                    for (int k = 0; k < rows.size(); k++) if (rows.get(k).kind == SLOT && rows.get(k).id.equals(h.id)) { mark(rows, k); keepVisible(k); }
                    return NOTHING;
                case H_GROUP: assignGroup(h.id, h.index); return NOTHING;
                case H_GROUP_MODE: toggleGroup(h.index, true); return NOTHING;
                case H_GROUP_AUTO: toggleGroup(h.index, false); return NOTHING;
                case H_FLUX: adjust("v".equals(h.id), h.index); return NOTHING;
                case H_TAB: tab = h.id; optAt = 0; cardScroll = 0; return NOTHING;
                case H_CARD: {
                    List<Option> opts = options(row, new RefitNames(data), true);
                    if (h.index >= 0 && h.index < opts.size()) { optAt = h.index; pick(row, opts.get(h.index)); }
                    return NOTHING;
                }
                default:
                    switch (h.index) {
                        case B_SEND: return trySend();
                        case B_RESET: reset(data); return NOTHING;
                        case B_BACK: return CLOSE;
                        case B_EXOTICA: return EXOTICA;
                        case B_DEST: cycleDestination(row); return NOTHING;
                        case B_UNDO: undo(row); return NOTHING;
                        case B_OFF: toggleRemove(row); return switchNow();
                        case B_BUILD: toggleBuildIn(row); return NOTHING;
                        case B_NOTE: typingNote = true; typingSearch = false; return NOTHING;
                        case B_SEARCH: typingSearch = true; typingNote = false; return NOTHING;
                        case B_SEARCH_CLEAR: search = ""; typingSearch = false; optAt = 0; cardScroll = 0; return NOTHING;
                        case B_MORE: shownMax += PAGE; return NOTHING;
                        case B_WHOLE: availableOnly = !availableOnly; cardScroll = 0; optAt = 0; return NOTHING;
                        case B_FILTERS: filterOpen = !filterOpen; return NOTHING;
                        case B_CLEAR: req.note = ""; return NOTHING;
                        default: return NOTHING;
                    }
            }
        }
        typingNote = false; // a click anywhere else ends typing
        typingSearch = false;
        return NOTHING;
    }

    /** The mouse wheel over the tiles or the cards (dir > 0: up). */
    public boolean scroll(float mx, float my, int dir) {
        int by = dir > 0 ? -1 : 1;
        if (tileArea != null && mx >= tileArea[0] && mx <= tileArea[0] + tileArea[2] && my >= tileArea[1] && my <= tileArea[1] + tileArea[3]) { rowScroll = Math.max(0, rowScroll + by); return true; }
        if (groupArea != null && in(groupArea, mx, my)) { groupScroll = Math.max(0, groupScroll + by); return true; }
        if (cardArea != null && mx >= cardArea[0] && mx <= cardArea[0] + cardArea[2] && my >= cardArea[1] && my <= cardArea[1] + cardArea[3]) {
            if (by > 0 && cardScroll >= maxCardScroll && moreLeft > 0) shownMax += PAGE; // at the end: the next 20
            cardScroll = Math.max(0, cardScroll + by);
            return true;
        }
        return false;
    }

    private int tilesShown = 10;

    private void keepVisible(int row) {
        if (row < rowScroll) rowScroll = row;
        if (row >= rowScroll + tilesShown) rowScroll = row - tilesShown + 1;
    }

    private static void sound() {
        try { Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f); } catch (Throwable t) { /* silent is fine */ }
    }

    // ---- drawing ----

    private static final Color CYAN = Style.ACCENT, TILE = new Color(255, 255, 255, 10), TILE_OVER = new Color(120, 210, 255, 30),
            TILE_MARKED = new Color(120, 210, 255, 55), BAR = new Color(4, 7, 12, 220);

    void render(LobbyScreen.View v, float x, float top, float w, float h) {
        String body = ui.body;
        hits.clear();
        tileArea = null;
        overFilters = filterOpen && filterArea != null && Style.inside(filterArea[0], filterArea[1], filterArea[2], filterArea[3]);
        filterArea = null;
        filterButton = null;
        cardArea = null;
        setData(v.refitData);
        // The ship this screen was opened on, as the host names it: no pick of ours is involved.
        Roster.Member mem = null;
        if (v.roster != null && data != null) for (Roster.Member m : v.roster.members) if (m.id.equals(data.memberId)) { mem = m; break; }
        String title = "REFIT" + (mem == null ? "" : ":  " + Roster.label(mem).toUpperCase());
        String where = data == null ? "" : (data.market.isEmpty() ? "host not at a market" : "docked at " + data.market)
                + "     host has " + grouped(Math.round(data.credits)) + " credits, " + data.storyPoints + " story point" + (data.storyPoints == 1 ? "" : "s");
        float y = Style.window(x, top - h, w, h, title, where) - 14f;
        if (data == null || req == null || mem == null) {
            ui.text("Asking the host for your ship and what this market has...", body, Panel.DIM, x + 16f, y, w - 32f);
            button(x + w - 16f - 120f, top - h + 12f, 120f, 34f, "BACK", Panel.DIM, true, B_BACK);
            return;
        }
        RefitNames names = new RefitNames(data);
        RefitPlan plan = new RefitPlan(data, req, names);
        long now = System.currentTimeMillis();
        if (now - savedAt > 3000) { savedAt = now; saveDraft(); } // (written only when it changed)
        List<Row> rows = rows();
        Row row = marked(rows);
        if (!filterable(row)) filterOpen = false;
        float barH = 56f, floor = top - h + barH + 14f;
        float gap = 28f, inner = w - 40f - 2 * gap;
        float w1 = inner * 0.31f, w2 = inner * 0.31f, w3 = inner - w1 - w2;
        float c1 = x + 20f, c2 = c1 + w1 + gap, c3 = c2 + w2 + gap;
        // thin rules between the three columns
        LobbyScreen.fill(c2 - gap / 2f, floor, 1f, y - floor, Style.RULE);
        LobbyScreen.fill(c3 - gap / 2f, floor, 1f, y - floor, Style.RULE);
        if (!docked()) {
            y = Style.callout("The host isn't docked at a market. Plan your refit with what the host carries; it is kept, and you can send it once the host docks."
                    + " Items from a market are checked again there.", Panel.WARN, c1, y, w - 40f) - 12f;
        }
        ship(plan, mem, row, rows, c1, y, w1, floor);
        tiles(rows, plan, names, c2, Style.section("Loadout", c2, y, w2) - 4f, w2, floor);
        side(row, plan, names, c3, y, w3, floor);
        LobbyScreen.fill(x + 1f, top - h + barH, w - 2f, 1f, Style.RULE);
        bottomBar(plan, x + 20f, top - h + 12f, w - 40f, barH - 22f);
        if (filterOpen) filterPopup(row); // last: over everything
    }

    /** A clickable button; returns its right edge. */
    private float button(float x, float y, float w, float h, String text, Color c, boolean enabled, int code) {
        boolean over = enabled && Style.inside(x, y, w, h);
        Style.button(x, y, w, h, text, c, enabled, over);
        if (enabled) hits.add(new Hit(x, y, w, h, H_BUTTON, code, ""));
        return x + w;
    }

    /** A tick box and its label (the hit is the caller's). */
    private void tick(float x, float y, float w, float h, String text, boolean ticked, boolean enabled, boolean over) {
        float bx = x + 4f, by = y + (h - 14f) / 2f;
        LobbyScreen.fill(bx, by, 14f, 14f, ticked ? Style.alpha(CYAN, 70) : new Color(0, 0, 0, 90));
        LobbyScreen.outline(bx, by, 14f, 14f, !enabled ? Style.FAINT : ticked || over ? CYAN : LobbyScreen.LINE);
        if (ticked) ui.text("x", ui.body, enabled ? CYAN : Style.FAINT, bx + 3f, by + 14f + 1f, 12f);
        Color c = !enabled ? Style.FAINT : ticked || over ? CYAN : Style.DIM;
        Style.text(text, Style.SMALL_FONT, c, bx + 22f, y + (h + Style.lineHeight(Style.SMALL_FONT)) / 2f, w - 26f);
    }

    private static final String[] FILTER_HEADS = { "MOUNT", "SOURCE", "DAMAGE", "WEAPON", null, "AMMO" };

    /** The filters, popped up under their button: tick boxes by group, the sliders, "fits within free OP", "show all". */
    private void filterPopup(Row row) {
        if (filterButton == null || !filterable(row)) { filterOpen = false; return; }
        boolean wing = row.kind == BAY;
        String[] names = wing ? WING_FILTERS : WEAPON_FILTERS;
        boolean[] f = filters(row);
        int groups = wing ? 3 : 6;
        float pad = 12f, line = 24f, head = 20f, gap = 8f, pw = 380f;
        float ph = pad;
        for (int g = 0; g < groups; g++) {
            int cols = GROUPS[g].length == 3 ? 3 : 2;
            ph += (FILTER_HEADS[g] != null ? head : 0f) + (float) Math.ceil(GROUPS[g].length / (double) cols) * line + gap;
        }
        ph += 2f * (head + 14f + gap) + line + gap + 18f + 26f + pad; // two sliders, fits, hint, show all
        float px = filterButton[0] + filterButton[2] - pw, ptop = filterButton[1] - 4f;
        LobbyScreen.fill(px, ptop - ph, pw, ph, new Color(6, 10, 16, 250));
        LobbyScreen.outline(px, ptop - ph, pw, ph, CYAN);
        float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
        float y = ptop - pad, iw = pw - 2f * pad;
        for (int g = 0; g < groups; g++) {
            String h = FILTER_HEADS[g];
            if (g == 0 && wing) h = "ROLE";
            if (h != null) { Style.text(h, Style.SMALL_FONT, Style.FAINT, px + pad, y, iw); y -= head; }
            int[] group = GROUPS[g];
            int cols = group.length == 3 ? 3 : 2;
            float cw = iw / cols;
            for (int k = 0; k < group.length; k++) {
                int i = group[k];
                float cx = px + pad + (k % cols) * cw, cy = y - (k / cols + 1) * line;
                boolean ok = applies(row, i), over = ok && Style.inside(cx, cy, cw, line);
                tick(cx, cy, cw, line, names[i].toUpperCase(), f[i] && ok, ok, over);
                if (ok) hits.add(new Hit(cx, cy, cw, line, H_FILTER, i, ""));
            }
            y -= (float) Math.ceil(group.length / (double) cols) * line + gap;
        }
        if (dragging >= 0) {
            if (Mouse.isButtonDown(0) && tracks[dragSlider] != null) drag(row, mx);
            else dragging = -1;
        }
        y = drawSlider(row, RANGE, true, px + pad, y, iw);
        y = drawSlider(row, OP, !fits(row), px + pad, y, iw);
        boolean overFits = Style.inside(px + pad, y - line, iw, line);
        tick(px + pad, y - line, iw, line, "FITS WITHIN FREE OP (" + freeOp(row) + ")", fits(row), true, overFits);
        hits.add(new Hit(px + pad, y - line, iw, line, H_FILTER, -4, ""));
        y -= line + gap;
        Style.text("CLICK: ONLY THAT   SHIFT+CLICK: ADD/REMOVE", Style.SMALL_FONT, Style.FAINT, px + pad, y, iw);
        y -= 18f;
        float sx = px + pw - pad - 110f, sy = y - 26f;
        Style.button(sx, sy, 110f, 26f, "SHOW ALL", CYAN, true, Style.inside(sx, sy, 110f, 26f));
        hits.add(new Hit(sx, sy, 110f, 26f, H_FILTER, -1, ""));
        filterArea = new float[] { px, ptop - ph, pw, ph };
    }

    /** Slider s: its name and value, then the track with its two handles (greyed and dead when not enabled). Returns y below it. */
    private float drawSlider(Row row, int s, boolean enabled, float x, float y, float w) {
        float[] r = slider(row, s), ends = sliderEnds(row, s);
        boolean lo = r[0] > ends[0], hi = r[1] < ends[1], set = enabled && (lo || hi);
        String value = !enabled ? "FREE OP" : !lo && !hi ? "ANY" : !lo ? "UP TO " + (int) r[1] : !hi ? (int) r[0] + " AND UP"
                : (int) r[0] + " - " + (int) r[1];
        Style.text(SLIDER_NAMES[s], Style.SMALL_FONT, Style.FAINT, x, y, w);
        Style.textRight(value, Style.SMALL_FONT, set ? CYAN : Style.DIM, x + w, y);
        y -= 20f;
        float tx = x + 6f, tw = w - 12f, ty = y - 7f;
        tracks[s] = enabled ? new float[] { tx, ty, tw } : null;
        if (!enabled && dragSlider == s) dragging = -1;
        Color c = enabled ? CYAN : Style.FAINT;
        float a = tx + (r[0] - ends[0]) / (ends[1] - ends[0]) * tw, b = tx + (r[1] - ends[0]) / (ends[1] - ends[0]) * tw;
        LobbyScreen.fill(tx, ty - 1f, tw, 2f, LobbyScreen.LINE);
        LobbyScreen.fill(a, ty - 1f, b - a, 2f, c);
        LobbyScreen.fill(a - 5f, ty - 7f, 10f, 14f, c);
        LobbyScreen.fill(b - 5f, ty - 7f, 10f, 14f, c);
        if (enabled) hits.add(new Hit(x, ty - 9f, w, 18f, H_FILTER, -2 - s, ""));
        return y - 14f - 8f;
    }
    private static final Map<String, List<String>> WING_WEAPONS = new java.util.HashMap<>();

    /** A wing's ("f:" key) own guns as "w:" keys, in slot order, each once; decorative and system mounts left out. */
    static List<String> wingWeapons(String wingKey) {
        List<String> out = WING_WEAPONS.get(wingKey);
        if (out != null) return out;
        Set<String> keys = new LinkedHashSet<>();
        try {
            ShipVariantAPI v = Global.getSettings().getFighterWingSpec(wingKey.substring(2)).getVariant();
            for (String slot : v.getFittedWeaponSlots()) {
                String id = v.getWeaponId(slot);
                WeaponSpecAPI ws = id == null ? null : Global.getSettings().getWeaponSpec(id);
                if (ws == null) continue;
                WeaponAPI.WeaponType t = ws.getType();
                if (t == WeaponAPI.WeaponType.DECORATIVE || t == WeaponAPI.WeaponType.SYSTEM || t == WeaponAPI.WeaponType.LAUNCH_BAY) continue;
                keys.add(RefitData.key(RefitData.WEAPON, id));
            }
        } catch (Throwable t) {
            // unknown wing: no guns shown
        }
        out = new ArrayList<>(keys);
        WING_WEAPONS.put(wingKey, out);
        return out;
    }

    /**
     * Every weapon the ship will have, once per slot ("i|slot|weapon", i as in ShipPicture.draw): those fitted or being changed, then
     * what the roster has (built-ins and decorations included).
     */
    private List<String> previewFits(Roster.Member mem, List<String> slots) {
        List<String> fits = new ArrayList<String>();
        java.util.Set<String> drawn = new java.util.HashSet<String>();
        for (String s : slots) {
            RefitRequest.Change c = req.change(s, false);
            String wpn = c != null ? c.item : builtInWeapon(s) != null ? builtInWeapon(s) : data.weaponIn(s);
            drawn.add("-1|" + s);
            if (wpn != null && !wpn.isEmpty()) fits.add("-1|" + s + "|" + wpn);
        }
        for (RefitData.Slot s : data.slots) {
            if (s.place.indexOf('/') < 0 && s.builtIn && !s.builtInWeapon.isEmpty() && drawn.add("-1|" + s.place))
                fits.add("-1|" + s.place + "|" + s.builtInWeapon);
        }
        for (String s : data.fitSlots) if (s.indexOf('/') < 0 && drawn.add("-1|" + s)) fits.add("-1|" + s + "|" + data.weaponIn(s)); // built-ins and the like, drawn as they are
        int[] at = moduleParts(data.modulePictures);
        for (String p : moduleWeaponSlots()) {
            int k = data.moduleSlots.indexOf(p.substring(0, p.indexOf('/')));
            if (k < 0 || at[k] < 0) continue;
            RefitRequest.Change c = req.change(p, false);
            String wpn = c != null ? c.item : builtInWeapon(p) != null ? builtInWeapon(p) : data.weaponIn(p);
            drawn.add(at[k] + "|" + RefitData.local(p));
            if (wpn != null && !wpn.isEmpty()) fits.add(at[k] + "|" + RefitData.local(p) + "|" + wpn);
        }
        for (String f : mem.fits) {
            String[] a = f.split("\\|");
            if (a.length >= 3 && a[0].equals("-1") && drawn.add(a[0] + "|" + a[1])) fits.add(f);
        }
        return fits;
    }

    /** Buttons for the flux vents (else capacitors) asked for, under their limit bar; returns the y below them. */
    private float fluxControl(boolean vents, float x, float y, float w) {
        int n = vents ? req.vents : req.caps, max = vents ? data.maxVents : data.maxCaps, was = vents ? data.vents : data.caps;
        String id = vents ? "v" : "c";
        float h = 22f, bw = 34f, bx = x;
        bx = fluxButton(bx, y - h, bw, h, "-5", n > 0, -5, id) + 4f;
        bx = fluxButton(bx, y - h, bw, h, "-", n > 0, -1, id) + 4f;
        ui.text(n + " / " + (max > 0 ? String.valueOf(max) : "?"), ui.body, n != was ? Panel.GOOD : Panel.TEXT, bx, y - 3f, 70f);
        bx += 70f;
        bx = fluxButton(bx, y - h, bw, h, "+", max <= 0 || n < max, 1, id) + 4f;
        bx = fluxButton(bx, y - h, bw, h, "+5", max <= 0 || n < max, 5, id) + 8f;
        if (n != was) fluxButton(bx, y - h, 56f, h, "UNDO", true, 0, id);
        return y - h - 8f;
    }

    private float fluxButton(float x, float y, float w, float h, String text, boolean enabled, int by, String id) {
        boolean over = enabled && Style.inside(x, y, w, h);
        Style.button(x, y, w, h, text, by == 0 ? Panel.WARN : Panel.TEXT, enabled, over);
        if (enabled) hits.add(new Hit(x, y, w, h, H_FLUX, by, id));
        return x + w;
    }
    /**
     * The ship's flux figures from the hull's own numbers, the vents and capacitors asked for, and the weapons it will have. Skills
     * and hull mods that change them aren't counted.
     */
    private float fluxStats(List<String> fits, float x, float y, float w) {
        float weapons = 0f;
        for (String f : fits) {
            String[] a = f.split("\\|");
            if (a.length < 3) continue;
            try {
                WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(a[2]);
                WeaponAPI.WeaponType t = ws.getType();
                if (t == WeaponAPI.WeaponType.DECORATIVE || t == WeaponAPI.WeaponType.SYSTEM || t == WeaponAPI.WeaponType.LAUNCH_BAY) continue;
                weapons += ws.getDerivedStats().getSustainedFluxPerSecond();
            } catch (Throwable t) { /* not counted */ }
        }
        ShipHullSpecAPI hull = hull();
        if (hull == null) return y;
        float dissipation = hull.getFluxDissipation() + 10f * req.vents, capacity = hull.getFluxCapacity() + 200f * req.caps;
        y = Style.section("Flux", x, y, w) - 2f;
        y = pair("Weapons", Math.round(weapons) + " / sec", "sustained", weapons > dissipation ? Panel.BAD : Panel.TEXT, x, y, w) - 4f;
        y = pair("Dissipation", Math.round(dissipation) + " / sec", "", Panel.TEXT, x, y, w) - 4f;
        y = pair("Capacity", String.valueOf(Math.round(capacity)), "", Panel.TEXT, x, y, w) - 4f;
        ShipHullSpecAPI.ShieldSpecAPI sh = hull.getShieldSpec();
        if (sh == null || sh.getType() == com.fs.starfarer.api.combat.ShieldAPI.ShieldType.NONE) y = pair("Shield", "none", "", Panel.DIM, x, y, w) - 4f;
        else if (sh.getType() == com.fs.starfarer.api.combat.ShieldAPI.ShieldType.PHASE) y = pair("Phase cloak", Math.round(sh.getPhaseUpkeep()) + " / sec", "activation " + Math.round(sh.getPhaseCost()), Panel.TEXT, x, y, w) - 4f;
        else {
            y = pair("Shield arc", Math.round(sh.getArc()) + " degrees", "", Panel.TEXT, x, y, w) - 4f;
            y = pair("Shield flux/damage", String.format("%.2f", sh.getFluxPerDamageAbsorbed()), "", Panel.TEXT, x, y, w) - 4f;
            y = pair("Shield upkeep", Math.round(sh.getUpkeepCost()) + " / sec", "", Panel.TEXT, x, y, w) - 4f;
        }
        return y - 8f;
    }
    // ---- left: the ship with rings on its slots, the totals, what is wrong ----

    private void ship(RefitPlan plan, Roster.Member mem, Row row, List<Row> rows, float x, float top, float w, float floor) {
        String body = ui.body;
        top = Style.section("Ship", x, top, w) - 4f;
        float box = Math.min(w, Math.max(120f, (top - floor) * (docked() ? 0.42f : 0.34f)));
        float cx = x + w / 2f, cy = top - box / 2f;
        LobbyScreen.fill(x, top - box, w, box, Style.WELL);
        List<String> slots = slots();
        List<String> fits = new ArrayList<String>();
        try {
            fits = previewFits(mem, slots);
            ShipPicture.draw(data.hullId, mem.sprite, data.modulePictures, fits, cx, cy, box, data, moduleParts(data.modulePictures));
        } catch (Throwable t) { /* no picture */ }
        // A ring on each slot that can take a weapon: click to choose it.
        String hover = null;
        try {
            Map<String, float[]> at = ShipPicture.slotPoints(data.hullId, slots, data);
            String arcSlot = row != null && row.kind == SLOT && slots.contains(row.id) ? row.id : null;
            for (String s : slots) {
                float[] p = at.get(s);
                if (p == null) continue;
                WeaponSlotAPI ws = slotOf(s);
                float r = ws == null ? 7f : 6f + 3f * ws.getSlotSize().ordinal();
                if (Style.inside(p[0] - r - 3f, p[1] - r - 3f, 2 * r + 6f, 2 * r + 6f)) arcSlot = s;
            }
            float[] arcPoint = at.get(arcSlot);
            WeaponSlotAPI arcMount = arcSlot == null ? null : slotOf(arcSlot);
            if (arcPoint != null && arcMount != null) {
                RefitWeaponArc.draw(arcPoint[0], arcPoint[1], box * 0.42f, arcMount.getAngle(), arcMount.getArc(),
                        typeColor(arcMount), x, top - box, w, box, Global.getSettings().getScreenScaleMult());
            }
            for (String s : slots) {
                float[] p = at.get(s);
                if (p == null) continue;
                WeaponSlotAPI ws = slotOf(s);
                float r = ws == null ? 7f : 6f + 3f * ws.getSlotSize().ordinal();
                boolean marked = row != null && row.kind == SLOT && row.id.equals(s), changedSlot = req.change(s, false) != null;
                boolean over = Style.inside(p[0] - r - 3f, p[1] - r - 3f, 2 * r + 6f, 2 * r + 6f);
                Color c = marked ? Color.WHITE : changedSlot ? Panel.GOOD : typeColor(ws);
                ring(p[0], p[1], r, c, marked || over ? 2.5f : 1.5f, marked ? 60 : over ? 40 : 0);
                hits.add(new Hit(p[0] - r - 3f, p[1] - r - 3f, 2 * r + 6f, 2 * r + 6f, H_RING, 0, s));
                if (over) {
                    RefitRequest.Change c2 = req.change(s, false);
                    String wpn = c2 != null ? c2.item : data.weaponIn(s);
                    hover = slotKind(ws) + (ws == null ? "" : " (" + Math.round(ws.getArc()) + " degree arc)") + ":  "
                            + (wpn == null || wpn.isEmpty() ? "empty" : new RefitNames(data).item(RefitData.key(RefitData.WEAPON, wpn)));
                }
            }
        } catch (Throwable t) { /* the tiles alone */ }
        ui.systemHelp(data.hullId, data.systemId, x, top, w);
        float y = top - box - 6f;
        y = ui.text(hover != null ? hover : "Click a ring on the ship, or a tile, to change it.", body, hover != null ? Panel.TEXT : Panel.DIM, x, y, w) - 12f;
        y = fluxStats(fits, x, y, w);
        y = Style.section("Limits", x, y, w) - 2f;
        y = limit("Ordnance points", plan.opUsed, data.opMax, x, y, w) - 4f;
        y = limit("Flux vents", req.vents, data.maxVents, x, y, w) - 4f;
        y = fluxControl(true, x, y, w);
        y = limit("Flux capacitors", req.caps, data.maxCaps, x, y, w) - 4f;
        y = fluxControl(false, x, y, w);
        y = limit("S-mods", plan.sMods, data.maxSMods, x, y, w) - 12f;
        y = Style.section("Cost", x, y, w) - 2f;
        int net = plan.net();
        String credits = plan.buy == 0 && plan.sell == 0 ? "none" : (plan.buy > 0 ? "pay " + grouped(plan.buy) : "") + (plan.buy > 0 && plan.sell > 0 ? ",  " : "") + (plan.sell > 0 ? "receive " + grouped(plan.sell) : "");
        y = pair("Credits", credits, "host has " + grouped(Math.round(data.credits)), net > data.credits ? Panel.BAD : Panel.TEXT, x, y, w) - 4f;
        Style.meter(x, y - 3f, w, 3f, Math.max(0, net), Math.max(1f, data.credits));
        y -= 8f;
        y = pair("Story points", String.valueOf(plan.storyPoints), "host has " + data.storyPoints, plan.storyPoints > data.storyPoints ? Panel.BAD : Panel.TEXT, x, y, w) - 12f;
        // What the plan wants that isn't where the host is now: click one to choose something else for it.
        if (!plan.unavailable.isEmpty()) {
            y = Style.section(docked() ? "Not available at " + data.market : "Waits for a market", x, y, w) - 2f;
            for (String place : plan.unavailable) {
                if (y - 40f < floor) break;
                boolean wing = place.startsWith("f");
                String id = place.substring(1);
                RefitRequest.Change ch = req.change(id, wing);
                String what = ch == null ? id : ch.item.isEmpty() ? "Removed: " + names(plan, ch) : new RefitNames(data).item(RefitData.key(wing ? RefitData.WING : RefitData.WEAPON, ch.item)).replace(" LPC", "");
                String label = (wing ? "Bay " + (Integer.parseInt(id) + 1) : slotKind(slotOf(id))) + ":  " + what + (docked() ? "   (pick another)" : "");
                float top0 = y;
                y = Style.callout(label, docked() ? Panel.WARN : Style.ACCENT, x, y, w) - 3f;
                int k = rowIndex(rows, wing ? BAY : SLOT, id);
                if (k >= 0) hits.add(new Hit(x, y + 3f, w, top0 - y - 3f, H_ROW, k, ""));
            }
            y -= 8f;
        }
        List<String> problems = plan.allProblems();
        problems.removeAll(plan.unavailableWhy);
        if (!problems.isEmpty()) {
            y = Style.section("To fix before sending", x, y, w) - 2f;
            for (int i = 0; i < problems.size(); i++) {
                if (y - 40f < floor) { y = ui.text("and " + (problems.size() - i) + " more", body, Panel.BAD, x, y, w) - 1f; break; }
                y = Style.callout(problems.get(i), Panel.BAD, x, y, w) - 3f;
            }
        }
    }

    private String names(RefitPlan plan, RefitRequest.Change ch) {
        String old = plan.before(ch);
        return old == null ? "" : new RefitNames(data).item(old).replace(" LPC", "");
    }

    private WeaponSlotAPI slotOf(String id) {
        RefitData.Slot slot = data.slot(id);
        return slot == null ? null : new RefitSlot(slot);
    }

    /** A hull mod the game keeps out of sight (its refit screen doesn't list it). */
    static boolean hiddenMod(String id) {
        try {
            HullModSpecAPI s = Global.getSettings().getHullModSpec(id);
            return s.isHidden() || s.isHiddenEverywhere();
        } catch (Throwable t) {
            return false;
        }
    }

    /** A D-mod (damage the hull carries), by the hull mod's own tag. */
    static boolean isDmod(String id) {
        try { return Global.getSettings().getHullModSpec(id).hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.HULLMOD_DMOD); } catch (Throwable t) { return false; }
    }

    private static int rowIndex(List<Row> rows, int kind, String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind == kind && rows.get(i).id.equals(id)) return i;
        return -1;
    }

    private static Color typeColor(WeaponSlotAPI s) {
        if (s == null) return CYAN;
        switch (s.getWeaponType()) {
            case BALLISTIC: return new Color(255, 215, 80);
            case ENERGY: return new Color(110, 170, 255);
            case MISSILE: return new Color(130, 230, 110);
            case HYBRID: return new Color(255, 160, 60);
            case COMPOSITE: return new Color(200, 230, 90);
            case SYNERGY: return new Color(90, 230, 220);
            case UNIVERSAL: return new Color(230, 230, 230);
            default: return CYAN;
        }
    }

    private static void ring(float cx, float cy, float r, Color c, float width, int fillAlpha) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (fillAlpha > 0) {
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, fillAlpha / 255f);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glVertex2f(cx, cy);
            for (int i = 0; i <= 20; i++) { double a = Math.PI * 2 * i / 20; GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r); }
            GL11.glEnd();
        }
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.95f);
        GL11.glLineWidth(width);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 20; i++) { double a = Math.PI * 2 * i / 20; GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r); }
        GL11.glEnd();
        GL11.glLineWidth(1f);
        GL11.glPopAttrib();
    }

    /** A limit: its name, used / most on the right, and a bar under. */
    private float limit(String what, int used, int max, float x, float y, float w) {
        Color c = max > 0 && used > max ? Panel.BAD : Panel.TEXT;
        float bottom = ui.text(what, ui.body, Panel.DIM, x, y, w * 0.6f);
        Style.textRight(used + " / " + (max > 0 ? String.valueOf(max) : "?"), ui.body, c, x + w, y);
        Style.meter(x, bottom - 4f, w, 3f, used, max);
        return bottom - 6f;
    }

    /** A name, its value right after the label column, and a note on the right. */
    private float pair(String what, String value, String note, Color c, float x, float y, float w) {
        float bottom = ui.text(what, ui.body, Panel.DIM, x, y, w * 0.4f);
        ui.text(value, ui.body, c, x + w * 0.4f, y, w * 0.6f);
        Style.textRight(note, ui.body, Style.FAINT, x + w, y);
        return bottom;
    }

    // ---- middle: the tiles ----

    private void tiles(List<Row> rows, RefitPlan plan, RefitNames names, float x, float top, float w, float floor) {
        String body = ui.body;
        float tileH = 44f, headH = 26f;
        tileArea = new float[] { x, floor, w, top - floor };
        // How many rows fit from rowScroll on (headers are shorter).
        float avail = top - floor;
        rowScroll = Math.max(0, Math.min(rowScroll, rows.size() - 1));
        float used = 0f;
        int last = rowScroll;
        while (last < rows.size()) {
            float rh = rows.get(last).kind == HEAD ? headH : tileH + 4f;
            if (used + rh > avail) break;
            used += rh;
            last++;
        }
        tilesShown = Math.max(1, last - rowScroll);
        if (last >= rows.size() && rowScroll > 0) { // at the end: no empty space left below
            while (rowScroll > 0) {
                float rh = rows.get(rowScroll - 1).kind == HEAD ? headH : tileH + 4f;
                if (used + rh > avail) break;
                used += rh;
                rowScroll--;
            }
        }
        float y = top;
        for (int i = rowScroll; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.kind == HEAD) {
                if (y - headH < floor) break;
                if (i > rowScroll) Style.text(r.id, Style.SMALL_FONT, Style.alpha(CYAN, 200), x, y - 8f, w);
                else Style.text(r.id, Style.SMALL_FONT, Style.alpha(CYAN, 200), x, y - 2f, w);
                y -= headH;
                continue;
            }
            if (y - tileH < floor) break;
            float ty = y - tileH;
            boolean marked = i == rowAt, over = Style.inside(x, ty, w, tileH);
            LobbyScreen.fill(x, ty, w, tileH, marked ? TILE_MARKED : over ? TILE_OVER : TILE);
            boolean missing = (r.kind == SLOT || r.kind == BAY) && plan.unavailable.contains((r.kind == BAY ? "f" : "w") + r.id);
            if (marked) { LobbyScreen.outline(x, ty, w, tileH, CYAN); LobbyScreen.fill(x, ty, 3f, tileH, CYAN); }
            else if (missing) LobbyScreen.fill(x, ty, 3f, tileH, docked() ? Panel.WARN : CYAN);
            else if (changed(r)) LobbyScreen.fill(x, ty, 3f, tileH, Panel.GOOD);
            tile(r, plan, names, x, ty, w, tileH);
            hits.add(new Hit(x, ty, w, tileH, H_ROW, i, ""));
            y -= tileH + 4f;
        }
        if (rowScroll > 0) ui.text("more above (wheel)", body, Panel.DIM, x + w - 130f, top + 16f, 130f);
        if (last < rows.size()) ui.text("more below (wheel)", body, Panel.DIM, x + w - 130f, floor + 2f, 130f);
    }

    /** One tile: picture, name, and a line about it. */
    private void tile(Row r, RefitPlan plan, RefitNames names, float x, float y, float w, float h) {
        String body = ui.body;
        String name, detail = "", key = null;
        boolean mod = false;
        Color c = Panel.TEXT;
        switch (r.kind) {
            case BUILT_IN:
            case SLOT: {
                RefitRequest.Change ch = r.kind == BUILT_IN ? null : req.change(r.id, false);
                String now = r.kind == BUILT_IN ? builtInWeapon(r.id) : ch != null ? ch.item : data.weaponIn(r.id);
                key = now == null || now.isEmpty() ? null : RefitData.key(RefitData.WEAPON, now);
                name = key == null ? "Empty" : names.item(key);
                WeaponSlotAPI ws = null;
                try { ws = slotOf(r.id); } catch (Throwable t) { /* no kind */ }
                Integer op = key == null ? null : data.opCosts.get(key);
                detail = slotKind(ws) + (op == null ? "" : "   " + op + " OP");
                if (r.kind == BUILT_IN) { c = Panel.DIM; detail += "  (built in)"; }
                else if (ch != null) { c = Panel.GOOD; detail = changeNote(ch, plan, names); }
                else if (key == null) c = Panel.DIM;
                break;
            }
            case BAY: {
                int bay = Integer.parseInt(r.id);
                RefitRequest.Change ch = req.change(r.id, true);
                String now = ch != null ? ch.item : data.wings.get(bay);
                key = now.isEmpty() ? null : RefitData.key(RefitData.WING, now);
                name = key == null ? "Empty bay" : names.item(key).replace(" LPC", "");
                Integer op = key == null ? null : data.opCosts.get(key);
                detail = "Bay " + (bay + 1) + (bay < builtInWings() ? "  (built in)" : "") + (op == null ? "" : "   " + op + " OP");
                if (ch != null) { c = Panel.GOOD; detail = changeNote(ch, plan, names); }
                else if (key == null || bay < builtInWings()) c = Panel.DIM;
                break;
            }
            case MOD: {
                key = r.id;
                mod = true;
                name = names.mod(r.id);
                Integer cost = data.modCosts.get(r.id);
                if (data.sMods.contains(r.id)) { detail = "S-mod (built in)"; c = Panel.GOOD; }
                else if (data.permaMods.contains(r.id)) { boolean dm = isDmod(r.id); detail = dm ? "D-mod (permanent)" : "Permanent"; c = dm ? Panel.BAD : Panel.WARN; }
                else if (req.removeMods.contains(r.id)) { detail = "Taken off"; c = Panel.WARN; }
                else {
                    boolean added = req.addMods.contains(r.id), built = req.buildIn.contains(r.id);
                    detail = (added ? "Added" : "Fitted") + (built ? ", built in: S-mod, 1 story point" : cost == null ? "" : "   " + cost + " OP");
                    if (added || built) c = Panel.GOOD;
                }
                break;
            }
            case WEAPON_GROUPS: {
                int n = 0;
                for (RefitData.Group g : shownGroups()) if (!g.slots.isEmpty()) n++;
                name = "Weapon groups   " + n;
                detail = "Which weapons fire together";
                if (plan.groupsChanged()) { c = Panel.GOOD; detail = "Changed"; }
                break;
            }
            case ADD_MOD: name = "+  Add a hull mod"; detail = "From those the host knows"; c = CYAN; break;
            default: name = req.note.isEmpty() && !typingNote ? "Write a note to the host" : req.note + (typingNote ? "_" : ""); detail = "Click, then type"; c = req.note.isEmpty() ? Panel.DIM : Panel.TEXT;
        }
        if (key != null && Style.inside(x, y, w, h)) { // the pop-up with its numbers and description
            Integer op = mod ? data.modCosts.get(key) : data.opCosts.get(key);
            Tips.hover(key, mod, data.hullSize, op == null ? -1 : op);
        }
        float icon = h - 8f, tx = x + 4f, right = x + w - 6f;
        if (r.kind == BAY && key != null) { // the wing's own guns, small, at the row's right end
            float s = Math.round(icon * 0.6f), gy = y + (h - s) / 2f;
            for (String wk : wingWeapons(key)) {
                right -= s;
                RefitIcons.iconBox(RefitIcons.picture(wk, false), right, gy, s, false);
                if (Style.inside(right, gy, s, s)) Tips.hover(wk, false, data.hullSize, -1);
                right -= 4f;
            }
        }
        if (r.kind == SLOT || r.kind == BUILT_IN || r.kind == BAY || r.kind == MOD) {
            RefitIcons.iconBox(RefitIcons.picture(key, mod), x + 4f, y + 4f, icon, r.kind == MOD && req.removeMods.contains(r.id));
            tx = x + icon + 12f;
        } else tx = x + 10f;
        ui.text(name, body, c, tx, y + h - 5f, right - tx);
        ui.text(detail, body, Panel.DIM, tx, y + h / 2f - 1f, right - tx);
    }

    /** "was X, to cargo" for a changed slot or bay. */
    private String changeNote(RefitRequest.Change ch, RefitPlan plan, RefitNames names) {
        String old = plan.before(ch);
        String src = ch.item.isEmpty() ? "" : "from " + names.source(ch.from);
        if (old == null) return "new, " + src;
        String to = ch.to.isEmpty() ? RefitRequest.CARGO : ch.to;
        return "was " + names.item(old).replace(" LPC", "") + (data.market(to) != null ? ", sold at " : ", to ") + names.source(to) + (src.isEmpty() ? "" : "; " + src);
    }

    private static String slotKind(WeaponSlotAPI s) {
        if (s == null) return "slot";
        try {
            return s.getSlotSize().name().charAt(0) + s.getSlotSize().name().substring(1).toLowerCase() + " " + s.getWeaponType().name().toLowerCase().replace('_', ' ')
                    + (s.isHardpoint() ? " hardpoint" : " turret");
        } catch (Throwable t) {
            return "slot";
        }
    }

    // ---- right: cards to choose from, or buttons ----

    private void side(Row row, RefitPlan plan, RefitNames names, float x, float top, float w, float floor) {
        String body = ui.body;
        if (row == null) return;
        float y;
        switch (row.kind) {
            case BUILT_IN:
            case SLOT:
            case BAY: {
                boolean builtIn = row.kind == BUILT_IN || (row.kind == BAY && Integer.parseInt(row.id) < builtInWings());
                WeaponSlotAPI ws = null;
                if (row.kind != BAY) { try { ws = slotOf(row.id); } catch (Throwable t) { /* none */ } }
                y = Style.section(row.kind != BAY ? slotKind(ws) : "Fighter bay " + (Integer.parseInt(row.id) + 1), x, top, w) - 4f;
                if (builtIn) { ui.text(row.kind == BUILT_IN ? "This weapon is built into the hull." : "This bay's wing is built into the hull.", body, Panel.DIM, x, y, w); return; }
                RefitRequest.Change ch = req.change(row.id, row.kind == BAY);
                String old = ch == null ? null : plan.before(ch);
                float bx = x;
                if (old != null) {
                    String to = ch.to.isEmpty() ? RefitRequest.CARGO : ch.to;
                    bx = button(bx, y - 28f, Math.min(w * 0.68f, 300f), 28f, names.item(old).replace(" LPC", "") + " goes to:  " + names.source(to) + "  >", Panel.TEXT, true, B_DEST) + 8f;
                }
                if (ch != null) button(bx, y - 28f, 80f, 28f, "UNDO", Panel.WARN, true, B_UNDO);
                if (old != null || ch != null) y -= 38f;
                tabsAndCards(row, names, x, y, w, floor);
                return;
            }
            case WEAPON_GROUPS:
                groupGrid(names, x, top, w, floor);
                return;
            case ADD_MOD:
                y = Style.section("Add a hull mod", x, top, w) - 4f;
                tabsAndCards(row, names, x, y, w, floor);
                return;
            case MOD: {
                y = Style.section("Hull mod", x, top, w) - 4f;
                y = ui.text(names.mod(row.id), LobbyScreen.HEAD, CYAN, x, y, w) - 8f;
                RefitIcons.iconBox(RefitIcons.picture(row.id, true), x, y - 64f, 64f, false);
                String desc = "";
                try {
                    HullModSpecAPI s = Global.getSettings().getHullModSpec(row.id);
                    Integer cost = data.modCosts.get(row.id);
                    desc = (cost == null ? "" : cost + " ordnance points on this hull.") + (data.noBuildIn.contains(row.id) ? " Can't be built in." : "")
                            + (s.getManufacturer() != null && !s.getManufacturer().isEmpty() ? " " + s.getManufacturer() + "." : "");
                } catch (Throwable t) { /* the name alone */ }
                ui.text(desc, body, Panel.DIM, x + 74f, y, w - 74f);
                y -= 76f;
                if (data.sMods.contains(row.id)) { ui.text("Built in (an S-mod): it stays.", body, Panel.TEXT, x, y, w); return; }
                if (data.permaMods.contains(row.id)) {
                    Style.callout(isDmod(row.id) ? "A D-mod: it stays. Restoring the ship is done by the host." : "Permanent: it stays.", isDmod(row.id) ? Panel.BAD : Panel.WARN, x, y, w);
                    return;
                }
                boolean off = req.removeMods.contains(row.id), added = req.addMods.contains(row.id), built = req.buildIn.contains(row.id);
                float bx = button(x, y - 34f, 150f, 34f, added ? "REMOVE" : off ? "PUT BACK" : "TAKE OFF", off ? Panel.GOOD : Panel.WARN, true, B_OFF) + 8f;
                if (!data.noBuildIn.contains(row.id) && !off) bx = button(bx, y - 34f, 200f, 34f, built ? "DON'T BUILD IN" : "BUILD IN (1 SP)", built ? Panel.WARN : Panel.GOOD, true, B_BUILD) + 8f;
                if (changed(row) && !added) button(bx, y - 34f, 90f, 34f, "UNDO", Panel.WARN, true, B_UNDO);
                y -= 48f;
                String switchNote = ModCompat.switchNote(row.id);
                ui.text(switchNote != null ? switchNote + " It goes to the host at once; their game picks what comes next."
                        : "Building in makes it an S-mod: permanent, free of ordnance points, 1 story point. Whether a hull mod suits this ship is for the host to judge.",
                        body, Panel.DIM, x, y, w);
                return;
            }
            default: {
                y = Style.section("Note to the host", x, top, w) - 6f;
                boolean over = Style.inside(x, y - 60f, w, 60f);
                LobbyScreen.fill(x, y - 60f, w, 60f, typingNote ? TILE_MARKED : over ? TILE_OVER : TILE);
                LobbyScreen.outline(x, y - 60f, w, 60f, typingNote ? CYAN : LobbyScreen.LINE);
                ui.text(req.note.isEmpty() && !typingNote ? "Click here and type a short note." : req.note + (typingNote ? "_" : ""), body,
                        req.note.isEmpty() && !typingNote ? Panel.DIM : Panel.TEXT, x + 8f, y - 6f, w - 16f);
                hits.add(new Hit(x, y - 60f, w, 60f, H_BUTTON, B_NOTE, ""));
                y -= 70f;
                if (!req.note.isEmpty()) button(x, y - 30f, 90f, 30f, "CLEAR", Panel.WARN, true, B_CLEAR);
            }
        }
    }

    /**
     * The weapon groups as the game's refit screen shows them: a weapon to a row, a group to a column. A click in a
     * cell puts the weapon in that group (in its own group: in none, and the game gives it one). Each column's head
     * switches it between linked and alternating, and autofire on or off.
     */
    private void groupGrid(RefitNames names, float x, float top, float w, float floor) {
        String body = ui.body;
        groupArea = null;
        float y = Style.section("Weapon groups", x, top, w) - 4f;
        if (req.groups != null) { button(x, y - 28f, 80f, 28f, "UNDO", Panel.WARN, true, B_UNDO); y -= 38f; }
        List<RefitData.Group> groups = shownGroups();
        List<String> weapons = groupSlots();
        if (weapons.isEmpty()) { ui.text("No weapons to group.", body, Panel.DIM, x, y, w); return; }
        int cols = RefitPlan.MAX_GROUPS;
        float cw = Math.min(48f, (w * 0.55f) / cols), nameW = w - cw * cols - 8f, gx = x + nameW + 8f;
        // the heads: number, LINK / ALT, AUTO
        for (int g = 0; g < cols; g++) {
            RefitData.Group grp = g < groups.size() ? groups.get(g) : null;
            float cx = gx + g * cw;
            boolean alt = grp != null && grp.alternating, auto = grp != null && grp.autofire;
            Style.text(String.valueOf(g + 1), Style.SMALL_FONT, CYAN, cx + cw / 2f - 4f, y, cw);
            boolean over = Style.inside(cx + 1f, y - 40f, cw - 2f, 18f);
            LobbyScreen.fill(cx + 1f, y - 40f, cw - 2f, 18f, over ? TILE_OVER : TILE);
            Style.text(alt ? "ALT" : "LINK", Style.SMALL_FONT, alt ? Panel.WARN : Panel.TEXT, cx + 4f, y - 24f, cw - 6f);
            hits.add(new Hit(cx + 1f, y - 40f, cw - 2f, 18f, H_GROUP_MODE, g, ""));
            over = Style.inside(cx + 1f, y - 62f, cw - 2f, 18f);
            LobbyScreen.fill(cx + 1f, y - 62f, cw - 2f, 18f, auto ? Style.alpha(CYAN, 60) : over ? TILE_OVER : TILE);
            Style.text("AUTO", Style.SMALL_FONT, auto ? CYAN : Panel.DIM, cx + 4f, y - 46f, cw - 6f);
            hits.add(new Hit(cx + 1f, y - 62f, cw - 2f, 18f, H_GROUP_AUTO, g, ""));
        }
        Style.text("LINK / ALT: fire together or in turn.  AUTO: on autofire when battle starts.", Style.SMALL_FONT, Panel.DIM, x, y - 24f, nameW - 6f);
        y -= 70f;
        float rowH = 24f, bottom = floor + 40f;
        int fit = Math.max(1, (int) ((y - bottom) / rowH));
        groupScroll = Math.max(0, Math.min(groupScroll, weapons.size() - fit));
        groupArea = new float[] { x, bottom, w, y - bottom };
        int end = Math.min(weapons.size(), groupScroll + fit);
        for (int i = groupScroll; i < end; i++) {
            String slot = weapons.get(i);
            float ry = y - rowH;
            String weapon = weaponAfter(slot);
            String key = weapon == null ? null : RefitData.key(RefitData.WEAPON, weapon);
            if (Style.inside(x, ry, w, rowH - 2f)) LobbyScreen.fill(x, ry, w, rowH - 2f, TILE);
            RefitIcons.iconBox(RefitIcons.picture(key, false), x + 2f, ry + 1f, rowH - 4f, false);
            ui.text(key == null ? slot : names.item(key), body, builtInWeapon(slot) != null ? Panel.DIM : Panel.TEXT, x + rowH + 4f, ry + rowH - 4f, nameW - rowH - 6f);
            if (key != null && Style.inside(x, ry, nameW, rowH - 2f)) { Integer op = data.opCosts.get(key); Tips.hover(key, false, data.hullSize, op == null ? -1 : op); }
            int in = -1;
            for (int g = 0; g < groups.size(); g++) if (groups.get(g).slots.contains(slot)) { in = g; break; }
            for (int g = 0; g < cols; g++) {
                float cx = gx + g * cw + 1f, cy = ry + 1f, cww = cw - 2f, chh = rowH - 4f;
                boolean over = Style.inside(cx, cy, cww, chh);
                LobbyScreen.fill(cx, cy, cww, chh, g == in ? Style.alpha(CYAN, 150) : over ? TILE_OVER : RefitIcons.BACK);
                if (over) LobbyScreen.outline(cx, cy, cww, chh, CYAN);
                hits.add(new Hit(cx, cy, cww, chh, H_GROUP, g, slot));
            }
            y -= rowH;
        }
        if (groupScroll > 0) ui.text("more above (wheel)", body, Panel.DIM, x + w - 130f, groupArea[1] + groupArea[3] + 14f, 130f);
        if (end < weapons.size()) ui.text("more below (wheel)", body, Panel.DIM, x + w - 130f, bottom - 2f, 130f);
        ui.text(plan().groupsChanged() ? "The host applies these with the rest of the refit." : "Click a cell to put a weapon in that group; again to take it out. The game groups any left out.",
                body, Panel.DIM, x, floor + 18f, w);
    }

    /** Tabs (all, cargo, storage, each market) and the cards of what can go in, two to a row, scrolled by the wheel. */
    private void tabsAndCards(Row row, RefitNames names, float x, float top, float w, float floor) {
        String body = ui.body;
        float y = searchBox(row, x, top, w) - 8f;
        List<String> srcs = sources(row, names);
        if (srcs.size() > 1 || !tab.isEmpty()) {
            float tx = x;
            List<String> all = new ArrayList<String>();
            all.add("");
            all.addAll(srcs);
            for (String s : all) {
                String label = s.isEmpty() ? "All" : names.source(s);
                if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                com.fs.starfarer.api.ui.LabelAPI l = ui.label(label, body, Panel.TEXT, 400f);
                float tw = Math.min(160f, l.computeTextWidth(label) + 18f);
                if (tx + tw > x + w) { tx = x; y -= 28f; }
                boolean on = tab.equals(s), over = Style.inside(tx, y - 24f, tw, 24f);
                LobbyScreen.fill(tx, y - 24f, tw, 24f, on ? TILE_MARKED : over ? TILE_OVER : TILE);
                LobbyScreen.outline(tx, y - 24f, tw, 24f, on ? CYAN : LobbyScreen.LINE);
                ui.text(label, body, on ? Color.WHITE : Panel.TEXT, tx + 9f, y - 4f, tw - 12f);
                hits.add(new Hit(tx, y - 24f, tw, 24f, H_TAB, 0, s));
                tx += tw + 6f;
            }
            y -= 32f;
        }
        List<Option> opts = options(row, names, true);
        if (opts.isEmpty() || (opts.size() == 1 && opts.get(0).key == null && row.kind != ADD_MOD)) {
            ui.text(!search.trim().isEmpty() ? "Nothing matches \"" + search.trim() + "\"." : filterable(row) && filtersOff(row) > 0 ? "Nothing gets past the filters."
                    : row.kind == ADD_MOD ? "No other hull mods are known to the host." : "Nothing the host has fits here.",
                    body, Panel.DIM, x, y - (opts.isEmpty() ? 0f : 66f), w);
            if (opts.isEmpty()) return;
        }
        float gap = 8f, cardW = (w - gap) / 2f, cardH = 58f;
        // 20 cards at a time: the rest come in sets of 20 (the whole game's list is long, and each picture is a texture)
        int shown = Math.min(opts.size(), shownMax);
        moreLeft = opts.size() - shown;
        int cols = 2, rowsFit = Math.max(1, (int) ((y - floor + gap) / (cardH + gap)));
        int totalRows = (shown + cols - 1) / cols + (moreLeft > 0 ? 1 : 0);
        maxCardScroll = Math.max(0, totalRows - rowsFit);
        cardScroll = Math.max(0, Math.min(cardScroll, maxCardScroll));
        cardArea = new float[] { x, floor, w, y - floor };
        RefitRequest.Change ch = row.kind == SLOT || row.kind == BAY ? req.change(row.id, row.kind == BAY) : null;
        String current = ch != null ? (ch.item.isEmpty() ? null : RefitData.key(row.kind == BAY ? RefitData.WING : RefitData.WEAPON, ch.item) + "|" + ch.from) : "";
        for (int i = cardScroll * cols; i < shown && i < (cardScroll + rowsFit) * cols; i++) {
            Option o = opts.get(i);
            int col = (i - cardScroll * cols) % cols, rowN = (i - cardScroll * cols) / cols;
            float cx = x + col * (cardW + gap), cy = y - (rowN + 1) * (cardH + gap) + gap;
            boolean over = !overFilters && Style.inside(cx, cy, cardW, cardH);
            boolean chosen = o.key == null ? ch != null && ch.item.isEmpty() : (o.key + "|" + o.from).equals(current);
            if (over && o.key != null) {
                Integer op = row.kind == ADD_MOD ? data.modCosts.get(o.key) : data.opCosts.get(o.key);
                Tips.hover(o.key, row.kind == ADD_MOD, data.hullSize, op == null ? -1 : op);
            }
            LobbyScreen.fill(cx, cy, cardW, cardH, chosen ? TILE_MARKED : over && o.usable ? TILE_OVER : TILE);
            LobbyScreen.outline(cx, cy, cardW, cardH, chosen ? Panel.GOOD : over && o.usable ? CYAN : LobbyScreen.LINE);
            String name, line;
            Color c = o.usable ? Panel.TEXT : Panel.DIM;
            if (o.key == null) { name = row.kind == BAY ? "Leave the bay empty" : "Leave it empty"; line = "The one there now comes out"; c = Panel.DIM; }
            else if (o.elsewhere) {
                name = o.name == null ? o.key : o.name.replace(" LPC", "");
                line = "Unavailable";
                c = Style.FAINT;
            } else if (row.kind == ADD_MOD) {
                Integer cost = data.modCosts.get(o.key);
                name = names.mod(o.key);
                line = cost == null ? "" : cost + " OP";
            } else {
                Integer op = data.opCosts.get(o.key);
                name = names.item(o.key).replace(" LPC", "");
                line = (op == null ? "" : op + " OP   ") + names.source(o.from) + " (" + o.count + ")" + (o.price > 0 ? "   " + grouped(Math.round(o.price)) + " cr" : "");
            }
            float icon = cardH - 10f;
            if (o.key != null) RefitIcons.iconBox(RefitIcons.picture(o.key, row.kind == ADD_MOD), cx + 5f, cy + 5f, icon, !o.usable);
            float tx = cx + (o.key != null ? icon + 12f : 10f);
            float nameW = cardW - (tx - cx) - 6f;
            if (!o.usable && !o.elsewhere) nameW -= Style.width("LOCKED", Style.SMALL_FONT) + 16f;
            if (!o.usable && !o.elsewhere) Style.chip("locked", Panel.WARN, cx + cardW - Style.width("LOCKED", Style.SMALL_FONT) - 16f, cy + cardH - 6f);
            ui.text(name, body, c, tx, cy + cardH - 6f, nameW);
            Integer modCost = row.kind == ADD_MOD && !o.elsewhere && o.key != null ? data.modCosts.get(o.key) : null;
            Color lineColor = modCost != null && modCost > data.opMax - plan().opUsed ? Panel.BAD : Panel.DIM; // costs more than the ship has free
            ui.text(line, body, lineColor, tx, cy + cardH / 2f - 2f, cardW - (tx - cx) - 6f);
            hits.add(new Hit(cx, cy, cardW, cardH, H_CARD, i, ""));
        }
        // the "more" card, a full row after the shown ones, when scrolled to
        int moreRow = (shown + cols - 1) / cols;
        if (moreLeft > 0 && moreRow >= cardScroll && moreRow < cardScroll + rowsFit) {
            float cy = y - (moreRow - cardScroll + 1) * (cardH + gap) + gap;
            boolean over = Style.inside(x, cy, w, cardH);
            Style.button(x, cy + 12f, w, cardH - 24f, "Show " + Math.min(PAGE, moreLeft) + " more  (" + moreLeft + " not shown)", CYAN, true, over);
            hits.add(new Hit(x, cy, w, cardH, H_BUTTON, B_MORE, ""));
        }
        if (totalRows > rowsFit) ui.text(Math.min(shown, (cardScroll + rowsFit) * cols) + " of " + opts.size() + (moreLeft > 0 ? " (the wheel, at the end, shows more)" : "  (wheel to scroll)"),
                body, Panel.DIM, x, floor + 2f, w);
    }

    /** The search box over the cards: click (or Ctrl+F) and type; the cards narrow to names with those words. */
    private float searchBox(Row row, float x, float top, float w) {
        String body = ui.body;
        float h = 28f, bh = Style.lineHeight(body);
        // the "Whole game" tick, right of the box
        String tickText = "AVAILABLE";
        float tickW = Style.width(tickText, Style.SMALL_FONT) + 34f;
        float kx = x + w - tickW;
        boolean overTick = Style.inside(kx, top - h, tickW, h);
        tick(kx, top - h, tickW, h, tickText, availableOnly, true, overTick);
        hits.add(new Hit(kx, top - h, tickW, h, H_BUTTON, B_WHOLE, ""));
        if (filterable(row)) { // the filters button, left of the tick: they pop up under it
            int off = filtersOff(row);
            String label = off > 0 ? "FILTERS (" + off + ")" : "FILTERS";
            float fw = Math.max(90f, Style.width(label, body) + 28f), fx = kx - 10f - fw;
            button(fx, top - h, fw, h, label, off > 0 || filterOpen ? CYAN : Panel.DIM, true, B_FILTERS);
            filterButton = new float[] { fx, top - h, fw, h };
            w -= fw + 10f;
        }
        w -= tickW + 10f;
        boolean over = Style.inside(x, top - h, w, h);
        LobbyScreen.fill(x, top - h, w, h, typingSearch ? new Color(0, 0, 0, 120) : over ? TILE_OVER : new Color(0, 0, 0, 80));
        LobbyScreen.outline(x, top - h, w, h, typingSearch ? CYAN : LobbyScreen.LINE);
        Style.text("SEARCH", Style.SMALL_FONT, typingSearch || !search.isEmpty() ? CYAN : Style.FAINT, x + 9f, top - (h - Style.lineHeight(Style.SMALL_FONT)) / 2f, 70f);
        String what = row.kind == ADD_MOD ? "hull mods" : row.kind == BAY ? "fighter wings" : "weapons";
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        String shown = search.isEmpty() && !typingSearch ? "Type to find " + what + " by name  (Ctrl+F)" : search + (typingSearch && blink ? "_" : "");
        ui.text(shown, body, search.isEmpty() && !typingSearch ? Style.FAINT : Panel.TEXT, x + 70f, top - (h - bh) / 2f, w - 110f);
        hits.add(new Hit(x, top - h, w, h, H_BUTTON, B_SEARCH, ""));
        if (!search.isEmpty()) {
            float cw = 26f;
            boolean overX = Style.inside(x + w - cw - 2f, top - h + 2f, cw, h - 4f);
            ui.text("x", body, overX ? Color.WHITE : Panel.DIM, x + w - cw + 7f, top - (h - bh) / 2f, cw);
            hits.add(new Hit(x + w - cw - 2f, top - h + 2f, cw, h - 4f, H_BUTTON, B_SEARCH_CLEAR, ""));
        }
        return top - h;
    }

    // ---- bottom: send, start over, back, and the host's answer ----

    private void bottomBar(RefitPlan plan, float x, float y, float w, float h) {
        boolean ready = docked() && !plan.isEmpty() && plan.allProblems().isEmpty();
        float bx = button(x, y, 220f, h, "SEND TO HOST", ready ? Panel.GOOD : Panel.DIM, true, B_SEND) + 10f;
        bx = button(bx, y, 150f, h, "START OVER", Panel.WARN, !plan.isEmpty() || !req.note.isEmpty(), B_RESET) + 10f;
        if (wingman.compat.ModCompat.exoticaEnabled()) bx = button(bx, y, 130f, h, "EXOTICA", Panel.TEXT, true, B_EXOTICA) + 10f;
        bx = button(bx, y, 110f, h, "BACK", Panel.TEXT, true, B_BACK) + 16f;
        String say = !status.isEmpty() ? status.replace('\n', ' ')
                : !docked() ? "Planning: your plan is kept. Send it once the host docks at a market."
                : plan.isEmpty() ? "Change something, then send it. The host checks it and does it if it passes; nothing changes until then."
                : !plan.allProblems().isEmpty() ? "Fix what is in red first." : "Ready to send.";
        ui.text(say, ui.body, !status.isEmpty() ? statusColor : ready ? Panel.GOOD : Panel.DIM, bx, y + h - 6f, x + w - bx);
    }

    private static String grouped(long n) {
        String s = String.valueOf(Math.abs(n));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(',');
            sb.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + sb;
    }
}
