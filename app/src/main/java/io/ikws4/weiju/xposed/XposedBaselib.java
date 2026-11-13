package io.ikws4.weiju.xposed;

import android.util.Log;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.luaj.vm2.lib.jse.JseBaseLib;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

class XposedBaselib extends JseBaseLib {

    @Override
    public LuaValue call(LuaValue modname, LuaValue env) {
        super.call(modname, env);
        env.set("hook", new hook());
        return env;
    }

    static final class hook extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            LuaTable table = arg.checktable();
            var clazz = (Class<?>) table.get("class").checkuserdata();
            var returns = table.get("returns");
            var method = table.get("method");
            var params = table.get("params");
            var replace = table.get("replace");
            var before = table.get("before");
            var after = table.get("after");

            boolean isConstructor = returns.isnil() && method.isnil();

            if (!isConstructor) {
                if (returns.isnil() || method.isnil()) {
                    throw new LuaError("Method signature not complete: expect `returns` and `method` not nil");
                }
            }

            Class<?>[] parameterTypes = new Class<?>[0];
            if (!params.isnil()) {
                var paramsTable = params.checktable();
                parameterTypes = new Class<?>[paramsTable.length()];
                for (int i = 0; i < paramsTable.length(); i++) {
                    parameterTypes[i] = (Class<?>) paramsTable.get(i + 1).checkuserdata();
                }
            }

            Executable executable;
            try {
                if (isConstructor) {
                    executable = ReflectionUtils.findConstructorExact(clazz, parameterTypes);
                } else {
                    executable = ReflectionUtils.findMethodExact(clazz, method.checkjstring(), parameterTypes);
                }
            } catch (NoSuchMethodException e) {
                throw new LuaError(e);
            }

            Class<?> returnType = isConstructor ? null : (Class<?>) returns.checkuserdata();

            XposedInit module = XposedInit.getInstance();
            if (module == null) {
                throw new LuaError("LibXposed module instance is not initialized");
            }

            XposedInterface.HookHandle hookHandle;
            if (!replace.isnil()) {
                hookHandle = module.hook(executable).intercept(chain -> {
                    try {
                        Object thisObj = chain.getThisObject();
                        List<Object> argsList = chain.getArgs();
                        Object[] argsArray = argsList.toArray();

                        LuaValue[] vargs = new LuaValue[3];
                        vargs[0] = CoerceJavaToLua.coerce(thisObj);
                        vargs[1] = CoerceJavaToLua.coerce(argsArray);
                        vargs[2] = LuaValue.NIL;

                        Varargs ret = replace.invoke(vargs);
                        if (isConstructor || returnType == void.class || ret.narg() == 0 || ret.arg1().isnil()) {
                            return null;
                        }
                        return CoerceLuaToJava.coerce(ret.arg1(), returnType);
                    } catch (Throwable e) {
                        module.log(Log.ERROR, "WeiJu2", "Error in replace hook", e);
                        Console.printErr(e);
                        throw e;
                    }
                });
            } else {
                hookHandle = module.hook(executable).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    List<Object> argsList = chain.getArgs();
                    Object[] argsArray = argsList.toArray();

                    // Before hook
                    Object result = null;
                    boolean shortCircuited = false;
                    if (!before.isnil()) {
                        try {
                            LuaValue[] vargs = new LuaValue[3];
                            vargs[0] = CoerceJavaToLua.coerce(thisObj);
                            vargs[1] = CoerceJavaToLua.coerce(argsArray);
                            vargs[2] = LuaValue.NIL;
                            Varargs ret = before.invoke(vargs);
                            if (!isConstructor && ret.narg() > 0) {
                                shortCircuited = true;
                                if (returnType != void.class && !ret.arg1().isnil()) {
                                    result = CoerceLuaToJava.coerce(ret.arg1(), returnType);
                                }
                            }
                        } catch (Throwable e) {
                            module.log(Log.ERROR, "WeiJu2", "Error in before hook", e);
                            Console.printErr(e);
                        }
                    }

                    // Proceed with original method / remaining chain
                    Throwable invocationException = null;
                    if (!shortCircuited) {
                        try {
                            result = chain.proceed(argsArray);
                        } catch (Throwable t) {
                            invocationException = t;
                        }
                    }

                    // After hook
                    if (!after.isnil()) {
                        try {
                            LuaValue[] vargs = new LuaValue[3];
                            vargs[0] = CoerceJavaToLua.coerce(thisObj);
                            vargs[1] = CoerceJavaToLua.coerce(argsArray);
                            vargs[2] = CoerceJavaToLua.coerce(result);

                            Varargs ret = after.invoke(vargs);
                            if (!isConstructor && ret.narg() > 0) {
                                if (returnType != void.class && !ret.arg1().isnil()) {
                                    result = CoerceLuaToJava.coerce(ret.arg1(), returnType);
                                } else {
                                    result = null;
                                }
                                invocationException = null; // Overriding return clears error
                            }
                        } catch (Throwable e) {
                            module.log(Log.ERROR, "WeiJu2", "Error in after hook", e);
                            Console.printErr(e);
                        }
                    }

                    if (invocationException != null) {
                        throw invocationException;
                    }
                    return result;
                });
            }

            return CoerceJavaToLua.coerce(hookHandle);
        }
    }
}
