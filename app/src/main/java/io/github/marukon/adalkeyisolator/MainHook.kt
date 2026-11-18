package io.github.marukon.adalkeyisolator

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.security.KeyStore
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainHook : XposedModule() {

    private val bootHooked = AtomicBoolean(false)
    private val appHooked = AtomicBoolean(false)
    private val firstRewriteLogged = AtomicBoolean(false)
    private val hookedClassLoaders = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())
    private val msalHookLabels = CopyOnWriteArraySet<String>()

    private val bootHookCount = AtomicInteger(0)
    private val msalHookCount = AtomicInteger(0)
    private val rewriteCount = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var targetPackage: String? = null

    @Volatile
    private var cachedVersionName: String = ""

    @Volatile
    private var lastError: String? = null

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        val pkg = param.packageName
        if (pkg == "system" || pkg == "android") return

        if (targetPackage == null && param.isFirstPackage) {
            targetPackage = pkg
        }
        val activePkg = targetPackage ?: pkg
        val isolatedAlias = "${ADAL_KEY_ALIAS}_$activePkg"

        if (param.isFirstPackage && bootHooked.compareAndSet(false, true)) {
            hookAndroidKeyStore(isolatedAlias)
        }
        if (appHooked.compareAndSet(false, true)) {
            hookApplicationOnCreate(activePkg)
        }
        hookMicrosoftProviders(activePkg, isolatedAlias, param.classLoader)
    }

    private fun onAliasRewritten(isolatedAlias: String) {
        rewriteCount.incrementAndGet()
        if (firstRewriteLogged.compareAndSet(false, true)) {
            logBoth(Log.INFO, "Rewriting alias '$ADAL_KEY_ALIAS' -> '$isolatedAlias'")
            reportStatusAsync(targetPackage)
        }
    }

    private fun proceedWithReplacedAlias(
        chain: XposedInterface.Chain,
        argIndex: Int,
        isolatedAlias: String,
        requireAndroidKeyStore: Boolean = false
    ): Any? {
        if (requireAndroidKeyStore) {
            val ks = chain.thisObject as? KeyStore ?: return chain.proceed()
            if (!ANDROID_KEY_STORE.equals(ks.type, ignoreCase = true)) {
                return chain.proceed()
            }
        }
        val raw = chain.getArg(argIndex) as? String
        if (raw == ADAL_KEY_ALIAS) {
            onAliasRewritten(isolatedAlias)
            val newArgs = chain.args.toTypedArray()
            newArgs[argIndex] = isolatedAlias
            return chain.proceed(newArgs)
        }
        return chain.proceed()
    }

    private fun hookApplicationOnCreate(pkg: String) {
        runCatching {
            val onCreateMethod = Application::class.java.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null && appContext == null) {
                    val ctx = app.applicationContext ?: app
                    appContext = ctx
                    cachedVersionName = runCatching {
                        ctx.packageManager.getPackageInfo(
                            pkg,
                            PackageManager.PackageInfoFlags.of(0L)
                        ).versionName.orEmpty()
                    }.getOrDefault("")
                    registerPingReceiver(app, pkg)
                    reportStatusAsync(pkg)
                }
                result
            }
        }.onFailure {
            recordError("Application.onCreate hook failed: ${it.message}")
        }
    }

    private fun registerPingReceiver(app: Application, pkg: String) {
        if (Application.getProcessName() != pkg) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    setResult(Activity.RESULT_OK, pkg, buildStatusBundle(pkg))
                    reportStatusAsync(pkg)
                }
            }
            app.registerReceiver(receiver, IntentFilter(ACTION_PING), Context.RECEIVER_EXPORTED)
        }.onFailure {
            recordError("Ping receiver failed: ${it.message}", it)
        }
    }

    private fun buildStatusBundle(pkgName: String): Bundle {
        val ctx = appContext
        if (cachedVersionName.isEmpty() && ctx != null) {
            cachedVersionName = runCatching {
                ctx.packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.PackageInfoFlags.of(0L)
                ).versionName.orEmpty()
            }.getOrDefault("")
        }
        val msalSummary = if (msalHookLabels.isEmpty()) {
            "none"
        } else {
            msalHookLabels.sorted().joinToString(", ")
        }
        return Bundle().apply {
            putInt(StatusProvider.KEY_BOOT_HOOK_COUNT, bootHookCount.get())
            putInt(StatusProvider.KEY_MSAL_HOOK_COUNT, msalHookCount.get())
            putInt(StatusProvider.KEY_REWRITE_COUNT, rewriteCount.get())
            putString(StatusProvider.KEY_PROCESS_NAME, Application.getProcessName())
            putString(StatusProvider.KEY_VERSION_NAME, cachedVersionName)
            putString(StatusProvider.KEY_HOOK_DETAILS, "KeyStore=${bootHookCount.get()} | MSAL=[$msalSummary]")
            putString(StatusProvider.KEY_ERROR, lastError.orEmpty())
        }
    }

    private fun reportStatusAsync(pkg: String?) {
        val ctx = appContext ?: return
        val pkgName = pkg ?: return
        if (Application.getProcessName() != pkgName) return
        Thread({
            runCatching {
                ctx.contentResolver.call(
                    Uri.parse("content://${StatusProvider.AUTHORITY}"),
                    StatusProvider.METHOD_REPORT,
                    pkgName,
                    buildStatusBundle(pkgName)
                )
            }
        }, "AdalKeyStatusReporter").apply {
            isDaemon = true
            start()
        }
    }

    private fun recordError(msg: String, tr: Throwable? = null) {
        lastError = if (lastError.isNullOrEmpty()) msg else "$lastError; $msg"
        logBoth(Log.ERROR, msg, tr)
    }

    private fun hookAndroidKeyStore(isolatedAlias: String) {
        runCatching {
            val aliasMethods = KeyStore::class.java.declaredMethods.filter { m ->
                m.name in KEYSTORE_ALIAS_METHODS && m.parameterTypes.firstOrNull() == String::class.java
            }
            aliasMethods.forEach { method ->
                hook(method).intercept { chain ->
                    proceedWithReplacedAlias(chain, 0, isolatedAlias, requireAndroidKeyStore = true)
                }
            }
            bootHookCount.addAndGet(aliasMethods.size)
            logBoth(Log.INFO, "Hooked KeyStore (${aliasMethods.size} methods) -> $isolatedAlias")
        }.onFailure {
            recordError("Failed to hook java.security.KeyStore: ${it.message}", it)
        }

        runCatching {
            val keyGenBuilder = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
            val ctors = keyGenBuilder.declaredConstructors.filter { c ->
                c.parameterTypes.firstOrNull() == String::class.java
            }
            ctors.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    proceedWithReplacedAlias(chain, 0, isolatedAlias)
                }
            }
            bootHookCount.addAndGet(ctors.size)
            logBoth(Log.INFO, "Hooked KeyGenParameterSpec.Builder (${ctors.size} ctors)")
        }.onFailure {
            recordError("Failed to hook KeyGenParameterSpec.Builder: ${it.message}", it)
        }

        runCatching {
            @Suppress("DEPRECATION")
            val keyPairBuilder = Class.forName("android.security.KeyPairGeneratorSpec\$Builder")
            val setAlias = keyPairBuilder.getDeclaredMethod("setAlias", String::class.java)
            hook(setAlias).intercept { chain ->
                proceedWithReplacedAlias(chain, 0, isolatedAlias)
            }
            bootHookCount.incrementAndGet()
            logBoth(Log.INFO, "Hooked KeyPairGeneratorSpec.Builder.setAlias")
        }.onFailure {
            logBoth(Log.WARN, "KeyPairGeneratorSpec.Builder.setAlias unavailable", it)
        }
    }

    private fun hookGetAliasMethod(
        cls: Class<*>,
        isolatedAlias: String,
        label: String
    ): Int {
        val method = runCatching { cls.getDeclaredMethod("getAlias") }.getOrNull() ?: return 0
        if (method.returnType != String::class.java) return 0
        hook(method).intercept { chain ->
            val res = chain.proceed()
            if (res == ADAL_KEY_ALIAS) {
                onAliasRewritten(isolatedAlias)
                isolatedAlias
            } else {
                res
            }
        }
        msalHookLabels.add("$label.getAlias")
        return 1
    }

    private fun hookMicrosoftProviders(pkg: String, isolatedAlias: String, classLoader: ClassLoader) {
        synchronized(hookedClassLoaders) {
            if (!hookedClassLoaders.add(classLoader)) return
        }

        var count = 0

        runCatching {
            val factoryClass = classLoader.loadClass(CLS_PROVIDER_FACTORY)
            val createMethod = factoryClass.getDeclaredMethod(
                "create",
                String::class.java,
                String::class.java,
                Context::class.java
            )
            hook(createMethod).intercept { chain ->
                proceedWithReplacedAlias(chain, 0, isolatedAlias)
            }
            msalHookLabels.add("Factory.create")
            count++
        }

        runCatching {
            val providerClass = classLoader.loadClass(CLS_KEYSTORE_PROVIDER)
            providerClass.declaredConstructors.filter { c ->
                val types = c.parameterTypes
                types.size >= 2 && types[0] == Context::class.java && types[1] == String::class.java
            }.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    proceedWithReplacedAlias(chain, 1, isolatedAlias)
                }
                msalHookLabels.add("KeyStoreProvider(ctor)")
                count++
            }
            count += hookGetAliasMethod(providerClass, isolatedAlias, "KeyStoreProvider")
        }

        runCatching {
            val wrappedClass = classLoader.loadClass(CLS_WRAPPED_PROVIDER)
            wrappedClass.declaredConstructors.filter { c ->
                c.parameterTypes.firstOrNull() == String::class.java
            }.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    proceedWithReplacedAlias(chain, 0, isolatedAlias)
                }
                msalHookLabels.add("WrappedProvider(ctor)")
                count++
            }
            count += hookGetAliasMethod(wrappedClass, isolatedAlias, "WrappedProvider")
        }

        if (count > 0) {
            msalHookCount.addAndGet(count)
            logBoth(Log.INFO, "Hooked $count MSAL KeyProvider entry points for $pkg -> $isolatedAlias")
        }
    }

    private fun logBoth(priority: Int, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            log(priority, TAG, msg, tr)
            Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
        } else {
            log(priority, TAG, msg)
            Log.println(priority, TAG, msg)
        }
    }

    companion object {
        private const val TAG = "OfficeAdalKeyIsolator"
        private const val ADAL_KEY_ALIAS = "AdalKey"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ACTION_PING = "io.github.marukon.adalkeyisolator.ACTION_PING"

        private const val CLS_PROVIDER_FACTORY =
            "com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProviderFactory"
        private const val CLS_KEYSTORE_PROVIDER =
            "com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProvider"
        private const val CLS_WRAPPED_PROVIDER =
            "com.microsoft.identity.common.crypto.AndroidWrappedKeyProvider"

        private val KEYSTORE_ALIAS_METHODS = setOf(
            "containsAlias",
            "deleteEntry",
            "getEntry",
            "setEntry",
            "getKey",
            "setKeyEntry",
            "getCertificate",
            "getCertificateChain",
            "setCertificateEntry",
            "isKeyEntry",
            "isCertificateEntry",
            "getCreationDate",
        )
    }
}
