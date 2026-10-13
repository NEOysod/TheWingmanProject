package lunalib.backend.ui.settings;

/** Test stand-in: one setting's description. */
public final class LunaSettingsData {
    private final String mod, field, type;
    public LunaSettingsData(String mod, String field, String type) { this.mod = mod; this.field = field; this.type = type; }
    public String getModID() { return mod; }
    public String getFieldID() { return field; }
    public String getFieldType() { return type; }
}
