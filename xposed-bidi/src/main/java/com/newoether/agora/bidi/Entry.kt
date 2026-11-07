package com.newoether.agora.bidi

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed entry point — the class named in META-INF/xposed/java_init.list
 * and assets/xposed_init. Scoped to Agora only (see scope.list + manifest).
 *
 * Hooks install from both [onPackageLoaded] and [onPackageReady]; install is
 * idempotent, so whichever fires first wins.
 */
class Entry : XposedModule() {

    /** Process this module instance was loaded into (ModuleLoadedParam is the only source). */
    private var processName = ""
    private var installedFor: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName ?: ""
        Log.i(BidiHooks.TAG, "module loaded | process=$processName" +
            " | framework=$frameworkName $frameworkVersion | api=$apiVersion")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        maybeInstall("onPackageLoaded", param.packageName, param.defaultClassLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        maybeInstall("onPackageReady", param.packageName, param.classLoader)
    }

    private fun maybeInstall(source: String, pkg: String?, classLoader: ClassLoader) {
        val self = moduleApplicationInfo?.packageName ?: return
        if (pkg == null || pkg == self || pkg != TARGET_PACKAGE) {
            return
        }
        // Only the main process draws chat text; helpers (:push, :webview) stay untouched.
        if (processName.isNotEmpty() && processName != pkg) {
            Log.i(BidiHooks.TAG, "skipping auxiliary process $processName")
            return
        }
        if (pkg == installedFor) {
            return
        }
        installedFor = pkg
        Log.i(BidiHooks.TAG, "$source pkg=$pkg process=$processName")
        try {
            BidiHooks.install(this, classLoader)
        } catch (t: Throwable) {
            // Let the next callback retry: a failed install is not final for the process.
            installedFor = null
            Log.e(BidiHooks.TAG, "install failed", t)
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.newoether.agora"
    }
}
