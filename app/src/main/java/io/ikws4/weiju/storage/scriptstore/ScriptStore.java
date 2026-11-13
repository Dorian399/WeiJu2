package io.ikws4.weiju.storage.scriptstore;

import android.content.Context;
import android.content.SharedPreferences;

import android.util.Base64;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.ikws4.weiju.WeiJu;
import io.ikws4.weiju.storage.scriptstore.strategy.SharedPreferencesStoreStrategy;
import io.ikws4.weiju.storage.scriptstore.strategy.StoreStrategy;
import io.ikws4.weiju.util.Logger;

public class ScriptStore {
    private final Context mContext;
    private StoreStrategy strategy;
    private XposedService mXposedService;

    public ScriptStore(Context context) {
        mContext = context.getApplicationContext();
        // First try standard private shared preferences
        strategy = new SharedPreferencesStoreStrategy(context, Context.MODE_PRIVATE);

        // Register listener for LibXposed modern service
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    mXposedService = service;
                    WeiJu.XPOSED_ENABLED = true;
                    Logger.d("ScriptStore:", "Connected to LibXposed service: " + service.getFrameworkName());
                    try {
                        SharedPreferences remotePrefs = service.getRemotePreferences(StoreStrategy.STORE_NAME);
                        // Sync current store to remote preferences
                        syncToRemote(remotePrefs);
                    } catch (Throwable t) {
                        Logger.e("ScriptStore:", "Failed to get remote preferences from service", t);
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    if (mXposedService == service) {
                        mXposedService = null;
                    }
                }
            });
        } catch (Throwable t) {
            Logger.e("ScriptStore:", "Failed to register XposedServiceHelper listener", t);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncToRemote(SharedPreferences remotePrefs) {
        if (remotePrefs == null) return;
        try {
            SharedPreferences localPrefs = mContext.getSharedPreferences(StoreStrategy.STORE_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = remotePrefs.edit();
            editor.putBoolean(StoreStrategy.DUMMY_KEY, true);
            for (Map.Entry<String, ?> entry : localPrefs.getAll().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Set<?>) {
                    editor.putStringSet(key, (Set<String>) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                }
            }
            editor.apply();
        } catch (Throwable t) {
            Logger.e("ScriptStore:", "Failed to sync to remote prefs", t);
        }
    }

    public String get(String k, String defValue) {
        return strategy.get(k, defValue);
    }

    public Set<String> get(String k, Set<String> defValue) {
        return strategy.get(k, defValue);
    }

    public void put(String k, String v) {
        strategy.put(k, v);
        if (mXposedService != null) {
            try {
                SharedPreferences remotePrefs = mXposedService.getRemotePreferences(StoreStrategy.STORE_NAME);
                if (v == null || v.isEmpty()) {
                    remotePrefs.edit().remove(k).apply();
                } else {
                    remotePrefs.edit().putString(k, android.util.Base64.encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.DEFAULT)).apply();
                }
            } catch (Throwable t) {
                Logger.e("ScriptStore:", "Failed to sync put String to LibXposed service", t);
            }
        }
    }

    public void put(String k, Set<String> defValue) {
        strategy.put(k, defValue);
        if (mXposedService != null) {
            try {
                SharedPreferences remotePrefs = mXposedService.getRemotePreferences(StoreStrategy.STORE_NAME);
                if (defValue == null || defValue.isEmpty()) {
                    remotePrefs.edit().remove(k).apply();
                } else {
                    remotePrefs.edit().putStringSet(k, defValue).apply();
                }
            } catch (Throwable t) {
                Logger.e("ScriptStore:", "Failed to sync put StringSet to LibXposed service", t);
            }
        }
    }

    public void hotReload(String pkg) {
        if (mXposedService != null && mXposedService.getApiVersion() >= XposedService.API_102) {
            try {
                for (HookedTarget target : mXposedService.getRunningTargets()) {
                    if (target.getProcessName().equals(pkg) || target.getProcessName().startsWith(pkg + ":")) {
                        mXposedService.hotReloadModule(target, null, (t, result) -> {
                            Logger.d("ScriptStore:", "Hot reload for " + t.getProcessName() + ": " + result.status());
                        });
                    }
                }
            } catch (Throwable t) {
                Logger.e("ScriptStore:", "Failed to hot reload module", t);
            }
        }
    }

    public XposedService getXposedService() {
        return mXposedService;
    }

    private static ScriptStore instance;

    public static ScriptStore getInstance(Context context) {
        if (instance == null) {
            instance = new ScriptStore(context.getApplicationContext());
        }
        return instance;
    }
}
