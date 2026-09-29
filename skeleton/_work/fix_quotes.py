# -*- coding: utf-8 -*-
"""A-1 缺陷修复：中文叙述文本内嵌 ASCII 双引号 -> 『』。

背景（本仓缺陷登记同族）：Java 字符串字面量里若内嵌未转义的 ASCII `"`，
字符串会被提前闭合，其后中文变成裸代码（"不是语句/需要';'"）。
本仓既有处置：中文叙述里一律用中文引号。

规则：
  - 词法状态机辨别 code / str / chr / line-comment / block-comment；
  - 仅在 str 状态内、遇到 `"` 时判断：若其后继（跳过空白/换行）不是
    `+ , ) ; . :` / 行尾 / 另一个 `"`，则该 `"` 是【叙述引号】而非闭合引号；
  - 叙述引号按【同一字符串字面量内】出现顺序交替替换为 『 / 』。
"""
import sys

PATH = sys.argv[1]
DRY = (len(sys.argv) < 3 or sys.argv[2] != '--apply')

src = open(PATH, encoding='utf-8').read()
n = len(src)

LEGAL_AFTER_CLOSE = set('+,);.:')
out = []
i = 0
state = 'code'          # code | str | chr | line | block
lit_open_idx = None
narr_count = 0          # 当前字符串字面量内已替换的叙述引号数
changes = []

def peek_nonspace(j):
    """返回 (char, next_index) —— 跳过空白、换行、以及【注释】；到末尾返回 ('', n)。

    🛑 必须跳过注释：一个字符串字面量的闭合引号后面完全可能紧跟一行注释
    （本文件就有这种排版）。若不跳过，后继会是 `/`，于是闭合引号被误判成
    叙述引号 —— 而且状态机会随之错乱，把后面的注释当字符串内容一起改掉。
    """
    while j < n:
        c = src[j]
        if c in ' \t\r\n':
            j += 1
            continue
        if c == '/' and j + 1 < n and src[j + 1] == '/':
            k = src.find('\n', j)
            j = n if k < 0 else k + 1
            continue
        if c == '/' and j + 1 < n and src[j + 1] == '*':
            k = src.find('*/', j + 2)
            j = n if k < 0 else k + 2
            continue
        return (c, j)
    return ('', n)

while i < n:
    c = src[i]
    nxt = src[i + 1] if i + 1 < n else ''

    if state == 'code':
        if c == '/' and nxt == '/':
            state = 'line'; out.append(c); out.append(nxt); i += 2; continue
        if c == '/' and nxt == '*':
            state = 'block'; out.append(c); out.append(nxt); i += 2; continue
        if c == '"':
            state = 'str'; lit_open_idx = len(out); narr_count = 0
            out.append(c); i += 1; continue
        if c == "'":
            state = 'chr'; out.append(c); i += 1; continue
        out.append(c); i += 1; continue

    if state == 'line':
        out.append(c)
        if c == '\n':
            state = 'code'
        i += 1; continue

    if state == 'block':
        if c == '*' and nxt == '/':
            out.append(c); out.append(nxt); state = 'code'; i += 2; continue
        out.append(c); i += 1; continue

    if state == 'str':
        if c == '\\':
            out.append(c)
            if i + 1 < n:
                out.append(src[i + 1])
            i += 2; continue
        if c == '"':
            nx, nj = peek_nonspace(i + 1)
            # 🛑 判定「闭合引号」的唯一依据 = 后继是语法边界。
            #    特例：`...没签""` 这种「叙述引号紧邻真正闭合引号」——此时当前 `"`
            #    的后继也是 `"`，它不是边界 ⇒ 正确判为叙述引号（紧接的那个才是闭合）。
            #    `""`（空串）不冲突：空串的第二个 `"` 后继本身是边界。
            if nx == '' or nx in LEGAL_AFTER_CLOSE:
                state = 'code'; out.append(c); i += 1; continue
            # 叙述引号：交替 『 / 』
            rep = '\u300e' if narr_count % 2 == 0 else '\u300f'
            changes.append((i, c, rep))
            out.append(rep); narr_count += 1; i += 1; continue
        out.append(c); i += 1; continue

    if state == 'chr':
        if c == '\\':
            out.append(c)
            if i + 1 < n:
                out.append(src[i + 1])
            i += 2; continue
        out.append(c)
        if c == "'":
            state = 'code'
        i += 1; continue

new = ''.join(out)
print("changes:", len(changes))
old_lines = src.split('\n')
new_lines = new.split('\n')
diff = 0
for idx, (a, b) in enumerate(zip(old_lines, new_lines), 1):
    if a != b:
        diff += 1
        print(f"L{idx}")
        print(f"  - {a.strip()[:150]}")
        print(f"  + {b.strip()[:150]}")
print("changed lines:", diff, "| total lines old/new:", len(old_lines), len(new_lines))

if not DRY:
    open(PATH, 'w', encoding='utf-8', newline='\n').write(new)
    print("APPLIED")