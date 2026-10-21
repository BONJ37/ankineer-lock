package stubhooks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Comportement simulé du framework Android pour les tests de fumée sur JVM.
 * Toutes les méthodes du jar Android transformé arrivent ici. Par défaut : valeurs « profondes »
 * (objet vide plutôt que null), émulation des propriétés setX/getX, de la hiérarchie des vues,
 * des préférences, des Handler (file exécutée par le test) et des services système.
 */
public final class Hooks {
    private Hooks() {}

    // ------------------------------------------------------------------ état simulé
    public static final Map<Object, Map<String, Object>> props = new IdentityHashMap<>();
    public static final Map<Object, List<Object>> children = new IdentityHashMap<>();
    public static final List<Runnable> queue = new ArrayList<>();
    public static final List<Object> windows = new ArrayList<>();
    public static final List<Object[]> dialogButtons = new ArrayList<>();    // {methodName, listener}
    public static final List<String> log = new ArrayList<>();
    public static final Map<String, Map<String, Object>> prefs = new HashMap<>();
    public static final Map<String, Object> singletons = new HashMap<>();

    // réglages du scénario
    public static boolean ankiInstalled = true;
    public static boolean a11yEnabled = true;
    public static boolean screenOn = true;
    public static boolean batteryOk = true;
    public static int[] widgetIds = new int[0];
    public static List<Object> accessibilityWindows = new ArrayList<>();
    public static Set<String> foundTexts = new HashSet<>();        // textes « trouvés » par findAccessibilityNodeInfosByText
    public static Set<String> launchablePkgs = new HashSet<>(Arrays.asList(
            "com.ichi2.anki", "com.google.android.youtube", "com.android.chrome", "com.android.vending", "com.whatsapp", "com.android.settings"));
    public static long clockOffset = 0;
    public static float density = 2.75f;
    public static byte[] pickedFile = new byte[]{1, 2, 3};
    public static int archiveVersionCode = 99;
    public static File tmp = new File(System.getProperty("java.io.tmpdir"), "smoke-cache");
    /** Panne simulée : la méthode « owner.name » lève une exception {@code failCount} fois. */
    public static String failOn;
    public static int failCount;

    public static synchronized void reset() {
        props.clear(); children.clear(); queue.clear(); windows.clear(); dialogButtons.clear(); log.clear();
        prefs.clear(); singletons.clear(); accessibilityWindows = new ArrayList<>(); foundTexts.clear();
        ankiInstalled = true; a11yEnabled = true; screenOn = true; widgetIds = new int[0]; clockOffset = 0; failOn = null; failCount = 0; batteryOk = true;
        tmp.mkdirs();
    }

    // ------------------------------------------------------------------ conversions de retour
    public static boolean z(Object o) { return o instanceof Boolean && (Boolean) o; }
    public static byte b(Object o) { return o instanceof Number ? ((Number) o).byteValue() : 0; }
    public static char c(Object o) { return o instanceof Character ? (Character) o : 0; }
    public static short s(Object o) { return o instanceof Number ? ((Number) o).shortValue() : 0; }
    public static int i(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
    public static long j(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    public static float f(Object o) { return o instanceof Number ? ((Number) o).floatValue() : 0; }
    public static double d(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }

    // ------------------------------------------------------------------ utilitaires

    public static Map<String, Object> p(Object o) {
        synchronized (Hooks.class) { return props.computeIfAbsent(o, k -> new HashMap<>()); }
    }

    public static Object prop(Object o, String k) {
        synchronized (Hooks.class) { Map<String, Object> m = props.get(o); return m == null ? null : m.get(k); }
    }

    public static List<Object> kids(Object o) {
        synchronized (Hooks.class) { return children.computeIfAbsent(o, k -> new ArrayList<>()); }
    }

    static Class<?> cls(String internal) {
        try { return Class.forName(internal.replace('/', '.'), false, Hooks.class.getClassLoader()); } catch (Throwable t) { return null; }
    }

    static Class<?> typeOf(String d) {
        switch (d.charAt(0)) {
            case 'Z': return boolean.class; case 'B': return byte.class; case 'C': return char.class; case 'S': return short.class;
            case 'I': return int.class; case 'J': return long.class; case 'F': return float.class; case 'D': return double.class;
            case 'V': return void.class;
            case '[': try { return Class.forName(d.replace('/', '.'), false, Hooks.class.getClassLoader()); } catch (Throwable t) { return Object[].class; }
            default: return cls(d.substring(1, d.length() - 1));
        }
    }

    static String retDesc(String desc) { return desc.substring(desc.indexOf(')') + 1); }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    /** Valeur par défaut « profonde » pour un type de retour. */
    public static Object deflt(Class<?> t) {
        if (t == null || t == void.class) return null;
        if (t.isPrimitive()) return null;          // converti en 0 / false
        if (t == String.class || t == CharSequence.class) return "";
        if (t.isArray()) return Array.newInstance(t.getComponentType(), 0);
        if (t == List.class || t == Collection.class || t == Iterable.class || t == ArrayList.class) return new ArrayList<>();
        if (t == Set.class || t == HashSet.class) return new HashSet<>();
        if (t == Map.class || t == HashMap.class) return new HashMap<>();
        String n = t.getName();
        if (!(n.startsWith("android.") || n.startsWith("com.android.") || n.startsWith("dalvik."))) return null;
        if (t.isInterface()) return proxy(t);
        try {
            java.lang.reflect.Constructor<?> k = t.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object single(String key, Class<?> t) {
        synchronized (Hooks.class) {
            Object o = singletons.get(key);
            if (o == null) { o = deflt(t); singletons.put(key, o); }
            return o;
        }
    }

    public static Object proxy(Class<?> itf) {
        final Object[] self = new Object[1];
        InvocationHandler h = (px, m, args) -> {
            String desc = Type_desc(m);
            Object r = call(px, m.getDeclaringClass().getName().replace('.', '/'), m.getName(), desc, args == null ? new Object[0] : args, true);
            Class<?> rt = m.getReturnType();
            if (r == null && rt.isPrimitive() && rt != void.class) {
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == char.class) return (char) 0;
                if (rt == long.class) return 0L;
                if (rt == float.class) return 0f;
                if (rt == double.class) return 0d;
                if (rt == byte.class) return (byte) 0;
                if (rt == short.class) return (short) 0;
                return 0;
            }
            if (r instanceof Integer && rt == long.class) return ((Integer) r).longValue();
            return r;
        };
        self[0] = Proxy.newProxyInstance(Hooks.class.getClassLoader(), new Class<?>[]{itf}, h);
        return self[0];
    }

    static String Type_desc(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> c : m.getParameterTypes()) sb.append(desc(c));
        return sb.append(')').append(desc(m.getReturnType())).toString();
    }

    static String desc(Class<?> c) {
        if (c == void.class) return "V"; if (c == boolean.class) return "Z"; if (c == byte.class) return "B"; if (c == char.class) return "C";
        if (c == short.class) return "S"; if (c == int.class) return "I"; if (c == long.class) return "J"; if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    static boolean isInstance(Class<?> c, String internal) {
        Class<?> k = cls(internal);
        return k != null && c != null && k.isAssignableFrom(c);
    }

    static boolean is(Object o, String internal) {
        Class<?> k = cls(internal);
        return k != null && o != null && k.isInstance(o);
    }

    public static synchronized void enqueue(Runnable r) { if (r != null) queue.add(r); }

    public static void setField(Object o, String name, Object v) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(o, v);
                    return;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ point d'entrée

    public static Object call(Object self, String owner, String name, String desc, Object[] args) {
        return call(self, owner, name, desc, args, false);
    }

    static synchronized Object call(Object self, String owner, String name, String desc, Object[] args, boolean fromProxy) {
        if (failOn != null && failCount > 0 && failOn.equals(owner + "." + name)) {
            failCount--;
            throw new RuntimeException("panne simulée : " + failOn);
        }
        String rd = retDesc(desc);
        Class<?> rt = typeOf(rd);
        int n = args.length;

        // --- java.lang.Object
        if (name.equals("equals") && n == 1) return self == args[0];
        if (name.equals("hashCode") && n == 0) return System.identityHashCode(self);
        if (name.equals("toString") && n == 0) {
            Object t = prop(self, "Text");
            if (t != null) return t.toString();
            return fromProxy ? "" : (self == null ? owner : self.getClass().getSimpleName());
        }

        // --- constructeurs : on garde les arguments utiles
        if (name.equals("<init>")) {
            if (owner.equals("android/content/Intent")) {
                for (Object a : args) {
                    if (a instanceof String) p(self).put("Action", a);
                    if (a instanceof Class) p(self).put("Component", ((Class<?>) a).getName());
                }
            }
            if (owner.equals("android/widget/LinearLayout$LayoutParams") || owner.equals("android/widget/FrameLayout$LayoutParams")
                    || owner.equals("android/view/ViewGroup$LayoutParams") || owner.equals("android/view/ViewGroup$MarginLayoutParams")) {
                if (n >= 2 && args[0] instanceof Integer && args[1] instanceof Integer) {
                    setField(self, "width", args[0]);
                    setField(self, "height", args[1]);
                }
            }
            return null;
        }

        // --- préférences
        if (name.equals("getSharedPreferences") && n == 2) return prefsProxy((String) args[0]);
        if (name.equals("getDefaultSharedPreferences")) return prefsProxy("default");

        // --- Context
        if (name.equals("getApplicationContext") && n == 0) return is(self, "android/content/Context") ? self : single("app", cls("android/content/Context"));
        if (name.equals("getBaseContext") && n == 0) return self;
        if (name.equals("getPackageName") && n == 0 && is(self, "android/content/Context")) return "com.ankineer.app";
        if (name.equals("getResources") && n == 0) return single("res", cls("android/content/res/Resources"));
        if (name.equals("getDisplayMetrics") && n == 0) {
            Object dm = single("dm", cls("android/util/DisplayMetrics"));
            setField(dm, "density", density);
            setField(dm, "scaledDensity", density);
            setField(dm, "densityDpi", (int) (160 * density));
            setField(dm, "widthPixels", (int) (392 * density));
            setField(dm, "heightPixels", (int) (850 * density));
            return dm;
        }
        if (name.equals("getContentResolver") && n == 0) return single("cr", cls("android/content/ContentResolver"));
        if (name.equals("getPackageManager") && n == 0) return single("pm", cls("android/content/pm/PackageManager"));
        if ((name.equals("getCacheDir") || name.equals("getFilesDir") || name.equals("getExternalCacheDir")) && n == 0) return tmp;
        if (name.equals("getSystemService") && n == 1 && args[0] instanceof String) return service((String) args[0]);
        if (name.equals("checkSelfPermission") || name.equals("checkCallingOrSelfPermission")) return 0;
        if (name.startsWith("startActivit") || name.equals("sendBroadcast") || name.equals("startService")) {
            log.add(name + " " + (n > 0 ? describe(args[0]) : ""));
            return null;
        }
        if (name.equals("runOnUiThread") && n == 1) { enqueue((Runnable) args[0]); return null; }
        if (name.equals("findViewById")) return null;
        if (name.equals("getIntent") && n == 0) {
            Object it = prop(self, "Intent");
            if (it == null) { it = deflt(cls("android/content/Intent")); p(self).put("Intent", it); }
            return it;
        }
        if (name.equals("requestPermissions")) { log.add("requestPermissions " + Arrays.toString((Object[]) args[0])); return null; }
        if (name.equals("recreate")) { log.add("recreate"); return null; }
        if (name.equals("finish")) { log.add("finish"); return null; }

        // --- Handler / View.post / animations
        if ((name.equals("post") || name.equals("postDelayed") || name.equals("postAtTime") || name.equals("postAtFrontOfQueue")) && n >= 1 && args[0] instanceof Runnable) {
            enqueue((Runnable) args[0]);
            return rt == boolean.class ? Boolean.TRUE : null;
        }
        if (name.equals("removeCallbacks") && n >= 1 && args[0] instanceof Runnable) { queue.remove(args[0]); return rt == boolean.class ? Boolean.TRUE : null; }
        if (name.equals("withEndAction") || name.equals("withStartAction")) { enqueue((Runnable) args[0]); return self; }

        // --- temps
        if (owner.equals("android/os/SystemClock")) {
            if (name.equals("elapsedRealtime") || name.equals("uptimeMillis")) return System.nanoTime() / 1000000L + clockOffset;
            if (name.equals("elapsedRealtimeNanos")) return System.nanoTime() + clockOffset * 1000000L;
            return null;
        }

        // --- réglages système
        if (owner.equals("android/provider/Settings$Global") && name.equals("getInt") && n == 3) return args[2];
        if (owner.equals("android/provider/Settings$Secure") && name.equals("getString")) {
            if ("enabled_accessibility_services".equals(args[1])) return a11yEnabled ? "com.ankineer.app/com.ankineer.app.LockService" : null;
            return null;
        }
        if (owner.startsWith("android/provider/Settings") && name.equals("getInt") && n == 3) return args[2];

        // --- gestionnaire de paquets
        if (name.equals("resolveContentProvider")) return ankiInstalled ? deflt(cls("android/content/pm/ProviderInfo")) : null;
        if (name.equals("resolveActivity")) return null;
        if (name.equals("getLaunchIntentForPackage")) {
            if (!launchablePkgs.contains(args[0])) return null;
            Object it = deflt(cls("android/content/Intent"));
            p(it).put("Package", args[0]);
            return it;
        }
        if (name.equals("queryIntentActivities")) {
            List<Object> out = new ArrayList<>();
            boolean home = args[0] != null && p(args[0]).containsKey("cat:android.intent.category.HOME");
            for (String pkg : home ? Collections.singleton("com.google.android.apps.nexuslauncher") : launchablePkgs) {
                Object ri = deflt(cls("android/content/pm/ResolveInfo"));
                Object ai = deflt(cls("android/content/pm/ActivityInfo"));
                setField(ai, "packageName", pkg);
                setField(ai, "name", pkg + ".Main");
                setField(ri, "activityInfo", ai);
                out.add(ri);
            }
            return out;
        }
        if (name.equals("getPackageInfo") && n == 2) {
            Object pi = deflt(cls("android/content/pm/PackageInfo"));
            setField(pi, "packageName", args[0]);
            setField(pi, "versionName", "2.5");
            setField(pi, "versionCode", 12);
            return pi;
        }
        if (name.equals("getPackageArchiveInfo")) {
            Object pi = deflt(cls("android/content/pm/PackageInfo"));
            setField(pi, "packageName", "com.ankineer.app");
            setField(pi, "versionName", "9.9");
            setField(pi, "versionCode", archiveVersionCode);
            return pi;
        }
        if (name.equals("getApplicationLabel") || name.equals("loadLabel")) return "App";
        if (name.equals("openWrite")) return new ByteArrayOutputStream();
        if (name.equals("openInputStream")) return new ByteArrayInputStream(pickedFile);

        // --- services
        if (name.equals("isInteractive") || name.equals("isScreenOn")) return screenOn;
        if (name.equals("isIgnoringBatteryOptimizations")) return batteryOk;
        if (name.equals("getAppWidgetIds")) return widgetIds.clone();
        if (name.equals("getWindows") && owner.contains("AccessibilityService")) return new ArrayList<>(accessibilityWindows);
        if (name.equals("findAccessibilityNodeInfosByText") && n == 1) {
            List<Object> out = new ArrayList<>();
            if (foundTexts.contains(args[0])) out.add(deflt(cls("android/view/accessibility/AccessibilityNodeInfo")));
            return out;
        }
        if (name.equals("performGlobalAction")) { log.add("globalAction " + args[0]); return Boolean.TRUE; }
        if (name.equals("notify") && owner.contains("NotificationManager")) { log.add("notify " + args[n - 2]); return null; }
        if (name.equals("cancel") && owner.contains("NotificationManager")) { log.add("cancelNotif " + args[n - 1]); return null; }
        if (owner.equals("android/widget/Toast")) {
            if (name.equals("makeText")) {
                Object t = deflt(cls("android/widget/Toast"));
                p(t).put("Text", String.valueOf(args[1]));
                return t;
            }
            if (name.equals("show")) { log.add("toast " + prop(self, "Text")); return null; }
        }
        if (name.equals("obtain") && owner.equals("android/view/accessibility/AccessibilityEvent")) {
            Object e = deflt(cls(owner));
            if (n == 1) p(e).put("EventType", args[0]);
            return e;
        }

        // --- WindowManager (overlay) : on garde les vues ajoutées
        if (name.equals("addView") && owner.equals("android/view/ViewManager") || (fromProxy && name.equals("addView"))) {
            windows.add(args[0]);
            log.add("window+ " + args[0].getClass().getSimpleName());
            return null;
        }
        if (fromProxy && (name.equals("removeView") || name.equals("removeViewImmediate"))) { windows.remove(args[0]); return null; }

        // --- hiérarchie des vues
        if (name.equals("addView") && n >= 1 && is(args[0], "android/view/View")) {
            List<Object> k = kids(self);
            int idx = (n >= 2 && args[1] instanceof Integer && desc.startsWith("(Landroid/view/View;I")) ? (Integer) args[1] : -1;
            if (idx >= 0 && idx <= k.size()) k.add(idx, args[0]); else k.add(args[0]);
            p(args[0]).put("Parent", self);
            for (Object a : args) if (is(a, "android/view/ViewGroup$LayoutParams")) p(args[0]).put("LayoutParams", a);
            return null;
        }
        if (name.equals("removeAllViews") || name.equals("removeAllViewsInLayout")) { kids(self).clear(); return null; }
        if (name.equals("removeView") && n == 1) { kids(self).remove(args[0]); return null; }
        if (name.equals("removeViewAt") && n == 1) { List<Object> k = kids(self); int i = (Integer) args[0]; if (i < k.size()) k.remove(i); return null; }
        if (name.equals("getChildCount") && n == 0) return kids(self).size();
        if (name.equals("getChildAt") && n == 1) { List<Object> k = kids(self); int i = (Integer) args[0]; return i >= 0 && i < k.size() ? k.get(i) : null; }
        if (name.equals("getParent") && n == 0) return prop(self, "Parent");
        if (name.equals("getText") && n == 0 && rt != null && rt.getName().equals("android.text.Editable")) {
            final String txt = String.valueOf(prop(self, "Text") == null ? "" : prop(self, "Text"));
            Object ed = proxy(rt);
            p(ed).put("Text", txt);
            return ed;
        }
        if (name.equals("length") && n == 0 && fromProxy) { Object t = prop(self, "Text"); return t == null ? 0 : t.toString().length(); }

        // --- dialogues : on garde les boutons pour pouvoir « confirmer »
        for (Object a : args) {
            if (is(a, "android/content/DialogInterface$OnClickListener")) dialogButtons.add(new Object[]{name, a});
        }
        if ((name.equals("show") || name.equals("create")) && owner.contains("AlertDialog")) {
            log.add("dialog " + prop(self, "Title"));
            Object d = deflt(rt == null || rt == void.class ? cls("android/app/AlertDialog") : rt);
            return d;
        }

        // --- Intent / Bundle : extras
        if ((owner.equals("android/content/Intent") || owner.equals("android/os/Bundle") || owner.equals("android/os/BaseBundle"))) {
            if ((name.equals("putExtra") || (name.startsWith("put") && !name.equals("putAll"))) && n == 2 && args[0] instanceof String) {
                p(self).put("x:" + args[0], args[1]);
                return rt != null && rt.isInstance(self) ? self : null;
            }
            if (name.equals("hasExtra") || name.equals("containsKey")) return p(self).containsKey("x:" + args[0]);
            if ((name.endsWith("Extra") || (name.startsWith("get") && n >= 1 && args[0] instanceof String && !name.equals("getStringArrayList")))
                    && n >= 1 && args[0] instanceof String) {
                Object v = p(self).get("x:" + args[0]);
                if (v != null && (rt == null || rt.isPrimitive() || rt.isInstance(v))) return v;
                return n >= 2 ? args[1] : deflt(rt);
            }
            if (name.equals("addCategory")) { p(self).put("cat:" + args[0], Boolean.TRUE); return self; }
            if (name.equals("addFlags") || name.equals("setFlags")) return self;
            if (name.equals("setClassName") || name.equals("setComponent") || name.equals("setPackage") || name.equals("setData") || name.equals("setType")) {
                if (n >= 1) p(self).put(name.substring(3), args[n - 1]);
                return self;
            }
        }
        if (name.equals("getIntentSender")) return deflt(rt);

        // --- émulation générique des propriétés
        if (name.startsWith("set") && name.length() > 3 && n == 1) {
            p(self).put(name.substring(3), args[0]);
            if (rt != null && rt != void.class && self != null && rt.isInstance(self)) return self;
            return rt == boolean.class ? Boolean.TRUE : deflt(rt);
        }
        if (n == 0 && (name.startsWith("get") || name.startsWith("is") || name.startsWith("has"))) {
            String key = name.substring(name.startsWith("is") ? 2 : 3);
            Object v = prop(self, key);
            if (v != null && rt != null) {
                if (rt.isPrimitive() || rt.isInstance(v)) return v;
            }
        }

        // --- chaînage façon « builder »
        if (self != null && rt != null && !rt.isPrimitive() && rt != Object.class && rt.isInstance(self)
                && !name.startsWith("get") && !name.startsWith("obtain") && !name.startsWith("clone") && !name.startsWith("build") && !name.startsWith("create")) {
            if (n >= 1) p(self).put(name, args[0]);
            return self;
        }
        return deflt(rt);
    }

    static String describe(Object o) {
        if (o == null) return "null";
        Map<String, Object> m = props.get(o);
        return o.getClass().getSimpleName() + (m == null ? "" : m.toString());
    }

    static Object service(String n) {
        switch (n) {
            case "window": return single("wm", cls("android/view/WindowManager"));
            case "power": return single("power", cls("android/os/PowerManager"));
            case "alarm": return single("alarm", cls("android/app/AlarmManager"));
            case "notification": return single("notif", cls("android/app/NotificationManager"));
            case "audio": return single("audio", cls("android/media/AudioManager"));
            case "input_method": return single("imm", cls("android/view/inputmethod/InputMethodManager"));
            case "accessibility": return single("a11y", cls("android/view/accessibility/AccessibilityManager"));
            case "activity": return single("am", cls("android/app/ActivityManager"));
            case "appwidget": return single("awm", cls("android/appwidget/AppWidgetManager"));
            case "keyguard": return single("kg", cls("android/app/KeyguardManager"));
            case "vibrator": return single("vib", cls("android/os/Vibrator"));
            default: return null;
        }
    }

    // ------------------------------------------------------------------ SharedPreferences en mémoire

    static Object prefsProxy(String file) {
        final Map<String, Object> data;
        synchronized (Hooks.class) { data = prefs.computeIfAbsent(file, k -> new HashMap<>()); }
        Class<?> sp = cls("android/content/SharedPreferences");
        Class<?> ed = cls("android/content/SharedPreferences$Editor");
        return Proxy.newProxyInstance(Hooks.class.getClassLoader(), new Class<?>[]{sp}, (px, m, a) -> {
            String nm = m.getName();
            synchronized (Hooks.class) {
                switch (nm) {
                    case "getString": case "getInt": case "getLong": case "getBoolean": case "getFloat": case "getStringSet": {
                        Object v = data.get((String) a[0]);
                        if (v == null) return a[1];
                        if (nm.equals("getStringSet")) return new HashSet<>((Set<?>) v);
                        Class<?> r = m.getReturnType();
                        if (r == int.class && !(v instanceof Integer)) throw new ClassCastException(a[0] + " n'est pas un int (" + v.getClass().getSimpleName() + ")");
                        if (r == long.class && !(v instanceof Long)) throw new ClassCastException(a[0] + " n'est pas un long (" + v.getClass().getSimpleName() + ")");
                        if (r == boolean.class && !(v instanceof Boolean)) throw new ClassCastException(a[0] + " n'est pas un boolean");
                        if (r == String.class && !(v instanceof String)) throw new ClassCastException(a[0] + " n'est pas un String");
                        return v;
                    }
                    case "contains": return data.containsKey(a[0]);
                    case "getAll": return new HashMap<>(data);
                    case "edit": return editor(ed, data);
                    case "hashCode": return System.identityHashCode(px);
                    case "equals": return px == a[0];
                    case "toString": return "prefs:" + file;
                    default: return null;
                }
            }
        });
    }

    static Object editor(Class<?> ed, Map<String, Object> data) {
        final Map<String, Object> pending = new HashMap<>();
        final Set<String> removed = new HashSet<>();
        final boolean[] clear = {false};
        return Proxy.newProxyInstance(Hooks.class.getClassLoader(), new Class<?>[]{ed}, (px, m, a) -> {
            String nm = m.getName();
            synchronized (Hooks.class) {
                if (nm.startsWith("put")) {
                    Object v = a[1];
                    if (v instanceof Set) v = new HashSet<>((Set<?>) v);
                    pending.put((String) a[0], v);
                    removed.remove(a[0]);
                    return px;
                }
                if (nm.equals("remove")) { removed.add((String) a[0]); pending.remove(a[0]); return px; }
                if (nm.equals("clear")) { clear[0] = true; return px; }
                if (nm.equals("apply") || nm.equals("commit")) {
                    if (clear[0]) data.clear();
                    for (String k : removed) data.remove(k);
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) data.remove(e.getKey()); else data.put(e.getKey(), e.getValue());
                    }
                    return nm.equals("commit") ? Boolean.TRUE : null;
                }
                if (nm.equals("hashCode")) return System.identityHashCode(px);
                if (nm.equals("equals")) return px == a[0];
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ file d'exécution (fil principal simulé)

    public interface ErrorSink { void error(String where, Throwable t); }

    /** Exécute jusqu'à {@code max} tâches en attente (les tâches qui se reprogramment sont bornées). */
    public static int drain(int max, ErrorSink sink) {
        int done = 0;
        while (done < max) {
            Runnable r;
            synchronized (Hooks.class) {
                if (queue.isEmpty()) break;
                r = queue.remove(0);
            }
            try { r.run(); } catch (Throwable t) { sink.error("tâche " + r.getClass().getName(), t); }
            done++;
        }
        return done;
    }
}
