# Security

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

**Report it to the Jenkins security team**, by following
[How to report a security issue](https://www.jenkins.io/security/reporting/). For Jenkins plugins,
only the Jenkins security team can assign a CVE and coordinate the advisory and the fix.

Please include the plugin version, your Jenkins version, and enough detail to reproduce it.

## Supported versions

Nothing has been released yet.

## What counts as a vulnerability here

The rules are in [docs/security-design.md](docs/security-design.md). In particular, please report:

- Concierge showing a user a job, build, configuration or summary they cannot see in Jenkins itself.
- An API key or other secret appearing in the UI, a log, an audit entry or a request to an AI vendor.
- Text in a log, commit message or job description causing an action without the user's
  confirmation.
- Any markup from a job, a tour or a model rendering as HTML.
