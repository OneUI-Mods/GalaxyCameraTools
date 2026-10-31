package dev.ryuya.galaxycamera

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.List
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmRecord

/* Explicit host contracts live in the versioned hook classes. */
object Reflect {
    // ClassValue releases entries with the host class loader; no global loader retention.
    private val FIELDS: ClassValue<MutableMap<String?, Field?>?> =
        object : ClassValue<MutableMap<String?, Field?>?>() {
            override fun computeValue(type: Class<*>?): MutableMap<String?, Field?>? {
                return ConcurrentHashMap<String?, Field?>()
            }
        }
    private val METHODS: ClassValue<MutableMap<Reflect.Signature?, Method?>?> =
        object : ClassValue<MutableMap<Reflect.Signature?, Method?>?>() {
            override fun computeValue(type: Class<*>?): MutableMap<Reflect.Signature?, Method?>? {
                return ConcurrentHashMap<Reflect.Signature?, Method?>()
            }
        }

    @kotlin.jvm.JvmStatic
    @Throws(NoSuchFieldException::class)
    fun field(type: Class<*>, name: String?): Field? {
        val cached = Reflect.FIELDS.get(type)!!.get(name)
        if (cached != null) return cached
        var c: Class<*>? = type
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f!!.setAccessible(true)
                Reflect.FIELDS.get(type)!!.put(name, f)
                return f
            } catch (ignored: NoSuchFieldException) {}
            c = c.getSuperclass()
        }
        throw NoSuchFieldException(type.getName() + "#" + name)
    }

    @kotlin.jvm.JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun get(target: Any, name: String?): Any? {
        return Reflect.field(if (target is Class<*>) target else target.javaClass, name)!!.get(
            if (target is Class<*>) null else target
        )
    }

    @kotlin.jvm.JvmStatic
    @Throws(NoSuchMethodException::class)
    fun method(type: Class<*>, name: String?, vararg args: Class<*>?): Method? {
        val signature = Reflect.Signature(name, List.of(*args))
        val cached = Reflect.METHODS.get(type)!!.get(signature)
        if (cached != null) return cached
        var c: Class<*>? = type
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *args)
                m!!.setAccessible(true)
                Reflect.METHODS.get(type)!!.put(signature, m)
                return m
            } catch (ignored: NoSuchMethodException) {}
            c = c.getSuperclass()
        }
        throw NoSuchMethodException(type.getName() + "#" + name)
    }

    @kotlin.jvm.JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun call(target: Any, name: String?): Any? {
        return Reflect.method(target.javaClass, name)!!.invoke(target)
    }

    @kotlin.jvm.JvmStatic
    fun value(type: Class<*>?, name: String?): Any? {
        val enumType = requireNotNull(type)
        val constantName = requireNotNull(name)
        return enumType.enumConstants!!.firstOrNull { (it as Enum<*>).name == constantName }
            ?: throw IllegalArgumentException("No enum constant ${enumType.name}.$constantName")
    }

    @JvmRecord
    private data class Signature(val name: String?, val parameters: MutableList<Class<*>?>?) {

        override fun toString(): String = "Signature[name=$name, parameters=$parameters]"
    }
}
