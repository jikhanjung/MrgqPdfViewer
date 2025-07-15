# Action Plan: Ensemble Mode Security Enhancement

## 1. Objective

This document provides a step-by-step action plan to implement WSS (WebSocket over TLS) for the real-time communication channel in Ensemble Mode. The goal is to secure the connection between the Conductor (server) and Performers (clients) on a local network by using a self-signed certificate, as outlined in the `Security_Strategy.md`.

This will prevent eavesdropping and Man-in-the-Middle (MITM) attacks within the local network.

## 2. Technical Strategy Overview

1.  **Generate a Self-Signed Certificate:** Create a BKS Keystore (`keystore.bks`) for the server and export its public certificate (`mycert.crt`).
2.  **Embed Assets in App:** Add both the keystore and the public certificate to the Android application's resources.
3.  **Configure Server (Conductor):** Modify `CollaborationServerManager.kt` and `SimpleWebSocketServer.kt` to use the embedded keystore to create a secure WSS server instead of a plaintext WS server.
4.  **Configure Client (Performer):** Use a `network_security_config.xml` file to instruct the Performer app to trust the embedded self-signed public certificate, allowing for a seamless and secure connection without browser-style warnings.

---

## 3. Detailed Action Plan

### Step 1: Generate Security Assets

We will use `keytool` (included with the Java Development Kit) to generate the necessary files. Execute these commands in your terminal.

**A. Create the BKS Keystore:**
This keystore will be used by the Conductor's server.

```bash
keytool -genkey -alias scoremate_local -keystore keystore.bks \
  -storetype BKS -keyalg RSA -keysize 2048 -validity 3650 \
  -provider org.bouncycastle.jce.provider.BouncyCastleProvider \
  -providerpath /path/to/bcprov-jdk15on-170.jar
```

*   You will be prompted for a password. **Use the same password for the keystore and the key.** Remember this password; it will be needed in the app code.
*   You will need the Bouncy Castle provider JAR. If you don't have it, you can download it or use a different `storetype` like `PKCS12` and adapt the server-side code accordingly.

**B. Export the Public Certificate:**
This `.crt` file will be used by the Performer client to verify the server's identity.

```bash
keytool -exportcert -alias scoremate_local -file mycert.crt -keystore keystore.bks
```

*   Enter the keystore password when prompted.

### Step 2: Add Assets to the Android Project

1.  Create the `raw` resource directory if it doesn't exist: `app/src/main/res/raw`.
2.  Copy the generated files into this directory:
    *   `keystore.bks` → `app/src/main/res/raw/keystore.bks`
    *   `mycert.crt` → `app/src/main/res/raw/mycert.crt`

### Step 3: Create and Configure Network Security Profile

1.  Create a new XML file: `app/src/main/res/xml/network_security_config.xml`.
2.  Add the following content to the file. This configuration tells the app to trust `mycert.crt` for all domains (which is safe here, as it only applies to this app's connections).

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <base-config cleartextTrafficPermitted="false">
            <trust-anchors>
                <!-- Trust the self-signed certificate for WSS -->
                <certificates src="@raw/mycert" />
                <!-- Trust default system certificates -->
                <certificates src="system" />
            </trust-anchors>
        </base-config>
    </network-security-config>
    ```

3.  Link this configuration in the `AndroidManifest.xml` by adding the `android:networkSecurityConfig` attribute to the `<application>` tag:

    ```xml
    <application
        ...
        android:networkSecurityConfig="@xml/network_security_config">
        ...
    </application>
    ```

### Step 4: Update Server Logic for WSS (Conductor)

The `SimpleWebSocketServer.kt` (which likely uses `NanoHTTPD`) needs to be configured to use SSL.

**File to Modify:** `app/src/main/java/com/mrgq/pdfviewer/SimpleWebSocketServer.kt`

-   The `NanoHTTPD` instance needs to be made secure. This typically involves calling a `makeSecure()` method and providing it with an `SSLServerSocketFactory`.
-   The factory will be created using the `keystore.bks` file and the password you defined in Step 1.

**Conceptual Code Changes in `SimpleWebSocketServer.kt` or `CollaborationServerManager.kt` (where the server is started):**

```kotlin
// In the server starting logic (e.g., inside CollaborationServerManager.startServer)

// 1. Get the context to access resources
// This might require passing the ApplicationContext into the manager

// 2. Load the keystore
val keyStore = KeyStore.getInstance("BKS")
val keyStoreStream = context.resources.openRawResource(R.raw.keystore)
val password = "your_secure_password".toCharArray() // Use the password from Step 1

keyStore.load(keyStoreStream, password)

// 3. Create KeyManagerFactory
val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
keyManagerFactory.init(keyStore, password)

// 4. Create SSLContext
val sslContext = SSLContext.getInstance("TLS")
sslContext.init(keyManagerFactory.keyManagers, null, null)

// 5. Get the ServerSocketFactory
val serverSocketFactory = sslContext.serverSocketFactory

// 6. Apply to NanoHTTPD instance
// This depends on the exact implementation, but it will look something like this:
// nanoHttpdInstance.setServerSocketFactory(serverSocketFactory)
// OR, if it's a constructor parameter, pass it there.

// The SimpleWebSocketServer needs to be adapted to accept and use this factory.
```

### Step 5: Update Client Logic for WSS (Performer)

This is the simplest change, as the `network_security_config.xml` handles the trust mechanism.

**File to Modify:** `app/src/main/java/com/mrgq/pdfviewer/CollaborationClientManager.kt`

-   The connection URL must be changed from `ws://` to `wss://`.

**Code Change:**

```kotlin
// In CollaborationClientManager.connectToConductor

// Change this line:
// val request = Request.Builder().url("ws://$ipAddress:$port").build()

// To this:
val request = Request.Builder().url("wss://$ipAddress:$port").build()
```

---

## 4. Summary of File Changes

-   **New Files:**
    -   `app/src/main/res/raw/keystore.bks`
    -   `app/src/main/res/raw/mycert.crt`
    -   `app/src/main/res/xml/network_security_config.xml`
-   **Modified Files:**
    -   `app/src/main/AndroidManifest.xml`: To link the network security config.
    -   `app/src/main/java/com/mrgq/pdfviewer/SimpleWebSocketServer.kt`: To implement the SSL/TLS server logic.
    -   `app/src/main/java/com/mrgq/pdfviewer/CollaborationServerManager.kt`: To provide the necessary context and password to the server.
    -   `app/src/main/java/com/mrgq/pdfviewer/CollaborationClientManager.kt`: To change the connection scheme from `ws` to `wss`.

## 5. Verification and Testing

1.  **Functionality Test:** After implementing all changes, run the app. Start Conductor mode on one device and Performer mode on another. The connection must succeed.
2.  **Security Test:** Use a network analysis tool (e.g., Wireshark on a computer connected to the same Wi-Fi) to monitor the traffic between the two devices.
    -   **Confirm:** You should see a TLS handshake followed by encrypted "Application Data" packets.
    -   **Verify:** You should NOT see any plaintext "WebSocket" protocol packets.
3.  **Negative Test:** Temporarily remove the `<certificates src="@raw/mycert" />` line from the network config and re-run. The Performer's connection attempt should now fail with an SSL handshake error, proving that the certificate pinning is working correctly.
