package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config

class MediaRecorderWrapper(val context: Context) : Recorder {
    private var noiseSuppressor: NoiseSuppressor? = null

    @Suppress("DEPRECATION")
    private var recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }.apply {
        setAudioSource(context.config.microphoneMode)
        setOutputFormat(context.config.getOutputFormat())
        setAudioEncoder(context.config.getAudioEncoder())
        setAudioEncodingBitRate(context.config.bitrate)
        setAudioSamplingRate(context.config.samplingRate)
    }

    override fun setOutputFile(path: String) {
        recorder.setOutputFile(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        val pFD = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
        recorder.setOutputFile(pFD.fileDescriptor)
    }

    override fun prepare() {
        recorder.prepare()
    }

    @SuppressLint("NewApi")
    private fun initNoiseSuppressor() {
        // NoiseSuppressor for MediaRecorder is only supported on Android 12 (S) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    val sessionId = recorder.activeRecordingConfiguration?.clientAudioSessionId ?: 0
                    if (sessionId != 0) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    override fun start() {
        recorder.start()
        initNoiseSuppressor()
    }

    override fun stop() {
        recorder.stop()
        releaseNoiseSuppressor()
    }

    @SuppressLint("NewApi")
    override fun pause() {
        recorder.pause()
    }

    @SuppressLint("NewApi")
    override fun resume() {
        recorder.resume()
    }

    override fun release() {
        releaseNoiseSuppressor()
        recorder.release()
    }

    private fun releaseNoiseSuppressor() {
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    override fun getMaxAmplitude(): Int {
        return recorder.maxAmplitude
    }
}
