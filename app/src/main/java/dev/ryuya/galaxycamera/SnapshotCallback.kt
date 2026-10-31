package dev.ryuya.galaxycamera

import java.lang.reflect.Proxy

/** Zero-argument proxy calls pass a null argument array on the JVM and Android. */
internal object SnapshotCallback {
    fun create(loader: ClassLoader, type: Class<*>, shown: Runnable): Any =
        Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "Galaxy Camera Tools snapshot callback"
                }
            } else {
                if (method.name == "onPreviewSnapshotShow") shown.run()
                null
            }
        }
}
