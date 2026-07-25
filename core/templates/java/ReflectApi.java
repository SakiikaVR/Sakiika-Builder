package __PKG__;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The generic gateway: call <em>any</em> Java or Android API from JavaScript.
 *
 * <p>The curated modules cover the common ground with friendly shapes. This one
 * covers everything else — if it exists in the framework, it is reachable here.
 *
 * <p>Java objects that cannot be turned into JSON are kept in a handle table and
 * referenced from JS as {@code {"__ref": 7}}. Pass a handle straight back in as
 * an argument or as the receiver of another call. Release with
 * {@code reflect.release({ref})}, or {@code reflect.releaseAll()}.
 *
 * <pre>
 * // Vibrate through the raw system service:
 * const v = await Android.reflect.service({name: "vibrator"});
 * await Android.reflect.call({ref: v, method: "vibrate",
 *                             args: [{type: "long", value: 50}]});
 * </pre>
 */
public class ReflectApi extends ApiModule {

    private final Map<Integer, Object> handles = new ConcurrentHashMap<>();
    private final AtomicInteger nextHandle = new AtomicInteger(1);

    public ReflectApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "reflect";
    }

    @Override
    public String[] methods() {
        return new String[]{"call", "staticCall", "new", "get", "getStatic", "set", "setStatic",
                "service", "context", "activity", "describe", "classOf", "instanceOf",
                "release", "releaseAll", "handles", "arrayOf", "toStringOf", "enumConstants"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        if (!Cfg.REFLECTION) {
            throw BridgeError.disabled("リフレクション");
        }
        switch (method) {
            case "call":
                return callInstance(a);
            case "staticCall":
                return callStatic(a);
            case "new":
                return construct(a);
            case "get":
                return readField(a, false);
            case "getStatic":
                return readField(a, true);
            case "set":
                return writeField(a, false);
            case "setStatic":
                return writeField(a, true);
            case "service":
                return wrap(systemService(Jsonx.need(a, "name")));
            case "context":
                return wrap(act.getApplicationContext());
            case "activity":
                return wrap(act);
            case "describe":
                return describe(a);
            case "classOf":
                return resolveTarget(a).getClass().getName();
            case "instanceOf":
                return classFor(Jsonx.need(a, "class")).isInstance(resolveTarget(a));
            case "release":
                handles.remove(Jsonx.i(a, "ref", -1));
                return true;
            case "releaseAll": {
                int n = handles.size();
                handles.clear();
                return Jsonx.obj("released", n);
            }
            case "handles": {
                JSONArray out = new JSONArray();
                for (Map.Entry<Integer, Object> e : handles.entrySet()) {
                    out.put(Jsonx.obj("ref", e.getKey(),
                            "class", e.getValue() == null ? "null"
                                    : e.getValue().getClass().getName()));
                }
                return out;
            }
            case "arrayOf":
                return wrap(buildArray(a));
            case "toStringOf":
                return String.valueOf(resolveTarget(a));
            case "enumConstants":
                return enumConstants(Jsonx.need(a, "class"));
            default:
                throw unknown(method);
        }
    }

    // -------------------------------------------------------------- handles

    private Object wrap(Object value) {
        Object simple = simplify(value);
        if (simple != NOT_SIMPLE) {
            return simple;
        }
        int id = nextHandle.getAndIncrement();
        handles.put(id, value);
        JSONObject ref = new JSONObject();
        try {
            ref.put("__ref", id);
            ref.put("class", value.getClass().getName());
            ref.put("toString", String.valueOf(value));
        } catch (Throwable ignored) {
        }
        return ref;
    }

    private static final Object NOT_SIMPLE = new Object();

    /** Values that survive as JSON are returned by value, not as a handle. */
    private static Object simplify(Object value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number
                || value instanceof Character || value instanceof CharSequence) {
            return Jsonx.wrap(value);
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        return NOT_SIMPLE;
    }

    private Object deref(Object raw) throws BridgeError {
        if (raw instanceof JSONObject && ((JSONObject) raw).has("__ref")) {
            int id = ((JSONObject) raw).optInt("__ref", -1);
            if (!handles.containsKey(id)) {
                throw new BridgeError("stale_ref",
                        "ハンドル " + id + " は解放済みか存在しません");
            }
            return handles.get(id);
        }
        return raw;
    }

    /** The receiver: an object handle, or a class name for static work. */
    private Object resolveTarget(JSONObject a) throws Exception {
        if (a.has("ref")) {
            Object raw = a.get("ref");
            if (raw instanceof Number) {
                int id = ((Number) raw).intValue();
                if (!handles.containsKey(id)) {
                    throw new BridgeError("stale_ref", "ハンドル " + id + " は解放済みです");
                }
                return handles.get(id);
            }
            return deref(raw);
        }
        throw new BridgeError("ref（オブジェクトハンドル）が必要です");
    }

    // -------------------------------------------------------------- classes

    private static Class<?> classFor(String name) throws BridgeError {
        Class<?> primitive = primitiveFor(name);
        if (primitive != null) {
            return primitive;
        }
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            // A bare name is a common slip; point at the usual suspects.
            if (!name.contains(".")) {
                for (String pkg : new String[]{"java.lang.", "java.util.", "android.os.",
                        "android.content.", "android.view.", "android.widget."}) {
                    try {
                        return Class.forName(pkg + name);
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            throw new BridgeError("no_class", "クラスが見つかりません: " + name);
        }
    }

    private static Class<?> primitiveFor(String name) {
        switch (name) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "void":
                return void.class;
            default:
                return null;
        }
    }

    // ----------------------------------------------------------- arguments

    private static final class Arg {
        final Object value;
        /** Declared type when the caller pinned one, else null (inferred). */
        final Class<?> type;

        Arg(Object value, Class<?> type) {
            this.value = value;
            this.type = type;
        }
    }

    private Arg[] parseArgs(JSONObject a) throws Exception {
        JSONArray raw = Jsonx.arr(a, "args");
        if (raw == null) {
            return new Arg[0];
        }
        Arg[] out = new Arg[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            out[i] = parseArg(raw.opt(i));
        }
        return out;
    }

    private Arg parseArg(Object raw) throws Exception {
        if (raw == null || raw == JSONObject.NULL) {
            return new Arg(null, null);
        }
        if (raw instanceof JSONObject) {
            JSONObject o = (JSONObject) raw;
            if (o.has("__ref")) {
                Object target = deref(o);
                return new Arg(target, null);
            }
            if (o.has("type")) {
                String typeName = o.optString("type");
                Object value = o.opt("value");
                Class<?> type = classFor(typeName);
                return new Arg(coerce(value, type), type);
            }
            if (o.has("class") && o.has("literal")) {
                // {"class":"java.lang.Class","literal":"android.view.View"}
                return new Arg(classFor(o.optString("literal")), Class.class);
            }
            throw new BridgeError("引数オブジェクトには type か __ref が必要です: " + o);
        }
        if (raw instanceof JSONArray) {
            // A bare array becomes String[]; use {"type":"int[]"} for anything else.
            JSONArray ja = (JSONArray) raw;
            String[] arr = new String[ja.length()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = ja.optString(i);
            }
            return new Arg(arr, String[].class);
        }
        if (raw instanceof Integer) {
            return new Arg(raw, int.class);
        }
        if (raw instanceof Long) {
            return new Arg(raw, long.class);
        }
        if (raw instanceof Double) {
            return new Arg(raw, double.class);
        }
        if (raw instanceof Boolean) {
            return new Arg(raw, boolean.class);
        }
        return new Arg(String.valueOf(raw), String.class);
    }

    private Object coerce(Object value, Class<?> type) throws Exception {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject && ((JSONObject) value).has("__ref")) {
            return deref(value);
        }
        if (type == String.class || type == CharSequence.class) {
            return String.valueOf(value);
        }
        if (type == int.class || type == Integer.class) {
            return (int) toDouble(value);
        }
        if (type == long.class || type == Long.class) {
            return (long) toDouble(value);
        }
        if (type == float.class || type == Float.class) {
            return (float) toDouble(value);
        }
        if (type == double.class || type == Double.class) {
            return toDouble(value);
        }
        if (type == short.class || type == Short.class) {
            return (short) toDouble(value);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) toDouble(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == char.class || type == Character.class) {
            String s = String.valueOf(value);
            return s.isEmpty() ? '\0' : s.charAt(0);
        }
        if (type == Class.class) {
            return classFor(String.valueOf(value));
        }
        if (type.isArray() && value instanceof JSONArray) {
            JSONArray ja = (JSONArray) value;
            Class<?> component = type.getComponentType();
            Object arr = Array.newInstance(component, ja.length());
            for (int i = 0; i < ja.length(); i++) {
                Array.set(arr, i, coerce(ja.opt(i), component));
            }
            return arr;
        }
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(String.valueOf(value))) {
                    return constant;
                }
            }
            throw new BridgeError(type.getName() + " に定数 " + value + " はありません");
        }
        if (type.isInstance(value)) {
            return value;
        }
        // Last resort: a single-String constructor covers Uri, File, URL and friends.
        try {
            Constructor<?> c = type.getConstructor(String.class);
            return c.newInstance(String.valueOf(value));
        } catch (Throwable ignored) {
        }
        throw new BridgeError("bad_arg",
                value + " を " + type.getName() + " に変換できません");
    }

    private static double toDouble(Object value) throws BridgeError {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BridgeError("bad_arg", "数値として読めません: " + value);
        }
    }

    // ------------------------------------------------------------- dispatch

    /**
     * Overload resolution: exact declared types first, then arity plus
     * assignability. Reflection gives us no better signal than the arguments.
     */
    private Method findMethod(Class<?> owner, String name, Arg[] args, boolean staticOnly)
            throws BridgeError {
        Class<?>[] declared = new Class<?>[args.length];
        boolean allDeclared = true;
        for (int i = 0; i < args.length; i++) {
            declared[i] = args[i].type;
            if (declared[i] == null) {
                allDeclared = false;
            }
        }
        if (allDeclared) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(name, declared);
                    if (!staticOnly || Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        return m;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        Method best = null;
        int bestScore = -1;
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (staticOnly && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) {
                    continue;
                }
                int score = 0;
                boolean fits = true;
                for (int i = 0; i < params.length; i++) {
                    if (args[i].type != null && args[i].type.equals(params[i])) {
                        score += 2;
                    } else if (canPass(args[i], params[i])) {
                        score += 1;
                    } else {
                        fits = false;
                        break;
                    }
                }
                if (fits && score > bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
        }
        if (best == null) {
            throw new BridgeError("no_method", owner.getName() + "." + name
                    + " に引数 " + args.length + " 個のメソッドが見つかりません"
                    + "（reflect.describe で候補を確認できます）");
        }
        best.setAccessible(true);
        return best;
    }

    private static boolean canPass(Arg arg, Class<?> param) {
        if (arg.value == null) {
            return !param.isPrimitive();
        }
        if (param.isPrimitive()) {
            return arg.value instanceof Number || arg.value instanceof Boolean
                    || arg.value instanceof Character;
        }
        return param.isInstance(arg.value)
                || (param == String.class && arg.value instanceof CharSequence);
    }

    private Object[] bind(Arg[] args, Class<?>[] params) throws Exception {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = coerce(args[i].value, params[i]);
        }
        return out;
    }

    private Object callInstance(JSONObject a) throws Exception {
        Object target = resolveTarget(a);
        String name = Jsonx.need(a, "method");
        Arg[] args = parseArgs(a);
        Method m = findMethod(target.getClass(), name, args, false);
        return invokeOn(m, target, args, a);
    }

    private Object callStatic(JSONObject a) throws Exception {
        Class<?> owner = classFor(Jsonx.need(a, "class"));
        String name = Jsonx.need(a, "method");
        Arg[] args = parseArgs(a);
        Method m = findMethod(owner, name, args, true);
        return invokeOn(m, null, args, a);
    }

    private Object invokeOn(final Method m, final Object target, Arg[] args, JSONObject a)
            throws Exception {
        final Object[] values = bind(args, m.getParameterTypes());
        // Many framework calls assert they are on the main thread.
        final boolean onUi = Jsonx.b(a, "onUiThread", false);
        try {
            Object result = onUi
                    ? bridge.onUi(() -> m.invoke(target, values))
                    : m.invoke(target, values);
            if (m.getReturnType() == void.class) {
                return Jsonx.obj("void", true);
            }
            return wrap(result);
        } catch (InvocationTargetException e) {
            throw asBridgeError(e);
        }
    }

    private BridgeError asBridgeError(InvocationTargetException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return new BridgeError("target_exception",
                cause.getClass().getName() + ": " + cause.getMessage());
    }

    private Object construct(JSONObject a) throws Exception {
        Class<?> owner = classFor(Jsonx.need(a, "class"));
        Arg[] args = parseArgs(a);
        Constructor<?> best = null;
        int bestScore = -1;
        for (Constructor<?> c : owner.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length != args.length) {
                continue;
            }
            int score = 0;
            boolean fits = true;
            for (int i = 0; i < params.length; i++) {
                if (args[i].type != null && args[i].type.equals(params[i])) {
                    score += 2;
                } else if (canPass(args[i], params[i])) {
                    score += 1;
                } else {
                    fits = false;
                    break;
                }
            }
            if (fits && score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null) {
            throw new BridgeError("no_constructor", owner.getName()
                    + " に引数 " + args.length + " 個のコンストラクターがありません");
        }
        best.setAccessible(true);
        final Constructor<?> ctor = best;
        final Object[] values = bind(args, ctor.getParameterTypes());
        try {
            Object instance = Jsonx.b(a, "onUiThread", false)
                    ? bridge.onUi(() -> ctor.newInstance(values))
                    : ctor.newInstance(values);
            return wrap(instance);
        } catch (InvocationTargetException e) {
            throw asBridgeError(e);
        }
    }

    private Field findField(Class<?> owner, String name) throws BridgeError {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new BridgeError("no_field", owner.getName() + "." + name + " が見つかりません");
    }

    private Object readField(JSONObject a, boolean isStatic) throws Exception {
        if (isStatic) {
            Class<?> owner = classFor(Jsonx.need(a, "class"));
            return wrap(findField(owner, Jsonx.need(a, "field")).get(null));
        }
        Object target = resolveTarget(a);
        return wrap(findField(target.getClass(), Jsonx.need(a, "field")).get(target));
    }

    private Object writeField(JSONObject a, boolean isStatic) throws Exception {
        Field f;
        Object target;
        if (isStatic) {
            f = findField(classFor(Jsonx.need(a, "class")), Jsonx.need(a, "field"));
            target = null;
        } else {
            target = resolveTarget(a);
            f = findField(target.getClass(), Jsonx.need(a, "field"));
        }
        if (Modifier.isFinal(f.getModifiers())) {
            throw new BridgeError("final_field", f.getName() + " は final です");
        }
        f.set(target, coerce(a.opt("value"), f.getType()));
        return true;
    }

    /** Everything callable on a class or handle — the discovery tool. */
    private JSONObject describe(JSONObject a) throws Exception {
        Class<?> owner;
        if (a.has("class")) {
            owner = classFor(Jsonx.need(a, "class"));
        } else {
            owner = resolveTarget(a).getClass();
        }
        String filter = Jsonx.str(a, "filter", "").toLowerCase(java.util.Locale.ROOT);
        boolean inherited = Jsonx.b(a, "inherited", false);

        JSONArray methods = new JSONArray();
        JSONArray fields = new JSONArray();
        JSONArray constructors = new JSONArray();
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                if (!filter.isEmpty()
                        && !m.getName().toLowerCase(java.util.Locale.ROOT).contains(filter)) {
                    continue;
                }
                methods.put(Jsonx.obj(
                        "name", m.getName(),
                        "params", typeNames(m.getParameterTypes()),
                        "returns", m.getReturnType().getName(),
                        "static", Modifier.isStatic(m.getModifiers()),
                        "declaredBy", c.getName()));
            }
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers())) {
                    continue;
                }
                if (!filter.isEmpty()
                        && !f.getName().toLowerCase(java.util.Locale.ROOT).contains(filter)) {
                    continue;
                }
                fields.put(Jsonx.obj(
                        "name", f.getName(),
                        "type", f.getType().getName(),
                        "static", Modifier.isStatic(f.getModifiers()),
                        "final", Modifier.isFinal(f.getModifiers()),
                        "declaredBy", c.getName()));
            }
            if (c == owner) {
                for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                    if (Modifier.isPublic(ctor.getModifiers())) {
                        constructors.put(Jsonx.obj("params", typeNames(ctor.getParameterTypes())));
                    }
                }
            }
            if (!inherited) {
                break;
            }
        }
        JSONArray interfaces = new JSONArray();
        for (Class<?> i : owner.getInterfaces()) {
            interfaces.put(i.getName());
        }
        return Jsonx.obj(
                "class", owner.getName(),
                "superclass", owner.getSuperclass() == null ? JSONObject.NULL
                        : owner.getSuperclass().getName(),
                "interfaces", interfaces,
                "isEnum", owner.isEnum(),
                "constructors", constructors,
                "methods", methods,
                "fields", fields);
    }

    private static JSONArray typeNames(Class<?>[] types) {
        JSONArray out = new JSONArray();
        for (Class<?> t : types) {
            out.put(t.getName());
        }
        return out;
    }

    private JSONArray enumConstants(String className) throws Exception {
        Class<?> c = classFor(className);
        Object[] constants = c.getEnumConstants();
        JSONArray out = new JSONArray();
        if (constants != null) {
            for (Object o : constants) {
                out.put(Jsonx.obj("name", ((Enum<?>) o).name(),
                        "ordinal", ((Enum<?>) o).ordinal()));
            }
        }
        return out;
    }

    /**
     * Builds a typed Java array from JS, for APIs that demand e.g. {@code int[]}.
     * {@code {component: "int", values: [1,2,3]}}
     */
    private Object buildArray(JSONObject a) throws Exception {
        Class<?> component = classFor(Jsonx.str(a, "component", "java.lang.String"));
        JSONArray values = Jsonx.arr(a, "values");
        int n = values == null ? Jsonx.i(a, "length", 0) : values.length();
        Object arr = Array.newInstance(component, n);
        for (int i = 0; i < n && values != null; i++) {
            Array.set(arr, i, coerce(values.opt(i), component));
        }
        return arr;
    }

    /**
     * A system service by its short name — "vibrator", "window", "power", … the
     * same strings {@code Context.getSystemService(String)} takes.
     */
    private Object systemService(String name) throws Exception {
        Object service = act.getSystemService(name);
        if (service == null) {
            // Maybe they passed a constant name like WINDOW_SERVICE.
            try {
                Field f = Context.class.getField(name);
                Object value = f.get(null);
                if (value instanceof String) {
                    service = act.getSystemService((String) value);
                }
            } catch (Throwable ignored) {
            }
        }
        if (service == null) {
            throw new BridgeError("no_service", "システムサービスがありません: " + name);
        }
        return service;
    }

    @Override
    public void dispose() {
        handles.clear();
    }
}
