/* Occavie Concierge: the assistant's brain. Rule-based and offline (no AI service, no keys): it recognises what a visitor asks,
   looks the answer up in the live event list the site already shows, and replies with plain text, clickable event cards and
   follow-up chips. Pure functions, unit-tested in src/test/js/concierge-core.test.js.

   createEngine({ events, today }).answer(text, state) -> { text, events, chips, links, state } */
(function (root, factory) {
  const smart = (typeof module === 'object' && module.exports) ? require('./smart-search.js') : root.OccavieSmart;
  if (typeof module === 'object' && module.exports) module.exports = factory(smart);
  else root.OccavieConcierge = factory(smart);
})(typeof self !== 'undefined' ? self : this, function (Smart) {
  'use strict';

  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const TITLE_SKIP = new Set(['the', 'and', 'for', 'with', 'by', 'of', 'in', 'to', 'a', 'an', 'at', 'on']);
  const FILLER = new Set(('what whats is are the a an of for to in on at with about tell me please can could you i do does there any how much many ' +
    'when where which who this that it its event events ticket tickets price prices cost seats seat left available availability full sold out ' +
    'time date day venue location address map details detail info information show find give need want like is it ok okay').split(/\s+/));

  const norm = s => String(s || '').toLowerCase().replace(/[^\p{L}\p{N}৳&\s'-]/gu, ' ').replace(/\s+/g, ' ').trim();
  const words = s => norm(s).split(' ').filter(Boolean);
  const stem = w => (w.length > 3 && w.endsWith('s') && !w.endsWith('ss')) ? w.slice(0, -1) : w;
  const dateOf = iso => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d); };
  const when = e => { const d = dateOf(e.d); return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}${e.tm ? ' · ' + e.tm : ''}`; };
  const seatsText = e => e.left <= 0 ? 'Sold out' : (e.left <= 10 ? `Only ${e.left} left` : `${e.left} seats left`);
  const isFree = e => e.p === 0 && /free/i.test(e.pl || 'free');
  const daysFrom = (today, iso) => Math.round((dateOf(iso) - today) / 86400000);

  const INTENT = {
    seats: /\b(seats?|spots?|spaces?|available|availability|vacanc\w*|full|sold out|capacity|left|room)\b/,
    price: /\b(price|prices|cost|costs|how much|fee|fees|charge|ticket types?|tickets|vip|standard|pay)\b/,
    when: /\b(when|what time|date|starts?|begins?|time|which day|what day)\b/,
    where: /\b(where|venue|location|address|map|directions?|located|place)\b/,
    about: /\b(about|details?|info|information|tell me|describe|what is|what's|whats)\b/,
    compare: /\b(compare|comparison|versus|vs|difference|which is better|better)\b/,
    similar: /\b(similar|more like|alternatives?|other events like|events like)\b/
  };

  const POLICY = [
    { id: 'refund', re: /\b(refund|money back|chargeback)\b/, text: 'Payments on this site are a demo: no real money is charged, so there is nothing to refund.',
      links: [['Contact support', '/contact']] },
    { id: 'cancel', re: /\b(cancel\w*|unregister|withdraw my|remove my registration|change my booking)\b/,
      text: 'You can cancel a free registration from My Registrations before the event starts. Paid bookings cannot be cancelled online, so please contact support if something went wrong.',
      links: [['My registrations', '/participant/registrations'], ['Contact support', '/contact']] },
    { id: 'ticket', re: /\b(my ticket|download (my |the |a )?ticket|get (my |the )?ticket|ticket pdf|pdf|qr|check.?in|receipt|print (my )?ticket|show (my )?ticket|entry pass|entrance)\b/,
      text: 'After you book, open My Registrations to download your PDF ticket (it has a QR code) and your receipt. Show the QR code at the entrance, on your phone or printed: the organizer scans it to check you in.',
      links: [['My registrations', '/participant/registrations']] },
    { id: 'payment', re: /\b(payment methods?|how (do|can) i pay|pay with|bkash|nagad|visa|card|checkout|payment)\b/,
      text: 'Checkout is a demo: you can pick bKash, Nagad or Visa, but no real payment is taken. Free events skip payment entirely, and your ticket and receipt are ready straight after booking.', links: [] },
    { id: 'coupon', re: /\b(coupon|promo|promo code|discount|voucher|offer code)\b/,
      text: 'Enter your promo code on the checkout page and press Apply: the discount shows before you confirm. Coupons are created by organizers for their own events.', links: [] },
    { id: 'waitlist', re: /\b(wait.?list|waiting list|event is full|if it.?s full|when (it|an event) is full)\b/,
      text: 'If an event is full, press Join waitlist on its page. When a seat frees up you get an offer that lasts for a limited time; accept it from the event page or your notifications to claim the seat.', links: [] },
    { id: 'account', re: /\b(sign ?up|register an account|create (an )?account|log ?in|login|password|forgot|account)\b/,
      text: 'Create a free account from Sign Up, then log in to book events, save favourites and see your tickets. Forgot your password? Use the link on the login page.',
      links: [['Sign up or log in', '/login']] },
    { id: 'organizer', re: /\b(organi[sz]er|host (an )?event|create (an )?event|publish|list my event|sell tickets|run an event)\b/,
      text: 'To host events, sign up and choose the Organizer role, then use Create Event in your dashboard. A step-by-step wizard saves drafts as you go, and an admin may need to approve an event before it goes public.',
      links: [['Sign up', '/login']] },
    { id: 'contact', re: /\b(contact|support|help desk|talk to (a )?(human|person)|real person|customer service|phone|email us|whatsapp)\b/,
      text: 'You can reach the team from the Contact page. Send a message and an administrator will reply.', links: [['Contact support', '/contact']] },
    { id: 'calendar', re: /\b(calendar|ics|google calendar|add to calendar|remind me|reminder)\b/,
      text: 'Open any event page and press Add to calendar to download an .ics file for your calendar app. Registered participants also get reminder notifications before the event.', links: [] },
    { id: 'save', re: /\b(save|bookmark|favou?rites?|wishlist)\b/,
      text: 'Press Save on an event page to bookmark it. Saved events appear on your participant dashboard, and they also make my recommendations better.', links: [['Browse events', '/events']] },
    { id: 'compare-feature', re: /\b(how (do|can) i compare|compare feature)\b/,
      text: 'Press Compare on two or three event cards, then open the comparison from the bar at the bottom. Or just tell me which events to compare, for example "compare yoga and cricket".', links: [] },
    { id: 'about', re: /\b(what is occavie|about occavie|who (are|made|built) (you|occavie)|what do you do)\b/,
      text: 'Occavie helps people discover events, book tickets and run unforgettable experiences. Visitors can search and compare events, participants get PDF tickets with QR codes, and organizers get a Copilot that helps them improve their events.', links: [] }
  ];

  const HOME_CHIPS = ['Free events this weekend', "What's popular right now?", 'How do I get my ticket?', 'Can I cancel a booking?', 'Cheap events next month'];

  function createEngine(data) {
    const events = (data && data.events) || [];
    const today = data && data.today ? dateOf(data.today) : new Date(new Date().getFullYear(), new Date().getMonth(), new Date().getDate());
    const byId = new Map(events.map(e => [e.id, e]));
    const categories = Array.from(new Set(events.map(e => e.c).filter(Boolean))).sort();
    const locations = Array.from(new Set(events.map(e => e.v).filter(Boolean)));

    const card = (e, why) => ({ id: e.id, title: e.t, when: when(e), venue: e.v || '', price: e.pl || (isFree(e) ? 'Free' : ''), seats: seatsText(e),
      href: '/events/' + e.id, img: e.img || '', category: e.c || '', why: why || '' });

    // ---------------------------------------------------------------- finding an event the visitor means
    function titleTokens(e) { return words(e.t).filter(w => w.length >= 2 && !TITLE_SKIP.has(w)).map(stem); }
    function resolveEvent(text, state) {
      const message = norm(text);
      const tokens = new Set(words(text).map(stem));
      const ranked = events.map(e => {
        const t = titleTokens(e); if (!t.length) return { e, matched: 0, score: 0 };
        const matched = t.filter(x => tokens.has(x)).length;
        let score = matched / t.length;
        if (message.includes(norm(e.t))) score = Math.max(score, 1.5);
        return { e, matched, score };
      }).filter(r => r.matched > 0).sort((a, b) => b.score - a.score);
      const best = ranked[0], second = ranked[1];
      if (best && (best.score >= 1 || (best.matched >= 2 && best.score >= 0.5 && (!second || best.score - second.score >= 0.2)) ||
          (best.matched >= 1 && best.score >= 0.99))) return { event: best.e };
      const ordinal = message.match(/\b(first|1st|second|2nd|third|3rd|last)\b(?: one)?/);
      const lastIds = (state && state.last && state.last.ids) || [];
      if (ordinal && lastIds.length) {
        const idx = { first: 0, '1st': 0, second: 1, '2nd': 1, third: 2, '3rd': 2, last: lastIds.length - 1 }[ordinal[1]];
        if (byId.get(lastIds[idx])) return { event: byId.get(lastIds[idx]) };
      }
      if (/\b(it|this|that|this event|that event|that one|this one|the event)\b/.test(message)) {
        const id = (state && state.page && state.page.eventId) || (state && state.last && state.last.focusId);
        if (id && byId.get(id)) return { event: byId.get(id) };
      }
      const candidates = ranked.filter(r => r.score >= 0.3).slice(0, 3).map(r => r.e);
      if (candidates.length === 1) return { event: candidates[0] };
      return candidates.length ? { candidates } : {};
    }

    // ---------------------------------------------------------------- searching (same rules as the Explore page)
    function matches(e, f) {
      if (f.category && (e.c || '').toLowerCase() !== f.category.toLowerCase()) return false;
      if (f.pricing === 'FREE' && !isFree(e)) return false;
      if (f.pricing === 'PAID' && isFree(e)) return false;
      if (f.maxPrice !== null && f.maxPrice !== undefined && e.p > f.maxPrice) return false;
      if (f.from && e.d < f.from) return false;
      if (f.to && e.d > f.to) return false;
      if (f.place) {
        const v = (e.v || '').toLowerCase();
        if (!v.includes(f.place) && !(f.cityFallback && !v.includes(','))) return false;
      }
      const hay = ((e.t || '') + ' ' + (e.c || '') + ' ' + (e.v || '') + ' ' + (e.org || '')).toLowerCase();
      return (f.regexes || []).every(re => re.test(hay));
    }

    // Conversational filler that should never become a search keyword.
    const CHAT_FILLER = /\b(what'?s|whats|what is|right now|currently|at the moment|please|anything|something|some|me|show|find|give|list|looking for|i want|i'd like|i would like|any|good|nice|cool|great|best|top|popular|trending|recommended?|suggest(ed|ions?)?|happening|going on|is on|are there|there are|do you have|can you|could you)\b/g;

    function search(text) {
      const cleaned = text.replace(CHAT_FILLER, ' ').replace(/\s+/g, ' ').trim();
      const p = Smart.parse(cleaned, { today, categories, locations });
      const base = { category: p.category, pricing: p.pricing, maxPrice: p.maxPrice, from: p.from, to: p.to, place: p.place, cityFallback: p.cityFallback, groups: p.groups };
      const run = drop => {
        const groups = drop.keywords ? [] : (drop.formats ? p.groups.filter(g => !g.format) : p.groups);
        const f = { category: base.category, pricing: base.pricing, maxPrice: drop.price ? null : base.maxPrice, from: drop.date ? '' : base.from, to: drop.date ? '' : base.to,
          place: drop.place ? '' : base.place, cityFallback: !drop.place && base.cityFallback, regexes: groups.map(g => Smart.groupRegex(g)) };
        return { f, found: events.filter(e => matches(e, f)) };
      };
      let drop = {}, result = run(drop);
      const notes = [];
      const ladder = [['formats', p.groups.some(g => g.format), 'the word "' + ((p.groups.find(g => g.format) || {}).label || '').toLowerCase() + '"'], ['keywords', p.groups.length, 'the extra words'],
        ['date', p.from, 'the dates'], ['price', p.maxPrice !== null, 'the price limit'], ['place', p.place, 'the place']];
      for (const [k, active, label] of ladder) {
        if (result.found.length) break;
        if (!active) continue;
        if (!p.understood && (k === 'keywords' || k === 'formats')) continue;      // nothing but unknown words: do not widen to everything
        drop = Object.assign({}, drop, { [k]: true }); notes.push(label); result = run(drop);
      }
      const sorted = result.found.slice();
      if (p.sort === 'price-asc') sorted.sort((a, b) => a.p - b.p || a.d.localeCompare(b.d));
      else if (p.sort === 'price-desc') sorted.sort((a, b) => b.p - a.p);
      else sorted.sort((a, b) => a.d.localeCompare(b.d));
      return { parsed: p, found: sorted, notes, dropped: drop };
    }

    function describeFilters(p, dropped) {
      const parts = p.chips.filter(c => c.kind !== 'keywords' && c.kind !== 'sort' && c.kind !== 'status' &&
        !(dropped.date && c.kind === 'date') && !(dropped.place && c.kind === 'place') && !(dropped.price && c.kind === 'maxprice')).map(c => c.label);
      const kw = (dropped.keywords ? [] : p.groups.filter(g => !(dropped.formats && g.format))).map(g => g.label);
      return parts.concat(kw.length ? ['matching ' + kw.join(', ')] : []).join(' · ');
    }

    function popular(limit) {
      const scored = events.map(e => {
        const days = daysFrom(today, e.d); let s = 0; const why = [];
        if (days <= 7) { s += 3; why.push('Happening this week'); } else if (days <= 14) { s += 2; why.push('Coming up soon'); } else if (days <= 30) s += 1;
        const filled = e.cap > 0 ? 1 - (e.left / e.cap) : 0;
        if (filled >= 0.7) { s += 3; why.push('Filling fast'); } else if (filled >= 0.3) { s += 1.5; why.push('Popular'); }
        if (e.f) { s += 2; why.push('Featured'); }
        if (isFree(e)) s += 0.5;
        if (e.left <= 0) s -= 10;
        return { e, s, why: why[0] || (isFree(e) ? 'Free to join' : 'On ' + when(e).split(' · ')[0]) };
      }).filter(x => x.e.left > 0).sort((a, b) => b.s - a.s || a.e.d.localeCompare(b.e.d));
      const picked = []; const perCategory = {};
      for (const x of scored) { if (picked.length >= limit) break; if ((perCategory[x.e.c] || 0) >= 2) continue; picked.push(x); perCategory[x.e.c] = (perCategory[x.e.c] || 0) + 1; }
      return picked;
    }

    // ---------------------------------------------------------------- answering
    function reply(text, extra) { return Object.assign({ text, events: [], chips: [], links: [] }, extra || {}); }
    const linksOf = pairs => (pairs || []).map(([label, href]) => ({ label, href }));

    function eventChips(e) { return ['Seats left?', 'Ticket prices', 'Where is it?', 'Similar events']; }

    function answer(text, stateIn) {
      const state = { last: Object.assign({ ids: [], focusId: '' }, (stateIn && stateIn.last) || {}), page: (stateIn && stateIn.page) || {} };
      const message = norm(text);
      const finish = r => { r.state = state; if (!r.chips.length) r.chips = r.events.length ? ['Compare these', 'Cheapest first', 'Show more'] : HOME_CHIPS.slice(0, 4); return r; };
      const focus = e => { state.last.focusId = e.id; };
      const showList = (list) => { state.last.ids = list.map(e => e.id); if (list[0]) state.last.focusId = list[0].id; };

      if (!message) return finish(reply('Ask me about events, tickets, seats or how Occavie works.', { chips: HOME_CHIPS }));
      if (/^(hi|hello|hey|hii+|salam|assalamu? ?alaikum|good (morning|afternoon|evening)|yo|hola)\b/.test(message) && words(message).length <= 4)
        return finish(reply('Hi! I am the Occavie Concierge. I can find events, check seats and ticket prices, compare events and explain how booking works. What are you looking for?', { chips: HOME_CHIPS }));
      if (/^(thanks|thank you|thx|ty|great|awesome|perfect|cool)\b/.test(message) && words(message).length <= 4)
        return finish(reply('You are welcome! Anything else I can help with?', { chips: ['Free events this weekend', 'How do I get my ticket?'] }));
      if (/^(bye|goodbye|see you|that.?s all)\b/.test(message)) return finish(reply('Goodbye! Enjoy your event.'));
      if (/\b(help|what can you do|what do you do|how does this work|options|menu)\b/.test(message) && words(message).length <= 6)
        return finish(reply('I can: find events ("free tech events this weekend"), check seats and ticket prices for an event, tell you when and where it is, compare events, suggest what is popular, and explain booking, tickets, payments, cancellations and the waitlist.', { chips: HOME_CHIPS }));

      let ref = resolveEvent(text, state);
      const pageEvent = state.page && state.page.eventId ? byId.get(state.page.eventId) : null;
      if (!ref.event && !ref.candidates && pageEvent && (INTENT.seats.test(message) || INTENT.price.test(message) || INTENT.when.test(message) || INTENT.where.test(message) || INTENT.similar.test(message))) ref = { event: pageEvent };
      const hasEvent = !!ref.event;

      // Policy questions (unless the visitor is clearly asking about a specific event's price/seats)
      const eventQuestion = INTENT.seats.test(message) || INTENT.price.test(message) || INTENT.when.test(message) || INTENT.where.test(message);
      for (const p of POLICY) {
        if (!p.re.test(message)) continue;
        if (hasEvent && eventQuestion && !['ticket', 'cancel', 'refund', 'waitlist'].includes(p.id)) continue;
        if (p.id === 'ticket' && hasEvent && INTENT.price.test(message) && !/\b(my ticket|download|pdf|qr|check.?in|receipt)\b/.test(message)) continue;
        return finish(reply(p.text, { links: linksOf(p.links) }));
      }

      // Compare
      if (INTENT.compare.test(message)) {
        const found = [];
        const tokens = new Set(words(text).map(stem));
        events.forEach(e => { const t = titleTokens(e); const m = t.filter(x => tokens.has(x)).length; if (m >= 1 && m / t.length >= 0.3) found.push({ e, m, r: m / t.length }); });
        found.sort((a, b) => b.r - a.r || b.m - a.m);
        const floor = found.length ? Math.min(0.5, found[0].r * 0.5) : 0;      // drop weak matches that only share a common word
        let picks = found.filter(x => x.r >= floor).slice(0, 3).map(x => x.e);
        if (picks.length < 2 && /\b(these|them|those|both)\b/.test(message) && state.last.ids.length >= 2) picks = state.last.ids.slice(0, 3).map(id => byId.get(id)).filter(Boolean);
        if (picks.length < 2) return finish(reply('Which events should I compare? Name two or three, for example "compare Cloud Native Meetup and Morning Yoga", or press Compare on event cards.', { chips: ['Compare these', "What's popular right now?"] }));
        showList(picks);
        const best = picks.slice().sort((a, b) => a.p - b.p)[0];
        return finish(reply(`Here is how they line up. ${best.t} has the lowest starting price (${best.pl || 'Free'}).`, {
          events: picks.map(e => card(e)), links: [{ label: 'Open the full comparison', href: '/compare?ids=' + picks.map(e => e.id).join(',') }], chips: ['Seats left?', 'Cheapest first'] }));
      }

      // A question about "an event" without saying which one (and nothing else to search on)
      const asksAbout = INTENT.seats.test(message) || /\b(how much|price|cost|fee)\b/.test(message) || INTENT.when.test(message) || INTENT.where.test(message);
      if (asksAbout && !hasEvent && !ref.candidates) {
        const probe = search(text);
        if (!probe.parsed.understood) {
          const named = probe.found.slice(0, 3);
          if (named.length) { showList(named); return finish(reply('Which event do you mean?', { events: named.map(e => card(e)), chips: named.map(e => e.t) })); }
          return finish(reply('I could not tell which event you mean. Tell me its name (for example "seats for Cloud Native Meetup"), or ask me to find events first.', { chips: HOME_CHIPS.slice(0, 4) }));
        }
      }

      // Event-specific questions
      const specific = INTENT.seats.test(message) || INTENT.price.test(message) || INTENT.when.test(message) || INTENT.where.test(message) || INTENT.about.test(message) || INTENT.similar.test(message);
      if (specific && (hasEvent || ref.candidates)) {
        if (!hasEvent) {
          showList(ref.candidates);
          return finish(reply('Which event do you mean?', { events: ref.candidates.map(e => card(e)), chips: ref.candidates.map(e => e.t) }));
        }
        const e = ref.event; focus(e);
        const link = { label: 'Open event page', href: '/events/' + e.id };
        if (INTENT.similar.test(message)) {
          const sim = events.filter(x => x.id !== e.id && x.c === e.c && x.left > 0).sort((a, b) => a.d.localeCompare(b.d)).slice(0, 4);
          if (!sim.length) return finish(reply(`I do not see other upcoming ${e.c} events right now.`, { chips: ["What's popular right now?"] }));
          showList(sim);
          return finish(reply(`More ${e.c} events like ${e.t}:`, { events: sim.map(x => card(x)) }));
        }
        if (INTENT.seats.test(message)) {
          const pct = e.cap > 0 ? Math.round((e.cap - e.left) * 100 / e.cap) : 0;
          const body = e.left <= 0 ? `${e.t} is sold out right now. You can join the waitlist on the event page and you will be offered a seat if one frees up.`
            : `${e.t} has ${e.left} of ${e.cap} seats left (${pct}% booked).` + (e.left <= 10 ? ' It is almost full, so book soon.' : '');
          return finish(reply(body, { links: [link], events: [card(e)], chips: eventChips(e) }));
        }
        if (INTENT.price.test(message)) {
          const tk = e.tk || [];
          const body = isFree(e) ? `${e.t} is free to join.` + (tk.length ? ' Tickets: ' + tk.map(t => `${t.n} (${t.p})`).join(', ') + '.' : '')
            : `${e.t} starts at ${e.pl}.` + (tk.length > 1 ? ' Ticket types: ' + tk.map(t => `${t.n} ${t.p}`).join(', ') + '.' : '') + ' Checkout is a demo, so no real payment is taken.';
          return finish(reply(body, { links: [link], events: [card(e)], chips: eventChips(e) }));
        }
        if (INTENT.where.test(message) && !INTENT.when.test(message))
          return finish(reply(`${e.t} takes place at ${e.v || 'a venue that will be announced'}.`, { links: [link], events: [card(e)], chips: eventChips(e) }));
        if (INTENT.when.test(message) && !INTENT.about.test(message))
          return finish(reply(`${e.t} is on ${when(e)}${e.v ? ' at ' + e.v : ''}.`, { links: [link], events: [card(e)], chips: eventChips(e) }));
        return finish(reply(`${e.t} (${e.c}) is on ${when(e)} at ${e.v || 'a venue to be announced'}. ${isFree(e) ? 'It is free.' : 'Tickets from ' + e.pl + '.'} ${seatsText(e)}.`,
          { links: [link], events: [card(e)], chips: eventChips(e) }));
      }

      // How many / categories
      if (/\bhow many\b/.test(message) && /\bevents?\b/.test(message) && !Smart.parse(text, { today, categories, locations }).understood) {
        const counts = categories.map(c => `${c} (${events.filter(e => e.c === c).length})`).join(', ');
        return finish(reply(`There are ${events.length} upcoming events: ${counts}.`, { chips: categories.slice(0, 4).map(c => c + ' events') }));
      }
      if (/\b(categories|topics|types of events|kinds of events)\b/.test(message))
        return finish(reply('Upcoming events by topic: ' + categories.map(c => `${c} (${events.filter(e => e.c === c).length})`).join(', ') + '.', { chips: categories.slice(0, 5).map(c => c + ' events') }));

      // Search and recommendations
      const wantsPopular = /\b(recommend\w*|suggest\w*|what should|something|surprise me|popular|trending|best|top|what.?s on|whats on|happening|any events|upcoming|good events|for me)\b/.test(message);
      const result = search(text);
      const understood = result.parsed.understood || result.parsed.groups.length > 0;
      if (wantsPopular && !result.parsed.understood && !result.parsed.groups.filter(g => !/^(Upcoming|Events?|S|Now|Right|Good|Best|Popular)$/i.test(g.label)).length) {
        const picks = popular(4);
        if (!picks.length) return finish(reply('There are no upcoming events open for booking right now. Please check back soon.'));
        showList(picks.map(x => x.e));
        return finish(reply('Here is what looks good right now: happening soon, filling up or featured.', { events: picks.map(x => card(x.e, x.why)),
          links: [{ label: 'Browse all events', href: '/events' }], chips: ['Free events this weekend', 'Compare these'] }));
      }
      if (/^(more|show more|next)$/.test(message) && state.last.ids.length) {
        const rest = events.filter(e => !state.last.ids.includes(e.id)).slice(0, 4); showList(state.last.ids.map(id => byId.get(id)).concat(rest).filter(Boolean));
        return finish(reply('More events:', { events: rest.map(e => card(e)), links: [{ label: 'Browse all events', href: '/events' }] }));
      }
      if (/^cheapest( first)?$/.test(message) && state.last.ids.length) {
        const list = state.last.ids.map(id => byId.get(id)).filter(Boolean).sort((a, b) => a.p - b.p); showList(list);
        return finish(reply('Cheapest first:', { events: list.slice(0, 4).map(e => card(e)) }));
      }
      if (!understood) {
        if (ref.candidates) { showList(ref.candidates); return finish(reply('Did you mean one of these?', { events: ref.candidates.map(e => card(e)), chips: ref.candidates.map(e => e.t) })); }
        return finish(reply('I am not sure I understood that. I can find events (try "free tech events this weekend"), check seats and prices, compare events or explain booking. What would you like?', { chips: HOME_CHIPS }));
      }
      const found = result.found;
      const summary = describeFilters(result.parsed, result.dropped);
      if (!found.length) return finish(reply(summary && result.parsed.understood ? `I could not find upcoming events matching ${summary}. Try fewer filters, or browse all events.` : `I could not find any upcoming event matching "${text.trim().slice(0, 60)}". Try a topic, a place or a date, or browse all events.`,
        { links: [{ label: 'Browse all events', href: '/events' }], chips: ["What's popular right now?", 'Free events this weekend'] }));
      showList(found);
      const lead = result.notes.length ? `No exact match, so I ignored ${result.notes.join(', ')}. ` : '';
      const head = `${lead}I found ${found.length} event${found.length === 1 ? '' : 's'}${summary ? ' for ' + summary : ''}.${found.length > 4 ? ' Here are the closest:' : ''}`;
      return finish(reply(head, { events: found.slice(0, 4).map(e => card(e)),
        links: [{ label: found.length > 4 ? `See all ${found.length} on Explore` : 'Open in Explore', href: '/events?ask=' + encodeURIComponent(text.trim()) }],
        chips: found.length > 1 ? ['Compare these', 'Cheapest first'] : eventChips(found[0]) }));
    }

    return { answer, homeChips: HOME_CHIPS, count: events.length };
  }

  return { createEngine, HOME_CHIPS };
});
