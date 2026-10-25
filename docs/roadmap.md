# Roadmap

Three phases, lowest risk first. Each phase ships on its own.

## Phase 1: onboarding, no AI (done)

Works with no API key and sends nothing off the instance. See [usage.md](usage.md).

- **Welcome page.** How the instance is organised into folders and teams, the key jobs, current
  health, what is running, agents and labels, and the plugins that matter. Computed live for each
  user from what they can see.
- **Learning paths by role.**
  - Developers: the jobs that build your repository, found by SCM URL; how to read a failure; how to
    re-run.
  - DevOps: agents, queues, shared libraries, which credentials exist (names only), who owns what.
  - New to Jenkins: concepts taught with this instance's real jobs as examples.
- **Guided tours** that highlight parts of real job pages: build history, console output,
  parameters, Pipeline stages.
- **Curated tours.** Admins or team leads pin "start here" jobs, add notes and order the steps.
- **Progress checklist** for each user.

## Phase 2: assistant, read-only (done)

See the assistant section of [usage.md](usage.md).

- Chat about jobs, builds, failures and plugins, aware of the page the user is on.
- Plain-language summaries of what a job does, generated for each viewer: done through the chat
  ("Explain what this job does" on a job page), not as a separate feature.
- AI-drafted tours that a person approves before anyone sees them: not built yet.
- Providers:

  | Provider | API | Notes |
  | --- | --- | --- |
  | Anthropic | Anthropic Messages API | Recommended for tool use. |
  | OpenAI | OpenAI API | Recommended for tool use. |
  | xAI Grok | OpenAI-compatible, `https://api.x.ai/v1` | |
  | DeepSeek | OpenAI-compatible | Off until an admin enables it; data is stored in China. |
  | Local | OpenAI-compatible (Ollama and similar) | Nothing leaves the network. |

- Optional routing through Cloudflare AI Gateway, set by an admin.

## Phase 3: actions with confirmation

- Run or rebuild a job from the chat.
- Off by default, limited to jobs an admin allows, confirmed by the user every time.

## Before phase 1

1. Post the idea on the jenkinsci-dev mailing list, and ask the MCP Server maintainers whether shared
   tools belong upstream.
2. Open the hosting request with the comparison in the [README](../README.md).
3. Build a demo instance, based on the Live Wall demo script.

## Name

Concierge fits the Jenkins butler mascot: a concierge shows newcomers around and also gets things
done on request, which matches both halves of the plugin. Other names considered: Wayfinder,
Instance Guide, Steward and Companion. None of them, Concierge included, was in use by a Jenkins
plugin on 25 October 2026.
