package dev.oos.nativepanels;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;

/** Use a consistent SystemUI dialog theme for the security disclosure. */
final class CombinedFooter {
  static int dialogTheme(Context c) {
    return c.getResources().getIdentifier("Theme.SystemUI.Dialog", "style", Protocol.TARGET);
  }

  static void applyTextColors(View view, ColorStateList foreground, ColorStateList link) {
    if (view instanceof TextView text) {
      if (foreground != null) text.setTextColor(foreground);
      if (link != null) text.setLinkTextColor(link);
    } else if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); ++i) applyTextColors(group.getChildAt(i), foreground, link);
    }
  }

  static void installDisclosure(PanelRuntime r) throws Exception {
    Class<?> utils = r.type("com.android.systemui.qs.QSSecurityFooterUtils");
    Class<?> delegate = r.type(utils.getName() + "$1");
    Class<?> factory = r.type("com.android.systemui.statusbar.phone.SystemUIDialog$Factory");
    Method create = factory.getDeclaredMethod("create", r.type("com.android.systemui.statusbar.phone.DialogDelegate"),
        Context.class, int.class, boolean.class, boolean.class);
    r.module.hook(create).setId("native-panels:disclosure-window").intercept(c -> {
      if (delegate.isInstance(c.getArg(0)) && (Integer)c.getArg(2) == 0 && r.combinedQs.layout.active()) {
        int theme = dialogTheme((Context)c.getArg(1));
        if (theme != 0) return c.proceed(new Object[]{c.getArg(0), c.getArg(1), theme, c.getArg(3), c.getArg(4)});
      }
      return c.proceed();
    });
    r.module.hook(utils.getMethod("createDialogView", Context.class)).setId("native-panels:disclosure-content").intercept(c -> {
      Context ctx = (Context)c.getArg(0);
      if (r.combinedQs.layout.hasCombinedGrid()) {
        int theme = dialogTheme(ctx);
        if (theme != 0) {
          Context themed = new ContextThemeWrapper(ctx, theme);
          View body = (View)c.proceed(new Object[]{themed});
          // The OEM layout sets its own COUI foreground, even inside a platform dialog.
          TypedArray colors = themed.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary, android.R.attr.textColorLink});
          try { applyTextColors(body, colors.getColorStateList(0), colors.getColorStateList(1)); }
          finally { colors.recycle(); }
          return body;
        }
      }
      return c.proceed();
    });
    for (Method caller : utils.getDeclaredMethods()) {
      Class<?>[] args = caller.getParameterTypes();
      if (args.length == 4 && args[0] == utils && args[1] == String.class && args[2] == android.view.View.class)
        r.module.deoptimize(caller);
    }
  }

}
