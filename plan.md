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
