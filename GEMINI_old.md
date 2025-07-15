
# GEMINI Development Guide for MrgqPdfViewer

This document provides a guide for developing the MrgqPdfViewer application with the help of the Gemini assistant. It outlines the project's architecture, key components, development conventions, and a roadmap for future features.

## 1. Project Overview

MrgqPdfViewer is an Android TV application designed for viewing PDF sheet music. It features a robust real-time collaboration mode that allows a "Conductor" to control the PDF viewing experience for multiple "Performers."

### Key Features:

*   **PDF Viewer:** High-resolution, cached rendering with single and two-page modes.
*   **File Management:** Local file browsing, sorting, and deletion.
*   **Web Upload:** A built-in web server for uploading files from a browser.
*   **Collaboration Mode:**
    *   **Conductor Role:** Leads the session, controlling page turns and file changes.
    *   **Performer Role:** Follows the Conductor's actions in real-time.
    *   **Auto-Discovery:** Uses UDP broadcasts for Performers to find Conductors on the network.
    *   **Real-time Sync:** Uses WebSockets for low-latency communication.
    *   **File Sharing:** The Conductor can share the current PDF with Performers.

### Technology Stack:

*   **Language:** Kotlin
*   **Platform:** Android TV (minSdk 21)
*   **UI:** Android Views with Material Design for TV
*   **Asynchronous Programming:** Kotlin Coroutines
*   **Database:** Room for storing user preferences and file metadata.
*   **Networking:**
    *   `NanoHTTPD`: For the embedded web server (file uploads).
    *   `OkHttp`: For WebSocket communication in collaboration mode.
    *   `Gson`: For JSON serialization/deserialization of collaboration messages.

## 2. Codebase Architecture

The application is structured around a few key components that manage the UI, state, and business logic.

### Core Components:

*   **`MainActivity.kt`**: The entry point of the app. It displays the list of PDF files, handles user interactions for sorting and file management, and initiates the collaboration setup.
*   **`PdfViewerActivity.kt`**: The screen for displaying PDF files. It manages the `PdfRenderer`, handles page navigation, and implements the logic for both Conductor and Performer roles during a collaboration session.
*   **`GlobalCollaborationManager.kt`**: A singleton that manages the lifecycle of the collaboration server and client. It holds the application-wide state of the collaboration mode, independent of any single Activity.
*   **`CollaborationServerManager.kt`**: Manages the WebSocket server for the Conductor role. It handles client connections, disconnections, and broadcasting messages.
*   **`CollaborationClientManager.kt`**: Manages the WebSocket client for the Performer role. It connects to the Conductor's server and handles incoming messages.
*   **`ConductorDiscovery.kt`**: Implements the UDP broadcast and listening logic for the auto-discovery feature.
*   **`PageCache.kt`**: A caching mechanism to pre-render and store `Bitmap` representations of PDF pages for faster page turning.
*   **`SimpleWebSocketServer.kt`**: A basic WebSocket server implementation.
*   **`FileServerManager.kt`**: Manages the HTTP server for file sharing during collaboration.

### Data Flow:

*   **UI Layer:** `MainActivity` and `PdfViewerActivity` are responsible for rendering the UI and capturing user input.
*   **State Management:** `GlobalCollaborationManager` acts as the central state holder for collaboration features.
*   **Business Logic:** The collaboration logic is split between `CollaborationServerManager` and `CollaborationClientManager`. PDF rendering logic is in `PdfViewerActivity`.
*   **Data Persistence:** `MusicRepository` and the `database` package handle all interactions with the Room database.

## 3. Development Conventions

To maintain consistency and quality, please adhere to the following conventions when modifying the codebase.

*   **Language:** All new code should be written in Kotlin.
*   **Asynchronous Operations:** Use Kotlin Coroutines for all background tasks, such as file I/O, database operations, and network requests. Use `lifecycleScope` in Activities.
*   **Dependency Injection:** While there is no formal DI framework, the project uses manual injection (e.g., passing context or repositories to classes). Follow this pattern.
*   **Logging:** Use the `android.util.Log` class for logging. Use descriptive tags to identify the source of the log message (e.g., `TAG = "PdfViewerActivity"`).
*   **UI:** Follow the existing UI patterns for TV applications. Ensure all UI elements are navigable with a D-pad.
*   **Collaboration Logic:** All changes to the collaboration state should go through the `GlobalCollaborationManager`. Avoid direct manipulation of the server or client from the UI layer.

## 4. Building and Running the App

The project uses Gradle as its build system.

*   **Build the app:**
    ```bash
    ./gradlew assembleDebug
    ```
*   **Install on a connected device:**
    ```bash
    ./gradlew installDebug
    ```
*   **Run tests:**
    ```bash
    ./gradlew test
    ```

## 5. Future Development Roadmap

Here are some potential areas for future development. When asked to implement a new feature, refer to this roadmap.

*   **High Priority:**
    *   **Implement "Last Read Page" Feature:** Remember the last viewed page for each PDF and automatically jump to it when the file is opened. This will involve extending the `UserPreference` entity and `MusicRepository`.
    *   **Improve Two-Page Mode:** Add more user-configurable options for two-page mode, such as right-to-left page order.
    *   **Enhance UI/UX:** Refine the UI for better visual appeal and usability on TV screens.

*   **Medium Priority:**
    *   **Add Zoom and Pan:** Implement zoom and pan functionality in the `PdfViewerActivity`.
    *   **Bookmark System:** Allow users to bookmark specific pages within a PDF.
    *   **Voice Control:** Integrate voice commands for page turning (e.g., "next page").

*   **Low Priority:**
    *   **Annotation Support:** Allow users to draw or add notes to the PDF. This would be a major feature requiring significant effort.
    *   **Cloud Sync:** Sync PDF files and user data across multiple devices using a cloud service.
