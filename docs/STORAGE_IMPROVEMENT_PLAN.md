# 스토리지 접근 방식 개선 계획

## 1. 개요

이 문서는 `IMPROVEMENT_REPORT.md`에서 제기된 저장소 관련 권한 및 보안 문제를 해결하기 위한 구체적인 실행 계획을 정의합니다. 현재의 `MANAGE_EXTERNAL_STORAGE` 권한 의존적인 방식에서 벗어나, Android의 최신 Scoped Storage 정책과 Storage Access Framework(SAF)를 도입하여 보안과 사용자 경험을 대폭 개선하는 것을 목표로 합니다.

## 2. 현재 문제점 분석

1.  **과도한 권한 (`MANAGE_EXTERNAL_STORAGE`):** 앱의 핵심 기능에 비해 지나치게 강력한 권한을 요구하여 사용자에게 부담을 주고 보안상 위험이 있습니다.
2.  **불편한 권한 부여 과정:** 사용자가 직접 시스템 설정 메뉴로 이동하여 수동으로 권한을 부여해야 하므로 사용자 경험(UX)을 해칩니다.
3.  **보안 및 개인정보 보호:** 앱이 사용자의 모든 파일에 접근할 수 있어 개인정보 보호에 취약합니다.
4.  **미래 호환성:** Google Play 정책은 `MANAGE_EXTERNAL_STORAGE` 권한 사용을 점점 더 엄격하게 제한하고 있어, 향후 앱 업데이트 및 배포에 걸림돌이 될 수 있습니다.
5.  **하드코딩된 경로 의존:** `/storage/emulated/0/Download/` 라는 특정 경로에 의존하여 유연성이 떨어집니다.

## 3. 개선 목표

*   `MANAGE_EXTERNAL_STORAGE` 권한을 완전히 제거합니다.
*   사용자가 직접 파일이나 폴더를 선택하여 앱에 접근 권한을 부여하는 안전하고 직관적인 방법을 제공합니다.
*   웹 업로드 등 앱이 생성하는 파일은 앱 전용 디렉토리에 저장하여 외부 저장소를 오염시키지 않습니다.
*   Android의 최신 저장소 정책을 준수하여 미래 호환성을 확보합니다.

## 4. 단계별 구현 계획

### 1단계: 웹 업로드 파일 저장 위치 변경

가장 먼저 외부 환경에 영향을 주는 웹 업로드 파일의 저장 위치를 앱 전용 디렉토리로 변경하여 격리합니다.

*   **1.1 `WebServerManager.kt` 수정:**
    *   파일을 저장하는 로직에서 현재의 공용 `Download` 폴더 경로 대신, `context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)`를 사용하여 앱 전용 디렉토리 내의 `PDFs`와 같은 하위 폴더에 저장하도록 변경합니다.
    *   이 경로는 별도의 권한 없이 읽고 쓸 수 있습니다.

*   **1.2 `MainActivity.kt` 수정:**
    *   기존의 `Download` 폴더를 스캔하는 로직과 더불어, 1.1에서 지정한 앱 전용 디렉토리도 함께 스캔하여 파일 목록에 포함하도록 `getCurrentPdfFiles()` 함수를 수정합니다.

*   **1.3 웹 인터페이스 UI 업데이트:**
    *   웹 업로드 페이지의 안내 문구를 "파일은 앱의 비공개 저장소에 안전하게 저장됩니다." 와 같이 변경하여 사용자에게 알려줍니다.

### 2단계: Storage Access Framework (SAF) 도입

사용자가 직접 파일이나 폴더를 선택하여 라이브러리에 추가하는 기능을 구현합니다. 이것이 레거시 파일 스캔 방식을 대체하게 됩니다.

*   **2.1 `MainActivity.kt` UI 변경:**
    *   기존의 자동 파일 스캔 방식 대신, "PDF 파일 가져오기" 또는 "폴더 추가하기" 같은 명시적인 버튼을 `activity_main.xml`에 추가합니다.

*   **2.2 SAF 인텐트 구현:**
    *   "PDF 파일 가져오기" 버튼 클릭 시: `ACTION_OPEN_DOCUMENT` 인텐트를 사용하여 사용자가 하나 또는 여러 개의 PDF 파일을 선택하도록 합니다.
    *   "폴더 추가하기" 버튼 클릭 시: `ACTION_OPEN_DOCUMENT_TREE` 인텐트를 사용하여 사용자가 PDF 파일들이 들어있는 폴더를 선택하도록 합니다.

*   **2.3 URI 기반 접근 및 영구 권한 획득:**
    *   사용자가 파일이나 폴더를 선택하면, 앱은 `content://` 형태의 URI를 반환받습니다.
    *   `contentResolver.takePersistableUriPermission()`을 호출하여, 반환된 URI에 대한 접근 권한을 영구적으로 획득합니다. 이를 통해 앱을 재시작해도 해당 파일/폴더에 계속 접근할 수 있습니다.

*   **2.4 데이터베이스 스키마 변경 및 마이그레이션:**
    *   `database/entity/PdfFile.kt` 엔티티를 수정하여, 기존의 `filePath` (문자열) 필드를 `fileUri` (문자열) 필드로 변경하여 `content://` URI를 저장하도록 합니다.
    *   Room 데이터베이스 마이그레이션 계획을 수립합니다. (상세 내용은 5번 항목 참조)

*   **2.5 `PdfViewerActivity.kt` 수정:**
    *   PDF 파일을 열 때, `File(path)`를 사용하는 대신 `contentResolver.openFileDescriptor(uri, "r")`를 사용하여 URI로부터 `ParcelFileDescriptor`를 얻도록 로직을 수정합니다.

### 3단계: 레거시 코드 및 권한 제거

새로운 저장소 접근 방식이 안정적으로 동작하는 것을 확인한 후, 기존 코드를 제거합니다.

*   **3.1 레거시 파일 스캔 로직 제거:**
    *   `MainActivity.kt`에서 `Download` 폴더를 스캔하던 `getCurrentPdfFiles()` 내부 로직을 제거합니다. 이제 파일 목록은 SAF를 통해 사용자가 직접 추가한 URI 목록을 기반으로 관리됩니다.

*   **3.2 `MANAGE_EXTERNAL_STORAGE` 권한 관련 코드 완전 제거:**
    *   `MainActivity.kt`의 `checkPermissions()`와 관련된 모든 로직을 삭제합니다.
    *   `AndroidManifest.xml`에서 `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />` 권한 선언을 삭제합니다.

*   **3.3 관련 문서 업데이트:**
    *   `README.md`, `CLAUDE.md` 등 모든 관련 문서에서 파일 관리 방식을 새로운 SAF 기반으로 수정하고, `MANAGE_EXTERNAL_STORAGE` 권한에 대한 언급을 삭제합니다.

## 5. 데이터베이스 마이그레이션 계획

기존 사용자의 파일 목록을 유지하기 위해 데이터베이스 마이그레이션이 필요합니다.

1.  **버전업:** `MusicDatabase.kt`의 데이터베이스 버전을 올립니다.
2.  **마이그레이션 클래스 작성:** `Migration` 클래스를 구현합니다.
    *   `ALTER TABLE PdfFile ADD COLUMN fileUri TEXT` 와 같은 SQL을 실행하여 새로운 `fileUri` 컬럼을 추가합니다.
    *   **주의:** 기존 `filePath`를 `fileUri`로 자동 변환하는 것은 불가능합니다. (사용자의 상호작용이 필요하기 때문)
3.  **사용자에게 재연동 안내:**
    *   앱 업데이트 후 첫 실행 시, 기존 `filePath` 데이터가 있는 사용자에게 "저장소 정책 변경으로 인해 기존 파일들을 다시 한번 선택(가져오기)해야 합니다." 라는 안내 메시지를 표시합니다.
    *   사용자가 SAF를 통해 파일을 다시 선택하면, 기존 `PdfFile` 엔티티의 `fileUri` 필드를 업데이트합니다.

## 6. 기대 효과

*   **보안 강화:** 앱이 꼭 필요한 파일에만 접근하여 사용자의 개인정보를 보호합니다.
*   **사용자 경험 향상:** 복잡한 설정 메뉴 대신, 앱 내에서 직관적으로 파일을 추가할 수 있습니다.
*   **미래 호환성:** 최신 Android 버전의 요구사항을 충족하여 안정적인 앱 운영이 가능해집니다.
*   **코드베이스 개선:** 특정 폴더에 대한 의존성이 사라지고, 명확한 API를 사용하게 되어 코드의 유연성과 안정성이 향상됩니다.
