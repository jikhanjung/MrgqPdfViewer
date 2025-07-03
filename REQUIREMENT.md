# Z18TV Pro용 PDF 리더 앱 요구사항

## 🎯 목표

Android TV OS 기반 Z18TV Pro에서 PDF 악보를 표시하고, 파일을 무선으로 업로드하고, 리모컨으로 탐색/열람할 수 있는 전용 앱을 개발한다.

---

## 📦 기능 요약

### 1. 📁 파일 목록 뷰어
- TV 화면에 `/sdcard/Download/` 혹은 앱 내부 디렉토리 내 PDF 파일 목록 표시
- 리모컨으로 탐색 (DPAD_UP / DPAD_DOWN / ENTER)
- 선택 시 PDF 뷰어로 해당 파일 열기

### 2. 📤 웹 서버 기반 업로드 기능
- TV에서 간단한 HTTP 서버 실행
- 브라우저에서 `http://<TV IP>:8080` 접속 시 업로드 UI 제공
- PDF 파일 업로드 시 지정 폴더에 저장 후 목록 자동 갱신
- 업로드 기능 ON/OFF 토글 가능 (앱 내 UI or 리모컨 버튼)

### 3. 📄 PDF 뷰어 (내장 렌더링)
- Android SDK의 `PdfRenderer` 사용
- 각 페이지를 Bitmap으로 렌더링하여 전체 화면에 표시
- 리모컨 좌/우 버튼으로 페이지 전환
- 화면 상단 또는 하단에 간단한 페이지 번호 표시 (선택)

---

## 🧱 기술 스택

| 구성 요소 | 기술 |
|-----------|------|
| UI 프레임워크 | Android SDK (Kotlin or Java) |
| PDF 렌더링 | PdfRenderer (Android 5.0+ 내장) |
| 리모컨 입력 | KeyEvent 처리 (`DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`, `ENTER`) |
| 파일 서버 | Ktor embedded HTTP server or NanoHTTPD |
| 파일 접근 | External storage 권한 또는 scoped storage (depending on API) |

---

## 🧪 기능 상세

### PDF 목록 기능
- PDF 파일만 필터링하여 표시
- 파일명 정렬 (A-Z)
- 썸네일 미사용 (단순 리스트)

### 웹 서버
- 기본 포트: `8080`
- HTML 업로드 폼 (단일 또는 다중 파일 지원)
- 업로드 후 파일 자동 저장
- 충돌 시 기존 파일 덮어쓰기 or 파일명 변경 (선택)

### PDF 뷰어
- 한 번에 한 페이지만 표시
- 고정 배율 or 화면에 맞춤 비율
- 리모컨으로 전/후 페이지 이동
- 자동 페이지 넘김 없음 (후속 기능)

---

## 🖥️ 화면 구성

1. **초기 화면**  
   PDF 파일 리스트 표시 (선택 가능)

2. **PDF 열람 화면**  
   - 전체 화면에 렌더링된 페이지
   - 리모컨으로 페이지 이동

3. **업로드 모드 (선택)**  
   - 켜면 HTTP 서버 실행
   - 꺼지면 외부 접근 차단

---

## 📂 디렉토리 구조 예시

```
/storage/emulated/0/Download/
 ├── 악보1.pdf
 ├── 악보2.pdf
 └── 악보3.pdf
```

---

## 🔐 권한 및 설정

- `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (API 28 이하)
- `MANAGE_EXTERNAL_STORAGE` 또는 scoped storage (API 29+)
- 네트워크 접근 권한 (`INTERNET`, `ACCESS_NETWORK_STATE`)

---

## 🚀 향후 확장 가능성

- 음성 인식으로 페이지 넘기기
- 박수나 소리 감지 기반 넘김
- 악보 북마크 / 즐겨찾기
- PDF 썸네일 또는 첫 페이지 미리보기
- JSON 기반 메타데이터 관리 (곡 제목, 작곡가 등)

---

