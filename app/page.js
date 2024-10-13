"use client";
import { useEffect, useState, useRef } from "react";
import {
  Button,
  Container,
  Typography,
  Box,
  CircularProgress,
} from "@mui/material";

export default function Home() {
  const [recognizer, setRecognizer] = useState(null);
  const [listening, setListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [status, setStatus] = useState('Click "Start Listening" to begin.');
  const [loading, setLoading] = useState(false);
  const wordBufferRef = useRef([]);
  const wakeLockRef = useRef(null);

  useEffect(() => {
    let recognizerInstance;
    const loadModel = async () => {
      if (typeof window !== "undefined") {
        setLoading(true);

        // Load TensorFlow.js and the speech commands model
        const tf = await import("@tensorflow/tfjs");
        const speechCommands = await import(
          "@tensorflow-models/speech-commands"
        );

        // Set the backend
        try {
          await tf.setBackend("webgl");
          await tf.ready();
          console.log("Using WebGL backend");
        } catch (error) {
          console.warn("WebGL backend not supported, falling back to CPU.");
          await tf.setBackend("cpu");
          await tf.ready();
        }

        recognizerInstance = speechCommands.create("BROWSER_FFT");
        await recognizerInstance.ensureModelLoaded();
        setRecognizer(recognizerInstance);
        setLoading(false);

        // Start listening automatically after the model is loaded
        startListening();
      }
    };

    loadModel();

    // Cleanup function
    return () => {
      // Stop the recognizer if it's listening
      if (recognizerInstance && recognizerInstance.isListening()) {
        recognizerInstance.stopListening().catch((error) => {
          console.error("Error stopping recognizer on unmount:", error);
        });
      }
      // Release the wake lock if active
      if (wakeLockRef.current) {
        wakeLockRef.current.release().catch((error) => {
          console.error("Error releasing wake lock on unmount:", error);
        });
      }
    };
  }, []);

  const requestWakeLock = async () => {
    if ("wakeLock" in navigator) {
      try {
        wakeLockRef.current = await navigator.wakeLock.request("screen");
        console.log("Wake lock is active");
      } catch (error) {
        console.error("Error requesting wake lock:", error);
      }
    } else {
      console.warn("Wake Lock API not supported in this browser.");
    }
  };

  const releaseWakeLock = async () => {
    if (wakeLockRef.current) {
      try {
        await wakeLockRef.current.release();
        console.log("Wake lock released");
        wakeLockRef.current = null;
      } catch (error) {
        console.error("Error releasing wake lock:", error);
      }
    }
  };

  // Success beep function (friendly melody)
  const playSuccessBeep = () => {
    if (typeof window !== "undefined") {
      const audioContext = new (window.AudioContext ||
        window.webkitAudioContext)();

      const playTone = (frequency, duration, delay = 0) => {
        const oscillator = audioContext.createOscillator();
        const gainNode = audioContext.createGain();

        oscillator.frequency.value = frequency;
        oscillator.type = "sine";
        gainNode.gain.value = 0.5;

        oscillator.connect(gainNode);
        gainNode.connect(audioContext.destination);

        oscillator.start(audioContext.currentTime + delay);
        oscillator.stop(audioContext.currentTime + delay + duration / 1000);
      };

      // Play a friendly ascending melody
      playTone(523.25, 150); // C5
      playTone(659.25, 150, 0.15); // E5
      playTone(783.99, 150, 0.3); // G5

      // Close the audio context after the melody finishes
      setTimeout(() => {
        audioContext.close();
      }, 500);
    }
  };

  // Error beep function (softer sound)
  const playErrorBeep = () => {
    if (typeof window !== "undefined") {
      const audioContext = new (window.AudioContext ||
        window.webkitAudioContext)();

      const playTone = (frequency, duration) => {
        const oscillator = audioContext.createOscillator();
        const gainNode = audioContext.createGain();

        oscillator.frequency.value = frequency;
        oscillator.type = "sine";
        gainNode.gain.value = 0.5; // Softer volume

        oscillator.connect(gainNode);
        gainNode.connect(audioContext.destination);

        oscillator.start();
        oscillator.stop(audioContext.currentTime + duration / 1000);
      };

      // Play a descending tone
      playTone(440, 300); // A4
      setTimeout(() => {
        playTone(349.23, 300); // F4
      }, 300);

      // Close the audio context after the tones finish
      setTimeout(() => {
        audioContext.close();
      }, 700);
    }
  };

  // Function to speak text using the Speech Synthesis API
  const speakText = (text, callback) => {
    if ("speechSynthesis" in window) {
      window.speechSynthesis.cancel(); // Cancel any ongoing speech
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = "en-US";

      const setVoice = () => {
        const voices = window.speechSynthesis.getVoices();
        let selectedVoice = null;

        // Prioritize male voices in 'en-US' language
        selectedVoice = voices.find(
          (voice) =>
            voice.lang === "en-US" && voice.name.toLowerCase().includes("male")
        );

        // If no male voice labeled, select any male voice
        if (!selectedVoice) {
          selectedVoice = voices.find((voice) =>
            voice.name.toLowerCase().includes("male")
          );
        }

        // If still not found, select any 'en-US' voice
        if (!selectedVoice) {
          selectedVoice = voices.find((voice) => voice.lang === "en-US");
        }

        // Set the selected voice if available
        if (selectedVoice) {
          utterance.voice = selectedVoice;
        }

        // Handle end event to call callback
        utterance.onend = () => {
          if (callback) callback();
        };

        window.speechSynthesis.speak(utterance);
      };

      // Load voices if not already loaded
      if (window.speechSynthesis.getVoices().length > 0) {
        setVoice();
      } else {
        window.speechSynthesis.onvoiceschanged = () => {
          setVoice();
        };
      }
    } else {
      console.warn("Speech Synthesis API not supported in this browser.");
      if (callback) callback();
    }
  };

  // Function to play a beep tone (used for start sound)
  const playBeep = (
    frequency = 440,
    duration = 500,
    volume = 1,
    type = "sine"
  ) => {
    if (typeof window !== "undefined") {
      const audioContext = new (window.AudioContext ||
        window.webkitAudioContext)();
      const oscillator = audioContext.createOscillator();
      const gainNode = audioContext.createGain();

      oscillator.frequency.value = frequency;
      oscillator.type = type;
      gainNode.gain.value = volume;

      oscillator.connect(gainNode);
      gainNode.connect(audioContext.destination);

      oscillator.start();

      setTimeout(() => {
        oscillator.stop();
        audioContext.close();
      }, duration);
    }
  };

  const startListening = () => {
    if (recognizer) {
      setListening(true);
      setStatus('Listening for wake word "go zero"...');
      wordBufferRef.current = []; // Reset the buffer

      // Play the start sound (e.g., high-pitched beep)
      playBeep(660, 200); // Frequency: 660Hz, Duration: 200ms

      // Request the wake lock to keep the screen on
      requestWakeLock();

      recognizer
        .listen(
          async (result) => {
            const { scores } = result;
            const labels = recognizer.wordLabels();
            const maxScoreIndex = scores.indexOf(Math.max(...scores));
            const detectedWord = labels[maxScoreIndex];

            // Update word buffer
            const newBuffer = [...wordBufferRef.current, detectedWord].slice(
              -2
            ); // Keep last two words
            wordBufferRef.current = newBuffer;

            // Check if the sequence matches "go" followed by "zero"
            if (newBuffer[0] === "go" && newBuffer[1] === "zero") {
              console.log('Wake word "go zero" detected!');
              setStatus('Wake word "go zero" detected!');
              if (recognizer.isListening()) {
                try {
                  await recognizer.stopListening();
                } catch (error) {
                  console.error("Error stopping recognizer:", error);
                }
              }
              wordBufferRef.current = []; // Reset buffer after detection
              setListening(false);
              startSpeechRecognition();
            }
          },
          {
            probabilityThreshold: 0.5,
            overlapFactor: 0.75,
            includeSpectrogram: false,
            invokeCallbackOnNoiseAndUnknown: false,
          }
        )
        .catch((error) => {
          console.error("Error during listening:", error);
        });
    }
  };

  const stopListening = () => {
    if (recognizer && recognizer.isListening()) {
      recognizer.stopListening().catch((error) => {
        console.error("Error stopping recognizer:", error);
      });
      setListening(false);
      setStatus("Stopped listening.");
      wordBufferRef.current = []; // Reset the buffer
      // Release the wake lock
      releaseWakeLock();
    }
  };

  const startSpeechRecognition = () => {
    if ("webkitSpeechRecognition" in window || "SpeechRecognition" in window) {
      const SpeechRecognition =
        window.SpeechRecognition || window.webkitSpeechRecognition;
      const recognition = new SpeechRecognition();

      recognition.lang = "en-US";
      recognition.interimResults = false;
      recognition.maxAlternatives = 1;

      let speechRecognized = false; // Flag to check if speech was recognized

      recognition.onstart = () => {
        console.log("Speech recognition started");
        setStatus("Listening for your command...");
      };

      recognition.onresult = (event) => {
        speechRecognized = true; // Speech was recognized
        const transcript = event.results[0][0].transcript;
        console.log("You said: ", transcript);
        setTranscript(transcript);
        setStatus("Processing your command...");

        // Send the recognized text to Home Assistant API
        sendToHomeAssistant(transcript);
      };

      recognition.onerror = (event) => {
        console.error("Speech recognition error:", event.error);
        setStatus(`Error: ${event.error}`);
        // Play error sound and restart wake word detection
        playErrorBeep();
        startListening();
      };

      recognition.onend = () => {
        console.log("Speech recognition ended");
        setListening(false);

        if (!speechRecognized) {
          // No speech was recognized
          console.log("No speech was recognized");
          setStatus("No speech was recognized. Please try again.");
          playErrorBeep();
          // Restart wake word detection
          startListening();
        }
        // If speech was recognized, we wait for the API response in sendToHomeAssistant
      };

      recognition.start();
    } else {
      alert("Speech Recognition API not supported in this browser.");
      setStatus("Speech Recognition API not supported.");
    }
  };

  // Function to send recognized text to the Next.js API route
  const sendToHomeAssistant = async (text) => {
    try {
      const response = await fetch("/api/home-assistant", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ text }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        console.error("Error from API route:", errorData);
        setStatus("Error processing your command.");
        // Speak error message and then play error beep
        const speechText = errorData.error || "Error processing your command.";
        speakText(speechText, () => {
          playErrorBeep(); // Error sound after speech
          startListening(); // Restart wake word detection
        });
        return;
      }

      const data = await response.json();
      console.log("API Route Response:", data);

      // Extract the speech text from the response
      const speechText = data.response?.speech?.plain?.speech || "";

      // Check response_type to determine success or error
      if (data.response && data.response.response_type === "action_done") {
        // Success
        setStatus(speechText || "Command executed successfully.");
        if (speechText) {
          speakText(speechText, () => {
            playSuccessBeep(); // Success sound after speech
            startListening(); // Restart wake word detection
          });
        } else {
          playSuccessBeep(); // Success sound immediately
          startListening(); // Restart wake word detection
        }
      } else {
        // Error
        setStatus(speechText || "Sorry, I couldn't understand that.");
        if (speechText) {
          speakText(speechText, () => {
            playErrorBeep(); // Error sound after speech
            startListening(); // Restart wake word detection
          });
        } else {
          playErrorBeep(); // Error sound immediately
          startListening(); // Restart wake word detection
        }
      }
    } catch (error) {
      console.error("Error sending command to API route:", error);
      setStatus("Error communicating with the server.");
      // Speak error message and then play error beep
      speakText("Error communicating with the server.", () => {
        playErrorBeep(); // Error sound after speech
        startListening(); // Restart wake word detection
      });
    }
  };

  return (
    <Container maxWidth="sm" sx={{ textAlign: "center", mt: 5 }}>
      <Typography variant="h4" gutterBottom>
        Voice Activated App
      </Typography>
      {loading ? (
        <Box sx={{ display: "flex", justifyContent: "center", my: 5 }}>
          <CircularProgress />
        </Box>
      ) : (
        <>
          <Typography variant="body1" gutterBottom aria-live="polite">
            {status}
          </Typography>
          <Box sx={{ my: 3 }}>
            <Button
              variant="contained"
              color={listening ? "secondary" : "primary"}
              onClick={listening ? stopListening : startListening}
              size="large"
            >
              {listening ? "Stop Listening" : "Start Listening"}
            </Button>
          </Box>
          {transcript && (
            <Box
              sx={{
                mt: 4,
                p: 2,
                border: "1px solid #ccc",
                borderRadius: "4px",
              }}
            >
              <Typography variant="h6">You said:</Typography>
              <Typography variant="body1">{transcript}</Typography>
            </Box>
          )}
        </>
      )}
    </Container>
  );
}
