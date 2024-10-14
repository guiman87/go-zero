# Go-Zero Custom Alexa App

Go-Zero is a custom voice assistant built using a Next.js app that leverages TensorFlow\.js and the Web Speech API. It acts as a "custom Alexa" that can run on your Home Assistant system and respond to custom commands. The app is installed as part of the custom Home Assistant add-on and utilizes advanced wake word detection for seamless voice interaction.

## Features

- **Wake Word Detection**: Utilizes TensorFlow\.js to detect the wake word "go zero."
- **Speech Recognition**: Processes spoken commands using the Web Speech API.
- **Custom Commands**: Allows for the creation of custom intents in Home Assistant.
- **Home Assistant Integration**: Works with your Home Assistant setup to control smart home devices.
- **Customizable Voice Options**: Configure voice pitch, rate, and selected voice for personalized responses.

## Installation

To install the Go-Zero Custom Alexa App, follow these steps:

1. Clone the repository to your local machine:

   ```sh
   git clone https://github.com/guiman87/go-zero.git
   ```

2. Navigate to the project directory:

   ```sh
   cd go-zero
   ```

3. Install the dependencies:

   ```sh
   npm install
   ```

4. Start the app:

   ```sh
   npm run start
   ```

   The app should now be running locally.

## Usage

### Wake Word Detection

The app uses TensorFlow\.js to detect the wake word "go zero." This means that the app is always listening in the background for the specific wake word, and once detected, it activates to process subsequent voice commands. This functionality allows you to trigger actions without any physical interaction.

### Custom Commands

Once the wake word is detected, Go-Zero listens for custom commands and communicates with Home Assistant via the `/api/home-assistant` endpoint. These commands can be configured to control various smart home devices or execute automation routines.

### Configuration

You can configure the following parameters through the app's UI:

- **Voice Selection**: Choose from available voices (e.g., Google UK English Male).
- **Sensitivity**: Adjust wake word detection sensitivity.
- **Pitch and Rate**: Customize the pitch and speaking rate of the app's responses.
- **Start Delay**: Set a delay for when speech starts after the wake word is detected.

All these configurations are saved in `localStorage`, allowing for persistence across sessions.

## Integration with Home Assistant

The Go-Zero app integrates directly with your Home Assistant instance. By providing your Home Assistant URL and access token, the app sends recognized intents to the Home Assistant API to trigger automations or control devices.

For example, after detecting the wake word and recognizing the spoken command, the app will make a POST request to the Home Assistant API with the recognized intent:

```js
const response = await fetch("/api/home-assistant", {
  method: "POST",
  headers: {
    "Authorization": `Bearer ${homeAssistantToken}`,
    "Content-Type": "application/json",
  },
  body: JSON.stringify({ text }),
});
```

## Contributing

We welcome contributions from the community! Feel free to open issues or submit pull requests to improve Go-Zero.

### How to Contribute

1. Fork the repository.
2. Create a new branch (`git checkout -b feature-branch`).
3. Make your changes and commit them (`git commit -m 'Add new feature'`).
4. Push to your branch (`git push origin feature-branch`).
5. Open a Pull Request.

## License

This project is licensed under the MIT License.

## Acknowledgements

- [TensorFlow.js](https://www.tensorflow.org/js) for providing the speech recognition capabilities.
- [Web Speech API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API) for supporting text-to-speech and speech recognition.
- The Home Assistant community for their support and integration capabilities.

