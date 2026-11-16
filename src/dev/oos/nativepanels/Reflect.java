package dev.oos.nativepanels;

import java.lang.reflect.*;

final class Reflect {
  static Field field(Class<?> cls, String name) throws NoSuchFieldException {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(cls.getName() + "." + name);
  }

  static Field optionalField(Class<?> cls, String name) {
    try { return field(cls, name); }
    catch (NoSuchFieldException absent) { return null; }
  }

  static Object get(Object obj, String name) throws Exception {
    return field(obj.getClass(), name).get(obj);
  }

  static Method method(Class<?> cls, String name, Class<?>... args) throws NoSuchMethodException {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
      } catch (NoSuchMethodException ignored) {
      }
    }
    throw new NoSuchMethodException(cls.getName() + "." + name);
  }

  static Object call(Object obj, String name) throws Throwable {
    return invoke(method(obj.getClass(), name), obj);
  }

  static Object invoke(Method m, Object obj, Object... args) throws Throwable {
    try {
      return m.invoke(obj, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
