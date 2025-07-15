### **Project Analysis and Improvement Plan: MrgqPdfViewer**

This report outlines two critical areas for improvement in the MrgqPdfViewer application: the storage model and network encryption. Addressing these issues will significantly enhance the application's security, stability, and long-term viability.

### **Part 1: Storage Model Modernization**

#### **Problem Definition**

The application currently uses a deprecated and insecure storage model that relies on broad access to the device's shared storage. This presents several significant problems:

1.  **Severe Security Risk:** The use of `MANAGE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage="true"` grants the application access to all user files on shared storage, not just the PDFs it needs. This violates the principle of least privilege and creates a large attack surface. If the app were ever compromised, it could potentially access, modify, or delete any of the user's personal files.

2.  **Future Incompatibility:** The `requestLegacyExternalStorage` flag is a temporary stop-gap measure that only works for apps targeting Android 10 (API 29) and below. While the app currently targets API 30, this legacy model is actively being phased out. Future Android updates or requirements from app stores will inevitably cause this approach to fail, rendering the app unusable.

3.  **Unreliability:** The application relies on a hardcoded path (`/storage/emulated/0/Download/`). This path is not guaranteed to be present, writable, or consistent across all Android devices from different manufacturers, leading to potential crashes and a poor user experience.

**Evidence from Code:**
*   `AndroidManifest.xml`: Contains `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" ... />`
*   `AndroidManifest.xml`: Contains `android:requestLegacyExternalStorage="true"` in the `<application>` tag.
*   `README.md`: Explicitly states that it uses the `/storage/emulated/0/Download/` directory.

#### **Proposed Solution: Adopt the Storage Access Framework (SAF)**

The correct and modern approach is to use the Storage Access Framework (SAF). SAF allows the user to grant the application access to a specific directory of their choosing, without giving the app broad, intrusive permissions.

Here is a detailed plan for migration:

**Step 1: Remove Legacy Permissions and Flags**

First, modify `app/src/main/AndroidManifest.xml` to remove the outdated permissions and flags.

*   **Remove:**
    ```xml
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />
    ```
*   **Remove the attribute `android:requestLegacyExternalStorage="true"`** from the `<application>` tag.

**Step 2: Implement Directory Selection**

In the `MainActivity` or a new setup screen, prompt the user to select the directory where their PDFs are stored.

*   Use the `ACTION_OPEN_DOCUMENT_TREE` intent to launch the system's directory picker.
*   When the user selects a directory, the app will receive a URI. This URI is a secure, long-lasting token that grants access to that directory and its contents.
*   Use `contentResolver.takePersistableUriPermission()` to save this access grant across device reboots.
*   Store the directory URI string in `SharedPreferences` so the app can access it every time it starts, without asking the user again.

**Step 3: Refactor File Access Logic**

Replace all code that uses `java.io.File` for direct path access with methods that use the persisted URI and Android's `ContentResolver`.

*   **Old Code (Example):**
    ```kotlin
    val downloadDir = File("/storage/emulated/0/Download/")
    val pdfFiles = downloadDir.listFiles { file -> file.extension == "pdf" }
    ```
*   **New Code (Conceptual):**
    ```kotlin
    // Retrieve the persisted URI from SharedPreferences
    val directoryUri = Uri.parse(savedUriString)
    
    // Get a DocumentFile representing the chosen directory
    val directory = DocumentFile.fromTreeUri(context, directoryUri)
    
    // List files using the DocumentFile interface
    val pdfFiles = directory?.listFiles()?.filter { it.name?.endsWith(".pdf", ignoreCase = true) == true }
    
    // To read a file, get its URI and open an InputStream
    pdfFiles?.forEach { documentFile ->
        val inputStream = context.contentResolver.openInputStream(documentFile.uri)
        // ... pass this stream to the PDF renderer
    }
    ```

**Step 4: Update File Upload Logic**

The web server's file upload functionality must also be updated to use SAF.

*   Instead of writing to a hardcoded path, use the `DocumentFile` representing the user-chosen directory to create a new file.
*   **New Code (Conceptual):**
    ```kotlin
    val directory = DocumentFile.fromTreeUri(context, directoryUri)
    val newFile = directory?.createFile("application/pdf", "newly_uploaded_score.pdf")
    
    // Get an OutputStream to write the uploaded data
    newFile?.uri?.let { fileUri ->
        val outputStream = context.contentResolver.openOutputStream(fileUri)
        // ... write the bytes from the HTTP request to this stream
    }
    ```

---

### **Part 2: Implementing Network Encryption**

#### **Problem Definition**

All network communication for the collaboration and file transfer features is currently unencrypted (cleartext). This includes WebSocket messages for page synchronization and HTTP transfers of entire PDF files.

1.  **Confidentiality Breach:** Anyone on the same local network (e.g., a public Wi-Fi at a café or concert hall) can use packet-sniffing tools to intercept and read the contents of the PDF files being transferred. They can also see the collaboration commands.

2.  **Data Integrity Risk:** An attacker on the network could perform a Man-in-the-Middle (MITM) attack to modify data in transit. They could alter the content of a PDF file or inject malicious commands into the collaboration session without the user's knowledge.

**Evidence from Code:**
*   `app/src/main/res/xml/network_security_config.xml`: The configuration `<base-config cleartextTrafficPermitted="true" />` explicitly disables Android's default network security protections, allowing unencrypted traffic to all destinations.
*   The use of `http://` and `ws://` protocols is implied by the lack of any SSL/TLS implementation code.

#### **Proposed Solution: Implement TLS/SSL Encryption**

All network traffic must be encrypted using TLS/SSL. For a local network application, a self-signed certificate is a standard and effective solution.

**Step 1: Generate and Bundle a Self-Signed Certificate**

A self-signed certificate and a corresponding keystore need to be generated and included in the app.

*   Use a tool like `keytool` (included with the Java Development Kit) to create a BKS or JKS keystore file (e.g., `keystore.bks`).
*   Place this keystore file in the `app/src/main/res/raw/` directory so it can be bundled with the application.

**Step 2: Secure the NanoHTTPD Server (HTTPS)**

Configure the `FileServerManager` to use the bundled keystore to serve content over HTTPS.

*   **Change Protocol:** The server will now run on `https://`.
*   **Implementation (Conceptual):**
    ```kotlin
    // In your WebServerManager/FileServerManager
    val keyStore = KeyStore.getInstance("BKS")
    val inputStream = context.resources.openRawResource(R.raw.keystore)
    keyStore.load(inputStream, "your_keystore_password".toCharArray())
    
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keyManagerFactory.init(keyStore, "your_key_password".toCharArray())
    
    // Apply the secure configuration to the NanoHTTPD instance
    nanoHttpdInstance.makeSecure(keyManagerFactory, null)
    ```

**Step 3: Secure the WebSocket Server (WSS)**

Similarly, the `CollaborationServerManager` (which uses OkHttp internally for its WebSocket server) must be configured to use TLS.

*   **Change Protocol:** The WebSocket connection will now use `wss://`.
*   **Implementation (Conceptual):**
    *   **Server-Side:** The WebSocket server needs to be initialized with an `SSLSocketFactory` created from the same keystore.
    *   **Client-Side:** The `CollaborationClientManager` on the "Performer" devices must be configured to trust this self-signed certificate. This involves creating a custom `TrustManager` that validates the server's certificate against the one bundled in the app. The client's `OkHttpClient` must be built with this custom `SSLSocketFactory` and `TrustManager`.

**Step 4: Enforce Encrypted Traffic**

Finally, update `app/src/main/res/xml/network_security_config.xml` to disable cleartext traffic, ensuring that only encrypted connections are possible.

*   **Change:**
    ```xml
    <base-config cleartextTrafficPermitted="false" />
    ```
    And remove the local domain overrides unless absolutely necessary for a specific, non-secure legacy purpose.

---

By implementing these solutions, MrgqPdfViewer will become a more secure, robust, and modern Android application, ensuring user data is protected and the app remains functional for years to come.
