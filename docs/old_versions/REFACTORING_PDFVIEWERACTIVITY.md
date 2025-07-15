# Refactoring Plan: PdfViewerActivity

## 1. Introduction & Problem Statement

The `PdfViewerActivity.kt` file has grown to over 600 lines and has accumulated a wide range of responsibilities. It currently manages:

- Android Activity lifecycle.
- UI state and ViewBinding.
- Low-level PDF rendering and page caching.
- Complex user input handling for D-pad navigation (including long press).
- Real-time collaboration logic for both Conductor and Performer roles.
- File loading and management between different PDFs.
- Display settings and user preferences.

This violates the Single Responsibility Principle (SRP), making the class difficult to read, maintain, debug, and test. Any change, no matter how small, requires understanding the entire complex context, increasing the risk of introducing bugs.

## 2. Goals of Refactoring

- **Separation of Concerns:** Decompose the monolithic `PdfViewerActivity` into smaller, focused, and more manageable classes, each with a single responsibility.
- **Improved Readability & Maintainability:** Make the codebase easier to understand and modify by isolating distinct functionalities.
- **Enhanced Testability:** Enable unit testing of individual components (e.g., input handling, PDF rendering logic) in isolation from the Android Framework.
- **Clearer Architecture:** Establish a clean, coordinator-based architecture where `PdfViewerActivity` acts as a view controller, delegating tasks to specialized handler/manager classes.

## 3. Proposed Architecture

We will refactor `PdfViewerActivity` by extracting its core responsibilities into four new, dedicated classes. `PdfViewerActivity` will be retained, but its role will be reduced to that of a **Coordinator** or **View Controller**.

**New Structure:**

```
[ PdfViewerActivity ]
      |
      |-- [ ViewerInputHandler ]   (Handles all key events)
      |
      |-- [ PdfPageManager ]       (Handles PDF rendering, caching, and display)
      |
      |-- [ ViewerFileManager ]    (Handles loading/switching PDF files)
      |
      `-- [ ViewerCollaborationManager ] (Handles all real-time collaboration logic)
```

`PdfViewerActivity` will own instances of these four helper classes. It will orchestrate the communication between them, typically using listener interfaces.

## 4. Detailed Component Roles

### A. `PdfPageManager`

This class will be the single source of truth for rendering and displaying PDF pages.

-   **Responsibilities:**
    -   Manage the `PdfRenderer` and `PageCache` instances.
    -   Handle all bitmap creation, transformation, and caching.
    -   Encapsulate the logic for single vs. two-page mode, including scaling, clipping, and padding.
    -   Provide the final `Bitmap` to be displayed.

-   **Key State to Manage:**
    -   `pdfRenderer: PdfRenderer?`
    -   `pageCache: PageCache?`
    -   `currentPageIndex: Int`
    -   `isTwoPageMode: Boolean`
    -   `displaySettings: DisplaySettings` (a data class for clipping, padding, etc.)

-   **Public API (Example):**
    ```kotlin
    class PdfPageManager(context: Context, private val repository: MusicRepository) {
        fun loadPdf(filePath: String, onComplete: (Boolean) -> Unit)
        suspend fun showPage(index: Int): Bitmap?
        fun updateDisplaySettings(settings: DisplaySettings)
        fun setTwoPageMode(isEnabled: Boolean)
        fun getPageCount(): Int
        fun close()
    }
    ```

-   **Methods to Move from `PdfViewerActivity`:**
    -   `loadPdf`, `showPage`, `renderSinglePage`, `renderTwoPages`, `combineTwoPages`, `calculateOptimalScale`, `applyDisplaySettings`, `checkAndSetTwoPageMode`, `saveDisplayModePreference`, `loadDisplaySettingsSync`, etc.

### B. `ViewerInputHandler`

This class will centralize all D-pad and key event logic.

-   **Responsibilities:**
    -   Process `onKeyDown` and `onKeyUp` events.
    -   Manage the logic for page navigation (next/previous page).
    -   Handle the display and interaction with the start/end of file navigation guides.
    -   Distinguish between short and long presses on the OK/Enter key.

-   **Key State to Manage:**
    -   `isNavigationGuideVisible: Boolean`
    -   `longPressHandler: Handler`

-   **Public API & Communication (Example):**
    ```kotlin
    interface InputListener {
        fun onNextPage()
        fun onPreviousPage()
        fun onNextFile()
        fun onPreviousFile()
        fun onShowOptionsMenu()
        fun onTogglePageInfo()
        fun onBack()
    }

    class ViewerInputHandler(private val listener: InputListener) {
        fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean
        fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean
    }
    ```

-   **Methods to Move from `PdfViewerActivity`:**
    -   All logic currently inside `onKeyDown` and `onKeyUp`.
    -   `showEndOfFileGuide`, `showStartOfFileGuide`, `hideNavigationGuide`.

### C. `ViewerFileManager`

This class will handle the process of switching between different PDF files.

-   **Responsibilities:**
    -   Maintain the list of PDF files (`filePathList`, `fileNameList`).
    -   Manage the `currentFileIndex`.
    -   Contain the logic to load the next or previous file.

-   **Public API & Communication (Example):**
    ```kotlin
    interface FileListener {
        fun onFileLoading(fileName: String)
        fun onFileLoaded(success: Boolean)
    }

    class ViewerFileManager(private val fileList: List<String>, private val nameList: List<String>, private val listener: FileListener) {
        fun loadNextFile()
        fun loadPreviousFile()
        fun loadFileByIndex(index: Int)
        fun getCurrentFileName(): String
    }
    ```

-   **Methods to Move from `PdfViewerActivity`:**
    -   `loadNextFile`, `loadPreviousFile`, `loadFile`.

### D. `ViewerCollaborationManager`

This class will isolate all collaboration-related logic from the viewer.

-   **Responsibilities:**
    -   Register and manage callbacks with the `GlobalCollaborationManager`.
    -   Handle incoming events (`onPageChangeReceived`, `onFileChangeReceived`, etc.).
    -   Broadcast actions when in Conductor mode (page changes, file changes).
    -   Handle the file download process when a Performer receives a file change event for a non-existent file.

-   **Public API & Communication (Example):**
    ```kotlin
    interface CollaborationListener {
        fun onRemotePageChange(pageIndex: Int)
        fun onRemoteFileChange(fileName: String, page: Int)
        fun onBackToList()
    }

    class ViewerCollaborationManager(private val listener: CollaborationListener) {
        fun initialize(mode: CollaborationMode)
        fun broadcastPageChange(pageIndex: Int, fileName: String)
        fun broadcastFileChange(fileName: String, page: Int)
        fun cleanup()
    }
    ```

-   **Methods to Move from `PdfViewerActivity`:**
    -   `initializeCollaboration`, `setupConductorCallbacks`, `setupPerformerCallbacks`, `handleRemotePageChange`, `handleRemoteFileChange`, `showDownloadDialog`, `downloadFileFromConductor`.

## 5. The New Role of `PdfViewerActivity`

After refactoring, `PdfViewerActivity` will be a lean coordinator. Its responsibilities will be:

1.  **Lifecycle Management:** Handle `onCreate`, `onDestroy`, etc.
2.  **ViewBinding:** Manage the `ActivityPdfViewerBinding`.
3.  **Object Instantiation:** Create and hold instances of the four new manager classes.
4.  **Coordination:** Implement the listener interfaces and delegate events to the appropriate manager. It acts as the central hub connecting the other components.

**Example of `PdfViewerActivity`'s new structure:**

```kotlin
class PdfViewerActivity : AppCompatActivity(), ViewerInputHandler.InputListener, ViewerCollaborationManager.CollaborationListener {

    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var pageManager: PdfPageManager
    private lateinit var inputHandler: ViewerInputHandler
    private lateinit var fileManager: ViewerFileManager
    private lateinit var collaborationManager: ViewerCollaborationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... binding setup ...

        // 1. Instantiate managers
        pageManager = PdfPageManager(this, /*...dependencies...*/)
        inputHandler = ViewerInputHandler(this)
        collaborationManager = ViewerCollaborationManager(this)
        // ... and so on

        // 2. Load initial file
        pageManager.loadPdf(initialFilePath) { success ->
            // ...
        }
    }

    // 3. Delegate input
    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = inputHandler.onKeyDown(keyCode, event)

    // 4. Implement listener methods to coordinate actions
    override fun onNextPage() {
        lifecycleScope.launch {
            pageManager.showPage(pageManager.currentPageIndex + 1)?.let {
                binding.pdfView.setImageBitmap(it)
                collaborationManager.broadcastPageChange(pageManager.currentPageIndex, fileManager.getCurrentFileName())
            }
        }
    }
    
    override fun onRemotePageChange(pageIndex: Int) {
        lifecycleScope.launch {
            pageManager.showPage(pageIndex)?.let {
                binding.pdfView.setImageBitmap(it)
            }
        }
    }
    // ... other listener implementations
}
```

## 6. Step-by-Step Refactoring Process

To perform this refactoring safely and incrementally:

1.  **Create New Files:** Create the empty `.kt` files for the four new classes.
2.  **Define Interfaces:** Define the `Listener` interfaces for communication between the managers and the Activity.
3.  **Refactor `PdfPageManager`:**
    -   Move all PDF rendering, caching, and display logic into `PdfPageManager`.
    -   In `PdfViewerActivity`, instantiate `PdfPageManager` and replace the old rendering calls with calls to the new manager.
4.  **Refactor `ViewerInputHandler`:**
    -   Move all `onKeyDown`/`onKeyUp` logic into `ViewerInputHandler`.
    -   Make `PdfViewerActivity` implement `ViewerInputHandler.InputListener`.
    -   Delegate key events from the Activity to `inputHandler`.
5.  **Refactor `ViewerCollaborationManager`:**
    -   Move all collaboration setup and event handling into `ViewerCollaborationManager`.
    -   Make `PdfViewerActivity` implement `ViewerCollaborationManager.CollaborationListener`.
6.  **Refactor `ViewerFileManager`:**
    -   Move file switching logic into `ViewerFileManager`.
7.  **Cleanup:** Once all logic is moved and the application is confirmed to be working, delete the now-unused private methods and properties from `PdfViewerActivity`.

This structured approach will transform `PdfViewerActivity` from a complex, monolithic component into a clean, maintainable, and well-architected part of the application.
