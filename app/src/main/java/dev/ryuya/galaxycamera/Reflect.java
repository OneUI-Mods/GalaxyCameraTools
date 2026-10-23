package dev.ryuya.galaxycamera;

import java.lang.reflect.*;

/** Explicit host contracts live in the versioned hook classes. */
final class Reflect {
  private record Signature(String name, java.util.List<Class<?>> parameters) {}

  // ClassValue releases entries with the host class loader; no global loader retention.
  private static final ClassValue<java.util.Map<String, Field>> FIELDS =
      new ClassValue<>() {
        protected java.util.Map<String, Field> computeValue(Class<?> type) {
          return new java.util.concurrent.ConcurrentHashMap<>();
        }
      };
  private static final ClassValue<java.util.Map<Signature, Method>> METHODS =
      new ClassValue<>() {
        protected java.util.Map<Signature, Method> computeValue(Class<?> type) {
          return new java.util.concurrent.ConcurrentHashMap<>();
        }
      };

  static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field cached = FIELDS.get(type).get(name);
    if (cached != null) return cached;
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        FIELDS.get(type).put(name, f);
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
    Signature signature = new Signature(name, java.util.List.of(args));
    Method cached = METHODS.get(type).get(signature);
    if (cached != null) return cached;
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        METHODS.get(type).put(signature, m);
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
