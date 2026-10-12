package io.github.meiyongai.toki.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.download.DownloadMenu
import io.github.meiyongai.toki.provider.ConfigClient
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import java.io.BufferedInputStream
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Optional download submenu, discovered by behavior rather than a version-specific X.* name. */
internal object DownloadMenuHook {
    private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val started = AtomicBoolean()

    fun init(module: XposedModule, loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 29) return // Retain native saving on Android 9.
        val resources = ModuleResources(module.moduleApplicationInfo, checkNotNull(javaClass.classLoader))
        val application = Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java)
        module.trackHook("DownloadHook", application).intercept { chain ->
            val result = chain.proceed()
            val app = chain.args[0] as Application
            if (app.packageName in setOf(TokiModule.PKG_TIKTOK_GLOBAL, TokiModule.PKG_TIKTOK_TRILL) &&
                android.app.Application.getProcessName() == app.packageName && started.compareAndSet(false, true)) {
                Thread({
                    try {
                        val info = app.applicationInfo
                        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
                        val candidates = discover(paths)
                        check(candidates.size == 1) { "下载按钮指纹不唯一：${candidates.size}" }
                        val target = candidates.single()
                        val owner = loader.loadClass(target.owner.removePrefix("L").removeSuffix(";").replace('/', '.'))
                        val aweme = loader.loadClass(AWEME.removePrefix("L").removeSuffix(";").replace('/', '.'))
                        val action = owner.declaredMethods.single { method ->
                            method.name == target.name && method.returnType == Void.TYPE &&
                                method.parameterTypes.map(::descriptor) == target.parameters && !Modifier.isStatic(method.modifiers)
                        }.apply { isAccessible = true }
                        module.trackHook("DownloadHook", action).intercept { call ->
                            if (!ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_FORCE_NO_WATERMARK) ||
                                Looper.myLooper() != Looper.getMainLooper()) return@intercept call.proceed()
                            // Use this action's Aweme argument, never the last video queried on the main thread.
                            val post = call.args.firstOrNull { aweme.isInstance(it) } ?: return@intercept call.proceed()
                            val activity = call.args.filterIsInstance<Context>().mapNotNull(::activity).firstOrNull()
                                ?: ownerActivity(call.thisObject) ?: return@intercept call.proceed()
                            val shown = runCatching { DownloadMenu.show(activity, resources.createActivityContext(activity), post) }
                                .onFailure { HookRuntime.failure("DownloadHook", it, "下载子菜单未打开") }.getOrDefault(false)
                            if (shown) null else call.proceed()
                        }
                        HookRuntime.state("DownloadSubmenu", "已注册")
                    } catch (error: Exception) {
                        // The existing watermark/path hooks stay usable when menu discovery is unavailable.
                        HookRuntime.failure("DownloadHook", error, "下载子菜单适配失败，基础保存功能可用")
                        HookRuntime.state("DownloadSubmenu", "下载子菜单适配失败")
                    }
                }, "TokiDownloadMenuScan").apply { priority = Thread.MIN_PRIORITY }.start()
            }
            result
        }
    }

    internal data class Action(val owner: String, val name: String, val parameters: List<String>)

    internal fun matches(method: Method): Boolean {
        if (method.returnType != "V" || method.name.startsWith('<') || method.accessFlags and 8 != 0 ||
            AWEME !in method.parameterTypes.map { it.toString() } || method.implementation == null) return false
        val parameters = method.parameterTypes.map { it.toString() }
        if (parameters.none { it in setOf("Landroid/content/Context;", "Landroid/app/Activity;") }) return false
        val literals = method.implementation!!.instructions.asSequence().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toSet()
        return "download_method" in literals && "single_song" in literals
    }

    private fun discover(paths: List<String>): Set<Action> = buildSet {
        for (path in paths) ZipFile(path).use { zip ->
            for (entry in zip.entries().asSequence().filter { it.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }) {
                zip.getInputStream(entry).use { input ->
                    val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), BufferedInputStream(input))
                    for (type in dex.classes) for (method in type.methods) if (matches(method))
                        add(Action(method.definingClass, method.name, method.parameterTypes.map { it.toString() }))
                }
            }
        }
    }

    private fun descriptor(type: Class<*>): String = when {
        type.isArray -> type.name.replace('.', '/')
        !type.isPrimitive -> "L${type.name.replace('.', '/')};"
        else -> when (type) {
            Boolean::class.javaPrimitiveType -> "Z"; Int::class.javaPrimitiveType -> "I"
            Long::class.javaPrimitiveType -> "J"; Float::class.javaPrimitiveType -> "F"
            Double::class.javaPrimitiveType -> "D"; Byte::class.javaPrimitiveType -> "B"
            Short::class.javaPrimitiveType -> "S"; Char::class.javaPrimitiveType -> "C"; else -> "V"
        }
    }

    private fun activity(value: Context): Activity? {
        var context = value
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (seen.add(context)) {
            if (context is Activity) return context
            if (context !is ContextWrapper) return null
            context = context.baseContext
        }
        return null
    }

    private fun ownerActivity(owner: Any?): Activity? {
        if (owner == null) return null
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) if (!Modifier.isStatic(field.modifiers) &&
                Activity::class.java.isAssignableFrom(field.type)) {
                val result = runCatching { field.isAccessible = true; field.get(owner) as? Activity }.getOrNull()
                if (result != null) return result
            }
            type = type.superclass
        }
        return null
    }
}
