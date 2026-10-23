package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;
import org.junit.Test;

public class CrashWindowTest {
  @Test
  public void thirdConfirmedCrashInWindowBlocks() {
    var a = CrashWindow.record(1000, 0, 0);
    var b = CrashWindow.record(10000, a.started(), a.count());
    assertFalse(b.blocked());
    assertTrue(CrashWindow.record(120999, b.started(), b.count()).blocked());
  }

  @Test
  public void spacedCrashesDoNotTurnIntoASlidingWindow() {
    var a = CrashWindow.record(1000, 0, 0);
    var b = CrashWindow.record(120000, a.started(), a.count());
    var c = CrashWindow.record(239000, b.started(), b.count());
    assertEquals(1, c.count());
    assertFalse(c.blocked());
  }

  @Test
  public void clockRollbackAndExactExpiryStartANewWindow() {
    assertEquals(1, CrashWindow.record(900, 1000, 2).count());
    assertEquals(1, CrashWindow.record(121000, 1000, 2).count());
  }
}
