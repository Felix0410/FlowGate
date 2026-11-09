#!/usr/bin/env python3
"""把仓库权威Markdown手册生成同源离线HTML；仅支持本文使用的基础语法，无运行时依赖。"""
import argparse
import html
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/guides/test-console-guide.md'
TARGET = ROOT / 'flowgate-control/src/main/resources/static/test-console/guide.html'


def inline(value):
    # 1. 先转义普通文本和代码，手册中的JSON/占位符不能变成HTML。
    chunks = re.split(r'(`[^`]+`)', value)
    return ''.join('<code>' + html.escape(chunk[1:-1]) + '</code>' if chunk.startswith('`') else
                   re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', html.escape(chunk)) for chunk in chunks)


def render(source):
    # 2. 文档是唯一内容来源；生成静态表格、列表和可定位目录，浏览器不需要解析Markdown。
    lines = source.splitlines()
    output, toc, paragraph = [], [], []
    anchor = None
    index = 0

    def flush():
        if paragraph:
            output.append('<p>' + inline(' '.join(paragraph)) + '</p>')
            paragraph.clear()

    while index < len(lines):
        line = lines[index].strip()
        if not line:
            flush(); index += 1; continue
        match = re.fullmatch(r'<a id="([a-z0-9-]+)"></a>', line)
        if match:
            flush(); anchor = match.group(1); index += 1; continue
        if line.startswith('```'):
            flush(); code = []; index += 1
            while index < len(lines) and not lines[index].strip().startswith('```'):
                code.append(lines[index]); index += 1
            output.append('<pre><code>' + html.escape('\n'.join(code)) + '</code></pre>'); index += 1; continue
        heading = re.match(r'^(#{1,3}) (.+)$', line)
        if heading:
            flush(); level, title = len(heading.group(1)), heading.group(2)
            identity = f' id="{anchor}"' if anchor else ''
            if level == 2 and anchor:
                toc.append(f'<a href="#{anchor}">{inline(title)}</a>')
            output.append(f'<h{level}{identity}>{inline(title)}</h{level}>'); anchor = None; index += 1; continue
        if line.startswith('|'):
            flush(); rows = []
            while index < len(lines) and lines[index].strip().startswith('|'):
                cells = [cell.strip() for cell in lines[index].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-{3,}:?', cell) for cell in cells):
                    tag = 'th' if not rows else 'td'
                    rows.append('<tr>' + ''.join(f'<{tag}>{inline(cell)}</{tag}>' for cell in cells) + '</tr>')
                index += 1
            output.append('<div class="table-scroll"><table>' + ''.join(rows) + '</table></div>'); continue
        listing = re.match(r'^(\d+\.|-) (.+)$', line)
        if listing:
            flush(); ordered = listing.group(1) != '-'; tag = 'ol' if ordered else 'ul'; items = []
            while index < len(lines):
                item = re.match(r'^(\d+\.|-) (.+)$', lines[index].strip())
                if not item or (item.group(1) != '-') != ordered:
                    break
                items.append('<li>' + inline(item.group(2)) + '</li>'); index += 1
            output.append(f'<{tag}>' + ''.join(items) + f'</{tag}>'); continue
        paragraph.append(line); index += 1
    flush()
    return ('<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>FlowGate 完整使用说明</title><link rel="stylesheet" href="style.css"></head><body class="manual">'
            '<header><h1>FlowGate 完整使用说明</h1><p>按实际页面逐项说明：先完成最小闭环，再按需抽查。无需访问GitHub。</p>'
            '<a class="manual-link" href="./">返回业务页面</a></header><nav class="manual-toc" aria-label="手册目录">' + ''.join(toc) +
            '</nav><main><article>' + '\n'.join(output) + '</article></main><footer><a href="#start">回到启动说明</a> · 本机业务手册</footer></body></html>\n')


def main():
    # 3. --check用于定向交付检查，防止只改仓库文档而漏更新网页手册。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--product', action='store_true', help='同步独立产品后台的任务式使用指南')
    parser.add_argument('--delivery', action='store_true', help='同步V11本地交付离线手册')
    args = parser.parse_args()
    if args.product and args.delivery: parser.error('--product与--delivery互斥')
    source = ROOT / 'docs/guides/product-console-guide.md' if args.product else SOURCE
    target = ROOT / 'flowgate-control/src/main/resources/static/console/guide.html' if args.product else TARGET
    if args.delivery:
        source = ROOT / 'docs/guides/v11-local-delivery.md'
        target = ROOT / 'flowgate-control/src/main/resources/static/console/local-delivery.html'
    rendered = render(source.read_text())
    if args.check:
        if not target.exists() or target.read_text() != rendered:
            raise SystemExit('网页手册未同步，请运行 python3 scripts/build-console-guide.py' + (' --delivery' if args.delivery else ' --product' if args.product else ''))
        print('PASS Markdown与同源HTML手册一致')
    else:
        target.write_text(rendered)
        print('已生成 ' + str(target.relative_to(ROOT)))


if __name__ == '__main__':
    main()
