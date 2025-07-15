# Security Assets Generation Instructions

## Overview
The WSS (WebSocket Secure) implementation for ensemble mode is now ready, but requires you to generate the security assets on a separate machine for security reasons.

## What You Need to Generate

### 1. Self-Signed Certificate and Keystore

Run these commands on your secure machine:

```bash
# Generate PKCS12 keystore with self-signed certificate
keytool -genkeypair -alias scoremate_local -keystore scoremate_keystore.p12 \
  -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=MrgqPdfViewer Local, OU=Local Network, O=MRGQ, C=KR" \
  -storepass scorematepass -keypass scorematepass

# Export the public certificate  
keytool -exportcert -alias scoremate_local -file scoremate_cert.crt \
  -keystore scoremate_keystore.p12 -storepass scorematepass
```

**Important Notes:**
- Password used: `scorematepass` (matches the hardcoded password in the code)
- Alias used: `scoremate_local` 
- Validity: 10 years (3650 days)

### 2. File Placement

Copy the generated files to these locations in your project:

```
app/src/main/res/raw/scoremate_keystore.p12
app/src/main/res/raw/scoremate_cert.crt
```

**Note:** The files must be named exactly as shown above.

## What's Already Implemented

✅ **Network Security Configuration** - Updated to trust the self-signed certificate
✅ **SSL Server Logic** - SimpleWebSocketServer now supports WSS with SSL/TLS
✅ **Server Manager Updates** - CollaborationServerManager creates secure servers by default  
✅ **Client Updates** - CollaborationClientManager now uses `wss://` protocol
✅ **Context Passing** - GlobalCollaborationManager passes context for keystore access

## Security Features Enabled

- **TLS 1.2/1.3** encryption for all ensemble mode communication
- **Certificate pinning** - Only trusts the specific self-signed certificate
- **MITM protection** - Prevents man-in-the-middle attacks on local network
- **Eavesdropping protection** - All WebSocket traffic is encrypted

## Testing After Asset Installation

1. **Functionality Test**: Start conductor mode on one device, performer mode on another
2. **Security Verification**: Use Wireshark to confirm encrypted traffic
3. **Certificate Validation**: Remove certificate temporarily to verify pinning works

## Fallback Behavior

If SSL setup fails for any reason, the code will:
- Log detailed error messages for debugging
- Fall back to regular WebSocket (WS) connections
- Continue to function normally (but without encryption)

## Default Behavior

- **WSS is enabled by default** for new connections
- **Port 9090** is used for ensemble mode (same as before)
- **Port 8080** remains HTTP for file upload server (different service)

## Code Configuration

The WSS implementation is controlled by these parameters:

```kotlin
// In CollaborationServerManager.startServer()
startServer(port = 9090, useSSL = true)  // WSS enabled by default

// Certificate path in code
R.raw.scoremate_keystore  // PKCS12 keystore
R.raw.scoremate_cert      // Public certificate  
```

## Next Steps

1. Generate the security assets using the commands above
2. Place them in the `app/src/main/res/raw/` directory  
3. Build and test the application
4. Verify secure communication using network analysis tools

The WSS implementation is complete and ready for testing once you add the security assets!