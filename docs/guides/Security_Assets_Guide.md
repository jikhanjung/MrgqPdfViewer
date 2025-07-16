# 보안 자산 가이드 (WSS 구현 시도용)

**주의: 이 문서는 WSS(WebSocket Secure) 구현 시도 과정을 기록한 것입니다. 현재 v0.1.8 버전에서는 WSS 구현이 실패하여 일반 WebSocket(WS)으로 롤백된 상태이므로, 이 가이드의 내용은 현재 프로젝트에 적용되지 않습니다. 향후 WSS 재시도를 위한 참고 자료로만 활용해주시기 바랍니다.**

이 문서는 MrgqPdfViewer의 합주 모드 통신 보안(WSS)을 위해 필요한 보안 자산(self-signed 인증서)을 생성하고 프로젝트에 적용하는 방법을 안내합니다.

## 1. 기술 전략 개요

합주 모드에서 지휘자(서버)와 연주자(클라이언트) 간의 실시간 통신은 WSS(WebSocket over TLS)를 통해 암호화됩니다. 이를 위해 앱 내부에 self-signed 인증서를 내장하고, 연주자 앱은 이 인증서를 신뢰하도록 구성하여 중간자 공격(MITM)을 방지하는 **인증서 피닝(Certificate Pinning)** 방식을 사용합니다.

## 2. 보안 자산 생성 (실패한 시도)

`keytool` (Java Development Kit에 포함)을 사용하여 필요한 키스토어와 공개키를 생성합니다. **아래 명령어들은 Android 플랫폼과의 호환성 문제로 실패했으므로, 참고용으로만 확인하시기 바랍니다.**

### 2.1 PKCS12 키스토어 생성 시도

```bash
# 이 명령어는 Android에서 "NoSuchAlgorithmException" 오류를 발생시켰습니다.
keytool -genkeypair -alias scoremate_local -keystore scoremate_keystore.p12 \
  -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=MrgqPdfViewer Local, OU=Local Network, O=MRGQ, C=KR" \
  -storepass scorematepass -keypass scorematepass
```

### 2.2 공개키(Public Certificate) 내보내기

```bash
keytool -exportcert -alias scoremate_local -file scoremate_cert.crt \
  -keystore scoremate_keystore.p12 -storepass scorematepass
```

## 3. Android 프로젝트에 자산 추가 (시도 내용)

생성된 `scoremate_keystore.p12`와 `scoremate_cert.crt` 파일을 Android 프로젝트의 리소스 디렉토리에 추가하려고 시도했습니다.

1.  `app/src/main/res/raw` 디렉토리가 없다면 생성합니다.
2.  생성된 파일을 다음 경로로 복사합니다:
    *   `scoremate_keystore.p12` → `app/src/main/res/raw/scoremate_keystore.p12`
    *   `scoremate_cert.crt` → `app/src/main/res/raw/scoremate_cert.crt`

## 4. 네트워크 보안 구성 (시도 내용)

연주자 앱이 내장된 self-signed 인증서를 신뢰하도록 `network_security_config.xml` 파일을 설정하려고 시도했습니다.

1.  `app/src/main/res/xml/network_security_config.xml` 파일을 생성합니다.
2.  다음 내용을 파일에 추가합니다.

    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <network-security-config>
        <base-config cleartextTrafficPermitted="false">
            <trust-anchors>
                <!-- WSS를 위한 self-signed 인증서 신뢰 시도 -->
                <certificates src="@raw/scoremate_cert" />
                <!-- 기본 시스템 인증서 신뢰 -->
                <certificates src="system" />
            </trust-anchors>
        </base-config>
    </network-security-config>
    ```

3.  `AndroidManifest.xml` 파일의 `<application>` 태그에 `android:networkSecurityConfig` 속성을 추가하여 이 구성을 연결합니다.

## 5. 결론: 롤백 및 향후 과제

위의 시도는 플랫폼 호환성 문제로 실패했으며, 현재 프로젝트는 일반 WebSocket(WS)을 사용하도록 롤백되었습니다. 따라서 `network_security_config.xml`에서는 `cleartextTrafficPermitted="true"`로 설정되어 있습니다.

향후 WSS를 성공적으로 구현하기 위해서는 Android와 호환되는 방식으로 인증서를 생성하는 새로운 방법을 찾아야 합니다. 이 가이드는 그 과정에서 참고할 수 있는 실패 사례로서의 의미를 가집다.
