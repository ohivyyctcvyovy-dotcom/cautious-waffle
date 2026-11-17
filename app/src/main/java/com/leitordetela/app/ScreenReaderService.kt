package com.leitordetela.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class ScreenReaderService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val channel = "455_capture"
    private val notificationId = 455

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> { beginCapture(); return START_STICKY }
            ACTION_SHOW_OVERLAY -> { showFloatingButton(); return START_STICKY }
            ACTION_HIDE_OVERLAY -> { hideFloatingButton(); return START_STICKY }
            ACTION_STOP -> { stopProjection(); return START_NOT_STICKY }
        }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") (intent?.getParcelableExtra(EXTRA_DATA))
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(notificationId, buildNotification())
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    hideFloatingButton()
                    display?.release(); display = null
                    reader?.close(); reader = null
                    projection = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, handler)
            display = projection?.createVirtualDisplay(
                "455-screen-capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
            )
            handler.postDelayed({ showFloatingButton() }, 350)
        } catch (e: Exception) {
            stopProjection()
            stopSelf()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val capture = PendingIntent.getService(this, 1,
            Intent(this, ScreenReaderService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, ScreenReaderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channel, "Captura do 455", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_455_launcher)
            .setContentTitle("455 — captura de tela ativa")
            .setContentText("Use o botão flutuante 455 para selecionar uma região.")
            .setOngoing(true)
            .addAction(0, "Capturar agora", capture)
            .addAction(0, "Encerrar", stop)
            .build()
    }

    private fun showFloatingButton() {
        if (floatingButton != null || projection == null || Build.VERSION.SDK_INT < 23 || !android.provider.Settings.canDrawOverlays(this)) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bubble = TextView(this).apply {
                text = "455"
                textSize = 17f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.rgb(113, 82, 220))
                    cornerRadius = dp(30).toFloat()
                    setStroke(dp(2), Color.WHITE)
                }
                elevation = dp(8).toFloat()
                setOnClickListener { beginCapture() }
            }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                       else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(dp(60), dp(60), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(12)
            }
            windowManager?.addView(bubble, params)
            floatingButton = bubble
        } catch (_: Exception) { floatingButton = null }
    }

    private fun hideFloatingButton() {
        val button = floatingButton ?: return
        try { windowManager?.removeView(button) } catch (_: Exception) { }
        floatingButton = null
    }

    private fun beginCapture() {
        if (projection == null || reader == null) return
        hideFloatingButton()
        handler.postDelayed({ captureFrame() }, 300)
    }

    private fun captureFrame() {
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            android.widget.Toast.makeText(this, "A tela ainda está sendo preparada. Toque no 455 novamente.", android.widget.Toast.LENGTH_SHORT).show()
            showFloatingButton()
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val wide = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(buffer)
            val bitmap = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
            wide.recycle()
            val out = File(cacheDir, "455_capture.png")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            startActivity(Intent(this, CaptureActivity::class.java)
                .putExtra(CaptureActivity.EXTRA_FILE, out.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Falha ao preparar a imagem: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            showFloatingButton()
        } finally { image.close() }
    }

    private fun stopProjection() {
        hideFloatingButton()
        display?.release(); display = null
        reader?.close(); reader = null
        val active = projection
        projection = null
        active?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideFloatingButton()
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val ACTION_CAPTURE = "com.leitordetela.app.CAPTURE"
        const val ACTION_STOP = "com.leitordetela.app.STOP"
        const val ACTION_SHOW_OVERLAY = "com.leitordetela.app.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.leitordetela.app.HIDE_OVERLAY"
    }
}
