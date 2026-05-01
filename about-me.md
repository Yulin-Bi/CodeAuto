# 🚀 CodeAuto

> **Java 21 原生的 CodeAuto AI 编程助手运行时**

---

## 📋 项目简介

CodeAuto 是一个基于 **Java 21** 构建的轻量级 AI 编程助手运行时，面向 Java 生态并借鉴现代 AI 编程助手的交互范式进行开发。它运行在命令行终端中，提供全屏 TUI 交互界面，支持工具调用、权限管理、会话管理、Skills 扩展和 MCP 协议集成。

| 项目维度 | 说明 |
|---------|------|
| **语言** | Java 21 |
| **构建工具** | Maven 3.9+ |
| **代码规模** | 66 个源文件，~6664 行代码 |
| **测试** | 50 个测试，全部通过 ✅ |
| **包名** | `com.codeauto` |
| **命令名** | `codeauto` |
| **配置目录** | `~/.codeauto/` |

---

## 🧩 技术架构

```
┌─────────────────────────────────────────────────┐
│                  TUI (JLine 3)                   │
│   ┌─────────┬──────────────┬────────┐          │
│   │ Header  │  Transcript  │ Prompt │          │
│   └─────────┴──────────────┴────────┘          │
├─────────────────────────────────────────────────┤
│              AgentLoop (核心循环)                 │
│   ┌───────────┐  ┌──────────┐  ┌───────────┐  │
│   │ Model     │→│ Tool     │→│ Session   │  │
│   │ Adapter   │  │ Registry │  │ Store     │  │
│   └───────────┘  └──────────┘  └───────────┘  │
├─────────────────────────────────────────────────┤
│              内置工具 (14 个)                     │
│   📂 文件  🔍 搜索  💻 命令  🌐 网络  🧠 Skill │
├─────────────────────────────────────────────────┤
│               Skills & MCP 扩展                  │
│   ┌──────────────┐  ┌──────────────────────┐   │
│   │ SkillService │  │ McpService           │   │
│   │ .code-auto/  │  │ .mcp.json + stdio    │   │
│   │ .claude/     │  │ Streamable HTTP      │   │
│   └──────────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────────┘
```

### 核心技术栈

| 组件 | 选型 |
|------|------|
| **CLI 框架** | Picocli |
| **序列化** | Jackson (JSON/JSONL) |
| **HTTP 客户端** | Java HttpClient |
| **终端控制** | JLine 3 (raw mode, Reader, 窗口尺寸检测) |
| **Diff 生成** | java-diff-utils |
| **测试框架** | JUnit 5 |
| **模型 API** | Anthropic Messages API (可扩展) |

---

## ✨ 核心功能

### 1. 🤖 多模型适配
- `MockModelAdapter` — 离线 mock 模式，无需 API Key 即可完整跑通工具循环
- `AnthropicModelAdapter` — 支持 Anthropic Messages API，含 429/5xx 自动重试 + exponential backoff

### 2. 🛠 14 个内置工具

| 类别 | 工具 |
|------|------|
| **文件操作** | `list_files` `grep_files` `read_file` `write_file` `edit_file` `patch_file` `modify_file` |
| **执行与交互** | `run_command` `ask_user` `background_tasks` |
| **网络** | `web_fetch` `web_search` |
| **扩展** | `load_skill` `mcp_helper` |

### 3. 🖥 全屏终端界面 (TUI)
基于 JLine 3 纯 ANSI 转义序列渲染，无第三方 TUI 框架：
- **面板布局**：Header / Transcript / Prompt / Footer
- **Markdown 渲染**：代码块、表格、标题、列表、内联代码 → ANSI 彩色
- **CJK 支持**：中文输入、字符显示宽度精确计算
- **交互菜单**：斜杠命令补全、交互式 session 选择器、权限审批弹窗
- **会话管理**：滚动、展开/折叠工具输出、Ctrl+O 切换

### 4. 🔐 权限与安全
- 危险命令检测 + 三层权限决策（allow/deny/feedback）
- `PermissionStore` 持久化到 `~/.codeauto/permissions.json`
- `FileReviewService`：修改前生成 unified diff，终端确认
- "Deny with Feedback" 模式方便模型调整策略

### 5. 💾 会话管理
- JSONL append-only 格式，按工作目录隔离
- 支持：恢复 (`resume`)、分叉 (`fork`)、重命名 (`rename`)、新建 (`new`)、压缩 (`compact`)
- 自动上下文压缩 + 微压缩 (`CompactService` + `MicroCompactService`)
- 超大工具结果落盘 (`ToolResultStorage`)
- 30 天过期清理

### 6. 🧩 Skills & MCP 扩展
- **Skills**：从 `.code-auto/skills` 和 `.claude/skills` 自动发现
- **MCP 协议**：支持 stdio（Content-Length 帧 + newline JSON 帧，自动协商）、Streamable HTTP
- **MCP 工具**：自动发现并包装为 `ToolDefinition`
- **辅助工具**：`list_mcp_resources` `read_mcp_resource` `list_mcp_prompts` `get_mcp_prompt`
- **Bearer token 管理**：mcp login/logout，环境变量插值 (`$VAR`)

---

## 🚀 快速启动

```bash
# 真实模型模式：先配置 CODEAUTO_BASE_URL / CODEAUTO_AUTH_TOKEN / CODEAUTO_MODEL
mvn exec:java

# 全屏 TUI 模式
mvn exec:java "-Dexec.args=--tui"

# 离线 mock 模式（无需 API Key，适合自测）
mvn exec:java "-Dexec.args=--mock --tui"

# 构建 fat JAR 并启动
mvn package -DskipTests
java -jar target/codeauto-0.1.0-SNAPSHOT-shaded.jar --tui

# 使用启动脚本
bin/codeauto --tui
```

---

## 📂 项目结构

```
codeauto/
├── src/
│   ├── main/java/com/codeauto/
│   │   ├── cli/          # Picocli CLI 入口 + 命令
│   │   ├── core/         # 核心类型 (ChatMessage, ToolCall, AgentStep...)
│   │   ├── agent/        # AgentLoop 主循环
│   │   ├── tools/        # 14 个内置工具实现
│   │   ├── model/        # 模型适配器接口 + Anthropic/Mock 实现
│   │   ├── permission/   # 权限管理 + FileReview
│   │   ├── session/      # 会话存储、上下文压缩
│   │   ├── tui/          # JLine 全屏终端界面
│   │   ├── skill/        # Skills 服务
│   │   └── mcp/          # MCP 客户端 + 服务
│   └── test/             # 50 个单元测试
├── docs/                 # 文档
├── bin/                  # 启动脚本
├── pom.xml               # Maven 配置
└── README.md             # 使用说明
```

---

## 📊 完成状态

| 阶段 | 状态 | 说明 |
|------|------|------|
| Phase 1: 核心运行时 | ✅ 完成 | AgentLoop, 核心类型, MockModelAdapter |
| Phase 2: 内置工具 | ✅ 完成 | 14 个工具全部实现并接入 diff review |
| Phase 3: 模型适配器 | ✅ 完成 | Anthropic + Mock，含重试与 backoff |
| Phase 4: 权限与 Review | ✅ 完成 | 决策流程、持久化、unified diff |
| Phase 5: 会话与压缩 | ✅ 完成 | JSONL 存储、resume/fork/compact |
| Phase 6: JLine TUI | ✅ 完成 | 全屏面板、Markdown 渲染、CJK 支持 |
| Phase 7: Skills & MCP | ✅ 完成 | Skills 发现、MCP stdio/HTTP、辅助工具 |
| Phase 8: 配置与启动 | ✅ 完成 | 多级配置、CLI 标志、启动脚本 |

---

## 🔗 技术亮点

- **纯 Java 21**：无 Spring/Quarkus 等重型框架，单体结构
- **纯 ANSI 渲染**：无第三方 TUI 框架，直接通过 ANSI 转义序列绘制界面
- **协议自动协商**：MCP stdio 自动尝试 `content-length` → `newline-json` 回退
- **本地 Token 估算**：`TokenEstimator` 作为 API usage 的 fallback
- **配置分层覆盖**：默认值 → 环境变量 → 项目 settings → 用户 settings → CLI 标志

---

> *"CodeAuto — 把 AI 编程助手的灵魂，装进 JVM 的躯壳里。"* 🚀
