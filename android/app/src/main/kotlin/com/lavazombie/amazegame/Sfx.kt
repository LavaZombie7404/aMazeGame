package com.lavazombie.amazegame

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny procedural-audio helper — synthesises 16-bit PCM directly into an
 * AudioTrack so we don't have to ship WAV resources. Matches the chime +
 * whoosh sounds the web side plays via Web Audio API.
 *
 * Each playback spawns a short-lived thread that builds the buffer and
 * writes it to a one-shot AudioTrack; the SFX are <500 ms so there's no
 * lifecycle to manage and no need for SoundPool.
 */
object Sfx {
    private const val SAMPLE_RATE = 44100

    fun playGoalChime() {
        playInBackground { buildGoalChime() }
    }

    fun playWhoosh() {
        playInBackground { buildWhoosh() }
    }

    private fun playInBackground(build: () -> ShortArray) {
        thread(isDaemon = true) {
            try {
                val buffer = build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                track.play()
                // Static-mode tracks free themselves once playback finishes,
                // but release explicitly after the buffer's worth of silence
                // to avoid keeping the audio hardware claimed.
                Thread.sleep(((buffer.size * 1000L) / SAMPLE_RATE) + 60L)
                track.release()
            } catch (_: Throwable) {
                // Audio is best-effort — silently swallow failures so the
                // game keeps running on devices without working audio.
            }
        }
    }

    /** 3-note arpeggio C5 · E5 · G5, sine-wave with a quick attack/decay. */
    private fun buildGoalChime(): ShortArray {
        val notes = floatArrayOf(523.25f, 659.25f, 783.99f)
        val noteSpacing = 0.09f
        val noteTail = 0.45f
        val totalSec = (notes.size - 1) * noteSpacing + noteTail
        val total = (SAMPLE_RATE * totalSec).toInt()
        val buf = ShortArray(total)
        for ((i, freq) in notes.withIndex()) {
            val startSec = i * noteSpacing
            mixSine(buf, startSec, noteTail, freq, 0.28f, attackSec = 0.02f)
        }
        return buf
    }

    /** White-noise burst with an exponential decay envelope. */
    private fun buildWhoosh(): ShortArray {
        val duration = 0.3f
        val total = (SAMPLE_RATE * duration).toInt()
        val buf = ShortArray(total)
        for (i in 0 until total) {
            val t = i.toFloat() / total
            val env = exp(-t * 2.5f)
            val noise = (Math.random().toFloat() * 2f - 1f) * env * 0.18f
            buf[i] = (noise * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    /**
     * Add a sine-wave note into the buffer starting at `startSec`, lasting
     * `lenSec`. Uses a linear attack followed by an exponential decay so the
     * note has a soft envelope and doesn't click on note start.
     */
    private fun mixSine(
        buf: ShortArray,
        startSec: Float,
        lenSec: Float,
        freq: Float,
        amp: Float,
        attackSec: Float,
    ) {
        val startIdx = (startSec * SAMPLE_RATE).toInt()
        val len = (lenSec * SAMPLE_RATE).toInt()
        val attackLen = (attackSec * SAMPLE_RATE).toInt()
        for (i in 0 until len) {
            val idx = startIdx + i
            if (idx >= buf.size) break
            val t = i.toFloat() / SAMPLE_RATE
            val envAttack = if (i < attackLen) i.toFloat() / attackLen else 1f
            val envDecay = exp(-t * 6f) // exponential decay
            val env = envAttack * envDecay
            val sample = sin(2 * PI * freq * t).toFloat() * amp * env
            val mixed = (buf[idx].toInt() + (sample * Short.MAX_VALUE).toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf[idx] = mixed.toShort()
        }
    }
}
