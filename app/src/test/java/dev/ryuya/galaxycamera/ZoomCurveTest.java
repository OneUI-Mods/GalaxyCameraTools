package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class ZoomCurveTest {
  @Test
  public void nativeTimingAndLensEndpointsArePreserved() {
    var source =
        List.of(
            new ZoomCurve.Point(.5f, 32), new ZoomCurve.Point(1f, 16), new ZoomCurve.Point(3f, 0));
    var result = ZoomCurve.atSixtyHz(source);
    assertEquals(4, result.size());
    assertEquals(.75f, result.get(1).zoom(), 0f);
    assertEquals(.5f, result.get(0).zoom(), 0f);
    assertEquals(32, source.get(0).delayMillis());
    assertEquals(48, result.stream().mapToInt(ZoomCurve.Point::delayMillis).sum());
    assertEquals(source.get(2), result.get(result.size() - 1));
    assertTrue(result.stream().allMatch(point -> point.delayMillis() <= 17));
  }

  @Test
  public void reverseCurveRetainsDirectionWithoutOvershooting() {
    var result =
        ZoomCurve.atSixtyHz(List.of(new ZoomCurve.Point(5f, 33), new ZoomCurve.Point(1f, 0)));
    assertEquals(3, result.size());
    assertTrue(result.get(1).zoom() > 1 && result.get(1).zoom() < 5);
    assertEquals(33, result.stream().mapToInt(ZoomCurve.Point::delayMillis).sum());
  }

  @Test
  public void invalidOrAlreadyFineGrainedCurvesAreUnchanged() {
    var valid = List.of(new ZoomCurve.Point(1f, 16), new ZoomCurve.Point(3f, 0));
    assertEquals(valid, ZoomCurve.atSixtyHz(valid));
    var invalid = List.of(new ZoomCurve.Point(1f, -1), new ZoomCurve.Point(3f, 0));
    assertSame(invalid, ZoomCurve.atSixtyHz(invalid));
  }
}
