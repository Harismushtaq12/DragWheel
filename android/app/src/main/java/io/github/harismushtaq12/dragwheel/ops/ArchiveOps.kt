package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ArchiveOps {

    fun zip(context: Context, files: List<InputFile>): SavedFile {
        val suffix = if (files.size > 1) " (+${files.size - 1})" else ""
        return Storage.save(context, "${files[0].baseName}$suffix.zip", "application/zip") { out ->
            ZipOutputStream(out).use { zip ->
                val used = mutableSetOf<String>()
                for (f in files) {
                    var name = f.name
                    var n = 2
                    while (!used.add(name)) name = "${f.baseName} ($n).${f.ext}".also { n++ }
                    zip.putNextEntry(ZipEntry(name))
                    Storage.open(context, f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Extracts into Downloads/DragWheel/<zip name>/, keeping folders. */
    fun unzip(context: Context, file: InputFile): List<SavedFile> {
        val saved = mutableListOf<SavedFile>()
        Storage.open(context, file).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = try {
                        zip.nextEntry
                    } catch (e: ZipException) {
                        // Android 14+ refuses entries like "../x". Keep what we already extracted.
                        if (saved.isEmpty()) throw IllegalStateException("This zip file contains unsafe paths and was not extracted.")
                        break
                    } ?: break
                    if (entry.isDirectory) continue
                    // Never allow "../" tricks: keep only safe path parts.
                    val parts = entry.name.replace('\\', '/').split('/')
                        .filter { it.isNotBlank() && it != "." && it != ".." }
                    if (parts.isEmpty()) continue
                    val sub = (listOf(file.baseName) + parts.dropLast(1)).joinToString("/")
                    saved += Storage.save(context, parts.last(), subfolder = sub) { out -> zip.copyTo(out) }
                }
            }
        }
        if (saved.isEmpty()) throw IllegalStateException("This zip file is empty.")
        return saved
    }

    fun sha256(context: Context, file: InputFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Storage.open(context, file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
