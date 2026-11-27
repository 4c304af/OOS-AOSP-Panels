package dev.oos.nativepanels;

import android.content.Context;
import android.provider.Settings;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/** Completes OOS's player-list withdrawal event for the currently bridged manager. */
final class MediaPlayerEvents {
  @FunctionalInterface interface Notification { void send() throws ReflectiveOperationException; }
  @FunctionalInterface interface Cache { List<?> read() throws ReflectiveOperationException; }

  private final Supplier<Object> owner;
  private Field extension, playerManager, context;

  MediaPlayerEvents(Supplier<Object> owner) { this.owner = owner; }

  void install(PanelRuntime runtime) throws ReflectiveOperationException {
    String pipeline = "com.oplus.systemui.media.controls.pipeline.";
    Class<?> callback = runtime.type(pipeline + "OplusMediaPlayerManager$oplusMediaDataCallback$1");
    Class<?> manager = runtime.type(pipeline + "OplusMediaPlayerManager");
    Field target = Reflect.field(callback, "this$0");
    Method changed = callback.getMethod("onPlayerInfoChange", List.class);
    Method cache = manager.getMethod("getOplusMediaPlayerDataInfoList");
    Method notify = manager.getMethod("notifyMediaPlayerDataInfoChanged", int.class, List.class);
    Method user = runtime.type("com.android.systemui.oplusutils.OsBinderCacheUtils")
        .getMethod("getCurrentUserId");
    extension = Reflect.field(runtime.type(
        "com.android.systemui.media.controls.domain.pipeline.LegacyMediaDataManagerImpl"), "mediaDataManagerEx");
    context = Reflect.field(extension.getDeclaringClass(), "context");
    playerManager = Reflect.field(runtime.type(pipeline + "OplusMediaDataManagerExImpl"), "mediaPlayerDataInfo");
    runtime.module.hook(changed).setId("native-panels:media-empty-player-event")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          // Retain the native subscription maintenance, then complete its empty branch.
          Object result = chain.proceed();
          List<?> event = (List<?>)chain.getArg(0);
          if (event == null || !event.isEmpty()) return result;
          Object current = target.get(chain.getThisObject());
          forwardEmpty(current, event, () -> (List<?>)cache.invoke(current),
              () -> notify.invoke(current, user.invoke(null), List.of()));
          return result;
        });
  }

  Object playerManager(Object legacyManager) throws IllegalAccessException {
    if (!extension.getDeclaringClass().isInstance(legacyManager)) return null;
    Context current = (Context)context.get(legacyManager);
    if (Settings.Global.getInt(current.getContentResolver(), "qs_media_controls", 1) <= 0) return null;
    Object extra = extension.get(legacyManager);
    return playerManager.getDeclaringClass().isInstance(extra) ? playerManager.get(extra) : null;
  }

  boolean forwardEmpty(Object target, List<?> event, Cache cached, Notification notification)
      throws ReflectiveOperationException {
    if (event == null || !event.isEmpty()) return false;
    Object active = owner.get();
    if (active == null || active != target) return false;
    // The OEM event contains the complete player snapshot, not an active-session
    // query. Its existing listener diffs this list and removes only its own players.
    cached.read().clear();
    notification.send();
    return true;
  }
}
