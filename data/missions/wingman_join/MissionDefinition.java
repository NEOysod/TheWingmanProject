package data.missions.wingman_join;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

import wingman.WingmanClientPlugin;
import wingman.JoinSetup;

public class MissionDefinition implements MissionDefinitionPlugin {

    public void defineMission(MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "WING", FleetGoal.ATTACK, false);
        api.initFleet(FleetSide.ENEMY, "LINK", FleetGoal.ATTACK, true);

        api.setFleetTagline(FleetSide.PLAYER, "Your stand-in ship (invisible once connected)");
        api.setFleetTagline(FleetSide.ENEMY, "The host's battle");

        // The map can only be sized now, so ask the host what battlefield it is on.
        JoinSetup.Info info = JoinSetup.fetch();
        api.addBriefingItem(info.status);
        api.addBriefingItem("Deploy the stand-in ship, then pick your ship in the ready check (Up/Down, F) or use Transfer Command.");
        api.addBriefingItem("The host's address and your name are set in the CO-OP panel on the title screen.");

        // Stand-ins only: both are taken off the field as soon as the battle starts.
        api.addToFleet(FleetSide.PLAYER, "lasher_CS", FleetMemberType.SHIP, "Uplink", true);
        api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);

        // The host's map size if we could get it; otherwise larger than any normal battle map.
        api.initMap(-info.width / 2f, info.width / 2f, -info.height / 2f, info.height / 2f);
        // The host's backdrop: image, hyperspace animation, glow, and the planet behind the battle.
        if (info.background != null && info.background.length() > 0) {
            api.setBackgroundSpriteName(info.background);
        }
        api.setHyperspaceMode(info.hyperspace);
        if (info.hasGlow && info.glow != null) {
            api.setBackgroundGlowColor(info.glow);
        }
        if (info.hasPlanet && info.planetType != null && info.planetType.length() > 0) {
            api.setPlanetBgSize(info.planetBgWidth, info.planetBgHeight);
            api.addPlanet(0f, info.planetYOffset, info.planetRadius, info.planetType, 0f, true);
        }

        // The host's own objectives, of whatever kind (a mod's too), exactly where the host has them. Their
        // own scripts then run here as on the host. Objectives can only be made now, before the battle starts.
        // A kind whose script can't run here is made as itself with a do-nothing script (see JoinSetup.kindToMake).
        JoinSetup.standIns.clear();
        // Mods' scripts that look at the campaign find a copy of the host's (see CampaignStandIn).
        JoinSetup.prepareCampaign();
        for (int i = 0; i < info.objectiveTypes.size(); i++) {
            try {
                api.addObjective(((Float) info.objectiveX.get(i)).floatValue(), ((Float) info.objectiveY.get(i)).floatValue(),
                        JoinSetup.kindToMake((String) info.objectiveTypes.get(i)));
            } catch (Throwable t) {
                // a kind this game doesn't know (the mod check should have caught it): a spare stands in if it can
            }
        }

        // Spare objectives of the game's own kinds, for when the host's list wasn't known in time (or the host
        // runs an older version). The plugin parks the ones not needed off the map.
        String[] kinds = new String[] { "nav_buoy", "sensor_array", "comm_relay" };
        for (int k = 0; k < kinds.length; k++) {
            for (int i = 0; i < 6; i++) {
                api.addObjective(-2000f + 200f * (k * 6 + i), 0f, kinds[k]);
            }
        }

        api.addPlugin(new WingmanClientPlugin());
    }
}
