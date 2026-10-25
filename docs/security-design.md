# Security design

Jenkins has no up-front security approval for plugins. A plugin passes a hosting review, is scanned
by the Jenkins Security Scan on every change, and gets a public advisory when someone reports a
problem. So "secure" here means designing out the patterns behind most Jenkins plugin advisories:
missing permission checks, secrets stored or shown in plain text, SSRF and credential capture
through test-connection buttons, and cross-site scripting.

A plugin that reads the whole instance and talks to AI vendors adds two more: data leaving the
instance, and untrusted text steering the model. Each rule below says which risk it addresses.

These rules apply to every pull request. A change that needs to break one needs a design discussion
first, in an issue.

## 1. Act as the user

**Risk: permission bypass.** The MCP Server plugin's
[CVE-2026-57300](https://nvd.nist.gov/vuln/detail/CVE-2026-57300) is the example: its replay-script
tool returned pipeline scripts to users who could only read the job.

- Every lookup and action runs with the requesting user's `Authentication`. Never `ACL.as2(ACL.SYSTEM2)`.
- Lists are filtered by `Item.READ`.
- Job configuration and pipeline scripts need `Item.EXTENDED_READ`.
- System-wide information needs `Overall/SystemRead` or `Administer`.
- A Concierge permission group controls who may use the assistant and who may edit tours.
- Nothing computed for one user is cached and shown to another, including the welcome page and AI
  summaries. Where caching is needed, the cache key includes the user.

## 2. Check permissions on the server, every time

**Risk: endpoints reachable without the UI.**

- Every endpoint that changes state is `@POST` and calls `checkPermission`. Stapler enforces the
  CSRF crumb on POST.
- Form validation (`doCheck…`) and auto-completion (`doAutoComplete…`) check permissions too.
- Hiding a button in Jelly is never the check.

## 3. Keys belong in Credentials

**Risk: secrets in plain text.** Plugins storing tokens unencrypted in `config.xml` are one of the
most common advisories.

- API keys are `StringCredentials` from the Credentials plugin, referenced by ID.
- Keys never appear in logs, the UI, exceptions or audit entries.
- The "test connection" endpoint and any custom base URL are admin-only and POST-only. Otherwise a
  user could point a stored key at a server they control.

## 4. Secrets never leave the instance

**Risk: data sent to an AI vendor.**

- Data sent to a model comes from an allowlist of fields. Raw `config.xml`, environment variables,
  credential IDs, parameters marked as secret and workspace files are never sent.
- Build logs are redacted before they are sent, including values the credentials masking missed.
- Admins choose the allowed vendors, which kinds of data may be sent (logs, configuration, SCM
  information) and size limits.
- DeepSeek's hosted API stores data in China, so it stays off until an admin enables it. Its open
  models can run locally instead.
- Traffic can optionally go through Cloudflare AI Gateway for logging, rate limits and DLP. That adds
  to Concierge's redaction; it does not replace it.
- The welcome page, tours and learning paths send nothing anywhere.

## 5. Untrusted text stays inert

**Risk: prompt injection.** Build logs, job descriptions, commit messages and test output are written
by other people, and the model reads them.

- Model output can never trigger an action directly. Any action shows a confirmation card built from
  the structured request, not from the model's text, and the user confirms each time.
- Actions are off by default and limited to jobs an admin allows.
- Some local models write a tool call as JSON text instead of using the tool-calling field. Such a
  reply is treated as a call only when it is nothing but that JSON and names a tool that was
  offered. Only the model's own reply is read this way, never tool results or other data.
- Some tools are never offered to the model: Script Console or Groovy, replaying builds or editing
  pipelines, reading credentials, reading agent workspaces, installing plugins, changing
  configuration.

## 6. Render safely

**Risk: cross-site scripting.**

- Model output and curated tour text are shown as text or sanitised Markdown. Never `innerHTML`.
- No inline JavaScript in Jelly. State goes through `data-` attributes, so pages work under the
  Jenkins Content-Security-Policy.
- Any tour library is bundled with the plugin, never loaded from a CDN.

## 7. Audit everything the assistant does

- Every prompt, tool call, action, user and provider is written to a log recorder, with optional
  Audit Log plugin integration.
- Chat history has a retention limit, because it contains job data.

## 8. Few dependencies

**Risk: known vulnerabilities in what the plugin ships.**

- Vendor APIs are called directly over HTTP rather than through a large AI framework. Anthropic has
  its own API; OpenAI, xAI Grok, DeepSeek and local runtimes such as Ollama share the OpenAI format,
  so two small clients cover all of them.
- The CVE gate in [quality-bar.md](quality-bar.md) blocks any shipped dependency with a known
  vulnerability.
