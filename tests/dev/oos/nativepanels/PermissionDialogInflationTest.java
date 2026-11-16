package dev.oos.nativepanels;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PermissionDialogInflationTest {
  @Test public void inflatedViewsUsePresentationResourcesWithoutChangingTheirActivity() throws Throwable {
    try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Resources originalResources = activity.getResources();
      Resources.Theme originalTheme = activity.getTheme();
      Configuration originalConfiguration = new Configuration(originalResources.getConfiguration());
      Context presentation = presentation(activity, Locale.JAPANESE);
      LayoutInflater original = LayoutInflater.from(activity);
      PermissionDialogInflation scope = new PermissionDialogInflation();

      View view = (View)scope.run(activity, presentation,
          () -> scope.select(activity, original).inflate(android.R.layout.simple_list_item_1, null));

      assertSame(presentation, view.getContext());
      assertSame(presentation.getResources(), view.getResources());
      assertSame(presentation.getResources().getAssets(), presentation.getAssets());
      assertSame(presentation, LayoutInflater.from(presentation).getContext());
      assertEquals("ja", view.getResources().getConfiguration().getLocales().get(0).getLanguage());
      assertNotSame(originalResources, view.getResources());
      assertSame(originalResources, activity.getResources());
      assertSame(originalTheme, activity.getTheme());
      assertEquals(originalConfiguration, activity.getResources().getConfiguration());
      assertSame(original, scope.select(activity, original));
    }
  }

  @Test public void cloningPreservesTheFactoryAndTheHandlersClickListenerAfterInflation() throws Throwable {
    try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Context presentation = presentation(activity, Locale.ENGLISH);
      LayoutInflater original = LayoutInflater.from(activity).cloneInContext(activity);
      AtomicReference<Context> factoryContext = new AtomicReference<>();
      LayoutInflater.Factory2 factory = new LayoutInflater.Factory2() {
        @Override public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
          if (!"TextView".equals(name)) return null;
          factoryContext.set(context);
          return new Button(context, attrs);
        }
        @Override public View onCreateView(String name, Context context, AttributeSet attrs) {
          return onCreateView(null, name, context, attrs);
        }
      };
      original.setFactory2(factory);
      PermissionDialogInflation scope = new PermissionDialogInflation();
      AtomicInteger callbacks = new AtomicInteger();
      AtomicReference<View> clickedView = new AtomicReference<>();
      View.OnClickListener resultListener = view -> {
        callbacks.incrementAndGet();
        clickedView.set(view);
      };

      Button button = (Button)scope.run(activity, presentation, () -> {
        LayoutInflater selected = scope.select(activity, original);
        assertSame(factory, selected.getFactory2());
        Button inflated = (Button)selected.inflate(android.R.layout.simple_list_item_1, null);
        // The original handler still binds its callback to the view it just inflated.
        inflated.setOnClickListener(resultListener);
        return inflated;
      });

      assertSame(presentation, factoryContext.get());
      assertSame(original, scope.select(activity, original));
      assertEquals(0, callbacks.get());
      assertTrue(button.performClick());
      assertEquals(1, callbacks.get());
      assertSame(button, clickedView.get());
      assertSame(activity, original.getContext());
    }
  }

  @Test public void unrelatedContextsAndOtherThreadsKeepTheirOriginalInflater() throws Throwable {
    try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Context presentation = presentation(activity, Locale.ENGLISH);
      Context unrelated = activity.getApplicationContext();
      LayoutInflater original = LayoutInflater.from(activity);
      LayoutInflater other = LayoutInflater.from(unrelated);
      PermissionDialogInflation scope = new PermissionDialogInflation();
      var executor = Executors.newSingleThreadExecutor();
      try {
        scope.run(activity, presentation, () -> {
          assertSame(other, scope.select(unrelated, other));
          assertSame(presentation, scope.select(activity, original).getContext());
          assertSame(original, executor.submit(() -> scope.select(activity, original)).get(5, TimeUnit.SECONDS));
          return null;
        });
      } finally { executor.shutdownNow(); }
      assertSame(original, scope.select(activity, original));
    }
  }

  @Test public void nestedInflationsRestoreTheOuterContextAfterAnException() throws Throwable {
    try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Context outer = presentation(activity, Locale.ENGLISH);
      Context inner = presentation(activity, Locale.JAPANESE);
      LayoutInflater original = LayoutInflater.from(activity);
      PermissionDialogInflation scope = new PermissionDialogInflation();
      RuntimeException failure = new RuntimeException("fixture inflation failure");
      Object result = new Object();
      assertSame(result, scope.run(activity, outer, () -> {
        assertSame(outer, scope.select(activity, original).getContext());
        try {
          scope.run(activity, inner, () -> {
            assertSame(inner, scope.select(activity, original).getContext());
            throw failure;
          });
          fail("The handler failure must reach its caller");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertSame(outer, scope.select(activity, original).getContext());
        return result;
      }));
      assertSame(original, scope.select(activity, original));
    }
  }

  @Test public void handlerExceptionsDoNotRetryTheActionOrLeakItsPresentationContext() throws Throwable {
    try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      Context presentation = presentation(activity, Locale.ENGLISH);
      LayoutInflater original = LayoutInflater.from(activity);
      PermissionDialogInflation scope = new PermissionDialogInflation();
      AtomicInteger calls = new AtomicInteger();
      RuntimeException failure = new RuntimeException("fixture handler failure");
      try {
        scope.run(activity, presentation, () -> {
          calls.incrementAndGet();
          throw failure;
        });
        fail("The handler failure must reach its caller");
      } catch (RuntimeException actual) { assertSame(failure, actual); }
      assertEquals(1, calls.get());
      assertSame(original, scope.select(activity, original));
    }
  }

  @Test public void resourcePreparationRejectsAContextThatSharesTheActivityResources() throws Exception {
    try (var controller = Robolectric.buildActivity(SharedResourcesActivity.class).setup()) {
      Activity activity = controller.get();
      Resources original = activity.getResources();
      var getLoaders = Resources.class.getDeclaredMethod("getLoaders");
      var loaders = getLoaders.invoke(original);
      assertThrows(IllegalStateException.class, () -> new PermissionDialogResources().create(activity));
      assertSame(original, activity.getResources());
      assertEquals(loaders, getLoaders.invoke(original));
    }
  }

  private static Context presentation(Activity activity, Locale locale) throws Exception {
    Configuration configuration = new Configuration();
    configuration.setLocales(new LocaleList(locale));
    Resources resources = activity.createConfigurationContext(configuration).getResources();
    Resources.Theme theme = resources.newTheme();
    theme.setTo(activity.getTheme());
    // Exercise the production wrapper without pretending this fixture contains the ROM's styles.
    Class<?> type = Class.forName(PermissionDialogResources.class.getName() + "$PresentationContext");
    Constructor<?> constructor = type.getDeclaredConstructor(Activity.class, Resources.class, Resources.Theme.class);
    constructor.setAccessible(true);
    return (Context)constructor.newInstance(activity, resources, theme);
  }

  public static final class SharedResourcesActivity extends Activity {
    @Override public Context createConfigurationContext(Configuration configuration) { return this; }
  }
}
