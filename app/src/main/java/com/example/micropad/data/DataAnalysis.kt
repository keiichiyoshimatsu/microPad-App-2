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


package com.example.micropad.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import java.util.Collections
import kotlin.math.abs
import kotlin.math.sqrt
import com.example.micropad.data.extractManualDotAtPoint
import com.example.micropad.data.ingestImages
import kotlin.collections.get
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.example.micropad.ui.runNavigationSimulation
import kotlinx.coroutines.Dispatchers

/**
 * Data class to hold image and its semantic label.
 */
data class LabeledImage(
    val uri: Uri,
    val label: String
)

/**
 * A class for handling the results of a whole-card classification.
 *
 * @property closestReferenceName The name of the reference that best matched the sample.
 * @property totalDistance The overall similarity distance (Euclidean or Manhattan) for the entire card.
 * @property wellNames The names of the dye wells included in the classification.
 * @property sampleColors The extracted RGB colors from the sample's dye wells.
 * @property referenceColors The RGB colors from the corresponding reference's dye wells.
 * @property wellDistances The individual distances computed per dye well.
 * ADDITION:
 * @property wellClosestReferences The name of the reference that best matched each individual well (optional).
 */
data class ClassificationResult(
    val closestReferenceName: String,
    val totalDistance: Double,
    val wellNames: List<String>,
    val sampleColors: List<Scalar>,
    val referenceColors: List<Scalar>,
    val wellDistances: List<Double>,
    //ADDITION
    val wellClosestReferences: List<String> = emptyList()
)

/**
 * Represents an entry in the analysis history.
 */
data class AnalysisHistoryEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val summary: String,
    val csvData: String,
    val distanceMetric: String,
    val colorMode: String,
    val normalizationStrategy: String
)

fun greyscale(r: Double, g: Double, b: Double): Double {
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/**
 * A sample to hold data on images.
 *
 * @property imageData The original image data obtained for the sample.
 * @property balanced The rebalanced image used for dye well extraction.
 * @property initialOrdering An optional bitmap showing the initial dot ordering.
 * @property dots The identified dots, as a list of contour and color pairs.
 */
class Sample(
    val imageData: Mat?,
    val balanced: Mat?,
    val initialOrdering: Bitmap?,
    val dots: MutableList<Pair<MatOfPoint, Scalar>>,
    val squares: MutableList<Scalar> = mutableListOf<Scalar>(),
    var type: String = "Sample", // "Reference" or "Sample"
    val isImage: Boolean = true
) {
    var rgb: MutableList<Scalar> = dots.map { it.second }.toMutableList()
    var greyscale: MutableList<Double> =
        rgb.map { greyscale(it.`val`[0], it.`val`[1], it.`val`[2]) }.toMutableList()
    var names = mutableStateListOf<String>().apply {
        repeat(rgb.size) { add("") }
    }
    var isSelected = mutableStateListOf<Boolean>().apply {
        repeat(rgb.size) { add(true) }
    }
    var referenceName: String = ""
    var classificationResults = mutableStateListOf<ClassificationResult>()

    var ordering by mutableStateOf(initialOrdering)
        private set

    /**
     * Appends a manually selected ROI to this sample.
     */
    fun addManualDot(center: Point, radius: Double, selectionStrategy: String) {
        val source = balanced ?: imageData ?: return
        val (contour, color) = extractManualDotAtPoint(source, center, radius, selectionStrategy)
        dots.add(Pair(contour, color))
        rgb.add(color)
        greyscale.add(greyscale(color.`val`[0], color.`val`[1], color.`val`[2]))
        names.add("")
        isSelected.add(true)
        if (imageData != null) ordering = drawOrdering(imageData, dots, selectionStates = isSelected)
    }

    /**
     * Normalizes the dot data based on the chosen strategy and mode.
     *
     * @param strategy The user-selected normalization: MinMax, Z-Score, or none.
     * @param mode Normalize in RGB or grayscale.
     * @param selection if colored squares should be included in the calculation.
     * @return List of DoubleArray, where each DoubleArray is the feature vector for a dot.
     */
    fun getNormalizedData(strategy: String, mode: String, selection: String): List<DoubleArray> {
        val greySquares = squares.map{ greyscale(it.`val`[0], it.`val`[1], it.`val`[2]) }

        val rawData = if (mode == "RGB") {
            // Append calibration square data to rgb data before normalizing
            val data = if (selection == "Include Squares") rgb + squares else rgb
            data.map { doubleArrayOf(it.`val`[0], it.`val`[1], it.`val`[2]) }
        } else {
            // Create greyscale values from calibration squares and append
            val data = if (selection == "Include Squares") greyscale + greySquares else greyscale
            data.map { doubleArrayOf(it) }
        }

        if (strategy == "None" || rawData.isEmpty()) return rawData

        val numDots = rawData.size
        val numFeatures = rawData[0].size
        var normalized = List(numDots) { DoubleArray(numFeatures) }

        for (f in 0 until numFeatures) {
            val vals = rawData.map { it[f] }
            val transformed = when (strategy) {
                "MinMax" -> {
                    val minVal = vals.minOrNull() ?: 0.0
                    val maxVal = vals.maxOrNull() ?: 255.0
                    if (maxVal == minVal) vals.map { 0.0 } else vals.map { (it - minVal) / (maxVal - minVal) }
                }

                "Z-Score" -> {
                    val mean = vals.average()
                    val std = sqrt(vals.map { (it - mean) * (it - mean) }.average())
                    if (std == 0.0) vals.map { 0.0 } else vals.map { (it - mean) / std }
                }

                else -> vals
            }
            for (i in transformed.indices) {
                normalized[i][f] = transformed[i]
            }
        }

        // Remove calibration square data from final color data
        if (selection == "Include Squares") {
            normalized = normalized.subList(0, normalized.size - squares.size)
        }

        return normalized
    }

    /**
     * Ensure selected dye well labels are unique and filled.
     *
     * @return True if labels are valid, false otherwise.
     */
    fun validateLabels(): Boolean {
        val activeNames = names.filterIndexed { index, _ -> isSelected[index] }
        if (activeNames.any { it.isBlank() }) return false
        return activeNames.toSet().size == activeNames.size
    }

    /**
     * Reassigns a label to a specific well index.
     *
     * @param index The index of the well.
     * @param newLabel The new label to assign.
     * @return True if reassignment was successful, false if label already exists elsewhere or index is invalid.
     */
    fun reassignLabel(index: Int, newLabel: String): Boolean {
        if (index !in names.indices) return false
        if (names.contains(newLabel) && names[index] != newLabel) return false
        names[index] = newLabel
        return true
    }

    /**
     * Toggles whether a well is selected for analysis.
     *
     * @param index The index of the well.
     * @param selected Whether the well should be selected.
     */
    fun toggleSelection(index: Int, selected: Boolean) {
        if (index in isSelected.indices) isSelected[index] = selected
    }

    /**
     * Swaps the position of two dots in the sample.
     *
     * @param from The original index of the dot.
     * @param to The new index to swap with.
     */
    fun reorder(from: Int, to: Int) {
        if (from !in dots.indices || to !in dots.indices) return
        try {
            Collections.swap(dots, from, to)
            Collections.swap(names, from, to)
            Collections.swap(rgb, from, to)
            Collections.swap(greyscale, from, to)
            Collections.swap(isSelected, from, to)
            if (imageData != null) ordering = drawOrdering(imageData, dots)
        } catch (e: Exception) {
            // swap failed — state unchanged, logged silently
        }
    }
}

/**
 * A collection of samples, providing methods for bulk operations and dataset-wide analysis.
 *
 * @property samples The list of samples in this dataset.
 */
class SampleDataset(val samples: MutableList<Sample>) {
    /**
     * Tracks which samples are currently selected.
     */
    var selected = mutableListOf<Boolean>().apply {repeat(samples.size) { add(true) } }

    fun isEmpty() = samples.isEmpty()

    /**
     * Updates the name of a well across all samples in the dataset.
     *
     * @param index The index of the well.
     * @param name The new name for the well.
     * @return True if the rename was successful for all samples.
     */
    fun nameWell(index: Int, name: String): Boolean {
        var worked = true
        for (sample in samples) {
            worked = worked && sample.reassignLabel(index, name)
        }
        return worked
    }

    /**
     * Toggles the selection status of a specific well index across all samples.
     *
     * @param index The index of the well.
     * @param selected The new selection status.
     */
    fun toggleWell(index: Int, selected: Boolean) {
        for (sample in samples) {
            sample.toggleSelection(index, selected)
        }
    }

    /**
     * Triggers a reorder operation on a specific sample.
     *
     * @param sampleID The index of the sample in the dataset.
     * @param from The starting index of the dot.
     * @param to The target index for the swap.
     */
    fun reorderSample(sampleID: Int, from: Int, to: Int) {
        if (sampleID in samples.indices) {
            samples[sampleID].reorder(from, to)
        }
    }

    /**
     * Populates the dataset by parsing a CSV file.
     *
     * @param uri The URI of the CSV file.
     * @param context The Android context for content resolution.
     */
    fun fromCSV(uri: Uri, context: Context) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: run {
                AppErrorLogger.logError(context, "CSV", "fromCSV: could not open input stream for $uri")
                return
            }
            samples.clear()
            val lines = input.bufferedReader().readLines()
            if (lines.isEmpty()) return

            val firstLine = lines[0].trimStart('\uFEFF')
            val header = firstLine.split(",")

            val dyeNames = header.filter { it.endsWith("_r") && !it.startsWith("cal") }
                                .map { it.removeSuffix("_r") }

            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val tokens = line.split(",")
                    val rowMap = header.indices.associate { i -> header[i] to tokens.getOrNull(i) }

                    val refName = rowMap["reference_name"]?.trim() ?: ""

                    val squares = mutableListOf<Scalar>()
                    for (i in 0..3) {
                        val r = rowMap["cal${i}_r"]?.toDoubleOrNull() ?: 0.0
                        val g = rowMap["cal${i}_g"]?.toDoubleOrNull() ?: 0.0
                        val b = rowMap["cal${i}_b"]?.toDoubleOrNull() ?: 0.0
                        squares.add(Scalar(r, g, b))
                    }

                    val dots = mutableListOf<Pair<MatOfPoint, Scalar>>()
                    val names = mutableListOf<String>()

                    for (name in dyeNames) {
                        val rStr = rowMap["${name}_r"]
                        val gStr = rowMap["${name}_g"]
                        val bStr = rowMap["${name}_b"]

                        if (!rStr.isNullOrBlank() && !gStr.isNullOrBlank() && !bStr.isNullOrBlank()) {
                            dots.add(Pair(MatOfPoint(), Scalar(rStr.toDoubleOrNull() ?: 0.0,
                                                               gStr.toDoubleOrNull() ?: 0.0,
                                                               bStr.toDoubleOrNull() ?: 0.0)))
                            names.add(name)
                        }
                    }

                    val sample = Sample(null, null, null, dots, type = "Reference", squares = squares, isImage = false)
                    sample.names.clear(); sample.names.addAll(names)
                    sample.referenceName = refName
                    samples.add(sample)
                    Log.d("CSV", "Sample added: ${sample.rgb.size} dots identified")

                } catch (e: Exception) {
                    AppErrorLogger.logError(context, "CSV", "fromCSV: failed to parse row: $line", e)
                }

                Log.d("CSV", "Samples imported: ${samples.size}")
            }
        } catch (e: Exception) {
            AppErrorLogger.logError(context, "CSV", "fromCSV: unexpected failure", e)
            samples.clear()
        }
    }

    /**
     * Performs classification on a target dataset using a reference dataset.
     * This method is deprecated in favor of whole-card classification.
     */
    fun classify(
        referenceData: SampleDataset,
        newData: SampleDataset,
        distance: String = "Euclidean",
        mode: String = "RGB",
        normalizationStrategy: String = "None"
    ) {
        // No longer actively used in the main flow, which prefers runWholeCardClassification.
    }
}

/**
 * ViewModel for managing the state of datasets across different screens in the app.
 */
class DatasetModel : ViewModel() {

    var simulationRunning by mutableStateOf(false)
    var labelingTargetIsReference by mutableStateOf(true)

    // Persistent Session State
    val pendingReferences = mutableStateListOf<LabeledImage>()
    val pendingSamples = mutableStateListOf<LabeledImage>()
    val referenceImageUris = mutableStateListOf<Uri>()
    val sampleImageUris = mutableStateListOf<Uri>()

    // Temporary storage for labeling flow
    var temporaryUris by mutableStateOf<List<Uri>>(emptyList())

    /**
     * The dataset used as a reference for classification.
     */
    var referenceDataset by mutableStateOf<SampleDataset?>(null)
    var newDataset by mutableStateOf<SampleDataset?>(null)

    var isLoading by mutableStateOf(false)
        private set

    var importedFileName by mutableStateOf("data.csv")
    var importedFileUri by mutableStateOf<Uri?>(null)

    var distanceMetric by mutableStateOf("Euclidean")
    var colorMode by mutableStateOf("RGB")
    var normalizationStrategy by mutableStateOf("None")
    var normalizationSelection by mutableStateOf("Include Squares")
    var comparisonMode by mutableStateOf("Whole Card")
    var selectionStrategy by mutableStateOf("Mean")

    // ROI names persistence across screen transitions/simulation
    val savedNames = mutableStateListOf<List<String>>()

    // Track last ingestion state to avoid redundant work
    private var lastIngestedRefUris = emptyList<Uri>()
    private var lastIngestedSampleUris = emptyList<Uri>()
    private var lastIngestedSelectionStrategy = ""

    var ingestSelectionStrategy by mutableStateOf("Mean")

    // Simulation State
    var isSimulating by mutableStateOf(false)
    var narrationText by mutableStateOf("")
    var highlightedButtonId by mutableStateOf<String?>(null)

    // History State
    val analysisHistory = mutableStateListOf<AnalysisHistoryEntry>()


    /**
     * Ingests all pending images into structured datasets.
     */
    fun ingestAllPending(context: Context, onComplete: () -> Unit) {
        if (isSimulating || isLoading) {
            onComplete()
            return
        }

        val currentRefUris = pendingReferences.map { it.uri }
        val currentSampleUris = pendingSamples.map { it.uri }

        // Skip if nothing has changed
        if (currentRefUris == lastIngestedRefUris &&
            currentSampleUris == lastIngestedSampleUris &&
            ingestSelectionStrategy == lastIngestedSelectionStrategy &&
            (referenceDataset != null || pendingReferences.isEmpty()) &&
            (newDataset != null || pendingSamples.isEmpty())
        ) {
            onComplete()
            return
        }

        viewModelScope.launch {
            isLoading = true

            // Sync current names into savedNames before they are potentially lost during re-ingestion
            syncNames()

            if (pendingReferences.isNotEmpty()) {
                try {
                    val dataset = ingestImages(
                        currentRefUris,
                        context,
                        log = false,
                        selectionStrategy = ingestSelectionStrategy
                    )
                    dataset.samples.forEachIndexed { i, sample ->
                        sample.type = "Reference"
                        sample.referenceName = pendingReferences.getOrNull(i)?.label ?: ""
                    }
                    referenceDataset = dataset
                    lastIngestedRefUris = currentRefUris
                } catch (e: Exception) {
                    AppErrorLogger.logError(context, "Ingest", "Failed ingesting references", e)
                }
            }

            if (pendingSamples.isNotEmpty()) {
                try {
                    val dataset = ingestImages(
                        currentSampleUris,
                        context,
                        log = false,
                        selectionStrategy = ingestSelectionStrategy
                    )
                    dataset.samples.forEachIndexed { i, sample ->
                        sample.type = "Sample"
                        sample.referenceName = pendingSamples.getOrNull(i)?.label ?: ""
                    }
                    newDataset = dataset
                    lastIngestedSampleUris = currentSampleUris
                } catch (e: Exception) {
                    AppErrorLogger.logError(context, "Ingest", "Failed ingesting samples", e)
                }
            }

            lastIngestedSelectionStrategy = ingestSelectionStrategy

            // Restore names from savedNames
            var nameIdx = 0
            referenceDataset?.samples?.filter { it.isImage }?.forEach { sample ->
                savedNames.getOrNull(nameIdx++)?.let { names ->
                    sample.names.clear()
                    sample.names.addAll(names)
                }
            }
            newDataset?.samples?.filter { it.isImage }?.forEach { sample ->
                savedNames.getOrNull(nameIdx++)?.let { names ->
                    sample.names.clear()
                    sample.names.addAll(names)
                }
            }

            isLoading = false
            onComplete()
        }
    }

    /**
     * Checks if all samples in the current dataset have valid labels.
     *
     * @return True if all active labels are valid.
     */
    fun allSamplesValid(): Boolean {
        val refValid = referenceDataset?.samples?.all { it.validateLabels() } ?: true
        val sampleValid = newDataset?.samples?.all { it.validateLabels() } ?: true
        return refValid && sampleValid
    }

    fun setImportedFile(uri: Uri, context: Context) {
        importedFileUri = uri
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) importedFileName = it.getString(nameIndex)
            }
        }
    }

    /**
     * Sets the reference dataset by loading it from a CSV file.
     *
     * @param uri The URI of the CSV file.
     * @param context Context for file access.
     */
    fun setReferenceDataset(uri: Uri, context: Context) {
        val dataset = SampleDataset(mutableListOf())
        dataset.fromCSV(uri, context)
        referenceDataset = dataset
    }

    private fun flattenSample(sample: Sample, normalizationStrategy: String): DoubleArray {
        val data = sample.getNormalizedData(normalizationStrategy, colorMode, selectionStrategy)
        return sample.isSelected.indices
            .filter { sample.isSelected[it] }
            .sortedBy { sample.names[it] }
            .flatMap { data[it].toList() }
            .toDoubleArray()
    }

    fun runWholeCardClassification() {
        val ref = referenceDataset ?: return
        val new = newDataset ?: return

        for (sample in new.samples) {
            try {
                sample.classificationResults.clear()
                val sampleVec = flattenSample(sample, normalizationStrategy)

                var bestDistance = Double.MAX_VALUE
                var bestRef: Sample? = null

                for (refSample in ref.samples) {
                    val refVec = flattenSample(refSample, normalizationStrategy)
                    if (refVec.size != sampleVec.size) continue

                    val distance = when (distanceMetric) {
                        "Euclidean" -> {
                            var sum = 0.0
                            for (i in sampleVec.indices) {
                                val diff = sampleVec[i] - refVec[i]
                                sum += diff * diff
                            }
                            sqrt(sum)
                        }
                        "Manhattan" -> {
                            var sum = 0.0
                            for (i in sampleVec.indices) { sum += abs(sampleVec[i] - refVec[i]) }
                            sum
                        }
                        else -> Double.MAX_VALUE
                    }
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestRef = refSample
                    }
                }

                if (bestRef != null) {
                    val wellNames = mutableListOf<String>()
                    val sampleColors = mutableListOf<Scalar>()
                    val referenceColors = mutableListOf<Scalar>()
                    val wellDistances = mutableListOf<Double>()

                    val normalizedSample = sample.getNormalizedData(normalizationStrategy, colorMode, selectionStrategy)
                    val normalizedRef = bestRef.getNormalizedData(normalizationStrategy, colorMode, selectionStrategy)

                    sample.isSelected.forEachIndexed { dotIdx, isSelected ->
                        if (isSelected) {
                            val dotFeatures = normalizedSample[dotIdx]
                            // Match by name if possible, or index fallback
                            val refDotIdx = bestRef.names.indexOfFirst { it == sample.names[dotIdx] }
                            if (refDotIdx != -1 && refDotIdx < normalizedRef.size) {
                                val refFeatures = normalizedRef[refDotIdx]
                                val wellDistance = when (distanceMetric) {
                                    "Euclidean" -> {
                                        var sum = 0.0
                                        for (i in dotFeatures.indices) {
                                            val diff = dotFeatures[i] - refFeatures[i]
                                            sum += diff * diff
                                        }
                                        sqrt(sum)
                                    }
                                    "Manhattan" -> {
                                        var sum = 0.0
                                        for (i in dotFeatures.indices) {
                                            sum += abs(dotFeatures[i] - refFeatures[i])
                                        }
                                        sum
                                    }
                                    else -> -1.0
                                }
                                wellNames.add(sample.names[dotIdx])
                                sampleColors.add(sample.rgb[dotIdx])
                                referenceColors.add(bestRef.rgb[refDotIdx])
                                wellDistances.add(wellDistance)
                            }
                        }
                    }

                    sample.classificationResults.add(
                        ClassificationResult(
                            closestReferenceName = bestRef.referenceName,
                            totalDistance = bestDistance,
                            wellNames = wellNames,
                            sampleColors = sampleColors,
                            referenceColors = referenceColors,
                            wellDistances = wellDistances
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("Classification", "Whole card classification failed for sample", e)
            }
        }
    }

    fun runPerColorClassification() {
        val ref = referenceDataset ?: return
        val new = newDataset ?: return

        for (sample in new.samples) {
            try {
                sample.classificationResults.clear()

                val wellNames = mutableListOf<String>()
                val sampleColors = mutableListOf<Scalar>()
                val referenceColors = mutableListOf<Scalar>()
                val wellDistances = mutableListOf<Double>()
                val wellClosestRefs = mutableListOf<String>()

                val normalizedSample = sample.getNormalizedData(normalizationStrategy, colorMode, selectionStrategy)

                sample.isSelected.forEachIndexed { dotIdx, isSelected ->
                    if (isSelected) {
                        val dotFeatures = normalizedSample[dotIdx]
                        var bestWellDistance = Double.MAX_VALUE
                        var bestWellRefName = "Unknown"
                        var bestWellRefColor = Scalar(0.0, 0.0, 0.0)

                        for (refSample in ref.samples) {
                            val normalizedRef = refSample.getNormalizedData(normalizationStrategy, colorMode, selectionStrategy)
                            val refDotIdx = refSample.names.indexOfFirst { it == sample.names[dotIdx] }
                            if (refDotIdx != -1 && refDotIdx < normalizedRef.size) {
                                val refFeatures = normalizedRef[refDotIdx]
                                val distance = when (distanceMetric) {
                                    "Euclidean" -> {
                                        var sum = 0.0
                                        for (i in dotFeatures.indices) {
                                            val diff = dotFeatures[i] - refFeatures[i]
                                            sum += diff * diff
                                        }
                                        sqrt(sum)
                                    }
                                    "Manhattan" -> {
                                        var sum = 0.0
                                        for (i in dotFeatures.indices) { sum += abs(dotFeatures[i] - refFeatures[i]) }
                                        sum
                                    }
                                    else -> Double.MAX_VALUE
                                }
                                if (distance < bestWellDistance) {
                                    bestWellDistance = distance
                                    bestWellRefName = refSample.referenceName
                                    bestWellRefColor = refSample.rgb[refDotIdx]
                                }
                            }
                        }

                        wellNames.add(sample.names[dotIdx])
                        sampleColors.add(sample.rgb[dotIdx])
                        referenceColors.add(bestWellRefColor)
                        wellDistances.add(bestWellDistance)
                        wellClosestRefs.add(bestWellRefName)
                    }
                }

                sample.classificationResults.add(
                    ClassificationResult(
                        closestReferenceName = "Mixed / Per Well",
                        totalDistance = wellDistances.sum(),
                        wellNames = wellNames,
                        sampleColors = sampleColors,
                        referenceColors = referenceColors,
                        wellDistances = wellDistances,
                        wellClosestReferences = wellClosestRefs
                    )
                )
            } catch (e: Exception) {
                Log.e("Classification", "Per color classification failed", e)
            }
        }
    }

    fun runClassification() {
        val ref = referenceDataset
        val new = newDataset
        if (ref == null || new == null) return
        if (comparisonMode == "Whole Card") {
            runWholeCardClassification()
        } else {
            runPerColorClassification()
        }
    }

    fun syncNames() {
        savedNames.clear()
        val combined = mutableListOf<Sample>()
        referenceDataset?.samples?.filter { it.isImage }?.let { combined.addAll(it) }
        newDataset?.samples?.filter { it.isImage }?.let { combined.addAll(it) }
        combined.forEach { sample ->
            savedNames.add(sample.names.toList())
        }
    }

    fun reset() {
        pendingReferences.clear()
        pendingSamples.clear()
        referenceImageUris.clear()
        sampleImageUris.clear()
        temporaryUris = emptyList()
        referenceDataset = null
        newDataset = null
        importedFileName = "data.csv"
        importedFileUri = null
        comparisonMode = "Whole Card"
        savedNames.clear()
        lastIngestedRefUris = emptyList()
        lastIngestedSampleUris = emptyList()
        lastIngestedSelectionStrategy = ""
    }

    fun saveToHistory() {
        val dataset = newDataset ?: return
        val summary = dataset.samples.joinToString("; ") { sample ->
            val result = sample.classificationResults.firstOrNull()
            "${sample.referenceName} -> ${result?.closestReferenceName ?: "N/A"}"
        }
        val csvData = toCsvString(includeHeader = true, datasetChoice = "combined")
        analysisHistory.add(0, AnalysisHistoryEntry(
            fileName = importedFileName,
            summary = summary,
            csvData = csvData,
            distanceMetric = distanceMetric,
            colorMode = colorMode,
            normalizationStrategy = normalizationStrategy
        ))
    }

    fun deleteHistoryEntry(entry: AnalysisHistoryEntry) {
        analysisHistory.remove(entry)
    }

    fun startSimulation(navController: NavController) {
        if (isSimulating) return  // 🔥 prevents double start

        isSimulating = true
        syncNames()

        viewModelScope.launch(Dispatchers.Default) {
            runNavigationSimulation(
                viewModel = this@DatasetModel,
                navController = navController,
                scope = viewModelScope
            )
            isSimulating = false
        }
    }

    fun toCsvString(includeHeader: Boolean = true, datasetChoice: String = "sample"): String {
        val table = if (datasetChoice == "sample") newDataset else referenceDataset
        if (table == null || table.samples.isEmpty()) return ""

        // Calculate the union of all well names across all samples in the dataset, regardless of selection
        val allWellNames = table.samples.flatMap { sample ->
            sample.names.filter { it.isNotBlank() }
        }.distinct().sorted()

        val dyeHeaders = allWellNames.flatMap { name ->
            listOf("${name}_r", "${name}_g", "${name}_b")
        }
        val calHeaders = listOf(
            "cal0_r","cal0_g","cal0_b",
            "cal1_r","cal1_g","cal1_b",
            "cal2_r","cal2_g","cal2_b",
            "cal3_r","cal3_g","cal3_b"
        )
        val header = (listOf("sample_id","reference_name","distance_calculation","similarity_distance")
                + calHeaders + dyeHeaders).joinToString(",")

        val rows = table.samples.mapIndexed { sampleIdx, sample ->
            val result = sample.classificationResults.firstOrNull()
            val totalDist = result?.totalDistance ?: ""
            val refName = result?.closestReferenceName ?: sample.referenceName
            val meta = listOf(sampleIdx.toString(), refName, distanceMetric, totalDist.toString())

            val cal = sample.squares.take(4).flatMap {
                listOf(it.`val`[0], it.`val`[1], it.`val`[2])
            }.map { it.toString() }

            val rgbParts = allWellNames.flatMap { name ->
                val index = sample.names.indexOf(name)
                // Fill with RGB values if well exists, even if not selected
                if (index != -1) {
                    val scalar = sample.rgb[index]
                    listOf(scalar.`val`[0].toString(), scalar.`val`[1].toString(), scalar.`val`[2].toString())
                } else {
                    listOf("", "", "")
                }
            }

            (meta + cal + rgbParts).joinToString(",")
        }

        val body = rows.joinToString("\n")
        return if (includeHeader) "$header\n$body" else body
    }
}
