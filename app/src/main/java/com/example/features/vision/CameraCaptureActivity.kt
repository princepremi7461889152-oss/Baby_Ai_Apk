package com.example.features.vision

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.BabyTheme
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import java.io.File

class CameraCaptureActivity : ComponentActivity() {

    private var statusMessage by mutableStateOf("Camera khol rahi hoon...")
    private var previewView: PreviewView? = null
    private var cameraStarted = false
    private var captureInFlight = false
    private var activeImageCapture: ImageCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) maybeStartCamera()
        else finishWithResult(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BabyTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also { view ->
                                    previewView = view
                                    view.post { maybeStartCamera() }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 44.dp, start = 20.dp, end = 20.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text(statusMessage, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { finishWithResult(null) }) {
                                    Text("Cancel")
                                }
                                Button(onClick = { triggerSingleShot() }) {
                                    Text("Capture Now")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.CAMERA)
        } else {
            previewView?.post { maybeStartCamera() }
        }
    }

    private fun maybeStartCamera() {
        if (cameraStarted) return
        val pv = previewView ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        cameraStarted = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: run {
                finishWithResult(null)
                return@addListener
            }
            val preview = Preview.Builder().build().also { p ->
                p.setSurfaceProvider(pv.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            activeImageCapture = capture

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }.onFailure {
                finishWithResult(null)
                return@addListener
            }

            statusMessage = "Photo le rahi hoon..."
            // Brief focus/exposure settle before automatic one-shot capture
            mainHandler.postDelayed({ triggerSingleShot() }, 700L)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerSingleShot() {
        val capture = activeImageCapture ?: return
        if (captureInFlight || isFinishing) return
        captureInFlight = true
        mainHandler.removeCallbacksAndMessages(null)

        // Never save to Gallery / MediaStore — temporary file inside app cacheDir only
        val tempFile = File(cacheDir, "${TEMP_PREFIX}${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val b64 = try {
                        encodeScaledBase64(tempFile)
                    } finally {
                        tempFile.delete()
                    }
                    finishWithResult(b64)
                }

                override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()
                    finishWithResult(null)
                }
            }
        )
    }

    private fun encodeScaledBase64(file: File): String? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val maxDim = 768
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                .also { bmp.recycle() }
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun finishWithResult(base64: String?) {
        mainHandler.removeCallbacksAndMessages(null)
        pendingDeferred?.complete(base64)
        pendingDeferred = null
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        purgeTempCacheFiles(this)
        pendingDeferred?.complete(null)
        pendingDeferred = null
        super.onDestroy()
    }

    companion object {
        private const val TEMP_PREFIX = "baby_vision_"
        private var pendingDeferred: CompletableDeferred<String?>? = null

        suspend fun captureOnePhotoBase64(context: Context): String? {
            val deferred = CompletableDeferred<String?>()
            pendingDeferred?.cancel()
            pendingDeferred = deferred
            val intent = Intent(context, CameraCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return deferred.await()
        }

        fun purgeTempCacheFiles(context: Context) {
            runCatching {
                context.cacheDir.listFiles { _, name -> name.startsWith(TEMP_PREFIX) }
                    ?.forEach { it.delete() }
            }
        }
    }
}
