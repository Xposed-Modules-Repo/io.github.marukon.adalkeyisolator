package io.github.marukon.adalkeyisolator

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.security.KeyStore
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private val bootHooked = AtomicBoolean(false)
    private val firstRewriteLogged = AtomicBoolean(false)
    private val hookedClassLoaders = Collections.newSetFromMap(IdentityHashMap<ClassLoader, Boolean>())

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        val pkg = param.packageName
        if (pkg == "system" || pkg == "android") return

        val isolatedAlias = "${ADAL_KEY_ALIAS}_${pkg}"
        if (param.isFirstPackage && bootHooked.compareAndSet(false, true)) {
            hookAndroidKeyStore(isolatedAlias)
        }
        hookMicrosoftProviders(pkg, isolatedAlias, param.classLoader)
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
            if (firstRewriteLogged.compareAndSet(false, true)) {
                logBoth(Log.INFO, "Rewriting alias '$ADAL_KEY_ALIAS' -> '$isolatedAlias'")
            }
            val newArgs = chain.args.toTypedArray()
            newArgs[argIndex] = isolatedAlias
            return chain.proceed(newArgs)
        }
        return chain.proceed()
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
            logBoth(Log.INFO, "Hooked KeyStore (${aliasMethods.size} methods) -> $isolatedAlias")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to hook java.security.KeyStore", it)
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
            logBoth(Log.INFO, "Hooked KeyGenParameterSpec.Builder (${ctors.size} ctors)")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to hook KeyGenParameterSpec.Builder", it)
        }

        runCatching {
            @Suppress("DEPRECATION")
            val keyPairBuilder = Class.forName("android.security.KeyPairGeneratorSpec\$Builder")
            val setAlias = keyPairBuilder.getDeclaredMethod("setAlias", String::class.java)
            hook(setAlias).intercept { chain ->
                proceedWithReplacedAlias(chain, 0, isolatedAlias)
            }
            logBoth(Log.INFO, "Hooked KeyPairGeneratorSpec.Builder.setAlias")
        }.onFailure {
            logBoth(Log.WARN, "KeyPairGeneratorSpec.Builder.setAlias unavailable", it)
        }
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
                count++
            }
        }

        runCatching {
            val wrappedClass = classLoader.loadClass(CLS_WRAPPED_PROVIDER)
            wrappedClass.declaredConstructors.filter { c ->
                c.parameterTypes.firstOrNull() == String::class.java
            }.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    proceedWithReplacedAlias(chain, 0, isolatedAlias)
                }
                count++
            }
        }

        if (count > 0) {
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
        private const val TAG = "AdalKeyIsolator"
        private const val ADAL_KEY_ALIAS = "AdalKey"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"

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
