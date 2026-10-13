package testmod;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

/** A mod's battle script asking the campaign: memory flags and the sector's saved data. */
public class Lookups {
    public static Object get(MemoryAPI m, String k) { return m.get(k); }
    public static boolean has(MemoryAPI m, String k) { return m.contains(k); }
    public static float number(MemoryAPI m, String k) { return m.getFloat(k); }
    public static boolean flag(MemoryAPI m, String k) { return m.is(k, true); }
    public static Object saved(SectorAPI s, String k) { return s.getPersistentData().get(k); }
    public static boolean savedHas(SectorAPI s, String k) { return s.getPersistentData().containsKey(k); }
}
