package wingman;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * Friend refit, joiner side: the numbers a loadout comes to, as the game's refit screen shows them, for the
 * joiner to build with: flux capacity and dissipation, the weapons' flux and damage per second, shields, hull,
 * armor and speed. Worked out on a copy of the ship made in this game (with its hull mods applied as the game
 * applies them before a battle); officers' and the host's skills aren't counted. If the copy can't be made (a
 * mod's hull mod that needs a campaign, say), the hull's own numbers with vents and capacitors are used, and
 * {@link Values#full} is false.
 */
final class LoadoutStats {
    private LoadoutStats() {}

    static final class Values {
        float capacity, dissipation, weaponFlux, dps, hull, armor, speed;
        ShieldType defense = ShieldType.NONE;
        float shieldArc, shieldUpkeep, shieldEfficiency, phaseCost, phaseUpkeep;
        /** Hull mods counted (false: the hull's numbers with vents and capacitors only). */
        boolean full;
        /** The weapons' flux and damage per second, each, by slot id. */
        Map<String, float[]> perSlot = new java.util.HashMap<String, float[]>();
    }

    /**
     * @param weapons slot id to weapon id, the slots that can be changed (built-in weapons are added from the hull)
     * @param mods hull mods fitted, sMods built in (S-mods), perma permanent ones (D-mods and the like)
     */
    static Values of(String hullId, Map<String, String> weapons, List<String> wings, Collection<String> mods, Collection<String> sMods,
            Collection<String> perma, int vents, int caps) {
        Values out = new Values();
        ShipHullSpecAPI hull;
        try { hull = Global.getSettings().getHullSpec(hullId); } catch (Throwable t) { return out; }
        if (hull == null) return out;
        MutableShipStatsAPI s = null;
        ShipVariantAPI v = null;
        try {
            v = Global.getSettings().createEmptyVariant(hullId + "_wingman_refit", hull);
            for (Map.Entry<String, String> e : weapons.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                try { v.addWeapon(e.getKey(), e.getValue()); } catch (Throwable t) { /* left out */ }
            }
            for (int i = 0; i < wings.size(); i++) {
                String w = wings.get(i);
                if (w != null && !w.isEmpty()) { try { v.setWingId(i, w); } catch (Throwable t) { /* left out */ } }
            }
            for (String id : mods) { try { v.addMod(id); } catch (Throwable t) { /* left out */ } }
            for (String id : sMods) { try { v.addPermaMod(id, true); } catch (Throwable t) { /* left out */ } }
            for (String id : perma) { try { v.addPermaMod(id, false); } catch (Throwable t) { /* left out */ } }
            v.setNumFluxVents(vents);
            v.setNumFluxCapacitors(caps);
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            m.updateStats();
            s = m.getStats();
            out.full = s != null;
        } catch (Throwable t) {
            s = null;
            Global.getLogger(LoadoutStats.class).info("Wingman: refit stats without hull mods (" + t + ")");
        }
        float perCap = 200f, perVent = 10f;
        try { perCap = Global.getSettings().getFloat("fluxPerCapacitor"); } catch (Throwable t) { /* the usual */ }
        try { perVent = Global.getSettings().getFloat("dissipationPerVent"); } catch (Throwable t) { /* the usual */ }
        if (s != null) {
            out.capacity = s.getFluxCapacity().getModifiedValue();
            out.dissipation = s.getFluxDissipation().getModifiedValue();
            if (out.capacity <= 0f) out.capacity = eff(s.getFluxCapacity(), hull.getFluxCapacity() + caps * perCap);
            if (out.dissipation <= 0f) out.dissipation = eff(s.getFluxDissipation(), hull.getFluxDissipation() + vents * perVent);
            out.hull = s.getHullBonus().computeEffective(hull.getHitpoints());
            out.armor = s.getArmorBonus().computeEffective(hull.getArmorRating());
            out.speed = s.getMaxSpeed().getModifiedValue();
            if (out.speed <= 0f) { try { out.speed = eff(s.getMaxSpeed(), hull.getEngineSpec().getMaxSpeed()); } catch (Throwable t) { /* 0 */ } }
        } else {
            out.capacity = hull.getFluxCapacity() + caps * perCap;
            out.dissipation = hull.getFluxDissipation() + vents * perVent;
            out.hull = hull.getHitpoints();
            out.armor = hull.getArmorRating();
            try { out.speed = hull.getEngineSpec().getMaxSpeed(); } catch (Throwable t) { /* 0 */ }
        }
        try {
            out.defense = hull.getShieldType() == null ? ShieldType.NONE : hull.getShieldType();
            ShipHullSpecAPI.ShieldSpecAPI sh = hull.getShieldSpec();
            if (sh != null && (out.defense == ShieldType.FRONT || out.defense == ShieldType.OMNI)) {
                out.shieldArc = s == null ? sh.getArc() : s.getShieldArcBonus().computeEffective(sh.getArc());
                out.shieldUpkeep = s == null ? sh.getUpkeepCost() : eff(s.getShieldUpkeepMult(), sh.getUpkeepCost());
                float eff = sh.getFluxPerDamageAbsorbed();
                out.shieldEfficiency = s == null ? eff : eff(s.getShieldDamageTakenMult(), s.getShieldAbsorptionMult().getModifiedValue() * eff);
            } else if (sh != null && out.defense == ShieldType.PHASE) {
                out.phaseCost = s == null ? sh.getPhaseCost() : s.getPhaseCloakActivationCostBonus().computeEffective(sh.getPhaseCost());
                out.phaseUpkeep = s == null ? sh.getPhaseUpkeep() : s.getPhaseCloakUpkeepCostBonus().computeEffective(sh.getPhaseUpkeep());
            }
        } catch (Throwable t) { /* no defense shown */ }
        // The weapons: those chosen and those built into the hull.
        Map<String, String> all = new java.util.LinkedHashMap<String, String>(weapons);
        try {
            for (Map.Entry<String, String> e : hull.getBuiltInWeapons().entrySet()) if (!all.containsKey(e.getKey())) all.put(e.getKey(), e.getValue());
        } catch (Throwable t) { /* the chosen ones */ }
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            try {
                if (hull.getWeaponSlotAPI(e.getKey()) != null && (hull.getWeaponSlotAPI(e.getKey()).isDecorative() || hull.getWeaponSlotAPI(e.getKey()).isSystemSlot())) continue;
                WeaponSpecAPI w = Global.getSettings().getWeaponSpec(e.getValue());
                WeaponAPI.DerivedWeaponStatsAPI d = w.getDerivedStats();
                if (d == null) continue;
                float flux = d.getFluxPerSecond(), dps = d.getSustainedDps() > 0f ? d.getSustainedDps() : d.getDps();
                if (s != null) {
                    switch (w.getType()) {
                        case BALLISTIC: flux = s.getBallisticWeaponFluxCostMod().computeEffective(flux); break;
                        case ENERGY: flux = s.getEnergyWeaponFluxCostMod().computeEffective(flux); break;
                        case MISSILE: flux = s.getMissileWeaponFluxCostMod().computeEffective(flux); break;
                        default: break;
                    }
                    if (w.isBeam()) flux *= s.getBeamWeaponFluxCostMult().getModifiedValue();
                }
                if (w.isNoDPSInTooltip()) dps = 0f;
                out.weaponFlux += flux;
                out.dps += dps;
                out.perSlot.put(e.getKey(), new float[] { flux, dps });
            } catch (Throwable t) { /* not counted */ }
        }
        return out;
    }

    /** What a stat makes of a base value, as the game works it out: percent, then flat, then multiplied. */
    private static float eff(com.fs.starfarer.api.combat.MutableStat m, float base) {
        return (base + base * m.getPercentMod() / 100f + m.getFlatMod()) * m.getMult();
    }

    /** The groups the game would make for this loadout, or null if it can't be worked out here. */
    static List<wingman.net.RefitData.Group> autoGroups(String hullId, Map<String, String> weapons) {
        try {
            ShipHullSpecAPI hull = Global.getSettings().getHullSpec(hullId);
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(hullId + "_wingman_groups", hull);
            for (Map.Entry<String, String> e : weapons.entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                try { v.addWeapon(e.getKey(), e.getValue()); } catch (Throwable t) { /* left out */ }
            }
            v.autoGenerateWeaponGroups();
            List<wingman.net.RefitData.Group> out = new java.util.ArrayList<wingman.net.RefitData.Group>();
            for (com.fs.starfarer.api.loading.WeaponGroupSpec g : v.getWeaponGroups()) {
                wingman.net.RefitData.Group rg = new wingman.net.RefitData.Group();
                rg.alternating = g.getType() == com.fs.starfarer.api.loading.WeaponGroupType.ALTERNATING;
                rg.autofire = g.isAutofireOnByDefault();
                rg.slots.addAll(g.getSlots());
                out.add(rg);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
