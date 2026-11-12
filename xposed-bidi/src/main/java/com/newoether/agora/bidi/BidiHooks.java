package com.newoether.agora.bidi;

import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

/**
 * Forces first-strong direction resolution (LTR fallback) for every text layout
 * in the Agora process.
 *
 * <p>Agora renders chat bubbles with Jetpack Compose Markdown
 * ({@code MessageItemMarkdown.kt} / {@code MessageBubbleAssets.kt}). Compose's
 * {@code AndroidParagraph} is built on the framework text stack internally
 * ({@code StaticLayout.Builder} for multi-line text, {@code BoringLayout} for
 * single-line text), so hooking the framework layer covers the Compose path
 * too — no hook on Compose's own (internal, R8-renamed in release builds)
 * paragraph classes is needed.
 *
 * <ol>
 *   <li>{@code StaticLayout$Builder} / {@code DynamicLayout$Builder} —
 *       {@code obtain()} binds {@code FIRSTSTRONG_LTR};
 *       {@code setTextDirection()} replaces any <em>inferred</em> heuristic
 *       ({@code FIRSTSTRONG_*}, {@code ANYRTL_LTR}, {@code LOCALE}) with it;
 *       {@code build()} re-applies it as a last line of defence. Explicit
 *       {@code LTR}/{@code RTL} forces are never touched.</li>
 *   <li>{@code BoringLayout.make()} — same replacement for single-line labels.</li>
 * </ol>
 *
 * <p>No characters are inserted, so link/selection offsets stay valid.
 */
public final class BidiHooks {

    public static final String TAG = "AgoraBidiFix";

    private static final Set<String> HOOKED = new HashSet<>();
    private static volatile XposedInterface xposed;
    private static volatile boolean installed;

    /** Builders whose caller set an explicit LTR/RTL direction; left alone. */
    private static final Map<Object, Boolean> EXPLICIT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BidiHooks() {
    }

    public static void install(XposedInterface framework, ClassLoader classLoader) {
        if (installed) {
            return;
        }
        xposed = framework;
        hookBuilder(classLoader, "android.text.StaticLayout$Builder");
        hookBuilder(classLoader, "android.text.DynamicLayout$Builder");
        hookBoringLayout(classLoader);
        hookAndroidBidi(classLoader);
        installed = !HOOKED.isEmpty();
        Log.i(TAG, "installed=" + installed + " hooks=" + HOOKED);
    }

    private static void hookBuilder(ClassLoader classLoader, String className) {
        try {
            final Class<?> builderClass = Class.forName(className, false, classLoader);
            final Method setter = builderClass.getDeclaredMethod(
                    "setTextDirection", TextDirectionHeuristic.class);
            int obtains = 0;
            for (Method m : builderClass.getDeclaredMethods()) {
                if (!"obtain".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                boolean hasText = false;
                for (Class<?> p : m.getParameterTypes()) {
                    if (CharSequence.class.isAssignableFrom(p)) {
                        hasText = true;
                        break;
                    }
                }
                if (!hasText) {
                    continue;
                }
                hook(m, "obtain", chain -> {
                    final Object builder = chain.proceed();
                    if (builder != null) {
                        try {
                            setter.invoke(builder, TextDirectionHeuristics.FIRSTSTRONG_LTR);
                        } catch (Throwable ignored) {
                            // protective: a failed direction override must not break layout
                        }
                    }
                    return builder;
                });
                obtains++;
            }
            // TextView installs its own heuristic after obtain(); explicit LTR/RTL
            // is adopted, everything inferred is replaced with FIRSTSTRONG_LTR.
            hook(setter, "setTextDirection", chain -> {
                final Object arg = chain.getArg(0);
                final Object self = chain.getThisObject();
                if (arg instanceof TextDirectionHeuristic
                        && self != null && Boolean.TRUE.equals(EXPLICIT.get(self))) {
                    return chain.proceed();
                }
                if (arg instanceof TextDirectionHeuristic
                        && !isExplicitForce((TextDirectionHeuristic) arg)) {
                    return chain.proceed(new Object[]{TextDirectionHeuristics.FIRSTSTRONG_LTR});
                }
                if (self != null) {
                    EXPLICIT.put(self, Boolean.TRUE);
                }
                return chain.proceed();
            });
            // Re-apply right before layout creation in case the caller changed it again.
            for (Method m : builderClass.getDeclaredMethods()) {
                if (!"build".equals(m.getName()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                hook(m, "build", chain -> {
                    final Object self = chain.getThisObject();
                    if (self != null && !Boolean.TRUE.equals(EXPLICIT.get(self))) {
                        try {
                            setter.invoke(self, TextDirectionHeuristics.FIRSTSTRONG_LTR);
                        } catch (Throwable ignored) {
                            // protective
                        }
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        if (self != null) {
                            EXPLICIT.remove(self);
                        }
                    }
                });
            }
            Log.i(TAG, "hooked " + className + " obtain=" + obtains);
        } catch (Throwable t) {
            Log.w(TAG, "hook failed for " + className, t);
        }
    }

    private static void hookBoringLayout(ClassLoader classLoader) {
        try {
            final Class<?> boringClass =
                    Class.forName("android.text.BoringLayout", false, classLoader);
            int hooked = 0;
            for (Method m : boringClass.getDeclaredMethods()) {
                if (!"make".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())
                        || m.getReturnType() != boringClass) {
                    continue;
                }
                final Class<?>[] types = m.getParameterTypes();
                int dirIndex = -1;
                for (int i = types.length - 1; i >= 0; i--) {
                    if (types[i] == TextDirectionHeuristic.class) {
                        dirIndex = i;
                        break;
                    }
                }
                if (dirIndex < 0) {
                    continue;
                }
                final int dirIdx = dirIndex;
                final int count = types.length;
                hook(m, "boringMake", chain -> {
                    final Object dir = chain.getArg(dirIdx);
                    if (dir instanceof TextDirectionHeuristic
                            && !isExplicitForce((TextDirectionHeuristic) dir)) {
                        final Object[] args = new Object[count];
                        for (int i = 0; i < count; i++) {
                            args[i] = i == dirIdx
                                    ? TextDirectionHeuristics.FIRSTSTRONG_LTR
                                    : chain.getArg(i);
                        }
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            Log.i(TAG, "hooked BoringLayout make=" + hooked);
        } catch (Throwable t) {
            // Not fatal: multi-line text is already covered by the builder hooks.
            Log.w(TAG, "BoringLayout hook skipped: " + t);
        }
    }

    /** LTR/RTL are explicit forces; every other heuristic only infers and stays overridable. */
    private static boolean isExplicitForce(TextDirectionHeuristic heuristic) {
        return heuristic == TextDirectionHeuristics.LTR
                || heuristic == TextDirectionHeuristics.RTL;
    }

    /**
     * Keeps a symbol glued to a Latin word on the Latin side inside an RTL paragraph
     * ({@code @ag-ui/claude-agent-sdk.} instead of {@code .ag-ui/claude-agent-sdk@}).
     *
     * <p>{@code android.text.AndroidBidi} is a hidden framework class; its exact signature is
     * logged on the first layout so the device log proves what was hooked (or that nothing
     * was). The hook only mutates the level array after {@code chain.proceed()}; it never
     * touches the characters and never changes the return value.
     */
    private static void hookAndroidBidi(ClassLoader classLoader) {
        try {
            final Class<?> bidiClass = Class.forName("android.text.AndroidBidi", false, classLoader);
            Method target = null;
            final StringBuilder signatures = new StringBuilder();
            for (Method m : bidiClass.getDeclaredMethods()) {
                signatures.append("\n  ").append(signatureOf(m));
                if (target == null
                        && Modifier.isStatic(m.getModifiers())
                        && "bidi".equals(m.getName())
                        && m.getReturnType() == int.class) {
                    final Class<?>[] params = m.getParameterTypes();
                    if (params.length == 3 && params[0] == int.class
                            && params[1] == char[].class && params[2] == byte[].class) {
                        target = m;
                    }
                }
            }
            if (target == null) {
                Log.w(TAG, "AndroidBidi.bidi not found; methods:" + signatures);
                return;
            }
            hook(target, "androidBidi", chain -> {
                final Object result = chain.proceed();
                final Object charsArg = chain.getArg(1);
                final Object levelsArg = chain.getArg(2);
                if (charsArg instanceof char[] && levelsArg instanceof byte[]) {
                    final int fixed = BidiLevelFixer.fix((char[]) charsArg, (byte[]) levelsArg);
                    noteBidiCall(fixed, (char[]) charsArg);
                }
                return result;
            });
            Log.i(TAG, "hooked " + signatureOf(target));
        } catch (Throwable t) {
            // The class or method may not exist on this platform; the rest keeps working.
            Log.w(TAG, "AndroidBidi hook skipped: " + t);
        }
    }

    private static String signatureOf(Method method) {
        final StringBuilder sb = new StringBuilder(method.getName()).append('(');
        final Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")->").append(method.getReturnType().getSimpleName()).toString();
    }

    private static final int BIDI_DIAGNOSTIC_AT = 300;
    private static final AtomicInteger bidiCalls = new AtomicInteger();
    private static final AtomicInteger bidiFixes = new AtomicInteger();
    private static int levelLogs;

    /**
     * A hook that installs but never fixes looks exactly like a hook that never runs, so after
     * {@value #BIDI_DIAGNOSTIC_AT} calls the module states which of the two it is: zero fixes
     * means either no glued symbol has been laid out yet, or this platform analyses through
     * {@code AndroidBidi.directions(...)} / {@code MeasuredParagraph} instead.
     */
    private static void noteBidiCall(int fixed, char[] chars) {
        final int calls = bidiCalls.incrementAndGet();
        if (fixed > 0) {
            bidiFixes.addAndGet(fixed);
            if (levelLogs < 20) {
                levelLogs++;
                Log.d(TAG, "levels fixed=" + fixed + " text=«"
                        + new String(chars, 0, Math.min(chars.length, 48)) + "»");
            }
        }
        if (calls == BIDI_DIAGNOSTIC_AT) {
            final int fixes = bidiFixes.get();
            Log.i(TAG, "bidi called " + calls + " times, levels fixed " + fixes
                    + (fixes == 0
                    ? ": no glued symbol so far, or the render path uses directions(...)"
                    : ""));
        }
    }

    private static <T extends Executable> void hook(
            T executable, String id, XposedInterface.Hooker hooker) {
        final String key = executable.getDeclaringClass().getName() + "#" + id
                + "#" + executable.getParameterCount();
        if (!HOOKED.add(key)) {
            return;
        }
        try {
            xposed.hook(executable)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
        } catch (Throwable t) {
            HOOKED.remove(key);
            Log.w(TAG, "hook " + key + " failed", t);
        }
    }
}
