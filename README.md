# BART Real-Time Android Widget

A Jetpack Glance-based Android widget that displays real-time BART (Bay Area Rapid Transit) departure information right on your home screen. It supports dynamic resizing, dark theme, and push-based updates via Firebase Cloud Messaging (FCM) and Turso (libSQL).

## Prerequisites

Before building the Android app, you need to set up the backend server, a Turso database, and a Firebase project.

### 1. Set up the Backend
The widget relies on a backend service to fetch live BART API data, write it to a database, and dispatch push notifications via FCM.

1. Clone the backend repository: [rjawesome/bartwidget-backend](https://github.com/rjawesome/bartwidget-backend)
2. Follow the instructions in the backend repository's README to configure your BART API keys, Firebase Admin SDK, and Turso Database credentials.
3. Start the backend server so it can begin pushing updates.

### 2. Configure Turso Database (Read-Only)
The Android app fetches its initial state directly from a Turso database using the embedded libSQL client.

1. In your Turso CLI, generate a **read-only** token for your database:
   ```bash
   turso db tokens create <your-database-name> --read-only
   ```
2. Get your database URL (e.g., `libsql://<your-database-name>.turso.io`).
3. Open `app/src/main/java/com/rohanj/bartwidget/MainActivity.kt` in this Android project.
4. Update the global constants with your URL and token:
   ```kotlin
   const val TURSO_URL = "libsql://<your-database-name>.turso.io"
   const val TURSO_TOKEN = "your_read_only_token_here"
   ```
   *(Note: Because this token is bundled in the app, it is crucial that it is strictly restricted to read-only access).*

### 3. Add Firebase Configuration
The app uses Firebase Cloud Messaging topics to receive real-time push updates for specific stations.

1. Go to the Firebase Console and create or open your project.
2. Add an Android app to the project with the package name: `com.rohanj.bartwidget`.
3. Download the `google-services.json` file.
4. Place the `google-services.json` file into the `app/` directory of this Android project (`bartwidget/app/google-services.json`).

### 4. Build and Run (Gradle)

1. Open the project in **Android Studio**.
2. Click **Sync Project with Gradle Files** (the elephant icon in the toolbar) to ensure all dependencies, including `tech.turso.libsql` and `androidx.glance`, are downloaded.
3. Select your device or emulator and click **Run** (Shift + F10).

## Usage

1. Long-press on your Android home screen and open the **Widgets** menu.
2. Find **BART Widget** and drag it onto your home screen.
3. Tap the **Select a Station** text on the widget.
4. The configuration app will open.
5. Search for and select your desired BART station.
6. The widget will automatically subscribe to the FCM topic for that station and pull the latest data from the Turso database!

## Resizing
The widget uses `SizeMode.Exact`. You can stretch it horizontally or vertically on your home screen, and the UI will dynamically hide or show train lines and departures to fit the available space.