package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.app.LocaleManager;
import android.content.Context;
import android.os.LocaleList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PanelLanguagesTest {
  private final Context app = RuntimeEnvironment.getApplication();

  private void expect(String override, String system, String first, String picker) {
    LocaleList result = PanelLanguages.resolve(
        LocaleList.forLanguageTags(override), LocaleList.forLanguageTags(system));
    assertEquals(first, result.get(0).toLanguageTag());
    assertEquals(picker, PanelLanguages.withLocales(app, result).getString(R.string.ime));
  }

  @Test public void respectsListOrderIncludingExplicitEnglish() {
    expect("", "fr-FR,ja-JP,en-US", "ja-JP", "入力方法の選択");
    expect("", "fr-FR,de-DE,zh-TW,ja-JP,en-US", "zh-TW", "輸入法選單");
    expect("", "fr-FR,en-GB,zh-TW", "en-GB", "Keyboard picker");
  }

  @Test public void chineseScriptAndRegionVariants() {
    expect("", "ko-KR,zh-HK,zh-CN", "zh-HK", "輸入法選單");
    expect("", "ko-KR,zh-MO", "zh-MO", "輸入法選單");
    expect("", "fr-FR,zh-SG", "zh-SG", "输入法菜单");
    expect("", "zh-Hant-CN,ja", "zh-Hant-CN", "輸入法選單");
    expect("", "zh-Hans-TW,ja", "zh-Hans-TW", "输入法菜单");
  }

  @Test public void orderedSystemLocalesReachActivityResources() {
    android.content.res.Resources resources = android.content.res.Resources.getSystem();
    android.content.res.Configuration original = new android.content.res.Configuration(resources.getConfiguration());
    LocaleList defaults = LocaleList.getDefault();
    android.content.res.Configuration ordered = new android.content.res.Configuration(original);
    ordered.setLocales(LocaleList.forLanguageTags("fr-FR,ja-JP,en-US"));
    resources.updateConfiguration(ordered, resources.getDisplayMetrics());
    try (ActivityController<MainActivity> ui = Robolectric.buildActivity(MainActivity.class).setup()) {
      assertEquals("入力方法の選択", ui.get().getString(R.string.ime));
      LocaleManager manager = app.getSystemService(LocaleManager.class);
      manager.setApplicationLocales(LocaleList.forLanguageTags("en"));
      assertEquals("en", PanelLanguages.forApp(app).get(0).getLanguage());
      manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
      assertEquals("ja", PanelLanguages.forApp(app).get(0).getLanguage());
    } finally {
      resources.updateConfiguration(original, resources.getDisplayMetrics());
      LocaleList.setDefault(defaults);
    }
  }

  @Test public void appPreferenceWinsAndClearingRestoresSystemOrder() {
    expect("en", "fr,zh-TW,ja", "en", "Keyboard picker");
    expect("zh-Hans", "ja,zh-TW", "zh-Hans", "输入法菜单");
    expect("", "fr,zh-TW,ja", "zh-TW", "輸入法選單");
  }

  @Test public void fallbackWhenNoLanguageMatches() {
    expect("", "fr,de,ko", "en", "Keyboard picker");
    expect("", "", "en", "Keyboard picker");
  }

  @Test public void settingsLanguageListContainsAllFourTranslations() throws Exception {
    java.util.Set<String> tags = new java.util.HashSet<>();
    try (android.content.res.XmlResourceParser xml = app.getResources().getXml(R.xml.locales_config)) {
      for (int event = xml.next(); event != xml.END_DOCUMENT; event = xml.next()) {
        if (event == xml.START_TAG && "locale".equals(xml.getName())) {
          tags.add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name"));
        }
      }
    }
    assertEquals(java.util.Set.of("en", "ja", "zh-Hans", "zh-Hant"), tags);
  }

  @Test public void nativePerAppPreferenceChangesTheLaunchedActivity() {
    LocaleManager manager = app.getSystemService(LocaleManager.class);
    LocaleList previous = manager.getApplicationLocales();
    try {
      for (String[] pair : new String[][]{{"ja","入力方法の選択"},{"zh-Hant","輸入法選單"},
          {"en","Keyboard picker"},{"zh-Hans","输入法菜单"}}) {
        manager.setApplicationLocales(LocaleList.forLanguageTags(pair[0]));
        try (ActivityController<MainActivity> ui = Robolectric.buildActivity(MainActivity.class).setup()) {
          assertEquals(pair[1], ui.get().getString(R.string.ime));
        }
      }
    } finally {
      manager.setApplicationLocales(previous);
    }
  }
}
