package app.hopline.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.hopline.mesh.Attachment
import app.hopline.mesh.ChunkStore
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Everything about file bytes lives here, out of the router's way:
 *  - a disk-backed ChunkStore (pieces being carried for the group),
 *  - shrinking a photo until it is small enough to hop,
 *  - gluing arrived pieces back into a real file.
 * Layout: files/blobs/<groupFingerprint>/chunks/<envelopeId>.json and .../files/<fid>-<name>.
 */
object Blobs {
    private const val TAG = "Hopline/Blobs"
    const val MAX_IMAGE_BYTES = 330_000       // ~24 pieces; a photo should hop in seconds, not minutes
    private const val THUMB_DIM = 48

    fun groupDir(ctx: Context, fp: String): File = File(File(ctx.filesDir, "blobs"), fp)
    private fun chunksDir(ctx: Context, fp: String): File = File(groupDir(ctx, fp), "chunks").apply { mkdirs() }
    private fun filesDir(ctx: Context, fp: String): File = File(groupDir(ctx, fp), "files").apply { mkdirs() }

    /** Groups whose files were deleted; late assemble threads must not resurrect the directory. */
    private val closed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun deleteGroup(ctx: Context, fp: String) {
        closed.add(fp)
        Thread { groupDir(ctx, fp).deleteRecursively() }.start()
    }

    // ------------------------------------------------------------------ the on-disk chunk store

    /**
     * Thread-safe: the router uses it on the main thread while assemble workers read from it.
     * Each file's mtime is forced to the envelope's ts, so expiry follows the message's real age
     * even when a chunk is re-received on a later hop.
     */
    class DiskChunkStore(private val dir: File) : ChunkStore {
        private val index = HashSet<String>()
        init {
            dir.mkdirs()
            dir.listFiles()?.forEach { f -> if (f.name.endsWith(".json")) index.add(f.name.removeSuffix(".json")) }
        }
        private fun fileOf(id: String) = File(dir, "$id.json")

        @Synchronized override fun put(env: Envelope): Boolean = try {
            val f = fileOf(env.id)
            f.writeText(env.json.toString())
            f.setLastModified(env.ts)
            index.add(env.id)
            true
        } catch (e: Exception) { Log.w(TAG, "chunk write failed", e); false }

        @Synchronized override fun has(id: String): Boolean = id in index
        @Synchronized override fun ids(): List<String> = index.toList()
        @Synchronized override fun get(id: String): Envelope? = try {
            if (id in index) Envelope(JSONObject(fileOf(id).readText())) else null
        } catch (e: Exception) { null }
        @Synchronized override fun expire(before: Long) {
            for (id in index.toList()) {
                val f = fileOf(id)
                if (f.lastModified() < before) { f.delete(); index.remove(id) }
            }
        }
    }

    fun chunkStore(ctx: Context, fp: String): DiskChunkStore {
        closed.remove(fp)   // rejoining a group reopens its blob space
        return DiskChunkStore(chunksDir(ctx, fp))
    }

    // ------------------------------------------------------------------ assembled files

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "file" }

    fun fileFor(ctx: Context, fp: String, att: Attachment): File =
        File(filesDir(ctx, fp), "${att.fid}-${safeName(att.name)}")

    /** Glue the pieces of one message's attachment into a real file. Returns true when it is on disk. */
    fun assemble(ctx: Context, fp: String, router: Router, m: Message): Boolean {
        if (fp in closed) return false
        val att = m.att ?: return false
        val out = fileFor(ctx, fp, att)
        if (out.exists()) return true
        if (!router.fileComplete(att)) return false
        return try {
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { os ->
                for (i in 0 until att.chunks) {
                    val env = router.chunks.get(Envelope.chunkId(att.fid, i)) ?: return false
                    os.write(Base64.decode(env.payload.getString("d"), Base64.NO_WRAP))
                }
            }
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
            true
        } catch (e: Exception) { Log.w(TAG, "assemble failed", e); false }
    }

    /** My own send: the bytes are already here — write the file directly so it shows instantly. */
    fun saveOwn(ctx: Context, fp: String, att: Attachment, bytes: ByteArray) {
        if (fp in closed) return
        try {
            val out = fileFor(ctx, fp, att)
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        } catch (e: Exception) { Log.w(TAG, "saveOwn failed", e) }
    }

    /** Cut bytes into base64 pieces sized for the radio. */
    fun chunkify(bytes: ByteArray): List<String> {
        val out = ArrayList<String>((bytes.size + Router.CHUNK_RAW - 1) / Router.CHUNK_RAW)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + Router.CHUNK_RAW)
            out.add(Base64.encodeToString(bytes, i, end - i, Base64.NO_WRAP))
            i = end
        }
        return out
    }

    // ------------------------------------------------------------------ images

    class Prepared(val bytes: ByteArray, val name: String, val mime: String, val width: Int, val height: Int, val thumbB64: String)

    /**
     * Shrink a picked photo until it hops well: longest side ≤1280, JPEG, ≤ MAX_IMAGE_BYTES.
     * Also bakes a tiny thumbnail into the message itself, so receivers see something at once.
     */
    fun prepareImage(ctx: Context, uri: Uri): Prepared? {
        return try {
            val src = decodeScaled(ctx, uri, 1280) ?: return null
            var quality = 78
            var bmp = src
            var bytes = jpeg(bmp, quality)
            while (bytes.size > MAX_IMAGE_BYTES && quality > 40) { quality -= 12; bytes = jpeg(bmp, quality) }
            if (bytes.size > MAX_IMAGE_BYTES) {
                bmp = scaleDown(bmp, 960); bytes = jpeg(bmp, 60)
            }
            if (bytes.size > MAX_IMAGE_BYTES) {
                bmp = scaleDown(bmp, 720); bytes = jpeg(bmp, 55)
            }
            val thumb = scaleDown(bmp, THUMB_DIM)
            val thumbB64 = Base64.encodeToString(jpeg(thumb, 45), Base64.NO_WRAP)
            Prepared(bytes, "photo-${System.currentTimeMillis() / 1000}.jpg", "image/jpeg", bmp.width, bmp.height, thumbB64)
        } catch (e: Exception) { Log.w(TAG, "prepareImage failed", e); null }
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun scaleDown(b: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxDim) return b
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }

    /** Decode with subsampling (no full-size bitmap in memory) and honour EXIF rotation. */
    private fun decodeScaled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null by design with inJustDecodeBounds — only the stream can be the failure here
        val boundsStream = cr.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        bmp = scaleDown(bmp, maxDim)
        val rotation = try {
            cr.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    // ------------------------------------------------------------------ arbitrary files

    class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

    /** Read a picked document. Returns null if it can't be read or is over the mesh's limit. */
    fun readPicked(ctx: Context, uri: Uri): PickedFile? {
        return try {
            val cr = ctx.contentResolver
            var name = "file"
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: name
            }
            val bytes = cr.openInputStream(uri)?.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Router.MAX_FILE) return null
                }
                out.toByteArray()
            } ?: return null
            PickedFile(bytes, name, cr.getType(uri) ?: "application/octet-stream")
        } catch (e: Exception) { Log.w(TAG, "readPicked failed", e); null }
    }

    fun prettySize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
