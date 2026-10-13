package lunalib.lunaSettings;

/** Test stand-in: records which mods were told their settings changed. */
public final class LunaSettings {
    public static final java.util.List<String> told = new java.util.ArrayList<String>();
    public static void reportSettingsChanged(String mod) { told.add(mod); }
}
