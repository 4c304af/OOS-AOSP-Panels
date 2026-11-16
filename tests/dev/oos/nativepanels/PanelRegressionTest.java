package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PanelRegressionTest {
  private final Context ctx = RuntimeEnvironment.getApplication();

  private void activity(String pkg, String name, String action, String category) {
    ActivityInfo info = new ActivityInfo();
    info.name = name;
    info.packageName = pkg;
    info.enabled = info.exported = true;
    ComponentName component = new ComponentName(pkg, name);
    var pm = Shadows.shadowOf(ctx.getPackageManager());
    pm.addOrUpdateActivity(info);
    IntentFilter filter = new IntentFilter(action);
    filter.addCategory(category);
    pm.addIntentFilterForActivity(component, filter);
  }

  @Test public void calculatorResolvesTheInstalledLauncherClass() {
    String pkg = CalculatorLaunch.GOOGLE;
    assertNull(CalculatorLaunch.launcher(ctx.getPackageManager(), true));
    activity(pkg, pkg + ".NewLauncher", Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER);
    Intent intent = CalculatorLaunch.launcher(ctx.getPackageManager(), true);
    assertNotNull(intent);
    assertEquals(new ComponentName(pkg, pkg + ".NewLauncher"), intent.getComponent());
    activity("com.coloros.calculator", "com.coloros.calculator.Main", CalculatorLaunch.VENDOR_ACTION, Intent.CATEGORY_DEFAULT);
    assertNull(CalculatorLaunch.launcher(ctx.getPackageManager(), true));
    assertEquals(intent.getComponent(), CalculatorLaunch.launcher(ctx.getPackageManager(), false).getComponent());
  }

  @Test public void fullyExpandedTilesFollowTheClosingShadeWithoutReexpanding() {
    assertEquals(1f, CombinedSettingsLaunch.closingExpansion(1, 1), 0);
    assertEquals(.48f, CombinedSettingsLaunch.closingExpansion(1, .48f), 0);
    assertEquals(.2f, CombinedSettingsLaunch.closingExpansion(.2f, .48f), 0);
    assertEquals(0f, CombinedSettingsLaunch.closingExpansion(1, 0), 0);
    assertEquals(-1f, CombinedSettingsLaunch.closingExpansion(-1, .5f), 0);
    assertEquals(.8f, CombinedSettingsLaunch.closingExpansion(.8f, Float.NaN), 0);
  }

  @Test public void relocationPreservesTileIdentityAndDistinguishesThirdPartyComponents() {
    String wifi = CombinedEditor.itemKey("wifi");
    assertEquals(wifi, CombinedEditor.itemKey(new String("wifi")));
    assertNotEquals(wifi, CombinedEditor.itemKey("bt"));
    assertNotEquals(CombinedEditor.itemKey("custom(com.example/.A)"), CombinedEditor.itemKey("custom(com.example/.B)"));
    assertNull(CombinedEditor.itemKey(null));
    assertNull(CombinedEditor.itemKey(""));
  }

  @Test public void disclosureKeepsTheVpnLinkSpanAndClickHandler() {
    LinearLayout body = new LinearLayout(ctx);
    TextView text = new TextView(ctx);
    body.addView(text);
    SpannableString label = new SpannableString("VPN settings");
    URLSpan span = new URLSpan("test://vpn");
    label.setSpan(span, 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setText(label);
    text.setOnClickListener(v -> v.setTag("clicked"));
    CombinedFooter.applyTextColors(body, ColorStateList.valueOf(0xffeeeeee), ColorStateList.valueOf(0xffabcdef));
    assertEquals(0xffeeeeee, text.getCurrentTextColor());
    assertEquals(0xffabcdef, text.getLinkTextColors().getDefaultColor());
    assertSame(span, ((Spanned)text.getText()).getSpans(0, label.length(), URLSpan.class)[0]);
    assertTrue(text.performClick());
    assertEquals("clicked", text.getTag());
  }

  @Test public void rotationGlyphFollowsTheActiveState() {
    assertEquals("qs_auto_rotate_icon_on", CombinedTileClicks.rotationDrawable(2));
    assertEquals("qs_auto_rotate_icon_off", CombinedTileClicks.rotationDrawable(1));
    assertEquals("qs_auto_rotate_icon_off", CombinedTileClicks.rotationDrawable(0));
  }
}
