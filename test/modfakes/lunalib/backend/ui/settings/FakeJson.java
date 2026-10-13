package lunalib.backend.ui.settings;

/** Test stand-in for the JSON object LunaLib keeps a mod's values in (LazyLib's, a JSONObject). */
public class FakeJson extends org.json.JSONObject {
    public final java.util.Map<String, Object> m = new java.util.HashMap<String, Object>();
    @Override public Object opt(String k) { return m.get(k); }
    @Override public org.json.JSONObject put(String k, Object v) { m.put(k, v); return this; }
    @Override public Object remove(String k) { return m.remove(k); }
}
