package com.novelr.reader.service

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Bộ xử lý âm thanh chống chói tai (De-Esser & Anti-Harshness DSP) thế hệ mới:
 * - TUYỆT ĐỐI KHÔNG hạ cao độ (Pitch = 100% nguyên bản) để giữ nguyên thanh điệu tiếng Việt và độ tự nhiên của giọng.
 * - Chỉ tác động vào dải tần số gây chói tai của tai người (3.4 kHz – 7.5 kHz, vùng âm xuýt s/x/ch/tr và cộng hưởng loa điện thoại)
 *   bằng bộ lọc Dynamic Split-Band De-Esser kết hợp Android DynamicsProcessing / Equalizer trên AudioSession.
 */
class WarmAudioDspProcessor(
    private val audioSessionId: Int
) {
    companion object {
        private const val TAG = "WarmAudioDsp"

        /**
         * Xử lý PCM 24kHz bằng thuật toán Split-Band Dynamic De-Esser + Biquad Presence Softener:
         * 1. Tách riêng dải tần giọng chính (< 3.4 kHz) và dải tần chói/âm xuýt (> 3.4 kHz).
         * 2. Giữ nguyên 100% độ trong trẻo và cao độ (pitch) của dải giọng chính (< 3.4 kHz) để tiếng không bị đục hay méo.
         * 3. Chỉ nén động (Dynamic De-Essing) khi dải > 3.4 kHz vượt ngưỡng chói tai, đồng thời làm mềm đỉnh sóng bằng đường cong Soft-Knee.
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

            // Thiết kế bộ lọc Biquad Peaking Notch nhẹ ở 4500 Hz (tần số gây chói tai nhất trên loa điện thoại)
            // Sample rate = 24000 Hz, f0 = 4500 Hz, Q = 1.15, Gain = -3.8 dB
            val sr = 24000.0
            val f0 = 4500.0
            val q = 1.15
            val dbGain = -3.8
            val a = Math.pow(10.0, dbGain / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)

            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosW0
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha / a

            val nb0 = (b0 / a0).toFloat()
            val nb1 = (b1 / a0).toFloat()
            val nb2 = (b2 / a0).toFloat()
            val na1 = (a1 / a0).toFloat()
            val na2 = (a2 / a0).toFloat()

            var x1 = 0f
            var x2 = 0f
            var y1 = 0f
            var y2 = 0f

            // Bộ tách tần bậc 1 ở 3400 Hz để đo năng lượng âm xuýt (Dynamic De-Esser)
            // alpha_lp = exp(-2*pi*3400/24000) ≈ 0.41f
            var lpState = 0f
            var hfEnvelope = 0f
            val lpCoeff = 0.41f
            val sibilanceThreshold = 0.085f

            for (i in 0 until voiceLength) {
                val raw = wav[startIdx + i]

                // Bước 1: Biquad Notch làm dịu vùng cộng hưởng 4.5 kHz mà không làm mờ dải trung (mid)
                val notched = nb0 * raw + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
                x2 = x1
                x1 = raw
                y2 = y1
                y1 = notched

                // Bước 2: Tách dải giọng chính (< 3.4 kHz) và dải âm cao (> 3.4 kHz)
                lpState = lpCoeff * lpState + (1f - lpCoeff) * notched
                val lowMidBand = lpState
                val highSibilanceBand = notched - lowMidBand

                // Bước 3: Đo đường bao năng lượng cao tần (Attack nhanh 0.25, Release mượt 0.02)
                val absHigh = abs(highSibilanceBand)
                hfEnvelope = if (absHigh > hfEnvelope) {
                    0.75f * hfEnvelope + 0.25f * absHigh
                } else {
                    0.98f * hfEnvelope + 0.02f * absHigh
                }

                // Chỉ giảm biên độ dải cao tần khi thực sự có gai âm chói (s, x, ch, tr), giữ nguyên 100% nguyên âm
                val hfGain = if (hfEnvelope > sibilanceThreshold) {
                    val excess = hfEnvelope - sibilanceThreshold
                    (sibilanceThreshold + excess * 0.35f) / hfEnvelope
                } else {
                    0.88f // Hạ nhẹ 1dB ở dải siêu cao để êm tai khi nghe lâu
                }

                var sample = lowMidBand + highSibilanceBand * hfGain

                // Bước 4: Soft-knee saturator nhẹ ở ngưỡng > 0.85 để chống vỡ tiếng (clipping) trên loa điện thoại
                val absVal = abs(sample)
                if (absVal > 0.85f) {
                    val sign = if (sample >= 0f) 1f else -1f
                    val compressed = 0.85f + 0.14f * tanh(((absVal - 0.85f) / 0.14f).toDouble()).toFloat()
                    sample = sign * compressed
                }

                sample = applyFadeEnvelope(sample, i, voiceLength, fadeInSamples, fadeOutSamples)
                pcm[leadInSamples + i] = (sample * 31200f).toInt().coerceIn(-32768, 32767).toShort()
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
     * Kích hoạt hoặc tắt bộ lọc chống chói phần cứng cho AudioSession (áp dụng cho cả Giọng Hệ Thống & VieNeu).
     * Hoàn toàn không thay đổi Pitch mà dùng Multi-Band Compressor (De-Esser) + Post-EQ ở dải 3.2kHz - 7kHz.
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
            Log.w(TAG, "Không thể bật DSP chống chói trên session $audioSessionId: ${e.message}")
        }
    }

    private fun setupDynamicsProcessing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val bandCount = 5
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                true, bandCount, // Pre-EQ 5 băng tần
                true, bandCount, // Multi-Band Compressor (De-Esser tự động ở dải cao)
                false, 0,
                true             // Limiter chống vọt đỉnh âm gây chói
            ).build()

            val dp = DynamicsProcessing(0, audioSessionId, cfg)

            // Cấu hình 5 dải tần số:
            // Band 0 (0 - 280 Hz): +1.0 dB (Tăng nhẹ độ ấm lồng ngực tự nhiên)
            // Band 1 (280 - 1400 Hz): 0.0 dB (Giữ nguyên 100% độ rõ của giọng nói)
            // Band 2 (1400 - 3200 Hz): -0.8 dB (Giữ độ nét nhưng giảm gắt nhẹ)
            // Band 3 (3200 - 6500 Hz): -3.5 dB + Nén động De-Esser mạnh (Triệt tiêu vùng tần số gây chói tai nhất)
            // Band 4 (6500 - 12000 Hz): -2.5 dB + Nén động De-Esser (Làm mượt âm xuýt s, x, ch)
            val cutoffs = floatArrayOf(280f, 1400f, 3200f, 6500f, 12000f)
            val eqGains = floatArrayOf(1.0f, 0.0f, -0.8f, -3.5f, -2.5f)

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
                if (i >= 3) {
                    // Băng tần 3 & 4 (3.2kHz - 12kHz): Hoạt động như một De-Esser chuyên dụng
                    band.attackTime = 3.0f
                    band.releaseTime = 65.0f
                    band.ratio = 3.5f
                    band.threshold = -21.0f
                    band.kneeWidth = 6.0f
                    band.postGain = 0.0f
                } else {
                    // Các băng tần trầm & trung: Không nén để giữ nguyên ngữ điệu tự nhiên
                    band.attackTime = 15.0f
                    band.releaseTime = 100.0f
                    band.ratio = 1.1f
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
            limiter.threshold = -2.0f
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
                // Chỉ hạ nhẹ dải 3kHz - 7kHz (-300 mB = -3.0 dB), giữ nguyên dải trung
                val targetMb = when {
                    centerFreqHz in 150..300 -> 80
                    centerFreqHz in 3000..7500 -> -320
                    centerFreqHz > 7500 -> -200
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
