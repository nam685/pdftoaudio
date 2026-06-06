package com.pdftoaudio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.util.*

class TtsService : Service(), TextToSpeech.OnInitListener {

    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    private val binder = TtsBinder()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isPlaying = false
        private set
    var isPaused = false
        private set

    private var textChunks = emptyList<String>()
    private var currentChunk = 0
    private var pdfTitle = "PDF"
    private var speechRate = 1.0f

    var callback: TtsCallback? = null

    interface TtsCallback {
        fun onStatus(message: String, progress: Int, total: Int)
        fun onStateChanged(isPlaying: Boolean, isPaused: Boolean)
    }

    companion object {
        const val CHANNEL_ID = "tts_channel"
        const val NOTIF_ID = 1
        const val ACTION_PLAY_PAUSE = "com.pdftoaudio.PLAY_PAUSE"
        const val ACTION_STOP = "com.pdftoaudio.STOP"
        private const val MAX_CHUNK = 3500
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY_PAUSE -> if (isPlaying && !isPaused) pause() else if (isPaused) resume()
                ACTION_STOP -> stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notifReceiver, filter)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(speechRate)
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    mainHandler.post {
                        if (isPlaying && !isPaused) {
                            currentChunk++
                            notifyProgress()
                            playNextChunk()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    mainHandler.post {
                        currentChunk++
                        playNextChunk()
                    }
                }
            })
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("Ready — select a PDF")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    fun loadAndPlay(context: Context, uri: Uri, startPage: Int, endPage: Int, speed: Float) {
        stop()
        speechRate = speed
        tts?.setSpeechRate(speed)
        notifyStatus("Loading PDF…", 0, 0)
        serviceScope.launch {
            try {
                val result = PdfExtractor.extract(context, uri, startPage, endPage)
                val chunks = splitChunks(result.text)
                mainHandler.post {
                    if (chunks.isEmpty()) {
                        notifyStatus("No readable text found in the selected pages.", 0, 0)
                        return@post
                    }
                    pdfTitle = result.title
                    textChunks = chunks
                    currentChunk = 0
                    isPlaying = true
                    isPaused = false
                    notifyStatus("Playing: $pdfTitle", 0, chunks.size)
                    callback?.onStateChanged(isPlaying, isPaused)
                    updateNotification()
                    playNextChunk()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    notifyStatus("Error reading PDF: ${e.message}", 0, 0)
                }
            }
        }
    }

    fun pause() {
        if (!isPlaying || isPaused) return
        tts?.stop()
        isPaused = true
        updateNotification()
        notifyStatus("Paused", currentChunk, textChunks.size)
        callback?.onStateChanged(isPlaying, isPaused)
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        updateNotification()
        notifyStatus("Playing: $pdfTitle", currentChunk, textChunks.size)
        callback?.onStateChanged(isPlaying, isPaused)
        playNextChunk()
    }

    fun stop() {
        tts?.stop()
        isPlaying = false
        isPaused = false
        currentChunk = 0
        textChunks = emptyList()
        updateNotification()
        notifyStatus("Stopped", 0, 0)
        callback?.onStateChanged(isPlaying, isPaused)
    }

    fun setSpeed(speed: Float) {
        speechRate = speed
        tts?.setSpeechRate(speed)
    }

    private fun playNextChunk() {
        if (!isPlaying || isPaused) return
        if (currentChunk >= textChunks.size) {
            isPlaying = false
            updateNotification()
            notifyStatus("Finished: $pdfTitle", textChunks.size, textChunks.size)
            callback?.onStateChanged(isPlaying, isPaused)
            return
        }
        val chunk = textChunks[currentChunk]
        val params = Bundle()
        val uid = "chunk_$currentChunk"
        tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    private fun notifyProgress() {
        notifyStatus("Playing: $pdfTitle (chunk ${currentChunk + 1}/${textChunks.size})", currentChunk, textChunks.size)
    }

    private fun notifyStatus(msg: String, progress: Int, total: Int) {
        callback?.onStatus(msg, progress, total)
    }

    private fun splitChunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        val clean = text.replace("\r\n", "\n").replace("\r", "\n")
        while (start < clean.length) {
            var end = minOf(start + MAX_CHUNK, clean.length)
            if (end < clean.length) {
                val sentenceBreak = clean.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), end)
                if (sentenceBreak > start + MAX_CHUNK / 2) end = sentenceBreak + 1
                else {
                    val wordBreak = clean.lastIndexOf(' ', end)
                    if (wordBreak > start) end = wordBreak
                }
            }
            val chunk = clean.substring(start, end).trim()
            if (chunk.isNotBlank()) result.add(chunk)
            start = end
        }
        return result
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "TTS Playback", NotificationManager.IMPORTANCE_LOW)
            ch.description = "PDF to Audio background playback"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ppPi = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ppLabel = if (isPlaying && !isPaused) "⏸ Pause" else "▶ Play"
        val ppIcon = if (isPlaying && !isPaused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDF to Audio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPi)
            .addAction(ppIcon, ppLabel, ppPi)
            .addAction(android.R.drawable.ic_delete, "■ Stop", stopPi)
            .setOngoing(isPlaying && !isPaused)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        val text = when {
            isPlaying && !isPaused -> "Playing: $pdfTitle"
            isPaused -> "Paused: $pdfTitle"
            else -> "Ready"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tts?.shutdown()
        unregisterReceiver(notifReceiver)
        super.onDestroy()
    }
}
