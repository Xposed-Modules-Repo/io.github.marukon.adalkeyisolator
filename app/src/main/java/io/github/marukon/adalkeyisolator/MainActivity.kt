package io.github.marukon.adalkeyisolator

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvVersionBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHookSummary: TextView
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnRequestScope: MaterialButton
    private lateinit var llInstalledList: LinearLayout
    private lateinit var tvNoInstalled: TextView
    private lateinit var cardUninstalled: MaterialCardView
    private lateinit var tvUninstalledHeader: TextView
    private lateinit var tvUninstalledChevron: TextView
    private lateinit var tvUninstalledBody: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var xposedService: XposedService? = null

    private var compatCache: Map<String, CompatCheckResult> = emptyMap()
    private val expandedPackages = mutableSetOf<String>()
    private var uninstalledExpanded = false

    data class CompatCheckResult(
        val installed: Boolean,
        val uid: Int = -1,
        val versionName: String = "",
        val matchedItems: Int = 0,
        val totalItems: Int = 4,
        val variantLabel: String = "",
        val missingDetails: List<String> = emptyList()
    )

    private enum class HealthState {
        FULL_SUCCESS,
        READY,
        WARN,
        ERROR,
        DISCONNECTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVersionBadge = findViewById(R.id.tvVersionBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvHookSummary = findViewById(R.id.tvHookSummary)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnRequestScope = findViewById(R.id.btnRequestScope)
        llInstalledList = findViewById(R.id.llInstalledList)
        tvNoInstalled = findViewById(R.id.tvNoInstalled)
        cardUninstalled = findViewById(R.id.cardUninstalled)
        tvUninstalledHeader = findViewById(R.id.tvUninstalledHeader)
        tvUninstalledChevron = findViewById(R.id.tvUninstalledChevron)
        tvUninstalledBody = findViewById(R.id.tvUninstalledBody)

        tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        tintPill(tvVersionBadge, R.color.status_idle_bg, R.color.status_idle_fg)

        btnRefresh.setOnClickListener { refreshAll(pingRunning = true) }
        btnRequestScope.setOnClickListener { requestMissingScopes() }
        cardUninstalled.setOnClickListener {
            uninstalledExpanded = !uninstalledExpanded
            tvUninstalledBody.visibility = if (uninstalledExpanded) View.VISIBLE else View.GONE
            tvUninstalledChevron.text = if (uninstalledExpanded) "▴" else "▾"
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                runOnUiThread {
                    updateHeaderStatus(service)
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

    private fun installedTargetPackages(): List<Pair<String, String>> =
        TARGET_PACKAGES.filter { (pkg, _) -> compatCache[pkg]?.installed == true }

    private fun updateHeaderStatus(service: XposedService) {
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val installed = installedTargetPackages()
        val enabledInstalled = installed.count { (pkg, _) -> pkg in scope }

        if (compatCache.isEmpty()) {
            tvStatus.text = getString(R.string.status_checking)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnRequestScope.isEnabled = false
            return
        }

        if (installed.isEmpty()) {
            tvStatus.text = getString(R.string.status_connected_none, service.apiVersion)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ready_fg))
            btnRequestScope.text = getString(R.string.btn_request_scope)
            btnRequestScope.isEnabled = false
            return
        }

        tvStatus.text = getString(
            R.string.status_connected_installed,
            service.apiVersion,
            enabledInstalled,
            installed.size
        )
        val hasMissing = enabledInstalled < installed.size
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (hasMissing) R.color.status_warn_fg else R.color.status_ok_fg
            )
        )
        btnRequestScope.text = getString(
            if (hasMissing) R.string.btn_request_scope else R.string.btn_scope_all_done
        )
        btnRequestScope.isEnabled = hasMissing
    }

    private fun showDisconnected() {
        tvStatus.text = getString(R.string.status_disconnected)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn_fg))
        btnRequestScope.text = getString(R.string.btn_request_scope)
        btnRequestScope.isEnabled = false
    }

    private fun requestMissingScopes() {
        val service = xposedService ?: return
        val scope = runCatching { service.scope }.getOrDefault(emptyList())
        val missing = installedTargetPackages()
            .map { it.first }
            .filter { pkg -> pkg !in scope }
        if (missing.isEmpty()) return

        service.requestScope(missing, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) {
                runOnUiThread {
                    updateHeaderStatus(service)
                    renderCards()
                }
            }

            override fun onScopeRequestFailed(message: String) {
                runOnUiThread { renderCards() }
            }
        })
    }

    private fun refreshAll(pingRunning: Boolean) {
        executor.execute {
            val results = LinkedHashMap<String, CompatCheckResult>()
            for ((pkg, _) in TARGET_PACKAGES) {
                results[pkg] = inspectTargetCompatibility(pkg)
            }
            compatCache = results
            mainHandler.post {
                val service = xposedService
                if (service != null) updateHeaderStatus(service) else showDisconnected()
                if (pingRunning) {
                    pingInstalledTargets(results)
                }
                renderCards()
            }
        }
    }

    private fun pingInstalledTargets(results: Map<String, CompatCheckResult>) {
        for ((pkg, compat) in results) {
            if (!compat.installed) continue
            runCatching {
                val intent = Intent(MainHook.ACTION_PING).setPackage(pkg)
                sendOrderedBroadcast(
                    intent,
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (resultCode == RESULT_OK) {
                                val extras = getResultExtras(false)
                                if (extras != null) {
                                    StatusProvider.saveReport(this@MainActivity, pkg, extras)
                                    renderCards()
                                }
                            }
                        }
                    },
                    mainHandler,
                    0,
                    null,
                    null
                )
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

        val targetCtx = runCatching {
            createPackageContext(
                pkg,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull()

        val cl = targetCtx?.classLoader ?: return CompatCheckResult(
            installed = true,
            uid = appInfo.uid,
            versionName = versionName,
            matchedItems = 0,
            totalItems = 4,
            missingDetails = listOf("无法加载目标 ClassLoader (createPackageContext 返回空)")
        )

        val missing = mutableListOf<String>()
        var matched = 0
        var usedR8Fallback = false

        val hasFactory = runCatching {
            val cls = cl.loadClass("com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProviderFactory")
            cls.getDeclaredMethod("create", String::class.java, String::class.java, Context::class.java)
            true
        }.getOrDefault(false)
        val hasEncManager = runCatching {
            cl.loadClass("com.microsoft.identity.common.crypto.AndroidAuthSdkStorageEncryptionManager")
            true
        }.getOrDefault(false)
        if (hasFactory || hasEncManager) {
            matched++
            if (!hasFactory) usedR8Fallback = true
        } else {
            missing.add("KeyStoreBackedSecretKeyProviderFactory.create / AndroidAuthSdkStorageEncryptionManager")
        }

        val providerCheck = inspectProviderClass(
            cl = cl,
            className = "com.microsoft.identity.common.crypto.KeyStoreBackedSecretKeyProvider",
            ctorMatcher = { types -> types.size >= 2 && types[0] == Context::class.java && types[1] == String::class.java }
        )
        when (providerCheck) {
            ProviderMatch.CTOR -> matched++
            ProviderMatch.GET_ALIAS -> {
                matched++
                usedR8Fallback = true
            }
            ProviderMatch.MISSING -> missing.add("KeyStoreBackedSecretKeyProvider (ctor / getAlias)")
        }

        val wrappedCheck = inspectProviderClass(
            cl = cl,
            className = "com.microsoft.identity.common.crypto.AndroidWrappedKeyProvider",
            ctorMatcher = { types -> types.firstOrNull() == String::class.java }
        )
        when (wrappedCheck) {
            ProviderMatch.CTOR -> matched++
            ProviderMatch.GET_ALIAS -> {
                matched++
                usedR8Fallback = true
            }
            ProviderMatch.MISSING -> missing.add("AndroidWrappedKeyProvider (ctor / getAlias)")
        }

        val storageHelperOk = runCatching {
            cl.loadClass("com.microsoft.aad.adal.StorageHelper")
            true
        }.getOrDefault(false)
        if (storageHelperOk) matched++ else missing.add("StorageHelper")

        val variantLabel = when {
            matched < 4 -> "兼容检测不完整 ($matched/4)"
            usedR8Fallback -> "R8 精简签名 (getAlias + KeyStore)"
            else -> "标准 MSAL 签名 (Factory + Ctor + getAlias)"
        }

        return CompatCheckResult(
            installed = true,
            uid = appInfo.uid,
            versionName = versionName,
            matchedItems = matched,
            totalItems = 4,
            variantLabel = variantLabel,
            missingDetails = missing
        )
    }

    private enum class ProviderMatch { CTOR, GET_ALIAS, MISSING }

    private fun inspectProviderClass(
        cl: ClassLoader,
        className: String,
        ctorMatcher: (Array<Class<*>>) -> Boolean
    ): ProviderMatch {
        val cls = runCatching { cl.loadClass(className) }.getOrNull() ?: return ProviderMatch.MISSING
        if (cls.declaredConstructors.any { ctorMatcher(it.parameterTypes) }) {
            return ProviderMatch.CTOR
        }
        val hasGetAlias = runCatching {
            val m = cls.getDeclaredMethod("getAlias")
            m.returnType == String::class.java
        }.getOrDefault(false)
        return if (hasGetAlias) ProviderMatch.GET_ALIAS else ProviderMatch.MISSING
    }

    private fun renderCards() {
        llInstalledList.removeAllViews()
        val service = xposedService
        val scope = runCatching { service?.scope }.getOrNull().orEmpty()
        val runningTargets: List<HookedTarget> = runCatching {
            service?.runningTargets
        }.getOrNull().orEmpty()
        val statusPrefs = getSharedPreferences(StatusProvider.PREF_HOOK_STATUS, Context.MODE_PRIVATE)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val inflater = LayoutInflater.from(this)

        val installedList = mutableListOf<Pair<String, String>>()
        val uninstalledList = mutableListOf<Pair<String, String>>()
        for (entry in TARGET_PACKAGES) {
            if (compatCache[entry.first]?.installed == true) {
                installedList.add(entry)
            } else {
                uninstalledList.add(entry)
            }
        }

        var fullSuccessCount = 0
        var totalRewrites = 0

        for ((pkg, label) in installedList) {
            val compat = compatCache[pkg] ?: continue
            val inScope = service != null && pkg in scope
            val procTargets = runningTargets.filter {
                it.processName == pkg || it.processName.startsWith("$pkg:")
            }

            val updatedAt = statusPrefs.getLong("${pkg}_updated_at", 0L)
            val hasReceipt = updatedAt > 0L
            val bootHooks = statusPrefs.getInt("${pkg}_boot_hooks", 0)
            val msalHooks = statusPrefs.getInt("${pkg}_msal_hooks", 0)
            val rewrites = statusPrefs.getInt("${pkg}_rewrite_count", 0)
            val reportedVer = statusPrefs.getString("${pkg}_version", "").orEmpty()
            val hookDetails = statusPrefs.getString("${pkg}_hook_details", "").orEmpty()
            val err = statusPrefs.getString("${pkg}_error", "").orEmpty()
            val verMismatch = hasReceipt && reportedVer.isNotEmpty() && compat.versionName.isNotEmpty() && reportedVer != compat.versionName
            if (hasReceipt && !verMismatch) totalRewrites += rewrites

            val receiptOk = hasReceipt && err.isEmpty() && !verMismatch && bootHooks > 0 && msalHooks > 0
            val compatOk = compat.matchedItems == compat.totalItems

            val (state, badgeText, summarySub) = when {
                service == null -> Triple(
                    HealthState.DISCONNECTED,
                    "未连接 LSP",
                    "v${compat.versionName.ifEmpty { "?" }} · 请在 LSPosed 中启用模块"
                )
                err.isNotEmpty() -> Triple(
                    HealthState.ERROR,
                    "✗ Hook 报错",
                    "v${compat.versionName} · 点击展开并复制错误信息"
                )
                !inScope -> Triple(
                    HealthState.WARN,
                    "✗ 未勾选作用域",
                    "v${compat.versionName} · 点击顶部「勾选缺失作用域」"
                )
                !compatOk -> Triple(
                    HealthState.ERROR,
                    "⚠ 兼容 ${compat.matchedItems}/${compat.totalItems}",
                    "v${compat.versionName} · 部分签名缺失，点击复制诊断"
                )
                verMismatch -> Triple(
                    HealthState.WARN,
                    "⚠ 需重启刷新",
                    "v${compat.versionName} · 回执属于旧版 $reportedVer"
                )
                procTargets.isNotEmpty() && receiptOk -> {
                    fullSuccessCount++
                    val pidStr = procTargets.joinToString(",") { it.pid.toString() }
                    Triple(
                        HealthState.FULL_SUCCESS,
                        "✓ Hook 成功",
                        "v${compat.versionName} · PID $pidStr · 已改写 $rewrites 次"
                    )
                }
                procTargets.isNotEmpty() && hasReceipt && bootHooks > 0 && msalHooks == 0 -> Triple(
                    HealthState.WARN,
                    "● 仅底层生效",
                    "v${compat.versionName} · KeyStore $bootHooks 处 (MSAL 0 处)"
                )
                procTargets.isNotEmpty() -> Triple(
                    HealthState.READY,
                    "○ 待回执",
                    "v${compat.versionName} · 已注入 PID ${procTargets.first().pid}，点刷新探测"
                )
                receiptOk -> {
                    fullSuccessCount++
                    Triple(
                        HealthState.READY,
                        "✓ 已验证就绪",
                        "v${compat.versionName} · 未运行 (上次改写 $rewrites 次)"
                    )
                }
                else -> Triple(
                    HealthState.READY,
                    "○ 已勾选就绪",
                    "v${compat.versionName} · 打开一次 $label 即可验证"
                )
            }

            val card = inflater.inflate(R.layout.item_target_card, llInstalledList, false) as MaterialCardView
            val vDot = card.findViewById<View>(R.id.vDot)
            val tvAppName = card.findViewById<TextView>(R.id.tvAppName)
            val tvBadge = card.findViewById<TextView>(R.id.tvBadge)
            val tvChevron = card.findViewById<TextView>(R.id.tvChevron)
            val tvSummarySub = card.findViewById<TextView>(R.id.tvSummarySub)
            val llDetails = card.findViewById<LinearLayout>(R.id.llDetails)
            val tvDetailMeta = card.findViewById<TextView>(R.id.tvDetailMeta)
            val llErrorBox = card.findViewById<LinearLayout>(R.id.llErrorBox)
            val tvErrorTitle = card.findViewById<TextView>(R.id.tvErrorTitle)
            val tvErrorCopyHint = card.findViewById<TextView>(R.id.tvErrorCopyHint)
            val tvErrorText = card.findViewById<TextView>(R.id.tvErrorText)
            val tvCopyReport = card.findViewById<TextView>(R.id.tvCopyReport)

            tvAppName.text = label
            tvBadge.text = badgeText
            tvSummarySub.text = summarySub

            val (bgRes, fgRes) = when (state) {
                HealthState.FULL_SUCCESS -> R.color.status_ok_bg to R.color.status_ok_fg
                HealthState.READY -> R.color.status_ready_bg to R.color.status_ready_fg
                HealthState.WARN -> R.color.status_warn_bg to R.color.status_warn_fg
                HealthState.ERROR -> R.color.status_err_bg to R.color.status_err_fg
                HealthState.DISCONNECTED -> R.color.status_idle_bg to R.color.status_idle_fg
            }
            tintPill(tvBadge, bgRes, fgRes)
            vDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, fgRes))

            val scopeText = if (service == null) "未知 (LSPosed 未连接)" else if (inScope) "✓ 已勾选" else "✗ 未勾选"
            val procText = when {
                service == null -> "未知"
                procTargets.isNotEmpty() -> "✓ 已注入运行中 [" + procTargets.joinToString(", ") { "${it.pid} (${it.state.name})" } + "]"
                else -> "○ 当前未运行（冷启动按作用域注入）"
            }
            val compatText = if (compatOk) {
                "✓ 4/4 类签名完整兼容 · ${compat.variantLabel}"
            } else {
                "⚠ ${compat.matchedItems}/${compat.totalItems} 部分缺失"
            }
            val receiptText = if (hasReceipt) {
                val t = timeFmt.format(Date(updatedAt))
                val detailSuffix = if (hookDetails.isNotEmpty()) "\n挂载详情: $hookDetails" else ""
                when {
                    err.isNotEmpty() -> "✗ Hook 报错 ($t)$detailSuffix"
                    verMismatch -> "⚠ 上次回执属于旧版本 $reportedVer ($t)，请重启应用"
                    else -> "✓ 实测成功 ($t): KeyStore $bootHooks 处 + MSAL $msalHooks 处 | 改写 $rewrites 次$detailSuffix"
                }
            } else {
                "○ 尚无进程回执（启用作用域后打开一次 $label 即可生成）"
            }

            val detailText = buildString {
                append("包名: $pkg (UID ${compat.uid})\n")
                append("版本: ${compat.versionName.ifEmpty { "unknown" }}\n")
                append("隔离别名: AdalKey_$pkg\n")
                append("作用域: $scopeText\n")
                append("进程状态: $procText\n")
                append("代码兼容: $compatText\n")
                append("进程回执: $receiptText")
            }
            tvDetailMeta.text = detailText

            val errorReasons = buildList {
                if (err.isNotEmpty()) add("Hook 报错: $err")
                if (!compatOk) add("缺失签名 (${compat.matchedItems}/${compat.totalItems}): ${compat.missingDetails.joinToString(", ")}")
                if (verMismatch) add("版本不一致: 回执为 $reportedVer，当前已安装 ${compat.versionName}")
            }

            if (errorReasons.isNotEmpty()) {
                val errBody = errorReasons.joinToString("\n")
                val fullErrorCopy = "$label ($pkg v${compat.versionName})\n$errBody"
                llErrorBox.visibility = View.VISIBLE
                val errBg = if (err.isNotEmpty() || !compatOk) R.color.status_err_bg else R.color.status_warn_bg
                val errFg = if (err.isNotEmpty() || !compatOk) R.color.status_err_fg else R.color.status_warn_fg
                llErrorBox.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, errBg))
                tvErrorTitle.setTextColor(ContextCompat.getColor(this, errFg))
                tvErrorCopyHint.setTextColor(ContextCompat.getColor(this, errFg))
                tvErrorText.setTextColor(ContextCompat.getColor(this, errFg))
                tvErrorText.text = errBody
                llErrorBox.setOnClickListener {
                    copyToClipboard("OfficeAdalKeyIsolator Error", fullErrorCopy, R.string.toast_error_copied)
                }
            } else {
                llErrorBox.visibility = View.GONE
            }

            val fullReportCopy = buildString {
                append("=== $label ===\n")
                append(detailText)
                if (errorReasons.isNotEmpty()) {
                    append("\n异常详情:\n")
                    append(errorReasons.joinToString("\n"))
                }
            }
            tvCopyReport.setOnClickListener {
                copyToClipboard("OfficeAdalKeyIsolator Report", fullReportCopy, R.string.toast_report_copied)
            }

            val applyExpanded = { expanded: Boolean ->
                llDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                tvChevron.text = if (expanded) "▴" else "▾"
            }
            applyExpanded(pkg in expandedPackages)

            card.setOnClickListener {
                val nowExpanded = if (pkg in expandedPackages) {
                    expandedPackages.remove(pkg)
                    false
                } else {
                    expandedPackages.add(pkg)
                    true
                }
                applyExpanded(nowExpanded)
            }

            llInstalledList.addView(card)
        }

        if (installedList.isEmpty()) {
            tvNoInstalled.visibility = View.VISIBLE
            tvHookSummary.text = "当前设备未检测到 Office 目标应用"
        } else {
            tvNoInstalled.visibility = View.GONE
            tvHookSummary.text = "已安装 ${installedList.size} 个应用 · 已通过验证 $fullSuccessCount / ${installedList.size} · 累计改写 $totalRewrites 次"
        }

        if (uninstalledList.isNotEmpty()) {
            cardUninstalled.visibility = View.VISIBLE
            tvUninstalledHeader.text = "未安装的 Office 目标 (${uninstalledList.size})"
            tvUninstalledBody.text = uninstalledList.joinToString("\n") { (pkg, label) -> "• $label ($pkg)" }
            tvUninstalledBody.visibility = if (uninstalledExpanded) View.VISIBLE else View.GONE
            tvUninstalledChevron.text = if (uninstalledExpanded) "▴" else "▾"
        } else {
            cardUninstalled.visibility = View.GONE
        }
    }

    private fun tintPill(view: TextView, @ColorRes bgRes: Int, @ColorRes fgRes: Int) {
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgRes))
        view.setTextColor(ContextCompat.getColor(this, fgRes))
    }

    private fun copyToClipboard(label: String, text: String, toastResId: Int) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastResId, Toast.LENGTH_SHORT).show()
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
