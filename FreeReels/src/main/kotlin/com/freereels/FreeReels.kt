package com.freereels

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = buildMainUrl()
    private val apiUrl = buildH5ApiBaseUrl()
    private val nativeApiUrl = buildNativeApiBaseUrl()
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "New",
        "coming_soon" to "Segera Hadir",
        "dubbing" to "Dubbing",
        "female" to "Perempuan",
        "male" to "Laki-Laki",
        "anime" to "Anime",
    )

    private val secureRandom = SecureRandom()
    private val cryptoKey = "2r36789f45q01ae5"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private var deviceId = randomDeviceId()
    private val deviceBrand = "Redmi"
    private val deviceModel = "23090RA98G"
    private val deviceManufacturer = "Xiaomi"
    private val deviceProduct = "sky"
    private val deviceFingerprint = "Redmi/sky_global/sky:14/UKQ1.231003.002/V816.0.11.0.UMWMIXM:user/release-keys"
    private val sessionId = java.util.UUID.randomUUID().toString()
    private var h5Session: Session? = null
    private var nativeSession: Session? = null
    private val nativeCategories = listOf(
        NativeCategory("popular", "Populer", "993", 10000),
        NativeCategory("new", "New", "995", 10000),
        NativeCategory("coming_soon", "Segera Hadir", "1004", 10000, isComingSoon = true),
        NativeCategory("dubbing", "Dubbing", "1002", 10000),
        NativeCategory("female", "Perempuan", "994", 10000),
        NativeCategory("male", "Laki-Laki", "996", 10000),
        NativeCategory("anime", "Anime", "1005", 10001),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = nativeCategories.firstOrNull { it.key == request.data }
            ?: throw ErrorLoadingException("Kategori FreeReels tidak ditemukan")

        val categoryPage = getCategoryPage(category, page)
        val items = categoryPage.items
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = categoryPage.hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val candidates = LinkedHashMap<String, HomeItem>()
        val normalizedKeyword = keyword.lowercase()
        val suggestedTerms = buildList {
            add(keyword)
            addAll(
                getNativeKeywordSuggestions(keyword)
                    .mapNotNull { it.keyword?.trim() }
                    .filter { it.isNotBlank() }
                    .take(10)
            )
        }.distinct()
        val normalizedTerms = suggestedTerms.map { it.lowercase() }

        fun addItems(items: Iterable<HomeItem>) {
            items.forEach { item ->
                val key = item.key?.trim().orEmpty()
                if (key.isNotBlank()) candidates.putIfAbsent(key, item)
            }
        }

        nativeCategories.forEach { category ->
            addItems(getCategoryItems(category))
        }

        return candidates.values
            .mapNotNull { item ->
                val score = normalizedTerms.maxOfOrNull { term -> item.searchScore(term) } ?: 0
                if (score <= 0) null else score to item
            }
            .sortedWith(
                compareByDescending<Pair<Int, HomeItem>> { it.first }
                    .thenByDescending { it.second.title?.trim()?.equals(keyword, true) == true }
                    .thenByDescending { it.second.title?.trim()?.contains(normalizedKeyword, true) == true }
                    .thenByDescending { it.second.followCount ?: 0L }
                    .thenByDescending { it.second.episodeCount ?: 0 }
            )
            .mapNotNull { it.second.toSearchResult() }
            .take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesKey = extractSeriesKey(url)
        if (seriesKey.isBlank()) throw ErrorLoadingException("Series key tidak ditemukan")

        val localTitle = getQueryParam(url, "title")
        val localPoster = getQueryParam(url, "poster")
        val localPlot = getQueryParam(url, "plot")
        val localTags = getQueryParam(url, "tags")
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

        val nativeItem = findNativeItemBySeriesKey(seriesKey)
        val detail = getNativeDramaInfo(seriesKey).data?.info
            ?: getDramaInfo(seriesKey).data?.info

        if (detail != null) {
            val title = localTitle
                ?: detail.name?.takeIf { it.isNotBlank() }
                ?: "FreeReels"

            val episodes = detail.episodeList.orEmpty()
                .sortedBy { it.index ?: Int.MAX_VALUE }
                .filter { it.hasPlayableSource() }
                .mapIndexed { index, episode ->
                    val episodeNumber = episode.index ?: index + 1
                    val episodeName = episode.name
                        ?.takeIf { it.isNotBlank() && !it.equals(title, true) }
                        ?: "Episode $episodeNumber"

                    newEpisode(
                        EpisodeLoadData(
                            seriesKey = seriesKey,
                            episodeId = episode.id,
                            episodeNumber = episodeNumber,
                            episodeName = episodeName,
                            h264Url = episode.externalAudioH264M3u8,
                            h265Url = episode.externalAudioH265M3u8,
                            m3u8Url = episode.m3u8Url,
                            videoUrl = episode.videoUrl,
                            subtitles = episode.subtitleList.orEmpty()
                        ).toJson()
                    ) {
                        name = episodeName
                        this.episode = episodeNumber
                        this.posterUrl = episode.cover
                    }
                }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    buildSeriesUrl(seriesKey, title, detail.cover ?: localPoster, localPlot ?: detail.desc, localTags ?: detail.allTags()),
                    TvType.AsianDrama,
                    episodes
                ) {
                    posterUrl = detail.cover ?: localPoster
                    plot = localPlot ?: detail.desc
                    tags = localTags ?: detail.allTags()
                    showStatus = detail.finishStatus.toShowStatus()
                }
            }
        }

        val fallbackEpisode = nativeItem?.episodeInfo
            ?.takeIf { it.hasPlayableSource() }
            ?: throw ErrorLoadingException("Detail FreeReels tidak ditemukan")
        val fallbackTitle = localTitle
            ?: nativeItem?.title?.takeIf { it.isNotBlank() }
            ?: fallbackEpisode.name?.takeIf { it.isNotBlank() }
            ?: "FreeReels"
        val fallbackPlot = localPlot ?: nativeItem?.desc
        val fallbackPoster = fallbackEpisode.cover ?: nativeItem?.cover ?: localPoster
        val fallbackTags = localTags ?: nativeItem?.allTags().orEmpty()
        val fallbackEpisodeNumber = fallbackEpisode.index ?: 1
        val fallbackEpisodeName = fallbackEpisode.name
            ?.takeIf { it.isNotBlank() && !it.equals(fallbackTitle, true) }
            ?: "Episode $fallbackEpisodeNumber"

        return newTvSeriesLoadResponse(
            fallbackTitle,
            buildSeriesUrl(seriesKey, fallbackTitle, fallbackPoster, fallbackPlot, fallbackTags),
            TvType.AsianDrama,
            listOf(
                newEpisode(
                    EpisodeLoadData(
                        seriesKey = seriesKey,
                        episodeId = fallbackEpisode.id,
                        episodeNumber = fallbackEpisodeNumber,
                        episodeName = fallbackEpisodeName,
                        h264Url = fallbackEpisode.externalAudioH264M3u8,
                        h265Url = fallbackEpisode.externalAudioH265M3u8,
                        m3u8Url = fallbackEpisode.m3u8Url,
                        videoUrl = fallbackEpisode.videoUrl,
                        subtitles = fallbackEpisode.subtitleList.orEmpty()
                    ).toJson()
                ) {
                    name = fallbackEpisodeName
                    episode = fallbackEpisodeNumber
                    posterUrl = fallbackPoster
                }
            )
        ) {
            posterUrl = fallbackPoster
            plot = fallbackPlot
            tags = fallbackTags
            showStatus = ShowStatus.Ongoing
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<EpisodeLoadData>(data)
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
        )

        loadData.subtitles.orEmpty()
            .distinctBy { it.vtt ?: it.subtitle ?: "${it.language}:${it.displayName}" }
            .forEach { subtitle ->
                val subUrl = subtitle.vtt?.takeIf { it.isNotBlank() }
                    ?: subtitle.subtitle?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val lang = subtitle.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: subtitle.language
                    ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }

        var hasLinks = false
        val seen = LinkedHashSet<String>()

        suspend fun emit(label: String, url: String?) {
            val mediaUrl = url?.trim().orEmpty()
            if (mediaUrl.isBlank() || !seen.add(mediaUrl)) return

            hasLinks = true
            val linkType = if (mediaUrl.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback.invoke(
                newExtractorLink(
                    source = "$name $label",
                    name = "$name $label",
                    url = mediaUrl,
                    type = linkType
                ) {
                    this.headers = headers
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        emit("H264", loadData.h264Url)
        emit("H265", loadData.h265Url)

        if (hasLinks) return true

        val seriesKey = loadData.seriesKey ?: return false
        val episodeId = loadData.episodeId ?: return false
        val episode = (
            getNativeDramaInfo(seriesKey).data?.info?.episodeList
                ?: getDramaInfo(seriesKey).data?.info?.episodeList
                ?: emptyList()
            ).firstOrNull { it.id == episodeId } ?: return false

        emit("H264", episode.externalAudioH264M3u8)
        emit("H265", episode.externalAudioH265M3u8)

        episode.subtitleList.orEmpty()
            .distinctBy { it.vtt ?: it.subtitle ?: "${it.language}:${it.displayName}" }
            .forEach { subtitle ->
                val subUrl = subtitle.vtt?.takeIf { it.isNotBlank() }
                    ?: subtitle.subtitle?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val lang = subtitle.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: subtitle.language
                    ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }

        return hasLinks
    }

    private suspend fun findNativeItemBySeriesKey(seriesKey: String): HomeItem? {
        for (category in nativeCategories) {
            val match = getCategoryItems(category).firstOrNull { it.key == seriesKey }
            if (match != null) return match
        }
        return null
    }

    private suspend fun getCategoryItems(category: NativeCategory): List<HomeItem> {
        return if (category.isComingSoon) {
            getComingSoonItems()
        } else {
            getNativeModuleIndex(category).items
                .flatMap { it.items.orEmpty() }
        }
    }

    private suspend fun getCategoryPage(category: NativeCategory, page: Int): CategoryPage {
        if (category.isComingSoon) {
            val items = if (page == 1) getComingSoonItems() else emptyList()
            return CategoryPage(items, false)
        }

        val moduleIndex = getNativeModuleIndex(category)
        if (page <= 1) {
            return CategoryPage(
                items = moduleIndex.items.flatMap { it.items.orEmpty() },
                hasNext = moduleIndex.pageInfo?.hasMore == true
            )
        }

        val feedPages = moduleIndex.items
            .filter { !it.moduleKey.isNullOrBlank() && !it.items.isNullOrEmpty() }
            .mapNotNull { module ->
                fetchNativeFeedPage(
                    moduleKey = module.moduleKey,
                    page = page,
                    initialNext = moduleIndex.pageInfo?.next
                )
            }

        return CategoryPage(
            items = feedPages.flatMap { it.items.orEmpty() },
            hasNext = feedPages.any { it.pageInfo?.hasMore == true }
        )
    }

    private suspend fun getNativeModuleIndex(category: NativeCategory): ModuleIndexData {
        val query = linkedMapOf(
            "tab_key" to category.tabKey,
            "position_index" to category.positionIndex.toString(),
        )
        val body = nativeApiGet("/homepage/v2/tab/index", query)
        return tryParseJson<ModuleIndexResponse>(body)?.data
            ?: throw ErrorLoadingException("Respons kategori FreeReels tidak valid")
    }

    private suspend fun getComingSoonItems(): List<HomeItem> {
        val body = nativeApiGet("/coming-soon/list")
        return tryParseJson<ComingSoonResponse>(body)?.data?.items
            .orEmpty()
            .flatMap { it.items.orEmpty() }
    }

    private suspend fun getNativeFeedItems(moduleKey: String, next: String?): FeedResponse {
        val body = nativeApiPost(
            path = "/homepage/v2/tab/feed",
            body = FeedRequest(
                moduleKey = moduleKey,
                next = next.orEmpty()
            )
        )
        return tryParseJson<FeedResponse>(body)
            ?: throw ErrorLoadingException("Respons feed native FreeReels tidak valid")
    }

    private suspend fun fetchNativeFeedPage(
        moduleKey: String?,
        page: Int,
        initialNext: String?
    ): FeedData? {
        if (moduleKey.isNullOrBlank() || page <= 1) return null

        var next = initialNext
        var current: FeedData? = null
        repeat(page - 1) {
            if (next.isNullOrBlank()) return current
            current = getNativeFeedItems(moduleKey, next).data
            next = current?.pageInfo?.next
        }
        return current
    }

    private suspend fun getNativeKeywordSuggestions(keyword: String): List<SearchKeyword> {
        val body = nativeApiPost(
            path = "/search/keywords",
            body = SearchKeywordRequest(keyword)
        )
        return tryParseJson<SearchKeywordResponse>(body)?.data?.keywords.orEmpty()
    }

    private suspend fun getPrimaryTab(): HomeTab {
        return getTabList().data?.list?.firstOrNull()
            ?: throw ErrorLoadingException("Tab FreeReels tidak ditemukan")
    }

    private suspend fun getPopularItemsForPage(page: Int, moduleResponse: ModuleIndexData): List<HomeItem> {
        val recommendItems = moduleResponse.items
            .firstOrNull { it.type.equals("recommend", true) }
            ?.items
            .orEmpty()
        if (page <= 1) return recommendItems

        return fetchFeedPage(
            moduleKey = moduleResponse.getRecommendModuleKey(),
            page = page,
            initialNext = moduleResponse.pageInfo?.next
        )?.items.orEmpty()
    }

    private suspend fun fetchFeedPage(
        moduleKey: String?,
        page: Int,
        initialNext: String?
    ): FeedData? {
        if (moduleKey.isNullOrBlank() || page <= 1) return null

        var next = initialNext
        var current: FeedData? = null
        repeat(page - 1) {
            if (next.isNullOrBlank()) return current
            current = getFeedItems(moduleKey, next).data
            next = current?.pageInfo?.next
        }
        return current
    }

    private suspend fun getTabList(): TabListResponse {
        val body = apiGet("/h5-api/homepage/v2/tab/list")
        return tryParseJson<TabListResponse>(body)
            ?: throw ErrorLoadingException("Respons tab FreeReels tidak valid")
    }

    private suspend fun getModuleIndex(tab: HomeTab): ModuleIndexData {
        val query = linkedMapOf(
            "tab_key" to tab.tabKey,
            "position_index" to tab.positionIndex?.toString(),
            "first" to "",
        )
        val body = apiGet("/h5-api/homepage/v2/tab/index", query)
        return tryParseJson<ModuleIndexResponse>(body)?.data
            ?: throw ErrorLoadingException("Respons homepage FreeReels tidak valid")
    }

    private suspend fun getFeedItems(moduleKey: String, next: String?): FeedResponse {
        val body = apiPost(
            path = "/h5-api/homepage/v2/tab/feed",
            body = FeedRequest(
                moduleKey = moduleKey,
                next = next.orEmpty()
            )
        )
        return tryParseJson<FeedResponse>(body)
            ?: throw ErrorLoadingException("Respons feed FreeReels tidak valid")
    }

    private suspend fun getDramaInfo(seriesKey: String): DramaInfoResponse {
        val query = mapOf("series_id" to seriesKey)
        val body = apiGet("/h5-api/drama/info", query)
        return tryParseJson<DramaInfoResponse>(body)
            ?: throw ErrorLoadingException("Respons detail FreeReels tidak valid")
    }

    private suspend fun getNativeDramaInfo(seriesKey: String): DramaInfoResponse {
        val query = linkedMapOf(
            "series_id" to seriesKey,
        )
        val body = nativeApiGet("/drama/info_v2", query)
        return tryParseJson<DramaInfoResponse>(body)
            ?: throw ErrorLoadingException("Respons detail native FreeReels tidak valid")
    }

    private suspend fun getKeywordSuggestions(keyword: String): List<SearchKeyword> {
        val body = apiPost(
            path = "/h5-api/search/keywords",
            b