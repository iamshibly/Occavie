/* Occavie Smart Search: turns a sentence such as "free tech events this weekend in Dhaka" into the filters the Explore page
   already has (category, price, dates, place) plus keyword groups. Rule-based and offline: no API, no key, nothing leaves
   the browser. parse(text, ctx) is a pure function (unit-tested in src/test/js/smart-search.test.js).

   ctx = { today: Date, categories: ['Technology', ...], locations: ['BICC, Dhaka', ...] } */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.OccavieSmart = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Words that mean a whole category. `specific` words also stay as keywords because they are narrower than the category.
  const CATEGORY_WORDS = {
    Technology: ['tech', 'technology', 'coding', 'programming', 'developer', 'developers', 'software', 'hackathon', 'cyber',
      'cybersecurity', 'security', 'robotics', 'robot', 'blockchain', 'cloud', 'devops', 'web', 'app', 'apps', 'flutter'],
    Business: ['business', 'startup', 'startups', 'entrepreneur', 'entrepreneurs', 'entrepreneurship', 'finance', 'marketing',
      'investor', 'investors', 'networking', 'sales'],
    Education: ['education', 'learn', 'learning', 'study', 'scholarship', 'scholarships', 'research', 'lecture', 'course', 'teaching', 'speaking'],
    Health: ['health', 'yoga', 'fitness', 'wellness', 'nutrition', 'mental', 'wellbeing', 'medical', 'healthy'],
    Music: ['music', 'concert', 'concerts', 'band', 'bands', 'acoustic', 'folk', 'singing'],
    Sports: ['sport', 'sports', 'cricket', 'football', 'soccer', 'marathon', 'run', 'running', 'tennis', 'athletics'],
    Career: ['career', 'careers', 'job', 'jobs', 'hiring', 'internship', 'internships', 'resume', 'cv', 'recruitment'],
    Community: ['community', 'volunteer', 'volunteers', 'volunteering', 'neighbourhood', 'neighborhood', 'tree', 'plantation'],
    Design: ['design', 'designer', 'ui', 'ux', 'figma', 'brand', 'branding', 'poster', 'graphic', 'motion'],
    Arts: ['art', 'arts', 'photography', 'painting', 'craft'],
    Science: ['science', 'scientific'],
    Food: ['food', 'cooking', 'cuisine', 'dining'],
    Charity: ['charity', 'donation', 'fundraiser', 'fundraising']
  };
  const SPECIFIC = new Set(['yoga', 'cricket', 'football', 'soccer', 'marathon', 'tennis', 'blockchain', 'flutter', 'figma',
    'photography', 'hackathon', 'cybersecurity', 'cyber', 'robotics', 'nutrition', 'resume', 'internship', 'internships',
    'scholarship', 'scholarships', 'acoustic', 'folk', 'poster', 'motion', 'devops', 'cloud', 'tree', 'plantation', 'volunteer', 'volunteers']);

  // Concepts that are not a category: they widen a word into several title words (OR).
  const CONCEPTS = {
    ai: { label: 'AI', alts: ['ai', 'machine learning', 'data science', 'robotics', 'cloud', 'automation'] },
    ml: { label: 'Machine learning', alts: ['machine learning', 'data science', 'ai'] },
    artificial: { label: 'AI', alts: ['ai', 'machine learning', 'data science'] },
    data: { label: 'Data', alts: ['data', 'machine learning', 'analytics'] },
    mobile: { label: 'Mobile', alts: ['mobile', 'flutter', 'app'] },
    music: null
  };

  const FORMATS = ['workshop', 'bootcamp', 'summit', 'conference', 'meetup', 'festival', 'fest', 'seminar', 'panel', 'fair',
    'expo', 'clinic', 'masterclass', 'webinar', 'forum', 'showcase', 'hackathon', 'sprint', 'jam', 'competition', 'cup',
    'tournament', 'camp', 'drive', 'night', 'day', 'review', 'circle', 'swap'];

  const STOP = new Set(('a an the and or of for to in on at with near around from by me my i we you us show find get list see give want ' +
    'wanna need looking look search any some all there are is be what whats which where when events event happening going go can could ' +
    'please upcoming new good best nice fun cool great popular recommended suggest suggestions some thing things stuff that those these ' +
    'this next coming next is are am do does have has just only also then than too very really like love love interested interesting ' +
    'around nearby close available book booking tickets ticket seats join attend attending visit').split(/\s+/));

  const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];
  const MON3 = MONTHS.map(m => m.slice(0, 3));
  const DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  const GENERIC_PLACE = new Set(['center', 'centre', 'hall', 'auditorium', 'lab', 'studio', 'training', 'convention', 'university', 'hub',
    'building', 'room', 'seminar', 'central', 'open', 'stage', 'ground', 'sports', 'community', 'innovation', 'creative', 'business',
    'computer', 'indoor', 'library', 'lake', 'track', 'running', 'campus', 'the', 'and']);

  const pad = n => String(n).padStart(2, '0');
  const iso = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const addDays = (d, n) => { const x = new Date(d.getFullYear(), d.getMonth(), d.getDate() + n); return x; };
  const fmt = d => d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
  const fmtDay = d => d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
  const cap = s => s.charAt(0).toUpperCase() + s.slice(1);
  const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  /** Bangladesh weekend is Friday + Saturday. Returns [from, to] of "this weekend" (or the current one if it is already running). */
  function weekend(today, next) {
    const dow = today.getDay();                       // 0=Sun .. 5=Fri, 6=Sat
    let friday;
    if (dow === 6) friday = addDays(today, -1);       // Saturday: weekend in progress
    else friday = addDays(today, (5 - dow + 7) % 7);
    if (next) friday = addDays(friday, 7);
    let from = friday, to = addDays(friday, 1);
    if (!next && from < today) from = today;
    return [from, to];
  }

  function parse(text, ctx) {
    const today = ctx && ctx.today ? new Date(ctx.today.getFullYear(), ctx.today.getMonth(), ctx.today.getDate()) : new Date();
    const categories = (ctx && ctx.categories) || [];
    const locations = (ctx && ctx.locations) || [];
    const result = { category: null, pricing: null, maxPrice: null, from: '', to: '', place: '', cityFallback: false, status: 'UPCOMING',
      sort: '', groups: [], chips: [], keywords: [], understood: false, original: String(text || '').trim() };
    let s = ' ' + result.original.toLowerCase().replace(/[“”"']/g, ' ').replace(/[?!;:]+|,(?!\d)|(?<!\d),/g, ' ').replace(/\s+/g, ' ') + ' ';
    if (s.trim().length === 0) return result;
    const chip = (kind, label) => result.chips.push({ kind, label });
    const take = (re, fn) => { const m = s.match(re); if (m) { s = s.replace(re, ' '); fn(m); return true; } return false; };

    // ---- status
    take(/\b(past|previous|completed|finished|ended|old)\b/, () => { result.status = 'COMPLETED'; chip('status', 'Past events'); });
    take(/\b(all events|everything|any time|anytime)\b/, () => { result.status = 'ALL'; chip('status', 'All events'); });

    // ---- price
    take(/\b(under|below|less than|up to|upto|max|maximum|within|cheaper than|not more than)\s*(?:৳|tk\.?|bdt|taka)?\s*(\d[\d,]*)\s*(?:tk|taka|bdt|৳)?\b/, m => {
      result.maxPrice = parseInt(m[2].replace(/,/g, ''), 10); chip('maxprice', `Under ৳${result.maxPrice.toLocaleString('en')}`);
    });
    take(/\b(free of charge|free|no cost|zero cost|complimentary)\b/, () => { result.pricing = 'FREE'; chip('price', 'Free'); });
    take(/\b(paid|ticketed|premium)\b/, () => { result.pricing = 'PAID'; chip('price', 'Paid'); });
    take(/\b(cheapest|cheap|affordable|budget|low cost|low-cost|inexpensive)\b/, () => { result.sort = 'price-asc'; chip('sort', 'Cheapest first'); });
    take(/\b(most expensive|priciest)\b/, () => { result.sort = 'price-desc'; chip('sort', 'Highest price first'); });
    take(/\b(soonest|earliest)\b/, () => { result.sort = 'date-asc'; });
    take(/\b(latest|furthest)\b/, () => { result.sort = 'date-desc'; chip('sort', 'Latest first'); });

    // ---- dates
    let dated = false;
    const setRange = (from, to, label) => { if (dated) return; dated = true; result.from = iso(from); result.to = iso(to); chip('date', label); };
    take(/\bnext weekend\b/, () => { const [f, t] = weekend(today, true); setRange(f, t, `Next weekend (${fmtDay(f)} – ${fmtDay(t)})`); });
    take(/\b(this )?weekend\b/, () => { const [f, t] = weekend(today, false); setRange(f, t, `This weekend (${fmtDay(f)} – ${fmtDay(t)})`); });
    take(/\b(today|tonight)\b/, () => setRange(today, today, `Today (${fmtDay(today)})`));
    take(/\btomorrow\b/, () => { const d = addDays(today, 1); setRange(d, d, `Tomorrow (${fmtDay(d)})`); });
    take(/\b(?:in )?(?:the )?next (\d{1,3}) days?\b/, m => { const n = Math.min(365, parseInt(m[1], 10)); setRange(today, addDays(today, n), `Next ${n} days`); });
    take(/\bnext week\b/, () => { const sat = addDays(today, (6 - today.getDay() + 7) % 7); const f = addDays(sat, 1), t = addDays(sat, 7); setRange(f, t, `Next week (${fmtDay(f)} – ${fmtDay(t)})`); });
    take(/\bthis week\b/, () => { const t = addDays(today, (6 - today.getDay() + 7) % 7); setRange(today, t, `This week (to ${fmtDay(t)})`); });
    take(/\bnext month\b/, () => { const f = new Date(today.getFullYear(), today.getMonth() + 1, 1); const t = new Date(today.getFullYear(), today.getMonth() + 2, 0); setRange(f, t, `Next month (${cap(MONTHS[f.getMonth()])})`); });
    take(/\bthis month\b/, () => { const t = new Date(today.getFullYear(), today.getMonth() + 1, 0); setRange(today, t, `This month (${cap(MONTHS[today.getMonth()])})`); });
    take(/\b(?:this |next |on )?(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b/, m => {
      const idx = DAYS.indexOf(m[1]); let d = addDays(today, (idx - today.getDay() + 7) % 7); setRange(d, d, fmt(d));
    });
    // "28 nov", "28th november", "nov 28"
    const monthRe = '(' + MONTHS.join('|') + '|' + MON3.join('|') + ')';
    if (!take(new RegExp('\\b(?:on |by )?(\\d{1,2})(?:st|nd|rd|th)?\\s+' + monthRe + '\\b(?:\\s+(\\d{4}))?'), m => {
      const mi = MON3.indexOf(m[2].slice(0, 3)); let y = m[3] ? parseInt(m[3], 10) : today.getFullYear(); let d = new Date(y, mi, parseInt(m[1], 10));
      if (!m[3] && d < today) d = new Date(y + 1, mi, parseInt(m[1], 10)); setRange(d, d, fmt(d));
    })) {
      take(new RegExp('\\b' + monthRe + '\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:\\s+(\\d{4}))?'), m => {
        const mi = MON3.indexOf(m[1].slice(0, 3)); let y = m[3] ? parseInt(m[3], 10) : today.getFullYear(); let d = new Date(y, mi, parseInt(m[2], 10));
        if (!m[3] && d < today) d = new Date(y + 1, mi, parseInt(m[2], 10)); setRange(d, d, fmt(d));
      });
    }
    // "in december", "december 2027", bare month names (except the verb "may", which needs "in/during/on")
    take(new RegExp('\\b(?:in |during |of |for )?(' + MONTHS.filter(m => m !== 'may').join('|') + '|' + MON3.filter(m => m !== 'may' && m !== 'mar').join('|') + ')\\b(?:\\s+(\\d{4}))?'), m => {
      const mi = MON3.indexOf(m[1].slice(0, 3)); let y = m[2] ? parseInt(m[2], 10) : today.getFullYear();
      let from = new Date(y, mi, 1), to = new Date(y, mi + 1, 0);
      if (!m[2] && to < today) { y += 1; from = new Date(y, mi, 1); to = new Date(y, mi + 1, 0); }
      if (from < today && to >= today) from = today;
      setRange(from, to, `${cap(MONTHS[mi])}${y !== today.getFullYear() ? ' ' + y : ''}`);
    });
    take(/\b(?:in |during |on )(may)\b/, () => {
      let y = today.getFullYear(); let from = new Date(y, 4, 1), to = new Date(y, 5, 0); if (to < today) { y += 1; from = new Date(y, 4, 1); to = new Date(y, 5, 0); }
      setRange(from, to, 'May' + (y !== today.getFullYear() ? ' ' + y : ''));
    });

    // ---- place: known city/venue words, or "in/at/near X"
    const venueTokens = new Map();                        // token -> count
    const cityCounts = new Map();
    locations.forEach(loc => {
      String(loc).toLowerCase().split(/[,\s]+/).forEach(t => { if (t.length >= 3 && !GENERIC_PLACE.has(t)) venueTokens.set(t, (venueTokens.get(t) || 0) + 1); });
      const parts = String(loc).split(',');
      if (parts.length > 1) { const city = parts[parts.length - 1].trim().toLowerCase(); if (city) cityCounts.set(city, (cityCounts.get(city) || 0) + 1); }
    });
    let defaultCity = '';
    cityCounts.forEach((n, c) => { if (!defaultCity || n > cityCounts.get(defaultCity)) defaultCity = c; });
    const placeMatch = s.match(/\b(?:in|at|near|around|from)\s+((?:the\s+)?[a-z][a-z .'-]{1,34}?)(?=\s+(?:this|next|on|for|under|below|free|paid|with|today|tomorrow|tonight|and|that|which|during|by|over|above)\b|\s*$)/);
    const wordsOf = p => p.split(/\s+/).filter(w => w && !STOP.has(w));
    const knownPlaceWords = w => w.filter(t => venueTokens.has(t) || t === defaultCity);
    if (placeMatch) {
      const words = wordsOf(placeMatch[1]).filter(w => !GENERIC_PLACE.has(w));
      const known = knownPlaceWords(words);
      const chosen = known.length ? known.sort((a, b) => b.length - a.length)[0] : (words.length && !CATEGORY_WORDS_FLAT.has(words[0]) && !FORMAT_SET.has(words[0]) ? words.join(' ') : '');
      if (chosen) {
        result.place = chosen; result.cityFallback = chosen === defaultCity; chip('place', cap(chosen));
        s = s.replace(placeMatch[0], ' ');
      }
    }
    if (!result.place) {                                  // bare mention, e.g. "dhaka events" or "banani workshops"
      const words = s.trim().split(/\s+/);
      const hit = words.find(w => w.length >= 3 && !STOP.has(w) && (w === defaultCity || (venueTokens.has(w) && !CATEGORY_WORDS_FLAT.has(w) && !FORMAT_SET.has(w) && venueTokens.get(w) >= 1 && isPlaceLike(w, locations))));
      if (hit) { result.place = hit; result.cityFallback = hit === defaultCity; chip('place', cap(hit)); s = s.replace(new RegExp('\\b' + esc(hit) + '\\b'), ' '); }
    }

    // ---- category + concepts + formats + leftover keywords
    const available = new Set(categories.map(c => c.toLowerCase()));
    const rest = s.trim().split(/\s+/).filter(Boolean);
    const catHits = new Map();                            // category -> words
    const groups = []; const used = new Set();
    const addGroup = (label, alts, format) => { const key = alts.join('|'); if (!used.has(key)) { used.add(key); groups.push({ label, alts, format: !!format }); } };
    rest.forEach(raw => {
      const w = raw.replace(/[^a-z0-9+#.-]/g, '').replace(/^[.-]+|[.-]+$/g, '');
      if (!w || STOP.has(w) || /^\d+$/.test(w)) return;
      const stem = w.length > 3 && w.endsWith('s') && !w.endsWith('ss') ? w.slice(0, -1) : w;
      if (CONCEPTS[stem] || CONCEPTS[w]) { const c = CONCEPTS[stem] || CONCEPTS[w]; addGroup(c.label, c.alts); return; }
      let matchedCategory = null;
      for (const [catName, words] of Object.entries(CATEGORY_WORDS)) { if ((words.includes(w) || words.includes(stem)) && available.has(catName.toLowerCase())) { matchedCategory = catName; break; } }
      if (matchedCategory) {
        if (!catHits.has(matchedCategory)) catHits.set(matchedCategory, []);
        catHits.get(matchedCategory).push(w);
        if (SPECIFIC.has(w) || SPECIFIC.has(stem)) addGroup(cap(stem), [w, stem].filter((v, i, a) => a.indexOf(v) === i));
        return;
      }
      if (available.has(w) || available.has(stem)) { const name = categories.find(c => c.toLowerCase() === w || c.toLowerCase() === stem); if (name) { catHits.set(name, (catHits.get(name) || []).concat(w)); return; } }
      addGroup(cap(stem), [w, stem].filter((v, i, a) => a.indexOf(v) === i), FORMAT_SET.has(w) || FORMAT_SET.has(stem));
    });
    if (catHits.size === 1) { const name = [...catHits.keys()][0]; result.category = name; chip('category', name); }
    else if (catHits.size > 1) {                           // several topics: keep them all as OR keywords instead of a single category
      const names = [...catHits.keys()]; names.forEach(n => addGroup(n, [n.toLowerCase()]));
    }
    // a group that only repeats the chosen category word is noise
    result.groups = groups.filter(g => !(result.category && g.alts.every(a => a === result.category.toLowerCase()))).slice(0, 5);
    result.keywords = result.groups.map(g => g.label);
    if (result.groups.length) chip('keywords', 'Matching: ' + result.groups.map(g => g.label).join(', '));
    result.understood = result.chips.some(c => c.kind !== 'keywords');
    return result;
  }

  // helpers that need the tables above
  const CATEGORY_WORDS_FLAT = new Set(Object.values(CATEGORY_WORDS).flat());
  const FORMAT_SET = new Set(FORMATS.concat(FORMATS.map(f => f + 's')));
  function isPlaceLike(word, locations) {
    // a venue token counts as a place word when it is the first word of a venue name or follows a comma, e.g. "Banani Creative Studio"
    return locations.some(l => { const parts = String(l).toLowerCase().split(','); return parts.some(p => p.trim().split(/\s+/)[0] === word) || parts[parts.length - 1].trim() === word; });
  }

  /** Default city of the data: the most common word after a comma in venue names (e.g. "BICC, Dhaka" -> dhaka). */
  function defaultCityOf(locations) {
    const counts = new Map();
    (locations || []).forEach(l => { const p = String(l).split(','); if (p.length > 1) { const c = p[p.length - 1].trim().toLowerCase(); counts.set(c, (counts.get(c) || 0) + 1); } });
    let best = ''; counts.forEach((n, c) => { if (!best || n > counts.get(best)) best = c; }); return best;
  }

  /** Compiled matcher for one keyword group: true when any alternative appears as a whole word/phrase. */
  function groupRegex(group) { return new RegExp('(^|[^a-z0-9])(' + group.alts.map(esc).join('|') + ')(s|es)?([^a-z0-9]|$)', 'i'); }

  return { parse, groupRegex, defaultCityOf, weekend };
});
