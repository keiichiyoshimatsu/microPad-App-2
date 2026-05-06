package com.example.micropad.data.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.micropad.data.AppErrorLogger
import com.example.micropad.data.DatasetModel
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Google Drive upload manager using Google Identity Services AuthorizationClient.
 *
 * This replaces the old SAF/OpenDocumentTree flow with OAuth-based Drive access.
 */
object CloudSyncManager {
    private const val TAG = "CloudSyncManager"

    private const val PREFS = "cloud_sync_prefs"
    private const val KEY_WEEKLY_ERROR_UPLOAD = "weekly_error_upload"
    private const val KEY_INSTALLATION_ID = "installation_id"
    const val WEEKLY_ERROR_WORK_NAME = "weekly_error_upload"

    // Minimal Drive scope recommended for app-created / app-opened files.
    private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    private val requestedScopes: List<Scope> = listOf(Scope(DRIVE_FILE_SCOPE))

    private val authorizationRequest: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

    /**
     * Starts Drive authorization.
     *
     * If access was already granted, [onAuthorized] is called immediately with an access token.
     * If user interaction is needed, the provided [launcher] is used.
     */
    fun startDriveAuthorization(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onAuthorized: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Identity.getAuthorizationClient(activity)
            .authorize(authorizationRequest)
            .addOnSuccessListener { authorizationResult ->
                val accessToken = authorizationResult.accessToken
                if (!accessToken.isNullOrBlank()) {
                    onAuthorized(accessToken)
                    return@addOnSuccessListener
                }

                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent
                    if (pendingIntent != null) {
                        launcher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } else {
                        onError(IllegalStateException("Authorization requires resolution but pendingIntent was null."))
                    }
                } else {
                    onError(IllegalStateException("Authorization succeeded but no access token was returned."))
                }
            }
            .addOnFailureListener(onError)
    }

    /**
     * Completes Drive authorization after the authorization UI returns.
     */
    fun finishDriveAuthorization(
        context: Context,
        data: Intent?,
        onAuthorized: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)

            val accessToken = result.accessToken
            if (accessToken.isNullOrBlank()) {
                onError(IllegalStateException("Authorization completed but access token was missing."))
                return
            }

            onAuthorized(accessToken)
        } catch (e: ApiException) {
            onError(e)
        }
    }

    /**
     * Optional cleanup if a token becomes invalid.
     */
    fun clearInvalidAccessToken(
        activity: Activity,
        invalidAccessToken: String,
        onDone: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        val request = com.google.android.gms.auth.api.identity.ClearTokenRequest.builder()
            .setToken(invalidAccessToken)
            .build()

        Identity.getAuthorizationClient(activity)
            .clearToken(request)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun isWeeklyErrorUploadEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_WEEKLY_ERROR_UPLOAD, false)
    }

    fun setWeeklyErrorUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_WEEKLY_ERROR_UPLOAD, enabled) }
        if (enabled) {
            scheduleWeeklyErrorUploads(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_ERROR_WORK_NAME)
        }
    }

    fun ensureScheduledWorkMatchesPreference(context: Context) {
        if (isWeeklyErrorUploadEnabled(context)) {
            scheduleWeeklyErrorUploads(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WEEKLY_ERROR_WORK_NAME)
        }
    }

    fun scheduleWeeklyErrorUploads(context: Context) {
        val request = PeriodicWorkRequestBuilder<FirebaseErrorLogWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WEEKLY_ERROR_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WEEKLY_ERROR_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun uploadDatasetArtifacts(
        context: Context,
        viewModel: DatasetModel,
        accessToken: String
    ): CloudUploadSummary {
        var csvCount = 0
        var imageCount = 0

        val resolver = context.contentResolver
        val timestamp = timestampForFileNames()

        fun uploadCsvTextIfNotBlank(
            text: String,
            fileName: String
        ): Boolean {
            if (text.isBlank()) return false
            return uploadTextToDrive(
                accessToken = accessToken,
                text = text,
                fileName = fileName,
                mimeType = "text/csv"
            )
        }

        fun mimeToExtension(mimeType: String?): String {
            return when (mimeType?.lowercase(Locale.US)) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                else -> "jpg"
            }
        }

        fun uploadUri(
            uri: Uri,
            fileName: String,
            fallbackMimeType: String
        ): Boolean {
            return runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    uploadStreamToDrive(
                        accessToken = accessToken,
                        inputBytes = input.readBytes(),
                        fileName = fileName,
                        mimeType = resolver.getType(uri) ?: fallbackMimeType
                    )
                } ?: false
            }.getOrElse { e ->
                AppErrorLogger.logError(
                    context,
                    "CloudSync",
                    "Failed to upload uri: $uri",
                    e
                )
                false
            }
        }

        // 1) Upload the originally imported CSV file, if present.
        viewModel.importedFileUri?.let { importedUri ->
            val importedName = viewModel.importedFileName
                .takeIf { it.isNotBlank() }
                ?: "imported_reference_$timestamp.csv"

            if (uploadUri(importedUri, importedName, "text/csv")) {
                csvCount++
            }
        }

        // 2) Upload generated CSV exports only when they actually contain data.
        if (viewModel.referenceDataset != null) {
            val referencesCsv = viewModel.toCsvString(
                includeHeader = true,
                datasetChoice = "references"
            )
            if (uploadCsvTextIfNotBlank(referencesCsv, "references_$timestamp.csv")) {
                csvCount++
            }
        }

        if (viewModel.newDataset != null) {
            val samplesCsv = viewModel.toCsvString(
                includeHeader = true,
                datasetChoice = "sample"
            )
            if (uploadCsvTextIfNotBlank(samplesCsv, "samples_$timestamp.csv")) {
                csvCount++
            }
        }

        if (viewModel.referenceDataset != null && viewModel.newDataset != null) {
            val referencesCsv = viewModel.toCsvString(
                includeHeader = true,
                datasetChoice = "references"
            )
            val sampleRows = viewModel.toCsvString(
                includeHeader = false,
                datasetChoice = "sample"
            )
            val combinedCsv = listOf(referencesCsv, sampleRows)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            if (uploadCsvTextIfNotBlank(combinedCsv, "combined_$timestamp.csv")) {
                csvCount++
            }
        }

        // 3) Upload images from the lists the UI actually uses.
        // Prefer pendingReferences/pendingSamples since those drive the home screen counts.
        // Fall back to referenceImageUris/sampleImageUris if needed.
        val referenceUris: List<Uri> =
            if (viewModel.pendingReferences.isNotEmpty()) {
                viewModel.pendingReferences.map { it.uri }
            } else {
                viewModel.referenceImageUris
            }

        val sampleUris: List<Uri> =
            if (viewModel.pendingSamples.isNotEmpty()) {
                viewModel.pendingSamples.map { it.uri }
            } else {
                viewModel.sampleImageUris
            }

        // Deduplicate by URI string in case the same images are represented in both places.
        val distinctReferenceUris = referenceUris.distinctBy { it.toString() }
        val distinctSampleUris = sampleUris.distinctBy { it.toString() }

        distinctReferenceUris.forEachIndexed { index, uri ->
            val mimeType = resolver.getType(uri)
            val extension = mimeToExtension(mimeType)
            val ok = uploadUri(
                uri = uri,
                fileName = "reference_${index + 1}_$timestamp.$extension",
                fallbackMimeType = mimeType ?: "image/jpeg"
            )
            if (ok) imageCount++
        }

        distinctSampleUris.forEachIndexed { index, uri ->
            val mimeType = resolver.getType(uri)
            val extension = mimeToExtension(mimeType)
            val ok = uploadUri(
                uri = uri,
                fileName = "sample_${index + 1}_$timestamp.$extension",
                fallbackMimeType = mimeType ?: "image/jpeg"
            )
            if (ok) imageCount++
        }

        return CloudUploadSummary(
            csvCount = csvCount,
            imageCount = imageCount
        )
    }

    fun uploadCurrentErrorLogToCloud(
        context: Context,
        accessToken: String
    ): Boolean {
        val file = AppErrorLogger.getLogFile(context)
        if (!file.exists() || file.length() == 0L) return false

        val name = "error_log_${timestampForFileNames()}.txt"
        return runCatching {
            uploadFileToDrive(accessToken, file, name, "text/plain")
        }.getOrElse { e ->
            AppErrorLogger.logError(context, "CloudSync", "Failed to upload error log", e)
            false
        }
    }

    fun getInstallationId(context: Context): String {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_INSTALLATION_ID, created) }
        return created
    }

    private fun uploadTextToDrive(
        accessToken: String,
        text: String,
        fileName: String,
        mimeType: String
    ): Boolean {
        return uploadStreamToDrive(
            accessToken = accessToken,
            inputBytes = text.toByteArray(Charsets.UTF_8),
            fileName = fileName,
            mimeType = mimeType
        )
    }

    private fun uploadFileToDrive(
        accessToken: String,
        file: File,
        fileName: String = file.name,
        mimeType: String = guessMimeType(fileName)
    ): Boolean {
        if (!file.exists()) return false
        return uploadStreamToDrive(
            accessToken = accessToken,
            inputBytes = file.readBytes(),
            fileName = fileName,
            mimeType = mimeType
        )
    }

    /**
     * Uploads a new file into the user's Drive using multipart upload.
     *
     * With drive.file, files created by the app are accessible to the app later.
     */
    private fun uploadStreamToDrive(
        accessToken: String,
        inputBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): Boolean {
        val boundary = "micropad-${System.currentTimeMillis()}"
        val safeName = sanitizeFileName(fileName)

        val metadataJson = JSONObject()
            .put("name", safeName)
            .toString()

        val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }

        return try {
            BufferedOutputStream(connection.outputStream).use { out ->
                val writer = OutputStreamWriter(out, Charsets.UTF_8)

                writer.write("--$boundary\r\n")
                writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                writer.write(metadataJson)
                writer.write("\r\n")

                writer.write("--$boundary\r\n")
                writer.write("Content-Type: $mimeType\r\n\r\n")
                writer.flush()

                out.write(inputBytes)
                out.flush()

                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
            }

            val code = connection.responseCode
            if (code in 200..299) {
                true
            } else {
                val errorText = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                Log.e(TAG, "Drive upload failed: HTTP $code $errorText")
                false
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun timestampForFileNames(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun guessMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

data class CloudUploadSummary(
    val csvCount: Int,
    val imageCount: Int
)