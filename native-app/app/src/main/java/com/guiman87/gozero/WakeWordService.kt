package com.guiman87.gozero

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class WakeWordService : Service(), WakeWordListener {

    private var classifier: TFLiteClassifier? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: android.speech.tts.TextToSpeech? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        
        // Acquire WakeLock for continuous listening (Dedicated Device)
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GoZero::WakeWordLock")
        wakeLock?.acquire()
        
        startForegroundService()
        
        // Initialize TTS
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("WakeWordService", "TTS Language not supported")
                } else {
                    Log.i("WakeWordService", "TTS Initialized successfully")
                }
            } else {
                Log.e("WakeWordService", "TTS Initialization failed")
            }
        }
        
        // Pass listener (this service) to classifier
        classifier = TFLiteClassifier(this, this)
        classifier?.start()
        
        // Observe Settings
        serviceScope.launch {
            val SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("sensitivity")
            val TIMEOUT = androidx.datastore.preferences.core.longPreferencesKey("sequence_timeout")
            val WAKE_PHRASE = androidx.datastore.preferences.core.stringPreferencesKey("wake_phrase")
            val context = this@WakeWordService

            context.dataStore.data.collect { prefs ->
                val sensitivity = prefs[SENSITIVITY] ?: 0.7f
                val timeout = prefs[TIMEOUT] ?: 2000L
                val phraseStr = prefs[WAKE_PHRASE] ?: WakePhrase.GO_ZERO.name

                classifier?.setSensitivity(sensitivity)
                classifier?.setTimeout(timeout)
                classifier?.setWakePhrase(WakePhrase.fromName(phraseStr))
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "WakeWordServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Wake Word Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Go Zero")
            .setContentText("Listening... (WakeLock Active)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    override fun onWakeWordDetected() {
        Log.d("WakeWordService", "Wake word detected! Starting Speech Recognition.")
        // Android SpeechRecognizer plays its own sound, so we just start listening.
        mainHandler.post {
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        // Pause classifier while listening for command
        classifier?.stop()

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Tune for snappier response (Dedicate Device)
            // End silence logic sooner (1.2 seconds instead of default ~3s)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d("STT", "Ready") }
            override fun onBeginningOfSpeech() { Log.d("STT", "Speaking") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d("STT", "End of speech") }

            override fun onError(error: Int) {
                Log.e("STT", "Error: $error")
                speak("I didn't catch that")
                // Restart wake word listener after short delay
                 mainHandler.postDelayed({
                    classifier?.start()
                }, 1000)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    Log.d("STT", "Command: $command")
                    processCommand(command)
                } else {
                    classifier?.start()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun processCommand(command: String) {
        serviceScope.launch {
            // Sanitize command: fix common "of" vs "off" error
            // e.g. "turn lights of" -> "turn lights off"
            val sanitizedCommand = if (command.trim().endsWith(" of", ignoreCase = true)) {
                command.trim().dropLast(3) + " off"
            } else {
                command.trim()
            }
            
            Log.d("STT", "Sanitized Command: $sanitizedCommand")

            val context = this@WakeWordService
            // Note: Context.dataStore is defined in MainActivity.kt
            val HA_URL = stringPreferencesKey("ha_url")
            val HA_TOKEN = stringPreferencesKey("ha_token")
            val LAST_COMMAND = stringPreferencesKey("last_command")
            val LAST_RESPONSE = stringPreferencesKey("last_response")

            // Save command to UI immediately
            context.dataStore.edit { prefs ->
                prefs[LAST_COMMAND] = sanitizedCommand
                prefs[LAST_RESPONSE] = "Processing..." 
            }

            // Read preferences
            val preferences = context.dataStore.data.first()
            val haUrl = preferences[HA_URL]
            val haToken = preferences[HA_TOKEN]

            if (!haUrl.isNullOrEmpty() && !haToken.isNullOrEmpty()) {
                try {
                    val api = HomeAssistantClient.getApi(haUrl)
                    val response = api.processConversation(
                        token = "Bearer $haToken",
                        request = ConversationRequest(text = sanitizedCommand)
                    )
                    
                    val speechResponse = response.response?.speech?.plain?.speech
                    Log.d("HA", "Response: $speechResponse")
                    
                    if (!speechResponse.isNullOrEmpty()) {
                        speak(speechResponse)
                        // Save response to UI
                        context.dataStore.edit { prefs ->
                            prefs[LAST_RESPONSE] = speechResponse
                        }
                    } else {
                        val fallback = "I sent that to Home Assistant"
                        speak(fallback)
                        context.dataStore.edit { prefs ->
                            prefs[LAST_RESPONSE] = fallback
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e("HA", "Error sending command", e)
                    speak("Error communicating with Home Assistant")
                    context.dataStore.edit { prefs ->
                        prefs[LAST_RESPONSE] = "Error: ${e.localizedMessage}"
                    }
                }
            } else {
                Log.w("HA", "HA URL or Token not configured")
                speak("Please configure Home Assistant settings")
            }
            
            // Resume listening for wake word after TTS finishes?
            // Simple approach: delayed restart
            mainHandler.postDelayed({
                classifier?.start()
            }, 4000) 
        }
    }
    
    private fun speak(text: String) {
        val params = Bundle()
        params.putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        
        val ret = tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "GoZeroResponse")
        if (ret == android.speech.tts.TextToSpeech.ERROR) {
            Log.e("WakeWordService", "TTS speak failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier?.stop()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
