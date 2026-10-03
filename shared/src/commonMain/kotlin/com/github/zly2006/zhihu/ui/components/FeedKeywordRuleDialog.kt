package com.github.zly2006.zhihu.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.github.zly2006.zhihu.platform.rememberUserMessageSink
import com.github.zly2006.zhihu.ui.AddKeywordDialog
import com.github.zly2006.zhihu.viewmodel.filter.BlockedKeyword
import com.github.zly2006.zhihu.viewmodel.filter.getContentFilterDatabase
import kotlinx.coroutines.launch

/** 信息流快捷入口与管理页使用相同的关键词规则编辑界面。 */
@Composable
fun FeedKeywordRuleDialog(onDismiss: () -> Unit, onSaved: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val userMessages = rememberUserMessageSink()
    AddKeywordDialog(
        onDismiss = onDismiss,
        onConfirm = { keyword, caseSensitive, isRegex, matchScope ->
            scope.launch {
                getContentFilterDatabase().blockedKeywordDao().insertKeyword(
                    BlockedKeyword(
                        keyword = keyword.trim(),
                        caseSensitive = caseSensitive,
                        isRegex = isRegex,
                        matchScope = matchScope.name,
                    ),
                )
                userMessages.showShortMessage("已添加关键词")
                onSaved()
                onDismiss()
            }
        },
    )
}
