# Dev Log: File List Sorting Fix in Web Server

**Date:** 2025-07-28

## 1. Problem Summary

A critical bug was identified in the web server component responsible for serving files. When new files are uploaded, particularly when they are inserted into the middle of an existing file list, the file list displayed to the user becomes inconsistent. Clicking on a file name in the list can lead to a different file being opened. This points to a fundamental issue in how the file list is maintained and presented.

## 2. Root Cause Analysis

The root cause of this issue was traced to the `FileServerManager.kt` class.

- **Unordered Data Structure:** The `availableFiles` property is a `MutableMap<String, String>`. In Kotlin, a standard `MutableMap` does not guarantee any specific order for its elements. The iteration order can change unpredictably as elements are added or removed.

- **Inconsistent List Generation:** The `handleListFiles` function directly iterates over `availableFiles.entries` to generate the JSON list of files for the client.
  ```kotlin
  // Problematic code in handleListFiles()
  availableFiles.entries.forEachIndexed { index, (fileName, filePath) ->
      // ... builds JSON object
  }
  ```
  Because the iteration order is not guaranteed, the list of files sent to the client can have a different order each time it's requested, especially after the contents of the map have changed. The client-side UI renders the list in the order it receives, leading to the mismatch reported by the user.

## 3. Solution Plan

To resolve this bug and ensure a consistent and predictable user experience, the file list must be sorted before being sent to the client. The most intuitive sorting method is alphabetical by filename.

The plan is to modify the `handleListFiles` function in `FileServerManager.kt` to perform this sorting.

### Implementation Steps

1.  **Retrieve and Sort:** Before building the JSON string, retrieve the entries from the `availableFiles` map and sort them by the key (which is the filename).
2.  **Build JSON from Sorted List:** Use the newly sorted list to construct the JSON array.

This will ensure that the file list presented to the user is always in a stable, alphabetical order, regardless of the internal storage order in the `MutableMap` or the order in which files were added.

### Proposed Code Change

The implementation will involve changing the `handleListFiles` function as follows:

**Old Code:**
```kotlin
private fun handleListFiles(): Response {
    val json = buildString {
        append("{\"files\":[")
        availableFiles.entries.forEachIndexed { index, (fileName, filePath) ->
            if (index > 0) append(",")
            val file = File(filePath)
            append("{")
            append("\"name\":\"$fileName\",")
            append("\"size\":${file.length()},")
            append("\"lastModified\":${file.lastModified()}")
            append("}")
        }
        append("]}")
    }
    // ...
}
```

**New Code:**
```kotlin
private fun handleListFiles(): Response {
    val json = buildString {
        append("{\"files\":[")
        // Sort the entries by filename (the key) before iterating
        val sortedFiles = availableFiles.entries.sortedBy { it.key }
        sortedFiles.forEachIndexed { index, (fileName, filePath) ->
            if (index > 0) append(",")
            val file = File(filePath)
            append("{")
            append("\"name\":\"$fileName\",")
            append("\"size\":${file.length()},")
            append("\"lastModified\":${file.lastModified()}")
            append("}")
        }
        append("]}")
    }
    // ...
}
```

This change is minimal but effectively resolves the inconsistency, ensuring the file list is always stable and reliable.

