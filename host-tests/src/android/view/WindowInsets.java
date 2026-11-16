package android.view;import android.graphics.Insets;
public class WindowInsets {
 public static class Type{public static int statusBars(){return 1;} public static int navigationBars(){return 2;}public static int displayCutout(){return 4;}}
 public Insets safe=Insets.NONE,nav=Insets.NONE;public DisplayCutout cutout;
 public Insets getInsetsIgnoringVisibility(int t){return safe;}
 public Insets getInsets(int t){return nav;} public DisplayCutout getDisplayCutout(){return cutout;}
}