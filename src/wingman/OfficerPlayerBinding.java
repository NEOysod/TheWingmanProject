package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;

/** Host-owned, save-persistent officer identity; only campaign code moves captains. */
final class OfficerPlayerBinding {
    static final String TAG_PREFIX = "wingman_player:";

    static String identity(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    static String boundPlayer(PersonAPI person) {
        String found = null;
        for (String tag : person.getTags()) {
            if (!tag.startsWith(TAG_PREFIX)) continue;
            String name = tag.substring(TAG_PREFIX.length());
            if (name.isEmpty() || found != null) return ""; // conflicting tags: never guess
            found = name;
        }
        return found;
    }

    private static boolean eligible(PersonAPI person) {
        return person != null && !person.isPlayer() && !person.isDefault() && !person.isAICore()
                && !com.fs.starfarer.api.util.Misc.isUnremovable(person);
    }

    /** A linked officer carries Officer Potential: granted when linked, and restored if it was lost. */
    private static void grant(PersonAPI person) {
        if (!person.getStats().hasSkill(OfficerPotential.ID)) person.getStats().setSkillLevel(OfficerPotential.ID, 1f);
    }

    private static List<PersonAPI> officers(CampaignFleetAPI fleet) {
        List<PersonAPI> result = new ArrayList<PersonAPI>();
        for (OfficerDataAPI data : fleet.getFleetData().getOfficersCopy()) result.add(data.getPerson());
        return result;
    }

    private static Map<String, HostSession.Player> uniquePlayers(List<HostSession.Player> players) {
        Map<String, HostSession.Player> result = new HashMap<String, HostSession.Player>();
        Set<String> duplicates = new HashSet<String>();
        for (HostSession.Player player : players) {
            String name = identity(player.name);
            if (name.isEmpty()) continue;
            if (result.containsKey(name)) duplicates.add(name);
            result.put(name, player);
        }
        for (String name : duplicates) result.remove(name);
        return result;
    }

    /** Binds an eligible, unbound roster officer to a player who has no officer yet; never steals or rebinds. */
    private static void bind(String player, PersonAPI captain, List<PersonAPI> officers) {
        if (!officers.contains(captain) || !eligible(captain) || boundPlayer(captain) != null) return;
        for (PersonAPI officer : officers) {
            if (player.equals(boundPlayer(officer))) return;
        }
        captain.addTag(TAG_PREFIX + player);
        grant(captain);
    }

    /** A binding acquired in combat, from an actual remote-controlled campaign ship. */
    static void observe(CampaignFleetAPI fleet, List<HostSession.Player> players, CombatEngineAPI engine) {
        if (fleet == null) return;
        List<PersonAPI> officers = officers(fleet);
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        for (Map.Entry<String, HostSession.Player> entry : uniquePlayers(players).entrySet()) {
            Object controlled = entry.getValue().ship;
            if (!(controlled instanceof ShipAPI)) continue;
            ShipAPI ship = (ShipAPI) controlled;
            if (ship.getOwner() != 0 || !engine.isEntityInPlay(ship) || !ship.isAlive()) continue;
            FleetMemberAPI member = ship.getFleetMember();
            if (member == null || !members.contains(member) || member == fleet.getFlagship()) continue;
            if (member.getCaptain() != ship.getCaptain()) continue;
            bind(entry.getKey(), member.getCaptain(), officers);
        }
    }

    /**
     * Called after lobby pick validation, before roster construction, never during combat. A player without an
     * officer binds the skilled captain of the ship they picked; a bound officer follows the player's pick.
     */
    static void follow(CampaignFleetAPI fleet, List<HostSession.Player> players, WingmanConfig cfg) {
        if (fleet == null) return;
        List<PersonAPI> officers = officers(fleet);
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        Map<String, FleetMemberAPI> targets = new HashMap<String, FleetMemberAPI>();
        Set<FleetMemberAPI> duplicateTargets = new HashSet<FleetMemberAPI>();
        Set<FleetMemberAPI> seen = new HashSet<FleetMemberAPI>();
        for (Map.Entry<String, HostSession.Player> entry : uniquePlayers(players).entrySet()) {
            String reserved = WingmanAPI.reservedShip(entry.getValue().name);
            for (FleetMemberAPI member : members) {
                if (!member.getId().equals(reserved)) continue;
                if (!seen.add(member)) duplicateTargets.add(member);
                targets.put(entry.getKey(), member);
            }
        }
        boolean changed = false;
        for (Map.Entry<String, FleetMemberAPI> entry : targets.entrySet()) {
            FleetMemberAPI target = entry.getValue();
            if (duplicateTargets.contains(target) || CampaignLobby.refusal(target, fleet.getFlagship(), cfg) != null) continue;
            PersonAPI bound = null;
            boolean ambiguous = false;
            for (PersonAPI officer : officers) {
                if (!entry.getKey().equals(boundPlayer(officer))) continue;
                if (bound != null) { ambiguous = true; break; }
                bound = officer;
            }
            if (bound == null) { bind(entry.getKey(), target.getCaptain(), officers); continue; } // already aboard: nothing to move
            if (ambiguous || !eligible(bound)) continue;
            grant(bound);
            FleetMemberAPI source = null;
            int assignments = 0;
            for (FleetMemberAPI member : members) {
                if (member.getCaptain() == bound) { source = member; assignments++; }
            }
            PersonAPI displaced = target.getCaptain();
            if (assignments > 1 || source == target || (source != null && source == fleet.getFlagship())
                    || (displaced != null && (displaced.isPlayer() || displaced.isAICore()
                            || com.fs.starfarer.api.util.Misc.isUnremovable(displaced)))) continue;
            // Swap directly, preserving both officer objects, their XP, tags, and roster membership.
            if (source != null) source.setCaptain(displaced);
            target.setCaptain(bound);
            changed = true;
        }
        if (changed) fleet.getFleetData().setSyncNeeded();
    }
}
