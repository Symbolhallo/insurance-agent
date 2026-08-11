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
- Phase2 enters Memory / Workflow pre-design after ProductAnalysisAgent Phase1 acceptance.
- Phase2-Task0 Memory / Workflow pre-design document is complete.
- Phase2 confirmed local database is OceanBase/MySQL protocol through `127.0.0.1:2881`.
- Phase2 allows plaintext model input/output in local database, uses mock identity fields, and keeps local memory permanently.
- Phase2 Memory should follow Spring AI `ChatMemory` / `ChatMemoryRepository`; `AgentInvocation` is audit/observation, not the primary memory table.
- ProductAnalysisAgent uses optional ChatMemory: default profile is stateless, local-db profile enables conversation history through `ReactAgent.call(List<Message>)`.
- local-db profile writes successful ProductAnalysisAgent requests to both `ai_chat_memory` and `ai_long_term_memory`; long-term memory is append-only history.
- `ai_chat_memory` and `ai_long_term_memory` must be written through `AgentMemoryService.saveSuccessfulExchange(...)` in one transaction.
- Phase2-Task11 Spring AI Alibaba Main Graph v1 skeleton is complete.
- Phase2-Task12 merges request loading, memory loading, and query understanding into `ContextAlignmentNode`.
- Main Graph v1 currently runs `ProductReferenceResolution -> (Mock ProductRecall -> HumanConfirm) -> ContextAlignment -> IntentRecognition -> PlannerAgent -> DagExecutor -> SummaryAgent -> OutputReview`.
- `ProductReferenceResolutionNode` is the single source of truth for product clues and candidate recall routing. It may only load confirmed products from the current `conversationId`.
- A first, fuzzy, or unresolved product reference enters candidate recall and persistent Human Confirm. A pure condition filter or a reference uniquely mapped to a product confirmed in the same conversation goes directly to context alignment.
- `ContextAlignmentNode` runs only after product resolution and uses standardized product data to align history and rewrite the question.
- `WorkflowPlannerAgent` is a dedicated Spring AI Alibaba `ReactAgent`; Planner v2 emits one or two whitelisted ProductAnalysisAgent/KnowledgeQAAgent tasks and only allows dependencies on earlier tasks.
- Phase2 OceanBase Graph Checkpoint foundation is complete: V4 creates thread/checkpoint tables, `OceanBaseCheckpointSaver` implements `BaseCheckpointSaver`, and Main Graph uses `workflowInstanceId` as threadId under the `local-db` profile.
- Phase2 Mock product recall, retrieval audit, context-alignment recall decision, and V6 workflow definition are complete.
- Phase2 Human Confirm, conversation-scoped confirmed products, Checkpoint update/resume API, and V7 workflow definition are complete.
- Phase2 KnowledgeQAAgent, isolated knowledge Skill/Tool, two-intent routing, dynamic single-agent invocation, and V8 workflow definition are complete.
- Phase2 dynamic DAG execution is complete: independent tasks run on a bounded executor, dependency failures skip successors, partial results are summarized, and V9 records the workflow definition.
- DAG child agents write invocation audit only; Main Workflow writes one final user/assistant exchange to ChatMemory and long-term memory after aggregation.
- Phase2 output review is complete: the node calls one `OutputReviewGateway.review(...)` method and uses a Mock gateway until the line-of-business microservice contract is supplied.
- Phase2 Summary Agent is complete: one successful task is passed through without a model call; multiple or mixed task results are synthesized by a dedicated tool-less ReactAgent. Main Graph runs `dag-executor -> summary -> output-review`, and V11 records the workflow definition.
- Only `OutputReviewResult.publishableAnswer` may become `finalAnswer`; invalid or failed review calls fail closed, and a BLOCK decision produces workflow status `REVIEW_BLOCKED`.
- Phase2 stage-level Workflow SSE is complete: `POST /runs/stream` starts a background Graph, `GET /runs/{workflowInstanceId}/events` replays by `Last-Event-ID`, and OceanBase stores sanitized replay events for 10 minutes by default; a dedicated 30-second cleanup schedule physically deletes expired rows.
- Phase2 live Agent streaming is complete: SSE runs publish `AGENT_MODEL_STREAMING` text immediately with `streamId`, `taskId`, `agentName`, and phase. Sub-agent and Summary streams are provisional; only the complete Summary is reviewed, and the `complete.finalAnswer` field is authoritative after PASS/REWRITE/BLOCK handling.
- Pre-workflow model streaming is complete: product-reference resolution, context alignment, intent recognition, and Planner publish model chunks under node-specific phases. Product confirmation uses a separate POST SSE resume endpoint that subscribes before restoring the OceanBase Checkpoint.
- Phase2 dynamic DAG v2 is complete: Planner tasks use `agentType`, `query`, `dependsOn`, `maxRetries`, and `required`; execution depends only on `dependsOn` and consumes individual completion events instead of waiting for whole waves.
- Every child task runs in an independent Spring AI Alibaba task subgraph and OceanBase Checkpoint thread. Successful task checkpoints are reused after recovery; V14 allows multiple graph threads per workflow instance.
- Main Workflow active recovery is available for orphaned `RUNNING` instances. The API atomically changes the database status to `RESUMING` before loading the latest OceanBase Checkpoint, so concurrent recovery requests cannot fork the same execution.
- Main Workflow finalization is a single OceanBase transaction: terminal instance state, deterministic `wfa-{workflowInstanceId}` Memory invocation, pending-step closure, Checkpoint state, terminal SSE Outbox event, and conversation lock release commit together.
- Graph Checkpoint retention defaults are 7 days for ACTIVE/RUNNING/FAILED and 24 hours for COMPLETED/RELEASED. Hourly cleanup physically deletes expired checkpoints first and then their expired thread rows; this policy is independent from the 10-minute SSE retention.
- `RUNNING`, `CONFIRMING`, and `RESUMING` execution leases are renewed by `WorkflowLeaseRecoveryJob` only while the current JVM still owns a non-expired lease. The same database statement refreshes the matching conversation lock; stale owners cannot revive or overwrite a transferred lease.
- Expired transient claims are released by `WorkflowLeaseRecoveryJob`, and expired invalid conversation locks are physically deleted. A lock remains protected while its workflow has a valid execution lease or an unexpired recoverable Graph thread.
- Every top-level request must provide a `requestId`. `(conversationId, requestId)` is unique, and `ai_conversation_workflow_lock` permits only one active top-level workflow per conversation. Startup performs conditional stale-lock cleanup before relying on the conversation primary key as the final multi-instance mutex.
- After every architecture, workflow, persistence, API, package-structure, or business-capability optimization, update `docs/project-understanding-guide.md` in the same change so it remains the authoritative project map.
- Spring AI Alibaba 1.1.2.0 may materialize nested final Record elements in generic lists as `LinkedHashMap` between Graph checkpoints. The Main Workflow checkpoint serializer explicitly normalizes and restores `IntentRoutingResult.routes` as `IntentRoute` objects; new nested State DTO lists require the same consecutive-checkpoint regression test.
- All four domain agents invoke the shared real ChatModel. ProductAnalysisAgent, KnowledgeQAAgent, PolicyQueryAgent, and AssetQueryAgent each have isolated Skill registries and Tool callbacks.
- PolicyQueryAgent and AssetQueryAgent currently query only `MOCK-CUSTOMER-001` through read-only Mock Tools, then use ReactAgent to format the Tool facts. They must never invent customer data. Production integration must inject customer identity and authorization through server-side context instead of model-generated Tool arguments.

## Technology Baseline

- Java 21
- Spring Boot 3.5.8
- Gradle
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.0
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

Before changing package ownership, workflow orchestration, database persistence, Memory,
Checkpoint, SSE, or domain Agent assembly, read the project map first:

```text
docs/project-understanding-guide.md
```

It is the maintained index of directory responsibilities, important functions and Beans,
database tables and relationships, runtime call chains, profiles, and file placement rules.

For every task involving Spring AI Alibaba architecture, Agent Framework, Graph Core,
ReactAgent, Tool, Skill, Hook, Interceptor, Memory, Checkpoint, streaming, or multi-agent
APIs, first consult the project reference documents under:

```text
docs/spring-ai-alibaba/
```

Use those documents as the project's primary development reference, then verify exact
class names, packages, builders, and method signatures against the locally resolved
dependency sources for the project's current version. When the documents and local
sources differ, the local dependency sources control compile-time decisions and the
difference must be documented. Do not rely on memory or unverified rolling website
examples for Spring AI Alibaba code.

Do not guess imports for Spring AI Alibaba Agent or Skill classes. The following classes were verified from local Gradle cache for version `1.1.2.0`.

```java
com.alibaba.cloud.ai.graph.agent.ReactAgent
com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook
com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry
com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry
com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry
```

Verified artifacts:

```text
com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework:1.1.2.0
com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.0
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

## Stage Acceptance Output

After completing every development stage, provide acceptance cases that include:

- Swagger UI request examples for the newly completed capability
- Expected HTTP response and important application log markers
- OceanBase verification SQL when the stage changes persistent data
- Negative or boundary cases relevant to the stage
- A clear completed / next stage / unfinished progress summary

Do not repeat IDEA Run/Debug configuration after every stage. Include IDEA startup information
only when the stage changes startup parameters, active profiles, environment variables, ports,
or other local launch requirements.

Do not treat automated tests alone as sufficient stage acceptance guidance.

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

Every Spring `@Bean` factory method must have method-level JavaDoc explaining the Bean purpose,
key dependencies, framework call relationship, and primary consumer. Every public business method
must describe its responsibility and important side effects. Private helper methods added for Agent,
Memory, or Workflow orchestration must have a concise purpose comment.

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
- Additional Planner capabilities beyond the validated two-task Planner v2
- Policy and asset agents
- Additional Human Confirm scenarios beyond product candidate confirmation
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
