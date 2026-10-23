package com.prestocast.grabador.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

enum class TrimMode {
    TRIM_END,   // Cortar al final (desechar la cola)
    TRIM_START, // Cortar al inicio (desechar el inicio)
    NO_TRIM     // Sin recortar
}

object AudioTrimmer {

    fun getAudioDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Ignorar
            }
        }
    }

    suspend fun trimAudioToTargetDuration(
        originalFile: File,
        processedFile: File,
        outputFile: File,
        trimMode: TrimMode = TrimMode.TRIM_END
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (trimMode == TrimMode.NO_TRIM) {
                processedFile.copyTo(outputFile, overwrite = true)
                stripMp3TagsInPlace(outputFile)
                return@withContext Result.success(outputFile)
            }

            val originalDurationMs = getAudioDurationMs(originalFile)
            val processedDurationMs = getAudioDurationMs(processedFile)

            if (originalDurationMs <= 0) {
                processedFile.copyTo(outputFile, overwrite = true)
                stripMp3TagsInPlace(outputFile)
                return@withContext Result.success(outputFile)
            }

            val targetDurationUs = originalDurationMs * 1000L
            val processedDurationUs = processedDurationMs * 1000L

            val extractor = MediaExtractor()
            extractor.setDataSource(processedFile.absolutePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if ((audioTrackIndex < 0) || (format == null)) {
                extractor.release()
                processedFile.copyTo(outputFile, overwrite = true)
                stripMp3TagsInPlace(outputFile)
                return@withContext Result.success(outputFile)
            }

            extractor.selectTrack(audioTrackIndex)

            val maxBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                128 * 1024
            }

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val fos = FileOutputStream(outputFile)

            try {
                if (trimMode == TrimMode.TRIM_START) {
                    val startOffsetUs = (processedDurationUs - targetDurationUs).coerceAtLeast(0L)
                    if (startOffsetUs > 0) {
                        extractor.seekTo(startOffsetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    }

                    val endCutTimeUs = startOffsetUs + targetDurationUs
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val currentSampleTimeUs = extractor.sampleTime
                        if (currentSampleTimeUs > endCutTimeUs) break

                        val bytes = ByteArray(sampleSize)
                        buffer.get(bytes)
                        fos.write(bytes)
                        buffer.clear()

                        extractor.advance()
                    }
                } else {
                    // TRIM_END
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val currentSampleTimeUs = extractor.sampleTime
                        if (currentSampleTimeUs > targetDurationUs) break

                        val bytes = ByteArray(sampleSize)
                        buffer.get(bytes)
                        fos.write(bytes)
                        buffer.clear()

                        extractor.advance()
                    }
                }
            } finally {
                fos.flush()
                fos.close()
                extractor.release()
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                processedFile.copyTo(outputFile, overwrite = true)
            }

            stripMp3TagsInPlace(outputFile)
            Result.success(outputFile)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                processedFile.copyTo(outputFile, overwrite = true)
                stripMp3TagsInPlace(outputFile)
                Result.success(outputFile)
            } catch (_: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Elimina cabeceras y etiquetas ID3v2, ID3v1 y APEv2 del archivo MP3 para dejar únicamente
     * los frames de audio MPEG limpios.
     */
    fun stripMp3TagsInPlace(file: File) {
        try {
            if (!file.exists() || file.length() < 128) return

            val bytes = file.readBytes()
            var startOffset = 0
            var endOffset = bytes.size

            // 1. Detectar cabecera ID3v2 explícita ("ID3" en los primeros 3 bytes)
            if (bytes.size >= 10 &&
                bytes[0] == 'I'.code.toByte() &&
                bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte()
            ) {
                val flags = bytes[5].toInt() and 0xFF
                val hasFooter = (flags and 0x10) != 0
                val s0 = bytes[6].toInt() and 0x7F
                val s1 = bytes[7].toInt() and 0x7F
                val s2 = bytes[8].toInt() and 0x7F
                val s3 = bytes[9].toInt() and 0x7F
                val tagBodySize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                val id3v2Size = 10 + tagBodySize + (if (hasFooter) 10 else 0)

                if (id3v2Size in 10..bytes.size) {
                    startOffset = id3v2Size
                }
            }

            // Omitir bytes nulos o basura inicial hasta encontrar el primer Sync Word MPEG (0xFF 0xE0..0xFF)
            while (startOffset < bytes.size - 3) {
                val b0 = bytes[startOffset].toInt() and 0xFF
                val b1 = bytes[startOffset + 1].toInt() and 0xFF

                // Sync word de MP3: 11 bits en 1 (0xFF seguido de máscara 0xE0)
                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val layerBits = (b1 shr 1) and 0x03
                    val versionBits = (b1 shr 3) and 0x03
                    // Versión MPEG válida (distinta de 01) y Capa válida (distinta de 00)
                    if (versionBits != 1 && layerBits != 0) {
                        break // Primer frame de audio MPEG válido
                    }
                }
                startOffset++
            }

            // 2. Eliminar etiqueta ID3v1 al final (últimos 128 bytes que comienzan con "TAG")
            if (endOffset - startOffset >= 128) {
                val tagIdx = endOffset - 128
                if (bytes[tagIdx] == 'T'.code.toByte() &&
                    bytes[tagIdx + 1] == 'A'.code.toByte() &&
                    bytes[tagIdx + 2] == 'G'.code.toByte()
                ) {
                    endOffset -= 128
                }
            }

            // 3. Eliminar etiqueta APEv2 al final (32 bytes que terminan/empiezan con "APETAGEX")
            if (endOffset - startOffset >= 32) {
                val apeIdx = endOffset - 32
                val apeHeader = String(bytes, apeIdx, 8, Charsets.US_ASCII)
                if (apeHeader == "APETAGEX") {
                    endOffset -= 32
                }
            }

            // Reescribir archivo si se eliminaron metadatos
            if (startOffset > 0 || endOffset < bytes.size) {
                val cleanSize = (endOffset - startOffset).coerceAtLeast(0)
                val cleanBytes = ByteArray(cleanSize)
                System.arraycopy(bytes, startOffset, cleanBytes, 0, cleanSize)
                file.writeBytes(cleanBytes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
