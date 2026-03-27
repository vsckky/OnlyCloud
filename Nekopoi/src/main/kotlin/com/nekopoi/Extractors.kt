package com.nekopoi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class Paistream : Streampai() {
    override val name = "Paistream"
    override val mainUrl = "https://paistream.my.id"
}

open class ZippyShare : ExtractorApi() {
    override val name = "ZippyShare"
    override val mainUrl = "https://zippysha.re"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val video = res.selectFirst("a#download-url")?.attr("href")
        callback.invoke(
			newExtractorLink(
                name,
                name,
                video ?: return
            ){
				this.referer = "$mainUrl/"
			}
        )
    }
}