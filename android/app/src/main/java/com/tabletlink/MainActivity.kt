package com.tabletlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val REQ_PERM = 10
    private val REQ_PROJECTION = 11

    private lateinit var serverField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val prefs = getSharedPreferences("tabletlink", MODE_PRIVATE)
        serverField.setText(prefs.getString("server", ""))
        tokenField.setText(prefs.getString("token", ""))
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** tabletlink://pair?server=...&token=...  (opened from the web page after scanning the QR) */
    private fun handleLink(i: Intent?) {
        val uri: Uri = i?.data ?: return
        if (uri.scheme != "tabletlink") return
        val server = uri.getQueryParameter("server")
        val token = uri.getQueryParameter("token")
        if (server.isNullOrBlank() || token.isNullOrBlank()) return
        serverField.setText(server)
        tokenField.setText(token)
        startFlow()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor("#0F1420"))
        }
        fun label(t: String, size: Float = 15f, color: String = "#E8ECF5") = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, pad / 2, 0, pad / 4)
            gravity = Gravity.START
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutDirection = View.LAYOUT_DIRECTION_LTR; setSingleLine(true)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        root.addView(label("📡 Tablet Link", 22f))
        root.addView(label("این اپ تصویر صفحه و موقعیت همین تبلت را به پنل شخصی خودت می‌فرستد و فرمان‌های تو را اجرا می‌کند.", 14f, "#8A94AD"))
        serverField = field("آدرس سرور (مثلاً https://myapp.up.railway.app)")
        tokenField = field("توکن جفت‌سازی")
        root.addView(label("سرور"))
        root.addView(serverField)
        root.addView(label("توکن"))
        root.addView(tokenField)
        root.addView(button("▶ اتصال و شروع اشتراک صفحه") { startFlow() })
        root.addView(button("⚙ فعال‌سازی کنترل (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("⛔ قطع اتصال") {
            stopService(Intent(this, ScreenService::class.java))
            Toast.makeText(this, "قطع شد", Toast.LENGTH_SHORT).show()
            statusView.postDelayed({ refreshStatus() }, 400)
        })
        statusView = label("", 14f, "#E8ECF5")
        root.addView(statusView)
        root.addView(
            label(
                "اگر گزینه‌ی Accessibility خاکستری بود: تنظیمات ← برنامه‌ها ← Tablet Link ← منوی ⋮ ← «Allow restricted settings»، " +
                    "سپس دوباره فعالش کن.",
                12f, "#8A94AD"
            )
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refreshStatus() {
        val ctl = if (ControlService.instance != null) "✅ کنترل (Accessibility) فعال" else "❌ کنترل غیرفعال"
        val svc = if (ScreenService.running) "✅ متصل / در حال اجرا" else "❌ متصل نیست"
        statusView.text = "$svc\n$ctl"
    }

    /* permissions -> screen capture consent -> start service */

    private fun startFlow() {
        var server = serverField.text.toString().trim().trimEnd('/')
        val token = tokenField.text.toString().trim()
        if (server.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "سرور و توکن را وارد کن", Toast.LENGTH_SHORT).show()
            return
        }
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "https://$server"
        serverField.setText(server)
        getSharedPreferences("tabletlink", MODE_PRIVATE).edit()
            .putString("server", server).putString("token", token).apply()

        if (ScreenService.running) {
            Toast.makeText(this, "از قبل متصل است. برای اتصال جدید ابتدا «قطع اتصال» را بزن.", Toast.LENGTH_LONG).show()
            return
        }

        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS

        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERM) else requestProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) requestProjection()
    }

    @Suppress("DEPRECATION")
    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "اجازه‌ی ضبط صفحه داده نشد", Toast.LENGTH_LONG).show()
            return
        }
        val svc = Intent(this, ScreenService::class.java)
            .putExtra(ScreenService.EXTRA_CODE, resultCode)
            .putExtra(ScreenService.EXTRA_DATA, data)
        startForegroundService(svc)
        statusView.postDelayed({ refreshStatus() }, 800)
        Toast.makeText(this, "متصل شد. به پنل برگرد و «نمایش صفحه» را بزن.", Toast.LENGTH_LONG).show()
    }
}
