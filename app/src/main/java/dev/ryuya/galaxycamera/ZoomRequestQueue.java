package dev.ryuya.galaxycamera;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Keeps one outstanding camera update and replaces its value instead of building a backlog. */
final class ZoomRequestQueue {
  interface Writer {
    void apply(int value) throws Exception;
  }

  private final Executor executor;
  private final Runnable drainTask = this::drain;
  private final Writer writer;
  private final Runnable completed;
  private final Consumer<Exception> failed;
  private int latest;
  private long revision;
  private boolean scheduled, cancelled, finishRequested;

  ZoomRequestQueue(
      Executor executor, Writer writer, Runnable completed, Consumer<Exception> failed) {
    this.executor = executor;
    this.writer = writer;
    this.completed = completed;
    this.failed = failed;
  }

  synchronized void submit(int value, boolean finish) {
    if (cancelled) return;
    latest = value;
    revision++;
    finishRequested = finish;
    if (!scheduled) {
      scheduled = true;
      executor.execute(drainTask);
    }
  }

  synchronized void cancel() {
    cancelled = true;
  }

  private void drain() {
    int value;
    long version;
    synchronized (this) {
      if (cancelled) return;
      value = latest;
      version = revision;
    }
    try {
      writer.apply(value);
    } catch (Exception error) {
      synchronized (this) {
        if (cancelled) return;
        cancelled = true;
      }
      failed.accept(error);
      return;
    }
    boolean finish;
    synchronized (this) {
      if (cancelled) return;
      if (version != revision) {
        executor.execute(drainTask);
        return;
      }
      scheduled = false;
      finish = finishRequested;
      if (finish) cancelled = true;
    }
    if (finish) completed.run();
  }
}
