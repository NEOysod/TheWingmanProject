package wingman;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.DescriptionSkillEffect;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.characters.PersonAPI;

/** An explicitly granted officer trait; it is not offered during normal skill selection. */
public final class OfficerPotential {
    public static final String ID = "wingman_officer_potential";

    public static int getMaxLevel(PersonAPI person, int original) {
        if (person == null || person.isPlayer() || !person.getStats().hasSkill(ID)) return original;
        return Math.max(original, Global.getSettings().getLevelupPlugin().getMaxLevel());
    }

    public static final int EXTRA_ELITE_SKILLS = 2;

    public static int getMaxEliteSkills(PersonAPI person, int original) {
        if (person == null || person.isPlayer() || !person.getStats().hasSkill(ID)) return original;
        return original + EXTRA_ELITE_SKILLS;
    }

    public static class Description implements DescriptionSkillEffect {
        public String getString() {
            return "Raises this officer's level limit to " + Global.getSettings().getLevelupPlugin().getMaxLevel()
                    + ", the player's maximum level. Preserves a higher existing limit."
                    + " Allows " + EXTRA_ELITE_SKILLS + " more elite skills than the officer could otherwise have."
                    + " Experience and skill points must still be earned normally."
                    + " This officer can also follow a joining player's ship picks.";
        }

        public String[] getHighlights() {
            return new String[] { String.valueOf(Global.getSettings().getLevelupPlugin().getMaxLevel()) };
        }

        public Color[] getHighlightColors() {
            return new Color[] { Misc.getHighlightColor() };
        }

        public Color getTextColor() {
            return null;
        }
    }
}
