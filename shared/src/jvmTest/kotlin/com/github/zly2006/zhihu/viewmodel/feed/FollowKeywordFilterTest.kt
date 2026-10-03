package com.github.zly2006.zhihu.viewmodel.feed

import com.github.zly2006.zhihu.data.CommonFeed
import com.github.zly2006.zhihu.data.Feed
import com.github.zly2006.zhihu.data.FeedDisplayItem
import com.github.zly2006.zhihu.data.ZhihuJson
import com.github.zly2006.zhihu.data.target
import com.github.zly2006.zhihu.viewmodel.FeedDisplaySettings
import com.github.zly2006.zhihu.viewmodel.PaginationEnvironment
import com.github.zly2006.zhihu.viewmodel.QualityFilterMode
import com.github.zly2006.zhihu.viewmodel.filter.BlockedKeyword
import com.github.zly2006.zhihu.viewmodel.filter.FeedFilterSettings
import com.github.zly2006.zhihu.viewmodel.filter.filterFollowFeedKeywords
import com.github.zly2006.zhihu.viewmodel.filter.getContentFilterDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FollowKeywordFilterTest {
    @Test
    fun bothFollowListsFilterRealDecodedDataAndReapplyRulesWithoutFetchingDetails() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val database = getContentFilterDatabase(createTempDirectory("follow-keywords").resolve("filter.db").toFile())
        val raw = ZhihuJson.json.parseToJsonElement(checkNotNull(javaClass.getResource("/real-api/moments-feed-items.json")).readText()) as JsonArray
        val answer = ZhihuJson.decodeJson<Feed>(raw[0]) as CommonFeed
        assertTrue(answer.actors.orEmpty().any { it.isFollowing })
        assertEquals("MEMBER_VOTEUP_ANSWER", answer.verb)
        database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = checkNotNull(answer.target).title, matchScope = "TITLE"))
        var settings = FeedFilterSettings(exemptFollowedAnswer = false)
        var listRequestCount = 0
        val environment = object : PaginationEnvironment {
            override fun httpClient(): HttpClient = error("过滤不应请求详情")

            override fun authenticatedCookies(): Map<String, String> = emptyMap()

            override fun feedDisplaySettings() = FeedDisplaySettings(qualityFilterMode = QualityFilterMode.OFF)

            override suspend fun fetchJson(url: String, include: String): JsonObject {
                listRequestCount++
                return buildJsonObject { put("data", raw) }
            }

            override suspend fun handleFetchFailure(tag: String?, error: Exception): Unit = throw error

            override suspend fun applyFollowFeedKeywordFilter(items: List<FeedDisplayItem>) = filterFollowFeedKeywords(items, settings, database)
        }
        try {
            for (model in listOf(FollowViewModel(), FollowRecommendViewModel())) {
                model.refresh(environment)
                withTimeout(5_000) { while (model.completedPageCount == 0) delay(10) }
                assertEquals(1, model.displayItems.size)
                assertIs<Feed.ArticleTarget>(
                    model.displayItems
                        .single()
                        .feed!!
                        .target,
                )
                val requestCount = listRequestCount
                settings = settings.copy(exemptFollowedVoteup = true)
                model.reapplyKeywordFilter(environment)
                assertEquals(2, model.displayItems.size)
                assertEquals(checkNotNull(answer.target).title, model.displayItems.first().title)
                assertEquals(requestCount, listRequestCount)
                settings = settings.copy(exemptFollowedVoteup = false)
                database.blockedKeywordDao().clearAllKeywords()
                model.reapplyKeywordFilter(environment)
                assertEquals(2, model.displayItems.size)
                assertEquals(requestCount, listRequestCount)
                database.blockedKeywordDao().insertKeyword(BlockedKeyword(keyword = checkNotNull(answer.target).title, matchScope = "TITLE"))
            }
            assertEquals(2, listRequestCount)
        } finally {
            database.close()
            Dispatchers.resetMain()
        }
    }
}
