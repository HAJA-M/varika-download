/*
 * Varika Download — extension navigateur (Chrome, Edge, Opera, Brave, Vivaldi…)
 *
 * 1. Interception : quand un téléchargement démarre dans le navigateur et qu'il
 *    correspond aux types de fichiers choisis, on le met en pause, on l'envoie à
 *    Varika Download (avec cookies + page d'origine), puis on l'annule dans le
 *    navigateur. Si Varika Download refuse le lien, le navigateur reprend la main.
 * 2. Clic droit : « Télécharger avec Varika Download » sur un lien ou un média.
 * 3. Si le logiciel est fermé, on le lance via le protocole varika://.
 */

const APP = 'http://127.0.0.1:47821';
const DEFAULTS = {
  enabled: true,
  allFiles: false,
  exts: 'zip rar 7z tar gz xz iso exe msi apk dmg deb rpm mp4 mkv avi mov webm flv wmv mp3 flac wav m4a aac ogg pdf epub psd',
  minSizeMB: 1,
};

const getSettings = () => chrome.storage.local.get(DEFAULTS);
// Journal de diagnostic : chrome.storage.session.get('vdlog') dans la console du service worker
const vdlog = [];
const log = (...a) => {
  vdlog.push(new Date().toISOString().slice(11, 19) + ' ' + a.join(' '));
  if (vdlog.length > 100) vdlog.shift();
  chrome.storage.session.set({ vdlog }).catch(() => {});
};

/* ------------------------------------------------------------ communication */
async function call(path, body, timeoutMs = 20000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(APP + path, {
      method: body ? 'POST' : 'GET',
      headers: Object.assign({ 'X-Varika-Client': 'extension' }, body ? { 'Content-Type': 'application/json' } : {}),
      body: body ? JSON.stringify(body) : undefined,
      signal: ctrl.signal,
      cache: 'no-store',
    });
    return await r.json();
  } finally {
    clearTimeout(timer);
  }
}

async function isAppRunning() {
  try {
    const r = await call('/ping', null, 1500);
    return r.app === 'Varika Download';
  } catch {
    return false;
  }
}

async function cookiesFor(url) {
  try {
    const list = await chrome.cookies.getAll({ url });
    return list.map((c) => `${c.name}=${c.value}`).join('; ');
  } catch {
    return '';
  }
}

async function sendToApp({ url, filename, referrer, kind, title, quality }) {
  return call('/add', {
    url,
    filename: filename || '',
    referrer: referrer || '',
    kind: kind || '',
    title: title || '',
    quality: quality || '',
    cookies: await cookiesFor(url),
    userAgent: navigator.userAgent,
  }, 45000);
}

/* Lance le logiciel via varika:// (le navigateur demande une confirmation). */
async function launchApp(params = '') {
  const target = 'varika://' + (params || 'open');
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (tab && /^https?:/i.test(tab.url || '')) {
      await chrome.tabs.update(tab.id, { url: target });   // la page reste affichée
      return;
    }
  } catch { /* on essaie autrement */ }
  chrome.tabs.create({ url: target });
}

function notify(title, message, buttons) {
  const opts = { type: 'basic', iconUrl: 'icons/icon-128.png', title, message: message || '', priority: 1 };
  if (buttons) opts.buttons = buttons;
  try {
    chrome.notifications.create('', opts);
  } catch {
    delete opts.buttons;                                // Opera : pas de boutons
    chrome.notifications.create('', opts);
  }
}

chrome.notifications.onButtonClicked.addListener(() => launchApp());

async function updateBadge() {
  const s = await getSettings();
  await chrome.action.setBadgeBackgroundColor({ color: '#0A0B0A' });
  await chrome.action.setBadgeText({ text: s.enabled ? '' : 'OFF' });
}

/* ------------------------------------------------------------ interception */
function fileNameOf(item) {
  const fromPath = (item.filename || '').split(/[\\/]/).pop();
  if (fromPath) return fromPath;
  try {
    return decodeURIComponent(new URL(item.finalUrl || item.url).pathname.split('/').pop() || '');
  } catch {
    return '';
  }
}

function shouldIntercept(item, s) {
  if (!s.enabled) return false;
  const url = item.finalUrl || item.url || '';
  if (!/^https?:\/\//i.test(url)) return false;          // blob:, data:… restent au navigateur
  if (item.byExtensionId) return false;                   // lancé par une extension
  if (item.incognito) return false;                       // navigation privée : on ne touche à rien
  const size = item.totalBytes > 0 ? item.totalBytes : item.fileSize;
  if (s.minSizeMB > 0 && size > 0 && size < s.minSizeMB * 1048576) return false;
  if (s.allFiles) return true;
  const ext = (/\.([a-z0-9]{1,8})$/i.exec(fileNameOf(item)) || [])[1];
  const list = new Set(String(s.exts).toLowerCase().split(/[\s,;]+/).filter(Boolean));
  return !!ext && list.has(ext.toLowerCase());
}

const seen = new Set();

async function takeOver(item) {
  if (seen.size > 500) seen.clear();                     // E3 : mémoire bornée
  if (seen.has(item.id) || item.state !== 'in_progress') return;
  const s = await getSettings();
  if (seen.has(item.id)) return;
  if (!shouldIntercept(item, s)) {
    // Sans nom de fichier, on attend : le nom réel (Content-Disposition) arrive plus tard.
    if (item.filename) { seen.add(item.id); log('ignoré', item.url); }
    return;
  }
  seen.add(item.id);
  log('capture', item.url);

  try {
    await chrome.downloads.pause(item.id);
  } catch (e) {
    log('pause impossible', e.message);
    return;                                               // déjà terminé ou annulé
  }

  let res;
  try {
    // Nom choisi par le navigateur s'il est connu ; sinon le logiciel lira Content-Disposition.
    const name = (item.filename || '').split(/[\\/]/).pop();
    res = await sendToApp({ url: item.finalUrl || item.url, filename: name, referrer: item.referrer });
  } catch (e) {
    res = { ok: false, offline: e.name !== 'AbortError', error: e.name === 'AbortError' ? 'délai dépassé' : 'non lancé' };
  }

  log('réponse', JSON.stringify(res));
  if (res.ok) {
    try {
      await chrome.downloads.cancel(item.id);
      await chrome.downloads.erase({ id: item.id });     // disparaît de la liste du navigateur
    } catch { /* rien */ }
    return;
  }

  try {
    await chrome.downloads.resume(item.id);               // le navigateur reprend la main
  } catch { /* rien */ }
  if (res.offline) {
    notify('Varika Download est fermé', 'Le fichier est téléchargé par le navigateur. Lancez Varika Download pour accélérer les prochains.',
      [{ title: 'Lancer Varika Download' }]);
  } else {
    notify('Téléchargé par le navigateur', `Varika Download n'a pas pu reprendre ce lien (${res.error || 'erreur'}).`);
  }
}

// Trois occasions de capturer, selon le navigateur et le moment où le nom est connu :
chrome.downloads.onCreated.addListener((item) => takeOver(item));

chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  suggest();                                              // on ne change rien au nom
  takeOver(item);
});

chrome.downloads.onChanged.addListener(async (delta) => {
  if (!delta.filename || seen.has(delta.id)) return;
  const [item] = await chrome.downloads.search({ id: delta.id });
  if (item) takeOver(item);
});

/* ------------------------------------------------------------ clic droit */
function createMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'vd-link', title: 'Télécharger avec Varika Download', contexts: ['link'] });
    chrome.contextMenus.create({ id: 'vd-media', title: 'Télécharger ce média avec Varika Download', contexts: ['image', 'video', 'audio'] });
  });
}

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.menuItemId === 'vd-link' ? info.linkUrl : info.srcUrl;
  if (!/^https?:\/\//i.test(url || '')) {
    notify('Lien non pris en charge', 'Seuls les liens http(s) peuvent être envoyés à Varika Download.');
    return;
  }
  const referrer = info.pageUrl || (tab && tab.url) || '';
  if (!(await isAppRunning())) {
    launchApp(`add?url=${encodeURIComponent(url)}&referrer=${encodeURIComponent(referrer)}`);
    return;
  }
  try {
    const r = await sendToApp({ url, referrer });
    if (!r.ok) notify('Échec de l\'envoi', r.error || 'Lien refusé');
  } catch {
    notify('Échec de l\'envoi', 'Varika Download ne répond pas.');
  }
});

/* ------------------------------------------------------------ détection des vidéos */
// Sites exclus : leurs conditions interdisent le téléchargement (et le Chrome Web Store
// refuse les extensions qui téléchargent depuis YouTube).
const BLOCKED = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|googlevideo\.com)$/i;
const MEDIA_EXT = /\.(mp4|webm|mkv|mov|m4v|flv|avi|wmv|mp3|m4a|ogg|oga|opus|wav|flac|m3u8)(?:$|[?#])/i;
const MIN_FILE = 300 * 1024;                              // ignore les petits extraits
const media = new Map();                                  // tabId -> Map(url -> entrée)

const hostOf = (u) => { try { return new URL(u).hostname; } catch { return ''; } };
const header = (hs, name) => (hs || []).find((h) => h.name.toLowerCase() === name)?.value || '';

function classify(url, mime) {
  mime = (mime || '').toLowerCase().split(';')[0].trim();
  if (mime.includes('mpegurl') || /\.m3u8(?:$|[?#])/i.test(url)) return 'hls';
  if (mime === 'video/mp2t' || mime === 'video/iso.segment' || /\.(ts|m4s)(?:$|[?#])/i.test(url)) return null;
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  if ((!mime || mime === 'application/octet-stream' || mime === 'binary/octet-stream') && MEDIA_EXT.test(url)) {
    return /\.(mp3|m4a|ogg|oga|opus|wav|flac)(?:$|[?#])/i.test(url) ? 'audio' : 'video';
  }
  return null;
}

async function saveTab(tabId) {
  const list = [...(media.get(tabId) || new Map()).values()];
  await chrome.storage.session.set({ ['media:' + tabId]: list }).catch(() => {});
  const shown = visibleMedia(list).length;
  const { enabled } = await getSettings();               // E12 : « OFF » reste visible si la capture est coupée
  chrome.action.setBadgeText({ tabId, text: !enabled ? 'OFF' : shown ? String(shown) : '' }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ tabId, color: '#7ACC2E' }).catch(() => {});
}

async function tabMedia(tabId) {
  if (!media.has(tabId)) {                                // le service worker a pu être relancé
    const saved = (await chrome.storage.session.get('media:' + tabId))['media:' + tabId] || [];
    media.set(tabId, new Map(saved.map((m) => [m.url, m])));
  }
  return media.get(tabId);
}

// Les playlists filles d'une playlist maître et les flux en direct ne sont pas proposés seuls.
function visibleMedia(list) {
  const children = new Set(list.flatMap((m) => (m.variants || []).map((v) => v.url)));
  return list.filter((m) => !children.has(m.url) && !m.live && !m.error)
    .sort((a, b) => (a.kind === 'hls' ? 0 : a.kind === 'video' ? 1 : 2) - (b.kind === 'hls' ? 0 : b.kind === 'video' ? 1 : 2)
      || (b.maxHeight || 0) - (a.maxHeight || 0) || (b.size || 0) - (a.size || 0));
}

async function inspectPlaylist(entry, tabId) {
  try {
    const r = await fetch(entry.url, { credentials: 'include', cache: 'no-store' });
    const text = await r.text();
    if (!text.startsWith('#EXTM3U')) { entry.error = 'pas une playlist'; return; }
    if (text.includes('#EXT-X-STREAM-INF')) {
      const lines = text.split(/\r?\n/);
      entry.variants = [];
      lines.forEach((l, i) => {
        if (!l.startsWith('#EXT-X-STREAM-INF')) return;
        const uri = lines.slice(i + 1).find((x) => x.trim() && !x.startsWith('#'));
        const res = /RESOLUTION=(\d+)x(\d+)/.exec(l);
        const bw = /BANDWIDTH=(\d+)/.exec(l);
        if (uri) entry.variants.push({ url: new URL(uri.trim(), entry.url).href, height: res ? +res[2] : 0, bandwidth: bw ? +bw[1] : 0 });
      });
      entry.variants.sort((a, b) => b.height - a.height || b.bandwidth - a.bandwidth);
      entry.maxHeight = entry.variants[0]?.height || 0;
    } else {
      entry.live = !text.includes('#EXT-X-ENDLIST');
      entry.duration = [...text.matchAll(/#EXTINF:([\d.]+)/g)].reduce((a, m) => a + parseFloat(m[1]), 0);
    }
    entry.drm = /METHOD=SAMPLE-AES|KEYFORMAT="(?!identity)/i.test(text);
    if (entry.drm) entry.error = 'protégé (DRM)';
  } catch (e) {
    entry.error = String(e);
  }
  await saveTab(tabId);
}

chrome.webRequest.onHeadersReceived.addListener((d) => {
  if (d.tabId < 0 || d.statusCode >= 400 || !/^https?:/i.test(d.url)) return;
  if (BLOCKED.test(hostOf(d.url)) || BLOCKED.test(hostOf(d.initiator || ''))) return;
  const kind = classify(d.url, header(d.responseHeaders, 'content-type'));
  if (!kind) return;
  const range = /\/(\d+)\s*$/.exec(header(d.responseHeaders, 'content-range'));
  const size = range ? +range[1] : +header(d.responseHeaders, 'content-length') || 0;
  if (kind !== 'hls' && size && size < MIN_FILE) return;
  (async () => {
    const map = await tabMedia(d.tabId);
    if (map.has(d.url)) return;
    const entry = { url: d.url, kind, size, mime: header(d.responseHeaders, 'content-type').split(';')[0], time: Date.now() };
    map.set(d.url, entry);
    if (map.size > 40) map.delete(map.keys().next().value);
    log('média détecté', kind, d.url);
    if (kind === 'hls') await inspectPlaylist(entry, d.tabId);
    else await saveTab(d.tabId);
  })();
}, { urls: ['<all_urls>'], types: ['media', 'xmlhttprequest', 'other', 'object', 'sub_frame'] }, ['responseHeaders']);

chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === 'loading' && info.url) {           // nouvelle page : on repart de zéro
    media.set(tabId, new Map());
    saveTab(tabId);
  }
});
chrome.tabs.onRemoved.addListener((tabId) => {
  media.delete(tabId);
  chrome.storage.session.remove('media:' + tabId).catch(() => {});
});

function label(m) {
  const size = m.size ? ` · ${(m.size / 1048576).toFixed(m.size < 10485760 ? 1 : 0).replace('.', ',')} Mo` : '';
  const dur = m.duration ? ` · ${Math.floor(m.duration / 60)} min ${String(Math.round(m.duration % 60)).padStart(2, '0')}` : '';
  if (m.kind === 'hls') return `Streaming HLS${m.maxHeight ? ' · jusqu\'à ' + m.maxHeight + 'p' : ''}${dur}`;
  const ext = (/\.([a-z0-9]{2,4})(?:$|[?#])/i.exec(m.url) || [])[1] || (m.mime || '').split('/')[1] || '';
  return `${m.kind === 'audio' ? 'Audio' : 'Vidéo'} ${ext.toUpperCase()}${size}`;
}

async function candidates(tabId, src) {
  const list = visibleMedia([...(await tabMedia(tabId)).values()]).map((m) => ({
    url: m.url, kind: m.kind === 'hls' ? 'hls' : 'video', label: label(m),
    qualities: (m.variants || []).map((v) => v.height).filter((h, i, a) => h && a.indexOf(h) === i),
  }));
  if (src && /^https?:/i.test(src) && !BLOCKED.test(hostOf(src)) && !list.some((c) => c.url === src)) {
    const kind = classify(src, '') === 'hls' ? 'hls' : 'video';
    list.unshift({ url: src, kind, label: kind === 'hls' ? 'Streaming HLS' : 'Vidéo de ce lecteur', qualities: [] });
  }
  return list;
}

async function downloadMedia(item, tab, title) {
  const payload = { url: item.url, kind: item.kind, quality: item.quality || '', title: title || tab?.title || '',
    referrer: tab?.url || '' };
  if (!(await isAppRunning())) {
    const q = new URLSearchParams({ url: payload.url, referrer: payload.referrer, kind: payload.kind, title: payload.title, quality: payload.quality });
    await launchApp('add?' + q.toString());
    return { ok: true, launched: true };
  }
  try {
    return await sendToApp(payload);
  } catch {
    return { ok: false, error: 'Varika Download ne répond pas' };
  }
}

/* ------------------------------------------------------------ messages du popup */
chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  (async () => {
    if (msg.type === 'status') reply({ running: await isAppRunning() });
    else if (msg.type === 'open') {
      if (await isAppRunning()) await call('/show', {}, 3000).catch(() => {});
      else await launchApp();
      reply({ ok: true });
    } else if (msg.type === 'media-list') {
      reply({ items: await candidates(msg.tabId, null) });
    } else if (msg.type === 'media-for-player') {
      reply({ items: await candidates(_sender.tab.id, msg.src), blocked: BLOCKED.test(hostOf(_sender.tab.url || '')) });
    } else if (msg.type === 'media-download') {
      const tab = msg.tabId ? await chrome.tabs.get(msg.tabId) : _sender.tab;
      reply(await downloadMedia(msg.item, tab, msg.title));
    } else if (msg.type === 'settings-changed') {
      await updateBadge();
      reply({ ok: true });
    }
  })();
  return true;                                            // réponse asynchrone
});

chrome.runtime.onInstalled.addListener(() => { createMenus(); updateBadge(); isAppRunning(); });
chrome.runtime.onStartup.addListener(() => { updateBadge(); isAppRunning(); });
