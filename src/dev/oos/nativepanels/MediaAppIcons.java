package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Preserve native launcher artwork when a media session has no notification small icon. */
final class MediaAppIcons {
  private static final String MEDIA = "com.android.systemui.media.remedia.";
  private final Supplier<Object> repository;
  private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
  private final Map<IdentityKey, WeakReference<Object>> fullColor = new HashMap<>();

  MediaAppIcons(Supplier<Object> repository) { this.repository = Objects.requireNonNull(repository); }

  void install(PanelRuntime runtime, ShadeLayout layout, Class<?> composer) throws ReflectiveOperationException {
    Class<?> fallback = runtime.type(MEDIA + "data.repository.MediaRepositoryImpl$getAltIcon$2");
    Field owner = Reflect.field(fallback, "this$0");
    Method load = fallback.getDeclaredMethod("invokeSuspend", Object.class);
    Class<?> icon = runtime.type("com.android.systemui.common.shared.model.Icon");
    Class<?> loaded = runtime.type(icon.getName() + "$Loaded");
    Method draw = runtime.type("com.android.systemui.common.ui.compose.IconKt").getDeclaredMethod(
        "Icon-FNF3uiM", icon, runtime.type("androidx.compose.ui.Modifier"), long.class,
        composer, int.class, int.class);
    Object colors = runtime.type("androidx.compose.ui.graphics.Color").getField("Companion").get(null);
    long unspecified = (Long)colors.getClass().getMethod("getUnspecified-0d7_KjU").invoke(colors);
    Method foreground = runtime.type(MEDIA + "ui.compose.MediaKt$$ExternalSyntheticLambda5")
        .getDeclaredMethod("invoke", Object.class, Object.class, Object.class);

    runtime.module.hook(load).setId("native-panels:media-app-icon-source")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          // This exact native fallback obtains the application's full-color icon for
          // MediaData.userId. Its missing-package Resource fallback remains tintable.
          remember(owner.get(chain.getThisObject()), result, loaded.isInstance(result));
          return result;
        });
    runtime.module.hook(draw).setId("native-panels:media-app-icon-colors")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          if (!isFullColor(chain.getArg(0)) || layout.compositionOwner(chain.getArg(3)) == null)
            return chain.proceed();
          return chain.proceed(withoutTint(chain.getArgs().toArray(), unspecified));
        });
    // The header applies its primary color to all icons, including opaque launcher
    // artwork. Deoptimize its confirmed caller so a cached inline copy cannot bypass us.
    deoptimize(runtime, foreground);
    deoptimize(runtime, load);
    deoptimize(runtime, fallback.getDeclaredMethod("invoke", Object.class, Object.class));
  }

  synchronized void remember(Object owner, Object icon, boolean loaded) {
    drain();
    if (!loaded || icon == null || owner == null || owner != repository.get()) return;
    fullColor.put(new IdentityKey(icon, collected), new WeakReference<>(owner));
  }

  synchronized boolean isFullColor(Object icon) {
    drain();
    Object current = repository.get();
    if (current == null || icon == null) return false;
    WeakReference<Object> owner = fullColor.get(new IdentityKey(icon, null));
    return owner != null && owner.get() == current;
  }

  static Object[] withoutTint(Object[] original, long unspecified) {
    Object[] arguments = original.clone();
    arguments[2] = unspecified;
    // Force Compose to compare the changed color and keep its default-content-color
    // branch from replacing Unspecified with the same solid tint on recomposition.
    arguments[4] = (Integer)arguments[4] & ~(7 << 6);
    arguments[5] = (Integer)arguments[5] & ~4;
    return arguments;
  }

  private void drain() {
    IdentityKey key;
    while ((key = (IdentityKey)collected.poll()) != null) fullColor.remove(key);
  }

  private static void deoptimize(PanelRuntime runtime, Method method) {
    try { runtime.module.deoptimize(method); }
    catch (RuntimeException unavailable) { /* The installed hook can still serve other callers. */ }
  }

  private static final class IdentityKey extends WeakReference<Object> {
    private final int hash;
    IdentityKey(Object icon, ReferenceQueue<Object> queue) {
      super(icon, queue);
      hash = System.identityHashCode(icon);
    }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
      if (this == other) return true;
      Object icon = get();
      return icon != null && other instanceof IdentityKey key && icon == key.get();
    }
  }
}
