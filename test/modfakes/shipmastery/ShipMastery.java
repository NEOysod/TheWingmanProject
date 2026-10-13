package shipmastery;

/** Test stand-in for Ship Mastery System's main class: only the members Wingman reaches, recording what happens. */
public class ShipMastery {
    public static class SaveDataTable extends java.util.HashMap<String, shipmastery.data.SaveData> {}
    public static Object table;
    public static boolean worked, random;
    public static void loadMasteryTable() { table = com.fs.starfarer.api.Global.getSector().getPersistentData().get("shipmastery_Mastery"); }
    public static void initMasteries(boolean randomMode) { worked = true; random = randomMode; }
}
