# Contributing

Thanks for looking. Concierge helps people find their way around a Jenkins instance and answers
questions about it, so the two things to keep in mind are: everyone sees only what they may, and
nothing changes without a person confirming it. [security-design.md](security-design.md) spells
both out.

## The bar

> **Every pull request must have all tests passing, and zero known vulnerabilities in anything
> this plugin ships.**

Both are enforced by CI. See [quality-bar.md](quality-bar.md).

## Submitting a pull request

1. **Open an issue first** for anything beyond a small fix, so we agree on the approach.
2. Fork, and branch from `main`.
3. Make the change, with a test. Anything that shows instance data needs a test that a user without
   permission cannot see it.
4. Run `mvn clean install`, and `mvn spotless:apply` if formatting failed.
5. Open the pull request and fill in the checklist.

## House rules

- **Follow [security-design.md](security-design.md).** A change that needs to break a rule needs an
  issue first.
- **No inline JavaScript in Jelly**, and never `innerHTML` with anything that came from a user, a job
  or a model.
- **Do not add dependencies without a reason.** Every shipped dependency is a future CVE.
- **Never run as SYSTEM** to make a lookup work.

## Licence

By contributing you agree that your contribution is licensed under the [MIT License](../LICENSE),
the same as the rest of the project.
