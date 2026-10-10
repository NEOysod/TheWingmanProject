package wingman;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.net.BattleInfo;

/**
 * A stand-in campaign on a joiner's PC, for mods' battle scripts to look at.
 *
 * A joiner's battle starts from the title screen, where the game has a sector but no player fleet, no player
 * cargo and none of the campaign's flags. Most mods never look; some do (an objective that counts the player's
 * AI cores, a hullmod that checks the player's skills or a campaign flag), and crash when they find nothing,
 * often while the battle is being set up, where nothing of ours can catch it. So, while the join mission is
 * set up, a player fleet is made from what the host sent: its cargo (commodities) and credits, a commander
 * with the player's name, level and skills, and the player's story points; the campaign's simple flags are
 * set on the sector's memory. The fleet is put in a place of its own (an empty star system, or hyperspace if
 * the host fights there), since scripts ask the player fleet where it is and crash on "nowhere" (Arma
 * Armatura's strikecraft hullmod looks for star coronas around it). The copies of ships are also put in stand-in fleets, one per side, so a script
 * that asks a ship for its fleet gets one.
 *
 * None of it changes the battle: the host's game still decides everything, and the stand-in only answers
 * questions. A real campaign is never touched: if this PC has a player fleet already, nothing is installed.
 * It is all taken away again when the joiner leaves the battle.
 */
public final class CampaignStandIn {
    private CampaignStandIn() {}

    private static CampaignFleetAPI player, enemy;
    private static boolean installed;
    private static final List<String> flagsSet = new ArrayList<String>();
    private static int storyPointsBefore = -1;
    private static boolean broken;
    /** Where the stand-in fleet is, and the empty star system made for it (null if it is in hyperspace). */
    private static LocationAPI place;
    private static StarSystemAPI madeSystem;
    private static long installedAt;

    /** Put the host's campaign in place. Called while the join mission is set up. */
    static synchronized void install(BattleInfo b) {
        SectorAPI sector = Global.getSector();
        if (sector == null || broken) return;
        if (installed) remove(); // an earlier battle's, left behind
        if (sector.getPlayerFleet() != null) return; // a real campaign: leave it alone
        if (!b.hasCampaign) return; // the host is not in a campaign battle either
        try {
            CampaignFleetAPI f = Global.getFactory().createEmptyFleet("player", "Wingman stand-in", true);
            try {
                for (int i = 0; i < b.cargoIds.size(); i++) f.getCargo().addCommodity(b.cargoIds.get(i), b.cargoAmounts.get(i));
                f.getCargo().getCredits().set(b.credits);
            } catch (Throwable t) { /* an empty hold then */ }
            try {
                PersonAPI me = Global.getFactory().createPerson();
                me.setName(new FullName(b.playerFirst, b.playerLast, FullName.Gender.ANY));
                if (!b.playerPortrait.isEmpty()) me.setPortraitSprite(b.playerPortrait);
                me.getStats().setLevel(b.playerLevel);
                for (int i = 0; i < b.skillIds.size(); i++) {
                    try { me.getStats().setSkillLevel(b.skillIds.get(i), b.skillLevels.get(i)); } catch (Throwable t) { /* a skill this game lacks */ }
                }
                f.setCommander(me);
            } catch (Throwable t) { /* a fleet without a named commander */ }
            sector.setPlayerFleet(f);
            player = f;
            placeFleet(sector, f, b.hyperspace);
            installed = true;
            installedAt = System.currentTimeMillis();
            try {
                if (sector.getPlayerStats() != null) {
                    storyPointsBefore = sector.getPlayerStats().getStoryPoints();
                    sector.getPlayerStats().setStoryPoints(b.storyPoints);
                }
            } catch (Throwable t) { /* no story points then */ }
            try {
                MemoryAPI mem = sector.getMemoryWithoutUpdate();
                for (int i = 0; i < b.flagKeys.size(); i++) {
                    String key = b.flagKeys.get(i);
                    if (mem.contains(key)) continue;
                    mem.set(key, b.flagValue(i));
                    flagsSet.add(key);
                }
            } catch (Throwable t) { /* no flags then */ }
            Global.getLogger(CampaignStandIn.class).info("Wingman: stand-in campaign set up for mods' scripts: "
                    + b.cargoIds.size() + " kinds of cargo, " + b.skillIds.size() + " skills, " + flagsSet.size() + " flags");
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(CampaignStandIn.class).warn("Wingman: the stand-in campaign can't be made on this PC; mods' scripts will find none", t);
            remove();
        }
    }

    /** Put the stand-in fleet somewhere: hyperspace if the host's battle is there, else an empty star system. */
    private static void placeFleet(SectorAPI sector, CampaignFleetAPI f, boolean hyperspace) {
        try {
            LocationAPI at = null;
            if (hyperspace) at = sector.getHyperspace();
            if (at == null) {
                madeSystem = sector.createStarSystem("Wingman stand-in");
                at = madeSystem;
            }
            at.addEntity(f);
            f.setLocation(0f, 0f);
            place = at;
        } catch (Throwable t) {
            Global.getLogger(CampaignStandIn.class).warn("Wingman: the stand-in fleet could not be given a place; scripts that ask where "
                    + "the player fleet is may fail", t);
        }
    }

    /** Whether the stand-in is in place now (the join mission asks, to decide which objectives it can make). */
    static synchronized boolean isInstalled() { return installed; }

    /** The stand-in fleet a copy of a ship on this side belongs to; null if there is none. */
    static synchronized CampaignFleetAPI fleetFor(int owner) {
        if (!installed || broken) return null;
        if (owner == 0) return player;
        if (owner != 1) return null;
        if (enemy == null) {
            try { enemy = Global.getFactory().createEmptyFleet("independent", "Wingman stand-in", true); } catch (Throwable t) { return null; }
        }
        return enemy;
    }

    /** Put a copy's fleet member in its side's stand-in fleet, so scripts that ask for its fleet get one. */
    static void enlist(FleetMemberAPI member, int owner) {
        CampaignFleetAPI f = fleetFor(owner);
        if (f == null || member == null) return;
        try {
            if (member.getFleetData() == null) f.getFleetData().addFleetMember(member);
        } catch (Throwable t) { /* without a fleet, as before */ }
    }

    /** On the title screen: if a stand-in is still in place (the battle was left another way), take it away. */
    static void removeIfLeft() {
        // The join mission is set up on the title screen a moment before its battle starts (and the mission list
        // sets it up just to show it): only a stand-in older than that is one left behind.
        if (installed && System.currentTimeMillis() - installedAt > 15000) remove();
    }

    /** Take it all away again (the joiner left the battle). */
    static synchronized void remove() {
        SectorAPI sector = Global.getSector();
        try {
            if (sector != null && installed && sector.getPlayerFleet() == player) sector.setPlayerFleet(null);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null && storyPointsBefore >= 0 && sector.getPlayerStats() != null) sector.getPlayerStats().setStoryPoints(storyPointsBefore);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null) for (String key : flagsSet) sector.getMemoryWithoutUpdate().unset(key);
        } catch (Throwable t) { /* gone with the sector */ }
        flagsSet.clear();
        try {
            if (place != null && player != null) place.removeEntity(player);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null && madeSystem != null) sector.removeStarSystem(madeSystem);
        } catch (Throwable t) { /* gone with the sector */ }
        place = null;
        madeSystem = null;
        storyPointsBefore = -1;
        player = null;
        enemy = null;
        installed = false;
    }
}
