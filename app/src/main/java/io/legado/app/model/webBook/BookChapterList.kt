package io.legado.app.model.webBook

import android.text.TextUtils
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.model.Debug
import io.legado.app.model.NoStackTraceException
import io.legado.app.model.TocEmptyException
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 获取目录
 */
object BookChapterList {

    private val falseRegex = "\\s*(?i)(null|false|0)\\s*".toRegex()

    suspend fun analyzeChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        redirectUrl: String,
        baseUrl: String,
        body: String?
    ): List<BookChapter> {
        body ?: throw NoStackTraceException(
            appCtx.getString(R.string.error_get_web_content, baseUrl)
        )
        val chapterList = ArrayList<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        Debug.log(bookSource.bookSourceUrl, body, state = 30)
        val tocRule = bookSource.getTocRule()
        val nextUrlList = arrayListOf(baseUrl)
        var reverse = false
        var listRule = tocRule.chapterList ?: ""
        if (listRule.startsWith("-")) {
            reverse = true
            listRule = listRule.substring(1)
        }
        if (listRule.startsWith("+")) {
            listRule = listRule.substring(1)
        }
        var chapterData =
            analyzeChapterList(
                scope, book, baseUrl, redirectUrl, body,
                tocRule, listRule, bookSource, log = true
            )
        chapterList.addAll(chapterData.first)
        when (chapterData.second.size) {
            0 -> Unit
            1 -> {
                var nextUrl = chapterData.second[0]
                while (nextUrl.isNotEmpty() && !nextUrlList.contains(nextUrl)) {
                    nextUrlList.add(nextUrl)
                    AnalyzeUrl(
                        mUrl = nextUrl,
                        source = bookSource,
                        ruleData = book,
                        headerMapF = bookSource.getHeaderMap()
                    ).getStrResponseAwait().body?.let { nextBody ->
                        chapterData = analyzeChapterList(
                            scope, book, nextUrl, nextUrl,
                            nextBody, tocRule, listRule, bookSource
                        )
                        nextUrl = chapterData.second.firstOrNull() ?: ""
                        chapterList.addAll(chapterData.first)
                    }
                }
                Debug.log(bookSource.bookSourceUrl, "◇目录总页数:${nextUrlList.size}")
            }
            else -> {
                Debug.log(bookSource.bookSourceUrl, "◇并发解析目录,总页数:${chapterData.second.size}")
                withContext(IO) {
                    val asyncArray = Array(chapterData.second.size) {
                        async(IO) {
                            val urlStr = chapterData.second[it]
                            val analyzeUrl = AnalyzeUrl(
                                mUrl = urlStr,
                                source = bookSource,
                                ruleData = book,
                                headerMapF = bookSource.getHeaderMap()
                            )
                            val res = analyzeUrl.getStrResponseAwait()
                            analyzeChapterList(
                                this, book, urlStr, res.url,
                                res.body!!, tocRule, listRule, bookSource, false
                            ).first
                        }
                    }
                    asyncArray.forEach { coroutine ->
                        chapterList.addAll(coroutine.await())
                    }
                }
            }
        }
        if (chapterList.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        //去重
        if (!reverse) {
            chapterList.reverse()
        }
        val lh = LinkedHashSet(chapterList)
        val list = ArrayList(lh)
        if (!book.getReverseToc()) {
            list.reverse()
        }
        Debug.log(book.origin, "◇目录总数:${list.size}")
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
        }
        book.latestChapterTitle = list.last().title
        book.durChapterTitle =
            list.getOrNull(book.durChapterIndex)?.title ?: book.latestChapterTitle
        if (book.totalChapterNum < list.size) {
            book.lastCheckCount = list.size - book.totalChapterNum
            book.latestChapterTime = System.currentTimeMillis()
        }
        book.lastCheckTime = System.currentTimeMillis()
        book.totalChapterNum = list.size
        return list
    }

    private fun analyzeChapterList(
        scope: CoroutineScope,
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        tocRule: TocRule,
        listRule: String,
        bookSource: BookSource,
        getNextUrl: Boolean = true,
        log: Boolean = false
    ): Pair<List<BookChapter>, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body).setBaseUrl(baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        //获取目录列表
        val chapterList = arrayListOf<BookChapter>()
        Debug.log(bookSource.bookSourceUrl, "┌获取目录列表", log)
        val elements = analyzeRule.getElements(listRule)
        Debug.log(bookSource.bookSourceUrl, "└列表大小:${elements.size}", log)
        //获取下一页链接
        val nextUrlList = arrayListOf<String>()
        val nextTocRule = tocRule.nextTocUrl
        if (getNextUrl && !nextTocRule.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "┌获取目录下一页列表", log)
            analyzeRule.getStringList(nextTocRule, true)?.let {
                for (item in it) {
                    if (item != baseUrl) {
                        nextUrlList.add(item)
                    }
                }
            }
            Debug.log(
                bookSource.bookSourceUrl,
                "└" + TextUtils.join("，\n", nextUrlList),
                log
            )
        }
        scope.ensureActive()
        if (elements.isNotEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "┌解析目录列表", log)
            val nameRule = analyzeRule.splitSourceRule(tocRule.chapterName)
            val urlRule = analyzeRule.splitSourceRule(tocRule.chapterUrl)
            val vipRule = analyzeRule.splitSourceRule(tocRule.isVip)
            val payRule = analyzeRule.splitSourceRule(tocRule.isPay)
            val upTimeRule = analyzeRule.splitSourceRule(tocRule.updateTime)
            elements.forEachIndexed { index, item ->
                scope.ensureActive()
                analyzeRule.setContent(item)
                val bookChapter = BookChapter(bookUrl = book.bookUrl, baseUrl = baseUrl)
                analyzeRule.chapter = bookChapter
                bookChapter.title = analyzeRule.getString(nameRule)
                bookChapter.url = analyzeRule.getString(urlRule)
                bookChapter.tag = analyzeRule.getString(upTimeRule)
                if (bookChapter.url.isEmpty()) {
                    bookChapter.url = baseUrl
                    Debug.log(bookSource.bookSourceUrl, "目录${index}未获取到url,使用baseUrl替代")
                }
                if (bookChapter.title.isNotEmpty()) {
                    val isVip = analyzeRule.getString(vipRule)
                    val isPay = analyzeRule.getString(payRule)
                    if (isVip.isNotEmpty() && !isVip.matches(falseRegex)) {
                        bookChapter.isVip = true
                    }
                    if (isPay.isNotEmpty() && !isPay.matches(falseRegex)) {
                        bookChapter.isPay = true
                    }
                    chapterList.add(bookChapter)
                }
            }
            Debug.log(bookSource.bookSourceUrl, "└目录列表解析完成", log)
            Debug.log(bookSource.bookSourceUrl, "┌获取首章名称", log)
            Debug.log(bookSource.bookSourceUrl, "└${chapterList[0].title}", log)
            Debug.log(bookSource.bookSourceUrl, "┌获取首章链接", log)
            Debug.log(bookSource.bookSourceUrl, "└${chapterList[0].url}", log)
            Debug.log(bookSource.bookSourceUrl, "┌获取首章信息", log)
            Debug.log(bookSource.bookSourceUrl, "└${chapterList[0].tag}", log)
        }
        return Pair(chapterList, nextUrlList)
    }

}