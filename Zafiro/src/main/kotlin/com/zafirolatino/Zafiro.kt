package com.zafirolatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
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

    /** Dominio "oficial" de cada servidor, por si el sitio usa un espejo que el extractor no reconoce. */
    private val canonicalHosts = mapOf(
        "voe" to "https://voe.sx",
        "streamtape" to "https://streamtape.com",
        "mixdrop" to "https://mixdrop.ag",
        "mixdropp" to "https://mixdrop.ag",
        "streamplay" to "https://streamplay.to",
        "filemoon" to "https://filemoon.sx",
        "streamwish" to "https://streamwish.to",
        "doodstream" to "https://dood.la",
        "uqload" to "https://uqload.to",
    )

    /**
     * Peticiones que delatan el reproductor real. goto.php redirige primero al dominio
     * oficial del servidor (p. ej. voe.sx/e/ID) y solo después a espejos con nombres
     * aleatorios, así que se aceptan solo los dominios oficiales para no confundirlos
     * con anuncios.
     */
    private val embedRegex = Regex(
        """^https?://(?:www\.)?(?:voe\.sx|[a-z0-9-]*streamtape\.[a-z]+|[a-z0-9-]*mixdropp?\.[a-z]+|""" +
            """[a-z0-9-]*streamplay\.[a-z]+|filemoon\.[a-z]+|streamwish\.[a-z]+|dood\.[a-z]+|""" +
            """uqload\.[a-z]+)/(?:e|v|f|d|embed|embed-)[/-]?[A-Za-z0-9]+""",
        RegexOption.IGNORE_CASE
    )

    /** URLs candidatas del reproductor dentro de un HTML (iframe, meta refresh, scripts). */
    private fun candidatesFromHtml(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        val out = mutableListOf<String>()
        doc.select("iframe[src], iframe[data-src]").forEach {
            out += it.attr("src").ifBlank { it.attr("data-src") }
        }
        doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.substringAfter("url=", "")?.trim()?.let { out += it }
        Regex("""https?:\\?/\\?/[^\s'"<>()]+""").findAll(html)
            .forEach { out += it.value.replace("\\/", "/") }

        return out.map { fixScheme(it.trim()) }
            .filter { u ->
                u.startsWith("http") && ignoredHosts.none { u.contains(it) } &&
                    !u.contains("popcash") && !u.contains("image.tmdb.org") &&
                    !u.endsWith(".js") && !u.endsWith(".css") && !u.endsWith(".png") &&
                    !u.endsWith(".svg") && !u.endsWith(".jpg")
            }
            .distinct()
    }

    private fun absUrl(base: String, href: String): String {
        val h = href.trim()
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> (Regex("^https?://[^/]+").find(base)?.value ?: "") + h
            else -> base.substringBefore("?").substringBeforeLast("/") + "/" + h
        }
    }

    /**
     * Cadena real del sitio (vista en el código de goto.php):
     *   player.php?h=X  -> botón play -> goto.php?h=X
     *   goto.php        -> "Anonimizador": formulario que se envía solo
     *                      <form action="rd.php" method="POST"><input name="url" value="X"></form>
     *   rd.php          -> redirige al servidor real (p. ej. voe.sx/e/ID)
     * Se reproduce ese recorrido enviando los formularios igual que el navegador.
     */
    private suspend fun gotoCandidates(playerUrl: String, pageUrl: String): List<String> {
        val gotoUrl = playerUrl.replace("/player.php", "/goto.php")
        val code = Regex("[?&]h=([^&]+)").find(playerUrl)?.groupValues?.get(1)
        val out = mutableListOf<String>()
        try {
            var url = gotoUrl
            val first = app.get(gotoUrl, timeout = 15L)
            if (!first.url.contains("cinehdplus")) return listOf(first.url)
            var html: String = first.text

            for (step in 0 until 4) {
                val form = org.jsoup.Jsoup.parse(html).selectFirst("form[action]")
                val action: String
                val fields: Map<String, String>
                if (form != null) {
                    action = absUrl(url, form.attr("action"))
                    fields = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
                } else if (step == 0 && code != null) {
                    // Por si goto.php cambia de aspecto: probar rd.php directamente
                    action = absUrl(url, "rd.php")
                    fields = mapOf("url" to code)
                } else {
                    out += candidatesFromHtml(html)
                    break
                }

                val isGet = form != null && form.attr("method").equals("get", ignoreCase = true)
                val res = if (isGet) {
                    app.get(action, params = fields, allowRedirects = false, timeout = 15L)
                } else {
                    app.post(action, data = fields, allowRedirects = false, timeout = 15L)
                }

                val loc = res.headers["location"]
                if (!loc.isNullOrBlank()) {
                    val next = absUrl(action, loc)
                    if (!next.contains("cinehdplus")) {
                        out += next
                        break
                    }
                    url = next
                    html = app.get(next, timeout = 15L).text
                    continue
                }
                url = action
                html = res.text
            }
        } catch (e: Exception) {
        }
        return out.distinct()
    }

    /** Abre player.php en un WebView oculto y captura la petición al servidor de video. */
    private suspend fun webViewCandidate(url: String, referer: String): String? = runCatching {
        val res = app.get(
            url.replace("/player.php", "/goto.php"),
            referer = url,
            interceptor = WebViewResolver(embedRegex, timeout = 35_000L)
        )
        res.url.takeIf { !it.contains("cinehdplus") }
    }.getOrNull()

    /** Igual que [extract] pero probando también el dominio oficial del servidor. */
    private suspend fun tryExtract(
        target: String,
        domain: String?,
        referer: String,
        lang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (runCatching { extract(target, referer, lang, subtitleCallback, callback) }.getOrDefault(false)) {
            return true
        }
        val canonical = domain?.lowercase()?.let { canonicalHosts[it] } ?: return false
        val path = Regex("^https?://[^/]+(/.*)$").find(target)?.groupValues?.get(1) ?: return false
        val alt = canonical + path
        if (alt == target) return false
        return runCatching { extract(alt, referer, lang, subtitleCallback, callback) }.getOrDefault(false)
    }

    private suspend fun resolveServer(
        playerUrl: String,
        domain: String?,
        pageUrl: String,
        lang: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!playerUrl.contains("cinehdplus")) {
            return tryExtract(playerUrl, domain, pageUrl, lang, subtitleCallback, callback)
        }
        // 1) goto.php (lo que abre el botón de play de player.php)
        val candidates = gotoCandidates(playerUrl, pageUrl)
        for (c in candidates) {
            if (tryExtract(c, domain, playerUrl, lang, subtitleCallback, callback)) return true
        }
        // 2) WebView sobre goto.php: captura la petición al reproductor real
        val captured = webViewCandidate(playerUrl, pageUrl) ?: return false
        return tryExtract(captured, domain, playerUrl, lang, subtitleCallback, callback)
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
        // Todos los servidores a la vez: cada enlace aparece en cuanto se resuelve
        servers.toList().amap { btn ->
            val playerUrl = fixScheme(btn.attr("data-url"))
            val lang = btn.attr("data-lang").ifBlank { null }
            val domain = btn.attr("data-domain").ifBlank { null }
            val ok = runCatching {
                resolveServer(playerUrl, domain, data, lang, subtitleCallback, callback)
            }.getOrDefault(false)
            if (ok) found = true
        }

        // Respaldo: iframe principal si no se encontraron botones de servidor
        if (servers.isEmpty()) {
            doc.select("iframe#main-player-iframe, iframe[src*=player]").forEach { f ->
                val src = fixScheme(f.attr("src"))
                if (resolveServer(src, null, data, null, subtitleCallback, callback)) found = true
            }
        }
        return found
    }
}
