"""修复 Java 源码里被误用为中文引号的 ASCII 双引号（词法状态机，跨行正确）。

背景：本仓断言消息大量使用中文引号「“ ”」。误敲成 ASCII 直引号后，
字符串会提前闭合，编译器从该行起连锁报「需要 ')' / 需要 ';'」。

算法（一个跨行维护状态的词法扫描器，而不是逐行启发式）：
  1. 维护 in_string 状态，跨行保持 —— 这样「一条数据脏了」「租户 A…」这类
     跨行引号对也能正确配对（逐行奇偶法在这里必然错）。
  2. 在字符串内部遇到未转义 " 时，看其后第一个非空白字符：
     - 是终止符（; , ) + ] = * & | ? : < > { } [ 或行尾/EOF）-> 这是【收尾引号】，退出字符串；
     - 否则 -> 这是【误用的中文引号】，替换为 “ 或 ”（在每个字符串字面量内交替）。
  3. 注释（// 与 /* */）与字符字面量（'x'）整体跳过。
"""
import io
import os
import sys

TERMINATORS = set(';,+)].=*&|?:<>{[]')


def _next_nonspace(text, i):
    n = len(text)
    j = i + 1
    while j < n and text[j] in ' \t':
        j += 1
    if j >= n:
        return None                      # EOF
    if text[j] == '\n' or text[j] == '\r':
        return None                      # 行尾
    return text[j]


def process(text, fix):
    """返回 (misuse_line_numbers, new_text_or_None, leftover_misuse)。"""
    out = list(text)
    lines = [1]
    for ch in text:
        if ch == '\n':
            lines.append(1)
        else:
            lines[-1] += 1
    line_of = []
    ln = 1
    for ch in text:
        line_of.append(ln)
        if ch == '\n':
            ln += 1

    misuse_lines = []
    in_string = False
    open_pending = True                  # 下一个误用引号应是 “ （开引号）
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if not in_string:
            if c == '/' and i + 1 < n and text[i + 1] == '/':
                while i < n and text[i] != '\n':
                    i += 1
                continue
            if c == '/' and i + 1 < n and text[i + 1] == '*':
                j = text.find('*/', i + 2)
                i = n if j < 0 else j + 2
                continue
            if c == "'":
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
                # 🛑 刻意【不】重置 open_pending：中文引号“ ”可能跨字符串字面量配对，
                #    例如 `+ "…而是"租户 A…"` + `"属于租户 B"");` ——
                #    后者的首个引号是前者的闭引号。若按字面量重置会写出「“」而非「”」。
            i += 1
            continue
        # ---- 字符串内部 ----
        if c == '\\':
            i += 2
            continue
        if c == '"':
            nxt = _next_nonspace(text, i)
            if nxt is None or nxt in TERMINATORS:
                in_string = False        # 收尾引号
                i += 1
                continue
            # 误用引号
            misuse_lines.append(line_of[i])
            if fix:
                out[i] = '\u201c' if open_pending else '\u201d'
            open_pending = not open_pending
            i += 1
            continue
        if c == '\n':
            # 字符串跨行 —— Java 不允许，说明前面已有误用；保持状态继续
            i += 1
            continue
        i += 1

    new_text = ''.join(out) if fix else None
    return misuse_lines, new_text, in_string


def scan_file(path, fix=False):
    raw = io.open(path, encoding='utf-8').read()
    misuse_lines, new_text, dangling = process(raw, fix)
    changed = False
    if fix and new_text is not None and new_text != raw:
        with io.open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(new_text)
        changed = True
    return misuse_lines, changed, dangling


def verify(path):
    """修后自证：重跑词法扫描，必须零误用且字符串状态闭合。"""
    raw = io.open(path, encoding='utf-8').read()
    misuse_lines, _, dangling = process(raw, fix=False)
    return misuse_lines, dangling


def collect(targets, root):
    files = []
    for t in targets:
        p = t if os.path.isabs(t) else os.path.join(root, t)
        if os.path.isdir(p):
            for r, d, fs in os.walk(p):
                for f in fs:
                    if f.endswith('.java'):
                        files.append(os.path.join(r, f))
        elif os.path.exists(p):
            files.append(p)
    return sorted(set(files))


if __name__ == '__main__':
    fix = '--fix' in sys.argv
    args = [a for a in sys.argv[1:] if a != '--fix']
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = collect(args, root)
    total = 0
    for f in files:
        misuse_lines, changed, dangling = scan_file(f, fix=fix)
        if misuse_lines:
            rel = os.path.relpath(f, root).replace('\\', '/')
            uniq = sorted(set(misuse_lines))
            print('=== %s  误用引号 %d 处 / 涉及 %d 行  已写回=%s' % (rel, len(misuse_lines), len(uniq), changed))
            print('    行号: %s' % uniq[:40])
            total += len(misuse_lines)
    print('\n误用引号合计 =', total)
    if fix:
        print('--- 修后自证（重跑词法扫描）---')
        allok = True
        for f in files:
            misuse_lines, dangling = verify(f)
            if misuse_lines or dangling:
                allok = False
                rel = os.path.relpath(f, root).replace('\\', '/')
                print('  ✗ %s 残留 %d 处，字符串状态%s' % (
                    rel, len(misuse_lines), '未闭合' if dangling else '已闭合'))
        print('  全部文件自证:', '通过 ✓' if allok else '失败 ✗')