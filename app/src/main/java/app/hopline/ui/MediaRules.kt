package app.hopline.ui

import app.hopline.core.Names
import java.util.Locale

/**
 * How a received file is shown and handed to other apps, as plain rules (tested on the JVM). The
 * name and the type a file came with are the sender's words, so neither is trusted:
 *  - the name is shown without control or direction-changing characters — "photo_‮gpj.apk"
 *    reads as the "photo_gpj.apk" it really is — and at a sane length that keeps its extension;
 *  - the type another app is told comes from that extension, out of a short list of kinds that
 *    open safely (pictures, sound, video, PDF, plain text, office documents). Anything else is
 *    "some bytes" (application/octet-stream). The type the sender gave is never passed on;
 *  - an app installer — by its name, or by the type it was sent as — is never opened or shared:
 *    the package installer would be one tap away. It can only be saved.
 * The name kept with the message never changes: the file on this phone is found by it.
 */
object MediaRules {
    /** "Some bytes": what another app is told about any file not on the list. */
    const val ANY = "application/octet-stream"
    /**
     * The longest name shown, the extension kept — counted the way a file name's length is (UTF-16
     * units: an emoji counts two), and no longer than BlobRules.displayName lets the copy handed
     * to another app be. That copy is then named exactly what [saveOnly] judged: a second cut,
     * made differently, could end a name in ".apk" that was judged as something else.
     */
    const val MAX_NAME = 100

    private const val INSTALLER_TYPE = "application/vnd.android.package-archive"
    private val INSTALLERS = setOf("apk", "apks", "apkm", "xapk")

    private val TYPES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac", "amr" to "audio/amr",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "pdf" to "application/pdf",
        "txt" to "text/plain", "csv" to "text/csv",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation", "rtf" to "application/rtf",
    )

    /**
     * [raw] as it may be shown, and given to another app as a file name: no control or
     * direction-changing characters, runs of spaces made one, and at most [MAX_NAME] long with the
     * extension kept. [fallback] when nothing is left.
     */
    fun shownName(raw: String, fallback: String = "file"): String {
        val whole = Names.clean(raw, raw.length.coerceAtLeast(1))
        if (whole.isEmpty()) return fallback
        if (whole.length <= MAX_NAME) return whole
        val dot = whole.lastIndexOf('.')
        val ext = if (dot > 0 && whole.length - dot in 2..10) whole.substring(dot) else ""
        var end = MAX_NAME - ext.length
        if (Character.isHighSurrogate(whole[end - 1])) end--   // never half an emoji
        return whole.substring(0, end).trimEnd() + ext
    }

    /** The extension of [name] as shown, in lower case ("" without one). Dots and spaces at the very end don't hide it. */
    fun extension(name: String): String {
        val n = shownName(name, "").trimEnd('.', ' ')
        val dot = n.lastIndexOf('.')
        return if (dot < 0) "" else n.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /** What another app is told [name] is: by its extension, from the list, or [ANY]. */
    fun typeFor(name: String): String = TYPES[extension(name)] ?: ANY

    /**
     * An app installer: by its name, or by the type its sender gave it ([sentType]). Never opened,
     * never shared — only saved, for someone who knows what they are doing with it.
     */
    fun saveOnly(name: String, sentType: String): Boolean =
        extension(name) in INSTALLERS || sentType.substringBefore(';').trim().lowercase(Locale.ROOT) == INSTALLER_TYPE

    /**
     * The broad kind another app may be asked for when nothing handles [type] exactly — any image
     * app for a picture, and so on; null for anything else. Never "any app at all".
     */
    fun broadType(type: String): String? = type.substringBefore('/').takeIf { it in BROAD }?.let { "$it/*" }

    private val BROAD = setOf("image", "audio", "video", "text")
}
