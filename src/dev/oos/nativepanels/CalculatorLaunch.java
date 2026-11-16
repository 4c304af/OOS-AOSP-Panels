package dev.oos.nativepanels;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.service.quicksettings.Tile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resolve the installed launcher activity in the user who owns the tile. */
final class CalculatorLaunch {
  static final String GOOGLE = "com.google.android.calculator";
  static final String VENDOR_ACTION = "oplus.intent.action.CALCULATOR";

  static Intent launcher(PackageManager packages, boolean vendorTile) {
    if (vendorTile && new Intent(VENDOR_ACTION).resolveActivity(packages) != null) return null;
    Intent launch = packages.getLaunchIntentForPackage(GOOGLE);
    ComponentName target = launch == null ? null : launch.getComponent();
    return target != null && GOOGLE.equals(target.getPackageName()) ? launch : null;
  }

  static void install(PanelRuntime r, boolean vendor) throws Exception {
    Class<?> cls = r.type(vendor ? "com.oplus.systemui.qs.tiles.CalculatorTile" : "com.android.systemui.qs.external.CustomTile");
    Class<?> source = r.type("com.android.systemui.animation.Expandable");
    Method launch = r.type("com.android.systemui.plugins.ActivityStarter").getMethod(
        "postStartActivityDismissingKeyguard", Intent.class, int.class,
        r.type("com.android.systemui.animation.ActivityTransitionAnimator$Controller"), String.class, UserHandle.class);
    Field starter = Reflect.field(cls, "mActivityStarter"), host = Reflect.field(cls, "mHost");
    Field component = vendor ? null : Reflect.field(cls, "mComponent");
    Field externalState = vendor ? null : Reflect.field(cls, "mTile");
    Field disabled = vendor ? null : Reflect.field(cls, "mShoulDisableTile");
    Method userContext = vendor ? r.type("com.android.systemui.qs.QSHost").getMethod("getUserContext") : cls.getMethod("getUserContext");
    Method getUser = Context.class.getMethod("getUser");
    Method state = cls.getMethod("getState");
    Class<?> stateClass = r.type("com.android.systemui.plugins.qs.QSTile$State");
    Field availability = stateClass.getField("state"), policy = stateClass.getField("disabledByPolicy");

    if (vendor) {
      r.module.hook(cls.getDeclaredMethod("isAvailable")).setId("native-panels:calculator-available").intercept(c -> {
        if ((Boolean)c.proceed()) return true;
        try {
          Context ctx = (Context)userContext.invoke(host.get(c.getThisObject()));
          return ctx != null && ctx.getPackageManager().getLaunchIntentForPackage(GOOGLE) != null;
        } catch (Exception ignored) { return false; }
      });
    }
    r.module.hook(cls.getDeclaredMethod("handleClick", source))
        .setId(vendor ? "native-panels:calculator-vendor" : "native-panels:calculator-service").intercept(c -> {
          Object tile = c.getThisObject();
          if (!vendor) {
            ComponentName name = (ComponentName)component.get(tile);
            if (name == null || !GOOGLE.equals(name.getPackageName())) return c.proceed();
            Tile live = (Tile)externalState.get(tile);
            if (live == null || live.getState() == Tile.STATE_UNAVAILABLE || disabled.getBoolean(tile)) return c.proceed();
          }
          Object current = state.invoke(tile);
          if (policy.getBoolean(current) || availability.getInt(current) == Tile.STATE_UNAVAILABLE) return c.proceed();
          try {
            Context ctx = (Context)userContext.invoke(vendor ? host.get(tile) : tile);
            Intent intent = launcher(ctx.getPackageManager(), vendor);
            if (intent != null) {
              launch.invoke(starter.get(tile), intent, 0, null, null, (UserHandle)getUser.invoke(ctx));
              return null;
            }
          } catch (ReflectiveOperationException | RuntimeException e) { r.combinedQs.layout.warn(e); }
          return c.proceed();
        });
  }
}
