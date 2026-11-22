# Occavie: portfolio description

**Occavie** is a full-stack event management platform (Java 26, Spring Boot, Thymeleaf, MongoDB) with separate Admin, Organizer and Participant workspaces. Participants discover events and get PDF tickets with signed QR codes; organizers build events with a drafts-and-preview wizard, run QR check-in at the door, manage waitlists and coupons, export CSVs and read analytics; admins moderate through an optional approval workflow.

What I built and learned: a capacity-safe booking and waitlist engine, a transactional email outbox, signed single-use tickets with atomic check-in, role/ownership security with CSRF, rate limiting and a strict CSP, production-safety checks that refuse to boot on weak secrets, WCAG AA contrast and keyboard accessibility verified with axe across four viewport sizes in light and dark mode, and a complete visual identity. Payments are deliberately simulated and labelled as demo values.
