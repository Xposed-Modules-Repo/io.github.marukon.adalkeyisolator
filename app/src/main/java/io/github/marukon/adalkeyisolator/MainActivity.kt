package io.github.marukon.adalkeyisolator

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var llTargetList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        llTargetList = findViewById(R.id.llTargetList)

        renderTargetPackages()

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runOnUiThread {
                    val scopeCount = runCatching { service.scope.size }.getOrDefault(0)
                    tvStatus.text = getString(
                        R.string.status_connected,
                        service.apiVersion,
                        scopeCount
                    )
                    tvStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
                    )
                }
            }

            override fun onServiceDied(service: XposedService) {
                runOnUiThread { showDisconnected() }
            }
        })
    }

    private fun showDisconnected() {
        tvStatus.text = getString(R.string.status_disconnected)
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
    }

    private fun renderTargetPackages() {
        llTargetList.removeAllViews()
        val pm = packageManager
        val padV = (10 * resources.displayMetrics.density).toInt()
        for ((pkg, label) in TARGET_PACKAGES) {
            val info = runCatching {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            }.getOrNull()
            val tv = TextView(this).apply {
                textSize = 14f
                setPadding(0, padV / 2, 0, padV / 2)
                text = if (info != null) {
                    getString(R.string.item_target_installed, label, pkg, info.uid, "AdalKey_$pkg")
                } else {
                    getString(R.string.item_target_missing, label, pkg)
                }
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
