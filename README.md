# Concierge

[![CI](https://github.com/eduardocerqueira/concierge-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/eduardocerqueira/concierge-plugin/actions/workflows/ci.yml)
[![CVE scan](https://github.com/eduardocerqueira/concierge-plugin/actions/workflows/cve-scan.yml/badge.svg)](https://github.com/eduardocerqueira/concierge-plugin/actions/workflows/cve-scan.yml)
[![Licence](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)

**Onboarding and an AI assistant for Jenkins that know your instance, and show every person only
what they are already allowed to see.**

![The Concierge welcome page for a developer on the payments team. A welcome message from the team,
eight tiles counting the jobs she can see by status, a "Start here" list of two pinned jobs with a
note on each, and three guided tours.](docs/images/welcome.png)

*The welcome page as alice, a developer on the payments team, sees it. Every number, job and tour
on it is computed from what she can read, so the platform team's jobs are simply not there.*

---

## Why

Joining a team means joining its Jenkins: hundreds of jobs, folders named after things you have
not heard of yet, and a red job nobody mentions. The usual answer is a wiki page that went stale
the week it was written.

Concierge answers from the instance itself, and makes three promises.

### Everyone sees their own Jenkins

The welcome page, the tours, the learning paths and the assistant are all computed for the person
looking, from their own permissions. A job they cannot read is not listed, not counted, not
toured and not mentioned by the assistant. One setup serves the whole controller, from the
newcomer with access to one folder to the administrator.

### Newcomers learn on the real thing

A tour walks across real pages of this instance and points at what matters: the job list, a
healthy job, its build history, its last build, its console. Learning paths for people new to
Jenkins, developers and DevOps engineers use this instance's jobs as their examples, and skip the
pages a person cannot open. A team lead can pin the jobs that matter, with a note on each, without
being an administrator.

![A guided tour on a real job page. The build history is outlined and the rest of the page dimmed;
a card reads "Step 3 of 6: Build history. Every build of this job, newest first. Click one to open
it." with Leave tour, Back and Next.](docs/images/tour.png)

*A tour step on a real job. If a later Jenkins changes the page so the highlighted part is gone,
the step is shown in the middle of the screen instead of being skipped.*

### The assistant looks things up, and asks before it acts

Ask "why did the last build fail?" on a job page and the assistant looks: the build, the end of its
log, the job's history. It uses read-only tools that run as you, so it can never see more than you
can. Logs are stripped of secrets before they leave the controller, and an administrator decides
what kinds of data may be shared at all.

![The Concierge chat window floating over a failing job. Asked why the last build failed, the
assistant quotes the end of the console log in a code block, "ERROR: connection refused
(staging.payments.internal:8443)", explains that the deploy step could not reach its server, and
lists what to check. Underneath: "Looked at" with chips for get build and get build
log.](docs/images/assistant.png)

*The chat floats over every page and knows which page you are on. Drag its corner to resize it,
or expand it. Every answer says which tools it used.*

When it is allowed to, the assistant can also propose running a job. It never starts anything
itself: a card shows the job and every parameter value, and nothing runs until you click
**Run it**. Everything is checked again at that moment, and the build is started as you.

![The assistant has proposed running payments/release-train again. A card asks "Run
payments/release-train again (like #3)?" and lists all seven parameters, VERSION, ENVIRONMENT,
REGION, CANARY_PERCENT, RUN_SMOKE_TESTS, RELEASE_NOTES and DEPLOY_TOKEN shown as hidden, with Run
it and Cancel.](docs/images/build-proposal.png)

*The card is built from the stored proposal, never from the model's text. Password parameters can
never be set by the assistant, and re-runs never copy secret values.*

---

## Quick start

Concierge is not on the Jenkins update centre yet. To try it:

```bash
./scripts/demo.sh --anthropic      # or --openai --model M, --ollama MODEL, or nothing for no AI
```

That builds the plugin and starts a throwaway, secured Jenkins on <http://localhost:8080/concierge/>
with three users who each see a different instance. [docs/demo.md](docs/demo.md) has a walk-through
of what to check as each of them.

To install it on your own controller, build it with `mvn clean package` and upload
`target/concierge.hpi` under **Manage Jenkins → Plugins → Advanced settings**, after installing the
**Credentials** and **Plain Credentials** plugins it depends on. Then:

1. Open **Concierge** from the Jenkins header. The welcome page, tours and learning paths work
   straight away, with no AI and nothing sent anywhere.
2. Optionally, pin the jobs newcomers should start with: **Edit welcome and start here**.
3. Optionally, turn on the assistant under **Manage Jenkins → System → Concierge assistant**.

Requires **Jenkins 2.568.3 or newer** on **Java 21 or newer**.

---

## What is in it

| | |
| --- | --- |
| **Welcome page** | Job health, what is building and queued, agents and executors, top-level folders, and plugin and update counts for people who may see them |
| **Start here** | Up to 12 pinned jobs with a note on each, curated by anyone with the Concierge/ManageTours permission |
| **Guided tours** | Start here, the dashboard, and a job with its build history, last build and console |
| **Learning paths** | New to Jenkins, Developer and DevOps, with progress saved per person |
| **Assistant** | A floating, resizable chat on every page and a full-page view, answering from jobs, builds, test results, the queue, agents and plugins |
| **Providers** | Anthropic, OpenAI, xAI Grok, DeepSeek (only once explicitly allowed), or a local model such as Ollama, optionally through an AI gateway |
| **Builds from the chat** | Proposed by the assistant, confirmed by the person, limited to jobs an administrator lists |
| **Cost** | Tokens and estimated cost on every answer, a monthly usage page, and an optional monthly budget |

### Choosing a model

Once a provider and an API key are set, Concierge fetches the provider's own model list and shows
each model with its price and where that price comes from: reported live by xAI, a dated list price
shipped with the plugin, or one you set yourself. A model with no known price says so, rather than
guessing.

![The Concierge assistant settings: Enable the AI assistant, Provider, API key with an Add button,
Base URL, and a Model drop-down showing a model and its price, followed by Custom model
id.](docs/images/settings.png)

*Plain-HTTP base URLs get a warning unless the model runs on the same machine. A custom model id
overrides the list, for gateways and fine-tuned models.*

Every setting is documented in **[docs/usage.md](docs/usage.md)**.

---

## Security

Concierge reads the whole instance and can talk to an AI provider, so it is built to a written set
of rules, **[docs/security-design.md](docs/security-design.md)**, which every change has to follow.
In short:

- **It acts as the person asking.** Every lookup and every assistant tool runs with their
  permissions, never as SYSTEM. Job configuration needs Extended Read; installed plugins need
  System Read.
- **Keys live in the Credentials plugin**, and are only ever sent to the provider. Testing a
  connection, listing models and setting a base URL are administrator-only.
- **Secrets do not leave.** Only allowlisted fields are sent to a model, logs are redacted
  first, and build logs, job configuration and source control are each off until an administrator
  allows them.
- **Untrusted text stays inert.** Logs and commit messages cannot start anything, because a person
  has to confirm every build. Script Console, credentials, replay and workspaces are never offered
  to the model.
- **Everything is audited**: every question and tool call goes to a dedicated logger, without
  answers, tool results or keys.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

---

## How it works

Everything on the welcome page is computed per request from what the current user can read, and
never cached for anyone else. Tours are lists of steps, each naming a page and a CSS selector, run
by a small script that every Jenkins page loads but which stays idle until you start a tour.

The assistant is a loop on the controller: it sends the conversation and a list of tools to the
provider, runs the tools the model asks for as the signed-in user, and repeats until there is an
answer or the tool-call limit is reached. Three pieces are worth knowing about:

| | |
| --- | --- |
| [`AssistantTools`](src/main/java/io/jenkins/plugins/concierge/AssistantTools.java) | The read-only tools. Each checks Jenkins permissions itself, builds its result field by field, and answers the same way for a hidden job as for a missing one, so the model cannot probe for names. |
| [`Redactor`](src/main/java/io/jenkins/plugins/concierge/Redactor.java) | Removes tokens, keys, passwords, private key blocks, credentials in URLs and the API key in use from everything sent to a model, with patterns bounded so a hostile log cannot slow it down. |
| [`BuildProposals`](src/main/java/io/jenkins/plugins/concierge/BuildProposals.java) | Holds the builds the assistant proposed, per user and session, for ten minutes and one use, and re-checks everything when the person confirms. |

The plugin ships no third-party libraries: HTTP uses the JDK client through Jenkins' proxy
settings, and JSON a small strict parser of its own, because the one Jenkins bundles turns strings
that look like JSON into objects.

---

## Documentation

| | |
| --- | --- |
| [Trying it locally](docs/demo.md) | The one-command demo, and what to check as each user |
| [Using Concierge](docs/usage.md) | Permissions, the welcome page, tours, curation, every assistant setting, cost and builds |
| [Security design](docs/security-design.md) | The rules every change follows, and the risk each one addresses |
| [Building](docs/building.md) | Building and running from source |
| [Contributing](docs/CONTRIBUTING.md) | Issues, pull requests and house rules |
| [The bar](docs/quality-bar.md) | All tests passing and zero shipped CVEs, for every pull request |

---

## Contributing

Issues and pull requests are welcome. Read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) first, and
[docs/security-design.md](docs/security-design.md) for anything the assistant touches.

Every pull request must have **all tests passing** and **zero known vulnerabilities in anything the
plugin ships**, both enforced by CI. Anything that shows instance data also needs a test proving
that a user without permission cannot see it.

---

## Licence

[MIT](LICENSE).
