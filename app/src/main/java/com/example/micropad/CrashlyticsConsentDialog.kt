package com.example.micropad

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.crashlytics.FirebaseCrashlytics

@Composable
fun CrashlyticsConsentDialog() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("micropad_prefs", Context.MODE_PRIVATE)
    var showDialog by remember {
        mutableStateOf(!prefs.getBoolean("crashlytics_consent", false))
    }

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Help Improve microPAD?") },
        text = {
            Text(
                "If the app encounters an error, would you like to automatically " +
                        "send a crash report to the developer? This helps us fix bugs faster. " +
                        "No personal data or images are ever included."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putBoolean("crashlytics_consent", true)
                    .putBoolean("crashlytics_enabled", true)
                    .apply()
                FirebaseCrashlytics.getInstance()
                    .setCrashlyticsCollectionEnabled(true)
                showDialog = false
            }) { Text("Yes, send reports") }
        },
        dismissButton = {
            TextButton(onClick = {
                prefs.edit()
                    .putBoolean("crashlytics_consent", true)
                    .putBoolean("crashlytics_enabled", false)
                    .apply()
                FirebaseCrashlytics.getInstance()
                    .setCrashlyticsCollectionEnabled(false)
                showDialog = false
            }) { Text("No thanks") }
        }
    )
}