# 보안 자산 가이드

이 문서는 MrgqPdfViewer의 합주 모드 통신 보안(WSS)을 위해 필요한 보안 자산(self-signed 인증서)을 생성하고 프로젝트에 적용하는 방법을 안내합니다.

## 1. 기술 전략 개요

합주 모드에서 지휘자(서버)와 연주자(클라이언트) 간의 실시간 통신은 WSS(WebSocket over TLS)를 통해 암호화됩니다. 이를 위해 앱 내부에 self-signed 인증서를 내장하고, 연주자 앱은 이 인증서를 신뢰하도록 구성하여 중간자 공격(MITM)을 방지하는 **인증서 피닝(Certificate Pinning)** 방식을 사용합니다.

## 2. 보안 자산 생성

`keytool` (Java Development Kit에 포함)을 사용하여 필요한 키스토어와 공개키를 생성합니다. 다음 명령어를 터미널에서 실행합니다.

### 2.1 BKS 키스토어 생성

이 키스토어는 지휘자 앱의 WSS 서버에서 사용됩니다.

```bash
keytool -genkey -alias scoremate_local -keystore keystore.bks \
  -storetype BKS -keyalg RSA -keysize 2048 -validity 3650 \
  -provider org.bouncycastle.jce.provider.BouncyCastleProvider \
  -providerpath /path/to/bcprov-jdk15on-170.jar
```

*   **`your_secure_password`**: 키스토어 및 키에 사용할 비밀번호를 입력합니다. 이 비밀번호는 앱 코드에서 사용되므로 반드시 기억해야 합니다. **키스토어 비밀번호와 키 비밀번호를 동일하게 설정하는 것을 권장합니다.**
*   **`bcprov-jdk15on-170.jar`**: Bouncy Castle 프로바이더 JAR 파일의 경로를 지정해야 합니다. 이 파일이 없다면 [Bouncy Castle 웹사이트](https://www.bouncycastle.org/latest_releases.html)에서 다운로드하거나, `storetype`을 `PKCS12`로 변경하고 서버 코드도 이에 맞춰 수정해야 합니다.

### 2.2 공개키(Public Certificate) 내보내기

이 `.crt` 파일은 연주자 앱이 서버의 신원을 확인하는 데 사용됩니다.

```bash
keytool -exportcert -alias scoremate_local -file mycert.crt -keystore keystore.bks
```

*   키스토어 비밀번호를 입력하라는 메시지가 나타나면 입력합니다.

## 3. Android 프로젝트에 자산 추가

생성된 `keystore.bks`와 `mycert.crt` 파일을 Android 프로젝트의 리소스 디렉토리에 추가합니다.

1.  `app/src/main/res/raw` 디렉토리가 없다면 생성합니다.
2.  생성된 파일을 다음 경로로 복사합니다:
    *   `keystore.bks` → `app/src/main/res/raw/keystore.bks`
    *   `mycert.crt` → `app/src/main/res/raw/mycert.crt`

## 4. 네트워크 보안 구성 (Network Security Configuration)

연주자 앱이 내장된 self-signed 인증서를 신뢰하도록 `network_security_config.xml` 파일을 설정합니다.

1.  `app/src/main/res/xml/network_security_config.xml` 파일을 생성합니다.
2.  다음 내용을 파일에 추가합니다. 이 설정은 앱이 `mycert.crt`를 신뢰하도록 지시합니다.

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <base-config cleartextTrafficPermitted="false">
            <trust-anchors>
                <!-- WSS를 위한 self-signed 인증서 신뢰 -->
                <certificates src="@raw/mycert" />
                <!-- 기본 시스템 인증서 신뢰 -->
                <certificates src="system" />
            </trust-anchors>
        </base-config>
    </network-security-config>
    ```

3.  `AndroidManifest.xml` 파일의 `<application>` 태그에 `android:networkSecurityConfig` 속성을 추가하여 이 구성을 연결합니다.

    ```xml
    <application
        ...
        android:networkSecurityConfig="@xml/network_security_config">
        ...
    </application>
    ```

## 5. 앱 코드 수정

생성된 보안 자산을 사용하도록 앱 코드를 수정합니다.

### 5.1 서버 로직 수정 (지휘자)

`SimpleWebSocketServer.kt` (또는 서버를 시작하는 `CollaborationServerManager.kt`)에서 SSL/TLS를 사용하도록 설정해야 합니다. 이는 `NanoHTTPD` 인스턴스에 `SSLServerSocketFactory`를 제공하는 방식으로 이루어집니다.

**개념적 코드 변경 (예시):**

```kotlin
// 서버 시작 로직 (예: CollaborationServerManager.startServer 내부)

// 1. 리소스 접근을 위한 Context 확보 (매니저에 ApplicationContext 전달 필요)

// 2. 키스토어 로드
val keyStore = KeyStore.getInstance("BKS")
val keyStoreStream = context.resources.openRawResource(R.raw.keystore)
val password = "your_secure_password".toCharArray() // 2.1에서 설정한 비밀번호 사용

keyStore.load(keyStoreStream, password)

// 3. KeyManagerFactory 생성
val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
keyManagerFactory.init(keyStore, password)

// 4. SSLContext 생성
val sslContext = SSLContext.getInstance("TLS")
sslContext.init(keyManagerFactory.keyManagers, null, null)

// 5. ServerSocketFactory 확보
val serverSocketFactory = sslContext.serverSocketFactory

// 6. NanoHTTPD 인스턴스에 적용
// SimpleWebSocketServer의 생성자 또는 메서드를 통해 이 factory를 전달해야 합니다.
// 예: nanoHttpdInstance.setServerSocketFactory(serverSocketFactory)
```

### 5.2 클라이언트 로직 수정 (연주자)

`CollaborationClientManager.kt`에서 WebSocket 연결 URL을 `ws://`에서 `wss://`로 변경합니다.

**코드 변경:**

```kotlin
// CollaborationClientManager.connectToConductor 내부

// 기존:
// val request = Request.Builder().url("ws://$ipAddress:$port").build()

// 변경:
val request = Request.Builder().url("wss://$ipAddress:$port").build()
```

이 가이드를 통해 MrgqPdfViewer의 합주 모드 통신에 필요한 보안 자산을 성공적으로 생성하고 적용할 수 있습니다.
