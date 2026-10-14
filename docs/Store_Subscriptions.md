# 스토어 배포 · 구독 결제 — Google Play · App Store

작성일: 2026-10-14
상태: **정리만** — 아직 아무것도 만들지 않았다. 지금 앱은 사이드로드 · ScoreMate 서버 배포뿐이다.

> ⚠️ 이 문서의 수수료 · 정책 · API 이름은 작성 시점에 아는 것을 정리한 것이다. 스토어 정책,
> 특히 바깥 결제 안내 규칙은 나라마다 자주 바뀐다. 실제로 올리기 전에 최신 정책과
> Console 화면을 다시 확인한다(§7 확인 목록).

---

## 0. 요약

- **앱은 무료로 두고, 앱 안 구독으로 멤버십을 판다.** 결제(카드 · 계정 · 환불 · 세금)는
  스토어가 맡는다. **누가 어떤 멤버십인지는 ScoreMate 서버가 정한다**(단일 출처).
- 스토어에서 앱을 받았다고 받은 사람의 계정 주소(구글 메일 · Apple ID)가 개발자에게 오지 않는다.
  사용자를 알아보는 것은 지금처럼 **ScoreMate 계정 연결(기기 코드)** 이 맡는다.
- 결제를 ScoreMate 계정에 잇는 방법은 두 스토어 모두 같다. 결제를 시작할 때 **우리 쪽 계정 표시값**을 넣으면
  구매 기록에 그 값이 남는다.
  - Google: `obfuscatedAccountId`
  - Apple: `appAccountToken`(UUID)
- 앱은 지금처럼 `GET /api/v1/membership/` 만 본다. 웹 결제 · 쿠폰 · Play · App Store 를 서버가 합쳐 판단한다.
- **Play 에 올리기 전에 막히는 것이 먼저다**(§5): targetSdk 30, 앱 안 업데이트(APK 직접 설치), 서명.

---

## 1. 공통 구조

```
 앱                          스토어                        ScoreMate 서버
 ─────────────────────────   ──────────────────────────   ───────────────────────────────
 상품 · 가격 불러오기   ───▶  (현지 가격)
 구독 누름 + 계정 표시값 ──▶  결제 화면 · 카드 · 계정
                        ◀──  구매 결과(토큰 / 서명된 거래)
 구매 결과 보냄  ──────────────────────────────────────▶  스토어 API 로 진짜인지 · 만료일 확인
                                                          멤버십 올림(source = google_play | app_store)
                                                          (Google 만) 승인 acknowledge
                             갱신 · 해지 · 환불  ──────▶  서버 알림 받음 → 다시 조회 → until · 등급 고침
 GET /membership/  ────────────────────────────────────▶  합친 결과(웹 · 쿠폰 · Play · Apple)
```

### 계정 표시값

- 서버가 사용자마다 **바뀌지 않는 UUID** 를 하나 만든다. 예: `billing_account_id`
- 두 스토어에 같은 값을 넣는다. Apple `appAccountToken` 은 UUID 만 받고, Google `obfuscatedAccountId` 는
  64자까지 받으므로 UUID 하나로 둘 다 된다.
- **메일 · 이름 같은 개인 정보는 넣지 않는다.** 두 스토어 모두 그렇게 권한다.
- 앱은 결제 전에 서버에서 이 값을 받아 온다. 연결하지 않은 기기는 구독 단추를 숨기거나 먼저 연결하게 한다.

### 서버가 할 일 (제안 — 서버 저장소에서 정한다)

| 일 | 예 |
|---|---|
| 구매 등록 | `POST /api/v1/membership/purchases/google/` `{product_id, purchase_token}` · `…/apple/` `{signed_transaction}` |
| 스토어 알림 받기 | Google RTDN(Pub/Sub push) · App Store Server Notifications V2 받는 주소 |
| 확인 | Google Play Developer API · App Store Server API 로 상태 조회 |
| 합치기 | 여러 출처(웹 · 쿠폰 · Play · Apple)가 겹치면 가장 높은 등급 · 가장 늦은 `until` |
| 기록 | 구매 토큰 · 원 거래 번호 ↔ 계정. **같은 토큰을 다른 계정에 다시 쓰지 못하게** |

- 앱이 보는 `Membership.source` 에 `google_play` · `app_store` 가 늘어난다.
- 앱 쪽은 결제 화면 · 구매 결과 보내기 · 구독 관리 링크만 더하면 된다.

---

## 2. Google Play

### 2.1 준비 (Play Console)
- 개발자 계정은 한 번만 25달러를 낸다. 결제 프로필에 사업자 정보 · 정산 계좌 · 세금 정보를 넣는다.
- **구독 상품**을 하나 만든다. 예: `scoremate_member`
  - **기본 요금제**: 월간 · 연간
  - **혜택**: 무료 체험 · 첫 몇 달 할인. 신규 · 재가입 같은 조건을 걸 수 있다.
  - **프로모션 코드**: 쿠폰처럼 쓸 수 있다.
- 가격은 원화로 정하면 다른 나라는 자동 환산된다. 나라마다 고칠 수 있다.
- **데이터 보안 양식**에 무엇을 모으는지 적는다. ScoreMate 계정, 기기 정보(업데이트 기록 #120) 등.

### 2.2 결제 흐름 (앱)
1. Play Billing Library(`BillingClient`)에 연결하고, 상품 정보를 조회해 현지 가격을 표시한다.
2. 사용자가 고르면 `launchBillingFlow` 를 부른다. 이때 `setObfuscatedAccountId(billing_account_id)` 를 넣는다.
3. Google Play 결제 시트가 뜬다. 카드 · 통신사 결제 · 구글 계정 선택은 Google 이 한다.
4. `onPurchasesUpdated` 로 `purchaseToken` 을 받으면 서버로 보낸다.
5. 앱 시작 때 `queryPurchasesAsync` 로 서버에 못 보낸 구매가 있는지 다시 확인한다.
   보내는 도중 앱이 꺼질 수 있기 때문이다.

### 2.3 서버
- **확인**: 서비스 계정 키로 Google Play Developer API `purchases.subscriptionsv2.get` 을 부른다.
  만료 시각 · 상태 · `obfuscatedExternalAccountId` 를 확인한다.
- **승인(acknowledge)**: ⚠️ **3일 안에 하지 않으면 자동 환불된다.** 서버에서 확인 직후 한다.
- **실시간 개발자 알림(RTDN)**: Cloud Pub/Sub 주제를 Console 에 등록하면 상태가 바뀔 때 알림이 온다.
  - 알림 종류: 갱신, 해지(기간 끝까지는 유효), 결제 실패 → 유예 기간 → 계정 보류, 만료, 환불 · 취소(`REVOKED`)
  - 알림에는 토큰만 있으니 **받으면 API 로 다시 조회**해서 반영한다.
- **환불 · 무효 구매**: Voided Purchases API 로 주기적으로 맞춰 볼 수 있다.

### 2.4 구독 관리 · 해지
- 사용자는 **Play 스토어 → 결제 및 정기 결제**에서 해지한다.
- 앱에는 그 화면으로 가는 딥 링크(`https://play.google.com/store/account/subscriptions?sku=…&package=…`)만 둔다.

### 2.5 돈
- Google 이 받아 **수수료를 뗀 뒤 매달 정산**한다. 구독 수수료는 보통 **15%** 다.
- 부가세는 나라에 따라 Google 이 처리한다. 한국 처리 방식은 세무 쪽에서 확인한다.

### 2.6 시험
- **라이선스 테스터**: Console 에 계정을 넣으면 돈이 나가지 않는 시험 카드로 결제된다.
  갱신 주기가 짧아 월간 구독이 몇 분마다 갱신된다.
- **내부 테스트 트랙**으로 배포해서 시험한다. 결제는 Play 에서 받은 설치본에서만 된다.

---

## 3. App Store (iOS · iPadOS)

> 지금 이 앱은 Android(Kotlin)뿐이다. iOS 는 앱을 따로 만들어야 하므로 **스토어 · 서버 쪽만** 정리한다.
> 세로 태블릿 기능(#066)을 생각하면 iPad 가 자연스러운 대상이다.

### 3.1 준비 (App Store Connect)
- Apple Developer Program 은 **매년 99달러**다.
- **유료 앱 계약(Paid Applications Agreement)**, 은행 · 세금 정보를 넣어야 인앱 구매가 켜진다.
- **구독 그룹**을 하나 만들고 그 안에 월간 · 연간을 둔다. 같은 그룹 안에서는 하나만 구독되고, 바꾸면 업그레이드 · 다운그레이드로 처리된다.
- **혜택**:
  - **첫 구독 혜택**: 무료 체험 · 첫 기간 할인
  - **프로모션 혜택**: 기존 · 해지한 구독자용. 서버 서명이 필요하다.
  - **혜택 코드(Offer Codes)**: 쿠폰처럼 나눠 줄 수 있다.
- **앱 개인정보 라벨**에 모으는 데이터를 적는다.

### 3.2 결제 흐름 (앱, StoreKit 2)
1. `Product.products(for: ["scoremate_member_monthly", …])` 로 상품 · 현지 가격을 불러온다.
2. `product.purchase(options: [.appAccountToken(billing_account_id)])` 를 부른다.
3. Apple 결제 시트가 뜨고 Face ID 등으로 승인한다. Apple ID · 결제 수단은 Apple 이 맡는다.
4. 결과는 **Apple 이 서명한 거래(JWS)** 다. 서버로 보내고, 서버가 받으면 `transaction.finish()` 를 부른다.
   끝내지 않은 거래는 다시 전달된다.
5. 앱이 켜져 있는 동안 `Transaction.updates` 를 듣는다. 다른 기기에서 갱신 · 환불된 거래도 여기로 온다.
6. **구매 복원** 단추를 둔다. `AppStore.sync()` 를 쓰며, 심사에서 요구한다.

### 3.3 서버
- **확인**: App Store Server API(In-App Purchase 키 `.p8` 로 만든 JWT)를 부른다.
  - `Get All Subscription Statuses`: 구독 상태
  - `Get Transaction Info`: 거래 하나
  - 앱이 보낸 JWS 는 Apple 루트 인증서 체인으로 서명을 검증한다.
- **승인은 없다**. Google 과 달리 3일 승인 규칙이 없다.
- **App Store Server Notifications V2**: Connect 에 주소를 넣으면 서명된 알림이 온다.
  - `SUBSCRIBED`, `DID_RENEW`
  - `DID_FAIL_TO_RENEW`(하위 종류 `GRACE_PERIOD`)
  - `EXPIRED`, `DID_CHANGE_RENEWAL_STATUS`(자동 갱신 끔)
  - `REFUND`, `REVOKE`(가족 공유 해제)
  - 알림의 서명을 검증하고, `appAccountToken` 으로 계정을 찾는다.
- **원 거래 번호(`originalTransactionId`)** 가 한 구독의 평생 번호다. 계정과 묶어 저장한다.

### 3.4 구독 관리 · 해지
- 앱 안에서 `showManageSubscriptions(in:)` 를 부르면 Apple 구독 관리 시트가 뜬다.
- 환불 요청 시트도 있다: `beginRefundRequest`.

### 3.5 돈
- 기본 수수료는 **첫해 30%, 같은 구독자가 1년을 넘기면 15%** 다.
- **소규모 사업자 프로그램**(직전 해 수익 100만 달러 이하)에 들면 처음부터 **15%** 다. 들어가려면 신청해야 한다.
- 매달 정산한다. 세금은 나라에 따라 Apple 이 처리한다.

### 3.6 시험
- **Xcode StoreKit 구성 파일**: 서버 없이 로컬에서 결제를 시험한다.
- **샌드박스 계정**: 갱신이 빨라 한 달이 몇 분이다.
- **TestFlight**: 실제와 같은 흐름이지만 돈은 나가지 않는다.

### 3.7 iOS 심사에서 걸리기 쉬운 것
- **여러 플랫폼 서비스(가이드라인 3.1.3(b))**: 웹에서 산 멤버십을 iOS 앱에서 쓰게 하려면,
  **같은 것을 앱 안 구매로도 팔아야 한다**("리더 앱"이 아니라면). 즉 iOS 앱에서는 "웹에서만 사기"가 원칙적으로 안 된다.
- **리더 앱 예외(3.1.3(a))**: 잡지 · 책 · 음악 · 영상 같은 콘텐츠를 "보는" 앱은 앱 안 구매 없이 바깥 계정 관리 링크
  하나를 둘 수 있다(External Link Account Entitlement). 악보 앱이 해당하는지는 불확실하다. 해당하지 않는다고 보고 계획한다.
- **계정 삭제(5.1.1(v))**: 앱 안에서 계정을 만들 수 있으면 앱 안에서 지울 수도 있어야 한다.
  지금처럼 계정은 웹에서 만들고 앱은 연결만 하면 부담이 적지만, 웹 삭제 페이지로 가는 길은 둔다.
- **Sign in with Apple(4.8)**: Google · 카카오 같은 바깥 소셜 로그인을 쓰면 같은 수준의 개인정보 보호 로그인을
  함께 줘야 한다. 우리 계정 + 기기 코드 연결만 쓰면 해당하지 않는다.

---

## 4. 두 스토어 비교

| | Google Play | App Store |
|---|---|---|
| 개발자 비용 | 25달러 한 번 | 99달러 매년 |
| 앱 라이브러리 | Play Billing Library | StoreKit 2 |
| 계정 표시값 | `obfuscatedAccountId`(문자열 64자) | `appAccountToken`(UUID) |
| 앱이 받는 것 | `purchaseToken` | 서명된 거래(JWS) |
| 서버 확인 | Play Developer API (서비스 계정) | App Store Server API (`.p8` 키 JWT) |
| 승인 | **3일 안 acknowledge 필수**(안 하면 환불) | 없음(앱이 `finish()`) |
| 상태 알림 | RTDN — Cloud Pub/Sub, 토큰만 → 다시 조회 | Server Notifications V2 — 서명된 내용 |
| 한 구독의 평생 번호 | `purchaseToken`(바꿀 때 `linkedPurchaseToken`) | `originalTransactionId` |
| 구독 관리 | Play 스토어 구독 화면(딥 링크) | `showManageSubscriptions` 시트 |
| 쿠폰 | 프로모션 코드 | 혜택 코드 |
| 구독 수수료 | 15% | 첫해 30% → 15%, 소규모 프로그램 15% |
| 시험 | 라이선스 테스터 · 내부 테스트 트랙 | StoreKit 구성 파일 · 샌드박스 · TestFlight |

---

## 5. Play 에 올리기 전에 막히는 것 (지금 앱 기준)

구독보다 이것들이 먼저다.

1. **targetSdk 30**
   - Play 는 새 앱 · 업데이트에 최근 API 수준(대략 최신 −1)을 요구한다. 지금은 `ExpiredTargetSdkVersion` Lint 를 끄고 있다(`app/build.gradle.kts`).
   - 올리면 이런 것들을 다시 확인해야 한다: 저장소 · 패키지 가시성 · 알림 권한 · 백그라운드 서비스 · 포그라운드 서비스 종류 · 가장자리까지 그리기.
2. **앱 안 업데이트(#054)**
   - Play 에서 받은 앱이 Play 말고 다른 방법으로 스스로 업데이트하면 정책 위반이다(기기 · 네트워크 악용).
   - `REQUEST_INSTALL_PACKAGES` 권한도 심사 대상이다.
   - **Play 판에서는 앱 안 업데이트를 빼고 권한도 뺀다.** 빌드 맛(flavor)을 `sideload` · `play` 로 나눈다.
3. **서명**
   - Play 는 Google 이 서명한다(Play App Signing).
   - 같은 `applicationId` 라도 사이드로드판과 서명이 다르면 서로 덮어 설치가 안 된다. 사용자 데이터를 옮기는 안내가 필요하다.
   - 다른 방법은 Play 판의 `applicationId` 를 따로 쓰는 것이다.
4. **"웹에서 관리" 단추**
   - Play 판에서는 앱 안에서 웹 결제로 안내하는 것이 제한될 수 있다(§6).
   - 멤버십 줄의 "웹에서 관리"(#141)는 문구 · 노출을 판마다 다르게 한다.
5. **Android TV**
   - TV 앱은 따로 심사 기준이 있다: 배너, 리모컨만으로 모든 기능, 터치 없는 기기 지원 선언.
   - TV 에서 구독 결제는 Play 결제 시트가 리모컨으로 뜬다.

iOS 는 앱이 없으므로 해당 없음.

---

## 6. 바깥 결제 안내 규칙 (나라마다 다름 — 꼭 다시 확인)

| | Google Play | App Store |
|---|---|---|
| 기본 | 앱 안 디지털 상품은 Play 결제. 앱 안에서 웹 결제로 안내 · 링크 금지 | 앱 안 디지털 상품은 앱 안 구매. 바깥 결제 안내 금지 |
| 한국 | 사용자 선택 결제(대체 결제 같이 제공) — 수수료가 몇 %p 낮을 뿐 크게 줄지 않음 | 한국 스토어 전용 앱이면 대체 결제 권한(StoreKit External Purchase) — 수수료 약 26% |
| 미국 | 2025년 판결 이후 바깥 링크 · 대체 결제가 허용되는 쪽으로 바뀌는 중 | 2025년 판결 이후 미국 스토어에서는 바깥 구매 링크가 허용되는 쪽으로 바뀜 |
| EU | 외부 혜택 프로그램 등 | DMA 에 따른 바깥 링크 · 대체 결제(별도 수수료 체계) |

- 결론: **두 스토어 판에서는 앱 안 구독을 정식 경로로 두고**, 웹 결제 · 쿠폰은 서버에서 합쳐 인정하는 것이 가장 안전하다.
- 앱 안에서 "웹이 더 싸다" 같은 안내는 하지 않는다.
- 사이드로드 · ScoreMate 서버 배포판은 이 규칙과 상관없으므로 지금처럼 웹 관리를 둔다.

---

## 7. 실제로 할 때 확인할 것

- [ ] Play · Apple 의 최신 target API · 수수료 · 바깥 결제 정책(한국 · 미국 · EU)
- [ ] 한국 부가세 · 정산(사업자 형태에 따라)
- [ ] 악보 앱이 Apple 리더 앱 예외에 드는지
- [ ] Play 판 서명 · `applicationId` 결정(사이드로드판과 공존 여부)
- [ ] 서버 계정 표시값(`billing_account_id`) · 구매 등록 · 알림 받는 주소 — 서버 저장소의 계획 문서
- [ ] 멤버십 등급과 스토어 상품의 대응표(Lite · 정식 · 월간 · 연간 · 체험)
- [ ] 두 스토어에 겹쳐 구독한 사용자 안내(서버는 합쳐 인정, 해지는 각 스토어에서)

### 순서 (Play)
1. targetSdk 올리기
2. 빌드 맛 나누기: Play 판에서 앱 안 업데이트 · 설치 권한 빼기
3. 서버: 계정 표시값 · 구매 등록 · RTDN
4. 앱: 결제 화면 · 구매 보내기 · 구독 관리 링크
5. 내부 테스트 트랙에서 라이선스 테스터로 시험
6. 출시
