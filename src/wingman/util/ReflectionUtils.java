package wingman.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/*
Made by Lukas04, improved with help from Starficz.
Concepts have been learned from lyravega, float, andylizi and their mods.
**/
public final class ReflectionUtils {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle PUBLIC_METHODS;
    private static final MethodHandle DECLARED_METHODS;
    private static final MethodHandle PUBLIC_FIELDS;
    private static final MethodHandle DECLARED_FIELDS;
    private static final MethodHandle DECLARED_CONSTRUCTORS;
    private static final MethodHandle METHOD_NAME;
    private static final MethodHandle METHOD_PARAMETERS;
    private static final MethodHandle METHOD_RETURN;
    private static final MethodHandle METHOD_MODIFIERS;
    private static final MethodHandle METHOD_DECLARING_CLASS;
    private static final MethodHandle CONSTRUCTOR_PARAMETERS;
    private static final MethodHandle FIELD_NAME;
    private static final MethodHandle FIELD_TYPE;
    private static final MethodHandle FIELD_GET;
    private static final MethodHandle FIELD_SET;
    private static final MethodHandle SET_ACCESSIBLE;
    private static final MethodHandle UNREFLECT;
    private static final MethodHandle UNREFLECT_CONSTRUCTOR;

    static {
        try {
            // Reflection types must remain indirect to avoid the game's reflection restriction.
            ClassLoader loader = Class.class.getClassLoader();
            Class<?> method = Class.forName("java.lang.reflect.Method", false, loader);
            Class<?> field = Class.forName("java.lang.reflect.Field", false, loader);
            Class<?> constructor = Class.forName("java.lang.reflect.Constructor", false, loader);
            Class<?> accessible = Class.forName("java.lang.reflect.AccessibleObject", false, loader);
            PUBLIC_METHODS = virtual(Class.class, "getMethods", Class.forName("[Ljava.lang.reflect.Method;", false, loader));
            DECLARED_METHODS = virtual(Class.class, "getDeclaredMethods", Class.forName("[Ljava.lang.reflect.Method;", false, loader));
            PUBLIC_FIELDS = virtual(Class.class, "getFields", Class.forName("[Ljava.lang.reflect.Field;", false, loader));
            DECLARED_FIELDS = virtual(Class.class, "getDeclaredFields", Class.forName("[Ljava.lang.reflect.Field;", false, loader));
            DECLARED_CONSTRUCTORS = virtual(Class.class, "getDeclaredConstructors", Class.forName("[Ljava.lang.reflect.Constructor;", false, loader));
            METHOD_NAME = virtual(method, "getName", String.class);
            METHOD_PARAMETERS = virtual(method, "getParameterTypes", Class[].class);
            METHOD_RETURN = virtual(method, "getReturnType", Class.class);
            METHOD_MODIFIERS = virtual(method, "getModifiers", int.class);
            METHOD_DECLARING_CLASS = virtual(method, "getDeclaringClass", Class.class);
            CONSTRUCTOR_PARAMETERS = virtual(constructor, "getParameterTypes", Class[].class);
            FIELD_NAME = virtual(field, "getName", String.class);
            FIELD_TYPE = virtual(field, "getType", Class.class);
            FIELD_GET = virtual(field, "get", Object.class, Object.class);
            FIELD_SET = virtual(field, "set", void.class, Object.class, Object.class);
            SET_ACCESSIBLE = virtual(accessible, "setAccessible", void.class, boolean.class);
            UNREFLECT = virtual(MethodHandles.Lookup.class, "unreflect", MethodHandle.class, method);
            UNREFLECT_CONSTRUCTOR = virtual(MethodHandles.Lookup.class, "unreflectConstructor", MethodHandle.class, constructor);
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static final ConcurrentMap<MethodKey, ReflectedMethod> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldKey, ReflectedField> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, List<MethodInfo>> METHOD_LISTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<InvocationKey, ReflectedMethod> INVOCATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<MethodKey, MethodHandle> CONSTRUCTORS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Set<String>> FIELD_NAMES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Set<Class<?>>> FIELD_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Set<String>> METHOD_NAMES = new ConcurrentHashMap<>();

    private ReflectionUtils() {}

    private static MethodHandle virtual(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException, IllegalAccessException {
        return LOOKUP.findVirtual(owner, name, MethodType.methodType(result, parameters));
    }

    public static final class ReflectedMethod {
        private final MethodHandle methodHandle;
        private final Class<?> returnType;
        private final Class<?>[] parameterTypes;
        private final MethodHandle invocationHandle;

        private ReflectedMethod(MethodInfo method) throws Throwable {
            MethodHandle handle;
            try {
                handle = (MethodHandle) UNREFLECT.invoke(LOOKUP, method.member);
            } catch (IllegalAccessException inaccessible) {
                SET_ACCESSIBLE.invoke(method.member, true);
                handle = (MethodHandle) UNREFLECT.invoke(LOOKUP, method.member);
            }
            methodHandle = handle;
            returnType = method.returnType;
            parameterTypes = method.parameters.clone();
            MethodHandle spreader = handle.asFixedArity().asSpreader(Object[].class, parameterTypes.length);
            if (method.isStatic()) spreader = MethodHandles.dropArguments(spreader, 0, Object.class);
            invocationHandle = spreader.asType(MethodType.methodType(Object.class, Object.class, Object[].class));
        }

        public MethodHandle getMethodHandle() { return methodHandle; }
        public Class<?> getReturnType() { return returnType; }
        public Class<?>[] getParameterTypes() { return parameterTypes.clone(); }

        public Object invoke(Object receiver, Object... arguments) throws Throwable {
            return (Object) invocationHandle.invokeExact(receiver, arguments);
        }
    }

    public static final class ReflectedField {
        private final Object field;

        private ReflectedField(Object field) throws Throwable {
            SET_ACCESSIBLE.invoke(field, true);
            this.field = field;
        }

        public Object get(Object receiver) throws Throwable { return FIELD_GET.invoke(field, receiver); }
        public void set(Object receiver, Object value) throws Throwable { FIELD_SET.invoke(field, receiver, value); }
    }

    /** A null parameter array is a wildcard; an empty array matches only zero arguments. */
    public static ReflectedMethod getMethod(String name, Class<?> clazz) throws Throwable {
        return getMethod(name, clazz, null, null, (Class<?>[]) null);
    }

    public static ReflectedMethod getMethod(String name, Class<?> clazz, Class<?> returnType,
            Integer parameterCount, Class<?>... parameterTypes) throws Throwable {
        MethodKey key = new MethodKey(clazz, name, returnType, parameterCount, parameterTypes);
        ReflectedMethod cached = METHODS.get(key);
        if (cached != null) return cached;
        MethodInfo selected = null;
        for (MethodInfo method : methods(clazz)) {
            if (name != null && !name.equals(method.name)) continue;
            if (returnType != null && returnType != method.returnType) continue;
            if (parameterCount != null && parameterCount != method.parameters.length) continue;
            if (key.parameters != null && !Arrays.equals(key.parameters, method.parameters)) continue;
            if (selected == null || prefer(method, selected)) selected = method;
        }
        if (selected == null) throw missingMethod(key);
        return cache(METHODS, key, selected.reflected());
    }

    /** Without a depth, searches local declared fields and all public fields. */
    public static ReflectedField getField(String name, Class<?> clazz, Class<?> fieldType) throws Throwable {
        return getField(name, clazz, fieldType, null);
    }

    /** A depth of zero searches only the supplied class; each positive step adds one superclass. */
    public static ReflectedField getField(String name, Class<?> clazz, Class<?> fieldType, Integer recursionLimit)
            throws Throwable {
        if (recursionLimit != null && recursionLimit < 0) throw new IllegalArgumentException("Negative field recursion depth");
        FieldKey key = new FieldKey(clazz, name, fieldType, recursionLimit);
        ReflectedField cached = FIELDS.get(key);
        if (cached != null) return cached;
        Class<?> current = clazz;
        int depth = recursionLimit == null ? 0 : recursionLimit;
        while (current != null) {
            Object field = findField((Object[]) DECLARED_FIELDS.invoke(current), name, fieldType);
            if (field != null) return cache(FIELDS, key, new ReflectedField(field));
            if (recursionLimit == null) {
                field = findField((Object[]) PUBLIC_FIELDS.invoke(current), name, fieldType);
                if (field != null) return cache(FIELDS, key, new ReflectedField(field));
            }
            if (depth-- == 0) break;
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name + " (type=" + fieldType + ", depth=" + recursionLimit + ")");
    }

    private static Object findField(Object[] fields, String name, Class<?> type) throws Throwable {
        for (Object field : fields) {
            if (name != null && !name.equals((String) FIELD_NAME.invoke(field))) continue;
            Class<?> actualType = (Class<?>) FIELD_TYPE.invoke(field);
            if (type != null && !type.isAssignableFrom(actualType)) continue;
            return field;
        }
        return null;
    }

    /** Infers fixed-arity signatures; exact reference matches precede unboxing, widening, and assignable references.
     * Unrelated equally applicable overloads fail rather than depend on reflection enumeration order.
     */
    public static Object invoke(String name, Object receiver, Object... arguments) throws Throwable {
        Objects.requireNonNull(receiver, "receiver");
        return inferredMethod(name, receiver.getClass(), false, arguments).invoke(receiver, arguments);
    }

    public static Object invokeStatic(int parameterCount, String name, Class<?> clazz, Object... arguments)
            throws Throwable {
        if (parameterCount != arguments.length) throw new IllegalArgumentException("Parameter count does not match arguments");
        return inferredMethod(name, clazz, true, arguments).invoke(null, arguments);
    }

    public static Object instantiate(Class<?> clazz, Object... arguments) throws Throwable {
        MethodKey key = new MethodKey(clazz, null, null, arguments.length, argumentTypes(arguments));
        MethodHandle handle = CONSTRUCTORS.get(key);
        if (handle == null) {
            List<Object> candidates = new ArrayList<>();
            List<Class<?>[]> signatures = new ArrayList<>();
            for (Object constructor : (Object[]) DECLARED_CONSTRUCTORS.invoke(clazz)) {
                Class<?>[] parameters = (Class<?>[]) CONSTRUCTOR_PARAMETERS.invoke(constructor);
                if (applicable(parameters, key.parameters)) {
                    candidates.add(constructor);
                    signatures.add(parameters);
                }
            }
            int selected = select(signatures, key.parameters, key.toString());
            Object constructor = candidates.get(selected);
            try {
                handle = (MethodHandle) UNREFLECT_CONSTRUCTOR.invoke(LOOKUP, constructor);
            } catch (IllegalAccessException inaccessible) {
                SET_ACCESSIBLE.invoke(constructor, true);
                handle = (MethodHandle) UNREFLECT_CONSTRUCTOR.invoke(LOOKUP, constructor);
            }
            handle = handle.asFixedArity().asSpreader(Object[].class, arguments.length)
                    .asType(MethodType.methodType(Object.class, Object[].class));
            handle = cache(CONSTRUCTORS, key, handle);
        }
        return (Object) handle.invokeExact(arguments);
    }

    public static Object get(String name, Object receiver, Class<?> type) throws Throwable {
        return getField(name, receiver.getClass(), type).get(receiver);
    }

    public static Object get(String name, Object receiver) throws Throwable { return get(name, receiver, null); }

    public static void set(String name, Object receiver, Object value, Class<?> type) throws Throwable {
        getField(name, receiver.getClass(), type).set(receiver, value);
    }

    public static void set(String name, Object receiver, Object value) throws Throwable { set(name, receiver, value, null); }

    public static boolean hasVariableOfName(String name, Object receiver) throws Throwable {
        Class<?> clazz = receiver.getClass();
        Set<String> names = FIELD_NAMES.get(clazz);
        if (names == null) {
            names = new HashSet<>();
            for (Object field : (Object[]) DECLARED_FIELDS.invoke(clazz)) names.add((String) FIELD_NAME.invoke(field));
            names = cache(FIELD_NAMES, clazz, Collections.unmodifiableSet(names));
        }
        return names.contains(name);
    }

    public static boolean hasVariableOfType(Class<?> type, Object receiver) throws Throwable {
        Class<?> clazz = receiver.getClass();
        Set<Class<?>> types = FIELD_TYPES.get(clazz);
        if (types == null) {
            types = new HashSet<>();
            for (Object field : (Object[]) DECLARED_FIELDS.invoke(clazz)) types.add((Class<?>) FIELD_TYPE.invoke(field));
            types = cache(FIELD_TYPES, clazz, Collections.unmodifiableSet(types));
        }
        return types.contains(type);
    }

    public static boolean hasMethodOfName(String name, Object receiver) throws Throwable {
        Class<?> clazz = receiver.getClass();
        Set<String> names = METHOD_NAMES.get(clazz);
        if (names == null) {
            names = new HashSet<>();
            for (Object method : (Object[]) DECLARED_METHODS.invoke(clazz)) names.add((String) METHOD_NAME.invoke(method));
            names = cache(METHOD_NAMES, clazz, Collections.unmodifiableSet(names));
        }
        return names.contains(name);
    }

    public static Object getFirstDeclaredField(Object receiver) throws Throwable {
        Object[] fields = (Object[]) DECLARED_FIELDS.invoke(receiver.getClass());
        if (fields.length == 0) throw new NoSuchFieldException(receiver.getClass().getName());
        return new ReflectedField(fields[0]).get(receiver);
    }

    private static ReflectedMethod inferredMethod(String name, Class<?> clazz, boolean isStatic, Object[] arguments)
            throws Throwable {
        MethodKey signature = new MethodKey(clazz, name, null, arguments.length, argumentTypes(arguments));
        InvocationKey key = new InvocationKey(signature, isStatic);
        ReflectedMethod cached = INVOCATIONS.get(key);
        if (cached != null) return cached;
        List<MethodInfo> candidates = new ArrayList<>();
        List<Class<?>[]> signatures = new ArrayList<>();
        for (MethodInfo method : methods(clazz)) {
            if (name != null && !name.equals(method.name)) continue;
            if (isStatic != method.isStatic() || !applicable(method.parameters, signature.parameters)) continue;
            int duplicate = -1;
            for (int i = 0; i < signatures.size(); i++) {
                if (Arrays.equals(signatures.get(i), method.parameters)) { duplicate = i; break; }
            }
            if (duplicate >= 0) {
                if (prefer(method, candidates.get(duplicate))) candidates.set(duplicate, method);
            } else {
                candidates.add(method);
                signatures.add(method.parameters);
            }
        }
        int selected = select(signatures, signature.parameters, signature.toString());
        return cache(INVOCATIONS, key, candidates.get(selected).reflected());
    }

    private static List<MethodInfo> methods(Class<?> clazz) throws Throwable {
        List<MethodInfo> cached = METHOD_LISTS.get(clazz);
        if (cached != null) return cached;
        List<MethodInfo> result = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (MethodHandle enumeration : new MethodHandle[] { PUBLIC_METHODS, DECLARED_METHODS }) {
            for (Object method : (Object[]) enumeration.invoke(clazz)) {
                if (seen.add(method)) result.add(new MethodInfo(method));
            }
        }
        return cache(METHOD_LISTS, clazz, Collections.unmodifiableList(result));
    }

    private static boolean prefer(MethodInfo candidate, MethodInfo previous) {
        boolean bridge = (candidate.modifiers & 0x40) != 0;
        boolean previousBridge = (previous.modifiers & 0x40) != 0;
        if (bridge != previousBridge) return !bridge;
        if (!Arrays.equals(candidate.parameters, previous.parameters)) return false;
        if (candidate.returnType != previous.returnType) return previous.returnType.isAssignableFrom(candidate.returnType);
        return candidate.declaringClass != previous.declaringClass
                && previous.declaringClass.isAssignableFrom(candidate.declaringClass);
    }

    private static Class<?>[] argumentTypes(Object[] arguments) {
        Class<?>[] types = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) types[i] = arguments[i] == null ? null : arguments[i].getClass();
        return types;
    }

    private static boolean applicable(Class<?>[] parameters, Class<?>[] arguments) {
        if (parameters.length != arguments.length) return false;
        for (int i = 0; i < parameters.length; i++) if (conversion(parameters[i], arguments[i]) < 0) return false;
        return true;
    }

    private static int conversion(Class<?> parameter, Class<?> argument) {
        if (argument == null) return parameter.isPrimitive() ? -1 : 3;
        if (parameter == argument) return 0;
        if (!parameter.isPrimitive()) return parameter.isAssignableFrom(argument) ? 3 : -1;
        Class<?> primitive = primitive(argument);
        if (primitive == parameter) return 1;
        return primitive != null && widens(primitive, parameter) ? 2 : -1;
    }

    private static Class<?> primitive(Class<?> type) {
        if (type == Boolean.class) return boolean.class;
        if (type == Byte.class) return byte.class;
        if (type == Short.class) return short.class;
        if (type == Character.class) return char.class;
        if (type == Integer.class) return int.class;
        if (type == Long.class) return long.class;
        if (type == Float.class) return float.class;
        if (type == Double.class) return double.class;
        return null;
    }

    private static boolean widens(Class<?> from, Class<?> to) {
        if (from == byte.class) return to == short.class || to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == short.class || from == char.class) return to == int.class || to == long.class || to == float.class || to == double.class;
        if (from == int.class) return to == long.class || to == float.class || to == double.class;
        if (from == long.class) return to == float.class || to == double.class;
        return from == float.class && to == double.class;
    }

    private static boolean moreSpecific(Class<?>[] left, Class<?>[] right, Class<?>[] arguments) {
        boolean strict = false;
        for (int i = 0; i < left.length; i++) {
            if (left[i] == right[i]) continue;
            int a = conversion(left[i], arguments[i]);
            int b = conversion(right[i], arguments[i]);
            if (a > b) return false;
            if (a == b && !(left[i].isPrimitive() && right[i].isPrimitive()
                    ? widens(left[i], right[i]) : right[i].isAssignableFrom(left[i]))) return false;
            strict = true;
        }
        return strict;
    }

    private static int select(List<Class<?>[]> signatures, Class<?>[] arguments, String description)
            throws NoSuchMethodException {
        int selected = -1;
        for (int i = 0; i < signatures.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < signatures.size(); j++) {
                if (i != j && moreSpecific(signatures.get(j), signatures.get(i), arguments)) { dominated = true; break; }
            }
            if (!dominated) {
                if (selected >= 0) throw new NoSuchMethodException("Ambiguous invocation: " + description);
                selected = i;
            }
        }
        if (selected < 0) throw new NoSuchMethodException(description);
        return selected;
    }

    private static NoSuchMethodException missingMethod(MethodKey key) { return new NoSuchMethodException(key.toString()); }

    private static <K, V> V cache(ConcurrentMap<K, V> cache, K key, V value) {
        V previous = cache.putIfAbsent(key, value);
        return previous == null ? value : previous;
    }

    private static final class MethodInfo {
        final Object member;
        final String name;
        final Class<?> returnType;
        final Class<?>[] parameters;
        final Class<?> declaringClass;
        final int modifiers;
        private ReflectedMethod reflected;

        MethodInfo(Object member) throws Throwable {
            this.member = member;
            name = (String) METHOD_NAME.invoke(member);
            returnType = (Class<?>) METHOD_RETURN.invoke(member);
            parameters = (Class<?>[]) METHOD_PARAMETERS.invoke(member);
            declaringClass = (Class<?>) METHOD_DECLARING_CLASS.invoke(member);
            modifiers = (int) METHOD_MODIFIERS.invoke(member);
        }

        boolean isStatic() { return (modifiers & 0x08) != 0; }

        synchronized ReflectedMethod reflected() throws Throwable {
            if (reflected == null) reflected = new ReflectedMethod(this);
            return reflected;
        }
    }

    private static final class MethodKey {
        final Class<?> clazz;
        final String name;
        final Class<?> returnType;
        final Integer parameterCount;
        final Class<?>[] parameters;
        private final int hash;

        MethodKey(Class<?> clazz, String name, Class<?> returnType, Integer parameterCount, Class<?>[] parameters) {
            this.clazz = Objects.requireNonNull(clazz, "clazz");
            this.name = name;
            this.returnType = returnType;
            this.parameterCount = parameterCount;
            this.parameters = parameters == null ? null : parameters.clone();
            int result = clazz.hashCode();
            result = 31 * result + Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(returnType);
            result = 31 * result + Objects.hashCode(parameterCount);
            hash = 31 * result + Arrays.hashCode(this.parameters);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof MethodKey)) return false;
            MethodKey key = (MethodKey) other;
            return clazz == key.clazz && Objects.equals(name, key.name) && returnType == key.returnType
                    && Objects.equals(parameterCount, key.parameterCount) && Arrays.equals(parameters, key.parameters);
        }
        @Override public int hashCode() { return hash; }
        @Override public String toString() { return clazz.getName() + "." + name + Arrays.toString(parameters) + " (arity=" + parameterCount + ", return=" + returnType + ")"; }
    }

    private static final class FieldKey {
        final Class<?> clazz;
        final String name;
        final Class<?> type;
        final Integer depth;

        FieldKey(Class<?> clazz, String name, Class<?> type, Integer depth) {
            this.clazz = Objects.requireNonNull(clazz, "clazz");
            this.name = name;
            this.type = type;
            this.depth = depth;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FieldKey)) return false;
            FieldKey key = (FieldKey) other;
            return clazz == key.clazz && Objects.equals(name, key.name) && type == key.type && Objects.equals(depth, key.depth);
        }
        @Override public int hashCode() {
            int result = 31 * clazz.hashCode() + Objects.hashCode(name);
            result = 31 * result + Objects.hashCode(type);
            return 31 * result + Objects.hashCode(depth);
        }
    }

    private static final class InvocationKey {
        final MethodKey signature;
        final boolean isStatic;

        InvocationKey(MethodKey signature, boolean isStatic) { this.signature = signature; this.isStatic = isStatic; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof InvocationKey)) return false;
            InvocationKey key = (InvocationKey) other;
            return isStatic == key.isStatic && signature.equals(key.signature);
        }
        @Override public int hashCode() { return 31 * signature.hashCode() + (isStatic ? 1 : 0); }
    }
}
