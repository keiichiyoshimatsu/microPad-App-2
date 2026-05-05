# microPad-App

An Android application for the automated analysis of microfluidic Paper-based Analytical Devices (microPADs). This tool leverages OpenCV to perform image processing, color calibration, and sample classification, providing a mobile solution for point-of-care diagnostics.

## Features

- **Automated Image Processing**: Uses OpenCV to detect microPAD cards, apply perspective warping for top-down views, and identify individual dye wells (ROIs).
- **Color Calibration**: Includes a robust rebalancing algorithm that uses on-card calibration squares to account for varying lighting conditions.
- **Sample Classification**: Supports multiple classification modes, including "Whole Card" matching and "Per Color" analysis against reference baselines.
- **Flexible Data Entry**: Import reference data via camera capture, gallery upload, or CSV files.
- **ROI Management**: Manually adjust detected regions of interest or label them for specific chemical indicators.
- **Cloud Synchronization**: Integrated with Google Drive for secure backup and cross-device access to datasets and analysis history.
- **Navigation Simulator**: A built-in demonstration mode that walks users through the core application flow.
- **Error Reporting**: Integrated with Firebase Crashlytics for real-time crash and error   reporting. Users are asked for consent on first launch. No personal data or images are ever included in reports.

## Getting Started

### Prerequisites

- Android Studio Iguana (or newer)
- Android SDK 34+
- A device or emulator running Android 8.0 (API 26) or higher

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/microPad-App.git
   ```
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Build and run the app on your device.

## Project Structure

- `app/src/main/java/com/example/micropad/data/analysis`: Core OpenCV image pipeline logic.
- `app/src/main/java/com/example/micropad/data/model`: Data structures for Samples and Datasets.
- `app/src/main/java/com/example/micropad/ui`: Jetpack Compose UI components and navigation.
- `app/src/main/java/com/example/micropad/data/cloud`: Google Drive integration and background sync workers.

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Image Processing**: OpenCV for Android
- **Networking/Storage**: Google Identity Services, Google Drive API, WorkManager
- **Image Loading**: Coil

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Developed for the analysis of microfluidic Paper-based Analytical Devices.
- Built using the OpenCV open-source library.
- Error reporting powered by Firebase Crashlytics.
