package com.example.micropad

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.micropad.data.AppErrorLogger
import com.example.micropad.data.ErrorHandler

/**
 * A utility class for parsing and validating CSV files.
 *
 * @property expectedHeaders A list of expected headers in the CSV file.
 * @receiver The Composable calling this function.
 * @return Unit
 */
object CsvParser {

    private val REQUIRED_HEADERS = listOf(
        "sample_id", "reference_name",
        "distance_calculation", "similarity_score"
    )

    /**
     * @return true if the file passes all validation checks, false otherwise.
     *         Never throws — all exceptions are caught and logged.
     */
    fun parseAndValidate(
        contentResolver: ContentResolver,
        uri: Uri,
        context: Context
    ): Boolean = ErrorHandler.safeExecute(context, defaultValue = false, tag = "CsvParser") {
        val inputStream = contentResolver.openInputStream(uri)
            ?: run {
                AppErrorLogger.logError(context, "CsvParser", "Cannot open stream for $uri")
                return@safeExecute false
            }

        val lines = inputStream.bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        if (lines.isEmpty()) {
            AppErrorLogger.logError(context, "CsvParser", "CSV is empty: $uri")
            return@safeExecute false
        }

        val headers = lines[0].split(",").map { it.trim() }
        if (!headers.containsAll(REQUIRED_HEADERS)) {
            AppErrorLogger.logError(
                context, "CsvParser",
                "Missing required headers. Found: $headers, need: $REQUIRED_HEADERS"
            )
            return@safeExecute false
        }

        if (lines.size < 2) {
            AppErrorLogger.logError(context, "CsvParser", "CSV has header but no data rows: $uri")
            return@safeExecute false
        }

        true
    } ?: false
}
