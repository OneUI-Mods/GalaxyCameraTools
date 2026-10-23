package dev.ryuya.galaxycamera;

/** Fixed two-minute windows: normal restarts never enter this ledger. */
final class CrashWindow {
  record State(long started, int count) {
    boolean blocked() {
      return count >= 3;
    }
  }

  static State record(long now, long started, int count) {
    if (now < started || now - started >= 120000 || count <= 0) return new State(now, 1);
    return new State(started, count + 1);
  }
}
