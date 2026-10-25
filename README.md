# Concierge

> **Status: proposal.** This repository holds the design and the build setup. There is no feature
> code yet. See [docs/roadmap.md](docs/roadmap.md) for what is planned and in what order.

Concierge helps people find their way around a Jenkins instance they have just joined, and answers
questions about it.

- **Onboarding.** A welcome page that shows how the instance is organised, which jobs matter and
  how healthy things are. Learning paths for developers, DevOps engineers and people new to Jenkins.
  Guided tours over real job pages, curated by admins or team leads.
- **An optional AI assistant.** Ask about jobs, builds, failures and plugins, using your own
  Anthropic, OpenAI, xAI Grok, DeepSeek or local model. Later, run a job from the chat after you
  confirm it.

Everything Concierge shows is computed for the person looking at it, from what they are already
allowed to see. Onboarding works with no AI and sends nothing off the instance.

## Why another plugin

The nearest existing plugins each cover one piece:

| Plugin | Inside the Jenkins UI | Knows this instance | Guided onboarding | AI with your own key | Admin control of data sent out |
| --- | --- | --- | --- | --- | --- |
| [MCP Server](https://plugins.jenkins.io/mcp-server/) | No, needs an external MCP client | Yes | No | Client's choice | No |
| [Explain Error](https://plugins.jenkins.io/explain-error) | Yes | Failed builds only | No | OpenAI, Gemini, Ollama, Bedrock | Limited |
| [Resources AI Chatbot](https://plugins.jenkins.io/resources-ai-chatbot) | Yes | No, documentation only | No | Local model, separate Python service | n/a |
| **Concierge** | Yes | Yes, per user permissions | Yes | Anthropic, OpenAI, Grok, DeepSeek, local | Yes, with an audit log |

## Security

Security is a design constraint here, not a phase. The rules every change has to follow are in
[docs/security-design.md](docs/security-design.md). The short version:

- Every lookup runs as the person asking, never as SYSTEM.
- API keys live in the Credentials plugin.
- Secrets never leave the instance; only allowlisted fields are sent to a model.
- Admins decide which vendors are allowed and what data may be sent, and every request is audited.
- Text from logs and commits can never trigger an action.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Documentation

- [docs/roadmap.md](docs/roadmap.md): phases, providers and the name.
- [docs/security-design.md](docs/security-design.md): the rules, and why each exists.
- [docs/quality-bar.md](docs/quality-bar.md): what every pull request must pass.
- [docs/building.md](docs/building.md): building and running locally.
- [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md): how to contribute.

## Licence

[MIT](LICENSE).
