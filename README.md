# CodeAuto

CodeAuto 是一个面向 Java 生态、借鉴现代 AI 编程助手交互范式、使用 Java 21 开发的 AI 编程助手运行时。当前实现包含命令行对话、全屏终端界面、工具调用、权限审批、会话保存、上下文压缩、Skills 和 MCP 支持。

## 环境要求

- JDK 21
- Maven 3.9 或更高版本
- 推荐终端：Windows Terminal / PowerShell、macOS Terminal、Linux 终端

## 常用启动方式

运行测试：

```bash
mvn test
```

首次使用真实模型前，需要先配置模型 API。CodeAuto 当前内置 Anthropic Messages API 适配器，最少需要配置 `CODEAUTO_BASE_URL`、`CODEAUTO_AUTH_TOKEN` 和 `CODEAUTO_MODEL`：

```powershell
$env:CODEAUTO_BASE_URL="https://api.anthropic.com"
$env:CODEAUTO_AUTH_TOKEN="你的 Anthropic API Key"
$env:CODEAUTO_MODEL="你的模型名称"
```

启动普通命令行界面：

```bash
mvn exec:java
```

启动全屏终端界面：

```bash
mvn exec:java "-Dexec.args=--tui"
```

使用离线 mock 模型启动普通命令行界面，适合开发自测、演示工具循环或没有 API Key 的环境：

```bash
mvn exec:java "-Dexec.args=--mock"
```

使用离线 mock 模型启动全屏终端界面：

```bash
mvn exec:java "-Dexec.args=--mock --tui"
```

复杂任务如果需要连续调用很多工具，可以调高单轮工具步数：

```bash
mvn exec:java "-Dexec.args=--tui --max-steps 64"
```

默认单轮上限为 32 步，通常足够完成一次较长的自测或文件生成任务。

构建 fat JAR 并直接启动全屏终端界面：

```bash
mvn package -DskipTests
java -jar target/codeauto-0.1.0-SNAPSHOT-shaded.jar --tui
```

也可以使用启动脚本：

```bash
bin/codeauto --tui
bin/codeauto.bat --tui
```

如果只是离线体验或验证安装是否成功，可以在以上命令中追加 `--mock`。

## API 配置

CodeAuto 的配置优先级从低到高为：默认值、环境变量、项目级 `.codeauto/settings.json`、用户级 `~/.codeauto/settings.json`、命令行参数。命令行参数目前支持覆盖模型名和最大输出 token，例如 `--model <name>` 和 `--max-tokens 8192`。

### 方式一：环境变量

PowerShell：

```powershell
$env:CODEAUTO_BASE_URL="https://api.anthropic.com"
$env:CODEAUTO_AUTH_TOKEN="你的 Anthropic API Key"
$env:CODEAUTO_MODEL="你的模型名称"
```

macOS / Linux：

```bash
export CODEAUTO_BASE_URL="https://api.anthropic.com"
export CODEAUTO_AUTH_TOKEN="你的 Anthropic API Key"
export CODEAUTO_MODEL="你的模型名称"
```

### 方式二：用户级配置

把长期使用的配置写入 `~/.codeauto/settings.json`。这个文件在用户目录下，不建议提交到项目仓库：

```json
{
  "baseUrl": "https://api.anthropic.com",
  "authToken": "你的 Anthropic API Key",
  "model": "你的模型名称",
  "maxOutputTokens": 4096,
  "maxRetries": 4
}
```

### 方式三：项目级配置

项目级配置位于当前仓库的 `.codeauto/settings.json`，适合放不敏感的团队默认项，例如模型名或输出 token。不要把 API Key 写进项目级配置并提交：

```json
{
  "model": "团队约定的模型名称",
  "maxOutputTokens": 4096
}
```

配置完成后可用真实模型启动：

```bash
mvn exec:java "-Dexec.args=--tui"
```

也可以临时覆盖模型名：

```bash
mvn exec:java "-Dexec.args=--tui --model <模型名称>"
```

## 会话恢复和分叉

会话按工作目录保存在 `~/.codeauto/projects/` 下。启动时会尽力清理 30 天以前的旧会话；如果保存目录暂时不可写，命令行界面会给出警告，但不会中断当前回答。

恢复最近一次会话：

```bash
mvn exec:java "-Dexec.args=--resume"
```

恢复指定会话：

```bash
mvn exec:java "-Dexec.args=--resume <id>"
```

从指定会话分叉：

```bash
mvn exec:java "-Dexec.args=--fork <id>"
```

在全屏终端界面中输入 `/resume` 可以打开会话选择器，用方向键选择历史会话，按 Enter 加载。

## 全屏终端界面操作说明

### 输入和退出

- Enter：发送当前输入。
- Esc：清空当前输入。
- Ctrl+C：退出 TUI。
- Tab：补全斜杠菜单命令。
- 输入 `/`：显示可选命令菜单。

### 查看聊天记录

- PageUp / PageDown：按页滚动会话记录面板。
- Alt+Up / Alt+Down：逐行滚动会话记录面板。
- Ctrl+Up / Ctrl+Down：逐行滚动会话记录面板。
- 鼠标滚轮：在支持 SGR mouse 的终端中滚动会话记录面板。
- 输入框为空时 Ctrl+A：跳到会话记录面板顶部。
- 输入框为空时 Ctrl+E：跳到会话记录面板底部。
- `/resume`：打开已保存会话列表，选择后查看之前聊过的记录。

### 工具输出

- Ctrl+O：展开或折叠最近一次非运行中的工具输出。
- 底部状态栏会显示当前运行中的后台 shell 数量和最近工具状态。

### 权限审批

当模型请求执行敏感命令或修改文件时，全屏终端界面会弹出审批框：

- 方向键：选择审批选项。
- Enter：确认当前选项。
- Esc：拒绝。
- 选择 `Deny with Feedback` 后可以输入拒绝原因，原因会作为工具结果返回给模型。

## CLI 内置命令

- `/help`：显示帮助。
- `/tools`：列出已注册工具。
- `/skills`：列出发现的 Skills。
- `/sessions`：列出当前工作目录下的会话。
- `/projects`：列出所有有保存会话的项目。
- `/mcp`：显示 MCP 配置和发现到的 MCP 工具。
- `/status`：显示工作目录、会话、工具数量和上下文估算。
- `/model`：显示当前模型。
- `/new`：开启新会话。
- `/resume <id>`：恢复指定会话。
- `/fork`：把当前上下文保存为新会话。
- `/rename <name>`：重命名当前会话。
- `/compact`：手动压缩中间上下文。
- `/config-paths`：显示配置目录。
- `/exit`：退出。

## 内置工具

内置工具覆盖文件、搜索、编辑、补丁、命令执行、Web、Skill 和 MCP 辅助能力。MCP 辅助工具包括：

- `list_mcp_resources`
- `read_mcp_resource`
- `list_mcp_prompts`
- `get_mcp_prompt`

mock 模式下可触发工具调用的示例输入：

- `list files`
- `read README.md`
- `grep CodeAuto`
- `run git status`
- `background ping 127.0.0.1`
- `background tasks`

`read_file` 支持 `path` 参数，也兼容 `file_path` 别名。

`web_search` 需要配置搜索代理地址：

```powershell
$env:CODEAUTO_SEARCH_URL="https://example/search?q={query}"
```

如果地址中包含 `{query}`，工具会把它替换为 URL 编码后的搜索词；否则会自动追加 `q=<query>` 查询参数。

## 权限和安全

危险命令和文件编辑会走权限层。交互式终端中，CodeAuto 会在执行敏感操作前请求确认，并可把 “always allow” 决策持久化到：

```text
~/.codeauto/permissions.json
```

拒绝操作时可以附带反馈，这些反馈会作为工具结果返回给模型，帮助模型换一种实现方式。

## Skills 管理

列出 Skills：

```bash
mvn exec:java "-Dexec.args=skills list"
```

添加 Skill：

```bash
mvn exec:java "-Dexec.args=skills add my-skill /path/to/skill"
```

移除 Skill：

```bash
mvn exec:java "-Dexec.args=skills remove my-skill"
```

Skills 会从项目级 `.code-auto/skills` 和用户级 `.claude/skills` 等目录中发现。

## MCP 管理

列出 MCP 配置：

```bash
mvn exec:java "-Dexec.args=mcp list"
```

添加 MCP server：

```bash
mvn exec:java "-Dexec.args=mcp add --protocol auto --env TOKEN=$TOKEN local node server.js"
```

登录并保存 bearer token：

```bash
mvn exec:java "-Dexec.args=mcp login local --token <bearer-token>"
```

登出或移除 MCP server：

```bash
mvn exec:java "-Dexec.args=mcp logout local"
mvn exec:java "-Dexec.args=mcp remove local"
```

MCP 配置可保存在用户级：

```text
~/.codeauto/mcp.json
```

也可保存在项目级：

```text
.mcp.json
```

支持 Java 扁平格式，也支持 Claude 兼容的 `mcpServers` 包装格式：

```json
{
  "mcpServers": {
    "filesystem": {
      "protocol": "auto",
      "command": "node",
      "args": ["server.js"]
    }
  }
}
```

stdio MCP 协议支持：

- `auto`
- `content-length`
- `newline-json`

`auto` 会先尝试标准 `content-length` 帧格式，失败后回退到 `newline-json`。

`mcp login` 保存的 token 位于：

```text
~/.codeauto/mcp-tokens.json
```

对于 stdio MCP server，保存的 token 会注入为 `MCP_BEARER_TOKEN` 和 `MCP_AUTH_TOKEN`，除非配置中已经显式设置这些环境变量。

## 常见问题

### 为什么不提交 dependency-reduced-pom.xml？

`dependency-reduced-pom.xml` 是 Maven Shade Plugin 在构建 shaded jar 时生成的中间文件，不是项目的主构建配置。仓库只需要提交 `pom.xml`；用户 clone 后执行 `mvn test`、`mvn exec:java` 或 `mvn package`，Maven 会根据 `pom.xml` 自动下载依赖并构建。这个项目也显式关闭了 dependency-reduced POM 的生成，避免构建后产生容易误解的文件。

### 鼠标滚轮不能翻聊天记录

请确认使用的是支持 SGR mouse 的终端。Windows 下推荐 Windows Terminal + PowerShell。当前全屏终端界面已支持 SGR mouse 滚轮事件。

### PowerShell 窗口缩放后 UI 没有变化

当前全屏终端界面已接入 JLine `WINCH` 窗口尺寸变化信号，窗口变化时会自动重绘。如果终端没有发出尺寸变化信号，按任意键也会触发一次尺寸检查。

### session 保存失败

检查 `~/.codeauto/projects/` 是否可写。普通命令行界面中保存失败只会显示警告，不会中断当前回答。

### Windows 下 Unix 命令不可用

`run_command` 会执行当前系统中的真实命令。Windows 下 `dir`、`type`、`copy` 等 `cmd` 内置命令会自动通过 `cmd /c` 执行。对于没有安装的 Unix 工具，例如 `head` 或 Unix 语法的 `find`，可以使用 PowerShell 等价命令：

```powershell
Get-Content README.md -TotalCount 20
Get-ChildItem -Recurse -Filter *.java
Select-String -Path src\**\*.java -Pattern "CodeAuto"
```

### PowerShell 输出中文或符号乱码

建议在当前 PowerShell 中使用 UTF-8：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```
