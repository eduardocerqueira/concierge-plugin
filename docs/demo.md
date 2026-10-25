# Trying Concierge locally

[`scripts/demo.sh`](../scripts/demo.sh) takes you from a clean checkout to a secured Jenkins with
Concierge, three users and a handful of jobs, in about three minutes.

```bash
./scripts/demo.sh                          # onboarding only, no AI
./scripts/demo.sh --anthropic              # with the assistant, using $ANTHROPIC_API_KEY
./scripts/demo.sh --ollama qwen2.5:7b      # with a local Ollama model
./scripts/demo.sh --stop                   # remove the container and image
```

You need Docker (or Podman), JDK 21 and Maven 3.9, and bash 4 or newer (`brew install bash` on
macOS). If your `~/.m2/settings.xml` mirrors `external:*`, the script uses `.mvn/settings.xml`
automatically; see [building.md](building.md).

## What it sets up

| User (password = name) | Sees | Can |
| --- | --- | --- |
| `admin` | Everything | Everything, including Concierge settings and curation |
| `alice` | Everything except `platform` | Build `payments` jobs, use the assistant |
| `bob` | Everything except `platform` | Read only, no assistant |

| Where | Jobs |
| --- | --- |
| `payments` | `api-build` (passing, a `BRANCH` parameter, two builds), `web-deploy` (fails with "connection refused", and prints a fake token in its log), `nightly-tests` (unstable), `release-train` (seven parameters, three builds, the last one failed), `checkout-service` (passing), `ledger-reconcile` (building for half an hour), `fraud-model-train` (never built), `legacy-batch` (disabled) |
| `platform` | `terraform-apply` (failing), `secrets-rotation` (passing), `k8s-cluster-upgrade` (unstable). Visible to admin only. |
| Top level | `docs-site` (passing), `dependency-audit` (unstable) |

`payments/release-train` is the job for trying builds with parameters. It has one of each common
kind:

| Parameter | Kind | Default |
| --- | --- | --- |
| `VERSION` | Text | `2.15.0` |
| `ENVIRONMENT` | Choice: `staging`, `production`, `sandbox` | `staging` |
| `REGION` | Choice: `eu-west-1`, `us-east-1`, `ap-south-1` | `eu-west-1` |
| `CANARY_PERCENT` | Choice: `5`, `10`, `25`, `50`, `100` | `5` |
| `RUN_SMOKE_TESTS` | True or false | true |
| `RELEASE_NOTES` | Multi-line text | `Routine release.` |
| `DEPLOY_TOKEN` | Password | a demo token |

It fails a production release that skips the smoke tests, which is what happened to build #3.

Curation is filled in: a welcome message and four pinned jobs, one of them in `platform`.

With an AI option, the assistant is on, shares build logs and job configuration, and may propose
builds of `payments/**`.

## Options

| Option | Effect |
| --- | --- |
| `--anthropic` | Assistant with Anthropic. Reads `ANTHROPIC_API_KEY`. Model defaults to `claude-sonnet-5-5`. |
| `--openai --model M` | Assistant with OpenAI. Reads `OPENAI_API_KEY`. |
| `--xai --model M` | Assistant with xAI Grok. Reads `XAI_API_KEY`. |
| `--ollama M` | Assistant with a local Ollama model. Start `ollama serve` first. |
| `--port P` | Use another port. Default 8080. |
| `--no-build` | Reuse `target/concierge.hpi`. |
| `--stop` | Remove the container and image. |

The key is passed to the container as an environment variable, which is fine for a throwaway
container on your laptop. On a real controller it belongs in the Credentials plugin only.

Small local models often answer without looking anything up. Anthropic or OpenAI give a much better
picture of what the assistant can do.

## A test walk-through

Open <http://localhost:8080/concierge/> and work through these. Each one checks something specific.

### 1. Everyone sees a different Jenkins

1. Sign in as **admin**. The welcome page counts 13 jobs, lists both folders and shows four pinned
   jobs.
2. Sign out, sign in as **bob**. Now it is 10 jobs, one folder, three pinned jobs. Nothing from
   `platform` appears anywhere: not in the counts, the lists, the pinned jobs or the tours.
3. As bob, the **DevOps** learning path has no plugin, log or security steps, because bob cannot
   open those pages.

### 2. Tours

1. As bob, start **A job, a build and its log**. It walks a real payments job, its build history,
   its last build and its console, then brings you back.
2. Start **Start here**. It visits only the pinned jobs bob can read.
3. Press Escape in the middle of a tour to leave it.

### 3. Curation

1. As **admin**, click **Edit welcome and start here**.
2. Put `<b>hello</b>` in the welcome message and save. It shows as text, not bold.
3. As bob, open `/concierge/manage` directly: it is refused.

### 4. The assistant (with an AI option)

1. As **bob**, there is no Concierge bell in the corner, and `/concierge/assistant/` is refused.
2. As **alice**, open `payments/web-deploy` and ask "Why did the last build fail?". The answer
   should mention the refused connection. The log contained a fake token (`tok_live_...`); ask "What
   token did the deploy use?" and the assistant should only see `[REDACTED]`.
3. Ask "What is in the platform folder?". It should find nothing: the tools only return what alice
   can see.
4. As **admin**, open **Manage Jenkins → System Log**, add a log recorder for
   `io.jenkins.plugins.concierge.audit` at level `FINE`, ask something, and read the trail: questions
   and tool calls, no keys and no tool results.

### 5. Builds with confirmation (with an AI option)

1. As **alice**, on `payments/api-build`, ask "Run this again with BRANCH=release/1". A card
   appears. Nothing is queued yet; check the job page.
2. Click **Run it**. The build starts, as "Started by user alice", with `BRANCH=release/1`.
3. On `payments/release-train`, ask "Why did the last release fail?". The log says production
   needs the smoke tests.
4. Ask "Release 2.16.0 to production in us-east-1 with a 10% canary, smoke tests on, release notes:
   faster refunds". The card lists all seven parameters: the six you asked for or their defaults,
   and `DEPLOY_TOKEN` as hidden. Click **Run it** and open the build's **Parameters** page to check
   them.
5. Ask "Release to the moon environment". It is refused, because `moon` is not one of the choices.
   Ask it to set `DEPLOY_TOKEN`: refused too, because the assistant can never set a password.
6. Ask to run `platform/terraform-apply`. It is refused: alice cannot see it, and it is not in the
   allowed jobs.
7. As **admin**, untick **Let the assistant propose builds**, save, and click **Run it** on an old
   card as alice. It is refused.

### 6. Settings

As **admin**, open **Manage Jenkins → System → Concierge assistant**. Try **Test connection**,
switch the provider to DeepSeek without ticking **Allow DeepSeek** and save (refused), and look at
the help for each field.

## Tearing down

```bash
./scripts/demo.sh --stop
```
