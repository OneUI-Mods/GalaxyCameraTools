package dev.ryuya.galaxycamera

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** Project-local adapter for API 102's interceptor chain. */
object ModernHook {
    @Volatile private var module: XposedModule? = null

    @JvmStatic
    fun attach(value: XposedModule) {
        check(value.apiVersion >= XposedInterface.API_102) { "Xposed API 102 is required" }
        module = value
    }

    @JvmStatic fun apiVersion(): Int = requireModule().apiVersion

    @JvmStatic
    fun frameworkDescription(): String =
        requireModule().let {
            "${it.frameworkName} ${it.frameworkVersion}"
        }

    @JvmStatic
    @Throws(ClassNotFoundException::class)
    fun findClass(name: String, loader: ClassLoader?): Class<*> = Class.forName(name, false, loader)

    @JvmStatic
    fun findAndHookMethod(owner: Class<*>, name: String, vararg signature: Any?): Handle {
        val callback =
            signature.lastOrNull() as? Callback
                ?: throw IllegalArgumentException("Last argument must be a callback")
        val parameters = Array(signature.size - 1) { signature[it] as Class<*> }
        try {
            val method = owner.getDeclaredMethod(name, *parameters)
            method.isAccessible = true
            return hookMethod(method, callback)
        } catch (error: NoSuchMethodException) {
            throw IllegalStateException("${owner.name}#$name signature changed", error)
        }
    }

    @JvmStatic
    fun hookMethod(executable: Executable, callback: Callback): Handle {
        val handle =
            requireModule()
                .hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val param = Param(chain)
                    callback.before(param)
                    param.proceed(chain)
                    callback.after(param)
                    param.resultOrThrow()
                }
        return Handle { handle.unhook() }
    }

    fun interface Around {
        @Throws(Throwable::class) fun run(chain: XposedInterface.Chain): Any?
    }

    @JvmStatic
    fun aroundMethod(executable: Executable, around: Around): Handle {
        val handle =
            requireModule()
                .hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(around::run)
        return Handle { handle.unhook() }
    }

    @JvmStatic
    fun hookAllConstructors(owner: Class<*>, callback: Callback): List<Handle> {
        val installed = mutableListOf<Handle>()
        try {
            for (constructor in owner.declaredConstructors) {
                constructor.isAccessible = true
                installed += hookMethod(constructor, callback)
            }
            return installed
        } catch (error: Throwable) {
            installed.forEach { it.unhook() }
            when (error) {
                is RuntimeException -> throw error
                is Error -> throw error
                else -> throw IllegalStateException(error)
            }
        }
    }

    @JvmStatic
    fun deoptimize(executable: Executable): Boolean = requireModule().deoptimize(executable)

    @JvmStatic
    fun log(message: String) {
        module?.log(Log.INFO, "GalaxyCameraTools", message)
    }

    fun interface Handle {
        fun unhook()
    }

    abstract class Callback {
        @Throws(Throwable::class) protected open fun beforeHookedMethod(param: Param) {}

        @Throws(Throwable::class) protected open fun afterHookedMethod(param: Param) {}

        internal fun before(param: Param) = beforeHookedMethod(param)

        internal fun after(param: Param) = afterHookedMethod(param)
    }

    class Param(chain: XposedInterface.Chain) {
        @JvmField val thisObject: Any? = chain.thisObject
        @JvmField val args: Array<Any?> = chain.args.toTypedArray()
        private var result: Any? = null
        private var throwable: Throwable? = null
        private var returnEarly = false
        private var extras: MutableMap<String, Any?>? = null

        fun hasThrowable(): Boolean = throwable != null

        fun getResult(): Any? = result

        fun setResult(value: Any?) {
            result = value
            throwable = null
            returnEarly = true
        }

        fun setObjectExtra(key: String, value: Any?) {
            val values = extras ?: mutableMapOf<String, Any?>().also { extras = it }
            values[key] = value
        }

        fun getObjectExtra(key: String): Any? = extras?.get(key)

        internal fun proceed(chain: XposedInterface.Chain) {
            if (returnEarly) return
            try {
                result = chain.proceed(args)
            } catch (error: Throwable) {
                throwable = error
            }
        }

        internal fun resultOrThrow(): Any? {
            throwable?.let { throw it }
            return result
        }
    }

    @JvmStatic
    private fun requireModule(): XposedModule =
        checkNotNull(module) { "Modern Xposed module is not attached" }
}
