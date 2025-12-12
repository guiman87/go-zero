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
    private val labels = listOf(
        "go", "unknown", "zero"
    )

    // State for sequence detection
    private var lastGoTime: Long = 0
    private var sequenceTimeoutMs = 2000L // Default
    private var sensitivityThreshold = 0.7f // Default

    fun setSensitivity(threshold: Float) {
        sensitivityThreshold = threshold
        Log.d("TFLite", "Sensitivity updated to: $threshold")
    }

    fun setTimeout(timeout: Long) {
        sequenceTimeoutMs = timeout
        Log.d("TFLite", "Timeout updated to: $timeout")
    }

    fun start() {
        if (isListening) return
        
        try {
            // 1. Load Model
            val mappedByteBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            val options = Interpreter.Options()
            interpreter = Interpreter(mappedByteBuffer, options)
            
            // 2. Setup Input
            val inputTensorIndex = 0
            val inputTensor = interpreter!!.getInputTensor(inputTensorIndex)
            val inputShape = inputTensor.shape()
            Log.d("TFLite", "Input Tensor Shape: ${inputShape.contentToString()}")
            
            // Calculate required sample count
            val targetSampleCount = if (inputShape.size > 1) {
                var count = 1
                for (i in 1 until inputShape.size) {
                    count *= inputShape[i]
                }
                count
            } else {
                inputShape[0] 
            }
            Log.d("TFLite", "Target Samples: $targetSampleCount")

            // 2b. Setup AudioRecord & TensorAudio
            // Note: Our custom model logic in python expects 16000 sample rate
            val audioFormat = android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                .build()
                
            val tensorAudioFormat = TensorAudio.TensorAudioFormat.create(audioFormat)
            tensorAudio = TensorAudio.create(tensorAudioFormat, targetSampleCount)

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
                     val ns = android.media.audiofx.NoiseSuppressor.create(audioRecord!!.audioSessionId)
                     ns.enabled = true
                     Log.i("TFLite", "NoiseSuppressor enabled")
                } else {
                     Log.w("TFLite", "NoiseSuppressor NOT available")
                }
                
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                     val aec = android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                     aec.enabled = true
                     Log.i("TFLite", "AcousticEchoCanceler enabled")
                } else {
                     Log.w("TFLite", "AcousticEchoCanceler NOT available")
                }

            } catch (e: SecurityException) {
                Log.e("TFLite", "Permission denied", e)
                return
            }
            
            audioRecord?.startRecording()
            
            // 3. Inference Loop
            executor = ScheduledThreadPoolExecutor(1)
            isListening = true
            
            // Run inference faster (200ms) for smoother averaging
            executor?.scheduleAtFixedRate({
                classifyAudio()
            }, 0, 200, TimeUnit.MILLISECONDS)
            
            Log.d("TFLite", "Interpreter started")

        } catch (e: Exception) {
            Log.e("TFLiteClassifier", "Error initializing", e)
        }
    }

    // Consective trigger counters
    private var consecutiveGoCount = 0
    private var consecutiveZeroCount = 0
    private val TRIGGER_FRAMES = 2 // Require 2 consecutive frames ~400ms

    // Smoothing state
    private val scoreHistory = FloatArray(3) { 0f } // Track last 3 "Go" scores
    private var historyIndex = 0

    private fun classifyAudio() {
        if (!isListening || interpreter == null || audioRecord == null) return

        try {
            // Load Audio
            tensorAudio?.load(audioRecord)
            
            // Prepare Output Buffer
            val outputTensor = interpreter!!.getOutputTensor(0)
            val outputShape = outputTensor.shape() 
            val outputBuffer = java.nio.FloatBuffer.allocate(outputShape[1])
            
            // Run
            interpreter!!.run(tensorAudio!!.tensorBuffer.buffer, outputBuffer)
            
            // Process Results
            val results = FloatArray(outputShape[1])
            outputBuffer.rewind()
            outputBuffer.get(results)
            
            // Apply Softmax to convert logits to probabilities
            val probs = softmax(results)
            
            // Helper to get score for a specific label
            fun getScore(label: String): Float {
                val idx = labels.indexOf(label)
                return if (idx != -1) probs[idx] else 0f
            }

            val goScore = getScore("go")
            val zeroScore = getScore("zero")
            val unknownScore = getScore("unknown")
            
            // Smoothing for "Go"
            scoreHistory[historyIndex] = goScore
            historyIndex = (historyIndex + 1) % scoreHistory.size
            val smoothedGoScore = scoreHistory.average().toFloat()

            // Logic: Go
            // Use SMOOTHED score for "Go" trigger, but check raw suppression too?
            // Actually, smoothing + consecutive check might be overkill, but safer.
            // Let's rely on Consecutive Check as the primary filter for spikes.
            
            if (smoothedGoScore > sensitivityThreshold && smoothedGoScore > unknownScore) {
                 consecutiveGoCount++
            } else {
                 consecutiveGoCount = 0
            }

            if (consecutiveGoCount >= TRIGGER_FRAMES) {
                 if (lastGoTime == 0L || (System.currentTimeMillis() - lastGoTime > 2000)) {
                      Log.d("TFLite", "Confirmed/Sustained GO detected")
                      lastGoTime = System.currentTimeMillis()
                      // Reset counter to prevent spamming log, but keep it high? 
                      // No, we set lastGoTime, so we won't process Zero until later.
                 }
            }
            
            // Logic: Zero
            if (zeroScore > sensitivityThreshold && zeroScore > goScore && zeroScore > unknownScore) {
                consecutiveZeroCount++
            } else {
                consecutiveZeroCount = 0
            }

            if (consecutiveZeroCount >= TRIGGER_FRAMES) {
                val now = System.currentTimeMillis()
                // 2. Timing: Must be AFTER "Go" (at least 200ms) but WITHIN timeout.
                if (lastGoTime > 0 && (now - lastGoTime < sequenceTimeoutMs) && (now - lastGoTime > 200)) {
                     Log.i("TFLite", "Wake Word 'Go Zero' COMPLETION!")
                     listener.onWakeWordDetected()
                     lastGoTime = 0 // Reset
                     consecutiveZeroCount = 0 // Reset
                     consecutiveGoCount = 0 // Reset
                     // Reset history
                     for(i in scoreHistory.indices) scoreHistory[i] = 0f
                }
            }
            
        } catch (e: Exception) {
            Log.e("TFLite", "Inference loop error", e)
        }
    }
    
    private fun softmax(logits: FloatArray): FloatArray {
        val probs = FloatArray(logits.size)
        var maxLogit = -Float.MAX_VALUE
        for (logit in logits) {
            if (logit > maxLogit) maxLogit = logit
        }
        
        var sum = 0.0f
        for (i in logits.indices) {
            probs[i] = Math.exp((logits[i] - maxLogit).toDouble()).toFloat()
            sum += probs[i]
        }
        
        for (i in probs.indices) {
            probs[i] /= sum
        }
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
