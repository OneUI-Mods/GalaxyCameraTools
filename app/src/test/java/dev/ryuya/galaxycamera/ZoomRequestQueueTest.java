package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ZoomRequestQueueTest {
  @Test
  public void pendingFramesCollapseToTheLatestValueAndFinishAfterItIsWritten() {
    var tasks = new ArrayDeque<Runnable>();
    var writes = new ArrayList<Integer>();
    var finished = new ArrayList<Boolean>();
    var queue =
        new ZoomRequestQueue(
            tasks::add, writes::add, () -> finished.add(true), error -> fail(error.toString()));
    queue.submit(1000, false);
    queue.submit(1500, false);
    queue.submit(3000, true);
    assertEquals(1, tasks.size());
    assertTrue(finished.isEmpty());
    tasks.remove().run();
    assertEquals(List.of(3000), writes);
    assertEquals(List.of(true), finished);
    assertTrue(tasks.isEmpty());
  }

  @Test
  public void cancelledMotionCannotWriteItsQueuedFrame() {
    var tasks = new ArrayDeque<Runnable>();
    var writes = new ArrayList<Integer>();
    var queue =
        new ZoomRequestQueue(
            tasks::add,
            writes::add,
            () -> fail("Cancelled motion completed"),
            error -> fail(error.toString()));
    queue.submit(5000, true);
    queue.cancel();
    tasks.remove().run();
    assertTrue(writes.isEmpty());
  }

  @Test
  public void newFrameArrivingDuringWriteIsAppliedBeforeCompletion() {
    var tasks = new ArrayDeque<Runnable>();
    var writes = new ArrayList<Integer>();
    var finished = new ArrayList<Boolean>();
    ZoomRequestQueue[] holder = new ZoomRequestQueue[1];
    holder[0] =
        new ZoomRequestQueue(
            tasks::add,
            value -> {
              writes.add(value);
              if (value == 1000) holder[0].submit(3000, true);
            },
            () -> finished.add(true),
            error -> fail(error.toString()));
    holder[0].submit(1000, false);
    tasks.remove().run();
    assertTrue(finished.isEmpty());
    assertEquals(1, tasks.size());
    tasks.remove().run();
    assertEquals(List.of(1000, 3000), writes);
    assertEquals(List.of(true), finished);
  }

  @Test
  public void writerFailureStopsFurtherUpdatesAndReportsOnce() {
    var tasks = new ArrayDeque<Runnable>();
    var errors = new ArrayList<Exception>();
    var queue =
        new ZoomRequestQueue(
            tasks::add,
            value -> {
              throw new Exception("unavailable");
            },
            () -> fail("Failed motion completed"),
            errors::add);
    queue.submit(1000, false);
    tasks.remove().run();
    queue.submit(3000, true);
    assertEquals(1, errors.size());
    assertTrue(tasks.isEmpty());
  }
}
