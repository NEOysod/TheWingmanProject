package data.missions.wingman_refit;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

import wingman.GameRefit;

public class MissionDefinition implements MissionDefinitionPlugin {

    public void defineMission(MissionDefinitionAPI api) {
        api.initFleet(FleetSide.PLAYER, "WING", FleetGoal.ATTACK, false);
        api.initFleet(FleetSide.ENEMY, "", FleetGoal.ATTACK, true);
        api.setFleetTagline(FleetSide.PLAYER, "Your ship in the host's fleet");
        api.setFleetTagline(FleetSide.ENEMY, "Nobody: this mission is only for refitting");
        api.addBriefingItem(GameRefit.briefing());

        // The ship first, so the game saves its refit as this mission's ship 0 (see GameRefit).
        FleetMemberAPI ship = GameRefit.member();
        if (ship != null) api.addFleetMember(FleetSide.PLAYER, ship);
        else api.addToFleet(FleetSide.PLAYER, "lasher_CS", FleetMemberType.SHIP, "Nothing to refit yet", false);
        api.addToFleet(FleetSide.ENEMY, "lasher_CS", FleetMemberType.SHIP, false);

        api.initMap(-4000f, 4000f, -4000f, 4000f);
        api.addPlugin(new GameRefit.EndAtOnce());
    }
}
