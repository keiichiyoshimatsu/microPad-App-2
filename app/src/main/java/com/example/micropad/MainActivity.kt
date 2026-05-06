/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.example.micropad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.micropad.data.CsvImportButton
import com.example.micropad.data.AppErrorLogger
import com.example.micropad.data.DatasetModel
import com.example.micropad.data.cloud.CloudSyncManager
import com.example.micropad.data.ErrorHandler
import com.example.micropad.ui.AnalysisConfigScreen
import com.example.micropad.ui.AnalysisScreen
import com.example.micropad.ui.CloudSyncScreen
import com.example.micropad.ui.GalleryReferenceFlow
import com.example.micropad.ui.LabelingScreen
import com.example.micropad.ui.WellNamingScreen
import com.example.micropad.ui.camera.CameraScreen
import com.example.micropad.ui.AnalysisScreen
import com.example.micropad.ui.GalleryReferenceFlow
import com.example.micropad.ui.HistoryScreen
import com.example.micropad.ui.LabelingScreen
import com.example.micropad.ui.SimulationOverlay
import com.example.micropad.ui.camera.CameraScreen
import com.example.micropad.ui.runNavigationSimulation
import com.example.micropad.ui.theme.MicroPadTheme
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("APP_FLOW", "onCreate")
        super.onCreate(savedInstanceState)

        ErrorHandler.safeExecute(this) {
            if (!OpenCVLoader.initDebug()) {
                android.util.Log.e("OpenCV", "Unable to load OpenCV!")
            } else {
                android.util.Log.d("OpenCV", "OpenCV loaded successfully.")
            }
        }
        CloudSyncManager.ensureScheduledWorkMatchesPreference(this)

        enableEdgeToEdge()

        Thread {
            try {
                Log.d("OpenCV", "Starting OpenCV init")

                val start = System.currentTimeMillis()
                val ok = OpenCVLoader.initLocal()

                Log.d(
                    "OpenCV",
                    "Finished init in ${System.currentTimeMillis() - start}ms, success=$ok"
                )

                if (!ok) {
                    Log.e("OpenCV", "OpenCV failed to load")
                }

            } catch (e: Exception) {
                Log.e("OpenCV", "OpenCV exception", e)
            }
        }.start()

        Log.d("APP_FLOW", "before setContent")
        setContent {
            MicroPadTheme {
                val viewModel: DatasetModel = viewModel()
                val navController = rememberNavController()
                CrashlyticsConsentDialog()
                MainContent(viewModel, navController)
            }
        }
    }
}

@Composable
fun MainContent(viewModel: DatasetModel, navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isUiBlocked = viewModel.isSimulating && currentRoute != "home"

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Instruction / Cancel Buttons
            Column(modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp)) {
                if (viewModel.isSimulating && currentRoute == "home") {
                    Button(
                        onClick = { viewModel.isSimulating = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text("Cancel")
                    }
                } else if (currentRoute != "analysis") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // Capture ROI names before simulation starts
                                viewModel.syncNames()

                                scope.launch {
                                    ErrorHandler.safeExecute(context) {
                                        runNavigationSimulation(viewModel, navController, scope)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Instructions")
                        }

                        if (currentRoute == "home") {
                            IconButton(onClick = { navController.navigate("history") }) {
                                Icon(Icons.Default.History, contentDescription = "History")
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        FrontPage(navController, viewModel)
                    }
                    composable("namingScreen") {
                        WellNamingScreen(viewModel, navController)
                    }
                    composable("options") {
                        AnalysisConfigScreen(viewModel, navController)
                    }
                    composable("analysis") {
                        AnalysisScreen(viewModel, navController)
                    }
                    composable("labelingScreen") {
                        LabelingScreen(viewModel, navController)
                    }
                    composable("history") {
                        HistoryScreen(viewModel, navController)
                    }
                    composable("cloudSync") {
                        CloudSyncScreen(viewModel, navController)
                    }

                    // Sub-flows for data acquisition
                    composable("camera_ref") {
                        CameraScreen(onImagesProcessed = { uris ->
                            viewModel.temporaryUris = uris
                            viewModel.labelingTargetIsReference = true
                            navController.navigate("labelingScreen")
                        })
                    }
                    composable("camera_sample") {
                        CameraScreen(onImagesProcessed = { uris ->
                            viewModel.temporaryUris = uris
                            viewModel.labelingTargetIsReference = false
                            navController.navigate("labelingScreen")
                        })
                    }
                    composable("gallery_ref") {
                        GalleryReferenceFlow(
                            onImagesPicked = { uris ->
                                viewModel.temporaryUris = uris
                                viewModel.labelingTargetIsReference = true
                                navController.navigate("labelingScreen")
                            },
                            onCancel = { navController.popBackStack() },
                            isSimulating = viewModel.isSimulating
                        )
                    }
                    composable("gallery_sample") {
                        GalleryReferenceFlow(
                            onImagesPicked = { uris ->
                                viewModel.temporaryUris = uris
                                viewModel.labelingTargetIsReference = false
                                navController.navigate("labelingScreen")
                            },
                            onCancel = { navController.popBackStack() },
                            isSimulating = viewModel.isSimulating
                        )
                    }
                }
            }
        }

         // SimulationOverlay(viewModel)
    }
}

@Composable
fun ReferenceOnlyDialog(navigate: () -> Unit, onDismissRequest: () -> Unit) {
    AlertDialog(
        title = { Text("No Sample Data") },
        text = { Text("You have only imported reference data. Move on only if you are only exporting a reference sheet for later use.") },
        onDismissRequest = onDismissRequest,
        confirmButton = {TextButton(onClick = navigate) { Text("Next") }},
        dismissButton = {TextButton(onClick = onDismissRequest) { Text("Return") } }
    )
}

    @Composable
    fun ErrorReportBanner() {
        val context = LocalContext.current
        // Evaluate once at composition time — not on every recompose
        var showDialog by remember { mutableStateOf(AppErrorLogger.hasErrors(context)) }
        if (!showDialog) return

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Share system errors to improve the app?") },
            text = {
                Text(
                    "Errors were recorded during this session. You can share them anonymously " +
                            "to help us fix issues. The file contains no personal data."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ErrorHandler.safeUnit(context, tag = "ErrorShare") {
                        val intent = AppErrorLogger.buildShareIntent(context)
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share error log"))
                        }
                        AppErrorLogger.clearLog(context)
                    }
                    showDialog = false
                }) { Text("Share once") }
            },
            dismissButton = {
                TextButton(onClick = {
                    ErrorHandler.safeUnit(context, tag = "ErrorShare") {
                        AppErrorLogger.clearLog(context)
                    }
                    showDialog = false
                }) { Text("Dismiss") }
                Row {
                    TextButton(onClick = {
                        CloudSyncManager.setWeeklyErrorUploadEnabled(context, true)
                        showDialog = false
                    }) { Text("Enable weekly upload") }
                    TextButton(onClick = { showDialog = false }) { Text("Later") }
                }
            }
        )
    }
    @Composable
    fun FrontPage(navController: NavHostController, viewModel: DatasetModel) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                val hasData = viewModel.pendingReferences.isNotEmpty() ||
                              viewModel.pendingSamples.isNotEmpty() ||
                              viewModel.referenceDataset != null

            val canProceed = (viewModel.referenceDataset != null || viewModel.pendingReferences.isNotEmpty()) &&
                             viewModel.pendingSamples.isNotEmpty()

            val canExportReference = viewModel.pendingReferences.isNotEmpty()

                val openAlertDialog = rememberSaveable { mutableStateOf(false) }

                if (openAlertDialog.value) {
                        ReferenceOnlyDialog(
                            navigate = {navController.navigate("namingScreen")},
                            onDismissRequest = { openAlertDialog.value = false })
                }

            Column {
                if (hasData) {
                    OutlinedButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restart Data Upload")
                    }
                }

                Button(
                    onClick = {
                        if (canProceed) {
                            navController.navigate("namingScreen")
                        }
                        else {
                            openAlertDialog.value = true
                        }},
                    enabled = canProceed || canExportReference,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp)
                ) {
                    Text("Next: Process & Name Wells")
                }
            }
        }
    ) { innerPadding ->
        HomePage(
            modifier = Modifier.padding(innerPadding),
            viewModel = viewModel,
            navController = navController
        )
    }
}

/**
 * Show user the main screen of the app.
 *
 * @param modifier The modifier to apply to the layout.
 * @param viewModel The view model for the app.
 * @receiver The Composable calling this function.
 * @return Unit
 */
@Composable
fun HomePage(modifier: Modifier, viewModel: DatasetModel, navController: NavHostController) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
            ErrorReportBanner()
            OutlinedButton(
                onClick = { navController.navigate("cloudSync") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cloud Sync & Backups")
            }
            Text(
                text = "Analyze microPAD",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

        // Card 1: References
        DataAcquisitionCard(
            title = "Upload Reference Data",
            description = "Capture or upload the photos of reference microPADs. Otherwise, import a reference file (CSV).",
            count = viewModel.pendingReferences.size + (if (viewModel.referenceDataset != null) 1 else 0),
            onGallery = { navController.navigate("gallery_ref") },
            onCamera = { navController.navigate("camera_ref") },
            showCsv = true,
            onCsv = { uri ->
                if (uri != null) {
                    viewModel.setImportedFile(uri, context)
                    viewModel.setReferenceDataset(uri, context)
                }
            },
            isGalleryHighlighted = viewModel.highlightedButtonId == "ref_gallery",
            isCameraHighlighted = viewModel.highlightedButtonId == "ref_camera",
            isCsvHighlighted = viewModel.highlightedButtonId == "ref_csv"
        )

        // Card 2: Samples
        DataAcquisitionCard(
            title = "Upload Test Samples",
            description = "Capture or upload the photos of microPADs for test samples.",
            count = viewModel.pendingSamples.size,
            onGallery = { navController.navigate("gallery_sample") },
            onCamera = { navController.navigate("camera_sample") },
            showCsv = false,
            isGalleryHighlighted = viewModel.highlightedButtonId == "sample_gallery",
            isCameraHighlighted = viewModel.highlightedButtonId == "sample_camera"
        )
    }
}

@Composable
fun DataAcquisitionCard(
    title: String,
    description: String,
    count: Int,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    showCsv: Boolean,
    onCsv: ((Uri?) -> Unit)? = null,
    isGalleryHighlighted: Boolean = false,
    isCameraHighlighted: Boolean = false,
    isCsvHighlighted: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (count > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("$count added", color = Color.White, modifier = Modifier.padding(4.dp))
                    }
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGallery,
                    modifier = Modifier.weight(1f).then(
                        if (isGalleryHighlighted) Modifier.border(
                            4.dp,
                            Color.Yellow,
                            RoundedCornerShape(8.dp)
                        ).padding(4.dp) else Modifier
                    )
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Gallery", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onCamera,
                    modifier = Modifier.weight(1f).then(
                        if (isCameraHighlighted) Modifier.border(
                            4.dp,
                            Color.Yellow,
                            RoundedCornerShape(8.dp)
                        ).padding(4.dp) else Modifier
                    )
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Camera", fontSize = 12.sp)
                }
            }
            if (showCsv) {
                val csvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri -> onCsv?.invoke(uri) }
                Button(
                    onClick = { csvLauncher.launch("text/*") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isCsvHighlighted) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary) else ButtonDefaults.buttonColors()
                ) {
                    Text("Import CSV")
                }
            }
        }
    }
}
