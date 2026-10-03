package com.github.zly2006.zhihu.viewmodel.filter

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.github.zly2006.zhihu.data.CommonFeed
import com.github.zly2006.zhihu.data.DataHolder
import com.github.zly2006.zhihu.data.Feed
import com.github.zly2006.zhihu.data.FeedDisplayItem
import com.github.zly2006.zhihu.data.Person
import com.github.zly2006.zhihu.data.toFeedDisplayItemNavDestinationJson
import com.github.zly2006.zhihu.navigation.Article
import com.github.zly2006.zhihu.navigation.ArticleType
import com.github.zly2006.zhihu.platform.MapSettingsStore
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedKeywordScopeTest {
    @Test
    fun scopesUseExistingTitleSummaryAndContentWithOriginalMatchingSemantics() = runTest {
        val database = database()
        val snapshots = listOf(
            snapshot("title", "FoO", "clean", "clean"),
            snapshot("summary", "clean", "FoO", "clean"),
            snapshot("body", "clean", "clean", "FoO"),
            snapshot("safe", "clean", "clean", "clean"),
        )
        for ((scope, expected) in listOf(
            KeywordMatchScope.TITLE to listOf("summary", "body", "safe"),
            KeywordMatchScope.BODY to listOf("title", "safe"),
            KeywordMatchScope.TITLE_AND_BODY to listOf("safe"),
        )) {
            database.blockedKeywordDao().clearAllKeywords()
            database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "foo", matchScope = scope.name))
            val pipeline = FeedKeywordFilterPipeline(FeedFilterSettings(), database.blockedKeywordDao())
            assertEquals(expected, pipeline.filter(snapshots).kept.map { it.contentId })
        }
        database.blockedKeywordDao().clearAllKeywords()
        database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "f.o", isRegex = true, matchScope = "BODY"))
        assertEquals(listOf("title", "safe"), FeedKeywordFilterPipeline(FeedFilterSettings(), database.blockedKeywordDao()).filter(snapshots).kept.map { it.contentId })
        database.blockedKeywordDao().clearAllKeywords()
        database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "foo", caseSensitive = true))
        assertEquals(snapshots, FeedKeywordFilterPipeline(FeedFilterSettings(), database.blockedKeywordDao()).filter(snapshots).kept)
        database.blockedKeywordDao().clearAllKeywords()
        database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "[", isRegex = true))
        assertEquals(snapshots, FeedKeywordFilterPipeline(FeedFilterSettings(), database.blockedKeywordDao()).filter(snapshots).kept)
        database.close()
    }

    @Test
    fun independentAnswerExemptionsControlKeywordsAndSmartFilteringAndStillRecordViews() = runTest {
        val database = database()
        database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "blocked"))
        val items = listOf(answer("author", 1, followedAuthor = true), answer("voteup", 2, followedVoteup = true), answer("unknown", 3))
        val manager = ContentFilterManager(database.contentFilterDao())
        for (settings in listOf(
            FeedFilterSettings(exemptFollowedAnswer = true, exemptFollowedVoteup = false),
            FeedFilterSettings(exemptFollowedAnswer = false, exemptFollowedVoteup = true),
            FeedFilterSettings(exemptFollowedAnswer = true, exemptFollowedVoteup = true),
            FeedFilterSettings(exemptFollowedAnswer = false, exemptFollowedVoteup = false),
        )) {
            val expected = buildList {
                if (settings.exemptFollowedAnswer) add("author")
                if (settings.exemptFollowedVoteup) add("voteup")
            }
            assertEquals(expected, filterFollowFeedKeywords(items, settings, database).map { it.authorName })
            items.forEach { manager.recordContentView("answer", it.resolveContentIdentity().id) }
            val priorCounts = items.associate { it.resolveContentIdentity().id to database.contentFilterDao().getViewRecord("answer:${it.resolveContentIdentity().id}")!!.viewCount }
            val kept = ForegroundReadFilterPipeline(settings, manager, database.contentOpenEventDao(), database.blockedFeedRecordDao()).filter(items)
            assertEquals(expected, kept.map { it.authorName })
            kept.forEach {
                val id = it.resolveContentIdentity().id
                assertEquals(priorCounts.getValue(id) + 1, database.contentFilterDao().getViewRecord("answer:$id")!!.viewCount)
            }
        }
        val article = snapshot("article", "blocked", null, null).copy(contentType = "article", isFollowing = true, isFollowedVoteup = true)
        assertEquals(emptyList(), FeedKeywordFilterPipeline(FeedFilterSettings(exemptFollowedVoteup = true), database.blockedKeywordDao()).filter(listOf(article)).kept)
        val disabled = FeedFilterSettings(enableKeywordBlocking = false)
        assertEquals(items, filterFollowFeedKeywords(items, disabled, database))
        database.close()
    }

    @Test
    fun legacySettingsAndVersionSevenDatabaseAndBackupsPreserveRules() = runTest {
        assertEquals(true, MapSettingsStore().toFeedFilterSettings().exemptFollowedAnswer)
        assertEquals(false, MapSettingsStore(mutableMapOf("filterFollowedUserContent" to true)).toFeedFilterSettings().exemptFollowedAnswer)
        assertEquals(true, MapSettingsStore(mutableMapOf("filterFollowedUserContent" to true, EXEMPT_FOLLOWED_ANSWER_KEY to true)).toFeedFilterSettings().exemptFollowedAnswer)
        val file = createTempDirectory("keyword-migration").resolve("filter.db").toFile()
        val original = getContentFilterDatabase(file)
        original.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "legacy"))
        original.contentFilterDao().insertOrUpdateViewRecord(ContentViewRecord(id = "answer:1", targetType = "answer", targetId = "1"))
        original.close()
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("ALTER TABLE blocked_keywords DROP COLUMN matchScope")
            connection.execSQL("PRAGMA user_version = 7")
        }
        val migrated = getContentFilterDatabase(file)
        assertEquals(
            "TITLE_AND_BODY",
            migrated
                .blockedKeywordDao()
                .getAllKeywords()
                .single()
                .matchScope,
        )
        assertEquals(1, migrated.contentFilterDao().getRecordCount())
        importBlocklistBackupFromJsonText(migrated.blockedKeywordDao(), migrated.blockedUserDao(), migrated.blockedQuestionAuthorDao(), migrated.blockedTopicDao(), """{"version":3,"keywords":[{"keyword":"old backup"}]}""")
        assertEquals(listOf("TITLE_AND_BODY", "TITLE_AND_BODY"), migrated.blockedKeywordDao().getAllKeywords().map { it.matchScope })
        migrated.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = "title only", matchScope = "TITLE"))
        val encoded = encodeBlocklistBackup(migrated.blockedKeywordDao(), migrated.blockedUserDao(), migrated.blockedQuestionAuthorDao(), migrated.blockedTopicDao())
        migrated.blockedKeywordDao().clearAllKeywords()
        importBlocklistBackupFromJsonText(migrated.blockedKeywordDao(), migrated.blockedUserDao(), migrated.blockedQuestionAuthorDao(), migrated.blockedTopicDao(), encoded)
        assertEquals(
            "TITLE",
            migrated
                .blockedKeywordDao()
                .getAllKeywords()
                .first { it.keyword == "title only" }
                .matchScope,
        )
        migrated.close()
    }

    private fun database() = getContentFilterDatabase(createTempDirectory("keyword-scope").resolve("filter.db").toFile())

    private fun snapshot(id: String, title: String, summary: String?, content: String?) = FilterableContent(title, summary, content, null, null, id, "answer", DataHolder.DummyContent)

    private fun answer(name: String, id: Long, followedAuthor: Boolean = false, followedVoteup: Boolean = false): FeedDisplayItem {
        val person = Person(id = name, name = name, url = "", userType = "people", headline = "", avatarUrl = "", isFollowing = followedAuthor)
        val target = Feed.AnswerTarget(id = id, url = "", author = person, question = Feed.QuestionTarget(id = 100, url = "", type = "question", _title = "blocked"))
        val feed = if (followedVoteup) CommonFeed(id = "$id", target = target, actors = listOf(person.copy(isFollowing = true)), verb = "MEMBER_VOTEUP_ANSWER") else CommonFeed(id = "$id", target = target)
        return FeedDisplayItem(title = "blocked", summary = "blocked body", authorName = name, details = "回答", feed = feed, navDestinationJson = Article(type = ArticleType.Answer, id = id).toFeedDisplayItemNavDestinationJson())
    }
}
