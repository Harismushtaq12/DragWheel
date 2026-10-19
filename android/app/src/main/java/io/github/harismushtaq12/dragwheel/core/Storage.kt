package io.github.harismushtaq12.dragwheel.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Files in and out.
 * Results are first written to a private workspace, so the user can look at them.
 * Only [publish] copies them to Downloads/DragWheel.
 */
object Storage {
    private const val FOLDER = "Download/DragWheel"

    @Volatile private var jobDir: File? = null

    fun describe(context: Context, uri: Uri): InputFile {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val typed = context.contentResolver.getType(uri)
        val mime = if (typed == null || typed == "application/octet-stream") mimeFor(finalName.substringAfterLast('.', "")) else typed
        return InputFile(uri, finalName, mime, size)
    }

    fun open(context: Context, file: InputFile): InputStream =
        context.contentResolver.openInputStream(file.uri) ?: throw IOException("Cannot open ${file.name}")

    /** Starts a new job: later [save] calls go into a fresh folder. */
    fun beginJob(context: Context): File {
        val root = File(context.cacheDir, "work")
        // Keep the workspace small: remove older jobs.
        root.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.deleteRecursively() }
        val dir = File(root, System.currentTimeMillis().toString())
        dir.mkdirs()
        jobDir = dir
        return dir
    }

    /** Writes a result into the workspace. */
    fun save(
        context: Context,
        name: String,
        mime: String = mimeFor(name.substringAfterLast('.', "")),
        subfolder: String? = null,
        write: (OutputStream) -> Unit,
    ): SavedFile {
        val base = jobDir ?: beginJob(context)
        val cleanSub = subfolder?.split('/')?.map(::safeName)?.filter { it.isNotBlank() && it != "." && it != ".." }
            ?.joinToString("/")?.takeIf { it.isNotBlank() }
        val dir = if (cleanSub == null) base else File(base, cleanSub)
        dir.mkdirs()
        val file = unique(dir, safeName(name))
        try {
            file.outputStream().use(write)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return SavedFile(uriFor(context, file), file.name, mime, file, cleanSub)
    }

    fun saveFile(context: Context, source: File, name: String, mime: String = mimeFor(name.substringAfterLast('.', ""))): SavedFile =
        save(context, name, mime) { out -> source.inputStream().use { it.copyTo(out) } }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** A private copy of an input, for tools that need a real file (PDF renderer). */
    fun localCopy(context: Context, file: InputFile): File {
        file.uri.path?.let { p -> if (file.uri.scheme == "file") return File(p) }
        val dir = File(context.cacheDir, "view").apply { mkdirs() }
        val key = (file.uri.toString().hashCode().toLong() and 0xffffffffL).toString(16)
        val target = File(dir, "$key.${file.ext.ifEmpty { "bin" }}")
        if (!target.exists() || target.length() == 0L) {
            val temp = File(dir, "$key.part")
            open(context, file).use { input -> temp.outputStream().use { input.copyTo(it) } }
            temp.renameTo(target)
        }
        return target
    }

    /** Copies results to Downloads/DragWheel. Returns the public copies. */
    fun publish(context: Context, outputs: List<SavedFile>): List<SavedFile> = outputs.map { out ->
        val source = out.file ?: throw IOException("Nothing to save for ${out.name}")
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, out.name)
            put(MediaStore.MediaColumns.MIME_TYPE, out.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (out.subfolder.isNullOrBlank()) FOLDER else "$FOLDER/${out.subfolder}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create ${out.name} in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { o -> source.inputStream().use { it.copyTo(o) } }
                ?: throw IOException("Could not write ${out.name}")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: out.name
        SavedFile(uri, finalName, out.mime, null, out.subfolder)
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }.take(150)

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }
}
