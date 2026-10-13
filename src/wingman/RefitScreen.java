package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import wingman.net.RefitData;
import wingman.net.Roster;

/**
 * Friend refit, joiner side: the refit screen, drawn as the lobby's third page (LobbyScreen.REFIT) with the
 * lobby's own drawing helpers. It shows the picked ship as it stands on the host (weapons by slot, fighter bays,
 * hull mods, ordnance points, vents and capacitors with the host's limits) and what the host could fit to it:
 * cargo, storage and each submarket here. Everything comes from the host (RefitData); the slots and names are
 * this game's own specs, which match the host's (same mods). See docs/FRIEND_REFIT_DESIGN.md.
 */
final class RefitScreen {
    private final LobbyScreen ui;

    RefitScreen(LobbyScreen ui) { this.ui = ui; }

    void render(LobbyScreen.View v, float x, float top, float w, float h) {
        String body = ui.body;
        LobbyScreen.frame(x, top - h, w, h);
        Roster.Member mem = v.roster == null ? null : v.roster.pickOf(v.me);
        RefitData d = v.refitData;
        String title = "REFIT" + (mem == null ? "" : ":  " + Roster.label(mem)) + (v.refitMarket.isEmpty() ? "" : "   at " + v.refitMarket);
        float y = ui.text(title, LobbyScreen.HEAD, LobbyScreen.TITLE, x + 16f, top - 12f, w - 32f) - 10f;
        if (d == null || mem == null || !d.memberId.equals(mem.id)) {
            ui.text("Asking the host for your ship and what this market has...", body, Panel.DIM, x + 16f, y, w - 32f);
            return;
        }
        float gap = 16f, colW = (w - 32f - 2 * gap) / 3f, floor = top - h + 16f;
        float c1 = x + 16f, c2 = c1 + colW + gap, c3 = c2 + colW + gap;
        ship(d, mem, c1, y, colW, floor);
        loadout(d, c2, y, colW, floor);
        supply(d, c3, y, colW, floor);
    }

    // ---- the ship: its picture and its limits ----

    private void ship(RefitData d, Roster.Member mem, float x, float top, float w, float floor) {
        String body = ui.body;
        float box = Math.min(w, Math.max(120f, (top - floor) * 0.55f));
        try {
            List<String> fits = new ArrayList<String>();
            for (int i = 0; i < d.fitSlots.size(); i++) fits.add("-1|" + d.fitSlots.get(i) + "|" + d.fitWeapons.get(i));
            for (String f : mem.fits) if (!f.startsWith("-1|")) fits.add(f); // modules' weapons, as the roster has them
            ShipPicture.draw(d.hullId, mem.sprite, mem.modules, fits, x + w / 2f, top - box / 2f, box);
        } catch (Throwable t) { /* no picture */ }
        float y = top - box - 12f;
        y = limit("Ordnance points", d.opUsed, d.opMax, x, y, w) - 2f;
        y = limit("Flux vents", d.vents, d.maxVents, x, y, w) - 2f;
        y = limit("Flux capacitors", d.caps, d.maxCaps, x, y, w) - 2f;
        y = limit("S-mods", d.sMods.size(), d.maxSMods, x, y, w) - 10f;
        ui.text("The limits are worked out on the host, with the host's skills.", body, Panel.DIM, x, Math.max(y, floor + 20f), w);
    }

    private float limit(String what, int used, int max, float x, float y, float w) {
        Color c = max > 0 && used > max ? Panel.BAD : Panel.TEXT;
        return ui.text(what + ":   " + used + " / " + (max > 0 ? String.valueOf(max) : "?"), ui.body, c, x, y, w);
    }

    // ---- what is fitted ----

    private void loadout(RefitData d, float x, float top, float w, float floor) {
        String body = ui.body;
        ShipHullSpecAPI hull = null;
        try { hull = Global.getSettings().getHullSpec(d.hullId); } catch (Throwable t) { /* names only */ }
        float y = ui.text("WEAPONS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
        List<String> lines = new ArrayList<String>();
        List<Color> colors = new ArrayList<Color>();
        Set<String> fitted = new HashSet<String>(d.fitSlots);
        for (int i = 0; i < d.fitSlots.size(); i++) {
            lines.add(weaponName(d.fitWeapons.get(i)) + slotNote(hull, d.fitSlots.get(i)));
            colors.add(Panel.TEXT);
        }
        if (hull != null) { // the slots that could take a weapon but are empty
            for (WeaponSlotAPI s : hull.getAllWeaponSlotsCopy()) {
                try {
                    if (fitted.contains(s.getId()) || s.isBuiltIn() || s.isDecorative() || s.isSystemSlot() || s.isHidden() || s.isStationModule()) continue;
                    lines.add("empty" + slotNote(hull, s.getId()));
                    colors.add(Panel.DIM);
                } catch (Throwable t) { /* left out */ }
            }
        }
        if (lines.isEmpty()) { lines.add("No weapon slots."); colors.add(Panel.DIM); }
        float listFloor = floor + (top - floor) * 0.45f;
        y = list(lines, colors, x, y, w, listFloor) - 10f;

        if (!d.wings.isEmpty()) {
            y = ui.text("FIGHTER BAYS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, y, w) - 4f;
            int builtIn = 0;
            try { builtIn = hull == null ? 0 : hull.getBuiltInWings().size(); } catch (Throwable t) { /* none */ }
            lines = new ArrayList<String>();
            colors = new ArrayList<Color>();
            for (int i = 0; i < d.wings.size(); i++) {
                String wing = d.wings.get(i);
                lines.add("Bay " + (i + 1) + ":  " + (wing.isEmpty() ? "empty" : wingName(wing)) + (i < builtIn && !wing.isEmpty() ? "  (built in)" : ""));
                colors.add(wing.isEmpty() ? Panel.DIM : Panel.GOOD);
            }
            y = list(lines, colors, x, y, w, floor + 90f) - 10f;
        }

        y = ui.text("HULL MODS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, y, w) - 4f;
        StringBuilder mods = new StringBuilder(), dmods = new StringBuilder();
        if (hull != null) {
            try {
                for (String id : hull.getBuiltInMods()) {
                    if (shown(id)) mods.append(mods.length() == 0 ? "" : ", ").append(modName(id)).append(d.sMods.contains(id) ? " (built in, S)" : " (built in)");
                }
            } catch (Throwable t) { /* none */ }
        }
        for (String id : d.sMods) {
            if (hull != null && hull.getBuiltInMods().contains(id)) continue; // said above
            if (shown(id)) mods.append(mods.length() == 0 ? "" : ", ").append(modName(id)).append(" (S)");
        }
        for (String id : d.hullmods) if (shown(id)) mods.append(mods.length() == 0 ? "" : ", ").append(modName(id));
        for (String id : d.permaMods) if (shown(id)) dmods.append(dmods.length() == 0 ? "" : ", ").append(modName(id));
        y = ui.text(mods.length() == 0 ? "None." : mods.toString(), body, mods.length() == 0 ? Panel.DIM : Panel.TEXT, x, y, w) - 2f;
        if (dmods.length() > 0) ui.text("Permanent: " + dmods, body, Panel.WARN, x, y, w);
    }

    /** " (medium ballistic)" for a slot. */
    private static String slotNote(ShipHullSpecAPI hull, String slotId) {
        try {
            WeaponSlotAPI s = hull.getWeaponSlotAPI(slotId);
            return "   (" + s.getSlotSize().name().toLowerCase() + " " + s.getWeaponType().name().toLowerCase().replace('_', ' ') + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Lines down to a floor; what doesn't fit is counted. */
    private float list(List<String> lines, List<Color> colors, float x, float y, float w, float floor) {
        for (int i = 0; i < lines.size(); i++) {
            if (y - 20f < floor && i < lines.size() - 1) return ui.text("and " + (lines.size() - i) + " more", ui.body, Panel.DIM, x, y, w) - 1f;
            y = ui.text(lines.get(i), ui.body, colors.get(i), x, y, w) - 1f;
        }
        return y;
    }

    // ---- what the host has ----

    private void supply(RefitData d, float x, float top, float w, float floor) {
        String body = ui.body;
        float y = ui.text("WHAT THE HOST HAS", LobbyScreen.HEAD, LobbyScreen.TITLE, x, top, w) - 4f;
        y = ui.text("Credits:  " + grouped(Math.round(d.credits)) + "      Story points:  " + d.storyPoints, body, Panel.TEXT, x, y, w) - 8f;
        y = ui.text("Cargo:  " + summary(d.cargo), body, Panel.TEXT, x, y, w) - 2f;
        y = ui.text(d.hasStorage ? "Storage here:  " + summary(d.storage) + " (free to take)" : "No storage here.", body, d.hasStorage ? Panel.TEXT : Panel.DIM, x, y, w) - 8f;
        for (RefitData.Market m : d.markets) {
            if (y - 40f < floor + 60f) break;
            String head = m.name + (m.usable ? "   (tariff " + Math.round(m.tariff * 100f) + "%)" : "");
            y = ui.text(head, body, m.usable ? Panel.GOOD : Panel.DIM, x, y, w) - 1f;
            y = ui.text(m.usable ? "    " + summary(m.stock) : "    " + m.reason, body, m.usable ? Panel.TEXT : Panel.WARN, x, y, w) - 6f;
        }
        y = ui.text("Hull mods the host knows:  " + d.knownHullmods.size(), body, Panel.DIM, x, y - 4f, w) - 14f;
        ui.text("For now this screen shows the ship and what the host has. Choosing weapons, fighters, vents and hull mods, "
                + "and sending the refit to the host, comes next.", body, Panel.DIM, x, Math.max(y, floor + 40f), w);
    }

    /** "12 weapons, 3 fighter LPCs" (counting each one). */
    private static String summary(List<RefitData.Item> items) {
        int weapons = 0, wings = 0;
        for (RefitData.Item it : items) { if (it.kind == RefitData.WING) wings += it.count; else weapons += it.count; }
        if (weapons == 0 && wings == 0) return "no weapons or fighter LPCs";
        return weapons + (weapons == 1 ? " weapon" : " weapons") + ", " + wings + (wings == 1 ? " fighter LPC" : " fighter LPCs");
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

    private static String weaponName(String id) {
        try { return Global.getSettings().getWeaponSpec(id).getWeaponName(); } catch (Throwable t) { return id; }
    }

    private static String wingName(String id) {
        try { return Global.getSettings().getFighterWingSpec(id).getWingName(); } catch (Throwable t) { return id; }
    }

    private static String modName(String id) {
        try { return Global.getSettings().getHullModSpec(id).getDisplayName(); } catch (Throwable t) { return id; }
    }

    private static boolean shown(String id) {
        try {
            HullModSpecAPI s = Global.getSettings().getHullModSpec(id);
            return !s.isHidden() && !s.isHiddenEverywhere();
        } catch (Throwable t) {
            return true;
        }
    }
}
