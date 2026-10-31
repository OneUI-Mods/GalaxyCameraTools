package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

class CameraMetadataTypesTest {
    @Test
    fun integerKeyRetainsBoxedJavaType() {
        assertEquals("java.lang.Integer", CameraMetadataTypes.integer.name)
        assertFalse(CameraMetadataTypes.integer.isPrimitive)
        assertTrue(CameraMetadataTypes.integer.isInstance(120))
    }

    @Test
    fun zoomKeyRetainsBoxedJavaType() {
        assertEquals("java.lang.Float", CameraMetadataTypes.float.name)
        assertFalse(CameraMetadataTypes.float.isPrimitive)
        assertTrue(CameraMetadataTypes.float.isInstance(0.6f))
    }
}
