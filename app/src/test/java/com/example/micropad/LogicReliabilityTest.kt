package com.example.micropad

import com.example.micropad.data.DatasetModel
import com.example.micropad.data.Sample
import com.example.micropad.data.SampleDataset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.opencv.core.Scalar

class LogicReliabilityTest {
    private lateinit var viewModel: DatasetModel

    @Before
    fun setup() {
        viewModel = DatasetModel()
    }

    /**
     * Requirement 4: Best-estimate behavior when no exact match exists.
     * Requirement 6: Deterministic output.
     */
    @Test
    fun `test classification returns best estimate and is deterministic`() {
        val refSample = Sample(null, null, null, mutableListOf(), mutableListOf(Scalar(100.0, 100.0, 100.0)), "Reference A", false)
        val refDataset = SampleDataset(mutableListOf(refSample))
        
        // Sample that is "close" but not an exact match
        val sample = Sample(null, null, null, mutableListOf(), mutableListOf(Scalar(110.0, 110.0, 110.0)), "Unknown", false)
        val sampleDataset = SampleDataset(mutableListOf(sample))

        viewModel.referenceDataset = refDataset
        viewModel.newDataset = sampleDataset
        viewModel.distanceMetric = "Euclidean"

        // Run multiple times to check determinism
        repeat(5) {
            viewModel.runWholeCardClassification()
            val result = sample.classificationResults.first()
            
            assertEquals("Reference A", result.closestReferenceName)
            assertTrue(result.totalDistance > 0)
            sample.classificationResults.clear()
        }
    }
}
