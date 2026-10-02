package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.hopline.R
import app.hopline.service.BlobRules
import app.hopline.service.Blobs
import java.io.File

/**
 * Save, share and "open with…" for a received photo, voice note or file — the same everywhere.
 * Make one a field of the activity (it registers for the Android 8–9 "Save as…" screen, which
 * Android only allows before the screen starts), then call [save], [share] or [openWith].
 *
 * Copying runs off the main thread. The person always hears how it went (a toast survives the
 * screen closing); [save]'s callback and any app launch happen only while the screen is alive.
 */
class MediaActions(private val activity: ComponentActivity) {
    // Read when used, not now: as an activity field this is built before the activity is attached.
    private val app: Context get() = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The file waiting on the "Save as…" screen: kept across rotation and the app being killed. */
    private var pendingSave: Bundle? = null
    private var pendingDone: ((Boolean) -> Unit)? = null

    private val saveAs = activity.registerForActivityResult(CreateDocumentAs()) { target -> onSaveAsPicked(target) }

    init {
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY) { Bundle().apply { pendingSave?.let { putBundle(PENDING, it) } } }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_CREATE) {
                activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)?.getBundle(PENDING)?.let { pendingSave = it }
            }
        })
    }

    /**
     * Keep [file] on the phone: Pictures/Hopline or Download/Hopline on Android 10+, wherever the
     * person picks on 8–9. [done] (main thread, only while the screen lives) says whether it worked.
     */
    fun save(file: File, name: String, mime: String, done: ((Boolean) -> Unit)? = null) {
        if (!file.isFile) { toast(app.getString(R.string.media_missing)); done?.invoke(false); return }
        if (Build.VERSION.SDK_INT >= 29) {
            val ctx = app
            Blobs.background {
                val saved = Blobs.save(ctx, file, name, mime)
                main.post {
                    toast(if (saved != null) ctx.getString(R.string.media_saved_to, saved.folder) else ctx.getString(R.string.media_save_failed))
                    finished(saved != null, done)
                }
            }
            return
        }
        pendingSave = Bundle().apply { putString(PATH, file.absolutePath) }
        pendingDone = done
        try {
            saveAs.launch(BlobRules.displayName(name, fallback = file.name) to mime)
        } catch (e: ActivityNotFoundException) {
            pendingSave = null; pendingDone = null
            toast(app.getString(R.string.media_save_failed))
            done?.invoke(false)
        }
    }

    private fun onSaveAsPicked(target: Uri?) {
        val pending = pendingSave
        val done = pendingDone
        pendingSave = null; pendingDone = null
        val path = pending?.getString(PATH)
        // Backed out of the picker: nothing to say, nothing went wrong.
        if (target == null || path == null) { done?.invoke(false); return }
        val ctx = app
        Blobs.background {
            val ok = Blobs.copyToUri(ctx, File(path), target)
            main.post {
                toast(ctx.getString(if (ok) R.string.media_saved else R.string.media_save_failed))
                finished(ok, done)
            }
        }
    }

    private fun finished(ok: Boolean, done: ((Boolean) -> Unit)?) {
        if (!alive()) return
        if (ok) activity.window?.decorView?.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        done?.invoke(ok)
    }

    /** Hand the file to another app (the system share sheet), under its real name. */
    fun share(file: File, name: String, mime: String) {
        withCopy(file, name) { copy ->
            val uri = uriFor(copy) ?: return@withCopy toast(app.getString(R.string.media_share_failed))
            val send = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(copy.name, uri)   // carries the read grant through the chooser
            try { activity.startActivity(Intent.createChooser(send, null)) }
            catch (e: Exception) { toast(app.getString(R.string.media_share_failed)) }
        }
    }

    /**
     * Open the file in whichever app on the phone handles it — for a document, a song, or a
     * picture this app can't draw. Falls back to the broad type ("any image app") before giving up.
     */
    fun openWith(file: File, name: String, mime: String) {
        withCopy(file, name) { copy ->
            val uri = uriFor(copy)
            val broad = mime.substringBefore('/', "").let { if (it in BROAD_TYPES) "$it/*" else "*/*" }
            if (uri != null) for (type in listOf(mime, broad).distinct()) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    return@withCopy
                } catch (e: ActivityNotFoundException) { } catch (e: SecurityException) { }
            }
            toast(app.getString(R.string.media_no_app, copy.name))
        }
    }

    private fun withCopy(file: File, name: String, then: (File) -> Unit) {
        if (!file.isFile) { toast(app.getString(R.string.media_missing)); return }
        val ctx = app
        Blobs.background {
            val copy = Blobs.shareCopy(ctx, file, name)
            main.post {
                if (copy == null) toast(ctx.getString(R.string.media_share_failed))
                else if (alive()) then(copy)
            }
        }
    }

    private fun uriFor(f: File): Uri? = try { FileProvider.getUriForFile(app, FILES_AUTHORITY, f) } catch (e: Exception) { null }

    private fun alive(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private fun toast(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    /** ACTION_CREATE_DOCUMENT with the type chosen per file (the stock contract fixes it up front). */
    private class CreateDocumentAs : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }

    companion object {
        /** Same provider as the chat's attachments (see AndroidManifest and res/xml/file_paths.xml). */
        const val FILES_AUTHORITY = "app.hopline.files"
        private val BROAD_TYPES = setOf("image", "audio", "video", "text")
        private const val STATE_KEY = "hopline.media.pendingSave"
        private const val PENDING = "pending"
        private const val PATH = "path"
    }
}
