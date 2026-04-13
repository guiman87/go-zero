package com.guiman87.gozero

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.support.common.FileUtil
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

interface WakeWordListener {
    fun onWakeWordDetected()
}

class TFLiteClassifier(val context: Context, val listener: WakeWordListener) {
    private var interpreter: Interpreter? = null
    private var tensorAudio: TensorAudio? = null
    private var audioRecord: AudioRecord? = null
    private var executor: ScheduledThreadPoolExecutor? = null
    private var isListening = false

    // Labels for custom "Go Zero" model (from train_go_zero.py)
    private val labels = listOf("go", "unknown", "zero")

    // Settings
    private var sequenceTimeoutMs = 2000L
    private var sensitivityThreshold = 0.7f
    @Volatile private var currentPhrase = WakePhrase.GO_ZERO

    fun setSensitivity(threshold: Float) {
        sensitivityThreshold = threshold
        Log.d("TFLite", "Sensitivity updated to: $threshold")
    }

    fun setTimeout(timeout: Long) {
        sequenceTimeoutMs = timeout
        Log.d("TFLite", "Timeout updated to: $timeout")
    }

    fun setWakePhrase(phrase: WakePhrase) {
        if (phrase != currentPhrase) {
            currentPhrase = phrase
            resetState()
            Log.d("TFLite", "Wake phrase updated to: ${phrase.displayName}")
        }
    }

    fun start() {
        if (isListening) return

        try {
            val mappedByteBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            interpreter = Interpreter(mappedByteBuffer, Interpreter.Options())

            val inputShape = interpreter!!.getInputTensor(0).shape()
            Log.d("TFLite", "Input Tensor Shape: ${inputShape.contentToString()}")

            val targetSampleCount = if (inputShape.size > 1) {
                var count = 1
                for (i in 1 until inputShape.size) count *= inputShape[i]
                count
            } else {
                inputShape[0]
            }
            Log.d("TFLite", "Target Samples: $targetSampleCount")

            val audioFormat = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                .build()

            tensorAudio = TensorAudio.create(TensorAudio.TensorAudioFormat.create(audioFormat), targetSampleCount)

            val minBufferSize = AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = Math.max(minBufferSize, targetSampleCount * 2)

            try {
                audioRecord = AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId).enabled = true
                    Log.i("TFLite", "NoiseSuppressor enabled")
                } else {
                    Log.w("TFLite", "NoiseSuppressor NOT available")
                }

                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId).enabled = true
                    Log.i("TFLite", "AcousticEchoCanceler enabled")
                } else {
                    Log.w("TFLite", "AcousticEchoCanceler NOT available")
                }

                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    android.media.audiofx.AutomaticGainControl.create(audioRecord!!.audioSessionId).enabled = true
                    Log.i("TFLite", "AutomaticGainControl enabled")
                } else {
                    Log.w("TFLite", "AutomaticGainControl NOT available")
                }
            } catch (e: SecurityException) {
                Log.e("TFLite", "Permission denied", e)
                return
            }

            audioRecord?.startRecording()

            val outputShape = interpreter!!.getOutputTensor(0).shape()
            outputBuffer = java.nio.FloatBuffer.allocate(outputShape[1])

            executor = ScheduledThreadPoolExecutor(1)
            isListening = true

            executor?.scheduleAtFixedRate({ classifyAudio() }, 0, 200, TimeUnit.MILLISECONDS)
            Log.d("TFLite", "Interpreter started (phrase: ${currentPhrase.displayName})")

        } catch (e: Exception) {
            Log.e("TFLiteClassifier", "Error initializing", e)
        }
    }

    // --- Generalised two-step state machine ---
    // First word needs 3 consecutive frames (arming is expensive — false arm wastes a listening slot).
    // Second word needs 2 consecutive frames (it's already armed, speed matters more).
    private val FIRST_TRIGGER_FRAMES = 3
    private val SECOND_TRIGGER_FRAMES = 2

    private var consecutiveFirstCount = 0
    private var consecutiveSecondCount = 0
    private var lastFirstWordTime: Long = 0

    // 3-frame rolling averages for each word (~600ms window)
    private val firstWordHistory = FloatArray(3) { 0f }
    private var firstHistoryIndex = 0
    private val secondWordHistory = FloatArray(3) { 0f }
    private var secondHistoryIndex = 0

    // Pre-allocated output buffer
    private var outputBuffer: java.nio.FloatBuffer? = null

    // Adaptive noise floor
    private var ambientRms = 0.02f
    private val AMBIENT_ALPHA = 0.002f
    private val SPEECH_GAIN = 1.7f
    private val AMBIENT_UPDATE_GAIN = 1.3f

    // Score margin: detected word must beat "unknown" by this amount
    private val SCORE_MARGIN = 0.10f

    private fun resetState() {
        consecutiveFirstCount = 0
        consecutiveSecondCount = 0
        lastFirstWordTime = 0
        for (i in firstWordHistory.indices) firstWordHistory[i] = 0f
        for (i in secondWordHistory.indices) secondWordHistory[i] = 0f
    }

    private fun classifyAudio() {
        if (!isListening || interpreter == null || audioRecord == null) return

        try {
            tensorAudio?.load(audioRecord)

            // Adaptive noise floor gate
            val audioFloats = tensorAudio!!.tensorBuffer.floatArray
            val rms = Math.sqrt(audioFloats.map { it * it }.average()).toFloat()

            if (rms < ambientRms * AMBIENT_UPDATE_GAIN) {
                ambientRms = ambientRms * (1f - AMBIENT_ALPHA) + rms * AMBIENT_ALPHA
            }

            if (rms < ambientRms * SPEECH_GAIN) {
                consecutiveFirstCount = 0
                consecutiveSecondCount = 0
                return
            }

            Log.v("TFLite", "Speech frame: rms=%.4f ambient=%.4f ratio=%.1fx".format(rms, ambientRms, rms / ambientRms))

            // Run inference
            val buf = outputBuffer ?: return
            buf.rewind()
            interpreter!!.run(tensorAudio!!.tensorBuffer.buffer, buf)
            val results = FloatArray(buf.capacity())
            buf.rewind()
            buf.get(results)

            val probs = softmax(results)
            fun getScore(label: String): Float {
                val idx = labels.indexOf(label)
                return if (idx != -1) probs[idx] else 0f
            }

            val phrase = currentPhrase
            val firstScore = getScore(phrase.firstWord)
            val secondScore = getScore(phrase.secondWord)
            val unknownScore = getScore("unknown")

            Log.v("TFLite", "Scores — ${phrase.firstWord}=%.2f ${phrase.secondWord}=%.2f unknown=%.2f | firstConsec=%d secondConsec=%d armed=%s"
                .format(firstScore, secondScore, unknownScore, consecutiveFirstCount, consecutiveSecondCount, if (lastFirstWordTime > 0) "yes" else "no"))

            // --- Step 1: detect first word ---
            firstWordHistory[firstHistoryIndex] = firstScore
            firstHistoryIndex = (firstHistoryIndex + 1) % firstWordHistory.size
            val smoothedFirst = firstWordHistory.average().toFloat()

            if (smoothedFirst > sensitivityThreshold && smoothedFirst > unknownScore + SCORE_MARGIN) {
                consecutiveFirstCount++
            } else {
                consecutiveFirstCount = 0
            }

            if (consecutiveFirstCount >= FIRST_TRIGGER_FRAMES) {
                if (lastFirstWordTime == 0L || (System.currentTimeMillis() - lastFirstWordTime > sequenceTimeoutMs)) {
                    Log.d("TFLite", "First word '${phrase.firstWord}' confirmed (smoothed=%.2f)".format(smoothedFirst))
                    lastFirstWordTime = System.currentTimeMillis()
                    // Reset second word history so same-word phrases (Zero Zero) don't
                    // immediately fire from leftover score momentum
                    for (i in secondWordHistory.indices) secondWordHistory[i] = 0f
                    consecutiveSecondCount = 0
                }
            }

            // --- Step 2: detect second word (only when armed) ---
            secondWordHistory[secondHistoryIndex] = secondScore
            secondHistoryIndex = (secondHistoryIndex + 1) % secondWordHistory.size
            val smoothedSecond = secondWordHistory.average().toFloat()

            if (smoothedSecond > sensitivityThreshold && smoothedSecond > unknownScore + SCORE_MARGIN) {
                consecutiveSecondCount++
            } else {
                consecutiveSecondCount = 0
            }

            if (consecutiveSecondCount >= SECOND_TRIGGER_FRAMES) {
                val now = System.currentTimeMillis()
                if (lastFirstWordTime > 0 && (now - lastFirstWordTime < sequenceTimeoutMs) && (now - lastFirstWordTime > 200)) {
                    Log.i("TFLite", "'${phrase.displayName}' COMPLETION! (second smoothed=%.2f, gap=%dms)".format(smoothedSecond, now - lastFirstWordTime))
                    listener.onWakeWordDetected()
                    resetState()
                }
            }

        } catch (e: Exception) {
            Log.e("TFLite", "Inference loop error", e)
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val probs = FloatArray(logits.size)
        var maxLogit = -Float.MAX_VALUE
        for (logit in logits) if (logit > maxLogit) maxLogit = logit
        var sum = 0.0f
        for (i in logits.indices) {
            probs[i] = Math.exp((logits[i] - maxLogit).toDouble()).toFloat()
            sum += probs[i]
        }
        for (i in probs.indices) probs[i] /= sum
        return probs
    }

    fun stop() {
        isListening = false
        executor?.shutdownNow()
        audioRecord?.stop()
        interpreter?.close()
        interpreter = null
    }
}
