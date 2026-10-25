#!/usr/bin/env bash
#
# Concierge — one command from a clean checkout to a secured Jenkins you can explore as three
# different people.
#
#   ./scripts/demo.sh                              # onboarding only, no AI
#   ./scripts/demo.sh --anthropic                  # assistant with Claude ($ANTHROPIC_API_KEY)
#   ./scripts/demo.sh --openai --model gpt-5       # assistant with OpenAI ($OPENAI_API_KEY)
#   ./scripts/demo.sh --ollama qwen2.5:7b          # assistant with a local Ollama model
#   ./scripts/demo.sh --stop                       # tear it all down again
#
# It builds this plugin from source, bakes it into a throwaway image with the plugins it needs,
# starts it, and seeds users, folders and thirteen jobs: admin sees everything, alice is a payments
# developer who may build and use the assistant, and bob may only read. A platform folder is visible
# to admin only, and payments/release-train has seven kinds of parameter. See docs/demo.md.

set -euo pipefail

if [ "${BASH_VERSINFO[0]:-0}" -lt 4 ]; then
    echo "error: this script needs bash 4 or newer." >&2
    echo "       macOS still ships bash 3.2; 'brew install bash' then re-run, or run it as" >&2
    echo "       'bash ./scripts/demo.sh' with a newer bash on your PATH." >&2
    exit 1
fi

# ------------------------------------------------------------------ defaults

PORT=8080
IMAGE="jenkins/jenkins:lts-jdk21"
CONTAINER="concierge-demo"
TAG="concierge-demo:latest"
BUILD_PLUGIN=1
STOP_ONLY=0
PROVIDER=""
MODEL=""
BASE_URL=""
API_KEY=""
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ------------------------------------------------------------------ pretty output

if [ -t 1 ]; then
    BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
else
    BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; RESET=""
fi

step() { printf '\n%s==>%s %s%s%s\n' "$GREEN" "$RESET" "$BOLD" "$*" "$RESET"; }
info() { printf '    %s\n' "$*"; }
note() { printf '    %s%s%s\n' "$DIM" "$*" "$RESET"; }
warn() { printf '    %s! %s%s\n' "$YELLOW" "$*" "$RESET"; }
die()  { printf '\n%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }

usage() {
    awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    cat <<EOF

Options:
  --anthropic        Use Anthropic for the assistant. Reads \$ANTHROPIC_API_KEY.
  --openai           Use OpenAI. Reads \$OPENAI_API_KEY. Needs --model.
  --xai              Use xAI Grok. Reads \$XAI_API_KEY. Needs --model.
  --ollama MODEL     Use a local Ollama model, reached from the container at host.docker.internal.
  --model MODEL      Model id. Anthropic defaults to claude-sonnet-5-5.
  --port PORT        Host port to publish Jenkins on. Default ${PORT}.
  --image IMAGE      Base Jenkins image. Default ${IMAGE}.
  --no-build         Reuse target/concierge.hpi instead of running Maven.
  --stop             Stop and remove the demo container and image, then exit.
  -h, --help         This.
EOF
}

# ------------------------------------------------------------------ arguments

while [ $# -gt 0 ]; do
    case "$1" in
        --anthropic) PROVIDER=ANTHROPIC; API_KEY="${ANTHROPIC_API_KEY:-}"; shift ;;
        --openai)    PROVIDER=OPENAI; API_KEY="${OPENAI_API_KEY:-}"; shift ;;
        --xai)       PROVIDER=XAI; API_KEY="${XAI_API_KEY:-}"; shift ;;
        --ollama)    PROVIDER=LOCAL; MODEL="${2:-}"; BASE_URL="http://host.docker.internal:11434/v1"; shift 2 ;;
        --model)     MODEL="${2:-}"; shift 2 ;;
        --port)      PORT="${2:-}"; shift 2 ;;
        --image)     IMAGE="${2:-}"; shift 2 ;;
        --no-build)  BUILD_PLUGIN=0; shift ;;
        --stop)      STOP_ONLY=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown option: $1 (try --help)" ;;
    esac
done

case "$PROVIDER" in
    ANTHROPIC) [ -n "$API_KEY" ] || die "--anthropic needs ANTHROPIC_API_KEY in the environment" ;;
    OPENAI)    [ -n "$API_KEY" ] || die "--openai needs OPENAI_API_KEY in the environment"
               [ -n "$MODEL" ] || die "--openai needs --model" ;;
    XAI)       [ -n "$API_KEY" ] || die "--xai needs XAI_API_KEY in the environment"
               [ -n "$MODEL" ] || die "--xai needs --model" ;;
    LOCAL)     [ -n "$MODEL" ] || die "--ollama needs a model name, for example --ollama qwen2.5:7b" ;;
esac

# ------------------------------------------------------------------ container runtime

if command -v docker >/dev/null 2>&1; then
    RUNTIME=docker
elif command -v podman >/dev/null 2>&1; then
    RUNTIME=podman
else
    die "neither docker nor podman is on your PATH"
fi
$RUNTIME info >/dev/null 2>&1 || die "$RUNTIME is installed but not running"

BASE="http://localhost:${PORT}"

teardown() {
    step "Tearing down"
    $RUNTIME rm -f "$CONTAINER" >/dev/null 2>&1 && info "removed container $CONTAINER" || note "no container named $CONTAINER"
    $RUNTIME rmi -f "$TAG" >/dev/null 2>&1 && info "removed image $TAG" || note "no image $TAG"
    info "done"
}

if [ "$STOP_ONLY" = "1" ]; then
    teardown
    exit 0
fi

# ------------------------------------------------------------------ build the plugin

if [ "$BUILD_PLUGIN" = "1" ]; then
    step "Building the plugin"
    command -v mvn >/dev/null 2>&1 || die "maven is not on your PATH (or pass --no-build)"
    MVN_ARGS=(-B -ntp -DskipTests clean package)
    if grep -q 'external:\*' "${HOME}/.m2/settings.xml" 2>/dev/null; then
        note "your ~/.m2/settings.xml mirrors external:*, using .mvn/settings.xml"
        MVN_ARGS=(-s "${REPO_ROOT}/.mvn/settings.xml" "${MVN_ARGS[@]}")
    fi
    (cd "$REPO_ROOT" && mvn "${MVN_ARGS[@]}")
fi

HPI="${REPO_ROOT}/target/concierge.hpi"
[ -f "$HPI" ] || die "no plugin at target/concierge.hpi — run without --no-build"
info "plugin: $HPI ($(du -h "$HPI" | cut -f1))"

# ------------------------------------------------------------------ build the image

step "Pulling $IMAGE"
$RUNTIME pull "$IMAGE"

step "Baking Concierge and its dependencies into a throwaway image"
CONTEXT="$(mktemp -d)"
trap 'rm -rf "$CONTEXT"' EXIT
cp "$HPI" "$CONTEXT/concierge.hpi"
cp "${REPO_ROOT}/scripts/demo-plugins.txt" "$CONTEXT/plugins.txt"
cp -R "${REPO_ROOT}/scripts/demo-init" "$CONTEXT/init.groovy.d"
cp "${REPO_ROOT}/scripts/demo.Dockerfile" "$CONTEXT/Dockerfile"
$RUNTIME build --build-arg "JENKINS_IMAGE=${IMAGE}" -t "$TAG" "$CONTEXT" >/dev/null
info "built $TAG"

# ------------------------------------------------------------------ start jenkins

step "Starting Jenkins on $BASE"
$RUNTIME rm -f "$CONTAINER" >/dev/null 2>&1 || true
RUN_ARGS=(-d --name "$CONTAINER" -p "127.0.0.1:${PORT}:8080" --add-host "host.docker.internal:host-gateway")
if [ -n "$PROVIDER" ]; then
    RUN_ARGS+=(-e "CONCIERGE_PROVIDER=${PROVIDER}" -e "CONCIERGE_MODEL=${MODEL}" -e "CONCIERGE_BASE_URL=${BASE_URL}")
    # The key goes in through the environment of a container bound to localhost. Fine for a demo on
    # your laptop; on a real controller it lives in the Credentials plugin and nowhere else.
    [ -z "$API_KEY" ] || RUN_ARGS+=(-e "CONCIERGE_API_KEY=${API_KEY}")
fi
$RUNTIME run "${RUN_ARGS[@]}" "$TAG" >/dev/null

printf '    waiting for Jenkins'
for _ in $(seq 1 180); do
    if curl -sf -o /dev/null "${BASE}/login"; then
        printf ' up\n'
        break
    fi
    printf '.'
    sleep 2
done
curl -sf -o /dev/null "${BASE}/login" || {
    printf '\n'
    $RUNTIME logs --tail 40 "$CONTAINER" >&2
    die "Jenkins did not come up on $BASE"
}

if $RUNTIME logs "$CONTAINER" 2>&1 | grep -qE "GroovyInitScript.init failed|Failed to run script"; then
    $RUNTIME logs "$CONTAINER" 2>&1 | grep -A 15 -E "GroovyInitScript.init failed|Failed to run script" >&2
    die "the seeding script failed; see the log above"
fi

# JSON rather than an XPath query: with security on, Jenkins refuses XPath text results.
VERSION="$(curl -s -u admin:admin "${BASE}/pluginManager/api/json?tree=plugins%5BshortName,version%5D" \
    | grep -o '"shortName":"concierge","version":"[^"]*"' | sed 's/.*"version":"//; s/"$//' || true)"
[ -n "$VERSION" ] || die "the concierge plugin is not loaded; try '$RUNTIME logs $CONTAINER'"
info "Concierge $VERSION is installed"

# ------------------------------------------------------------------ done

if [ -n "$PROVIDER" ]; then
    ASSISTANT="assistant on (${PROVIDER}${MODEL:+, $MODEL}): sign in as alice or admin and click the bell in the bottom-right corner"
else
    ASSISTANT="assistant off. Re-run with --anthropic, --openai, --xai or --ollama MODEL to try it"
fi

cat <<EOF

${GREEN}${BOLD}Ready.${RESET}  ${BOLD}${BASE}/concierge/${RESET}

  Sign in as each of these and compare what you see (password = user name):
      ${BOLD}admin${RESET}  everything, including the platform folder and every setting
      ${BOLD}alice${RESET}  payments developer: builds payments/*, uses the assistant
      ${BOLD}bob${RESET}    read-only, no assistant
      Neither of them can see the platform folder.

  ${ASSISTANT}

  Try a build with parameters: payments/release-train (as alice, ask the assistant)
  Things to try: docs/demo.md
  Tear down:     ./scripts/demo.sh --stop

  ${YELLOW}Throwaway controller with known passwords. It is bound to localhost; keep it that way.${RESET}
EOF
