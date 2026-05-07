# CodeAuto

当前主流的 AI Coding 工具几乎全部基于 TypeScript 或 Python 实现，JVM 生态严重缺位。

对于以 Java 为主力语言的开发者来说，想学习或二次开发 AI Coding Agent，往往需要越过语言壁垒，门槛极高。

CodeAuto 参考 Claude Code 源码的设计思路，融合 MINICODE 的轻量可扩展理念，用 **Java 21** 构建了一个简单、可扩展、贴近 JVM 开发者的 AI 编程代理运行时。

提供普通 CLI 和全屏 TUI 两种交互方式，内置工具调用、权限审批、文件 diff review、会话保存与恢复、上下文压缩、Skills、MCP、持久化记忆、多级项目指令加载、自反思（Reflexion）和 ACE 结构化经验记忆。

## 环境要求

- JDK 21
- Maven 3.9 或更高版本
- 推荐终端：Windows Terminal / PowerShell、macOS Terminal、Linux 终端

## 快速开始

运行测试：

```bash
mvn test
```

离线 mock 模式启动 TUI，不需要 API Key：

```bash
mvn exec:java "-Dexec.args=--mock --tui"
```

真实模型模式启动 TUI：

```bash
mvn exec:java "-Dexec.args=--tui"
```

普通 CLI 模式：

```bash
mvn exec:java
```

构建 shaded JAR：

```bash
mvn package -DskipTests
java -jar target/codeauto-0.1.0-SNAPSHOT-shaded.jar --tui
```

也可以使用启动脚本，**从任意目录运行**，自动以当前目录为工作目录：

```bash
bin/codeauto --tui
bin/codeauto.bat --tui
```

指定其他目录作为工作目录：

```bash
bin/codeauto --cwd /path/to/project --tui
```

### 随处运行

将 `bin` 目录添加到 `PATH` 后，可直接在任何目录调用：

```bash
codeauto --tui                    # 当前目录为工作目录
codeauto --mock --tui             # 离线 Mock 模式
codeauto --cwd D:/other-project   # 指定其他工作目录
```

**Windows PowerShell（管理员）添加到 PATH：**

```powershell
[Environment]::SetEnvironmentVariable("Path", [Environment]::GetEnvironmentVariable("Path", "User") + ";$env:USERPROFILE\CodeAuto\bin", "User")
```

## 模型配置

CodeAuto 当前内置 Anthropic Messages API 适配器和离线 Mock 适配器。

配置优先级从低到高：

1. 默认值
2. 环境变量
3. 项目级 `.codeauto/settings.json`
4. 用户级 `~/.codeauto/settings.json`
5. CLI 参数

PowerShell 示例：

```powershell
$env:CODEAUTO_BASE_URL="https://api.anthropic.com"
$env:CODEAUTO_AUTH_TOKEN="your-api-key"
$env:CODEAUTO_MODEL="your-model-name"
```

macOS / Linux 示例：

```bash
export CODEAUTO_BASE_URL="https://api.anthropic.com"
export CODEAUTO_AUTH_TOKEN="your-api-key"
export CODEAUTO_MODEL="your-model-name"
```

用户级配置示例：

```json
{
  "baseUrl": "https://api.anthropic.com",
  "authToken": "your-api-key",
  "model": "your-model-name",
  "maxOutputTokens": 4096,
  "maxRetries": 4,
  "modelTimeoutSeconds": 600
}
```

TUI 和 CLI 中可以直接切换并持久化模型：

```text
/model <name>
```

## 核心能力

### CLI 和 TUI

- 普通 CLI 对话模式
- JLine 3 全屏 TUI
- Header / Transcript / Prompt / Footer 面板
- Markdown 到 ANSI 渲染
- CJK 显示宽度和中文输入支持
- 鼠标滚轮、PageUp/PageDown、Alt/Ctrl 方向键滚动
- 斜杠菜单、Tab 补全、输入历史
- 斜杠菜单限高显示，避免命令提示铺满屏幕
- 长文本优先自动换行，减少窄窗口下行尾被截断成 `...`
- Anthropic 文本回复支持流式输出，TUI 原地刷新，CLI 边收边打印
- 权限审批弹窗和 Deny with Feedback

### AgentLoop

- 支持模型 final response、progress message、tool call 循环
- 支持工具结果回填和最大步骤限制
- 支持 provider usage token 统计，缺失时使用本地估算
- 支持自动上下文压缩和微压缩

### 内置工具

默认注册 23 个内置工具：

- 文件：`list_files`、`grep_files`、`read_file`、`write_file`、`edit_file`、`patch_file`、`modify_file`
- 命令和交互：`run_command`、`ask_user`、`background_tasks`
- 网络：`web_fetch`、`web_search`
- 扩展：`load_skill`
- 记忆：`save_memory`、`list_memory`、`delete_memory`
- 任务：`todo_create`、`todo_update`、`todo_list`
- MCP helper：`list_mcp_resources`、`read_mcp_resource`、`list_mcp_prompts`、`get_mcp_prompt`

TUI 还支持直接绕过模型的本地快捷命令：

```text
/ls [path]
/grep <pattern>::[path]
/read <path>
/write <path>::<content>
/modify <path>::<content>
/edit <path>::<search>::<replace>
/patch <path>::<search>::<replace>...
/cmd <command>
```

## 会话管理

会话按 workspace 隔离，保存在：

```text
~/.codeauto/projects/
```

常用命令：

```text
/sessions
/resume
/resume <id>
/fork
/rename <name>
/new
/compact
```

CLI 参数：

```bash
mvn exec:java "-Dexec.args=--resume"
mvn exec:java "-Dexec.args=--resume <id>"
mvn exec:java "-Dexec.args=--fork <id>"
```

## 持久化记忆

CodeAuto 支持跨会话记忆，默认存储在：

```text
~/.codeauto/memory/
```

记忆使用 frontmatter Markdown 文件保存，类型包括：

- `user`
- `feedback`
- `project`
- `reference`

启动新会话时，相关记忆会被注入 system prompt 的 `<system-reminder>` 区域。超过 24 小时未更新的记忆会标记为 `[stale]`，提醒模型先核实再依赖。

用户命令：

```text
/memory list [query]
/memory add <type>::<title>::<content>
/memory delete <id>
```

记忆保存由 AI 驱动：system prompt 会提示 AI 在用户表达偏好、项目约定、架构决策时主动调用 `save_memory`。保存前 AI 会先 `list_memory` 检查是否有矛盾或过时的旧记忆，有则 `delete_memory` 清理。AI 会询问用户选择保存位置：

- `project`：写入当前 workspace 的 `CLAUDE.md`
- `global`：写入 `~/.claude/CLAUDE.md`
- `codeauto`：写入 `~/.codeauto/CLAUDE.md`
- `store`：写入 `~/.codeauto/memory/`

模型通过内置工具管理记忆：

```text
save_memory destination=store|project|global|codeauto
list_memory
delete_memory
```

## 多级指令加载

CodeAuto 会在构建 system prompt 时加载多级 Markdown 指令：

1. `~/.claude/CLAUDE.md`
2. `~/.codeauto/CLAUDE.md`
3. `<project>/CLAUDE.md`
4. `<project>/CLAUDE.local.md`

越靠后的本地指令优先级越高。`CLAUDE.local.md` 适合放不提交到仓库的私有偏好。

## 权限与安全

CodeAuto 对敏感命令和文件编辑走权限层：

- 危险命令检测
- 文件编辑前生成 unified diff review
- allow once / always / turn
- deny once / always / with feedback
- 权限持久化到 `~/.codeauto/permissions.json`

查看权限状态：

```text
/permissions
```

权限规则支持精确匹配和通配匹配，例如：

```text
Bash(npm run *)
Bash(python scripts/*)
Bash(git push --force*)
Edit(src/*.java)
Edit(secret/*)
```

## Skills

Skills 会从项目级目录发现，也支持用户级管理配置。通过 `load_skill` 加载后，skill 的完整指令会在当前会话的每一轮 system prompt 中注入，确保 AI 在整个会话中遵循 skill 指令。

```text
.codeauto/skills           # 项目级 skills 目录
.claude/skills             # 项目级 .claude skills 目录
~/.codeauto/skills.json    # 用户级 managed skills (skills add/remove CLI)
```

管理命令：

```bash
mvn exec:java "-Dexec.args=skills list"
mvn exec:java "-Dexec.args=skills add my-skill /path/to/skill"
mvn exec:java "-Dexec.args=skills remove my-skill"
```

## MCP

CodeAuto 支持 stdio MCP 和 Streamable HTTP MCP。启动时自动发现所有配置的 MCP 服务器，将其工具注册到工具注册表，Agent Loop 可自动调用，无需手动指定。

### 配置文件

MCP 配置可以放在：

```text
~/.codeauto/mcp.json          # 用户级全局配置
<project>/.mcp.json            # 项目级配置（不会覆盖用户级同名 server）
```

### 快速添加 MCP Server

```bash
# stdio 方式（最常用）
codeauto mcp add fs npx -- -y @modelcontextprotocol/server-filesystem /tmp

# HTTP 方式（手动编辑 mcp.json，添加 url 字段）
```

配置示例（`~/.codeauto/mcp.json`）：

```json
{
  "local-node": {
    "protocol": "auto",
    "command": "node",
    "args": ["server.js"],
    "env": { "API_KEY": "xxx" }
  },
  "remote-api": {
    "url": "https://mcp.example.com/api",
    "headers": { "Authorization": "Bearer xxx" }
  }
}
```

### 管理命令

```bash
codeauto mcp list
codeauto mcp add <name> <command> [args...] [--protocol auto|content-length|newline-json] [--env KEY=VALUE...]
codeauto mcp login <name> --token <bearer-token>
codeauto mcp logout <name>
codeauto mcp remove <name>
```

TOKEN 会自动注入为 `MCP_BEARER_TOKEN` 和 `MCP_AUTH_TOKEN` 环境变量，无需在 env 中重复配置。

TUI 内查看 MCP 状态：

```text
/mcp
```

### 协议

stdio 协议支持 `auto`（默认）、`content-length`、`newline-json`。`auto` 会先尝试 `content-length`，失败后回退到 `newline-json`。

如果 MCP 服务器初始化慢，建议显式指定协议避免超时等待：

```json
{
  "fs": {
    "protocol": "newline-json",
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
  }
}
```

### 系统指令一览
<system-reminder>
  # CLAUDE.md 多级指令
  # Todo summary
  # Available skills (索引，按需加载)
  # Loaded skill instructions (已加载的全量)
  # Relevant persistent memories (最多 5 条)
  # Past experience   ← 新增：告诉模型去哪找，不注入内容
    遇到错误 → grep .codeauto/bullets/ (compact 经验)
    需要全量分析 → read .codeauto/reflections/ (详细复盘)
</system-reminder>


### Windows 兼容性

Windows 下 Java `ProcessBuilder` 无法直接执行 `.cmd`/`.bat` 文件。CodeAuto 会自动检测并解析 `.cmd` 包装脚本，提取出底层真实可执行文件（如 `node.exe`）直接调用，无需用户手动处理。

直接使用 `npx`、`node`、`python` 等命令即可，无需指定完整路径。建议显式指定 `protocol` 为 `newline-json`，避免 `auto` 模式下 content-length 协商超时等待。

## 常用斜杠命令

```text
/help
/tools
/skills
/sessions
/projects
/mcp
/memory
/status
/model
/model <name>
/permissions
/new
/resume
/resume <id>
/fork
/rename <name>
/compact
/config-paths
/exit
```

## 项目结构

```text
src/main/java/com/codeauto/
  background/     后台任务
  cli/            Picocli CLI
  config/         配置加载
  context/        token 估算和上下文压缩
  core/           AgentLoop 和核心消息类型
  curator/        ACE Bullet 确定性合并引擎
  instructions/   多级指令加载
  manage/         管理配置存储
  mcp/            MCP 客户端和服务
  memory/         持久化记忆系统
  model/          模型适配器
  permissions/    权限管理
  reflection/     Reflexion 自反思服务
  session/        会话存储
  skills/         Skills 发现
  tool/           工具接口和注册表
  todo/           TodoList 任务追踪
  tools/          内置工具
  tui/            全屏终端界面
```

## 测试状态

当前测试：

```text
Tests run: 111, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

主要覆盖：

- AgentLoop + 流式输出 + 取消打断
- ToolRegistry 和内置工具（含参数兼容性）
- SessionStore + 压缩边界
- Context 压缩 + 微压缩 + 压缩产物落盘
- PermissionManager + 通配规则
- MCP client/service
- MemoryManager + bullet 序列化往返
- InstructionLoader + 多级指令加载 + Skill 会话注入 + ACE Playbook 注入
- TodoStore
- CLI 编码和 workspace 解析
- TUI escape sequence + diff 高亮
- ReflectionService + 4 种触发检测 + FEEDBACK 记忆保存 + Bullet 自动创建
- Curator + BulletDelta ADD/REMOVE/TAG/计数器/项目过滤/内容更新 + Jaccard 去重

## 常见问题

### PowerShell 输出中文乱码

建议在当前 PowerShell 会话设置 UTF-8：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```

### CLI 模式中文输入被模型误解

CLI 普通模式会优先使用 JLine `LineReader` 读取终端输入；如果 JLine 初始化失败，会回退到 Scanner，并按以下顺序选择 stdin 字符集：`-Dcodeauto.cli.charset`、`CODEAUTO_CLI_CHARSET`、真实 console charset、`native.encoding`、JVM 默认 charset。

Windows / Maven exec 环境下如果仍遇到中文输入被模型误解，可以显式指定：

```powershell
$env:CODEAUTO_CLI_CHARSET="GBK"
```

或：

```bash
mvn exec:java "-Dexec.jvmArgs=-Dcodeauto.cli.charset=UTF-8"
```

### 启动时出现 JLine deprecated provider 警告

CodeAuto 已在 CLI 入口默认设置 `org.jline.terminal.disableDeprecatedProviderWarning=true`，正常运行时不应再显示该警告。

### 鼠标滚轮不能滚动聊天记录

请确认使用支持 SGR mouse 的终端。Windows 下推荐 Windows Terminal + PowerShell。

### session 保存失败

检查 `~/.codeauto/projects/` 是否可写。保存失败只会显示警告，不会中断当前回答。

### web_search 没有结果

`web_search` 默认会尝试使用 DuckDuckGo HTML 搜索，无需 API Key。如果你的网络环境无法访问默认搜索页，或想接入自己的搜索代理，可以配置：

```powershell
$env:CODEAUTO_SEARCH_URL="https://example/search?q={query}"
```

## 最近更新（2026-05-06）

### Reflexion 自反思 + ACE Bullet 结构化经验记忆

- 每轮对话结束后自动检测失败信号（工具错误 / 达到最大步数 / 用户取消 / 用户不满），触发模型反思并保存 `FEEDBACK` 记忆。
- 反思结构包含 What Went Wrong / Root Cause / What Should Have Been Done Differently / Reusable Lesson。
- 反思提取 Reusable Lesson 后自动创建 ACE Bullet（带 `[bullet:<id>]` ID 和 helpful/harmful 计数器）。
- Curator 确定性增量引擎：支持 BulletDelta Add/Update/Tag/Remove，Jaccard 相似度去重（阈值 0.55），section 范围匹配。
- 文件分离：reflections → `<project>/.codeauto/reflections/`，bullets → `<project>/.codeauto/bullets/`，memories → `~/.codeauto/memory/`。
- `InstructionLoader` 独立注入 ACE Playbook（最多 10 条），bullet 以单行紧凑索引显示，不占用普通记忆配额。
- 反思异步执行（`CompletableFuture.runAsync` + `List.copyOf` 防御性拷贝），不阻塞用户交互。
- `detectTrigger` 仅扫描本轮增量消息（`turnStartIndex` 限定），避免历史错误重复触发。
- 模型答复中引用 bullet 时标注 `[bullet:<id>]`，ReflectionService 自动解析 Bullet Tags 并更新计数器。

### Windows MCP 自动命令解析

- `McpClient` 在 Windows 下自动检测 `.cmd`/`.bat` 包装脚本，解析并提取底层可执行文件（如 `node.exe`）直接调用。
- 用户可直接用 `npx`、`node`、`python` 等命令配置 MCP Server，无需手动拼接完整路径。
- 修复 `initialize` 缺失 `capabilities` 字段导致新版 MCP Server 拒绝握手的问题。
- `redirectErrorStream(true)` + JSON 噪声过滤，防止 stderr 管道死锁。
- 建议显式指定 `"protocol": "newline-json"` 跳过 `auto` 模式的 content-length 协商等待。

## 最近更新（2026-05-05）

### 上下文压缩模型辅助摘要

- `CompactService.compactWithStats()` 新增 `ModelAdapter` 参数，压缩时优先让模型生成结构化摘要。
- 摘要包含 6 个结构化 section：User Intent / Key Decisions / File Changes / Errors & Fixes / TODOs / Important Context。
- 模型不可用或调用失败时自动 fallback 到改进的启发式摘要（按消息类型分组：User Requests / Tools Called / Key Outputs / Assistant Responses）。
- 压缩产物始终落盘到 `.codeauto/compacted/compact-*.md`，保证完整上下文可回溯。
- 自动压缩和手动 `/compact` 均生效。

### Ctrl+C 打断对话

- Ctrl+C 在 AgentLoop 执行中会中断当前对话，不会退出 TUI。空闲状态下仍退出 TUI。
- `AgentLoop` 内置可取消检查点（每轮开始、API 调用前、工具执行前），确保快速响应。
- JLine INT 信号处理器提供 OS 层兜底拦截。
- 打断后状态完全重置：流式缓冲、progress trace、工具状态均清理干净。

### 上下文压缩产物落盘

- 压缩时完整消息保存到 `.codeauto/compacted/compact-<timestamp>.md`。
- 摘要末尾自动注入文件路径，AI 发现摘要信息不足时可自行 `read_file` 查阅。
- 自动压缩和手动 `/compact` 均生效。

### 记忆系统简化

- 移除主动记忆自动捕获弹窗，记忆保存完全由 AI 驱动。
- system prompt 增强 `Memory behavior:` 指引，AI 保存前先检查矛盾/过时记忆。

### Skills 会话注入

- `load_skill` 加载后，skill 指令在每轮 system prompt 中注入，标记 `[loaded]`。
- Skill 列表展示名称和描述，减少 `list_skills` 调用。

### TodoList 功能

- 新增 `todo_create`、`todo_update`、`todo_list` 工具，TUI header 展示进度。

### 验证状态

```
Tests run: 111, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

如果地址包含 `{query}`，工具会替换为 URL 编码后的搜索词；否则会自动追加 `q=<query>` 参数。
