package dev.oos.nativepanels;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

/** A SystemUI package on the Robolectric broadcast bus with the production settings handler. */
final class TestSystemUiReceiver implements AutoCloseable {
  final PanelRuntime runtime = new PanelRuntime(null, getClass().getClassLoader());
  final SharedPreferences preferences;
  private final Context context;
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    @Override public void onReceive(Context ignored, Intent request) {
      // Preview panels themselves require OOS classes. Their command routing is under test here.
      Intent command = "test".equals(request.getStringExtra("command"))
          ? new Intent().putExtra("command", "status") : request;
      RuntimeReply reply = runtime.handleCommand(command);
      setResultCode(reply.code());
      setResultData(reply.report());
      setResultExtras(reply.values());
    }
  };

  TestSystemUiReceiver() throws Exception {
    Application app = RuntimeEnvironment.getApplication();
    PackageInfo info = new PackageInfo();
    info.packageName = Protocol.TARGET;
    info.applicationInfo = new ApplicationInfo();
    info.applicationInfo.packageName = info.packageName;
    info.applicationInfo.dataDir = app.getDir("systemui", Context.MODE_PRIVATE).getPath();
    Shadows.shadowOf(app.getPackageManager()).installPackage(info);
    context = app.createPackageContext(Protocol.TARGET, 0);
    preferences = context.getSharedPreferences("oos_native_panels_v1", Context.MODE_PRIVATE);
    Reflect.field(PanelRuntime.class, "prefs").set(runtime, preferences);
    Reflect.field(PanelRuntime.class, "app").set(runtime, app);
    runtime.graph = new Object();
    runtime.privacyHook = true;
    runtime.detailsHook = true;
    runtime.users.ready = true;
    context.registerReceiver(receiver, new IntentFilter(Protocol.ACTION), Context.RECEIVER_EXPORTED);
  }

  void enableCardHook() throws Exception {
    runtime.combinedQs.status = R.string.combined_selected;
    Reflect.field(NotificationCards.class, "ready").setBoolean(runtime.combinedQs.layout.cards, true);
  }

  @Override public void close() {
    context.unregisterReceiver(receiver);
  }
}
