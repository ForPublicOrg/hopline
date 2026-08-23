package app.hopline.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Tiny image loader: background decode, memory cache, no libraries. */
object Images {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Load a file into a view, downscaled to roughly targetPx on the longest side. */
    fun load(file: File, view: ImageView, targetPx: Int = 720) {
        val key = "${file.absolutePath}@$targetPx:${file.lastModified()}"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bmp = decode(file, targetPx) ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    fun decode(file: File, targetPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    /** The tiny thumbnail baked into a file message, decoded inline (it is ~1 KB). */
    fun thumb(b64: String): Bitmap? = try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) { null }
}
