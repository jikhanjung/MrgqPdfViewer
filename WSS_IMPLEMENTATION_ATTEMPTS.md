# WSS (WebSocket Secure) 구현 시도 기록

## 개요
MrgqPdfViewer 프로젝트에서 합주 모드의 보안 강화를 위해 WSS(WebSocket Secure) 구현을 시도했으나, Android 플랫폼의 호환성 문제로 실패했습니다.

**날짜**: 2025-07-15  
**버전**: v0.1.8  
**상태**: 실패 (일반 WebSocket으로 폴백)

## 시도 과정

### 1. 초기 설정
- **목표**: 기존 WS(WebSocket) 연결을 WSS(WebSocket Secure)로 업그레이드
- **보안 요구사항**: TLS 1.2/1.3 암호화, 자체 서명 인증서 사용
- **구현 범위**: 
  - 서버 측: SimpleWebSocketServer에 SSL 지원 추가
  - 클라이언트 측: CollaborationClientManager에서 WSS 연결

### 2. 인증서 생성 시도

#### 2.1 첫 번째 시도 - 기본 PKCS12 키스토어
```bash
keytool -genkeypair \
  -alias mrgqpdfviewer_local \
  -keystore mrgqpdfviewer_keystore.p12 \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -dname "CN=MrgqPdfViewer Local, OU=Local Network, O=MRGQ, C=KR" \
  -storepass mrgqpdfviewerpass \
  -keypass mrgqpdfviewerpass
```

**결과**: 암호화 알고리즘 호환성 문제 발생

#### 2.2 두 번째 시도 - Android 호환성 개선
```bash
keytool -genkeypair \
  -alias mrgqpdfviewer_local \
  -keystore mrgqpdfviewer_keystore \
  -storetype PKCS12 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -dname "CN=localhost, OU=MrgqPdfViewer, O=MRGQ, L=Seoul, ST=Seoul, C=KR" \
  -storepass mrgqpdfviewerpass \
  -keypass mrgqpdfviewerpass \
  -destalias mrgqpdfviewer_local \
  -sigalg SHA256withRSA
```

**결과**: 여전히 동일한 오류 발생

#### 2.3 세 번째 시도 - JKS → PKCS12 변환
```bash
# JKS 형식으로 생성
keytool -genkeypair \
  -alias mrgqpdfviewer_local \
  -keystore mrgqpdfviewer_keystore.jks \
  -storetype JKS \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -dname "CN=localhost, OU=MrgqPdfViewer, O=MRGQ, L=Seoul, ST=Seoul, C=KR" \
  -storepass mrgqpdfviewerpass \
  -keypass mrgqpdfviewerpass

# PKCS12로 변환
keytool -importkeystore \
  -srckeystore mrgqpdfviewer_keystore.jks \
  -destkeystore mrgqpdfviewer_keystore \
  -srcstoretype JKS \
  -deststoretype PKCS12 \
  -srcstorepass mrgqpdfviewerpass \
  -deststorepass mrgqpdfviewerpass \
  -srcalias mrgqpdfviewer_local \
  -destalias mrgqpdfviewer_local \
  -srckeypass mrgqpdfviewerpass \
  -destkeypass mrgqpdfviewerpass
```

**결과**: 호환성 문제 지속

### 3. 코드 구현

#### 3.1 서버 측 구현
**파일**: `SimpleWebSocketServer.kt`

```kotlin
class SimpleWebSocketServer(
    private val port: Int,
    private val serverManager: CollaborationServerManager,
    private val context: Context? = null,
    private val useSSL: Boolean = true // WSS 활성화
) {
    
    private fun createSSLServerSocket(): ServerSocket {
        // KeyStore 로딩
        val keyStore = KeyStore.getInstance("PKCS12")
        val keyStoreStream: InputStream = context.resources.openRawResource(R.raw.mrgqpdfviewer_keystore)
        val keystorePassword = BuildConfig.KEYSTORE_PASSWORD.toCharArray()
        
        keyStore.load(keyStoreStream, keystorePassword)
        
        // SSL 컨텍스트 생성
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, keystorePassword)
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        
        // SSL 서버 소켓 생성
        val sslServerSocket = sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        sslServerSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        
        return sslServerSocket
    }
}
```

#### 3.2 클라이언트 측 구현
**파일**: `CollaborationClientManager.kt`

```kotlin
// WSS 연결 시도
val request = Request.Builder()
    .url("wss://$ipAddress:$port")
    .build()

webSocket = okHttpClient!!.newWebSocket(request, CollaborationWebSocketListener())
```

#### 3.3 빌드 설정
**파일**: `app/build.gradle.kts`

```kotlin
buildFeatures {
    viewBinding = true
    buildConfig = true
}

buildTypes {
    release {
        buildConfigField("String", "KEYSTORE_PASSWORD", "\"mrgqpdfviewerpass\"")
    }
    debug {
        buildConfigField("String", "KEYSTORE_PASSWORD", "\"mrgqpdfviewerpass\"")
    }
}
```

#### 3.4 네트워크 보안 설정
**파일**: `app/src/main/res/xml/network_security_config.xml`

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="@raw/mrgqpdfviewer_cert" />
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">192.168.0.0/16</domain>
        <domain includeSubdomains="true">10.0.0.0/8</domain>
        <domain includeSubdomains="true">172.16.0.0/12</domain>
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <trust-anchors>
            <certificates src="@raw/mrgqpdfviewer_cert" />
            <certificates src="system" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

### 4. 발생한 오류들

#### 4.1 KeyStore 로딩 오류
```
SimpleWebSocketServer: Failed to create SSL ServerSocket
java.io.IOException: exception unwrapping private key - 
java.security.NoSuchAlgorithmException: 1.2.840.113549.1.5.12 SecretKeyFactory not available
    at com.android.org.bouncycastle.jcajce.provider.keystore.pkcs12.PKCS12KeyStoreSpi.unwrapKey(PKCS12KeyStoreSpi.java:652)
    at com.android.org.bouncycastle.jcajce.provider.keystore.pkcs12.PKCS12KeyStoreSpi.engineLoad(PKCS12KeyStoreSpi.java:891)
    at java.security.KeyStore.load(KeyStore.java:1484)
```

**원인**: Android의 BouncyCastle 구현에서 특정 PKCS12 암호화 알고리즘(`1.2.840.113549.1.5.12`)을 지원하지 않음

#### 4.2 네트워크 보안 정책 오류
```
CollaborationClient: WebSocket connection failed
java.net.UnknownServiceException: CLEARTEXT communication to 192.168.55.62 not permitted by network security policy
```

**원인**: WSS 실패 후 WS로 폴백했지만 네트워크 보안 정책에서 cleartext 통신 차단

### 5. 해결 시도들

#### 5.1 인증서 생성 방법 변경
- 다양한 keytool 옵션 시도
- JKS → PKCS12 변환
- 서명 알고리즘 변경 (`SHA256withRSA` 명시)

#### 5.2 Android 호환성 개선
- BuildConfig에서 비밀번호 관리
- 적절한 import 구문 추가
- 오류 처리 강화

#### 5.3 네트워크 보안 정책 조정
- cleartext 트래픽 허용
- 로컬 네트워크 도메인 설정
- AndroidManifest.xml에 `usesCleartextTraffic` 추가

### 6. 최종 상태

**WSS 구현 포기 이유**:
1. **암호화 알고리즘 호환성**: Android BouncyCastle에서 지원하지 않는 PKCS12 암호화 방식
2. **플랫폼 제한**: 자체 서명 인증서 사용 시 추가 보안 정책 필요
3. **복잡성**: 로컬 네트워크에서 사용하기에는 과도한 보안 설정

**폴백 해결책**:
- WSS → WS 변경
- 네트워크 보안 정책에서 cleartext 허용
- 일반 WebSocket으로 협업 모드 구현

### 7. 최종 코드 변경사항

#### 7.1 SSL 비활성화
```kotlin
// SimpleWebSocketServer.kt
private val useSSL: Boolean = false

// CollaborationServerManager.kt
fun startServer(port: Int = DEFAULT_PORT, useSSL: Boolean = false)

// CollaborationClientManager.kt
.url("ws://$ipAddress:$port") // wss → ws
```

#### 7.2 네트워크 보안 설정 완화
```xml
<base-config cleartextTrafficPermitted="true">
    <trust-anchors>
        <certificates src="system" />
    </trust-anchors>
</base-config>
```

#### 7.3 AndroidManifest.xml 설정
```xml
<application
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config">
```

## 향후 WSS 구현 가능성

### 대안 방안
1. **프로그래밍 방식 인증서 생성**: 런타임에 자체 서명 인증서 생성
2. **Android Keystore API 사용**: 하드웨어 보안 모듈 활용
3. **OkHttp SSL 설정 커스터마이징**: 자체 TrustManager 구현
4. **외부 라이브러리 활용**: Netty, Conscrypt 등의 SSL 라이브러리

### 권장사항
- 현재 로컬 네트워크 환경에서는 WS(일반 WebSocket)로 충분
- 실제 운영 환경에서는 VPN이나 다른 보안 계층 활용 권장
- WSS 구현 시 Android 버전별 호환성 테스트 필수

## 파일 위치
- **키스토어**: `app/src/main/res/raw/mrgqpdfviewer_keystore`
- **인증서**: `app/src/main/res/raw/mrgqpdfviewer_cert`
- **네트워크 보안 설정**: `app/src/main/res/xml/network_security_config.xml`
- **빌드 설정**: `app/build.gradle.kts`

## 결론
WSS 구현은 Android 플랫폼의 암호화 라이브러리 호환성 문제로 실패했습니다. 현재는 일반 WebSocket으로 동작하며, 향후 더 안정적인 SSL 구현 방법을 모색할 필요가 있습니다.