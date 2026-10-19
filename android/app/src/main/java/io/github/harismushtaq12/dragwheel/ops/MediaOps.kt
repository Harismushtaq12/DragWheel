@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.harismushtaq12.dragwheel.ops

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Video and audio with Media3 Transformer, which uses the phone's hardware encoders. */
object MediaOps {

    private suspend fun export(
        context: Context,
        file: InputFile,
        outName: String,
        removeAudio: Boolean = false,
        removeVideo: Boolean = false,
        videoEffects: List<Effect> = emptyList(),
    ): SavedFile {
        val temp = File(context.cacheDir, "export-${System.nanoTime()}.mp4")
        try {
            // Transformer must be created and driven from the main thread.
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val builder = Transformer.Builder(context)
                    if (!removeVideo) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    if (!removeAudio) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    val transformer = builder.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    }).build()

                    val item = EditedMediaItem.Builder(MediaItem.fromUri(file.uri))
                        .setRemoveAudio(removeAudio)
                        .setRemoveVideo(removeVideo)
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()
                    transformer.start(item, temp.absolutePath)
                    cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                }
            }
            val mime = if (removeVideo) "audio/mp4" else "video/mp4"
            return Storage.saveFile(context, temp, outName, mime)
        } finally {
            temp.delete()
        }
    }

    suspend fun toMp4(context: Context, file: InputFile) =
        export(context, file, "${file.baseName}.mp4")

    suspend fun compress(context: Context, file: InputFile) =
        export(context, file, "${file.baseName} compressed.mp4", videoEffects = listOf(Presentation.createForHeight(720)))

    suspend fun mute(context: Context, file: InputFile) =
        export(context, file, "${file.baseName} muted.mp4", removeAudio = true)

    suspend fun rotate(context: Context, file: InputFile) =
        export(
            context, file, "${file.baseName} rotated.mp4",
            videoEffects = listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(90f).build()),
        )

    /** Audio from a video, or any audio file, as AAC in an .m4a file. */
    suspend fun audioOnly(context: Context, file: InputFile) =
        export(context, file, "${file.baseName}.m4a", removeVideo = true)

    fun firstFrame(context: Context, file: InputFile): SavedFile {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            val frame: Bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Could not read a frame from this video.")
            return Storage.save(context, "${file.baseName} frame.png", "image/png") {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            retriever.release()
        }
    }
}
