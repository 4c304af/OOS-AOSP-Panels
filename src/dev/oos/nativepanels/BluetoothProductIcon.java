package dev.oos.nativepanels;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** SettingsLib's product photo and adaptive frame fitted to the QS device row. */
final class BluetoothProductIcon extends Drawable {
  private final Bitmap photo;
  private final Path mask;
  private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint outline;
  private final int outlineAlpha;
  private final int inset;
  private final Path frame = new Path();
  private final RectF image = new RectF();

  BluetoothProductIcon(Bitmap photo, Path mask, Paint outline, int inset) {
    this.photo = photo;
    this.mask = mask;
    this.outline = new Paint(outline);
    this.outlineAlpha = outline.getAlpha();
    this.inset = inset;
    surface.setColor(Color.WHITE);
  }

  @Override protected void onBoundsChange(Rect bounds) {
    float side = Math.min(bounds.width(), bounds.height());
    float framedSide = side - outline.getStrokeWidth();
    frame.rewind();
    image.setEmpty();
    if (framedSide <= 0) return;
    float cx = bounds.exactCenterX(), cy = bounds.exactCenterY();
    // The system icon mask uses a 100 x 100 viewport. Keep the outer half of its stroke in bounds.
    Matrix transform = new Matrix();
    transform.setScale(framedSide / 100f, framedSide / 100f);
    transform.postTranslate(cx - framedSide / 2f, cy - framedSide / 2f);
    mask.transform(transform, frame);

    // Keep even an opaque square photo inside the circular badge at larger display sizes.
    float available = Math.min(side - 2f * Math.min(inset, side / 4f), (float)(framedSide / Math.sqrt(2)));
    float scale = available / Math.max(photo.getWidth(), photo.getHeight());
    float halfWidth = photo.getWidth() * scale / 2f, halfHeight = photo.getHeight() * scale / 2f;
    image.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
  }

  @Override public void draw(Canvas canvas) {
    if (image.isEmpty()) return;
    canvas.drawPath(frame, surface);
    // An explicit destination also avoids Canvas applying the source bitmap's density a second time.
    canvas.drawBitmap(photo, null, image, surface);
    canvas.drawPath(frame, outline);
  }

  @Override public void setAlpha(int alpha) {
    surface.setAlpha(alpha);
    outline.setAlpha(outlineAlpha * alpha / 255);
    invalidateSelf();
  }

  @Override public int getAlpha() { return surface.getAlpha(); }

  @Override public void setColorFilter(ColorFilter filter) {
    surface.setColorFilter(filter);
    outline.setColorFilter(filter);
    invalidateSelf();
  }

  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
