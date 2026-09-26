# Security Policy

## Reporting a vulnerability

Report security vulnerabilities **privately** using GitHub Security Advisories:

**[Open a private advisory](https://github.com/luvax-social/luvax/security/advisories/new)**

Do not open a public issue. Do not send vulnerability details by email. GitHub Security Advisories keep the report confidential until a fix is released and allow coordinated disclosure.

## What to include

Provide as much of the following as possible:

- Repository URL and affected branch or version
- Whether the vulnerability is already publicly disclosed
- Detailed description of the vulnerability
- Step-by-step instructions to reproduce the issue
- Affected versions, if known
- Any suggested remediation

The more detail you provide, the faster triage and patching can proceed.

## Response timeline

- **Acknowledgment**: within 5 business days of submission.
- **Triage and severity assessment**: communicated after initial review.
- **Patch timeline**: communicated after triage based on severity.

Critical vulnerabilities are prioritized. If you do not receive acknowledgment within 5 business days, follow up via the advisory thread.

## Confidentiality

Keep the vulnerability details private until a fix has been released. We will coordinate the disclosure timeline with you and credit reporters in release notes unless anonymity is requested.

## Scope

**In scope** — issues we treat as security vulnerabilities:

- Authentication bypasses
- JWT handling flaws (token forgery, insufficient validation, improper blacklisting)
- SQL injection or other injection attacks
- Sensitive data exposure (tokens, passwords, PII)
- Privilege escalation between user roles (`user`, `moderator`, `admin`)
- Broken access control on private account content

**Out of scope** — not treated as security vulnerabilities:

- Rate-limiting threshold configuration
- Missing features or functional bugs without a security impact
- Vulnerabilities in third-party dependencies already tracked by a public CVE
