package wingman;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;

/**
 * On the host: the carrier a joiner left to fly one of its fighters (UAF's Isenoria Manual Override). Its own AI
 * would decide its carrier systems for it (Isenoria's AI switches Rafflesya Control off with no target in sight,
 * and the carrier's dock script then recalls every Rafflesya, the joiner's included, and holds it on the pad).
 * In single player the player's fighter is exempt from that as "the player's ship"; a joiner's isn't. So the
 * carrier is held as the joiner left it, systems untouched, slowing to a stop, until they come back to it or take
 * another ship.
 */
final class CarrierHoldAI extends PuppetAI {
    private final ShipAPI ship;

    CarrierHoldAI(ShipAPI ship) { this.ship = ship; }

    @Override
    public void advance(float amount) {
        ship.giveCommand(ShipCommand.DECELERATE, null, 0);
    }
}
