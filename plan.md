# CodeAuto — 完成度评估与后续计划

## 项目概览

| 指标 | 数值 |
|------|------|
| Java 源文件 | 66 个 |
| 测试文件 | 14 个 |
| 测试数量 | 50 个（全部通过 ✅） |
| 代码行数 | ~6664 行 |
| TUI 源文件 | 5 个（Ansi/PanelRenderer/MarkdownRenderer/TranscriptEntry/TuiApp） |
| 技术栈 | Java 21 + Maven + Jackson + Picocli + JLine + java-diff-utils |

## 各阶段完成度

### Phase 1：核心运行时 ✅ 完成

- [x] Maven 项目结构 + Picocli CLI 入口
- [x] 核心类型：`ChatMessage`、`ToolCall`、`AgentStep`、`ProviderUsage`、`CompressionResult`
- [x] `AgentLoop`：支持 final response、tool call 循环、tool result 回填、progress message、空响应重试、最大步骤限制
- [x] `AgentLoopListener`：工具生命周期回调（onToolStart/onToolResult/onProgressMessage/onAutoCompact）
- [x] `MockModelAdapter`：离线可跑完整 loop

### Phase 2：内置工具 ✅ 完成

- [x] 14 个内置工具全部实现：
  - 文件操作：`list_files`、`grep_files`、`read_file`、`write_file`、`edit_file`、`patch_file`、`modify_file`
  - 执行与交互：`run_command`、`ask_user`、`background_tasks`
  - 网络：`web_fetch`、`web_search`
  - 扩展：`load_skill`、`mcp_helper`
- [x] 工具输入使用 Jackson POJO/JsonNode 校验
- [x] 文件写入类工具接入 diff review
- [x] `DefaultTools` 统一注册

### Phase 3：模型适配器 ✅ 完成

- [x] `ModelAdapter` 接口 + `AnthropicModelAdapter` + `MockModelAdapter`
- [x] Anthropic Messages API：system prompt、消息转换、tool definitions、text/tool_use 解析、usage 解析
- [x] 429/5xx 重试 + exponential backoff + `Retry-After`
- [x] 配置：model name、base URL、auth token、max output tokens、retry limit

### Phase 4：权限与文件 Review ✅ 完成

- [x] 路径、命令、编辑三类权限请求
- [x] 权限决策：allow once/always/turn、deny once/always/with-feedback
- [x] `PermissionStore` 持久化到 `~/.codeauto/`
- [x] `FileReviewService`：文件修改前生成 unified diff，终端 review 确认
- [x] 危险命令检测

### Phase 5：会话、上下文与压缩 ✅ 完成

- [x] JSONL append-only session，按工作目录隔离
- [x] resume、fork、rename、new、compact
- [x] 标签式 compact boundary（load 时只读最新 segment）
- [x] 会话过期清理
- [x] `ContextStats` + `TokenEstimator`（本地 token 估算）
- [x] `CompactService` 自动压缩 + `MicroCompactService` 微压缩
- [x] `ToolResultStorage`：超大工具结果落盘，只保留 preview
- [x] `ProviderUsage` 优先，本地 estimate 作为 fallback

### Phase 6：JLine TUI（原 Lanterna/Swing 已替换）✅ 完成（P1 功能已补齐）

2026-04 重构：移除 Lanterna `TerminalChatApp`、Swing `SwingChatApp`、`TuiStatusApp`，替换为纯 JLine 3 全屏 TUI。

- [x] `Ansi.java` — ANSI 转义码常量和工具（CJK 显示宽度、颜色常量、屏幕管理）
- [x] `PanelRenderer.java` — 面板绘制（╭─╮ 边框、标题居左、右标题、自动换行）
- [x] `MarkdownRenderer.java` — Markdown → ANSI 彩色渲染
- [x] `TranscriptEntry.java` — 对话条目类型（User/Assistant/Tool/Progress）
- [x] `TuiApp.java` — 主应用：
  - [x] Alternate screen + raw mode
  - [x] Header 面板（模型名、session ID、上下文用量徽章）
  - [x] Session feed 面板（滚动对话、自动滚动、滚动指示器）
  - [x] Prompt 面板（输入行 + 视觉光标 `Ansi.REVERSE`）
  - [x] Footer 状态栏（Ready/Thinking + tools/skills/mcp + 后台 shell 数量）
  - [x] 键盘导航（方向键、PageUp/Down、Home/End、Backspace/Delete）
  - [x] CJK 中文输入支持
  - [x] 输入历史（内存中上/下翻）
  - [x] 斜杠命令 Tab 补全（/help, /tools, /skills, /sessions, /status 等）
  - [x] 工具条目展开/折叠（Ctrl+O）
  - [x] 权限审批弹窗（上/下选择 + Enter 确认 + Esc 拒绝）
  - [x] 权限反馈模式（选择 "Deny with Feedback" 后文本输入框）
  - [x] 交互式斜杠菜单（输入 `/` 弹出可选列表，方向键导航，Enter 填充）
  - [x] 交互式 session 选择器（`/resume` 无参时交互列表，支持删除、跨项目浏览）
  - [x] AgentLoop 回调集成（工具启动/结果、进度、助手消息、自动压缩）
  - [x] Ctrl+C 退出
- [x] `CodeAutoCli.java` — `--tui` 标志启动 TUI

### Phase 7：Skills 与 MCP ✅ 完成

- [x] Skills 扫描 `.code-auto/skills` + `.claude/skills`
- [x] `SkillService.discover()` + `LoadSkillTool`
- [x] `SkillsCommand`（list/add/remove）
- [x] MCP stdio client：Content-Length 帧 + newline JSON 帧 + 协议自动协商
- [x] `McpService`：用户级 + 项目级 `.mcp.json` 配置加载
- [x] MCP 工具自动发现并包装为 `ToolDefinition`
- [x] MCP resources/prompts helper tools（`list_mcp_resources`、`read_mcp_resource`、`list_mcp_prompts`、`get_mcp_prompt`）
- [x] `McpCommand`（list/add/login/logout/remove）+ token 存储
- [x] 环境变量插值（`$VAR` → 系统环境变量）
- [x] Bearer token 注入
- [x] Streamable HTTP MCP 传输（`McpHttpClient`，自动识别 `url` 字段）

### Phase 8：配置与启动 ✅ 完成

- [x] 多级配置加载链：默认值 → 环境变量 → 项目 settings.json → 用户 settings.json → CLI 标志
- [x] `RuntimeConfig.merge()` / `withXxx()` 分层覆盖模式
- [x] CLI 标志：`--model`、`--cwd`、`--max-tokens`、`--tui`、`--mock`、`--resume`、`--fork`
- [x] `codeauto` 启动脚本（Unix shell + Windows batch）
- [x] 首次启动自动构建 fat JAR

## TUI 功能对比：Java vs TypeScript

### 已有功能 ✅

| 功能 | TypeScript | Java | 备注 |
|------|-----------|------|------|
| 全屏 Alternate Screen | ✅ | ✅ |  |
| 面板布局（Header/Transcript/Prompt/Footer） | ✅ | ✅ |  |
| CJK 文字显示宽度计算 | ✅ | ✅ | `Ansi.charDisplayWidth()` |
| Markdown → ANSI 渲染 | ✅ | ✅ | 代码块、表格、标题、列表、内联代码 |
| 对话滚动 + 滚动指示器 | ✅ | ✅ |  |
| 上下文用量徽章 | ✅ | ✅ | 彩色百分比 + 警告级别 |
| 工具条目展开/折叠 | ✅ | ✅ | Ctrl+O |
| 权限审批弹窗 | ✅ | ✅ | 键盘选择 |
| 斜杠命令补全 | ✅ | ✅ | Tab 补全 |
| 输入历史 | ✅ | ✅ | Up/Down（内存中） |
| CJK 中文输入 | ✅ | ✅ | 首次支持 |
| Ctrl+U / Ctrl+A / Ctrl+E / 方向键 | ✅ | ✅ |  |
| 鼠标滚轮滚动 | ✅ | ✅ | 未使用 SGR 模式，依赖终端行为 |
| 会话管理（/resume /fork /new） | ✅ | ✅ |  |
| 会话压缩（/compact /auto-compact） | ✅ | ✅ |  |
| 隐藏终端光标，使用渲染光标 | ✅ | ✅ | `Ansi.REVERSE` |
| 交互式斜杠菜单 | ✅ | ✅ | 输入 `/` 弹出列表，方向键选择，Enter 填充 |
| 交互式 session 选择器 | ✅ | ✅ | `/resume` 无参时弹出交互列表，支持删除、跨项目浏览 |
| 权限反馈模式 | ✅ | ✅ | "Deny with Feedback" 后弹出文本输入框 |
| 后台任务状态展示 | ✅ | ✅ | Footer 显示运行中的 shell 数量 |
| 权限快捷键（P2） | ✅ | ✅ | `y`/`n`/`1`-`7` 直接选择 |
| 差异高亮 word-level（P2） | ✅ | ✅ | diff 内加粗显示变化词 |
| 历史持久化（P2） | ✅ | ✅ | 保存到 `~/.codeauto/history.jsonl` |
| Compact 标识（P2） | ✅ | ✅ | Footer 显示 "ctx -X% (saved Y tokens)" |
| 工具面板（P3） | ✅ | ✅ | 当前运行工具 + 最近工具列表（ok/err） |
| 跨项目 session 浏览器（P3） | ✅ | ✅ | Tab 切换到所有项目视图 |
| Session fork 自动命名（P3） | ✅ | ✅ | `<title>_fork<N>` |
| Meta+Up/Down 滚动（P3） | ✅ | ✅ | Alt+Up/Alt+Down 滚动 transcript |
| 上下文感知 Ctrl+A/E（P3） | ✅ | ✅ | 输入为空时滚动到 transcript 顶部/底部 |
| SGR 鼠标模式（P3） | ✅ | ✅ | 精确鼠标事件 + 滚轮支持 |

### 缺失功能

无。全部 P1-P3 功能已完成。

## 当前优化计划（2026-05-02）

### TUI 滚动与布局体验优化

- [x] 修复鼠标滚轮在 Windows Terminal / PowerShell 下可能无法滚动的问题：增强 SGR mouse tracking，并把 ESC 序列读取改为等待完整终止符。
- [x] 修复 PageUp/PageDown、Alt/Ctrl+方向键滚动后 UI 边框可能“散掉”的问题：聊天记录先按可视宽度换行，再做滚动切片，避免长行二次换行导致总输出高度超过终端。
- [x] 优化 TUI 可视空间：压缩 Header 内容，把更多高度留给 session feed。
- [x] 优化面板密度与窄窗口稳定性：移除面板标题后的固定空行，面板宽度跟随真实终端宽度，Footer 超长内容会截断。
- [x] 改善重绘稳定性：每次从屏幕左上角清到末尾后再绘制，减少旧边框残留。
- [x] 完成测试验证后，将本轮修复记录补充到 `docs/fix-report-2026-05-01.md`。

## 测试状态

```
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 测试覆盖领域

| 测试 | 测试数 | 覆盖内容 |
|------|--------|---------|
| AgentLoopTest | 3 | 完整工具调用闭环 + 自动压缩 + 监听器事件 |
| ToolRegistryTest | 3 | 注册、查找、列表 |
| SessionStoreTest | 9 | save/load/list/rename/compact/cleanup/transcript + 多压缩边界 |
| ContextTest | 5 | TokenEstimator + CompactService + MicroCompact + ToolResultStorage |
| RunCommandToolTest | 7 | 命令执行、引号解析、shell 片段、超时控制、反馈拒绝 |
| PermissionManagerTest | 3 | 决策流程 |
| FileReviewServiceTest | 1 | unified diff 生成 |
| McpClientTest | 2 | JSON-RPC 帧读写 |
| McpServiceTest | 7 | 服务发现与协议协商 |
| McpBackedToolTest | 1 | MCP 工具包装 |
| McpHelperToolTest | 2 | resources/prompts 辅助工具 |
| BackgroundTaskTest | 3 | 后台任务启动/列表/取消/不存在检查 |
| ManagementStoreTest | 3 | 配置读写 |
| SkillServiceTest | 1 | skills 发现 |

## 技术选型

- **Java 21** + **Maven** — 单体结构，不拆分多模块
- **Jackson** — JSON/JSONL 序列化
- **Java HttpClient** — 模型 API 调用
- **Picocli** — CLI 框架
- **JLine 3** — 终端控制（raw mode、Reader、窗口大小检测）
- **java-diff-utils** — unified diff 生成
- **JUnit 5** — 测试
- 包名：`com.codeauto`
- 命令名：`codeauto`
- 配置目录：`~/.codeauto/`
- 不引入 Spring/Quarkus 等重型框架
- 纯 ANSI 转义序列渲染（无第三方 TUI 框架）
## FuturePlan 优化追加记录（2026-05-02）

### 第一阶段：TUI 即时体验优化

- [x] 增加 TUI 本地工具快捷命令，简单文件和命令操作不再需要进入 AgentLoop：
  - `/ls [path]`：直接调用 `list_files`
  - `/grep <pattern>::[path]`：直接调用 `grep_files`
  - `/read <path>`：直接调用 `read_file`
  - `/write <path>::<content>`：直接调用 `write_file`，保留 diff review
  - `/modify <path>::<content>`：直接调用 `modify_file`，保留 diff review
  - `/edit <path>::<search>::<replace>`：直接调用 `edit_file`，保留 diff review
  - `/patch <path>::<search>::<replace>...`：批量替换后复用 `modify_file` 写入 review
  - `/cmd <command>`：直接调用 `run_command`
- [x] 增强 `/model`：支持 `/model <name>` 在 TUI 内切换当前模型，并持久化写入 `~/.codeauto/settings.json`；切换后重建 `AgentLoop`，后续对话立即使用新模型。
- [x] 增加 TUI `/mcp`：展示已配置 MCP server 的连接/错误状态、传输类型和工具数量，便于在 TUI 内调试 MCP。
- [x] 增加 `ConfigLoader.writeUserSettings()`，为 TUI 内模型切换提供用户级 settings 写回能力。
- [x] 增加配置写回测试，确保写入的用户 settings 可被 `ConfigLoader` 重新加载。

### 验证结果

```
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第二批）

### 第二阶段：多级指令加载

- [x] 增加 `InstructionLoader`，启动时自动加载多级 Markdown 指令：
  - `~/.claude/CLAUDE.md`：用户级通用指令
  - `~/.codeauto/CLAUDE.md`：CodeAuto 应用级指令
  - `<project>/CLAUDE.md`：项目级指令
  - `<project>/CLAUDE.local.md`：项目本地私有指令
- [x] 将加载到的指令注入 system prompt 的 `<system-reminder>` 区域，并明确“越靠后的本地指令优先级越高”。
- [x] CLI 和 TUI 的新会话、恢复会话入口统一使用 `InstructionLoader.systemPrompt()` 构建系统提示，避免硬编码 system prompt 分散在多个位置。
- [x] 增加指令加载测试，覆盖加载顺序、无指令文件时保持原始紧凑 system prompt 的行为。

### 验证结果

```
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第三批）

### 第二/三阶段：细粒度权限通配规则

- [x] 增强 `PermissionManager`，让现有 `allowedCommandPatterns` / `deniedCommandPatterns` 真正支持通配规则。
- [x] 支持 `Bash(<pattern>)` / `Command(<pattern>)` / `RunCommand(<pattern>)` 形式的命令规则，例如 `Bash(python scripts/*)`、`Bash(git push --force*)`。
- [x] 增强编辑权限规则，支持 `Edit(<path-pattern>)` / `Write(...)` / `Modify(...)` 等形式，例如 `Edit(src/*.java)`。
- [x] 保持旧有精确命令、精确路径和目录前缀权限兼容，不改变 `permissions.json` 的字段结构。
- [x] 增加权限通配测试，覆盖允许危险命令、拒绝命令、允许编辑路径、拒绝编辑路径等场景。

### 验证结果

```
Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第四批）

### 低优先级补齐：`/permissions` 命令

- [x] 增加 `PermissionStore.path()`，暴露当前权限文件路径用于诊断展示。
- [x] 增加 `PermissionManager.describePermissions()`，统一输出权限文件路径、workspace、持久化规则数量和 turn-scoped 临时权限数量。
- [x] CLI 增加 `/permissions`，可直接查看权限存储位置和规则概览。
- [x] TUI 增加 `/permissions` 斜杠菜单项和命令处理，和 CLI 使用同一份权限摘要。
- [x] 增加权限摘要测试，覆盖路径展示和规则计数。

### 验证结果

```
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第五批）

### 第二阶段：持久化记忆系统最小闭环

- [x] 新增 `MemoryType`，支持 `user`、`feedback`、`project`、`reference` 四类记忆。
- [x] 新增 `MemoryEntry`，统一描述记忆 id、类型、标题、项目路径、标签、创建/更新时间、正文和文件路径。
- [x] 新增 `MemoryManager`，默认使用 `~/.codeauto/memory/` 存储 frontmatter Markdown 记忆文件。
- [x] 支持记忆保存、列表、删除和相关性检索；检索优先匹配当前 workspace，再结合项目名/查询关键词命中排序。
- [x] 将相关记忆注入 `InstructionLoader.systemPrompt()` 的 `<system-reminder>` 区域，最多注入 5 条，并对超过 24 小时未更新的记忆标记 `[stale]`。
- [x] 增加记忆系统测试，覆盖 Markdown 保存/读取/删除、项目相关检索、system prompt 记忆注入和 stale 标记。

### 验证结果

```
Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第六批）

### 第二阶段：记忆管理工具与命令

- [x] 新增 `MemoryTool`，注册 `save_memory`、`list_memory`、`delete_memory` 三个内置工具，让模型可以主动保存、查询和删除持久化记忆。
- [x] `DefaultTools` 默认注册记忆工具，和现有文件、命令、MCP helper 工具走同一套 `ToolRegistry`。
- [x] CLI 增加 `/memory` 命令：
  - `/memory list [query]`
  - `/memory add <type>::<title>::<content>`
  - `/memory delete <id>`
- [x] TUI 增加 `/memory` 斜杠菜单、帮助文本和命令处理，用户可在全屏界面直接管理记忆。
- [x] 增加工具层测试，覆盖 `save_memory`、`list_memory`、`delete_memory` 的完整闭环。

### 验证结果

```
Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Bug 修复记录（2026-05-02）

### CLI 中文输入编码修复

- [x] 修复普通 CLI 模式下中文输入可能被错误字符集解码，导致发送给模型的消息变成乱码的问题。
- [x] 普通 CLI 输入读取优先切换为 JLine `LineReader`，和 TUI 共享更可靠的终端输入处理能力。
- [x] JLine 初始化失败时回退到 Scanner；Scanner stdin 字符集选择顺序为：`-Dcodeauto.cli.charset`、`CODEAUTO_CLI_CHARSET`、真实 console charset、`native.encoding`、JVM 默认 charset。
- [x] 为 Maven exec / IDE 等 `System.console()` 为空的 fallback 场景增加 `native.encoding`。
- [x] 增加 CLI 编码策略测试，覆盖显式覆盖和 fallback。
- [x] README 增加 CLI 中文输入编码排查说明。

### 验证结果

```
Tests run: 73, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Bug 修复记录（2026-05-02，第二批）

### bin 启动默认 workspace 修复

- [x] 修复 Windows `bin/codeauto.bat` 在 JAR 已存在时不会切换到项目根目录，导致默认 workspace 变成 `CodeAuto/bin` 的问题。
- [x] CLI 默认 cwd 解析增加 bundled `bin` 目录识别：当当前目录是项目根下的 `bin`，且父目录包含 `pom.xml` 和 CodeAuto 源码结构时，自动提升 workspace 到父项目根。
- [x] 增加回归测试，覆盖 `CodeAuto/bin` 自动解析为项目根。

### 验证结果

```
Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 文档更新记录（2026-05-02）

### README 与 about-me 同步

- [x] 重写 `README.md`，补齐当前 CodeAuto 的 CLI/TUI、工具、会话、权限、记忆、多级指令、Skills、MCP、测试状态和常见问题说明。
- [x] 重写 `about-me.md`，更新项目定位、架构模块、核心运行流程、TUI 体验、工具系统、权限模型、记忆系统和后续演进方向。
- [x] 文档补充近期修复：CLI 中文输入编码、Windows `bin` 启动 workspace、JLine deprecated provider 警告隐藏、TUI 斜杠菜单限高和长文本换行优化。

## 体验优化记录（2026-05-02）

### AI 回复流式输出

- [x] 扩展 `ModelAdapter`，新增兼容式流式入口 `next(messages, listener)`，默认回退到原一次性响应，保持 Mock 和测试模型兼容。
- [x] 扩展 `AgentLoopListener.onAssistantDelta()`，让模型适配器可以在最终 `AgentStep` 返回前推送文本增量。
- [x] `AnthropicModelAdapter` 接入 Messages API `stream=true`，解析 SSE 中的 `text_delta`，并保留最终完整内容用于会话保存和工具调用循环。
- [x] TUI 流式刷新同一条 assistant transcript，避免每个 delta 生成一条新消息。
- [x] CLI 模式边接收边打印文本，并避免最终回复重复输出。
- [x] 增加 AgentLoop 流式 delta 测试，覆盖流式事件和最终消息保存。

### 验证结果

```
Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 页面优化记录（2026-05-02）

### instruction 展示页视觉优化

- [x] 重写 `instruction/index.html`，保留 CodeAuto 原有深色终端气质，不参考其他项目页面版式。
- [x] 移除展示图片引用，改为纯 HTML/CSS 的 TUI 终端示意，突出真实工作流、命令、状态和测试结果。
- [x] 优化首屏标题、导航、按钮、终端示意、能力卡片、启动步骤、记忆说明和架构模块区，让页面更专业、克制。
- [x] 保持页面为单文件静态 HTML，不引入图片依赖或额外构建依赖。

## TUI 体验优化记录（2026-05-02）

### 输入光标闪烁

- [x] 为 TUI 增加轻量 `ScheduledExecutorService` 光标刷新器，每 500ms 切换 prompt 光标可见状态。
- [x] 输入光标在反色块和普通字符之间切换，形成动态闪烁效果；TUI 退出时会关闭刷新线程。
- [x] 保持终端真实光标隐藏，继续使用自绘 prompt 光标，避免和 ANSI 面板渲染冲突。

### 验证结果

```
Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Bug 修复记录（2026-05-02，第三批）

### TUI 工具输出 diff 高亮卡死修复

- [x] 修复 TUI 在渲染工具输出 diff 时，极短的删除/新增行可能触发 `StringIndexOutOfBoundsException: Range [1, 0)` 的问题。
- [x] 增强 `wordDiff()` 边界保护，对 `-a`/`+`、`-`/`+b`、`-`/`+` 等极短 diff 行不再抛异常。
- [x] TUI `render()` 改为同步渲染，避免光标闪烁线程、模型流式输出线程和输入线程同时重绘造成状态竞争。
- [x] Prompt 渲染统一 clamp 光标位置，避免输入内容和光标位置短暂不同步时触发 substring 边界异常。
- [x] 增加 TUI diff 高亮回归测试。

### 验证结果

```
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Bug 修复记录（2026-05-02，第四批）

### TUI 长命令/长工具输出闪退防护

- [x] 为 TUI 渲染增加最外层兜底保护：渲染异常不再让全屏界面退出到 PowerShell，而是在 TUI 内显示 render error。
- [x] `AgentLoop` 异步任务和手动压缩任务捕获 `Throwable`，避免长任务或渲染链路中的运行时错误直接终止后台回合。
- [x] `run_command` 前台输出增加 512KB 上限，超过后追加 `[truncated command output ...]`，避免自测类长命令输出过大导致内存或界面压力。
- [x] 保持已有工具结果预览折叠逻辑，完整超长输出仍建议使用后台任务或写入报告文件。

### 验证结果

```
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## FuturePlan 优化追加记录（2026-05-02，第七批）

### 自测报告问题修复

- [x] 修复所有工具的 inputSchema 返回空对象：当前 `WriteFileTool.inputSchema()` 等返回 `{"type":"object"}`，无 required/properties，模型无法通过 schema 获知参数约束。需为每个内置工具补齐准确的 properties 和 required 定义。
- [x] WriteFileTool / ModifyFileTool 增加 content 非空校验：content 为 null 或空字符串时拒绝写入，避免参数缺失时静默覆盖文件。`edit_file` / `patch_file` 也应一并加固参数校验。
- [x] RunCommandTool 跨平台兼容性：Windows 上调用 Linux 命令（head/tail/grep/wc/awk）时直接失败，需要命令可用性预检或友好提示，避免用户困惑。
- [x] web_search 配置体验：当前依赖环境变量 `CODEAUTO_SEARCH_URL` 才能工作，考虑内置默认搜索端点或提供更清晰的首次配置引导。

### TUI 渲染与布局优化

- [x] 修复流式输出时 progress 面板挤占 assistant 生成区域的问题：工具调用长命令时，thinking/progress 指示器不应抢占 streaming text 的显示空间。需确保 assistant 消息面板拥有足够高度渲染流式输出，progress 提示不应导致对话内容被推离可视区。
- [x] 优化 TUI 面板布局：将 tools 面板移到 codeauto 面板下方，让 user 消息和 assistant 消息在视觉上更接近，减少顶部信息面板对对话区域的割裂感。
- [x] 修复 Ctrl+O 在工具结果返回后无法展开/折叠的问题：工具执行结束后，tool entry 的 toggle 状态未正确响应键盘事件，按 Ctrl+O 无反应。需检查 tool entry 列表的焦点/事件分发逻辑。
- [x] 修复工具调用后 Session 框边框渲染出现缺口的问题：每次工具调用后，session 边框线出现断裂/缺口，边框字符被挤入框内。需排查面板重绘时内容更新和边框渲染的顺序/坐标计算。

### 验证结果

```
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## TUI 工具条目折叠优化记录（2026-05-02）

### 工具条目压缩为单行 + Ctrl+O 执行中可用

- [x] 工具条目在 transcript 中默认折叠为单行，只显示 `tool <name> <status>` 和 `[+]` 展开提示，不再占用多行预览空间。
- [x] Ctrl+O 展开/折叠在 `isBusy`（AgentLoop 执行中）也可用，不再被事件循环跳过。先前按键在忙碌时被 `continue` 吞掉，现单独识别 `0x0F` 并调用 `toggleToolExpand()`。
- [x] 展开后显示工具完整 body（保留 diff 高亮），与之前行为一致。
- [x] 移除不再需要的 `TOOL_PREVIEW_LINES` 和 `TOOL_COLLAPSE_CHARS` 常量。

### 验证结果

```
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## TUI 状态行与工具管理优化记录（2026-05-02）

### 问题

1. **Ctrl+O 只能展开最后一个工具条目** — `toggleToolExpand()` 从末尾循环找第一个 tool entry 后 `break`，重复按只在同一个工具上切换。
2. **工具调用间隙挤掉主消息** — 即使工具折叠为 1 行，10 个工具调用 + 分隔符 ≈ 20 行 transcript 空间，占满可视区后将 assistant 回复推出视图。
3. **Assistant/Progress 消息重复** — `onProgressMessage` 和 `onAssistantMessage` 同时生效，Progress 条目残留导致看起来像消息显示了两次。

### 方案

#### 1. 新增 Status 条目 + 动态 spinner

- 新增 `TranscriptEntry.Status` record，在 agent loop 执行期间替代 Tool/Progress 条目。
- 每次重绘一行动态状态，格式：`<spinner> <状态文本>`，例如：
  - `⠋ Thinking...`
  - `⠙ Running web_search...`
  - `⠸ Processed web_search (5 total)`
- 使用 10 帧 spinner 动画（⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏），每 500ms 推进一帧。
- Spinner 推进复用现有 500ms 光标闪烁线程，`isBusy` 时切换为 spinner。

#### 2. 不将 Tool/Progress 条目加入 transcript

- `onToolStart()` / `onToolResult()` 只更新 `statusLineText`+ tools panel，不再添加 `TranscriptEntry.Tool` 条目。
- `onProgressMessage()` 只更新 `statusLineText`，不再添加 `TranscriptEntry.Progress` 条目。
- 执行结束后（`finally` 块）自动清除 Status 条目。
- Tool 状态仍然在独立的 tools panel 展示（运行中工具名、最近 5 个工具 ok/err）。

#### 3. Assistant 开始时自动清除 Status

- `onAssistantDelta()` 流式或 `onAssistantMessage()` 非流式路径，在添加 assistant 条目前清除 Status 条目。
- 避免 Progress 残留导致消息重复感。

#### 4. Ctrl+O 展开/折叠所有工具

- `toggleToolExpand()` 改为遍历全部 tool entries：有任意折叠时展开全部，全部已展开时折叠全部。
- 执行期间 transcript 中无 tool entries（只有 `/cmd` 快捷命令产生的和历史会话恢复的工具条目），故 Ctrl+O 对执行中无影响。

### 实现清单

- [x] `TranscriptEntry` 新增 `Status` record
- [x] `TuiApp.SPINNER_FRAMES` 常量、`statusLineText`/`statusEntryId`/`spinnerFrame` 字段
- [x] `updateStatusLine()` / `clearStatusLine()` 辅助方法
- [x] Cursor blinker `isBusy` 时切换为 spinner 推进
- [x] `onToolStart()` / `onToolResult()` 不再添加 Tool 条目，改为更新 status line
- [x] `onProgressMessage()` 改为更新 status line
- [x] `onAssistantDelta()` / `onAssistantMessage()` 清除 status line
- [x] `submitInput()` 开始时预置 "Thinking..." status line
- [x] `finally` 块清除 status line
- [x] `toggleToolExpand()` 改为遍历所有工具，统一展开/折叠
- [x] `renderTranscriptEntry()` 新增 Status 分支
- [x] 测试：mvn test 全部通过

### 验证结果

```
Tests run: 77, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
