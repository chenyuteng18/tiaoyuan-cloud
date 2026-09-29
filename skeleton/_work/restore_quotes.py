"""精确还原：把【非注释区】内被误改成 “ ” 的引号还原为 ASCII "。

用途：修复 fix_cjk_quotes.py 早期版本（终止符集合漏了 `/`）造成的破坏 ——
它把「合法收尾引号 + 紧跟行尾注释」误判为误用，进而状态机持续失准，
把 12 个原本编译通过的既有文件改坏。

判据依据（为什么这个还原是对的）：
  这 12 个文件在破坏前【全量测试 1162 例全绿】⇒ 它们原本编译通过 ⇒
  其字符串字面量内部【不可能】含中文引号（那会导致编译失败）。
  故这些文件里，非注释区出现的 “ ” 必然是结构性引号 `"` 被误改的结果。

🛑 状态机规则（三态：代码 / 字符串 / 注释）：
   - 代码态：`"` → 进入字符串；`“ ”` → 还原为 `"` 并进入字符串。
   - 字符串态：`"` → 退出；`“ ”` → 还原为 `"` 并退出；
              `\\` 跳过 2 字符（转义）；换行 → 兜底退出（Java 字符串不跨行）。
   - 注释态：整段原样保留（注释里本来就有中文引号）。
"""
import io
import os
import sys

CN = ('\u201c', '\u201d')          # “ ”


def restore_text(text):
    """返回 (新文本, 还原处数)。"""
    chars = list(text)
    n = len(text)
    i = 0
    in_string = False
    in_block = False
    count = 0
    while i < n:
        c = text[i]

        # ---------------- 块注释 ----------------
        if in_block:
            if c == '*' and i + 1 < n and text[i + 1] == '/':
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if c == '/' and i + 1 < n and text[i + 1] == '*':
            in_block = True
            i += 2
            continue

        # ---------------- 代码态 ----------------
        if not in_string:
            if c == '/' and i + 1 < n and text[i + 1] == '/':
                while i < n and text[i] != '\n':
                    i += 1
                continue                                   # 行尾注释整体保留
            if c == "'":                                   # 字符字面量
                i += 1
                while i < n:
                    if text[i] == '\\':
                        i += 2
                        continue
                    if text[i] == "'" or text[i] == '\n':
                        i += 1
                        break
                    i += 1
                continue
            if c == '"':
                in_string = True
                i += 1
                continue
            if c in CN:
                chars[i] = '"'
                in_string = True
                count += 1
                i += 1
                continue
            i += 1
            continue

        # ---------------- 字符串态 ----------------
        if c == '\\':
            i += 2
            continue
        if c == '"':
            in_string = False
            i += 1
            continue
        if c in CN:
            chars[i] = '"'
            in_string = False
            count += 1
            i += 1
            continue
        if c == '\n':
            in_string = False                              # 兜底：字符串不跨行
            i += 1
            continue
        i += 1

    return ''.join(chars), count


def main(paths, root):
    total = 0
    for p in paths:
        f = p if os.path.isabs(p) else os.path.join(root, p)
        if not os.path.exists(f):
            print('  !! 不存在:', p)
            continue
        raw = io.open(f, encoding='utf-8').read()
        new, cnt = restore_text(raw)
        rel = os.path.relpath(f, root).replace('\\', '/')
        if cnt:
            with io.open(f, 'w', encoding='utf-8', newline='') as fh:
                fh.write(new)
        total += cnt
        print('  %-76s 还原 %d 处' % (rel, cnt))
    print('\n还原合计 =', total)


if __name__ == '__main__':
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1:], root)