# ClaudeCode-Android

在 Android 手机上直接用 [Claude Code](https://docs.anthropic.com/claude-code) 的 App，不需要 Termux。

App 自带一个小型 Linux 环境（Alpine + proot），Claude Code 就跑在里面；界面是原生 Android 控件，不是 WebView，滚动和选字都是系统自己的。

## 功能

- **对话**：流式回复、思考过程、工具步骤（命令、文件改动的 diff、待办列表）都按 Claude Code 终端里的样子折叠成行；回复中可以继续发消息（插入或排队）。
- **对话列表**：侧边抽屉列出 Claude Code 自己保存的历史对话，可搜索、置顶、删除；任意位置右滑打开。
- **附件**：图片和文件从系统选择器添加，文件存进工作目录。
- **斜杠命令**：`/model`、`/effort`、`/folder`、`/find`、`/tree`（回退到某条消息）等由 App 处理，其余（`/compact`、`/context`、技能……）照原样交给 Claude Code。
- **终端**：内置终端（基于 Termux 的终端组件），可以 `apk add` 装软件，或者直接运行 `claude`。
- **设置**：API 地址和 Key、模型、权限模式、系统提示、CLAUDE.md、技能、工具开关、MCP 服务器、后台保活。
- **外观**：Claude 配色和 RikkaHub 的几套配色，各有浅色和深色，深色可以用纯黑。默认是 Claude 配色，深浅跟随系统。

## 安装

- 要求：Android 8.0 及以上，arm64 设备。
- 在 [Releases](../../releases) 下载最新的 `ClaudeCode-v*.apk` 安装。
- 第一次打开会下载并解压运行环境（约半分钟），然后填 API 地址和 Key；也可以从 Termux 导入原来的配置，或者在终端里用 `claude` 的 `/login` 登录 Claude 订阅。

## 构建

GitHub Actions（`.github/workflows/android.yml`）在每次推送时构建运行环境和签名的 APK。代码在 `code/`，实现细节见 [code/README.md](code/README.md)。
