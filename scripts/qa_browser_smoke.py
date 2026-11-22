#!/usr/bin/env python3
"""Non-destructive Occavie browser smoke audit.

Run against an isolated local application (recommended), or set QA_PUBLIC_ONLY=1
for read-only checks against a publicly hosted instance.

Requires: pip install playwright && python -m playwright install chromium
"""
import json
import os
import sys
import traceback
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

BASE = os.environ.get("QA_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
PUBLIC_ONLY = os.environ.get("QA_PUBLIC_ONLY", "0") == "1"
OUT = Path(os.environ.get("QA_OUTPUT_DIR", "qa-output"))
OUT.mkdir(parents=True, exist_ok=True)
REPORT = {"target": BASE, "passed": [], "failed": [], "warnings": []}
PUBLIC = ["/", "/about", "/events", "/contact", "/login", "/register"]
ROLE_PAGES = {
    "PARTICIPANT": ["/participant/dashboard", "/participant/registrations", "/events", "/profile", "/notifications"],
    "ORGANIZER": ["/organizer/dashboard", "/organizer/events", "/organizer/events/new",
                  "/organizer/bookings", "/organizer/coupons", "/organizer/past-events",
                  "/organizer/feedback", "/profile", "/notifications"],
    "ADMIN": ["/admin/dashboard", "/admin/users", "/admin/events",
              "/admin/approvals", "/admin/registrations", "/admin/coupons",
              "/admin/past-events", "/admin/feedback", "/admin/contact-requests",
              "/profile", "/notifications"],
}
DEMO = {
    "PARTICIPANT": ("participant@eventflow.com", "Participant123!"),
    "ORGANIZER": ("organizer@eventflow.com", "Organizer123!"),
    "ADMIN": ("admin@eventflow.com", "Admin123!"),
}


def ok(label):
    REPORT["passed"].append(label)
    print("PASS", label, flush=True)


def fail(label, exc, page=None):
    REPORT["failed"].append({"check": label, "error": str(exc)})
    print("FAIL", label, str(exc), flush=True)
    if page:
        try:
            name = "".join(x if x.isalnum() else "-" for x in label)[:90]
            page.screenshot(path=str(OUT / (name + ".png")), full_page=True, timeout=10000)
        except Exception:
            pass


def warn(label, message):
    REPORT["warnings"].append({"check": label, "warning": message})
    print("WARN", label, message, flush=True)


def attempt(label, operation, page=None):
    try:
        operation()
        ok(label)
    except Exception as exc:
        fail(label, exc, page)


def navigate(page, path):
    response = page.goto(BASE + path, wait_until="domcontentloaded", timeout=120000)
    if response is None or response.status >= 400:
        raise AssertionError("HTTP status: " + str(response.status if response else "no response"))
    if not page.locator("body").count():
        raise AssertionError("Body element missing")
    return response


def inspect_ui(page, label):
    stats = page.evaluate("""() => {
      const width = document.documentElement.clientWidth;
      const overflow = document.documentElement.scrollWidth - width;
      const unnamed = [...document.querySelectorAll('button')].filter(b => {
        if (b.hidden || b.getAttribute('aria-hidden') === 'true' || b.disabled ||
            getComputedStyle(b).display === 'none') return false;
        const title = b.getAttribute('aria-label') || b.getAttribute('title') ||
          b.innerText || b.getAttribute('aria-labelledby') || '';
        return !title.trim();
      }).length;
      const noAlt = document.querySelectorAll('img:not([alt])').length;
      const ids = [...document.querySelectorAll('[id]')].map(n => n.id);
      return {overflow, unnamed, noAlt, duplicatedIds: ids.length - new Set(ids).size,
              iconCount: document.querySelectorAll('i.bi').length};
    }""")
    if stats["overflow"] > 5:
        warn(label, "Horizontal overflow: " + str(stats["overflow"]) + "px")
    for field in ["unnamed", "noAlt", "duplicatedIds"]:
        if stats[field]:
            warn(label, field + " count: " + str(stats[field]))
    if stats["iconCount"] == 0 and label.endswith("/"):
        warn(label, "No Bootstrap icons found")


def guest_checks(browser):
    for label, width, height in [("desktop", 1440, 900), ("tablet", 768, 1024),
                                 ("mobile", 390, 844)]:
        context = browser.new_context(viewport={"width": width, "height": height})
        page = context.new_page()
        page.on("pageerror", lambda ex, tag=label: warn(tag, "JavaScript exception: " + str(ex)))
        for route in PUBLIC:
            test = label + " GET " + route
            attempt(test, lambda route=route: (navigate(page, route), inspect_ui(page, test)), page)
        def theme():
            navigate(page, "/about")
            b = page.locator("[data-theme-toggle]").first
            if not b.count():
                raise AssertionError("Theme toggle missing")
            before = page.locator("html").get_attribute("data-theme")
            b.click()
            after = page.locator("html").get_attribute("data-theme")
            if before == after:
                raise AssertionError("Theme toggle did not switch the theme")
        attempt(label + " theme toggle", theme, page)
        def auth_tabs():
            navigate(page, "/login")
            page.locator("#registerTab").click()
            if not page.locator("#registerView").is_visible():
                raise AssertionError("Register tab not visible")
            page.locator("#loginTab").click()
            if not page.locator("#loginView").is_visible():
                raise AssertionError("Login tab not visible")
        attempt(label + " login/register tabs", auth_tabs, page)
        def show_password():
            navigate(page, "/login")
            password = page.locator("#loginPassword")
            page.locator('[data-target="loginPassword"]').click()
            if password.get_attribute("type") != "text":
                raise AssertionError("Show password did not work")
        attempt(label + " password visibility", show_password, page)
        def search():
            navigate(page, "/events")
            field = page.locator("#eventSearch")
            field.fill("technology")
            field.dispatch_event("input")
            if field.input_value() != "technology":
                raise AssertionError("Search input did not accept text")
            page.locator("#v1ClearFilters").click()
            if field.input_value() != "":
                raise AssertionError("Reset filters did not clear search")
        attempt(label + " event search/reset", search, page)
        context.close()


def role_checks(browser):
    if PUBLIC_ONLY:
        warn("roles", "Skipped: QA_PUBLIC_ONLY enabled; no production login attempted")
        return
    if urlsplit(BASE).hostname not in ("localhost", "127.0.0.1"):
        raise RuntimeError("Demo login tests are allowed only on localhost / isolated QA database")
    for role, (email, password) in DEMO.items():
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()
        def login():
            navigate(page, "/login")
            page.locator("#loginEmail").fill(email)
            page.locator("#loginPassword").fill(password)
            page.locator('#loginForm button[type="submit"]').click()
            page.wait_for_url("**/dashboard", timeout=30000)
            if "/login" in page.url:
                raise AssertionError("Still on login page")
        attempt(role + " login", login, page)
        if "/login" not in page.url and "/dashboard" in page.url:
            for route in ROLE_PAGES[role]:
                attempt(role + " GET " + route, lambda route=route: navigate(page, route), page)
            def about_authenticated():
                navigate(page, "/about")
                if page.get_by_text("Create a free account", exact=True).is_visible():
                    raise AssertionError("Guest signup CTA still visible when signed in")
                if not page.get_by_text("Open dashboard", exact=True).count():
                    raise AssertionError("Logged-in dashboard CTA missing")
            attempt(role + " About CTA after login", about_authenticated, page)
            def profile_link():
                navigate(page, "/dashboard")
                link = page.locator('a[href="/profile"]').first
                if not link.count():
                    raise AssertionError("Profile link not found")
                link.click()
                if "/profile" not in page.url:
                    raise AssertionError("Profile link did not navigate")
            attempt(role + " profile link click", profile_link, page)
        context.close()


def main():
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, args=["--no-sandbox"])
        try:
            guest_checks(browser)
            role_checks(browser)
        finally:
            browser.close()
    (OUT / "report.json").write_text(json.dumps(REPORT, indent=2), encoding="utf-8")
    print("\nRESULT", len(REPORT["passed"]), "passed,",
          len(REPORT["failed"]), "failed,", len(REPORT["warnings"]), "warnings")
    if REPORT["failed"]:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(2)
