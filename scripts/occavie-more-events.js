// Occavie: 30 ADDITIONAL upcoming demo events (mongosh / Compass shell). Additive and re-runnable:
// it only deletes records whose _id starts with "more30_" (its own earlier run) and never touches other events,
// bookings, users or the 40-event seed. Events use no uploaded photo, so the site shows a category cover.
(function () {
  const d = db.getSiblingDB("eventflow");
  const organizer = d.users.findOne({ email: "organizer@eventflow.com" });
  if (!organizer) throw new Error("Start Occavie once to create the demo organizer, then run this script.");
  const organizerId = String(organizer._id);
  const tag = "OCCAVIE_MORE30";
  for (const c of ["events", "event_ticket_types", "event_schedule"]) d.getCollection(c).deleteMany({ _id: /^more30_/ });

  // Same time handling as the 40-event seed, so times display correctly whatever timezone the server uses.
  let timeShift = 0;
  const sample = d.events.findOne({ title: "Tech Innovation Summit", time: { $type: "date" } });
  if (sample && sample.time instanceof Date) {
    timeShift = sample.time.getUTCHours() * 60 + sample.time.getUTCMinutes() - 600;
    if (timeShift > 720) timeShift -= 1440;
    if (timeShift < -720) timeShift += 1440;
  }
  const timeOf = (h, m) => { const x = new Date(Date.UTC(2026, 8, 23)); x.setUTCMinutes(h * 60 + (m || 0) + timeShift); return x; };
  const today = new Date();
  const dateIn = n => new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() + n, 12));
  const money = x => NumberDecimal(String(x));

  // [title, category, venue, days from today, hour, minute, capacity, price in BDT]
  const rows = [
    ["Flutter Mobile Dev Day", "Technology", "Bangladesh Startup Hub", 7, 10, 0, 160, 400],
    ["Cloud Native Meetup", "Technology", "BICC, Dhaka", 11, 17, 0, 220, 0],
    ["Women in Leadership Summit", "Business", "Gulshan Innovation Hub", 14, 9, 30, 300, 900],
    ["Small Business Finance Clinic", "Business", "Motijheel Training Centre", 17, 11, 0, 110, 250],
    ["Morning Yoga by the Lake", "Health", "Dhanmondi Lake", 6, 6, 30, 80, 0],
    ["Nutrition & Fitness Workshop", "Health", "SEU Community Hall", 19, 10, 0, 120, 150],
    ["Inter-Department Cricket Cup", "Sports", "SEU Sports Ground", 9, 14, 0, 600, 100],
    ["5K Fun Run for Clean Air", "Sports", "Hatirjheel Running Track", 23, 6, 45, 700, 200],
    ["Intro to Machine Learning", "Education", "SEU CSE Lab 2", 13, 10, 30, 90, 300],
    ["Scholarship & Study Abroad Fair", "Education", "SEU Central Auditorium", 27, 10, 0, 800, 0],
    ["Acoustic Evening", "Music", "Banani Creative Studio", 16, 18, 30, 150, 350],
    ["Battle of the Bands", "Music", "SEU Open Stage", 30, 17, 0, 900, 250],
    ["Poster & Brand Design Jam", "Design", "Banani Creative Studio", 21, 11, 0, 70, 300],
    ["Figma for Beginners", "Design", "SEU Seminar Hall", 33, 14, 0, 100, 0],
    ["Resume & LinkedIn Clinic", "Career", "SEU Seminar Hall", 10, 11, 0, 140, 0],
    ["Tech Hiring Day", "Career", "International Convention City", 36, 10, 0, 500, 0],
    ["Tree Plantation Drive", "Community", "Hatirjheel, Dhaka", 12, 8, 0, 200, 0],
    ["Neighbourhood Book Swap", "Community", "Dhanmondi Lake", 25, 16, 0, 120, 0],
    ["Open Source Sprint", "Technology", "SEU Computer Lab", 28, 9, 30, 85, 0],
    ["Cybersecurity CTF Night", "Technology", "Dhaka Innovation Center", 40, 18, 0, 180, 200],
    ["Investor Speed Meetings", "Business", "Gulshan Business Centre", 44, 13, 0, 90, 600],
    ["Mental Wellbeing Circle", "Health", "SEU Community Hall", 47, 15, 0, 60, 0],
    ["Table Tennis Open", "Sports", "SEU Indoor Hall", 50, 15, 30, 128, 80],
    ["Research Paper Writing Workshop", "Education", "SEU Library Hall", 53, 10, 0, 100, 0],
    ["Folk Music Mehfil", "Music", "University Auditorium", 56, 18, 0, 400, 150],
    ["Motion Graphics Workshop", "Design", "Banani Creative Studio", 59, 11, 0, 75, 450],
    ["Internship Showcase", "Career", "SEU Central Auditorium", 62, 11, 0, 450, 0],
    ["Charity Food Fair", "Community", "Southeast University Ground", 66, 12, 0, 1000, 0],
    ["DevOps Practice Day", "Technology", "BICC, Dhaka", 70, 9, 30, 150, 500],
    ["Startup Weekend Dhaka", "Business", "Bangladesh Startup Hub", 74, 9, 0, 250, 700]
  ];
  const key = i => String(i + 1).padStart(2, "0");
  const id = i => "more30_event_" + key(i);

  d.events.insertMany(rows.map((r, i) => ({
    _id: id(i), title: r[0], category: r[1], location: r[2], date: dateIn(r[3]), time: timeOf(r[4], r[5]),
    description: r[0] + " brings people together at " + r[2] + " for talks, hands-on sessions and networking. Demo event for Occavie.",
    capacity: r[6], status: "UPCOMING", featured: false, organizerId, organizerName: organizer.fullName,
    imageUrl: "/images/event-visual.png",   // replaced on the site by a category cover
    pricingType: r[7] > 0 ? "PAID" : "FREE", basePrice: money(r[7]), seedTag: tag,
    _class: "com.eventflow.model.Event"
  })));

  const tickets = [];
  rows.forEach((r, i) => {
    tickets.push({ _id: "more30_ticket_" + key(i) + "_std", eventId: id(i), name: r[7] > 0 ? "Standard" : "General Admission",
      description: r[7] > 0 ? "Regular event entry" : "Free admission", price: money(r[7]),
      capacity: r[7] > 0 ? Math.floor(r[6] * 0.8) : r[6], active: true, sortOrder: 1, seedTag: tag, _class: "com.eventflow.model.EventTicketType" });
    if (r[7] > 0) tickets.push({ _id: "more30_ticket_" + key(i) + "_vip", eventId: id(i), name: "VIP",
      description: "Priority seating and enhanced access", price: money(r[7] * 2), capacity: r[6] - Math.floor(r[6] * 0.8),
      active: true, sortOrder: 2, seedTag: tag, _class: "com.eventflow.model.EventTicketType" });
  });
  d.event_ticket_types.insertMany(tickets);

  const sessions = [];
  rows.forEach((r, i) => {
    const start = r[4] * 60 + r[5];
    [["Registration & Welcome", 0, 25], ["Main Session: " + r[0], 30, 100], ["Questions & Networking", 110, 155]].forEach((p, j) => {
      const f = start + p[1], t = start + p[2];
      sessions.push({ _id: "more30_schedule_" + key(i) + "_" + (j + 1), eventId: id(i), title: p[0],
        startTime: timeOf(Math.floor(f / 60) % 24, f % 60), endTime: timeOf(Math.floor(t / 60) % 24, t % 60),
        location: r[2], description: "", speakerId: null, seedTag: tag, _class: "com.eventflow.model.EventScheduleItem" });
    });
  });
  d.event_schedule.insertMany(sessions);
  print("Added " + rows.length + " upcoming events, " + tickets.length + " ticket types, " + sessions.length + " sessions.");
})();
