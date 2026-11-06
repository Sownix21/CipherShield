package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.example.ui.theme.*
import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = CosmicBackground,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    CipherShieldApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

enum class ShieldTab {
    SHIELD, UNSHIELD, ABOUT
}

@Composable
fun CipherShieldApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val coroutineScope = rememberCoroutineScope()
    val operationGate = remember { SensitiveOperationGate() }
    
    // Core Navigation / Tab State
    var currentTab by remember { mutableStateOf(ShieldTab.SHIELD) }
    
    // Shield Tab Input State
    var shieldRawInput by remember { mutableStateOf("") }
    var shieldPassword by remember { mutableStateOf("") }
    var shieldedOutput by remember { mutableStateOf("") }
    var generatedPasswordUsed by remember { mutableStateOf("") }
    var isShieldPasswordVisible by remember { mutableStateOf(false) }
    var showShieldQrCode by remember { mutableStateOf(false) }
    var isShielding by remember { mutableStateOf(false) }
    
    // Unshield Tab State
    var unshieldPayloadInput by remember { mutableStateOf("") }
    var unshieldPassword by remember { mutableStateOf("") }
    var unshieldedResult by remember { mutableStateOf("") }
    var isUnshieldPasswordVisible by remember { mutableStateOf(false) }
    var unshieldError by remember { mutableStateOf<String?>(null) }
    var isUnshielding by remember { mutableStateOf(false) }
    
    // Volatile Memory Countdown State (for Decrypted result)
    var countdownSeconds by remember { mutableIntStateOf(0) }
    var countdownTimerActive by remember { mutableStateOf(false) }
    
    // Camera Scan Overlay state
    var showCameraScanner by remember { mutableStateOf(false) }
    var scannerForTab by remember { mutableStateOf(ShieldTab.UNSHIELD) } // which tab is scanning

    // Remove all app-held secrets as soon as the UI leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                operationGate.invalidate()
                isShieldPasswordVisible = false
                isUnshieldPasswordVisible = false
                shieldRawInput = ""
                shieldPassword = ""
                shieldedOutput = ""
                generatedPasswordUsed = ""
                unshieldPayloadInput = ""
                unshieldPassword = ""
                unshieldedResult = ""
                unshieldError = null
                countdownSeconds = 0
                countdownTimerActive = false
                showShieldQrCode = false
                showCameraScanner = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // Gallery Image Picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetTab = scannerForTab
            coroutineScope.launch {
                val token = operationGate.begin()
                val qrText = withContext(Dispatchers.IO) { decodeUriToQr(context, uri) }
                if (!operationGate.isCurrent(token) || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                if (qrText != null) {
                    val version = CipherShieldEngine.detectVersion(qrText)
                    if (version != null) {
                        unshieldPayloadInput = qrText
                        currentTab = ShieldTab.UNSHIELD
                        Toast.makeText(context, "CipherShield $version payload loaded from gallery", Toast.LENGTH_SHORT).show()
                    } else if (targetTab == ShieldTab.SHIELD) {
                        shieldRawInput = qrText
                        currentTab = ShieldTab.SHIELD
                        Toast.makeText(context, "QR text loaded into Shield input", Toast.LENGTH_SHORT).show()
                    } else {
                        unshieldPayloadInput = qrText
                        currentTab = ShieldTab.UNSHIELD
                        Toast.makeText(context, "Text loaded from QR", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No readable QR code found in selected image", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Mark sensitive clipboard data and remove it after a short exposure window.
    fun copySecurely(label: String, text: String, isSensitive: Boolean) {
        val clipData = ClipData.newPlainText(label, text)
        if (isSensitive) {
            clipData.description.extras = PersistableBundle().apply {
                // Documented literal keeps this rendering hint safe on API 24-32 too.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        try {
            clipboardManager.setPrimaryClip(clipData)
        } catch (_: RuntimeException) {
            Toast.makeText(context, "Clipboard unavailable or text too large. Use Save file instead.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        if (isSensitive) {
            val expectedHash = sha256Utf8(text)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val currentText = clipboardManager.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.text
                        ?.toString()
                    if (currentText != null) {
                        val currentHash = sha256Utf8(currentText)
                        try {
                            if (MessageDigest.isEqual(expectedHash, currentHash)) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    clipboardManager.clearPrimaryClip()
                                } else {
                                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Cleared", ""))
                                }
                            }
                        } finally {
                            Arrays.fill(currentHash, 0.toByte())
                        }
                    }
                } catch (_: RuntimeException) {
                    // Android can deny clipboard access while this app is in the background.
                } finally {
                    Arrays.fill(expectedHash, 0.toByte())
                }
            }, 60_000)
        }
    }

    // Auto-countdown ticker for volatile unshielded results
    LaunchedEffect(countdownTimerActive, countdownSeconds) {
        if (countdownTimerActive && countdownSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            countdownSeconds--
            if (countdownSeconds == 0) {
                unshieldedResult = ""
                countdownTimerActive = false
                Toast.makeText(context, "Decrypted data cleared from app state", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-mode detection helper on typing or pasting
    fun handleRawInputDetection(text: String, isPayloadInput: Boolean) {
        operationGate.invalidate()
        if (isPayloadInput) {
            unshieldedResult = ""
            unshieldError = null
            countdownTimerActive = false
            countdownSeconds = 0
        } else {
            shieldedOutput = ""
        }
        val maxChars = if (isPayloadInput) {
            CipherShieldEngine.MAX_ENCODED_PAYLOAD_CHARS
        } else {
            CipherShieldEngine.MAX_PLAINTEXT_BYTES
        }
        if (text.length > maxChars) {
            Toast.makeText(context, "Input exceeds the 5 MiB safety limit", Toast.LENGTH_LONG).show()
            return
        }
        val firstContent = text.indexOfFirst { it != ' ' && it != '\t' && it != '\r' && it != '\n' }
        val looksLikeEnvelope = firstContent >= 0 && text.regionMatches(firstContent, "Q1N2", 0, 4)
        val detected = if (looksLikeEnvelope) CipherShieldEngine.detectVersion(text) else null
        if (detected != null) {
            if (isPayloadInput) {
                unshieldPayloadInput = text
            } else {
                // User pasted encrypted payload into Shield screen - Auto-detect and route
                unshieldPayloadInput = text
                shieldRawInput = "" // Clear the shield raw input to avoid confusion
                currentTab = ShieldTab.UNSHIELD
                Toast.makeText(context, "$detected payload detected. Routed to Unshield mode.", Toast.LENGTH_LONG).show()
            }
        } else {
            if (isPayloadInput) {
                unshieldPayloadInput = text
            } else {
                shieldRawInput = text
            }
        }
    }

    // Import arbitrary UTF-8 text formats without requesting broad storage access.
    val textFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val token = operationGate.begin()
                val imported = runCatching {
                    withContext(Dispatchers.IO) {
                        val text = readBoundedUtf8Text(context, uri, CipherShieldEngine.MAX_ENCODED_PAYLOAD_CHARS)
                        val payload = CipherShieldEngine.detectVersion(text) != null
                        require(payload || utf8Size(text) <= CipherShieldEngine.MAX_PLAINTEXT_BYTES) { "Plaintext file exceeds 5 MiB" }
                        text to payload
                    }
                }
                if (!operationGate.isCurrent(token) || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                imported.onSuccess { (text, payload) ->
                    if (payload) {
                        unshieldPayloadInput = text
                    } else {
                        shieldRawInput = text
                    }
                    currentTab = if (payload) ShieldTab.UNSHIELD else ShieldTab.SHIELD
                    Toast.makeText(context, "Text file loaded", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "File must be valid UTF-8 text no larger than 5 MiB", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Explicit export holds only the selected output while Android's file picker is open.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var confirmPlaintextExport by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val output = pendingExport
        pendingExport = null
        if (uri != null && output != null) coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(output) }
                        ?: error("Destination unavailable")
                }.isSuccess
            }
            Toast.makeText(context, if (saved) "File saved" else "Could not save file; destination may be incomplete", Toast.LENGTH_LONG).show()
        } else if (uri != null) {
            Toast.makeText(context, "Export was interrupted. The selected file may be empty; please try again.", Toast.LENGTH_LONG).show()
        }
    }
    fun launchExport(text: String, filename: String) {
        if (pendingExport != null) return
        pendingExport = text
        try {
            exportLauncher.launch(filename)
        } catch (_: RuntimeException) {
            pendingExport = null
            Toast.makeText(context, "No file picker is available on this device", Toast.LENGTH_LONG).show()
        }
    }
    if (confirmPlaintextExport) AlertDialog(
        onDismissRequest = { confirmPlaintextExport = false },
        title = { Text("Save decrypted text?") },
        text = { Text("This creates an unencrypted file outside CipherShield that does not expire. The selected text is held while you choose a destination. Choose a trusted local folder; a cloud provider may upload it.") },
        confirmButton = { TextButton(onClick = {
            confirmPlaintextExport = false
            launchExport(unshieldedResult, "ciphershield-decrypted.txt")
        }, enabled = unshieldedResult.isNotEmpty()) { Text("Save plaintext") } },
        dismissButton = { TextButton(onClick = { confirmPlaintextExport = false }) { Text("Cancel") } },
    )

    // Main view
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF050B14),
                        Color(0xFF020617)
                    )
                )
            )
    ) {
        // Ambient Background Glows
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(TechCyan.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(w * 0.2f, h * 0.12f),
                    radius = w * 0.75f
                ),
                center = Offset(w * 0.2f, h * 0.12f),
                radius = w * 0.75f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ScannerPurple.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(w * 0.85f, h * 0.45f),
                    radius = w * 0.8f
                ),
                center = Offset(w * 0.85f, h * 0.45f),
                radius = w * 0.8f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(EmeraldGreen.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.15f, h * 0.85f),
                    radius = w * 0.65f
                ),
                center = Offset(w * 0.15f, h * 0.85f),
                radius = w * 0.65f
            )
        }

        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            
            // --- 1. STICKY HEADER WITH GLOWING LOCAL SECURE MODE INDICATOR ---
            StickyHeader()

            // --- 2. PREMIUM TAB BAR SELECTOR ---
            TabBar(
                currentTab = currentTab,
                onTabSelected = { tab ->
                    val previousTab = currentTab
                    if (tab != previousTab) operationGate.invalidate()
                    isShieldPasswordVisible = false
                    isUnshieldPasswordVisible = false
                    currentTab = tab
                    if (tab != previousTab && previousTab == ShieldTab.SHIELD) {
                        shieldRawInput = ""
                        shieldPassword = ""
                        shieldedOutput = ""
                        generatedPasswordUsed = ""
                        showShieldQrCode = false
                    }
                    if (tab != previousTab && previousTab == ShieldTab.UNSHIELD) {
                        unshieldPayloadInput = ""
                        unshieldPassword = ""
                        unshieldedResult = ""
                        unshieldError = null
                        countdownTimerActive = false
                        countdownSeconds = 0
                    }
                }
            )

            // --- 3. SCROLLABLE TAB BODY ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (currentTab) {
                    ShieldTab.SHIELD -> {
                        ShieldTabContent(
                            rawInput = shieldRawInput,
                            onRawInputChange = { handleRawInputDetection(it, false) },
                            password = shieldPassword,
                            onPasswordChange = { value ->
                                if (value.length <= CipherShieldEngine.MAX_PASSWORD_CHARS) {
                                    operationGate.invalidate()
                                    shieldedOutput = ""
                                    shieldPassword = value
                                    if (value != generatedPasswordUsed) generatedPasswordUsed = ""
                                }
                            },
                            shieldedOutput = shieldedOutput,
                            generatedPasswordUsed = generatedPasswordUsed,
                            isPasswordVisible = isShieldPasswordVisible,
                            isShielding = isShielding,
                            onTogglePasswordVisibility = { isShieldPasswordVisible = !isShieldPasswordVisible },
                            onGeneratePassword = {
                                operationGate.invalidate()
                                shieldedOutput = ""
                                val generated = generateHighEntropyKey()
                                shieldPassword = generated
                                generatedPasswordUsed = generated
                                Toast.makeText(context, "High-entropy key generated!", Toast.LENGTH_SHORT).show()
                            },
                            onShield = {
                                if (isShielding || isUnshielding) {
                                    Toast.makeText(context, "Please wait for the current operation", Toast.LENGTH_SHORT).show()
                                } else if (shieldRawInput.isEmpty()) {
                                    Toast.makeText(context, "Please enter plaintext input to shield", Toast.LENGTH_SHORT).show()
                                } else if (shieldPassword.isEmpty()) {
                                    Toast.makeText(context, "Please configure a shield password", Toast.LENGTH_SHORT).show()
                                } else if (shieldPassword.length < 16 || evaluatePasswordStrength(shieldPassword).score < KeyStrengthEvaluator.StrengthTier.STRONG.score) {
                                    Toast.makeText(context, "Use a Strong or Excellent password with at least 16 characters", Toast.LENGTH_LONG).show()
                                } else {
                                    isShielding = true
                                    val token = operationGate.begin()
                                    val input = shieldRawInput
                                    val passwordSnapshot = shieldPassword
                                    val generatedSnapshot = generatedPasswordUsed.takeIf { it == passwordSnapshot }.orEmpty()
                                    coroutineScope.launch {
                                        try {
                                            val output = withContext(Dispatchers.Default) {
                                                val pwdChars = passwordSnapshot.toCharArray()
                                                try {
                                                    CipherShieldEngine.shield(input, pwdChars)
                                                } finally {
                                                    Arrays.fill(pwdChars, '\u0000')
                                                }
                                            }
                                            if (operationGate.isCurrent(token) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                                shieldedOutput = output
                                                generatedPasswordUsed = generatedSnapshot
                                                shieldRawInput = ""
                                                shieldPassword = ""
                                                Toast.makeText(context, "Encryption completed with CSv3 spec", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            if (operationGate.isCurrent(token)) Toast.makeText(context, "Encryption failed. Check text encoding and the 5 MiB size limit.", Toast.LENGTH_LONG).show()
                                        } finally {
                                            isShielding = false
                                        }
                                    }
                                }
                            },
                            onCopyOutput = { copySecurely("Protected Ciphertext", shieldedOutput, false) },
                            onCopyGeneratedPassword = { copySecurely("Generated Password Key", generatedPasswordUsed, true) },
                            onWipeVolatile = {
                                operationGate.invalidate()
                                shieldedOutput = ""
                                generatedPasswordUsed = ""
                                shieldPassword = ""
                                shieldRawInput = ""
                                Toast.makeText(context, "Sensitive app state cleared", Toast.LENGTH_SHORT).show()
                            },
                            onShowQr = { showShieldQrCode = true },
                            onScanCamera = {
                                operationGate.invalidate()
                                scannerForTab = ShieldTab.SHIELD
                                showCameraScanner = true
                            },
                            onImportGallery = {
                                scannerForTab = ShieldTab.SHIELD
                                galleryLauncher.launch("image/*")
                            },
                            onImportTextFile = { textFileLauncher.launch(arrayOf("*/*")) },
                            onSaveOutput = {
                                launchExport(shieldedOutput, "ciphershield-encrypted.txt")
                            },
                        )
                    }
                    ShieldTab.UNSHIELD -> {
                        UnshieldTabContent(
                            payloadInput = unshieldPayloadInput,
                            onPayloadInputChange = { handleRawInputDetection(it, true) },
                            password = unshieldPassword,
                            onPasswordChange = {
                                if (it.length <= CipherShieldEngine.MAX_PASSWORD_CHARS) {
                                    operationGate.invalidate()
                                    unshieldPassword = it
                                }
                            },
                            unshieldedResult = unshieldedResult,
                            isPasswordVisible = isUnshieldPasswordVisible,
                            isUnshielding = isUnshielding,
                            onTogglePasswordVisibility = { isUnshieldPasswordVisible = !isUnshieldPasswordVisible },
                            unshieldError = unshieldError,
                            countdownSeconds = countdownSeconds,
                            onUnshield = {
                                if (isShielding || isUnshielding) {
                                    Toast.makeText(context, "Please wait for the current operation", Toast.LENGTH_SHORT).show()
                                } else if (unshieldPayloadInput.isBlank()) {
                                    Toast.makeText(context, "Please enter encrypted payload", Toast.LENGTH_SHORT).show()
                                } else if (unshieldPassword.isEmpty()) {
                                    Toast.makeText(context, "Please enter the password", Toast.LENGTH_SHORT).show()
                                } else {
                                    unshieldError = null
                                    isUnshielding = true
                                    val token = operationGate.begin()
                                    val input = unshieldPayloadInput
                                    val passwordSnapshot = unshieldPassword
                                    coroutineScope.launch {
                                        try {
                                            val decrypted = withContext(Dispatchers.Default) {
                                                val pwdChars = passwordSnapshot.toCharArray()
                                                try {
                                                    CipherShieldEngine.unshield(input, pwdChars)
                                                } finally {
                                                    Arrays.fill(pwdChars, '\u0000')
                                                }
                                            }
                                            if (operationGate.isCurrent(token) && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                                unshieldedResult = decrypted
                                                unshieldPayloadInput = ""
                                                unshieldPassword = ""
                                                countdownSeconds = 30
                                                countdownTimerActive = true
                                                Toast.makeText(context, "Payload successfully unshielded", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            if (!operationGate.isCurrent(token)) return@launch
                                            unshieldedResult = ""
                                            countdownTimerActive = false
                                            countdownSeconds = 0
                                            unshieldError = "Unshield failed. Wrong password, modified bytes, or bad format."
                                        } finally {
                                            isUnshielding = false
                                        }
                                    }
                                }
                            },
                            onCopyResult = { copySecurely("Decrypted Text", unshieldedResult, true) },
                            onShredResult = {
                                operationGate.invalidate()
                                unshieldedResult = ""
                                countdownTimerActive = false
                                countdownSeconds = 0
                                Toast.makeText(context, "Decrypted data cleared from app state", Toast.LENGTH_SHORT).show()
                            },
                            onImportGallery = {
                                scannerForTab = ShieldTab.UNSHIELD
                                galleryLauncher.launch("image/*")
                            },
                            onImportTextFile = { textFileLauncher.launch(arrayOf("*/*")) },
                            onSaveResult = { confirmPlaintextExport = true },
                            onScanCamera = {
                                operationGate.invalidate()
                                scannerForTab = ShieldTab.UNSHIELD
                                showCameraScanner = true
                            }
                        )
                    }
                    ShieldTab.ABOUT -> {
                        AboutTabContent()
                    }
                }
            }
        }

        // --- 4. CAMERA SCANNER MODAL OVERLAY ---
        if (showCameraScanner) {
            CameraScanOverlay(
                onQrCodeScanned = { scannedText ->
                    operationGate.invalidate()
                    shieldedOutput = ""
                    unshieldedResult = ""
                    unshieldError = null
                    countdownTimerActive = false
                    countdownSeconds = 0
                    showCameraScanner = false
                    val version = CipherShieldEngine.detectVersion(scannedText)
                    if (version != null) {
                        unshieldPayloadInput = scannedText
                        currentTab = ShieldTab.UNSHIELD
                        Toast.makeText(context, "CipherShield $version QR Code scanned successfully", Toast.LENGTH_LONG).show()
                    } else if (scannerForTab == ShieldTab.SHIELD) {
                        shieldRawInput = scannedText
                        currentTab = ShieldTab.SHIELD
                        Toast.makeText(context, "Scanned into Shield input field", Toast.LENGTH_SHORT).show()
                    } else {
                        unshieldPayloadInput = scannedText
                        currentTab = ShieldTab.UNSHIELD
                        Toast.makeText(context, "QR text scanned", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = { showCameraScanner = false }
            )
        }

        // --- 5. QR CODE PREVIEW DIALOG ---
        if (showShieldQrCode && shieldedOutput.isNotEmpty()) {
            QrCodeDialog(
                text = shieldedOutput,
                onDismiss = { showShieldQrCode = false }
            )
        }
    }
}

// --- SUB-COMPONENTS ---

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    backgroundColor: Color = Color.White.copy(alpha = 0.07f),
    borderColorTop: Color = Color.White.copy(alpha = 0.40f),
    borderColorBottom: Color = Color.White.copy(alpha = 0.10f),
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val borderBrush = Brush.verticalGradient(
        colors = listOf(borderColorTop, borderColorBottom)
    )
    Surface(
        modifier = modifier
            .clip(shape)
            .border(BorderStroke(borderWidth, borderBrush), shape),
        color = backgroundColor,
        shape = shape
    ) {
        Column(
            modifier = Modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent))).padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun StickyHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(TechCyan.copy(alpha = 0.15f), ScannerPurple.copy(alpha = 0.16f)))),
        )
        Column(Modifier.weight(1f)) {
            Text("CipherShield", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = WhiteText)
            Text("Your words. Only your key.", fontSize = 12.sp, color = SoftText)
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = EmeraldGreen.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.25f)),
        ) {
            Text("Offline", fontSize = 11.sp, color = EmeraldGreen,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
        }
    }
}

@Composable
fun TabBar(currentTab: ShieldTab, onTabSelected: (ShieldTab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        val shape = RoundedCornerShape(24.dp)
        val borderBrush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))
        )
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(shape)
                .border(BorderStroke(1.dp, borderBrush), shape),
            color = Color.White.copy(alpha = 0.08f),
            shape = shape
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val totalWidth = maxWidth
                val tabsCount = ShieldTab.values().size
                val tabWidth = totalWidth / tabsCount
                val selectedIndex = currentTab.ordinal

                val indicatorOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "TabPillIndicatorOffset"
                )

                // Single-container sliding pill tab active indicator
                Box(
                    modifier = Modifier
                        .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(TechCyan, ScannerPurple)
                            )
                        )
                )

                // Row options wrapped in a single unified glass container
                Row(
                    modifier = Modifier.fillMaxSize().selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShieldTab.values().forEach { tab ->
                        val active = currentTab == tab

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(selected = active, role = Role.Tab, onClick = { onTabSelected(tab) })
                                .testTag("tab_${tab.name.lowercase()}"),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (tab) {
                                    ShieldTab.SHIELD -> Icons.Filled.Shield
                                    ShieldTab.UNSHIELD -> Icons.Filled.LockOpen
                                    ShieldTab.ABOUT -> Icons.Filled.Info
                                },
                                contentDescription = null,
                                tint = if (active) CosmicBackground else SoftText,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (tab) {
                                    ShieldTab.SHIELD -> "Encrypt"
                                    ShieldTab.UNSHIELD -> "Decrypt"
                                    ShieldTab.ABOUT -> "About"
                                },
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (active) CosmicBackground else WhiteText,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- SHIELD TAB LAYOUT ---

@Composable
fun ShieldTabContent(
    rawInput: String,
    onRawInputChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    shieldedOutput: String,
    generatedPasswordUsed: String,
    isPasswordVisible: Boolean,
    isShielding: Boolean = false,
    onTogglePasswordVisibility: () -> Unit,
    onGeneratePassword: () -> Unit,
    onShield: () -> Unit,
    onCopyOutput: () -> Unit,
    onCopyGeneratedPassword: () -> Unit,
    onWipeVolatile: () -> Unit,
    onShowQr: () -> Unit,
    onScanCamera: () -> Unit,
    onImportGallery: () -> Unit,
    onImportTextFile: () -> Unit,
    onSaveOutput: () -> Unit,
) {
    val pwdString = password
    val strength = remember(pwdString) { evaluatePasswordStrength(pwdString) }
    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        
        // Header info
        GlassCard(
            borderColorTop = TechCyan.copy(alpha = 0.5f),
            borderColorBottom = TechCyan.copy(alpha = 0.15f),
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.EnhancedEncryption, contentDescription = "Shield mode", tint = TechCyan, modifier = Modifier.size(24.dp))
                Column {
                    Text("Private text. Protected locally.", color = WhiteText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Messages, notes, credentials and VPN configs. Keep the password separate from your encrypted text.", color = SoftText, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }

        // Input Text Area
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Text to protect", color = TechCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                
                // Small Paste from Clipboard button above text box
                TextButton(
                    onClick = {
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pastedText = clip.getItemAt(0).text?.toString().orEmpty()
                            if (pastedText.isNotEmpty()) {
                                onRawInputChange(pastedText)
                                Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste Clipboard", tint = TechCyan, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Paste", fontSize = 12.sp, color = TechCyan, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = rawInput,
                onValueChange = onRawInputChange,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text),
                placeholder = { Text("Enter any confidential text, secret notes, credentials, or proxy URIs...", color = SoftText, fontSize = 13.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .testTag("shield_raw_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WhiteText,
                    unfocusedTextColor = WhiteText,
                    focusedBorderColor = TechCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Actions to import/scan QR for Shielding
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onScanCamera,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(TechCyan.copy(alpha = 0.6f), TechCyan.copy(alpha = 0.2f)))),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Camera Scan", tint = TechCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan QR Camera", fontSize = 11.sp, color = TechCyan, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onImportGallery,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(ScannerPurple.copy(alpha = 0.6f), ScannerPurple.copy(alpha = 0.2f)))),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery Picker", tint = ScannerPurple, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import QR Photo", fontSize = 11.sp, color = ScannerPurple, fontWeight = FontWeight.Bold)
            }
        }

        Button(
            onClick = onImportTextFile,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
            border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Filled.Description, contentDescription = "Import UTF-8 text file", tint = EmeraldGreen, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Import Any UTF-8 Text File", fontSize = 11.sp, color = EmeraldGreen, fontWeight = FontWeight.Bold)
        }

        // Shield Password Area
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Password", color = TechCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Generate Key",
                    color = ScannerPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onGeneratePassword() }
                        .padding(4.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = pwdString,
                onValueChange = onPasswordChange,
                placeholder = { Text("Enter a high-strength password...", color = SoftText, fontSize = 13.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shield_password_input"),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle password visibility",
                            tint = SoftText
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WhiteText,
                    unfocusedTextColor = WhiteText,
                    focusedBorderColor = TechCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
            
            // Segmented Strength Indicator
            if (pwdString.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PasswordStrengthVisual(strength = strength)
            }
        }

        // Action Shield Button
        Button(
            onClick = onShield,
            enabled = !isShielding,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("btn_shield"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = CosmicBackground
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(TechCyan, ScannerPurple)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isShielding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = CosmicBackground,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypting…", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CosmicBackground)
                    } else {
                        Icon(Icons.Filled.Security, contentDescription = "Encrypt", tint = CosmicBackground, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypt text", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = CosmicBackground)
                    }
                }
            }
        }

        // Outputs
        AnimatedVisibility(
            visible = shieldedOutput.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            GlassCard(
                borderColorTop = TechCyan.copy(alpha = 0.6f),
                borderColorBottom = TechCyan.copy(alpha = 0.2f),
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CSv3 Shielded Payload (Base64)", color = TechCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Wipe",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onWipeVolatile() }
                            .padding(4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Cryptographic display
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = shieldedOutput.take(8_000),
                        color = WhiteText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions panel
                Text("Preview only · Copy or save keeps the complete payload", color = SoftText, fontSize = 11.sp)
                OutlinedButton(onClick = onSaveOutput, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save encrypted file")
                }
                if (generatedPasswordUsed.isNotEmpty()) Text(
                    "Copy your generated password before opening the file picker. Leaving the app clears the displayed key.",
                    color = AmberAccent, fontSize = 11.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCopyOutput,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, TechCyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text", tint = TechCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Payload", fontSize = 11.sp, color = TechCyan, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onShowQr,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, ScannerPurple.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.QrCode, contentDescription = "QR", tint = ScannerPurple, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Show QR", fontSize = 11.sp, color = ScannerPurple, fontWeight = FontWeight.Bold)
                    }
                }

                if (generatedPasswordUsed.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Auto-Generated Password", color = ScannerPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = generatedPasswordUsed,
                            color = WhiteText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = onCopyGeneratedPassword,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy key", tint = ScannerPurple, modifier = Modifier.size(18.dp))
                        }
                    }
                    
                    // Sharing guidance
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassCard(
                        borderColorTop = ScannerPurple.copy(alpha = 0.4f),
                        borderColorBottom = ScannerPurple.copy(alpha = 0.1f),
                        backgroundColor = Color(0xFF1E1B4B).copy(alpha = 0.5f),
                        contentPadding = PaddingValues(10.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = "Security guidance", tint = ScannerPurple, modifier = Modifier.size(16.dp))
                            Text(
                                text = "TIP: Exchange the password and payload through separate trusted channels. This reduces the chance that one compromised channel exposes both, but it cannot guarantee transport security.",
                                fontSize = 10.sp,
                                color = SoftText,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordStrengthVisual(strength: KeyStrengthEvaluator.StrengthTier) {
    val barsCount = strength.score
    val activeColor = when (strength) {
        KeyStrengthEvaluator.StrengthTier.WEAK -> Color(0xFFEF4444) // Red
        KeyStrengthEvaluator.StrengthTier.MEDIUM -> Color(0xFFFBBF24) // Yellow
        KeyStrengthEvaluator.StrengthTier.STRONG -> Color(0xFF10B981) // Green
        KeyStrengthEvaluator.StrengthTier.EXCELLENT -> TechCyan // Cyan
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Key Strength:",
                fontSize = 10.sp,
                color = SoftText
            )
            Text(
                text = strength.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in 1..4) {
                val filled = i <= barsCount
                val color = if (filled) activeColor else Color(0xFF334155)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color)
                )
            }
        }
    }
}

// --- UNSHIELD TAB LAYOUT ---

@Composable
fun UnshieldTabContent(
    payloadInput: String,
    onPayloadInputChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    unshieldedResult: String,
    isPasswordVisible: Boolean,
    isUnshielding: Boolean = false,
    onTogglePasswordVisibility: () -> Unit,
    unshieldError: String?,
    countdownSeconds: Int,
    onUnshield: () -> Unit,
    onCopyResult: () -> Unit,
    onShredResult: () -> Unit,
    onImportGallery: () -> Unit,
    onImportTextFile: () -> Unit,
    onSaveResult: () -> Unit,
    onScanCamera: () -> Unit
) {
    val pwdString = password
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        
        // Header info
        GlassCard(
            borderColorTop = ScannerPurple.copy(alpha = 0.5f),
            borderColorBottom = ScannerPurple.copy(alpha = 0.15f),
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = "Unshield mode", tint = ScannerPurple, modifier = Modifier.size(24.dp))
                Column {
                    Text("Unlock your text", color = WhiteText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Decryption stays on this device. The decrypted result clears after 30 seconds; inputs clear when the app leaves the foreground.", color = SoftText, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }

        OutlinedButton(onClick = onImportTextFile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import encrypted text file")
        }
        // Actions to import QR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onScanCamera,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(TechCyan.copy(alpha = 0.6f), TechCyan.copy(alpha = 0.2f)))),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Camera Scan", tint = TechCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan QR Camera", fontSize = 11.sp, color = TechCyan, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onImportGallery,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(ScannerPurple.copy(alpha = 0.6f), ScannerPurple.copy(alpha = 0.2f)))),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery Picker", tint = ScannerPurple, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import QR Photo", fontSize = 11.sp, color = ScannerPurple, fontWeight = FontWeight.Bold)
            }
        }

        // Payload Input Area
        Column {
            Text("CSv2 / CSv3 Payload (Base64)", color = TechCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = payloadInput,
                onValueChange = onPayloadInputChange,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                placeholder = { Text("Paste CipherShield Base64 text (Q1N2…)", color = SoftText, fontSize = 13.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .testTag("unshield_payload_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WhiteText,
                    unfocusedTextColor = WhiteText,
                    focusedBorderColor = TechCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Password input
        Column {
            Text("Password", color = TechCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = pwdString,
                onValueChange = onPasswordChange,
                placeholder = { Text("Enter correct password...", color = SoftText, fontSize = 13.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unshield_password_input"),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle password visibility",
                            tint = SoftText
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WhiteText,
                    unfocusedTextColor = WhiteText,
                    focusedBorderColor = TechCyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = Color.White.copy(alpha = 0.06f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Decrypt Error (if any)
        if (unshieldError != null) {
            GlassCard(
                borderColorTop = Color(0xFFEF4444).copy(alpha = 0.6f),
                borderColorBottom = Color(0xFFEF4444).copy(alpha = 0.2f),
                backgroundColor = Color(0xFF450A0A).copy(alpha = 0.5f),
                contentPadding = PaddingValues(12.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Error, contentDescription = "Decryption failure", tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                    Text(unshieldError, color = Color(0xFFFCA5A5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Unshield Button
        Button(
            onClick = onUnshield,
            enabled = !isUnshielding,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("btn_unshield"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = CosmicBackground
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(ScannerPurple, EmeraldGreen)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isUnshielding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = CosmicBackground,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decrypting…", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CosmicBackground)
                    } else {
                        Icon(Icons.Filled.LockOpen, contentDescription = "Decrypt", tint = CosmicBackground, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decrypt text", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = CosmicBackground)
                    }
                }
            }
        }

        // Decrypted Result Block
        AnimatedVisibility(
            visible = unshieldedResult.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            GlassCard(
                borderColorTop = EmeraldGreen.copy(alpha = 0.6f),
                borderColorBottom = EmeraldGreen.copy(alpha = 0.2f),
                contentPadding = PaddingValues(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Decrypted Text (Temporary App State)", color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                            text = "Clear Now",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onShredResult() }
                            .padding(4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                // Countdown text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Timer, contentDescription = "Countdown", tint = SoftText, modifier = Modifier.size(14.dp))
                    Text(
                        text = "Decrypted app state clears in $countdownSeconds seconds...",
                        fontSize = 11.sp,
                        color = SoftText
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Progress Bar of volatile decay
                LinearProgressIndicator(
                    progress = { countdownSeconds / 30f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = EmeraldGreen,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Result Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = unshieldedResult.take(8_000),
                        color = WhiteText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 12,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Copy Action
                Text("Preview only · Copy or save keeps the complete text", color = SoftText, fontSize = 11.sp)
                OutlinedButton(onClick = onSaveResult, modifier = Modifier.fillMaxWidth()) {
                    Text("Save decrypted text…")
                }
                Button(
                    onClick = onCopyResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy decrypted data", tint = EmeraldGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Decrypted Plaintext", fontSize = 11.sp, color = EmeraldGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- ABOUT TAB CONTENT ---

@Composable
fun AboutTabContent() {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        
        // Brand Hero Box
        GlassCard(
            borderColorTop = TechCyan.copy(alpha = 0.5f),
            borderColorBottom = ScannerPurple.copy(alpha = 0.2f),
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "CipherShield private document with a C lock seal",
                    modifier = Modifier.size(112.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CipherShield Utility",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = WhiteText
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} Offline",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TechCyan,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Protect UTF-8 messages, notes, credentials and VPN configurations on your device. Compatible software and the exact password can decrypt the result. Only share the encrypted output. A file picker or messaging app you choose may use its own network connection; CipherShield itself has no internet permission.",
                    fontSize = 11.sp,
                    color = SoftText,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }

        // Specs & Cryptographic Engine properties
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Engine Architecture Properties", color = TechCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            
            SpecRow(title = "Symmetric Encryption", desc = "AES-256-GCM (128-bit tag)")
            SpecRow(title = "Key Derivation Function", desc = "PBKDF2-HMAC-SHA512 (600,000 iterations)")
            SpecRow(title = "Data Integrity Layer", desc = "Encrypt-then-HMAC (HMAC-SHA512 split-key)")
            SpecRow(title = "Network Communication", desc = "No INTERNET permission or cloud SDK")
            SpecRow(title = "Sensitive State", desc = "Best-effort buffer zeroing + background clear")
            SpecRow(title = "Payload Formats Supported", desc = "CSv2 / CSv3 Auto-Mode detection")
        }

        // Security Disclaimers
        GlassCard(
            borderColorTop = Color(0xFFEF4444).copy(alpha = 0.5f),
            borderColorBottom = Color(0xFFEF4444).copy(alpha = 0.15f),
            backgroundColor = Color(0xFF450A0A).copy(alpha = 0.3f),
            contentPadding = PaddingValues(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = "Disclaimer", tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                    Text("Security Disclaimer", color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Safety depends on a strong password, a trusted device and safe key exchange. The strength meter is guidance, not a guarantee. There is no server or password recovery. Screen protection and memory clearing are best-effort. Clipboard expiry can be delayed or prevented by Android or process termination. Exported plaintext remains in the destination; pending exports retain the selected text until the file picker returns. If you lose the password, CipherShield cannot recover your text.",
                    color = SoftText,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Developed by SONIX",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SoftText,
                letterSpacing = 2.sp
            )
            IconButton(
                modifier = Modifier.size(48.dp),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sownix21")))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.github_profile_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = stringResource(R.string.open_sonix_github),
                    tint = SoftText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun SpecRow(title: String, desc: String) {
    GlassCard(
        borderColorTop = Color.White.copy(alpha = 0.25f),
        borderColorBottom = Color.White.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = SoftText, fontSize = 12.sp)
            Text(desc, color = WhiteText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// --- QR CODE RENDERER (NATIVE COMPOSE CANVAS DRAW) ---

@Composable
fun QrCodeDisplay(text: String, sizeDp: Dp = 200.dp) {
    val generatedMatrix by produceState<BitMatrix?>(initialValue = null, key1 = text) {
        value = withContext(Dispatchers.Default) { try {
            require(text.length <= 2_953) { "Too large for QR" }
            val writer = MultiFormatWriter()
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 4
            )
            writer.encode(text, BarcodeFormat.QR_CODE, 256, 256, hints)
        } catch (e: Exception) {
            null
        } }
    }

    val matrix = generatedMatrix
    if (matrix != null) {
        val width = matrix.width
        val height = matrix.height
        
        Canvas(
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
        ) {
            val cellWidth = size.width / width
            val cellHeight = size.height / height

            for (x in 0 until width) {
                for (y in 0 until height) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x * cellWidth, y * cellHeight),
                            size = Size(cellWidth + 0.5f, cellHeight + 0.5f) // Avoid pixel alignment gaps
                        )
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(12.dp))
                .background(CosmicSurface),
            contentAlignment = Alignment.Center
        ) {
            Text("Too much text for a QR code. Save an encrypted file instead.", color = SoftText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
    }
}

// --- QR PREVIEW DIALOG OVERLAY ---

@Composable
fun QrCodeDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = TechCyan, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = "Secure QR Payload",
                color = WhiteText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "This QR code contains the secure password-encrypted ciphertext. Anyone scanning must have your lock password to read.",
                    fontSize = 11.sp,
                    color = SoftText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                QrCodeDisplay(text = text, sizeDp = 220.dp)
            }
        },
        containerColor = CosmicCard,
        shape = RoundedCornerShape(16.dp)
    )
}

// --- CAMERA QR SCANNING MODAL ---

@Composable
fun CameraScanOverlay(
    onQrCodeScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { cameraGranted = it },
    )
    
    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraGranted) {
            CameraScanScreen(onQrCodeScanned = onQrCodeScanned)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Camera, contentDescription = "No Camera", tint = ScannerPurple, modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Permission Required",
                    color = WhiteText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CipherShield scans secure QR codes completely locally on-device. Please grant camera access to use this tool.",
                    color = SoftText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = TechCyan, contentColor = CosmicBackground)
                ) {
                    Text("Grant Permission", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onClose) {
                    Text("Cancel", color = SoftText)
                }
            }
        }

        // Close Floating Button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
        }
    }
}

@Composable
fun CameraScanScreen(
    onQrCodeScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val isDisposed = remember { AtomicBoolean(false) }

    DisposableEffect(cameraProviderFuture, analysisExecutor, isDisposed) {
        onDispose {
            isDisposed.set(true)
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            analysisExecutor.shutdownNow()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    if (isDisposed.get()) return@addListener
                    val cameraProvider = try {
                        cameraProviderFuture.get()
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e("CipherShieldCamera", "Camera provider failure", e)
                        return@addListener
                    }
                    val preview = CameraPreview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply {
                            setAnalyzer(analysisExecutor, QrCodeAnalyzer { text ->
                                if (!isDisposed.get() && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    cameraProvider.unbindAll()
                                    onQrCodeScanned(text)
                                }
                            })
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e("CipherShieldCamera", "Camera binding failure", e)
                    }
                }, mainExecutor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay framing guide
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val frameSize = size.minDimension * 0.6f
            val left = (w - frameSize) / 2f
            val top = (h - frameSize) / 2f
            
            // Draw dimmed outer bounds
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, 0f),
                size = Size(w, top)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, top + frameSize),
                size = Size(w, h - top - frameSize)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, top),
                size = Size(left, frameSize)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(left + frameSize, top),
                size = Size(w - left - frameSize, frameSize)
            )

            // Draw bright scan box corners (Tech Cyan)
            val cornerLen = 30f
            val strokeW = 6f
            
            // Top-Left
            drawLine(Color(0xFF22D3EE), Offset(left, top), Offset(left + cornerLen, top), strokeWidth = strokeW)
            drawLine(Color(0xFF22D3EE), Offset(left, top), Offset(left, top + cornerLen), strokeWidth = strokeW)
            
            // Top-Right
            drawLine(Color(0xFF22D3EE), Offset(left + frameSize, top), Offset(left + frameSize - cornerLen, top), strokeWidth = strokeW)
            drawLine(Color(0xFF22D3EE), Offset(left + frameSize, top), Offset(left + frameSize, top + cornerLen), strokeWidth = strokeW)
            
            // Bottom-Left
            drawLine(Color(0xFF22D3EE), Offset(left, top + frameSize), Offset(left + cornerLen, top + frameSize), strokeWidth = strokeW)
            drawLine(Color(0xFF22D3EE), Offset(left, top + frameSize), Offset(left, top + frameSize - cornerLen), strokeWidth = strokeW)
            
            // Bottom-Right
            drawLine(Color(0xFF22D3EE), Offset(left + frameSize, top + frameSize), Offset(left + frameSize - cornerLen, top + frameSize), strokeWidth = strokeW)
            drawLine(Color(0xFF22D3EE), Offset(left + frameSize, top + frameSize), Offset(left + frameSize, top + frameSize - cornerLen), strokeWidth = strokeW)
        }

        Text(
            text = "ALIGNED QR CODE INSIDE SCAN BOX",
            color = TechCyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// Multi-pass ZXing QR Decoder for dense, complex, inverted, or low-contrast QR codes
fun decodeLuminanceSource(source: LuminanceSource, reader: MultiFormatReader): Result? {
    // Pass 1: Standard Hybrid Binarizer
    try {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val res = reader.decodeWithState(bitmap)
        if (res != null) return res
    } catch (_: Exception) {} finally {
        reader.reset()
    }

    // Pass 2: Global Histogram Binarizer (better for high-density pixels / uneven contrast)
    try {
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
        val res = reader.decodeWithState(bitmap)
        if (res != null) return res
    } catch (_: Exception) {} finally {
        reader.reset()
    }

    // Pass 3: Inverted Hybrid Binarizer (for dark mode or inverted QR codes)
    try {
        val invertedSource = source.invert()
        val bitmap = BinaryBitmap(HybridBinarizer(invertedSource))
        val res = reader.decodeWithState(bitmap)
        if (res != null) return res
    } catch (_: Exception) {} finally {
        reader.reset()
    }

    // Pass 4: Inverted Global Histogram Binarizer
    try {
        val invertedSource = source.invert()
        val bitmap = BinaryBitmap(GlobalHistogramBinarizer(invertedSource))
        val res = reader.decodeWithState(bitmap)
        if (res != null) return res
    } catch (_: Exception) {} finally {
        reader.reset()
    }

    return null
}

// Custom ZXing Image Frame Analyzer
class QrCodeAnalyzer(private val onQrCodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val delivered = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val width = image.width
            val height = image.height
            require(width in 1..8_192 && height in 1..8_192) { "Unsupported camera frame size" }
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val basePosition = buffer.position()
            val data = ByteArray(Math.multiplyExact(width, height))
            if (pixelStride == 1) {
                for (row in 0 until height) {
                    val rowStart = basePosition + row * rowStride
                    require(rowStart >= basePosition && rowStart + width <= buffer.limit()) { "Malformed camera plane" }
                    buffer.position(rowStart)
                    buffer.get(data, row * width, width)
                }
            } else {
                for (row in 0 until height) {
                    val rowStart = basePosition + row * rowStride
                    val rowEnd = rowStart + (width - 1) * pixelStride
                    require(rowStart >= basePosition && rowEnd < buffer.limit()) { "Malformed camera plane" }
                    for (column in 0 until width) {
                        data[row * width + column] = buffer.get(rowStart + column * pixelStride)
                    }
                }
            }

            val source = PlanarYUVLuminanceSource(
                data, width, height, 0, 0, width, height, false
            )
            
            val result = decodeLuminanceSource(source, reader)

            if (result != null && delivered.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).post {
                    onQrCodeScanned(result.text)
                }
            }
        } catch (_: Exception) {
            // Invalid or undecodable frames are expected while the camera is moving.
        } finally {
            image.close()
        }
    }
}

// --- OFFLINE GALLERY DECODE HELPER ---

fun decodeUriToQr(context: Context, uri: Uri): String? {
    return try {
        val maxDim = 1200
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > 50_000 || bounds.outHeight > 50_000
        ) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDim || bounds.outHeight / (sampleSize * 2) >= maxDim) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / Math.max(bitmap.width, bitmap.height)
            val scaled = bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader().apply {
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
            setHints(hints)
        }
        val result = decodeLuminanceSource(source, reader)
        result?.text
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

internal fun readBoundedUtf8Text(context: Context, uri: Uri, maximumBytes: Int = CipherShieldEngine.MAX_PLAINTEXT_BYTES): String {
    val bytes = ByteArray(maximumBytes + 1)
    return try {
        val size = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            var total = 0
            while (total < bytes.size) {
                val read = input.read(bytes, total, bytes.size - total)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    bytes[total++] = single.toByte()
                } else {
                    total += read
                }
            }
            require(total <= maximumBytes) { "File is too large" }
            total
        } ?: throw IllegalArgumentException("File could not be opened")

        val characters = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, size))
        try {
            characters.toString()
        } finally {
            if (characters.hasArray()) Arrays.fill(characters.array(), '\u0000')
        }
    } finally {
        Arrays.fill(bytes, 0.toByte())
    }
}

private fun evaluatePasswordStrength(password: String): KeyStrengthEvaluator.StrengthTier {
    val chars = password.toCharArray()
    return try {
        KeyStrengthEvaluator.evaluate(chars)
    } finally {
        Arrays.fill(chars, '\u0000')
    }
}

private fun sha256Utf8(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
    } finally {
        Arrays.fill(bytes, 0.toByte())
    }
}

private fun utf8Size(text: String): Int {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return try { bytes.size } finally { Arrays.fill(bytes, 0.toByte()) }
}

// --- CRYPTOGRAPHIC RANDOM STRING GENERATOR ---

fun generateHighEntropyKey(): String {
    val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val lower = "abcdefghijklmnopqrstuvwxyz"
    val digits = "0123456789"
    val special = "!@#$%^&*()-_=+[]{}|;:,.<>?"
    val groups = arrayOf(upper, lower, digits, special)
    val allChars = groups.joinToString("")
    val secureRandom = SecureRandom()
    val chars = CharArray(24)
    return try {
        do {
            groups.forEachIndexed { index, group -> chars[index] = group[secureRandom.nextInt(group.length)] }
            for (index in groups.size until chars.size) {
                chars[index] = allChars[secureRandom.nextInt(allChars.length)]
            }
            for (index in chars.lastIndex downTo 1) {
                val swapWith = secureRandom.nextInt(index + 1)
                val value = chars[index]
                chars[index] = chars[swapWith]
                chars[swapWith] = value
            }
        } while (KeyStrengthEvaluator.evaluate(chars) != KeyStrengthEvaluator.StrengthTier.EXCELLENT)
        String(chars)
    } finally {
        Arrays.fill(chars, '\u0000')
    }
}
