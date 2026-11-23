const DEFAULTS = {
  enabled: true,
  allFiles: false,
  exts: 'zip rar 7z tar gz xz iso exe msi apk dmg deb rpm mp4 mkv avi mov webm flv wmv mp3 flac wav m4a aac ogg pdf epub psd',
  minSizeMB: 1,
};
const $ = (id) => document.getElementById(id);

async function load() {
  const s = await chrome.storage.local.get(DEFAULTS);
  $('enabled').checked = s.enabled;
  $('allFiles').checked = s.allFiles;
  $('exts').value = s.exts;
  $('exts').disabled = s.allFiles;
  $('minSize').value = s.minSizeMB;
}

async function save() {
  await chrome.storage.local.set({
    enabled: $('enabled').checked,
    allFiles: $('allFiles').checked,
    exts: $('exts').value.trim(),
    minSizeMB: Math.max(0, Number($('minSize').value) || 0),
  });
  $('exts').disabled = $('allFiles').checked;
  chrome.runtime.sendMessage({ type: 'settings-changed' });
}

async function status() {
  const el = $('status');
  const r = await chrome.runtime.sendMessage({ type: 'status' }).catch(() => ({ running: false }));
  el.className = 'status ' + (r.running ? 'on' : 'off');
  el.querySelector('span').textContent = r.running ? 'Connecté à Varika Download' : 'Varika Download n\'est pas lancé';
  $('open').textContent = r.running ? 'Afficher Varika Download' : 'Lancer Varika Download';
}

['enabled', 'allFiles', 'exts', 'minSize'].forEach((id) => $(id).addEventListener('change', save));
$('open').addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ type: 'open' });
  window.close();
});

async function mediaList() {
  const box = $('media');
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab) return;
  const r = await chrome.runtime.sendMessage({ type: 'media-list', tabId: tab.id }).catch(() => ({ items: [] }));
  box.textContent = '';
  if (!r.items || !r.items.length) {
    const p = document.createElement('div');
    p.className = 'm-empty';
    p.textContent = 'Aucune vidéo détectée. Lancez la lecture de la vidéo, puis rouvrez cette fenêtre.';
    box.appendChild(p);
    return;
  }
  r.items.slice(0, 6).forEach((it) => {
    const row = document.createElement('div');
    row.className = 'm-item';
    const label = document.createElement('span');
    label.textContent = it.label;
    label.title = it.url;
    row.appendChild(label);
    let sel = null;
    if (it.qualities.length > 1) {
      sel = document.createElement('select');
      it.qualities.forEach((q) => { const o = document.createElement('option'); o.value = q; o.textContent = q + 'p'; sel.appendChild(o); });
      row.appendChild(sel);
    }
    const b = document.createElement('button');
    b.textContent = 'Télécharger';
    b.onclick = async () => {
      b.disabled = true;
      b.textContent = '…';
      const res = await chrome.runtime.sendMessage({ type: 'media-download', tabId: tab.id, item: { ...it, quality: sel ? sel.value : '' }, title: tab.title });
      b.textContent = res && res.ok ? 'Ajouté ✓' : 'Échec';
    };
    row.appendChild(b);
    box.appendChild(row);
  });
}

load();
status();
mediaList();
