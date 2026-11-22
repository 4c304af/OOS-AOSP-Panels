package dev.oos.nativepanels;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GitHubUpdates {
  private static final String REPO = "onichan0923/OOS-AOSP-Panels";
  private static final Pattern TAG = Pattern.compile(
      "v?(([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})(-test)?)(?:-build\\.[0-9]+\\.[0-9]+)?");

  record Version(String name, int major, int minor, int patch, boolean test)
      implements Comparable<Version> {
    static Version parse(String tag) {
      Matcher m = TAG.matcher(tag);
      if (!m.matches()) return null;
      return new Version(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
          Integer.parseInt(m.group(4)), m.group(5) != null);
    }

    @Override public int compareTo(Version other) {
      int order = Integer.compare(major, other.major);
      if (order == 0) order = Integer.compare(minor, other.minor);
      if (order == 0) order = Integer.compare(patch, other.patch);
      if (order == 0) order = Boolean.compare(other.test, test);
      return order;
    }
  }

  record Release(Version version, String tag) {
    String page() { return "https://github.com/" + REPO + "/releases/tag/" + tag; }
  }

  enum Status { CHECKING, AVAILABLE, CURRENT, EMPTY, RATE_LIMIT, FAILED }
  record Result(Status status, Release release) {}

  static Result check(String installedName) {
    Version installed = Version.parse(installedName);
    if (installed == null) return new Result(Status.FAILED, null);
    Release newest = null;
    try {
      for (int page = 1; ; page++) {
        URL url = new URL("https://api.github.com/repos/" + REPO + "/releases?per_page=100&page=" + page);
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        try {
          http.setConnectTimeout(8000);
          http.setReadTimeout(12000);
          http.setRequestProperty("Accept", "application/vnd.github+json");
          http.setRequestProperty("User-Agent", "OOS-AOSP-Panels/" + installedName);
          int code = http.getResponseCode();
          if (code == 429 || (code == 403 && ("0".equals(http.getHeaderField("X-RateLimit-Remaining"))
              || http.getHeaderField("Retry-After") != null))) return new Result(Status.RATE_LIMIT, null);
          if (code != 200) return new Result(Status.FAILED, null);
          StringBuilder json = new StringBuilder();
          try (BufferedReader input = new BufferedReader(
              new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = input.read(buffer)) != -1) json.append(buffer, 0, count);
          }
          newest = newestOnPage(new JSONArray(json.toString()), installed, newest);
          String links = http.getHeaderField("Link");
          if (links == null || !links.contains("rel=\"next\"")) return result(installed, newest);
          if (Thread.currentThread().isInterrupted()) return new Result(Status.FAILED, null);
        } finally {
          http.disconnect();
        }
      }
    } catch (IOException | JSONException failure) {
      return new Result(Status.FAILED, null);
    }
  }

  static Release newestOnPage(JSONArray items, Version installed, Release newest) throws JSONException {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      if (item.getBoolean("draft")) continue;
      String tag = item.getString("tag_name");
      Version version = Version.parse(tag);
      if (version == null || (!installed.test && (version.test || item.getBoolean("prerelease")))) continue;
      if (newest != null && version.compareTo(newest.version) <= 0) continue;
      String apk = "OOS-AOSP-Panels-" + version.name + ".apk";
      JSONArray assets = item.getJSONArray("assets");
      for (int j = 0; j < assets.length(); j++) {
        JSONObject asset = assets.getJSONObject(j);
        if (apk.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) {
          newest = new Release(version, tag);
          break;
        }
      }
    }
    return newest;
  }

  static Result result(Version installed, Release newest) {
    if (newest == null) return new Result(Status.EMPTY, null);
    if (newest.version.compareTo(installed) > 0) return new Result(Status.AVAILABLE, newest);
    return new Result(Status.CURRENT, null);
  }
}
