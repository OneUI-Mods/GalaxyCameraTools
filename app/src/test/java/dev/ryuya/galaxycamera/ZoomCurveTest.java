package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ZoomCurveTest {
  private final List<ZoomCurve.Point> curve =
      List.of(
          new ZoomCurve.Point(1f, 32), new ZoomCurve.Point(1.2f, 16), new ZoomCurve.Point(3f, 0));

  @Test
  public void nativeEasingAndDurationArePreserved() {
    assertEquals(48, ZoomCurve.duration(curve));
    assertEquals(1.1f, ZoomCurve.valueAt(curve, 16), .0001f);
    assertEquals(2.1f, ZoomCurve.valueAt(curve, 40), .0001f);
    assertEquals(1f, ZoomCurve.valueAt(curve, -10), 0f);
    assertEquals(3f, ZoomCurve.valueAt(curve, 1000), 0f);
  }

  @Test
  public void missedFramesSampleCurrentTimeWithoutReplayingOldSteps() {
    assertEquals(2.55f, ZoomCurve.valueAt(curve, 44), .0001f);
    assertEquals(2, ZoomCurve.nextIndex(curve, 44));
    assertEquals(2, ZoomCurve.nextIndex(curve, 999));
  }

  @Test
  public void reverseDirectionDoesNotOvershoot() {
    var reverse = List.of(new ZoomCurve.Point(5f, 33), new ZoomCurve.Point(1f, 0));
    assertEquals(33, ZoomCurve.duration(reverse));
    assertTrue(ZoomCurve.valueAt(reverse, 16) > 1 && ZoomCurve.valueAt(reverse, 16) < 5);
    assertEquals(1f, ZoomCurve.valueAt(reverse, 33), 0f);
  }

  @Test
  public void invalidCurveIsRejectedBeforeTakingOverTheNativeRunner() {
    assertEquals(
        -1, ZoomCurve.duration(List.of(new ZoomCurve.Point(1f, -1), new ZoomCurve.Point(3f, 0))));
    assertEquals(
        -1,
        ZoomCurve.duration(
            List.of(new ZoomCurve.Point(Float.NaN, 16), new ZoomCurve.Point(3f, 0))));
  }
}
