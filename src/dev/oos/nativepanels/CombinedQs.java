package dev.oos.nativepanels;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;

final class CombinedQs {
  private final PanelRuntime runtime;
  final CombinedQsExpansion expansion = new CombinedQsExpansion();
  final ShadeLayout layout;
  final CombinedEditorTiles editor;
  // Events can precede Application.onCreate's return, when translated resources are unavailable.
  volatile int status = R.string.combined_unavailable;
  volatile String error;

  CombinedQs(PanelRuntime runtime) {
    this.runtime = runtime;
    layout = new ShadeLayout(runtime);
    editor = new CombinedEditorTiles(runtime);
  }

  void install() throws Exception {
    Class<?> host = runtime.type("com.oplus.systemui.statusbar.phone.OplusCentralSurfacesExImpl");
    Class<?> compose = runtime.type("com.android.systemui.qs.composefragment.QSFragmentCompose");
    Class<?>[] parameters = {
        runtime.type("com.android.systemui.fragments.FragmentService"),
        runtime.type("com.android.systemui.shade.NotificationShadeWindowView"), boolean.class
    };
    Method factory = host.getDeclaredMethod("createDefaultQSFragment", parameters);
    Method parent = Reflect.method(host.getSuperclass(), factory.getName(), parameters);
    // Method.invoke uses virtual dispatch and would re-enter the Oplus override.
    XposedInterface.Invoker<?, Method> stock =
        runtime.module.getInvoker(parent).setType(XposedInterface.Invoker.Type.ORIGIN);
    layout.install(compose);
    expansion.install(runtime, compose);
    runtime.module.hook(factory).setId("native-panels:combined-qs")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
        .intercept(chain -> {
          expansion.reset();
          layout.clear();
          error = null;
          if (Boolean.TRUE.equals(chain.getArg(2))) {
            status = R.string.combined_separate;
            return chain.proceed();
          }
          try {
            View window = (View) chain.getArg(1);
            Bundle settings = window.getContext().getContentResolver().call(
                CombinedQsSettings.URI, CombinedQsSettings.QUERY, null, null);
            if (settings == null || !settings.containsKey(CombinedQsSettings.QUERY))
              throw new IllegalStateException("No response from combined QS settings");
            if (settings.getBoolean(CombinedQsSettings.QUERY)) {
              Object qs = stock.invokeSpecial(chain.getThisObject(), chain.getArg(0), window, false);
              if (!compose.isInstance(qs)) throw new IllegalStateException("Unexpected QS fragment: " + qs);
              layout.select((android.app.Fragment) qs);
              status = R.string.combined_selected;
              return qs;
            }
            status = R.string.combined_oos;
          } catch (Exception | LinkageError e) {
            status = R.string.combined_fallback;
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            error = Log.getStackTraceString(cause);
            runtime.module.log(Log.WARN, "OOSNativePanels", "Combined QS factory", cause);
          }
          return chain.proceed();
        });
    status = R.string.combined_waiting;
  }
}
