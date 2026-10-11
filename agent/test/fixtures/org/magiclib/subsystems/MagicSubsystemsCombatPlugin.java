package org.magiclib.subsystems;

import java.util.List;

/** Like MagicLib's: one screen-wide plugin advances every ship's subsystems. */
public class MagicSubsystemsCombatPlugin {
    public void advance(List<MagicSubsystem> all, float amount) {
        for (MagicSubsystem s : all) s.advanceInternal(amount);
    }
}
