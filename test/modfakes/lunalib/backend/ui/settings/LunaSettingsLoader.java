package lunalib.backend.ui.settings;

/** Test stand-in for LunaLib's settings store, laid out as the Kotlin object compiles. */
public final class LunaSettingsLoader {
    public static final LunaSettingsLoader INSTANCE = new LunaSettingsLoader();
    private static boolean hasLoaded = true;
    private static java.util.Map<String, Object> settings = new java.util.HashMap<String, Object>();
    private static java.util.List<LunaSettingsData> data = new java.util.ArrayList<LunaSettingsData>();
    public boolean getHasLoaded() { return hasLoaded; }
    public void load() { hasLoaded = true; }
    public static java.util.Map<String, Object> getSettings() { return settings; }
    public static java.util.List<LunaSettingsData> getSettingsData() { return data; }
}
