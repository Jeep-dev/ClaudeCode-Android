---
name: webview
description: 用手机系统的 WebView 打开网页：读 JS 渲染后的文字或 HTML、在页面里执行 JS、截图。网页要靠 JS 才显示内容、需要登录、或要看页面长什么样时用；普通静态页面用 curl 更省。
---

`webview` 命令用 Claude Code app 在后台开着的系统 WebView（Chromium 内核），这里不用装浏览器。
只有一个页面，在命令之间保留（Cookie 也保留）：不带网址的命令作用在已经打开的页面上。闲置 10 分钟后页面关闭。

- `webview open <网址>`：打开，输出标题和最终地址
- `webview text [网址]`：页面上看得到的文字（innerText）
- `webview html [网址]`：渲染后的 HTML。往往很长，能用 text 或 js 取需要的部分就别用它
- `webview js [网址] '<代码>'`：在页面里执行。代码是 async 函数体，用 `return` 返回结果（可以 `await`）；只有一个表达式时可以省略 `return`；`-` 从标准输入读。
  代码让页面跳转（点链接、提交表单）时，等新页面加载完才返回。
  例：`webview js "return [...document.querySelectorAll('h2')].map(e => e.innerText)"`
- `webview shot [网址] [-o 文件.png] [--full]`：截图，默认手机屏幕大小，`--full` 整页；输出图片路径，再用 Read 看图

通用选项：`--desktop`（打开电脑版网页）、`--wait 秒`（加载完再等，默认 1）、`--timeout 秒`（默认 30）、`--width` / `--height`（CSS 像素）。

本机的服务（如 `python3 -m http.server`）用 `http://127.0.0.1:端口` 打开；不支持 `file://`。
