package com.example.micropad.ui.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.example.micropad.data.ErrorHandler
import java.io.File

/**
 * Enum representing the flow of the camera screen.
 */
enum class CameraFlowScreen {
    CAMERA, PROMPT
}

/**
 * A screen that handles camera permission and displays the camera preview.
 *
 * @param onImagesProcessed A callback invoked when the user finishes capturing and labeling all
 * images.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
fun CameraScreen(onImagesProcessed: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            hasCameraPermission = isGranted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraContent(onImagesProcessed = onImagesProcessed)
        } else {
            PermissionRationaleScreen(
                onRequestPermission = {
                    if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            Manifest.permission.CAMERA
                        )
                    ) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", context.packageName, null)
                        context.startActivity(intent)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        }
    }
}

/**
 * The main content of the camera screen, shown when permission is granted.
 *
 * @param onImagesProcessed A callback invoked when the user finishes capturing and labeling all
 * images.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
private fun CameraContent(onImagesProcessed: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val capturedUris = remember { mutableStateListOf<Uri>() }
    var currentScreen by remember { mutableStateOf(CameraFlowScreen.CAMERA) }
    var latestCapturedUri by remember { mutableStateOf<Uri?>(null) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }

    LaunchedEffect(lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
    }

    when (currentScreen) {
        CameraFlowScreen.CAMERA -> {
            if (latestCapturedUri == null) {
                CameraPreview(
                    controller = cameraController,
                    onCapture = { uri ->
                        latestCapturedUri = uri
                    }
                )
            } else {
                ImagePreviewScreen(
                    imageUri = latestCapturedUri ?: return,
                    onRetake = { latestCapturedUri = null },
                    onUsePhoto = {
                        capturedUris.add(latestCapturedUri!!)
                        currentScreen = CameraFlowScreen.PROMPT
                    }
                )
            }
        }
        CameraFlowScreen.PROMPT -> {
            NextStepPrompt(
                capturedCount = capturedUris.size,
                onCaptureMore = {
                    latestCapturedUri = null
                    currentScreen = CameraFlowScreen.CAMERA
                },
                onProcess = {
                    onImagesProcessed(capturedUris.toList())
                }
            )
        }
    }
}

/**
 * Ask user for camera permission.
 *
 * @param onRequestPermission A callback invoked when the user clicks the "Grant Permission" button.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
private fun PermissionRationaleScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Camera permission is required to use this feature. Please grant the permission to continue.",
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

/**
 * Display the camera preview and capture button.
 *
 * @param controller The camera controller.
 * @param onCapture A callback invoked when the capture button is clicked.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
fun CameraPreview(
    controller: LifecycleCameraController,
    onCapture: (Uri) -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PreviewView(it).apply {
                    this.controller = controller
                    this.scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        BoundingBoxOverlay()

        Button(
            onClick = {
                val photoFile = File(
                    context.cacheDir,
                    "photo_${System.currentTimeMillis()}.jpg"
                )

                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    @Suppress("DEPRECATION")
                    (context as? Activity)?.windowManager?.defaultDisplay
                }

                val outputOptions =
                    ImageCapture.OutputFileOptions.Builder(photoFile).build()

                controller.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onCapture(Uri.fromFile(photoFile))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            ErrorHandler.safeExecute(context) {
                                throw exception
                            }
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(80.dp),
            shape = CircleShape,
            border = BorderStroke(4.dp, Color.White),
            contentPadding = PaddingValues(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * Display a preview of the captured image.
 *
 * @param imageUri The URI of the captured image.
 * @param onRetake A callback invoked when the user clicks the "Retake" button.
 * @param onUsePhoto A callback invoked when the user clicks the "Use" button.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
fun ImagePreviewScreen(
    imageUri: Uri,
    onRetake: () -> Unit,
    onUsePhoto: () -> Unit
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = imageUri,
            contentDescription = "Captured Image Preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onUsePhoto) {
                Text("Use")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRetake) {
                Text("Retake")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { saveImageToGallery(context, imageUri) }) {
                Text("Save to Gallery")
            }
        }
    }
}

@Composable
fun NextStepPrompt(
    capturedCount: Int,
    onCaptureMore: () -> Unit,
    onProcess: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Images Captured!",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Total images in batch: $capturedCount",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        Button(
            onClick = onCaptureMore,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Text("Add Another Image")
        }

        OutlinedButton(
            onClick = onProcess,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Finish")
        }
    }
}

/**
 * Save the image to the device's gallery.
 *
 * @param context The context of the app.
 * @param uri The URI of the image to save.
 * @receiver The Composable calling this function.
 * @return Unit
 */
private fun saveImageToGallery(context: Context, uri: Uri) {
    ErrorHandler.safeExecute(context) {

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "microPad_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/microPad")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to create gallery entry")

        val outputStream = resolver.openOutputStream(imageUri)
            ?: throw Exception("Output stream is null")

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Input stream is null")

        outputStream.use { out ->
            inputStream.use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(imageUri, contentValues, null, null)
        }

        Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
    }
}