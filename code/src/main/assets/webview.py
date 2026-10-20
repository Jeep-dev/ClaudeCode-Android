"""webview: web pages opened in the phone's own WebView, which the Claude Code app keeps off screen
(Browser.java), read from the userland, where there is no browser. One page, kept between commands
with its cookies: a command without an address works on the page already open. Installed by the
app as /usr/local/bin/webview."""

import argparse
import base64
import json
import socket
import sys
import time

SOCKET = '/root/.ccapp/webview.sock'


def ask(request, timeout):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout + 30)
    try:
        s.connect(SOCKET)
    except OSError:
        sys.exit('webview: 连不上 app 的 WebView（Claude Code app 没在运行，或版本太旧）')
    with s:
        s.sendall((json.dumps(request, ensure_ascii=False) + '\n').encode())
        data = b''
        while not data.endswith(b'\n'):
            chunk = s.recv(1 << 16)
            if not chunk:
                break
            data += chunk
    if not data:
        sys.exit('webview: app 没有回应')
    return json.loads(data)


def main():
    p = argparse.ArgumentParser(prog='webview', description='用手机系统的 WebView 打开、读取网页。只有一个页面，'
                                '在命令之间保留（Cookie 也保留）；不带网址的命令作用在已经打开的页面上。')
    sub = p.add_subparsers(dest='cmd', required=True, metavar='命令')

    def command(name, help, url='optional'):
        c = sub.add_parser(name, help=help, description=help)
        if url == 'required':
            c.add_argument('url', help='网址')
        elif url == 'optional':
            c.add_argument('url', nargs='?', help='网址；省略时用已经打开的页面')
        c.add_argument('--wait', type=float, default=1, metavar='秒', help='加载完再等几秒，让页面的脚本跑完（默认 1）')
        c.add_argument('--timeout', type=float, default=30, metavar='秒', help='最多等多久（默认 30）')
        c.add_argument('--mobile', action='store_true', help='打开网址时用手机版（默认电脑版，宽 1280）')
        c.add_argument('--width', type=int, metavar='px', help='页面宽度，CSS 像素（默认 1280，手机版为屏幕宽）')
        c.add_argument('--height', type=int, metavar='px', help='页面高度，CSS 像素（默认 800，手机版为屏幕高）')
        return c

    command('open', '打开网址，输出标题和最终地址', url='required')
    command('text', '输出页面上的文字（innerText）')
    command('html', '输出渲染后的 HTML')
    js = command('js', '在页面里执行 JS，输出返回值', url=None)
    js.add_argument('args', nargs='+', metavar='[网址] 代码',
                    help='代码是 async 函数体，用 return 返回（可以 await）；只有一个表达式时可省略 return；- 从标准输入读')
    shot = command('shot', '截图，输出图片路径')
    shot.add_argument('-o', '--out', metavar='文件', help='PNG 存到哪里（默认 /tmp/webview-时间.png）')
    shot.add_argument('--full', action='store_true', help='整页（最高 8000 CSS 像素）')
    shot.add_argument('--scale', type=float, help='每 CSS 像素几个图片像素（默认电脑版 1，手机版 2）')
    a = p.parse_args()

    req = {'cmd': a.cmd, 'wait': a.wait, 'timeout': a.timeout, 'desktop': not a.mobile}
    if a.width:
        req['width'] = a.width
    if a.height:
        req['height'] = a.height
    if a.cmd == 'js':
        if len(a.args) > 2:
            p.error('js：多余的参数（代码要用引号括起来）')
        url, code = (a.args[0], a.args[1]) if len(a.args) == 2 else (None, a.args[0])
        if code == '-':
            code = sys.stdin.read()
        if 'return' not in code and ';' not in code and '\n' not in code.strip():
            code = 'return (' + code + ')'
        req['code'] = code
    else:
        url = a.url
    if url:
        req['url'] = url
    if a.cmd == 'shot':
        req['full'] = a.full
        if a.scale:
            req['scale'] = a.scale

    r = ask(req, a.timeout)
    if not r.get('ok'):
        sys.exit('webview: ' + str(r.get('error')))
    if r.get('warning'):
        print('webview: ' + r['warning'], file=sys.stderr)
    if r.get('status'):
        print('webview: HTTP %s' % r['status'], file=sys.stderr)
    if a.cmd == 'open':
        print(r.get('title') or '（无标题）')
        print(r.get('url') or '')
    elif a.cmd == 'shot':
        out = a.out or time.strftime('/tmp/webview-%Y%m%d-%H%M%S.png')
        with open(out, 'wb') as f:
            f.write(base64.b64decode(r['png']))
        print(out)
    else:
        v = r.get('result')
        if isinstance(v, str):
            print(v)
        elif v is not None:
            print(json.dumps(v, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
