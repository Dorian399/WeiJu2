package io.ikws4.weiju.xposed;

import com.android.dx.stock.ProxyBuilder;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.LuajavaLib;

import java.io.File;

public class XposedLuajavaLib extends LuajavaLib {

    @Override
    protected Object object(Class clazz, LuaValue lobj) {
        ProxyInvocationHandler handler = new ProxyInvocationHandler(lobj);

        try {
            var appInfo = XposedInit.getCurrentAppInfo();
            var classLoader = XposedInit.getCurrentClassLoader();
            String dataDir = (appInfo != null && appInfo.dataDir != null) ? appInfo.dataDir : "/data/local/tmp";
            var path = new File(dataDir + "/code_cache/");
            if (!path.exists()) {
                path.mkdirs();
            }
            return ProxyBuilder.forClass(clazz)
                .dexCache(path)
                .parentClassLoader(classLoader)
                .handler(handler)
                .build();
        } catch (Exception e) {
            throw new LuaError(e);
        }
    }

    @Override
    protected Class classForName(String name) {
        try {
            return ReflectionUtils.findClass(name, XposedInit.getCurrentClassLoader());
        } catch (ClassNotFoundException e) {
            throw new LuaError(e);
        }
    }
}
