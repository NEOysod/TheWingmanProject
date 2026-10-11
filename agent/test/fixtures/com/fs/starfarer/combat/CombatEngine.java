package com.fs.starfarer.combat;

import java.util.List;

import com.fs.starfarer.combat.entities.Ship;

/** Stands in for the game's engine: runs each ship's frame. */
public class CombatEngine {
    public void advanceShips(List<Ship> ships, float amount) {
        for (Ship s : ships) s.advance(amount);
    }
}
