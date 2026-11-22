/* Occavie event discovery: client-side search, filters, sorting and pagination
   over the events rendered by the server (/events). State is mirrored in the URL
   so filtered views can be shared and the back button works. */
(() => {
  const $ = id => document.getElementById(id);
  const input = $('eventSearch'), location_ = $('locationFilter');
  const categorySelect = $('categoryFilter'), priceSelect = $('pricingFilter'), statusSelect = $('eventStatusFilter');
  const dateFrom = $('dateFrom'), dateTo = $('dateTo'), sortSelect = $('sortSelect');
  const grid = $('v1EventGrid'), count = $('resultCount'), empty = $('v1Empty'), pager = $('ovPagination');
  if (!input || !grid) return;

  const PAGE_SIZE = 12;
  const cards = Array.from(grid.querySelectorAll('.ov-event-card'));
  const chips = Array.from(document.querySelectorAll('.ov-category-button'));
  let page = 1;

  // "Free", "From ৳500", "৳1,200" -> number (Free = 0)
  const priceOf = card => {
    const label = (card.dataset.price || '').replace(/,/g, '');
    const m = label.match(/\d+(\.\d+)?/);
    return m ? parseFloat(m[0]) : 0;
  };

  const ensureOption = (select, value) => {
    if (!value) return;
    const match = Array.from(select.options).find(o => o.value.toLowerCase() === value.toLowerCase());
    if (match) { select.value = match.value; return; }
    const option = document.createElement('option');
    option.value = value; option.textContent = value; select.add(option); select.value = value;
  };

  const params = new URLSearchParams(window.location.search);
  input.value = params.get('q') || '';
  location_.value = params.get('location') || '';
  ensureOption(categorySelect, params.get('category'));
  const pick = (select, key, allowed) => { const v = (params.get(key) || '').toUpperCase(); if (allowed.includes(v)) select.value = v; };
  pick(priceSelect, 'pricing', ['ALL', 'FREE', 'PAID']);
  pick(statusSelect, 'status', ['ALL', 'UPCOMING', 'COMPLETED']);
  dateFrom.value = /^\d{4}-\d{2}-\d{2}$/.test(params.get('from') || '') ? params.get('from') : '';
  dateTo.value = /^\d{4}-\d{2}-\d{2}$/.test(params.get('to') || '') ? params.get('to') : '';
  const sorts = Array.from(sortSelect.options).map(o => o.value);
  if (sorts.includes(params.get('sort'))) sortSelect.value = params.get('sort');
  page = Math.max(1, parseInt(params.get('page') || '1', 10) || 1);

  // ---- Smart search state (see smart-search.js). Empty when the user filters by hand.
  const Smart = window.OccavieSmart;
  const smartForm = $('ovSmartForm'), smartInput = $('ovSmartInput'), smartChips = $('ovSmartChips'), smartNote = $('ovSmartNote');
  const smart = { ask: '', groups: [], regexes: [], maxPrice: null, cityFallback: false, chips: [] };
  const cardText = new Map(cards.map(c => [c, ((c.dataset.search || '') + ' ' + (c.dataset.category || '')).toLocaleLowerCase()]));
  const locationsOnPage = Array.from(new Set(cards.map(c => c.dataset.location || '').filter(Boolean)));

  const comparators = {
    'date-asc': (a, b) => (a.dataset.date || '').localeCompare(b.dataset.date || ''),
    'date-desc': (a, b) => (b.dataset.date || '').localeCompare(a.dataset.date || ''),
    'title-asc': (a, b) => (a.dataset.title || '').localeCompare(b.dataset.title || '', undefined, { sensitivity: 'base' }),
    'price-asc': (a, b) => priceOf(a) - priceOf(b),
    'price-desc': (a, b) => priceOf(b) - priceOf(a)
  };

  function pageButton(label, target, { current = false, disabled = false, aria } = {}) {
    const b = document.createElement('button');
    b.type = 'button'; b.textContent = label;
    if (aria) b.setAttribute('aria-label', aria);
    if (current) { b.className = 'is-current'; b.setAttribute('aria-current', 'page'); }
    b.disabled = disabled;
    b.addEventListener('click', () => { page = target; apply(true); });
    return b;
  }

  function renderPager(totalPages) {
    pager.replaceChildren();
    if (totalPages <= 1) { pager.hidden = true; return; }
    pager.hidden = false;
    pager.append(pageButton('Previous', page - 1, { disabled: page === 1, aria: 'Previous page' }));
    const shown = new Set([1, totalPages, page - 1, page, page + 1]);
    let last = 0;
    [...shown].filter(n => n >= 1 && n <= totalPages).sort((a, b) => a - b).forEach(n => {
      if (n - last > 1) { const dots = document.createElement('span'); dots.textContent = '…'; dots.setAttribute('aria-hidden', 'true'); pager.append(dots); }
      pager.append(pageButton(String(n), n, { current: n === page, aria: `Page ${n}` }));
      last = n;
    });
    pager.append(pageButton('Next', page + 1, { disabled: page === totalPages, aria: 'Next page' }));
  }

  function apply(scroll) {
    const search = input.value.trim().toLocaleLowerCase();
    const place = location_.value.trim().toLocaleLowerCase();
    const cat = categorySelect.value.toLocaleLowerCase();
    const pricing = priceSelect.value, status = statusSelect.value;
    const from = dateFrom.value, to = dateTo.value;

    const matches = cards.filter(card => {
      const date = card.dataset.date || '';
      return (!search || (card.dataset.search || '').toLocaleLowerCase().includes(search))
        && (!place || (card.dataset.location || '').toLocaleLowerCase().includes(place)
            || (smart.cityFallback && !(card.dataset.location || '').includes(',')))
        && (smart.maxPrice === null || priceOf(card) <= smart.maxPrice)
        && smart.regexes.every(re => re.test(cardText.get(card)))
        && (cat === 'all' || (card.dataset.category || '').toLocaleLowerCase() === cat)
        && (pricing === 'ALL' || card.dataset.pricing === pricing)
        && (status === 'ALL' || card.dataset.status === status)
        && (!from || date >= from) && (!to || date <= to);
    }).sort(comparators[sortSelect.value] || comparators['date-asc']);

    const totalPages = Math.max(1, Math.ceil(matches.length / PAGE_SIZE));
    page = Math.min(page, totalPages);
    const visible = new Set(matches.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE));
    matches.forEach(card => grid.appendChild(card));            // reflect sort order in the DOM
    cards.forEach(card => { card.hidden = !visible.has(card); });

    const start = matches.length ? (page - 1) * PAGE_SIZE + 1 : 0;
    count.textContent = matches.length === 0 ? '0 events'
      : `${start}–${start + visible.size - 1} of ${matches.length} event${matches.length === 1 ? '' : 's'}`;
    if (empty) empty.hidden = matches.length > 0 || cards.length === 0;
    grid.hidden = matches.length === 0;
    renderPager(totalPages);

    chips.forEach(chip => {
      const selected = chip.dataset.category.toLocaleLowerCase() === cat;
      chip.classList.toggle('is-active', selected);
      chip.setAttribute('aria-pressed', String(selected));
    });

    const next = new URLSearchParams();
    if (smart.ask) next.set('ask', smart.ask);
    if (!smart.ask && input.value.trim()) next.set('q', input.value.trim());
    if (!smart.ask) {
      if (location_.value.trim()) next.set('location', location_.value.trim());
      if (categorySelect.value !== 'ALL') next.set('category', categorySelect.value);
      if (pricing !== 'ALL') next.set('pricing', pricing);
      if (status !== 'UPCOMING') next.set('status', status);
      if (from) next.set('from', from);
      if (to) next.set('to', to);
      if (sortSelect.value !== 'date-asc') next.set('sort', sortSelect.value);
    }
    if (page > 1) next.set('page', String(page));
    history.replaceState(null, '', `${window.location.pathname}${next.size ? '?' + next : ''}`);
    if (scroll) document.getElementById('resultsHeading')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    return matches.length;
  }

  const clearSmart = () => {
    smart.ask = ''; smart.groups = []; smart.regexes = []; smart.maxPrice = null; smart.cityFallback = false; smart.chips = [];
    if (smartChips) smartChips.replaceChildren();
    if (smartNote) { smartNote.textContent = ''; smartNote.hidden = true; }
  };
  const reset = () => {
    clearSmart(); if (smartInput) smartInput.value = '';
    input.value = ''; location_.value = ''; categorySelect.value = 'ALL'; priceSelect.value = 'ALL';
    statusSelect.value = 'UPCOMING'; dateFrom.value = ''; dateTo.value = ''; sortSelect.value = 'date-asc';
    page = 1; apply();
  };
  const filterChanged = () => { if (smart.ask) clearSmart(); page = 1; apply(); };

  let timer;
  [input, location_].forEach(el => el.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(filterChanged, 150); }));
  [categorySelect, priceSelect, statusSelect, dateFrom, dateTo, sortSelect].forEach(el => el.addEventListener('change', filterChanged));
  chips.forEach(chip => chip.addEventListener('click', () => { categorySelect.value = chip.dataset.category; filterChanged(); }));
  $('v1ClearFilters')?.addEventListener('click', reset);
  $('v1ClearFilters2')?.addEventListener('click', reset);
  apply();

  // ---------------------------------------------------------------- Smart search
  const CHIP_ICON = { category: 'bi-grid', price: 'bi-tag', maxprice: 'bi-cash-coin', date: 'bi-calendar3', place: 'bi-geo-alt', keywords: 'bi-search', status: 'bi-clock-history', sort: 'bi-sort-down' };

  function showNote(text) { if (!smartNote) return; smartNote.textContent = text || ''; smartNote.hidden = !text; }

  function renderSmartChips() {
    if (!smartChips) return;
    smartChips.replaceChildren();
    smart.chips.forEach(c => {
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'ov-smart-chip'; b.setAttribute('aria-label', 'Remove filter: ' + c.label);
      const icon = document.createElement('i'); icon.className = 'bi ' + (CHIP_ICON[c.kind] || 'bi-funnel'); icon.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span'); label.textContent = c.label;
      const x = document.createElement('i'); x.className = 'bi bi-x-lg'; x.setAttribute('aria-hidden', 'true');
      b.append(icon, label, x);
      b.addEventListener('click', () => removeChip(c));
      smartChips.append(b);
    });
  }

  function removeChip(chip) {
    smart.chips = smart.chips.filter(c => c !== chip);
    switch (chip.kind) {
      case 'category': categorySelect.value = 'ALL'; break;
      case 'price': priceSelect.value = 'ALL'; break;
      case 'maxprice': smart.maxPrice = null; break;
      case 'date': dateFrom.value = ''; dateTo.value = ''; break;
      case 'place': location_.value = ''; smart.cityFallback = false; break;
      case 'status': statusSelect.value = 'UPCOMING'; break;
      case 'sort': sortSelect.value = 'date-asc'; break;
      case 'keywords': smart.groups = []; smart.regexes = []; break;
      default: break;
    }
    if (!smart.chips.length) { clearSmart(); }
    else if (!smart.groups.length && smart.maxPrice === null && !smart.cityFallback) { smart.ask = ''; }
    page = 1; apply(); renderSmartChips();
    showNote('');
  }

  function runSmart(text, opts) {
    if (!Smart) return;
    const ask = String(text || '').trim();
    if (!ask) { reset(); return; }
    const select = categorySelect;
    const parsed = Smart.parse(ask, {
      today: new Date(), locations: locationsOnPage,
      categories: Array.from(select.options).map(o => o.value).filter(v => v && v !== 'ALL')
    });
    const apply_ = (r, drop) => {
      clearSmart(); smart.ask = ask;
      input.value = '';
      categorySelect.value = r.category || 'ALL';
      priceSelect.value = r.pricing || 'ALL';
      statusSelect.value = r.status || 'UPCOMING';
      dateFrom.value = drop.date ? '' : (r.from || '');
      dateTo.value = drop.date ? '' : (r.to || '');
      location_.value = drop.place ? '' : (r.place || '');
      smart.cityFallback = !drop.place && !!r.cityFallback;
      smart.maxPrice = drop.price ? null : r.maxPrice;
      sortSelect.value = r.sort || 'date-asc';
      smart.groups = drop.keywords ? [] : (drop.formats ? r.groups.filter(g => !g.format) : r.groups);
      smart.regexes = smart.groups.map(g => Smart.groupRegex(g));
      smart.chips = r.chips.filter(c => !(drop.date && c.kind === 'date') && !(drop.place && c.kind === 'place')
        && !(drop.price && c.kind === 'maxprice') && !(drop.keywords && c.kind === 'keywords'))
        .map(c => c.kind === 'keywords' && smart.groups.length ? { kind: 'keywords', label: 'Matching: ' + smart.groups.map(g => g.label).join(', ') } : c)
        .filter(c => !(c.kind === 'keywords' && !smart.groups.length));
      page = 1;
      return apply(opts && opts.scroll);
    };

    // 1) everything as understood; 2) if nothing matches, relax one filter at a time and say so
    let found = apply_(parsed, {});
    const notes = [];
    const ladder = [['formats', parsed.groups.some(g => g.format), 'the word ' + (parsed.groups.filter(g => g.format).map(g => '"' + g.label.toLowerCase() + '"')[0] || 'workshop')],
      ['keywords', parsed.groups.length, 'the extra words'], ['date', parsed.from, 'the dates'],
      ['price', parsed.maxPrice !== null, 'the price limit'], ['place', parsed.place, 'the place']];
    const drop = {};
    for (const [key, active, label] of ladder) {
      if (found > 0) break;
      if (!active) continue;
      drop[key] = true; notes.push(label);
      found = apply_(parsed, drop);
    }
    if (smartInput) smartInput.value = ask;
    renderSmartChips();
    if (!parsed.understood && !parsed.groups.length) showNote('I could not find any filters in that. Try something like "free tech events this weekend".');
    else if (found === 0) showNote('No events match that yet. Try removing a filter above, or browse all events.');
    else if (notes.length && !smart.chips.length) showNote('No events mention "' + ask + '". Showing all upcoming events instead.');
    else if (notes.length) showNote('No exact match, so I ignored ' + notes.join(', ') + ' and show the closest events.');
    else showNote('');
    return found;
  }

  if (smartForm && smartInput && Smart) {
    smartForm.addEventListener('submit', e => { e.preventDefault(); runSmart(smartInput.value, { scroll: true }); });
    document.querySelectorAll('[data-ask]').forEach(btn => btn.addEventListener('click', () => { smartInput.value = btn.dataset.ask; runSmart(btn.dataset.ask, { scroll: true }); }));
    const initialAsk = params.get('ask');
    if (initialAsk) runSmart(initialAsk, { scroll: false });
  }
})();
