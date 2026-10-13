package shipmastery.plugin;

/** Test stand-in: the remembered campaign that Wingman makes it forget. */
public class ModPlugin {
    private static String lastSaveId = "someone's save";
    public static String lastSaveId() { return lastSaveId; }
}
