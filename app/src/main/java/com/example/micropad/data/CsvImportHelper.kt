package com.example.micropad.data

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Provide a UI button to launch a file picker for importing a CSV file.
 *
 * Opens Android system file picker restricted to CSV files only.
 * Returns selected file URI via callback.
 *
 * @param onFileSelected Callback to handle the selected file URI.
 * @param isHighlighted Whether the button should be highlighted (used in simulations).
 * @return A button to trigger the file picker.
 */
@Composable
fun CsvImportButton(onFileSelected: (Uri?) -> Unit, isHighlighted: Boolean = false) {

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        onFileSelected(uri)
    }

    Button(
        onClick = {
            launcher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "*/*"))
        },
        modifier = if (isHighlighted) {
            Modifier.border(4.dp, Color.Yellow, RoundedCornerShape(8.dp)).padding(4.dp)
        } else Modifier
    ) {
        Text("Import CSV")
    }
}
