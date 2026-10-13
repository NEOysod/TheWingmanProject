package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
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
 * lobby's own drawing helpers. The middle column lists what can be changed (each weapon slot, each fighter bay,
 * vents, capacitors, hull mods, then a note and SEND); the right column offers what can go in the marked row,
 * from the host's cargo, storage and each submarket here, only what fits; the left shows the ship with the new
 * loadout, the totals (ordnance points, credits, story points, S-mods) and anything that stops it being sent.
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
    private int rowAt, optAt;
    private boolean inOptions, typingNote;
    /** The last word from the host on a request, and the color to show it in. */
    private String status = "";
    private Color statusColor = Panel.DIM;
    private final List<float[]> rowRects = new ArrayList<float[]>(), optRects = new ArrayList<float[]>();

    /** New data from the host: a different ship starts afresh; the same keeps what was chosen. */
    void setData(RefitData d) {
        if (d == null) { data = null; return; }
        if (req == null || !req.memberId.equals(d.memberId)) reset(d);
        data = d;
    }

    private void reset(RefitData d) {
        req = new RefitRequest();
        req.memberId = d.memberId;
        req.vents = d.vents;
        req.caps = d.caps;
        rowAt = 0;
        optAt = 0;
        inOptions = false;
        typingNote = false;
    }

    /** The screen opened afresh (or closed): nothing chosen, nothing heard. */
    void clear() {
        req = null;
        data = null;
        status = "";
        typingNote = false;
    }

    void onResult(RefitResult r) {
        switch (r.status) {
            case RefitResult.RECEIVED: statusColor = Panel.GOOD; break;
            case RefitResult.ACCEPTED: statusColor = Panel.GOOD; req = null; break; // starts afresh on the new loadout the host sends
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

    // ---- rows: what can be changed ----

    private static final int SLOT = 0, BAY = 1, VENTS = 2, CAPS = 3, MOD = 4, ADD_MOD = 5, NOTE = 6, SEND_ROW = 7;

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

    private List<Row> rows() {
        List<Row> rows = new ArrayList<Row>();
        ShipHullSpecAPI hull = hull();
        List<String> slots = new ArrayList<String>();
        if (hull != null) {
            for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
                try { if (changeable(s)) slots.add(s.getId()); } catch (Throwable t) { /* left out */ }
            }
        } else slots.addAll(data.fitSlots);
        Collections.sort(slots);
        for (String s : slots) rows.add(new Row(SLOT, s));
        for (int i = 0; i < data.wings.size(); i++) rows.add(new Row(BAY, String.valueOf(i)));
        rows.add(new Row(VENTS, ""));
        rows.add(new Row(CAPS, ""));
        for (String id : data.sMods) rows.add(new Row(MOD, id));
        for (String id : data.permaMods) rows.add(new Row(MOD, id));
        for (String id : data.hullmods) rows.add(new Row(MOD, id));
        for (String id : req.addMods) rows.add(new Row(MOD, id));
        rows.add(new Row(ADD_MOD, ""));
        rows.add(new Row(NOTE, ""));
        rows.add(new Row(SEND_ROW, ""));
        return rows;
    }

    private int builtInWings() {
        try { ShipHullSpecAPI h = hull(); return h == null ? 0 : h.getBuiltInWings().size(); } catch (Throwable t) { return 0; }
    }

    // ---- options: what can go in the marked row ----

    private static final class Option {
        final String key, from;
        final int count;
        final float price;
        final boolean usable;
        Option(String key, String from, int count, float price, boolean usable) { this.key = key; this.from = from; this.count = count; this.price = price; this.usable = usable; }
    }

    private List<Option> options(Row row, FriendRefit.Names names) {
        List<Option> out = new ArrayList<Option>();
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
                    int c = n.item(a.key).compareToIgnoreCase(n.item(b.key));
                    return c != 0 ? c : Float.compare(a.price, b.price);
                }
            });
            out.addAll(found);
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
        return out;
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
        if (row.kind == ADD_MOD) {
            if (o.key != null && !req.addMods.contains(o.key)) req.addMods.add(o.key);
            inOptions = false;
            return;
        }
        if (!o.usable) return;
        RefitRequest.Change c = changeFor(row);
        c.item = o.key == null ? "" : o.key.substring(2);
        c.from = o.key == null ? "" : o.from;
        tidy();
        inOptions = false;
    }

    /** Changes that change nothing are dropped. */
    private void tidy() {
        RefitPlan p = plan();
        for (int i = req.changes.size() - 1; i >= 0; i--) if (p.sameAsBefore(req.changes.get(i))) req.changes.remove(i);
    }

    private void undo(Row row) {
        if (row.kind == SLOT || row.kind == BAY) {
            RefitRequest.Change c = req.change(row.id, row.kind == BAY);
            if (c != null) req.changes.remove(c);
        } else if (row.kind == VENTS) req.vents = data.vents;
        else if (row.kind == CAPS) req.caps = data.caps;
        else if (row.kind == MOD) { req.removeMods.remove(row.id); req.addMods.remove(row.id); req.buildIn.remove(row.id); }
        else if (row.kind == NOTE) req.note = "";
    }

    /** Where the item taken out of a slot or bay goes next: cargo, storage, then each submarket that would buy it. */
    private void cycleDestination(Row row) {
        if (row.kind != SLOT && row.kind != BAY) return;
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
        if (row.kind != MOD || data.sMods.contains(row.id) || data.permaMods.contains(row.id)) return;
        if (req.buildIn.remove(row.id)) return;
        if (req.removeMods.contains(row.id)) return;
        req.buildIn.add(row.id);
    }

    private void toggleRemove(Row row) {
        if (row.kind != MOD) return;
        if (req.addMods.remove(row.id)) { req.buildIn.remove(row.id); return; }
        if (!data.hullmods.contains(row.id)) return; // S-mods, D-mods and built-ins stay
        if (!req.removeMods.remove(row.id)) { req.removeMods.add(row.id); req.buildIn.remove(row.id); }
    }

    private void adjust(Row row, int by) {
        if (row.kind == VENTS) req.vents = Math.max(0, Math.min(data.maxVents > 0 ? data.maxVents : 999, req.vents + by));
        if (row.kind == CAPS) req.caps = Math.max(0, Math.min(data.maxCaps > 0 ? data.maxCaps : 999, req.caps + by));
    }

    private int trySend() {
        RefitPlan p = plan();
        if (p.isEmpty()) { status = "Nothing is changed yet."; statusColor = Panel.WARN; return NOTHING; }
        if (!p.allProblems().isEmpty()) { status = "Fix what is in red first."; statusColor = Panel.BAD; return NOTHING; }
        return SEND;
    }

    /** A key on the refit screen (LWJGL key code, its character, Ctrl and Shift held). */
    int key(int key, char ch, boolean ctrl, boolean shift) {
        if (data == null || req == null) return key == 1 ? CLOSE : NOTHING;
        if (typingNote) {
            if (key == 1 || key == 28 || key == 156) typingNote = false;                      // Esc, Enter: done
            else if (key == 14) { if (!req.note.isEmpty()) req.note = req.note.substring(0, req.note.length() - 1); }
            else if (ch >= ' ' && ch < 127 && req.note.length() < RefitRequest.MAX_NOTE) req.note += ch;
            return NOTHING;
        }
        List<Row> rows = rows();
        rowAt = Math.max(0, Math.min(rows.size() - 1, rowAt));
        Row row = rows.get(rowAt);
        if (ctrl && Panel.isEnter(key)) return trySend();
        if (key == 44) { if (shift) reset(data); else undo(row); return NOTHING; }      // Z: undo this row, Shift+Z: everything
        if (key == 31) { cycleDestination(row); return NOTHING; }                         // S: where the old one goes
        if (key == 48) { toggleBuildIn(row); return NOTHING; }                            // B: build in
        if (key == 211) { toggleRemove(row); return NOTHING; }                            // Delete: hull mod off (or back on)
        if (inOptions) {
            List<Option> opts = options(row, new FriendRefit.Names(data));
            if (key == 1 || key == 203) { inOptions = false; return NOTHING; }          // Esc, Left: back to the rows
            if (opts.isEmpty()) { inOptions = false; return NOTHING; }
            if (key == 200) optAt = (optAt + opts.size() - 1) % opts.size();
            else if (key == 208) optAt = (optAt + 1) % opts.size();
            else if (key == 201) optAt = Math.max(0, optAt - 10);
            else if (key == 209) optAt = Math.min(opts.size() - 1, optAt + 10);
            else if (Panel.isEnter(key)) pick(row, opts.get(Math.max(0, Math.min(opts.size() - 1, optAt))));
            return NOTHING;
        }
        if (key == 1) return CLOSE;
        if (key == 200) rowAt = (rowAt + rows.size() - 1) % rows.size();
        else if (key == 208) rowAt = (rowAt + 1) % rows.size();
        else if (key == 201) rowAt = Math.max(0, rowAt - 10);
        else if (key == 209) rowAt = Math.min(rows.size() - 1, rowAt + 10);
        else if (key == 203 || key == 205) adjust(row, (key == 205 ? 1 : -1) * (shift ? 5 : 1));
        else if (Panel.isEnter(key)) {
            if (row.kind == SEND_ROW) return trySend();
            if (row.kind == NOTE) typingNote = true;
            else if (row.kind == SLOT || row.kind == BAY || row.kind == ADD_MOD) {
                if (!options(row, new FriendRefit.Names(data)).isEmpty()) { inOptions = true; optAt = 0; }
            }
        }
        return NOTHING;
    }

    /** A click at (x, y) on the screen. */
    int click(float x, float y) {
        if (data == null || req == null) return NOTHING;
        List<Row> rows = rows();
        for (float[] r : optRects) {
            if (x < r[0] || x > r[0] + r[2] || y < r[1] || y > r[1] + r[3]) continue;
            List<Option> opts = options(rows.get(Math.max(0, Math.min(rows.size() - 1, rowAt))), new FriendRefit.Names(data));
            int i = (int) r[4];
            if (i >= 0 && i < opts.size()) { optAt = i; pick(rows.get(rowAt), opts.get(i)); }
            return NOTHING;
        }
        for (float[] r : rowRects) {
            if (x < r[0] || x > r[0] + r[2] || y < r[1] || y > r[1] + r[3]) continue;
            int i = (int) r[4];
            if (i < 0 || i >= rows.size()) return NOTHING;
            boolean again = i == rowAt;
            rowAt = i;
            Row row = rows.get(i);
            typingNote = false;
            if (row.kind == SEND_ROW) return trySend();
            if (row.kind == NOTE) typingNote = true;
            inOptions = (row.kind == SLOT || row.kind == BAY || row.kind == ADD_MOD) && (again || inOptions);
            if (inOptions) optAt = 0;
            return NOTHING;
        }
        return NOTHING;
    }

    // ---- drawing ----

    void render(LobbyScreen.View v, float x, float top, float w, float h) {
        String body = ui.body;
        rowRects.clear();
        optRects.clear();
        LobbyScreen.frame(x, top - h, w, h);
        Roster.Member mem = v.roster == null ? null : v.roster.pickOf(v.me);
        setData(v.refitData);
        String title = "REFIT" + (mem == null ? "" : ":  " + Roster.label(mem)) + (v.refitMarket.isEmpty() ? "" : "   at " + v.refitMarket);
        float y = ui.text(title, LobbyScreen.HEAD, LobbyScreen.TITLE, x + 16f, top - 12f, w - 32f) - 10f;
        if (data == null || req == null || mem == null || !data.memberId.equals(mem.id)) {
            ui.text("Asking the host for your ship and what this market has...", body, Panel.DIM, x + 16f, y, w - 32f);
            return;
        }
        FriendRefit.Names names = new FriendRefit.Names(data);
        RefitPlan plan = new RefitPlan(data, req, names);
        float gap = 16f, floor = top - h + 16f;
        float w1 = (w - 32f - 2 * gap) * 0.30f, w2 = (w - 32f - 2 * gap) * 0.38f, w3 = (w - 32f - 2 * gap) - w1 - w2;
        float c1 = x + 16f, c2 = c1 + w1 + gap, c3 = c2 + w2 + gap;
        List<Row> rows = rows();
        rowAt = Math.max(0, Math.min(rows.size() - 1, rowAt));
        ship(plan, mem, c1, y, w1, floor);
        rowList(rows, plan, names, c2, y, w2, floor);
        side(rows.get(rowAt), plan, names, c3, y, w3, floor);
    }

    /** The ship with the new loadout, the totals, and what is wrong. */
    private void ship(RefitPlan plan, Roster.Member mem, float x, float top, float w, float floor) {
        String body = ui.body;
        float box = Math.min(w, Math.max(110f, (top - floor) * 0.40f));
        try {
            List<String> fits = new ArrayList<String>();
            List<String> slots = new ArrayList<String>(data.fitSlots);
            for (RefitRequest.Change c : req.changes) if (!c.wing && !slots.contains(c.place)) slots.add(c.place);
            for (String s : slots) {
                RefitRequest.Change c = req.change(s, false);
                String wpn = c != null ? c.item : data.weaponIn(s);
                if (wpn != null && !wpn.isEmpty()) fits.add("-1|" + s + "|" + wpn);
            }
            for (String f : mem.fits) if (!f.startsWith("-1|")) fits.add(f);
            ShipPicture.draw(data.hullId, mem.sprite, mem.modules, fits, x + w / 2f, top - box / 2f, box);
        } catch (Throwable t) { /* no picture */ }
        float y = top - box - 10f;
        y = limit("Ordnance points", plan.opUsed, data.opMax, x, y, w) - 1f;
        y = limit("Flux vents", req.vents, data.maxVents, x, y, w) - 1f;
        y = limit("Flux capacitors", req.caps, data.maxCaps, x, y, w) - 1f;
        y = limit("S-mods", plan.sMods, data.maxSMods, x, y, w) - 6f;
        int net = plan.net();
        y = ui.text("Credits:  " + (plan.buy > 0 ? "pay " + grouped(plan.buy) : "") + (plan.buy > 0 && plan.sell > 0 ? ",  " : "")
                + (plan.sell > 0 ? "receive " + grouped(plan.sell) : "") + (plan.buy == 0 && plan.sell == 0 ? "none" : "")
                + "   (host has " + grouped(Math.round(data.credits)) + ")", body, net > data.credits ? Panel.BAD : Panel.TEXT, x, y, w) - 1f;
        y = ui.text("Story points:  " + plan.storyPoints + "   (host has " + data.storyPoints + ")", body, plan.storyPoints > data.storyPoints ? Panel.BAD : Panel.TEXT, x, y, w) - 8f;
        List<String> problems = plan.allProblems();
        for (int i = 0; i < problems.size(); i++) {
            if (y - 40f < floor) { y = ui.text("and " + (problems.size() - i) + " more", body, Panel.BAD, x, y, w) - 1f; break; }
            y = ui.text(problems.get(i), body, Panel.BAD, x, y, w) - 1f;
        }
        if (!status.isEmpty()) ui.text(status.replace('\n', ' '), body, statusColor, x, Math.max(floor + 34f, y - 6f), w);
    }

    private float limit(String what, int used, int max, float x, float y, float w) {
        Color c = max > 0 && used > max ? Panel.BAD : Panel.TEXT;
        return ui.text(what + ":   " + used + " / " + (max > 0 ? String.valueOf(max) : "?"), ui.body, c, x, y, w);
    }

    /** The middle column: every row, the marked one highlighted, changes in green. */
    private void rowList(List<Row> rows, RefitPlan plan, FriendRefit.Names names, float x, float top, float w, float floor) {
        String body = ui.body;
        float rowH = 21f;
        int fit = Math.max(4, (int) ((top - floor) / rowH));
        int from = Math.max(0, Math.min(rowAt - fit / 2, rows.size() - fit)), to = Math.min(rows.size(), from + fit);
        float y = top;
        ShipHullSpecAPI hull = hull();
        for (int i = from; i < to; i++) {
            Row row = rows.get(i);
            if (i == rowAt) LobbyScreen.fill(x - 4f, y - rowH + 2f, w + 8f, rowH, inOptions ? new Color(120, 210, 255, 25) : LobbyScreen.MARK);
            String text;
            Color c = Panel.TEXT;
            switch (row.kind) {
                case SLOT: {
                    RefitRequest.Change ch = req.change(row.id, false);
                    String now = ch != null ? ch.item : data.weaponIn(row.id);
                    text = slotTag(hull, row.id) + "  " + (now == null || now.isEmpty() ? "empty" : names.item(RefitData.key(RefitData.WEAPON, now)));
                    if (ch != null) { c = Panel.GOOD; text += changeNote(ch, plan, names); }
                    else if (now == null) c = Panel.DIM;
                    break;
                }
                case BAY: {
                    int bay = Integer.parseInt(row.id);
                    RefitRequest.Change ch = req.change(row.id, true);
                    String now = ch != null ? ch.item : data.wings.get(bay);
                    text = "Bay " + (bay + 1) + "  " + (now.isEmpty() ? "empty" : names.item(RefitData.key(RefitData.WING, now)).replace(" LPC", ""))
                            + (bay < builtInWings() ? "  (built in)" : "");
                    if (ch != null) { c = Panel.GOOD; text += changeNote(ch, plan, names); }
                    else if (now.isEmpty() || bay < builtInWings()) c = Panel.DIM;
                    break;
                }
                case VENTS: text = "Flux vents   " + req.vents + " / " + data.maxVents + "   (Left/Right)"; if (req.vents != data.vents) c = Panel.GOOD; break;
                case CAPS: text = "Flux capacitors   " + req.caps + " / " + data.maxCaps + "   (Left/Right)"; if (req.caps != data.caps) c = Panel.GOOD; break;
                case MOD: {
                    Integer cost = data.modCosts.get(row.id);
                    String name = names.mod(row.id);
                    if (data.sMods.contains(row.id)) { text = "S-mod  " + name; c = Panel.DIM; }
                    else if (data.permaMods.contains(row.id)) { text = "Permanent  " + name; c = Panel.WARN; }
                    else if (req.removeMods.contains(row.id)) { text = "Taken off  " + name; c = Panel.WARN; }
                    else {
                        boolean added = req.addMods.contains(row.id), built = req.buildIn.contains(row.id);
                        text = (added ? "Added  " : "Hull mod  ") + name + (built ? "   built in (S-mod, 1 story point)" : cost == null ? "" : "   " + cost + " OP");
                        if (added || built) c = Panel.GOOD;
                    }
                    break;
                }
                case ADD_MOD: text = "+  Add a hull mod"; c = LobbyScreen.TITLE; break;
                case NOTE: text = "Note to the host:  " + (req.note.isEmpty() && !typingNote ? "(Enter to write one)" : req.note + (typingNote && i == rowAt ? "_" : "")); c = Panel.DIM; break;
                default: {
                    RefitPlan p = plan;
                    boolean ready = !p.isEmpty() && p.allProblems().isEmpty();
                    text = "SEND TO THE HOST   (Ctrl+Enter)";
                    c = ready ? Panel.GOOD : Panel.DIM;
                }
            }
            ui.text(text, body, c, x, y - 2f, w);
            rowRects.add(new float[] { x - 4f, y - rowH + 2f, w + 8f, rowH, i });
            y -= rowH;
        }
        if (rows.size() > fit) ui.text((rowAt + 1) + " of " + rows.size(), body, Panel.DIM, x, floor + 14f, w);
    }

    /** "  (was X, to cargo)" for a changed slot or bay. */
    private String changeNote(RefitRequest.Change ch, RefitPlan plan, FriendRefit.Names names) {
        String old = plan.before(ch);
        String src = ch.item.isEmpty() ? "" : ", from " + names.source(ch.from);
        if (old == null) return "   (new" + src + ")";
        String to = ch.to.isEmpty() ? RefitRequest.CARGO : ch.to;
        return "   (" + names.item(old).replace(" LPC", "") + (data.market(to) != null ? " sold at " : " to ") + names.source(to) + src + ")";
    }

    private static String slotTag(ShipHullSpecAPI hull, String slotId) {
        try {
            WeaponSlotAPI s = hull.getWeaponSlotAPI(slotId);
            String size = s.getSlotSize().name().substring(0, 1);
            String type = s.getWeaponType().name().toLowerCase();
            return "[" + size + " " + (type.length() > 4 ? type.substring(0, 4) : type) + "]";
        } catch (Throwable t) {
            return "[" + slotId + "]";
        }
    }

    /** The right column: what can go in the marked row, or what can be done with it. */
    private void side(Row row, RefitPlan plan, FriendRefit.Names names, float x, float top, float w, float floor) {
        String body = ui.body;
        float y;
        switch (row.kind) {
            case SLOT:
            case BAY: {
                boolean builtIn = row.kind == BAY && Integer.parseInt(row.id) < builtInWings();
                y = ui.text(row.kind == SLOT ? "FOR THIS SLOT" : "FOR THIS BAY", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                if (builtIn) { ui.text("This bay's wing is built into the hull.", body, Panel.DIM, x, y, w); return; }
                if (row.kind == SLOT) y = ui.text(slotDetail(row.id), body, Panel.DIM, x, y, w) - 4f;
                List<Option> opts = options(row, names);
                if (opts.size() <= 1) y = ui.text("Nothing the host has fits here.", body, Panel.DIM, x, y, w) - 4f;
                optionList(opts, names, x, y, w, floor + 70f);
                RefitRequest.Change ch = req.change(row.id, row.kind == BAY);
                String old = ch == null ? null : plan.before(ch);
                String help = (inOptions ? "Up/Down, Enter: choose     Left/Esc: back" : "Enter (or a click): choose what goes in")
                        + (old != null ? "\nS: where " + names.item(old).replace(" LPC", "") + " goes (cargo, storage, or sold)" : "") + "\nZ: undo";
                ui.text(help, body, Panel.DIM, x, floor + 64f, w);
                return;
            }
            case ADD_MOD: {
                y = ui.text("HULL MODS THE HOST KNOWS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                List<Option> opts = options(row, names);
                if (opts.isEmpty()) ui.text("No other hull mods are known to the host.", body, Panel.DIM, x, y, w);
                else optionList(opts, names, x, y, w, floor + 50f);
                ui.text(inOptions ? "Up/Down, Enter: add     Left/Esc: back" : "Enter (or a click): choose one to add", body, Panel.DIM, x, floor + 34f, w);
                return;
            }
            case MOD: {
                y = ui.text(names.mod(row.id).toUpperCase(), LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                String desc = "";
                try {
                    HullModSpecAPI s = Global.getSettings().getHullModSpec(row.id);
                    Integer cost = data.modCosts.get(row.id);
                    desc = (cost == null ? "" : cost + " ordnance points on this hull.") + (data.noBuildIn.contains(row.id) ? " Can't be built in." : "");
                    if (s.getManufacturer() != null && !s.getManufacturer().isEmpty()) desc += " " + s.getManufacturer() + ".";
                } catch (Throwable t) { /* the name alone */ }
                y = ui.text(desc, body, Panel.DIM, x, y, w) - 8f;
                String help;
                if (data.sMods.contains(row.id)) help = "Built in (an S-mod): it stays.";
                else if (data.permaMods.contains(row.id)) help = "Permanent (a D-mod or similar): it stays. Restoring is done by the host.";
                else help = "Delete: take it off (or put it back)" + (data.noBuildIn.contains(row.id) ? "" : "\nB: build it in as an S-mod (1 story point; it then costs no ordnance points)") + "\nZ: undo";
                ui.text(help, body, Panel.TEXT, x, y, w);
                ui.text("Whether a hull mod suits this ship (what it needs, what it rules out) is for the host to judge when accepting.", body, Panel.DIM, x, floor + 34f, w);
                return;
            }
            case VENTS:
            case CAPS:
                y = ui.text(row.kind == VENTS ? "FLUX VENTS" : "FLUX CAPACITORS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                ui.text("Left/Right: one less or more (Shift: five). Each costs 1 ordnance point. The most this ship can have, with the host's skills: "
                        + (row.kind == VENTS ? data.maxVents : data.maxCaps) + ".\nZ: back to " + (row.kind == VENTS ? data.vents : data.caps) + ".", body, Panel.TEXT, x, y, w);
                return;
            case NOTE:
                y = ui.text("NOTE", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                ui.text(typingNote ? "Type your note; Enter when done." : "Enter: write a short note the host sees with your refit. Z: clear it.", body, Panel.TEXT, x, y, w);
                return;
            default: {
                y = ui.text("SEND", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
                String what = plan.isEmpty() ? "Nothing is changed yet." : !plan.allProblems().isEmpty() ? "Fix what is in red first."
                        : "Enter (or Ctrl+Enter anywhere): send it. The host checks it again and accepts or denies it; nothing changes until then. Sending again replaces it.";
                y = ui.text(what, body, plan.isEmpty() || !plan.allProblems().isEmpty() ? Panel.WARN : Panel.TEXT, x, y, w) - 8f;
                ui.text("Shift+Z: start over.", body, Panel.DIM, x, y, w);
            }
        }
    }

    private String slotDetail(String slotId) {
        try {
            WeaponSlotAPI s = hull().getWeaponSlotAPI(slotId);
            return s.getSlotSize().name().toLowerCase() + " " + s.getWeaponType().name().toLowerCase().replace('_', ' ') + " " + (s.isTurret() ? "turret" : "hardpoint");
        } catch (Throwable t) {
            return slotId;
        }
    }

    private void optionList(List<Option> opts, FriendRefit.Names names, float x, float top, float w, float floor) {
        String body = ui.body;
        float rowH = 20f;
        int fit = Math.max(3, (int) ((top - floor) / rowH));
        optAt = Math.max(0, Math.min(opts.size() - 1, optAt));
        int from = Math.max(0, Math.min(optAt - fit / 2, opts.size() - fit)), to = Math.min(opts.size(), from + fit);
        float y = top;
        for (int i = from; i < to; i++) {
            Option o = opts.get(i);
            if (inOptions && i == optAt) LobbyScreen.fill(x - 4f, y - rowH + 2f, w + 8f, rowH, LobbyScreen.MARK);
            String text;
            Color c = o.usable ? Panel.TEXT : Panel.DIM;
            if (o.key == null) { text = "Leave it empty"; c = Panel.DIM; }
            else if (o.from.isEmpty()) {
                Integer cost = data.modCosts.get(o.key);
                text = names.mod(o.key) + (cost == null ? "" : "   " + cost + " OP");
            } else {
                Integer op = data.opCosts.get(o.key);
                text = names.item(o.key).replace(" LPC", "") + (op == null ? "" : "  " + op + " OP") + "   " + names.source(o.from) + " (" + o.count + ")"
                        + (o.price > 0 ? "  " + grouped(Math.round(o.price)) + " cr" : "") + (o.usable ? "" : "  - can't be bought");
                if (!o.from.equals(RefitRequest.CARGO) && !o.from.equals(RefitRequest.STORAGE) && o.usable) c = new Color(200, 220, 255);
            }
            ui.text(text, body, c, x, y - 2f, w);
            optRects.add(new float[] { x - 4f, y - rowH + 2f, w + 8f, rowH, i });
            y -= rowH;
        }
        if (opts.size() > fit) ui.text((optAt + 1) + " of " + opts.size(), body, Panel.DIM, x, y - 2f, w);
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
