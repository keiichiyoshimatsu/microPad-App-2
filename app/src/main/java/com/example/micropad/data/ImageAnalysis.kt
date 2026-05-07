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
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Create a bit mapping from image and save to device storage.
 *
 * @param mat: Matrix of image columns and rows (colors).
 * @param filename: Filename to save image.
 * @param context: Context of the Composable calling this function
 * @return String? path to saved image
 */
fun saveMat(mat: Mat, filename: String, context: Context): String? {
    return try {
        if (mat.empty()) {
            AppErrorLogger.logError(context, "ImagePipeline", "saveMat: received empty Mat for $filename")
            return null
        }
        val bitmap = createBitmap(mat.cols(), mat.rows())
        Utils.matToBitmap(mat, bitmap)
        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: run {
                AppErrorLogger.logError(context, "ImagePipeline", "saveMat: external storage unavailable")
                return null
            }
        val file = File(picturesDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Log.d("Pipeline", "Saved: ${file.absolutePath} (${mat.cols()}x${mat.rows()})")
        file.absolutePath
    } catch (e: Exception) {
        AppErrorLogger.logError(context, "ImagePipeline", "saveMat failed for $filename", e)
        null
    }
}

/**
 * Detect contours around each dye well in the image.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param context: Context of the Composable calling this function.
 * @param log: Boolean indicating whether or not the function should save snapshots of each.
 * @returns ArrayList<MatOfPoint> list of contours
 */
fun findContours(image: Mat, context: Context, log: Boolean): ArrayList<MatOfPoint> {
    return try {
        if (image.empty()) {
            AppErrorLogger.logError(context, "ImagePipeline", "findContours: empty input image")
            return arrayListOf()
        }
        Log.d("Pipeline", "--- Stage: Contour Detection ---")
        val gray = Mat()
        val blurred = Mat()
        val thresh = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 0.0)
            if (log) saveMat(blurred, "blurred.png", context)
            Imgproc.adaptiveThreshold(
                blurred, thresh, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 35, 2.0
            )
            if (log) saveMat(thresh, "threshold.png", context)
            Imgproc.findContours(
                thresh, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            Log.d("Pipeline", "Found ${contours.size} contours")
        } finally {
            gray.release(); blurred.release(); thresh.release(); hierarchy.release()
        }
        contours
    } catch (e: Exception) {
        AppErrorLogger.logError(context, "ImagePipeline", "findContours failed", e)
        arrayListOf()
    }
}


/**
 * Detects the white card in the image, applies a perspective warp, and returns a flattened,
 * top-down crop of just the card.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param context: Context of the Composable calling this function.
 * @param log: Boolean indicating whether the function should save snapshots of each.
 * @return Null if no card candidate is found.
 */
fun findAndWarpCard(image: Mat, context: Context, log: Boolean): Mat? {
    Log.d("Pipeline", "--- Stage: Card Localization & Warping ---")
    val hsv = Mat()
    Imgproc.cvtColor(image, hsv, Imgproc.COLOR_BGR2HSV)

    val whiteMask = Mat()
    Core.inRange(
        hsv,
        Scalar(0.0, 0.0, 160.0),
        Scalar(180.0, 50.0, 255.0),
        whiteMask
    )

    val whiteRatio = Core.countNonZero(whiteMask).toDouble() / image.total()
    if (whiteRatio > 0.5) {
        hsv.release(); whiteMask.release()
        Log.d("Pipeline", "Warping Skipped: Image is ${(whiteRatio * 100).toInt()}% white, returning full image")
        return image
    }

    if (log) saveMat(whiteMask, "card_white_mask.png", context)

    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
    val closed = Mat()
    Imgproc.morphologyEx(whiteMask, closed, Imgproc.MORPH_CLOSE, kernel)

    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

    val best = contours
        .filter { Imgproc.contourArea(it) > image.total() * 0.10 }
        .maxByOrNull { Imgproc.contourArea(it) }

    val area = Imgproc.contourArea(best)
    if (area > image.total() * 0.80) {
        Log.w("Pipeline", "Card region too large — likely background bleed, skipping crop")
        return image  // return full image rather than a bad crop
    }

    if (best == null) {
        Log.w("Pipeline", "Warping Failed: No card contour found")
        return null
    }

    val rect = Imgproc.boundingRect(best)
    Log.d("Pipeline", "Data Movement: Cropping to Card Bounding Rect: $rect")
    val cropped = Mat(image, rect)

    if (log) saveMat(cropped, "card_warped.png", context)

    hsv.release(); whiteMask.release()
    closed.release(); hierarchy.release()

    return cropped
}

/**
 * Attempts to find the calibration rectangle in the image.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param contours: List of contours found in the image.
 * @param context: Context of the Composable calling this function.
 * @param log: Boolean indicating whether the function should save snapshots of each.
 * @return MutableList<Pair<Mat, Point>> A pair consisting of the rectangle portion of the image and
 * its bounding box
 */
fun findCalibrationSquares(image: Mat, contours: ArrayList<MatOfPoint>, context: Context, log: Boolean): MutableList<Pair<Mat, Point>> {
    Log.d("Pipeline", "--- Stage: Calibration Square Isolation ---")
    data class Candidate(val region: Mat, val center: Point, val area: Double)
    val candidates = mutableListOf<Candidate>()

    for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area < 50) continue

        val contour2f = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.04 * peri, true)

        if (approx.total() !in 4L..6L) continue

        val rect = Imgproc.boundingRect(contour)
        val ratio = rect.width.toDouble() / rect.height.toDouble()
        if (ratio < 0.7 || ratio > 1.3) continue

        val m = Imgproc.moments(contour)
        if (m.m00 == 0.0) continue
        val center = Point(m.m10 / m.m00, m.m01 / m.m00)

        candidates.add(Candidate(Mat(image, rect), center, area))
    }

    Log.d("Pipeline", "Data Extracted: Found ${candidates.size} square-like candidates")

    if (candidates.size < 4) {
        Log.w("Pipeline", "Calibration Failed: Insufficient candidates")
        return mutableListOf()
    }

    val sortedAreas = candidates.map { it.area }.sorted()
    val medianArea = sortedAreas[sortedAreas.size / 2]
    val areaFiltered = candidates.filter { abs(it.area - medianArea) / medianArea < 0.5 }

    if (areaFiltered.size < 4) {
        Log.w("Pipeline", "Calibration Failed: Too many outliers after area filtering")
        return mutableListOf()
    }

    // --- Step 3: find the 4 closest points ---
    /**
     * Calculate the collinearity score of a set of points.
     * Among all combinations of 4, pick the most collinear group.
     * Higher scores indicate more collinearity.
     *
     * @param pts: List of points to score.
     * @return Double score.
     */
    fun collinearityScore(pts: List<Point>): Double {
        val cx = pts.map { it.x }.average()
        val cy = pts.map { it.y }.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val nx = -sin(angle); val ny = cos(angle)
        return pts.sumOf { p ->
            val dx = p.x - cx; val dy = p.y - cy
            (dx * nx + dy * ny).pow(2)
        }
    }

    // Also check that the 4 points are roughly evenly spaced
    /**
     * Calculate the spacing score of a set of points.
     * Among all combinations of 4, pick the most evenly spaced group.
     *
     * @param pts: List of points to score.
     * @return Double score.
     */
    fun spacingScore(pts: List<Point>): Double {
        val cx = pts.map { it.x }.average()
        val cy = pts.map { it.y }.average()
        val angle = atan2(pts.last().y - pts.first().y, pts.last().x - pts.first().x)
        val projections = pts.map { p ->
            (p.x - cx) * cos(angle) + (p.y - cy) * sin(angle)
        }.sorted()
        val gaps = projections.zipWithNext { a, b -> b - a }
        val meanGap = gaps.average()
        return gaps.sumOf { (it - meanGap).pow(2) } / meanGap.pow(2)
    }

    var bestScore = Double.MAX_VALUE
    var bestGroup = areaFiltered.take(4)

    val n = areaFiltered.size
    for (i in 0 until n) for (j in i+1 until n) for (k in j+1 until n) for (l in k+1 until n) {
        val group = listOf(areaFiltered[i], areaFiltered[j], areaFiltered[k], areaFiltered[l])
        val pts = group.map { it.center }
        val score = collinearityScore(pts) + spacingScore(pts) * medianArea
        if (score < bestScore) {
            bestScore = score
            bestGroup = group
        }
    }

    val shapes = bestGroup
        .sortedBy { it.center.x}
        .map { Pair(it.region, it.center) }
        .toMutableList()

    Log.d("Pipeline", "Data Selected: Optimized set of 4 calibration squares identified")
    return shapes
}

/**
 * Extract the calibration colors from a calibration region.
 *
 * @param shapes: List of calibration regions.
 * @return MutableList<Scalar> List of colors.
 */
fun extractCalibrationColors(shapes: MutableList<Pair<Mat, Point>>): MutableList<Scalar> {
    val colors = mutableListOf<Scalar>()
    for (shape in shapes) {
        if (shape.first.empty()) {
            colors.add(Scalar(128.0, 128.0, 128.0))
            continue
        }
        colors.add(Core.mean(shape.first))
    }
    return colors
}

/**
 * Find a linear fit that tries to push image brightness towards calibration standard.
 * Cannot always find a perfect match because this function does not know the expected RGB
 * values.
 *
 * @param measured: List of measured values.
 * @param expected: List of expected values.
 * @return Pair<Double, Double>: (scale, offset)
 */
fun computeLinearFit(measured: DoubleArray, expected: DoubleArray): Pair<Double, Double> {
    val n = measured.size
    if (n == 0) return Pair(1.0, 0.0)
    val sumX = measured.sum()
    val sumY = expected.sum()
    val sumXY = measured.zip(expected).sumOf { it.first * it.second }
    val sumX2 = measured.sumOf { it * it }
    val denom = n * sumX2 - sumX * sumX
    // Guard: avoid division by zero when all measured values are identical
    if (denom == 0.0) return Pair(1.0, 0.0)
    val scale = (n * sumXY - sumX * sumY) / denom
    val offset = (sumY - scale * sumX) / n
    return Pair(scale, offset)
}


/**
 * Rebalances the image given a set of the found color points and the intended reference color
 * points.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param found: List of found color points.
 * @param reference: List of intended reference color points.
 * @return Mat rebalanced image.
 */
fun rebalanceImage(image: Mat, found: List<Scalar>, reference: List<Scalar>): Mat {
    if (found.isEmpty() || reference.isEmpty() || found.size != reference.size) return image
    return try {
        Log.d("Pipeline", "--- Stage: Color Calibration ---")
        val balanced = image.clone()
        // OpenCV BGR: val[0]=Blue, val[1]=Green, val[2]=Red
        val bMeasured = found.map { it.`val`[0] }.toDoubleArray()
        val gMeasured = found.map { it.`val`[1] }.toDoubleArray()
        val rMeasured = found.map { it.`val`[2] }.toDoubleArray()
        val bExpected  = reference.map { it.`val`[0] }.toDoubleArray()
        val gExpected  = reference.map { it.`val`[1] }.toDoubleArray()
        val rExpected  = reference.map { it.`val`[2] }.toDoubleArray()
        val (bScale, bOffset) = computeLinearFit(bMeasured, bExpected)
        val (gScale, gOffset) = computeLinearFit(gMeasured, gExpected)
        val (rScale, rOffset) = computeLinearFit(rMeasured, rExpected)
        val channels = ArrayList<Mat>()
        Core.split(balanced, channels)
        if (channels.size >= 3) {
            channels[0].convertTo(channels[0], -1, bScale, bOffset)
            channels[1].convertTo(channels[1], -1, gScale, gOffset)
            channels[2].convertTo(channels[2], -1, rScale, rOffset)
            Core.merge(channels, balanced)
        }
        channels.forEach { it.release() }
        balanced
    } catch (e: Exception) {
        android.util.Log.e("ImagePipeline", "rebalanceImage failed: ${e.message}", e)
        image
    }
}

/**
 * Get the center of a contour.
 *
 * @param contour: Contour to get center of.
 * @return Point: Center of contour.
 */
fun getCenter(contour: MatOfPoint): Point {
    return try {
        val m = Imgproc.moments(contour)
        if (m.m00 == 0.0) Point(0.0, 0.0) else Point(m.m10 / m.m00, m.m01 / m.m00)
    } catch (_: Exception) { Point(0.0, 0.0) }
}


/**
 * Shrink a contour around its center to extract the center of the dye dots.
 *
 * @param contour: Contour to shrink.
 * @param shrink: Shrink factor.
 * @return MatOfPoint: Shrunken contour.
 */
fun shrinkContour(contour: MatOfPoint, shrink: Float): MatOfPoint {
    return try {
        val effectiveShrink = shrink.coerceIn(0.01f, 1.0f)
        val center = getCenter(contour)
        val points = contour.toList().map { point ->
            val dx = (point.x - center.x) * effectiveShrink
            val dy = (point.y - center.y) * effectiveShrink
            Point(center.x + dx, center.y + dy)
        }
        MatOfPoint(*points.toTypedArray())
    } catch (_: Exception) { contour }
}

/**
 * Draw the ordering of the dots on the image.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param orderedDots: List of ordered dots.
 * @param highlightIndex: Index of dot to highlight.
 * @param selectionStates: List of selection states.
 * @return Bitmap: Image with ordering drawn.
 */
fun drawOrdering(
    image: Mat,
    orderedDots: List<Pair<MatOfPoint, Scalar>>,
    highlightIndex: Int? = null,
    selectionStates: List<Boolean>? = null
): Bitmap {
    return try {
        if (image.empty()) return createBitmap(1, 1)
        val output = Mat()
        image.copyTo(output)
        // Loop through dots and draw them on the image
        for ((index, pair) in orderedDots.withIndex()) {
            val isSelected = selectionStates?.getOrNull(index) ?: true
            val contour = pair.first
            val center = getCenter(contour)
            val area = Imgproc.contourArea(contour)
            if (area <= 0) continue
            val radius = sqrt(area / Math.PI)
            val fontScale = (radius / 20.0).coerceAtLeast(0.3)
            val thickness = (fontScale * 3).toInt().coerceAtLeast(1)
            val text = (index + 1).toString()
            val baseline = IntArray(1)
            val textSize = Imgproc.getTextSize(
                text, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, thickness, baseline
            )
            val textOrigin = Point(
                center.x - textSize.width / 2.0,
                center.y + textSize.height / 2.0
            )

            // White outline drawn first, then black text on top
            // Imgproc.drawContours(output, listOf(contour), -1
            // fontScale, Scalar(255.0, 255.0, 255.0, 255.0), outlineThickness
            val color = if (isSelected) Scalar(0.0, 0.0, 0.0, 255.0)
            else Scalar(128.0, 128.0, 128.0, 128.0)
            Imgproc.drawContours(output, listOf(contour), -1, Scalar(255.0, 255.0, 255.0, 255.0), -1)
            Imgproc.drawContours(output, listOf(contour), -1, color, if (isSelected) 6 else 2)
            Imgproc.putText(output, text, textOrigin, Imgproc.FONT_HERSHEY_SIMPLEX, fontScale, color, thickness)
            if (!isSelected) {
                // Draw an X over unselected wells
                val xSize = radius * 0.8
                Imgproc.line(output, Point(center.x - xSize, center.y - xSize), Point(center.x + xSize, center.y + xSize), color, 2)
                Imgproc.line(output, Point(center.x + xSize, center.y - xSize), Point(center.x - xSize, center.y + xSize), color, 2)
            }
        }
        val bitmap = createBitmap(output.cols().coerceAtLeast(1), output.rows().coerceAtLeast(1))
        Utils.matToBitmap(output, bitmap)
        output.release()
        bitmap
    } catch (e: Exception) {
        createBitmap(1, 1)
    }
}

/**
 * Assign a grid index to each dot.
 *
 * @param dots: List of dots.
 * @return List<Pair<Pair<MatOfPoint, Scalar>, Pair<Int, Int>>>: List of pairs of dots and their
 * grid indices.
 */
fun assignGridIndices(
    dots: List<Pair<MatOfPoint, Scalar>>
): List<Pair<Pair<MatOfPoint, Scalar>, Pair<Int, Int>>> {
    if (dots.size < 2) return dots.mapIndexed { i, d -> Pair(d, Pair(0, i)) }
    return try {
        val centers = dots.map { Pair(it, getCenter(it.first)) }
        val distances = centers.map { (_, p) ->
            centers.filter { (_, q) -> q != p }
                .minOfOrNull { (_, q) -> Math.hypot(q.x - p.x, q.y - p.y) } ?: 0.0
        }
        val spacing = distances.average()
        if (spacing <= 0.0) return dots.mapIndexed { i, d -> Pair(d, Pair(0, i)) }
        val rowTolerance = spacing * 0.6
        val rows = mutableListOf<MutableList<Pair<Pair<MatOfPoint, Scalar>, Point>>>()
        // Sort points in each row by X proximity
        for (item in centers.sortedBy { it.second.y }) {
            val matchingRow = rows.find { row ->
                val avgY = row.map { it.second.y }.average()
                abs(item.second.y - avgY) < rowTolerance
            }
            if (matchingRow != null) matchingRow.add(item) else rows.add(mutableListOf(item))
        }
        rows.sortedBy { row -> row.map { it.second.y }.average() }
            .flatMapIndexed { rowIdx, row ->
                row.sortedBy { it.second.x }
                    .mapIndexed { colIdx, item -> Pair(item.first, Pair(rowIdx, colIdx)) }
            }
    } catch (e: Exception) {
        dots.mapIndexed { i, d -> Pair(d, Pair(0, i)) }
    }
}


/**
 * Extract the dye color from a contour.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param contour: Contour to extract color from.
 * @return Scalar: Color.
 */
fun extractContour(image: Mat, contour: MatOfPoint): Mat {
    return try {
        val mask = Mat.zeros(image.size(), CvType.CV_8UC1)
        Imgproc.drawContours(mask, listOf(contour), 0, Scalar(255.0), Imgproc.FILLED)
        val extractedData = Mat()
        image.copyTo(extractedData, mask)
        mask.release()
        extractedData
    } catch (_: Exception) { Mat() }
}

/**
 * Extract the dye color from an image.
 *
 * @param extractedMat: Matrix of image columns and rows (colors).
 * @param selectionStrategy: Strategy to use for color extraction.
 * @return Scalar: Color.
 */
fun extractDyeColor(extractedMat: Mat, selectionStrategy: String): Scalar {
    if (extractedMat.empty()) return Scalar(128.0, 128.0, 128.0, 0.0)
    val hsv = Mat(); val colorMask = Mat(); val whiteMask = Mat(); val alphaMask = Mat()
    return try {
        Imgproc.cvtColor(extractedMat, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(0.0, 15.0, 50.0), Scalar(180.0, 255.0, 255.0), colorMask)
        Core.inRange(hsv, Scalar(0.0, 0.0, 200.0), Scalar(180.0, 40.0, 255.0), whiteMask)
        Core.subtract(colorMask, whiteMask, colorMask)
        val channels = ArrayList<Mat>()
        Core.split(extractedMat, channels)
        if (channels.size >= 4) {
            // Create mask: alpha > 0
            Imgproc.threshold(channels[3], alphaMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
        }
        channels.forEach { it.release() }

        if (Core.countNonZero(colorMask) == 0) {
            return Core.mean(extractedMat,
                if (!alphaMask.empty()) alphaMask else Mat())
        }

        if (selectionStrategy == "Mean") {
            Core.mean(extractedMat, colorMask)
        } else {
            val moments = Imgproc.moments(colorMask)
            if (moments.m00 == 0.0) return Core.mean(extractedMat, colorMask)
            val cx = (moments.m10 / moments.m00).toInt()
            val cy = (moments.m01 / moments.m00).toInt()
            // Clamp to prevent out-of-bounds submat
            val safeX = cx.coerceIn(2, extractedMat.cols() - 3)
            val safeY = cy.coerceIn(2, extractedMat.rows() - 3)
            // Takes mean of a small rectangle of pixels to reduce noise
            Core.mean(extractedMat.submat(Rect(safeX - 2, safeY - 2, 5, 5)))
        }
    } catch (_: Exception) {
        Scalar(128.0, 128.0, 128.0, 0.0)
    } finally {
        hsv.release(); colorMask.release(); whiteMask.release()
        if (!alphaMask.empty()) alphaMask.release()
    }
}

/**
 * Find the donut shapes in the preprocessed image.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param contours: List of contours found in the image.
 * @param context: Context of the Composable calling this function.
 * @param log: Boolean indicating whether the function should save snapshots of each.
 * @param selectionStrategy: Strategy to use for color extraction.
 * @param shrink: Shrink factor.
 * @return MutableList<Pair<MatOfPoint, Scalar>>: List of dots.
 */
fun findDots(
    image: Mat,
    contours: ArrayList<MatOfPoint>,
    context: Context,
    log: Boolean,
    selectionStrategy: String,
    shrink: Float = 0.35f
): MutableList<Pair<MatOfPoint, Scalar>> {
    return try {
        Log.d("Pipeline", "--- Stage: Well Identification ---")
        val candidates = mutableListOf<Pair<MatOfPoint, Scalar>>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 100) continue
            val rect = Imgproc.boundingRect(contour)
            val diameter = (rect.width + rect.height) / 2.0
            val circularArea = (diameter / 2.0).pow(2) * Math.PI
            val areaError = abs((circularArea - area) / area)
            val perim = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            if (perim <= 0) continue
            val circularity = 4 * Math.PI * area / (perim * perim)
            val perimeterError = abs(1 - circularity)
            // Check error tolerance
            if (areaError < 0.3 && perimeterError < 0.4) {
                val center = shrinkContour(contour, shrink.coerceIn(0.01f, 1.0f))
                val extracted = extractContour(image, center)
                if (!extracted.empty()) {
                    candidates.add(Pair(center, extractDyeColor(extracted, selectionStrategy)))
                    extracted.release()
                }
            }
        }

        Log.d("Pipeline", "Identified ${candidates.size} well candidates")
        if (candidates.isEmpty()) return mutableListOf()
        // Sort candidates by size
        val sizeSorted = candidates
            .map { it to Imgproc.contourArea(it.first) }
            .sortedByDescending { it.second }

        // Safe median: require at least 2 candidates; use index size/2 (lower-middle)
        if (sizeSorted.size < 2) return mutableListOf()
        // Candidate locations
        val top = sizeSorted.take(4)
        val medianArea = top.map { it.second }.sorted()[top.size / 2]

        if (medianArea <= 0) return mutableListOf()

        val finalDots = sizeSorted
            .filter { abs(it.second - medianArea) / medianArea < 0.2 }
            .map { it.first }

        if (log) {
            finalDots.forEachIndexed { i, dot ->
                val extracted = extractContour(image, dot.first)
                if (!extracted.empty()) { saveMat(extracted, "dot$i.png", context); extracted.release() }
            }
        }

        val indexed = assignGridIndices(finalDots)
        indexed.sortedWith(compareBy({ it.second.first }, { it.second.second }))
            .map { it.first }
            .toMutableList()
    } catch (e: Exception) {
        AppErrorLogger.logError(context, "ImagePipeline", "findDots failed", e)
        mutableListOf()
    }
}


/**
 * Build a circular contour around a center point. Useful for manually added ROIs.
 *
 * @param center Center of the ROI in image coordinates.
 * @param radius Radius in pixels.
 * @param numPoints Number of vertices used to approximate the circle.
 * @return MatOfPoint contour approximating the circle.
 */
fun createCircularContour(center: Point, radius: Double, numPoints: Int = 36): MatOfPoint {
    val points = (0 until numPoints).map { i ->
        val theta = 2.0 * PI * i / numPoints
        Point(
            center.x + radius * kotlin.math.cos(theta),
            center.y + radius * kotlin.math.sin(theta)
        )
    }
    return MatOfPoint(*points.toTypedArray())
}

/**
 * Estimate a reasonable ROI radius from already detected wells.
 */
fun estimateWellRadius(dots: List<Pair<MatOfPoint, Scalar>>, fallback: Double = 18.0): Double {
    if (dots.isEmpty()) return fallback
    val radii = dots.map { sqrt(Imgproc.contourArea(it.first) / Math.PI) }.filter { it.isFinite() && it > 0 }
    if (radii.isEmpty()) return fallback
    return radii.sorted()[radii.size / 2]
}

/**
 * Extract a dye color from a manually selected image location by reusing the same color extraction
 * strategy as automatically detected wells.
 */
fun extractManualDotAtPoint(
    image: Mat,
    center: Point,
    radius: Double,
    selectionStrategy: String,
    shrink: Float = 0.85f
): Pair<MatOfPoint, Scalar> {
    val baseContour = createCircularContour(center, radius)
    val sampleContour = shrinkContour(baseContour, shrink)
    val extractedData = extractContour(image, sampleContour)
    val dataPoint = extractDyeColor(extractedData, selectionStrategy)
    extractedData.release()
    return Pair(sampleContour, dataPoint)
}

// Colors used on dye sheet, arranged in BGR ordering
val expectedColors = mutableListOf(
    Scalar(0.0, 0.0, 0.0), Scalar(255.0, 255.0, 0.0),
    Scalar(0.0, 255.0, 255.0), Scalar(255.0, 0.0, 255.0)
)


/**
 * Preprocess an image.
 *
 * @param image: Matrix of image columns and rows (colors).
 * @param context: Context of the Composable calling this function.
 * @param log: Boolean indicating whether the function should save snapshots of each.
 * @param normalizationStrategy: Strategy to use for color normalization.
 * @param selectionStrategy: Strategy to use for color extraction.
 * @return Sample: Preprocessed image.
 */
fun preprocessImage(
    image: Mat,
    context: Context,
    log: Boolean,
    normalizationStrategy: String,
    selectionStrategy: String
): Sample? { // <-- return nullable now
    return try {
        if (image.empty()) {
            AppErrorLogger.logError(context, "ImagePipeline", "preprocessImage: empty input")
            return null
        }
        val contours = findContours(image, context, log)
        val shapes   = findCalibrationSquares(image, contours, context, log)
        val colors   = extractCalibrationColors(shapes)
        val dots     = findDots(image, contours, context, log, selectionStrategy)

        if (dots.isEmpty()) {
            AppErrorLogger.logError(context, "ImagePipeline", "No wells detected — skipping sample")
            return null
        }
        var balanced = image
//        if (normalizationStrategy == "Regression" && colors.size == 4) {
//            balanced = rebalanceImage(image, colors, expectedColors)
//        }

        val orderingImage = drawOrdering(image, dots)
        Sample(image, image, orderingImage, dots, squares = colors)
    } catch (e: Exception) {
        AppErrorLogger.logError(context, "ImagePipeline", "preprocessImage failed", e)
        null
    }
}

/**
 * A function for taking in a list of image locations and returning a list of completely
 * preprocessed images in the form of a list of Samples. This includes extracting the colors
 * from each of the dye spots in each image.
 *
 * @param addresses The Uris identifying the locations of all the images that we want
 * to preprocess.
 * @param context The context of the Composable calling this function,
 * obtained from LocalContext.current. This is only used for logging.
 *
 * @param log Boolean indicating whether or not the function should save snapshots of each
 * step in the preprocessing algorithm.
 * @param normalizationStrategy Identifies how the algorithm should normalize the image data.
 * Options are:
 *  Regression: Rebalances using a linear regression to perform a min-max normalization
 *  on the whole RGB gamut.
 *  MinMax: Performs normalization by pulling the maximum and minimum observed colors to the
 *  expected minimums and maximums separately for each channel.
 *  Z-Score: Performs normalization by subtracting the mean and dividing by the standard
 *  deviation.
 *
 * @return A SampleDataset containing the preprocessed images.
 */
suspend fun ingestImages(
    addresses: List<Uri>,
    context: Context,
    log: Boolean = false,
    normalizationStrategy: String = "Regression",
    selectionStrategy: String = "Mean"
): SampleDataset = coroutineScope {
    Log.d("Pipeline", "Ingesting ${addresses.size} image(s)")

    val results = addresses.map { uri ->
        async(Dispatchers.Default) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: run {
                        AppErrorLogger.logError(context, "Ingest", "Cannot open stream: $uri")
                        return@async null
                    }
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    AppErrorLogger.logError(context, "Ingest", "BitmapFactory returned null: $uri")
                    return@async null
                }

                val rotation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f

                val mutableBitmap = if (rotation != 0f) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                val mat = Mat()
                Utils.bitmapToMat(mutableBitmap, mat)
                mutableBitmap.recycle()

                if (mat.empty()) {
                    AppErrorLogger.logError(context, "Ingest", "Mat conversion produced empty result: $uri")
                    mat.release()
                    return@async null
                }

                val image = Mat()
                val ratio = minOf(
                    1000.0 / mat.width().toDouble(),
                    1000.0 / mat.height().toDouble()
                ).coerceAtMost(1.0)  // never upscale
                Imgproc.resize(mat, image, Size(0.0, 0.0), ratio, ratio, Imgproc.INTER_AREA)
                mat.release()

                val cropped = findAndWarpCard(image, context, log) ?: image
                preprocessImage(cropped, context, log, normalizationStrategy, selectionStrategy)

            } catch (e: CancellationException) {
                throw e   // propagate coroutine cancellation — never swallow
            } catch (e: Exception) {
                AppErrorLogger.logError(context, "Ingest", "Failed processing URI: $uri", e)
                null
            }
        }
    }.awaitAll()

    val successful = results.filterNotNull()
    if (successful.size < addresses.size) {
        AppErrorLogger.logError(
            context, "Ingest",
            "${addresses.size - successful.size} of ${addresses.size} image(s) failed processing"
        )
    }

    SampleDataset(successful.toMutableList())
}