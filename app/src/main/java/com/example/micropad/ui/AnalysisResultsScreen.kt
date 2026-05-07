package com.example.micropad.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.micropad.data.ClassificationResult
import com.example.micropad.data.DatasetModel
import com.example.micropad.data.writeToCsv
import org.opencv.core.Scalar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.platform.testTag

/**
 * Display the results of a classification run.
 *
 * @param viewModel The view model for the app.
 * @param navController The navigation controller for the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(viewModel: DatasetModel, navController: NavController) {
    val dataset = viewModel.newDataset
    var showRestartDialog by remember { mutableStateOf(false) }
    val title = if (viewModel.comparisonMode == "Whole Card") "Whole Card Comparison" else "Per Dye Well Comparison"

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart Analysis") },
            text = { Text("Would you like to save the current analysis results to history before restarting?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveToHistory()
                    viewModel.reset()
                    showRestartDialog = false
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }) {
                    Text("Save & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.reset()
                    showRestartDialog = false
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }) {
                    Text("Discard & Restart")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRestartDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restart")
                    }
                }
            )
        }
    ) { innerPadding ->

        if (dataset == null || dataset.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No analysis results available.\nPlease scan a micropad first.",
                    color = Color.Gray
                )
            }
            return@Scaffold
        }

        val initialName = viewModel.importedFileName
        val context = LocalContext.current
        var exportChoice by remember { mutableStateOf("samples") }

        val refLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            val csvData = viewModel.toCsvString(includeHeader = true, datasetChoice = exportChoice)
            if (uri != null) writeToCsv(csvData, exportChoice, uri, context)
        }

        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .testTag("analysisList"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                dataset.samples.forEachIndexed { sampleIndex, _ ->
                    item {
                        SampleResultCard(
                            viewModel = viewModel,
                            sampleIndex = sampleIndex,
                            distanceMetric = viewModel.distanceMetric
                        )
                    }
                }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Export Results", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            exportChoice = "sample"
                            refLauncher.launch(initialName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Samples")
                    }
                    Button(
                        onClick = {
                            exportChoice = "references"
                            refLauncher.launch(initialName)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("References")
                    }
                }
                Button(
                    onClick = {
                        exportChoice = "combined"
                        refLauncher.launch(initialName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Combined Dataset")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { showRestartDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restart Analysis")
                }
            }
        }
    }
}

/**
 * Display the results of a single sample classification.
 */
@Composable
fun SampleResultCard(
    viewModel: DatasetModel,
    sampleIndex: Int,
    distanceMetric: String
) {
    val sample = viewModel.newDataset?.samples?.getOrNull(sampleIndex) ?: return
    val result = sample.classificationResults.firstOrNull()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = sample.referenceName.ifBlank { "Sample $sampleIndex" },
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (result == null) {
                Text(text = "No results available.", color = Color.Gray, fontSize = 14.sp)
                return@Column
            }

            Text("Detected Match:", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                text = result.closestReferenceName,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            val totalDistText = "%.2f".format(result.totalDistance)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Total Distance", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(text = "Metric: $distanceMetric", fontSize = 11.sp, color = Color.Gray)
                }
                Text(text = totalDistText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Dye Well Details", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    result.wellNames.forEachIndexed { i, name ->
                        WellResultRow(
                            name = name,
                            sampleColor = result.sampleColors[i],
                            referenceColor = result.referenceColors[i],
                            distance = result.wellDistances[i],
                            closestRef = result.wellClosestReferences.getOrNull(i)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WellResultRow(
    name: String,
    sampleColor: Scalar,
    referenceColor: Scalar,
    distance: Double,
    closestRef: String?
) {
    val distText = "%.2f".format(distance)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.2f)) {
            Text(text = name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = closestRef ?: "Individual Well",
                fontSize = 11.sp,
                color = if (closestRef != null) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorSwatch(color = sampleColor, label = "S")
            Spacer(modifier = Modifier.width(8.dp))
            ColorSwatch(color = referenceColor, label = "R")
        }

        Column(modifier = Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
            Text(text = "Dist", fontSize = 11.sp, color = Color.Gray)
            Text(text = distText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun ColorSwatch(color: Scalar, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    Color(
                        (color.`val`[0] / 255.0).toFloat().coerceIn(0f, 1f),
                        (color.`val`[1] / 255.0).toFloat().coerceIn(0f, 1f),
                        (color.`val`[2] / 255.0).toFloat().coerceIn(0f, 1f)
                    ),
                    shape = CircleShape
                )
        )
        Text(text = label, fontSize = 9.sp, color = Color.Gray)
    }
}
