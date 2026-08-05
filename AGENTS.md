# Insurance Agent Project Memory

## Project Context

This project is `insurance-agent`, a technical verification project for a future banking and financial agent platform.

The current business goal is to build an insurance product management agent system based on Spring AI Alibaba.

Final target capabilities:

- Product comparison and analysis agent
- Insurance business knowledge Q&A agent
- Customer policy query agent
- Customer asset query agent
- Multi-agent collaboration through Workflow in later phases

Current phase:

- Phase1 focuses only on the `ProductAnalysisAgent` single-agent closed loop.
- Phase1-Task1 project initialization is complete.
- Phase1-Task2 Skill infrastructure is complete.
- Phase1-Task3 ProductAnalysisAgent skeleton and ReactAgent assembly is complete.
- Phase1-Task4 product domain model, mock service, formatter, and controlled invocation boundary is complete.
- Phase1-Task5 ProductAnalysisTool and ReactAgent tool-calling integration is complete.
- Phase1-Task6 controlled model invocation API for local verification with DeepSeek is complete.
- Phase1-Task7 production-style API response, error handling, and traceId boundary is complete.
- Phase1-Task8 AI model status API for local DeepSeek verification is complete.
- Phase1-Task9 Skill-level output contract and DeepSeek manual verification guide is complete.
- Phase1-Task10 Agent invocation duration and answer format inspection is complete.
- Phase1-Task11 Agent invocation id, answered timestamp, and answer length observation is complete.

## Technology Baseline

- Java 21
- Spring Boot 3.5.8
- Gradle
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.3
- Lombok

Current model strategy:

- Single-model mode.
- A global `ChatModel` Bean is reused by upper layers.
- API keys must be read from environment variables.
- Never hard-code API keys.

Common local model environment variables:

```text
AI_API_KEY=...
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-chat
```

For DashScope OpenAI-compatible mode:

```text
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
AI_MODEL=qwen-plus
```

## Architecture Principles

Use a single Gradle module for now, but keep package boundaries ready for future multi-module extraction.

Future module candidates:

- `ai-core`
- `product-agent`
- `policy-agent`
- `knowledge-agent`
- `asset-agent`

Current package root:

```text
com.xxx.insurance
```

Current package layout:

```text
ai
├── agent
├── controller
├── skill
├── tool
├── memory
├── model
├── service
└── config

product
├── agent
├── config
├── controller
├── skill
├── tool
├── service
├── formatter
└── model

common
├── config
├── exception
├── result
└── util
```

## Spring AI Alibaba Verified Classes

Do not guess imports for Spring AI Alibaba Agent or Skill classes. The following classes were verified from local Gradle cache for version `1.1.2.3`.

```java
com.alibaba.cloud.ai.graph.agent.ReactAgent
com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook
com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry
com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry
com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry
```

Verified artifacts:

```text
com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework:1.1.2.3
com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.3
```

`spring-ai-alibaba-graph-core` is pulled transitively by `spring-ai-alibaba-agent-framework`.

## Current Dependency Direction

The project should keep Spring AI base model support and Spring AI Alibaba agent support aligned.

Expected dependency direction:

```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-openai'
implementation "com.alibaba.cloud.ai:spring-ai-alibaba-dashscope:${springAiAlibabaVersion}"
implementation "com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework:${springAiAlibabaVersion}"
```

Avoid adding broad or experimental dependencies unless a task explicitly requires them.

## Skill Design

Skill is not just a file loader.

Skill represents:

- Domain capability description
- Prompt/context enhancement
- Tool capability declaration
- Input and output contract
- Usage rules
- Financial compliance constraints

Each `SKILL.md` must include YAML frontmatter required by Spring AI Alibaba's `SkillScanner`:

```markdown
---
name: limited-product-analysis
description: Short description used by SkillRegistry metadata.
allowed_tools: []
---
```

The `name` value must match the skill directory name.

Current resource layout must isolate skills by business agent:

```text
src/main/resources/skills/
└── product-analysis/
    ├── limited-product-analysis/
    │   └── SKILL.md
    └── batch-product-analysis/
        └── SKILL.md
```

Do not point an agent-specific registry at the shared root `skills`, because that would allow one sub-agent to load skills owned by another sub-agent.

For `ProductAnalysisAgent`, use this registry root in later tasks:

```java
SkillRegistry registry = ClasspathSkillRegistry.builder()
        .classpathPath("skills/product-analysis")
        .build();
```

Future sibling roots:

```text
src/main/resources/skills/
├── product-analysis/
├── policy-query/
├── knowledge-qa/
└── asset-query/
```

Spring AI Alibaba Skill integration target:

```text
ReactAgent
↓
SkillsAgentHook
↓
SkillRegistry
↓
SKILL.md
```

## ProductAnalysisAgent Direction

`ProductAnalysisAgent` is the Phase1 target agent, but it must not be implemented during project initialization tasks.

Expected future structure:

```text
ProductAnalysisAgent
↓
ReactAgent
↓
SkillsAgentHook
↓
SkillRegistry
↓
ProductAnalysisTool
↓
Product domain Service
↓
Formatter
```

Supported future skills:

- `limited-product-analysis`: for explicit product analysis.
- `batch-product-analysis`: for multiple products or attribute-based product comparison.

## Tool Design Rules

Agent is responsible for reasoning and parameter extraction.

Tool is responsible for deterministic business calls.

Tool must not parse arbitrary raw user questions as its main responsibility.

Preferred direction:

```text
Agent output structured parameters
↓
Tool executes deterministic query
↓
Service returns raw domain data
↓
Formatter converts data for agent context or final output
```

## Logging Rules

Reserve consistent log markers for the agent execution chain:

```text
[Agent]
[Skill]
[Tool]
[Memory]
```

HTTP APIs return a unified response envelope and include `X-Trace-Id` in the response header.
The same traceId is also written into the logging MDC for local API and Agent-chain troubleshooting.

Use these markers when implementing agent, skill, tool, or memory infrastructure.

## Coding Rules

Spring AI Alibaba integration code must include detailed comments explaining:

- Why the design is used
- How Spring AI Alibaba calls are wired
- How future ReactAgent, Skill, Tool, Memory, or Workflow layers will consume it

Normal Java business code should follow enterprise style:

- Clear names
- Small focused classes
- Interface before replaceable implementation
- No excessive comments
- No unrelated refactoring

## Test Rules

Each phase must keep at least a Spring Boot startup test.

Before handoff, run:

```bash
./gradlew test
```

For local boot verification, run with an API key environment variable:

```bash
AI_API_KEY=test-api-key ./gradlew bootRun
```

The current stage does not actively call a model during startup.

## Forbidden Unless Explicitly Requested

Do not implement the following outside the task boundary:

- ProductAnalysisAgent
- ReactAgent assembly
- Tool Calling
- Graph Workflow
- Planner
- DAG Executor
- Human Confirm
- Vector Database
- Custom agent framework that bypasses Spring AI Alibaba
- Hard-coded API keys or secrets

When a task belongs to a later phase, keep only placeholders or comments if needed.

## Current Known Local Startup Notes

If IDEA reports:

```text
Invalid profile property value found in Environment under 'spring.profiles.active'
```

it usually means `AI_API_KEY=...` was incorrectly placed into Active profiles.

Correct IDEA configuration:

- Put `AI_API_KEY`, `AI_BASE_URL`, and `AI_MODEL` under Environment variables.
- Leave Active profiles empty unless a real Spring profile such as `dev` or `test` is needed.
