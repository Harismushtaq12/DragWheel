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
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.github.harismushtaq12.dragwheel.core.FileInfo
import io.github.harismushtaq12.dragwheel.core.InputFile
import io.github.harismushtaq12.dragwheel.core.SavedFile
import io.github.harismushtaq12.dragwheel.core.Storage
import io.github.harismushtaq12.dragwheel.core.TimeRange
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        clip: TimeRange? = null,
        videoBitrate: Int? = null,
    ): SavedFile {
        val temp = File(context.cacheDir, "export-${System.nanoTime()}.mp4")
        try {
            // Transformer must be created and driven from the main thread.
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val builder = Transformer.Builder(context)
                    if (!removeVideo) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    if (!removeAudio) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    if (videoBitrate != null) {
                        // Asking for a bitrate also forces a real re-encode, so the file really gets smaller.
                        builder.setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                                .build()
                        )
                    }
                    val transformer = builder.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    }).build()

                    val mediaItem = MediaItem.Builder().setUri(file.uri).apply {
                        if (clip != null) setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs)
                                .setEndPositionMs(clip.endMs)
                                .build()
                        )
                    }.build()

                    val item = EditedMediaItem.Builder(mediaItem)
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

    /**
     * Re-encodes at a lower bitrate and scales the short side down to [size] (480, 720, 1080), never up.
     * Works for both wide and tall (phone) videos.
     */
    suspend fun compress(context: Context, file: InputFile, size: Int): SavedFile {
        val i = info(context, file)
        val w = i.width ?: 0
        val h = i.height ?: 0
        val effects = mutableListOf<Effect>()
        if (w > 0 && h > 0 && minOf(w, h) > size) {
            effects += if (w >= h) Presentation.createForHeight(size)
            else Presentation.createForWidthAndHeight(size, (size.toLong() * h / w).toInt(), Presentation.LAYOUT_SCALE_TO_FIT)
        }
        val bitrate = when {
            size >= 1080 -> 5_000_000
            size >= 720 -> 2_500_000
            else -> 1_200_000
        }
        return export(context, file, "${file.baseName} ${size}p.mp4", videoEffects = effects, videoBitrate = bitrate)
    }

    suspend fun mute(context: Context, file: InputFile) =
        export(context, file, "${file.baseName} muted.mp4", removeAudio = true)

    suspend fun rotate(context: Context, file: InputFile) =
        export(
            context, file, "${file.baseName} rotated.mp4",
            // Media3 turns counter-clockwise, so 270 is 90° to the right.
            videoEffects = listOf(ScaleAndRotateTransformation.Builder().setRotationDegrees(270f).build()),
        )

    /** Audio from a video, or any audio file, as AAC in an .m4a file. */
    suspend fun audioOnly(context: Context, file: InputFile) =
        export(context, file, "${file.baseName}.m4a", removeVideo = true)

    /** Keeps only the part between start and end. */
    suspend fun trim(context: Context, file: InputFile, range: TimeRange, isAudio: Boolean): SavedFile {
        require(range.endMs - range.startMs >= 500) { "Choose at least half a second." }
        return if (isAudio) export(context, file, "${file.baseName} trimmed.m4a", removeVideo = true, clip = range)
        else export(context, file, "${file.baseName} trimmed.mp4", clip = range)
    }

    fun firstFrame(context: Context, file: InputFile, atMs: Long = 0): SavedFile {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, file.uri)
            val frame: Bitmap = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Could not read a picture from this video.")
            return Storage.save(context, "${file.baseName} frame.png", "image/png") {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            retriever.release()
        }
    }

    /** Uncompressed WAV sound, which every audio program can open and edit. */
    suspend fun toWav(context: Context, file: InputFile): SavedFile {
        val extractor = android.media.MediaExtractor()
        val temp = File(context.cacheDir, "wav-${System.nanoTime()}.wav")
        try {
            extractor.setDataSource(context, file.uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("There is no sound in this file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var isFloat = false
            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var total = 0L
            try {
                java.io.RandomAccessFile(temp, "rw").use { raf ->
                    raf.write(ByteArray(44))
                    val info = android.media.MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (!inputDone) {
                            val i = codec.dequeueInputBuffer(10_000)
                            if (i >= 0) {
                                val buf = codec.getInputBuffer(i)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val o = codec.dequeueOutputBuffer(info, 10_000)
                        if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = codec.outputFormat
                            channels = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                            rate = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                                isFloat = of.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                            }
                        } else if (o >= 0) {
                            val ob = codec.getOutputBuffer(o)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bytes = if (isFloat) {
                                val fb = ob.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                                val outB = java.nio.ByteBuffer.allocate(fb.remaining() * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                while (fb.hasRemaining()) outB.putShort((fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                outB.array()
                            } else ByteArray(info.size).also { ob.get(it) }
                            raf.write(bytes)
                            total += bytes.size
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (total > 2_000_000_000L) throw IllegalStateException("The sound is too long for one WAV file.")
                        }
                    }
                    val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    h.put("RIFF".toByteArray()).putInt((36 + total).toInt()).put("WAVE".toByteArray())
                    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
                        .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
                    h.put("data".toByteArray()).putInt(total.toInt())
                    raf.seek(0)
                    raf.write(h.array())
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return Storage.saveFile(context, temp, "${file.baseName}.wav", "audio/wav")
        } finally {
            extractor.release()
            temp.delete()
        }
    }

    /** A picture every few seconds. */
    suspend fun frames(context: Context, file: InputFile, everySeconds: Int): List<SavedFile> {
        val length = info(context, file).durationMs ?: throw IllegalStateException("Could not read the length of this video.")
        val step = everySeconds.coerceAtLeast(1) * 1000L
        val times = generateSequence(0L) { it + step }.takeWhile { it < length }.take(120).toList()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, file.uri)
            return times.mapIndexed { i, t ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val frame = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1920)
                    ?: throw IOException("Could not read a picture at ${t / 1000} s.")
                Storage.save(context, "frame ${(i + 1).toString().padStart(3, '0')}.jpg", "image/jpeg", subfolder = "${file.baseName} frames") {
                    frame.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }.also { frame.recycle() }
            }
        } finally {
            r.release()
        }
    }

    /** Length and size as shown, taking phone rotation into account. */
    fun info(context: Context, file: InputFile): FileInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, file.uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val turned = rot == 90 || rot == 270
            FileInfo(size = file.size, durationMs = d, width = if (turned) h else w, height = if (turned) w else h)
        } catch (e: Exception) {
            FileInfo(size = file.size)
        } finally {
            r.release()
        }
    }
}
