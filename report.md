# CodeAuto 技术报告（面试准备版）

## 1. 项目概述

CodeAuto 是一个基于 Java 21 实现的 AI Coding Agent 运行时。它参考 Claude Code 这类终端编码助手的交互模式，用 JVM 技术栈实现了一个支持普通 CLI、全屏 TUI、模型工具调用、权限审批、上下文压缩、会话恢复、MCP 扩展、Skills 加载和持久化记忆的智能编程助手。

一句话面试表达：

> 我做的是一个 Java 版的 AI Coding Agent。核心不是简单调用大模型，而是实现了“模型规划 -> 工具调用 -> 结果回填 -> 多轮继续”的 AgentLoop，并围绕真实开发场景补齐了权限、安全、上下文管理、会话持久化、MCP 扩展和 TUI 交互。

项目价值：

- 对 Java 开发者友好：主流 AI Coding Agent 多用 TypeScript 或 Python 实现，本项目把 Agent 运行时搬到 JVM 生态。
- 工程闭环完整：不是 Demo 式聊天程序，而是包含模型适配、工具系统、安全审批、存储、UI、测试的完整运行时。
- 面试亮点集中：可以重点讲架构分层、Agent 循环、工具抽象、权限模型、上下文压缩、MCP 协议适配、TUI 复杂交互、测试覆盖。

## 2. 技术栈

### 2.1 后端/运行时

| 技术 | 用途 | 选择原因 |
| --- | --- | --- |
| Java 21 | 主开发语言 | records、sealed interface、现代 switch、HttpClient 等能力成熟，适合写稳定的本地运行时 |
| Maven | 构建与依赖管理 | Java 项目标准化程度高，便于测试、打包 shaded jar |
| Jackson Databind | JSON 序列化/反序列化 | 模型 API、工具 schema、会话 JSONL、MCP JSON-RPC 都依赖 JSON |
| Java HttpClient | 模型 API 和 HTTP MCP 请求 | 避免引入重量级 HTTP 框架，JDK 内置即可满足需求 |
| Picocli | 命令行参数和子命令 | 适合构建 CLI，支持 `mcp`、`skills` 等子命令 |
| JLine 3 | 终端输入与 TUI 基础能力 | 支持 raw mode、按键读取、终端尺寸、历史输入、跨平台终端能力 |
| java-diff-utils | 文件修改 diff 生成 | 写文件前后生成 unified diff，便于审查和权限确认 |
| JUnit 5 | 单元测试 | 覆盖 AgentLoop、权限、MCP、Session、工具、上下文等模块 |
| Maven Shade Plugin | 打包可执行 jar | 生成带 Main-Class 的 shaded jar，降低部署和运行门槛 |

### 2.2 外部协议和数据格式

| 协议/格式 | 用途 |
| --- | --- |
| Anthropic Messages API | 大模型对话、工具调用、流式输出 |
| Server-Sent Events | Anthropic 流式响应解析 |
| JSON Schema | 工具入参声明，暴露给模型 |
| JSON-RPC 2.0 | MCP stdio/http 通信基础 |
| MCP | 外部工具、资源、Prompt 扩展 |
| JSONL | 会话事件持久化 |
| Markdown + frontmatter | 持久化记忆存储 |
| Unified diff | 文件变更审查 |

## 3. 目录结构与职责划分

核心源码位于 `src/main/java/com/codeauto/`：

| 包 | 职责 |
| --- | --- |
| `cli` | Picocli 入口、普通 CLI 模式、子命令接入 |
| `tui` | 全屏终端界面、输入事件、渲染、滚动、权限弹窗、Session 选择 |
| `core` | AgentLoop、消息类型、模型 step、工具调用抽象 |
| `model` | 模型适配器，当前包括 Anthropic 和 Mock |
| `tool` | 工具接口、工具上下文、工具注册表 |
| `tools` | 内置工具：文件、命令、搜索、记忆、MCP helper 等 |
| `permissions` | 文件写入和危险命令审批、持久化规则、deny feedback |
| `context` | token 估算、自动压缩、微压缩、大工具结果外置 |
| `session` | 会话 JSONL 保存、恢复、fork、rename、compact boundary |
| `mcp` | MCP stdio/http 客户端、服务发现、MCP 工具包装 |
| `memory` | 持久化记忆保存、检索、注入 system prompt |
| `skills` | 本地和托管 Skills 发现、加载 |
| `instructions` | 多级 CLAUDE.md 指令加载 |
| `config` | 默认配置、环境变量、项目配置、用户配置、CLI 覆盖 |
| `background` | 后台命令任务注册和状态管理 |
| `manage` | MCP/Skills 等管理配置存储 |

面试表达：

> 我把项目拆成了 UI、Agent 核心、模型适配、工具系统、安全权限、上下文、持久化和扩展协议几个层。这样模型供应商、工具实现、终端 UI、会话存储可以独立演进，不会互相绑死。

## 4. 整体架构

### 4.1 分层视角

```text
User
  |
  | CLI / TUI 输入
  v
CodeAutoCli / TuiApp
  |
  | 构造 system prompt、加载配置、初始化工具和权限
  v
AgentLoop
  |
  | messages + tools schema
  v
ModelAdapter (Anthropic / Mock)
  |
  | final response / progress / tool_use
  v
ToolRegistry
  |
  | 内置工具 / MCP backed tools
  v
文件系统、Shell、Web、Memory、MCP Server
  |
  | tool_result
  v
AgentLoop 回填工具结果，继续下一步
```

### 4.2 运行链路

一次用户请求的大致流程：

1. CLI/TUI 收到用户输入。
2. PermissionManager 开始一个 turn，清空本轮临时授权。
3. 用户消息加入 `messages`。
4. AgentLoop 进入最多 `maxSteps` 次循环。
5. 每步先做 microcompact 和 token 估算。
6. 如果上下文接近危险区，自动用 CompactService 压缩中间消息。
7. ModelAdapter 把内部 ChatMessage 转成 Anthropic Messages API 请求。
8. 模型返回 final 文本、progress 文本，或 tool_use。
9. 如果是工具调用，ToolRegistry 根据名称分发到具体 ToolDefinition。
10. 工具执行前检查权限，执行后生成 ToolResult。
11. 大工具结果可能被外置到 `~/.codeauto/tool-results/`，只把摘要和预览回填给模型。
12. 工具结果作为 `tool_result` 回填 messages，AgentLoop 继续让模型下一步。
13. 得到 final response 或达到最大步数后结束。
14. SessionStore 追加保存新消息到 JSONL。

## 5. 核心模块详解

### 5.1 AgentLoop：项目的核心状态机

相关文件：`src/main/java/com/codeauto/core/AgentLoop.java`

AgentLoop 负责把大模型从“单轮聊天”变成“可执行任务的代理”。它处理三类模型输出：

- `AssistantStep`：普通助手回复，可能是 final，也可能是 progress。
- `ToolCallsStep`：模型请求工具调用。
- 空响应或异常边界：空响应最多重试两次，超过后返回明确提示。

关键处理：

- 每次模型调用前做 `MicroCompactService.microcompact`，清理旧的大工具输出。
- 首轮发现 context warning level 为 `critical` 或 `blocked` 时触发自动压缩。
- 工具调用前后通过 `AgentLoopListener` 通知 CLI/TUI，实现流式 UI、工具状态展示、上下文状态展示。
- 支持 `rawContent` 保留 provider 原始 tool_use 内容，解决 Anthropic thinking/tool_use round-trip 的兼容问题。
- 每轮有 `maxSteps` 限制，避免模型无限调用工具。

为什么这么处理：

- Agent 场景的复杂度在于多步执行，不是一次请求一次回答。
- 模型可能输出 progress，然后继续干活；也可能请求多个工具；也可能返回空响应。
- 用 AgentLoop 统一管理状态，能让 CLI 和 TUI 共享同一套智能体逻辑。

和简单实现的对比：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 直接调用模型返回文本 | 实现简单 | 不能执行真实任务，无法改文件、跑命令 |
| UI 里硬编码工具调用 | 快速做 Demo | CLI/TUI 逻辑重复，难测试 |
| 独立 AgentLoop 状态机 | 复用性高，可测试，可扩展 | 状态设计更复杂，需要处理消息协议细节 |

面试可讲：

> 我把 AgentLoop 做成了核心状态机，它不关心 UI，只关心 messages、model、tools 和 context。这样普通 CLI、全屏 TUI、测试里的 MockModel 都能复用同一个执行闭环。

### 5.2 消息模型：sealed interface + records

相关文件：`src/main/java/com/codeauto/core/ChatMessage.java`

项目用 Java 21 的 sealed interface 表达消息类型：

- `SystemMessage`
- `UserMessage`
- `AssistantMessage`
- `AssistantRawMessage`
- `AssistantProgressMessage`
- `AssistantToolCallMessage`
- `ToolResultMessage`
- `ContextSummaryMessage`

为什么这样设计：

- sealed interface 限定消息类型集合，switch 处理时更安全。
- record 适合不可变数据载体，天然适合会话消息。
- Jackson 通过 `@JsonTypeInfo` 和 `@JsonSubTypes` 序列化 polymorphic JSON，便于 JSONL 持久化。

与 Map/String 方案对比：

- Map 灵活但缺少类型约束，字段拼错运行期才发现。
- 统一 String 简单但表达不了 tool_use、tool_result、summary 等结构。
- sealed records 类型安全、可读性好，适合长期维护。

### 5.3 模型适配层：AnthropicModelAdapter

相关文件：`src/main/java/com/codeauto/model/AnthropicModelAdapter.java`

职责：

- 根据 ToolRegistry 生成 Anthropic tools schema。
- 将内部 ChatMessage 转成 Anthropic Messages API 请求。
- 支持普通 HTTP 响应和 SSE 流式响应。
- 解析 `text`、`tool_use`、usage token。
- 处理 429 和 5xx 重试，支持 `retry-after`。

关键点：

- system message 会被合并到 Anthropic 请求的 `system` 字段。
- 连续多个 `ToolResultMessage` 会合并成一个 user message 的多个 `tool_result` block。
- streaming 时需要处理 `content_block_start`、`content_block_delta`、`content_block_stop`、`message_delta` 等事件。
- tool input 在流式场景里是 `input_json_delta` 分片，需要 StringBuilder 拼接后 parse JSON。
- 模型返回 `<progress>` 和 `<final>` 标签时会被解析为不同 kind。

为什么做适配层：

- 上层 AgentLoop 不应该依赖某个模型供应商。
- Anthropic 的 message/tool_result 格式和内部消息格式不完全一致，需要转换层隔离。
- 后续接 OpenAI、Gemini、本地模型时，只需要新增 ModelAdapter。

方案对比：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 业务代码里直接拼 Anthropic JSON | 开发快 | 与供应商强耦合，后期替换困难 |
| 抽象 ModelAdapter | 上层稳定，方便 Mock 测试和多模型接入 | 需要设计统一 AgentStep |

### 5.4 工具系统：ToolDefinition + ToolRegistry

相关文件：

- `src/main/java/com/codeauto/tool/ToolDefinition.java`
- `src/main/java/com/codeauto/tool/ToolRegistry.java`
- `src/main/java/com/codeauto/tools/DefaultTools.java`

工具接口包含：

- `name()`：工具名。
- `description()`：暴露给模型的说明。
- `inputSchema()`：JSON Schema，帮助模型生成合法参数。
- `run(JsonNode input, ToolContext context)`：执行逻辑。

ToolRegistry 用 `LinkedHashMap` 保存工具，保证顺序稳定；注册重复工具时使用 `putIfAbsent`，避免 MCP 工具覆盖内置工具。

内置工具约 20 个，覆盖：

- 文件：`list_files`、`grep_files`、`read_file`、`write_file`、`edit_file`、`patch_file`、`modify_file`
- 命令：`run_command`、`background_tasks`
- 交互：`ask_user`
- 网络：`web_fetch`、`web_search`
- Skills：`load_skill`
- Memory：`save_memory`、`list_memory`、`delete_memory`
- MCP helper：资源和 prompt 相关操作

为什么工具用 JSON Schema：

- 大模型工具调用天然需要 schema。
- Java 端用 Jackson JsonNode 可以兼容不同工具的参数形态。
- 后续 MCP 工具本身也携带 inputSchema，可以统一暴露。

对比：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 每个工具写 if/else 分支 | 简单直接 | 工具多了难维护，不能动态注册 |
| 统一 ToolDefinition 接口 | 可插拔、可测试、可动态扩展 | 每个工具都要补 schema 和参数校验 |

### 5.5 文件编辑与 diff review

相关文件：

- `src/main/java/com/codeauto/tools/EditFileTool.java`
- `src/main/java/com/codeauto/tools/ModifyFileTool.java`
- `src/main/java/com/codeauto/tools/PatchFileTool.java`
- `src/main/java/com/codeauto/tools/FileReviewService.java`

实现思路：

- 所有写文件工具都先读取 before。
- 生成 after 后调用 `FileReviewService.reviewAndWrite`。
- 写入前走 PermissionManager 的 `canWrite`。
- 写入后返回 unified diff。

难点：

- LLM 修改文件必须可审查，否则用户很难相信改了什么。
- edit/modify/patch 三种工具入口不同，但都应该共享权限和 diff 逻辑。
- PatchFileTool 要处理 `a/`、`b/` 前缀、上下文匹配、行尾差异。

为什么返回 diff 而不是只返回“成功”：

- Agent 可以看到自己改了什么，必要时继续修正。
- 用户也能从 TUI/CLI transcript 看到变更。
- 面试里这是“安全可控”的重要体现。

### 5.6 命令执行与后台任务

相关文件：`src/main/java/com/codeauto/tools/RunCommandTool.java`

关键设计：

- 支持 `command` 字符串，也支持 `command + args` 数组。
- 简单命令用 ProcessBuilder 直接执行。
- 检测到管道、重定向、括号、变量等 shell snippet 时走 `cmd /c` 或 `sh -lc`。
- Windows 下识别 shell builtin，例如 `dir`、`copy`，自动走 shell。
- 前台命令默认 20 秒超时。
- 输出最多读取 512KB，避免 prompt 爆炸。
- 支持 `background=true`，交给 BackgroundTaskRegistry 管理。
- 对危险命令走 PermissionManager 审批。

为什么不用 Runtime.exec：

- ProcessBuilder 参数模型更清晰，可设置工作目录和环境。
- 更容易避免字符串拼接带来的平台差异。

为什么限制超时和输出：

- Agent 执行命令不可完全信任，必须防止卡住。
- 大输出会污染上下文，影响模型后续决策。

### 5.7 权限系统：安全边界

相关文件：`src/main/java/com/codeauto/permissions/PermissionManager.java`

权限能力：

- 默认允许读取 workspace。
- 文件写入必须确认，支持：
  - allow once
  - allow always
  - allow this turn
  - deny once
  - deny always
  - deny with feedback
- 危险命令需要确认，例如：
  - `git reset --hard`
  - `git clean`
  - `git checkout --`
  - `git push --force`
  - `npm publish`
  - `python`、`bash`、`powershell` 等可执行任意代码的命令
- 支持精确规则和 glob 规则，例如 `Bash(npm run *)`、`Edit(src/*.java)`。
- 持久化到 `~/.codeauto/permissions.json`。

亮点：

- `deny with feedback` 会把用户拒绝原因回填给模型，让模型换方案，而不是简单失败。
- `beginTurn/endTurn` 清理本轮临时授权，符合 Agent “一轮任务”语义。
- 规则支持工具别名，例如 Bash/Command/RunCommand，Edit/Write/Modify。

为什么这样做：

- AI Coding Agent 最大风险之一是误删文件、执行危险命令。
- 不能把所有命令都拦住，否则体验差。
- 只拦危险命令、写文件和用户配置的敏感路径，是安全和可用性的折中。

和其他方案对比：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 全部放行 | 体验流畅 | 风险极高，面试里很难自圆其说 |
| 全部拦截 | 安全 | 体验差，Agent 无法自主完成任务 |
| 风险分类 + 持久规则 | 安全与效率平衡 | 分类规则需要不断完善 |

### 5.8 上下文管理：TokenEstimator、Compact、MicroCompact、ToolResultStorage

相关文件：

- `src/main/java/com/codeauto/context/TokenEstimator.java`
- `src/main/java/com/codeauto/context/CompactService.java`
- `src/main/java/com/codeauto/context/MicroCompactService.java`
- `src/main/java/com/codeauto/context/ToolResultStorage.java`

上下文压力来源：

- 文件读取很长。
- 命令输出很长。
- 多轮工具结果堆积。
- 会话恢复后历史消息变多。

项目用了三层策略：

1. TokenEstimator：估算当前消息 token 数和 warning level。
2. MicroCompactService：上下文超过 50% 时，把旧的可压缩工具结果替换为 `[Output cleared for context space]`，保留最近 3 个。
3. CompactService：当首轮达到 critical/blocked 时，把中间消息压缩成 `ContextSummaryMessage`，保留 system 和尾部消息。
4. ToolResultStorage：单个工具结果超过 50,000 字符，或一批工具结果超过 200,000 字符时，把完整内容落盘，只把路径、长度和前 2,000 字符预览放回上下文。

为什么不是直接截断：

- 直接截断会丢失关键信息，并且模型不知道信息被删了。
- summary message 能明确告诉模型“前面发生过什么”。
- 外置大结果保留了完整原文路径，后续需要还能读取。

面试表达：

> 我没有简单粗暴地把历史消息砍掉，而是按信息价值分层处理。旧工具输出价值低，先微压缩；大结果原文落盘，prompt 里只放预览；真正达到危险区再做会话摘要。这样既控制 token，又尽量保留任务连续性。

### 5.9 会话持久化：JSONL 事件流

相关文件：`src/main/java/com/codeauto/session/SessionStore.java`

设计：

- 每条消息保存为一行 JSON，即 JSONL。
- 会话按 workspace 隔离，目录名由 cwd 规范化生成。
- 支持 `save` 增量追加，避免每轮重写全部消息。
- 支持 rename、fork、resume。
- 支持 compact boundary：恢复时只加载最后一次 compact boundary 之后的消息。
- 支持 30 天过期清理。

为什么用 JSONL：

- 追加写简单，崩溃时最多损坏最后一行。
- 适合会话事件流。
- 便于手动排查和后续做 transcript 分析。

和数据库对比：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| SQLite | 查询能力强，事务完善 | 引入依赖和迁移成本，对本地 CLI 稍重 |
| 单个 JSON 文件 | 读取简单 | 每次保存要重写，异常容易损坏整个文件 |
| JSONL 事件流 | 追加友好、易恢复、易调试 | 聚合查询需要扫描 |

### 5.10 TUI：全屏终端交互

相关文件：`src/main/java/com/codeauto/tui/TuiApp.java`

TUI 能力：

- JLine raw mode。
- alternate screen。
- Header / transcript / prompt / footer 多面板。
- Slash command 菜单和 Tab 补全。
- 输入历史。
- 鼠标滚轮、PageUp/PageDown、Alt/Ctrl 方向键滚动。
- 流式输出原地刷新。
- 工具运行状态和 recent tools 展示。
- 权限审批弹窗。
- Session picker。
- CJK 字符宽度处理和 markdown 渲染。
- 终端 resize 处理。

难点：

- 终端 UI 没有浏览器布局引擎，需要自己处理宽度、换行、光标、滚动。
- ANSI 控制序列在 Windows/macOS/Linux 终端表现不完全一致。
- 模型流式输出和用户输入都可能触发 render，需要线程安全。
- 中文字符宽度不是 1，必须用 display width 计算。

为什么 TUI 和 AgentLoop 分离：

- TUI 很复杂，但它不应该污染 Agent 执行逻辑。
- AgentLoop 通过 listener 通知 UI，UI 只负责展示和交互。
- 这样 CLI 和 TUI 能共享核心能力。

### 5.11 MCP 扩展

相关文件：

- `src/main/java/com/codeauto/mcp/McpService.java`
- `src/main/java/com/codeauto/mcp/McpClient.java`
- `src/main/java/com/codeauto/mcp/McpHttpClient.java`
- `src/main/java/com/codeauto/mcp/McpBackedTool.java`

MCP 支持：

- stdio MCP。
- Streamable HTTP MCP。
- 支持 `content-length` 和 `newline-json` 两种 stdio frame。
- `auto` 协议先尝试 content-length，失败后 fallback 到 newline-json。
- 支持 `~/.codeauto/mcp.json` 和项目级 `.mcp.json`。
- 支持 token 注入和 HTTP headers。
- MCP 工具会被包装成 ToolDefinition，动态加入 ToolRegistry。

为什么要支持 MCP：

- Agent 的能力边界取决于工具生态。
- 内置工具不可能覆盖所有场景。
- MCP 是 AI 工具生态的通用扩展协议，支持后项目可以接数据库、GitHub、浏览器、文档等服务。

难点：

- stdio 协议需要自己处理 frame。
- MCP server 可能不稳定，失败不能阻塞本地工具启动。
- 不同 server 使用不同协议风格，需要 fallback。

### 5.12 Skills 和多级指令加载

相关文件：

- `src/main/java/com/codeauto/skills/SkillService.java`
- `src/main/java/com/codeauto/instructions/InstructionLoader.java`

Skills 来源：

- 项目级 `.code-auto/skills`
- 项目级 `.claude/skills`
- 用户管理的 skills 配置

指令加载顺序：

1. `~/.claude/CLAUDE.md`
2. `~/.codeauto/CLAUDE.md`
3. `<project>/CLAUDE.md`
4. `<project>/CLAUDE.local.md`

越靠后的本地指令优先级越高。加载后的内容放进 system prompt 的 `<system-reminder>`。

为什么这么做：

- Agent 需要理解用户偏好、团队规范、项目约定。
- 项目级和用户级指令要同时存在。
- 本地私有指令不应该提交仓库，所以提供 `CLAUDE.local.md`。

### 5.13 持久化记忆

相关文件：`src/main/java/com/codeauto/memory/MemoryManager.java`

Memory 用 Markdown + frontmatter 保存，包括：

- id
- type
- title
- project
- tags
- createdAt / updatedAt
- content

检索逻辑：

- 项目路径匹配加分。
- title/tags/content 包含 query 或项目名加分。
- 按 score 和 updatedAt 排序。
- system prompt 最多注入 5 条相关记忆。
- 超过一定时间未更新会标记 stale，提醒模型先核实。

为什么不用向量数据库：

- 当前项目定位是轻量本地运行时。
- Markdown 文件可读可迁移，便于用户审查。
- 简单关键词 + 项目路径对 MVP 已经足够。

可以在面试里补充：

> 如果后续要做规模化记忆，我会把 MemoryManager 的检索接口抽象出来，替换成 embedding + vector store，但不影响 prompt 注入层。

### 5.14 配置优先级

相关文件：`src/main/java/com/codeauto/config/ConfigLoader.java`

配置来源从低到高：

1. 默认值。
2. 环境变量。
3. 项目级 `.codeauto/settings.json`。
4. 用户级 `~/.codeauto/settings.json`。
5. CLI 参数覆盖。

注意：代码当前注释里写的是 defaults -> env -> project -> user；README 中曾描述“用户级高于项目级”。这里要以代码为准。面试时可以说：

> 我做了多层配置 merge，支持项目配置、用户配置和 CLI 临时覆盖；CLI 永远最高优先级，适合临时切模型或输出 token。

## 6. 关键难点、处理思路与为什么

### 6.1 难点一：如何让模型可靠调用工具

问题：

- 模型输出不确定。
- 工具入参可能缺字段。
- 工具结果可能过长。
- 工具调用后模型还要继续推理。

处理：

- 每个工具提供 JSON Schema。
- ToolRegistry 统一捕获异常，返回 ToolResult.error。
- AgentLoop 把 tool_result 回填给模型继续下一步。
- ToolResultStorage 控制结果大小。

为什么：

- 对 Agent 来说，工具调用失败也应该是上下文的一部分，而不是让程序崩溃。
- 模型看到错误信息后可以自我修正。

### 6.2 难点二：安全和可用性的平衡

问题：

- 如果所有操作都审批，体验很差。
- 如果都不审批，风险不可接受。

处理：

- 文件写入统一审批。
- 只对危险命令审批，普通读命令默认允许。
- 支持 allow always / allow turn，降低重复打扰。
- deny with feedback 给模型修正空间。

为什么：

- 编码 Agent 的用户目标是“完成任务”，不能因为安全层太重导致无法使用。
- 但写文件和危险 shell 是明确风险点，必须有边界。

### 6.3 难点三：上下文爆炸

问题：

- AI Agent 会不断读取文件、执行命令，tool_result 很容易撑爆上下文。

处理：

- 大工具结果外置。
- 可压缩旧工具结果清空。
- critical 时压缩历史消息。
- token 使用 provider usage 或本地估算。

为什么：

- 不同信息价值不一样，旧工具输出通常低于当前任务尾部消息。
- 外置比截断更可恢复。

### 6.4 难点四：终端 TUI 的复杂性

问题：

- 终端没有 DOM/CSS，所有布局都要自己算。
- 中文宽度、ANSI 控制序列、鼠标事件、resize 都容易出 bug。

处理：

- 抽出 Ansi、PanelRenderer、MarkdownRenderer。
- transcript 做缓存，dirty 后才重算。
- 输入和 transcript 状态同步保护。
- 针对 escape sequence 写测试。

为什么：

- TUI 是体验入口，但不能让渲染逻辑拖垮 Agent 核心。

### 6.5 难点五：MCP 协议兼容

问题：

- MCP server 有 stdio 和 HTTP。
- stdio frame 可能是 Content-Length，也可能是 newline JSON。
- 外部 server 出错不能影响本地工具。

处理：

- McpService 负责配置发现和协议分发。
- McpClient 负责 stdio frame。
- McpHttpClient 负责 HTTP。
- auto fallback 提高兼容性。
- 创建 backed tools 时吞掉单个 server 失败。

为什么：

- 扩展生态不稳定，主程序必须具备容错能力。

## 7. 和别人常见处理方式的优劣对比

### 7.1 与简单 ChatGPT Wrapper 对比

别人常见做法：

- 输入用户问题。
- 调 API。
- 打印模型回复。

CodeAuto 的区别：

- 有 AgentLoop，可多步调用工具。
- 有文件修改、命令执行、MCP。
- 有权限审批和 diff。
- 有会话、记忆、上下文压缩。

优点：

- 能真正完成开发任务。
- 有工程安全边界。
- 具备扩展性。

代价：

- 架构复杂度更高。
- 需要处理更多异常和状态。

### 7.2 与 LangChain 类框架对比

使用框架的优点：

- 快速搭建 Agent。
- 提供现成抽象。

本项目自研的优点：

- 更贴合 CLI/TUI 和本地文件系统场景。
- 权限、diff、session、MCP 等细节可以按产品需求定制。
- 面试中能体现对 Agent 底层机制的理解。

自研的不足：

- 需要自己维护协议兼容和边界条件。
- 没有框架生态中大量现成组件。

### 7.3 与 TypeScript/Python Agent 对比

TS/Python 优点：

- AI 工具生态更成熟。
- 包管理和脚本能力强。
- 现有案例多。

Java 方案优点：

- JVM 生态适合企业内部工具。
- 类型系统和长期维护性强。
- 对 Java 开发者学习成本低。
- Maven/JUnit/records/sealed interface 让工程结构清晰。

Java 方案挑战：

- 终端生态和 AI SDK 丰富度不如 TS/Python。
- JSON 结构处理比动态语言更繁琐。

### 7.4 与“直接执行 shell”型 Agent 对比

直接执行 shell：

- 实现快。
- 模型能力释放充分。
- 但安全风险高。

CodeAuto：

- 对危险命令审批。
- 支持持久授权规则。
- 对文件写入做 diff review。
- 更适合真实使用和面试表达。

### 7.5 与数据库持久化方案对比

SQLite/数据库：

- 查询强、事务好。
- 适合大规模数据。

JSONL/Markdown：

- 文件透明、轻量、易迁移。
- 适合本地 CLI 工具。
- 用户可直接查看和修复。

CodeAuto 当前选择轻量文件存储，是为了降低运行门槛。

## 8. 测试情况

当前测试数量：76 个 JUnit 测试。

覆盖模块：

- AgentLoop：工具调用、自动压缩、listener、流式 delta、raw tool_use round-trip。
- ToolRegistry：注册、查找、重复工具、异常处理。
- RunCommandTool：命令拆分、shell snippet、Windows 兼容、超时等。
- PermissionManager：危险命令分类、持久规则、turn 规则、glob 匹配。
- SessionStore：保存、恢复、fork/rename、compact boundary、项目列表。
- MCP：client frame、service 配置、协议 fallback、HTTP/header/token 等。
- Context：token 估算、compact、microcompact、大工具结果外置。
- MemoryManager：保存、列表、相关性检索。
- SkillService：技能发现。
- InstructionLoader：多级指令和 memory 注入。
- TUI：escape sequence 完整性。
- CLI 编码：JLine fallback 和 charset。

面试表达：

> 我把测试重点放在 AgentLoop 状态机、权限、安全、会话恢复和协议适配上，因为这些模块一旦出错，会直接影响任务执行可靠性。UI 渲染较难做完整单测，但我至少覆盖了 escape sequence 这类容易跨平台出问题的逻辑。

## 9. 可扩展点

### 9.1 新增模型供应商

新增一个 `ModelAdapter` 实现：

- 实现 `next(messages)`。
- 实现 `next(messages, listener)` 支持或兼容流式输出。
- 把 ChatMessage 转换为目标模型 API 格式。
- 把模型输出转成 AgentStep。

AgentLoop 和工具系统无需修改。

### 9.2 新增内置工具

新增类实现 `ToolDefinition`：

- 定义 name、description、inputSchema。
- 在 run 中解析 JsonNode。
- 使用 ToolContext 获取 cwd 和 permissions。
- 注册到 DefaultTools。

如果是写操作，建议走 `FileReviewService.reviewAndWrite`。

### 9.3 新增 MCP Server

用户通过 `mcp add` 或配置 `.mcp.json`，McpService 会发现工具并包装为 ToolDefinition。无需改 Java 代码。

### 9.4 替换记忆检索

保留 MemoryManager 的对外接口，把内部相关性排序替换为：

- embedding 生成。
- 本地向量库。
- BM25。
- 混合检索。

## 10. 项目不足与改进方向

### 10.1 模型适配还比较集中

当前 AnthropicModelAdapter 同时负责：

- 请求构造。
- 重试。
- 流式解析。
- 协议转换。

后续可以拆成：

- RequestBuilder。
- StreamingParser。
- MessageMapper。
- RetryPolicy。

好处是单测更细，后续多模型更容易。

### 10.2 上下文压缩目前是启发式摘要

CompactService 当前用 excerpt 拼摘要，不是真正调用模型总结。优点是离线、稳定、便宜；缺点是摘要质量有限。

后续可以：

- 支持模型辅助摘要。
- 对工具结果、用户意图、文件改动分别结构化总结。
- 保留关键决策和 TODO。

### 10.3 权限危险命令规则还需要持续完善

目前覆盖了常见危险操作，但 shell 组合命令很复杂，例如删除、移动、重定向覆盖等场景需要更深入分析。

后续可以：

- 解析 shell AST。
- 按命令段分别审批。
- 对 rm/del/move 等文件破坏型命令做路径级审批。

### 10.4 TUI 文件较大

TuiApp 当前承担了事件处理、状态管理、渲染拼装、命令处理等多种职责。

后续可以拆分：

- InputController。
- SlashCommandController。
- SessionPicker。
- ApprovalDialog。
- TranscriptRenderer。
- TuiState。

### 10.5 MCP Client 生命周期可以优化

当前很多 MCP 操作是短连接式启动 server、initialize、请求、关闭。简单可靠，但性能不是最优。

后续可以：

- 做连接池。
- 缓存 initialize 后的 client。
- 后台健康检查。
- server capability 缓存。

## 11. 面试讲述主线

建议按下面顺序讲，比较容易让面试官跟上：

1. 项目动机：Java 生态缺少 AI Coding Agent，想做一个 JVM 版。
2. 总体架构：CLI/TUI -> AgentLoop -> ModelAdapter -> ToolRegistry -> Tools/MCP。
3. 核心闭环：模型可以返回 tool_use，AgentLoop 执行工具并把结果回填，直到 final。
4. 安全设计：写文件 diff review、危险命令审批、持久规则、deny feedback。
5. 上下文治理：token 估算、微压缩、自动压缩、大工具结果外置。
6. 扩展能力：工具接口、MCP backed tools、Skills、Memory。
7. 工程质量：76 个测试覆盖核心状态机、权限、MCP、Session、Context。
8. 反思改进：模型适配拆分、TUI 拆分、权限规则增强、模型辅助压缩。

## 12. STAR 示例

### 12.1 AgentLoop

S：普通聊天模型无法直接完成编码任务，必须能读文件、改文件、跑命令。

T：设计一个能多步调用工具的 Agent 核心。

A：抽象 ChatMessage、AgentStep、ModelAdapter、ToolRegistry。AgentLoop 每步调用模型，识别 final/progress/tool_use，执行工具并回填 tool_result，同时做上下文压缩和最大步数保护。

R：CLI/TUI 共用这套核心逻辑，测试中 MockModel 也能验证完整工具闭环。

### 12.2 权限系统

S：AI Agent 具备本地文件和 shell 能力后，误操作风险很高。

T：在不严重牺牲体验的前提下加安全边界。

A：写文件统一走 diff review，危险命令按规则分类审批，支持 allow once/turn/always 和 deny with feedback，权限持久化到用户目录。

R：用户既能让 Agent 完成任务，又能控制高风险动作。

### 12.3 上下文压缩

S：Agent 多轮调用工具后，文件内容和命令输出会快速占满上下文。

T：降低 token 占用，同时保留任务连续性。

A：旧工具结果微压缩，大工具结果落盘并放预览，critical 时插入 summary message，provider usage 缺失时本地估算。

R：长会话可以继续运行，模型仍然知道上下文发生过压缩。

## 13. 面试问题库（至少 50 个）

### 13.1 项目整体

1. 你这个项目解决了什么问题？
2. 为什么要用 Java 实现 AI Coding Agent，而不是 Python 或 TypeScript？
3. 项目的核心架构是什么？
4. 你认为这个项目最有技术含量的模块是什么？
5. CodeAuto 和普通 ChatGPT Wrapper 的区别是什么？
6. CodeAuto 和 Claude Code 这类工具的相似点和差异是什么？
7. 项目的主要用户是谁？
8. 这个项目如何体现工程化，而不是 Demo？
9. 你在项目里做过哪些架构取舍？
10. 如果只能讲一个亮点，你会讲什么？

### 13.2 AgentLoop 和模型调用

11. AgentLoop 的职责是什么？
12. AgentLoop 为什么需要最大步数限制？
13. 模型返回 tool_use 后，系统是怎么执行工具并继续的？
14. progress message 和 final response 有什么区别？
15. 为什么需要处理模型空响应？
16. AgentLoop 如何和 CLI/TUI 解耦？
17. AgentLoopListener 的作用是什么？
18. 为什么要保留 AssistantRawMessage？
19. Anthropic 的 tool_use 和内部 ToolCall 是怎么映射的？
20. 如果以后接 OpenAI，你会怎么改？
21. 流式输出是如何实现的？
22. SSE 解析时有哪些边界情况？
23. 为什么工具结果要作为 user message 回填给模型？
24. ProviderUsage 在项目里有什么作用？
25. MockModelAdapter 的价值是什么？

### 13.3 工具系统

26. ToolDefinition 为什么要包含 JSON Schema？
27. ToolRegistry 如何处理重复工具？
28. 内置工具和 MCP 工具如何统一？
29. 新增一个工具需要改哪些地方？
30. 工具执行异常如何处理？
31. 为什么工具入参用 JsonNode 而不是强类型 DTO？
32. 文件编辑工具之间有什么区别？
33. PatchFileTool 是如何应用 patch 的？
34. 为什么写文件后要返回 unified diff？
35. RunCommandTool 为什么既支持字符串命令又支持 args 数组？
36. 为什么 shell snippet 要单独识别？
37. Windows 命令兼容做了哪些处理？
38. 后台任务适合处理什么场景？
39. WebSearchTool 为什么需要代理 URL 配置？
40. AskUserTool 在 Agent 场景中的意义是什么？

### 13.4 权限和安全

41. 项目有哪些安全边界？
42. PermissionManager 的核心设计是什么？
43. 哪些命令被认为是危险命令？
44. allow once、allow turn、allow always 有什么区别？
45. deny with feedback 为什么重要？
46. 权限规则如何持久化？
47. glob 规则是如何匹配的？
48. 为什么读 workspace 默认允许？
49. 如果模型想删除文件，当前系统如何处理？
50. 当前权限系统还有什么不足？
51. 如果命令是 `sh -lc "rm -rf target"`，你会如何增强识别？
52. 如何防止模型越权写 workspace 外部文件？

### 13.5 上下文和存储

53. 为什么 Agent 项目容易出现上下文爆炸？
54. TokenEstimator 是做什么的？
55. MicroCompactService 的策略是什么？
56. CompactService 和 MicroCompactService 的区别是什么？
57. ToolResultStorage 为什么要把大结果落盘？
58. 为什么不直接截断工具输出？
59. 上下文压缩会带来什么风险？
60. 如何改进当前压缩策略？
61. 会话为什么用 JSONL 保存？
62. compact boundary 是什么？
63. Session 恢复时为什么只加载最后一个 compact boundary 后的消息？
64. 为什么 memory 用 Markdown + frontmatter？
65. 记忆检索目前是如何打分的？

### 13.6 MCP 和扩展

66. 什么是 MCP？为什么要支持它？
67. MCP 工具如何变成 CodeAuto 的工具？
68. stdio MCP 的 Content-Length frame 是怎么处理的？
69. 为什么要支持 newline-json？
70. protocol auto fallback 的意义是什么？
71. MCP server 失败为什么不能阻塞本地工具？
72. HTTP MCP headers 和 token 是怎么处理的？
73. Skills 和 MCP 的区别是什么？
74. 多级 CLAUDE.md 指令加载解决了什么问题？
75. 如果多个指令冲突，项目如何处理？

### 13.7 TUI 和交互

76. 为什么要做全屏 TUI，而不是只做 CLI？
77. TUI 的主要难点是什么？
78. JLine 在项目里承担什么职责？
79. 中文字符宽度为什么需要特殊处理？
80. TUI 如何处理模型流式输出？
81. TUI 如何处理权限审批？
82. Slash command 菜单如何提升效率？
83. 为什么 transcript 渲染要做缓存？
84. 如何处理终端 resize？
85. TUI 当前有哪些可重构点？

### 13.8 测试和工程质量

86. 项目测试覆盖了哪些模块？
87. 为什么 AgentLoop 适合单元测试？
88. MCP 协议如何测试？
89. 权限规则如何测试？
90. RunCommandTool 测试的重点是什么？
91. TUI 为什么难做完整单测？
92. 你如何保证文件编辑工具安全？
93. 如果线上用户反馈 session 恢复错乱，你怎么排查？
94. 如果模型一直调用工具不结束，你怎么处理？
95. 如果工具结果太大导致模型表现下降，你怎么优化？

### 13.9 设计取舍和开放问题

96. 为什么没有直接用 LangChain？
97. 为什么不用数据库保存会话？
98. 为什么不默认启用向量数据库做记忆？
99. 为什么配置优先级要分层？
100. 如果让你重构 AnthropicModelAdapter，你会怎么拆？
101. 如果让你增强权限系统，你会做哪些事？
102. 如果要支持团队协作，你会改哪些模块？
103. 如果要做插件市场，你会基于 MCP 还是 Skills？
104. 如果要提升 TUI 性能，你会从哪里入手？
105. 这个项目最大的技术风险是什么？

## 14. 部分高频问题参考回答

### Q1：这个项目的核心是什么？

核心是 AgentLoop。它把一次模型调用扩展成多步执行闭环：模型可以请求工具，系统执行工具，把结果回填给模型，模型再决定下一步，直到 final response。围绕这个闭环，我实现了工具注册、权限审批、上下文压缩、会话保存和 UI 展示。

### Q2：为什么说它不是简单调用大模型？

简单调用大模型只能返回文本。CodeAuto 能操作真实开发环境，包括读文件、搜索、改文件、跑命令、接 MCP 工具、保存会话和记忆。并且它有安全审批和上下文治理，能支撑长任务。

### Q3：你怎么保证安全？

我主要做了三层：第一，文件写入统一走 PermissionManager，并返回 diff；第二，危险命令需要审批，例如 `git reset --hard`、`git clean`、`git push --force`、`npm publish`、脚本解释器等；第三，权限支持 turn/always 规则和 deny with feedback，让用户既能控制风险，也不至于每一步都被打断。

### Q4：上下文满了怎么办？

我没有只做截断，而是分层处理：旧工具输出先 microcompact，只保留最近几个；单个或批量工具结果过大时落盘，prompt 里放路径和预览；达到 critical 时，把中间历史压缩成 ContextSummaryMessage，保留 system 和尾部上下文。

### Q5：MCP 是怎么接入的？

McpService 读取用户级和项目级 MCP 配置，发现 server 后 list tools，再把每个 MCP tool 包装成 McpBackedTool，实现同一个 ToolDefinition 接口。这样 AgentLoop 和 ToolRegistry 不需要知道工具来自内置还是 MCP。

### Q6：为什么用 JSONL 保存 session？

因为会话天然是事件流，JSONL 追加写非常适合。每条消息一行，保存时只追加新增消息，不需要重写整个文件。出问题也方便人工检查。相比数据库，它更轻量；相比单个 JSON，它更抗中断。

### Q7：TUI 的难点是什么？

TUI 没有浏览器布局能力，所有渲染都要自己算，包括 ANSI 控制、光标位置、滚动、中文宽度、resize、流式刷新和用户输入并发状态。我的做法是把 AgentLoop 和 TUI 解耦，TUI 通过 listener 接收事件，只负责展示和交互。

### Q8：如果接入 OpenAI 模型怎么做？

新增一个 OpenAIModelAdapter，实现 ModelAdapter，把内部 ChatMessage 转换为 OpenAI 的 messages/tools 格式，再把 OpenAI 的 tool calls 转回 AgentStep.ToolCallsStep。AgentLoop、ToolRegistry、权限系统都不用改。

### Q9：当前项目最大不足是什么？

我认为主要有三点：AnthropicModelAdapter 职责偏多，可以拆请求构造、流式解析和消息映射；TuiApp 文件较大，可以拆 controller 和 renderer；权限系统对复杂 shell 命令还不够深入，可以引入 shell AST 或命令段级审批。

### Q10：你从这个项目里学到了什么？

我最大的收获是 Agent 产品的难点不只是“调模型”，而是模型、工具、安全、上下文和交互之间的系统工程。尤其是工具结果回填、权限边界、长上下文治理和 UI 事件流，这些才决定一个 Agent 能不能真实可用。

## 15. 简历描述建议

可以写成：

> CodeAuto：基于 Java 21 实现的 AI Coding Agent 运行时，支持 CLI/TUI、Anthropic Messages API、模型工具调用、文件编辑 diff review、危险命令权限审批、会话 JSONL 持久化、上下文压缩、MCP 工具扩展、Skills 和持久化记忆。负责核心 AgentLoop 状态机、工具注册体系、权限模型、MCP stdio/http 协议适配、TUI 交互与测试体系建设，完成 76 个 JUnit 测试覆盖核心模块。

更面试导向版本：

> 设计并实现 Java 版 AI Coding Agent，抽象 ModelAdapter、ToolDefinition、AgentLoop 等核心接口，实现大模型 tool_use 多步执行闭环；通过 PermissionManager 和 FileReviewService 实现危险命令审批与文件 diff 审查；通过 MicroCompact、Compact 和 ToolResultStorage 解决长会话上下文膨胀；支持 MCP stdio/http 动态工具接入和 JSONL 会话恢复。

## 16. 项目答辩时可以强调的关键词

- AgentLoop 状态机
- 工具调用闭环
- ModelAdapter 解耦模型供应商
- ToolDefinition + JSON Schema
- PermissionManager 安全边界
- deny with feedback
- Unified diff review
- Token 估算和上下文压缩
- 大工具结果外置
- JSONL event sourcing
- MCP backed tools
- stdio frame fallback
- JLine TUI
- CJK display width
- MockModel 可测试性
- Java 21 sealed interface 和 records

## 17. 建议演示路径

面试如果需要现场演示，可以按这个顺序：

1. `mvn test` 展示测试。
2. `mvn exec:java "-Dexec.args=--mock --tui"` 展示离线 TUI。
3. `/tools` 展示工具列表。
4. `/status` 展示上下文和 session。
5. `/ls`、`/read pom.xml` 展示本地快捷命令。
6. 让模型执行一个简单读文件任务，展示 tool_use 和 tool_result。
7. 展示写文件时的权限审批和 diff。
8. `/sessions`、`/resume` 展示会话管理。
9. `/mcp` 展示 MCP 扩展入口。

## 18. 结论

CodeAuto 的核心价值在于把 AI Coding Agent 的关键工程问题系统化落到了 Java 生态：模型适配、工具调用、安全审批、上下文治理、扩展协议、会话存储和终端交互都形成了闭环。面试时不建议只说“我做了一个 AI 助手”，而要重点讲“我实现了一个可扩展、可控、可恢复的 Agent 运行时”。

