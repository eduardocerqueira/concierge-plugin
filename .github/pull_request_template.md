## What this changes

<!-- One or two sentences. What is different for someone using Concierge? -->

## Why

<!-- Link the issue if there is one: Fixes #123 -->

## The bar (see docs/quality-bar.md)

- [ ] `mvn clean install` passes locally, and every test passes in CI
- [ ] The CVE scan reports **zero** known vulnerabilities in what the plugin ships
- [ ] New behaviour is covered by a test
- [ ] Anything that shows instance data has a test that a user without permission cannot see it
- [ ] Follows docs/security-design.md: runs as the user, nothing secret sent to a model, no action without confirmation
- [ ] No inline JavaScript in Jelly, and no `innerHTML` with job, tour or model text
- [ ] Docs updated if a setting was added, removed or renamed
- [ ] **Labelled**

## How it was checked

<!-- Which Jenkins version, which permissions you tested as, which provider if the assistant is involved. -->
