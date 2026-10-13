package wingman;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;

import wingman.net.Out;
import wingman.net.Person;
import wingman.net.Plain;

/**
 * Host side: who a person is, as plain data (see wingman.net.Person), and a memory's plain values within a byte
 * budget. Values that are a mod's own objects are left out.
 */
final class Identity {
    private Identity() {}

    static Person person(PersonAPI p, boolean withSkills) {
        Person o = new Person();
        try { o.id = p.getId() == null ? "" : p.getId(); } catch (Throwable t) { /* no id */ }
        try {
            if (p.getName() != null) {
                o.first = p.getName().getFirst() == null ? "" : p.getName().getFirst();
                o.last = p.getName().getLast() == null ? "" : p.getName().getLast();
            }
        } catch (Throwable t) { /* nameless */ }
        try { o.portrait = p.getPortraitSprite() == null ? "" : p.getPortraitSprite(); } catch (Throwable t) { /* none */ }
        try { o.faction = p.getFaction() == null || p.getFaction().getId() == null ? "" : p.getFaction().getId(); } catch (Throwable t) { /* none */ }
        try { o.rank = p.getRankId() == null ? "" : p.getRankId(); } catch (Throwable t) { /* none */ }
        try { o.post = p.getPostId() == null ? "" : p.getPostId(); } catch (Throwable t) { /* none */ }
        try { o.personality = p.getPersonalityAPI() == null ? "" : p.getPersonalityAPI().getId(); } catch (Throwable t) { /* none */ }
        try { o.aiCore = p.getAICoreId() == null ? "" : p.getAICoreId(); } catch (Throwable t) { /* none */ }
        try { o.level = p.getStats().getLevel(); } catch (Throwable t) { /* 0 */ }
        if (withSkills) {
            try {
                for (MutableCharacterStatsAPI.SkillLevelAPI sk : p.getStats().getSkillsCopy()) {
                    if (sk.getLevel() <= 0f || sk.getSkill() == null || o.skillIds.size() >= Person.MAX_SKILLS) continue;
                    o.skillIds.add(sk.getSkill().getId());
                    o.skillLevels.add(sk.getLevel());
                }
            } catch (Throwable t) { /* as far as it went */ }
        }
        try {
            if (p.getTags() != null) for (String tag : p.getTags()) if (tag != null && o.tags.size() < Person.MAX_TAGS) o.tags.add(tag);
        } catch (Throwable t) { /* none */ }
        try { o.memory = memory(p.getMemoryWithoutUpdate(), Person.MAX_MEMORY_BYTES); } catch (Throwable t) { /* none */ }
        return o;
    }

    /** A memory's plain values (keys up to 200 characters, "$wingman..." left out), as many as fit in maxBytes. */
    static Map<String, Object> memory(MemoryAPI mem, int maxBytes) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (mem == null) return out;
        int left = maxBytes;
        try {
            for (String key : mem.getKeys()) {
                if (key == null || key.length() > 200 || key.startsWith("$wingman")) continue;
                Object v;
                try { v = mem.get(key); } catch (Throwable t) { continue; }
                if (!Plain.isPlain(v)) continue;
                Out o = new Out();
                Plain.write(o, v);
                int size = o.toByteArray().length + key.length() * 2 + 8;
                if (size > left) continue;
                left -= size;
                out.put(key, v);
            }
        } catch (Throwable t) { /* what was read so far */ }
        return out;
    }

    /** A fleet's ships as fleet-wide scripts see them (see FleetInfo.reserves), at most MAX_RESERVES. */
    static java.util.List<wingman.net.FleetInfo.Reserve> roster(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet) {
        java.util.List<wingman.net.FleetInfo.Reserve> out = new java.util.ArrayList<wingman.net.FleetInfo.Reserve>();
        try {
            for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
                if (out.size() >= wingman.net.FleetInfo.MAX_RESERVES) break;
                try {
                    if (m.isFighterWing()) continue;
                    com.fs.starfarer.api.combat.ShipVariantAPI v = m.getVariant();
                    wingman.net.FleetInfo.Reserve r = new wingman.net.FleetInfo.Reserve();
                    r.id = m.getId() == null ? "" : m.getId();
                    r.hullId = v.getHullSpec().getHullId();
                    r.name = m.getShipName() == null ? "" : m.getShipName();
                    r.permaMods.addAll(v.getPermaMods());
                    r.sMods.addAll(v.getSMods());
                    for (String mod : v.getHullMods()) if (!r.permaMods.contains(mod)) r.hullmods.add(mod);
                    r.wings.addAll(v.getFittedWings());
                    r.cr = m.getRepairTracker() == null ? 0.7f : m.getRepairTracker().getCR();
                    r.mothballed = m.isMothballed();
                    r.flagship = m.isFlagship();
                    out.add(r);
                } catch (Throwable t) { /* that one is left out */ }
            }
        } catch (Throwable t) { /* none */ }
        return out;
    }
}
