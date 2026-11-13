package io.ikws4.weiju.storage.scriptstore;

import android.content.Context;

import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.ikws4.weiju.storage.scriptstore.strategy.EmptyStoreStrategy;
import io.ikws4.weiju.storage.scriptstore.strategy.LibXposedRemotePreferencesStoreStrategy;
import io.ikws4.weiju.storage.scriptstore.strategy.RemoteSharedPreferencesStoreStrategy;
import io.ikws4.weiju.storage.scriptstore.strategy.StoreStrategy;
import io.ikws4.weiju.util.Logger;

public class XScriptStore {
    private StoreStrategy strategy;

    public XScriptStore(XposedInterface xposedInterface, Context context) {
        if (xposedInterface != null) {
            strategy = new LibXposedRemotePreferencesStoreStrategy(xposedInterface);
            if (strategy.canRead()) {
                Logger.d("XScriptStore:", "use LibXposedRemotePreferencesStoreStrategy");
                return;
            }
        }

        if (context != null) {
            strategy = new RemoteSharedPreferencesStoreStrategy(context);
            if (strategy.canRead()) {
                Logger.d("XScriptStore:", "use RemoteSharedPreferencesStoreStrategy");
                return;
            }
        }

        this.strategy = new EmptyStoreStrategy();
        Logger.e("XScriptStore:", "can not load scripts.");
    }

    public boolean canRead() {
        return strategy.canRead();
    }

    public String get(String k, String defValue) {
        return strategy.get(k, defValue);
    }

    public Set<String> get(String key, Set<String> defValue) {
        return strategy.get(key, defValue);
    }
}
