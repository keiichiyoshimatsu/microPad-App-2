package com.example.micropad.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Provide a UI button to launch a file picker for creating a new CSV or appending to an existing one.
 *
 * @param dataRows The new data rows to be added (newline separated).
 * @param type Either "references" or "samples".
 * @param initialFilename Default filename if creating a new file.
 * @param existingUri The Uri of the file to append to. If null, a file picker will be launched.
 * @param navHome Optional function to allow for immediate navigation home in cases where an export is the final step.
 */
@Composable
fun CsvExportButton(
    dataRows: String,
    type: String,
    initialFilename: String? = "data.csv",
    existingUri: Uri? = null,
    navHome: () -> Unit = {}
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            writeToCsv(dataRows, type, uri, context)
            navHome()
        }
        // user cancelled picker — do nothing
    }

    Button(onClick = {
        if (existingUri != null) {
            writeToCsv(dataRows, type, existingUri, context)
            navHome()
        } else {
            launcher.launch(initialFilename ?: "data.csv")
        }
    }) {
        Text("Save as $type")
    }
}

/**
 * Write newData to filePath atomically by using a temporary file.
 * The CSV file is structured with:
 * 1. Header
 * 2. References rows
 * 3. A blank row separator
 * 4. Samples rows
 *
 * This function ensures no duplicate rows are added to the specified section.
 *
 * @param newData String containing one or more rows separated by newlines.
 * @param type Section to append to: "references" or "samples".
 */
fun writeToCsv(newData: String, type: String, filePath: Uri, context: Context) {
    val tempFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.csv")
    try {
        // 1. Read existing file content
        val existingLines = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(filePath)?.use { stream ->
                stream.bufferedReader().forEachLine { existingLines.add(it) }
            }
        } catch (e: Exception) {
            Log.d("CsvExportHelper", "Starting new file or file unreadable: ${e.message}")
        }

        // 2. Identify header and sections
        val incomingLines = newData.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (incomingLines.isEmpty()) return

        val incomingHeaderLine = if (incomingLines.first().contains("_r,") || incomingLines.first().startsWith("sample_id")) {
            incomingLines.first()
        } else null

        val incomingHeader = incomingHeaderLine?.split(",") ?: emptyList()
        val incomingRows = if (incomingHeaderLine != null) incomingLines.drop(1) else incomingLines

        val existingHeaderLine = if (existingLines.isNotEmpty() && existingLines[0].contains(",")) {
            existingLines[0]
        } else null
        val existingHeader = existingHeaderLine?.split(",") ?: emptyList()

        // 3. Merge Headers: Union of all columns, maintaining order
        // Order: meta columns, then cal columns, then dye columns (sorted)
        val metaCols = listOf("sample_id", "reference_name", "distance_calculation", "similarity_distance")
        val calCols = (0..3).flatMap { listOf("cal${it}_r", "cal${it}_g", "cal${it}_b") }

        val allHeaderCols = (existingHeader + incomingHeader).distinct()
        val dyeCols = allHeaderCols.filter { it !in metaCols && it !in calCols }.sorted()

        val finalHeaderCols = (metaCols.filter { it in allHeaderCols } +
                calCols.filter { it in allHeaderCols } +
                dyeCols)
        val finalHeaderLine = finalHeaderCols.joinToString(",")

        // 4. Parse Rows into Maps for re-alignment
        fun parseRow(header: List<String>, row: String): Map<String, String> {
            val tokens = row.split(",")
            return header.zip(tokens).toMap()
        }

        fun rowToCsv(header: List<String>, data: Map<String, String>): String {
            return header.map { data[it] ?: "" }.joinToString(",")
        }

        val existingContentRows = if (existingHeaderLine != null) existingLines.drop(1) else existingLines
        val blankRowIndex = existingContentRows.indexOfFirst { it.trim().isEmpty() }

        val references = mutableListOf<Map<String, String>>()
        val samples = mutableListOf<Map<String, String>>()

        if (blankRowIndex == -1) {
            references.addAll(existingContentRows.filter { it.isNotBlank() }.map { parseRow(existingHeader, it) })
        } else {
            references.addAll(existingContentRows.subList(0, blankRowIndex).filter { it.isNotBlank() }.map { parseRow(existingHeader, it) })
            samples.addAll(existingContentRows.subList(blankRowIndex + 1, existingContentRows.size).filter { it.isNotBlank() }.map { parseRow(existingHeader, it) })
        }

        val isReferences = type.equals("references", ignoreCase = true)
        val targetList = if (isReferences) references else samples

        for (rowStr in incomingRows) {
            val incomingRowMap = parseRow(incomingHeader, rowStr)
            // Duplicate check: check if a row with identical values for its present columns already exists
            val isDuplicate = targetList.any { existingMap ->
                incomingRowMap.all { (k, v) ->
                    // If both have the key, they must match. If existing doesn't have it, we consider it a match (to be updated)
                    // Actually, simpler: check if the resulting CSV strings for the final header match
                    rowToCsv(finalHeaderCols, existingMap) == rowToCsv(finalHeaderCols, incomingRowMap)
                }
            }
            if (!isDuplicate) {
                targetList.add(incomingRowMap)
            }
        }

        val finalContent = buildString {
            appendLine(finalHeaderLine)
            references.forEach { appendLine(rowToCsv(finalHeaderCols, it)) }
            appendLine() // Section separator
            samples.forEachIndexed { i, rowMap ->
                val line = rowToCsv(finalHeaderCols, rowMap)
                if (i < samples.lastIndex) appendLine(line) else append(line)
            }
        }

        // 5. Write to a temporary file first
        FileOutputStream(tempFile).use { fos ->
            fos.write(finalContent.toByteArray())
            fos.flush()
            fos.fd.sync()
        }

        // 6. Replace target file content
        context.contentResolver.openOutputStream(filePath, "rwt")?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    } catch (e: IOException) {
        AppErrorLogger.logError(context, "CSV", "writeToCsv: atomic write failed", e)
    } catch (e: Exception) {
        AppErrorLogger.logError(context, "CSV", "writeToCsv: unexpected error", e)
    } finally {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}
