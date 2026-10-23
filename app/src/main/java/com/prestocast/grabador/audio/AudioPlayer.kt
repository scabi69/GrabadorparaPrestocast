package com.prestocast.grabador.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import java.io.File

class AudioPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var onCompletionListener: (() -> Unit)? = null

    fun play(file: File, onCompletion: (() -> Unit)? = null) {
        stop()
        onCompletionListener = onCompletion
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                setOnCompletionListener {
                    stop()
                    onCompletionListener?.invoke()
                }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        try {
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.seekTo(positionMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
