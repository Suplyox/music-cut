package com.muzikkirp.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK = 1001
        const val MIN_GAP_MS = 500
    }

    private var sourceUri: Uri? = null
    private var baseName = "muzik"
    private var durationMs = 0
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var pickButton: Button
    private lateinit var fileName: TextView
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var lengthLabel: TextView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var previewButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView

    private val stopChecker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                if (p.currentPosition >= endSeek.progress) {
                    stopPreview()
                } else {
                    handler.postDelayed(this, 50)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickButton = findViewById(R.id.pickButton)
        fileName = findViewById(R.id.fileName)
        startLabel = findViewById(R.id.startLabel)
        endLabel = findViewById(R.id.endLabel)
        lengthLabel = findViewById(R.id.lengthLabel)
        startSeek = findViewById(R.id.startSeek)
        endSeek = findViewById(R.id.endSeek)
        previewButton = findViewById(R.id.previewButton)
        saveButton = findViewById(R.id.saveButton)
        statusText = findViewById(R.id.statusText)

        pickButton.setOnClickListener { pickFile() }
        previewButton.setOnClickListener { togglePreview() }
        saveButton.setOnClickListener { saveTrimmed() }

        startSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val maxStart = (endSeek.progress - MIN_GAP_MS).coerceAtLeast(0)
                    if (progress > maxStart) sb.progress = maxStart
                    stopPreview()
                }
                updateLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        endSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minEnd = (startSeek.progress + MIN_GAP_MS).coerceAtMost(durationMs)
                    if (progress < minEnd) sb.progress = minEnd
                    stopPreview()
                }
                updateLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        updateLabels()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { loadFile(it) }
        }
    }

    private fun loadFile(uri: Uri) {
        releasePlayer()
        statusText.text = ""

        val mp = MediaPlayer()
        try {
            mp.setDataSource(this, uri)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            return
        }
        mp.setOnCompletionListener { stopPreview() }

        player = mp
        sourceUri = uri
        durationMs = mp.duration

        val name = queryName(uri) ?: "muzik"
        baseName = name.substringBeforeLast('.').ifBlank { "muzik" }
        fileName.text = name

        startSeek.max = durationMs
        endSeek.max = durationMs
        startSeek.progress = 0
        endSeek.progress = durationMs
        startSeek.isEnabled = true
        endSeek.isEnabled = true
        previewButton.isEnabled = true
        saveButton.isEnabled = true
        previewButton.setText(R.string.preview)
        updateLabels()
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateLabels() {
        val s = startSeek.progress
        val e = endSeek.progress
        startLabel.text = getString(R.string.start_label, formatMs(s))
        endLabel.text = getString(R.string.end_label, formatMs(e))
        lengthLabel.text = getString(R.string.length_label, formatMs((e - s).coerceAtLeast(0)))
    }

    private fun formatMs(ms: Int): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val tenth = (ms % 1000) / 100
        return String.format(Locale.US, "%d:%02d.%d", m, s, tenth)
    }

    private fun togglePreview() {
        val p = player ?: return
        if (p.isPlaying) {
            stopPreview()
        } else {
            p.seekTo(startSeek.progress)
            p.start()
            previewButton.setText(R.string.stop)
            handler.post(stopChecker)
        }
    }

    private fun stopPreview() {
        handler.removeCallbacks(stopChecker)
        val p = player ?: return
        if (p.isPlaying) p.pause()
        previewButton.setText(R.string.preview)
    }

    private fun saveTrimmed() {
        val src = sourceUri ?: return
        val startMs = startSeek.progress
        val endMs = endSeek.progress
        if (endMs - startMs < MIN_GAP_MS) {
            Toast.makeText(this, R.string.error_range, Toast.LENGTH_LONG).show()
            return
        }

        stopPreview()
        setBusy(true)
        statusText.setText(R.string.processing)

        Thread {
            val ok = try {
                writeTrimmed(src, startMs, endMs)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                setBusy(false)
                statusText.setText(if (ok) R.string.saved else R.string.error_trim)
            }
        }.start()
    }

    private fun writeTrimmed(src: Uri, startMs: Int, endMs: Int): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${baseName}_kirpildi.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MuzikKirp")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outUri = contentResolver.insert(collection, values) ?: return false

        var ok = false
        try {
            contentResolver.openFileDescriptor(outUri, "rw")?.use { pfd ->
                ok = AudioTrimmer.trim(
                    this, src, startMs * 1000L, endMs * 1000L, pfd.fileDescriptor
                )
            }
        } catch (e: Exception) {
            ok = false
        }

        if (ok) {
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            contentResolver.update(outUri, done, null, null)
        } else {
            contentResolver.delete(outUri, null, null)
        }
        return ok
    }

    private fun setBusy(busy: Boolean) {
        val enabled = !busy && sourceUri != null
        pickButton.isEnabled = !busy
        previewButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        startSeek.isEnabled = enabled
        endSeek.isEnabled = enabled
    }

    private fun releasePlayer() {
        handler.removeCallbacks(stopChecker)
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
