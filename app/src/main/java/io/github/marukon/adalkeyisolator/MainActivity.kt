package io.github.marukon.adalkeyisolator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var llTargetList: LinearLayout
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnRequestScope: MaterialButton

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var xposedService: XposedService? = null

    private var compatCache: Map<String, CompatCheckResult> = emptyMap()

    data class CompatCheckResult(
        val installed: Boolean,
        val uid: Int = -1,
        val versionName: String = "",
        val matchedItems: Int = 0,
        val totalItems: Int = 4,
        val missingDetails: List<String> = emptyList()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        llTargetList = findViewById(R.id.llTargetList)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnRequestScope = findViewById(R.id.btnRequestScope)

        btnRefresh.setOnClickListener { refreshAll(pingRunning = true) }
        btnRequestScope.setOnClickListener { requestMissingScopes() }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                runOnUiThread {
                    updateLspStatus(service)
                    refreshAll(pingRunning = true)
                }
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                runOnUiThread {
                    showDisconnected()
                    renderCards()
                }
            }
        })

        refreshAll(pingRunning = true)
    }

    override fun onResume() {
        super.onResume()
        refreshAll(pingRunning = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun updateLspStatus(service: XposedService) {
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val enabledTargets = TARGET_PACKAGES.count { (pkg, _) -> pkg in scope }
        tvStatus.text = getString(
            R.string.status_connected,
            service.apiVersion,
            enabledTargets,
            TARGET_PACKAGES.size
        )
        tvStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        )
        val hasMissing = TARGET_PACKAGES.any { (pkg, _) ->
            compatCache[pkg]?.installed == true && pkg !in scope
        }
        btnRequestScope.isEnabled = hasMissing
    }

    private fun showDisconnected() {
        tvStatus.text = getString(R.string.status_disconnected)
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        btnRequestScope.isEnabled = false
    }

    private fun requestMissingScopes() {
        val service = xposedService ?: return
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val missing = TARGET_PACKAGES
            .map { it.first }
            .filter { pkg -> compatCache[pkg]?.installed == true && pkg !in scope }
        if (missing.isEmpty()) return

        service.requestScope(missing, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) {
                runOnUiThread {
                    updateLspStatus(service)
                    renderCards()
                }
            }

            override fun onScopeRequestFailed(message: String) {
                runOnUiThread { renderCards() }
            }
        })
    }

    private fun refreshAll(pingRunning: Boolean) {
        if (pingRunning) {
            for ((pkg, _) in TARGET_PACKAGES) {
                runCatching {
                    val intent = Intent(MainHook.ACTION_PING).setPackage(pkg)
                    sendBroadcast(intent)
                }
            }
        }

        executor.execute {
            val results = LinkedHashMap<String, CompatCheckResult>()
            for ((pkg, _) in TARGET_PACKAGES) {
                results[pkg] = inspectTargetCompatibility(pkg)
            }
            compatCache = results
            mainHandler.post {
                xposedService?.let { updateLspStatus(it) }
                renderCards()
            }
            if (pingRunning) {
                mainHandler.postDelayed({
                    if (!isDestroyed) renderCards()
                }, 400L)
            }
        }
    }

    private fun inspectTargetCompatibility(pkg: String): CompatCheckResult {
        val pm = packageManager
        val appInfo = try {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
        } catch (_: Throwable) {
            null
        } ?: return CompatCheckResult(installed = false)

        val pkgInfo = try {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
        } catch (_: Throwable) {
            null
        }
        val versionName = pkgInfo?.versionName.orEmpty()

        val missing = mutableListOf<String>()
        var matched = 0

        val targetCtx = runCatching {
            createPackageContext(
                pkg,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull()

        val cl = targetCtx?.classLoader
        if (cl == null) {
            return CompatCheckResult(
                installed = true,
                uid = appInfo.uid,
                versionName = versionName,
                matchedItems = 0,
                totalItems = 4,
                missingDetails = listOf("无法加载目标 ClassLoader")
            )
        }

        val factoryOk = runCatching {
            val cls = cl.loadClass("com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProviderFactory")
            cls.getDeclaredMethod("create", String::class.java, String::class.java, Context::class.java)
            true
        }.getOrDefault(false)
        if (factoryOk) matched++ else missing.add("KeyStoreBackedSecretKeyProviderFactory.create")

        val providerOk = runCatching {
            val cls = cl.loadClass("com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProvider")
            cls.declaredConstructors.any { c ->
                val p = c.parameterTypes
                p.size >= 2 && p[0] == Context::class.java && p[1] == String::class.java
            }
        }.getOrDefault(false)
        if (providerOk) matched++ else missing.add("KeyStoreBackedSecretKeyProvider")

        val wrappedOk = runCatching {
            val cls = cl.loadClass("com.microsoft.identity.common.crypto.AndroidWrappedKeyProvider")
            cls.declaredConstructors.any { c -> c.parameterTypes.firstOrNull() == String::class.java }
        }.getOrDefault(false)
        if (wrappedOk) matched++ else missing.add("AndroidWrappedKeyProvider")

        val storageHelperOk = runCatching {
            cl.loadClass("com.microsoft.aad.adal.StorageHelper")
            true
        }.getOrDefault(false)
        if (storageHelperOk) matched++ else missing.add("StorageHelper")

        return CompatCheckResult(
            installed = true,
            uid = appInfo.uid,
            versionName = versionName,
            matchedItems = matched,
            totalItems = 4,
            missingDetails = missing
        )
    }

    private fun renderCards() {
        llTargetList.removeAllViews()
        val service = xposedService
        val scope = runCatching { service?.scope }.getOrNull().orEmpty()
        val runningTargets: List<HookedTarget> = runCatching {
            service?.runningTargets
        }.getOrNull().orEmpty()
        val statusPrefs = getSharedPreferences(StatusProvider.PREF_HOOK_STATUS, Context.MODE_PRIVATE)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val padV = (8 * resources.displayMetrics.density).toInt()

        for ((pkg, label) in TARGET_PACKAGES) {
            val compat = compatCache[pkg]
            val tv = TextView(this).apply {
                textSize = 13.5f
                setPadding(0, padV, 0, padV)
            }

            if (compat == null || !compat.installed) {
                tv.text = getString(R.string.item_target_missing, label, pkg)
                llTargetList.addView(tv)
                continue
            }

            val inScope = if (service == null) "未知 (LSPosed 未连接)" else if (pkg in scope) "✓ 已勾选" else "✗ 未勾选"
            val procTargets = runningTargets.filter {
                it.processName == pkg || it.processName.startsWith("$pkg:")
            }
            val runningLine = if (service == null) {
                "未知"
            } else if (procTargets.isNotEmpty()) {
                val pids = procTargets.joinToString(", ") { "${it.pid}(${it.state.name})" }
                "✓ 已注入运行中 [PID: $pids]"
            } else {
                "○ 当前未运行（冷启动时按作用域注入）"
            }

            val compatLine = if (compat.matchedItems == compat.totalItems) {
                "✓ 4/4 类签名完整兼容"
            } else {
                "⚠ ${compat.matchedItems}/${compat.totalItems} 部分缺失: ${compat.missingDetails.joinToString(", ")}"
            }

            val updatedAt = statusPrefs.getLong("${pkg}_updated_at", 0L)
            val receiptLine = if (updatedAt > 0L) {
                val bootHooks = statusPrefs.getInt("${pkg}_boot_hooks", 0)
                val msalHooks = statusPrefs.getInt("${pkg}_msal_hooks", 0)
                val rewrites = statusPrefs.getInt("${pkg}_rewrite_count", 0)
                val err = statusPrefs.getString("${pkg}_error", "").orEmpty()
                val reportedVer = statusPrefs.getString("${pkg}_version", "").orEmpty()
                val timeStr = timeFmt.format(Date(updatedAt))
                val verMismatch = reportedVer.isNotEmpty() && compat.versionName.isNotEmpty() && reportedVer != compat.versionName
                if (err.isNotEmpty()) {
                    "✗ Hook 报错 ($timeStr): $err"
                } else if (verMismatch) {
                    "⚠ 上次回执属于旧版本 $reportedVer（当前 ${compat.versionName}），请重启该应用刷新"
                } else {
                    "✓ 实测成功 ($timeStr): KeyStore $bootHooks 处 + MSAL $msalHooks 处 | 已拦截改写 $rewrites 次"
                }
            } else {
                "○ 尚无进程回执（启用作用域后打开一次 $label 即可生成）"
            }

            tv.text = buildString {
                append("• $label ($pkg)\n")
                append("  版本: ${compat.versionName.ifEmpty { "unknown" }} | UID: ${compat.uid}\n")
                append("  隔离别名: AdalKey_$pkg\n")
                append("  作用域: $inScope\n")
                append("  LSPosed 状态: $runningLine\n")
                append("  代码兼容检查: $compatLine\n")
                append("  进程内 Hook 回执: $receiptLine")
            }
            llTargetList.addView(tv)
        }
    }

    companion object {
        private val TARGET_PACKAGES = listOf(
            "com.microsoft.office.excel" to "Microsoft Excel",
            "com.microsoft.office.word" to "Microsoft Word",
            "com.microsoft.office.powerpoint" to "Microsoft PowerPoint",
            "com.microsoft.office.onenote" to "Microsoft OneNote",
            "com.microsoft.office.officehubrow" to "Microsoft 365 (Copilot)",
        )
    }
}
