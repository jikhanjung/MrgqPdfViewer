# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }
# ── R8 방어 규칙 (2026-08-15) ────────────────────────────────────────────────
#
# release 는 isMinifyEnabled = true 인데 규칙이 NanoHTTPD 만 있었다. 그리고 릴리스 APK 는
# 지금껏 한 번도 실행된 적이 없다 — Android Studio 는 debug 를 설치하고, v0.1.13 릴리스는
# 빌드·서명·배포만 됐다. "소스는 되는데 최적화 빌드에서 깨지는" 전형적 사각지대다.

# Room 은 enum 을 name()/valueOf() 로 문자열 저장한다 (Converters.kt).
# enum 상수 이름이 난독화되면 DB 에 저장된 "DOUBLE" 을 다시 못 읽고 valueOf 가 던진다 —
# 즉 **사용자의 파일별 표시 설정이 통째로 깨진다.** 방어적으로 고정한다.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# Room 엔티티 — 생성 코드가 필드에 직접 접근하지만, 스키마 검증이 클래스명을 쓰므로 유지.
-keep class com.mrgq.pdfviewer.database.entity.** { *; }
-keep class com.mrgq.pdfviewer.database.converter.** { *; }

# Gson — 현재는 JsonObject 만 쓰지만(리플렉션 없음), 나중에 데이터 클래스를 직렬화하게
# 되면 필드명 난독화로 조용히 깨진다. 애노테이션과 시그니처를 남겨 둔다.
-keepattributes Signature, *Annotation*, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
