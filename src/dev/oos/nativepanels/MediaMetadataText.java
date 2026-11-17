package dev.oos.nativepanels;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;

/** Normalizes absent text only while our Compose repository converts its source MediaData. */
final class MediaMetadataText {
  private final Supplier<Object> repository;
  private final ThreadLocal<Object> converting = new ThreadLocal<>();

  @FunctionalInterface interface Conversion { Object run() throws Throwable; }

  MediaMetadataText(Supplier<Object> repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  void install(PanelRuntime runtime) throws ReflectiveOperationException {
    String media = "com.android.systemui.media.";
    Class<?> conversion = runtime.type(media + "remedia.data.repository.MediaRepositoryImpl$toDataModel$2");
    Field source = Reflect.field(conversion, "$this_toDataModel");
    Field owner = Reflect.field(conversion, "this$0");
    Method invoke = Reflect.method(conversion, "invokeSuspend", Object.class);
    Class<?> data = runtime.type(media + "controls.shared.model.MediaData");
    Method[] text = {data.getMethod("getApp"), data.getMethod("getSong"), data.getMethod("getArtist")};

    runtime.module.hook(invoke).setId("native-panels:media-text-conversion")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
            convert(owner.get(chain.getThisObject()), source.get(chain.getThisObject()), chain::proceed));
    for (Method getter : text) {
      runtime.module.hook(getter).setId("native-panels:media-text-" + getter.getName())
          .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
              value(chain.getThisObject(), chain.proceed()));
    }
    // The ROM calls these tiny getters before String.valueOf and then stores the
    // resulting strings across a possible suspension. Do not hook String itself.
    try { runtime.module.deoptimize(invoke); }
    catch (RuntimeException unavailable) { /* Keep an installed hook usable on another ART build. */ }
  }

  Object convert(Object owner, Object source, Conversion original) throws Throwable {
    Object previous = converting.get();
    Object current = repository.get();
    if (current != null && current == owner && source != null) converting.set(source);
    else converting.remove();
    try { return original.run(); }
    finally {
      // A suspended coroutine may resume on a different thread. Never carry its
      // source beyond this invocation, and preserve an outer nested conversion.
      if (previous == null) converting.remove();
      else converting.set(previous);
    }
  }

  Object value(Object source, Object value) {
    // Keep actual text "null", styled text, and every unrelated native read intact.
    return value == null && source != null && converting.get() == source ? "" : value;
  }
}
