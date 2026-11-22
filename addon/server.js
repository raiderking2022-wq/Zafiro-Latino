/* Zafiro · addon para Nuvio/Stremio (protocolo Stremio), desplegable en Vercel.
 * Catálogos y fichas de CineHDPlus; los enlaces se resuelven con el mismo motor que el
 * proveedor de Nuvio (nuvio/providers/zafiro.js): goto.php -> rd.php -> safelink -> VOE/Streamtape/Mixdrop.
 * No aloja video.
 */
'use strict';
const http = require('node:http');
const { getStreams, _internal: z } = require('../nuvio/providers/zafiro.js');

const VERSION = '1.0.0';
const BASE = 'https://cinehdplus.org';
const PREFIX = 'zaf:';
const pageSize = { movie: 0, series: 0 };

/* ---------- IDs: zaf:p283318~el-vortice  /  zaf:s35783~obi-wan-kenobi[:T:E] ---------- */
function toId(url) {
  const m = /cinehdplus\.org\/(pelicula|series-tv)-(\d+)\/([^/?#]+)/.exec(url);
  return m ? PREFIX + (m[1] === 'pelicula' ? 'p' : 's') + m[2] + '~' + m[3] : null;
}
function fromId(id) {
  const m = /^zaf:([ps])(\d+)~([^:]+)(?::(\d+):(\d+))?$/.exec(id);
  if (!m) return null;
  return {
    url: BASE + '/' + (m[1] === 'p' ? 'pelicula' : 'series-tv') + '-' + m[2] + '/' + m[3] + '/',
    type: m[1] === 'p' ? 'movie' : 'series',
    season: m[4] ? +m[4] : 0, episode: m[5] ? +m[5] : 0
  };
}

/* El proxy de imágenes del sitio (wp-json/rm/v1/img/?src=/xxx.jpg) -> TMDB directo. */
function tmdbImage(src, size) {
  if (!src) return undefined;
  src = z.decode(src);
  const m = /[?&]src=([^&]+)/.exec(src);
  if (m) {
    const p = decodeURIComponent(m[1]);
    return /^https?:/.test(p) ? p : 'https://image.tmdb.org/t/p/' + (size || 'w500') + p;
  }
  return src;
}

/* ---------- Catálogos ---------- */
function cards(html) {
  const out = [], seen = new Set();
  const re = /<a\s[^>]*href="(https:\/\/cinehdplus\.org\/(pelicula|series-tv)-\d+\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
  let m;
  while ((m = re.exec(html))) {
    const img = /<img[^>]+src="([^"]+)"[^>]*alt="([^"]+)"/.exec(m[3]);
    const id = toId(m[1]);
    if (!img || !id || seen.has(id)) continue;
    seen.add(id);
    const year = /((?:19|20)\d{2})\s*·/.exec(m[3]);
    const rating = /tabular-nums[^>]*>\s*([\d.]+)\s*</.exec(m[3]);
    out.push({
      id, type: m[2] === 'pelicula' ? 'movie' : 'series',
      name: z.decode(img[2]).replace(/\s+-\s+(Película|Serie de TV|Serie|Anime)$/i, '').trim(),
      poster: tmdbImage(img[1], 'w342'),
      releaseInfo: year ? year[1] : undefined,
      imdbRating: rating ? rating[1] : undefined
    });
  }
  return out;
}

async function catalog(type, id, search, skip) {
  let url;
  if (search) url = BASE + '/?s=' + encodeURIComponent(search);
  else {
    const section = id === 'zafiro-latino' ? 'idioma/latino' : type === 'series' ? 'series' : 'peliculas';
    const size = pageSize[type] || 24;
    const page = skip ? Math.floor(skip / size) + 1 : 1;
    url = BASE + '/' + section + '/' + (page > 1 ? 'page/' + page + '/' : '');
  }
  const html = await z.get(url, BASE + '/');
  const metas = cards(html).filter(c => c.type === type);
  if (!search && !skip && metas.length) pageSize[type] = metas.length;
  return { metas };
}

/* ---------- Fichas ---------- */
function episodesFrom(html, seriesId) {
  const out = [];
  const re = /href="https:\/\/cinehdplus\.org\/episodio-\d+\/[^"]*?-(\d+)x(\d+)\/?"[^>]*>([\s\S]*?)<\/a>/g;
  let m;
  while ((m = re.exec(html))) {
    const t = /<h3[^>]*>([\s\S]*?)<\/h3>/.exec(m[3]);
    const thumb = /<img[^>]+src="([^"]+)"/.exec(m[3]);
    out.push({
      id: seriesId + ':' + (+m[1]) + ':' + (+m[2]),
      season: +m[1], episode: +m[2],
      title: t ? z.decode(t[1].replace(/<[^>]+>/g, '')).trim() : 'Episodio ' + (+m[2]),
      thumbnail: thumb ? tmdbImage(thumb[1], 'w300') : undefined,
      released: new Date(0).toISOString()
    });
  }
  return out;
}

async function meta(type, id) {
  const info = fromId(id);
  if (!info) return { meta: null };
  const html = await z.get(info.url, BASE + '/');
  const h2 = /<div class="flex-1 min-w-0">\s*<h2[^>]*>\s*([^<]+?)\s*<span[^>]*>\s*\((\d{4})\)/.exec(html);
  const og = /<meta property="og:title" content="([^"]+)"/.exec(html);
  const poster = /aspect-2\/3[^>]*>\s*<img[^>]+src="([^"]+)"/.exec(html);
  const bg = /<img[^>]+src="([^"]+)"[^>]*class="[^"]*opacity-20/.exec(html);
  const plot = /prose-custom[^>]*>\s*<p>([\s\S]*?)<\/p>/.exec(html) ||
    /<meta property="og:description" content="([^"]*)"/.exec(html);
  const genres = [], gre = /href="https:\/\/cinehdplus\.org\/genero\/[^"]+"[^>]*>([^<]+)</g;
  let g;
  const hero = html.slice(Math.max(0, html.indexOf('<div class="flex-1 min-w-0">')), html.indexOf('<div class="flex-1 min-w-0">') + 6000);
  while ((g = gre.exec(hero))) { const n = z.decode(g[1]).trim(); if (n && !genres.includes(n) && genres.length < 6) genres.push(n); }
  const trailer = /data-url="https:\/\/www\.youtube\.com\/embed\/([\w-]+)"/.exec(html);

  const m = {
    id, type,
    name: h2 ? z.decode(h2[1]) : og ? z.decode(og[1]).replace(/^Ver\s+/, '').replace(/\s+Online.*$/, '') : id,
    releaseInfo: h2 ? h2[2] : undefined,
    poster: poster ? tmdbImage(poster[1], 'w500') : undefined,
    background: bg ? tmdbImage(bg[1], 'original') : undefined,
    description: plot ? z.decode(plot[1].replace(/<[^>]+>/g, '')).trim() : undefined,
    genres,
    trailers: trailer ? [{ source: trailer[1], type: 'Trailer' }] : undefined
  };

  if (type === 'series') {
    let videos = episodesFrom(html, id);
    const seasons = [];
    const sre = /href="(https:\/\/cinehdplus\.org\/temporadas-tv-\d+\/[^"]+)"/g;
    let s;
    while ((s = sre.exec(html))) if (!seasons.includes(s[1])) seasons.push(s[1]);
    const pages = await Promise.all(seasons.slice(0, 30).map(u => z.get(u, info.url).catch(() => '')));
    pages.forEach(p => { videos = videos.concat(episodesFrom(p, id)); });
    const seen = new Set();
    m.videos = videos.filter(v => !seen.has(v.id) && seen.add(v.id))
      .sort((a, b) => a.season - b.season || a.episode - b.episode);
  }
  return { meta: m };
}

/* ---------- Enlaces ---------- */
function toStream(s) {
  return {
    name: s.name, title: s.title, url: s.url,
    behaviorHints: { notWebReady: true, bingeGroup: 'zafiro', proxyHeaders: { request: s.headers } },
    headers: s.headers
  };
}

async function streams(type, id) {
  let list;
  if (id.startsWith(PREFIX)) {
    const info = fromId(id);
    if (!info) return { streams: [] };
    let pageUrl = info.url;
    if (type === 'series') {
      pageUrl = await z.findEpisode(info.url, info.season || 1, info.episode || 1);
      if (!pageUrl) return { streams: [] };
    }
    const html = await z.get(pageUrl, BASE + '/');
    const servers = z.serversFromPage(html)
      .filter(s => /player\.php|goto\.php/.test(s.url) || z.HOST_RE.test(s.url));
    list = (await Promise.all(servers.map(z.resolveServer))).filter(Boolean);
  } else {
    // IDs de IMDb (tt123 / tt123:1:2) o TMDB (tmdb:123 / tmdb:123:1:2)
    const p = id.split(':');
    const base = p[0] === 'tmdb' ? p[1] : p[0];
    const rest = p[0] === 'tmdb' ? p.slice(2) : p.slice(1);
    list = await getStreams(base, type === 'series' ? 'tv' : 'movie', rest[0], rest[1]);
  }
  console.log('[zafiro] stream ' + id + ': ' + list.length + ' fuentes');
  return { streams: list.map(toStream) };
}

/* ---------- Servidor ---------- */
function manifest() {
  const extra = [{ name: 'search', isRequired: false }, { name: 'skip', isRequired: false }];
  return {
    id: 'org.zafirolatino.cinehdplus', version: VERSION, name: 'Zafiro',
    description: 'Películas y series de CineHDPlus en español (Latino, Castellano): catálogo, episodios y enlaces VOE/Streamtape/Mixdrop.',
    logo: 'https://www.google.com/s2/favicons?domain=cinehdplus.org&sz=256',
    types: ['movie', 'series'],
    idPrefixes: [PREFIX, 'tt', 'tmdb:'],
    resources: [
      { name: 'catalog', types: ['movie', 'series'] },
      { name: 'meta', types: ['movie', 'series'], idPrefixes: [PREFIX] },
      { name: 'stream', types: ['movie', 'series'], idPrefixes: [PREFIX, 'tt', 'tmdb:'] }
    ],
    catalogs: [
      { type: 'movie', id: 'zafiro-movies', name: 'Zafiro · Películas', extra },
      { type: 'series', id: 'zafiro-series', name: 'Zafiro · Series', extra },
      { type: 'movie', id: 'zafiro-latino', name: 'Zafiro · Latino', extra: [{ name: 'skip', isRequired: false }] }
    ],
    behaviorHints: { configurable: false, configurationRequired: false }
  };
}

async function handler(req, res) {
  const send = (code, data, cache) => {
    if (res.headersSent) return res.end();
    res.writeHead(code, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': cache || 'no-store'
    });
    res.end(JSON.stringify(data));
  };
  try {
    const u = new URL(req.url, 'http://x');
    const path = u.pathname.replace(/^\/api\/index/, '') || '/';
    if (path === '/' || path === '/manifest.json') return send(200, manifest(), 'public, max-age=3600');
    let m;
    if ((m = /^\/catalog\/(movie|series)\/([\w-]+)(?:\/([^/]+))?\.json$/.exec(path))) {
      const extra = new URLSearchParams(m[3] ? decodeURIComponent(m[3]) : '');
      const search = extra.get('search') || u.searchParams.get('search');
      const skip = +(extra.get('skip') || u.searchParams.get('skip') || 0);
      return send(200, await catalog(m[1], m[2], search, skip), 'public, max-age=1800');
    }
    if ((m = /^\/meta\/(movie|series)\/([^/]+)\.json$/.exec(path))) {
      return send(200, await meta(m[1], decodeURIComponent(m[2])), 'public, max-age=3600');
    }
    if ((m = /^\/stream\/(movie|series)\/([^/]+)\.json$/.exec(path))) {
      return send(200, await streams(m[1], decodeURIComponent(m[2])));
    }
    send(404, { error: 'not_found' });
  } catch (e) {
    console.log('[zafiro] ' + req.url + ' -> ' + (e && e.message));
    const p = (req.url || '').split('/')[1];
    send(200, p === 'stream' ? { streams: [] } : p === 'meta' ? { meta: null } : { metas: [] });
  }
}

if (require.main === module) http.createServer(handler).listen(process.env.PORT || 3000);
module.exports = handler;
module.exports._internal = { cards, toId, fromId, tmdbImage, episodesFrom };
