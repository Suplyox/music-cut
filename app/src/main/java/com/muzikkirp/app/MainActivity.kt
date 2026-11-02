package com.muzikkirp.app

import android.app.AlertDialog
import android.content.ClipData
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.view.View
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

    private var worker: Thread? = null
    private var savedUri: Uri? = null
    private lateinit var outputName: EditText
    private lateinit var quality: Spinner
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: Button
    private lateinit var shareButton: Button
    private lateinit var openButton: Button
    private lateinit var resetButton: Button
    private lateinit var timeButton: Button
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

        outputName = findViewById(R.id.outputName)
        quality = findViewById(R.id.quality)
        progressBar = findViewById(R.id.progressBar)
        cancelButton = findViewById(R.id.cancelButton)
        shareButton = findViewById(R.id.shareButton)
        openButton = findViewById(R.id.openButton)
        resetButton = findViewById(R.id.resetButton)
        timeButton = findViewById(R.id.timeButton)
        quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("128 kbps · Küçük dosya", "192 kbps · Dengeli", "256 kbps · Yüksek kalite"))
        quality.setSelection(1)
        cancelButton.setOnClickListener { worker?.interrupt(); cancelButton.isEnabled = false }
        shareButton.setOnClickListener { savedUri?.let { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Ses", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Kaydı paylaş"))
        } }
        openButton.setOnClickListener { savedUri?.let { uri ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "Ses oynatıcı bulunamadı.", Toast.LENGTH_LONG).show()
            }
        } }
        resetButton.setOnClickListener {
            stopPreview()
            startSeek.progress = 0
            endSeek.progress = durationMs
        }
        timeButton.setOnClickListener { editTimes() }
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
        sourceUri = null
        setBusy(false)
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
        outputName.setText("${baseName}_kirpildi")

        startSeek.max = durationMs
        endSeek.max = durationMs
        startSeek.progress = 0
        endSeek.progress = durationMs
        startSeek.isEnabled = true
        endSeek.isEnabled = true
        previewButton.isEnabled = true
        saveButton.isEnabled = true
        previewButton.setText(R.string.preview)
        setBusy(false)
        updateLabels()
    }

    private fun editTimes() {
        val fields = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }
        fun field(label: String, ms: Int) = EditText(this).apply {
            hint = label
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.3f", ms / 1000.0))
            fields.addView(this)
        }
        val start = field("Başlangıç (saniye)", startSeek.progress)
        val end = field("Bitiş (saniye)", endSeek.progress)
        val dialog = AlertDialog.Builder(this).setTitle("Hassas zaman · saniye")
            .setView(fields).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Uygula", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = start.text.toString().replace(',', '.').toDoubleOrNull()
                val e = end.text.toString().replace(',', '.').toDoubleOrNull()
                if (s == null || e == null || !s.isFinite() || !e.isFinite() ||
                    s < 0 || e * 1000 > durationMs || (e - s) * 1000 < MIN_GAP_MS) {
                    end.error = "En az 0,5 saniyelik geçerli bir aralık gir."
                } else {
                    stopPreview()
                    startSeek.progress = (s * 1000).toInt()
                    endSeek.progress = (e * 1000).toInt()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
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
            p.setOnSeekCompleteListener { ready ->
                ready.setOnSeekCompleteListener(null)
                if (!isDestroyed && hasWindowFocus()) {
                    ready.start()
                    previewButton.setText(R.string.stop)
                    handler.post(stopChecker)
                }
            }
            p.seekTo(startSeek.progress.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    private fun stopPreview() {
        handler.removeCallbacks(stopChecker)
        val p = player ?: return
        p.setOnSeekCompleteListener(null)
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

        val name = outputName.text.toString().trim().removeSuffix(".m4a")
            .replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(100).ifBlank { "muzik_kirpildi" }
        val bitrate = intArrayOf(128_000, 192_000, 256_000)[quality.selectedItemPosition]
        savedUri = null
        shareButton.isEnabled = false
        openButton.isEnabled = false
        progressBar.progress = 0
        worker = Thread {
            var result: Uri? = null
            var message = "Kayıt oluşturulamadı. Dosyayı ve boş depolama alanını kontrol et."
            try {
                result = writeTrimmed(src, startMs, endMs, name, bitrate)
                message = "Kaydedildi · Music/MuzikKirp"
            } catch (_: InterruptedException) {
                message = "İşlem iptal edildi."
            } catch (_: Exception) {
                // The pending output is removed by writeTrimmed.
            }
            val finalUri = result
            val finalMessage = message
            runOnUiThread {
                if (!isDestroyed) {
                    worker = null
                    savedUri = finalUri
                    setBusy(false)
                    shareButton.isEnabled = finalUri != null
                    openButton.isEnabled = finalUri != null
                    statusText.text = finalMessage
                }
            }
        }.also { it.start() }
    }

    private fun writeTrimmed(src: Uri, startMs: Int, endMs: Int, name: String, bitrate: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MuzikKirp")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outUri = contentResolver.insert(collection, values) ?: error("Cannot create output")
        var published = false
        try {
            contentResolver.openFileDescriptor(outUri, "rw")?.use { pfd ->
                var lastPercent = -1
                check(AudioTrimmer.trim(applicationContext, src, startMs * 1000L,
                    endMs * 1000L, pfd.fileDescriptor, bitrate) { percent ->
                    if (percent != lastPercent) {
                      lastPercent = percent
                      runOnUiThread {
                        if (!isDestroyed) {
                            progressBar.progress = percent
                            statusText.text = "İşleniyor · %$percent"
                        }
                      }
                    }
                })
            } ?: error("Cannot open output")
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            check(contentResolver.update(outUri, done, null, null) > 0)
            published = true
            return outUri
        } finally {
            if (!published) contentResolver.delete(outUri, null, null)
        }
    }

    private fun setBusy(busy: Boolean) {
        val enabled = !busy && sourceUri != null && player != null
        pickButton.isEnabled = !busy
        previewButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        startSeek.isEnabled = enabled
        endSeek.isEnabled = enabled
        timeButton.isEnabled = enabled
        resetButton.isEnabled = enabled
        outputName.isEnabled = !busy
        quality.isEnabled = !busy
        cancelButton.visibility = if (busy) View.VISIBLE else View.GONE
        cancelButton.isEnabled = busy
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
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
        worker?.interrupt()
        releasePlayer()
        super.onDestroy()
    }
}
