package dev.ryuya.galaxycamera

/** Camera2 key types must retain the boxed Java metadata contract. */
internal object CameraMetadataTypes {
    val integer: Class<Int> = Int::class.javaObjectType
    val float: Class<Float> = Float::class.javaObjectType
}
