package com.prestocast.grabador.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object Mp3Transcoder {

    suspend fun convertM4aToMp3(
        inputFile: File,
        outputFile: File,
        targetBitrateKbps: Int = 128,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var androidLame: AndroidLame? = null
        var fos: FileOutputStream? = null

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(inputFile.absolutePath)

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val detectedBitrateKbps = bitrateStr?.toIntOrNull()?.let { (it / 1000).coerceIn(64, 320) } ?: targetBitrateKbps

            retriever.release()

            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                return@withContext Result.failure(Exception("No se encontró pista de audio válida"))
            }

            extractor.selectTrack(trackIndex)

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            fos = FileOutputStream(outputFile)

            androidLame = LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutSampleRate(sampleRate)
                .setOutChannels(channels)
                .setOutBitrate(detectedBitrateKbps)
                .setQuality(5)
                .build()

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false

            val kTimeoutUs = 5000L
            val mp3Buf = ByteArray(8192)

            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inputBufIndex = decoder.dequeueInputBuffer(kTimeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()

                                if (durationMs > 0) {
                                    val percent = ((sampleTimeUs / 1000) * 100 / durationMs).toInt().coerceIn(0, 99)
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, kTimeoutUs)
                if (outputBufIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val pcmShorts = ShortArray(bufferInfo.size / 2)
                        outputBuffer.asShortBuffer().get(pcmShorts)

                        val bytesEncoded = if (channels == 2) {
                            val left = ShortArray(pcmShorts.size / 2)
                            val right = ShortArray(pcmShorts.size / 2)
                            for (i in 0 until pcmShorts.size / 2) {
                                left[i] = pcmShorts[i * 2]
                                right[i] = pcmShorts[i * 2 + 1]
                            }
                            androidLame.encode(left, right, pcmShorts.size / 2, mp3Buf)
                        } else {
                            androidLame.encode(pcmShorts, pcmShorts, pcmShorts.size, mp3Buf)
                        }

                        if (bytesEncoded > 0) {
                            fos.write(mp3Buf, 0, bytesEncoded)
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                }
            }

            val flushBytes = androidLame.flush(mp3Buf)
            if (flushBytes > 0) {
                fos.write(mp3Buf, 0, flushBytes)
            }

            AudioTrimmer.stripMp3TagsInPlace(outputFile)

            onProgress(100)
            Result.success(outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try { androidLame?.close() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
        }
    }
}
