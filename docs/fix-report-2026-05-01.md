# CodeAuto 完成度检查与修复记录

日期：2026-05-01

## 完成度检查

根据 `plan.md` 和 `codeauto/README.md` 对照检查，Java 版本已经覆盖计划中的主要范围：

- 核心运行时、工具循环、模型适配器、权限流程、会话存储、上下文压缩、全屏终端界面、Skills、MCP、配置加载和启动脚本均已实现。
- 文档中声明的测试目标通过：50 个测试，0 个失败。
- Maven package 目标可以构建可运行的 shaded jar。

## 发现的问题

### 普通命令行界面在 session 持久化失败时崩溃

复现命令：

```bash
"list files`n/exit" | mvn exec:java "-Dexec.args=--mock"
```

修复前现象：

- mock 模型已经完成工具调用。
- `SessionStore.save` 写入 `~/.codeauto/projects/` 失败。
- 异常从 `CodeAutoCli.run` 向外抛出，导致命令以 exit code 1 退出，并且没有打印最终助手回答。

这类问题可能出现在沙箱环境、用户目录被锁定、权限不足或会话目录暂时不可写的情况下。会话保存失败不应该打断已经完成的对话回合。

## 已完成修复

- 更新 `CodeAutoCli`，将普通命令行界面中的 session 写入改为尽力保存。
- 新增安全包装方法：
  - session save
  - session rename
  - compact boundary save
- 为 `/sessions` 和 `/projects` 增加异常保护。
- 当持久化不可用时，命令行界面现在会打印警告，然后继续输出模型回答。
- 将 `README.md` 中容易在终端编码不匹配时显示乱码的非 ASCII 破折号替换为 ASCII 标点。

## 验证记录

执行命令：

```bash
mvn test
mvn package -DskipTests
"list files`n/exit" | mvn exec:java "-Dexec.args=--mock"
java -jar target\codeauto-0.1.0-SNAPSHOT.jar --help
java -jar target\codeauto-0.1.0-SNAPSHOT-shaded.jar --help
```

验证结果：

- `mvn test`：通过，50 个测试。
- `mvn package -DskipTests`：通过。
- mock 普通命令行界面冒烟测试：通过；session 持久化失败现在会显示警告，不再导致崩溃。
- 两个 jar 的 `--help` 均能正常输出 CLI usage。

## 后续修复

### 全屏终端界面的会话记录滚动和窗口缩放

问题：

- 鼠标滚轮无法滚动会话记录面板。原因是 SGR mouse 事件解析把第三个字段当作按钮码，但 SGR mouse 实际格式为 `button;col;row`，所以滚轮事件 `64` 和 `65` 没有被识别。
- PowerShell 或终端窗口放大缩小时，界面空闲状态下不会立即重绘。原因是之前只在下一次按键后检查终端尺寸。

修复：

- 修正 `TuiApp` 中的 SGR mouse 解析逻辑。
- 增加 JLine `WINCH` 窗口尺寸变化处理器，终端尺寸变化时立即重绘。
- 允许 agent 忙碌时继续处理转义序列滚动事件，因此 PageUp/PageDown 和鼠标滚轮可以在工具运行期间滚动会话记录。
- 将全屏终端界面导航操作说明整理到 `README.md`。

验证命令：

```bash
mvn test
```

验证结果：通过，50 个测试。

## 备注

- 根目录 `plan.md` 在当前终端读取时出现 mojibake，但项目源码和测试可正常用于完成度核验。
- 全屏终端界面的多数 session 操作本来已经在界面内捕获异常并显示错误信息；本次可复现的崩溃路径主要发生在普通命令行界面。

## 真实 API 工具调用修复

### 创建本地 Markdown 文件时报 `thinking` 回传错误

问题：

- 使用 DeepSeek Anthropic 兼容接口时，让模型创建本地 Markdown 文件会触发工具调用。
- 模型响应中可能包含 `thinking` 内容块和 `tool_use` 内容块。
- 旧实现只把文本和工具调用拆成内部消息，没有把 provider 返回的原始 assistant content 数组保存下来。
- 下一次请求携带工具结果时，接口要求把 `thinking` 块原样传回，因此返回 400：`The content ... thinking ... must be passed back to the API`。

修复：

- 新增 `ChatMessage.AssistantRawMessage`，用于保存 provider 返回的原始 assistant content 数组。
- 扩展 `AgentStep.ToolCallsStep`，携带 `rawContent`。
- `AnthropicModelAdapter` 解析工具调用时保留完整 `content` 数组，包括 `thinking`、`text`、`tool_use` 等块。
- `AgentLoop` 在工具调用回合中优先保存 `AssistantRawMessage`，再追加工具结果，保证下一次请求能原样回传。
- `AnthropicModelAdapter` 发送历史消息时识别 `AssistantRawMessage`，直接写回原始 content 数组。
- 更新会话恢复、上下文估算、压缩摘要和 TUI transcript 重建逻辑，使新消息类型可以正常序列化、显示和估算。
- 新增测试覆盖原始 assistant content 的保存路径，防止后续回归。

验证命令：

```bash
mvn test
```

验证结果：通过，51 个测试。

## 全屏终端界面布局修复

### 顶部栏被中间会话记录面板挤掉

问题：

- TUI 原先用固定公式 `termHeight - 20` 估算中间会话记录面板高度。
- 当底部 prompt、工具面板或其他面板变高时，总渲染行数可能超过终端高度。
- 终端发生滚屏后，顶部 CodeAuto 栏会被挤出可视区域，看起来像是消失。

修复：

- 渲染时先生成顶部栏、工具面板和底部交互面板。
- 根据这些固定区域的实际行数计算剩余高度。
- 将中间会话记录面板限制在剩余高度内，避免整屏输出超过终端高度。
- 保留已有的会话记录滚动逻辑，内容超出时继续通过 PageUp/PageDown、Alt/Ctrl+方向键或鼠标滚轮查看。

验证命令：

```bash
mvn test
```

验证结果：通过，51 个测试。

## TUI 滚动、中文输入法和多工具结果修复

### 会话记录最后一行始终显示为 `1 more line`

问题：

- 当会话记录处于底部并且上方还有历史内容时，顶部的 “more line” 提示会占用一行。
- 旧的自动滚动偏移量按完整面板高度计算，没有扣掉这条顶部提示，因此最后一行回复会被挤到下方提示里，表现为始终还有 `1 more line` 看不到。

修复：

- 自动滚到底部时，将顶部滚动提示占用的行数纳入偏移量计算。
- 底部状态现在会真正显示最后一行内容，不再把最后一行留在 `1 more line` 后面。

### 中文输入法候选框出现在右下角状态栏附近

问题：

- TUI 使用反色字符绘制“视觉光标”，但真实终端光标停在最后一次输出位置，也就是底部状态栏附近。
- Windows 中文输入法会跟随真实终端光标位置，因此候选框出现在右下角 `skills` 状态后面。

修复：

- 每次渲染结束后，将真实终端光标移动到 prompt 输入行的视觉光标位置。
- 光标仍然保持隐藏，界面视觉上继续使用反色光标，但输入法可以跟随正确位置。
- 输入较长并发生换行时，会按输入框宽度计算真实光标所在行列。

### 多个工具调用结果被拆成多条 user message

问题：

- Anthropic/DeepSeek 要求 assistant 的 `tool_use` 后，下一条 user message 必须包含所有对应的 `tool_result` block。
- 旧实现会把连续的 `ToolResultMessage` 分别序列化为多条 user message。
- 当模型为了创建 Markdown 文档先检查目录、再读取 README、再编辑/写入文件时，可能出现多个工具调用，接口返回 400：`tool use ids were found without tool_result blocks immediately after`。

修复：

- `AnthropicModelAdapter` 组装请求时会合并连续的 `ToolResultMessage`。
- 合并后的下一条 user message 中包含所有 `tool_result` block，满足 Anthropic/DeepSeek 的工具调用协议。

验证命令：

```bash
mvn test
```

验证结果：通过，51 个测试。

## 工具自测报告修复

来源：`tool-test-report.md`

### `read_file` 参数别名不兼容

问题：

- 自测报告指出工具 schema 和实际实现存在理解偏差。
- 模型可能使用 `file_path`，但 `read_file` 只读取 `path`，导致返回 `path is required`。

修复：

- `read_file` 继续支持标准参数 `path`。
- 新增兼容别名 `file_path`。
- 新增测试覆盖 `file_path` 别名读取。

### 非交互环境下文件写入工具被权限层拒绝

问题：

- `write_file`、`modify_file`、`edit_file`、`patch_file` 在模型自测或 Maven 执行这类无真实控制台环境中，会经过 `ConsolePermissionPrompt`。
- 旧逻辑在没有 `System.console()` 时直接返回 `DENY_ONCE`，导致工作区内安全写入也被拒绝。
- TUI 场景本身有独立审批弹窗，不受这个问题影响。

修复：

- `ConsolePermissionPrompt` 在无真实控制台时，对 `edit` 类型请求默认允许一次。
- `PermissionManager.canWrite` 仍会先验证路径必须在允许读取的工作区内，因此工作区外路径仍然拒绝。
- 文件 diff review 在有真实控制台时继续二次确认；无控制台时跳过二次确认，适合自动化和模型工具调用。
- 新增测试覆盖非交互工作区写入成功和工作区外写入拒绝。

### 配置和平台说明补充

处理：

- 在 `README.md` 中补充 `web_search` 需要 `CODEAUTO_SEARCH_URL`。
- 在 `README.md` 中补充 Windows 下 `head`、Unix `find` 等命令不可用时的 PowerShell 等价命令。
- 在 `README.md` 中补充 PowerShell UTF-8 输出设置，减少中文或符号乱码。

验证命令：

```bash
mvn test
```

验证结果：通过，55 个测试。

## 第二轮工具自测报告修复

来源：`tool-test-report.md` 第二轮报告。

### `modify_file` / `edit_file` 与 `write_file` 权限表现不一致

问题：

- 第二轮报告显示 `write_file` 已经可以创建文件，但 `modify_file` 和 `edit_file` 仍返回 `Edit rejected by user.`。
- 根因不是权限层拒绝，而是 `FileReviewService` 在权限通过后又尝试使用 `System.console()` 做第二次 diff 确认。
- 在 TUI/JLine 或模型自测场景中，这个底层控制台确认不可见或无法交互，导致修改被拒绝。
- `edit_file` 还额外提前调用了一次 `canWrite`，在 “allow once” 语义下会造成重复审批。

修复：

- 移除 `FileReviewService` 的隐藏二次控制台确认，统一以权限层审批为准。
- 工具结果仍然返回 unified diff，调用者可以看到实际改动。
- 移除 `edit_file` 中的提前 `canWrite`，所有写入工具统一由 `FileReviewService` 做一次权限检查。
- 新增测试覆盖 `modify_file` 和 `edit_file` 在非交互工作区内可正常修改文件。

### `patch_file` 在 Windows CRLF / 行尾空白场景下匹配失败

问题：

- 第二轮报告显示 `patch_file` 在 Windows 生成的文件上报 `Patch removal line not found`。
- 原实现做逐行精确匹配，遇到 CRLF 归一化差异或行尾空白差异时容易找不到删除行或上下文行。

修复：

- `patch_file` 读取原文件后先将 CRLF/CR 归一化为 LF 进行匹配。
- 写回时根据原文件内容尽量保留 CRLF 或 LF 风格。
- 上下文行和删除行匹配时允许行尾空白差异，提升 Windows 文件兼容性。
- 新增测试覆盖 CRLF 文件和行尾空白删除行。

验证命令：

```bash
mvn test
```

验证结果：通过，57 个测试。

## 第三轮工具自测报告修复

来源：`tool-test-report.md` 第三轮报告。

### 长任务容易达到单轮工具调用上限

问题：

- 第三轮报告指出，在“自测所有功能并写入报告”这类长任务中，Agent 可能在完成所有检查和写入前达到单轮工具步数上限。
- 原 CLI 默认 `--max-steps` 为 8，对于连续读取、搜索、执行命令、写文件的任务偏紧。

修复：

- 将 CLI 默认 `--max-steps` 从 8 提高到 32。
- 在 `README.md` 中补充 `--max-steps` 的使用方式，例如复杂任务可手动提高到 64。

### Windows `cmd` 内置命令需要手动加 `cmd /c`

问题：

- 第三轮报告指出 Windows 下直接调用 `dir`、`type` 等命令会失败，因为它们是 `cmd.exe` 内置命令，不是独立可执行文件。
- 旧实现只有在命令中出现 `&&`、管道等 shell 语法时才自动走 shell。

修复：

- `run_command` 在 Windows 下识别常见 `cmd` 内置命令。
- 对 `dir`、`type`、`copy`、`del`、`ren`、`move`、`mkdir`、`rmdir`、`echo`、`ver` 等命令自动使用 `cmd /c` 执行。
- 新增 Windows 条件测试覆盖直接执行 `dir` 的场景。
- 在 `README.md` 中更新 Windows 命令说明。

验证命令：

```bash
mvn test
```

验证结果：通过，58 个测试。
