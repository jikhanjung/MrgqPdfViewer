# 합주 모드 설정 화면 설계

## 개요
SettingsActivity에 합주 모드 관련 설정을 추가하는 것에 대한 설계 문서입니다.
실제 구현 전에 필요성과 실용성을 검토하기 위해 작성되었습니다.

---

## 현재 상황

### ✅ 기존 메인 화면의 합주 모드 기능
- **연주자 모드 버튼**: 자동 발견으로 지휘자 연결
- **지휘자 모드 버튼**: 서버 시작 후 연주자 대기
- **모드 상태 표시**: 화면 하단에 현재 모드와 연결 상태 표시

### ❓ 설정 화면 추가의 필요성
- 메인 화면에 이미 핵심 기능이 모두 구현됨
- 추가 설정이 필요한지 검토 필요

---

## 합주 모드별 설정 정보 분석

### 🎵 연주자 모드

#### 표시할 정보
```
🎵 연주자 모드: 활성
📡 연결 상태: 지휘자에 연결됨
🎯 지휘자: 4K Google TV Stick (192.168.1.100)
🔄 마지막 연결: 2분 전
📶 연결 방법: 자동 발견
```

#### 가능한 액션
- **연결 해제**: 연주자 모드 비활성화
- **재연결 시도**: 현재 지휘자에 다시 연결
- **수동 연결**: IP 주소 직접 입력

#### 실용성 평가
- **필요도**: 낮음 - 메인 화면에서 충분히 관리 가능
- **사용빈도**: 낮음 - 한 번 연결하면 계속 사용
- **복잡도**: 단순함 - 정보 표시 위주

### 🎼 지휘자 모드

#### 표시할 정보
```
🎼 지휘자 모드: 활성
👥 연결된 연주자: 2명
🌐 서버 주소: 192.168.1.100:9090
📡 브로드캐스트: 활성 (포트 9091)
🗂️ 파일 서버: 활성 (포트 8090)
```

#### 연결된 연주자 상세 정보
```
연결된 연주자 목록:
┌─────────────────────────────────┐
│ 📱 Android TV (연주자)           │
│    IP: 192.168.1.101            │
│    연결 시간: 5분 전             │
│    상태: 활성                   │
│ ├─────────────────────────────┤
│ 📱 Galaxy Tab S8               │
│    IP: 192.168.1.102            │
│    연결 시간: 3분 전             │
│    상태: 활성                   │
│ ├─────────────────────────────┤
│ 📱 iPhone 15                   │
│    IP: 192.168.1.103            │
│    연결 시간: 2분 전 (연결 해제) │
│    상태: 비활성                 │
└─────────────────────────────────┘
```

#### 가능한 액션
- **개별 연주자 연결 해제**: 특정 기기 연결 끊기
- **모든 연주자 연결 해제**: 전체 연결 해제
- **서버 재시작**: 포트 충돌 등 문제 해결
- **브로드캐스트 재시작**: 자동 발견 기능 재시작

#### 실용성 평가
- **필요도**: 중간 - 다중 기기 환경에서 유용할 수 있음
- **사용빈도**: 낮음 - 문제 상황에서만 필요
- **복잡도**: 복잡함 - 실시간 상태 관리 필요

---

## UI 설계안

### 📋 미니멀 버전 (추천)

```xml
<!-- 합주 모드 섹션 -->
<TextView android:text="🎼 합주 모드" />

<LinearLayout android:orientation="vertical"
              android:background="@drawable/card_background"
              android:padding="16dp">
    
    <!-- 현재 모드 표시 -->
    <TextView 
        android:text="현재 모드: 지휘자 모드 활성"
        android:textColor="@color/tv_secondary" />
    
    <!-- 상태 정보 -->
    <TextView 
        android:text="연결된 연주자: 2명"
        android:textColor="@color/tv_text_secondary" />
    
    <!-- 액션 버튼 -->
    <LinearLayout android:orientation="horizontal">
        <Button android:text="모드 변경" />
        <Button android:text="서버 재시작" />
    </LinearLayout>
    
</LinearLayout>
```

### 📋 상세 버전

```xml
<!-- 합주 모드 섹션 -->
<TextView android:text="🎼 합주 모드" />

<!-- 모드별 상세 정보 -->
<LinearLayout android:id="@+id/collaborationModeDetails">
    
    <!-- 지휘자 모드일 때 -->
    <include layout="@layout/conductor_mode_settings" />
    
    <!-- 연주자 모드일 때 -->
    <include layout="@layout/performer_mode_settings" />
    
</LinearLayout>

<!-- 공통 액션 -->
<LinearLayout android:orientation="horizontal">
    <Button android:text="연주자 모드로 변경" />
    <Button android:text="지휘자 모드로 변경" />
    <Button android:text="합주 모드 종료" />
</LinearLayout>
```

---

## 구현 고려사항

### 🔧 기술적 측면

1. **실시간 상태 업데이트**
   - GlobalCollaborationManager와의 콜백 연동
   - 연결/해제 시 즉시 UI 반영
   - onResume()에서 상태 갱신

2. **메모리 관리**
   - 설정 화면에서도 합주 모드 리소스 적절히 관리
   - 중복 서버 시작 방지

3. **사용자 경험**
   - 메인 화면과 설정 화면 간 상태 동기화
   - 설정 변경 시 즉시 적용

### 🎯 사용성 측면

1. **기능 중복성**
   - 메인 화면: 핵심 기능 (시작/중지)
   - 설정 화면: 상세 관리 (모니터링/트러블슈팅)

2. **사용 빈도**
   - 일반 사용: 메인 화면으로 충분
   - 문제 해결: 설정 화면에서 상세 정보

3. **학습 곡선**
   - 추가 설정 화면으로 인한 복잡도 증가
   - TV 환경에서의 탐색 어려움

---

## 결론 및 권장사항

### 🎯 권장 사항: **구현 보류**

**이유:**
1. **기능 중복**: 메인 화면에서 이미 핵심 기능 제공
2. **낮은 필요도**: 일반적인 사용에서 추가 설정 불필요
3. **복잡도 증가**: TV 환경에서 설정 화면 복잡성 증가
4. **유지보수 부담**: 실시간 상태 관리 추가 구현 필요

### 🔄 대안 접근법

1. **현재 메인 화면 개선**
   - 상태 표시를 더 상세하게 (연결된 기기 수 등)
   - 문제 발생 시 재시작 버튼 추가

2. **필요시 추후 추가**
   - 사용자 피드백 수집 후 결정
   - 실제 다중 기기 사용 패턴 파악 후 설계

3. **최소 기능만 추가**
   - 단순한 상태 정보 표시만
   - 복잡한 관리 기능은 제외

### 📅 향후 검토 시점

- 실제 TV 기기에서 다중 연주자 테스트 완료 후
- 사용자들의 실제 사용 패턴 파악 후
- 메인 화면만으로 부족한 기능이 구체적으로 식별된 후

---

**결론**: 현재로서는 메인 화면의 합주 모드 기능이 충분하므로, 설정 화면 추가는 보류하고 필요성이 확실해진 후 재검토하는 것이 바람직함.