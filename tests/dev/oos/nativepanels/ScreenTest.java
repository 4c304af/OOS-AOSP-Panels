package dev.oos.nativepanels;

import static org.junit.Assert.*;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "zh-rTW-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenTest {
  @Test
  public void homeLight() throws Exception {
    render(false);
  }

  @Test
  public void homeDark() throws Exception {
    render(true);
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h800dp-xhdpi")
  public void englishHomeAndDiagnostics() throws Exception {
    checkLanguage("AOSP Panels", "Keyboard picker", "Diagnostics", "Restart System UI?", "en");
  }

  @Test
  @Config(qualifiers = "ja-rJP-w360dp-h800dp-xhdpi")
  public void japaneseHomeAndDiagnostics() throws Exception {
    checkLanguage("AOSP パネル", "入力方法の選択", "診断ログ", "システム UI を再起動しますか？", "ja");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
  public void simplifiedChineseHomeAndDiagnostics() throws Exception {
    checkLanguage("原生面板", "输入法菜单", "诊断日志", "重启系统界面？", "zh-CN");
  }

  @Test
  public void privacyPreviewAndReplacementAreIndependent() {
    checkPreviewAndSwitch("privacy", R.string.privacy);
  }

  @Test
  public void userSwitcherCanBePreviewedBeforeEnablingOosReplacement() {
    checkPreviewAndSwitch("users", R.string.users);
  }

  private void checkPreviewAndSwitch(String key, int label) {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      View decor = activity.getWindow().getDecorView();
      ViewGroup titleRow = (ViewGroup) findText(decor, activity.getString(label)).getParent().getParent();
      ViewGroup card = (ViewGroup) titleRow.getParent();
      MaterialSwitch toggle = (MaterialSwitch) findText(card, activity.getString(R.string.replace));
      assertFalse(toggle.isChecked());
      findText(card, activity.getString(R.string.open_panel)).performClick();
      toggle.performClick();
      java.util.List<String> commands = new java.util.ArrayList<>();
      for (android.content.Intent intent : org.robolectric.Shadows.shadowOf(
          activity.getApplication()).getBroadcastIntents()) {
        if (key.equals(intent.getStringExtra("feature"))) {
          assertEquals(Protocol.TARGET, intent.getPackage());
          commands.add(intent.getStringExtra("command"));
          if ("set".equals(intent.getStringExtra("command")))
            assertTrue(intent.getBooleanExtra("enabled", false));
        }
      }
      assertEquals(java.util.Arrays.asList("test", "set"), commands);
    }
  }

  @Test
  public void combinedQsCanBeEnabledAndRevertedWhileSystemUiIsOffline() {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      View decor = activity.getWindow().getDecorView();
      MaterialSwitch toggle = decor.findViewWithTag("combined_qs");
      assertNotNull(toggle);
      assertFalse(toggle.isChecked());
      assertFalse(readCombinedSetting(activity));
      toggle.performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertTrue(readCombinedSetting(activity));
      for (String feature : Protocol.KEYS)
        assertEquals(feature, !Protocol.oosPanel(feature), decor.findViewWithTag(feature).isShown());
      controller.recreate();
      ShadowLooper.idleMainLooper();
      toggle = controller.get().getWindow().getDecorView().findViewWithTag("combined_qs");
      assertTrue(toggle.isChecked());
      toggle.performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertFalse(readCombinedSetting(controller.get()));
      for (String feature : Protocol.KEYS)
        assertTrue(feature, controller.get().getWindow().getDecorView().findViewWithTag(feature).isShown());
      assertNull(controller.get().getContentResolver().call(CombinedQsSettings.URI, "set", null, null));
    }
  }

  @Test public void optionsCanReopenBeforeTheCollapseFinishes() {
    try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      MaterialSwitch toggle = decor.findViewWithTag("combined_qs");
      View inputMethod = decor.findViewWithTag("ime");
      View net = decor.findViewWithTag("internet");
      toggle.performClick();
      ShadowLooper.idleMainLooper(80, java.util.concurrent.TimeUnit.MILLISECONDS);
      toggle.performClick();
      ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
      assertTrue(net.isShown());
      assertTrue(inputMethod.isShown());
      assertFalse(readCombinedSetting(activity));
    }
  }

  @Test public void homeBatterySwitchWorksWithoutCombinedQs() {
    try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity app = c.get();
      String key = CombinedQsSettings.HOME_BATTERY;
      MaterialSwitch sw = app.getWindow().getDecorView().findViewWithTag(key);
      assertFalse(sw.isChecked());
      sw.performClick();
      assertFalse(readCombinedSetting(app));
      assertTrue(app.getContentResolver().call(CombinedQsSettings.URI, key, null, null).getBoolean(key));
      c.recreate();
      MaterialSwitch restored = c.get().getWindow().getDecorView().findViewWithTag(key);
      assertTrue(restored.isChecked());
      restored.performClick();
      assertFalse(CombinedQsSettings.enabled(c.get(), key));
    }
  }

  @Test public void oldEnabledMobileOrderSettingIsIgnoredAfterUpgrade() {
    String key = "mobile_type_before_signal";
    var context = org.robolectric.RuntimeEnvironment.getApplication();
    assertTrue(CombinedQsSettings.setEnabled(context, key, true));
    try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity app = c.get();
      assertNull(app.getWindow().getDecorView().findViewWithTag(key));
      assertNull(app.getContentResolver().call(CombinedQsSettings.URI, key, null, null));
    }
  }

  private boolean readCombinedSetting(MainActivity activity) {
    android.os.Bundle value = activity.getContentResolver().call(
        CombinedQsSettings.URI, CombinedQsSettings.QUERY, null, null);
    assertNotNull(value);
    return value.getBoolean(CombinedQsSettings.QUERY);
  }

  @Test
  public void unlockedShadeTracksItsPanelFraction() {
    assertFalse(CombinedQsExpansion.isOpen(0f, 0, false, true, true));
    assertTrue(CombinedQsExpansion.isOpen(.1f, 0, false, false, false));
    assertTrue(CombinedQsExpansion.isOpen(1f, 0, false, true, true));
    assertFalse(CombinedQsExpansion.isOpen(1f, 0, true, true, true));
  }

  @Test public void lockedPullShowsOnlyDuringAnAllowedVisibleGesture() {
    for (int state : new int[]{CombinedQsExpansion.KEYGUARD, CombinedQsExpansion.SHADE_LOCKED}) {
      for (float drag : new float[]{0f,.2f,1f,.3f,0f})
        assertEquals(drag > 0f, CombinedQsExpansion.isOpen(drag, state, false, true, true));
      assertFalse(CombinedQsExpansion.isOpen(1f, state, false, false, true));
      assertFalse(CombinedQsExpansion.isOpen(1f, state, false, true, false));
      assertFalse(CombinedQsExpansion.isOpen(1f, state, true, true, true));
      assertFalse(CombinedQsExpansion.isOpen(Float.NaN, state, false, true, true));
    }
    assertFalse(CombinedQsExpansion.isOpen(1f, 5, false, true, true));
  }

  private void checkLanguage(String home, String picker, String log, String confirm, String tag)
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      View decor = activity.getWindow().getDecorView();
      TextView heading = (TextView) findText(decor, home);
      assertNotNull(heading);
      if ("en".equals(tag) || "ja".equals(tag)) {
        String appName = "en".equals(tag) ? "AOSP Panels" : "AOSP パネル";
        assertEquals(appName, activity.getString(R.string.app_name));
        int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, 1782);
        assertTrue(heading.getHeight() >= heading.getLayout().getHeight());
      }
      assertNotNull(findText(decor, picker));
      assertNotNull(findText(decor, activity.getString(R.string.privacy)));
      if ("ja".equals(tag)) assertNotNull(findText(decor, "パネルの動作を確認してから、置き換えを有効にしてください。"));
      assertHomeSwitches(decor);
      capture(decor, "locale-" + tag + ".png");
      findText(decor, activity.getString(R.string.restart_ui)).performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
      assertTrue(dialog.isShowing());
      assertNotNull(findText(dialog.getWindow().getDecorView(), confirm));
      dialog.dismiss();
      Method showLog = MainActivity.class.getDeclaredMethod("showDiagnostics");
      showLog.setAccessible(true);
      showLog.invoke(activity);
      ShadowLooper.idleMainLooper();
      assertNotNull(findText(activity.getWindow().getDecorView(), log));
      assertTrue(activity.getString(R.string.status_hooks, 1,2,3,4,5,6,7,8,9,10).contains("10"));
    }
  }

  @Test
  public void diagnosticHistoryRendersInTheLanguageOfTheReader() throws Exception {
    Class<?> type = Class.forName("dev.oos.nativepanels.PanelRuntime$Event");
    java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(int.class, Object[].class);
    constructor.setAccessible(true);
    Object name = constructor.newInstance(R.string.modes, new Object[0]);
    Object event = constructor.newInstance(R.string.hook_state, new Object[]{name, true});
    Method format = type.getDeclaredMethod("format", android.content.res.Resources.class);
    format.setAccessible(true);
    String[][] cases = {
      {"ja", "サイレント モードの置き換え：有効"},
      {"en", "Do Not Disturb shortcut replacement: On"},
      {"zh-Hans", "勿扰模式入口替换：已启用"},
      {"zh-Hant", "勿擾模式入口替換：已啟用"}
    };
    for (String[] pair : cases) {
      android.content.res.Configuration config = new android.content.res.Configuration();
      config.setLocale(java.util.Locale.forLanguageTag(pair[0]));
      android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication()
          .createConfigurationContext(config);
      assertEquals(pair[1], format.invoke(event, ctx.getResources()));
    }
  }

  private void render(boolean dark) throws Exception {
    AppCompatDelegate.setDefaultNightMode(
        dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup().visible()) {
      MainActivity activity = controller.get();
      ShadowLooper.idleMainLooper();
      View decor = activity.getWindow().getDecorView();
      assertHomeSwitches(decor);
      assertNotNull(findText(decor, "使用者切換"));
      assertNotNull(findText(decor, "勿擾模式"));
      assertNotNull(findText(decor, "手電筒亮度"));
      assertNotNull(findText(decor, "隱私指示器"));
      assertNotNull(findText(decor, "輸入法選單"));
      assertNotNull(findText(decor, "開啟選單"));
      assertNotNull(findText(decor, "開啟後長按一則通知試用"));
      assertNull(findText(decor, "OOS  /  ANDROID"));
      View restart = findText(decor, "重啟系統介面");
      assertNotNull(restart);
      assertEquals(1, ((ViewGroup)restart.getParent()).indexOfChild(restart));
      capture(decor, dark ? "home-dark.png" : "home-light.png");
      Field scrollField = MainActivity.class.getDeclaredField("scroll");
      scrollField.setAccessible(true);
      ScrollView scroll = (ScrollView) scrollField.get(activity);
      scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
      capture(decor, dark ? "home-bottom-dark.png" : "home-bottom-light.png");
      restart.performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
      assertTrue(confirmation.isShowing());
      confirmation.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      ShadowLooper.idleMainLooper();
      assertFalse(confirmation.isShowing());
      Method log = MainActivity.class.getDeclaredMethod("showDiagnostics");
      log.setAccessible(true);
      log.invoke(activity);
      ShadowLooper.idleMainLooper();
      capture(
          activity.getWindow().getDecorView(),
          dark ? "diagnostics-dark.png" : "diagnostics-light.png");
    }
  }

  private void assertHomeSwitches(View decor) {
    String[] settings = {"combined_qs", CombinedQsSettings.HOME_BATTERY,
        CombinedQsSettings.PERMISSION_DIALOG, CombinedQsSettings.BLUETOOTH_DETAILS, CombinedQsSettings.ABOUT_PHONE};
    for (String key : Protocol.KEYS) assertTrue(key, decor.findViewWithTag(key) instanceof MaterialSwitch);
    for (String key : settings) assertTrue(key, decor.findViewWithTag(key) instanceof MaterialSwitch);
    assertEquals(Protocol.KEYS.length + settings.length, countSwitches(decor));
  }

  private int countSwitches(View v) {
    int n = v instanceof MaterialSwitch ? 1 : 0;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) n += countSwitches(g.getChildAt(i));
    }
    return n;
  }

  private View findText(View v, String text) {
    if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void capture(View v, String name) throws Exception {
    v.measure(
        View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, 822, 1782);
    Bitmap bitmap = Bitmap.createBitmap(822, 1782, Bitmap.Config.ARGB_8888);
    v.draw(new Canvas(bitmap));
    File dir = new File("build/screenshots");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }
}
