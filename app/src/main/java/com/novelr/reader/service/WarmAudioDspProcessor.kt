package com.novelr.reader.service

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Bộ xử lý âm thanh chuẩn Phòng thu Sách nói (Studio Audiobook Mastering DSP & Dynamic De-Esser):
 * - Giữ nguyên 100% cao độ (Pitch = 100% nguyên bản) và độ mở dải tần cao (Air band 8kHz–12kHz).
 * - Tái tạo chất âm dày ấm, trong trẻo bằng chuỗi Biquad Studio 3 dải tần:
 *   1. **Low-Mid Warmth Boost (165 Hz, +1.8 dB)**: Tăng độ dày và độ ấm lồng ngực (chest resonance).
 *   2. **Vocal Presence & Clarity (2.650 Hz, +1.4 dB)**: Tăng độ rõ chữ và độ sắc nét của thanh điệu Tiếng Việt.
 *   3. **Dynamic Split-Band De-Esser (4.800 Hz)**: Chỉ làm mềm khi có gai âm xuýt mạnh (s, x, ch, tr),
 *      giữ nguyên 100% độ sáng trong trẻo (unity gain 1.0f) ở các âm tiết bình thường.
 */
class WarmAudioDspProcessor(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "WarmAudioDsp"

        private class BiquadFilter(
            sr: Double,
            f0: Double,
            q: Double,
            dbGain: Double
        ) {
            private val nb0: Float
            private val nb1: Float
            private val nb2: Float
            private val na1: Float
            private val na2: Float

            private var x1 = 0f
            private var x2 = 0f
            private var y1 = 0f
            private var y2 = 0f

            init {
                val a = 10.0.pow(dbGain / 40.0)
                val w0 = 2.0 * PI * f0 / sr
                val cosW0 = cos(w0)
                val alpha = sin(w0) / (2.0 * q)

                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosW0
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cosW0
                val a2 = 1.0 - alpha / a

                nb0 = (b0 / a0).toFloat()
                nb1 = (b1 / a0).toFloat()
                nb2 = (b2 / a0).toFloat()
                na1 = (a1 / a0).toFloat()
                na2 = (a2 / a0).toFloat()
            }

            fun process(input: Float): Float {
                val out = nb0 * input + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
                x2 = x1
                x1 = input
                y2 = y1
                y1 = out
                return out
            }
        }

        /**
         * Xử lý PCM 24kHz với chuỗi Studio Mastering EQ (165Hz Warmth + 2.65kHz Clarity + 4.8kHz Dynamic De-Esser):
         * - Không làm đục dải tần cao (giữ nguyên hfGain = 1.0f khi không có âm xuýt gắt).
         * - Nâng biên độ chuẩn đầu ra lên 32000f kèm Soft-Knee Mastering Limiter giúp âm thanh to, rõ, tròn tiếng.
         */
        fun processPcm24kHz(
            wav: FloatArray,
            startIdx: Int,
            voiceLength: Int,
            fadeInSamples: Int,
            fadeOutSamples: Int,
            leadInSamples: Int,
            totalLength: Int,
            warmEnabled: Boolean
        ): ShortArray {
            val pcm = ShortArray(totalLength)
            if (voiceLength <= 0) return pcm

            if (!warmEnabled) {
                for (i in 0 until voiceLength) {
                    var sample = wav[startIdx + i]
                    sample = applyFadeEnvelope(sample, i, voiceLength, fadeInSamples, fadeOutSamples)
                    pcm[leadInSamples + i] = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                return pcm
            }

            val sr = 24000.0
            // 1. Tăng độ trầm ấm lồng ngực (165 Hz, +1.8 dB)
            val warmthFilter = BiquadFilter(sr = sr, f0 = 165.0, q = 0.85, dbGain = 1.8)
            // 2. Tăng độ rõ chữ & độ nét nguyên âm Tiếng Việt (2650 Hz, +1.4 dB)
            val clarityFilter = BiquadFilter(sr = sr, f0 = 2650.0, q = 1.10, dbGain = 1.4)
            // 3. Làm dịu nhẹ đỉnh cộng hưởng gắt của loa điện thoại (4800 Hz, -1.8 dB)
            val harshNotchFilter = BiquadFilter(sr = sr, f0 = 4800.0, q = 1.40, dbGain = -1.8)

            // Bộ tách tần ở 3800 Hz để chỉ nén động khi âm gió (s, x, ch) vượt ngưỡng cao
            var lpState = 0f
            var hfEnvelope = 0f
            val lpCoeff = 0.37f
            val sibilanceThreshold = 0.11f

            for (i in 0 until voiceLength) {
                val raw = wav[startIdx + i]

                // Chuỗi 3 bộ lọc Biquad chuẩn phòng thu
                val s1 = warmthFilter.process(raw)
                val s2 = clarityFilter.process(s1)
                val eqSample = harshNotchFilter.process(s2)

                // Tách dải giọng chính (< 3.8 kHz) và dải tần cao (3.8 kHz – 12 kHz)
                lpState = lpCoeff * lpState + (1f - lpCoeff) * eqSample
                val lowMidBand = lpState
                val highBand = eqSample - lowMidBand

                val absHigh = abs(highBand)
                hfEnvelope = if (absHigh > hfEnvelope) {
                    0.75f * hfEnvelope + 0.25f * absHigh
                } else {
                    0.98f * hfEnvelope + 0.02f * absHigh
                }

                // Giữ nguyên 100% dải tần cao (1.0f) để âm thanh sáng rõ; chỉ nén nhẹ khi vượt ngưỡng âm xuýt
                val hfGain = if (hfEnvelope > sibilanceThreshold) {
                    val excess = hfEnvelope - sibilanceThreshold
                    (sibilanceThreshold + excess * 0.45f) / hfEnvelope
                } else {
                    1.0f
                }

                var sample = lowMidBand + highBand * hfGain

                // Soft-knee studio limiter ở ngưỡng > 0.88 để âm lượng dày và không bao giờ vỡ tiếng
                val absVal = abs(sample)
                if (absVal > 0.88f) {
                    val sign = if (sample >= 0f) 1f else -1f
                    val compressed = 0.88f + 0.11f * tanh(((absVal - 0.88f) / 0.11f).toDouble()).toFloat()
                    sample = sign * compressed
                }

                sample = applyFadeEnvelope(sample, i, voiceLength, fadeInSamples, fadeOutSamples)
                pcm[leadInSamples + i] = (sample * 32200f).toInt().coerceIn(-32768, 32767).toShort()
            }

            return pcm
        }

        private fun applyFadeEnvelope(
            sample: Float,
            index: Int,
            voiceLength: Int,
            fadeInSamples: Int,
            fadeOutSamples: Int
        ): Float {
            var out = sample
            if (index < fadeInSamples && fadeInSamples > 0) {
                val ratio = index.toDouble() / fadeInSamples.toDouble()
                val gain = (0.5 * (1.0 - cos(PI * ratio))).toFloat()
                out *= gain
            }
            if (index >= voiceLength - fadeOutSamples && fadeOutSamples > 0) {
                val remain = (voiceLength - 1 - index).toDouble() / fadeOutSamples.toDouble()
                val gain = (0.5 * (1.0 - cos(PI * remain))).toFloat()
                out *= gain
            }
            return out
        }
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null

    /**
     * Kích hoạt bộ lọc phần cứng trên AudioSession dành riêng cho Giọng Hệ Thống (Native Android TTS).
     * Khi chạy các bộ máy AI 24kHz (Edge TTS, Piper, VieNeu) vốn đã xử lý qua [processPcm24kHz],
     * tắt bộ lọc phần cứng trùng lặp để giữ trọn độ trong trẻo của dải tần cao (3.5kHz – 12kHz).
     */
    fun setEnabled(enabled: Boolean) {
        if (audioSessionId <= 0) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (dynamicsProcessing == null && enabled) {
                    setupDynamicsProcessing()
                }
                dynamicsProcessing?.enabled = enabled
            } else {
                if (equalizer == null && enabled) {
                    setupFallbackEqualizer()
                }
                equalizer?.enabled = enabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể bật DSP trên session $audioSessionId: ${e.message}")
        }
    }

    private fun setupDynamicsProcessing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val bandCount = 5
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                true, bandCount,
                true, bandCount,
                false, 0,
                true
            ).build()

            val dp = DynamicsProcessing(0, audioSessionId, cfg)

            // Cấu hình 5 dải tần số cân bằng ấm & sáng rõ:
            // Band 0 (0 - 250 Hz): +1.6 dB (Tăng độ ấm trầm)
            // Band 1 (250 - 1500 Hz): +0.4 dB (Giọng chính dày dặn)
            // Band 2 (1500 - 3400 Hz): +1.0 dB (Tăng độ rõ chữ, sắc nét)
            // Band 3 (3400 - 6500 Hz): -1.8 dB + De-Esser động (Làm mượt âm gắt)
            // Band 4 (6500 - 12000 Hz): +0.5 dB (Giữ độ thoáng sáng Air band)
            val cutoffs = floatArrayOf(250f, 1500f, 3400f, 6500f, 12000f)
            val eqGains = floatArrayOf(1.6f, 0.4f, 1.0f, -1.8f, 0.5f)

            val preEq = dp.getPreEqByChannelIndex(0)
            preEq.isEnabled = true
            for (i in 0 until bandCount) {
                val band = preEq.getBand(i)
                band.isEnabled = true
                band.cutoffFrequency = cutoffs[i]
                band.gain = eqGains[i]
                preEq.setBand(i, band)
            }
            dp.setPreEqAllChannelsTo(preEq)

            val mbc = dp.getMbcByChannelIndex(0)
            mbc.isEnabled = true
            for (i in 0 until bandCount) {
                val band = mbc.getBand(i)
                band.isEnabled = true
                band.cutoffFrequency = cutoffs[i]
                if (i == 3) {
                    band.attackTime = 3.0f
                    band.releaseTime = 65.0f
                    band.ratio = 2.8f
                    band.threshold = -20.0f
                    band.kneeWidth = 6.0f
                    band.postGain = 0.0f
                } else {
                    band.attackTime = 15.0f
                    band.releaseTime = 100.0f
                    band.ratio = 1.05f
                    band.threshold = -6.0f
                    band.kneeWidth = 4.0f
                    band.postGain = 0.0f
                }
                mbc.setBand(i, band)
            }
            dp.setMbcAllChannelsTo(mbc)

            val limiter = dp.getLimiterByChannelIndex(0)
            limiter.isEnabled = true
            limiter.attackTime = 2.0f
            limiter.releaseTime = 50.0f
            limiter.ratio = 6.0f
            limiter.threshold = -1.5f
            limiter.postGain = 0.0f
            dp.setLimiterAllChannelsTo(limiter)

            dynamicsProcessing = dp
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing không khả dụng, chuyển sang Equalizer: ${e.message}")
            setupFallbackEqualizer()
        }
    }

    private fun setupFallbackEqualizer() {
        try {
            val eq = Equalizer(0, audioSessionId)
            val numBands = eq.numberOfBands.toInt()
            val minLevel = eq.bandLevelRange[0].toInt()
            val maxLevel = eq.bandLevelRange[1].toInt()

            for (b in 0 until numBands) {
                val bandIdx = b.toShort()
                val centerFreqHz = eq.getCenterFreq(bandIdx) / 1000
                val targetMb = when {
                    centerFreqHz in 120..300 -> 150
                    centerFreqHz in 2000..3400 -> 100
                    centerFreqHz in 3500..6500 -> -180
                    else -> 0
                }.coerceIn(minLevel, maxLevel).toShort()
                eq.setBandLevel(bandIdx, targetMb)
            }
            equalizer = eq
        } catch (e: Exception) {
            Log.w(TAG, "Không thể khởi tạo Equalizer: ${e.message}")
        }
    }

    fun release() {
        try {
            dynamicsProcessing?.release()
        } catch (_: Exception) {}
        try {
            equalizer?.release()
        } catch (_: Exception) {}
        dynamicsProcessing = null
        equalizer = null
    }
}
