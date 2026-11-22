/* Organizer check-in. The browser only reads the QR code; every ticket is verified and checked in by the server
   (POST /organizer/events/{id}/check-in/scan). */
(() => {
  const app = document.getElementById('checkinApp');
  if (!app) return;
  const eventId = app.dataset.event;
  const csrf = { header: app.dataset.csrfHeader, token: app.dataset.csrf };
  const $ = id => document.getElementById(id);
  const result = $('ciResult'), video = $('ciVideo'), wrap = $('ciVideoWrap');
  const base = `/organizer/events/${encodeURIComponent(eventId)}/check-in`;
  let stream = null, timer = null, busy = false, lastToken = '', lastAt = 0, detector = null, jsQR = null;

  const headers = { 'Content-Type': 'application/json', [csrf.header]: csrf.token };

  function showStats(stats) {
    if (!stats) return;
    $('ciChecked').textContent = stats.checkedIn; $('ciRemaining').textContent = stats.remaining; $('ciTotal').textContent = stats.total;
    $('ciPercent').textContent = (stats.total ? Math.round(stats.checkedIn * 100 / stats.total) : 0) + '%';
  }

  const VIEW = {
    SUCCESS: ['is-success', 'bi-check-circle-fill', 'Checked in'],
    ALREADY_CHECKED_IN: ['is-warning', 'bi-exclamation-circle-fill', 'Already checked in'],
    INVALID: ['is-error', 'bi-x-octagon-fill', 'Invalid ticket'],
    WRONG_EVENT: ['is-error', 'bi-x-octagon-fill', 'Wrong event'],
    CANCELLED: ['is-error', 'bi-slash-circle-fill', 'Cancelled'],
    FORBIDDEN: ['is-error', 'bi-shield-lock-fill', 'Not allowed'],
    NETWORK: ['is-neutral', 'bi-wifi-off', 'Network error']
  };

  function render(status, message, attendee, at, retry) {
    const [cls, icon, title] = VIEW[status] || VIEW.INVALID;
    result.className = 'ci-result ' + cls; result.hidden = false;
    result.replaceChildren();
    const head = document.createElement('div'); head.className = 'ci-result-head';
    const i = document.createElement('i'); i.className = 'bi ' + icon; i.setAttribute('aria-hidden', 'true');
    const h = document.createElement('strong'); h.textContent = title;
    head.append(i, h); result.append(head);
    const p = document.createElement('p'); p.textContent = message; result.append(p);
    if (attendee) {
      const d = document.createElement('dl');
      [['Attendee', attendee.name], ['Ticket', attendee.ticketType], ['Email', attendee.email], ['Ref', attendee.reference]].forEach(([k, v]) => {
        if (!v) return; const dt = document.createElement('dt'); dt.textContent = k; const dd = document.createElement('dd'); dd.textContent = v; d.append(dt, dd);
      });
      if (at) { const dt = document.createElement('dt'); dt.textContent = 'Checked in at'; const dd = document.createElement('dd'); dd.textContent = new Date(at).toLocaleString(); d.append(dt, dd); }
      result.append(d);
    }
    if (retry) { const b = document.createElement('button'); b.type = 'button'; b.className = 'secondary-button'; b.textContent = 'Try again'; b.addEventListener('click', retry); result.append(b); }
    if (navigator.vibrate) navigator.vibrate(status === 'SUCCESS' ? 80 : [60, 40, 60]);
  }

  async function post(url, body, retry) {
    try {
      const res = await fetch(url, { method: 'POST', headers, credentials: 'same-origin', body: body ? JSON.stringify(body) : undefined });
      if (res.status === 401 || res.redirected) { render('FORBIDDEN', 'Your session expired. Sign in again.', null, null); return; }
      const data = await res.json();
      render(data.status, data.message, data.attendee, data.checkedInAt);
      showStats(data.stats);
      return data;
    } catch (e) {
      render('NETWORK', 'Could not reach the server. Nothing was checked in. Check your connection and try again.', null, null, retry);
    }
  }

  const verify = token => {
    token = (token || '').trim();
    if (!token) return;
    return post(`${base}/scan`, { token }, () => verify(token));
  };

  $('ciTokenForm').addEventListener('submit', e => { e.preventDefault(); verify($('ciToken').value); $('ciToken').select(); });

  // ---- manual lookup
  let searchTimer;
  $('ciSearch').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(search, 250); });
  async function search() {
    const q = $('ciSearch').value.trim(); const list = $('ciList'); list.replaceChildren();
    const note = text => { const li = document.createElement('li'); li.className = 'ci-empty'; li.textContent = text; list.append(li); };
    if (q.length < 2) return note('Type at least two characters to search registrations for this event.');
    try {
      const res = await fetch(`${base}/search?q=${encodeURIComponent(q)}`, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      const rows = await res.json();
      if (!rows.length) return note('No matching attendees.');
      rows.forEach(r => {
        const li = document.createElement('li'); li.className = 'ci-row';
        const info = document.createElement('div'); const n = document.createElement('strong'); n.textContent = r.name; const s = document.createElement('small'); s.textContent = `${r.ticketType || 'Ticket'} · ${r.email}`; info.append(n, s);
        const btn = document.createElement('button'); btn.type = 'button';
        if (r.checkedIn) { btn.className = 'status-badge success-status'; btn.textContent = 'Checked in'; btn.disabled = true; }
        else { btn.className = 'primary-button compact-button'; btn.textContent = 'Check in'; btn.addEventListener('click', async () => { btn.disabled = true; const d = await post(`${base}/registrations/${encodeURIComponent(r.registrationId)}`, null, () => btn.click()); if (d && (d.status === 'SUCCESS' || d.status === 'ALREADY_CHECKED_IN')) search(); else btn.disabled = false; }); }
        li.append(info, btn); list.append(li);
      });
    } catch (e) { note('Could not search. Check your connection and try again.'); }
  }

  // ---- camera scanning (decoding only; verification stays on the server)
  async function loadJsQR() {
    if (window.jsQR) return window.jsQR;
    await new Promise((ok, fail) => { const s = document.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js'; s.onload = ok; s.onerror = fail; document.head.append(s); });
    return window.jsQR;
  }

  async function start() {
    if (!navigator.mediaDevices?.getUserMedia) { $('ciCameraHelp').textContent = 'This browser cannot access the camera. Use the ticket code field or lookup instead.'; return; }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
    } catch (e) { $('ciCameraHelp').textContent = 'Camera permission was denied or the page is not secure (HTTPS). Use the ticket code field or lookup instead.'; return; }
    video.srcObject = stream; await video.play(); wrap.hidden = false; $('ciStart').hidden = true; $('ciStop').hidden = false;
    if ('BarcodeDetector' in window) { try { detector = new BarcodeDetector({ formats: ['qr_code'] }); } catch (e) { detector = null; } }
    if (!detector) { try { jsQR = await loadJsQR(); } catch (e) { $('ciCameraHelp').textContent = 'Could not load the QR decoder. Use the ticket code field or lookup instead.'; stop(); return; } }
    const canvas = document.createElement('canvas'); const ctx = canvas.getContext('2d', { willReadFrequently: true });
    timer = setInterval(async () => {
      if (busy || video.readyState < 2) return;
      let value = '';
      try {
        if (detector) { const codes = await detector.detect(video); value = codes[0]?.rawValue || ''; }
        else { canvas.width = video.videoWidth; canvas.height = video.videoHeight; ctx.drawImage(video, 0, 0); const img = ctx.getImageData(0, 0, canvas.width, canvas.height); value = jsQR(img.data, img.width, img.height)?.data || ''; }
      } catch (e) { return; }
      if (!value || (value === lastToken && Date.now() - lastAt < 4000)) return;
      lastToken = value; lastAt = Date.now(); busy = true;
      try { await verify(value); } finally { setTimeout(() => { busy = false; }, 1200); }
    }, 250);
  }

  function stop() {
    clearInterval(timer); timer = null;
    stream?.getTracks().forEach(t => t.stop()); stream = null; video.srcObject = null;
    wrap.hidden = true; $('ciStart').hidden = false; $('ciStop').hidden = true;
  }
  $('ciStart').addEventListener('click', start);
  $('ciStop').addEventListener('click', stop);
  window.addEventListener('pagehide', stop);
})();
