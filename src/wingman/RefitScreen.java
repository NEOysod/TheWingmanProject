package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import wingman.net.RefitData;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;
import wingman.net.RefitResult;
import wingman.net.Roster;

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
final class RefitScreen {
    private final LobbyScreen ui;

    RefitScreen(LobbyScreen ui) { this.ui = ui; }

    /** What key() and click() ask of the title plugin. */
    static final int NOTHING = 0, CLOSE = 1, SEND = 2;

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
    void saveDraft() {
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

    void onResult(RefitResult r) {
        switch (r.status) {
            case RefitResult.RECEIVED: statusColor = Panel.GOOD; break;
            case RefitResult.ACCEPTED: // starts afresh on the new loadout, once the host has sent it
                statusColor = Panel.GOOD;
                if (req != null) Drafts.forget(req.memberId);
                req = null;
                data = null;
                break;
            case RefitResult.DENIED: statusColor = Panel.WARN; break;
            default: statusColor = Panel.BAD;
        }
        status = r.text;
    }

    /** The request to send, with the totals the joiner was shown. */
    RefitRequest request() {
        RefitPlan p = plan();
        req.expectCredits = p.net();
        req.expectStoryPoints = p.storyPoints;
        status = "Sending...";
        statusColor = Panel.DIM;
        return req;
    }

    private RefitPlan plan() { return new RefitPlan(data, req, new FriendRefit.Names(data)); }

    // ---- rows (tiles): what can be changed ----

    private static final int SLOT = 0, BAY = 1, VENTS = 2, CAPS = 3, MOD = 4, ADD_MOD = 5, NOTE = 6, HEAD = 7;

    private static final class Row {
        final int kind;
        final String id;
        Row(int kind, String id) { this.kind = kind; this.id = id; }
    }

    private ShipHullSpecAPI hull() {
        try { return Global.getSettings().getHullSpec(data.hullId); } catch (Throwable t) { return null; }
    }

    private static boolean changeable(WeaponSlotAPI s) {
        return s.isWeaponSlot() && !s.isBuiltIn() && !s.isDecorative() && !s.isSystemSlot() && !s.isHidden() && !s.isStationModule();
    }

    private List<String> slots() {
        List<String> slots = new ArrayList<String>();
        ShipHullSpecAPI hull = hull();
        if (hull != null) {
            for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
                try { if (changeable(s)) slots.add(s.getId()); } catch (Throwable t) { /* left out */ }
            }
        } else slots.addAll(data.fitSlots);
        // biggest first, then by place, as the game's own list
        final ShipHullSpecAPI h = hull;
        Collections.sort(slots, new Comparator<String>() {
            public int compare(String a, String b) {
                int c = size(h, b) - size(h, a);
                return c != 0 ? c : a.compareTo(b);
            }
        });
        return slots;
    }

    private static int size(ShipHullSpecAPI hull, String slot) {
        try { return hull.getWeaponSlotAPI(slot).getSlotSize().ordinal(); } catch (Throwable t) { return 0; }
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row(HEAD, "WEAPONS"));
        for (String s : slots()) rows.add(new Row(SLOT, s));
        if (!data.wings.isEmpty()) {
            rows.add(new Row(HEAD, "FIGHTER BAYS"));
            for (int i = 0; i < data.wings.size(); i++) rows.add(new Row(BAY, String.valueOf(i)));
        }
        rows.add(new Row(HEAD, "FLUX"));
        rows.add(new Row(VENTS, ""));
        rows.add(new Row(CAPS, ""));
        rows.add(new Row(HEAD, "HULL MODS"));
        // (hidden hull mods, the ones the game's refit screen doesn't show, aren't shown here either)
        for (String id : data.sMods) if (!hiddenMod(id)) rows.add(new Row(MOD, id));
        for (String id : data.permaMods) if (!hiddenMod(id)) rows.add(new Row(MOD, id));
        for (String id : data.hullmods) if (!hiddenMod(id)) rows.add(new Row(MOD, id));
        for (String id : req.addMods) rows.add(new Row(MOD, id));
        rows.add(new Row(ADD_MOD, ""));
        rows.add(new Row(HEAD, "NOTE"));
        rows.add(new Row(NOTE, ""));
        return rows;
    }

    private int builtInWings() {
        try { ShipHullSpecAPI h = hull(); return h == null ? 0 : h.getBuiltInWings().size(); } catch (Throwable t) { return 0; }
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

    // ---- options (cards): what can go in the marked row ----

    private static final class Option {
        final String key, from;
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

    private List<Option> options(Row row, FriendRefit.Names names, boolean filtered) {
        if (!filtered || row == null) return build(row, names, filtered);
        String key = row.kind + "|" + row.id + "|" + search + "|" + tab + "|" + availableOnly + "|" + req.addMods + "|" + req.changes.size();
        if (cached == null || cachedData != data || !key.equals(cachedFor)) {
            if (!key.equals(cachedFor)) shownMax = PAGE;
            cached = build(row, names, true);
            cachedFor = key;
            cachedData = data;
        }
        return cached;
    }

    private List<Option> build(Row row, FriendRefit.Names names, boolean filtered) {
        List<Option> out = new ArrayList<Option>();
        if (row == null) return out;
        if (row.kind == SLOT || row.kind == BAY) {
            if (row.kind == BAY && Integer.parseInt(row.id) < builtInWings()) return out;
            out.add(new Option(null, "", 0, 0f, true)); // leave it empty
            WeaponSlotAPI slot = null;
            if (row.kind == SLOT) { try { slot = hull().getWeaponSlotAPI(row.id); } catch (Throwable t) { /* no fit check */ } }
            List<Option> found = new ArrayList<Option>();
            addFrom(found, data.cargo, RefitRequest.CARGO, null, row, slot);
            if (data.hasStorage) addFrom(found, data.storage, RefitRequest.STORAGE, null, row, slot);
            for (RefitData.Market m : data.markets) addFrom(found, m.stock, m.id, m, row, slot);
            final FriendRefit.Names n = names;
            Collections.sort(found, new Comparator<Option>() {
                public int compare(Option a, Option b) {
                    if (a.usable != b.usable) return a.usable ? -1 : 1;
                    int c = n.item(a.key).compareToIgnoreCase(n.item(b.key));
                    return c != 0 ? c : Float.compare(a.price, b.price);
                }
            });
            for (Option o : found) if (!filtered || tab.isEmpty() || tab.equals(o.from)) out.add(o);
        } else if (row.kind == ADD_MOD) {
            final FriendRefit.Names n = names;
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
    private void addWholeGame(List<Option> out, Row row, FriendRefit.Names names) {
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (Option o : out) if (o.key != null) have.add(o.key);
        List<Option> more = new ArrayList<Option>();
        String hide = com.fs.starfarer.api.impl.campaign.ids.Tags.HIDE_IN_CODEX;
        try {
            if (row.kind == SLOT) {
                WeaponSlotAPI slot = null;
                try { slot = hull().getWeaponSlotAPI(row.id); } catch (Throwable t) { /* no fit check */ }
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
    private boolean matches(Option o, Row row, FriendRefit.Names names) {
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
    private List<String> sources(Row row, FriendRefit.Names names) {
        Set<String> s = new LinkedHashSet<String>();
        for (Option o : options(row, names, false)) if (o.key != null && !o.from.isEmpty()) s.add(o.from);
        return new ArrayList<String>(s);
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
        } else if (row.kind == VENTS) req.vents = data.vents;
        else if (row.kind == CAPS) req.caps = data.caps;
        else if (row.kind == MOD) { req.removeMods.remove(row.id); req.addMods.remove(row.id); req.buildIn.remove(row.id); }
        else if (row.kind == NOTE) req.note = "";
    }

    private boolean changed(Row row) {
        if (row == null) return false;
        if (row.kind == SLOT || row.kind == BAY) return req.change(row.id, row.kind == BAY) != null;
        if (row.kind == VENTS) return req.vents != data.vents;
        if (row.kind == CAPS) return req.caps != data.caps;
        if (row.kind == MOD) return req.removeMods.contains(row.id) || req.addMods.contains(row.id) || req.buildIn.contains(row.id);
        if (row.kind == NOTE) return !req.note.isEmpty();
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

    private void adjust(Row row, int by) {
        if (row == null) return;
        if (row.kind == VENTS) req.vents = Math.max(0, Math.min(data.maxVents > 0 ? data.maxVents : 999, req.vents + by));
        if (row.kind == CAPS) req.caps = Math.max(0, Math.min(data.maxCaps > 0 ? data.maxCaps : 999, req.caps + by));
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

    int key(int key, char ch, boolean ctrl, boolean shift) {
        if (data == null || req == null) return key == 1 ? CLOSE : NOTHING;
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
        if (key == 211) { toggleRemove(row); return NOTHING; }                            // Delete: hull mod off (or back on)
        if (key == 200 || key == 208) {                                                   // Up/Down: the tiles
            int i = rowAt;
            do { i = (i + (key == 200 ? rows.size() - 1 : 1)) % rows.size(); } while (rows.get(i).kind == HEAD && i != rowAt);
            mark(rows, i);
            return NOTHING;
        }
        if (row != null && (row.kind == VENTS || row.kind == CAPS)) {
            if (key == 203 || key == 205) adjust(row, (key == 205 ? 1 : -1) * (shift ? 5 : 1));
            return NOTHING;
        }
        List<Option> opts = options(row, new FriendRefit.Names(data), true);
        if (opts.isEmpty()) return NOTHING;
        if (key == 203) optAt = (optAt + opts.size() - 1) % opts.size();                 // Left/Right: the cards
        else if (key == 205) optAt = (optAt + 1) % opts.size();
        else if (Panel.isEnter(key)) pick(row, opts.get(Math.max(0, Math.min(opts.size() - 1, optAt))));
        return NOTHING;
    }

    // ---- the mouse: what was drawn where, and the clicks on it ----

    private static final int H_ROW = 0, H_CARD = 1, H_TAB = 2, H_RING = 3, H_BUTTON = 4;
    private static final int B_SEND = 0, B_RESET = 1, B_BACK = 2, B_DEST = 3, B_UNDO = 4, B_MINUS5 = 5, B_MINUS = 6, B_PLUS = 7, B_PLUS5 = 8,
            B_OFF = 9, B_BUILD = 10, B_NOTE = 11, B_CLEAR = 12, B_SEARCH = 13, B_SEARCH_CLEAR = 14, B_MORE = 15, B_WHOLE = 16;
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
    private float[] tileArea, cardArea;

    int click(float mx, float my) {
        if (data == null || req == null) return NOTHING;
        List<Row> rows = rows();
        for (int i = hits.size() - 1; i >= 0; i--) { // the last drawn is on top
            Hit h = hits.get(i);
            if (!h.has(mx, my)) continue;
            sound();
            Row row = marked(rows);
            switch (h.type) {
                case H_ROW: mark(rows, h.index); return NOTHING;
                case H_RING:
                    for (int k = 0; k < rows.size(); k++) if (rows.get(k).kind == SLOT && rows.get(k).id.equals(h.id)) { mark(rows, k); keepVisible(k); }
                    return NOTHING;
                case H_TAB: tab = h.id; optAt = 0; cardScroll = 0; return NOTHING;
                case H_CARD: {
                    List<Option> opts = options(row, new FriendRefit.Names(data), true);
                    if (h.index >= 0 && h.index < opts.size()) { optAt = h.index; pick(row, opts.get(h.index)); }
                    return NOTHING;
                }
                default:
                    switch (h.index) {
                        case B_SEND: return trySend();
                        case B_RESET: reset(data); return NOTHING;
                        case B_BACK: return CLOSE;
                        case B_DEST: cycleDestination(row); return NOTHING;
                        case B_UNDO: undo(row); return NOTHING;
                        case B_MINUS5: adjust(row, -5); return NOTHING;
                        case B_MINUS: adjust(row, -1); return NOTHING;
                        case B_PLUS: adjust(row, 1); return NOTHING;
                        case B_PLUS5: adjust(row, 5); return NOTHING;
                        case B_OFF: toggleRemove(row); return NOTHING;
                        case B_BUILD: toggleBuildIn(row); return NOTHING;
                        case B_NOTE: typingNote = true; typingSearch = false; return NOTHING;
                        case B_SEARCH: typingSearch = true; typingNote = false; return NOTHING;
                        case B_SEARCH_CLEAR: search = ""; typingSearch = false; optAt = 0; cardScroll = 0; return NOTHING;
                        case B_MORE: shownMax += PAGE; return NOTHING;
                        case B_WHOLE: availableOnly = !availableOnly; cardScroll = 0; optAt = 0; return NOTHING;
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
    boolean scroll(float mx, float my, int dir) {
        int by = dir > 0 ? -1 : 1;
        if (tileArea != null && mx >= tileArea[0] && mx <= tileArea[0] + tileArea[2] && my >= tileArea[1] && my <= tileArea[1] + tileArea[3]) { rowScroll = Math.max(0, rowScroll + by); return true; }
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

    private static final Color CYAN = LobbyScreen.TITLE, TILE = new Color(255, 255, 255, 10), TILE_OVER = new Color(120, 210, 255, 30),
            TILE_MARKED = new Color(120, 210, 255, 55), ICON_BACK = new Color(255, 255, 255, 16), BAR = new Color(4, 7, 12, 220);

    void render(LobbyScreen.View v, float x, float top, float w, float h) {
        String body = ui.body;
        hits.clear();
        tileArea = null;
        cardArea = null;
        Roster.Member mem = v.roster == null ? null : v.roster.pickOf(v.me);
        setData(v.refitData);
        String title = "REFIT" + (mem == null ? "" : ":  " + Roster.label(mem).toUpperCase());
        String where = data == null ? "" : (data.market.isEmpty() ? "host not at a market" : "docked at " + data.market)
                + "     host has " + grouped(Math.round(data.credits)) + " credits, " + data.storyPoints + " story point" + (data.storyPoints == 1 ? "" : "s");
        float y = Style.window(x, top - h, w, h, title, where) - 14f;
        if (data == null || req == null || mem == null || !data.memberId.equals(mem.id)) {
            ui.text("Asking the host for your ship and what this market has...", body, Panel.DIM, x + 16f, y, w - 32f);
            button(x + w - 16f - 120f, top - h + 12f, 120f, 34f, "BACK", Panel.DIM, true, B_BACK);
            return;
        }
        FriendRefit.Names names = new FriendRefit.Names(data);
        RefitPlan plan = new RefitPlan(data, req, names);
        long now = System.currentTimeMillis();
        if (now - savedAt > 3000) { savedAt = now; saveDraft(); } // (written only when it changed)
        List<Row> rows = rows();
        Row row = marked(rows);
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
    }

    /** A clickable button; returns its right edge. */
    private float button(float x, float y, float w, float h, String text, Color c, boolean enabled, int code) {
        boolean over = enabled && LobbyScreen.inside(x, y, w, h);
        Style.button(x, y, w, h, text, c, enabled, over);
        if (enabled) hits.add(new Hit(x, y, w, h, H_BUTTON, code, ""));
        return x + w;
    }

    /** A picture for an item key ("w:" weapon, "f:" fighter wing) or a hull mod id, or null. */
    static SpriteAPI picture(String key, boolean mod) {
        if (key == null) return null;
        try {
            if (mod) return LobbyScreen.sprite(Global.getSettings().getHullModSpec(key).getSpriteName());
            String id = key.substring(2);
            if (key.startsWith("f:")) return LobbyScreen.sprite(Global.getSettings().getFighterWingSpec(id).getVariant().getHullSpec().getSpriteName());
            WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(id);
            SpriteAPI s = LobbyScreen.sprite(ws.getTurretSpriteName());
            return s != null ? s : LobbyScreen.sprite(ws.getHardpointSpriteName());
        } catch (Throwable t) {
            return null;
        }
    }

    static void iconBox(SpriteAPI s, float x, float y, float size, boolean faded) {
        LobbyScreen.fill(x, y, size, size, ICON_BACK);
        if (s == null) return;
        s.setAlphaMult(faded ? 0.4f : 1f);
        LobbyScreen.icon(s, x + 2f, y + 2f, size - 4f);
        s.setAlphaMult(1f);
    }

    // ---- left: the ship with rings on its slots, the totals, what is wrong ----

    private void ship(RefitPlan plan, Roster.Member mem, Row row, List<Row> rows, float x, float top, float w, float floor) {
        String body = ui.body;
        top = Style.section("Ship", x, top, w) - 4f;
        float box = Math.min(w, Math.max(120f, (top - floor) * (docked() ? 0.42f : 0.34f)));
        float cx = x + w / 2f, cy = top - box / 2f;
        LobbyScreen.fill(x, top - box, w, box, Style.WELL);
        List<String> slots = slots();
        try {
            List<String> fits = new ArrayList<String>();
            for (String s : slots) {
                RefitRequest.Change c = req.change(s, false);
                String wpn = c != null ? c.item : data.weaponIn(s);
                if (wpn != null && !wpn.isEmpty()) fits.add("-1|" + s + "|" + wpn);
            }
            for (String s : data.fitSlots) if (!slots.contains(s)) fits.add("-1|" + s + "|" + data.weaponIn(s)); // built-ins and the like, drawn as they are
            for (String f : mem.fits) if (!f.startsWith("-1|")) fits.add(f);
            ShipPicture.draw(data.hullId, mem.sprite, mem.modules, fits, cx, cy, box);
        } catch (Throwable t) { /* no picture */ }
        // A ring on each slot that can take a weapon: click to choose it.
        String hover = null;
        try {
            Map<String, float[]> at = ShipPicture.slotPoints(data.hullId, slots);
            ShipHullSpecAPI hull = hull();
            for (String s : slots) {
                float[] p = at.get(s);
                if (p == null) continue;
                WeaponSlotAPI ws = hull == null ? null : hull.getWeaponSlotAPI(s);
                float r = ws == null ? 7f : 6f + 3f * ws.getSlotSize().ordinal();
                boolean marked = row != null && row.kind == SLOT && row.id.equals(s), changedSlot = req.change(s, false) != null;
                boolean over = LobbyScreen.inside(p[0] - r - 3f, p[1] - r - 3f, 2 * r + 6f, 2 * r + 6f);
                Color c = marked ? Color.WHITE : changedSlot ? Panel.GOOD : typeColor(ws);
                ring(p[0], p[1], r, c, marked || over ? 2.5f : 1.5f, marked ? 60 : over ? 40 : 0);
                hits.add(new Hit(p[0] - r - 3f, p[1] - r - 3f, 2 * r + 6f, 2 * r + 6f, H_RING, 0, s));
                if (over) {
                    RefitRequest.Change c2 = req.change(s, false);
                    String wpn = c2 != null ? c2.item : data.weaponIn(s);
                    hover = slotKind(ws) + ":  " + (wpn == null || wpn.isEmpty() ? "empty" : new FriendRefit.Names(data).item(RefitData.key(RefitData.WEAPON, wpn)));
                }
            }
        } catch (Throwable t) { /* the tiles alone */ }
        float y = top - box - 6f;
        y = ui.text(hover != null ? hover : "Click a ring on the ship, or a tile, to change it.", body, hover != null ? Panel.TEXT : Panel.DIM, x, y, w) - 12f;
        y = Style.section("Limits", x, y, w) - 2f;
        y = limit("Ordnance points", plan.opUsed, data.opMax, x, y, w) - 4f;
        y = limit("Flux vents", req.vents, data.maxVents, x, y, w) - 4f;
        y = limit("Flux capacitors", req.caps, data.maxCaps, x, y, w) - 4f;
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
                String what = ch == null ? id : ch.item.isEmpty() ? "Removed: " + names(plan, ch) : new FriendRefit.Names(data).item(RefitData.key(wing ? RefitData.WING : RefitData.WEAPON, ch.item)).replace(" LPC", "");
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
        return old == null ? "" : new FriendRefit.Names(data).item(old).replace(" LPC", "");
    }

    private WeaponSlotAPI slotOf(String id) {
        try { return hull().getWeaponSlotAPI(id); } catch (Throwable t) { return null; }
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

    private void tiles(List<Row> rows, RefitPlan plan, FriendRefit.Names names, float x, float top, float w, float floor) {
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
            boolean marked = i == rowAt, over = LobbyScreen.inside(x, ty, w, tileH);
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
    private void tile(Row r, RefitPlan plan, FriendRefit.Names names, float x, float y, float w, float h) {
        String body = ui.body;
        String name, detail = "", key = null;
        boolean mod = false;
        Color c = Panel.TEXT;
        switch (r.kind) {
            case SLOT: {
                RefitRequest.Change ch = req.change(r.id, false);
                String now = ch != null ? ch.item : data.weaponIn(r.id);
                key = now == null || now.isEmpty() ? null : RefitData.key(RefitData.WEAPON, now);
                name = key == null ? "Empty" : names.item(key);
                WeaponSlotAPI ws = null;
                try { ws = hull().getWeaponSlotAPI(r.id); } catch (Throwable t) { /* no kind */ }
                Integer op = key == null ? null : data.opCosts.get(key);
                detail = slotKind(ws) + (op == null ? "" : "   " + op + " OP");
                if (ch != null) { c = Panel.GOOD; detail = changeNote(ch, plan, names); }
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
            case VENTS: name = "Flux vents   " + req.vents + " / " + data.maxVents; detail = "1 OP each"; if (req.vents != data.vents) { c = Panel.GOOD; detail = "was " + data.vents; } break;
            case CAPS: name = "Flux capacitors   " + req.caps + " / " + data.maxCaps; detail = "1 OP each"; if (req.caps != data.caps) { c = Panel.GOOD; detail = "was " + data.caps; } break;
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
            case ADD_MOD: name = "+  Add a hull mod"; detail = "From those the host knows"; c = CYAN; break;
            default: name = req.note.isEmpty() && !typingNote ? "Write a note to the host" : req.note + (typingNote ? "_" : ""); detail = "Click, then type"; c = req.note.isEmpty() ? Panel.DIM : Panel.TEXT;
        }
        if (key != null && LobbyScreen.inside(x, y, w, h)) { // the pop-up with its numbers and description
            Integer op = mod ? data.modCosts.get(key) : data.opCosts.get(key);
            Tips.hover(key, mod, data.hullSize, op == null ? -1 : op);
        }
        float icon = h - 8f, tx = x + 4f;
        if (r.kind == SLOT || r.kind == BAY || r.kind == MOD) {
            iconBox(picture(key, mod), x + 4f, y + 4f, icon, r.kind == MOD && req.removeMods.contains(r.id));
            tx = x + icon + 12f;
        } else tx = x + 10f;
        ui.text(name, body, c, tx, y + h - 5f, w - (tx - x) - 6f);
        ui.text(detail, body, Panel.DIM, tx, y + h / 2f - 1f, w - (tx - x) - 6f);
    }

    /** "was X, to cargo" for a changed slot or bay. */
    private String changeNote(RefitRequest.Change ch, RefitPlan plan, FriendRefit.Names names) {
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

    private void side(Row row, RefitPlan plan, FriendRefit.Names names, float x, float top, float w, float floor) {
        String body = ui.body;
        if (row == null) return;
        float y;
        switch (row.kind) {
            case SLOT:
            case BAY: {
                boolean builtIn = row.kind == BAY && Integer.parseInt(row.id) < builtInWings();
                WeaponSlotAPI ws = null;
                if (row.kind == SLOT) { try { ws = hull().getWeaponSlotAPI(row.id); } catch (Throwable t) { /* none */ } }
                y = Style.section(row.kind == SLOT ? slotKind(ws) : "Fighter bay " + (Integer.parseInt(row.id) + 1), x, top, w) - 4f;
                if (builtIn) { ui.text("This bay's wing is built into the hull.", body, Panel.DIM, x, y, w); return; }
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
            case ADD_MOD:
                y = Style.section("Add a hull mod", x, top, w) - 4f;
                tabsAndCards(row, names, x, y, w, floor);
                return;
            case MOD: {
                y = Style.section("Hull mod", x, top, w) - 4f;
                y = ui.text(names.mod(row.id), LobbyScreen.HEAD, CYAN, x, y, w) - 8f;
                iconBox(picture(row.id, true), x, y - 64f, 64f, false);
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
                ui.text("Building in makes it an S-mod: permanent, free of ordnance points, 1 story point. Whether a hull mod suits this ship is for the host to judge.",
                        body, Panel.DIM, x, y, w);
                return;
            }
            case VENTS:
            case CAPS: {
                y = Style.section(row.kind == VENTS ? "Flux vents" : "Flux capacitors", x, top, w) - 8f;
                int n = row.kind == VENTS ? req.vents : req.caps, max = row.kind == VENTS ? data.maxVents : data.maxCaps;
                float bx = button(x, y - 40f, 54f, 40f, "-5", Panel.TEXT, n > 0, B_MINUS5) + 6f;
                bx = button(bx, y - 40f, 54f, 40f, "-", Panel.TEXT, n > 0, B_MINUS) + 10f;
                ui.text(n + " / " + max, LobbyScreen.BIG, Panel.TEXT, bx, y - 6f, 120f);
                bx += 110f;
                bx = button(bx, y - 40f, 54f, 40f, "+", Panel.TEXT, max <= 0 || n < max, B_PLUS) + 6f;
                bx = button(bx, y - 40f, 54f, 40f, "+5", Panel.TEXT, max <= 0 || n < max, B_PLUS5) + 10f;
                if (changed(row)) button(bx, y - 40f, 80f, 40f, "UNDO", Panel.WARN, true, B_UNDO);
                y -= 56f;
                ui.text("Each costs 1 ordnance point. The most this ship can have, with the host's skills: " + max + ". It had " + (row.kind == VENTS ? data.vents : data.caps) + ".",
                        body, Panel.DIM, x, y, w);
                return;
            }
            default: {
                y = Style.section("Note to the host", x, top, w) - 6f;
                boolean over = LobbyScreen.inside(x, y - 60f, w, 60f);
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

    /** Tabs (all, cargo, storage, each market) and the cards of what can go in, two to a row, scrolled by the wheel. */
    private void tabsAndCards(Row row, FriendRefit.Names names, float x, float top, float w, float floor) {
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
                boolean on = tab.equals(s), over = LobbyScreen.inside(tx, y - 24f, tw, 24f);
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
            ui.text(!search.trim().isEmpty() ? "Nothing matches \"" + search.trim() + "\"." : row.kind == ADD_MOD ? "No other hull mods are known to the host." : "Nothing the host has fits here.",
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
            boolean over = LobbyScreen.inside(cx, cy, cardW, cardH);
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
            if (o.key != null) iconBox(picture(o.key, row.kind == ADD_MOD), cx + 5f, cy + 5f, icon, !o.usable);
            float tx = cx + (o.key != null ? icon + 12f : 10f);
            float nameW = cardW - (tx - cx) - 6f;
            if (!o.usable && !o.elsewhere) nameW -= Style.width("LOCKED", Style.SMALL_FONT) + 16f;
            if (!o.usable && !o.elsewhere) Style.chip("locked", Panel.WARN, cx + cardW - Style.width("LOCKED", Style.SMALL_FONT) - 16f, cy + cardH - 6f);
            ui.text(name, body, c, tx, cy + cardH - 6f, nameW);
            ui.text(line, body, Panel.DIM, tx, cy + cardH / 2f - 2f, cardW - (tx - cx) - 6f);
            hits.add(new Hit(cx, cy, cardW, cardH, H_CARD, i, ""));
        }
        // the "more" card, a full row after the shown ones, when scrolled to
        int moreRow = (shown + cols - 1) / cols;
        if (moreLeft > 0 && moreRow >= cardScroll && moreRow < cardScroll + rowsFit) {
            float cy = y - (moreRow - cardScroll + 1) * (cardH + gap) + gap;
            boolean over = LobbyScreen.inside(x, cy, w, cardH);
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
        float kx = x + w - tickW, ks = 14f, ky = top - (h + ks) / 2f;
        boolean overTick = LobbyScreen.inside(kx, top - h, tickW, h);
        LobbyScreen.fill(kx, ky, ks, ks, availableOnly ? Style.alpha(CYAN, 70) : new Color(0, 0, 0, 90));
        LobbyScreen.outline(kx, ky, ks, ks, availableOnly || overTick ? CYAN : LobbyScreen.LINE);
        if (availableOnly) ui.text("x", body, Color.WHITE, kx + 3f, ky + ks - (ks - bh) / 2f + 1f, ks);
        Style.text(tickText, Style.SMALL_FONT, availableOnly || overTick ? CYAN : Style.DIM, kx + ks + 8f, top - (h - Style.lineHeight(Style.SMALL_FONT)) / 2f, tickW);
        hits.add(new Hit(kx, top - h, tickW, h, H_BUTTON, B_WHOLE, ""));
        w -= tickW + 10f;
        boolean over = LobbyScreen.inside(x, top - h, w, h);
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
            boolean overX = LobbyScreen.inside(x + w - cw - 2f, top - h + 2f, cw, h - 4f);
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
        bx = button(bx, y, 110f, h, "BACK", Panel.TEXT, true, B_BACK) + 16f;
        String say = !status.isEmpty() ? status.replace('\n', ' ')
                : !docked() ? "Planning: your plan is kept. Send it once the host docks at a market."
                : plan.isEmpty() ? "Change something, then send it. The host checks it and accepts or denies it; nothing changes until then."
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
