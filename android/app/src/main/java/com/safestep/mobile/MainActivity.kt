package com.safestep.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.location.LocationManager
import android.location.Location
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val copilotExecutor = Executors.newSingleThreadExecutor()
    private val bleRooms = mutableMapOf<String, RoomSignal>()
    private data class TrustedMember(val name: String, val phone: String)
    private val trustedMembers = mutableListOf<TrustedMember>()
    private data class RoomSignal(val room: String, var rssi: Double, var seenAt: Long)
    private data class Edge(val to: String, val meters: Int, val instructionHi: String)
    private data class RouteNode(val key: String, val edges: List<Edge>)

    private lateinit var statusView: TextView
    private lateinit var locationView: TextView
    private lateinit var movementView: TextView
    private lateinit var detectionView: TextView
    private lateinit var indoorMapView: IndoorMapView
    private lateinit var mapStatusView: TextView
    private lateinit var cameraButton: Button
    private lateinit var markerButton: Button
    private lateinit var bleStatusView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var detectorOverlay: DetectorOverlayView
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: View
    private lateinit var trustedMembersView: LinearLayout
    private lateinit var copilotStatus: TextView
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechIntent: Intent? = null
    private var ttsReady = false
    private var greeted = false
    private var voiceEnabled = true
    private var currentlySpeaking = false
    private var listeningNow = false
    private var userSpeaking = false
    private var pushToTalkHeld = false
    private var pushToTalkSession = false
    private var lastTtsCompletedAt = 0L
    private var deferredSpeech: String? = null
    private var speechAfterCurrent: String? = null
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var lastStepAt = 0L
    private var walkingNow = false
    private var movementRegistered = false
    private var drawerOpen = false
    private var listenStartRunnable: Runnable? = null
    private var holdGestureRunnable: Runnable? = null
    private var holdGestureActivated = false
    private var holdGestureDownTime = 0L
    private var holdGestureX = 0f
    private var holdGestureY = 0f
    private var activeTtsUtteranceId: String? = null
    private var cameraEnabled = false
    private var detector: NanoDetDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanCallback: ScanCallback? = null
    private var scanRetryRunnable: Runnable? = null
    private var activityStarted = false
    private val bleExpiryRunnable = object : Runnable {
        override fun run() {
            if (!activityStarted) return
            removeExpiredBle()
            mainHandler.postDelayed(this, 2500L)
        }
    }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var activeRoomBeacon: String? = null
    private var selectedRoomBeacon: String? = null
    private var bluetoothReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopBleScan()
                    stopAdvertisingRoom(clearSelection = false)
                    val hadLocation = currentRoom != null
                    bleRooms.clear()
                    currentRoom = null
                    candidateRoom = null
                    candidateRoomHits = 0
                    candidateRoomSince = 0L
                    if (navigationTarget != null) {
                        navigationTarget = null
                        navigationPath = emptyList()
                    }
                    if (::locationView.isInitialized) locationView.text = "जगह अभी पता नहीं है"
                    updateIndoorMapView()
                    setStatus("Bluetooth band hai · BLE location aur markers pause hain")
                    updateBleStatus(true)
                    if (hadLocation) speak("Bluetooth band ho gaya. Indoor location ab unknown hai.")
                }
                BluetoothAdapter.STATE_ON -> mainHandler.postDelayed({
                    if (!activityStarted) return@postDelayed
                    if (hasBleScanPermission()) startBleScan()
                    selectedRoomBeacon?.let { startAdvertisingRoom(it) }
                }, 700L)
            }
        }
    }
    private var dangerPending = false
    private var emergencyFlowActive = false
    private var dangerSeconds = 30
    private var dangerDeadlineElapsed = 0L
    private var dangerTimerRunnable: Runnable? = null
    private val utteranceCompletionHandlers = mutableMapOf<String, () -> Unit>()
    private var emergencyDispatchWaitingForPermission = false
    private var trustedContactVoiceStep: String? = null
    private var voiceContactName = ""
    private var voiceContactPhone = ""
    private var currentRoom: String? = null
    private var candidateRoom: String? = null
    private var candidateRoomHits = 0
    private var candidateRoomSince = 0L
    private var candidateLastSampleAt = 0L
    private var lastBleUiUpdateAt = 0L
    private var navigationPath: List<String> = emptyList()
    private var navigationIndex = 0
    private var navigationTarget: String? = null
    private var lastAlertLevel = 0
    private var lastObstacleCueKey = ""
    private var lastObstacleCueAt = 0L
    private var clearFrames = 0
    private var candidateObstacleKey = ""
    private var candidateObstacleHits = 0
    private var lastInferenceAt = 0L
    private var copilotBaseUrl = if (
        Build.FINGERPRINT.lowercase(Locale.ROOT).contains("generic") || Build.MODEL.contains("Emulator", ignoreCase = true)
    ) "http://10.0.2.2:4173" else "http://127.0.0.1:4173"
    // Markers at entrance/junction are needed for live turn-by-turn progress; room-only markers give approximate room proximity.
    private val knownRooms = listOf("Entrance", "Junction", "Kitchen", "Library", "Bedroom")
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                lastStepAt = SystemClock.elapsedRealtime()
                refreshMovementState()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }
    }
    private val movementMonitor = object : Runnable {
        override fun run() {
            refreshMovementState()
            if (!isFinishing && !isDestroyed) mainHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) { initializeSpeech(); maybeGreet() }
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        if (grants[Manifest.permission.ACTIVITY_RECOGNITION] == true) startMovementTracking()
        if (hasBleScanPermission()) startBleScan()
        if (trustedMembers.isNotEmpty()) requestEmergencyPermissions()
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) setStatus("Kuch permission deny hui. Voice, camera, BLE settings mein alag se allow karein.")
    }
    private val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startBleScan()
            selectedRoomBeacon?.let { startAdvertisingRoom(it) }
        } else setStatus("BLE room scan ke liye Bluetooth on karein.")
    }
    private val emergencyPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val smsAllowed = grants[Manifest.permission.SEND_SMS] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val callAllowed = grants[Manifest.permission.CALL_PHONE] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (emergencyDispatchWaitingForPermission) {
            emergencyDispatchWaitingForPermission = false
            dispatchEmergency(smsAllowed, callAllowed)
        } else {
            setStatus(if (smsAllowed && callAllowed) "Emergency SMS aur primary trusted member call permissions ready hain." else "Auto emergency SMS/call permission allow nahi hui.")
            speak(if (smsAllowed && callAllowed) "Emergency contact permissions taiyaar hain." else "Emergency SMS ya call permission allow nahi hui.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this, this)
        copilotBaseUrl = getPreferences(MODE_PRIVATE).getString("copilot_url", copilotBaseUrl) ?: copilotBaseUrl
        if (!isEmulator() && copilotBaseUrl == "http://10.0.2.2:4173") copilotBaseUrl = "http://127.0.0.1:4173"
        loadTrustedMembers()
        buildAccessibleUi()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        requestNeededPermissions()
    }

    private fun buildAccessibleUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val cameraFrame = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Live rear camera view and obstacle guidance"
        }
        detectorOverlay = DetectorOverlayView()
        cameraFrame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(detectorOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cameraFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(244, 247, 245)) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
        }
        scroll.addView(page)

        val drawerHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        drawerHeader.addView(text("SAFESTEP  ·  Controls", 19, Color.rgb(20, 55, 40), true), LinearLayout.LayoutParams(0, dp(54), 1f))
        drawerHeader.addView(actionButton("बंद करें", "Close the controls panel") { setDrawerOpen(false) }, LinearLayout.LayoutParams(dp(100), dp(48)))
        page.addView(drawerHeader, marginParams(bottom = 8))

        fun panelSection(title: String, tint: Int = Color.WHITE): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(13))
            background = roundedBackground(tint, Color.rgb(214, 225, 217))
            addView(text(title, 15, Color.rgb(20, 55, 40), true), marginParams(bottom = 8))
        }

        statusView = text("Camera, voice aur Bluetooth start ho rahe hain…", 15, Color.WHITE, true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(Color.argb(205, 12, 33, 26), Color.argb(205, 12, 33, 26))
            maxLines = 2
        }

        val navigationSection = panelSection("MAP & NAVIGATION")
        val locationCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(13))
            background = roundedBackground(Color.rgb(228, 240, 232), Color.rgb(198, 219, 204))
            addView(text("आपकी अनुमानित जगह", 14, Color.rgb(70, 91, 76), true), marginParams(bottom = 3))
        }
        locationView = text("जगह अभी पता नहीं है", 21, Color.rgb(18, 36, 29), true)
        locationView.contentDescription = "Approximate current room from the nearest BLE marker"
        locationCard.addView(locationView)
        movementView = text("गतिविधि: चालू sensor शुरू हो रहा है…", 14, Color.rgb(70, 91, 76), false)
        locationCard.addView(movementView, marginParams(top = 5))
        navigationSection.addView(locationCard, marginParams(bottom = 9))

        val mapCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.WHITE, Color.rgb(214, 225, 217))
        }
        mapCard.addView(text("घर का indoor map", 18, Color.rgb(20, 55, 40), true), marginParams(bottom = 3))
        mapStatusView = text("BLE marker का इंतज़ार · location अभी unknown", 13, Color.rgb(79, 94, 83), false)
        mapCard.addView(mapStatusView, marginParams(bottom = 6))
        indoorMapView = IndoorMapView()
        mapCard.addView(indoorMapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        mapCard.addView(text("नीला dot = BLE से अनुमानित marker · gray = sample floor plan", 12, Color.rgb(79, 94, 83), false), marginParams(top = 5))
        navigationSection.addView(mapCard, marginParams(bottom = 9))
        updateIndoorMapView()

        navigationSection.addView(text("जल्दी चुनें · या बोलकर बताएं", 14, Color.rgb(70, 91, 76), true), marginParams(bottom = 5))
        val destinations = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("रसोई" to "Kitchen", "लाइब्रेरी" to "Library", "बेडरूम" to "Bedroom").forEach { (label, destination) ->
            val shortcut = actionButton(label, "Take me to $destination") { startRoute(destination) }.apply {
                textSize = 15f
                minHeight = dp(56)
            }
            destinations.addView(shortcut, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginEnd = dp(4)
                marginStart = dp(4)
            })
        }
        navigationSection.addView(destinations)
        page.addView(navigationSection, marginParams(bottom = 10))

        val helpCard = panelSection("SAFETY & EMERGENCY", Color.rgb(255, 248, 247)).apply {
            background = roundedBackground(Color.rgb(255, 241, 239), Color.rgb(238, 204, 199))
        }
        helpCard.addView(actionButton("मुझे मदद चाहिए  ·  DANGER", "Say help or danger to start the emergency confirmation.", danger = true) { beginEmergency() }, marginParams(bottom = 4))
        helpCard.addView(text("Danger prompt ke baad ‘हाँ’ ya ‘नहीं’ seedha boliye—tap/hold nahi chahiye. 30 सेकंड में jawab na mile toh trusted members ko alert bhejne ki koshish hogi.", 13, Color.rgb(111, 61, 55), false))
        page.addView(helpCard, marginParams(bottom = 8))

        val cameraSection = panelSection("CAMERA & OBSTACLES")
        detectionView = text("OpenCV detector starting…", 16, Color.rgb(18, 36, 29), true)
        cameraSection.addView(detectionView, marginParams(bottom = 5))
        cameraButton = actionButton("Turn camera off", "Camera toggle. Camera starts automatically after permission.") { if (cameraEnabled) stopCamera() else startCamera() }
        cameraSection.addView(cameraButton, marginParams(bottom = 6))
        cameraSection.addView(text("Camera exact distance nahi naapta. Obstacle cue ke saath cane use karein.", 13, Color.rgb(90, 82, 63), false))
        page.addView(cameraSection, marginParams(bottom = 10))

        val markerSection = panelSection("ROOM MARKERS · BLUETOOTH")
        markerSection.addView(text("Har map point par SafeStep marker phone kholkar room select karein aur screen khuli rakhein. Navigation phone BLE scan karke nearest marker ka approximate room update karega.", 14, Color.rgb(65, 76, 69), false), marginParams(bottom = 7))
        markerButton = actionButton("Act as a map marker", "Advertise this phone as an Entrance, Junction, Kitchen, Library or Bedroom map marker.") { chooseBeaconRoom() }
        markerSection.addView(markerButton, marginParams(bottom = 5))
        bleStatusView = text("BLE scan starting…", 14, Color.rgb(35, 74, 54), true)
        markerSection.addView(bleStatusView, marginParams(bottom = 6))
        markerSection.addView(actionButton("Where am I? / मेरी लोकेशन", "Hear the strongest recent room beacon estimate.") { announceLocation() })
        page.addView(markerSection, marginParams(bottom = 10))

        val trustedSection = panelSection("TRUSTED CONTACTS")
        trustedSection.addView(text("Emergency par members ko SMS; pehle contact ko call.", 14, Color.rgb(90, 82, 63), false), marginParams(bottom = 6))
        trustedMembersView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        trustedSection.addView(trustedMembersView, marginParams(bottom = 5))
        trustedSection.addView(actionButton("+ Add trusted member", "Add a trusted person's name and phone number.") { showAddTrustedMemberDialog() })
        page.addView(trustedSection, marginParams(bottom = 10))

        val copilotSection = panelSection("GITHUB COPILOT · OPTIONAL")
        copilotStatus = text("Optional · connect to the existing Copilot SDK server", 14, Color.rgb(75, 88, 80), false)
        copilotSection.addView(copilotStatus, marginParams(bottom = 5))
        copilotSection.addView(actionButton("Ask GitHub Copilot", "Ask the optional GitHub Copilot assistant a question.") { openCopilotDialog() }, marginParams(bottom = 5))
        copilotSection.addView(text("Copilot route ya safety cues control nahi karta.", 13, Color.rgb(90, 82, 63), false))
        page.addView(copilotSection, marginParams(bottom = 8))
        renderTrustedMembers()

        drawerScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { setDrawerOpen(false) }
            contentDescription = "Close controls panel"
        }
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(12), dp(12), dp(72), 0)
        })
        val menuButton = actionButton("☰", "Open navigation, map, emergency and settings") { setDrawerOpen(true) }.apply {
            textSize = 21f
            contentDescription = "Menu. Open the side panel."
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(12), dp(12), 0)
        })
        root.addView(drawerScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val drawerWidth = minOf((resources.displayMetrics.widthPixels * .88f).toInt(), dp(420))
        drawerPanel = scroll
        scroll.visibility = View.GONE
        root.addView(scroll, FrameLayout.LayoutParams(drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        setContentView(root)
    }

    private fun loadTrustedMembers() {
        val preferences = getPreferences(MODE_PRIVATE)
        val saved = preferences.getString("trusted_members", null)
        if (saved == null) {
            val legacyPhone = preferences.getString("contact", "").orEmpty().trim()
            if (legacyPhone.isNotBlank()) {
                trustedMembers.add(TrustedMember("Trusted contact", legacyPhone))
                saveTrustedMembers()
            }
            return
        }
        try {
            val array = org.json.JSONArray(saved)
            for (index in 0 until array.length()) {
                val member = array.optJSONObject(index) ?: continue
                val name = member.optString("name").trim()
                val phone = member.optString("phone").trim()
                if (name.isNotBlank() && phone.isNotBlank()) trustedMembers.add(TrustedMember(name, phone))
            }
        } catch (_: Exception) { trustedMembers.clear() }
    }

    private fun saveTrustedMembers() {
        val array = org.json.JSONArray()
        trustedMembers.forEach { array.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        getPreferences(MODE_PRIVATE).edit().putString("trusted_members", array.toString()).apply()
    }

    private fun renderTrustedMembers() {
        if (!::trustedMembersView.isInitialized) return
        trustedMembersView.removeAllViews()
        if (trustedMembers.isEmpty()) {
            trustedMembersView.addView(text("Abhi koi trusted member add nahi hai.", 14, Color.rgb(90, 82, 63), false), marginParams(bottom = 5))
            return
        }
        trustedMembers.toList().forEach { member ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(6), dp(6))
                background = roundedBackground(Color.WHITE, Color.rgb(224, 231, 226))
            }
            val details = text("${member.name}\n${member.phone}", 15, Color.rgb(30, 47, 37), true)
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = Button(this).apply {
                text = "Remove"; textSize = 13f; isAllCaps = false; minHeight = dp(42)
                contentDescription = "Remove ${member.name} from trusted members"
                setTextColor(Color.rgb(157, 44, 37)); setOnClickListener {
                    trustedMembers.remove(member); saveTrustedMembers(); renderTrustedMembers()
                }
            }
            row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            trustedMembersView.addView(row, marginParams(bottom = 5))
        }
    }

    private fun showAddTrustedMemberDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }
        val nameInput = EditText(this).apply {
            hint = "Member name"
            textSize = 17f
            setSingleLine(true)
            contentDescription = "Trusted member name"
        }
        val phoneInput = EditText(this).apply {
            hint = "Phone with country code, e.g. +91..."
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 17f
            setSingleLine(true)
            contentDescription = "Trusted member phone number with country code"
        }
        form.addView(nameInput, marginParams(bottom = 8))
        form.addView(phoneInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add trusted member")
            .setMessage("Emergency par SMS aur primary contact ko call ke liye use hoga.")
            .setView(form)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim().replace("[\\s()-]".toRegex(), "")
                if (name.isBlank() || !phone.matches(Regex("\\+?[0-9]{7,15}"))) {
                    setStatus("Member ka naam aur valid phone number country code ke saath bharein.")
                    speak("Naam aur phone number dobara jaanch kar bharein.")
                    return@setOnClickListener
                }
                if (trustedMembers.any { it.phone == phone }) {
                    setStatus("Yeh phone number pehle se trusted list mein hai.")
                    speak("Yeh number pehle se joda hua hai.")
                    return@setOnClickListener
                }
                trustedMembers.add(TrustedMember(name, phone))
                saveTrustedMembers()
                renderTrustedMembers()
                setStatus("$name trusted members mein add ho gaye.")
                speak("$name trusted members mein jod diye gaye.")
                dialog.dismiss()
                requestEmergencyPermissions()
            }
        }
        dialog.show()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 29) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        val needed = permissions.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            startGrantedFeatures()
            if (trustedMembers.isNotEmpty()) requestEmergencyPermissions()
        } else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startGrantedFeatures() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { initializeSpeech(); maybeGreet() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        if (hasBleScanPermission()) startBleScan()
    }

    private fun initializeSpeech() {
        if (!voiceEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { setStatus("Is phone par voice recognition service available nahi hai."); return }
        if (speechRecognizer == null) {
            // Prefer the system recognizer's network-capable service: emulators often lack Hindi offline packs.
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listeningNow = true; runOnUiThread { if (!currentlySpeaking) setStatus("अब बोलिए: मंज़िल, लोकेशन या मदद") } }
                override fun onBeginningOfSpeech() { userSpeaking = true; runOnUiThread { setStatus("आवाज़ मिल रही है · बोलना जारी रखें") } }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { userSpeaking = false }
                override fun onError(error: Int) {
                    listeningNow = false
                    userSpeaking = false
                    if (error == SpeechRecognizer.ERROR_CLIENT && pushToTalkHeld) return
                    if (pushToTalkHeld && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && error != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                        pushToTalkSession = true
                        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                            runOnUiThread { setStatus("Voice service check ho rahi hai · button dabaye rakhein") }
                        }
                        scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 700 else 1100)
                        return
                    }
                    pushToTalkSession = false
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        voiceEnabled = false
                        runOnUiThread { setStatus("Microphone permission allow karein, phir screen ko daba kar command boliye.") }
                        return
                    }
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                        runOnUiThread { setStatus("Hindi speech service unavailable. Internet on karein aur Google Speech Services mein Hindi add karein.") }
                    } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                        runOnUiThread { setStatus("Voice recognition ke liye internet ya speech service check karein.") }
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                        runOnUiThread { setStatus("आवाज़ साफ़ नहीं मिली · फोन के mic के पास बोलकर फिर कोशिश करें") }
                    } else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        runOnUiThread { setStatus("कुछ सुनाई नहीं दिया · mic input चालू करके बोलें") }
                    } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                        runOnUiThread { setStatus("Voice service restart ho rahi hai. Mic permission aur Google Speech Services check karein.") }
                    }
                    val deferred = deferredSpeech
                    if (deferred != null && error != SpeechRecognizer.ERROR_CLIENT) {
                        deferredSpeech = null
                        mainHandler.postDelayed({ if (!currentlySpeaking) speak(deferred) else speechAfterCurrent = deferred }, 250)
                    }
                    if (dangerPending && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error != SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && error != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                        scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1200 else 700)
                    }
                }
                override fun onResults(results: Bundle?) {
                    listeningNow = false
                    userSpeaking = false
                    pushToTalkSession = false
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (words.isNotBlank() && isRecentTtsEcho(words)) {
                        setStatus("Assistant ki awaaz ignore ki · ab aap bol sakte hain")
                    } else if (words.isNotBlank()) {
                        if (trustedContactVoiceStep != null) handleTrustedContactVoice(words) else handleVoiceCommand(words)
                    }
                    val deferred = deferredSpeech
                    deferredSpeech = null
                    if (deferred != null) {
                        if (currentlySpeaking) speechAfterCurrent = deferred else speak(deferred)
                    } else if (dangerPending && !currentlySpeaking) {
                        scheduleListen(350)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) runOnUiThread { setStatus("सुना: $partial") }
                }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
        }
    }

    private fun scheduleListen(delay: Long) {
        listenStartRunnable?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            listenStartRunnable = null
            if (!voiceEnabled || (!pushToTalkHeld && !dangerPending) || currentlySpeaking || listeningNow || isFinishing || isDestroyed) return@Runnable
            val recognizer = speechRecognizer ?: return@Runnable
            try {
                listeningNow = true
                recognizer.startListening(speechIntent)
            } catch (_: Exception) {
                listeningNow = false
                setStatus("Mic dobara chalu ho raha hai. Thodi der mein boliye.")
                scheduleListen(1200)
            }
        }
        listenStartRunnable = task
        mainHandler.postDelayed(task, delay)
    }

    private fun beginPushToTalk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            setStatus("Voice command ke liye microphone permission allow karein.")
            return
        }
        voiceEnabled = true
        initializeSpeech()
        listenStartRunnable?.let(mainHandler::removeCallbacks)
        listenStartRunnable = null
        textToSpeech?.stop()
        currentlySpeaking = false
        activeTtsUtteranceId = null
        speechAfterCurrent = null
        deferredSpeech = null
        speechRecognizer?.cancel()
        listeningNow = false
        userSpeaking = false
        pushToTalkHeld = true
        pushToTalkSession = true
        setStatus("बोलिए… बोलना पूरा हो तो स्क्रीन छोड़ दें")
        vibrate(longArrayOf(45, 55, 45))
        scheduleListen(180)
    }

    private fun endPushToTalk() {
        if (!pushToTalkHeld) return
        pushToTalkHeld = false
        vibrate(longArrayOf(45))
        if (listeningNow) {
            try { speechRecognizer?.stopListening() } catch (_: Exception) { pushToTalkSession = false }
            setStatus("आपकी बात समझ रहा है…")
        } else {
            listenStartRunnable?.let(mainHandler::removeCallbacks)
            listenStartRunnable = null
            pushToTalkSession = false
            setStatus("Command सुनने के लिए screen ko दबाकर रखें।")
        }
    }

    private fun setDrawerOpen(open: Boolean) {
        if (!::drawerPanel.isInitialized || !::drawerScrim.isInitialized) return
        drawerOpen = open
        if (open) {
            drawerScrim.visibility = View.VISIBLE
            drawerScrim.alpha = 0f
            drawerScrim.animate().alpha(1f).setDuration(160).start()
            drawerPanel.visibility = View.VISIBLE
            drawerPanel.translationX = drawerPanel.width.takeIf { it > 0 }?.toFloat() ?: dp(360).toFloat()
            drawerPanel.animate().translationX(0f).setDuration(210).start()
            drawerPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            drawerScrim.animate().alpha(0f).setDuration(140).withEndAction { drawerScrim.visibility = View.GONE }.start()
            drawerPanel.animate().translationX(drawerPanel.width.toFloat()).setDuration(180).withEndAction {
                if (!drawerOpen) drawerPanel.visibility = View.GONE
            }.start()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdGestureActivated = false
                holdGestureDownTime = event.downTime
                holdGestureX = event.x
                holdGestureY = event.y
                if (voiceEnabled && !pushToTalkHeld && !dangerPending) {
                    val pending = Runnable {
                        holdGestureRunnable = null
                        holdGestureActivated = true
                        cancelPendingTapGesture()
                        beginPushToTalk()
                    }
                    holdGestureRunnable = pending
                    mainHandler.postDelayed(pending, 450L)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holdGestureRunnable?.let(mainHandler::removeCallbacks)
                holdGestureRunnable = null
                if (holdGestureActivated) {
                    endPushToTalk()
                    holdGestureActivated = false
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (holdGestureActivated) return true
                val dx = event.x - holdGestureX
                val dy = event.y - holdGestureY
                if (dx * dx + dy * dy > dp(14) * dp(14).toFloat()) {
                    holdGestureRunnable?.let(mainHandler::removeCallbacks)
                    holdGestureRunnable = null
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelPendingTapGesture() {
        val cancel = MotionEvent.obtain(
            holdGestureDownTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            holdGestureX,
            holdGestureY,
            0
        )
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) setDrawerOpen(false) else super.onBackPressed()
    }

    private var lastSpoken = ""

    private fun isRecentTtsEcho(raw: String): Boolean {
        if (SystemClock.elapsedRealtime() - lastTtsCompletedAt > 6000L) return false
        fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val heard = normalize(raw)
        val spoken = normalize(lastSpoken)
        // Keep short confirmations like "yes/no/haan/nahi" usable during emergency prompts.
        return heard.length >= 8 && spoken.length >= 8 && (spoken.contains(heard) || heard.contains(spoken))
    }

    private fun speak(message: String, flush: Boolean = true, onComplete: (() -> Unit)? = null) {
        if (pushToTalkSession) return
        if (userSpeaking) {
            deferredSpeech = message
            return
        }
        if (!ttsReady || message.isBlank()) { lastSpoken = message; setStatus(message); onComplete?.invoke(); return }
        lastSpoken = message
        if (flush) textToSpeech?.stop()
        speechRecognizer?.cancel()
        listeningNow = false
        currentlySpeaking = true
        val id = "speech-${System.nanoTime()}"
        activeTtsUtteranceId = id
        if (onComplete != null) utteranceCompletionHandlers[id] = onComplete
        val result = textToSpeech?.speak(message, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (result == TextToSpeech.ERROR) utteranceCompletionHandlers.remove(id)?.invoke()
    }

    private fun speakRouteLines(lines: List<String>) {
        if (pushToTalkSession || userSpeaking) return
        speechRecognizer?.cancel()
        currentlySpeaking = true
        textToSpeech?.stop()
        var finalUtteranceId: String? = null
        lines.forEachIndexed { index, line ->
            lastSpoken = line
            val id = "route-$index-${System.nanoTime()}"
            textToSpeech?.speak(line, TextToSpeech.QUEUE_ADD, Bundle(), id)
            finalUtteranceId = id
            if (index < lines.lastIndex) vibrate(longArrayOf(55, 45, 55))
        }
        lastRouteUtteranceId = finalUtteranceId
        activeTtsUtteranceId = finalUtteranceId
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { setStatus("Hindi speech engine start nahi ho paya."); return }
        val result = textToSpeech?.setLanguage(Locale("hi", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        textToSpeech?.setSpeechRate(.9f)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { }
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    utteranceId?.let { utteranceCompletionHandlers.remove(it) }?.invoke()
                    if (utteranceId != activeTtsUtteranceId) return@runOnUiThread
                    if (utteranceId?.startsWith("route-") == true && utteranceId != lastRouteUtteranceId) return@runOnUiThread
                    lastTtsCompletedAt = SystemClock.elapsedRealtime()
                    currentlySpeaking = false
                    val queued = speechAfterCurrent
                    if (queued != null) {
                        speechAfterCurrent = null
                        speak(queued)
                    } else if (voiceEnabled) scheduleListen(900)
                }
            }
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    utteranceId?.let { utteranceCompletionHandlers.remove(it) }?.invoke()
                    if (utteranceId != activeTtsUtteranceId) return@runOnUiThread
                    lastTtsCompletedAt = SystemClock.elapsedRealtime()
                    currentlySpeaking = false
                    val queued = speechAfterCurrent
                    if (queued != null) {
                        speechAfterCurrent = null
                        speak(queued)
                    } else if (voiceEnabled) scheduleListen(900)
                }
            }
        })
        if (!ttsReady) setStatus("Phone mein Hindi voice data nahi hai. Android Text-to-speech settings mein Hindi install karein.")
        maybeGreet()
    }

    private fun maybeGreet() {
        if (greeted || !ttsReady || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        greeted = true
        voiceEnabled = true
        initializeSpeech()
        speak("नमस्ते। वॉइस और कैमरा चालू हो रहे हैं। कोई command बोलने के लिए screen को आधा सेकंड दबाकर रखें, बोलना पूरा हो तो छोड़ दें। मंज़िल के लिए रसोई, लाइब्रेरी, या बेडरूम बोलें। मदद के लिए खतरा बोलें।")
    }

    private var lastRouteUtteranceId: String? = null

    private fun handleVoiceCommand(raw: String) {
        val command = raw.lowercase(Locale.ROOT)
        if (trustedContactVoiceStep != null) { handleTrustedContactVoice(raw); return }
        if (dangerPending) {
            when {
                isNegative(command) -> cancelEmergency()
                isAffirmative(command) -> confirmEmergency()
                else -> speak("कृपया yes या हाँ बोलें, या no या नहीं बोलकर रद्द करें।")
            }
            return
        }
        when {
            command.contains("open menu") || command.contains("show menu") || command.contains("settings") || command.contains("map kholo") || command.contains("मेन्यू खोल") || command.contains("सेटिंग खोल") || command.contains("मैप खोल") -> { setDrawerOpen(true); speak("Controls panel khol diya.") }
            command.contains("close menu") || command.contains("hide menu") || command.contains("मेन्यू बंद") || command.contains("पैनल बंद") -> { setDrawerOpen(false); speak("Controls panel band kar diya.") }
            command.contains("add trusted") || command.contains("add contact") || command.contains("संपर्क जोड़") || command.contains("कॉन्टैक्ट जोड़") || command.contains("member जोड़") -> beginTrustedContactVoiceSetup()
            command.startsWith("ask copilot ") -> sendCopilotQuestion(raw.substring("ask copilot".length).trim())
            command.startsWith("hey assistant ") -> sendCopilotQuestion(raw.substring("hey assistant".length).trim())
            command.startsWith("assistant ") -> sendCopilotQuestion(raw.substring("assistant".length).trim())
            command.startsWith("copilot ") -> sendCopilotQuestion(raw.substring("copilot".length).trim())
            command.startsWith("कोपायलट से पूछो ") -> sendCopilotQuestion(raw.substringAfter("कोपायलट से पूछो").trim())
            command.contains("stop beacon") || command.contains("beacon बंद") || command.contains("बीकन बंद") -> { stopAdvertisingRoom(); speak("कमरे का बीकन बंद कर दिया।") }
            command.contains("start kitchen beacon") || command.contains("किचन बीकन चालू") -> startAdvertisingRoom("Kitchen")
            command.contains("start library beacon") || command.contains("लाइब्रेरी बीकन चालू") -> startAdvertisingRoom("Library")
            command.contains("start bedroom beacon") || command.contains("बेडरूम बीकन चालू") -> startAdvertisingRoom("Bedroom")
            command.contains("danger") || command.contains("help") || command.contains("emergency") || command.contains("sos") ||
                command.contains("मदद") || command.contains("खतरा") || command.contains("सहायता") ||
                command.contains("madad") || command.contains("bachao") || command.contains("बचाओ") || command.contains("बचाइए") -> beginEmergency()
            command.contains("cancel navigation") || command.contains("stop navigation") || command.contains("रास्ता रद्द") || command.contains("नेविगेशन बंद") -> { navigationTarget = null; navigationPath = emptyList(); updateIndoorMapView(); speak("Navigation rok di. Zaroorat ho toh nayi manzil boliye.") }
            command.contains("kitchen") || command.contains("रसोई") || command.contains("किचन") || command.contains("rasoi") -> startRoute("Kitchen")
            command.contains("library") || command.contains("लाइब्रेरी") || command.contains("पुस्तकालय") || command.contains("pustakalay") -> startRoute("Library")
            command.contains("bedroom") || command.contains("बेडरूम") || command.contains("कमरा") || command.contains("kamra") -> startRoute("Bedroom")
            command.contains("where am i") || command.contains("location") || command.contains("मेरी लोकेशन") || command.contains("कहाँ") || command.contains("कहां") -> announceLocation()
            command.contains("camera off") || command.contains("कैमरा बंद") -> stopCamera()
            command.contains("camera") || command.contains("कैमरा चालू") -> startCamera()
            command.contains("repeat") || command.contains("दोहराओ") || command.contains("फिर से") -> speak(lastSpoken.ifBlank { "कोई निर्देश उपलब्ध नहीं है।" })
            command.contains("stop") || command.contains("रुको") || command.contains("बंद करो") -> { textToSpeech?.stop(); currentlySpeaking = false; speak("बोले जा रहे निर्देश रोक दिए।") }
            command.contains("copilot") || command.contains("कोपायलट") -> openCopilotDialog()
            else -> speak("समझ नहीं आया। मंज़िल, लोकेशन, कैमरा चालू या बंद, trusted contact जोड़ें, help, दोहराओ, या रुको बोलें।")
        }
    }

    private fun isAffirmative(command: String): Boolean {
        val words = command.split(Regex("[^\\p{L}\\p{N}]+"))
        return words.any { it in setOf("yes", "yeah", "yep", "haan", "han", "confirm", "correct", "okay", "ok") } ||
            listOf("हाँ", "हां", "भेज दो", "ठीक है", "सही है").any(command::contains)
    }

    private fun isNegative(command: String): Boolean {
        val words = command.split(Regex("[^\\p{L}\\p{N}]+"))
        return words.any { it in setOf("no", "nope", "nah", "nahi", "nahin", "cancel", "stop", "radd") } ||
            listOf("नहीं", "नही", "मत भेजो", "रद्द", "कैंसल").any(command::contains)
    }

    private fun isEmulator(): Boolean = Build.FINGERPRINT.lowercase(Locale.ROOT).contains("generic") || Build.MODEL.contains("Emulator", ignoreCase = true)

    private fun beginTrustedContactVoiceSetup() {
        if (trustedContactVoiceStep != null) return
        voiceContactName = ""
        voiceContactPhone = ""
        trustedContactVoiceStep = "name"
        speak("Trusted contact ka naam boliye.")
    }

    private fun handleTrustedContactVoice(raw: String) {
        when (trustedContactVoiceStep) {
            "name" -> {
                val name = raw.trim().take(50)
                if (name.isBlank() || name.length < 2) { speak("Naam samajh nahi aaya. Dobara naam boliye."); return }
                voiceContactName = name
                trustedContactVoiceStep = "phone"
                speak("Ab country code ke saath phone number ke digits ek ek karke boliye.")
            }
            "phone" -> {
                val digits = spokenPhoneDigits(raw)
                if (digits.length !in 7..15) { speak("Number samajh nahi aaya. Digits dheere dheere dobara boliye."); return }
                voiceContactPhone = if (digits.length == 10) "+91$digits" else "+$digits"
                trustedContactVoiceStep = "confirm"
                speak("Number ${voiceContactPhone.toCharArray().joinToString(" ")} suna. Save karne ke liye haan boliye, cancel ke liye nahi.")
            }
            "confirm" -> {
                val answer = raw.lowercase(Locale.ROOT)
                if (isAffirmative(answer)) {
                    val duplicate = trustedMembers.any { it.phone == voiceContactPhone }
                    if (duplicate) speak("Yeh number pehle se saved hai.") else {
                        trustedMembers.add(TrustedMember(voiceContactName, voiceContactPhone))
                        saveTrustedMembers(); renderTrustedMembers()
                        setStatus("${voiceContactName} trusted members mein add ho gaye.")
                        speak("${voiceContactName} trusted member ke roop mein save ho gaye.")
                        requestEmergencyPermissions()
                    }
                    trustedContactVoiceStep = null
                } else if (isNegative(answer)) {
                    trustedContactVoiceStep = null
                    speak("Contact add karna cancel kiya.")
                } else speak("Save karne ke liye haan, cancel karne ke liye nahi boliye.")
            }
            else -> trustedContactVoiceStep = null
        }
    }

    private fun spokenPhoneDigits(raw: String): String {
        val digitWords = mapOf(
            "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "to" to "2", "three" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
            "शून्य" to "0", "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4", "पांच" to "5", "पाँच" to "5", "छह" to "6", "छः" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9"
        )
        val normalizedDigits = raw.mapNotNull { char ->
            when {
                char.isDigit() -> Character.getNumericValue(char).takeIf { it in 0..9 }?.toString()
                else -> null
            }
        }.joinToString("")
        if (normalizedDigits.length >= 7) return normalizedDigits.takeLast(15)
        val words = raw.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
        return words.mapNotNull { digitWords[it] }.joinToString("").takeLast(15)
    }

    private fun routeGraph(): Map<String, RouteNode> = mapOf(
        "entrance" to RouteNode("entrance", listOf(Edge("junction", 4, "प्रवेश से मुख्य गलियारे में सीधे चार मीटर चलें।"))),
        "junction" to RouteNode("junction", listOf(
            Edge("kitchen", 5, "गलियारे के मोड़ पर दाएँ मुड़ें और रसोई मार्कर की ओर चलें।"),
            Edge("bedroom", 4, "गलियारे के मोड़ पर बाएँ मुड़ें और बेडरूम मार्कर की ओर चलें।"),
            Edge("library", 6, "मोड़ से सीधे लाइब्रेरी मार्कर की ओर गलियारे में चलें।")
        )),
        "kitchen" to RouteNode("kitchen", listOf(Edge("junction", 5, "रसोई से बाहर मुख्य गलियारे के जंक्शन की ओर चलें।"))),
        "bedroom" to RouteNode("bedroom", listOf(Edge("junction", 4, "बेडरूम से बाहर मुख्य गलियारे के जंक्शन की ओर चलें।"))),
        "library" to RouteNode("library", listOf(Edge("junction", 6, "लाइब्रेरी से बाहर मुख्य गलियारे के जंक्शन की ओर चलें।")))
    )

    private fun findPath(room: String): List<String>? {
        val target = room.lowercase(Locale.ROOT)
        val graph = routeGraph()
        // Do not invent an entrance location: navigation needs a room beacon fix first.
        val start = currentRoom?.lowercase(Locale.ROOT)?.takeIf { it in graph } ?: return null
        if (start == target) return listOf(start)
        val distance = mutableMapOf(start to 0)
        val previous = mutableMapOf<String, Pair<String, Edge>>()
        val queue = java.util.PriorityQueue(compareBy<Pair<String, Int>> { it.second })
        queue.add(start to 0)
        while (queue.isNotEmpty()) {
            val (node, cost) = queue.remove()
            if (cost != distance[node]) continue
            if (node == target) break
            for (edge in graph[node]?.edges.orEmpty()) {
                val next = cost + edge.meters
                if (next < (distance[edge.to] ?: Int.MAX_VALUE)) { distance[edge.to] = next; previous[edge.to] = node to edge; queue.add(edge.to to next) }
            }
        }
        if (target !in distance) return null
        val path = mutableListOf<String>()
        var cursor = target
        path.add(cursor)
        while (cursor != start) {
            val pair = previous[cursor] ?: return null
            cursor = pair.first
            path.add(cursor)
        }
        path.reverse()
        setStatus("${roomHindi(room)} route · ${roomHindi(path.first())} marker se · lagbhag ${distance[target]}m · BLE estimate")
        return path
    }

    private fun startRoute(room: String) {
        setDrawerOpen(false)
        if (knownRooms.none { it.equals(room, ignoreCase = true) }) return
        if (currentRoom == null) {
            navigationTarget = null; navigationPath = emptyList(); updateIndoorMapView()
            speak("अभी कमरा पहचान में नहीं आया। पहले Kitchen, Library या Bedroom नाम वाला BLE device या marker पास में चालू करें।")
            return
        }
        val path = findPath(room)
        if (path == null) { navigationTarget = null; navigationPath = emptyList(); updateIndoorMapView(); speak("Is map par ${roomHindi(room)} ka rasta nahi bana hai."); return }
        if (path.size == 1) { navigationTarget = null; navigationPath = emptyList(); updateIndoorMapView(); speak("Aap ${roomHindi(room)} marker ke paas hain. BLE se yeh sirf anumaan hai."); return }
        navigationPath = path
        navigationIndex = 0
        navigationTarget = path.last()
        updateIndoorMapView()
        val firstEdge = routeGraph()[path[0]]?.edges?.firstOrNull { it.to == path[1] }
        if (firstEdge == null) { navigationTarget = null; speak("Map route incomplete hai. Caregiver se map check karayein."); return }
        val origin = "Aapke ${currentRoom?.let(::roomHindi)} BLE room marker ke hisaab se"
        setStatus("Navigation active · ${roomHindi(room)} · har agle BLE map marker par direction update hogi")
        vibrate(longArrayOf(60, 50, 60))
        speak("${roomHindi(room)} ke liye rasta. $origin. ${firstEdge.instructionHi} Agle marker par agla nirdesh milega.")
    }

    private fun advanceNavigation(room: String) {
        val target = navigationTarget ?: return
        val path = navigationPath
        if (path.isEmpty()) return
        val reachedIndex = path.indexOfFirstFrom(navigationIndex + 1, room.lowercase(Locale.ROOT))
        if (reachedIndex < 0) {
            val reroute = findPath(target) ?: return
            if (reroute.size < 2) return
            navigationPath = reroute
            navigationIndex = 0
            updateIndoorMapView()
            val next = reroute[1]
            val edge = routeGraph()[room.lowercase(Locale.ROOT)]?.edges?.firstOrNull { it.to == next } ?: return
            vibrate(longArrayOf(80, 50, 80))
            setStatus("Naya BLE room marker mila · route dobara calculate hua")
            speak("रास्ता अपडेट। आप ${roomHindi(room)} marker के पास हैं। ${edge.instructionHi}")
            return
        }
        navigationIndex = reachedIndex
        if (room.equals(target, ignoreCase = true)) {
            navigationTarget = null
            navigationPath = emptyList()
            updateIndoorMapView()
            vibrate(longArrayOf(100, 70, 100))
            speak("Aap ${roomHindi(room)} marker ke paas pahunch gaye. Jagah BLE ke hisaab se anumaanit hai. Ruk kar aas paas ka rasta jaanch lein.")
            return
        }
        val next = path.getOrNull(reachedIndex + 1) ?: return
        val edge = routeGraph()[room.lowercase(Locale.ROOT)]?.edges?.firstOrNull { it.to == next } ?: return
        setStatus("${roomHindi(room)} marker mila · agla ${roomHindi(next)} marker")
        vibrate(longArrayOf(55, 45, 55))
        speak("${roomHindi(room)} marker ke paas hain. ${edge.instructionHi} Agle marker par agla nirdesh milega.")
    }

    private fun List<String>.indexOfFirstFrom(start: Int, value: String): Int {
        for (index in start.coerceAtLeast(0) until size) if (this[index].equals(value, ignoreCase = true)) return index
        return -1
    }

    private fun roomHindi(room: String) = when (room.lowercase(Locale.ROOT)) {
        "entrance" -> "प्रवेश द्वार"; "junction" -> "गलियारा जंक्शन"; "kitchen" -> "रसोई"; "library" -> "लाइब्रेरी"; "bedroom" -> "बेडरूम"; else -> room
    }

    private fun updateIndoorMapView() {
        if (!::indoorMapView.isInitialized) return
        indoorMapView.update(currentRoom, navigationPath, navigationTarget != null && currentRoom == null)
    }

    private inner class DetectorOverlayView : View(this@MainActivity) {
        private var detections: List<NanoDetDetector.Detection> = emptyList()
        private var sourceWidth = 1
        private var sourceHeight = 1

        init { isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun update(objects: List<NanoDetDetector.Detection>, frameWidth: Int, frameHeight: Int) {
            detections = objects
            sourceWidth = frameWidth.coerceAtLeast(1)
            sourceHeight = frameHeight.coerceAtLeast(1)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
            val shownWidth = sourceWidth * scale
            val shownHeight = sourceHeight * scale
            val offsetX = (width - shownWidth) / 2f
            val offsetY = (height - shownHeight) / 2f
            fun screenX(value: Float) = offsetX + value * shownWidth
            fun screenY(value: Float) = offsetY + value * shownHeight

            val guide = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 199, 55); strokeWidth = dp(1).toFloat(); style = android.graphics.Paint.Style.STROKE
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(7).toFloat(), dp(5).toFloat()), 0f)
            }
            canvas.drawLine(screenX(.20f), 0f, screenX(.20f), height.toFloat(), guide)
            canvas.drawLine(screenX(.80f), 0f, screenX(.80f), height.toFloat(), guide)

            val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 224, 140); strokeWidth = dp(2).toFloat(); style = android.graphics.Paint.Style.STROKE }
            val tagPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 6, 53, 39); style = android.graphics.Paint.Style.FILL }
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(12).toFloat(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
            detections.forEach { item ->
                val left = screenX(item.left); val top = screenY(item.top)
                val right = screenX(item.right); val bottom = screenY(item.bottom)
                if (right <= left || bottom <= top) return@forEach
                canvas.drawRect(left, top, right, bottom, boxPaint)
                val caption = "${item.label} ${(item.confidence * 100).toInt()}%"
                val captionWidth = textPaint.measureText(caption) + dp(10)
                val tagTop = (top - dp(21)).coerceAtLeast(0f)
                canvas.drawRoundRect(left, tagTop, left + captionWidth, tagTop + dp(20), dp(4).toFloat(), dp(4).toFloat(), tagPaint)
                canvas.drawText(caption, left + dp(5), tagTop + dp(14).toFloat(), textPaint)
            }
        }
    }

    private inner class IndoorMapView : View(this@MainActivity) {
        private var locatedRoom: String? = null
        private var route: List<String> = emptyList()
        private var assumedEntrance = false
        private val nodes = mapOf(
            "entrance" to (.10f to .50f), "junction" to (.39f to .50f),
            "bedroom" to (.65f to .20f), "library" to (.88f to .50f), "kitchen" to (.65f to .80f)
        )
        private val connections = listOf("entrance" to "junction", "junction" to "bedroom", "junction" to "library", "junction" to "kitchen")

        init { contentDescription = "Schematic home map. Current room is unknown until a BLE marker is detected."; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }

        fun update(room: String?, path: List<String>, assumed: Boolean) {
            locatedRoom = room?.lowercase(Locale.ROOT)
            route = path
            assumedEntrance = assumed
            val where = locatedRoom?.let(::roomHindi) ?: if (assumedEntrance) "प्रवेश द्वार (मानकर)" else "अज्ञात"
            val destination = path.lastOrNull()?.let(::roomHindi)
            contentDescription = buildString {
                append("Indoor map. Approximate BLE location: ").append(where).append(".")
                if (destination != null) append(" Route to ").append(destination).append(" is highlighted.")
                append(" Sample map: Entrance, corridor junction, bedroom, library and kitchen.")
            }
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val inset = dp(12).toFloat()
            val w = (width - inset * 2).coerceAtLeast(1f)
            val h = (height - inset * 2).coerceAtLeast(1f)
            fun xy(key: String): Pair<Float, Float> {
                val (nx, ny) = nodes.getValue(key)
                return inset + nx * w to inset + ny * h
            }
            val corridor = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(228, 234, 229); strokeWidth = dp(18).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND; style = android.graphics.Paint.Style.STROKE }
            val centerLine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(197, 207, 199); strokeWidth = dp(2).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND; style = android.graphics.Paint.Style.STROKE }
            connections.forEach { (a, b) ->
                val (x1, y1) = xy(a); val (x2, y2) = xy(b)
                canvas.drawLine(x1, y1, x2, y2, corridor)
                canvas.drawLine(x1, y1, x2, y2, centerLine)
            }
            val routePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 137, 100); strokeWidth = dp(7).toFloat(); strokeCap = android.graphics.Paint.Cap.ROUND; style = android.graphics.Paint.Style.STROKE }
            route.zipWithNext().forEach { (a, b) ->
                val (x1, y1) = xy(a); val (x2, y2) = xy(b)
                canvas.drawLine(x1, y1, x2, y2, routePaint)
            }
            val roomFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 249, 246); style = android.graphics.Paint.Style.FILL }
            val roomBorder = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 119, 99); strokeWidth = dp(2).toFloat(); style = android.graphics.Paint.Style.STROKE }
            val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 61, 46); textSize = dp(11).toFloat(); textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            listOf("entrance" to "Entrance", "bedroom" to "Bedroom", "library" to "Library", "kitchen" to "Kitchen").forEach { (key, title) ->
                val (x, y) = xy(key); val halfW = dp(34).toFloat(); val halfH = dp(18).toFloat()
                canvas.drawRoundRect(x - halfW, y - halfH, x + halfW, y + halfH, dp(7).toFloat(), dp(7).toFloat(), roomFill)
                canvas.drawRoundRect(x - halfW, y - halfH, x + halfW, y + halfH, dp(7).toFloat(), dp(7).toFloat(), roomBorder)
                canvas.drawText(title, x, y + dp(4).toFloat(), label)
            }
            val (jx, jy) = xy("junction")
            val junctionPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 92, 72); style = android.graphics.Paint.Style.FILL }
            canvas.drawCircle(jx, jy, dp(8).toFloat(), junctionPaint)
            label.textSize = dp(9).toFloat(); canvas.drawText("Junction", jx, jy - dp(13).toFloat(), label)
            val current = locatedRoom ?: if (assumedEntrance) "entrance" else null
            if (current != null && current in nodes) {
                val (ux, uy) = xy(current)
                val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(65, 25, 113, 210); style = android.graphics.Paint.Style.FILL }
                val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (assumedEntrance && locatedRoom == null) Color.rgb(225, 135, 28) else Color.rgb(25, 113, 210); style = android.graphics.Paint.Style.FILL }
                canvas.drawCircle(ux, uy, dp(13).toFloat(), halo)
                canvas.drawCircle(ux, uy, dp(7).toFloat(), dot)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        if (cameraEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)); setStatus("Camera permission maang raha hoon."); return }
        cameraEnabled = true
        cameraButton.text = "Turn camera off"
        detectionView.text = "OpenCV model load ho raha hai…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                if (!cameraEnabled || isFinishing || isDestroyed) return@addListener
                val provider = providerFuture.get(); cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyzeFrame(image) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraExecutor.execute {
                    try {
                        val readyDetector = NanoDetDetector(this)
                        runOnUiThread {
                            if (cameraEnabled && !isFinishing && !isDestroyed) {
                                detector = readyDetector
                                detectionView.text = "OpenCV NanoDet · live camera scanning"
                                setStatus("Camera preview aur OpenCV obstacle scanning chalu hai")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { detectionView.text = "OpenCV model start nahi hua: ${e.message ?: e.javaClass.simpleName}"; setStatus("Camera preview ho sakta hai, lekin obstacle detector start nahi hua") }
                    }
                }
            } catch (e: Exception) {
                cameraEnabled = false
                runOnUiThread {
                    cameraButton.text = "Turn camera on"
                    detectionView.text = "Camera start nahi hua: ${e.message ?: e.javaClass.simpleName}"
                    setStatus("Camera bind nahi hua. Permission, doosri camera app aur device camera check karein.")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastInferenceAt < 250 || detector == null || !cameraEnabled) { proxy.close(); return }
        lastInferenceAt = now
        var rgba: Mat? = null; var modelFrame: Mat? = null; var rotated: Mat? = null
        try {
            val plane = proxy.planes[0]
            val buffer: ByteBuffer = plane.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
            val pixelStride = plane.pixelStride
            require(pixelStride >= 4) { "Unexpected RGBA pixel stride: $pixelStride" }
            rgba = Mat(proxy.height, proxy.width, CvType.CV_8UC4)
            val rowSize = proxy.width * 4
            val rowPixels = ByteArray(rowSize)
            for (row in 0 until proxy.height) {
                val offset = row * plane.rowStride
                val lastPixelEnd = offset + (proxy.width - 1) * pixelStride + 4
                if (lastPixelEnd > bytes.size) throw IllegalStateException("Camera frame row is truncated")
                for (column in 0 until proxy.width) System.arraycopy(bytes, offset + column * pixelStride, rowPixels, column * 4, 4)
                rgba.put(row, 0, rowPixels)
            }
            // OpenCV Zoo NanoDet preprocessing converts camera BGR to RGB before applying
            // its ImageNet mean/std normalization. CameraX gives RGBA, so feed RGB here.
            modelFrame = Mat(); Imgproc.cvtColor(rgba, modelFrame, Imgproc.COLOR_RGBA2RGB)
            rotated = Mat()
            when (proxy.imageInfo.rotationDegrees) {
                90 -> Core.rotate(modelFrame, rotated, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(modelFrame, rotated, Core.ROTATE_180)
                270 -> Core.rotate(modelFrame, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> modelFrame.copyTo(rotated)
            }
            val detections = detector?.detect(rotated).orEmpty()
            // Treat a persistent object in the lower center as a stop cue. Side detections
            // are reported separately so they do not cause a false turn instruction.
            val relevant = detections.filter { it.area >= .008f && it.bottom >= .42f }
            val laneObstacle = relevant.filter { it.centerX in .38f.. .62f }.maxByOrNull { it.area }
            val leftObstacle = relevant.filter { it.centerX < .38f }.maxByOrNull { it.area }
            val rightObstacle = relevant.filter { it.centerX > .62f }.maxByOrNull { it.area }
            val cueObject = laneObstacle ?: leftObstacle ?: rightObstacle
            // Capture primitive dimensions before releasing the native Mat in finally; UI callbacks run later.
            val frameWidth = rotated.cols()
            val frameHeight = rotated.rows()
            runOnUiThread {
                if (::detectorOverlay.isInitialized) detectorOverlay.update(detections, frameWidth, frameHeight)
                processObstacleCue(cueObject, laneObstacle != null, leftObstacle != null && rightObstacle != null)
            }
        } catch (e: Exception) {
            runOnUiThread { detectionView.text = "Object scan hiccup: ${e.javaClass.simpleName}" }
        } finally {
            rgba?.release(); modelFrame?.release(); rotated?.release(); proxy.close()
        }
    }

    private fun processObstacleCue(detection: NanoDetDetector.Detection?, blocksWalkingLane: Boolean, bothSidesBlocked: Boolean) {
        if (!cameraEnabled) return
        if (detection == null || detection.confidence < .25f || detection.area < .008f) {
            clearFrames++
            candidateObstacleKey = ""; candidateObstacleHits = 0
            if (clearFrames >= 5) {
                lastAlertLevel = 0
                lastObstacleCueKey = ""
                lastObstacleCueAt = 0L
                val details = detector?.lastDiagnostics.orEmpty()
                detectionView.text = if (details.contains("maxScore")) "Scan: object clear nahi · $details" else "Scan chalu · koi path obstacle confirm nahi hua"
            }
            return
        }
        clearFrames = 0
        // Box area and lower-frame position are visual urgency clues, never metres.
        // Absence of a box cannot establish that another direction is safe or clear.
        val level = if (blocksWalkingLane) 2 else 0
        val hiObject = objectHindi(detection.label)
        val side = when {
            bothSidesBlocked -> "दोनों किनारों पर"
            detection.centerX < .36f -> "बाईं ओर"
            detection.centerX > .64f -> "दाईं ओर"
            else -> "सामने"
        }
        val key = "${detection.label}:$side:$level"
        if (candidateObstacleKey == key) candidateObstacleHits++ else { candidateObstacleKey = key; candidateObstacleHits = 1 }
        val action = if (level == 2) "STOP" else "SIDE · STRAIGHT LANE CLEAR"
        detectionView.text = "Camera: $action · $side $hiObject · ${(detection.confidence * 100).toInt()}%"
        // Keep detection visible, but do not let camera cues mask an active emergency flow.
        if (emergencyFlowActive) return
        if (candidateObstacleHits < 2) return
        val cueChanged = key != lastObstacleCueKey
        val escalated = level > lastAlertLevel
        val cooldownPassed = System.currentTimeMillis() - lastObstacleCueAt >= 4500L
        if (escalated || (cueChanged && cooldownPassed)) {
            lastAlertLevel = level
            lastObstacleCueKey = key
            lastObstacleCueAt = System.currentTimeMillis()
            when (level) {
                2 -> {
                    vibrate(longArrayOf(240, 100, 240, 100, 350))
                    speak("रुकें। $side $hiObject सीधे चलने के रास्ते में दिख रहा है। रास्ता जाँचें।")
                }
                else -> {
                    vibrate(longArrayOf(70, 60, 70))
                    val movementCue = if (walkingNow) "धीरे सीधे चलते रहें" else "आप स्थिर हैं; सामने का बीच वाला हिस्सा साफ दिख रहा है, धीरे सीधे शुरू करें"
                    speak("$movementCue। कैमरे के बीच वाले रास्ते में वस्तु नहीं दिखी; $side $hiObject दिख रहा है। कैमरा पूरा रास्ता सुरक्षित साबित नहीं कर सकता।")
                }
            }
        }
    }

    private fun objectHindi(label: String) = when (label) {
        "person" -> "व्यक्ति"; "bicycle" -> "साइकिल"; "backpack", "handbag", "suitcase" -> "बैग"; "chair" -> "कुर्सी"; "couch" -> "सोफ़ा"; "bed" -> "बिस्तर"; "dining table" -> "मेज़"; "potted plant" -> "गमला"; "bottle" -> "बोतल"; "cup" -> "कप"; "bowl" -> "कटोरा"; "dog" -> "कुत्ता"; "cat" -> "बिल्ली"; "tv" -> "टीवी"; "laptop" -> "लैपटॉप"; "microwave" -> "माइक्रोवेव"; "oven" -> "ओवन"; "sink" -> "सिंक"; "refrigerator" -> "फ्रिज"; "toilet" -> "टॉयलेट"; "book" -> "किताब"; "sports ball" -> "गेंद"; "umbrella" -> "छाता"; else -> "वस्तु"
    }

    private fun stopCamera() {
        cameraEnabled = false; detector = null; cameraProvider?.unbindAll(); if (::detectorOverlay.isInitialized) detectorOverlay.update(emptyList(), 1, 1); cameraButton.text = "Turn camera on"; detectionView.text = "Camera off"; speak("कैमरा और बाधा पहचान बंद कर दी।")
    }

    private fun hasBleScanPermission(): Boolean {
        val nearbyGranted = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return nearbyGranted && locationGranted
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasBleScanPermission()) { setStatus("Nearby devices permission BLE scan ke liye zaroori hai."); return }
        if (Build.VERSION.SDK_INT <= 30 && !legacyLocationServicesEnabled()) {
            setStatus("Android 11 ya purane phone par BLE room scan ke liye Location setting bhi ON karein.")
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) { setStatus("Is phone ka Bluetooth available nahi."); return }
        if (!adapter.isEnabled) { setStatus("Bluetooth on karne ki permission maang raha hoon."); try { bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { setStatus("Bluetooth Settings mein jaakar Bluetooth on karein.") }; return }
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run { setStatus("Is phone ka BLE scanner available nahi."); return }
        if (scanCallback != null) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                runOnUiThread { if (scanCallback === this) updateBleResult(result) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                runOnUiThread { if (scanCallback === this) results.forEach(::updateBleResult) }
            }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    if (scanCallback !== this) return@runOnUiThread
                    scanCallback = null
                    if (activityStarted) {
                        mainHandler.removeCallbacks(bleExpiryRunnable)
                        mainHandler.postDelayed(bleExpiryRunnable, 2500L)
                    }
                    setStatus("BLE scan start nahi hua · ${scanError(errorCode)}")
                    updateBleStatus(true)
                    if (errorCode == ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY && activityStarted) {
                        scanRetryRunnable?.let(mainHandler::removeCallbacks)
                        scanRetryRunnable = Runnable { scanRetryRunnable = null; if (activityStarted && scanCallback == null) startBleScan() }
                        mainHandler.postDelayed(scanRetryRunnable!!, 3500L)
                    }
                }
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            setStatus("Nearby BLE room markers scan ho rahe hain")
            updateBleStatus(true)
            mainHandler.removeCallbacks(bleExpiryRunnable)
            mainHandler.postDelayed(bleExpiryRunnable, 2500L)
        } catch (e: SecurityException) {
            scanCallback = null
            setStatus("BLE permission missing hai. Nearby devices aur Location permission check karein.")
        } catch (e: IllegalStateException) {
            scanCallback = null
            setStatus("BLE scanner abhi ready nahi hai. Bluetooth on karke kuch der baad try karein.")
        }
    }

    private fun legacyLocationServicesEnabled(): Boolean {
        val location = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    private fun scanError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "scan pehle se chalu hai"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android BLE registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "phone ka Bluetooth error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning supported nahi"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "BLE resources busy hain"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "retry limit; kuch der mein retry hoga"
        else -> "error $code"
    }

    @SuppressLint("MissingPermission")
    private fun updateBleResult(result: ScanResult) {
        val scanRecord = result.scanRecord
        val servicePayload = scanRecord?.getServiceData(BEACON_SERVICE_UUID)
        // SafeStep v1 payload: [version, room ID, 4-byte marker ID]. Accept the
        // original one-byte room ID while older demo APKs are still installed.
        val isVersionedPayload = servicePayload != null && servicePayload.size >= 6 && servicePayload[0].toInt() == BEACON_PROTOCOL_VERSION
        val serviceRoomId = when {
            isVersionedPayload -> servicePayload!![1].toInt() and 0xFF
            servicePayload?.size == 1 -> servicePayload[0].toInt() and 0xFF
            else -> null
        }
        val roomFromService = serviceRoomId?.let { BEACON_ROOM_IDS.entries.firstOrNull { entry -> entry.value == it }?.key }
        val name = try {
            scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: result.device.name.orEmpty()
        } catch (_: SecurityException) { scanRecord?.deviceName.orEmpty() }
        val room = roomFromService ?: roomFromAdvertisedName(name) ?: return
        val address = try { result.device.address } catch (_: SecurityException) { "" }
        val markerKey = if (isVersionedPayload) {
            "safestep:${servicePayload!!.slice(2..5).joinToString("") { "%02x".format(it) }}"
        } else "device:${address.ifBlank { name.lowercase(Locale.ROOT) }}"
        val key = "$markerKey:${room.lowercase(Locale.ROOT)}"
        val current = bleRooms[key]
        val filtered = if (current == null) result.rssi.toDouble() else current.rssi * .72 + result.rssi * .28
        bleRooms[key] = RoomSignal(room, filtered, SystemClock.elapsedRealtime())
        refreshLocation(newAdvertisementRoom = room)
        updateBleStatus()
    }

    /** Recognize room labels carried in a BLE advertisement's local name. A normal
     * Bluetooth device name is visible only if that device advertises it over BLE. */
    private fun roomFromAdvertisedName(rawName: String): String? {
        val name = rawName.lowercase(Locale.ROOT)
        return when {
            listOf("kitchen", "rasoi", "रसोई", "किचन").any { name.contains(it, ignoreCase = true) } -> "Kitchen"
            listOf("library", "pustakalay", "पुस्तकालय", "लाइब्रेरी").any { name.contains(it, ignoreCase = true) } -> "Library"
            listOf("bedroom", "bed room", "कमरा", "बेडरूम").any { name.contains(it, ignoreCase = true) } -> "Bedroom"
            listOf("entrance", "entry", "प्रवेश").any { name.contains(it, ignoreCase = true) } -> "Entrance"
            listOf("junction", "hallway", "corridor", "गलियारा").any { name.contains(it, ignoreCase = true) } -> "Junction"
            else -> null
        }
    }

    private fun removeExpiredBle() {
        val cutoff = SystemClock.elapsedRealtime() - BLE_SIGNAL_TTL_MS
        bleRooms.entries.removeAll { it.value.seenAt < cutoff }
        refreshLocation()
    }

    private fun refreshLocation(newAdvertisementRoom: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val freshSignals = bleRooms.values.filter { now - it.seenAt < BLE_SIGNAL_TTL_MS && it.rssi >= BLE_MIN_RSSI_FOR_ROOM }
        val strongest = freshSignals.maxByOrNull { it.rssi }
        val oldRoomSignal = currentRoom?.let { room -> freshSignals.filter { it.room.equals(room, ignoreCase = true) }.maxByOrNull { it.rssi } }
        val newRoom = when {
            strongest == null -> { candidateRoom = null; candidateRoomHits = 0; candidateRoomSince = 0L; candidateLastSampleAt = 0L; null }
            strongest.room.equals(currentRoom, ignoreCase = true) -> { candidateRoom = null; candidateRoomHits = 0; candidateRoomSince = 0L; candidateLastSampleAt = 0L; strongest.room }
            else -> {
                if (!candidateRoom.equals(strongest.room, ignoreCase = true)) {
                    candidateRoom = strongest.room
                    candidateRoomHits = 1
                    candidateRoomSince = now
                    candidateLastSampleAt = now
                } else if (newAdvertisementRoom?.equals(candidateRoom, ignoreCase = true) == true && now - candidateLastSampleAt >= BLE_SAMPLE_INTERVAL_MS) {
                    candidateRoomHits++
                    candidateLastSampleAt = now
                }
                val candidateSampleNow = newAdvertisementRoom?.equals(candidateRoom, ignoreCase = true) == true
                val candidateStable = candidateSampleNow && candidateRoomHits >= BLE_MINIMUM_SAMPLES && now - candidateRoomSince >= BLE_MINIMUM_STABLE_MS
                if (candidateStable && (oldRoomSignal == null || strongest.rssi >= oldRoomSignal.rssi + BLE_SWITCH_MARGIN_DB)) {
                    candidateRoom = null; candidateRoomHits = 0; candidateRoomSince = 0L; candidateLastSampleAt = 0L; strongest.room
                } else currentRoom
            }
        }
        if (newRoom != currentRoom) {
            currentRoom = newRoom
            runOnUiThread {
                locationView.text = if (newRoom == null) "जगह अभी पता नहीं है" else "${roomHindi(newRoom)} मार्कर के पास · अनुमानित"
                updateIndoorMapView()
                if (newRoom != null) {
                    if (navigationTarget != null) advanceNavigation(newRoom)
                    else {
                        val arrival = "आप ${roomHindi(newRoom)} marker के पास हैं। यह Bluetooth signal से अनुमान है।"
                        setStatus(arrival)
                        if (currentlySpeaking) speechAfterCurrent = arrival else speak(arrival)
                    }
                } else {
                    if (navigationTarget != null) {
                        navigationTarget = null
                        navigationPath = emptyList()
                        setStatus("BLE room marker खो गया · navigation रोक दी · room marker के पास वापस जाएँ")
                        speak("कमरे का Bluetooth marker अब नहीं मिल रहा। सुरक्षित रूप से रुकें। लोकेशन वापस मिले तब navigation दोबारा शुरू करें।")
                        updateIndoorMapView()
                    } else {
                        val lost = "Room marker signal nahi mil raha. Aapki indoor location abhi unknown hai."
                        setStatus(lost)
                        if (currentlySpeaking) speechAfterCurrent = lost else speak(lost)
                    }
                }
            }
        }
        updateBleStatus()
    }

    private fun updateBleStatus(force: Boolean = false) {
        if (!::bleStatusView.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBleUiUpdateAt < 1000) return
        lastBleUiUpdateAt = now
        val freshSignals = bleRooms.values.filter { now - it.seenAt < BLE_SIGNAL_TTL_MS && it.rssi >= BLE_MIN_RSSI_FOR_ROOM }
        val nearest = freshSignals.maxByOrNull { it.rssi }
        val confirmed = currentRoom?.let { room -> freshSignals.filter { it.room.equals(room, ignoreCase = true) }.maxByOrNull { it.rssi } }
        val text = when {
            activeRoomBeacon != null -> "Marker ON · $activeRoomBeacon · scanner ${if (scanCallback != null) "ON" else "OFF"}"
            scanCallback == null -> "BLE scan OFF · Bluetooth/permission check karein"
            nearest == null && bleRooms.values.any { now - it.seenAt < BLE_SIGNAL_TTL_MS } -> "Marker mila, signal kamzor hai · paas jaakar ruk kar dobara check karein"
            nearest == null -> "BLE scan ON · Entrance/Junction/Kitchen/Library/Bedroom markers dhoondh raha hai"
            confirmed != null && candidateRoom != null -> "Room: $currentRoom · candidate: $candidateRoom (${nearest.rssi.toInt()} dBm) · switching verify ho raha hai"
            confirmed != null -> "Room estimate: $currentRoom · ${confirmed.rssi.toInt()} dBm · BLE approximate"
            else -> "BLE candidate: ${nearest.room} · ${nearest.rssi.toInt()} dBm · location confirm ho raha hai"
        }
        runOnUiThread { if (::bleStatusView.isInitialized) bleStatusView.text = text }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        mainHandler.removeCallbacks(bleExpiryRunnable)
        scanRetryRunnable?.let(mainHandler::removeCallbacks)
        scanRetryRunnable = null
        val callback = scanCallback ?: return
        scanCallback = null
        try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
    }

    private fun announceLocation() {
        removeExpiredBle()
        val now = SystemClock.elapsedRealtime()
        val signal = currentRoom?.let { room -> bleRooms.values.filter { it.room.equals(room, ignoreCase = true) && now - it.seenAt < BLE_SIGNAL_TTL_MS && it.rssi >= BLE_MIN_RSSI_FOR_ROOM }.maxByOrNull { it.rssi } }
        if (currentRoom == null || signal == null) speak("अभी कमरे की लोकेशन confirm नहीं हुई है। Room marker के पास रुकें और कुछ पल इंतज़ार करें।")
        else {
            val band = when { signal.rssi > -58 -> "सिग्नल मज़बूत"; signal.rssi > -75 -> "सिग्नल मध्यम"; else -> "सिग्नल कमज़ोर" }
            speak("आप ${roomHindi(currentRoom!!)} marker के पास होने ka अनुमान hai. $band. Bluetooth signal room ke andar hone ki guarantee nahi deta.")
        }
    }

    private fun chooseBeaconRoom() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) { setStatus("Nearby devices permission beacon ke liye zaroori hai."); requestNeededPermissions(); return }
        AlertDialog.Builder(this).setTitle("Choose this phone's room beacon")
            .setItems(knownRooms.toTypedArray()) { _, index -> startAdvertisingRoom(knownRooms[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingRoom(room: String) {
        if (selectedRoomBeacon == room && advertiseCallback != null) return
        stopAdvertisingRoom(clearSelection = false)
        selectedRoomBeacon = room
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter?.isEnabled != true) {
            setStatus("Bluetooth on karein, phir map marker dobara chalu karein.")
            try { bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { }
            return
        }
        val bleAdvertiser = adapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) { setStatus("Phone BLE advertising support nahi karta."); return }
        advertiser = bleAdvertiser
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).build()
        val roomId = BEACON_ROOM_IDS[room]
        if (roomId == null) { setStatus("Unknown map marker: $room"); return }
        val markerId = getOrCreateMarkerId(room)
        // Service data is [protocol version, room ID, unique marker ID]. It fits
        // legacy BLE advertising packets and lets the scanner track each beacon.
        fun advertisement(includePhoneName: Boolean) = AdvertiseData.Builder().setIncludeDeviceName(includePhoneName)
            .addServiceData(BEACON_SERVICE_UUID, byteArrayOf(
                BEACON_PROTOCOL_VERSION.toByte(), roomId.toByte(),
                (markerId ushr 24).toByte(), (markerId ushr 16).toByte(),
                (markerId ushr 8).toByte(), markerId.toByte()
            )).build()
        var retriedWithoutName = false
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                activeRoomBeacon = room
                runOnUiThread {
                    markerButton.text = "Stop $room room beacon"
                    markerButton.setOnClickListener { stopAdvertisingRoom() }
                    setStatus("Yeh phone apna Bluetooth naam aur $room room marker BLE par advertise kar raha hai")
                    updateBleStatus(true)
                }
            }
            override fun onStartFailure(errorCode: Int) {
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && !retriedWithoutName) {
                    retriedWithoutName = true
                    try { bleAdvertiser.startAdvertising(settings, advertisement(false), this); return }
                    catch (_: Exception) { }
                }
                runOnUiThread {
                    advertiseCallback = null
                    activeRoomBeacon = null
                    setStatus("BLE beacon start nahi hua · ${advertiseError(errorCode)}")
                    updateBleStatus(true)
                }
            }
        }
        advertiseCallback = callback
        try { bleAdvertiser.startAdvertising(settings, advertisement(true), callback) }
        catch (_: SecurityException) { advertiseCallback = null; setStatus("BLE advertise permission missing hai.") }
        catch (_: IllegalStateException) { advertiseCallback = null; setStatus("BLE advertiser abhi ready nahi hai; Bluetooth check karein.") }
    }

    private fun getOrCreateMarkerId(room: String): Int {
        val preferences = getPreferences(MODE_PRIVATE)
        val key = "ble_marker_id_${room.lowercase(Locale.ROOT)}"
        val existing = preferences.getInt(key, 0)
        if (existing != 0) return existing
        var created = UUID.randomUUID().mostSignificantBits.toInt()
        if (created == 0) created = 1
        preferences.edit().putInt(key, created).apply()
        return created
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingRoom(clearSelection: Boolean = true) {
        try { if (advertiser != null && advertiseCallback != null) advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertiser = null; advertiseCallback = null; activeRoomBeacon = null
        if (clearSelection) selectedRoomBeacon = null
        updateBleStatus(true)
        if (::markerButton.isInitialized) {
            markerButton.text = "Act as a room beacon"
            markerButton.setOnClickListener { chooseBeaconRoom() }
        }
    }

    private fun beginEmergency() {
        if (dangerPending || emergencyFlowActive) return
        setDrawerOpen(false)
        emergencyFlowActive = true
        dangerPending = true; dangerSeconds = 30
        dangerTimerRunnable?.let(mainHandler::removeCallbacks)
        vibrate(longArrayOf(140, 70, 140))
        setStatus("Emergency confirmation शुरू · 30 seconds")
        speak("क्या आप खतरे में हैं? अलर्ट भेजने और trusted member को call करने के लिए हाँ बोलें। रद्द करने के लिए नहीं बोलें। सवाल पूरा होने के बाद आपके पास तीस सेकंड होंगे।", onComplete = {
            if (dangerPending) startEmergencyCountdown()
        })
    }

    private fun startEmergencyCountdown() {
        if (!dangerPending) return
        dangerDeadlineElapsed = SystemClock.elapsedRealtime() + 30_000L
        val timer = object : Runnable {
            override fun run() {
                if (!dangerPending) return
                dangerSeconds = ((dangerDeadlineElapsed - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                setStatus("Emergency confirmation · $dangerSeconds seconds")
                if (dangerSeconds <= 0) {
                    dangerPending = false
                    dangerTimerRunnable = null
                    listeningNow = false
                    speechRecognizer?.cancel()
                    vibrate(longArrayOf(250, 100, 250))
                    speak("जवाब नहीं मिला। अब emergency alert भेज रहा हूँ।")
                    openEmergencyDraft()
                    return
                }
                if (dangerSeconds == 20 || dangerSeconds == 10) speak("$dangerSeconds सेकंड बचे हैं। हाँ बोलकर पुष्टि करें या नहीं बोलकर रद्द करें।")
                mainHandler.postDelayed(this, 250)
            }
        }
        dangerTimerRunnable = timer
        mainHandler.post(timer)
        scheduleListen(350)
    }

    private fun clearEmergencyCountdown() {
        dangerTimerRunnable?.let(mainHandler::removeCallbacks)
        dangerTimerRunnable = null
        dangerDeadlineElapsed = 0L
    }

    private fun confirmEmergency() {
        if (!dangerPending) return
        dangerPending = false
        clearEmergencyCountdown()
        setStatus("Emergency confirmed · sending alert")
        speak("हाँ समझ गया। Emergency alert भेज रहा हूँ।")
        openEmergencyDraft()
    }

    private fun cancelEmergency() {
        if (!dangerPending) return
        dangerPending = false
        emergencyFlowActive = false
        clearEmergencyCountdown()
        setStatus("Emergency alert cancelled")
        speak("ठीक है। अलर्ट रद्द किया।")
        vibrate(longArrayOf(70))
    }

    private fun requestEmergencyPermissions() {
        val missing = listOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) emergencyPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun openEmergencyDraft() {
        val recipients = trustedMembers.map { it.phone }.distinct()
        if (recipients.isEmpty()) {
            speak("Trusted contact ka number saved nahi hai. Pehle panel mein trusted member add karein. Abhi call nahi lagaya gaya.")
            setStatus("Emergency SMS draft nahi khula · trusted members add karein")
            mainHandler.postDelayed({ emergencyFlowActive = false }, 7000L)
            return
        }
        val smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (!smsGranted || !callGranted) {
            emergencyDispatchWaitingForPermission = true
            val missing = buildList {
                if (!smsGranted) add(Manifest.permission.SEND_SMS)
                if (!callGranted) add(Manifest.permission.CALL_PHONE)
            }
            setStatus("Emergency confirmation ke baad SMS/call permission maang raha hoon.")
            emergencyPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        dispatchEmergency(smsGranted, callGranted)
    }

    private fun dispatchEmergency(smsGranted: Boolean, callGranted: Boolean) {
        val room = currentRoom?.let { "near $it marker (approximate)" } ?: "unknown indoor room"
        val gps = recentGpsLocation()
        val gpsDetails = gps?.let {
            "GPS: https://maps.google.com/?q=${it.latitude},${it.longitude} (accuracy about ${it.accuracy.toInt()}m)"
        } ?: "GPS location unavailable; indoor room is an approximate BLE estimate."
        val body = "SOS - SafeStep emergency. I need help now. Indoor location: $room. $gpsDetails Please call me."
        val recipients = trustedMembers.map { it.phone }.distinct()
        var smsSubmittedCount = 0
        if (smsGranted) {
            val smsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else legacySmsManager()
            recipients.forEach { recipient ->
                try {
                    val parts = smsManager.divideMessage(body)
                    if (parts.size > 1) smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
                    else smsManager.sendTextMessage(recipient, null, body, null, null)
                    smsSubmittedCount++
                } catch (e: Exception) {
                    android.util.Log.e("SafeStepSOS", "SMS submission failed for a trusted member", e)
                }
            }
        }
        if (smsSubmittedCount > 0) {
            setStatus("Emergency SMS submitted to $smsSubmittedCount of ${recipients.size} trusted member(s). Carrier delivery is not confirmed.")
            speak("Emergency message $smsSubmittedCount trusted member ko bhejne ke liye phone network ko de diya hai. Delivery ki pushti nahi mili hai.")
        } else {
            setStatus("Emergency SMS nahi bheja gaya. SMS permission, active SIM aur mobile network check karein.")
            speak("Emergency SMS nahi bheja ja saka. SMS permission, active SIM aur mobile network check karein.")
        }
        val primary = trustedMembers.firstOrNull()?.phone ?: run { emergencyFlowActive = false; return }
        mainHandler.postDelayed({
            try {
                val action = if (callGranted) Intent.ACTION_CALL else Intent.ACTION_DIAL
                startActivity(Intent(action, Uri.fromParts("tel", primary, null)))
                if (callGranted) {
                    setStatus("Primary trusted member call launch hua. Network connection confirm nahi ho sakta.")
                    speak("Primary trusted member ko call shuru kiya. Call connect hua ya nahi, phone network par nirbhar hai.")
                } else {
                    setStatus("Auto-call permission nahi mili; primary contact ka dialer khola.")
                    speak("Auto call permission nahi mili. Primary contact ka dialer khula hai; call lagane ke liye phone screen par call confirm karein.")
                }
            } catch (e: Exception) {
                setStatus("Call could not start (${e.javaClass.simpleName}); phone app or permission check karein.")
                speak("Call shuru nahi ho paya. Phone app, SIM aur call permission check karein.")
            }
            mainHandler.postDelayed({ emergencyFlowActive = false }, 7000L)
        }, if (smsSubmittedCount > 0) 1500 else 4500)
    }

    @SuppressLint("MissingPermission")
    private fun recentGpsLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val ageLimitMs = 10 * 60 * 1000L
        return try {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .filter { System.currentTimeMillis() - it.time in 0..ageLimitMs }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun legacySmsManager(): SmsManager = SmsManager.getDefault()

    private fun openCopilotDialog() {
        val input = EditText(this).apply { hint = "Question for Copilot"; textSize = 18f }
        AlertDialog.Builder(this).setTitle("Ask GitHub Copilot").setMessage("Demo explanation only; Copilot does not calculate your live route.")
            .setView(input).setPositiveButton("Ask") { _, _ -> sendCopilotQuestion(input.text.toString()) }
            .setNeutralButton("Server URL") { _, _ -> editCopilotUrl() }.setNegativeButton("Cancel", null).show()
    }

    private fun editCopilotUrl() {
        val input = EditText(this).apply { setText(copilotBaseUrl); inputType = InputType.TYPE_TEXT_VARIATION_URI; textSize = 16f }
        AlertDialog.Builder(this).setTitle("Copilot server address").setMessage("Emulator default is 10.0.2.2:4173. On a phone, use the computer's LAN IP and run the existing server there.")
            .setView(input).setPositiveButton("Save") { _, _ -> copilotBaseUrl = input.text.toString().trim().trimEnd('/'); getPreferences(MODE_PRIVATE).edit().putString("copilot_url", copilotBaseUrl).apply(); openCopilotDialog() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sendCopilotQuestion(question: String) {
        if (question.isBlank()) return
        copilotBaseUrl = getPreferences(MODE_PRIVATE).getString("copilot_url", copilotBaseUrl) ?: copilotBaseUrl
        copilotStatus.text = "Copilot soch raha hai…"
        copilotExecutor.execute {
            val answer = try {
                val connection = (URL("$copilotBaseUrl/api/copilot/chat").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 5000; readTimeout = 45000; doOutput = true; setRequestProperty("Content-Type", "application/json") }
                connection.outputStream.use { it.write(JSONObject().put("message", question).toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                val data = stream.bufferedReader().use { it.readText() }
                val parsed = JSONObject(data)
                if (connection.responseCode in 200..299) parsed.optString("reply", "Jawab nahi mila.") else parsed.optString("error", "Copilot server unavailable.")
            } catch (e: Exception) { "Copilot server tak connect nahi hua. URL aur server sign-in check karein." }
            runOnUiThread { copilotStatus.text = answer; speak(answer) }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) else vibrator.vibrate(pattern, -1)
    }

    private fun setStatus(message: String) { if (::statusView.isInitialized) runOnUiThread { statusView.text = message } }

    private fun refreshMovementState() {
        if (!::movementView.isInitialized) return
        val sensor = stepSensor
        if (sensor == null) {
            walkingNow = false
            movementView.text = "गतिविधि: इस फोन में step sensor उपलब्ध नहीं"
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            walkingNow = false
            movementView.text = "गतिविधि: movement permission allow nahi hui"
            return
        }
        val nowWalking = lastStepAt > 0L && SystemClock.elapsedRealtime() - lastStepAt <= 2500L
        walkingNow = nowWalking
        movementView.text = if (nowWalking) "गतिविधि: चल रहे हैं · phone step sensor estimate" else "गतिविधि: खड़े/स्थिर · phone step sensor estimate"
    }

    private fun startMovementTracking() {
        if (movementRegistered) return
        val motionAllowed = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!motionAllowed) { refreshMovementState(); return }
        movementRegistered = try {
            stepSensor?.let { sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        } catch (_: SecurityException) { false }
        refreshMovementState()
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun card(child: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = roundedBackground(Color.WHITE, Color.rgb(224, 231, 226)); addView(child)
    }

    private fun actionButton(label: String, description: String, danger: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 19f; isAllCaps = false; minHeight = dp(62); gravity = Gravity.CENTER
        val fill = if (danger) Color.rgb(157, 44, 37) else Color.rgb(20, 92, 72)
        setTextColor(Color.WHITE); backgroundTintList = null; background = roundedBackground(fill, fill)
        contentDescription = description; setOnClickListener { onClick() }
        stateListAnimator = null; elevation = dp(2).toFloat()
    }

    private fun roundedBackground(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun marginParams(bottom: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom); topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "payload too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many active advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already running"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported"
        else -> "error $code"
    }

    companion object {
        private const val BEACON_PROTOCOL_VERSION = 1
        private const val BLE_SIGNAL_TTL_MS = 12_000L
        private const val BLE_MIN_RSSI_FOR_ROOM = -88.0
        private const val BLE_MINIMUM_SAMPLES = 4
        private const val BLE_SAMPLE_INTERVAL_MS = 400L
        private const val BLE_MINIMUM_STABLE_MS = 1_800L
        private const val BLE_SWITCH_MARGIN_DB = 8.0
        private val BEACON_SERVICE_UUID = ParcelUuid(UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa84"))
        private val BEACON_ROOM_IDS = mapOf("Entrance" to 1, "Junction" to 2, "Kitchen" to 3, "Library" to 4, "Bedroom" to 5)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (!bluetoothReceiverRegistered) {
            ContextCompat.registerReceiver(this, bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            bluetoothReceiverRegistered = true
        }
        startMovementTracking()
        mainHandler.removeCallbacks(movementMonitor)
        mainHandler.post(movementMonitor)
        if (cameraEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraEnabled = false
            startCamera()
        }
        if (hasBleScanPermission() && scanCallback == null) startBleScan()
        mainHandler.removeCallbacks(bleExpiryRunnable)
        mainHandler.postDelayed(bleExpiryRunnable, 2500L)
        if ((getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true) {
            selectedRoomBeacon?.let { startAdvertisingRoom(it) }
        }
    }
    override fun onStop() {
        activityStarted = false
        if (bluetoothReceiverRegistered) {
            try { unregisterReceiver(bluetoothStateReceiver) } catch (_: IllegalArgumentException) { }
            bluetoothReceiverRegistered = false
        }
        mainHandler.removeCallbacks(movementMonitor)
        holdGestureRunnable?.let(mainHandler::removeCallbacks)
        holdGestureRunnable = null
        holdGestureActivated = false
        sensorManager?.unregisterListener(stepListener)
        movementRegistered = false
        listenStartRunnable?.let(mainHandler::removeCallbacks)
        listenStartRunnable = null
        pushToTalkHeld = false
        pushToTalkSession = false
        listeningNow = false
        speechRecognizer?.cancel()
        cameraProvider?.unbindAll()
        stopBleScan(); stopAdvertisingRoom(clearSelection = false)
        super.onStop()
    }
    override fun onDestroy() {
        dangerPending = false
        emergencyFlowActive = false
        clearEmergencyCountdown()
        utteranceCompletionHandlers.clear()
        speechRecognizer?.destroy(); speechRecognizer = null
        textToSpeech?.stop(); textToSpeech?.shutdown(); textToSpeech = null
        stopBleScan(); stopAdvertisingRoom(); cameraProvider?.unbindAll(); cameraExecutor.shutdown(); copilotExecutor.shutdown()
        super.onDestroy()
    }
}
