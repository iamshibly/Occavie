/* Occavie "Compare events": pick up to three events on any list, then open /compare?ids=... for a side-by-side view.
   The selection lives in this browser only (localStorage); nothing is sent anywhere until you press Compare. */
(() => {
  const KEY = 'occavieCompare', MAX = 3;
  const read = () => { try { return JSON.parse(localStorage.getItem(KEY) || '[]').filter(x => x && /^[A-Za-z0-9_-]{1,40}$/.test(x.id)).slice(0, MAX); } catch (e) { return []; } };
  const write = list => { try { localStorage.setItem(KEY, JSON.stringify(list)); } catch (e) { /* private mode: selection just will not persist */ } };

  const onComparePage = !!document.querySelector('.ov-compare-page');
  let selected = read();
  const toggles = [];

  // Cards on list pages get a Compare button automatically.
  document.querySelectorAll('.ov-event-card[data-id]').forEach(card => {
    const id = card.dataset.id;
    const title = (card.querySelector('h3') || {}).textContent || 'this event';
    const btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'ov-compare-toggle'; btn.dataset.compareToggle = ''; btn.dataset.id = id; btn.dataset.title = title.trim();
    btn.innerHTML = '<i class="bi bi-layout-split" aria-hidden="true"></i><span>Compare</span>';
    card.appendChild(btn);
  });
  document.querySelectorAll('[data-compare-toggle]').forEach(b => toggles.push(b));

  // Tray
  const tray = document.createElement('div');
  tray.className = 'ov-compare-tray'; tray.hidden = true; tray.setAttribute('role', 'region'); tray.setAttribute('aria-label', 'Events selected for comparison');
  tray.innerHTML = '<div class="ov-compare-tray-inner"><ul class="ov-compare-picks"></ul><p class="ov-compare-msg" role="status" aria-live="polite"></p>' +
    '<div class="ov-compare-tray-actions"><button type="button" class="ov-compare-clear">Clear</button><a class="primary-button ov-compare-go" href="/compare" aria-disabled="true">Compare</a></div></div>';
  document.body.appendChild(tray);
  const list = tray.querySelector('.ov-compare-picks'), msg = tray.querySelector('.ov-compare-msg'), go = tray.querySelector('.ov-compare-go');

  function say(text) { msg.textContent = text || ''; }

  function render() {
    toggles.forEach(b => {
      const on = selected.some(x => x.id === b.dataset.id);
      b.setAttribute('aria-pressed', String(on)); b.classList.toggle('is-on', on);
      const label = b.querySelector('span'); if (label) label.textContent = on ? 'Added' : 'Compare';
      b.setAttribute('aria-label', (on ? 'Remove from comparison: ' : 'Add to comparison: ') + (b.dataset.title || 'event'));
    });
    list.replaceChildren();
    selected.forEach(x => {
      const li = document.createElement('li'); const name = document.createElement('span'); name.textContent = x.title;
      const rm = document.createElement('button'); rm.type = 'button'; rm.setAttribute('aria-label', 'Remove ' + x.title + ' from comparison'); rm.innerHTML = '<i class="bi bi-x-lg" aria-hidden="true"></i>';
      rm.addEventListener('click', () => { selected = selected.filter(y => y.id !== x.id); write(selected); say(''); render(); });
      li.append(name, rm); list.append(li);
    });
    tray.hidden = selected.length === 0 || onComparePage;
    const ready = selected.length >= 2;
    go.setAttribute('aria-disabled', String(!ready)); go.classList.toggle('is-disabled', !ready);
    go.href = ready ? '/compare?ids=' + encodeURIComponent(selected.map(x => x.id).join(',')).replace(/%2C/g, ',') : '/compare';
    go.textContent = ready ? 'Compare ' + selected.length + ' events' : 'Pick one more';
    document.body.classList.toggle('has-compare-tray', selected.length > 0);
  }

  document.addEventListener('click', e => {
    const b = e.target.closest && e.target.closest('[data-compare-toggle]');
    if (!b) return;
    e.preventDefault();
    const id = b.dataset.id, title = b.dataset.title || 'Event';
    if (selected.some(x => x.id === id)) { selected = selected.filter(x => x.id !== id); say(''); }
    else if (selected.length >= MAX) { say('You can compare up to ' + MAX + ' events. Remove one first.'); return; }
    else { selected = selected.concat([{ id, title }]); say(''); }
    write(selected); render();
  });
  go.addEventListener('click', e => { if (go.getAttribute('aria-disabled') === 'true') { e.preventDefault(); say('Pick at least two events to compare.'); } });
  tray.querySelector('.ov-compare-clear').addEventListener('click', () => { selected = []; write(selected); say(''); render(); });

  // The comparison page defines the selection: it mirrors what is shown (so "Remove" there is remembered).
  if (onComparePage) {
    const ids = (new URLSearchParams(location.search).get('ids') || '').split(',').map(x => x.trim()).filter(x => /^[A-Za-z0-9_-]{1,40}$/.test(x)).slice(0, MAX);
    const titles = Array.from(document.querySelectorAll('.ov-compare-headbox strong')).map(n => n.textContent.trim());
    if (titles.length === ids.length) { selected = ids.map((id, i) => ({ id, title: titles[i] })); write(selected); }
  }
  render();
})();
