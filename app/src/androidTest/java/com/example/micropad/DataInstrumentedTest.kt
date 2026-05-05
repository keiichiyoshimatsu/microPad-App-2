package com.example.micropad

import android.net.Uri
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.micropad.data.DatasetModel
import com.example.micropad.ui.theme.MicroPadTheme
import org.junit.Rule
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.io.File

class DataInstrumentedTest {
    /**
     * Helper to extract test images from the test APK assets to local storage.
     * Place images in: app/src/androidTest/assets/test_images/
     */
    private fun prepareTestImages(): List<Uri> {
        val instrumentationContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val targetContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val assetNames = listOf("ref1.jpg", "ref2.jpg", "ref3.jpg", "ref4.jpg", "sample1.jpg", "sample2.jpg", "sample3.jpg", "sample4.jpg", "sample5.jpg")

        return assetNames.map { name ->
            val outFile = File(targetContext.cacheDir, name)
            instrumentationContext.assets.open("images/$name").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(outFile)
        }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Navigates through the application to upload and label images.
     *
     * This function performs the following actions:
     * 1. Sets up the MainContent with a DatasetModel and NavHostController.
     * 2. Locates available images in the assets/images directory
     * 3. Navigates to the LabelingScreen for Reference images, naming the first three "Reference 1", "Reference 2", and "Reference 3".
     * 4. Navigates to the LabelingScreen for Sample images, naming the next two "Sample 1" and "Sample 2".
     * 5. Proceeds to the WellNamingScreen, waits for image processing to complete, and labels the first 6 ROIs as:
     *    "No Dye", "DMGO", "Phen", "XO", "DCP", and "Par".
     * 6. Exits the WellNamingScreen by clicking the "Next" button.
     * NOTE: The first 4 references should match the first 4 samples. The last sample should not match any reference
     *
     * End State: The application is left on the "options" (Analysis Configuration) screen with a fully labeled dataset.
     */
    fun openAndLabelImages() {
        val imageUris = try {
            prepareTestImages()
        } catch (e: Exception) {
            // Fallback to mock URIs if assets are missing to prevent crash during development
            (1..5).map { Uri.parse("file:///sdcard/mock_image_$it.png") }
        }

        if (!OpenCVLoader.initLocal()) {
            throw RuntimeException("OpenCV failed to load")
        }

        lateinit var viewModel: DatasetModel
        lateinit var navController: NavHostController

        composeTestRule.setContent {
            MicroPadTheme {
                viewModel = viewModel()
                navController = rememberNavController()
                MainContent(viewModel, navController)
            }
        }

        // 1. Label Reference Images
        composeTestRule.runOnUiThread {
            viewModel.temporaryUris = imageUris.take(4)
            viewModel.labelingTargetIsReference = true
            navController.navigate("labelingScreen")
        }

        for (i in 1..4) {
            composeTestRule.onNodeWithText("Enter Name (e.g. H2O, Sample A)").performTextInput("Reference $i")
            composeTestRule.onNodeWithText(if (i < 4) "Next Image" else "Finish").performClick()
        }

        // 2. Label Sample Images
        composeTestRule.runOnUiThread {
            viewModel.temporaryUris = imageUris.drop(4).take(5)
            viewModel.labelingTargetIsReference = false
            navController.navigate("labelingScreen")
        }

        for (i in 1..5) {
            composeTestRule.onNodeWithText("Enter Name (e.g. H2O, Sample A)").performTextInput("Sample $i")
            composeTestRule.onNodeWithText(if (i < 5) "Next Image" else "Finish").performClick()
        }

        // 3. Navigate to Well Naming
        composeTestRule.onNodeWithText("Next: Process & Name Wells").performClick()

        // Wait for image processing to start
        composeTestRule.waitUntil(timeoutMillis = 500) {
            viewModel.isLoading
        }

        // Wait for image processing to finish
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            !viewModel.isLoading
        }

        // 4. Label ROIs
        composeTestRule.onNodeWithText("ROI Naming").performClick()

        val roiLabels = listOf("No Dye", "DMGO", "Phen", "XO", "DCP", "Par")
        roiLabels.forEachIndexed { index, label ->
            val roiName = "ROI ${index + 1}"

            // Scroll the list until the node appears in the tree
            composeTestRule.onNodeWithTag("roiList")
                .performScrollToNode(hasText(roiName))

            // Now it's composed and interactable
            composeTestRule.onNodeWithText(roiName)
                .performTextReplacement(label)
        }

        // 5. Finish and Exit WellNamingScreen
        composeTestRule.onNodeWithText("Next").performClick()
    }

    @Test
    fun closestMatch_isDisplayed() {
        openAndLabelImages()

        composeTestRule.onNodeWithText("Manhattan").performClick()
        composeTestRule.onNodeWithText("Run Classification & View Results").performClick()

        for (i in 1..5) {
            composeTestRule.onNodeWithTag("analysisList").performScrollToNode(hasText("Sample $i")).assertExists()
        }

        for (i in 1..4) {
            composeTestRule.onNodeWithTag("analysisList")
                .performScrollToNode(hasText("Sample $i"))

            composeTestRule.onNode(
                hasText("Reference $i") and hasAnySibling(hasText("Sample $i"))
            ).assertExists()
        }
    }

    @Test
    fun noMatch_isDisplayed() {
        openAndLabelImages()

        composeTestRule.onNodeWithText("Manhattan").performClick()
        composeTestRule.onNodeWithText("Run Classification & View Results").performClick()

        for (i in 1..5) {
            composeTestRule.onNodeWithTag("analysisList").performScrollToNode(hasText("Sample $i")).assertExists()
        }

        composeTestRule.onNodeWithTag("analysisList")
            .performScrollToNode(hasText("Sample 5"))

        composeTestRule.onNode(
            hasText("No Match") and hasAnySibling(hasText("Sample 5"))
        ).assertExists()
    }

    @Test
    fun bestEstimate_isDisplayed() {
        openAndLabelImages()

        composeTestRule.onNodeWithText("Manhattan").performClick()
        composeTestRule.onNodeWithText("Run Classification & View Results").performClick()

        for (i in 1..5) {
            composeTestRule.onNodeWithTag("analysisList").performScrollToNode(hasText("Sample $i")).assertExists()
        }

        composeTestRule.onNodeWithTag("analysisList")
            .performScrollToNode(hasText("Sample 5"))

        // Requirement 4: System returns best estimate (closest reference) 
        // instead of "No Match" or crashing.
        composeTestRule.onNode(
            hasText("Reference", substring = true) and hasAnySibling(hasText("Sample 5"))
        ).assertExists()
    }
}