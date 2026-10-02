package app.hopline.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import app.hopline.service.Blobs
import app.hopline.service.Core
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Tiny image loader: background decode, memory cache, no libraries. Safe in a recycled list row:
 * a row never shows another message's picture, not even for a frame.
 */
object Images {
    private const val TAG = "Hopline/Images"
    /** Honest thumbnails are 48 px and ~2 KB of base64; anything far bigger is not one of ours. */
    private const val THUMB_MAX_B64 = 12_000
    private const val THUMB_MAX_DIM = 256
    /** One decode may hold at most this much pixel memory (a huge picture is sampled down further). */
    private const val MAX_DECODE_BYTES = 24L * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "hopline-images").apply { isDaemon = true } }

    /** An eighth of the app's memory, as Android suggests: more photos stay decoded on a roomy phone. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceIn(16L shl 20, 64L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** Inline thumbnails, keyed by their own text — two messages can never swap pictures. */
    private val thumbs = object : LruCache<String, Bitmap>(2 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount + key.length * 2
    }
    /** Pictures that can't be decoded at all, so a scrolling list doesn't retry them on every bind. */
    private val unreadable = LruCache<String, Boolean>(128)
    private val badThumbs = LruCache<String, Boolean>(128)

    init {
        // Hand the memory back when Hopline leaves the screen; the mesh service keeps running without it.
        Core.app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll()
                else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) cache.trimToSize(cache.maxSize() / 2)
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() { cache.evictAll() }
        })
    }

    /**
     * Load a file into a view, downscaled to roughly targetPx on the longest side. Until it is
     * ready the view shows [placeholder] (say, the message's own thumbnail) or nothing — never the
     * picture a recycled row showed before. [onResult] (main thread) says whether it could be shown;
     * it only runs while the view still wants this file.
     */
    fun load(file: File, view: ImageView, targetPx: Int = 720, placeholder: Bitmap? = null, onResult: ((Boolean) -> Unit)? = null) {
        val key = keyOf(file, targetPx)
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); onResult?.invoke(true); return }
        if (placeholder != null) view.setImageBitmap(placeholder) else view.setImageDrawable(null)
        if (unreadable.get(key) != null) { onResult?.invoke(false); return }
        val showing = view.drawable
        val ref = WeakReference(view)
        pool.execute {
            // Scrolled on to another photo while this waited its turn? Then don't spend the time.
            if (ref.get()?.tag != key) return@execute
            val bmp = cache.get(key) ?: decodeInternal(file, targetPx)?.also { if (it.byteCount <= cache.maxSize() / 3) cache.put(key, it) }
            main.post {
                val v = ref.get() ?: return@post
                // The row moved on: to another photo (the tag), or something else was drawn into it
                // (a still-arriving message's thumbnail) — either way this picture isn't wanted there.
                if (v.tag != key || v.drawable !== showing) return@post
                // A file that won't decode keeps its placeholder (the thumbnail, or the empty frame).
                if (bmp != null) v.setImageBitmap(bmp)
                onResult?.invoke(bmp != null)
            }
        }
    }

    /** Decode a picture file downscaled to about [targetPx]; null if it isn't one this phone can read. */
    fun decode(file: File, targetPx: Int): Bitmap? = decodeInternal(file, targetPx)

    /** The file's size and time are part of it: a re-assembled file is a new picture. */
    private fun keyOf(file: File, targetPx: Int) = "${file.absolutePath}@$targetPx:${file.lastModified()}:${file.length()}"

    private fun decodeInternal(file: File, targetPx: Int): Bitmap? {
        return try {
            decodeOrThrow(file, targetPx, 1).also { if (it == null) unreadable.put(keyOf(file, targetPx), true) }
        } catch (oom: OutOfMemoryError) {
            // Make room and try once at half the size — a softer photo beats a crash or a blank.
            cache.evictAll()
            try { decodeOrThrow(file, targetPx, 2) } catch (t: Throwable) { Log.w(TAG, "decode failed twice", t); null }
        } catch (t: Throwable) { Log.w(TAG, "decode failed", t); null }
    }

    private fun decodeOrThrow(file: File, targetPx: Int, extraSample: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        // Turning never changes the longest side, so the sample size is the same either way.
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= targetPx) sample *= 2
        while ((w.toLong() / sample) * (h.toLong() / sample) * 4 > MAX_DECODE_BYTES) sample *= 2
        sample *= extraSample
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val m = upright(file, bounds.outMimeType) ?: Matrix()
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > targetPx * 5 / 4) {
            // Sampling only halves; finish the job so the cache holds what the screen needs, not 4x that.
            val f = targetPx.toFloat() / longest
            m.preScale(maxOf(1, (bmp.width * f).toInt()).toFloat() / bmp.width, maxOf(1, (bmp.height * f).toInt()).toFloat() / bmp.height)
        }
        if (m.isIdentity) return bmp
        // Scale and turn in one pass, so a big photo never needs two extra bitmaps at once.
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * A photo sent as a file (or by an app that doesn't turn its pixels) keeps the camera's EXIF
     * "turn me" note; without it a portrait shot lies on its side in the chat and the viewer.
     */
    private fun upright(file: File, mime: String?): Matrix? {
        if (mime == null || !ExifInterface.isSupportedMimeType(mime)) return null
        return try { Blobs.uprightMatrix(ExifInterface(file.absolutePath)) } catch (e: Exception) { null }
    }

    /**
     * The tiny thumbnail baked into a file message. It arrives from other phones, so it is checked
     * before it is drawn: too long, too large in pixels, or not a picture at all, and it is ignored.
     * Cached, so a list rebinding a row doesn't decode it again on the main thread.
     */
    fun thumb(b64: String): Bitmap? {
        if (b64.isEmpty() || b64.length > THUMB_MAX_B64) return null
        thumbs.get(b64)?.let { return it }
        if (badThumbs.get(b64) != null) return null
        val bmp = try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || maxOf(bounds.outWidth, bounds.outHeight) > THUMB_MAX_DIM) null
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) { null }   // bad base64, a decoder fault, or no memory: just no thumbnail
        if (bmp != null) thumbs.put(b64, bmp) else badThumbs.put(b64, true)
        return bmp
    }
}
