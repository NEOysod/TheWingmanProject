package com.fs.starfarer.title;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;

/** Stands in for the game's wrapper that runs each screen-wide plugin's frame. */
public class PluginRunner {
    public static void run(List<EveryFrameCombatPlugin> plugins, float amount) {
        List events = new ArrayList();
        for (EveryFrameCombatPlugin p : plugins) p.advance(amount, events);
    }
}
