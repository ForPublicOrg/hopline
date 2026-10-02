package app.hopline.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.hopline.R
import app.hopline.service.Core
import java.io.File

/**
 * Records one voice note at a time. AAC mono at 24 kbps: a full minute is ~180 KB — hops the
 * mesh in seconds. A process-wide object so a screen rotation doesn't kill a recording.
 */
object VoiceRecorder {
    const val MAX_SECONDS = 60
    private const val TAG = "Hopline/Voice"

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null
    var startedAt = 0L; private set
    /** Fired on the main thread when the cap is hit, so the UI can send what's there. */
    var onMaxReached: (() -> Unit)? = null

    val recording: Boolean get() = recorder != null

    fun start(ctx: Context): Boolean {
        cancel()
        // The recorder outlives screens (a rotation must not end a take): never hold an Activity.
        val app = ctx.applicationContext
        var r: MediaRecorder? = null
        var f: File? = null
        return try {
            val dir = File(app.cacheDir, "voice").apply { mkdirs() }
            // Nothing is recording now, and a finished clip is read the moment it stops: anything
            // still here is a stub from a failed start or an app that was killed mid-take.
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "voice-${System.currentTimeMillis() / 1000}.m4a").also { f = it }
            @Suppress("DEPRECATION")
            val rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()).also { r = it }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(24_000)
            rec.setMaxDuration(MAX_SECONDS * 1000)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached?.invoke()
            }
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            // Music pauses while you talk, like any messenger; it resumes when the take ends.
            focus = AudioFocus.request(app, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, null)
            rec.start()
            recorder = rec; file = out; startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            // Usually the mic is busy (a call, another recorder). A prepared recorder that is never
            // released keeps the mic claimed on many phones, so every later tap would fail too.
            Log.w(TAG, "couldn't start recording", e)
            try { r?.reset() } catch (ignored: Exception) { }
            try { r?.release() } catch (ignored: Exception) { }
            f?.delete()
            AudioFocus.abandon(app, focus); focus = null
            false
        }
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
        releaseFocus()
        return if (f == null || sec < 1 || !f.exists() || f.length() < 200) { f?.delete(); null }
        else f to sec.coerceAtMost(MAX_SECONDS)
    }

    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) {
            try { r.stop() } catch (e: Exception) { }
            try { r.release() } catch (e: Exception) { }
        }
        file?.delete(); file = null
        releaseFocus()
    }

    private fun releaseFocus() {
        focus?.let { AudioFocus.abandon(Core.app, it) }
        focus = null
    }
}

/** Plays one voice note at a time, chat-wide, and pokes the UI while the position moves. */
object VoicePlayer {
    private const val TAG = "Hopline/Voice"
    var playingFid: String? = null; private set
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false
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
    /** Headphones pulled out mid-note: stop, rather than suddenly play it out loud to the room. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    /** Play [file], or stop it if it is the one playing. Returns false (and says so) if it can't be played. */
    fun toggle(file: File, fid: String): Boolean {
        if (playingFid == fid) { stop(); return true }
        stop()
        val app = Core.app
        var p: MediaPlayer? = null
        return try {
            val mp = MediaPlayer().also { p = it }
            mp.setAudioAttributes(AudioFocus.SPEECH)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.prepare()
            // Other audio pauses for the note (and a call or another player stops it in turn).
            focus = AudioFocus.request(app, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
            }
            mp.start()
            player = mp; playingFid = fid
            registerNoisy(app)
            handler.post(ticker)
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't play a voice note", e)
            // Never set as the player, so stop() can't see it: release it here, or each tap leaks one.
            if (player !== p) try { p?.release() } catch (ignored: Exception) { }
            stop()
            Toast.makeText(app, R.string.voice_cant_play, Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        val p = player
        player = null
        try { p?.stop() } catch (e: Exception) { }
        try { p?.release() } catch (e: Exception) { }
        focus?.let { AudioFocus.abandon(Core.app, it) }
        focus = null
        unregisterNoisy()
        if (playingFid != null) { playingFid = null; onChanged?.invoke() }
    }

    fun positionMs(fid: String): Int =
        if (playingFid == fid) try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 } else 0

    private fun registerNoisy(app: Context) {
        if (noisyRegistered) return
        try {
            ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } catch (e: Exception) { Log.w(TAG, "couldn't watch for unplugged headphones", e) }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        try { Core.app.unregisterReceiver(noisy) } catch (e: Exception) { }
    }
}

/** Audio focus, the polite way apps share the speaker: ask before sound starts, give it back after. */
private object AudioFocus {
    val SPEECH: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Returns the request to give back later, or null if focus wasn't granted (we go ahead anyway). */
    fun request(ctx: Context, gain: Int, onChange: ((Int) -> Unit)?): AudioFocusRequest? = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(SPEECH)
            .apply { if (onChange != null) setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper())) }
            .build()
        if (am?.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else null
    } catch (e: Exception) { null }

    fun abandon(ctx: Context, req: AudioFocusRequest?) {
        if (req == null) return
        try { ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) } catch (e: Exception) { }
    }
}
