package dev.ryuya.galaxycamera;

import java.lang.reflect.*;

/** Explicit host contracts live in the versioned hook classes. */
final class Reflect {
  static Field field(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(type.getName() + "#" + name);
  }

  static Object get(Object target, String name) throws ReflectiveOperationException {
    return field(target instanceof Class<?> c ? c : target.getClass(), name)
        .get(target instanceof Class<?> ? null : target);
  }

  static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
      } catch (NoSuchMethodException ignored) {
      }
    }
    throw new NoSuchMethodException(type.getName() + "#" + name);
  }

  static Object call(Object target, String name) throws ReflectiveOperationException {
    return method(target.getClass(), name).invoke(target);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static Object value(Class<?> type, String name) {
    return Enum.valueOf((Class) type, name);
  }
}
