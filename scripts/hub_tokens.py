#!/usr/bin/env python3
"""Token count of the plugin's shipped source the way the plugin-hub review bot counts it.

The hub's review bot refuses a plugin over 200,000 tokens. Only src/main/java counts, with comments
stripped before tokenizing (riktenx, plugin-hub PR 17419, 2026-09-30); tests, resources, scripts and
the JMH set are free. This script strips comments the same way and tokenizes with tiktoken's
cl100k_base, which ran 3.7 percent above the bot on the 0.14.0 tree (210,265 here against the
bot's 202,760), so read 200k on the hub as about 207k here and keep a margin.

    pip install tiktoken
    python scripts/hub_tokens.py                      # this checkout
    python scripts/hub_tokens.py --top 40 --by-package
    python scripts/hub_tokens.py --members src/main/java/gps/ShortestPathPlugin.java
    python scripts/hub_tokens.py --diff ../gps-old .  # per-file growth between two trees

A tree is a directory holding src/main/java; `git archive v0.13.2 src/main/java | tar -x -C dir`
exports one from a tag. --members splits one file by top-level member (field, method, inner
class) at brace depth 1 to show what a huge file spends its tokens on.
"""
import os
import sys

try:
    import tiktoken
except ImportError:
    sys.exit('tiktoken is required: pip install tiktoken')

BS = chr(92)
NL = chr(10)
DQ = chr(34)
SQ = chr(39)
ENC = tiktoken.get_encoding('cl100k_base')


def strip_comments(src):
    """Remove // and /* */ comments; string, char and text-block literals stay intact."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find(NL, i)
            i = n if j < 0 else j
        elif c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == DQ and src.startswith(DQ * 3, i):
            j = i + 3
            while j < n:
                if src[j] == BS:
                    j += 2
                    continue
                if src.startswith(DQ * 3, j):
                    j += 3
                    break
                j += 1
            out.append(src[i:j])
            i = j
        elif c == DQ or c == SQ:
            j = i + 1
            while j < n:
                if src[j] == BS:
                    j += 2
                    continue
                if src[j] == c or src[j] == NL:
                    j += 1
                    break
                j += 1
            out.append(src[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    lines = [l for l in ''.join(out).split(NL) if l.strip()]
    return NL.join(lines) + NL


def tokens(text):
    return len(ENC.encode(text))


def java_files(root):
    base = os.path.join(root, 'src', 'main', 'java')
    for dirpath, _, files in os.walk(base):
        for f in sorted(files):
            if f.endswith('.java'):
                p = os.path.join(dirpath, f)
                with open(p, encoding='utf-8') as fh:
                    yield os.path.relpath(p, base).replace(os.sep, '/'), fh.read()


def count_tree(root):
    rows = []
    for rel, src in java_files(root):
        stripped = strip_comments(src)
        rows.append((rel, len(src), len(stripped), tokens(stripped)))
    return rows


def report_tree(root, top, by_package):
    rows = count_tree(root)
    total = sum(r[3] for r in rows)
    print('=== %s: %d files, %d raw chars, %d stripped chars, %d tokens (hub limit 200k is about 207k here)'
          % (root, len(rows), sum(r[1] for r in rows), sum(r[2] for r in rows), total))
    rows.sort(key=lambda r: -r[3])
    print('%-52s %8s %8s %8s %6s %6s' % ('file', 'raw', 'strip', 'tokens', 'share', 'cum'))
    cum = 0
    for rel, raw, strp, t in rows[:top]:
        cum += t
        print('%-52s %8d %8d %8d %5.1f%% %5.1f%%' % (rel, raw, strp, t, 100.0 * t / total, 100.0 * cum / total))
    rest = rows[top:]
    if rest:
        print('%-52s %8d %8d %8d %5.1f%%' % ('(%d other files)' % len(rest), sum(r[1] for r in rest),
                                              sum(r[2] for r in rest), sum(r[3] for r in rest),
                                              100.0 * sum(r[3] for r in rest) / total))
    if by_package:
        pk = {}
        for rel, _, _, t in rows:
            pkg = rel.rsplit('/', 1)[0] if '/' in rel else '(default)'
            pk[pkg] = pk.get(pkg, 0) + t
        print('-- by package')
        for pkg, t in sorted(pk.items(), key=lambda kv: -kv[1]):
            print('%-52s %8d %5.1f%%' % (pkg, t, 100.0 * t / total))
    print()


def report_diff(old_root, new_root):
    old = {r[0]: r[3] for r in count_tree(old_root)}
    new = {r[0]: r[3] for r in count_tree(new_root)}
    rows = []
    for k in set(old) | set(new):
        a, b = old.get(k, 0), new.get(k, 0)
        if a != b:
            rows.append((b - a, a, b, k))
    rows.sort(key=lambda r: -abs(r[0]))
    print('%-52s %8s %8s %8s' % ('file', 'old', 'new', 'delta'))
    for d, a, b, k in rows:
        tag = ' (new)' if a == 0 else (' (gone)' if b == 0 else '')
        print('%-52s %8d %8d %+8d%s' % (k, a, b, d, tag))
    print('%-52s %8d %8d %+8d' % ('TOTAL', sum(old.values()), sum(new.values()),
                                   sum(new.values()) - sum(old.values())))


def members(stripped):
    """Split a class body into top-level members at brace depth 1; good enough to rank them."""
    depth = 0
    out = []
    name = None
    body = []
    for line in stripped.split(NL):
        if not line.strip():
            continue
        if depth == 1 and name is None:
            name = line.strip()[:70]
        if depth >= 1:
            body.append(line)
        quote = None
        i = 0
        while i < len(line):
            c = line[i]
            if quote:
                if c == BS:
                    i += 2
                    continue
                if c == quote:
                    quote = None
            elif c == DQ or c == SQ:
                quote = c
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            i += 1
        if name is not None and depth <= 1:
            s = line.strip()
            if depth < 1 or s.endswith('}') or s.endswith(';') or s.endswith(','):
                out.append((name, NL.join(body)))
                name, body = None, []
    if name is not None:
        out.append((name, NL.join(body)))
    return out


def report_members(path, top):
    with open(path, encoding='utf-8') as fh:
        stripped = strip_comments(fh.read())
    total = tokens(stripped)
    rows = sorted(((tokens(b), n) for n, b in members(stripped)), key=lambda r: -r[0])
    print('=== %s: %d tokens, %d members' % (path, total, len(rows)))
    cum = 0
    for t, n in rows[:top]:
        cum += t
        print('%6d %5.1f%% %5.1f%%  %s' % (t, 100.0 * t / total, 100.0 * cum / total, n))
    rest = sum(r[0] for r in rows[top:])
    if rest:
        print('%6d %5.1f%%         (%d other members)' % (rest, 100.0 * rest / total, len(rows) - top))
    print()


def main():
    args = sys.argv[1:]
    top = 25
    by_package = False
    mode = 'tree'
    operands = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--top':
            top = int(args[i + 1])
            i += 2
        elif a == '--by-package':
            by_package = True
            i += 1
        elif a == '--members':
            mode = 'members'
            i += 1
        elif a == '--diff':
            mode = 'diff'
            i += 1
        else:
            operands.append(a)
            i += 1
    if mode == 'members':
        for p in operands:
            report_members(p, top)
    elif mode == 'diff':
        if len(operands) != 2:
            sys.exit('--diff takes two trees: old new')
        report_diff(operands[0], operands[1])
    else:
        for root in operands or ['.']:
            report_tree(root, top, by_package)


if __name__ == '__main__':
    main()
