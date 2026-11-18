package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.os.IBinder;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** Exercise the real service 101 helper, whose registerListener replaces a single listener. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ScopeServiceConnectionTest {
  @Before public void resetBefore() throws Exception { resetHelper(); }
  @After public void resetAfter() throws Exception { resetHelper(); }

  @Test public void cachedFrameworkBindingReachesBothLateObserversWithoutReplacingHelperListener()
      throws Exception {
    Transport framework = new Transport(List.of());
    receive(framework);
    ScopeServiceConnection connection = application().scopeService();
    Listener permission = new Listener();
    Listener bluetooth = new Listener();

    connection.listen(permission);
    Object registered = helperListener();
    connection.listen(bluetooth);

    assertNotNull(registered);
    assertSame(registered, helperListener());
    assertEquals(1, permission.bound.size());
    assertEquals(1, bluetooth.bound.size());
    assertSame(permission.bound.get(0), bluetooth.bound.get(0));
    framework.die();
    assertEquals(permission.bound, permission.died);
    assertEquals(bluetooth.bound, bluetooth.died);
  }

  @Test public void futureBindingsFanOutAndOldBinderDeathDoesNotDisconnectItsReplacement()
      throws Exception {
    ScopeServiceConnection connection = application().scopeService();
    Listener permission = new Listener();
    Listener bluetooth = new Listener();
    connection.listen(permission);
    connection.listen(bluetooth);
    Transport old = new Transport(List.of());
    Transport replacement = new Transport(List.of());

    receive(old);
    receive(replacement);
    assertEquals(2, permission.bound.size());
    assertEquals(permission.bound, bluetooth.bound);
    old.die();
    assertTrue(permission.died.isEmpty());
    assertTrue(bluetooth.died.isEmpty());
    replacement.die();
    assertEquals(List.of(permission.bound.get(1)), permission.died);
    assertEquals(permission.died, bluetooth.died);

    Listener afterDeath = new Listener();
    connection.listen(afterDeath);
    assertTrue(afterDeath.bound.isEmpty());
  }

  @Test public void permissionAndBluetoothControllersShareCachedAndFutureFrameworkConnections()
      throws Exception {
    PanelApplication application = application();
    CombinedQsSettings.setEnabled(application, CombinedQsSettings.PERMISSION_DIALOG, true);
    CombinedQsSettings.setEnabled(application, CombinedQsSettings.BLUETOOTH_DETAILS, true);
    CombinedQsSettings.setEnabled(application, CombinedQsSettings.ABOUT_PHONE, true);
    List<String> packages = List.of(PermissionDialogHook.PACKAGE,
        BluetoothDetailsSettings.SETTINGS_PACKAGE, BluetoothDetailsSettings.WIRELESS_PACKAGE);
    Transport cached = new Transport(packages);
    receive(cached);

    PermissionDialogScope permission = application.permissionScope();
    BluetoothDetailsScope bluetooth = application.bluetoothDetailsScope();
    AboutPhoneScope about = application.aboutPhoneScope();
    ShadowLooper.idleMainLooper();
    assertEquals(PermissionDialogScope.State.ADDED, permission.state());
    assertEquals(BluetoothDetailsScope.State.ADDED, bluetooth.state());
    assertEquals(AboutPhoneScope.State.ADDED, about.state());
    assertEquals(0, cached.requests);

    Transport replacement = new Transport(packages);
    receive(replacement);
    cached.die();
    ShadowLooper.idleMainLooper();
    assertEquals(PermissionDialogScope.State.ADDED, permission.state());
    assertEquals(BluetoothDetailsScope.State.ADDED, bluetooth.state());
    assertEquals(AboutPhoneScope.State.ADDED, about.state());
    replacement.die();
    ShadowLooper.idleMainLooper();
    assertEquals(PermissionDialogScope.State.UNAVAILABLE, permission.state());
    assertEquals(BluetoothDetailsScope.State.UNAVAILABLE, bluetooth.state());
    assertEquals(AboutPhoneScope.State.UNAVAILABLE, about.state());
    assertEquals(0, replacement.requests);
  }

  private static PanelApplication application() {
    return (PanelApplication)RuntimeEnvironment.getApplication();
  }

  private static Object helperListener() throws Exception {
    Field field = XposedServiceHelper.class.getDeclaredField("mListener");
    field.setAccessible(true);
    return field.get(null);
  }

  private static void resetHelper() throws Exception {
    Field listener = XposedServiceHelper.class.getDeclaredField("mListener");
    listener.setAccessible(true);
    listener.set(null, null);
    Field cache = XposedServiceHelper.class.getDeclaredField("mCache");
    cache.setAccessible(true);
    ((Set<?>)cache.get(null)).clear();
  }

  private static void receive(Transport framework) throws Exception {
    Method receive = XposedServiceHelper.class.getDeclaredMethod("onBinderReceived", IBinder.class);
    receive.setAccessible(true);
    receive.invoke(null, framework.binder);
  }

  private static final class Listener implements XposedServiceHelper.OnServiceListener {
    final List<XposedService> bound = new ArrayList<>();
    final List<XposedService> died = new ArrayList<>();
    @Override public void onServiceBind(XposedService service) { bound.add(service); }
    @Override public void onServiceDied(XposedService service) { died.add(service); }
  }

  /** A local AIDL transport; the helper still creates and caches its real XposedService. */
  private static final class Transport {
    final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
    final IBinder binder;
    Object service;
    int requests;

    Transport(List<String> packages) throws ClassNotFoundException {
      binder = (IBinder)Proxy.newProxyInstance(IBinder.class.getClassLoader(),
          new Class<?>[]{IBinder.class}, (proxy, method, args) -> switch (method.getName()) {
            case "queryLocalInterface" -> this.service;
            case "linkToDeath" -> { deaths.add((IBinder.DeathRecipient)args[0]); yield null; }
            case "unlinkToDeath" -> deaths.remove(args[0]);
            case "pingBinder", "isBinderAlive" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Scope test binder";
            default -> null;
          });
      // service 101 exposes the AIDL interface as a runtime dependency only.
      Class<?> remote = Class.forName("io.github.libxposed.service.IXposedService");
      service = Proxy.newProxyInstance(remote.getClassLoader(),
          new Class<?>[]{remote}, (proxy, method, args) -> switch (method.getName()) {
            case "asBinder" -> binder;
            case "getScope" -> packages;
            case "requestScope" -> { requests++; yield null; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "Scope test service";
            default -> null;
          });
    }

    void die() {
      for (IBinder.DeathRecipient death : new ArrayList<>(deaths)) death.binderDied();
    }
  }
}
