package dev.oos.nativepanels;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Hosts SystemUI's Compose media carousel and feeds it SystemUI's own filtered MediaData. */
final class CombinedMedia {
  private static final String MEDIA = "com.android.systemui.media.";
  private final PanelRuntime runtime;
  private final ShadeLayout layout;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final MediaEntryBridge entries = new MediaEntryBridge();
  private Object model, filter, listener, published;
  private MediaPlayerEvents playerEvents;
  private volatile Object repository;
  private Field userEntries;
  private Class<?> listenerType;
  private Method getState, setState, getEntries, getValue, getId, getToken, isPlaying,
      hasActive, hasAny, isSwipedAway, add, remove, addListener, removeListener;
  private boolean installed, rendered;
  private record Feed(Object repository, Object interactor) {}

  enum Scenes {
    LEGACY, EMPTY, SHARED, EXPANDED_ONLY;

    static Scenes fromCards(boolean any, boolean active, boolean dismissed) {
      if (!any) return EMPTY;
      return active && !dismissed ? SHARED : EXPANDED_ONLY;
    }

    boolean visible(boolean compact) {
      return this == SHARED || (this == EXPANDED_ONLY && !compact);
    }
  }
  private boolean listening;
  private final Runnable refresh = this::refresh;
  private final MediaEntryBridge.Store store = new MediaEntryBridge.Store() {
    public Map<?, ?> entries() throws Exception { return (Map<?, ?>)getValue.invoke(getEntries.invoke(repository)); }
    public boolean dismissed() throws Exception { return (Boolean)isSwipedAway.invoke(repository); }
    public void add(Object data) throws Exception { add.invoke(repository, data); }
    public void remove(Object id, Object data) throws Exception { remove.invoke(repository, id, data); }
  };

  CombinedMedia(PanelRuntime runtime, ShadeLayout layout) { this.runtime = runtime; this.layout = layout; }

  void install(Class<?> composer) throws Exception {
    Class<?> vm = runtime.type("com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel");
    Class<?> slotClass = runtime.type("com.android.systemui.qs.composefragment.QSFragmentComposeKt");
    Class<?> modifier = runtime.type("androidx.compose.ui.Modifier");
    Class<?> function = runtime.type("kotlin.jvm.functions.Function0");
    Class<?> renderer = runtime.type(MEDIA + "remedia.ui.compose.MediaKt");
    Class<?> factory = runtime.type(MEDIA + "remedia.ui.viewmodel.MediaViewModel$Factory");
    Class<?> style = runtime.type(MEDIA + "remedia.ui.compose.MediaPresentationStyle");
    Class<?> behavior = runtime.type(MEDIA + "remedia.ui.compose.MediaUiBehavior");
    Class<?> location = runtime.type(MEDIA + "remedia.ui.compose.Media$Location");
    Method render = renderer.getDeclaredMethod("Media", factory, style, behavior, function, modifier,
        function, function, location, function, composer, int.class, int.class);
    Method slot = slotClass.getDeclaredMethod("MediaObject", runtime.type("com.android.compose.animation.scene.ContentScope"),
        runtime.type(MEDIA + "controls.ui.view.MediaHost"), modifier, factory, style, function,
        behavior, function, location, function, composer, int.class, int.class);
    Object base = modifier.getField("Companion").get(null);
    Method makeState = runtime.type("androidx.compose.runtime.SnapshotStateKt").getMethod("mutableStateOf$default", Object.class);
    published = makeState.invoke(null, (Object)null);
    getState = runtime.type("androidx.compose.runtime.State").getMethod("getValue");
    setState = runtime.type("androidx.compose.runtime.MutableState").getMethod("setValue", Object.class);
    getValue = runtime.type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
    Class<?> data = runtime.type(MEDIA + "controls.shared.model.MediaData");
    Class<?> repo = runtime.type(MEDIA + "remedia.data.repository.MediaRepositoryImpl");
    Class<?> id = runtime.type("com.android.internal.logging.InstanceId");
    isSwipedAway = repo.getMethod("isSwipedAway");
    getEntries = repo.getMethod("getCurrentUserEntries");
    getId = data.getMethod("getInstanceId");
    getToken = data.getMethod("getToken");
    isPlaying = data.getMethod("isPlaying");
    Class<?> interactor = runtime.type(MEDIA + "remedia.domain.interactor.MediaInteractor");
    hasActive = interactor.getMethod("getHasActiveMedia");
    hasAny = interactor.getMethod("getHasAnyMedia");
    add = repo.getMethod("addCurrentUserMediaEntry", data);
    remove = repo.getMethod("removeCurrentUserMediaEntry", id, data);
    Class<?> scene = runtime.type("com.android.compose.animation.scene.ContentScope");
    Class<?> key = runtime.type("com.android.compose.animation.scene.ElementKey");
    Class<?> three = runtime.type("kotlin.jvm.functions.Function3");
    Class<?> one = runtime.type("kotlin.jvm.functions.Function1");
    Class<?> keys = runtime.type(MEDIA + "remedia.ui.compose.Media$Elements");
    Object carousel = keys.getMethod("getMediaCarousel").invoke(keys.getField("INSTANCE").get(null));
    Method element = scene.getMethod("Element", key, modifier, three, composer, int.class);
    Object compactLocation = location.getField("SHADE").get(null);
    Method remember = runtime.type("androidx.compose.runtime.internal.ComposableLambdaKt")
        .getMethod("rememberComposableLambda", int.class, boolean.class, Object.class, composer, int.class);
    Object unit = runtime.type("kotlin.Unit").getField("INSTANCE").get(null);
    Method height = runtime.type("com.android.compose.modifiers.SizeKt").getMethod("height", modifier, one);
    Method round = runtime.type("androidx.compose.ui.unit.Density").getMethod("roundToPx-0680j_4", float.class);
    Method progress = function.getMethod("invoke");
    Class<?> media = runtime.type(MEDIA + "remedia.ui.compose.Media");
    float shortDp = media.getField("COMPRESSED_HEIGHT").getFloat(null);
    float fullDp = media.getField("DEFAULT_HEIGHT").getFloat(null);
    Object compressed = style.getField("Compressed").get(null);
    Method begin = composer.getMethod("startReplaceGroup", int.class);
    Method end = composer.getMethod("endReplaceGroup");
    // MediaObject on this ROM retained only the old AndroidView branch, but still
    // receives all the dependencies for the Android 17 Compose player.
    runtime.module.hook(slot).setId("native-panels:systemui-media").intercept(c -> {
      Object composition = c.getArg(10);
      if (layout.compositionOwner(composition) == null) return c.proceed();
      Scenes scenes = scenes();
      if (scenes == Scenes.LEGACY) return c.proceed();
      Object where = c.getArg(8);
      boolean compact = where == compactLocation;
      // These groups keep a disappearing slot from retaining its old scene element.
      begin.invoke(composition, 0x14c1d74 + scenes.ordinal());
      try {
        if (!scenes.visible(compact)) return null;
        Object placement = c.getArg(2) == null ? base : c.getArg(2);
        Object vmFactory = c.getArg(3), presentation = c.getArg(4), dismiss = c.getArg(5), uiBehavior = c.getArg(6);
        Object visible = c.getArg(7), expansion = c.getArg(9);
        rendered = true;
        if (scenes == Scenes.EXPANDED_ONLY) {
          // QuickSettingsContent already owns the grid's native fade and anchored translation.
          // An independent shared element with no visible QQS counterpart can switch in at the endpoint.
          render.invoke(null, vmFactory, presentation, uiBehavior, dismiss, placement, visible, null,
              where, expansion, composition, 0, 0x40);
          return null;
        }
        if (presentation == compressed) {
          Object measure = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{one}, (proxy, m, args) ->
              switch (m.getName()) {
                case "invoke" -> round.invoke(args[0], mediaHeight(shortDp, fullDp, (Float)progress.invoke(expansion)));
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "QS compressed media height";
                default -> throw new UnsupportedOperationException(m.getName());
              });
          placement = height.invoke(null, placement, measure);
        }
        // Both scenes must carry precisely the same SystemUI element key. The
        // scene engine then uses the intermediate placement for drawing AND input.
        // Capture normal arguments, never the hook chain or its original Composer.
        Object block = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{three}, (proxy, m, args) ->
            switch (m.getName()) {
              case "invoke" -> {
                render.invoke(null, vmFactory, presentation, uiBehavior, dismiss, base, visible, null, where,
                    expansion, args[1], 0, 0x40);
                yield unit;
              }
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              case "toString" -> "SystemUI media carousel";
              default -> throw new UnsupportedOperationException(m.getName());
            });
        Object content = remember.invoke(null, 0x14c1d76, true, block, composition, 0x36);
        element.invoke(c.getArg(0), carousel, placement, content, composition, 0);
        return null;
      } finally { end.invoke(composition); }
    });
    for (String name : new String[]{"getQqsMediaVisible", "getQsMediaVisible"}) {
      boolean quick = name.equals("getQqsMediaVisible");
      runtime.module.hook(vm.getDeclaredMethod(name)).setId("native-panels:" + name).intercept(c -> {
        if (c.getThisObject() != model) return c.proceed();
        Scenes scenes = scenes();
        if (scenes == Scenes.LEGACY) return c.proceed();
        return scenes.visible(quick);
      });
    }
    try { runtime.module.deoptimize(runtime.type("com.android.systemui.qs.composefragment.QSFragmentCompose$$ExternalSyntheticLambda13")
        .getDeclaredMethod("invoke", Object.class, Object.class)); } catch (ReflectiveOperationException ignored) {}
    // The snapshot observer also reads QQS visibility and measured height outside composition.
    // It must see the same result as the media slot, even on an AOT-compiled SystemUI.
    try { runtime.module.deoptimize(runtime.type("com.android.systemui.qs.composefragment.QSFragmentCompose$setListenerCollections$1$1$$ExternalSyntheticLambda0")
        .getDeclaredMethod("invoke")); } catch (ReflectiveOperationException e) { layout.warn(e); }
    try { installTransportGlyph(renderer, composer); }
    catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
    try { new MediaCarouselCorners(layout::hapticOwner).install(runtime, layout, renderer, composer); }
    catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
    try { new MediaMetadataText(() -> repository).install(runtime); }
    catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
    try { new MediaAppIcons(() -> repository).install(runtime, layout, composer); }
    catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
    try {
      MediaPlayerEvents events = new MediaPlayerEvents(this::playerEventOwner);
      events.install(runtime);
      playerEvents = events;
    } catch (ReflectiveOperationException unavailable) { layout.warn(unavailable); }
    // Some builds already feed the Compose repository. Only bridge legacy-filter
    // entries on builds which actually select LegacyMediaDataManagerImpl.
    try {
      Class<?> legacyFilter = runtime.type(MEDIA + "controls.domain.pipeline.LegacyMediaDataFilterImpl");
      userEntries = Reflect.field(legacyFilter, "userEntries");
      listenerType = runtime.type(MEDIA + "controls.domain.pipeline.MediaDataManager$Listener");
      addListener = legacyFilter.getMethod("addListener", listenerType);
      removeListener = legacyFilter.getMethod("removeListener", listenerType);
    } catch (ReflectiveOperationException e) { layout.warn(e); }
    installed = true;
  }

  // The OOS media action carries the source Icon, but the Compose model discards its ID
  // when converting it to a Drawable. Keep the provenance without changing the shared model.
  private void installTransportGlyph(Class<?> renderer, Class<?> composer) throws Exception {
    Class<?> action = runtime.type(MEDIA + "controls.shared.model.MediaAction");
    Class<?> modelAction = runtime.type(MEDIA + "remedia.domain.model.MediaActionModel$Action");
    Class<?> icon = runtime.type("com.android.systemui.common.shared.model.Icon");
    Class<?> description = runtime.type("com.android.systemui.common.shared.model.ContentDescription");
    Class<?> button = runtime.type(MEDIA + "remedia.ui.viewmodel.MediaPlayPauseActionViewModel");
    Class<?> state = runtime.type(MEDIA + "remedia.shared.model.MediaSessionState");
    Class<?> click = runtime.type("kotlin.jvm.functions.Function0");
    Method convert = runtime.type(MEDIA + "remedia.domain.interactor.MediaInteractorImpl")
        .getDeclaredMethod("getMediaActionModel", action, Integer.class);
    Method getExtra = action.getMethod("getMediaActionEx");
    Method getSource = getExtra.getReturnType().getMethod("getIcon");
    Method modelIcon = modelAction.getMethod("getIcon");
    Method draw = renderer.getDeclaredMethod("PlayPauseAction-Y0xEhic",
        runtime.type("com.android.compose.animation.scene.ContentScope"), button, long.class, long.class,
        runtime.type("kotlin.jvm.functions.Function1"), runtime.type("androidx.compose.ui.Modifier"), composer, int.class);
    Method getIcon = button.getMethod("getIcon"), getState = button.getMethod("getState");
    Method getClick = button.getMethod("getOnClick"), getDescription = icon.getMethod("getContentDescription");
    Method copy = button.getMethod("copy", state, icon, click);
    var resourceIcon = runtime.type("com.android.systemui.common.shared.model.Icon$Resource")
        .getConstructor(int.class, description);
    Map<Object, Icon> sources = Collections.synchronizedMap(new WeakHashMap<>());
    runtime.module.hook(convert).setId("native-panels:media-action-source").intercept(chain -> {
      Object result = chain.proceed();
      if (modelAction.isInstance(result)) {
        Object extra = getExtra.invoke(chain.getArg(0));
        Icon source = extra == null ? null : (Icon)getSource.invoke(extra);
        if (source != null && source.getType() == Icon.TYPE_RESOURCE)
          sources.put(modelIcon.invoke(result), source);
      }
      return result;
    });
    runtime.module.hook(draw).setId("native-panels:qs-transport-glyph").intercept(chain -> {
      Object vm = chain.getArg(1), oldIcon = getIcon.invoke(vm);
      Icon source = sources.get(oldIcon);
      if (source == null) return chain.proceed();
      ShadeDecor owner = layout.compositionOwner(chain.getArg(6));
      if (owner == null) return chain.proceed();
      Context context = owner.frame.getContext();
      String pkg = context.getPackageName();
      if (!pkg.equals(source.getResPackage())) return chain.proceed();
      var res = context.getResources();
      String entry;
      try { entry = res.getResourceEntryName(source.getResId()); }
      catch (android.content.res.Resources.NotFoundException unavailable) { return chain.proceed(); }
      if (!isOosTransportContainer(entry)) return chain.proceed();
      // This native AVD starts as play and ends as pause. The standard renderer
      // supplies atEnd from the session state, including on a mid-animation reversal.
      int glyph = res.getIdentifier("ic_media_play_button", "drawable", pkg);
      if (glyph == 0) return chain.proceed();
      Object replacement = resourceIcon.newInstance(glyph, getDescription.invoke(oldIcon));
      Object[] args = chain.getArgs().toArray();
      args[1] = copy.invoke(vm, getState.invoke(vm), replacement, getClick.invoke(vm));
      args[7] = (Integer)args[7] & ~(7 << 3);
      return chain.proceed(args);
    });
  }

  static boolean isOosTransportContainer(String entry) {
    return "qs_media_container_action_play".equals(entry) || "qs_media_container_action_pause".equals(entry);
  }

  static float mediaHeight(float compact, float expanded, float progress) {
    float t = Float.isFinite(progress) ? Math.max(0f, Math.min(1f, progress)) : 0f;
    return compact + (expanded - compact) * t;
  }

  private Scenes scenes() throws ReflectiveOperationException {
    Feed feed = (Feed)getState.invoke(published);
    if (feed == null) return Scenes.LEGACY;
    boolean any = (Boolean)hasAny.invoke(feed.interactor);
    boolean dismissed = any && (Boolean)isSwipedAway.invoke(feed.repository);
    boolean active = any && !dismissed && (Boolean)hasActive.invoke(feed.interactor);
    return Scenes.fromCards(any, active, dismissed);
  }

  void bind(Object owner) { stop(); model = owner; rendered = false; }

  void start(Context context) {
    if (!installed || model == null || runtime.locked()) return;
    if (listening) { refresh(); return; }
    try {
      if (Settings.Global.getInt(context.getContentResolver(), "qs_media_controls", 1) <= 0) return;
      repository = runtime.provider("providesMediaRepositoryProvider");
      Object manager = runtime.provider("providesMediaDataManagerProvider");
      Field source = Reflect.optionalField(manager.getClass(), "mediaDataFilter");
      if (source != null && userEntries != null && addListener != null && removeListener != null) {
        Object candidate = source.get(manager);
        if (userEntries.getDeclaringClass().isInstance(candidate)) filter = candidate;
      }
      listening = true;
      if (filter != null) {
        // Register after bootstrapping: addListener synchronously publishes OOS's
        // current accepted entry, and subsequent callbacks include final filtering.
        for (Map.Entry<?, ?> entry : ((Map<?, ?>)userEntries.get(filter)).entrySet()) {
          entries.loaded(entry.getKey(), null, mediaEntry(entry.getValue()));
        }
        listener = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{listenerType}, (proxy, method, args) ->
            switch (method.getName()) {
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              case "toString" -> "Native panels media listener";
              case "onMediaDataLoaded", "onMediaDataRemoved" -> {
                Object[] event = args.clone();
                if (Looper.myLooper() == main.getLooper()) sourceChanged(proxy, method.getName(), event);
                else main.post(() -> sourceChanged(proxy, method.getName(), event));
                yield null;
              }
              default -> null;
            });
        addListener.invoke(filter, listener);
      }
      refresh();
      setState.invoke(published, new Feed(repository, runtime.provider("providesMediaInteractorProvider")));
    } catch (Throwable e) { stop(); layout.warn(e); }
  }

  private void refresh() {
    if (!listening || filter == null) return;
    try { entries.refresh(store); }
    catch (Exception e) { layout.warn(e); }
  }

  private Object playerEventOwner() {
    // UI detachment/locking stops our Compose feed, but must not leave the OEM
    // source cache stale for the next attach. Resolve only on an actual empty event.
    if (!installed || playerEvents == null || runtime.combinedQs.status != R.string.combined_selected) return null;
    try { return playerEvents.playerManager(runtime.provider("providesMediaDataManagerProvider")); }
    catch (Throwable unavailable) { layout.warn(unavailable); return null; }
  }

  private MediaEntryBridge.Entry mediaEntry(Object data) throws ReflectiveOperationException {
    return new MediaEntryBridge.Entry(data, getId.invoke(data), getToken.invoke(data),
        Boolean.TRUE.equals(isPlaying.invoke(data)));
  }

  private void sourceChanged(Object owner, String event, Object[] args) {
    if (!listening || listener != owner) return;
    try {
      if (event.equals("onMediaDataLoaded")) entries.loaded(args[0], args[1], mediaEntry(args[2]));
      else entries.removed(args[0]);
      main.removeCallbacks(refresh);
      main.post(refresh);
    } catch (ReflectiveOperationException e) { layout.warn(e); }
  }

  static boolean canSupply(Object current, Object previous) {
    return current == null || current == previous;
  }

  void onShadeLocked() {
    if (listening) stop();
  }

  void stop() {
    main.removeCallbacks(refresh);
    listening = false;
    Object oldListener = listener;
    listener = null;
    if (oldListener != null && filter != null) {
      try { removeListener.invoke(filter, oldListener); }
      catch (ReflectiveOperationException e) { layout.warn(e); }
    }
    filter = null;
    if (published != null) {
      try { setState.invoke(published, (Object)null); }
      catch (Exception e) { layout.warn(e); }
    }
    try { entries.clear(repository == null ? null : store); }
    catch (Exception e) { layout.warn(e); }
    repository = null;
  }

  String report() {
    String state = "unavailable";
    if (installed) {
      try { state = scenes().name(); } catch (ReflectiveOperationException ignored) {}
    }
    return "QS media: listening=" + listening + " bridged=" + entries.size()
        + " nativeRendered=" + rendered + " scenes=" + state;
  }
}
