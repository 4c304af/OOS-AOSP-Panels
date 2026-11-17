package dev.oos.nativepanels;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Resources;
import android.media.session.*;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.List;
import java.util.ArrayDeque;

final class PanelRuntime {
  final ModuleEntry module;
  final ClassLoader loader;
  final Handler main = new Handler(Looper.getMainLooper());
  final CombinedQs combinedQs;
  final HomeBatteryHook homeBattery = new HomeBatteryHook(this);
  final Android17StatusBar statusBar = new Android17StatusBar(this);
  final CombinedSettingsLaunch settingsLaunch = new CombinedSettingsLaunch(this);
  final DiagnosticSnapshot snapshots = new DiagnosticSnapshot(this);
  final WifiBridge wifi;
  final PanelMotion motion;
  final UserSwitcherPanel users;
  final ModesPanel modes;
  final ChannelPanel channels;
  final FlashlightPanel flashlight;
  final PrivacyPanel privacy;
  boolean channelsHook, flashlightHook, privacyHook;
  volatile Object graph;
  volatile boolean wifiHook, detailsHook, mediaHook, navigationHook, carrierHook, modesHook;
  private Application app;
  private SharedPreferences prefs;
  private ImeMenuStyle imeMenu;
  Object bluetoothModel;
  Object internetManager;
  private Object mediaManager;
  private volatile Resources strings;
  private Context moduleContext;
  private Event last = new Event(R.string.not_tested);
  private final ArrayDeque<Event> history = new ArrayDeque<>();
  private boolean registered;

  PanelRuntime(ModuleEntry module, ClassLoader loader) {
    this.module = module;
    this.loader = loader;
    combinedQs = new CombinedQs(this);
    wifi = new WifiBridge(this);
    motion = new PanelMotion(this);
    users = new UserSwitcherPanel(this);
    modes = new ModesPanel(this);
    channels = new ChannelPanel(this);
    privacy = new PrivacyPanel(this);
    flashlight = new FlashlightPanel(this);
  }

  Class<?> type(String name) throws ClassNotFoundException {
    return Class.forName(name, false, loader);
  }

  boolean enabled(String key) {
    if (combinedQs.layout.hasCombinedGrid() && Protocol.oosPanel(key)) return !failedInGrid.contains(key);
    return saved(key);
  }

  private final java.util.Set<String> failedInGrid = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private boolean saved(String key) {
    return prefs != null && prefs.getBoolean(key, false);
  }

  boolean locked() {
    if (app == null) return true;
    KeyguardManager k = app.getSystemService(KeyguardManager.class);
    return k != null && k.isKeyguardLocked();
  }

  synchronized void attach(Application a) {
    if (registered) return;
    app = a;
    try {
      // R.string IDs belong to the module APK. SystemUI has a different resource table.
      moduleContext = a.createPackageContext("dev.oos.nativepanels", 0);
      strings = PanelLanguages.withLocales(moduleContext, PanelLanguages.resolve(
          LocaleList.getEmptyLocaleList(), a.getResources().getConfiguration().getLocales())).getResources();
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      // LSPosed already supplies the installed APK information, also for a secondary user.
      try {
        strings = a.getPackageManager().getResourcesForApplication(module.getModuleApplicationInfo());
      } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
        module.log(Log.ERROR, "OOSNativePanels", "Module resources unavailable", missing);
        return;
      }
    }
    try {
      java.lang.reflect.Field component = Reflect.optionalField(a.getClass(), "mSysUIComponent");
      if (component != null) graph = component.get(a);
      if (graph == null) {
        Class<?> api = type("com.android.systemui.dagger.SysUIComponent");
        // Dagger field names can differ across branches. Match the interface on the live application.
        for (Class<?> cls = a.getClass(); graph == null && cls != null && cls != Application.class; cls = cls.getSuperclass()) {
          for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (api.isAssignableFrom(f.getType()) && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
              f.setAccessible(true);
              graph = f.get(a);
              if (graph != null) break;
            }
          }
        }
      }
      note(graph == null ? R.string.graph_pending : R.string.graph_ready);
    } catch (Exception e) {
      module.log(Log.ERROR, "OOSNativePanels", text(R.string.graph_read_error), e);
    }
    prefs =
        a.createDeviceProtectedStorageContext()
            .getSharedPreferences("oos_native_panels_v1", Context.MODE_PRIVATE);
    imeMenu = new ImeMenuStyle(a.getContentResolver(), prefs, this::text);
    IntentFilter filter = new IntentFilter(Protocol.ACTION);
    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            RuntimeReply reply = RuntimeReply.capture(() -> {
              int code;
              try {
                String locales = intent.getStringExtra("locales");
                if (moduleContext != null && locales != null && !locales.isEmpty()) {
                  strings = PanelLanguages.withLocales(moduleContext, PanelLanguages.resolve(
                      LocaleList.forLanguageTags(locales), a.getResources().getConfiguration().getLocales())).getResources();
                }
                String command = intent.getStringExtra("command");
                String feature = intent.getStringExtra("feature");
                if ("set".equals(command)) {
                  if (!Protocol.isFeature(feature)) throw new IllegalArgumentException(text(R.string.unknown_panel));
                  boolean enable = intent.getBooleanExtra("enabled", false);
                  if ("ime".equals(feature)) {
                    if (locked()) throw new IllegalStateException(text(R.string.unlock));
                    imeMenu.setEnabled(enable);
                    note(enable ? R.string.ime_on : R.string.ime_off);
                  } else {
                    prefs.edit().putBoolean(feature, enable).apply();
                    note(R.string.hook_state, featureName(feature), enabled(feature));
                  }
                } else if ("test".equals(command)) {
                  if (locked()) throw new IllegalStateException(text(R.string.unlock));
                  if ("internet".equals(feature)) showInternet(null);
                  else if ("bluetooth".equals(feature)) showBluetooth(null);
                  else if ("media".equals(feature)) testMedia();
                  else if ("modes".equals(feature)) modes.show(null);
                  else if ("flashlight".equals(feature)) flashlight.test();
                  else if ("users".equals(feature)) users.preview();
                  else if ("privacy".equals(feature)) privacy.show(null, true);
                  else throw new IllegalArgumentException(text(R.string.unknown_panel));
                } else if (!"status".equals(command)) throw new IllegalArgumentException(text(R.string.unknown_command));
                code = 1;
              } catch (Throwable e) {
                if ("ime".equals(intent.getStringExtra("feature"))) note(R.string.ime_problem, e.toString());
                else failure(intent.getStringExtra("feature"), e);
                code = -1;
              }
              String imeValue = null;
              boolean imeReadable = false;
              try {
                imeValue = imeMenu.read();
                imeReadable = true;
              } catch (RuntimeException error) {
                note(R.string.ime_read_error, error.getClass().getSimpleName());
              }
              String report = status() + "\n" + text(R.string.ime_status,
                  imeReadable ? String.valueOf(imeValue) : text(R.string.unreadable));
              Bundle b = new Bundle();
              b.putBoolean("ready", graph != null);
              for (String key : Protocol.KEYS)
                b.putBoolean(key, "ime".equals(key) ? "1".equals(imeValue) : saved(key));
              b.putBoolean("imeReadable", imeReadable);
              return new RuntimeReply(code, report, b, null);
            });
            if (reply.failure() != null)
              module.log(Log.ERROR, "OOSNativePanels", "Could not format SystemUI response", reply.failure());
            setResultCode(reply.code());
            setResultData(reply.report());
            setResultExtras(reply.values());
          }
        };
    if (Build.VERSION.SDK_INT >= 33)
      a.registerReceiver(receiver, filter, Protocol.PERMISSION, main, Context.RECEIVER_EXPORTED);
    else a.registerReceiver(receiver, filter, Protocol.PERMISSION, main);
    registered = true;
    note(R.string.control_ready);
  }

  Object provider(String name) throws Throwable {
    Object g = graph;
    if (g == null) throw new IllegalStateException(text(R.string.graph_missing));
    Object provider = Reflect.get(g, name);
    return Reflect.call(provider, "get");
  }

  void showInternet(Object source) throws Throwable {
    Object ap = provider("provideAccessPointControllerImplProvider");
    boolean mobile = (Boolean) Reflect.call(ap, "canConfigMobileData");
    boolean wifi = (Boolean) Reflect.call(ap, "canConfigWifi");
    Object manager = provider("internetDialogManagerProvider");
    internetManager = manager;
    Reflect.invoke(
        Reflect.method(
            manager.getClass(),
            "create",
            boolean.class,
            boolean.class,
            boolean.class,
            type("com.android.systemui.animation.Expandable")),
        manager,
        mobile,
        wifi,
        true,
        source);
    note(R.string.internet_open);
  }

  void showBluetooth(Object source) throws Throwable {
    Object controller = provider("bluetoothControllerImplProvider");
    if (!(Boolean) Reflect.call(controller, "canConfigBluetooth"))
      throw new IllegalStateException(text(R.string.bt_denied));
    if (bluetoothModel == null) {
      Object original = provider("bluetoothDetailsContentViewModelProvider");
      bluetoothModel = SafeScope.copyBluetoothModel(this, original);
    }
    Reflect.invoke(
        Reflect.method(
            bluetoothModel.getClass(),
            "showDialog",
            type("com.android.systemui.animation.Expandable")),
        bluetoothModel,
        source);
    note(R.string.bt_open);
  }

  UserHandle currentUser() throws Throwable {
    return (UserHandle) Reflect.call(provider("provideUserTrackerProvider"), "getUserHandle");
  }

  void testMedia() throws Throwable {
    MediaSessionManager sessions = app.getSystemService(MediaSessionManager.class);
    List<MediaController> active;
    @SuppressWarnings("unchecked")
    List<MediaController> selected =
        (List<MediaController>)
            Reflect.invoke(
                Reflect.method(
                    MediaSessionManager.class,
                    "getActiveSessionsForUser",
                    ComponentName.class,
                    UserHandle.class),
                sessions,
                null,
                currentUser());
    active = selected;
    MediaController best = null;
    for (MediaController c : active) {
      if (best == null) best = c;
      PlaybackState s = c.getPlaybackState();
      if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
        best = c;
        break;
      }
    }
    Object manager = provider("mediaOutputDialogManagerProvider");
    showMedia(
        manager,
        best == null ? null : best.getPackageName(),
        best == null ? null : best.getSessionToken(),
        true,
        null);
  }

  void showMedia(
      Object manager, String packageName, MediaSession.Token token, boolean above, View sourceView)
      throws Throwable {
    mediaManager = manager;
    Class<?> switching = type("com.android.systemui.media.dialog.MediaSwitchingType");
    Object output = switching.getField("OUTPUT").get(null);
    Reflect.invoke(
        Reflect.method(
            manager.getClass(),
            "createAndShow",
            UserHandle.class,
            String.class,
            boolean.class,
            type("com.android.systemui.animation.DialogTransitionAnimator$Controller"),
            boolean.class,
            MediaSession.Token.class,
            switching,
            boolean.class),
        manager,
        currentUser(),
        packageName,
        above,
        motion.mediaController(sourceView),
        false,
        token,
        output,
        false);
    note(R.string.media_open);
  }

  interface Work {
    Object run() throws Throwable;
  }

  Object redirect(XposedInterface.Chain chain, String key, Object asyncReturn, Work action)
      throws Throwable {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      try {
        return action.run();
      } catch (Throwable e) {
        failure(key, e);
        return chain.proceed();
      }
    }
    // Never retain the Xposed Chain across threads. Keep an original-method invoker instead.
    Method method = (Method) chain.getExecutable();
    Object target = chain.getThisObject();
    Object[] args = chain.getArgs().toArray();
    Handler caller = Looper.myLooper() == null ? main : new Handler(Looper.myLooper());
    XposedInterface.Invoker<?, Method> original =
        module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN);
    main.post(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            failure(key, e);
            caller.post(
                () -> {
                  try {
                    original.invoke(target, args);
                  } catch (Throwable fallback) {
                    failure(key, fallback);
                  }
                });
          }
        });
    return asyncReturn;
  }

  String text(int id, Object... args) {
    return RuntimeReply.text(strings, id, args);
  }

  Resources moduleResources(Context host) throws android.content.pm.PackageManager.NameNotFoundException {
    Resources loaded = strings;
    return loaded != null ? loaded : host.getPackageManager().getResourcesForApplication(module.getModuleApplicationInfo());
  }

  // Keep translated fields as resource IDs, so the log can also be read after a language change.
  private static final class Event {
    final int id;
    final Object[] args;
    String trace;

    Event(int id, Object... args) {
      this.id = id;
      this.args = args;
    }

    String format(Resources resources) {
      Object[] display = args.clone();
      for (int i = 0; i < display.length; i++) {
        if (display[i] instanceof Boolean on) {
          display[i] = resources.getString(on ? R.string.enabled_label : R.string.disabled_label);
        } else if (display[i] instanceof Event entry) {
          display[i] = entry.format(resources);
        }
      }
      return resources.getString(id, display);
    }
  }

  private Event featureName(String key) {
    return new Event(switch (key) {
      case "internet" -> R.string.internet;
      case "modes" -> R.string.modes;
      case "bluetooth" -> R.string.bluetooth;
      case "media" -> R.string.media;
      case "users" -> R.string.users;
      case "privacy" -> R.string.privacy;
      case "channels" -> R.string.channels;
      case "notification_cards" -> R.string.notification_cards;
      case "flashlight" -> R.string.flashlight;
      case "ime" -> R.string.ime;
      default -> R.string.unknown_panel;
    });
  }

  void message(int id) {
    note(id);
    main.post(() -> Toast.makeText(app, text(id), Toast.LENGTH_SHORT).show());
  }

  private synchronized void record(Event entry) {
    last = entry;
    if (history.size() == 100) history.removeFirst();
    history.addLast(entry);
  }

  void note(int id, Object... args) {
    Event entry = new Event(id, args);
    record(entry);
  }

  void failure(String feature, Throwable error) {
    Throwable e =
        error instanceof InvocationTargetException && error.getCause() != null
            ? error.getCause()
            : error;
    if (feature != null && Protocol.oosPanel(feature) && combinedQs.layout.hasCombinedGrid())
      failedInGrid.add(feature);
    else if (Protocol.isFeature(feature) && prefs != null)
      prefs.edit().putBoolean(feature, false).apply();
    Event entry = new Event(R.string.failure_detail, featureName(String.valueOf(feature)),
        e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
    entry.trace = Log.getStackTraceString(e);
    record(entry);
    module.log(Log.ERROR, "OOSNativePanels", String.valueOf(feature), e);
    if (app != null) main.post(() -> snapshots.failure(app));
    if (Looper.myLooper() == Looper.getMainLooper()) cleanup(feature);
    else main.post(() -> cleanup(feature));
    if (app != null)
      main.post(() -> Toast.makeText(app, text(R.string.auto_disabled), Toast.LENGTH_LONG).show());
  }

  private void cleanup(String feature) {
    try {
      if ("internet".equals(feature) && internetManager != null) {
        Object dialog = Reflect.field(internetManager.getClass(), "dialog").get(null);
        if (dialog instanceof android.app.Dialog) ((android.app.Dialog) dialog).dismiss();
        Reflect.call(internetManager, "destroyDialog");
      } else if ("media".equals(feature) && mediaManager != null)
        Reflect.call(mediaManager, "dismiss");
      else if ("bluetooth".equals(feature) && bluetoothModel != null)
        Reflect.call(bluetoothModel, "cancelJob");
      else if ("modes".equals(feature)) modes.dismiss();
    } catch (Throwable cleanupError) {
      module.log(Log.WARN, "OOSNativePanels", text(R.string.cleanup_error), cleanupError);
    }
  }

  synchronized String status() {
    Resources res = strings;
    StringBuilder log = new StringBuilder();
    for (Event entry : history) {
      log.append(entry.format(res)).append('\n');
      if (entry.trace != null) log.append(entry.trace).append('\n');
      if (log.length() > 20000) log.delete(0, log.length() - 16000);
    }
    return res.getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + "\n"
        + res.getString(R.string.status_header, res.getString(graph == null ? R.string.missing : R.string.available)) + "\n"
        + new Event(R.string.status_hooks, wifiHook, detailsHook, mediaHook, navigationHook, carrierHook,
            modesHook, channelsHook, flashlightHook, privacyHook, detailsHook && users.ready).format(res) + "\n"
        + res.getString(R.string.combined_qs) + " · " + res.getString(combinedQs.status) + "\n"
        + (combinedQs.status == R.string.combined_selected ? combinedQs.expansion.report()
            + combinedQs.layout.report() + "\n" + combinedQs.editor.report() + "\n" : "")
        + homeBattery.report() + "\n"
        + statusBar.report() + "\n"
        + (combinedQs.error == null ? "" : combinedQs.error + "\n")
        + res.getString(R.string.last_result, last.format(res)) + "\n\n" + log;
  }
}
