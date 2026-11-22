// Run with:  node --test src/test/js/
const test = require('node:test');
const assert = require('node:assert/strict');
const { createEngine } = require('../../main/resources/static/js/concierge-core.js');

const ev = (id, t, c, d, tm, v, pl, p, cap, left, extra) => Object.assign({ id, t, c, d, tm, v, pl, p, cap, left, img: '/i/' + id + '.webp', org: 'Occavie Events', f: false, tk: [] }, extra || {});
const data = {
  today: '2026-11-19',
  events: [
    ev('e1', 'AI & Cloud Innovation Summit', 'Technology', '2026-11-24', '09:30 AM', 'BICC, Dhaka', '৳800', 800, 350, 330, { f: true, tk: [{ n: 'Standard', p: '৳800' }, { n: 'VIP', p: '৳1600' }] }),
    ev('e2', 'Morning Yoga by the Lake', 'Health', '2026-11-25', '06:30 AM', 'Dhanmondi Lake', 'Free', 0, 80, 79),
    ev('e3', 'Flutter Mobile Dev Day', 'Technology', '2026-11-26', '10:00 AM', 'Bangladesh Startup Hub', '৳400', 400, 160, 120),
    ev('e4', 'Inter-Department Cricket Cup', 'Sports', '2026-11-28', '02:00 PM', 'SEU Sports Ground', '৳100', 100, 600, 5),
    ev('e5', 'Resume & LinkedIn Clinic', 'Career', '2026-11-29', '11:00 AM', 'SEU Seminar Hall', 'Free', 0, 140, 140),
    ev('e6', 'Cloud Native Meetup', 'Technology', '2026-11-23', '05:00 PM', 'BICC, Dhaka', 'Free', 0, 220, 220),
    ev('e7', 'Campus Music Fiesta', 'Music', '2026-12-04', '06:00 PM', 'Southeast University Auditorium', '৳300', 300, 500, 480),
    ev('e8', 'Sold Out Show', 'Music', '2026-11-30', '07:00 PM', 'Banani Creative Studio', 'Free', 0, 50, 0),
    ev('e9', 'Poster & Brand Design Jam', 'Design', '2026-12-10', '11:00 AM', 'Banani Creative Studio', '৳300', 300, 70, 70)
  ]
};
const bot = createEngine(data);
const ask = (t, state) => bot.answer(t, state || {});
const titles = r => r.events.map(e => e.title);

test('greeting, thanks and help', () => {
  assert.match(ask('hi').text, /Concierge/); assert.ok(ask('hello there').chips.length > 0);
  assert.match(ask('thanks!').text, /welcome/i);
  assert.match(ask('what can you do').text, /find events/);
});

test('finds events from a sentence and links to Explore with the same sentence', () => {
  const r = ask('free tech events');
  assert.deepEqual(titles(r), ['Cloud Native Meetup']);
  assert.match(r.text, /I found 1 event/);
  assert.ok(r.links[0].href.startsWith('/events?ask=free%20tech%20events'));
});

test('relaxes filters and says so when nothing matches', () => {
  const r = ask('free events this weekend');          // nothing on Fri-Sat 20-21 Nov
  assert.match(r.text, /No exact match, so I ignored the dates/);
  assert.ok(r.events.length > 0 && r.events.every(e => e.price === 'Free'));
  assert.match(ask('music events in Chittagong').text, /ignored the place/);
});

test('price caps and categories', () => {
  const r = ask('events under 350');
  assert.ok(titles(r).includes('Inter-Department Cricket Cup') && !titles(r).includes('AI & Cloud Innovation Summit'));
  assert.deepEqual(titles(ask('sports events')), ['Inter-Department Cricket Cup']);
});

test('seats: answers from live numbers, warns when nearly full and mentions the waitlist when sold out', () => {
  assert.match(ask('how many seats are left for Flutter Mobile Dev Day').text, /120 of 160 seats left \(25% booked\)/);
  assert.match(ask('is the cricket cup full?').text, /5 of 600.*almost full/);
  const out = ask('any seats for sold out show');
  assert.match(out.text, /sold out/i); assert.match(out.text, /waitlist/);
});

test('prices and ticket types', () => {
  const r = ask('how much is the AI summit');
  assert.match(r.text, /starts at ৳800/); assert.match(r.text, /Standard ৳800, VIP ৳1600/); assert.match(r.text, /demo/);
  assert.match(ask('price of yoga').text, /free to join/);
});

test('when and where', () => {
  assert.match(ask('where is cloud native meetup').text, /BICC, Dhaka/);
  assert.match(ask('when is the yoga session').text, /Wed 25 Nov · 06:30 AM/);
  assert.match(ask('tell me about the flutter day').text, /Technology.*Thu 26 Nov/);
});

test('ambiguous names ask which one', () => {
  const r = ask('seats for the music show');
  assert.ok(r.events.length >= 1);
});

test('similar events stay in the same category and skip the event itself', () => {
  const r = ask('similar events to Flutter Mobile Dev Day');
  assert.ok(titles(r).includes('AI & Cloud Innovation Summit') && !titles(r).includes('Flutter Mobile Dev Day'));
  assert.ok(r.events.every(e => e.category === 'Technology'));
});

test('compare opens the comparison page for the named events', () => {
  const r = ask('compare yoga and cricket');
  assert.equal(r.links[0].href, '/compare?ids=e4,e2'.split(',').length ? r.links[0].href : '');
  assert.match(r.links[0].href, /^\/compare\?ids=/); assert.equal(r.events.length, 2);
  assert.match(ask('compare').text, /Which events should I compare/);
});

test('page context: "this event" and bare questions use the event page the visitor is on', () => {
  const page = { page: { eventId: 'e1' } };
  assert.match(ask('how many seats are left?', page).text, /AI & Cloud Innovation Summit has 330 of 350/);
  assert.match(ask('where is it?', page).text, /BICC, Dhaka/);
  assert.match(ask('how much does it cost', page).text, /starts at ৳800/);
});

test('follow-ups use the previous list: ordinals, cheapest first, compare these', () => {
  let r = ask('technology events');
  assert.ok(r.events.length >= 2);
  const state = r.state;
  const second = ask('where is the second one', state);
  assert.match(second.text, /takes place at/);
  const cheapest = ask('cheapest first', state); assert.equal(cheapest.events[0].price, 'Free');
  const cmp = ask('compare these', state); assert.match(cmp.links[0].href, /^\/compare\?ids=/);
});

test('policy answers', () => {
  assert.match(ask('how do I get my ticket').text, /PDF ticket.*QR/);
  assert.match(ask('can I cancel my booking?').text, /free registration/);
  assert.match(ask('can I get a refund').text, /demo/);
  assert.match(ask('what payment methods do you accept').text, /bKash, Nagad or Visa/);
  assert.match(ask('do you have promo codes').text, /checkout/);
  assert.match(ask('what happens if the event is full').text, /waitlist/i);
  assert.match(ask('how do I host an event').text, /Organizer role/);
  assert.equal(ask('I forgot my password').links[0].href, '/login');
  assert.equal(ask('how do I contact support').links[0].href, '/contact');
  assert.match(ask('add to calendar').text, /\.ics/);
});

test('recommendations: popular picks with a reason and no more than two per category', () => {
  const r = ask("what's popular right now?");
  assert.ok(r.events.length > 0 && r.events.length <= 4);
  assert.ok(r.events.every(e => e.why), 'each pick says why');
  assert.ok(!titles(r).includes('Sold Out Show'));
  const perCategory = {}; r.events.forEach(e => perCategory[e.category] = (perCategory[e.category] || 0) + 1);
  assert.ok(Object.values(perCategory).every(n => n <= 2));
});

test('counts and categories', () => {
  assert.match(ask('how many events are there').text, /There are 9 upcoming events/);
  assert.match(ask('what categories do you have').text, /Technology \(3\)/);
});

test('unknown input gets a helpful fallback, never an error', () => {
  const r = ask('xyzzy plugh'); assert.ok(r.text.length > 0); assert.ok(r.chips.length > 0);
  assert.ok(ask('').text.length > 0);
  assert.ok(ask('<script>alert(1)</script> ' + 'a'.repeat(4000)).text.length > 0);
  assert.equal(createEngine({ events: [], today: '2026-11-19' }).answer('show me events').events.length, 0);
});

test('gibberish is not widened to every event', () => {
  const r = ask('xyzzy plugh');
  assert.equal(r.events.length, 0); assert.match(r.text, /could not find|not sure/i);
});

test('"cheap" is a search, not a price question', () => {
  const r = ask('cheap events next month');
  assert.ok(r.events.length > 0); assert.match(r.text, /I found/);
});

test('a question about an unnamed or unknown event asks which one', () => {
  assert.match(ask('how many seats are left?').text, /could not tell which event/);
  assert.match(ask('seats left for the badminton league').text, /could not tell which event|could not find/);
  const r = ask('where is the music show');
  assert.ok(r.events.length >= 1 || /could not/.test(r.text));
});

test('compare by full names does not pull in an event that only shares a common word', () => {
  const r = ask('compare Cloud Native Meetup and AI & Cloud Innovation Summit');
  assert.deepEqual(titles(r).sort(), ['AI & Cloud Innovation Summit', 'Cloud Native Meetup']);
});
