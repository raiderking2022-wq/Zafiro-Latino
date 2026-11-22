package com.zafirolatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Zafiro : MainAPI() {
    override var mainUrl = "https://cinehdplus.org"
    override var name = "Zafiro"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/series/" to "Series",
        "$mainUrl/idioma/latino/" to "Latino",
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

    private fun fixScheme(url: String): String = if (url.startsWith("//")) "https:$url" else url

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
        if (url.contains("/series-tv-") || url.contains("/temporadas-tv-") || url.contains("/episodio-"))
            TvType.TvSeries else TvType.Movie

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
        val doc = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
        return doc.cards()
    }

    // ---------- Detalles ----------

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val heading = doc.selectFirst("h1") ?: doc.selectFirst("div.flex-1.min-w-0 > h2")
        val title = heading?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removePrefix("Ver ")?.substringBefore(" Online")
                ?.substringBefore(" – ")?.trim()
            ?: return null
        // Póster vertical (contenedor aspect-2/3) y fondo (imagen tenue detrás del hero)
        val poster = doc.selectFirst("div[class*=aspect-2/3] img")?.let { fixImage(it.attr("src")) }
            ?: fixImage(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val backdrop = doc.selectFirst("img[class*=opacity-20]")?.let { fixImage(it.attr("src")) }
            ?.replace("/w500/", "/original/")
        val plot = doc.selectFirst("div.prose-custom p, p[class*=line-clamp-3]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()
        val year = heading?.selectFirst("span")?.text()
            ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val tags = doc.select("div.flex-1.min-w-0 a[href*=/genero/]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        val trailer = doc.selectFirst("button.player-tab[data-type=trailer]")?.attr("data-url")
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
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                addTrailer(trailer)
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

        // Las temporadas tienen páginas propias: /temporadas-tv-ID/slug-temporada-N/
        doc.select("a[href*=/temporadas-tv-]").mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()
            .forEach { seasonUrl ->
                runCatching { episodes += parseEpisodes(app.get(seasonUrl).document, poster) }
            }

        return episodes.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    // ---------- Enlaces de video ----------

    private val ignoredHosts = listOf(
        "cinehdplus", "googletagmanager", "google-analytics", "cloudflare", "jquery",
        "w3.org", "recaptcha", "gstatic", "fonts.googleapis"
    )

    /**
     * Los servidores apuntan a //api.cinehdplus.org/ir/player.php?h=<cifrado>.
     * Esa página redirige o incrusta el reproductor real (voe, streamtape, mixdrop...).
     */
    private suspend fun resolvePlayer(url: String, referer: String): String? {
        val res = app.get(url, referer = referer)
        if (!res.url.contains("cinehdplus")) return res.url

        val doc = res.document
        doc.selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let { return fixScheme(it) }
        doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.substringAfter("url=", "")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { return fixScheme(it) }

        val html = res.text
        Regex("""(?:location(?:\.href)?\s*=|location\.replace\(|window\.open\(|src\s*[:=]|url\s*[:=])\s*['"]((?:https?:)?//[^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { u -> ignoredHosts.none { u.contains(it) } }
            ?.let { return fixScheme(it.replace("\\/", "/")) }

        return Regex("""https?:\\?/\\?/[^\s'"<>]+""").findAll(html)
            .map { it.value.replace("\\/", "/") }
            .firstOrNull { u ->
                ignoredHosts.none { u.contains(it) } && !u.endsWith(".js") && !u.endsWith(".css")
            }
    }

    /** Llama al extractor y añade el idioma al nombre del enlace. */
    private suspend fun extract(
        url: String,
        referer: String,
        lang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = mutableListOf<ExtractorLink>()
        loadExtractor(url, referer, subtitleCallback) { links.add(it) }
        links.forEach { link ->
            if (lang.isNullOrBlank()) {
                callback(link)
            } else {
                callback(
                    newExtractorLink(link.source, "${link.name} [$lang]", link.url) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.type = link.type
                        this.headers = link.headers
                    }
                )
            }
        }
        return links.isNotEmpty()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        val servers = doc.select("button.player-tab[data-type=server][data-url]")
        servers.forEach { btn ->
            val playerUrl = fixScheme(btn.attr("data-url"))
            val lang = btn.attr("data-lang").ifBlank { null }
            runCatching {
                val target = if (playerUrl.contains("cinehdplus")) resolvePlayer(playerUrl, data)
                else playerUrl
                if (target != null && extract(target, playerUrl, lang, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        // Respaldo: iframe principal si no se encontraron botones de servidor
        if (servers.isEmpty()) {
            doc.select("iframe#main-player-iframe, iframe[src*=player]").forEach { f ->
                val src = fixScheme(f.attr("src"))
                runCatching {
                    val target = if (src.contains("cinehdplus")) resolvePlayer(src, data) else src
                    if (target != null && extract(target, src, null, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }
        return found
    }
}
