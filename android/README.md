# SafeStep Android POC

Native Android app for the indoor-navigation hackathon POC. This is the mobile app; the older root-level HTML files are retained as a reference prototype.

## Open and run

1. Install Android Studio and its Android SDK Platform 35. Use a JDK 17 Gradle runtime (Android Studio's Gradle JDK setting).
2. In Android Studio choose **Open** and select this `android` folder.
3. Let Gradle sync download AndroidX, CameraX and the OpenCV 4.12.0 Android library. Wait for sync to finish.
4. Connect an Android phone with Developer options and USB debugging enabled, or create an Android emulator. A physical phone is required to demonstrate BLE markers and camera detection realistically.
5. Press **Run**. On first launch, allow microphone, camera, nearby-device and location permissions. Turn Bluetooth on when Android asks.
6. The app starts Hindi voice recognition, the back camera/OpenCV detector, and BLE scanning after permissions are granted. Android Text-to-speech must have Hindi voice data installed.

The app has a bundled OpenCV Zoo NanoDet-Plus 1.5x ONNX model, so model inference itself works offline. Gradle still needs internet the first time to download Maven dependencies.

## Two-phone BLE room demo

1. Install and launch SafeStep on the navigation phone and marker phone(s); grant Nearby devices and Location permissions. Keep both navigation and marker apps in the foreground while testing. BLE markers are connectionless broadcasts; the phones do not pair or connect to each other.
2. On each marker phone open **Room Markers → Act as a map marker** and choose its mapped point: Entrance, Junction, Kitchen, Library or Bedroom. Keep the marker app open while it advertises. For an Entrance → Junction → Kitchen demo, four phones (one navigation phone plus three marker phones) give the clearest continuous hand-off. With only two phones, move the beacon phone to the next expected point during the demo.
3. On the navigation phone say **“take me to kitchen”**, **“library”**, or **“bedroom”**. It gives the first direction, then gives the next direction after it confirms the next BLE marker on the route. Say **“where am I?”**, **“repeat”**, or **“cancel navigation”** at any time. With no confirmed marker, it reports the location as unknown and will not invent an Entrance position.
4. Keep markers separated and at their actual map points. SafeStep markers broadcast a versioned room ID and unique marker ID in BLE service data, so the app can track several marker phones independently without relying on the phone's ordinary Bluetooth name. A new room needs at least four candidate-room readings spanning 1.8 seconds and an 8 dBm advantage over the current room; weak readings below -88 dBm are not used as a room fix. Readings expire after 12 seconds, including when scanning fails. These filters reduce rapid room flipping but cannot identify exact distance: walls, phone orientation and radio conditions can still produce wrong room changes. Android 11 and older also require the phone's Location setting to be on for BLE scanning.

## Copilot SDK assistant

The existing Copilot SDK server remains at the project root. Start it on the computer with `npm install` once and `npm start`; sign in to GitHub Copilot on that computer as described in the root README. For a USB-connected physical phone, run `adb reverse tcp:4173 tcp:4173`; the app uses `http://127.0.0.1:4173`. An emulator uses `http://10.0.2.2:4173`. On a physical phone over Wi-Fi instead, set **Server URL** to the computer's LAN address, e.g. `http://192.168.1.20:4173`, with both devices on the same network. The app also accepts spoken prompts like **“Assistant, explain the route demo”**. For anything beyond a local demo, configure HTTPS and do not expose the unauthenticated development endpoint to the internet.

## Camera and route scope

CameraX streams rear-camera frames to OpenCV DNN on-device using the OpenCV Zoo NanoDet-Plus 1.5x COCO model. Camera frames are converted RGBA→RGB as required by the Zoo preprocessing, then resized/letterboxed and normalized. The live preview overlays detected COCO boxes, names and confidence. A rule-based camera decision layer prioritizes detections overlapping the lower central walking lane: it says slow for a detected lane obstacle and stop for a large/lower-frame lane obstacle. A side-only detection produces a left/right caution, not an instruction to turn. Decisions persist across two inference frames and cues have a cooldown. Box size and lower-frame position are visual urgency clues, not calibrated distance; no detection does not prove the path is clear. COCO models can miss objects outside their classes, glass, stairs, floor-level items or obstacles in poor lighting. Treat these cues as experimental only.

The Dijkstra route graph has a demo entrance, junction and three rooms. Voice navigation advances at the next expected BLE map marker; place markers at turns as well as destinations for turn-by-turn prompts. Without a current marker it assumes the entrance and says so. Edge lengths and turn directions are sample map data that must be measured and edited for the demo site. BLE does not track phone heading, confirm a user's turn, measure walking distance or guarantee arrival. Never use this prototype instead of a cane, guide dog or human assistance.

Emergency help asks for voice confirmation and, after 30 seconds without confirmation, submits an SMS to each saved trusted member and calls the first member. The SMS includes the approximate BLE room and a recent GPS coordinates link when Android has a location fix (up to 10 minutes old). Android requests SMS and phone permissions after trusted members are configured. If permission is denied or SMS cannot be submitted, the app reports that it was not sent. SMS submission does not confirm carrier delivery, and a call may fail or go unanswered. The app only calls the first saved member; test with consenting contacts and a real SIM, not the emulator.

Voice commands include room navigation, location, obstacle camera on/off, emergency help and confirmation, trusted contact setup (say "add trusted contact," then speak the name and digits), BLE room beacon controls, and direct Copilot questions (say "ask Copilot ..."). System permissions and initial trusted-member setup still require Android's permission dialog; radio, SIM and network operations require device support.

Voice recognition uses Android's system speech service in network-capable mode because Android emulators commonly lack an offline Hindi speech model. Allow microphone permission, enable the emulator's **Virtual microphone uses host audio input** control, and ensure the host and emulator have internet. On a phone, verify Hindi is available in the installed speech-recognition service. TTS Hindi voice data may also need to be installed separately.

## Detector asset

`app/src/main/assets/object_detection_nanodet_2022nov.onnx` is NanoDet-Plus 1.5x, input 416 x 416, full precision. OpenCV DNN uses CPU backend, confidence threshold 0.25, three paired feature levels (strides 8/16/32), distribution-focal regression decoding and class-wise NMS IoU 0.60. The OpenCV Zoo model directory states Apache-2.0 licensing; see `app/src/main/assets/MODEL-NOTICE.txt`.
