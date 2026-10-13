package wingman.agent;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The sector's saved data (getPersistentData()) as a joiner's battle scripts see it while lookups are recorded:
 * the same map, every call passed straight through, except that a key asked for and not there is noted (see
 * Shared.misses). Names no game class, so it is shared by every copy of Hooks (public: a copy of Hooks in
 * another class loader is in another runtime package).
 */
public final class RecordingMap implements Map<String, Object> {
    private final Map<String, Object> inner;

    public RecordingMap(Map<String, Object> inner) { this.inner = inner; }

    private void check(Object key) {
        if (Shared.recording && key instanceof String && !inner.containsKey(key)) Shared.miss("saved data: " + key);
    }

    public Object get(Object key) {
        Object v = inner.get(key);
        if (v == null) check(key);
        return v;
    }

    public boolean containsKey(Object key) {
        boolean has = inner.containsKey(key);
        if (!has) check(key);
        return has;
    }

    public int size() { return inner.size(); }
    public boolean isEmpty() { return inner.isEmpty(); }
    public boolean containsValue(Object value) { return inner.containsValue(value); }
    public Object put(String key, Object value) { return inner.put(key, value); }
    public Object remove(Object key) { return inner.remove(key); }
    public void putAll(Map<? extends String, ? extends Object> m) { inner.putAll(m); }
    public void clear() { inner.clear(); }
    public Set<String> keySet() { return inner.keySet(); }
    public Collection<Object> values() { return inner.values(); }
    public Set<Map.Entry<String, Object>> entrySet() { return inner.entrySet(); }
    public boolean equals(Object o) { return inner.equals(o); }
    public int hashCode() { return inner.hashCode(); }
    public String toString() { return inner.toString(); }
}
