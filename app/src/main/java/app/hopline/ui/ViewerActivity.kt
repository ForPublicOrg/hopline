package app.hopline.ui

import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import app.hopline.R
import app.hopline.databinding.ActivityViewerBinding
import app.hopline.mesh.Attachment
import app.hopline.mesh.Message
import app.hopline.service.BlobRules
import app.hopline.service.Core
import java.io.File

/**
 * A received photo, full screen: pinch or double-tap to zoom, tap to hide the bars, pull down to
 * close. Save keeps it in Pictures/Hopline; Share hands it on; a picture this phone can't draw
 * offers "Open with…" instead of a black screen.
 */
class ViewerActivity : AppCompatActivity() {
    private lateinit var b: ActivityViewerBinding
    private val media = MediaActions(this)
    private var file: File? = null
    private var name = ""
    private var mime = "image/jpeg"
    private var barsHidden = false
    private var problem = false
    private var caption = ""
    private val showSpinner = Runnable { if (!isDestroyed) b.loading.isVisible = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Hopline_Viewer)
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fitBarsToInsets()

        // Only ever a received file of this app: the path is checked, not trusted.
        file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }?.takeIf { BlobRules.isInside(File(filesDir, "blobs"), it) }
        name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { file?.name.orEmpty() }
        mime = intent.getStringExtra(EXTRA_MIME)?.takeIf { it.contains('/') } ?: "image/jpeg"
        barsHidden = savedInstanceState?.getBoolean(STATE_BARS_HIDDEN) ?: false

        // The back arrow points the way the reading goes.
        b.back.drawable?.mutate()?.isAutoMirrored = true
        b.back.setOnClickListener { finish() }
        b.save.setOnClickListener { save() }
        b.share.setOnClickListener { file?.let { media.share(it, name, mime) } }
        b.openWith.setOnClickListener { file?.let { media.openWith(it, name, mime) } }
        b.caption.movementMethod = ScrollingMovementMethod()
        b.image.onTap = { if (!problem) setBarsHidden(!barsHidden, animate = true) }
        b.image.onDrag = { pulled -> b.topBar.alpha = 1f - pulled; b.caption.alpha = 1f - pulled }
        b.image.onDismiss = { closeWithFade() }

        Core.version.observe(this) { showDetails() }
        showDetails()
        showPhoto()
        setBarsHidden(barsHidden, animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BARS_HIDDEN, barsHidden)
    }

    override fun onDestroy() {
        if (::b.isInitialized) b.root.removeCallbacks(showSpinner)
        super.onDestroy()
    }

    private fun showPhoto() {
        val f = file
        if (f == null || !f.isFile) { showProblem(R.string.viewer_missing, canOpen = false); return }
        // A spinner only if decoding is slow: a flash of one on every open would be noise.
        b.root.postDelayed(showSpinner, 250)
        val thumb = message()?.att?.thumb?.let { Images.thumb(it) }
        Images.load(f, b.image, targetPx = targetPx(), placeholder = thumb) { ok ->
            b.root.removeCallbacks(showSpinner)
            b.loading.isVisible = false
            if (!ok) showProblem(R.string.viewer_cant_show, canOpen = true)
        }
    }

    /** Sharp at full screen and at a moderate zoom, without holding a 40 MB bitmap. */
    private fun targetPx(): Int {
        val dm = resources.displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels).coerceIn(1080, 2048)
    }

    private fun showProblem(text: Int, canOpen: Boolean) {
        problem = true
        b.image.setImageDrawable(null)
        b.image.isVisible = false
        b.problem.isVisible = true
        b.problemText.setText(text)
        b.openWith.isVisible = canOpen
        // Nothing to save or share when the file is gone; the back arrow must stay reachable.
        val exists = file?.isFile == true
        b.save.isVisible = exists
        b.share.isVisible = exists
        setBarsHidden(false, animate = false)
    }

    /** Who sent it, when, and its caption — from the message, once the mesh is up. */
    private fun showDetails() {
        val r = Core.router
        val m = message()
        if (r != null && m != null) {
            val who = if (m.from == r.me.id) getString(R.string.reply_you) else Ui.nameOf(r, m.from, m.fromName)
            b.title.text = who
            val at = minOf(m.ts, System.currentTimeMillis())
            b.subtitle.text = DateUtils.getRelativeDateTimeString(this, at, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0)
            b.subtitle.isVisible = true
            caption = m.text.trim()
            b.image.contentDescription = if (caption.isEmpty()) getString(R.string.viewer_photo_from, who)
                                         else getString(R.string.viewer_photo_from_caption, who, caption)
        } else {
            b.title.text = name.ifEmpty { getString(R.string.viewer_photo) }
            b.subtitle.isVisible = false
            caption = ""
            b.image.contentDescription = getString(R.string.viewer_photo)
        }
        b.caption.text = caption
        b.caption.isVisible = caption.isNotEmpty() && !barsHidden && !problem
    }

    /** The message this file belongs to: the file is named "<fid>-<name>" in its group's folder. */
    private fun message(): Message? {
        val r = Core.router ?: return null
        val f = file ?: return null
        if (f.parentFile?.parentFile?.name != r.group.fingerprint) return null
        val fid = f.name.substringBefore('-')
        if (!Attachment.validFid(fid)) return null
        return r.fileMessage(fid)?.takeIf { it.att?.fid == fid }
    }

    private fun save() {
        val f = file ?: return
        if (!b.save.isEnabled) return
        // One save at a time: a double tap must not make two copies.
        b.save.isEnabled = false
        b.save.alpha = 0.4f
        media.save(f, name, mime) { _ -> b.save.isEnabled = true; b.save.alpha = 1f }
    }

    /** Tap the photo to see just the photo (and the system bars go too); tap again for the controls. */
    private fun setBarsHidden(hidden: Boolean, animate: Boolean) {
        barsHidden = hidden
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
        fade(b.topBar, !hidden, animate)
        fade(b.caption, !hidden && caption.isNotEmpty() && !problem, animate)
    }

    private fun fade(v: View, show: Boolean, animate: Boolean) {
        v.animate().cancel()
        if (!animate) { v.alpha = 1f; v.isVisible = show; return }
        if (show) {
            if (!v.isVisible) { v.alpha = 0f; v.isVisible = true }
            v.animate().alpha(1f).setDuration(FADE_MS).start()
        } else if (v.isVisible) {
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction { v.isVisible = false; v.alpha = 1f }.start()
        }
    }

    /** Edge to edge: the photo fills the screen, the bars keep clear of the status bar, notch and gesture bar. */
    private fun fitBarsToInsets() {
        val top = b.topBar.paddingTop; val start = b.topBar.paddingStart; val end = b.topBar.paddingEnd
        val capBottom = b.caption.paddingBottom; val capStart = b.caption.paddingStart; val capEnd = b.caption.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { root, insets ->
            // Measured as if the bars were showing, so hiding them never makes anything jump.
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (rtl) bars.right else bars.left
            val endInset = if (rtl) bars.left else bars.right
            b.topBar.updatePaddingRelative(start = startInset + start, top = bars.top + top, end = endInset + end)
            b.caption.updatePaddingRelative(start = startInset + capStart, end = endInset + capEnd, bottom = bars.bottom + capBottom)
            b.problem.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun closeWithFade() {
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        finish()
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        private const val STATE_BARS_HIDDEN = "barsHidden"
        private const val FADE_MS = 160L
    }
}
