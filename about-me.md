# About CodeAuto

## 项目背景

当前主流的 AI Coding 工具几乎全部基于 TypeScript 或 Python 实现，JVM 生态严重缺位。

对于以 Java 为主力语言的开发者来说，想学习或二次开发 AI Coding Agent，往往需要越过语言壁垒，门槛极高。

CodeAuto 参考 Claude Code 源码的设计思路，融合 MINICODE 的轻量可扩展理念，用 **Java 21** 构建了一个简单、可扩展、贴近 JVM 开发者的 AI 编程代理运行时。

## 项目定位

CodeAuto 的目标不是做一个庞大的 IDE，而是做一个清晰、可审计、可扩展的本地 coding agent runtime：

- 能在普通终端和全屏 TUI 中工作
- 能让模型安全地读文件、改文件、跑命令
- 能保存会话和项目记忆
- 能通过 MCP 和 Skills 扩展能力
- 能用 Java 工程方式测试和维护

## 当前能力概览

| 维度 | 状态 |
| --- | --- |
| 语言 | Java 21 |
| 构建 | Maven |
| CLI | Picocli + JLine 输入 |
| TUI | JLine 3 + ANSI |
| 模型 | Anthropic Messages API + Mock |
| 工具 | 20 个默认内置工具 |
| 会话 | JSONL append-only，按 workspace 隔离 |
| 权限 | 命令/路径/编辑权限，支持通配规则 |
| 记忆 | frontmatter Markdown 持久化记忆 |
| 指令 | 多级 CLAUDE.md 加载 |
| MCP | stdio + Streamable HTTP |
| 测试 | 75 个测试通过 |

## 架构模块

```text
com.codeauto
  background     后台命令任务
  cli            CLI 入口和子命令
  config         多级配置加载
  context        token 估算、压缩和工具结果落盘
  core           AgentLoop、ChatMessage、ToolCall、AgentStep
  instructions   CLAUDE.md 和 system prompt 指令注入
  manage         用户级管理配置读写
  mcp            MCP client/service/backed tool
  memory         持久化记忆系统
  model          ModelAdapter、Anthropic、Mock
  permissions    权限审批和持久化规则
  session        会话保存、恢复、fork、compact boundary
  skills         Skills 发现和加载
  tool           工具接口、注册表和结果类型
  tools          内置工具实现
  tui            全屏终端 UI
```

## 核心运行流程

1. CLI 解析参数并加载配置。
2. 根据配置创建 `ModelAdapter`。
3. 注册本地工具和 MCP backed tools。
4. 创建 `PermissionManager` 和 `SessionStore`。
5. 通过 `InstructionLoader` 构建 system prompt。
6. `AgentLoop` 驱动模型响应、工具调用、工具结果回填和最终回答。
7. 会话追加保存到 `~/.codeauto/projects/`。

## TUI 体验

全屏 TUI 使用 JLine 3 raw mode 和 ANSI 序列绘制，没有引入重量级 UI 框架。

主要能力：

- Header 显示模型、session、workspace 和上下文用量
- Transcript 支持滚动
- Prompt 支持中文输入、历史、光标渲染和 Tab 补全
- Footer 显示状态、后台任务和最近工具结果
- 权限审批弹窗支持快捷键和反馈输入
- `/` 斜杠菜单支持补全，并限制可见行数，避免铺满屏幕
- 长文本优先自动换行，窄窗口下减少不必要的 `...` 截断
- Anthropic 文本回复支持流式输出，TUI 原地刷新，CLI 边收边打印

近期补强：

- SGR mouse 滚轮
- PageUp/PageDown 和 Alt/Ctrl 方向键滚动稳定性
- 本地工具快捷命令
- `/model <name>` TUI 内切换模型并写入 settings
- `/mcp`、`/permissions`、`/memory`
- CLI 中文输入编码修复
- Windows `bin` 启动 workspace 自动回到项目根目录
- JLine deprecated provider 警告默认隐藏
- Assistant streaming delta 事件接入 AgentLoop

## 工具系统

工具接口由 `ToolDefinition` 定义，统一注册到 `ToolRegistry`。默认工具包括：

- 文件读写、搜索、编辑、patch、modify
- 命令执行和后台任务
- Web fetch/search
- Skill 加载
- MCP resources/prompts helper
- 持久化记忆保存、列表、删除

文件写入类工具统一接入 `FileReviewService`，写入前生成 unified diff 并走权限审批。

## 权限模型

CodeAuto 的权限层由 `PermissionManager` 和 `PermissionStore` 组成。

支持：

- workspace 内默认可读
- 编辑前审批
- 危险命令审批
- allow once / always / turn
- deny once / always / with feedback
- 持久化权限规则
- 通配规则，例如 `Bash(npm run *)`、`Edit(src/*.java)`

`/permissions` 可以查看当前权限文件路径、workspace 和规则数量。

## 记忆系统

记忆系统由 `MemoryManager`、`MemoryEntry`、`MemoryType` 和 `MemoryTool` 组成。

特点：

- 默认存储目录：`~/.codeauto/memory/`
- 文件格式：frontmatter Markdown
- 类型：`user`、`feedback`、`project`、`reference`
- 支持保存、列表、删除、相关性检索
- 相关记忆会注入 system prompt
- 24 小时以上未更新的记忆会标记为 stale
- 用户可通过 `/memory` 管理，模型可通过 `save_memory` 等工具管理

## 指令加载

`InstructionLoader` 会加载：

1. `~/.claude/CLAUDE.md`
2. `~/.codeauto/CLAUDE.md`
3. `<project>/CLAUDE.md`
4. `<project>/CLAUDE.local.md`

这些内容会进入 system prompt 的 `<system-reminder>` 区域。越靠后的本地文件优先级越高。

## 会话和上下文

会话系统支持：

- 新建会话
- resume
- fork
- rename
- compact
- 跨项目 session 浏览
- 30 天过期清理

上下文管理支持：

- `TokenEstimator`
- `ContextStats`
- `CompactService`
- `MicroCompactService`
- `ToolResultStorage`
- provider usage 优先，估算作为 fallback

## Skills 和 MCP

Skills：

- 项目级 `.code-auto/skills`
- 用户级 `.claude/skills`
- `load_skill` 工具
- `skills list/add/remove` CLI 子命令

MCP：

- 用户级 `~/.codeauto/mcp.json`
- 项目级 `.mcp.json`
- stdio `content-length` / `newline-json` 自动协商
- Streamable HTTP
- token 存储和注入
- MCP backed tools 自动注册

## 测试

当前测试状态：

```text
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试覆盖：

- AgentLoop
- 工具注册和工具参数兼容
- 文件 review
- 命令执行和后台任务
- 权限审批、反馈、通配规则、权限摘要
- 会话保存、恢复和压缩边界
- MCP client/service/helper/backed tool
- Skills 发现
- 指令加载
- 记忆保存、检索、注入和工具管理
- CLI 编码和 workspace 解析
- Assistant 流式输出事件
- TUI escape sequence 处理

## 常用 CLI 参数

```bash
# 工作目录（默认：当前目录）
codeauto --cwd /path/to/project

# 启动模式
codeauto --tui             # 全屏 TUI 模式
codeauto --mock --tui      # 离线 Mock + TUI（无需 API Key）
codeauto                   # 普通 CLI 模式

# 会话相关
codeauto --resume          # 恢复最近会话
codeauto --resume <id>     # 恢复指定会话
codeauto --fork <id>       # 从指定会话分叉

# 模型配置
codeauto --model claude-sonnet-4-6
codeauto --max-tokens 8192

# 最大工具调用步数
codeauto --max-steps 64

# 随处运行：将 bin/ 目录加入 PATH 即可
```

## 设计与架构

CodeAuto 保持几个工程原则：

- 轻量：不引入 Spring/Quarkus
- 可测试：核心逻辑优先写成普通 Java 类
- 可审计：敏感操作必须经过权限层
- 可恢复：会话和记忆都落盘
- 可扩展：工具、MCP、Skills 和记忆系统都可继续演进

下一步适合继续推进：

- SessionMemory 自动提取
- L2 Session Memory 压缩层
- 同文件多次编辑 transcript 聚合
- Skill 变量替换和 fork 模式
- 更细的模型上下文窗口查询表
