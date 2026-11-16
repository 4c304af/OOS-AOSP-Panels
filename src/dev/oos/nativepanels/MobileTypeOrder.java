package dev.oos.nativepanels;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Change only the links in OOS 17's large-type chain. Its binder still owns each view. */
final class MobileTypeOrder {
  static void installView(PanelRuntime runtime) throws Exception {
    Class<?> binder = runtime.type("com.oplus.systemui.statusbar.pipeline.mobile.ui.view.OplusStatusBarMobileViewBinder$Os17Binding");
    Method container = binder.getMethod("getMobileComboReal");
    Method text = binder.getMethod("getMobileTypeText");
    Class<?> params = runtime.type("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
    Field start = params.getField("startToEnd"), end = params.getField("endToStart");
    Field right = params.getField("endToEnd"), left = params.getField("startToStart");
    runtime.module.hook(binder.getDeclaredMethod("initViews", int.class))
        .setId("native-panels:large-mobile-order")
        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
          Object result = chain.proceed();
          Object owner = chain.getThisObject();
          ViewGroup group = (ViewGroup) container.invoke(owner);
          View label = (View) text.invoke(owner);
          if (label.getParent() != group || !params.isInstance(label.getLayoutParams())) return result;

          ViewGroup.LayoutParams lp = label.getLayoutParams();
          if (start.getInt(lp) <= 0 || right.getInt(lp) != 0) return result;
          View bars = group.findViewById(start.getInt(lp));
          if (bars == null || bars.getParent() != group || !params.isInstance(bars.getLayoutParams())) return result;
          ViewGroup.LayoutParams bp = bars.getLayoutParams();
          if (end.getInt(bp) != label.getId() || start.getInt(bp) <= 0) return result;
          View roam = group.findViewById(start.getInt(bp));
          if (roam == null || roam.getParent() != group || !params.isInstance(roam.getLayoutParams())) return result;
          ViewGroup.LayoutParams rp = roam.getLayoutParams();
          if (left.getInt(rp) != 0 || end.getInt(rp) != bars.getId()) return result;

          end.setInt(rp, label.getId());
          start.setInt(lp, roam.getId());
          end.setInt(lp, bars.getId());
          right.setInt(lp, -1);
          start.setInt(bp, label.getId());
          end.setInt(bp, -1);
          right.setInt(bp, 0);
          roam.setLayoutParams(rp);
          label.setLayoutParams(lp);
          bars.setLayoutParams(bp);
          if (label instanceof TextView tv && lp instanceof ViewGroup.MarginLayoutParams lm
              && bp instanceof ViewGroup.MarginLayoutParams bm) {
            TypeSpacing spacing = new TypeSpacing(tv, bm, lm.getMarginEnd());
            tv.addTextChangedListener(spacing);
            spacing.update(tv.getText());
          }
          return result;
        });
  }

  static float oneSp(Resources res) {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, res.getDisplayMetrics());
  }

  static int compactEndMargin(int oldEnd, int signalStart, int trailingBlank, int target) {
    return Math.min(oldEnd, target - signalStart - trailingBlank);
  }

  // ONextBox binds an artwork span after OOS has set the text. Handling the text change
  // covers both binding orders and restores the original margin for plain labels.
  private static final class TypeSpacing implements TextWatcher {
    final TextView label;
    final ViewGroup.MarginLayoutParams bars;
    final int original;
    TypeSpacing(TextView label, ViewGroup.MarginLayoutParams bars, int original) {
      this.label = label; this.bars = bars; this.original = original;
    }
    void update(CharSequence text) {
      int blank = label.getPaddingEnd();
      if (text instanceof Spanned spanned) {
        for (ReplacementSpan span : spanned.getSpans(0, text.length(), ReplacementSpan.class)) {
          if (spanned.getSpanStart(span) == 0 && spanned.getSpanEnd(span) == text.length()) {
            blank += artworkInset(span, text);
            break;
          }
        }
      }
      ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)label.getLayoutParams();
      int end = compactEndMargin(original, bars.getMarginStart(), blank, Math.round(oneSp(label.getResources())));
      if (lp.getMarginEnd() != end) {
        lp.setMarginEnd(end);
        label.setLayoutParams(lp);
      }
    }
    private int artworkInset(ReplacementSpan span, CharSequence text) {
      // Release builds obfuscate ArtworkSpan. Check the rendered box against its
      // drawable and the known symmetric inset before subtracting any pixels.
      int side = Math.round(2f * label.getResources().getDisplayMetrics().density);
      try {
        for (Field field : span.getClass().getDeclaredFields()) {
          if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !Drawable.class.isAssignableFrom(field.getType())) continue;
          field.setAccessible(true);
          Drawable icon = (Drawable)field.get(span);
          if (icon != null && icon.getIntrinsicWidth() > 0
              && span.getSize(label.getPaint(), text, 0, text.length(), null) == icon.getIntrinsicWidth() + 2 * side)
            return side;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // Unknown spans keep their own spacing.
      }
      return 0;
    }
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update(s); }
    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void afterTextChanged(Editable s) {}
  }

  // Keep the measured inter-item gap, the stack's total width and any roaming prefix.
  static void swapPositions(int[] widths, int[] positions, int bars, boolean rtl) {
    if (bars < 0 || bars + 2 != widths.length || widths.length != positions.length) return;
    int type = bars + 1;
    if (rtl) {
      int left = positions[type];
      positions[type] = positions[bars] + widths[bars] - widths[type];
      positions[bars] = left;
    } else {
      int left = positions[bars];
      positions[bars] = positions[type] + widths[type] - widths[bars];
      positions[type] = left;
    }
  }
}
