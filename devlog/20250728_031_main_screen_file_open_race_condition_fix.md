# Dev Log: Fixing Main Screen File Opening Race Condition

**Date:** 2025-07-28

## 1. Problem Definition

Based on a more detailed analysis, the root cause of the "wrong file opening" issue is not on the server side, but within the Android client's `MainActivity`. When a user clicks a file in the `RecyclerView`, a race condition can occur if the file list is being updated simultaneously (e.g., after a file upload or a sort order change). This results in the `PdfViewerActivity` being launched with an incorrect file index, causing the wrong PDF to be displayed.

## 2. Root Cause Analysis

The core of the problem lies in the `openPdfFile` function, which trusts the `position` parameter passed from the `RecyclerView.Adapter`'s click listener. 

```kotlin
// The position parameter is the index in the list AT THE MOMENT OF THE CLICK.
onItemClick = { pdfFile, position ->
    openPdfFile(pdfFile, position)
}

// The list can change between the click and this function's execution.
private fun openPdfFile(pdfFile: PdfFile, position: Int) {
    // ...
    // This 'position' can be stale if the list has been updated.
    intent.putExtra("current_index", position)
    // ...
}
```

Because `ListAdapter` uses `DiffUtil` on a background thread to calculate list updates, there's a window of time where the `position` from the click event no longer matches the actual position of the clicked item in the newly updated list. Relying on this transient `position` is inherently unsafe in a dynamic list.

## 3. Solution Plan

To fix this, we will stop relying on the `position` and instead use a stable, unique identifier for each file—its path. We will also improve `RecyclerView`'s efficiency by implementing stable IDs.

### Phase 1: Implement Stable IDs in `PdfFileAdapter.kt`

This helps `RecyclerView` better understand and handle changes, moves, and animations for specific items.

**Action:** Modify `PdfFileAdapter.kt`.

1.  **Enable Stable IDs:** In the adapter's `init` block, add `setHasStableIds(true)`.
2.  **Provide Unique IDs:** Override the `getItemId` function to return a unique and stable ID for each item. The file's path is a perfect candidate, and its `hashCode` can be converted to a `Long`.

**Code Snippet for `PdfFileAdapter.kt`:**
```kotlin
class PdfFileAdapter(...) : ListAdapter<PdfFile, ...>(..._DIFF_CALLBACK) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        // Use the file's path to generate a stable, unique ID.
        return getItem(position).path.hashCode().toLong()
    }

    // ... rest of the adapter
}
```

### Phase 2: Use Stable Identifier in `MainActivity.kt`

This is the primary fix. We will modify `openPdfFile` to find the *current* index of the clicked file just before launching the viewer, ensuring the index is always correct relative to the list being passed.

**Action:** Modify the `openPdfFile` function in `MainActivity.kt`.

1.  **Ignore `position`:** The `position` parameter from the click listener will no longer be used to determine the index.
2.  **Find Real-time Index:** Use the passed `pdfFile` object's unique `path` to find its current index in the adapter's most up-to-date list (`pdfAdapter.currentList`).
3.  **Pass Correct Index:** Use this newly found, reliable index in the `Intent` extras.
4.  **Add Safety Check:** If the index is not found (`-1`), it means the file was deleted from the list between the click and this execution. Show a `Toast` message and abort opening the file.

**Code Snippet for `MainActivity.kt`:**
```kotlin
// The 'position' parameter is kept for the lambda signature but is no longer used inside.
private fun openPdfFile(pdfFile: PdfFile, position: Int) {
    val currentPdfFiles = pdfAdapter.currentList
    
    // Use the stable file path to find the ACTUAL current index.
    // This is the crucial fix.
    val actualIndex = currentPdfFiles.indexOfFirst { it.path == pdfFile.path }

    // Safety check: if file was deleted in the meantime, abort.
    if (actualIndex == -1) {
        Toast.makeText(this, getString(R.string.error_file_not_found_or_moved), Toast.LENGTH_SHORT).show()
        return
    }

    val filePathList = currentPdfFiles.map { it.path }
    val fileNameList = currentPdfFiles.map { it.name }

    val intent = Intent(this, PdfViewerActivity::class.java).apply {
        // Use the reliable, just-in-time calculated index.
        putExtra(PdfViewerActivity.EXTRA_CURRENT_INDEX, actualIndex)
        putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_PATH_LIST, ArrayList(filePathList))
        putStringArrayListExtra(PdfViewerActivity.EXTRA_FILE_NAME_LIST, ArrayList(fileNameList))
        // Add any other extras as needed
    }
    startActivity(intent)
}
```

## 4. Expected Outcome

After implementing these changes, clicking a file in the main screen will consistently open the correct file, even during rapid list updates caused by file uploads, deletions, or sorting changes. The application's file navigation will be robust and free of this race condition.
