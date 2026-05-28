# 메인 화면 파일 인덱스 불일치 문제 분석

**날짜**: 2025-07-28

## 1. 문제 현상

웹서버를 통해 여러 차례에 걸쳐 파일을 업로드하면, 메인 화면에서 파일을 클릭했을 때 다른 파일이 열리는 심각한 문제가 발생합니다.

## 2. 문제 원인 분석

### 2.1 현재 구조
```kotlin
// PdfFileAdapter.kt
onItemClick = { pdfFile, position ->
    openPdfFile(pdfFile, position)
}

// MainActivity.kt - openPdfFile()
val currentPdfFiles = pdfAdapter.currentList
val filePathList = currentPdfFiles.map { it.path }
val fileNameList = currentPdfFiles.map { it.name }

val intent = Intent(this, PdfViewerActivity::class.java).apply {
    putExtra("current_index", position)  // position을 직접 사용
    putStringArrayListExtra("file_path_list", ArrayList(filePathList))
    putStringArrayListExtra("file_name_list", ArrayList(fileNameList))
}
```

### 2.2 문제 발생 시나리오

1. **비동기 리스트 갱신**
   - 파일 업로드 → `loadPdfFiles()` 호출 (비동기)
   - 정렬 적용 (이름순/시간순)
   - ListAdapter DiffUtil이 변경사항 계산
   - RecyclerView UI 업데이트

2. **타이밍 불일치**
   - 사용자가 파일 A를 클릭 (position: 3)
   - 동시에 새 파일 업로드로 리스트 갱신 중
   - 클릭 시점의 position 3과 실제 열리는 시점의 position 3이 다른 파일을 가리킴

3. **RecyclerView position의 한계**
   - position은 현재 보이는 리스트에서의 인덱스
   - 리스트가 변경되면 같은 position이 다른 파일을 가리킬 수 있음

## 3. 해결 방안

### 방안 1: Position 대신 파일 경로 사용 (권장)
```kotlin
private fun openPdfFile(pdfFile: PdfFile, position: Int) {
    val currentPdfFiles = pdfAdapter.currentList
    val filePathList = currentPdfFiles.map { it.path }
    val fileNameList = currentPdfFiles.map { it.name }
    
    // position 대신 실제 파일 경로로 인덱스 찾기
    val actualIndex = currentPdfFiles.indexOfFirst { it.path == pdfFile.path }
    
    if (actualIndex == -1) {
        Toast.makeText(this, "파일을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
        return
    }
    
    val intent = Intent(this, PdfViewerActivity::class.java).apply {
        putExtra("current_index", actualIndex)  // 실제 인덱스 사용
        putStringArrayListExtra("file_path_list", ArrayList(filePathList))
        putStringArrayListExtra("file_name_list", ArrayList(fileNameList))
    }
    startActivity(intent)
}
```

### 방안 2: 클릭 시점의 리스트 스냅샷 사용
```kotlin
private fun openPdfFile(pdfFile: PdfFile, position: Int) {
    // 클릭한 파일의 경로를 기준으로 안전하게 처리
    val filePath = pdfFile.path
    val fileName = pdfFile.name
    
    // 현재 리스트에서 해당 파일이 있는지 재확인
    val currentPdfFiles = pdfAdapter.currentList
    if (!currentPdfFiles.any { it.path == filePath }) {
        Toast.makeText(this, "파일이 삭제되었거나 이동되었습니다", Toast.LENGTH_SHORT).show()
        return
    }
    
    // 나머지 로직...
}
```

### 방안 3: 리스트 갱신 시 사용자 상호작용 차단
```kotlin
private fun loadPdfFiles() {
    // 로딩 중 RecyclerView 비활성화
    binding.recyclerView.isEnabled = false
    
    CoroutineScope(Dispatchers.IO).launch {
        val pdfFiles = getCurrentPdfFiles()
        
        withContext(Dispatchers.Main) {
            pdfAdapter.submitList(pdfFiles) {
                // 리스트 업데이트 완료 후 다시 활성화
                binding.recyclerView.isEnabled = true
            }
        }
    }
}
```

## 4. 추가 개선 사항

### 4.1 파일 고유 ID 사용
```kotlin
data class PdfFile(
    val id: String = UUID.randomUUID().toString(),  // 고유 ID 추가
    val name: String,
    val path: String,
    // ...
)
```

### 4.2 StableIds 활용
```kotlin
class PdfFileAdapter : ListAdapter<PdfFile, PdfViewHolder>(...) {
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        return getItem(position).path.hashCode().toLong()
    }
}
```

## 5. 테스트 시나리오

1. **동시 업로드 테스트**
   - 파일 목록 표시 중 웹서버로 새 파일 업로드
   - 업로드 중 기존 파일 클릭
   - 정확한 파일이 열리는지 확인

2. **빠른 정렬 변경 테스트**
   - 이름순 ↔ 시간순 빠르게 전환하며 파일 클릭
   - 올바른 파일이 열리는지 확인

3. **대량 파일 테스트**
   - 50개 이상 파일로 테스트
   - 스크롤 중 파일 업로드/삭제
   - 인덱스 일치 여부 확인

## 6. 결론

RecyclerView의 position에 의존하는 현재 방식은 동적으로 변하는 리스트에서 취약합니다. 파일의 고유 식별자(경로 또는 ID)를 기반으로 하는 방식으로 변경하면 이 문제를 근본적으로 해결할 수 있습니다.