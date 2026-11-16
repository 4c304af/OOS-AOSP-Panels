package dev.oos.nativepanels;

import java.lang.reflect.*;

/** A separate Bluetooth ViewModel shares services, but owns its dialog job and error handler. */
final class SafeScope {
  private static final String[] FIELDS = {
    "deviceItemInteractor",
    "deviceItemActionInteractor",
    "bluetoothStateInteractor",
    "bluetoothAutoOnInteractor",
    "audioSharingInteractor",
    "audioModeInteractor",
    "audioSharingButtonViewModelFactory",
    "bluetoothDeviceMetadataInteractor",
    "dialogTransitionAnimator",
    "logger",
    "coroutineScope",
    "mainDispatcher",
    "backgroundDispatcher",
    "sharedPreferences",
    "bluetoothDialogDelegateFactory",
    "bluetoothDetailsContentManagerFactory"
  };

  static Object copyBluetoothModel(PanelRuntime runtime, Object original) throws Throwable {
    Object[] args = new Object[FIELDS.length];
    for (int i = 0; i < args.length; i++) args[i] = Reflect.get(original, FIELDS[i]);
    args[10] = isolated(runtime, args[10]);
    for (Constructor<?> c : original.getClass().getDeclaredConstructors()) {
      if (c.getParameterCount() != args.length) continue;
      Class<?>[] types = c.getParameterTypes();
      boolean match = true;
      for (int i = 0; i < args.length; i++)
        if (args[i] != null && !types[i].isInstance(args[i])) match = false;
      if (!match) continue;
      c.setAccessible(true);
      try {
        return c.newInstance(args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
    throw new NoSuchMethodException(runtime.text(R.string.bt_model_error));
  }

  private static Object isolated(PanelRuntime r, Object original) throws Throwable {
    Class<?> context = r.type("kotlin.coroutines.CoroutineContext");
    Class<?> element = r.type("kotlin.coroutines.CoroutineContext$Element");
    Class<?> keyClass = r.type("kotlin.coroutines.CoroutineContext$Key");
    Class<?> handlerType = r.type("kotlinx.coroutines.CoroutineExceptionHandler");
    Object handlerKey = handlerType.getField("Key").get(null);
    Class<?> defaults = r.type("kotlin.coroutines.CoroutineContext$Element$DefaultImpls");
    Object handler =
        Proxy.newProxyInstance(
            r.loader,
            new Class<?>[] {handlerType},
            (proxy, method, args) -> {
              String name = method.getName();
              if (name.equals("handleException")) {
                r.failure("bluetooth", (Throwable) args[1]);
                return null;
              }
              if (name.equals("getKey")) return handlerKey;
              if (name.equals("toString")) return "OOSNativePanelsBluetoothExceptionHandler";
              if (name.equals("hashCode")) return System.identityHashCode(proxy);
              if (name.equals("equals")) return proxy == args[0];
              Class<?>[] old = method.getParameterTypes();
              Class<?>[] signature = new Class<?>[old.length + 1];
              signature[0] = element;
              System.arraycopy(old, 0, signature, 1, old.length);
              Object[] values = new Object[old.length + 1];
              values[0] = proxy;
              if (args != null) System.arraycopy(args, 0, values, 1, args.length);
              return Reflect.invoke(Reflect.method(defaults, name, signature), null, values);
            });
    Object current = Reflect.call(original, "getCoroutineContext");
    Class<?> job = r.type("kotlinx.coroutines.Job");
    Object jobKey = job.getField("Key").get(null);
    current = Reflect.invoke(Reflect.method(context, "minusKey", keyClass), current, jobKey);
    Object supervisor =
        Reflect.invoke(
            Reflect.method(r.type("kotlinx.coroutines.SupervisorKt"), "SupervisorJob", job),
            null,
            (Object) null);
    Method plus = Reflect.method(context, "plus", context);
    current = Reflect.invoke(plus, current, supervisor);
    current = Reflect.invoke(plus, current, handler);
    return Reflect.invoke(
        Reflect.method(r.type("kotlinx.coroutines.CoroutineScopeKt"), "CoroutineScope", context),
        null,
        current);
  }
}
