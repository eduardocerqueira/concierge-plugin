# Using Concierge

Concierge adds a **Concierge** button to the Jenkins header. It opens `/concierge`, the welcome
page. Everything on it is computed for the person looking at it, from what they are allowed to read.

## Permissions

| Permission | Implied by | Allows |
| --- | --- | --- |
| Concierge/Use | Overall/Read | The welcome page, learning paths and tours. |
| Concierge/ManageTours | Overall/Administer | Editing the welcome message and the "start here" jobs. |
| Concierge/Assistant | Overall/Administer | The AI assistant (phase 2). |

With matrix-based security you can grant Concierge/ManageTours to a team lead who is not an
administrator.

## The welcome page

- **At a glance**: how many jobs you can see, how many are failing, unstable or passing, what is
  building and queued, agents and executors. People with Overall/SystemRead also see how many
  plugins are active and how many have updates.
- **Start here**: the jobs curated for newcomers, with a note on each. Pinned jobs you cannot read
  are left out.
- **Guided tours**: see below.
- **Learning paths**: short lists of steps for people new to Jenkins, developers and DevOps
  engineers. Each step links to a real page on this instance. Steps for pages you cannot open, such
  as the plugin manager for a non-administrator, are left out. Signed-in users can tick steps off;
  progress is stored per user.
- **Failing now**, **Recently built** and **How it is organised** (top-level folders).

## Tours

A tour walks across real pages, highlights the part that matters and explains it in a card. Use
**Next** and **Back**, or **Leave tour** (or Escape) to stop.

| Tour | Pages |
| --- | --- |
| Start here | Each curated job, with its note. Only shown when jobs are pinned. |
| The dashboard | Search, the side panel, the job list, the build queue and executors. |
| A job, a build and its log | A healthy job from this instance, its build history, its last build and its console. |

If a later Jenkins version or another plugin changes a page so that the highlighted part cannot be
found, the step is shown in the middle of the screen instead of being skipped.

Tours keep their place in the browser tab's session storage. Opening an unrelated page ends the
tour.

## Curating the welcome page

People with Concierge/ManageTours see **Edit welcome and start here** on the welcome page.

- **Welcome message**: plain text, up to 2000 characters. Line breaks are kept. Markup is shown as
  text, never rendered.
- **Start here**: up to 12 jobs, by full name (for example `payments/api-build`), each with a note of
  up to 500 characters. The list becomes the "Start here" tour.

One list serves everyone: each person only sees the pinned jobs they can read.

## The AI assistant

Off until an administrator turns it on under **Manage Jenkins → System → Concierge assistant**.
People with the Concierge/Assistant permission then get an **Ask Concierge** button on every page.
It opens a side panel; **Full page** opens the same conversation at `/concierge/assistant`.

The assistant answers by calling read-only tools that run as the person asking: job lists and
details, builds, test counts, the build queue, agents, and, for Overall/SystemRead, plugins. It
knows which page you are on, so "why did this fail?" works on a job or build page.

### Settings

| Setting | Notes |
| --- | --- |
| Provider | Anthropic, OpenAI, xAI Grok, DeepSeek, or a local OpenAI-compatible model such as Ollama. |
| Model | Picked from the provider's own model list, fetched with the API key once provider, key and base URL are set. Each model shows its price per 1M tokens and where the price comes from: reported live (xAI), a dated list price shipped with Concierge (Anthropic), set by an administrator, or "price unknown". Anthropic defaults to `claude-sonnet-5-5`. Use a model with reliable tool calling; small local models often answer without looking anything up. |
| Custom model id | Typed by hand; overrides the list. For gateways, fine-tuned models, or models the list does not show. |
| Model prices | Your own prices, `model-id = input / output` in US dollars per 1M tokens, one per line. They override every other source and are only used for display and cost estimates. |
| API key | A Secret text credential. Not needed for most local models. |
| Base URL | Empty for the provider's own endpoint. Set it for a local model (`http://localhost:11434/v1`) or an AI gateway such as Cloudflare AI Gateway. |
| Allow DeepSeek | Required to use DeepSeek's hosted API, which stores data in China. |
| What the assistant may see | Build logs (redacted), job configuration (needs Item/ExtendedRead), source control. All off by default. |
| Limits | Log lines per request, questions per user per hour, tool calls per question. |
| Users bring their own key | Lets people enter their own key for the configured provider on their user settings page. |

**Test connection** sends one short request with the settings on the form. It is only available to
administrators.

### Audit

Every question, tool call (name and arguments) and outcome is logged to the
`io.jenkins.plugins.concierge.audit` logger. Tool results and API keys never are. Add a log recorder
for that logger under **Manage Jenkins → System Log** to keep a trail.

### Conversation

The conversation is kept in your browser session on the controller, at most the last 12 turns, and
only the questions and answers: never the data the tools looked up. **New conversation** clears it.

## Starting builds from the assistant

Off by default. Under **Manage Jenkins → System → Concierge assistant**, tick **Let the assistant
propose builds** and list the jobs it may propose in **Jobs it may propose**, one pattern per line:
`team/api-build`, `team/*` (one folder level) or `team/**` (any depth). Empty means none.

When someone asks for a build ("run this again", "build api with BRANCH=release"), the assistant
proposes it and a card appears in the chat with the job and every parameter value. **Nothing runs
until the person clicks Run it.** Clicking **Cancel** drops the proposal.

On **Run it**, everything is checked again: the person still has the Build permission on the job,
the job is still listed, it can be built, the parameter values are valid, and the proposal is
theirs, from this session, less than ten minutes old and not used before. The build is started as
that person ("Started by user ...").

- Password parameters can never be set by the assistant; the job's default is used.
- **Run it again** copies the earlier build's ordinary parameter values, never sensitive ones.
- The audit logger records each proposal, confirmation, refusal and cancellation.
