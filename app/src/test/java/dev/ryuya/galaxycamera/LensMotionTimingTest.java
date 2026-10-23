package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;

import org.junit.Test;

public class LensMotionTimingTest {
  @Test
  public void shortTransitionsAreVisibleWithoutMakingLongTransitionsUnbounded() {
    assertEquals(320, LensMotionTiming.duration(144));
    assertEquals(454, LensMotionTiming.duration(336));
    assertEquals(1100, LensMotionTiming.duration(3000));
  }

  @Test
  public void easingAcceleratesThenDeceleratesWithoutOvershoot() {
    float startStep = LensMotionTiming.phase(.1f) - LensMotionTiming.phase(0);
    float middleStep = LensMotionTiming.phase(.5f) - LensMotionTiming.phase(.4f);
    float endStep = LensMotionTiming.phase(1f) - LensMotionTiming.phase(.9f);
    assertTrue(middleStep > startStep * 2);
    assertEquals(startStep, endStep, .00001f);
    float previous = 0;
    for (int i = 0; i <= 100; i++) {
      float current = LensMotionTiming.phase(i / 100f);
      assertTrue(current >= previous && current <= 1f);
      previous = current;
    }
    assertEquals(0, LensMotionTiming.phase(-1), 0);
    assertEquals(1, LensMotionTiming.phase(2), 0);
  }

  @Test
  public void nativeHandoffUsesTheEasedPositionRatherThanWallTime() {
    int duration = LensMotionTiming.duration(336);
    assertEquals(0, LensMotionTiming.curveTime(-100, duration, 336));
    assertEquals(336, LensMotionTiming.curveTime(duration + 100, duration, 336));
    assertTrue(LensMotionTiming.curveTime(duration / 4, duration, 336) < 336 / 4);
    assertTrue(LensMotionTiming.curveTime(duration * 3 / 4, duration, 336) > 336 * 3 / 4);
  }
}
