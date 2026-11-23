/*
 * Varika Download — bouton « Télécharger cette vidéo » au-dessus des lecteurs vidéo,
 * comme le panneau d'IDM. Il apparaît quand la souris passe sur une vidéo ou quand
 * elle démarre, et propose les flux détectés par l'extension (qualités HLS, fichiers MP4…).
 */
(() => {
  if (window.__varikaVideo) return;
  window.__varikaVideo = true;

  const MIN_W = 220, MIN_H = 120;
  let host, root, btn, panel, current = null, hideTimer = null, panelOpen = false;

  const ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19h14"/></svg>';

  function build() {
    host = document.createElement('varika-download-ui');
    host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;top:0;left:0;width:0;height:0;';
    root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `
      <style>
        :host{all:initial}
        *{box-sizing:border-box;font-family:"Segoe UI",system-ui,sans-serif}
        .btn{position:fixed;display:none;align-items:center;gap:7px;height:32px;padding:0 12px 0 10px;border:0;border-radius:9px;
             background:#BFFE6F;color:#0A0B0A;font-size:13px;font-weight:600;cursor:pointer;box-shadow:0 6px 18px rgba(0,0,0,.35);
             opacity:.96;transition:opacity .15s,transform .15s}
        .btn:hover{opacity:1;transform:translateY(-1px)}
        .btn.drm{background:#2b2f2a;color:#cfd5cb;cursor:default}
        .panel{position:fixed;display:none;width:300px;max-height:320px;overflow:auto;background:#141714;color:#eef1ea;
               border:1px solid #333a32;border-radius:12px;box-shadow:0 18px 40px rgba(0,0,0,.5);padding:8px}
        .head{display:flex;justify-content:space-between;align-items:center;padding:4px 6px 8px;font-size:12px;color:#959e91}
        .head b{color:#eef1ea;font-size:13px}
        .x{border:0;background:none;color:#959e91;cursor:pointer;font-size:16px;line-height:1}
        .item{display:flex;align-items:center;gap:8px;padding:8px;border-radius:9px}
        .item:hover{background:#1c211c}
        .item span{flex:1;font-size:12.5px;line-height:1.35;word-break:break-word}
        .item select{background:#0b0c0b;color:#eef1ea;border:1px solid #333a32;border-radius:7px;font-size:12px;padding:3px}
        .item button{border:0;border-radius:8px;background:#BFFE6F;color:#0A0B0A;font-weight:600;font-size:12px;padding:6px 10px;cursor:pointer}
        .msg{padding:10px 8px;font-size:12.5px;color:#959e91;line-height:1.45}
        .ok{color:#BFFE6F}
      </style>
      <button class="btn" part="btn">${ICON}<span>Télécharger cette vidéo</span></button>
      <div class="panel"></div>`;
    btn = root.querySelector('.btn');
    panel = root.querySelector('.panel');
    btn.addEventListener('click', onClick);
    btn.addEventListener('mouseenter', () => clearTimeout(hideTimer));
    panel.addEventListener('mouseenter', () => clearTimeout(hideTimer));
    (document.body || document.documentElement).appendChild(host);
  }

  const big = (v) => { const r = v.getBoundingClientRect(); return r.width >= MIN_W && r.height >= MIN_H; };

  function place() {
    if (!current || !current.isConnected) { hide(true); return; }
    const r = current.getBoundingClientRect();
    if (r.bottom < 0 || r.top > innerHeight || r.width < MIN_W) { btn.style.display = 'none'; return; }
    const top = Math.max(8, r.top + 10), right = Math.max(8, innerWidth - r.right + 10);
    btn.style.top = top + 'px';
    btn.style.right = right + 'px';
    panel.style.top = (top + 40) + 'px';
    panel.style.right = right + 'px';
  }

  function show(v) {
    if (!host) build();
    if (!host.isConnected) (document.body || document.documentElement).appendChild(host);
    current = v;
    const drm = !!v.mediaKeys;
    btn.classList.toggle('drm', drm);
    btn.querySelector('span').textContent = drm ? 'Vidéo protégée (DRM)' : 'Télécharger cette vidéo';
    btn.style.display = 'flex';
    place();
    clearTimeout(hideTimer);
    if (!panelOpen) hideTimer = setTimeout(() => hide(false), 3500);
  }

  function hide(force) {
    if (panelOpen && !force) return;
    btn && (btn.style.display = 'none');
    panel && (panel.style.display = 'none');
    panelOpen = false;
  }

  function videoAt(x, y) {
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom && big(v)) return v;
    }
    return null;
  }

  async function onClick() {
    if (!current || current.mediaKeys) return;
    panelOpen = true;
    clearTimeout(hideTimer);
    panel.style.display = 'block';
    panel.innerHTML = '<div class="msg">Recherche des flux…</div>';
    const src = current.currentSrc || current.src || '';
    let res;
    try {
      res = await chrome.runtime.sendMessage({ type: 'media-for-player', src });
    } catch {
      panel.innerHTML = '<div class="msg">Extension indisponible : rechargez la page.</div>';
      return;
    }
    const items = res.items || [];
    if (!items.length) {
      panel.innerHTML = '<div class="head"><b>Varika Download</b><button class="x">×</button></div>' +
        '<div class="msg">Aucun flux trouvé pour l\'instant. Lancez la lecture quelques secondes, puis réessayez.</div>';
    } else if (items.length === 1 && !items[0].qualities.length) {
      send(items[0]);
      return;
    } else {
      panel.innerHTML = '<div class="head"><b>Choisir le flux</b><button class="x">×</button></div>';
      items.forEach((it) => {
        const row = document.createElement('div');
        row.className = 'item';
        const label = document.createElement('span');
        label.textContent = it.label;
        row.appendChild(label);
        let sel = null;
        if (it.qualities.length > 1) {
          sel = document.createElement('select');
          it.qualities.forEach((q) => { const o = document.createElement('option'); o.value = q; o.textContent = q + 'p'; sel.appendChild(o); });
          row.appendChild(sel);
        }
        const b = document.createElement('button');
        b.textContent = 'Télécharger';
        b.onclick = () => send({ ...it, quality: sel ? sel.value : '' });
        row.appendChild(b);
        panel.appendChild(row);
      });
    }
    panel.querySelector('.x')?.addEventListener('click', () => hide(true));
  }

  async function send(item) {
    panel.style.display = 'block';
    panel.innerHTML = '<div class="msg">Envoi à Varika Download…</div>';
    let r;
    try {
      r = await chrome.runtime.sendMessage({ type: 'media-download', item, title: document.title });
    } catch {
      r = { ok: false, error: 'extension indisponible' };
    }
    panel.innerHTML = r.ok
      ? `<div class="msg ok">${r.launched ? 'Ouverture de Varika Download…' : '✓ Ajouté à Varika Download'}</div>`
      : `<div class="msg">Échec : ${String(r.error || 'erreur').replace(/[<>&]/g, '')}</div>`;
    setTimeout(() => hide(true), 2200);
  }

  // Survol (même si une couche transparente du lecteur recouvre la vidéo)
  let raf = 0;
  document.addEventListener('mousemove', (e) => {
    if (raf) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      const v = videoAt(e.clientX, e.clientY);
      if (v) show(v);
    });
  }, { passive: true });
  // Démarrage de la lecture
  document.addEventListener('play', (e) => { if (e.target instanceof HTMLVideoElement && big(e.target)) show(e.target); }, true);
  addEventListener('scroll', () => current && place(), { passive: true, capture: true });
  addEventListener('resize', () => current && place());
  document.addEventListener('fullscreenchange', () => {
    // en plein écran, le bouton doit être dans l'élément affiché
    if (!host) return;
    const fs = document.fullscreenElement;
    (fs && fs.tagName !== 'VIDEO' ? fs : document.body || document.documentElement).appendChild(host);
  });
})();
