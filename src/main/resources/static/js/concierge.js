/* Occavie Concierge: floating assistant for visitors. Answers come from /concierge/data.json (the same public event list the site shows)
   through concierge-core.js. Rule-based and offline: no AI service, nothing is sent anywhere except that one read-only request. */
(() => {
  const Core = window.OccavieConcierge;
  if (!Core || document.querySelector('.ov-concierge')) return;
  const KEY = 'occavieConcierge';
  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const pageEventId = (document.body && document.body.dataset.eventId) || '';

  let engine = null, loading = null, state = { last: { ids: [], focusId: '' }, page: pageEventId ? { eventId: pageEventId } : {} };
  let log = [];                                                         // [{role, text, events, links}]
  try { const saved = JSON.parse(sessionStorage.getItem(KEY) || 'null'); if (saved && Array.isArray(saved.log)) { log = saved.log.slice(-30); if (saved.state) state = Object.assign(state, saved.state, { page: state.page }); } } catch (e) { /* ignore */ }
  const persist = () => { try { sessionStorage.setItem(KEY, JSON.stringify({ log: log.slice(-30), state })); } catch (e) { /* private mode */ } };

  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined) n.textContent = text; return n; };

  // ---- shell
  const root = el('div', 'ov-concierge');
  const launch = el('button', 'ov-concierge-launch'); launch.type = 'button';
  launch.setAttribute('aria-expanded', 'false'); launch.setAttribute('aria-controls', 'ovConciergePanel'); launch.setAttribute('aria-label', 'Open the Occavie Concierge assistant');
  launch.innerHTML = '<i class="bi bi-chat-dots-fill" aria-hidden="true"></i><span>Ask Occavie</span>';
  const panel = el('section', 'ov-concierge-panel'); panel.id = 'ovConciergePanel'; panel.hidden = true;
  panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-label', 'Occavie Concierge');
  const head = el('header', 'ov-concierge-head');
  const mark = el('img', 'ov-concierge-mark'); mark.src = '/images/occavie-mark.svg'; mark.alt = ''; mark.width = 34; mark.height = 34;
  const titles = el('div', 'ov-concierge-title'); titles.append(el('strong', '', 'Occavie Concierge'), el('small', '', 'Events, tickets, seats and booking help'));
  const clear = el('button', 'ov-concierge-icon'); clear.type = 'button'; clear.setAttribute('aria-label', 'Clear conversation'); clear.innerHTML = '<i class="bi bi-arrow-counterclockwise" aria-hidden="true"></i>';
  const close = el('button', 'ov-concierge-icon'); close.type = 'button'; close.setAttribute('aria-label', 'Close assistant'); close.innerHTML = '<i class="bi bi-x-lg" aria-hidden="true"></i>';
  head.append(mark, titles, clear, close);
  const logEl = el('div', 'ov-concierge-log'); logEl.setAttribute('role', 'log'); logEl.setAttribute('aria-live', 'polite'); logEl.setAttribute('aria-label', 'Conversation');
  const chipsEl = el('div', 'ov-concierge-chips'); chipsEl.setAttribute('role', 'group'); chipsEl.setAttribute('aria-label', 'Suggested questions');
  const form = el('form', 'ov-concierge-form'); form.autocomplete = 'off';
  const label = el('label', 'ov-sr', 'Ask the Occavie Concierge'); label.htmlFor = 'ovConciergeInput';
  const input = el('input'); input.id = 'ovConciergeInput'; input.type = 'text'; input.maxLength = 200; input.placeholder = 'Ask about events, seats, tickets…';
  const send = el('button', 'ov-concierge-send'); send.type = 'submit'; send.setAttribute('aria-label', 'Send'); send.innerHTML = '<i class="bi bi-send-fill" aria-hidden="true"></i>';
  form.append(label, input, send);
  const note = el('p', 'ov-concierge-note', 'Rule-based assistant. Answers come from the live event list on this site.');
  panel.append(head, logEl, chipsEl, form, note);
  root.append(panel, launch);
  document.body.appendChild(root);

  // ---- rendering
  function renderMessage(m) {
    const row = el('div', 'ov-cc-msg is-' + m.role);
    row.appendChild(el('div', 'ov-cc-bubble', m.text));
    if (m.events && m.events.length) {
      const list = el('div', 'ov-cc-cards');
      m.events.forEach(e => {
        const a = el('a', 'ov-cc-card'); a.href = e.href;
        if (e.img) { const img = el('img'); img.src = e.img; img.alt = ''; img.width = 64; img.height = 64; img.loading = 'lazy'; a.appendChild(img); }
        const body = el('span', 'ov-cc-card-body');
        body.append(el('strong', '', e.title), el('small', '', e.when + (e.venue ? ' · ' + e.venue : '')));
        const meta = el('span', 'ov-cc-card-meta'); meta.append(el('b', '', e.price), el('em', '', e.seats));
        body.appendChild(meta);
        if (e.why) body.appendChild(el('span', 'ov-cc-why', e.why));
        a.appendChild(body); list.appendChild(a);
      });
      row.appendChild(list);
    }
    if (m.links && m.links.length) {
      const wrap = el('div', 'ov-cc-links');
      m.links.forEach(l => { const a = el('a', 'ov-cc-link', l.label); a.href = l.href; wrap.appendChild(a); });
      row.appendChild(wrap);
    }
    return row;
  }
  function renderAll() { logEl.replaceChildren(...log.map(renderMessage)); logEl.scrollTop = logEl.scrollHeight; }
  function setChips(list) {
    chipsEl.replaceChildren(...(list || []).slice(0, 5).map(text => { const b = el('button', 'ov-cc-chip', text); b.type = 'button'; b.addEventListener('click', () => ask(text)); return b; }));
  }
  function push(m) { log.push(m); logEl.appendChild(renderMessage(m)); logEl.scrollTop = logEl.scrollHeight; persist(); }

  // ---- data + answering
  function ready() {
    if (engine) return Promise.resolve(engine);
    if (!loading) loading = fetch('/concierge/data.json', { credentials: 'same-origin' })
      .then(r => { if (!r.ok) throw new Error('data'); return r.json(); })
      .then(data => { engine = Core.createEngine(data); return engine; })
      .catch(err => { loading = null; throw err; });
    return loading;
  }
  function ask(text) {
    const clean = String(text || '').trim().slice(0, 200);
    if (!clean) return;
    input.value = ''; push({ role: 'user', text: clean }); setChips([]);
    const typing = el('div', 'ov-cc-msg is-bot'); typing.appendChild(el('div', 'ov-cc-bubble ov-cc-typing', '…')); logEl.appendChild(typing); logEl.scrollTop = logEl.scrollHeight;
    ready().then(bot => {
      const r = bot.answer(clean, state);
      state = Object.assign({}, r.state, { page: state.page });
      setTimeout(() => { typing.remove(); push({ role: 'bot', text: r.text, events: r.events, links: r.links }); setChips(r.chips); }, reduceMotion ? 0 : 280);
    }).catch(() => {
      typing.remove();
      push({ role: 'bot', text: 'I cannot reach the event list right now. You can still browse everything on the Explore page.', links: [{ label: 'Browse events', href: '/events' }] });
    });
  }

  const EVENT_CHIPS = ['Seats left?', 'Ticket prices', 'Where is it?', 'Similar events', 'Can I cancel a booking?'];

  function welcome() {
    const onEvent = !!pageEventId;
    push({ role: 'bot', text: onEvent ? 'Hi! Ask me about this event: seats left, ticket prices, where and when. I can also find similar events.'
      : 'Hi! I am the Occavie Concierge. I can find events, check seats and ticket prices, compare events and explain how booking works.' });
    setChips(onEvent ? EVENT_CHIPS : Core.HOME_CHIPS);
  }

  // ---- open / close
  function open() {
    panel.hidden = false; launch.setAttribute('aria-expanded', 'true'); launch.setAttribute('aria-label', 'Close the Occavie Concierge assistant'); root.classList.add('is-open');
    if (!log.length) welcome(); else { renderAll(); setChips(pageEventId ? EVENT_CHIPS : Core.HOME_CHIPS.slice(0, 4)); }
    ready().catch(() => {});
    setTimeout(() => input.focus(), 30);
  }
  function shut() { panel.hidden = true; launch.setAttribute('aria-expanded', 'false'); launch.setAttribute('aria-label', 'Open the Occavie Concierge assistant'); root.classList.remove('is-open'); launch.focus(); }
  launch.addEventListener('click', () => (panel.hidden ? open() : shut()));
  close.addEventListener('click', shut);
  clear.addEventListener('click', () => { log = []; state = { last: { ids: [], focusId: '' }, page: state.page }; persist(); logEl.replaceChildren(); welcome(); input.focus(); });
  panel.addEventListener('keydown', e => { if (e.key === 'Escape') { e.stopPropagation(); shut(); } });
  form.addEventListener('submit', e => { e.preventDefault(); ask(input.value); });
  // any link inside the chat closes nothing: it simply navigates; keep the history for the next page
  window.OccavieConciergeOpen = open;
})();
