package dev.oos.nativepanels;

import static org.junit.Assert.*;

import org.junit.Test;

public class BrightnessTrackPaddingTest {
  public interface Predicate { boolean invoke(Object element); }

  public interface Modifier {
    boolean any(Predicate match);
    int endSpace();
    default Modifier then(Modifier next) { return new Chain(this, next); }
  }

  record Element(int endSpace) implements Modifier {
    public boolean any(Predicate match) { return match.invoke(this); }
  }

  record Chain(Modifier outer, Modifier inner) implements Modifier {
    public boolean any(Predicate match) { return outer.any(match) || inner.any(match); }
    public int endSpace() { return outer.endSpace() + inner.endSpace(); }
  }

  private final Element base = new Element(0), padding = new Element(56);
  private final BrightnessTrackPadding button;
  public BrightnessTrackPaddingTest() throws Exception {
    button = new BrightnessTrackPadding(Modifier.class, Predicate.class, base, padding);
  }

  private Object[] track(Modifier modifier, int defaults) {
    Object[] args = new Object[18];
    args[9] = modifier;
    args[15] = 0x7fffffff;
    args[17] = defaults;
    return args;
  }

  @Test public void successiveTrackRestartsKeepOneButtonAndOneGapAtAnyWidth() throws Exception {
    Object[] args = track(null, (1 << 9) | (1 << 11));
    assertTrue(button.reserve(args));
    assertEquals(1 << 11, args[17]);
    assertEquals(0x0fffffff, args[15]);
    Modifier captured = (Modifier)args[9];
    for (int rowWidth : new int[]{360, 360, 420, 320, 360, 360}) {
      // Compose may run a child restart while the container is on the stack.
      args = track(captured, 1 << 11);
      assertFalse(button.reserve(args));
      assertSame(captured, args[9]);
      assertEquals(0x7fffffff, args[15]);
      assertEquals(rowWidth - 56, rowWidth - captured.endSpace());
    }
  }

  @Test public void recognizesNestedOwnedPaddingAndPreservesIndependentHostInsets() throws Exception {
    Element hostPadding = new Element(56); // Same dimensions, distinct owner.
    Object[] fresh = track(hostPadding, 0);
    assertTrue(button.reserve(fresh));
    assertEquals(112, ((Modifier)fresh[9]).endSpace());
    Modifier decorated = base.then((Modifier)fresh[9]).then(base);
    Object[] resumed = track(decorated, 0);
    assertFalse(button.reserve(resumed));
    assertSame(decorated, resumed[9]);
    assertTrue(button.reserve(track(hostPadding, 0))); // A newly composed parent owns a new button.
  }
}
