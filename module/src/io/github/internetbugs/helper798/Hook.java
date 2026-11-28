package io.github.internetbugs.helper798;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern (libxposed API 101/102) entry point. Declared in META-INF/xposed/java_init.list.
 */
public class Hook extends XposedModule {

    static final String TAG = "N798";
    static final String TARGET = "com.cloudora.android";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded: api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        log(Log.INFO, TAG, "target package loaded, installing hooks");
        ClassLoader cl = param.getDefaultClassLoader();
        AdBlock.install(this, cl);
        NavBlock.install(this, cl);
        MeBlock.install(this, cl);
        AntiTouch.install(this, cl);
    }
}
