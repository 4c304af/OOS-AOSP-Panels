package dev.oos.nativepanels;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import java.util.*;

public final class MainActivity extends AppCompatActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout oosGroup;
  private boolean groupHidden;
  private android.animation.ValueAnimator groupMotion;
  private final Map<String, MaterialSwitch> switches = new LinkedHashMap<>();
  private final Map<String, Boolean> values = new LinkedHashMap<>();
  private MaterialTextView stateTitle, stateDetail, reportView;
  private ScrollView scroll;
  private boolean updating, connected, ready, imeReadable;
  private String report;
  private OnBackPressedCallback back;
  private MaterialButton restartButton;
  private boolean restarting;

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(PanelLanguages.withLocales(base, PanelLanguages.forApp(base)));
  }

  @Override
  public void onCreate(Bundle state) {
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(state);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    boolean light =
        !MaterialColors.isColorLight(color(com.google.android.material.R.attr.colorOnSurface));
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightStatusBars(light);
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightNavigationBars(light);
    back =
        new OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            showHome();
          }
        };
    getOnBackPressedDispatcher().addCallback(this, back);
    report = offlineReport();
    showHome();
  }

  @Override
  protected void onDestroy() {
    cancelGroupMotion();
    super.onDestroy();
  }

  @Override
  public void onResume() {
    super.onResume();
    request("status", null, false);
  }

  private LinearLayout page() {
    LinearLayout content = column();
    content.setPadding(dp(20), dp(12), dp(20), dp(24));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
    scroll.addView(content);
    ViewCompat.setOnApplyWindowInsetsListener(
        scroll,
        (view, insets) -> {
          androidx.core.graphics.Insets edge =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(edge.left, edge.top, edge.right, edge.bottom);
          return insets;
        });
    setContentView(scroll);
    ViewCompat.requestApplyInsets(scroll);
    return content;
  }

  private void showHome() {
    back.setEnabled(false);
    reportView = null;
    switches.clear();
    cancelGroupMotion();
    oosGroup = null;
    LinearLayout page = page();
    LinearLayout top = row();
    MaterialTextView title = label(getString(R.string.home_title), 36, true);
    title.setLetterSpacing(-.025f);
    title.setMinHeight(dp(64));
    top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    MaterialButton log = iconButton(R.drawable.ic_diagnostics, getString(R.string.diagnostics));
    log.setOnClickListener(v -> showDiagnostics());
    top.addView(log, new LinearLayout.LayoutParams(dp(48), dp(48)));
    page.addView(top);
    restartButton =
        new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle);
    restartButton.setText(restarting ? getString(R.string.root_pending) : getString(R.string.restart_ui));
    restartButton.setEnabled(!restarting);
    restartButton.setIconResource(R.drawable.ic_refresh);
    restartButton.setOnClickListener(
        v ->
            new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.restart_title))
                .setMessage(getString(R.string.restart_explain))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.restart), (dialog, which) -> restartSystemUi())
                .show());
    margin(restartButton, 4, 10);
    page.addView(restartButton);
    MaterialCardView state =
        card(color(com.google.android.material.R.attr.colorSecondaryContainer), 28);
    LinearLayout stateRow = row();
    stateRow.setPadding(dp(18), dp(16), dp(18), dp(16));
    int stateColor = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
    stateRow.addView(
        icon(R.drawable.ic_link, stateColor), new LinearLayout.LayoutParams(dp(24), dp(24)));
    LinearLayout texts = column();
    texts.setPadding(dp(14), 0, dp(8), 0);
    stateTitle = label(getString(R.string.connect), 15, true);
    stateDetail = label(getString(R.string.loading), 12, false);
    stateDetail.setPadding(0, dp(4), 0, 0);
    stateTitle.setTextColor(stateColor);
    stateDetail.setTextColor(stateColor);
    texts.addView(stateTitle);
    texts.addView(stateDetail);
    stateRow.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    stateRow.addView(
        icon(R.drawable.ic_arrow, stateColor), new LinearLayout.LayoutParams(dp(20), dp(20)));
    state.addView(stateRow);
    state.setClickable(true);
    state.setOnClickListener(
        v -> {
          if (connected) showDiagnostics();
          else showSetup();
        });
    margin(state, 0, 20);
    page.addView(state);
    feature(page, "combined_qs", getString(R.string.combined_qs), getString(R.string.combined_desc),
        R.drawable.ic_combined_qs, com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer);
    feature(page, "notification_cards", getString(R.string.notification_cards), getString(R.string.notification_cards_desc),
        R.drawable.ic_channels, com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(page, CombinedQsSettings.HOME_BATTERY, getString(R.string.home_battery), getString(R.string.home_battery_desc),
        R.drawable.ic_battery, com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(page, CombinedQsSettings.MOBILE_ORDER, getString(R.string.mobile_order), getString(R.string.mobile_order_desc),
        R.drawable.ic_mobile_signal, com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(page, CombinedQsSettings.PERMISSION_DIALOG, getString(R.string.permission_dialogs), getString(R.string.permission_dialogs_desc),
        R.drawable.ic_privacy, com.google.android.material.R.attr.colorTertiaryContainer,
        com.google.android.material.R.attr.colorOnTertiaryContainer);
    oosGroup = column();
    page.addView(oosGroup, new LinearLayout.LayoutParams(-1,-2));
    groupHidden = CombinedQsSettings.enabled(this);
    oosGroup.setVisibility(groupHidden ? View.GONE : View.VISIBLE);
    feature(
        oosGroup,
        "internet",
        getString(R.string.internet),
        getString(R.string.internet_desc),
        R.drawable.ic_wifi,
        com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer);
    feature(
        oosGroup,
        "modes",
        getString(R.string.modes),
        getString(R.string.modes_desc),
        R.drawable.ic_dnd,
        com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(
        oosGroup,
        "bluetooth",
        getString(R.string.bluetooth),
        getString(R.string.bluetooth_desc),
        R.drawable.ic_bluetooth,
        com.google.android.material.R.attr.colorTertiaryContainer,
        com.google.android.material.R.attr.colorOnTertiaryContainer);
    feature(
        oosGroup,
        "media",
        getString(R.string.media),
        getString(R.string.media_desc),
        R.drawable.ic_audio,
        com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(oosGroup, "users", getString(R.string.users), getString(R.string.users_desc),
        R.drawable.ic_users, com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer);
    feature(oosGroup, "privacy", getString(R.string.privacy), getString(R.string.privacy_desc),
        R.drawable.ic_privacy, com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    feature(oosGroup, "flashlight", getString(R.string.flashlight), getString(R.string.flashlight_desc),
        R.drawable.ic_flashlight, com.google.android.material.R.attr.colorTertiaryContainer,
        com.google.android.material.R.attr.colorOnTertiaryContainer);
    feature(page, "channels", getString(R.string.channels), getString(R.string.channels_desc),
        R.drawable.ic_channels, com.google.android.material.R.attr.colorPrimaryContainer,
        com.google.android.material.R.attr.colorOnPrimaryContainer);
    feature(page, "ime", getString(R.string.ime), getString(R.string.ime_desc),
        R.drawable.ic_keyboard, com.google.android.material.R.attr.colorSecondaryContainer,
        com.google.android.material.R.attr.colorOnSecondaryContainer);
    MaterialTextView hint = label(getString(R.string.try_hint), 13, false);
    hint.setGravity(Gravity.CENTER);
    hint.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    margin(hint, 8, 8);
    page.addView(hint);
    MaterialTextView version = label(getString(R.string.test_version, BuildConfig.VERSION_NAME.replace("-test", "")), 12, false);
    version.setGravity(Gravity.CENTER);
    version.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    page.addView(version);
    updateState();
  }

  private void feature(
      LinearLayout page,
      String key,
      String title,
      String description,
      int iconRes,
      int iconBg,
      int iconFg) {
    MaterialCardView card =
        card(color(com.google.android.material.R.attr.colorSurfaceContainer), 28);
    LinearLayout body = column();
    body.setPadding(dp(18), dp(18), dp(18), dp(16));
    LinearLayout heading = row();
    FrameLayout iconBox = new FrameLayout(this);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(color(iconBg));
    bg.setCornerRadius(dp(18));
    iconBox.setBackground(bg);
    iconBox.addView(
        icon(iconRes, color(iconFg)), new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
    heading.addView(iconBox, new LinearLayout.LayoutParams(dp(50), dp(50)));
    LinearLayout titles = column();
    titles.setPadding(dp(14), 0, 0, 0);
    titles.addView(label(title, 22, true));
    MaterialTextView desc = label(description, 14, false);
    desc.setPadding(0, dp(4), 0, 0);
    desc.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
    titles.addView(desc);
    heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(heading);
    LinearLayout actions = row();
    margin(actions, 16, 0);
    boolean combined = "combined_qs".equals(key);
    boolean persistent = combined || CombinedQsSettings.standalone(key);
    if (persistent || "notification_cards".equals(key)) {
      actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    } else if ("channels".equals(key)) {
      MaterialTextView hint = label(getString(R.string.channels_hint), 13, false);
      hint.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
      actions.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
    } else {
      MaterialButton test =
          new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle);
      test.setText("ime".equals(key) ? getString(R.string.open_picker) : getString(R.string.open_panel));
      test.setTextSize(14);
      test.setAllCaps(false);
      test.setIconResource(R.drawable.ic_open);
      test.setIconSize(dp(18));
      test.setIconPadding(dp(8));
      test.setInsetTop(0);
      test.setInsetBottom(0);
      test.setOnClickListener(
          v -> {
            if ("ime".equals(key)) {
              // The picker service checks the calling client. Use the foreground Activity/display.
              getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker();
              return;
            }
            request("test", key, false);
            handler.postDelayed(() -> request("status", null, false), 1800);
          });
      actions.addView(test, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    MaterialSwitch sw = new MaterialSwitch(this);
    sw.setText(getString(R.string.replace));
    sw.setTextSize(13);
    sw.setContentDescription(persistent ? title : getString(R.string.replace_accessible, title));
    sw.setPadding(dp(16), 0, 0, 0);
    sw.setSwitchPadding(dp(8));
    sw.setChecked(persistent ? CombinedQsSettings.enabled(this, combined ? CombinedQsSettings.QUERY : key) : Boolean.TRUE.equals(values.get(key)));
    sw.setTag(key);
    switches.put(key, sw);
    actions.addView(sw, new LinearLayout.LayoutParams(-2, dp(48)));
    sw.setOnCheckedChangeListener(
        (button, checked) -> {
          if (updating) return;
          if (persistent) {
            if (!CombinedQsSettings.setEnabled(this, combined ? CombinedQsSettings.QUERY : key, checked)) {
              updateState();
              Snackbar.make(scroll, R.string.combined_save_failed, Snackbar.LENGTH_LONG).show();
            } else if (combined) updateOosCards(checked, true);
          } else {
            request("set", key, checked);
          }
        });
    body.addView(actions);
    card.addView(body);
    margin(card, 0, 12);
    page.addView(card);
  }

  private void cancelGroupMotion() {
    if (groupMotion == null) return;
    groupMotion.removeAllListeners();
    groupMotion.removeAllUpdateListeners();
    groupMotion.cancel();
    groupMotion = null;
  }

  private void updateOosCards(boolean hide, boolean animate) {
    LinearLayout group = oosGroup;
    if (group == null || hide == groupHidden) return;
    groupHidden = hide;
    int runningHeight = group.getLayoutParams().height;
    int from = group.getVisibility() == View.GONE ? 0
        : groupMotion != null && runningHeight >= 0 ? runningHeight : group.getHeight();
    float fromAlpha = group.getVisibility() == View.GONE ? 0f : group.getAlpha();
    cancelGroupMotion();
    ViewGroup.LayoutParams lp = group.getLayoutParams();
    boolean motion = animate && android.animation.ValueAnimator.areAnimatorsEnabled()
        && group.isAttachedToWindow() && ((View)group.getParent()).getWidth() > 0;
    if (!motion) {
      group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      lp.height = -2; group.setLayoutParams(lp);
      group.setAlpha(1f);
      group.setVisibility(hide ? View.GONE : View.VISIBLE);
      return;
    }
    // A single clipped container also moves the settings below it. On reversal,
    // start from the current height and alpha, even before the next layout pass.
    int width = group.getWidth();
    if (width <= 0) {
      View parent = (View)group.getParent();
      width = parent.getWidth() - parent.getPaddingStart() - parent.getPaddingEnd();
    }
    group.setVisibility(View.VISIBLE);
    group.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int to = hide ? 0 : group.getMeasuredHeight();
    lp.height = from; group.setLayoutParams(lp); group.setAlpha(fromAlpha);
    group.setImportantForAccessibility(hide ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
    groupMotion = animator;
    animator.setDuration(hide ? 230 : 300);
    animator.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
    animator.addUpdateListener(a -> {
      float t = (Float)a.getAnimatedValue();
      lp.height = Math.round(from + (to - from) * t);
      group.setLayoutParams(lp);
      group.setAlpha(fromAlpha + ((hide ? 0f : 1f) - fromAlpha) * t);
    });
    animator.addListener(new android.animation.AnimatorListenerAdapter() {
      @Override public void onAnimationEnd(android.animation.Animator ended) {
        groupMotion = null;
        group.setVisibility(hide ? View.GONE : View.VISIBLE);
        lp.height = -2; group.setLayoutParams(lp);
        group.setAlpha(1f);
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
      }
    });
    animator.start();
  }

  private void showDiagnostics() {
    cancelGroupMotion();
    oosGroup = null;
    back.setEnabled(true);
    LinearLayout page = page();
    LinearLayout toolbar = row();
    MaterialButton backButton = iconButton(R.drawable.ic_back, getString(R.string.back));
    backButton.setOnClickListener(v -> showHome());
    toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    MaterialTextView title = label(getString(R.string.diagnostics), 24, true);
    title.setPadding(dp(12), 0, 0, 0);
    toolbar.addView(title);
    page.addView(toolbar);
    MaterialTextView intro = label(getString(R.string.diagnostics_hint), 14, false);
    margin(intro, 20, 18);
    page.addView(intro);
    LinearLayout actions = row();
    MaterialButton refresh =
        new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonTonalStyle);
    refresh.setText(getString(R.string.refresh));
    refresh.setIconResource(R.drawable.ic_refresh);
    refresh.setOnClickListener(v -> request("status", null, false));
    actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(52), 1));
    MaterialButton copy = new MaterialButton(this);
    copy.setText(getString(R.string.copy_log));
    copy.setIconResource(R.drawable.ic_copy);
    copy.setOnClickListener(
        v -> {
          getSystemService(ClipboardManager.class)
              .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), diagnosticReport()));
          Snackbar.make(scroll, getString(R.string.log_copied), Snackbar.LENGTH_SHORT).show();
        });
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(52), 1);
    cp.setMarginStart(dp(10));
    actions.addView(copy, cp);
    page.addView(actions);
    MaterialCardView box =
        card(color(com.google.android.material.R.attr.colorSurfaceContainer), 24);
    margin(box, 18, 0);
    reportView = label(diagnosticReport(), 13, false);
    reportView.setTypeface(Typeface.MONOSPACE);
    reportView.setTextIsSelectable(true);
    reportView.setPadding(dp(18), dp(18), dp(18), dp(18));
    box.addView(reportView);
    page.addView(box);
    request("status", null, false);
  }

  private void showSetup() {
    new MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.connect))
        .setMessage(getString(R.string.setup_steps))
        .setPositiveButton(getString(R.string.check_again), (d, w) -> request("status", null, false))
        .setNegativeButton(getString(R.string.close), null)
        .show();
  }

  private void restartSystemUi() {
    if (restarting) return;
    restarting = true;
    restartButton.setEnabled(false);
    restartButton.setText(getString(R.string.root_pending));
    new Thread(
            () -> {
              String message;
              boolean requested = false;
              try {
                RootRestart.Result result = RootRestart.restart();
                requested = result == RootRestart.Result.REQUESTED;
                switch (result) {
                  case REQUESTED:
                    message = getString(R.string.restart_requested);
                    break;
                  case DENIED:
                    message = getString(R.string.root_denied);
                    break;
                  case TIMEOUT:
                    message = getString(R.string.root_timeout);
                    break;
                  case NOT_RUNNING:
                    message = getString(R.string.ui_stopped);
                    break;
                  default:
                    message = getString(R.string.restart_failed);
                }
              } catch (java.io.IOException e) {
                message = getString(R.string.shell_failed);
              }
              String resultMessage = message;
              boolean didRequest = requested;
              handler.post(
                  () -> {
                    restarting = false;
                    if (isFinishing() || isDestroyed()) return;
                    restartButton.setEnabled(true);
                    restartButton.setText(getString(R.string.restart_ui));
                    Snackbar.make(scroll, resultMessage, Snackbar.LENGTH_LONG).show();
                    if (didRequest) {
                      for (long delay : new long[] {1500, 4000, 8000})
                        handler.postDelayed(
                            () -> {
                              if (!isFinishing() && !isDestroyed()) request("status", null, false);
                            },
                            delay);
                    }
                  });
            },
            "RootRestart")
        .start();
  }

  private String offlineReport() {
    DiagnosticSnapshot.Record saved = DiagnosticSnapshot.read(this);
    if (saved == null) return getString(R.string.disconnected);
    String when = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(saved.time()));
    return getString(R.string.disconnected) + "\n\n" + getString(R.string.saved_diagnostics, when) + "\n" + saved.text();
  }

  private String diagnosticReport() {
    return report + "\n\n" + PermissionDialogSettings.diagnostic(this);
  }

  private void request(String command, String feature, boolean enabled) {
    Intent intent =
        new Intent(Protocol.ACTION)
            .setPackage(Protocol.TARGET)
            .putExtra("locales", PanelLanguages.forApp(this).toLanguageTags())
            .putExtra("command", command)
            .putExtra("feature", feature)
            .putExtra("enabled", enabled)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
    sendOrderedBroadcast(
        intent,
        null,
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent ignored) {
            if (isFinishing() || isDestroyed()) return;
            connected = getResultCode() != 0;
            Bundle b = getResultExtras(false);
            ready = connected && b != null && b.getBoolean("ready", false);
            imeReadable = connected && b != null && b.getBoolean("imeReadable", false);
            if (connected && getResultData() != null) {
              report = getResultData();
              DiagnosticSnapshot.remember(context, report);
            } else report = offlineReport();
            for (String key : Protocol.KEYS)
              values.put(key, connected && b != null && b.getBoolean(key, false));
            updateState();
            if (!connected && !"status".equals(command)) showSetup();
            else if (getResultCode() < 0)
              Snackbar.make(scroll, "ime".equals(feature) ? getString(R.string.ime_update_failed) : getString(R.string.open_failed), Snackbar.LENGTH_LONG)
                  .setAction(getString(R.string.view_log), v -> showDiagnostics())
                  .show();
          }
        },
        handler,
        0,
        null,
        null);
  }

  private void updateState() {
    if (stateTitle != null) {
      stateTitle.setText(ready ? getString(R.string.connected) : connected ? getString(R.string.preparing) : getString(R.string.offline));
      stateDetail.setText(ready ? getString(R.string.ready) : connected ? getString(R.string.pending) : getString(R.string.setup_hint));
    }
    updateOosCards(CombinedQsSettings.enabled(this), false);
    updating = true;
    for (Map.Entry<String, MaterialSwitch> e : switches.entrySet())
      e.getValue().setChecked("combined_qs".equals(e.getKey()) ? CombinedQsSettings.enabled(this)
          : CombinedQsSettings.standalone(e.getKey()) ? CombinedQsSettings.enabled(this, e.getKey())
          : Boolean.TRUE.equals(values.get(e.getKey())));
    MaterialSwitch ime = switches.get("ime");
    if (ime != null) ime.setEnabled(imeReadable);
    updating = false;
    if (reportView != null) reportView.setText(diagnosticReport());
  }

  private int color(int attr) {
    return MaterialColors.getColor(this, attr, "NativePanels");
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private MaterialTextView label(String value, int size, boolean bold) {
    MaterialTextView t = new MaterialTextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    return t;
  }

  private MaterialCardView card(int background, int radius) {
    MaterialCardView c = new MaterialCardView(this);
    c.setCardBackgroundColor(background);
    c.setRadius(dp(radius));
    c.setCardElevation(0);
    c.setStrokeWidth(0);
    return c;
  }

  private ImageView icon(int res, int tint) {
    ImageView i = new ImageView(this);
    i.setImageResource(res);
    i.setImageTintList(ColorStateList.valueOf(tint));
    i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return i;
  }

  private MaterialButton iconButton(int resource, String label) {
    MaterialButton b = (MaterialButton) getLayoutInflater().inflate(R.layout.icon_button, null);
    b.setIconResource(resource);
    b.setContentDescription(label);
    return b;
  }

  private void margin(View v, int top, int bottom) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    p.bottomMargin = dp(bottom);
    v.setLayoutParams(p);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
