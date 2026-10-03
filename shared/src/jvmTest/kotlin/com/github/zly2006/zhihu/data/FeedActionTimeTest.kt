package com.github.zly2006.zhihu.data

import com.github.zly2006.zhihu.util.formatFeedActionTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FeedActionTimeTest {
    private val timeZone = TimeZone.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-03T04:00:00Z").epochSeconds

    @Test
    fun relativeAndCalendarBoundaries() {
        assertEquals("刚刚", formatFeedActionTime(now - 59, now, timeZone))
        assertEquals("1分钟前", formatFeedActionTime(now - 60, now, timeZone))
        assertEquals("59分钟前", formatFeedActionTime(now - 3599, now, timeZone))
        assertEquals("1小时前", formatFeedActionTime(now - 3600, now, timeZone))
        assertEquals("23小时前", formatFeedActionTime(now - 86399, now, timeZone))
        assertEquals("昨天 12:00", formatFeedActionTime(now - 86400, now, timeZone))
        assertEquals("昨天 00:00", formatFeedActionTime(Instant.parse("2026-10-01T16:00:00Z").epochSeconds, now, timeZone))
        assertEquals("10-01", formatFeedActionTime(Instant.parse("2026-10-01T15:59:59Z").epochSeconds, now, timeZone))
        assertEquals("09-28", formatFeedActionTime(Instant.parse("2026-09-28T02:00:00Z").epochSeconds, now, timeZone))
    }

    @Test
    fun actorNameDefinesInsertionPosition() {
        val name = "昵称赞同了回答"
        val actor = Person("1", "", "people", name = name, headline = "", avatarUrl = "")
        val feed = CommonFeed(createdTime = now - 3600, actionText = "${name}赞同了想法", actors = listOf(actor))
        assertEquals("$name 1小时前 赞同了想法", feed.sourceLabelWithActionTime(now))
        assertEquals(feed.sourceLabel, feed.copy(createdTime = -1).sourceLabelWithActionTime(now))
        assertEquals(feed.sourceLabel, feed.copy(createdTime = now + 1).sourceLabelWithActionTime(now))
        assertEquals(feed.sourceLabel, feed.copy(actors = null).sourceLabelWithActionTime(now))
        assertEquals(feed.sourceLabel, feed.copy(actors = listOf(actor, actor)).sourceLabelWithActionTime(now))
        assertEquals("其他来源", feed.copy(actionText = "其他来源").sourceLabelWithActionTime(now))
    }
}
