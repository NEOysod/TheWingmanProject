package com.fs.starfarer.combat.entities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/** Stands in for the game's ship (compiled abstract, then made concrete: only these methods are ever called). */
public abstract class Ship implements ShipAPI {
    public final Map<String, Object> data = new HashMap<String, Object>();
    public ShipAPI parent;
    public String name;
    public static final List<String> log = new ArrayList<String>();
    public Map<String, Object> getCustomData() { return data; }
    public ShipAPI getParentStation() { return parent; }
    public boolean isFighter() { return false; }
    public FighterWingAPI getWing() { return null; }
    /** The ship's frame: its hullmods and systems would run here. */
    public void advance(float amount) {
        ShipAPI p = testmod.ModScript.who(Global.getCombatEngine());
        log.add(name + " sees " + p);
    }
    public String toString() { return name; }
}
