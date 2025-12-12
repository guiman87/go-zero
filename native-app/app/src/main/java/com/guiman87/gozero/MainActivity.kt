package com.guiman87.gozero

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Preferences keys
    val HA_URL = stringPreferencesKey("ha_url")
    val HA_TOKEN = stringPreferencesKey("ha_token")
    val LAST_COMMAND = stringPreferencesKey("last_command")
    val LAST_RESPONSE = stringPreferencesKey("last_response")
    
    // State
        // DataStore Keys
    val SENSITIVITY = androidx.datastore.preferences.core.floatPreferencesKey("sensitivity")
    val TIMEOUT = androidx.datastore.preferences.core.longPreferencesKey("sequence_timeout")

    // State
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }
    var lastCommand by remember { mutableStateOf("") }
    var lastResponse by remember { mutableStateOf("") }
    var sensitivity by remember { mutableStateOf(0.7f) }
    var timeout by remember { mutableStateOf(2000L) }
    var isListening by remember { mutableStateOf(false) }

    // Load saved preferences
    val preferencesState = context.dataStore.data.collectAsState(initial = null)
    
    LaunchedEffect(preferencesState.value) {
        preferencesState.value?.let { preferences ->
            if (haUrl.isEmpty()) haUrl = preferences[HA_URL] ?: ""
            if (haToken.isEmpty()) haToken = preferences[HA_TOKEN] ?: ""
            lastCommand = preferences[LAST_COMMAND] ?: ""
            lastResponse = preferences[LAST_RESPONSE] ?: ""
            sensitivity = preferences[SENSITIVITY] ?: 0.7f
            timeout = preferences[TIMEOUT] ?: 2000L
        }
    }

    // Permissions
    val recordAudioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "Go Zero Settings", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = haUrl,
            onValueChange = { haUrl = it },
            label = { Text("Home Assistant URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = haToken,
            onValueChange = { haToken = it },
            label = { Text("Long-Lived Access Token") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // Sensitivity Slider
        // Inverted logic for UI: 
        // Underlying value is "Threshold" (High = Strict/Less Sensitive).
        // UI shows "Sensitivity" (High = Loose/More Sensitive).
        val uiSensitivity = 1f - sensitivity
        
        Text(text = "Sensitivity: ${(uiSensitivity * 100).toInt()}%")
        Slider(
            value = uiSensitivity,
            onValueChange = { newValue -> 
                sensitivity = 1f - newValue 
            },
            valueRange = 0.01f..0.9f, 
            steps = 9
        )
        Text(text = "Higher = Easier to trigger (Lower confidence threshold)", style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(16.dp))

        // Timeout Slider
        Text(text = "Word Sequence Timeout: ${timeout}ms")
        Slider(
            value = timeout.toFloat(),
            onValueChange = { timeout = it.toLong() },
            valueRange = 500f..5000f,
            steps = 9
        )
        Text(text = "Time allowed between 'Go' and 'Zero'", style = MaterialTheme.typography.bodySmall)
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    context.dataStore.edit { preferences ->
                        preferences[HA_URL] = haUrl
                        preferences[HA_TOKEN] = haToken
                        preferences[SENSITIVITY] = sensitivity
                        preferences[TIMEOUT] = timeout
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Status: ${if (isListening) "Listening..." else "Stopped"}")
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (recordAudioPermissionState.status.isGranted) {
                    isListening = !isListening
                    val intent = Intent(context, WakeWordService::class.java)
                    if (isListening) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.stopService(intent)
                    }
                } else {
                    recordAudioPermissionState.launchPermissionRequest()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isListening) "Stop Listening" else "Start Listening")
        }
        
        if (!recordAudioPermissionState.status.isGranted) {
             Text("Microphone permission required for wake word detection.")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Feedback Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Last Activity", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(text = "You said:", style = MaterialTheme.typography.labelMedium)
                Text(text = lastCommand.ifEmpty { "-" }, style = MaterialTheme.typography.bodyLarge)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(text = "Response:", style = MaterialTheme.typography.labelMedium)
                Text(text = lastResponse.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
