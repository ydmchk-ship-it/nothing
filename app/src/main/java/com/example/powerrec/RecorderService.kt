package com.example.powerrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Один сервис делает всё: пишет звук и (через root) слушает кнопку питания.
 * Он запускается из открытого приложения, поэтому Android 14 разрешает ему микрофон,
 * пока сервис работает, даже при выключенном экране.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_WATCH_ON = "powerrec.WATCH_ON"
        const val ACTION_WATCH_OFF = "powerrec.WATCH_OFF"
        const val ACTION_TOGGLE = "powerrec.TOGGLE"
        const val ACTION_STOP_REC = "powerrec.STOP_REC"

        private const val CHANNEL = "powerrec"
        private const val NOTIF_ID = 1
        private const val DOUBLE_MS = 700L

        @Volatile var recording = false
        @Volatile var watching = false
        @Volatile var rootProblem = false

        fun recordsDir(ctx: Context): File {
            val d = File(ctx.getExternalFilesDir(null), "records")
            d.mkdirs()
            return d
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var suProc: Process? = null
    @Volatile private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_WATCH_ON -> startWatcher()
            ACTION_WATCH_OFF -> stopWatcher()
            ACTION_TOGGLE -> toggleRec()
            ACTION_STOP_REC -> stopRec()
        }
        if (!recording && !watching) {
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWatcher()
        stopRec()
        super.onDestroy()
    }

    // ---------- уведомление ----------

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Диктофон", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif())
    }

    @Suppress("DEPRECATION")
    private fun buildNotif(): Notification {
        val stopAction = if (recording) ACTION_STOP_REC else ACTION_WATCH_OFF
        val stopIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(stopAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (recording) "Идёт запись" else "Жду двойное нажатие питания")
            .setContentText(if (recording) "Нажми питание дважды, чтобы остановить" else "Двойное нажатие начнёт запись")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (recording) "Стоп" else "Выключить",
                stopIntent
            )
            .build()
    }

    // ---------- запись ----------

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun toggleRec() {
        if (recording) stopRec() else startRec()
    }

    private fun startRec() {
        if (recording) return
        val r = newRecorder()
        try {
            val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(File(recordsDir(this), name).absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recording = true
            buzz(false)
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            recorder = null
            recording = false
            Toast.makeText(this, "Не удалось начать запись: " + e.message, Toast.LENGTH_LONG).show()
        }
        updateNotif()
    }

    private fun stopRec() {
        val r = recorder ?: return
        try { r.stop() } catch (_: RuntimeException) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        recording = false
        buzz(true)
        updateNotif()
    }

    private fun buzz(stop: Boolean) {
        val v = getSystemService(Vibrator::class.java) ?: return
        val effect = if (stop) {
            VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1)
        } else {
            VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        v.vibrate(effect)
    }

    // ---------- кнопка питания через root ----------

    private fun startWatcher() {
        if (watching) return
        watching = true
        rootProblem = false
        val gen = ++generation
        val t = Thread { watchLoop(gen) }
        t.isDaemon = true
        t.start()
        updateNotif()
    }

    private fun stopWatcher() {
        watching = false
        rootProblem = false
        generation++
        try { suProc?.destroy() } catch (_: Exception) {}
        suProc = null
        if (recorder != null || recording) updateNotif()
    }

    private fun watchLoop(gen: Int) {
        var lastDown = 0L
        while (gen == generation) {
            val started = SystemClock.elapsedRealtime()
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "exec getevent -lq"))
                suProc = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (gen == generation && line.contains("KEY_POWER") && line.contains("DOWN")) {
                        rootProblem = false
                        val now = SystemClock.elapsedRealtime()
                        if (lastDown != 0L && now - lastDown < DOUBLE_MS) {
                            lastDown = 0L
                            main.post { toggleRec() }
                        } else {
                            lastDown = now
                        }
                    }
                }
                p.waitFor()
            } catch (_: Exception) {
            }
            if (gen != generation) break
            // su закрылся почти сразу: скорее всего, нет root или доступ не выдан
            if (SystemClock.elapsedRealtime() - started < 3000) rootProblem = true
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
        }
    }
}
