package com.mrgq.pdfviewer

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mrgq.pdfviewer.database.MusicDatabase
import com.mrgq.pdfviewer.database.entity.DisplayMode
import com.mrgq.pdfviewer.database.entity.PageOrientation
import com.mrgq.pdfviewer.database.entity.UserPreference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 마이그레이션 테스트 (v1~v4 → 최신).
 *
 * 과거에 마이그레이션 오류로 DisplayMode 저장이 깨진 적이 있다(v0.1.8). 그런데 app/schemas 에는
 * export 를 켠 v4 이후의 JSON 만 있어서 `helper.createDatabase(name, 1)` 로 옛 DB 를 만들 수
 * 없다. 그래서 v1~v3 DB 는 당시 Room 이 만들었을 SQL 로 직접 만들고, 결과만 최신 JSON 과
 * 대조한다. v4 부터는 `helper.createDatabase` 를 쓴다.
 *
 * 옛 스키마의 출처 (git):
 *  - v1: `96c23d9` 의 엔티티 (UserPreference 에 클리핑·여백 없음)
 *  - v2: **커밋된 적 없다** (`96c23d9` v1 → `dc2c09f` v3). MIGRATION_1_2/2_3 에서 역산 —
 *        클리핑 2개(REAL) + centerPadding(INTEGER, 픽셀)
 *  - v3: `dc2c09f` 의 엔티티 (= 현재 v4 와 동일한 테이블)
 */
@RunWith(AndroidJUnit4::class)
class MusicDatabaseMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, MusicDatabase::class.java)

    @Before
    @After
    fun deleteTestDb() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun v1_에서_최신까지_스키마가_일치한다() = assertMigratesToLatest(1, V1_USER_PREFERENCES) {
        it.execSQL(INSERT_V1_PREF)
    }

    @Test
    fun v2_에서_최신까지_스키마가_일치한다() = assertMigratesToLatest(2, V2_USER_PREFERENCES) {
        it.execSQL(INSERT_V2_PREF)
    }

    @Test
    fun v3_에서_최신까지_스키마가_일치한다() = assertMigratesToLatest(3, V3_USER_PREFERENCES) {
        it.execSQL(INSERT_V3_PREF)
    }

    @Test
    fun v4_에서_v5_는_설정을_보존하고_문서정보_컬럼을_더한다() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(INSERT_FILE)
            execSQL(INSERT_V3_PREF) // v4 의 user_preferences 는 v3 와 같다
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT author, docInfoReadAt, totalPages FROM pdf_files WHERE id = 'file-1'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertTrue("새 컬럼은 null 로 시작", c.isNull(0))
            assertTrue("docInfoReadAt null = 다음 목록 로드에서 문서 정보를 읽는다", c.isNull(1))
            assertEquals(13, c.getInt(2))
        }
        // 3→4 와 달리 테이블을 재생성하지 않으므로 설정이 남아야 한다
        db.query("SELECT displayMode, centerPadding FROM user_preferences WHERE pdfFileId = 'file-1'").use { c ->
            assertEquals("v4→v5 에서 파일별 표시 설정이 사라졌다", 1, c.count)
            c.moveToFirst()
            assertEquals("DOUBLE", c.getString(0))
            assertEquals(0.1, c.getDouble(1), 1e-9)
        }
    }

    @Test
    fun v5_에서_v6_는_마디_테이블을_더하고_기존_데이터를_보존한다() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(INSERT_FILE)
            execSQL(INSERT_V3_PREF)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT scoreAnalyzedAt FROM pdf_files WHERE id = 'file-1'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertTrue("분석 전 = null → 뷰어에서 처음 필요할 때 분석", c.isNull(0))
        }
        db.query("SELECT COUNT(*) FROM score_measures").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.query("SELECT displayMode FROM user_preferences WHERE pdfFileId = 'file-1'").use { c ->
            assertEquals("v5→v6 에서 파일별 표시 설정이 사라졌다", 1, c.count)
        }
    }

    @Test
    fun v6_에서_v7_은_메트로놈_컬럼을_더하고_표시설정을_보존한다() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(INSERT_FILE)
            execSQL(INSERT_V3_PREF)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query(
            "SELECT metronomeBpm, metronomeBeatsPerBar, displayMode, topClippingPercent " +
                "FROM user_preferences WHERE pdfFileId = 'file-1'"
        ).use { c ->
            assertEquals("v6→v7 에서 파일별 표시 설정이 사라졌다", 1, c.count)
            c.moveToFirst()
            assertTrue("미설정 = null → 기본 템포", c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals("DOUBLE", c.getString(2))
            assertEquals(0.05, c.getDouble(3), 1e-9)
        }
    }

    @Test
    fun v7_에서_v8_은_문서정보를_다시_읽게_하고_나머지는_보존한다() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(INSERT_FILE)
            execSQL("UPDATE pdf_files SET author = 'ì€—ìŸ‹ìŽ—', docInfoReadAt = 1234, scoreAnalyzedAt = 5678 WHERE id = 'file-1'")
            // v7 의 user_preferences 는 메트로놈 컬럼까지 10개라 v3 용 INSERT 를 쓸 수 없다
            execSQL(
                "INSERT INTO user_preferences (pdfFileId, displayMode, lastPageNumber, bookmarkedPages, " +
                    "topClippingPercent, bottomClippingPercent, centerPadding, updatedAt, metronomeBpm) " +
                    "VALUES ('file-1', 'DOUBLE', 7, '', 0.05, 0.03, 0.1, 1000, 96)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT docInfoReadAt, scoreAnalyzedAt FROM pdf_files WHERE id = 'file-1'").use { c ->
            c.moveToFirst()
            assertTrue("깨진 작성자를 다시 읽도록 비워야 한다", c.isNull(0))
            assertEquals("마디 분석 표시는 건드리지 않는다", 5678L, c.getLong(1))
        }
        db.query("SELECT displayMode, metronomeBpm FROM user_preferences WHERE pdfFileId = 'file-1'").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("DOUBLE", c.getString(0))
            assertEquals(96, c.getInt(1))
        }
    }

    @Test
    fun v8_에서_v9_는_박자_컬럼을_더하고_마디를_다시_분석하게_한다() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(INSERT_FILE)
            execSQL("UPDATE pdf_files SET scoreAnalyzedAt = 5678 WHERE id = 'file-1'")
            execSQL("INSERT INTO score_measures VALUES ('file-1', 1, 0, 0, 75.1, 132.8, 212.1, 410.9, 595.3, 841.9)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT scoreAnalyzedAt FROM pdf_files WHERE id = 'file-1'").use { c ->
            c.moveToFirst()
            assertTrue("박자를 채우려면 다시 분석해야 한다", c.isNull(0))
        }
        db.query("SELECT timeSigNumerator, timeSigDenominator, rightPt FROM score_measures WHERE pdfFileId = 'file-1'").use { c ->
            assertEquals("기존 마디 행은 다시 분석될 때 교체된다 — 마이그레이션은 지우지 않는다", 1, c.count)
            c.moveToFirst()
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
            assertEquals(212.1, c.getDouble(2), 1e-3)
        }
    }

    @Test
    fun v9_에서_v10_은_박_단위_컬럼을_더하고_메트로놈_설정을_보존한다() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(INSERT_FILE)
            execSQL(
                "INSERT INTO user_preferences (pdfFileId, displayMode, lastPageNumber, bookmarkedPages, " +
                    "topClippingPercent, bottomClippingPercent, centerPadding, updatedAt, metronomeBpm, metronomeBeatsPerBar) " +
                    "VALUES ('file-1', 'DOUBLE', 7, '', 0.05, 0.03, 0.1, 1000, 180, 6)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query(
            "SELECT metronomeBpm, metronomeBeatsPerBar, metronomeBeatUnit, displayMode " +
                "FROM user_preferences WHERE pdfFileId = 'file-1'"
        ).use { c ->
            assertEquals("v9→v10 에서 파일별 설정이 사라졌다", 1, c.count)
            c.moveToFirst()
            assertEquals(180, c.getInt(0))
            assertEquals(6, c.getInt(1))
            assertTrue("박 단위 미설정 = null → 대화상자가 악보 박자표로 채운다", c.isNull(2))
            assertEquals("DOUBLE", c.getString(3))
        }
    }

    @Test
    fun v2_to_v3_중앙여백_픽셀이_비율로_변환된다() {
        val db = createDbAt(2, V2_USER_PREFERENCES) {
            it.execSQL(INSERT_FILE)
            it.execSQL(INSERT_FILE.replace("'file-1'", "'file-2'"))
            it.execSQL(INSERT_V2_PREF) // centerPadding 60px
            it.execSQL(
                "INSERT INTO user_preferences VALUES ('file-2', 'SINGLE', 1, '', 0.0, 0.0, 0, 1000)"
            )
        }
        val migration = MusicDatabase.ALL_MIGRATIONS.single { it.startVersion == 2 }
        migration.migrate(db)

        db.query(
            "SELECT pdfFileId, displayMode, lastPageNumber, topClippingPercent, " +
                "bottomClippingPercent, centerPadding FROM user_preferences ORDER BY pdfFileId"
        ).use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("file-1", c.getString(0))
            assertEquals("DOUBLE", c.getString(1))
            assertEquals(7, c.getInt(2))
            assertEquals(0.05, c.getDouble(3), 1e-9)
            assertEquals(0.03, c.getDouble(4), 1e-9)
            // 평균 페이지 너비 600px 가정: 60px → 10%
            assertEquals(0.1, c.getDouble(5), 1e-9)
            c.moveToNext()
            assertEquals("여백 0 은 0 으로 남아야 한다", 0.0, c.getDouble(5), 1e-9)
        }
        db.close()
    }

    /**
     * 테스트 헬퍼에 마이그레이션을 넘기는 것만으로는 **앱의 builder 에 등록이 빠진 것**을 못 잡는다.
     * 앱과 같은 목록(ALL_MIGRATIONS)으로 v1 DB 를 Room 으로 열고 DAO 로 읽고 쓴다.
     */
    @Test
    fun v1_DB_를_앱의_마이그레이션으로_열어_읽고_쓸_수_있다() = runBlocking {
        createDbAt(1, V1_USER_PREFERENCES) {
            it.execSQL(INSERT_FILE)
            it.execSQL(INSERT_V1_PREF)
        }.close()

        val db = Room.databaseBuilder(context, MusicDatabase::class.java, TEST_DB)
            .addMigrations(*MusicDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val file = db.pdfFileDao().getPdfFileById("file-1")
            assertNotNull("마이그레이션 후 파일 메타데이터가 사라졌다", file)
            assertEquals("바흐 무반주 1번.pdf", file!!.filename)
            assertEquals(PageOrientation.PORTRAIT, file.orientation)

            val prefDao = db.userPreferenceDao()
            prefDao.insertUserPreference(
                UserPreference(pdfFileId = "file-1", displayMode = DisplayMode.DOUBLE, centerPadding = 0.1f)
            )
            val pref = prefDao.getUserPreference("file-1")!!
            assertEquals(DisplayMode.DOUBLE, pref.displayMode)
            assertEquals(0.1f, pref.centerPadding, 1e-6f)
        } finally {
            db.close()
        }
    }

    /** 옛 버전 DB 를 만들고 최신으로 올린 뒤 4.json 과 대조한다. */
    private fun assertMigratesToLatest(
        version: Int,
        userPreferencesSql: String,
        seedPreference: (SupportSQLiteDatabase) -> Unit,
    ) {
        createDbAt(version, userPreferencesSql) {
            it.execSQL(INSERT_FILE)
            seedPreference(it)
        }.close()

        // 테이블 구조·외래키가 최신 스키마 JSON 과 다르면 여기서 IllegalStateException
        val db = helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *MusicDatabase.ALL_MIGRATIONS)

        db.query("SELECT filename, orientation FROM pdf_files WHERE id = 'file-1'").use { c ->
            assertEquals("pdf_files 행이 마이그레이션에서 사라졌다", 1, c.count)
            c.moveToFirst()
            assertEquals("바흐 무반주 1번.pdf", c.getString(0))
            assertEquals("PORTRAIT", c.getString(1))
        }
        // 현재 동작 고정: MIGRATION_3_4 가 테이블을 DROP 후 재생성하므로 v1~v3 의 설정은 남지 않는다.
        // 이 단언이 깨졌다면 설정을 보존하도록 바뀐 것이다 — 의도한 변경인지 확인하고 갱신할 것.
        db.query("SELECT COUNT(*) FROM user_preferences").use { c ->
            c.moveToFirst()
            assertEquals("v$version 설정은 MIGRATION_3_4 에서 초기화되는 것이 현재 동작", 0, c.getInt(0))
        }
    }

    /** 당시 Room 이 생성했을 스키마로 [version] DB 파일을 만든다 (room_master_table 없이). */
    private fun createDbAt(
        version: Int,
        userPreferencesSql: String,
        seed: (SupportSQLiteDatabase) -> Unit,
    ): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(PDF_FILES)
                    db.execSQL(userPreferencesSql)
                    seed(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("새 파일에서만 쓴다")
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val LATEST_VERSION = 10

        // pdf_files 는 v1 부터 바뀐 적이 없다
        const val PDF_FILES = "CREATE TABLE IF NOT EXISTS `pdf_files` (`id` TEXT NOT NULL, " +
            "`filename` TEXT NOT NULL, `filePath` TEXT NOT NULL, `totalPages` INTEGER NOT NULL, " +
            "`orientation` TEXT NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, " +
            "`title` TEXT, `composer` TEXT, `key` TEXT, `tempo` TEXT, `genre` TEXT, " +
            "`difficulty` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

        private const val PREFS_FK = "PRIMARY KEY(`pdfFileId`), FOREIGN KEY(`pdfFileId`) " +
            "REFERENCES `pdf_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        const val V1_USER_PREFERENCES = "CREATE TABLE IF NOT EXISTS `user_preferences` (" +
            "`pdfFileId` TEXT NOT NULL, `displayMode` TEXT NOT NULL, " +
            "`lastPageNumber` INTEGER NOT NULL, `bookmarkedPages` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " + PREFS_FK

        const val V2_USER_PREFERENCES = "CREATE TABLE IF NOT EXISTS `user_preferences` (" +
            "`pdfFileId` TEXT NOT NULL, `displayMode` TEXT NOT NULL, " +
            "`lastPageNumber` INTEGER NOT NULL, `bookmarkedPages` TEXT NOT NULL, " +
            "`topClippingPercent` REAL NOT NULL, `bottomClippingPercent` REAL NOT NULL, " +
            "`centerPadding` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " + PREFS_FK

        const val V3_USER_PREFERENCES = "CREATE TABLE IF NOT EXISTS `user_preferences` (" +
            "`pdfFileId` TEXT NOT NULL, `displayMode` TEXT NOT NULL, " +
            "`lastPageNumber` INTEGER NOT NULL, `bookmarkedPages` TEXT NOT NULL, " +
            "`topClippingPercent` REAL NOT NULL, `bottomClippingPercent` REAL NOT NULL, " +
            "`centerPadding` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, " + PREFS_FK

        const val INSERT_FILE = "INSERT INTO pdf_files (id, filename, filePath, totalPages, " +
            "orientation, width, height, createdAt, updatedAt) VALUES ('file-1', " +
            "'바흐 무반주 1번.pdf', '/data/PDFs/바흐 무반주 1번.pdf', 13, 'PORTRAIT', 595.0, 842.0, 1000, 1000)"

        const val INSERT_V1_PREF = "INSERT INTO user_preferences VALUES ('file-1', 'DOUBLE', 7, '', 1000)"
        const val INSERT_V2_PREF =
            "INSERT INTO user_preferences VALUES ('file-1', 'DOUBLE', 7, '', 0.05, 0.03, 60, 1000)"
        const val INSERT_V3_PREF =
            "INSERT INTO user_preferences VALUES ('file-1', 'DOUBLE', 7, '', 0.05, 0.03, 0.1, 1000)"
    }
}
