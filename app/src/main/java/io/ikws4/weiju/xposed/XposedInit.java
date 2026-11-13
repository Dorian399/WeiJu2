package io.ikws4.weiju.xposed;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.ikws4.weiju.BuildConfig;
import io.ikws4.weiju.storage.scriptstore.XScriptStore;

public class XposedInit extends XposedModule {
    private static XposedInit sInstance;

    private static String sCurrentPackageName;
    private static ClassLoader sCurrentClassLoader;
    private static ApplicationInfo sCurrentAppInfo;
    private static XScriptStore sStore;

    private final AtomicBoolean mScriptsInjected = new AtomicBoolean(false);

    public static XposedInit getInstance() {
        return sInstance;
    }

    public static String getCurrentPackageName() {
        return sCurrentPackageName;
    }

    public static ClassLoader getCurrentClassLoader() {
        return sCurrentClassLoader;
    }

    public static ApplicationInfo getCurrentAppInfo() {
        return sCurrentAppInfo;
    }

    public static XScriptStore getStore() {
        return sStore;
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        sInstance = this;
        sCurrentPackageName = param.getPackageName();
        sCurrentAppInfo = param.getApplicationInfo();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                sCurrentClassLoader = param.getDefaultClassLoader();
            } catch (Throwable ignored) {
            }
        }

        if (sCurrentPackageName.equals(BuildConfig.APPLICATION_ID)) {
            updateHostXposedStatus();
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        sInstance = this;
        sCurrentPackageName = param.getPackageName();
        sCurrentAppInfo = param.getApplicationInfo();
        sCurrentClassLoader = param.getClassLoader();

        if (sCurrentPackageName.equals(BuildConfig.APPLICATION_ID)) {
            updateHostXposedStatus();
            return;
        }

        if (mScriptsInjected.get()) {
            return;
        }

        sStore = new XScriptStore(this, null);
        if (sStore.canRead()) {
            injectScripts();
            mScriptsInjected.set(true);
            return;
        }

        // If remote preferences could not be read without Context, hook Application.attach
        try {
            Method attachMethod = ReflectionUtils.findMethodExact(Application.class, "attach", Context.class);
            hook(attachMethod).intercept(chain -> {
                Object res = chain.proceed();
                if (!mScriptsInjected.get()) {
                    Context ctx = (Context) chain.getArg(0);
                    sStore = new XScriptStore(XposedInit.this, ctx);
                    if (sStore.canRead()) {
                        injectScripts();
                        mScriptsInjected.set(true);
                    }
                }
                return res;
            });
        } catch (Throwable t) {
            log(android.util.Log.ERROR, "WeiJu2", "Failed to hook Application.attach", t);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        Object[] state = new Object[] { sCurrentPackageName, sCurrentAppInfo, sCurrentClassLoader };
        param.setSavedInstanceState(state);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        sInstance = this;
        mScriptsInjected.set(false);
        // unhook old hooks first
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }

        Object saved = param.getSavedInstanceState();
        if (saved instanceof Object[]) {
            Object[] arr = (Object[]) saved;
            sCurrentPackageName = (String) arr[0];
            sCurrentAppInfo = (ApplicationInfo) arr[1];
            sCurrentClassLoader = (ClassLoader) arr[2];
        }

        if (sCurrentPackageName != null) {
            sStore = new XScriptStore(this, null);
            if (sStore.canRead()) {
                injectScripts();
                mScriptsInjected.set(true);
            }
        }
    }

    private void injectScripts() {
        injectScriptsFor(BuildConfig.APPLICATION_ID); // global scripts
        injectScriptsFor(sCurrentPackageName);
    }

    private void injectScriptsFor(String pkgName) {
        Globals globals = XposedPlatform.create(pkgName);
        Set<String> keys = sStore.get(pkgName, Collections.emptySet());

        // provide param / context to lua environment
        globals.set("packageName", sCurrentPackageName);
        LuaTable lpparamTable = new LuaTable();
        lpparamTable.set("packageName", LuaValue.valueOf(sCurrentPackageName != null ? sCurrentPackageName : ""));
        if (sCurrentClassLoader != null) {
            lpparamTable.set("classLoader", CoerceJavaToLua.coerce(sCurrentClassLoader));
        }
        if (sCurrentAppInfo != null) {
            lpparamTable.set("appInfo", CoerceJavaToLua.coerce(sCurrentAppInfo));
        }
        globals.set("lpparam", lpparamTable);

        for (String key : keys) {
            String script = sStore.get(key, "");
            try {
                globals.load(script).call();
            } catch (Throwable e) {
                log(android.util.Log.ERROR, "WeiJu2", "Error running script: " + key, e);
                Console.printErr(e);
            }
        }
    }

    private void updateHostXposedStatus() {
        try {
            Class<?> clazz = ReflectionUtils.findClass("io.ikws4.weiju.WeiJu", sCurrentClassLoader);
            ReflectionUtils.setStaticBooleanField(clazz, "XPOSED_ENABLED", true);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, "WeiJu2", "Failed to update host xposed status", t);
        }
    }
}
