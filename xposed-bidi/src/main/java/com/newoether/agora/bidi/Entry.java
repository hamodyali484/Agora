package com.newoether.agora.bidi;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point — the class named in META-INF/xposed/java_init.list
 * and assets/xposed_init. Scoped to Agora only (see scope.list + manifest).
 *
 * <p>Hooks install from both {@code onPackageLoaded} and {@code onPackageReady};
 * install is idempotent, so whichever fires first wins.
 */
public class Entry extends XposedModule {

    private static final String TARGET_PACKAGE = "com.newoether.agora";

    /** Process this module instance was loaded into (ModuleLoadedParam is the only source). */
    private String processName = "";
    private String installedFor;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param.getProcessName() != null) {
            processName = param.getProcessName();
        }
        Log.i(BidiHooks.TAG, "module loaded | process=" + processName
                + " | framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " | api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        maybeInstall("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        maybeInstall("onPackageReady", param.getPackageName(), param.getClassLoader());
    }

    private void maybeInstall(String source, String pkg, ClassLoader classLoader) {
        final String self;
        try {
            self = getModuleApplicationInfo().packageName;
        } catch (Throwable t) {
            return;
        }
        if (pkg == null || pkg.equals(self) || !TARGET_PACKAGE.equals(pkg)) {
            return;
        }
        // Only the main process draws chat text; helpers (:push, :webview) stay untouched.
        if (!processName.isEmpty() && !processName.equals(pkg)) {
            Log.i(BidiHooks.TAG, "skipping auxiliary process " + processName);
            return;
        }
        if (pkg.equals(installedFor)) {
            return;
        }
        installedFor = pkg;
        Log.i(BidiHooks.TAG, source + " pkg=" + pkg + " process=" + processName);
        try {
            BidiHooks.install(this, classLoader);
        } catch (Throwable t) {
            // Let the next callback retry: a failed install is not final for the process.
            installedFor = null;
            Log.e(BidiHooks.TAG, "install failed", t);
        }
    }
}
