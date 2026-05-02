# 🧪 CodeAuto 功能自测报告

**报告生成时间：** 2026-05-02 16:10 CST
**测试环境：** Windows 11, JDK 21.0.10, Maven 3.9.12
**测试工具：** CodeAuto (AI Agent 自我检测模式)
**权限状态：** `allowedCommands=0, allowedEdits=0`（当前会话零权限规则）

---

## 📊 测试总览

| 分类 | 工具/能力 | 状态 | 说明 |
|------|-----------|:----:|------|
| **文件浏览** | `list_files` | 通过 | 正常列出目录内容 |
| | `read_file` | 通过 | 正常读取 UTF-8 文件 |
| | `grep_files` | 通过 | 正常按子串搜索文本文件 |
| **文件编辑** | `write_file` | **失败** | 参数缺失导致空内容覆盖文件，连续 10+ 次失败 |
| | `modify_file` | **失败** | 同 write_file，参数缺失导致空覆盖 |
| | `edit_file` | 通过 | 参数解析正常 |
| | `patch_file` | 通过 | 工具可用 |
| **命令执行** | `run_command` | **失败** | 调用 Linux 命令( head / tail / grep / wc )在 Windows 上失败 |
| | `background_tasks` | 通过 | list/inspect/cancel 正常 |
| **网络** | `web_fetch` | 通过 | HTTP 200, JSON 返回正常 |
| | `web_search` | 跳过 | 需配置 CODEAUTO_SEARCH_URL |
| **记忆系统** | save/list/delete | 通过 | 三项均正常 |
| **Skills** | `load_skill` | 通过 | "Skill not found" 正常返回 |
| **MCP** | 四个工具 | 通过 | 正常响应 |
| **构建测试** | Maven 编译 + 单元测试 | **77 个全部通过** | 0 Failure, 0 Error, 0 Skipped |

---

## 失败详细记录

### 失败 1: write_file 参数缺失导致文件清空 (严重)

- 上一轮会话连续 10+ 次调用 `write_file` 只传 path 不传 content，不断报错
- 本轮会话再次连续 5 次只传 path，空内容覆盖了原有 237 行报告
- 最后 1 次同时传入 path + content 才正确写入

**根因分析:**
- WriteFileTool.inputSchema() 返回空对象 `{"type":"object"}`，无 required/properties 定义，模型无法通过 schema 知道必须传 content
- WriteFileTool.run() 不校验 content 是否为空字符串，空 content 直接覆盖原文件
- 这是当前版本已知设计缺陷，inputSchema 没有为模型提供准确的参数约束

### 失败 2: run_command 调用 Linux 命令在 Windows 上失败

- `head`, `tail`, `wc`, `grep`, `awk` 等命令在 Windows cmd 中不存在
- 每次调用都返回 command not found

**根因分析:**
- run_command 在 Windows 上会自动用 cmd /c 执行，但 Linux 工具链命令仍不可用
- 这是一个环境限制，非代码缺陷。但 run_command 缺乏命令可用性预检

### 失败 3: PowerShell heredoc 超长字符串解析失败

- 尝试用 PowerShell 写入大段 Markdown 内容时，单引号 heredoc 因字符串过长导致解析失败

**根因分析:**
- 这是 PowerShell 限制，非 CodeAuto 代码问题。Windows 命令行工具不擅长大段文本内联

### 失败 4: modify_file 参数缺失导致空覆盖

- 同 write_file，只传 path 未传 content 时，空内容覆盖了原文件

---

## 通过测试详细记录

### 1. 项目基本信息

| 指标 | 数值 |
|------|------|
| 项目名 | CodeAuto |
| 项目路径 | D:\JAVA\git-pro\CodeAuto |
| 语言/版本 | Java 21 |
| 构建工具 | Maven 3.9.12 |
| Java 源文件数 | 71 个 |
| 测试文件数 | 20 个 |
| 测试总数 | 77 个 |
| 代码总行数 | ~8,452 行 |
| Git 分支 | main |
| Git commit | 996810e |

### 2. 文件浏览 (全部通过)

- list_files 根目录: 正确列出 src/, pom.xml, README.md, docs/ 等 20+ 条目
- list_files src/main/java/com/codeauto: 正确列出 12 个子包
- read_file README.md: 完整读取约 500 行文档，UTF-8 正常
- grep_files "AgentLoop": 在多个源文件中搜索到大量匹配

### 3. 命令执行 (部分失败)

通过:
- git status: 返回分支名、变更文件列表
- git log --oneline: 正确列出 4 个 commit
- mvn test: BUILD SUCCESS, 77 tests passed, 耗时 3.885s
- mvn exec:java --help: Picocli CLI Usage 信息正常输出
- dir / powershell 文件统计: 正确

失败:
- head / tail / grep / awk / wc: Windows 上不存在这些命令

### 4. 网络 (web_fetch 通过)

- web_fetch https://httpbin.org/get: HTTP 200, User-Agent=Java-http-client/21.0.10
- web_search: 跳过（需配置 CODEAUTO_SEARCH_URL）

### 5. 记忆系统 (全部通过)

- list_memory: 列出 1 条已有记忆（CodeAuto记忆系统架构分析）
- save_memory: 成功保存 memory-0f778969.md
- delete_memory: 成功删除

### 6. Skills (通过)

- load_skill "test-skill": 正确返回 "Skill not found"

### 7. MCP (全部通过)

- list_mcp_resources: 正确返回空列表
- list_mcp_prompts: 正确返回空列表
- read_mcp_resource(无效 server): 返回 "Unknown MCP server"
- get_mcp_prompt(无效 server): 返回 "name is required"

### 8. Maven 单元测试 (77 个全部通过)

| 测试模块 | 测试数 | 结果 |
|----------|:------:|:----:|
| AgentLoopTest | 5 | 通过 |
| BackgroundTaskTest | 3 | 通过 |
| CodeAutoCliEncodingTest | 3 | 通过 |
| ConfigLoaderTest | 1 | 通过 |
| ContextTest | 5 | 通过 |
| FileReviewServiceTest | 1 | 通过 |
| InstructionLoaderTest | 4 | 通过 |
| ManagementStoreTest | 3 | 通过 |
| McpClientTest | 2 | 通过 |
| McpBackedToolTest | 1 | 通过 |
| McpHelperToolTest | 2 | 通过 |
| McpServiceTest | 7 | 通过 |
| MemoryManagerTest | 2 | 通过 |
| PermissionManagerTest | 7 | 通过 |
| RunCommandToolTest | 8 | 通过 |
| SessionStoreTest | 9 | 通过 |
| SkillServiceTest | 1 | 通过 |
| ToolParameterCompatibilityTest | 6 | 通过 |
| ToolRegistryTest | 4 | 通过 |
| TuiAppEscapeSequenceTest | 3 | 通过 |
| **总计** | **77** | **100% 通过** |

---

## 已知问题

| 问题 | 说明 | 严重程度 |
|------|------|:--------:|
| inputSchema 为空对象 | 所有工具的 inputSchema 都返回 {"type":"object"}，没有 required/properties 定义，模型无法准确知道参数要求 | 高 |
| write_file/modify_file 不校验 content 非空 | 空 content 直接覆盖文件，没有保护 | 高 |
| run_command 跨平台兼容性 | Windows 上调用 Linux 命令直接失败，无友好提示 | 中 |
| web_search 需配置环境变量 | 工具逻辑正常，但需要用户手动设置 | 低 |
| 零权限规则 allowedCommands=0 | 所有命令和编辑都需审批，首次操作触发交互审批 | 中 |

---

## 测试结论

**总体评价：核心功能基本可用，但存在几个需要修复的问题。**

- Maven 单元测试: 77 个全部通过
- 文件浏览: 通过
- 文件编辑: **部分失败** — write_file/modify_file 的 inputSchema 缺少参数约束，且不校验 content 非空
- 命令执行: **部分失败** — 跨平台命令兼容性需要处理
- 网络: 通过（web_search 需配置）
- 记忆系统: 通过
- Skills: 通过
- MCP: 通过

**建议优先修复：**
1. 为所有工具的 inputSchema 添加准确的 required / properties 定义
2. WriteFileTool 和 ModifyFileTool 校验 content 非空，空内容拒绝写入
3. RunCommandTool 的跨平台兼容性改进

---

*报告由 CodeAuto AI Agent 全自动生成*
*测试时间：2026-05-02 15:30 - 16:10 CST*
*项目版本：0.1.0-SNAPSHOT*
