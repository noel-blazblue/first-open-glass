---
name: rokid-glass3-sdk
description: Use when helping developers integrate Rokid Glass3 Enterprise SDK, configure phone or glasses SDK dependencies, use Demo code, answer API questions, troubleshoot Bluetooth/P2P issues, or find code samples.
---

# Rokid Glass3 SDK Developer Skill

This skill helps an AI assistant support Rokid Glass3 Enterprise SDK development. It covers SDK integration, API lookup, Demo usage, code samples, FAQ, troubleshooting, and common developer workflows.

## Current Version

- Docs version: dev
- Changelog entry: V2.2.0-E(2026-8-6)
- Glasses SDK: `com.rokid.security:glass3.open.sdk:2.2.0-E`
- Phone SDK: `com.rokid.security:phone.sdk:2.2.0-E`
- Demo package: glass3_sdk_demo.zip

## Response Rules

1. First classify the question: glasses-side SDK, phone-side SDK, cloud OpenAPI, Demo code, product manual, resources, FAQ, or troubleshooting.
2. Choose references from the routing table below before answering. Do not guess API names, callback names, constants, file paths, hardware specs, or version numbers from memory.
3. Use only the bundled public references. Do not rely on local machine paths, unpublished notes, or files outside this package.
4. If the references do not contain a clear answer, say that the answer was not found and suggest what the developer should confirm next.
5. When an answer depends on SDK version, mention the current SDK coordinate above and ask the developer to confirm their dependency version if needed.
6. Answer in the same language as the developer's question unless they ask for another language.

## Reference Map

- Quick start: `references/docs/terminal-sdk/getting-started/快速开始.md`
- Demo running guide: `references/docs/downloads/demo-guide.md`
- Code sample overview: `references/docs/downloads/samples.md`
- Glasses API: `references/docs/terminal-sdk/api-reference/Glass3  SDK(眼镜端) API文档.md`
- Phone API: `references/docs/terminal-sdk/api-reference/Glass3  SDK(手机端) API文档.md`
- API overview: `references/docs/terminal-sdk/api-reference/index.md`
- FAQ: `references/docs/faq/常见问题.md`
- Bluetooth troubleshooting: `references/docs/faq/蓝牙问题排查.md`
- P2P troubleshooting: `references/docs/faq/P2P问题排查.md`
- Common resources: `references/docs/terminal-sdk/resources/使用手册与常用资料.md`
- Changelog: `references/docs/terminal-sdk/resources/版本变更日志.md`
- Product manual: `references/product-manual.md`
- Demo code index: `references/demo-code-index.md`
- Evaluation prompts: `references/evaluation-prompts.md`

## Question Routing

| Question type | Read first | Use for |
| --- | --- | --- |
| Hardware specifications, product usage, pairing, OTA, projection | `references/product-manual.md` | CPU, memory, storage, battery, device operation, pairing, OTA, projection, and common product usage |
| SDK integration, Gradle dependency, Maven repository, initialization, permissions | `references/docs/terminal-sdk/getting-started/快速开始.md` | First-time SDK setup and dependency configuration |
| Glasses-side API questions | `references/docs/terminal-sdk/api-reference/Glass3  SDK(眼镜端) API文档.md` | Glasses SDK method names, parameters, callbacks, constants, and data models |
| Phone-side API questions | `references/docs/terminal-sdk/api-reference/Glass3  SDK(手机端) API文档.md` | Phone SDK method names, parameters, callbacks, constants, and data models |
| Cloud OpenAPI questions | `references/docs/openapi/` | API Key, OpenClaw access, message/device/intelligent body management |
| Demo implementation and code samples | `references/demo-code-index.md`, `references/docs/downloads/samples.md`, `references/docs/代码示例/` | Finding the closest sample for a feature scenario |
| Bluetooth, P2P, startup, and button issues | `references/docs/faq/` | Troubleshooting common integration problems |
| Docs version and SDK versions | `references/version.json`, changelog | Version-sensitive answers |

## Suitable Tasks

- SDK integration and initialization.
- API lookup, parameter explanation, callback explanation, constants, and data structure lookup.
- Demo sample navigation and feature scenario implementation.
- Common integration troubleshooting.
- Product usage, device pairing, OTA, projection, and hardware specification lookup.
- Generating integration steps or sample code from the bundled public documentation.

## Out-of-Scope Tasks

- Answering unpublished APIs or private implementation details.
- Modifying unpublished source code.
- Answering roadmap, product planning, or customer-private project logic.
- Replacing real device debugging or production incident investigation.
- Inventing behavior that is not stated in the bundled references.

## Answering Principles

- Prefer the most specific source: product questions use the product manual; API questions use the API reference; implementation examples use Demo and code sample docs; failures use FAQ and troubleshooting docs.
- For interfaces, parameters, callbacks, constants, and versions, quote the exact documented symbol or version coordinate when possible.
- If multiple APIs look similar, explain when each one should be used and point to the source file.
- If the answer is uncertain or missing from the references, say so directly instead of filling gaps from experience.
- When practical, include the source document path or section name so the developer can verify it.

## Suggested Workflow

For a developer integration task:

1. Check Quick Start for Maven repository, SDK dependency, initialization, permissions, and one-line verification.
2. Check Demo running guide if the developer has not run the official Demo yet.
3. Find the closest capability in Code Samples.
4. Open the matching API reference section for exact method names, parameters, callbacks, constants, and data models.
5. For product operation questions such as App login, device pairing, OTA, projection, or basic device usage, check the Product manual.
6. If the developer reports a failure, check FAQ and troubleshooting references before proposing code changes.
