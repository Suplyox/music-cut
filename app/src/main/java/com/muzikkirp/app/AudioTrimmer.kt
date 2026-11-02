package com.muzikkirp.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Herhangi bir ses dosyasının [startUs, endUs) aralığını kırpıp AAC (.m4a) olarak yazar.
 *
 * Yöntem iki aşamalıdır ve yalnızca Android'in yerleşik codec'lerini kullanır:
 *  1) Seçilen aralık çözülür (decode) ve geçici bir PCM dosyasına yazılır.
 *  2) PCM, AAC olarak kodlanıp MP4/M4A kapsayıcısına yazılır.
 */
object AudioTrimmer {

    private const val TIMEOUT_US = 10_000L
    private const val BITRATE = 192_000

    private class PcmInfo(val sampleRate: Int, val channels: Int, val bytes: Long)

    /** Başarılıysa true döner. [output] yazılabilir ve aranabilir (seekable) bir dosya tanıtıcısı olmalıdır. */
    fun trim(
        context: Context,
        input: Uri,
        startUs: Long,
        endUs: Long,
        output: FileDescriptor,
        bitrate: Int = BITRATE,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        require(startUs >= 0 && endUs > startUs)
        require(bitrate in setOf(128_000, 192_000, 256_000))
        val tmp = File.createTempFile("pcm", ".raw", context.cacheDir)
        return try {
            val info = decodeToPcm(context, input, startUs, endUs, tmp, onProgress) ?: return false
            if (info.bytes <= 0L) return false
            encodeToAac(tmp, info, output, bitrate, onProgress)
            onProgress(100)
            true
        } finally {
            tmp.delete()
        }
    }

    private fun decodeToPcm(
        context: Context,
        input: Uri,
        startUs: Long,
        endUs: Long,
        outFile: File,
        onProgress: (Int) -> Unit
    ): PcmInfo? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, input, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val d = MediaCodec.createDecoderByType(mime)
            decoder = d
            d.configure(format, null, null, 0)
            d.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var written = 0L

            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            FileOutputStream(outFile).use { out ->
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
                    if (!inputDone) {
                        val inIdx = d.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = d.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            val t = extractor.sampleTime
                            if (size < 0 || t > endUs) {
                                d.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                d.queueInputBuffer(inIdx, 0, size, t, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = d.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = d.outputFormat
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outIdx >= 0 -> {
                            if (bufInfo.size > 0) {
                                val bpf = 2 * channels // 16 bit PCM
                                val frames = bufInfo.size / bpf
                                val bufStartUs = bufInfo.presentationTimeUs

                                val firstFrame = if (startUs > bufStartUs) {
                                    ((startUs - bufStartUs) * sampleRate / 1_000_000L).toInt()
                                } else 0
                                val lastFrame = minOf(
                                    frames.toLong(),
                                    (endUs - bufStartUs) * sampleRate / 1_000_000L
                                ).toInt()

                                if (lastFrame > firstFrame) {
                                    val ob = d.getOutputBuffer(outIdx)!!
                                    ob.limit(bufInfo.offset + lastFrame * bpf)
                                    ob.position(bufInfo.offset + firstFrame * bpf)
                                    val chunk = ByteArray((lastFrame - firstFrame) * bpf)
                                    ob.get(chunk)
                                    out.write(chunk)
                                    written += chunk.size
                                    onProgress((((bufStartUs - startUs).coerceAtLeast(0) * 50) / (endUs - startUs)).toInt().coerceIn(0, 50))
                                }
                            }
                            d.releaseOutputBuffer(outIdx, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            return PcmInfo(sampleRate, channels, written)
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            decoder?.release()
            extractor.release()
        }
    }

    private fun encodeToAac(pcm: File, info: PcmInfo, output: FileDescriptor, bitrate: Int, onProgress: (Int) -> Unit) {
        val bpf = 2 * info.channels

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufInfo = MediaCodec.BufferInfo()
            val chunk = ByteArray(16384)
            var inputDone = false
            var outputDone = false
            var fedBytes = 0L

            FileInputStream(pcm).use { ins ->
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Cancelled")
                    if (!inputDone) {
                        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = encoder.getInputBuffer(inIdx)!!
                            buf.clear()
                            val maxBytes = (minOf(buf.capacity(), chunk.size) / bpf) * bpf
                            val n = readFully(ins, chunk, maxBytes)
                            val ptsUs = fedBytes / bpf * 1_000_000L / info.sampleRate
                            if (n <= 0) {
                                encoder.queueInputBuffer(
                                    inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                encoder.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                fedBytes += n
                                onProgress(50 + (fedBytes * 49 / info.bytes).toInt().coerceIn(0, 49))
                            }
                        }
                    }

                    val outIdx = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val ob = encoder.getOutputBuffer(outIdx)!!
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufInfo.size = 0
                            }
                            if (bufInfo.size > 0 && muxerStarted) {
                                ob.position(bufInfo.offset)
                                ob.limit(bufInfo.offset + bufInfo.size)
                                muxer.writeSampleData(track, ob, bufInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {
            }
            muxer.release()
        }
    }

    /** [maxBytes] dolana veya dosya bitene kadar okur; okunan bayt sayısını döner. */
    private fun readFully(ins: InputStream, buffer: ByteArray, maxBytes: Int): Int {
        var total = 0
        while (total < maxBytes) {
            val r = ins.read(buffer, total, maxBytes - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
