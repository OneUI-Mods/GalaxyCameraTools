package dev.ryuya.galaxycamera;

import static org.junit.Assert.*;
import org.junit.Test;

public class PreviewPolicyTest {
  @Test
  public void supportedNormalPhotoCanUse60() {
    assertTrue(PreviewPolicy.eligible(true, 0, 1, true, 16666667, 0));
  }

  @Test
  public void unsupportedAndUnknownStreamsKeepNative() {
    assertFalse(PreviewPolicy.eligible(true, 0, 1, true, 0, 0));
    assertFalse(PreviewPolicy.eligible(true, 0, 1, true, 33333333, 0));
    assertFalse(PreviewPolicy.eligible(true, 0, 1, false, 16666667, 0));
  }

  @Test
  public void videoNightProAndHeatDoNotUsePhotoOverride() {
    assertFalse(PreviewPolicy.eligible(true, 1, 2, true, 16666667, 0));
    assertFalse(PreviewPolicy.eligible(true, 18, 1, true, 16666667, 0));
    assertFalse(PreviewPolicy.eligible(true, 3, 1, true, 16666667, 0));
    assertFalse(PreviewPolicy.eligible(true, 0, 1, true, 16666667, 3));
    assertFalse(PreviewPolicy.eligible(false, 0, 1, true, 16666667, 0));
  }
}
