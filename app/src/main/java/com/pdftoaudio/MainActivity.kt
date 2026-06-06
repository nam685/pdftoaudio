package com.pdftoaudio

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private var ttsService: TtsService? = null
    private var bound = false
    private var selectedUri: Uri? = null

    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCacheStatus: TextView
    private lateinit var etStartPage: TextInputEditText
    private lateinit var etEndPage: TextInputEditText
    private lateinit var btnPickFile: MaterialButton
    private lateinit var btnPreprocess: MaterialButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var layoutControls: View

    private val ttsCallback = object : TtsService.TtsCallback {
        override fun onStatus(message: String, progress: Int, total: Int) {
            runOnUiThread {
                tvStatus.text = message
                if (total > 0) {
                    progressBar.max = total
                    progressBar.progress = progress
                    tvProgress.text = "Chunk $progress / $total"
                    tvProgress.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                } else {
                    tvProgress.visibility = View.GONE
                    progressBar.visibility = View.GONE
                }
            }
        }

        override fun onStateChanged(isPlaying: Boolean, isPaused: Boolean, isProcessing: Boolean) {
            runOnUiThread { syncControls() }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val svc = (service as TtsService.TtsBinder).getService()
            ttsService = svc
            svc.callback = ttsCallback
            bound = true
            syncControls()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            ttsService?.callback = null
            ttsService = null
            bound = false
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            tvFileName.text = getDisplayName(uri)
            tvStatus.text = "File selected"
            updateCacheStatus()
            syncControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)
        tvCacheStatus = findViewById(R.id.tvCacheStatus)
        etStartPage = findViewById(R.id.etStartPage)
        etEndPage = findViewById(R.id.etEndPage)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnPreprocess = findViewById(R.id.btnPreprocess)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        layoutControls = findViewById(R.id.layoutControls)

        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE

        btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf("application/pdf"))
        }

        btnPreprocess.setOnClickListener {
            val svc = ttsService ?: return@setOnClickListener
            val uri = selectedUri ?: return@setOnClickListener
            svc.preprocessAndSave(this, uri) { success, message ->
                tvStatus.text = message
                if (success) updateCacheStatus()
                syncControls()
            }
        }

        btnPlayPause.setOnClickListener {
            val svc = ttsService ?: return@setOnClickListener
            when {
                svc.isPlaying && !svc.isPaused -> svc.pause()
                svc.isPaused -> svc.resume()
                else -> {
                    val uri = selectedUri ?: return@setOnClickListener
                    val start = etStartPage.text?.toString()?.toIntOrNull() ?: 1
                    val end = etEndPage.text?.toString()?.toIntOrNull() ?: Int.MAX_VALUE
                    svc.loadAndPlay(this, uri, start, end, currentSpeed())
                }
            }
        }

        btnStop.setOnClickListener { ttsService?.stop() }

        seekBarSpeed.max = 20
        seekBarSpeed.progress = 10
        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = speedFromProgress(progress)
                tvSpeed.text = "Speed: ${"%.1f".format(speed)}×"
                if (fromUser) ttsService?.setSpeed(speed)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        tvSpeed.text = "Speed: 1.0×"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        syncControls()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TtsService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        ttsService?.callback = null
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun syncControls() {
        val svc = ttsService
        val hasFile = selectedUri != null
        val playing = svc?.isPlaying == true
        val paused = svc?.isPaused == true
        val processing = svc?.isProcessing == true

        btnPreprocess.isEnabled = hasFile && !processing && !playing
        btnPreprocess.text = if (processing) "Processing…" else "Preprocess & Save"

        btnPlayPause.isEnabled = (hasFile || playing || paused) && !processing
        btnStop.isEnabled = (playing || paused) && !processing

        btnPlayPause.text = when {
            playing && !paused -> "⏸ Pause"
            paused -> "▶ Resume"
            else -> "▶ Play"
        }
    }

    private fun updateCacheStatus() {
        val uri = selectedUri ?: return
        if (PdfExtractor.hasCached(this, uri)) {
            val kb = PdfExtractor.cachedSizeKb(this, uri)
            tvCacheStatus.text = "Preprocessed text cached (${kb} KB) — Play will use this"
            tvCacheStatus.visibility = View.VISIBLE
        } else {
            tvCacheStatus.text = "No cache — tap Preprocess & Save first for best results"
            tvCacheStatus.visibility = View.VISIBLE
        }
    }

    private fun currentSpeed() = speedFromProgress(seekBarSpeed.progress)

    private fun speedFromProgress(p: Int) = 0.5f + p * 0.075f

    private fun getDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "PDF"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
        }
        return name
    }
}
