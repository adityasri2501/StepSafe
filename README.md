# SafeStep Indoor Navigation POC

**Native Android app:** open the [`android/`](android/README.md) project in Android Studio. It uses CameraX, OpenCV DNN + a bundled NanoDet ONNX model, Android Hindi speech APIs, and live BLE scanning/room advertising. See [Android run instructions](android/README.md). The browser app below is retained as the original visual prototype.

Phone-friendly hackathon prototype for a Hindi voice-first indoor navigation concept. The page attempts to start voice guidance and camera detection automatically; allow the browser's microphone and camera prompts.

## Run

Requires Node.js 20.19+ (or 22.12+), internet for dependency installation, and a GitHub Copilot account if you want the in-app Copilot assistant to respond.

```powershell
npm install
npm start
```

Open `http://localhost:4173`. To use from another phone on the same Wi-Fi, open `http://<computer-local-ip>:4173` on the phone. Camera and microphone permissions may require HTTPS outside localhost; use a secure tunnel for a phone demo. The browser must allow access to these devices.

For the Copilot SDK, authenticate the Copilot CLI on the host according to GitHub's Copilot SDK setup, then reload the page. The app sends only the user's Copilot chat prompt to `/api/copilot/chat`. Copilot answers questions about the demo; it does not calculate the navigation route. The route remains a deterministic route over the predefined graph.

## What the prototype demonstrates

- Accessible, responsive floor map with a demo location dot and destination routes.
- Hindi speech recognition (`hi-IN`) and Hindi speech output for navigation and safety prompts. Say **रसोई / किचन**, **लाइब्रेरी**, **बेडरूम**, **लोकेशन**, **कैमरा चालू**, **कैमरा बंद**, **दोहराओ**, **रुको**, or **मदद / खतरा**. English room names and commands are also accepted by supported browsers.
- Voice-selected destinations start spoken route prompts directly; voice repeat, stop, and vibration cues are available without using the route buttons.
- Live BLE advertisement scan where the browser supports `navigator.bluetooth.requestLEScan`. Detected device RSSI is smoothed and refreshed; select a detected advertiser as the Kitchen or Library map marker.
- Camera preview and MediaPipe EfficientDet Lite object detection start automatically when permission is granted; the model/WASM are fetched from their CDN/model host on first use. Repeated central detections trigger a slow-down cue; a large-in-view cue says stop and check, with vibration. Image-box size is not a distance measurement.
- Danger confirmation dialog with a 30-second timeout. It opens an SMS draft with the stored contact and last known/approximate room; the user still has to send it.
- In-app Copilot chat wired to the GitHub Copilot SDK backend.

## Boundaries

This web POC is for a controlled hackathon demonstration, not real-world assisted navigation. Voice recognition availability and language quality depend on the browser and installed speech services; microphone permission is required. The page requests camera access on load, but the browser/OS can deny or defer it. BLE scan access is experimental/limited in browsers and requires a secure context, user permission, and a user gesture; use supported Chrome on Android and HTTPS (localhost is also secure). For this reason BLE scan still needs its scan button. A marker must actually advertise BLE packets. A normal phone does not continuously advertise a room label by itself; run a BLE beacon/broadcaster app on the marker phones and configure its advertised name, or use actual beacon hardware. This web page cannot turn a phone into a BLE advertiser.

The map dot updates from the strongest assigned, recently heard BLE marker and means “near this marker,” not exact indoor positioning or verified room occupancy. Wall penetration, body blocking, antenna orientation, device model and environment all affect RSSI. Voice-selected routes are fixed demo directions from the drawn map; the app does not continuously verify heading, turns, or arrival. The camera detector recognizes only objects from its pretrained label set. Bounding-box size is not a safe distance measurement, and camera warnings are not collision prevention. MediaPipe provides the pretrained on-device object detector used here. OpenCV.js can run DNN models too, but requires compatible model/config/weights; adding OpenCV alone would not improve recognition. Reliable background operation, phone-to-phone BLE advertising and automatic SMS sending require a native mobile implementation and location/device-specific validation. Do not present this demo as a substitute for a cane, guide dog, or verified safety system.

## Copilot use

The app includes an optional in-app Copilot SDK assistant. GitHub Copilot is also useful during development for generating/refining code; it is not a replacement for the route graph or text-to-speech engine.
