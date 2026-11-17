package dev.oos.nativepanels;

/** Continuous join amounts shared by the steady and native animation renderers. */
final class NotificationCornerTransition {
  static final int TOP = 1, BOTTOM = 2;
  static final long DURATION_MILLIS = 180;

  private final Edge top = new Edge();
  private final Edge bottom = new Edge();

  void target(int joined, long now, boolean animationsEnabled) {
    top.target((joined & TOP) != 0 ? 1f : 0f, now, animationsEnabled);
    bottom.target((joined & BOTTOM) != 0 ? 1f : 0f, now, animationsEnabled);
  }

  float top(long now) { return top.value(now); }
  float bottom(long now) { return bottom.value(now); }

  boolean isRunning(long now) { return top.isRunning(now) || bottom.isRunning(now); }

  private static final class Edge {
    private boolean initialized;
    private float from, to;
    private long started;

    void target(float target, long now, boolean animate) {
      if (!initialized || !animate) {
        initialized = true;
        from = to = target;
        started = now;
      } else if (target != to) {
        // A reversal starts at the visible amount, not the previous endpoint.
        from = value(now);
        to = target;
        started = now;
      }
    }

    float value(long now) {
      if (from == to || now - started >= DURATION_MILLIS) return to;
      if (now <= started) return from;
      float fraction = (float)(now - started) / DURATION_MILLIS;
      return from + (to - from) * ease(fraction);
    }

    boolean isRunning(long now) {
      return from != to && now - started < DURATION_MILLIS;
    }
  }

  // Material fast-out-slow-in: cubic-bezier(0.4, 0, 0.2, 1). Invert x with
  // bounded work; no Animator, clock reads, allocation or idle frame loop.
  private static float ease(float fraction) {
    float low = 0f, high = 1f;
    for (int i = 0; i < 16; i++) {
      float t = (low + high) * .5f;
      float inverse = 1f - t;
      float x = 3f * inverse * inverse * t * .4f + 3f * inverse * t * t * .2f + t * t * t;
      if (x < fraction) low = t;
      else high = t;
    }
    float t = (low + high) * .5f;
    return t * t * (3f - 2f * t);
  }
}
