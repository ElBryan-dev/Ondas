// Puente nativo de Ondas (solo en la app Android).
// - Catálogo de YouTube Music con youtubei.js; las peticiones salen por HTTP nativo (sin CORS).
// - Devuelve los mismos formatos JSON que el servidor Python, así la interfaz no cambia.
import { Capacitor, registerPlugin } from '@capacitor/core';
import { Innertube, YTNodes } from 'youtubei.js/web';

const Native = registerPlugin('OndasNative');
const MediaSession = registerPlugin('MediaSession');

// ---------- base64 <-> bytes
function toB64(bytes) {
  let s = '';
  const CH = 0x8000;
  for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
  return btoa(s);
}
function fromB64(b64) {
  const s = atob(b64 || '');
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
async function bodyBytes(body) {
  if (body == null) return null;
  if (typeof body === 'string') return new TextEncoder().encode(body);
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (ArrayBuffer.isView(body)) return new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  if (body instanceof Blob) return new Uint8Array(await body.arrayBuffer());
  if (body instanceof URLSearchParams) return new TextEncoder().encode(body.toString());
  return new TextEncoder().encode(String(body));
}

// fetch compatible con youtubei.js, ejecutado por OkHttp en Android
async function nativeFetch(input, init = {}) {
  const isReq = typeof Request !== 'undefined' && input instanceof Request;
  const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
  let method = (init.method || (isReq ? input.method : 'GET') || 'GET').toUpperCase();
  const headers = {};
  const add = (h) => {
    if (!h) return;
    if (typeof h.forEach === 'function' && !Array.isArray(h)) h.forEach((v, k) => { headers[k.toLowerCase()] = v; });
    else if (Array.isArray(h)) h.forEach(([k, v]) => { headers[k.toLowerCase()] = v; });
    else Object.keys(h).forEach((k) => { headers[k.toLowerCase()] = h[k]; });
  };
  if (isReq) add(input.headers);
  add(init.headers);
  let body = init.body;
  if (body == null && isReq && method !== 'GET' && method !== 'HEAD') {
    try { body = await input.clone().arrayBuffer(); } catch (e) { body = null; }
  }
  const bytes = await bodyBytes(body);
  if (bytes && bytes.length && method === 'GET') method = 'POST';
  const r = await Native.fetch({ url, method, headers, body: bytes && bytes.length ? toB64(bytes) : null });
  const data = fromB64(r.body);
  const resp = new Response(r.status === 204 || r.status === 304 || method === 'HEAD' ? null : data, {
    status: r.status || 500,
    headers: r.headers || {},
  });
  try { Object.defineProperty(resp, 'url', { value: r.url || url }); } catch (e) { /* noop */ }
  return resp;
}

// ---------- YouTube Music
let ytP = null;
function yt() {
  if (!ytP) {
    ytP = Innertube.create({
      fetch: nativeFetch,
      lang: 'es',
      location: 'PE',
      retrieve_player: false,
      generate_session_locally: true,
    }).catch((e) => { ytP = null; throw e; });
  }
  return ytP;
}

// Si la sesión falla (p. ej. tras horas en segundo plano) se crea otra y se reintenta una vez
async function withRetry(fn) {
  try {
    return await fn(await yt());
  } catch (e) {
    console.warn('[ondas] reintentando', e);
    ytP = null;
    return await fn(await yt());
  }
}
const secsOf = (txt) => {
  const m = String(txt || '').match(/(\d+):(\d{2})(?::(\d{2}))?/);
  if (!m) return 0;
  return m[3] ? (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]) : (+m[1]) * 60 + (+m[2]);
};
const T = (x) => {
  if (x == null) return '';
  if (typeof x === 'string') return x;
  if (typeof x.toString === 'function') {
    const s = x.toString();
    if (s && s !== '[object Object]') return s;
  }
  return x.text || '';
};
function thumbOf(t, size = 544) {
  let arr = Array.isArray(t) ? t : t && Array.isArray(t.contents) ? t.contents : t && Array.isArray(t.thumbnails) ? t.thumbnails : [];
  if (!arr.length) return null;
  const best = arr.slice().sort((a, b) => (b.width || 0) - (a.width || 0))[0];
  let u = best.url || '';
  if (u.startsWith('//')) u = 'https:' + u;
  return u.replace(/=w\d+-h\d+/, `=w${size}-h${size}`) || null;
}
const runsWithIds = (text) =>
  ((text && text.runs) || [])
    .map((r) => ({ name: r.text, id: r.endpoint && r.endpoint.payload && r.endpoint.payload.browseId }))
    .filter((r) => r.id && String(r.id).startsWith('UC'));
const yearOf = (s) => { const m = String(s || '').match(/\b(19|20)\d{2}\b/); return m ? m[0] : null; };
const isExplicit = (i) => ((i.badges || [])).some((b) => /explicit/i.test(b.icon_type || b.label || ''));

function songFrom(i, extra = {}) {
  if (!i) return null;
  if (i.primary) i = i.primary; // PlaylistPanelVideoWrapper
  const ep = i.endpoint && i.endpoint.payload;
  const id = i.video_id || (i.item_type === 'song' || i.item_type === 'video' || i.item_type === 'non_music_track' ? i.id : null) || (ep && ep.videoId);
  if (!id || String(id).length !== 11) return null;
  let arts = (i.artists || i.authors || []).map((a) => ({ name: a.name, id: a.channel_id || null })).filter((a) => a.name);
  if (!arts.length && i.author) arts = [{ name: typeof i.author === 'string' ? i.author : i.author.name, id: (i.author && i.author.channel_id) || null }];
  if (!arts.length && extra.artists) arts = extra.artists;
  return {
    id,
    title: T(i.title) || i.name || '',
    artists: arts,
    artist: arts.map((a) => a.name).join(', '),
    album: (i.album && i.album.name) || extra.album || '',
    albumId: (i.album && i.album.id) || extra.albumId || null,
    duration: (i.duration && i.duration.seconds) || 0,
    thumb: thumbOf(i.thumbnail || i.thumbnails) || extra.thumb || null,
    explicit: isExplicit(i),
  };
}
function itemFrom(i) {
  if (!i) return null;
  const type = i.item_type;
  if (type === 'song' || type === 'video' || type === 'non_music_track' || i.video_id) {
    const s = songFrom(i);
    return s ? { kind: 'song', ...s } : null;
  }
  if (type === 'album') {
    if (!i.id) return null;
    const ar = (i.artists && i.artists[0] && i.artists[0].name) || (i.author && i.author.name) || '';
    return { kind: 'album', id: i.id, title: T(i.title) || i.name || '', artist: ar, year: i.year || yearOf(T(i.subtitle)), type: 'Álbum', thumb: thumbOf(i.thumbnail || i.thumbnails) };
  }
  if (type === 'playlist') {
    if (!i.id) return null;
    const id = String(i.id).replace(/^VL/, '');
    return { kind: 'playlist', id, title: T(i.title) || i.name || '', sub: (i.author && i.author.name) || T(i.subtitle) || '', thumb: thumbOf(i.thumbnail || i.thumbnails) };
  }
  if (type === 'artist' || type === 'library_artist') {
    if (!i.id) return null;
    return { kind: 'artist', id: i.id, name: T(i.title) || i.name || '', thumb: thumbOf(i.thumbnail || i.thumbnails), subs: i.subscribers || null };
  }
  return null;
}
const strip = ({ kind, ...rest }) => rest;
const shelfItems = (search) => {
  const sh = (search.contents || []).find((c) => Array.isArray(c.contents));
  return sh ? sh.contents : [];
};

function cardTop(sh) {
  const p = (sh.on_tap && sh.on_tap.payload) || {};
  const sub = T(sh.subtitle);
  const parts = sub.split('•').map((x) => x.trim()).filter(Boolean);
  const thumb = thumbOf(sh.thumbnail);
  const title = T(sh.title);
  if (p.videoId) {
    const artists = runsWithIds(sh.subtitle);
    return { kind: 'song', id: p.videoId, title, artists, artist: artists.map((a) => a.name).join(', ') || parts[1] || '', album: '', albumId: null, duration: secsOf(parts[parts.length - 1]), thumb, explicit: false, label: parts[0] || 'Canción' };
  }
  const bid = p.browseId || '';
  if (bid.startsWith('UC')) return { kind: 'artist', id: bid, name: title, thumb, subs: parts[1] || null, label: parts[0] || 'Artista' };
  if (bid.startsWith('MPRE')) return { kind: 'album', id: bid, title, artist: parts[1] || '', year: yearOf(sub), type: parts[0] || 'Álbum', thumb, label: parts[0] || 'Álbum' };
  if (bid.startsWith('VL')) return { kind: 'playlist', id: bid.slice(2), title, sub: parts.slice(1).join(' · '), thumb, label: 'Playlist' };
  return null;
}

async function search(q) {
  return withRetry(async (c) => {
    const out = { top: null, songs: [], videos: [], albums: [], artists: [], playlists: [], corrected: null };
    const seen = new Set();
    const add = (x, isVideo) => {
      if (!x || seen.has(x.kind + x.id)) return;
      seen.add(x.kind + x.id);
      const { kind, ...rest } = x;
      if (kind === 'song') (isVideo ? out.videos : out.songs).push(rest);
      else if (kind === 'album') out.albums.push(rest);
      else if (kind === 'artist') out.artists.push(rest);
      else if (kind === 'playlist') out.playlists.push(rest);
    };
    const s = await c.music.search(q);
    try {
      const sr = s.showing_results_for;
      if (sr) out.corrected = T(sr.corrected_query);
      const dy = s.did_you_mean;
      if (dy && !out.corrected) out.didYouMean = T(dy.corrected_query);
    } catch (e) { /* noop */ }
    for (const sh of s.contents || []) {
      if (sh.type === 'MusicCardShelf') {
        const top = cardTop(sh);
        if (top) {
          out.top = top;
          seen.add(top.kind + top.id);
        }
        for (const it of sh.contents || []) add(itemFrom(it), it.item_type === 'video');
      } else if (Array.isArray(sh.contents)) {
        for (const it of sh.contents) add(itemFrom(it), it.item_type === 'video');
      }
    }
    if (out.songs.length < 6) {
      try {
        const f = await c.music.search(q, { type: 'song' });
        for (const it of shelfItems(f)) add(itemFrom(it), false);
      } catch (e) { /* noop */ }
    }
    if (!out.top) {
      const s0 = out.songs[0] || out.videos[0];
      if (out.artists[0] && (!s0 || out.artists[0].name.toLowerCase() === q.trim().toLowerCase())) out.top = { kind: 'artist', ...out.artists[0], label: 'Artista' };
      else if (s0) out.top = { kind: 'song', ...s0, label: 'Canción' };
    }
    if (out.top && out.top.kind === 'song') out.songs = out.songs.filter((x) => x.id !== out.top.id);
    out.songs = out.songs.slice(0, 40);
    return out;
  });
}

async function suggest(q) {
  return withRetry(async (c) => {
    const secs = await c.music.getSearchSuggestions(q);
    const items = [];
    const quick = [];
    for (const sec of secs || []) {
      for (const x of sec.contents || []) {
        if (x.suggestion) items.push(T(x.suggestion));
        else {
          const it = itemFrom(x);
          if (it) quick.push(it);
        }
      }
    }
    return { items: items.slice(0, 8), quick: quick.slice(0, 4) };
  });
}

async function charts() {
  return withRetry(async (c) => {
    const ep = new YTNodes.NavigationEndpoint({ browseEndpoint: { browseId: 'FEmusic_charts' } });
    const r = await ep.call(c.actions, { client: 'YTMUSIC', parse: true });
    const shelves = r && r.contents_memo ? r.contents_memo.getType(YTNodes.MusicCarouselShelf) : [];
    const out = { songs: [], videos: [], artists: [], shelves: [] };
    for (const sh of shelves || []) {
      const title = T(sh.header && sh.header.title);
      const items = (sh.contents || []).map(itemFrom).filter(Boolean);
      if (!items.length) continue;
      const k = items[0].kind;
      if (k === 'song') {
        const list = items.filter((x) => x.kind === 'song').map(strip);
        if (/v[ií]deo/i.test(title)) { if (!out.videos.length) out.videos = list; }
        else if (!out.songs.length) out.songs = list;
        else out.videos.push(...list);
      } else if (k === 'artist' && !out.artists.length) out.artists = items.filter((x) => x.kind === 'artist').map(strip);
      else out.shelves.push({ title, items: items.slice(0, 20) });
    }
    if (!out.songs.length && out.videos.length) { out.songs = out.videos; out.videos = []; }
    return out;
  });
}

async function album(id) {
  return withRetry(async (c) => {
  const a = await c.music.getAlbum(id);
  const h = a.header || {};
  const title = T(h.title);
  const artists = runsWithIds(h.strapline_text_one).length ? runsWithIds(h.strapline_text_one) : runsWithIds(h.subtitle);
  const artistName = T(h.strapline_text_one) || (h.author && h.author.name) || artists.map((x) => x.name).join(', ');
  const thumb = thumbOf(h.thumbnail || h.thumbnails || a.background);
  const sub = T(h.subtitle);
  const tracks = (a.contents || []).map((t) => songFrom(t, { album: title, albumId: id, thumb, artists: artists.length ? artists : [{ name: artistName, id: null }] })).filter(Boolean);
  return { id, title, artist: artistName, artists, year: h.year || yearOf(sub), type: (sub.split('•')[0] || 'Álbum').trim() || 'Álbum', thumb, duration: h.total_duration || T(h.second_subtitle).split('•').pop().trim() || null, tracks };
  });
}

async function artist(id) {
  return withRetry(async (c) => {
  const a = await c.music.getArtist(id);
  const h = a.header || {};
  const out = { id, name: T(h.title), thumb: thumbOf(h.thumbnail || h.foreground_thumbnail), description: T(h.description), subs: null, songs: [], albums: [], singles: [], related: [] };
  for (const s of a.sections || []) {
    const title = T(s.title || (s.header && s.header.title)).toLowerCase();
    const items = (s.contents || []).map(itemFrom).filter(Boolean);
    if (!items.length) continue;
    const kind = items[0].kind;
    if (kind === 'song' && !out.songs.length) out.songs = items.filter((x) => x.kind === 'song').map(strip).slice(0, 10);
    else if (kind === 'album') {
      const list = items.filter((x) => x.kind === 'album').map(strip);
      if (/sencill|single|ep\b/.test(title)) out.singles.push(...list);
      else if (!out.albums.length) out.albums = list;
      else out.singles.push(...list);
    } else if (kind === 'artist' && !out.related.length) out.related = items.filter((x) => x.kind === 'artist').map(strip);
  }
  return out;
  });
}

async function playlist(id) {
  return withRetry(async (c) => {
  const p = await c.music.getPlaylist(id);
  const h = p.header || {};
  return { id, title: T(h.title), sub: T(h.strapline_text_one) || T(h.subtitle), thumb: thumbOf(h.thumbnail || h.thumbnails || p.background), tracks: (p.contents || []).map((t) => songFrom(t)).filter(Boolean) };
  });
}

async function radio(vid) {
  return withRetry(async (c) => {
  const panel = await c.music.getUpNext(vid, true);
  return { tracks: (panel.contents || []).map((t) => songFrom(t)).filter(Boolean) };
  });
}

async function lyrics(vid) {
  const c = await yt();
  try {
    const sh = await c.music.getLyrics(vid);
    const txt = sh ? T(sh.description) : '';
    return { lyrics: txt || null, source: sh ? T(sh.footer) : null, synced: false };
  } catch (e) {
    return { lyrics: null };
  }
}

async function home() {
  return withRetry(async (c) => {
  const hf = await c.music.getHomeFeed();
  const shelves = [];
  for (const s of hf.sections || []) {
    const title = T((s.header && s.header.title) || s.title);
    const items = (s.contents || []).map(itemFrom).filter(Boolean).slice(0, 20);
    if (title && items.length) shelves.push({ title, items });
  }
  return { shelves };
  });
}

async function fetchJSON(url) {
  const r = await nativeFetch(url, { headers: { accept: 'application/json' } });
  if (!r.ok) throw new Error('Error ' + r.status);
  return r.json();
}

const friendly = (e) => {
  const m = String((e && e.message) || e || '');
  if (/network|timeout|Unable to resolve|failed to connect|UnknownHost/i.test(m)) return 'Sin conexión a internet';
  return 'YouTube Music no respondió. Intenta de nuevo.';
};

async function api(path) {
  const u = new URL(path, 'https://x');
  const p = u.pathname.split('/').filter(Boolean).map(decodeURIComponent); // ['api', name, id]
  try {
    switch (p[1]) {
      case 'search': return await search(u.searchParams.get('q') || '');
      case 'suggest': return await suggest(u.searchParams.get('q') || '');
      case 'charts': return await charts();
      case 'album': return await album(p[2]);
      case 'artist': return await artist(p[2]);
      case 'playlist': return await playlist(p[2]);
      case 'radio': return await radio(p[2]);
      case 'lyrics': return await lyrics(p[2]);
      case 'home': return await home();
      case 'ia': return await fetchJSON(u.searchParams.get('u'));
      case 'health': return { ok: true, app: 'ondas', native: true };
      default: throw new Error('Ruta desconocida: ' + path);
    }
  } catch (e) {
    console.error('[ondas]', path, e);
    throw new Error(friendly(e));
  }
}

window.OndasBridge = {
  native: Capacitor.isNativePlatform(),
  api,
  fetchJSON,
  nativeFetch,
  media: MediaSession,
  plugin: Native,
};
