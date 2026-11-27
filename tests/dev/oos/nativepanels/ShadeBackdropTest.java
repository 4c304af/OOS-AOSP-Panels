package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShadeBackdropTest {
  public static class Flow {
    float value;
    public void setValue(Object value) { this.value = (Float)value; }
  }
  public static class Interactor {
    final Flow blur = new Flow(), scale = new Flow();
    final Interactor repository = this;
    boolean bouncer;
    public Flow getBlurRequestedByShade() { return blur; }
    public Flow getScaleRequestedByShade() { return scale; }
    public boolean requestBlurForShade(float scale, int radius) {
      if (bouncer) return false;
      this.scale.value = scale;
      this.blur.value = radius;
      return true;
    }
  }
  public static class Depth {
    final Interactor window = new Interactor();
    float spring = 1f, vendor = 0f;
    int applied = 96, writes;
    public void update(int direction, float expansion, float velocity, boolean tracking) { spring = expansion; }
    public void setAmount(float amount, String reason) { vendor = amount; writes++; }
    public static void applied(Depth depth, int radius, float zoom) { depth.applied = radius; }
  }

  @Test public void leavingUnlockedShadeClearsTheWindowAndRestoresTheLatestVendorRequestOnce() throws Exception {
    for (boolean bouncer : new boolean[]{false, true}) {
      ShadeBackdrop backdrop = new ShadeBackdrop(null);
      Depth depth = new Depth();
      depth.window.bouncer = bouncer;
      depth.window.blur.value = 96f;
      depth.window.scale.value = .95f;
      View view = new View(RuntimeEnvironment.getApplication());
      bind(backdrop, "behind", view);
      bind(backdrop, "depth", depth);
      bind(backdrop, "viewExtension", depth);
      bind(backdrop, "lastVendorAmount", .65f);
      bind(backdrop, "windowBlur", Reflect.field(Depth.class, "window"));
      bind(backdrop, "repository", Reflect.field(Interactor.class, "repository"));
      bind(backdrop, "updateSpring", Depth.class.getMethod("update", int.class, float.class, float.class, boolean.class));
      bind(backdrop, "onBlurApplied", Depth.class.getMethod("applied", Depth.class, int.class, float.class));
      bind(backdrop, "setAmount", Depth.class.getMethod("setAmount", float.class, String.class));
      bind(backdrop, "clearBlur", Interactor.class.getMethod("requestBlurForShade", float.class, int.class));
      bind(backdrop, "blurRequest", Interactor.class.getMethod("getBlurRequestedByShade"));
      bind(backdrop, "scaleRequest", Interactor.class.getMethod("getScaleRequestedByShade"));
      bind(backdrop, "setValue", Flow.class.getMethod("setValue", Object.class));
      backdrop.onViewDetachedFromWindow(view);
      backdrop.release();
      assertEquals(0f, depth.spring, 0f);
      assertEquals(0f, depth.window.blur.value, 0f);
      assertEquals(1f, depth.window.scale.value, 0f);
      assertEquals(0, depth.applied);
      assertEquals(.65f, depth.vendor, 0f);
      assertEquals(1, depth.writes);
    }
  }
  private static void bind(ShadeBackdrop backdrop, String name, Object value) throws Exception {
    Reflect.field(ShadeBackdrop.class, name).set(backdrop, value);
  }
}
