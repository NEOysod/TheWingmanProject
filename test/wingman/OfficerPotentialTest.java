package wingman;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.OfficerLevelupPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.plugins.LevelupPlugin;
import com.fs.starfarer.api.util.DynamicStatsAPI;

/** Exercises the real vanilla level-up implementation with strict, read-only API test doubles. */
public final class OfficerPotentialTest {
    private static int playerCap = 15, checks;

    public static void main(String[] args) throws Exception {
        LevelupPlugin playerLevels = proxy(LevelupPlugin.class, (p, m, a) -> {
            if (m.getName().equals("getMaxLevel")) return playerCap;
            throw new AssertionError("Unexpected player-level call: " + m.getName());
        });
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getLevelupPlugin")) return playerLevels;
            if (m.getName().equals("getFloat")) {
                if (a[0].equals("officerMaxLevel")) return 6f;
                if (a[0].equals("officerMaxEliteSkills")) return 2f;
                if (a[0].equals("officerXPRequiredMult")) return 1f;
            }
            throw new AssertionError("Unexpected settings call: " + m.getName());
        }));
        try {
            OfficerLevelupPluginImpl plugin = new OfficerLevelupPluginImpl() {
                @Override
                public int getMaxLevel(PersonAPI person) {
                    final Object[] result = { super.getMaxLevel(person) };
                    wingman.patch.Patches.OfficerPotentialCap.after(proxy(patchlib.api.context.AfterContext.class, (p, m, a) -> {
                        if (m.getName().equals("isMostDerivedCall")) return true;
                        if (m.getName().equals("getInferredReturnValue")) return result[0];
                        if (m.getName().equals("getArg")) return person;
                        if (m.getName().equals("setReturnValue")) { result[0] = a[0]; return null; }
                        throw new AssertionError("Unexpected hook call: " + m.getName());
                    }));
                    return (Integer) result[0];
                }
            };
            OfficerLevelupPluginImpl vanilla = new OfficerLevelupPluginImpl();
            Officer officer = new Officer(), other = new Officer();
            check(plugin.getMaxLevel(null) == 6, "null queries retain base cap");
            check(plugin.getMaxLevel(officer.person) == 6, "ordinary officer unchanged");
            officer.skill = true;
            check(plugin.getMaxLevel(officer.person) == 15, "marked officer reaches player maximum");
            check(plugin.getMaxLevel(other.person) == 6, "other officer unaffected");
            check(officer.memory.isEmpty(), "cap query does not write persistent memory");
            officer.skill = false;
            check(plugin.getMaxLevel(officer.person) == 6, "removing skill restores normal cap immediately");
            officer.memory.put(MemFlags.OFFICER_MAX_LEVEL, 7);
            check(plugin.getMaxLevel(officer.person) == 7, "unmarked special cap preserved");
            officer.skill = true;
            check(plugin.getMaxLevel(officer.person) == 15, "marked special officer raised");
            check(officer.memory.get(MemFlags.OFFICER_MAX_LEVEL) == 7, "special cap not overwritten");
            officer.memory.put(MemFlags.OFFICER_MAX_LEVEL, 30);
            check(plugin.getMaxLevel(officer.person) == 30, "higher existing cap never reduced");
            officer.memory.clear();
            playerCap = 42;
            check(plugin.getMaxLevel(officer.person) == 42, "modded player maximum respected");
            playerCap = 3;
            check(plugin.getMaxLevel(officer.person) == 6, "lower player maximum never reduces cap");
            playerCap = 15;
            officer.player = true;
            check(plugin.getMaxLevel(officer.person) == vanilla.getMaxLevel(officer.person), "player unaffected");
            officer.player = false;
            officer.commanderBonus.modifyFlat("training", 1f);
            other.commanderBonus.modifyFlat("training", 1f);
            check(plugin.getMaxLevel(officer.person) == 15, "commander bonus does not overshoot target");
            check(plugin.getMaxLevel(other.person) == 7, "normal commander training retained");
            check(plugin.getMaxEliteSkills(officer.person) == vanilla.getMaxEliteSkills(officer.person), "elite skill limit unchanged");
            for (int level : new int[] { 1, 6, 15, 30 }) {
                check(plugin.getXPForLevel(level) == vanilla.getXPForLevel(level), "XP curve unchanged at " + level);
            }
            OfficerPotential.Description description = new OfficerPotential.Description();
            check(description.getString().contains("15"), "tooltip shows configured cap");
            playerCap = 42;
            check(description.getString().contains("42"), "tooltip follows modded player cap");
            check(description.getHighlights()[0].equals("42"), "tooltip highlights correct cap");
            if (args.length > 0) registration(java.nio.file.Paths.get(args[0]));
            System.out.println("Officer Potential: " + checks + " checks passed (vanilla level-up and staged skill registration).");
        } finally {
            Global.setSettings(null);
        }
    }

    private static void registration(java.nio.file.Path root) throws Exception {
        org.json.JSONObject config = json(root.resolve("data/config/settings.json"));
        org.json.JSONObject plugins = config.getJSONObject("plugins");
        check(!plugins.has("officerLevelUp"), "cap patch does not replace officer picker");
        check(plugins.has("wingman_host") && plugins.has("wingman_title"), "multiplayer plugins retained");
        check(plugins.has("wingman_officer_binding"), "officer binding retained");
        org.json.JSONObject skill = json(root.resolve("data/characters/skills/" + OfficerPotential.ID + ".skill"));
        check(skill.getString("id").equals(OfficerPotential.ID), "skill ID registered");
        check(skill.getString("scope").equals("NONE"), "trait has personal rather than fleet scope");
        org.json.JSONObject group = skill.getJSONArray("effectGroups").getJSONObject(0);
        check(group.getInt("requiredSkillLevel") == 1, "trait activates at rank one");
        org.json.JSONObject effect = group.getJSONArray("effects").getJSONObject(0);
        check(effect.getString("type").equals("DESCRIPTION"), "description effect registered");
        check(effect.getString("script").equals(OfficerPotential.Description.class.getName()), "description class matches");
        String csv = new String(java.nio.file.Files.readAllBytes(root.resolve("data/characters/skills/skill_data.csv")), java.nio.charset.StandardCharsets.UTF_8);
        String[] lines = csv.trim().split("\\r?\\n");
        String[] header = lines[0].split(",", -1), row = lines[1].split(",", -1);
        check(lines.length == 2 && header.length == row.length, "skill CSV row shape valid");
        check(row[0].equals(OfficerPotential.ID) && row[8].equals("TRUE"), "officer skill listed");
        check(row[11].equals("npc_only"), "trait not added to player skill tree");
        check(row[12].equals("graphics/icons/skills/officer_training.png"), "vanilla icon referenced");
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(root.resolve("jars/Wingman.jar").toFile())) {
            check(jar.getJarEntry("wingman/patch/Patches$OfficerPotentialCap.class") != null, "packaged cap patch");
            for (String name : new String[] { "OfficerPotential", "OfficerPotential$Description" }) {
                check(jar.getJarEntry("wingman/" + name + ".class") != null, "packaged skill class " + name);
            }
        }
    }

    private static org.json.JSONObject json(java.nio.file.Path file) throws Exception {
        return new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class Officer {
        boolean skill, player;
        final Map<String, Integer> memory = new HashMap<String, Integer>();
        final StatBonus commanderBonus = new StatBonus();
        final StatBonus noBonus = new StatBonus();
        final DynamicStatsAPI dynamic = proxy(DynamicStatsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getMod")) {
                return a[0].equals(com.fs.starfarer.api.impl.campaign.ids.Stats.OFFICER_MAX_LEVEL_MOD) ? commanderBonus : noBonus;
            }
            throw new AssertionError("Unexpected dynamic-stat call: " + m.getName());
        });
        final MutableCharacterStatsAPI stats = proxy(MutableCharacterStatsAPI.class, (p, m, a) -> {
            if (m.getName().equals("hasSkill")) return skill && a[0].equals(OfficerPotential.ID);
            if (m.getName().equals("getDynamic")) return dynamic;
            throw new AssertionError("Unexpected stats access or mutation: " + m.getName());
        });
        final MemoryAPI mem = proxy(MemoryAPI.class, (p, m, a) -> {
            if (m.getName().equals("contains")) return memory.containsKey(a[0]);
            if (m.getName().equals("getInt")) return memory.get(a[0]);
            throw new AssertionError("Unexpected memory access or mutation: " + m.getName());
        });
        final PersonAPI person = proxy(PersonAPI.class, (p, m, a) -> {
            if (m.getName().equals("getMemoryWithoutUpdate")) return mem;
            if (m.getName().equals("getFleetCommanderStats") || m.getName().equals("getStats")) return stats;
            if (m.getName().equals("isPlayer")) return player;
            throw new AssertionError("Unexpected person access or mutation: " + m.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
