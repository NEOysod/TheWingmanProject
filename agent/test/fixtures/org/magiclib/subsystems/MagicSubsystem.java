package org.magiclib.subsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

public abstract class MagicSubsystem {
    protected final ShipAPI ship;
    public ShipAPI seen;
    public MagicSubsystem(ShipAPI ship) { this.ship = ship; }
    public void advanceInternal(float amount) { seen = testmod.ModScript.who(Global.getCombatEngine()); }
}
