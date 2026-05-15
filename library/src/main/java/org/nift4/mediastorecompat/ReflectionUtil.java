package org.nift4.mediastorecompat;

import java.lang.reflect.Method;
import java.util.Objects;

/* package */ class ReflectionUtil {
    public static void enableReflection() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod",
                    String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null,
                    "dalvik.system.VMRuntime");
            Method getRuntime = Objects.requireNonNull((Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", null));
            Method setHiddenApiExemptions = Objects.requireNonNull((Method)
                    getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions",
                            new Class[]{String[].class}));
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, (Object) new String[]{"L"});
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
