package com.tabletlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service:
 *  - keeps a WebSocket to the Railway server
 *  - captures the screen (MediaProjection) and sends JPEG frames while the dashboard is watching
 *  - sends GPS location
 *  - executes commands (tap/swipe/keys via ControlService, notify, beep, locate)
 */
class ScreenService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "tabletlink"

        @Volatile
        var running = false
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var stopped = false

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var streaming = false
    private var lastFrame = 0L
    private var lastRebuild = 0L
    private var capLandscape = false

    private var locationManager: LocationManager? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            sendLocation(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, 1, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { shutdown() }
                }
            }, main)
        } catch (e: Exception) {
            shutdown()
            return START_NOT_STICKY
        }

        thread = HandlerThread("tabletlink-capture").also { it.start() }
        bg = Handler(thread!!.looper)
        running = true
        stopped = false
        bg?.post { setupCapture() }
        connectWs()
        startLocation()
        return START_NOT_STICKY
    }

    /* ----------------------------- capture ----------------------------- */

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun setupCapture() {
        val proj = projection ?: return
        val dm = realMetrics()
        val longSide = max(dm.widthPixels, dm.heightPixels)
        val scale = min(1f, 960f / longSide)
        val w = ((dm.widthPixels * scale).toInt() / 2) * 2
        val h = ((dm.heightPixels * scale).toInt() / 2) * 2
        capLandscape = dm.widthPixels > dm.heightPixels

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ rd -> onImage(rd) }, bg)

        val vd = vdisplay
        if (vd == null) {
            vdisplay = proj.createVirtualDisplay(
                "TabletLink", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, bg
            )
        } else {
            // Android 14+ does not allow creating a second virtual display on the same projection
            vd.resize(w, h, dm.densityDpi)
            vd.surface = newReader.surface
        }
        val old = reader
        reader = newReader
        try {
            old?.close()
        } catch (_: Exception) {
        }
        lastRebuild = SystemClock.elapsedRealtime()
    }

    private fun isLandscape(): Boolean {
        val dm = realMetrics()
        return dm.widthPixels > dm.heightPixels
    }

    private fun onImage(rd: ImageReader) {
        val img = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        var bytes: ByteArray? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (streaming && now - lastFrame >= 140) {
                if (isLandscape() != capLandscape && now - lastRebuild > 1000) {
                    bg?.post { setupCapture() }
                } else {
                    val plane = img.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowPadding = plane.rowStride - pixelStride * img.width
                    val full = Bitmap.createBitmap(
                        img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
                    )
                    full.copyPixelsFromBuffer(plane.buffer)
                    val bmp = if (rowPadding > 0) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
                    val out = ByteArrayOutputStream(64 * 1024)
                    bmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
                    if (bmp !== full) bmp.recycle()
                    full.recycle()
                    bytes = out.toByteArray()
                    lastFrame = now
                }
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
        bytes?.let { ws?.send(it.toByteString()) }
    }

    /* ----------------------------- websocket ----------------------------- */

    private fun wsUrl(): String? {
        val p = getSharedPreferences("tabletlink", MODE_PRIVATE)
        val server = p.getString("server", null) ?: return null
        val token = p.getString("token", null) ?: return null
        val base = when {
            server.startsWith("https://") -> "wss://" + server.removePrefix("https://")
            server.startsWith("http://") -> "ws://" + server.removePrefix("http://")
            else -> "wss://$server"
        }.trimEnd('/')
        return "$base/ws?role=device&token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }

    private fun connectWs() {
        if (stopped) return
        val url = wsUrl() ?: return
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendInfo(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { handle(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        streaming = false
        main.postDelayed({ connectWs() }, 3000)
    }

    private fun sendJson(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun sendInfo(sock: WebSocket? = ws) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val dm = realMetrics()
        val o = JSONObject()
            .put("type", "info")
            .put("src", "app")
            .put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("screen", JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels))
        sock?.send(o.toString())
    }

    private fun status(text: String) {
        sendJson(JSONObject().put("type", "status").put("text", text))
    }

    /* ----------------------------- commands ----------------------------- */

    private fun handle(text: String) {
        val m = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        when (m.optString("type")) {
            "startScreen" -> {
                lastFrame = 0
                streaming = true
                sendInfo()
            }
            "stopScreen" -> streaming = false
            "tap" -> withControl { c, w, h ->
                c.tap((m.getDouble("x") * w).toFloat(), (m.getDouble("y") * h).toFloat(), m.optBoolean("long"))
            }
            "swipe" -> withControl { c, w, h ->
                c.swipe(
                    (m.getDouble("x1") * w).toFloat(), (m.getDouble("y1") * h).toFloat(),
                    (m.getDouble("x2") * w).toFloat(), (m.getDouble("y2") * h).toFloat(),
                    m.optLong("ms", 300)
                )
            }
            "key" -> withControl { c, _, _ -> c.key(m.optString("key")) }
            "locate" -> locateNow()
            "notify" -> Toast.makeText(this, m.optString("text"), Toast.LENGTH_LONG).show()
            "beep" -> beep()
        }
    }

    private fun withControl(block: (ControlService, Int, Int) -> Unit) {
        val c = ControlService.instance
        if (c == null) {
            status("کنترل فعال نیست: Accessibility را روی تبلت فعال کن.")
            return
        }
        val dm = realMetrics()
        try {
            block(c, dm.widthPixels, dm.heightPixels)
        } catch (e: Exception) {
            status("خطا در اجرای فرمان")
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000)
            main.postDelayed({ tg.release() }, 3500)
        } catch (_: Exception) {
        }
    }

    /* ----------------------------- location ----------------------------- */

    private fun hasLocationPermission() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) {
            status("دسترسی لوکیشن به اپ داده نشده است.")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 5000L, 0f, locListener, Looper.getMainLooper())
                }
            }
        } catch (_: SecurityException) {
        }
    }

    private fun locateNow() {
        val lm = locationManager ?: return
        try {
            val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (best != null) sendLocation(best) else status("هنوز موقعیتی پیدا نشده؛ GPS را روشن کن.")
        } catch (_: SecurityException) {
        }
    }

    private fun sendLocation(l: Location) {
        sendJson(
            JSONObject().put("type", "location").put("src", "app")
                .put("lat", l.latitude).put("lng", l.longitude)
                .put("acc", l.accuracy.toDouble()).put("ts", System.currentTimeMillis())
        )
    }

    /* ----------------------------- lifecycle ----------------------------- */

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tablet Link", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Tablet Link فعال است")
            .setContentText("این تبلت به پنل شما متصل است. برای قطع اتصال اپ را باز کن.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun shutdown() {
        if (stopped && !running) return
        stopped = true
        running = false
        streaming = false
        main.removeCallbacksAndMessages(null)
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
        try {
            locationManager?.removeUpdates(locListener)
        } catch (_: Exception) {
        }
        try {
            vdisplay?.release()
        } catch (_: Exception) {
        }
        vdisplay = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        thread?.quitSafely()
        thread = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
