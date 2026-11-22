/* Zafiro 1.0.1 - Proveedor nativo para Nuvio (películas y series de CineHDPlus).
 * Misma lógica que el plugin Zafiro de CloudStream, pero con extractores propios
 * (Nuvio no trae extractores de VOE/Streamtape/Mixdrop).
 * Sin eval, sin claves incrustadas, sin dependencias de Node.
 */
'use strict';
var NAME = 'Zafiro';
var BASE = 'https://cinehdplus.org';
var UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36';
var TIMEOUT_MS = 12000;

function log(msg) { try { console.log('[Zafiro] ' + msg); } catch (e) {} }

function withTimeout(promise, ms) {
  return new Promise(function (resolve, reject) {
    var done = false;
    var t = setTimeout(function () { if (!done) { done = true; reject(new Error('timeout')); } }, ms);
    promise.then(function (v) { if (!done) { done = true; clearTimeout(t); resolve(v); } },
      function (e) { if (!done) { done = true; clearTimeout(t); reject(e); } });
  });
}

// Petición genérica: devuelve { url (final, tras redirecciones), status, text }.
function request(url, opts) {
  opts = opts || {};
  var headers = { 'User-Agent': opts.ua || UA, 'Accept-Language': 'es-ES,es;q=0.9' };
  if (opts.referer) headers.Referer = opts.referer;
  var init = { method: opts.method || 'GET', headers: headers };
  if (opts.form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    init.body = Object.keys(opts.form).map(function (k) {
      return encodeURIComponent(k) + '=' + encodeURIComponent(opts.form[k]);
    }).join('&');
  }
  return withTimeout(fetch(url, init).then(function (r) {
    return r.text().then(function (t) { return { url: r.url || url, status: r.status, text: t }; });
  }), opts.timeout || TIMEOUT_MS);
}

function get(url, referer, ua) {
  return request(url, { referer: referer, ua: ua }).then(function (r) {
    if (r.status >= 400) throw new Error('http_' + r.status);
    return r.text;
  });
}

function decode(s) {
  return String(s || '').replace(/&quot;/g, '"').replace(/&#0?38;/g, '&').replace(/&amp;/g, '&')
    .replace(/&#8211;/g, '–').replace(/&#8217;/g, "'").replace(/&#(\d+);/g, function (_, n) {
      return String.fromCharCode(+n);
    }).replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

// Normaliza títulos: sin acentos, sin signos, minúsculas.
function norm(s) {
  return decode(s).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/&/g, ' y ').replace(/[^a-z0-9]+/g, ' ').replace(/^(el|la|los|las|the) /, '').trim();
}

/* ---------- Metadatos (sin clave): TMDB -> Wikidata ---------- */
function metadata(tmdbId, type) {
  var id = String(tmdbId).replace(/^tmdb:/, '');
  var prop, imdbMode = /^tt\d+$/.test(id);
  var where;
  if (imdbMode) where = '?item wdt:P345 "' + id + '".';
  else {
    if (!/^\d+$/.test(id)) return Promise.reject(new Error('invalid_id'));
    prop = type === 'tv' ? 'P4983' : 'P4947';
    where = '?item wdt:' + prop + ' "' + id + '".';
  }
  var q = 'SELECT ?es ?en ?mx ?mul ?orig ?date WHERE {' + where +
    ' OPTIONAL{?item rdfs:label ?mul FILTER(lang(?mul)="mul")}' +
    ' OPTIONAL{?item wdt:P1476 ?orig}' +
    ' OPTIONAL{?item rdfs:label ?es FILTER(lang(?es)="es")}' +
    ' OPTIONAL{?item rdfs:label ?mx FILTER(lang(?mx)="es-419")}' +
    ' OPTIONAL{?item rdfs:label ?en FILTER(lang(?en)="en")}' +
    ' OPTIONAL{?item wdt:P577 ?date}} LIMIT 1';
  return get('https://query.wikidata.org/sparql?format=json&query=' + encodeURIComponent(q))
    .then(function (t) {
      var b = (JSON.parse(t).results.bindings || [])[0] || {};
      var titles = [b.mx, b.es, b.en, b.mul, b.orig].filter(Boolean).map(function (x) { return x.value; });
      var year = b.date ? parseInt(b.date.value.slice(0, 4), 10) : 0;
      if (!titles.length) throw new Error('metadata_not_found');
      return { titles: titles.filter(function (v, i, a) { return a.indexOf(v) === i; }), year: year };
    });
}

function metadataTmdb(tmdbId, type) {
  var key = typeof TMDB_API_KEY !== 'undefined' ? TMDB_API_KEY : '';
  var id = String(tmdbId).replace(/^tmdb:/, '');
  if (!key || !/^\d+$/.test(id)) return metadata(tmdbId, type);
  return get('https://api.themoviedb.org/3/' + type + '/' + id + '?language=es-MX&api_key=' + encodeURIComponent(key))
    .then(function (t) {
      var m = JSON.parse(t);
      var titles = [m.title || m.name, m.original_title || m.original_name].filter(Boolean);
      return { titles: titles, year: parseInt((m.release_date || m.first_air_date || '0').slice(0, 4), 10) };
    }).then(function (a) {
      return metadata(tmdbId, type).then(function (b) {
        a.titles = a.titles.concat(b.titles).filter(function (v, i, x) { return x.indexOf(v) === i; });
        return a;
      }, function () { return a; });
    }, function () { return metadata(tmdbId, type); });
}

// Título latino (es-MX) desde la ficha pública de TMDB, sin clave.
function metadataLatino(tmdbId, type) {
  var id = String(tmdbId).replace(/^tmdb:/, '');
  var fallback = function () { return metadataTmdb(tmdbId, type); };
  if (!/^\d+$/.test(id)) return fallback();
  return get('https://www.themoviedb.org/' + type + '/' + id + '?language=es-MX', null, 'okhttp/4.12.0').then(function (html) {
    var t = /<meta property="og:title" content="([^"]+)"/.exec(html);
    var y = /<title>[^<]*\((?:[^)]*?)(\d{4})\)/.exec(html);
    if (!t) return fallback();
    var a = { titles: [decode(t[1])], year: y ? +y[1] : 0 };
    return fallback().then(function (b) {
      a.titles = a.titles.concat(b.titles).filter(function (v, i, x) { return x.indexOf(v) === i; });
      if (!a.year) a.year = b.year;
      return a;
    }, function () { return a; });
  }, fallback);
}


/* ---------- Utilidades ---------- */
var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function b64decode(s) {
  s = String(s).replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+/]/g, '');
  var out = '', bits = 0, val = 0;
  for (var i = 0; i < s.length; i++) {
    val = (val << 6) | B64.indexOf(s.charAt(i));
    bits += 6;
    if (bits >= 8) { bits -= 8; out += String.fromCharCode((val >> bits) & 0xff); }
  }
  return out;
}
function utf8(bin) { try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; } }

function absUrl(base, href) {
  href = decode(String(href || '').trim());
  if (/^https?:/i.test(href)) return href;
  if (/^\/\//.test(href)) return 'https:' + href;
  var origin = /^https?:\/\/[^/]+/.exec(base)[0];
  if (/^\//.test(href)) return origin + href;
  return base.split('?')[0].replace(/[^/]*$/, '') + href;
}

function attr(tag, name) {
  var m = new RegExp('\\s' + name + '\\s*=\\s*"([^"]*)"', 'i').exec(tag) ||
    new RegExp('\\s' + name + "\\s*=\\s*'([^']*)'", 'i').exec(tag);
  return m ? decode(m[1]) : '';
}

/* ---------- Búsqueda en CineHDPlus ---------- */
function parseCards(html) {
  var out = [], re = /<a\s[^>]*href="(https:\/\/cinehdplus\.org\/(pelicula|series-tv)-\d+\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g, m;
  while ((m = re.exec(html))) {
    var alt = /alt="([^"]+)"/.exec(m[3]);
    if (!alt) continue;
    var title = decode(alt[1]).replace(/\s+-\s+(Película|Serie de TV|Serie|Anime)$/i, '').trim();
    var y = /((?:19|20)\d{2})\s*·/.exec(m[3]);
    out.push({ url: m[1], title: title, year: y ? +y[1] : 0, serie: m[2] === 'series-tv' });
  }
  // Página de resultados de búsqueda: enlace vacío con aria-label, y el resto de datos
  // (título original, año) en el bloque que sigue hasta la próxima tarjeta.
  var re2 = /<a\s[^>]*href="(https:\/\/cinehdplus\.org\/(pelicula|series-tv)-\d+\/[^"]+)"[^>]*aria-label="([^"]+)"[^>]*>\s*<\/a>([\s\S]*?)(?=<a\s[^>]*href="https:\/\/cinehdplus\.org\/(?:pelicula|series-tv)-\d+\/[^"]+"[^>]*aria-label=|<\/main>|$)/g;
  while ((m = re2.exec(html))) {
    var orig = /<\/h3>\s*<span[^>]*>\s*([^<]+?)\s*<\/span>/.exec(m[4]);
    var yr = /<\/svg>\s*((?:19|20)\d{2})\s*</.exec(m[4]);
    out.push({
      url: m[1], title: decode(m[3]).trim(), original: orig ? decode(orig[1]).trim() : '',
      year: yr ? +yr[1] : 0, serie: m[2] === 'series-tv'
    });
  }
  var seen = {};
  return out.filter(function (c) { return seen[c.url] ? false : (seen[c.url] = true); });
}

function search(query) {
  return get(BASE + '/?s=' + encodeURIComponent(query), BASE + '/')
    .then(parseCards).catch(function (e) { log('busqueda: ' + e.message); return []; });
}

function findPage(meta, type) {
  var wanted = meta.titles.map(norm);
  var queries = [];
  meta.titles.forEach(function (t) {
    queries.push(t);
    var short = t.split(/[:–-]/)[0].trim();
    if (short.length > 2) queries.push(short);
  });
  queries = queries.filter(function (v, i, a) { return a.indexOf(v) === i; }).slice(0, 6);

  function score(card) {
    if ((type === 'tv') !== card.serie) return -1;
    var names = [card.title, card.original].filter(Boolean).map(norm).filter(Boolean), s = 0;
    names.forEach(function (n) {
      if (wanted.indexOf(n) >= 0) s = Math.max(s, 100);
      else if (wanted.some(function (w) { return n.indexOf(w) === 0 || w.indexOf(n) === 0; })) s = Math.max(s, 60);
    });
    if (!s) return -1;
    if (meta.year && card.year) {
      var d = Math.abs(meta.year - card.year);
      if (d === 0) s += 30; else if (d === 1) s += 15; else if (type !== 'tv') s -= 40;
    }
    return s;
  }

  var i = 0;
  function next() {
    if (i >= queries.length) return Promise.resolve(null);
    return search(queries[i++]).then(function (cards) {
      var best = null, bs = 0;
      cards.forEach(function (c) { var s = score(c); if (s > bs) { bs = s; best = c; } });
      return best && bs >= 60 ? best : next();
    });
  }
  return next();
}

// Página del episodio: /episodio-ID/slug-<T>x<E>/ en la ficha o en la página de la temporada.
function findEpisode(seriesUrl, season, episode) {
  var reEp = new RegExp('href="(https://cinehdplus\\.org/episodio-\\d+/[^"]*-' + season + 'x' + episode + '/?)"');
  return get(seriesUrl, BASE + '/').then(function (html) {
    var m = reEp.exec(html);
    if (m) return m[1];
    var reSeason = /href="(https:\/\/cinehdplus\.org\/temporadas-tv-\d+\/[^"]+)"/g, s, urls = [];
    while ((s = reSeason.exec(html))) if (urls.indexOf(s[1]) < 0) urls.push(s[1]);
    var exact = urls.filter(function (u) { return new RegExp('temporada-' + season + '/?$').test(u); });
    var order = exact.concat(urls.filter(function (u) { return exact.indexOf(u) < 0; }));
    var i = 0;
    function next() {
      if (i >= order.length) return null;
      return get(order[i++], seriesUrl).then(function (h) {
        var e = reEp.exec(h);
        return e ? e[1] : next();
      }, next);
    }
    return next();
  });
}

/* ---------- Servidores de la página ---------- */
function serversFromPage(html) {
  var out = [], re = /<button\b[^>]*data-type="server"[^>]*>/gi, m;
  while ((m = re.exec(html))) {
    var url = attr(m[0], 'data-url');
    if (!url) continue;
    out.push({
      url: absUrl(BASE + '/', url),
      domain: (attr(m[0], 'data-domain') || '').toLowerCase(),
      lang: attr(m[0], 'data-lang') || 'Latino'
    });
  }
  return out;
}

/* ---------- Cadena player.php -> goto.php -> rd.php -> safelink -> servidor ---------- */
var HOST_RE = /^https?:\/\/(?:www\.)?(?:voe\.sx\/(?:e\/)?[A-Za-z0-9]{8,}|[^/?#]+\/e\/[a-z0-9]{12}(?:[/?#]|$)|(?:[a-z0-9-]*streamtape\.[a-z]+|[a-z0-9-]*mixdropp?\.[a-z]+|[a-z0-9-]*streamplay\.[a-z]+)\/(?:e|v|f|d|embed|embed-)[/-]?[A-Za-z0-9]+)/i;
var URL_RE = /https?:\\?\/\\?\/[^\s'"<>()]+/g;

function findTarget(html) {
  var urls = (html.match(URL_RE) || []).map(function (u) { return u.replace(/\\\//g, '/'); });
  for (var i = 0; i < urls.length; i++) if (HOST_RE.test(urls[i])) return urls[i];
  var tokens = html.match(/[A-Za-z0-9+/_-]{24,}={0,2}/g) || [];
  for (var j = 0; j < tokens.length; j++) {
    var v = tokens[j];
    for (var layer = 0; layer < 3; layer++) {
      var dec = b64decode(v);
      if (!dec || /[\x00-\x08]/.test(dec)) break;
      var inner = (dec.match(URL_RE) || []).map(function (u) { return u.replace(/\\\//g, '/'); });
      for (var k = 0; k < inner.length; k++) if (HOST_RE.test(inner[k])) return inner[k];
      v = dec.trim();
    }
  }
  return null;
}

function nextHop(html, base) {
  var meta = /<meta[^>]+http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"'>]+)/i.exec(html);
  if (meta) return absUrl(base, meta[1].replace(/^['"]|['"]$/g, ''));
  var js = /(?:location(?:\.href)?\s*=|location\.(?:replace|assign)\()\s*['"]([^'"]+)['"]/.exec(html);
  return js ? absUrl(base, js[1]) : null;
}

function firstForm(html) {
  var f = /<form\b([^>]*)>([\s\S]*?)<\/form>/i.exec(html);
  if (!f) return null;
  var fields = {}, re = /<(?:input|textarea)\b[^>]*>/gi, m;
  while ((m = re.exec(f[2]))) {
    var name = attr(m[0], 'name');
    if (name) fields[name] = attr(m[0], 'value');
  }
  return { action: attr(' ' + f[1], 'action'), method: (attr(' ' + f[1], 'method') || 'get').toLowerCase(), fields: fields };
}

function resolveChain(playerUrl) {
  var gotoUrl = playerUrl.replace('/player.php', '/goto.php');
  var code = (/[?&]h=([^&]+)/.exec(playerUrl) || [])[1];
  var step = 0;

  function handle(res, referer) {
    if (HOST_RE.test(res.url)) return res.url;
    var found = findTarget(res.text);
    if (found) return found;
    if (step++ >= 8) return null;
    var form = firstForm(res.text);
    if (form) {
      var action = absUrl(res.url, form.action || res.url);
      var p = form.method === 'post'
        ? request(action, { method: 'POST', form: form.fields, referer: res.url })
        : request(action + (action.indexOf('?') < 0 ? '?' : '&') + Object.keys(form.fields).map(function (k) {
          return encodeURIComponent(k) + '=' + encodeURIComponent(form.fields[k]);
        }).join('&'), { referer: res.url });
      return p.then(function (r) { return handle(r, res.url); });
    }
    if (step === 1 && code) {
      return request(absUrl(res.url, 'rd.php'), { method: 'POST', form: { url: code } })
        .then(function (r) { return handle(r, res.url); });
    }
    var hop = nextHop(res.text, res.url);
    if (!hop) return null;
    if (HOST_RE.test(hop)) return hop;
    return request(hop, { referer: res.url }).then(function (r) { return handle(r, res.url); });
  }

  return request(gotoUrl).then(function (r) { return handle(r, null); })
    .catch(function (e) { log('cadena: ' + e.message); return null; });
}

/* ---------- Extractores ---------- */
function rot13(s) {
  return s.replace(/[a-zA-Z]/g, function (c) {
    var b = c <= 'Z' ? 65 : 97;
    return String.fromCharCode((c.charCodeAt(0) - b + 13) % 26 + b);
  });
}

// VOE guarda la fuente en <script type="application/json">["..."]</script> ofuscada:
// rot13 -> quitar marcadores -> base64 -> restar 3 a cada carácter -> invertir -> base64 -> JSON.
function voeDecode(enc) {
  var s = rot13(enc);
  ['@$', '^^', '~@', '%?', '*~', '!!', '#&'].forEach(function (p) { s = s.split(p).join(''); });
  s = b64decode(s);
  s = s.split('').map(function (c) { return String.fromCharCode(c.charCodeAt(0) - 3); }).join('');
  s = b64decode(s.split('').reverse().join(''));
  return JSON.parse(utf8(s));
}

function voe(url) {
  var id = url.replace(/[?#].*$/, '').replace(/\/+$/, '').split('/').pop();
  var start = 'https://voe.sx/e/' + id;
  function page(u, hops) {
    return request(u, { referer: BASE + '/' }).then(function (r) {
      var redir = /window\.location\.href\s*=\s*['"]([^'"]+)['"]/.exec(r.text);
      if (redir && hops < 3 && !/application\/json/.test(r.text)) return page(absUrl(r.url, redir[1]), hops + 1);
      return r;
    });
  }
  return page(start, 0).then(function (r) {
    var json = /<script[^>]*type=["']application\/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*<\/script>/i.exec(r.text);
    if (json) {
      try {
        var data = voeDecode(json[1]);
        var src = data.source || data.direct_access_url;
        if (src) return { url: src, headers: { 'User-Agent': UA, 'Referer': r.url } };
      } catch (e) { log('voe decode: ' + e.message); }
    }
    var hls = /['"]hls['"]\s*:\s*['"]([^'"]+)['"]/.exec(r.text);
    if (hls) {
      var v = /^aHR0c/.test(hls[1]) ? b64decode(hls[1]) : hls[1];
      return { url: v, headers: { 'User-Agent': UA, 'Referer': r.url } };
    }
    var m3u8 = /https?:\/\/[^\s'"<>]+\.m3u8[^\s'"<>]*/.exec(r.text);
    return m3u8 ? { url: m3u8[0], headers: { 'User-Agent': UA, 'Referer': r.url } } : null;
  });
}

function streamtape(url) {
  var id = (/\/(?:e|v)\/([A-Za-z0-9]+)/.exec(url) || [])[1];
  var page = id ? 'https://streamtape.com/e/' + id : url;
  return get(page, BASE + '/').then(function (html) {
    var m = /getElementById\(['"]robotlink['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?\.substring\((\d+)\)(?:\.substring\((\d+)\))?/.exec(html);
    if (!m) return null;
    var tail = m[2].substring(+m[3]);
    if (m[4]) tail = tail.substring(+m[4]);
    var link = 'https:' + (m[1] + tail).replace(/^https?:/, '');
    return { url: link + '&stream=1', headers: { 'User-Agent': UA, 'Referer': 'https://streamtape.com/' } };
  });
}

// Desempaquetador de eval(function(p,a,c,k,e,d){...}) sin usar eval.
function unpack(packed) {
  var m = /\}\('([\s\S]*)',\s*(\d+),\s*(\d+),\s*'([\s\S]*?)'\.split\('\|'\)/.exec(packed);
  if (!m) return null;
  var p = m[1], a = +m[2], c = +m[3], k = m[4].split('|');
  function enc(n) {
    return (n < a ? '' : enc(Math.floor(n / a))) + ((n = n % a) > 35 ? String.fromCharCode(n + 29) : n.toString(36));
  }
  while (c--) if (k[c]) p = p.replace(new RegExp('\\b' + enc(c) + '\\b', 'g'), k[c]);
  return p;
}

function mixdrop(url) {
  var id = (/\/(?:e|f)\/([A-Za-z0-9]+)/.exec(url) || [])[1];
  var page = id ? 'https://mixdrop.ag/e/' + id : url;
  return request(page, { referer: BASE + '/' }).then(function (r) {
    var packed = /eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\('\|'\)[^)]*\)\)/.exec(r.text);
    var code = packed ? unpack(packed[0]) : r.text;
    var w = /MDCore\.wurl\s*=\s*["']([^"']+)["']/.exec(code || '');
    if (!w) return null;
    var origin = /^https?:\/\/[^/]+/.exec(r.url)[0];
    return { url: absUrl(r.url, w[1]), headers: { 'User-Agent': UA, 'Referer': origin + '/' } };
  });
}

var EXTRACTORS = { voe: voe, streamtape: streamtape, mixdrop: mixdrop, mixdropp: mixdrop };

function extractorFor(server, target) {
  if (EXTRACTORS[server.domain]) return EXTRACTORS[server.domain];
  if (/voe|\/e\/[a-z0-9]{12}/i.test(target)) return voe;
  if (/streamtape|tape/i.test(target)) return streamtape;
  if (/mixdrop/i.test(target)) return mixdrop;
  return null;
}

function prettyServer(d) { return d ? d.charAt(0).toUpperCase() + d.slice(1).replace(/pp$/, 'p') : 'Servidor'; }

function resolveServer(server) {
  return resolveChain(server.url).then(function (target) {
    if (!target) { log(server.domain + ': sin enlace'); return null; }
    var ex = extractorFor(server, target);
    if (!ex) { log(server.domain + ': sin extractor para ' + target); return null; }
    return ex(target).then(function (s) {
      if (!s || !s.url) return null;
      return {
        name: NAME,
        title: NAME + ' · ' + server.lang + ' · ' + prettyServer(server.domain),
        url: s.url,
        quality: /\.m3u8/.test(s.url) ? 'Auto' : 'HD',
        headers: s.headers
      };
    });
  }).catch(function (e) { log(server.domain + ': ' + e.message); return null; });
}

/* ---------- Entrada de Nuvio ---------- */
function getStreams(tmdbId, mediaType, season, episode) {
  var type = mediaType === 'tv' || mediaType === 'series' ? 'tv' : 'movie';
  season = parseInt(season, 10) || 1;
  episode = parseInt(episode, 10) || 1;
  return metadataLatino(tmdbId, type).then(function (meta) {
    log('titulos: ' + meta.titles.join(' | '));
    return findPage(meta, type);
  }).then(function (card) {
    if (!card) { log('sin coincidencia'); return null; }
    return type === 'tv' ? findEpisode(card.url, season, episode) : card.url;
  }).then(function (pageUrl) {
    if (!pageUrl) return [];
    return get(pageUrl, BASE + '/').then(function (html) {
      var servers = serversFromPage(html).filter(function (s) { return /player\.php|goto\.php/.test(s.url) || HOST_RE.test(s.url); });
      return Promise.all(servers.map(resolveServer));
    }).then(function (list) { return list.filter(Boolean); });
  }).catch(function (e) { log('error: ' + e.message); return []; });
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { getStreams: getStreams };
  // Uso interno del addon en Vercel (y de las pruebas); Nuvio solo usa getStreams.
  module.exports._internal = {
    get: get, parseCards: parseCards, findPage: findPage, serversFromPage: serversFromPage, findEpisode: findEpisode,
    resolveServer: resolveServer, findTarget: findTarget, firstForm: firstForm,
    voeDecode: voeDecode, unpack: unpack, b64decode: b64decode, decode: decode, HOST_RE: HOST_RE
  };
}
