# CodeAuto 详细说明

## 启动

环境要求：JDK 21、Maven 3.9+。

```bash
# 运行测试
mvn test

# Mock 模式启动 TUI，不需要 API Key
mvn exec:java "-Dexec.args=--mock --tui"

# 启动单进程 Web 工作台
mvn exec:java "-Dexec.args=--mock --web --web-port 0"

# 构建并运行 shaded JAR
mvn package -DskipTests
java -jar target/codeauto-0.1.0-SNAPSHOT-shaded.jar --tui
```

## 模型 Key 与配置

真实模型模式至少需要配置模型服务地址、认证 Key 和模型名称。PowerShell 示例：

```powershell
$env:CODEAUTO_BASE_URL="https://api.anthropic.com"
$env:CODEAUTO_AUTH_TOKEN="your-api-key"
$env:CODEAUTO_MODEL="your-model-name"
$env:CODEAUTO_STRIP_THINKING="true"
mvn exec:java "-Dexec.args=--web --web-port 0"
```

macOS / Linux：

```bash
export CODEAUTO_BASE_URL="https://api.anthropic.com"
export CODEAUTO_AUTH_TOKEN="your-api-key"
export CODEAUTO_MODEL="your-model-name"
export CODEAUTO_STRIP_THINKING=true
mvn exec:java "-Dexec.args=--web --web-port 0"
```

也可以写入用户级 `~/.codeauto/settings.json` 或项目级 `.codeauto/settings.json`：

```json
{
  "baseUrl": "https://api.anthropic.com",
  "authToken": "your-api-key",
  "model": "your-model-name",
  "maxOutputTokens": 4096,
  "maxRetries": 4,
  "modelTimeoutSeconds": 600,
  "contextWindow": 200000,
  "stripThinking": true
}
```

配置优先级由低到高为：默认值、环境变量、项目配置、用户配置、CLI 参数。`stripThinking` 用于适配不同模型对 extended thinking 的要求：Anthropic 通常设为 `true`，需要原样回传 thinking block 的模型设为 `false`。

## 在任意目录启动

项目自带 `bin` 启动脚本，会把当前目录作为工作区：

```powershell
bin\codeauto.bat --web --web-port 0
bin\codeauto.bat --tui
bin\codeauto.bat --cwd D:\path\to\another-project --web --web-port 0
```

如果希望在任意终端直接输入 `codeauto`，将项目的 `bin` 目录加入用户 PATH：

```powershell
[Environment]::SetEnvironmentVariable(
  "Path",
  [Environment]::GetEnvironmentVariable("Path", "User") + ";D:\JAVA\git-pro\CodeAuto\bin",
  "User"
)
```

重新打开终端后即可使用：

```powershell
codeauto --web --web-port 0
codeauto --tui
codeauto --cwd D:\path\to\another-project
```

其中 `codeauto --web` 会自动把你执行命令时所在的目录作为当前工作区。例如先进入任意项目目录，再运行：

```powershell
cd D:\workspace\my-project
codeauto --web
```

Web 服务启动后，终端会打印本地访问地址。`--web-port 0` 是默认值，表示自动选择空闲端口；也可以指定固定端口，例如 `codeauto --web --web-port 8080`。

## 交互方式

CodeAuto 提供普通 CLI、全屏 TUI 和 Web 工作台。三者共享 Agent Loop、工具注册、会话存储、权限和评估数据；Web 工作台的静态资源与 API 由同一个 Java 服务提供，不需要单独运行前端服务。

## Web 工作台使用指南

### 会话与对话

- 左侧历史按父会话和分支组织；未发送消息的新会话不会无限创建。
- 当前会话、对话记录和输入框分别管理滚动，刷新或切换分支不会抢夺用户正在查看的位置。
- 最终回答使用 Markdown 渲染；思考过程和工具调用在任务结束后收缩，用户可以按需展开。

### Git Worktree 与提交

右侧 Worktree 面板展示当前项目的真实 commit graph、分支颜色、未暂存文件和本地提交状态。暂存、提交和推送是独立操作：提交只写入本地仓库，推送前需要配置远端与凭据。分支节点与会话分支保持绑定，点击节点只更新图，不会重建文件状态面板。

### 评估与经验

评估页按当前会话或当前项目读取本地持久化数据，包含 Token 使用趋势、缓存命中 Token、工具正确率和经验命中率。图表支持按日期筛选，并可在自适应时间范围和全天时间范围之间切换。反思条目可以打开 Markdown 弹窗查看对应 Bullet。

### 权限

执行需要授权的命令时，Web 会显示审批入口。可以选择仅本次、当前轮次或始终允许；没有审批时不要重复发送消息，先处理待审批项。

### 诊断

出现页面数据未更新时，先确认 Java Web 服务仍在运行，再刷新页面。Git 面板的数据来自当前工作区，评估和反思面板按当前会话或项目范围读取本地持久化数据。

## 核心能力

- 文件、命令、网络、Todo、记忆、后台任务和 Git 工具
- 权限审批与统一 Diff review
- Git checkpoint、undo 和 Worktree 状态管理
- 会话保存、恢复、分支和上下文压缩
- 多级项目指令、Skills 和 MCP
- Reflexion 自反思与 ACE Bullet 结构化经验记忆

## 数据位置

- 会话：`~/.codeauto/projects/`
- 用户记忆：`~/.codeauto/memory/`
- 项目评估：`<project>/.codeauto/evaluation/`
- 项目经验：`<project>/.codeauto/bullets/`
- 权限配置：`~/.codeauto/permissions.json`

## 项目结构

```text
src/main/java/com/codeauto/
  core/           AgentLoop 和核心消息类型
  model/          模型适配器
  tool/           工具接口和注册表
  tui/            全屏终端界面
  web/            Web API 与静态资源服务
  reflection/     Reflexion 自反思
  curator/        ACE Bullet 经验合并
  git/            Git 检查点与 Worktree
  memory/         持久化记忆
  instructions/   多级指令加载
```
