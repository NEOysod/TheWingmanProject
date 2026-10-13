package shipmastery.data;

/** Test stand-in for Ship Mastery System's per-hull record. */
public class SaveData {
    public float points;
    public int level;
    public final java.util.NavigableMap<Integer, String> activeLevels = new java.util.TreeMap<Integer, String>();
    public SaveData(float points, int level) { this.points = points; this.level = level; }
    public boolean activateLevel(int level, String id) { return !id.equals(activeLevels.put(level, id)); }
}
