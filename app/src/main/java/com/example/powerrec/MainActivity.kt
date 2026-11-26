package com.example.powerrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var btnRec: Button
    private lateinit var btnWatch: Button
    private lateinit var list: ListView

    private var files: List<File> = emptyList()
    private var shownNames: List<String> = emptyList()
    private var player: MediaPlayer? = null
    private var playing: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Диктофон"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)

        btnRec = Button(this).apply { setOnClickListener { onRecClick() } }
        btnWatch = Button(this).apply { setOnClickListener { onWatchClick() } }
        root.addView(btnRec, LinearLayout.LayoutParams(-1, -2))
        root.addView(btnWatch, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Записи (нажми — слушать, удерживай — удалить)"
            textSize = 13f
            setPadding(0, dp(16), 0, dp(4))
        })

        list = ListView(this).apply {
            emptyView = null
            setOnItemClickListener { _, _, pos, _ -> togglePlay(files[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> deleteFile(files[pos]); true }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }

    // ---------- кнопки ----------

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
        Toast.makeText(this, "Разреши микрофон и уведомления, потом нажми кнопку ещё раз", Toast.LENGTH_LONG).show()
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, RecorderService::class.java).setAction(action))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun onRecClick() {
        if (!hasMic()) { askPermissions(); return }
        stopPlayer()
        send(RecorderService.ACTION_TOGGLE)
    }

    private fun onWatchClick() {
        if (!hasMic()) { askPermissions(); return }
        send(if (RecorderService.watching) RecorderService.ACTION_WATCH_OFF else RecorderService.ACTION_WATCH_ON)
    }

    // ---------- список записей ----------

    private fun togglePlay(f: File) {
        if (playing == f) { stopPlayer(); return }
        stopPlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { stopPlayer() }
            mp.prepare()
            mp.start()
            player = mp
            playing = f
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось воспроизвести", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun stopPlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = null
    }

    private fun deleteFile(f: File) {
        if (playing == f) stopPlayer()
        f.delete()
        refresh()
    }

    private fun describe(f: File): String {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(f.lastModified()))
        val kb = f.length() / 1024
        val mark = if (playing == f) "▶ " else ""
        return "$mark$date · ${kb} КБ"
    }

    // ---------- обновление экрана ----------

    private fun refresh() {
        val rec = RecorderService.recording
        val watch = RecorderService.watching

        btnRec.text = if (rec) "■ Остановить запись" else "● Начать запись"
        btnWatch.text = if (watch) "Выключить двойное нажатие питания" else "Включить двойное нажатие питания"

        status.text = when {
            rec -> "● Идёт запись"
            else -> "Не записываю"
        } + "\n" + when {
            watch && RecorderService.rootProblem -> "Кнопка питания: нет root-доступа. Выдай доступ этому приложению в Magisk / KernelSU."
            watch -> "Кнопка питания: слушаю. Два быстрых нажатия включают и выключают запись."
            else -> "Кнопка питания: выключено."
        }

        files = RecorderService.recordsDir(this).listFiles { f -> f.name.endsWith(".m4a") }
            ?.sortedByDescending { it.name } ?: emptyList()
        val names = files.map { describe(it) }
        if (names != shownNames) {
            shownNames = names
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        }
    }
}
