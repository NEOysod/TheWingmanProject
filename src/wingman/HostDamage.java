package wingman;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import wingman.net.DamageState;
import wingman.net.HostSession;
import wingman.net.Msg;

/**
 * Host side of armor and EMP damage. These change in bursts and are bulky (an armor grid is a couple of
 * hundred cells), so instead of riding in every snapshot they are checked four times a second and only
 * the ships whose state actually changed are sent, and of their armor only the cells that changed.
 */
final class HostDamage {
    private final Map<Integer, DamageState.Ship> lastSent = new HashMap<Integer, DamageState.Ship>();
    private long lastTickAt, lastFullAt;
    private boolean broken;
    private int round;
    /** Ships with something new to report for certain (ammo used), looked at on the next pass. */
    private final java.util.Set<Integer> touched = new java.util.HashSet<Integer>();
    int looked, passedOver;

    void touch(int id) { touched.add(id); }

    /** Someone needs the whole picture (a player just joined): send everything on the next tick. */
    void resendAll() { lastSent.clear(); }

    /**
     * @param hit ships that have taken damage of any kind since the last pass (emptied here), or null if that
     *            can't be known, in which case every ship is looked at every time, as before.
     *
     * Reading a ship means going through its whole armor grid, so only the ships with a reason are read: hit,
     * ammo changed, flown by a player, never sent. The rest take turns, an eighth of them each pass, which
     * catches what gives no sign (a weapon coming back from repair, armor a script mends) within two seconds.
     */
    void tick(HostSession session, Map<Integer, ShipAPI> byId, java.util.Set<Integer> hit) {
        if (broken || session.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastTickAt < 250) return;
        lastTickAt = now;
        if (now - lastFullAt > 10000) { lastFullAt = now; lastSent.clear(); } // periodic refresh as a safety net
        try {
            round++;
            java.util.Set<Integer> flown = new java.util.HashSet<Integer>();
            for (HostSession.Player p : session.getPlayers()) if (p.shipNetId >= 0) flown.add(p.shipNetId);
            DamageState out = new DamageState();
            for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
                ShipAPI s = e.getValue();
                if (s.isFighter()) continue;
                Integer id = e.getKey();
                if (hit != null && lastSent.containsKey(id) && !hit.contains(id) && !touched.contains(id) && !flown.contains(id)
                        && ((id + round) & 7) != 0) { passedOver++; continue; }
                looked++;
                DamageState.Ship d = read(e.getKey(), s);
                // Armor goes as the cells that changed since last time; the whole grid only the first time, on
                // the ten-second refresh, or when most of it changed at once.
                DamageState.Ship last = lastSent.get(e.getKey());
                boolean armorChanged = d.armorAgainst(last);
                if (!armorChanged && last != null && d.sameSystems(last)) continue;
                lastSent.put(e.getKey(), d);
                out.ships.add(d);
                if (out.ships.size() >= 40) { session.broadcast(Msg.DAMAGE, out.encode()); out = new DamageState(); }
            }
            if (!out.ships.isEmpty()) session.broadcast(Msg.DAMAGE, out.encode());
            if (lastSent.size() > byId.size() + 50) lastSent.keySet().retainAll(byId.keySet());
            if (hit != null) hit.clear();
            touched.clear();
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(HostDamage.class).error("Wingman: sending armor/EMP state failed, that sync is off for this battle", t);
        }
    }

    private static DamageState.Ship read(int id, ShipAPI s) {
        DamageState.Ship d = new DamageState.Ship();
        d.netId = id;
        List<WeaponAPI> weapons = s.getAllWeapons();
        d.ammo = new short[Math.min(weapons.size(), 64)];
        for (int i = 0; i < d.ammo.length; i++) {
            WeaponAPI w = weapons.get(i);
            if (w.isDisabled()) d.weaponsDisabled |= 1L << i;
            d.ammo[i] = w.usesAmmo() ? (short) Math.max(0, Math.min(Short.MAX_VALUE, w.getAmmo())) : -1;
        }
        ShipEngineControllerAPI ec = s.getEngineController();
        if (ec != null) {
            d.flamedOut = ec.isFlamedOut();
            List<ShipEngineAPI> engines = ec.getShipEngines();
            for (int i = 0; engines != null && i < engines.size() && i < 32; i++) if (engines.get(i).isDisabled()) d.enginesDisabled |= 1 << i;
        }
        ArmorGridAPI grid = s.getArmorGrid();
        float[][] cells = grid == null ? null : grid.getGrid();
        if (cells != null && cells.length > 0 && cells.length < 256 && cells[0].length < 256) {
            d.gridWidth = cells.length;
            d.gridHeight = cells[0].length;
            d.armor = new byte[d.gridWidth * d.gridHeight];
            float max = grid.getMaxArmorInCell();
            for (int x = 0; x < d.gridWidth; x++) {
                for (int y = 0; y < d.gridHeight; y++) {
                    float f = max <= 0f ? 1f : Math.max(0f, Math.min(1f, cells[x][y] / max));
                    d.armor[x * d.gridHeight + y] = (byte) Math.round(f * 255f);
                }
            }
        }
        return d;
    }
}
