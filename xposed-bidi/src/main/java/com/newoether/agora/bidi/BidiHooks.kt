package com.newoether.agora.bidi

import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.util.Log
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Forces first-strong direction resolution (LTR fallback) for every text layout
 * in the Agora process.
 *
 * Agora renders chat bubbles with Jetpack Compose Markdown
 * (`MessageItemMarkdown.kt` / `MessageBubbleAssets.kt`). Compose resolves the
 * paragraph direction from `TextStyle.textDirection` (default: locale-dependent)
 * and then lays out through `android.text.StaticLayout.Builder` under the hood,
 * so both layers are hooked:
 *
 * 1. `StaticLayout$Builder` / `DynamicLayout$Builder` — `obtain()` binds
 *    `FIRSTSTRONG_LTR`; `setTextDirection()` replaces any inferred heuristic
 *    (FIRSTSTRONG_*, ANYRTL_LTR, LOCALE) with it; `build()` re-applies it as a
 *    last line of defence. Explicit `LTR`/`RTL` forces are never touched.
 * 2. `BoringLayout.make()` — single-line labels use BoringLayout, not
 *    StaticLayout; the heuristic parameter is replaced the same way.
 * 3. Compose `AndroidParagraphIntrinsics` constructors (both the current
 *    `androidx.compose.ui.text` package and the older `...ui.text.platform`
 *    one) — a `TextStyle` with `Unspecified`/`Content` direction (the two
 *    locale-dependent values) is rewritten to `ContentOrLtr`: first-strong
 *    per paragraph, LTR fallback. Explicit styles stay untouched.
 *
 * No characters are inserted, so link/selection offsets stay valid.
 */
object BidiHooks {

    const val TAG = "AgoraBidiFix"

    private val HOOKED = HashSet<String>()
    private var xposed: XposedInterface? = null
    private var installed = false

    /** Builders whose caller set an explicit LTR/RTL direction; left alone. */
    private val explicit: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private var decisionLogs = 0

    fun install(framework: XposedInterface, classLoader: ClassLoader) {
        if (installed) {
            return
        }
        xposed = framework
        hookBuilder(classLoader, "android.text.StaticLayout\$Builder")
        hookBuilder(classLoader, "android.text.DynamicLayout\$Builder")
        hookBoringLayout(classLoader)
        hookComposeIntrinsics(classLoader, "androidx.compose.ui.text.AndroidParagraphIntrinsics")
        hookComposeIntrinsics(
            classLoader,
            "androidx.compose.ui.text.platform.AndroidParagraphIntrinsics",
        )
        installed = HOOKED.isNotEmpty()
        Log.i(TAG, "installed=$installed hooks=$HOOKED")
    }

    private fun hookBuilder(classLoader: ClassLoader, name: String) {
        try {
            val builder = Class.forName(name, false, classLoader)
            val setter = builder.getDeclaredMethod(
                "setTextDirection", TextDirectionHeuristic::class.java,
            )
            var obtains = 0
            for (m in builder.declaredMethods) {
                if (m.name != "obtain" || !Modifier.isStatic(m.modifiers)) {
                    continue
                }
                if (m.parameterTypes.none { CharSequence::class.java.isAssignableFrom(it) }) {
                    continue
                }
                hook(m, "obtain") { chain ->
                    val b = chain.proceed()
                    if (b != null) {
                        runCatching { setter.invoke(b, TextDirectionHeuristics.FIRSTSTRONG_LTR) }
                    }
                    b
                }
                obtains++
            }
            // TextView installs its own heuristic after obtain(); adopt explicit
            // LTR/RTL, replace everything inferred with FIRSTSTRONG_LTR.
            hook(setter, "setTextDirection") { chain ->
                val arg = chain.getArg(0)
                val self = chain.thisObject
                if (arg is TextDirectionHeuristic && self != null && explicit[self] == true) {
                    return@hook chain.proceed()
                }
                if (arg is TextDirectionHeuristic && !isExplicitForce(arg)) {
                    return@hook chain.proceed(arrayOf<Any?>(TextDirectionHeuristics.FIRSTSTRONG_LTR))
                }
                if (self != null) {
                    explicit[self] = true
                }
                chain.proceed()
            }
            // Re-apply right before layout creation in case the caller changed it again.
            for (m in builder.declaredMethods) {
                if (m.name != "build" || Modifier.isStatic(m.modifiers)) {
                    continue
                }
                hook(m, "build") { chain ->
                    val self = chain.thisObject
                    if (self != null && explicit[self] != true) {
                        runCatching { setter.invoke(self, TextDirectionHeuristics.FIRSTSTRONG_LTR) }
                    }
                    try {
                        chain.proceed()
                    } finally {
                        if (self != null) {
                            explicit.remove(self)
                        }
                    }
                }
            }
            Log.i(TAG, "hooked $name obtain=$obtains")
        } catch (t: Throwable) {
            Log.w(TAG, "hook failed for $name", t)
        }
    }

    private fun hookBoringLayout(classLoader: ClassLoader) {
        try {
            val boring = Class.forName("android.text.BoringLayout", false, classLoader)
            var hooked = 0
            for (m in boring.declaredMethods) {
                if (m.name != "make" || !Modifier.isStatic(m.modifiers) ||
                    m.returnType != boring
                ) {
                    continue
                }
                val types = m.parameterTypes
                val dirIdx = types.indexOfLast { it == TextDirectionHeuristic::class.java }
                if (dirIdx < 0) {
                    continue
                }
                val count = types.size
                hook(m, "boringMake") { chain ->
                    val dir = chain.getArg(dirIdx)
                    if (dir is TextDirectionHeuristic && !isExplicitForce(dir)) {
                        val args = Array<Any?>(count) { i ->
                            if (i == dirIdx) TextDirectionHeuristics.FIRSTSTRONG_LTR
                            else chain.getArg(i)
                        }
                        return@hook chain.proceed(args)
                    }
                    chain.proceed()
                }
                hooked++
            }
            Log.i(TAG, "hooked BoringLayout make=$hooked")
        } catch (t: Throwable) {
            // Not fatal: multi-line text is already covered by the builder hooks.
            Log.w(TAG, "BoringLayout hook skipped: $t")
        }
    }

    private fun hookComposeIntrinsics(classLoader: ClassLoader, name: String) {
        val intr: Class<*>
        try {
            intr = Class.forName(name, false, classLoader)
        } catch (t: Throwable) {
            // Expected on Compose versions that use the other package; info, not warning.
            Log.i(TAG, "$name not present, skipped")
            return
        }
        var hooked = 0
        for (ctor in intr.declaredConstructors) {
            val types = ctor.parameterTypes
            val styleIdx = types.indexOfFirst { it.name == "androidx.compose.ui.text.TextStyle" }
            val textIdx = types.indexOfFirst { it == String::class.java }
            if (styleIdx < 0) {
                continue
            }
            hook(ctor, "intrinsics") { chain ->
                val style = chain.getArg(styleIdx) as? TextStyle
                    ?: return@hook chain.proceed()
                val dir = style.textDirection
                if (dir == TextDirection.Unspecified || dir == TextDirection.Content) {
                    val args = Array<Any?>(types.size) { i ->
                        if (i == styleIdx) style.copy(textDirection = TextDirection.ContentOrLtr)
                        else chain.getArg(i)
                    }
                    logStyleRewrite(if (textIdx >= 0) chain.getArg(textIdx) else null)
                    return@hook chain.proceed(args)
                }
                chain.proceed()
            }
            hooked++
        }
        Log.i(TAG, "hooked $name ctors=$hooked")
    }

    /** LTR/RTL are explicit forces; every other heuristic only infers and stays overridable. */
    private fun isExplicitForce(h: TextDirectionHeuristic): Boolean {
        return h === TextDirectionHeuristics.LTR || h === TextDirectionHeuristics.RTL
    }

    private fun <T : Executable> hook(exe: T, id: String, block: (XposedInterface.Chain) -> Any?) {
        val key = exe.declaringClass.name + "#" + id + "#" + exe.parameterCount
        if (!HOOKED.add(key)) {
            return
        }
        try {
            xposed!!.hook(exe)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> block(chain) }
        } catch (t: Throwable) {
            HOOKED.remove(key)
            Log.w(TAG, "hook $key failed", t)
        }
    }

    /** First rewrites only — enough for logcat, cheap afterwards. */
    private fun logStyleRewrite(text: Any?) {
        if (decisionLogs >= 20) {
            return
        }
        decisionLogs++
        val preview = text?.toString()?.take(48)
        Log.d(TAG, "intrinsics Content/Unspecified -> ContentOrLtr text=«$preview»")
    }
}
