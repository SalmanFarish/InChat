package com.example.inchat.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DeviceIdentityTransferCoordinator
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.crypto.DevicePairingProtocol
import com.example.inchat.crypto.FirebaseDeviceTransferTransport
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private enum class TransferMode {
    CHOOSE,
    SOURCE_START,
    SOURCE_REQUEST,
    SOURCE_COMPARE,
    SOURCE_WAITING,
    SOURCE_DONE,
    DESTINATION_START,
    DESTINATION_RESPONSE,
    DESTINATION_CONFIRMATION,
    DESTINATION_IMPORTED,
    DESTINATION_DONE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceTransferScreen(
    uid: String,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val identityRepository = remember(context) { DeviceIdentityRepository(context) }
    val directoryRepository = remember { DeviceKeyDirectoryRepository() }
    val coordinator = remember(identityRepository, directoryRepository) {
        DeviceIdentityTransferCoordinator(identityRepository, directoryRepository)
    }
    val transport = remember { FirebaseDeviceTransferTransport() }
    val sourceProtocol = remember { DevicePairingProtocol() }
    val destinationProtocol = remember { DevicePairingProtocol() }

    var mode by remember { mutableStateOf(TransferMode.CHOOSE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sourceDeviceId by remember { mutableStateOf<String?>(null) }
    var targetDeviceId by remember { mutableStateOf<String?>(null) }
    var transactionId by remember { mutableStateOf<String?>(null) }
    var requestQr by remember { mutableStateOf<String?>(null) }
    var responseQr by remember { mutableStateOf<String?>(null) }
    var confirmationQr by remember { mutableStateOf<String?>(null) }
    var pairingPreview by remember { mutableStateOf<DevicePairingProtocol.PairingPreview?>(null) }
    var verifiedPairing by remember { mutableStateOf<DevicePairingProtocol.VerifiedPairing?>(null) }

    suspend fun scanQr(): String {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val barcode = GmsBarcodeScanning.getClient(context, options).startScan().await()
        return requireNotNull(barcode.rawValue) { "The scanned QR code contains no text" }
    }

    fun runTask(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The device transfer could not continue."
            } finally {
                busy = false
            }
        }
    }

    fun resetFlow() {
        transactionId?.let { tx ->
            sourceProtocol.clearEphemeralSession(tx)
            destinationProtocol.clearEphemeralSession(tx)
        }
        requestQr = null
        responseQr = null
        confirmationQr = null
        pairingPreview = null
        verifiedPairing = null
        sourceDeviceId = null
        targetDeviceId = null
        transactionId = null
        mode = TransferMode.CHOOSE
        error = null
        notice = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Transfer device identity",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Move this InChat identity to another phone",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Both phones must be signed in to the same InChat account and have an internet connection. Keep both phones unlocked and nearby until the transfer finishes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (mode) {
                TransferMode.CHOOSE -> {
                    ActionCard(
                        title = "This is the old phone",
                        description = "Show a pairing QR code and securely send this phone's encrypted identity to the new phone.",
                        buttonText = "Transfer from this phone",
                        enabled = !busy,
                        onClick = { mode = TransferMode.SOURCE_START },
                    )
                    ActionCard(
                        title = "This is the new phone",
                        description = "Scan the old phone's QR code, compare the safety code, and receive the identity.",
                        buttonText = "Receive on this phone",
                        enabled = !busy,
                        onClick = { mode = TransferMode.DESTINATION_START },
                    )
                    InfoCard(
                        "The old phone's identity is retired only after the new phone imports it, publishes the destination identity, and returns a valid signed acknowledgement. Do not uninstall or clear either phone while transferring.",
                    )
                }

                TransferMode.SOURCE_START -> {
                    Text("Start on the old phone", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The source identity must be published in your account's device directory before it can be transferred.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            runTask {
                                check(directoryRepository.authenticatedUserId == uid) {
                                    "The signed-in account changed. Sign in to the same account on both phones."
                                }
                                val bundle = withContext(Dispatchers.IO) {
                                    identityRepository.ensurePublicKeyBundle()
                                }
                                withContext(Dispatchers.IO) {
                                    directoryRepository.publishMyDevice(bundle)
                                    identityRepository.markPublicKeyBundlePublished()
                                }
                                val tx = UUID.randomUUID().toString().replace("-", "")
                                val request = withContext(Dispatchers.IO) {
                                    sourceProtocol.createRequest(bundle.deviceId, tx)
                                }
                                sourceDeviceId = bundle.deviceId
                                transactionId = tx
                                requestQr = request
                                mode = TransferMode.SOURCE_REQUEST
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Create pairing QR")
                    }
                }

                TransferMode.SOURCE_REQUEST -> {
                    StepLabel("1 of 4 · Scan this code with the new phone")
                    QrPanel(requireNotNull(requestQr), "Scan this QR code from the new phone")
                    Text(
                        "This QR contains temporary public pairing information only. It does not contain your identity private keys or the transfer secret.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            runTask {
                                val scanned = scanQr()
                                val request = requireNotNull(requestQr)
                                val source = requireNotNull(sourceDeviceId)
                                val tx = requireNotNull(transactionId)
                                val preview = withContext(Dispatchers.IO) {
                                    sourceProtocol.inspectResponse(request, scanned, source, tx)
                                }
                                responseQr = scanned
                                pairingPreview = preview
                                targetDeviceId = preview.targetDeviceId
                                mode = TransferMode.SOURCE_COMPARE
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.QrCode, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan response from new phone")
                    }
                }

                TransferMode.SOURCE_COMPARE -> {
                    StepLabel("2 of 4 · Compare the safety code")
                    SasPanel(requireNotNull(pairingPreview).sasCode)
                    Text(
                        "The new phone must display exactly the same six-digit code. If the codes differ, cancel and start again. Never approve a mismatch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            runTask {
                                val scanned = scanQr()
                                val preview = requireNotNull(pairingPreview)
                                val request = requireNotNull(requestQr)
                                val response = requireNotNull(responseQr)
                                val source = requireNotNull(sourceDeviceId)
                                val tx = requireNotNull(transactionId)
                                val verified = withContext(Dispatchers.IO) {
                                    sourceProtocol.verifyConfirmation(
                                        encodedRequest = request,
                                        encodedResponse = response,
                                        encodedConfirmation = scanned,
                                        expectedSourceDeviceId = source,
                                        expectedTransactionId = tx,
                                        confirmedSasCode = preview.sasCode,
                                    )
                                }
                                verifiedPairing = verified
                                withContext(Dispatchers.IO) {
                                    coordinator.beginTransfer(verified, preview.sasCode)
                                    coordinator.exportPairedIdentityToRelay(
                                        sourceProtocol,
                                        verified,
                                        transport,
                                    )
                                }
                                mode = TransferMode.SOURCE_WAITING
                                notice = "Encrypted identity uploaded. The old phone remains locked until the new phone acknowledges the transfer."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Codes match · scan final confirmation")
                    }
                    OutlinedButton(
                        onClick = { resetFlow() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel pairing") }
                }

                TransferMode.SOURCE_WAITING -> {
                    StepLabel("3 of 4 · Waiting for the new phone")
                    InfoCard(
                        "The encrypted transfer is uploaded. On the new phone, choose Receive identity, import it, and publish the signed acknowledgement. This phone will not retire its identity until that acknowledgement is verified.",
                    )
                    Text(
                        "Destination device: " + targetDeviceId.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            runTask {
                                val completed = withContext(Dispatchers.IO) {
                                    coordinator.acknowledgeAndRetireSourceFromRelay(
                                        targetDeviceId = requireNotNull(targetDeviceId),
                                        transactionId = requireNotNull(transactionId),
                                        transport = transport,
                                    )
                                }
                                if (completed) {
                                    sourceProtocol.clearEphemeralSession(requireNotNull(transactionId))
                                    mode = TransferMode.SOURCE_DONE
                                    notice = "The new phone acknowledged the transfer. This phone's old identity has been retired."
                                } else {
                                    notice = "No acknowledgement yet. Keep both phones connected and check again after the new phone publishes it."
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Check destination acknowledgement")
                    }
                }

                TransferMode.SOURCE_DONE -> {
                    StepLabel("4 of 4 · Transfer complete")
                    InfoCard(
                        "Your old device identity has been retired. Continue using InChat on the new phone. Re-establish device trust and verification on the new installation where needed.",
                    )
                    Button(onClick = { resetFlow() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }

                TransferMode.DESTINATION_START -> {
                    Text("Start on the new phone", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Scan the QR code shown on the old phone. The pairing request expires after two minutes, so keep both screens ready.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            runTask {
                                check(directoryRepository.authenticatedUserId == uid) {
                                    "Sign in to the same InChat account on both phones."
                                }
                                val scanned = scanQr()
                                val source = JSONObject(scanned).getString("sourceDeviceId")
                                val tx = JSONObject(scanned).getString("transactionId")
                                val target = withContext(Dispatchers.IO) {
                                    identityRepository.currentDeviceId()
                                }
                                val response = withContext(Dispatchers.IO) {
                                    destinationProtocol.respondToRequest(scanned, target)
                                }
                                sourceDeviceId = source
                                targetDeviceId = target
                                transactionId = tx
                                requestQr = scanned
                                responseQr = response.encoded
                                pairingPreview = DevicePairingProtocol.PairingPreview(
                                    targetDeviceId = target,
                                    sasCode = response.sasCode,
                                    expiresAtMillis = System.currentTimeMillis() + 120_000L,
                                )
                                mode = TransferMode.DESTINATION_RESPONSE
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Outlined.QrCode, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Scan old phone's QR")
                        }
                    }
                }

                TransferMode.DESTINATION_RESPONSE -> {
                    StepLabel("1 of 4 · Compare this code on both phones")
                    SasPanel(requireNotNull(pairingPreview).sasCode)
                    QrPanel(requireNotNull(responseQr), "Scan this response QR with the old phone")
                    Text(
                        "Check that the six digits match the old phone before confirming. The old phone must scan this response QR first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            runTask {
                                val confirmation = withContext(Dispatchers.IO) {
                                    destinationProtocol.confirmResponse(
                                        encodedResponse = requireNotNull(responseQr),
                                        targetDeviceId = requireNotNull(targetDeviceId),
                                        confirmedSasCode = requireNotNull(pairingPreview).sasCode,
                                    )
                                }
                                confirmationQr = confirmation
                                mode = TransferMode.DESTINATION_CONFIRMATION
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("The codes match · confirm this phone") }
                    OutlinedButton(
                        onClick = { resetFlow() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel pairing") }
                }

                TransferMode.DESTINATION_CONFIRMATION -> {
                    StepLabel("2 of 4 · Let the old phone scan this confirmation")
                    QrPanel(requireNotNull(confirmationQr), "Scan this confirmation QR from the old phone")
                    InfoCard(
                        "Keep this screen open. After the old phone uploads the encrypted identity, return here and receive it.",
                    )
                    Button(
                        onClick = {
                            runTask {
                                val imported = withContext(Dispatchers.IO) {
                                    coordinator.importPairedIdentityFromRelay(
                                        pairingProtocol = destinationProtocol,
                                        sourceDeviceId = requireNotNull(sourceDeviceId),
                                        transactionId = requireNotNull(transactionId),
                                        transport = transport,
                                    )
                                }
                                if (imported) {
                                    mode = TransferMode.DESTINATION_IMPORTED
                                    notice = "Encrypted identity imported successfully. Publish the acknowledgement next."
                                } else {
                                    notice = "The old phone has not uploaded the encrypted identity yet. Try again shortly."
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Receive encrypted identity")
                    }
                }

                TransferMode.DESTINATION_IMPORTED -> {
                    StepLabel("3 of 4 · Publish destination and acknowledge")
                    InfoCard(
                        "The identity is imported locally. The next action publishes this phone's public identity and sends a signed acknowledgement to the old phone. The old phone remains locked until it verifies that acknowledgement.",
                    )
                    Button(
                        onClick = {
                            runTask {
                                withContext(Dispatchers.IO) {
                                    coordinator.publishDestinationAcknowledgementToRelay(
                                        sourceDeviceId = requireNotNull(sourceDeviceId),
                                        ownerUserId = uid,
                                        transactionId = requireNotNull(transactionId),
                                        transport = transport,
                                    )
                                }
                                destinationProtocol.clearEphemeralSession(requireNotNull(transactionId))
                                mode = TransferMode.DESTINATION_DONE
                                notice = "Destination published and signed acknowledgement sent. Return to the old phone to finish retirement."
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Publish identity and send acknowledgement")
                    }
                }

                TransferMode.DESTINATION_DONE -> {
                    StepLabel("4 of 4 · Destination ready")
                    InfoCard(
                        "This phone now holds the transferred identity and has sent its signed acknowledgement. Return to the old phone and check the acknowledgement to complete retirement.",
                    )
                    Button(onClick = { resetFlow() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Done")
                    }
                }
            }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Working securely…", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    description: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepLabel(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SasPanel(code: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SAFETY CODE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                code.chunked(3).joinToString(" "),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
                textAlign = TextAlign.Center,
            )
            Text("Both phones must show the same six digits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QrPanel(content: String, description: String) {
    val bitmap = remember(content) { createQrBitmap(content) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.size(260.dp),
            )
            Text(description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

private fun createQrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        720,
        720,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ),
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}
