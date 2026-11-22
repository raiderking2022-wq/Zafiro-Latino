package com.zafirolatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class CineHDPlus : MainAPI() {
    override var mainUrl = "https://cinehdplus.org"
    override var name = "CineHDPlus"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/series/" to "Series",
        "$mainUrl/genero/accion/" to "Acción",
        "$mainUrl/genero/animacion/" to "Animación",
        "$mainUrl/genero/anime/" to "Anime",
        "$mainUrl/genero/comedia/" to "Comedia",
        "$mainUrl/genero/terror/" to "Terror",
        "$mainUrl/genero/drama/" to "Drama",
        "$mainUrl/genero/ciencia-ficcion/" to "Ciencia ficción",
        "$mainUrl/genero/documental/" to "Documental",
    )

    // ---------- Utilidades ----------

    /** Convierte el proxy de imágenes del sitio (wp-json/rm/v1/img) a la URL directa de TMDB. */
    private fun fixImage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val url = fixUrl(raw)
        if (url.contains("/rm/v1/img/")) {
            val src = Regex("[?&]src=([^&]+)").find(url)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") }
            if (src != null) {
                return if (src.startsWith("http")) src
                else "https://image.tmdb.org/t/p/w500$src"
            }
        }
        return url
    }

    private fun Element.imageUrl(): String? {
        val img = selectFirst("img") ?: return null
        return fixImage(
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        )
    }

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s+-\\s+(Película|Serie de TV|Serie|Anime)$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun typeFromUrl(url: String): TvType =
        if (url.contains("/series-tv-") || url.contains("/episodio-")) TvType.TvSeries else TvType.Movie

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/pelicula-") && !href.contains("/series-tv-")) return null
        val title = selectFirst("h3, p.truncate")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.let { cleanTitle(it) }
            ?: return null
        val poster = imageUrl()
        val year = Regex("(19|20)\\d{2}").find(select("p").text())?.value?.toIntOrNull()

        return if (typeFromUrl(href) == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a[href*=/pelicula-], a[href*=/series-tv-]")
            .filter { it.selectFirst("img") != null }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

    // ---------- Página principal y búsqueda ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.cards()
        val hasNext = doc.selectFirst("a.next, a[href*=/page/${page + 1}/]") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.encodeUri()}").document
        return doc.cards()
    }

    private fun String.encodeUri(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ---------- Detalles ----------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" – ")?.substringBefore(" - ")?.trim()
            ?: return null
        val poster = fixImage(
            doc.selectFirst("img[alt*=Póster], img[alt*=Poster], img.poster")?.attr("src")
        ) ?: fixImage(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val backdrop = fixImage(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")?.trim()
        val year = doc.selectFirst("h1 span")?.text()
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val tags = doc.select("a[href*=/genero/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val recommendations = doc.cards().filter { it.url != url }

        return if (typeFromUrl(url) == TvType.TvSeries) {
            val episodes = getEpisodes(doc, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private val episodeRegex = Regex("-(\\d+)x(\\d+)/?$")

    private fun parseEpisodes(doc: Document, fallbackPoster: String?): List<Episode> =
        doc.select("a[href*=/episodio-]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val match = episodeRegex.find(href)
            val season = match?.groupValues?.get(1)?.toIntOrNull()
            val number = match?.groupValues?.get(2)?.toIntOrNull()
            val epName = a.selectFirst("h3, h4, p")?.text()?.trim()?.takeIf { it.isNotBlank() }
            newEpisode(href) {
                this.season = season
                this.episode = number
                this.name = epName ?: number?.let { "Episodio $it" }
                this.posterUrl = a.imageUrl() ?: fallbackPoster
            }
        }

    private suspend fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val episodes = parseEpisodes(doc, poster).toMutableList()

        // Si las temporadas están en páginas aparte, se recorren también
        doc.select("a[href*=temporada]").mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()
            .forEach { seasonUrl ->
                runCatching { episodes += parseEpisodes(app.get(seasonUrl).document, poster) }
            }

        return episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // ---------- Enlaces de video ----------

    private val playerAttrs = listOf(
        "data-src", "data-url", "data-link", "data-embed", "data-player", "data-video", "data-iframe"
    )

    private fun decodeMaybeBase64(value: String): String {
        if (value.startsWith("http") || value.startsWith("//")) return value
        return runCatching { String(base64DecodeArray(value)) }.getOrNull()
            ?.takeIf { it.startsWith("http") || it.startsWith("//") } ?: value
    }

    private fun collectPlayerUrls(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        doc.select("iframe").forEach { f ->
            listOf("src", "data-src", "data-lazy-src").map { f.attr(it) }
                .firstOrNull { it.isNotBlank() && it != "about:blank" }
                ?.let { urls += it }
        }
        doc.select(playerAttrs.joinToString(", ") { "[$it]" }).forEach { el ->
            if (el.tagName() == "img") return@forEach
            playerAttrs.map { el.attr(it) }.filter { it.isNotBlank() }
                .forEach { urls += decodeMaybeBase64(it) }
        }
        // URLs de reproductores que aparezcan dentro de scripts
        Regex("""https?:\\?/\\?/[^"'\s<>]+?/(?:e|embed|v|f|d|play|video)/[^"'\s<>]+""")
            .findAll(doc.select("script").html())
            .forEach { urls += it.value.replace("\\/", "/") }

        return urls.map { if (it.startsWith("//")) "https:$it" else it }
            .filter { it.startsWith("http") && !it.contains("googletagmanager") && !it.contains("recaptcha") }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        collectPlayerUrls(doc).forEach { playerUrl ->
            if (playerUrl.contains("cinehdplus")) {
                // Reproductor intermedio del propio sitio: se abre y se extraen sus servidores
                runCatching {
                    val inner = app.get(playerUrl, referer = data).document
                    collectPlayerUrls(inner).filter { !it.contains("cinehdplus") }.forEach {
                        if (loadExtractor(it, playerUrl, subtitleCallback, callback)) found = true
                    }
                }
            } else {
                if (loadExtractor(playerUrl, data, subtitleCallback, callback)) found = true
            }
        }
        return found
    }
}
