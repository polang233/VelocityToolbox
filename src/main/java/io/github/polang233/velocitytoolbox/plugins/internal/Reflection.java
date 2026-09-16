package io.github.polang233.velocitytoolbox.plugins.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 内部反射工具；统一查找继承成员并保留原始异常。 */
public final class Reflection {
    private Reflection() {}

    static Object optionalField(Object target, String name) {
        try {
            return get(field(target.getClass(), name), target);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static Object optionalGet(Field field, Object target) {
        try {
            if (!field.trySetAccessible()) {
                return null;
            }
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    static boolean sameLoader(ClassLoader left, ClassLoader right) {
        return left != null && left == right;
    }

    public static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Missing Velocity runtime class: " + name, exception);
        }
    }

    static Constructor<?> constructor(Class<?> type, Class<?>... parameters) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method method = iface.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续看下一个接口。
            }
        }
        throw new IllegalStateException("Missing method: " + type.getName() + "." + name);
    }

    static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("Missing field: " + type.getName() + "." + name);
    }

    static Object newInstance(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw unwrap(exception);
        }
    }

    public static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw unwrap(exception);
        }
    }

    static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static RuntimeException unwrap(ReflectiveOperationException exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }
}
