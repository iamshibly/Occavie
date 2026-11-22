// Run with:  node --test src/test/js/
const test = require('node:test');
const assert = require('node:assert/strict');
const S = require('../../main/resources/static/js/smart-search.js');

const ctx = {
  today: new Date(2026, 10, 19),                // Thursday 19 Nov 2026
  categories: ['Technology', 'Business', 'Education', 'Health', 'Design', 'Music', 'Career', 'Sports', 'Community', 'Arts', 'Science', 'Food', 'Charity', 'Other'],
  locations: ['BICC, Dhaka', 'Bangladesh Startup Hub', 'Banani Creative Studio', 'Gulshan Innovation Hub', 'Hatirjheel, Dhaka',
    'Southeast University, Dhaka', 'SEU Computer Lab', 'Dhanmondi Lake', 'Motijheel Training Centre']
};
const p = text => S.parse(text, ctx);

test('the headline example: free AI workshops in Dhaka this weekend', () => {
  const r = p('Free AI workshops in Dhaka this weekend');
  assert.equal(r.pricing, 'FREE');
  assert.equal(r.place, 'dhaka');
  assert.equal(r.cityFallback, true);
  assert.equal(r.from, '2026-11-20'); assert.equal(r.to, '2026-11-21');    // Bangladesh weekend: Fri + Sat
  assert.ok(r.groups.some(g => g.label === 'AI' && g.alts.includes('machine learning')));
  assert.ok(r.groups.some(g => g.alts.includes('workshop')));
  assert.equal(r.category, null);
  assert.ok(r.understood);
});

test('category words become the category filter', () => {
  const r = p('free tech events this weekend');
  assert.equal(r.category, 'Technology'); assert.equal(r.pricing, 'FREE'); assert.equal(r.from, '2026-11-20');
});

test('price caps, cheap sorting and months', () => {
  const r = p('cheap music under 500 in december');
  assert.equal(r.category, 'Music'); assert.equal(r.maxPrice, 500); assert.equal(r.sort, 'price-asc');
  assert.equal(r.from, '2026-12-01'); assert.equal(r.to, '2026-12-31');
  assert.equal(p('events below tk 300').maxPrice, 300);
  assert.equal(p('under ৳1,200').maxPrice, 1200);
});

test('specific topics keep their keyword next to the category', () => {
  const y = p('yoga tomorrow'); assert.equal(y.category, 'Health'); assert.ok(y.groups.some(g => g.label === 'Yoga')); assert.equal(y.from, '2026-11-20'); assert.equal(y.to, '2026-11-20');
  const c = p('cricket'); assert.equal(c.category, 'Sports'); assert.ok(c.groups.some(g => g.label === 'Cricket'));
});

test('places, weeks and single dates', () => {
  const b = p('events in Banani next week'); assert.equal(b.place, 'banani'); assert.equal(b.cityFallback, false); assert.equal(b.from, '2026-11-22'); assert.equal(b.to, '2026-11-28');
  assert.equal(p('28 nov').from, '2026-11-28');
  assert.equal(p('marathon on 3 december').from, '2026-12-03');
  const w = p('jobs and internships this week'); assert.equal(w.category, 'Career'); assert.equal(w.from, '2026-11-19'); assert.equal(w.to, '2026-11-21');
  assert.equal(p('what is on friday').from, '2026-11-20');
  assert.equal(p('events next 7 days').to, '2026-11-26');
  assert.equal(p('next month').from, '2026-12-01');
});

test('past dates roll to next year and May is only a month after in/during', () => {
  assert.equal(p('in may').from, '2027-05-01');
  assert.equal(p('1 jan').from, '2027-01-01');
  assert.ok(!p('may i find a workshop').from);
});

test('status words', () => {
  assert.equal(p('past workshops').status, 'COMPLETED');
  assert.equal(p('all events').status, 'ALL');
});

test('two topics are kept as OR keywords, not one wrong category', () => {
  const r = p('I want to learn design under tk 300');
  assert.equal(r.category, null); assert.equal(r.maxPrice, 300);
  assert.ok(r.groups.some(g => g.alts.includes('education'))); assert.ok(r.groups.some(g => g.alts.includes('design')));
});

test('unknown sentences fall back to keyword groups and are not "understood"', () => {
  const r = p('hello world');
  assert.equal(r.understood, false); assert.deepEqual(r.groups.map(g => g.label), ['Hello', 'World']);
  assert.equal(p('').groups.length, 0);
});

test('unknown places are still reported so the page can explain a miss', () => {
  const r = p('music events in Chittagong'); assert.equal(r.category, 'Music'); assert.equal(r.place, 'chittagong'); assert.equal(r.cityFallback, false);
});

test('keyword groups match whole words only', () => {
  const g = S.groupRegex({ label: 'AI', alts: ['ai', 'machine learning'] });
  assert.ok(g.test('AI & Cloud Innovation Summit')); assert.ok(g.test('Intro to Machine Learning'));
  assert.ok(!g.test('Mail campaign training')); assert.ok(!g.test('Fair trade'));
});

test('hostile input cannot break the parser', () => {
  const r = p('<script>alert(1)</script> (((( [[ \\ ' + 'a'.repeat(5000));
  assert.ok(Array.isArray(r.groups));
});
