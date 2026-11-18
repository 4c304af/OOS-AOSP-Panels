package dev.oos.nativepanels;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supplies the native editor with the same available tiles and artwork as the OOS editor. */
final class CombinedEditorTiles {
  private static final long QUERY_TIMEOUT_MS = 4000;
  private final PanelRuntime runtime;
  private Field currentTiles, stateLabel, queryHandler, queryCache;
  private Method currentUser, userContext, currentSpecs, flowValue, specName, editingState;
  private Method query, clearQuery, listen, allTiles, infoSpec, infoState, infoVisible;
  private Method iconProvider, providerIcon, iconDrawable;
  private Method tileDestroyed, destroyTile;
  private Method tileMappers, mappedSpecs;
  private Method dataSpec, dataIcon, dataLabel, dataAppName, dataAppIcon, dataCategory;
  private Constructor<?> dataConstructor, loadedIcon, loadedText, loadedDescription;
  private Constructor<?> mapperConstructor;
  private Class<?> listenerType, customTile;
  private XposedInterface.Invoker<?, Method> startOriginal;
  private volatile Session session;
  private volatile String status = "not installed";
  private int queries, completed, filtered, customFiltered, labels, icons;

  CombinedEditorTiles(PanelRuntime runtime) { this.runtime = runtime; }

  void install() throws ReflectiveOperationException {
    Class<?> edit = runtime.type("com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel");
    editingState = edit.getMethod("isEditing");
    currentTiles = Reflect.field(edit, "currentTilesInteractor");
    Class<?> interactor = currentTiles.getType();
    currentUser = interactor.getMethod("getUserId");
    userContext = interactor.getMethod("getUserContext");
    currentSpecs = interactor.getMethod("getCurrentTilesSpecs");
    flowValue = runtime.type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
    Class<?> spec = runtime.type("com.android.systemui.qs.pipeline.shared.TileSpec");
    specName = spec.getMethod("getSpec");

    Class<?> helper = runtime.type("com.oplus.systemui.qs.customize.OplusTileQueryHelper");
    listenerType = runtime.type("com.android.systemui.plugins.qs.QSTileQueryHelper$ColorTileStateListener");
    query = helper.getMethod("queryTiles");
    clearQuery = helper.getMethod("clearCacheTile");
    listen = helper.getMethod("setColorTileStateListener", listenerType);
    allTiles = helper.getMethod("getAllTiles");
    queryHandler = Reflect.field(helper, "mBgHandler");
    queryCache = Reflect.field(helper, "mCacheTiles");
    Class<?> tile = runtime.type("com.android.systemui.plugins.qs.QSTile");
    tileDestroyed = tile.getMethod("isDestroyed");
    destroyTile = tile.getMethod("destroy");
    customTile = runtime.type("com.android.systemui.qs.external.CustomTile");
    Class<?> mapper = runtime.type("com.oplus.systemui.qs.pipeline.domain.operate.QSTileMapperProvider");
    mapperConstructor = mapper.getConstructor(Resources.class);
    tileMappers = mapper.getMethod("getTileMappers");
    mappedSpecs = runtime.type("javax.inject.Provider").getMethod("get");
    Class<?> info = runtime.type("com.android.systemui.plugins.qs.QSTileQueryHelper$OplusTileInfo");
    infoSpec = info.getMethod("getSpec");
    infoState = info.getMethod("getState");
    infoVisible = info.getMethod("getVisible");
    Class<?> state = runtime.type("com.android.systemui.plugins.qs.QSTile$State");
    stateLabel = state.getField("label");
    iconProvider = runtime.type("com.android.systemui.qs.panels.ui.viewmodel.TileUiStateKt")
        .getMethod("toIconProvider", state);
    providerIcon = runtime.type("com.android.systemui.qs.panels.ui.viewmodel.IconProvider").getMethod("getIcon");
    iconDrawable = runtime.type("com.android.systemui.plugins.qs.QSTile$Icon").getMethod("getDrawable", Context.class);
    Class<?> commonIcon = runtime.type("com.android.systemui.common.shared.model.Icon");
    Class<?> text = runtime.type("com.android.systemui.common.shared.model.Text");
    Class<?> description = runtime.type("com.android.systemui.common.shared.model.ContentDescription");
    loadedIcon = runtime.type(commonIcon.getName() + "$Loaded").getConstructor(Drawable.class, description);
    loadedText = runtime.type(text.getName() + "$Loaded").getConstructor(String.class);
    loadedDescription = runtime.type(description.getName() + "$Loaded").getConstructor(String.class);
    Class<?> data = runtime.type("com.android.systemui.qs.panels.shared.model.EditTileData");
    Class<?> category = runtime.type("com.android.systemui.qs.shared.model.TileCategory");
    dataConstructor = data.getConstructor(spec, commonIcon, text, text, commonIcon, category);
    dataSpec = data.getMethod("getTileSpec");
    dataIcon = data.getMethod("getIcon");
    dataLabel = data.getMethod("getLabel");
    dataAppName = data.getMethod("getAppName");
    dataAppIcon = data.getMethod("getAppIcon");
    dataCategory = data.getMethod("getCategory");
    Constructor<?> model = runtime.type("com.android.systemui.qs.panels.domain.model.EditTilesModel")
        .getConstructor(List.class, List.class);

    Method start = edit.getMethod("startEditing");
    startOriginal = runtime.module.getInvoker(start).setType(XposedInterface.Invoker.Type.ORIGIN);
    runtime.module.hook(start).setId("native-panels:edit-query").intercept(chain -> {
      if (!runtime.combinedQs.layout.hasCombinedGrid()) return chain.proceed();
      Object owner = chain.getThisObject();
      try {
        if (!Boolean.TRUE.equals(flowValue.invoke(editingState.invoke(owner)))) {
          if (session != null && session.owner.get() == owner && session.gate.pending() && session.current()) return null;
          cancel();
          begin(owner);
          return null;
        }
      } catch (ReflectiveOperationException | RuntimeException failure) {
        cancel();
        status = "query unavailable: " + failure;
        runtime.module.log(Log.WARN, "OOSNativePanels", "Editor tile query", failure);
      }
      return chain.proceed();
    });
    runtime.module.hook(edit.getMethod("stopEditing")).setId("native-panels:edit-query-cancel").intercept(chain -> {
      if (session != null && session.owner.get() == chain.getThisObject()) cancel();
      return chain.proceed();
    });
    runtime.module.hook(model).setId("native-panels:edit-tile-metadata").intercept(chain -> {
      Session active = session;
      if (active == null || active.catalog == null || !active.current()) return chain.proceed();
      Object[] arguments = null;
      try {
        List<?> replacement = presentPlatform((List<?>)chain.getArg(0), active.catalog);
        List<?> custom = presentCustom((List<?>)chain.getArg(1), replacement, active.catalog);
        // Surviving services retain their own app identity, metadata and actions.
        arguments = new Object[]{replacement, custom};
      } catch (ReflectiveOperationException | RuntimeException failure) {
        status = "metadata unavailable: " + failure;
      }
      // Never retry the native constructor if its own implementation throws.
      return arguments == null ? chain.proceed() : chain.proceed(arguments);
    });
    deoptimize("com.android.systemui.qs.panels.domain.interactor.EditTilesListInteractor", "getTilesToEdit");
    deoptimize("com.android.systemui.qs.panels.domain.interactor.EditTilesListInteractor$getTilesToEdit$1", "invokeSuspend");
    deoptimize("com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel", "onButtonClick");
    deoptimize("com.android.systemui.qs.panels.ui.viewmodel.toolbar.EditModeButtonViewModel$onButtonClick$1", "invokeSuspend");
    try { installRecorderPreview(interactor, tile, state, spec, commonIcon, category); }
    catch (ReflectiveOperationException absent) { /* Other editor versions keep their native preview. */ }
    status = "ready";
  }

  private void installRecorderPreview(Class<?> interactor, Class<?> tile, Class<?> state, Class<?> spec,
      Class<?> icon, Class<?> category) throws ReflectiveOperationException {
    Class<?> text = runtime.type("androidx.compose.ui.text.AnnotatedString");
    Constructor<?> preview = runtime.type("com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel")
        .getConstructor(spec, icon, text, text, text, icon, boolean.class, boolean.class, Set.class, category);
    Method current = interactor.getMethod("getCurrentQSTiles");
    Method tileName = tile.getMethod("getTileSpec"), tileState = tile.getMethod("getState");
    Field capability = state.getField("dualTarget");
    runtime.module.hook(preview).setId("native-panels:edit-recorder-targets").intercept(chain -> {
      Session active = session;
      if (active == null || !(boolean)chain.getArg(6) || (boolean)chain.getArg(7)) return chain.proceed();
      String name = (String)specName.invoke(chain.getArg(0));
      if (!CombinedTileClicks.recorderSpec(name) || !active.current()) return chain.proceed();
      // The editor rebuilds its current tiles after Add and Resize. Read the same live backend as the grid.
      for (Object backend : (List<?>)current.invoke(active.source)) {
        if (CombinedTileClicks.recorderBackend(name, backend.getClass().getName())
            && name.equals(tileName.invoke(backend)) && capability.getBoolean(tileState.invoke(backend))) {
          Object[] args = chain.getArgs().toArray();
          args[7] = true;
          return chain.proceed(args);
        }
      }
      return chain.proceed();
    });
    deoptimize("com.android.systemui.qs.panels.ui.viewmodel.UnloadedEditTileViewModel", "load");
  }

  private void begin(Object owner) throws ReflectiveOperationException {
    Object source = currentTiles.get(owner);
    int user = (Integer)flowValue.invoke(currentUser.invoke(source));
    Context context = (Context)flowValue.invoke(userContext.invoke(source));
    Object graph = runtime.graph;
    if (graph == null) throw new IllegalStateException("SystemUI graph is not ready");
    Object factory = Reflect.method(graph.getClass(), "oplusTileQueryHelperFactory").invoke(graph);
    Object helper = factory.getClass().getMethod("create").invoke(factory);
    Handler background = (Handler)queryHandler.get(helper);
    Session next = new Session(owner, source, context, user, helper);
    session = next;
    Object listener = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
      if (method.getName().equals("onStockTilesChanged")) {
        if (!next.gate.pending() || !next.current() || next.helper == null) return null;
        try {
          next.gate.complete(snapshot(next));
        } catch (ReflectiveOperationException | RuntimeException failure) {
          status = "metadata query failed: " + failure;
          next.gate.timeout();
        }
      }
      if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
      if (method.getName().equals("equals")) return proxy == args[0];
      if (method.getName().equals("toString")) return "NativePanelsEditorQuery";
      return null;
    });
    try {
      listen.invoke(helper, listener);
      queries++;
      status = "querying";
      runtime.main.postDelayed(next.timeout, QUERY_TIMEOUT_MS);
      query.invoke(helper);
    } finally {
      // Native query work enqueues temporary-tile destruction on this looper.
      // Two queue hops also allow those nested destroy messages to finish before
      // clearing the helper, including after cancellation or a UI timeout.
      background.post(() -> background.post(() -> runtime.main.post(next::drained)));
    }
  }

  private Catalog snapshot(Session active) throws ReflectiveOperationException {
    Map<String, Presentation> presentation = new LinkedHashMap<>();
    Set<String> available = new LinkedHashSet<>(), selected = new LinkedHashSet<>();
    for (Object selectedSpec : (List<?>)currentSpecs.invoke(active.source))
      selected.add((String)specName.invoke(selectedSpec));
    for (Object info : (List<?>)allTiles.invoke(active.helper)) {
      String spec = (String)infoSpec.invoke(info);
      if (spec == null || spec.isEmpty()) continue;
      if ((boolean)infoVisible.invoke(info)) available.add(spec);
      if (spec.startsWith("custom(")) continue;
      Object state = infoState.invoke(info);
      CharSequence nativeLabel = state == null ? null : (CharSequence)stateLabel.get(state);
      if (nativeLabel == null || nativeLabel.length() == 0) continue;
      String label = nativeLabel.toString();
      Object icon = null;
      try {
        Object nativeIcon = providerIcon.invoke(iconProvider.invoke(null, state));
        Drawable drawable = nativeIcon == null ? null : (Drawable)iconDrawable.invoke(nativeIcon, active.context);
        Drawable copy = independentIcon(drawable, active.context.getResources());
        if (copy != null) {
          icon = loadedIcon.newInstance(copy, loadedDescription.newInstance(label));
          icons++;
        }
      } catch (ReflectiveOperationException | RuntimeException unavailable) { /* Keep the existing editor icon. */ }
      presentation.put(spec, new Presentation(icon, loadedText.newInstance(label)));
      labels++;
    }
    Map<String, List<String>> aliases = new LinkedHashMap<>();
    try {
      Object mapper = mapperConstructor.newInstance(active.context.getResources());
      for (Map.Entry<?, ?> entry : ((Map<?, ?>)tileMappers.invoke(mapper)).entrySet()) {
        if (!(entry.getKey() instanceof String spec) || !spec.startsWith("custom(")) continue;
        Object mapped = mappedSpecs.invoke(entry.getValue());
        // A null provider result explicitly means that this service is already
        // the correct backend (including the OOS recorder on applicable devices).
        if (!(mapped instanceof List<?> targets)) continue;
        ArrayList<String> names = new ArrayList<>();
        for (Object target : targets) names.add((String)target);
        aliases.put(spec, Collections.unmodifiableList(names));
      }
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      // Keep the custom list unchanged if canonicalization cannot be established;
      // the successfully queried native platform metadata is still useful.
      aliases.clear();
      runtime.module.log(Log.WARN, "OOSNativePanels", "Editor service aliases", unavailable);
    }
    return new Catalog(Collections.unmodifiableSet(available), Collections.unmodifiableSet(selected),
        Collections.unmodifiableMap(presentation), Collections.unmodifiableMap(aliases));
  }

  static boolean keepPlatform(String spec, Set<String> available, Set<String> selected) {
    return spec != null && (spec.startsWith("custom(") || available.contains(spec) || selected.contains(spec));
  }

  List<?> presentPlatform(List<?> platform, Catalog catalog) throws ReflectiveOperationException {
    ArrayList<Object> replacement = new ArrayList<>();
    for (Object item : platform) {
      String name = (String)specName.invoke(dataSpec.invoke(item));
      if (!keepPlatform(name, catalog.available, catalog.selected)) {
        filtered++;
        continue;
      }
      Presentation presentation = catalog.presentation.get(name);
      if (presentation == null || name.startsWith("custom(")) replacement.add(item);
      else replacement.add(dataConstructor.newInstance(dataSpec.invoke(item),
          presentation.icon != null ? presentation.icon : dataIcon.invoke(item),
          presentation.label != null ? presentation.label : dataLabel.invoke(item),
          dataAppName.invoke(item), dataAppIcon.invoke(item), dataCategory.invoke(item)));
    }
    return replacement;
  }

  List<?> presentCustom(List<?> custom, List<?> platform, Catalog catalog) throws ReflectiveOperationException {
    Set<String> presented = new LinkedHashSet<>(catalog.selected);
    for (Object item : platform) presented.add((String)specName.invoke(dataSpec.invoke(item)));
    for (Object item : custom) presented.add((String)specName.invoke(dataSpec.invoke(item)));
    ArrayList<Object> replacement = new ArrayList<>();
    for (Object item : custom) {
      String spec = (String)specName.invoke(dataSpec.invoke(item));
      List<String> targets = catalog.aliases.get(spec);
      boolean keep = targets == null || catalog.selected.contains(spec) || targets.contains(spec);
      if (!keep) {
        for (String target : targets) {
          if (catalog.available.contains(target) && !presented.contains(target)) {
            keep = true;
            break;
          }
        }
      }
      // An OEM service alias can normalize to a tile already represented above,
      // making Add appear to do nothing. Third-party services without a native
      // mapping are untouched; selected aliases remain removable/reorderable.
      if (keep) replacement.add(item);
      else customFiltered++;
    }
    return replacement;
  }

  static Drawable independentIcon(Drawable original, Resources resources) {
    if (original == null || original.getConstantState() == null) return null;
    return original.getConstantState().newDrawable(resources).mutate();
  }

  void destroyOrphanedTiles(Object helper) throws ReflectiveOperationException {
    // queryTiles can throw halfway through creating stock tiles, before it queues
    // their normal destruction. This private cache owns temporary tiles only;
    // current host tiles are queried separately and never added to it.
    Map<?, ?> cache = (Map<?, ?>)queryCache.get(helper);
    Exception firstFailure = null;
    for (Object tile : new ArrayList<>(cache.values())) {
      try {
        if (!customTile.isInstance(tile) && !Boolean.TRUE.equals(tileDestroyed.invoke(tile)))
          destroyTile.invoke(tile);
      } catch (ReflectiveOperationException | RuntimeException failure) {
        if (firstFailure == null) firstFailure = failure;
      }
    }
    if (firstFailure instanceof ReflectiveOperationException failure) throw failure;
    if (firstFailure instanceof RuntimeException failure) throw failure;
  }

  private void cancel() {
    Session previous = session;
    session = null;
    if (previous != null) {
      runtime.main.removeCallbacks(previous.timeout);
      previous.gate.cancel();
      // Do not clear the native queue before its temporary-tile destruction runs.
    }
  }

  private void deoptimize(String name, String methodName) {
    try {
      for (Method method : runtime.type(name).getDeclaredMethods())
        if (method.getName().equals(methodName)) runtime.module.deoptimize(method);
    } catch (ReflectiveOperationException | RuntimeException absent) { /* Optional caller on another ROM. */ }
  }

  String report() {
    return "editor=" + status + " queries=" + queries + " completed=" + completed
        + " labels=" + labels + " icons=" + icons + " filtered=" + filtered
        + " customAliases=" + customFiltered;
  }

  record Presentation(Object icon, Object label) {}
  record Catalog(Set<String> available, Set<String> selected, Map<String, Presentation> presentation,
      Map<String, List<String>> aliases) {
    Catalog(Set<String> available, Set<String> selected, Map<String, Presentation> presentation) {
      this(available, selected, presentation, Map.of());
    }
  }

  private final class Session {
    final WeakReference<Object> owner;
    final Object source;
    final Context context;
    final Configuration configuration;
    final int user;
    boolean opened;
    Object helper;
    volatile Catalog catalog;
    final EditorQuerySession<Catalog> gate;
    final Runnable timeout;

    Session(Object owner, Object source, Context context, int user, Object helper) {
      this.owner = new WeakReference<>(owner);
      this.source = source;
      this.context = context;
      configuration = new Configuration(context.getResources().getConfiguration());
      this.user = user;
      this.helper = helper;
      gate = new EditorQuerySession<>(this::current, result -> {
        catalog = result;
        completed++;
        status = "ready";
        open();
      }, () -> {
        status = "native query timed out";
        open();
      }, this::cleanup);
      timeout = gate::timeout;
    }

    boolean current() {
      if (session != this || owner.get() == null || !runtime.combinedQs.layout.hasCombinedGrid()
          || (!opened && !runtime.combinedQs.layout.gridVisible()) || runtime.locked()
          || !configuration.equals(context.getResources().getConfiguration())) return false;
      try { return user == (Integer)flowValue.invoke(currentUser.invoke(source)); }
      catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    void open() {
      runtime.main.removeCallbacks(timeout);
      Object viewModel = owner.get();
      if (viewModel == null) return;
      opened = true;
      try { startOriginal.invoke(viewModel); }
      catch (Throwable failure) {
        status = "could not open editor: " + failure;
        runtime.module.log(Log.WARN, "OOSNativePanels", "Native tile editor", failure);
      }
    }

    void drained() {
      try { gate.drained(); }
      catch (RuntimeException failure) {
        status = "query cleanup failed: " + failure;
        runtime.module.log(Log.WARN, "OOSNativePanels", "Editor query cleanup", failure);
      }
    }

    void cleanup() {
      if (!opened && session == this) session = null;
      runtime.main.removeCallbacks(timeout);
      Object finished = helper;
      helper = null;
      if (finished == null) return;
      try {
        listen.invoke(finished, new Object[]{null});
      } catch (ReflectiveOperationException | RuntimeException failure) {
        runtime.module.log(Log.WARN, "OOSNativePanels", "Detach editor query listener", failure);
      } finally {
        try { destroyOrphanedTiles(finished); }
        catch (ReflectiveOperationException | RuntimeException failure) {
          runtime.module.log(Log.WARN, "OOSNativePanels", "Destroy unfinished editor query tiles", failure);
        } finally {
          try { clearQuery.invoke(finished); }
          catch (ReflectiveOperationException | RuntimeException failure) {
            runtime.module.log(Log.WARN, "OOSNativePanels", "Clear editor query cache", failure);
          }
        }
      }
    }
  }
}
