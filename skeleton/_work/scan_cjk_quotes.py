"""定位 Java 源码里【字符串字面量内部】出现的未转义 ASCII 双引号。

这类引号是把中文引号“ ”误写成 " 造成的，会让字符串提前闭合。
本脚本只报不改，配合 --fix 才写回。
"""
import io
import os
import re
import sys

CJK = re.compile(r'[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef\u2018\u2019\u201c\u201d]')


def scan(text):
    """返回 [(offset, char_before, char_after)] —— 位于字符串字面量内部的未转义双引号。"""
    hits = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "'":
            # 字符字面量
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                if text[i] == '\n':
                    break
                i += 1
            continue
        if c == '"':
            # 字符串字面量开始
            start = i
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == '"':
                    break
                if text[i] == '\n':
                    break
                if text[i] == '"':
                    break
                i += 1
            # text[i] 应为收尾引号（或换行/EOF）
            if i < n and text[i] == '"':
                end = i
                # 检查字面量内部是否有未转义引号 —— 不可能：上面循环已在第一个引号停下。
                i = end + 1
                continue
            # 字符串在行尾未闭合 => 内部必有误用引号，交由全局扫描
            i = start + 1
            continue
        i += 1
    return hits


def scan_by_lines(path):
    """逐行扫描：字符串字面量左边界的判定用「未闭合」检测（行内无跨行字符串）。"""
    lines = io.open(path, encoding='utf-8').read().split('\n')
    out = []
    for ln, line in enumerate(lines, 1):
        st = line.lstrip()
        if st.startswith('*') or st.startswith('//') or st.startswith('/*'):
            continue
        in_str = False
        i = 0
        n = len(line)
        while i < n:
            c = line[i]
            if not in_str:
                if c == "'":
                    i += 1
                    while i < n and line[i] != "'":
                        if line[i] == '\\':
                            i += 1
                        i += 1
                    i += 1
                    continue
                if c == '"':
                    in_str = True
                i += 1
                continue
            # 在字符串内部
            if c == '\\':
                i += 2
                continue
            if c == '"':
                nxt = line[i + 1] if i + 1 < n else ''
                prev = line[i - 1] if i > 0 else ''
                # 判断这个引号是「收尾」还是「误用的中文引号」：
                # 收尾引号后面只能是 ; ) , + . 或行尾/空白/运算符
                if nxt in ('', ' ', '\t') or nxt in ');,+.=<>!&|?:[]{}':
                    in_str = False
                    i += 1
                    continue
                out.append((ln, i + 1, prev, nxt, line.strip()))
                i += 1
                continue
            i += 1
    return out


if __name__ == '__main__':
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    targets = []
    for base in sys.argv[1:]:
        p = base if os.path.isabs(base) else os.path.join(root, base)
        if os.path.isdir(p):
            for r, d, fs in os.walk(p):
                for f in fs:
                    if f.endswith('.java'):
                        targets.append(os.path.join(r, f))
        elif os.path.exists(p):
            targets.append(p)
    total = 0
    for t in sorted(targets):
        hits = scan_by_lines(t)
        if hits:
            total += len(hits)
            print('=== %s  (%d)' % (os.path.relpath(t, root).replace('\\', '/'), len(hits)))
            for ln, col, prev, nxt, txt in hits:
                print('  %d:%d  prev=%r next=%r | %s' % (ln, col, prev, nxt, txt[:110]))
    print('\nTOTAL suspicious quotes inside string literals =', total)