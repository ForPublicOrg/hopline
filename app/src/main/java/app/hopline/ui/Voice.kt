package app.hopline.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Records one voice note at a time. AAC mono at 24 kbps: a full minute is ~180 KB — hops the
 * mesh in seconds. A process-wide object so a screen rotation doesn't kill a recording.
 */
object VoiceRecorder {
    const val MAX_SECONDS = 60

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    var startedAt = 0L; private set
    /** Fired on the main thread when the cap is hit, so the UI can send what's there. */
    var onMaxReached: (() -> Unit)? = null

    val recording: Boolean get() = recorder != null

    fun start(ctx: Context): Boolean {
        cancel()
        return try {
            val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
            val f = File(dir, "voice-${System.currentTimeMillis() / 1000}.m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_SECONDS * 1000)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached?.invoke()
            }
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; file = f; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) { cancel(); false }
    }

    /** Stop and hand over the clip: (file, seconds). Null if it was too short or broke. */
    fun finish(): Pair<File, Int>? {
        val r = recorder ?: return null
        val f = file
        recorder = null; file = null
        val sec = (((System.currentTimeMillis() - startedAt) + 500) / 1000).toInt()
        // At the max-duration cap the recorder stops ITSELF and stop() may then throw — but the
        // clip on disk is finished and perfectly good. Judge by the file, not by stop()'s mood.
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        return if (f == null || sec < 1 || !f.exists() || f.length() < 200) { f?.delete(); null }
        else f to sec.coerceAtMost(MAX_SECONDS)
    }

    fun cancel() {
        val r = recorder ?: return run { file?.delete(); file = null }
        recorder = null
        try { r.stop() } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        file?.delete(); file = null
    }
}

/** Plays one voice note at a time, chat-wide, and pokes the UI while the position moves. */
object VoicePlayer {
    var playingFid: String? = null; private set
    private var player: MediaPlayer? = null
    /** The open chat sets this to its redraw; the diff stamp limits rebinds to the one row. */
    var onChanged: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (playingFid == null) return
            onChanged?.invoke()
            handler.postDelayed(this, 500)   // 2 Hz is smooth enough for a 4dp bar and half the diffs
        }
    }

    fun toggle(file: File, fid: String) {
        if (playingFid == fid) { stop(); return }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepare(); p.start()
            player = p; playingFid = fid
            handler.post(ticker)
        } catch (e: Exception) { stop() }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        val p = player
        player = null
        try { p?.stop() } catch (e: Exception) { }
        try { p?.release() } catch (e: Exception) { }
        if (playingFid != null) { playingFid = null; onChanged?.invoke() }
    }

    fun positionMs(fid: String): Int =
        if (playingFid == fid) try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 } else 0
}
