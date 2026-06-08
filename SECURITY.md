# Security Notes

## Dashboard auth
The Memoly Centre Admin dashboard JWT lives in localStorage (XSS-exposed).
The same 30-day token serves both the app and the dashboard.
Acceptable for a pilot. Future fix: short-lived dashboard session via httpOnly
cookie through a BFF (Next.js route handler). Do not implement until post-pilot.
