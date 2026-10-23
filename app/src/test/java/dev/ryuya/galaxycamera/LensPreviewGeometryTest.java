package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;

import org.junit.Test;

public class LensPreviewGeometryTest {
  @Test
  public void outgoingPictureAndIncomingViewHaveTheSameFieldOfViewDuringZoomOut() {
    float from = 5f, target = 1f;
    for (float current : new float[] {5f, 4f, 3f, 2f, 1f}) {
      assertEquals(
          from * LensPreviewGeometry.snapshotScale(from, current),
          target * LensPreviewGeometry.incomingScale(target, current),
          0.00001f);
    }
  }

  @Test
  public void incomingPreviewNeverShrinksToRevealUnpaintedEdges() {
    for (float target : new float[] {0.6f, 1f, 3f, 5f})
      for (float current : new float[] {0.6f, 1f, 2f, 3f, 5f})
        assertTrue(LensPreviewGeometry.incomingScale(target, current) >= 1f);
  }

  @Test
  public void completedTransitionReturnsIncomingPreviewToItsOriginalScale() {
    for (float target : new float[] {0.6f, 1f, 3f, 5f})
      assertEquals(1f, LensPreviewGeometry.incomingScale(target, target), 0f);
  }
}
