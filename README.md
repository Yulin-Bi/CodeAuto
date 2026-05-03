# CodeAuto

当前主流的 AI Coding 工具几乎全部基于 TypeScript 或 Python 实现，JVM 生态严重缺位。

对于以 Java 为主力语言的开发者来说，想学习或二次开发 AI Coding Agent，往往需要越过语言壁垒，门槛极高。

CodeAuto 参考 Claude Code 源码的设计思路，融合 MINICODE 的轻量可扩展理念，用 **Java 21** 构建了一个简单、可扩展、贴近 JVM 开发者的 AI 编程代理运行时。

提供普通 CLI 和全屏 TUI 两种交互方式，内置工具调用、权限审批、文件 diff review、会话保存与恢复、上下文压缩、Skills、MCP、持久化记忆和多级项目指令加载。

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

默认注册 20 个内置工具：

- 文件：`list_files`、`grep_files`、`read_file`、`write_file`、`edit_file`、`patch_file`、`modify_file`
- 命令和交互：`run_command`、`ask_user`、`background_tasks`
- 网络：`web_fetch`、`web_search`
- 扩展：`load_skill`
- 记忆：`save_memory`、`list_memory`、`delete_memory`
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

模型也可以通过工具主动调用：

```text
save_memory
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

Skills 会从项目级目录发现，也支持用户级管理配置：

```text
.code-auto/skills          # 项目级 skills 目录
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

CodeAuto 支持 stdio MCP 和 Streamable HTTP MCP。

MCP 配置可以放在：

```text
~/.codeauto/mcp.json
.mcp.json
```

管理命令：

```bash
mvn exec:java "-Dexec.args=mcp list"
mvn exec:java "-Dexec.args=mcp add --protocol auto --env TOKEN=$TOKEN local node server.js"
mvn exec:java "-Dexec.args=mcp login local --token <bearer-token>"
mvn exec:java "-Dexec.args=mcp logout local"
mvn exec:java "-Dexec.args=mcp remove local"
```

TUI 内查看 MCP 状态：

```text
/mcp
```

stdio 协议支持：

- `auto`
- `content-length`
- `newline-json`

`auto` 会先尝试 `content-length`，失败后回退到 `newline-json`。

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
  instructions/   多级指令加载
  manage/         管理配置存储
  mcp/            MCP 客户端和服务
  memory/         持久化记忆系统
  model/          模型适配器
  permissions/    权限管理
  session/        会话存储
  skills/         Skills 发现
  tool/           工具接口和注册表
  tools/          内置工具
  tui/            全屏终端界面
```

## 测试状态

当前测试：

```text
Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

主要覆盖：

- AgentLoop
- ToolRegistry 和内置工具
- SessionStore
- Context 压缩
- PermissionManager
- MCP client/service
- MemoryManager
- InstructionLoader
- CLI 编码和 workspace 解析
- Assistant 流式输出事件
- TUI escape sequence

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

如果地址包含 `{query}`，工具会替换为 URL 编码后的搜索词；否则会自动追加 `q=<query>` 参数。
