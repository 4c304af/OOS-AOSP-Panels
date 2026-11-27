package dev.oos.nativepanels;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GitHubUpdatesTest {
  private static JSONObject release(String tag, String apkVersion) throws JSONException {
    JSONArray assets = new JSONArray();
    if (apkVersion != null) assets.put(new JSONObject().put("name", "OOS-AOSP-Panels-" + apkVersion + ".apk")
        .put("state", "uploaded"));
    return new JSONObject().put("tag_name", tag).put("prerelease", tag.contains("-test"))
        .put("draft", false).put("assets", assets);
  }

  private static GitHubUpdates.Result check(String current, JSONObject... releases) throws JSONException {
    var installed = GitHubUpdates.Version.parse(current);
    JSONArray feed = new JSONArray();
    for (JSONObject item : releases) feed.put(item);
    return GitHubUpdates.result(installed, GitHubUpdates.newestOnPage(feed, installed, null));
  }

  @Test public void stableBuildOnlySeesPublishedStableApksAndComparesNumerically() throws Exception {
    var result = check("0.6.9", release("v0.7.0-test", "0.7.0-test"),
        release("v0.6.15", "0.6.15").put("prerelease", true), release("v0.6.14", null),
        release("v0.6.13", "0.6.13").put("draft", true), release("v0.6.12", "0.6.11"),
        release("v0.6.10", "0.6.10"), release("v0.6.9", "0.6.9"));
    assertEquals(GitHubUpdates.Status.AVAILABLE, result.status());
    assertEquals("https://github.com/4c304af/OOS-AOSP-Panels/releases/tag/v0.6.10", result.release().page());
  }

  @Test public void testBuildCanMoveToStableOfSameVersionAndNeverDowngrades() throws Exception {
    var stable = release("v0.6.75", "0.6.75");
    var test = release("v0.6.75-test", "0.6.75-test");
    assertEquals("0.6.75", check("0.6.75-test", stable, test).release().version().name());
    assertEquals("0.6.75-test", check("0.6.74-test", test).release().version().name());
    assertEquals(GitHubUpdates.Status.CURRENT, check("0.6.75", test, stable).status());
    assertEquals(GitHubUpdates.Status.CURRENT, check("0.7.0", stable).status());
  }

  @Test public void legacyTagsUseApkVersionForComparisonAndOriginalTagForNavigation() throws Exception {
    var legacy = release("v0.6.44-test-build.4.1", "0.6.44-test");
    assertEquals(GitHubUpdates.Status.CURRENT, check("0.6.44-test", legacy).status());
    assertTrue(check("0.6.43-test", legacy).release().page().endsWith("/v0.6.44-test-build.4.1"));
  }

  @Test public void ignoresInvalidTagsAndUnfinishedUploads() throws Exception {
    var incomplete = release("v0.6.80", "0.6.80");
    incomplete.getJSONArray("assets").getJSONObject(0).put("state", "starter");
    assertEquals(GitHubUpdates.Status.EMPTY, check("0.6.75", incomplete,
        release("v0.6.999999999999999", null), release("latest", null)).status());
  }

  @Test public void statusBarBuildsStayOnTheForkChannelAndCompareRevisionNumbers() throws Exception {
    var current = "0.6.84-android17-statusbar.1";
    var next = release("v0.6.84-android17-statusbar.10", "0.6.84-android17-statusbar.10")
        .put("prerelease", true);
    assertEquals("0.6.84-android17-statusbar.10", check(current,
        release("v0.6.99", "0.6.99"), next,
        release("v0.6.84-android17-statusbar.2", "0.6.84-android17-statusbar.2"))
        .release().version().name());
    assertEquals(GitHubUpdates.Status.CURRENT, check(current,
        release("v0.6.84-android17-statusbar", "0.6.84-android17-statusbar"),
        release("v0.6.84-android17-statusbar.1", current)).status());
    assertEquals(GitHubUpdates.Status.EMPTY, check(current, release("v0.6.99", "0.6.99")).status());
  }
}
