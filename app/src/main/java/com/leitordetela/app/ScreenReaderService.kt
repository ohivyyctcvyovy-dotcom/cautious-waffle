package com.leitordetela.app

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class ScreenReaderService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val channel = "455_capture"
    private val notificationId = 455

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE) {
            captureFrame()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopProjection()
            return START_NOT_STICKY
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
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopProjection() }
            }, Handler(Looper.getMainLooper()))
            display = projection?.createVirtualDisplay(
                "455-screen-capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
            )
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
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_455_launcher)
            .setContentTitle("455 — captura de tela ativa")
            .setContentText("Volte ao aplicativo desejado e toque em Capturar agora.")
            .setOngoing(true)
            .addAction(0, "Capturar agora", capture)
            .addAction(0, "Encerrar", stop)
            .build().also {
                if (Build.VERSION.SDK_INT >= 26) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.createNotificationChannel(NotificationChannel(channel, "Captura do 455", NotificationManager.IMPORTANCE_LOW))
                }
            }
    }

    private fun captureFrame() {
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            android.widget.Toast.makeText(this, "A tela ainda está sendo preparada. Tente novamente.", android.widget.Toast.LENGTH_SHORT).show()
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
        } finally { image.close() }
    }

    private fun stopProjection() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { display?.release(); reader?.close(); projection?.stop(); super.onDestroy() }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val ACTION_CAPTURE = "com.leitordetela.app.CAPTURE"
        const val ACTION_STOP = "com.leitordetela.app.STOP"
    }
}
