package com.example.micropad.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.micropad.data.DatasetModel
import com.example.micropad.data.Sample
import com.example.micropad.data.SampleDataset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI Component that displays a narration overlay during the navigation simulation.
 * It utilizes a semi-transparent black background with white text at the bottom.
 *
 * @param viewModel The shared [DatasetModel] containing simulation state.
 */
@Composable
fun SimulationOverlay(viewModel: DatasetModel) {
    AnimatedVisibility(
        visible = viewModel.isSimulating,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 100.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = viewModel.narrationText,
                    color = Color.White,
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
            }
        }
    }
}

/**
 * Executes a automated navigation simulation of the app's core features.
 * Demonstrates CSV uploading, image processing, ROI labeling, and classification options.
 *
 * @param viewModel The [DatasetModel] drive the simulation logic.
 * @param navController The [NavController] to handle UI transitions.
 */
suspend fun runNavigationSimulation(
    viewModel: DatasetModel,
    navController: NavController,
    scope: CoroutineScope
) {
    // Capture state to restore later
    val prevReferences = viewModel.pendingReferences.toList()
    val prevSamples = viewModel.pendingSamples.toList()
    val prevRefDataset = viewModel.referenceDataset
    val prevNewDataset = viewModel.newDataset
    val prevMetric = viewModel.distanceMetric
    val prevStrategy = viewModel.normalizationStrategy
    val prevCompMode = viewModel.comparisonMode
    val prevSavedNames = viewModel.savedNames.toList()
    val prevIngestStrategy = viewModel.ingestSelectionStrategy
    val startRoute = navController.currentDestination?.route

    try {
        withContext(Dispatchers.Main) {
            navController.navigate("home") {
                popUpTo(navController.graph.startDestinationId)
                launchSingleTop = true
            }
        }

        suspend fun step(text: String, time: Long) {
            if (!currentCoroutineContext().isActive || !viewModel.isSimulating) {
                throw kotlinx.coroutines.CancellationException("Simulation Canceled")
            }

            withContext(Dispatchers.Main) {
                viewModel.narrationText = text
            }

            delay(time)
        }

        suspend fun pause(time: Long) {
            if (!currentCoroutineContext().isActive || !viewModel.isSimulating) {
                throw kotlinx.coroutines.CancellationException("Simulation Canceled")
            }
            delay(time)
            if (!currentCoroutineContext().isActive || !viewModel.isSimulating) {
                throw kotlinx.coroutines.CancellationException("Simulation Canceled")
            }
        }

        // 1. Reference CSV
        step("First, we upload a CSV for reference baselines.", 5000)
        viewModel.highlightedButtonId = "ref_csv"
        pause(1500)
        viewModel.highlightedButtonId = null

        val mockRef = Sample(null, null, null, mutableListOf(
            Pair(MatOfPoint(), Scalar(100.0, 50.0, 50.0)),
            Pair(MatOfPoint(), Scalar(50.0, 100.0, 50.0)),
            Pair(MatOfPoint(), Scalar(50.0, 50.0, 100.0)),
            Pair(MatOfPoint(), Scalar(100.0, 100.0, 50.0))
        ), type = "Reference")
        mockRef.referenceName = "Standard A"
        mockRef.names.clear()
        mockRef.names.addAll(listOf("Iron", "Nickel", "Copper", "Zinc"))
        viewModel.referenceDataset = SampleDataset(mutableListOf(mockRef))

        // 2. Sample Images
        step("Next, we can import test samples via CSV or capture them with the Camera.", 5000)
        viewModel.narrationText = "We can pick images from the gallery for our test samples."
        withContext(Dispatchers.Main) {
            viewModel.highlightedButtonId = "sample_gallery"
        }
        delay(1500)
        viewModel.highlightedButtonId = null

        // Mock Gallery Screen
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            navController.navigate("gallery_sample")
        }
        delay(300)

        // Mock Labeling Screen
        viewModel.narrationText = "Give your sample a descriptive label."
        navController.navigate("labelingScreen")
        delay(300)

        val mockSample = Sample(null, null, null, mutableListOf(
            Pair(MatOfPoint(), Scalar(110.0, 55.0, 45.0)),
            Pair(MatOfPoint(), Scalar(45.0, 105.0, 55.0)),
            Pair(MatOfPoint(), Scalar(55.0, 45.0, 105.0)),
            Pair(MatOfPoint(), Scalar(105.0, 105.0, 45.0))
        ))
        mockSample.names.clear()
        mockSample.names.addAll(listOf("Iron", "Nickel", "Copper", "Zinc"))
        viewModel.newDataset = SampleDataset(mutableListOf(mockSample))

        // 3. Navigate to Naming
        viewModel.narrationText = "Now we process and name our Regions of Interest."
        navController.navigate("namingScreen")
        delay(300)

        // 4. Default ROI labels and uncheck dyes
        step("We set labels for the wells. You can use fewer than all dyes by unchecking them.", 5000)
        mockSample.isSelected[2] = false
        mockSample.isSelected[3] = false

        // 5. Navigate to Options
        viewModel.narrationText = "Choose your analysis configuration."
        navController.navigate("options")
        delay(300)

        // 6. Euclidean and Softmax
        step("Demonstrating Euclidean distance with Softmax normalization.", 5000)
        viewModel.distanceMetric = "Euclidean"
        viewModel.normalizationStrategy = "Softmax"

        // 7. Per Color Classification
        step("Running classification 'by dye values' individually...", 5000)
        viewModel.comparisonMode = "Per Color"
        withContext(Dispatchers.IO) {
            viewModel.runClassification()
        }

        withContext(Dispatchers.Main) {
            delay(300) // small buffer for UI sync
            navController.navigate("analysis")
        }

        // 8. Whole Card Classification
        step("...and 'by whole card' for a comprehensive match.", 5000)
        viewModel.comparisonMode = "Whole Card"
        withContext(Dispatchers.Default) {
            viewModel.runClassification()
        }

        // 9. Reset and Finish
        step("Simulation complete. Returning you to where you were.", 5000)

    } finally {
        withContext(kotlinx.coroutines.Dispatchers.Main) {

            viewModel.pendingReferences.clear()
            viewModel.pendingReferences.addAll(prevReferences)

            viewModel.pendingSamples.clear()
            viewModel.pendingSamples.addAll(prevSamples)

            viewModel.referenceDataset = prevRefDataset
            viewModel.newDataset = prevNewDataset
            viewModel.distanceMetric = prevMetric
            viewModel.normalizationStrategy = prevStrategy
            viewModel.comparisonMode = prevCompMode
            viewModel.ingestSelectionStrategy = prevIngestStrategy

            viewModel.savedNames.clear()
            viewModel.savedNames.addAll(prevSavedNames)

            viewModel.isSimulating = false
            viewModel.narrationText = ""
            viewModel.highlightedButtonId = null
        }
    }
}
