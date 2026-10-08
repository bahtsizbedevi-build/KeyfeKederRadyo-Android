package com.keyfekederradyo.android

import android.media.AudioFormat
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps the decoded PCM that ExoPlayer is about to play and turns it into [BANDS] log-spaced
 * frequency levels (0..1). No microphone or RECORD_AUDIO permission is involved: only the
 * app's own playback is analysed. Frames are time-stamped so the UI can show the frame that is
 * audible *now* (decoded audio reaches the speaker roughly [OUTPUT_LATENCY_MS] later).
 */
@OptIn(UnstableApi::class)
object SpectrumAnalyzer : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 48
    private const val FFT_SIZE = 2048
    private const val OUTPUT_LATENCY_MS = 280L
    private const val HISTORY = 96

    private var sampleRate = 44100
    private var channels = 2
    private var encoding = AudioFormat.ENCODING_PCM_16BIT

    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var sinceLast = 0
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private var bandEdges = IntArray(BANDS + 1)
    private var peak = 1e-3f

    // Ring of analysed frames: timestamps (uptime ms at which the frame becomes audible) + levels
    private val frameTimes = LongArray(HISTORY)
    private val frames = Array(HISTORY) { FloatArray(BANDS) }
    private var frameWrite = 0
    @Volatile private var lastAudioAt = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(this) {
            sampleRate = sampleRateHz.coerceAtLeast(8000)
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            ringPos = 0; sinceLast = 0
            ring.fill(0f)
            bandEdges = computeEdges(sampleRate)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val buf = buffer.duplicate().order(ByteOrder.nativeOrder())
        synchronized(this) {
            val hop = sampleRate / 60 // ~60 analyses per second
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> {
                    val shorts = buf.asShortBuffer()
                    while (shorts.remaining() >= channels) {
                        var sum = 0f
                        repeat(channels) { sum += shorts.get() / 32768f }
                        push(sum / channels, hop)
                    }
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val floats = buf.asFloatBuffer()
                    while (floats.remaining() >= channels) {
                        var sum = 0f
                        repeat(channels) { sum += floats.get() }
                        push(sum / channels, hop)
                    }
                }
                else -> return
            }
        }
    }

    private fun push(sample: Float, hop: Int) {
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % FFT_SIZE
        if (++sinceLast >= hop) {
            sinceLast = 0
            analyse()
        }
    }

    private fun analyse() {
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringPos + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val out = frames[frameWrite]
        var framePeak = 0f
        for (b in 0 until BANDS) {
            val from = bandEdges[b]; val to = max(bandEdges[b + 1], from + 1)
            var energy = 0f
            for (k in from until to) energy += re[k] * re[k] + im[k] * im[k]
            val mag = sqrt(energy / (to - from))
            // perceptual tilt: lift highs a little so the right side of the spectrum is not flat
            val tilt = 1f + 1.6f * b / BANDS
            out[b] = mag * tilt
            if (out[b] > framePeak) framePeak = out[b]
        }
        // automatic gain: follow the loudest band quickly up, slowly down
        peak = if (framePeak > peak) framePeak else peak * 0.995f + framePeak * 0.005f
        val ref = max(peak, 1e-3f)
        for (b in 0 until BANDS) {
            val db = 20f * log10(max(out[b] / ref, 1e-4f)) // -80..0
            out[b] = ((db + 48f) / 48f).coerceIn(0f, 1f).pow(1.35f)
        }
        val now = SystemClock.uptimeMillis()
        frameTimes[frameWrite] = now + OUTPUT_LATENCY_MS
        frameWrite = (frameWrite + 1) % HISTORY
        lastAudioAt = now
    }

    /** Copies the frame audible right now into [dest]; returns false when nothing is playing. */
    fun read(dest: FloatArray): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAudioAt > 600) return false
        synchronized(this) {
            var best = -1
            var bestTime = Long.MIN_VALUE
            for (i in 0 until HISTORY) {
                val t = frameTimes[i]
                if (t in (bestTime + 1)..now) { best = i; bestTime = t }
            }
            if (best < 0) return false
            System.arraycopy(frames[best], 0, dest, 0, minOf(dest.size, BANDS))
        }
        return true
    }

    private fun computeEdges(rate: Int): IntArray {
        val minF = 45.0; val maxF = minOf(16000.0, rate / 2.0 - 1)
        val binHz = rate.toDouble() / FFT_SIZE
        return IntArray(BANDS + 1) { i ->
            val f = minF * Math.exp(ln(maxF / minF) * i / BANDS)
            (f / binHz).toInt().coerceIn(1, FFT_SIZE / 2)
        }
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = x[i]; x[i] = x[j]; x[j] = t; t = y[i]; y[i] = y[j]; y[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = x[b] * cr - y[b] * ci
                    val ti = x[b] * ci + y[b] * cr
                    x[b] = x[a] - tr; y[b] = y[a] - ti
                    x[a] += tr; y[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
