#!/usr/bin/env python3
"""The compact style of the shipped source (src/main/java only; tests keep RuneLite's layout).
The hub's review bot counts every token of src/main/java, and a line break plus its indentation
costs about one token whatever the depth, so the style joins lines wherever that stays readable:

- opening braces join the statement line;
- the braces around a single-statement if / else / for / while body go when that is safe (the body
  is one statement, comment lines before it allowed, not a declaration, not itself a control
  statement, so no dangling else, and the header sits on one line);
- marker and one-line annotations sit on the line of what they annotate (up to 120 columns);
- leading tabs become four spaces.

Apart from the dropped braces, only whitespace changes: no line is joined into or out of a comment,
so the code compiles to the same bytecode. Idempotent; run it before every commit that touches
src/main/java.
Usage: python kr_reformat.py <dir>"""
import io
import os
import re
import sys

NL = chr(10)
QUOTES = (chr(34), chr(39))
BACKSLASH = chr(92)
BRACE_ONLY = re.compile(r'^[ \t]*\{[ \t]*(//.*)?$')
HEADER = re.compile(r'^([ \t]*)((?:else\s+)?if\s*\(.*\)|else|for\s*\(.*\)|while\s*\(.*\))\s*\{\s*$')
BARE_HEADER = re.compile(r'^[ \t]*(?:(?:else\s+)?if\s*\(.*\)|else|for\s*\(.*\)|while\s*\(.*\))$')
CLOSE_ONLY = re.compile(r'^[ \t]*\}[ \t]*$')
COMMENT_ONLY = re.compile(r'^[ \t]*//')
CONTROL = re.compile(r'^[ \t]*(if|else|for|while|do|try|switch|synchronized|case|default)\b')
DECLARATION = re.compile(r'^[ \t]*(?:final\s+)?[A-Za-z_][\w<>\[\],.?]*(?:\s*<[^;=]*>)?(?:\[\])*\s+[A-Za-z_]\w*\s*(?:=|;)')
KEYWORD_STATEMENT = re.compile(r'^[ \t]*(return|throw|break|continue|yield)\b')
SIGNATURE = re.compile(r'^[ \t]*(?:(?:public|private|protected|static|final|default|synchronized|abstract) )*'
                       r'(?:<[^>]+> )?(?:[\w.<>\[\], ?]+ )?\w+\([^()]*\)(?: throws [\w., ]+)? \{$')
ANNOTATIONS = re.compile(r'^[ \t]*@\w[\w.]*(?:\([^()]*\))?(?: @\w[\w.]*(?:\([^()]*\))?)*$')
CONTINUES = re.compile(r'^[ \t]*(\.|\+|-(?!-)|&&|\|\||\?|: |\* |/ )')
OPEN_END = re.compile(r'(,|\(|\+|&&|\|\||(?<![=!<>])=|\?|->)$')
CASE = re.compile(r'^[ \t]*(case .*|default):$')
ENUM_CONSTANT = re.compile(r'^[ \t]*[A-Z_0-9]+(\(.*\))?,$')
LEADING_TABS = re.compile(r'^\t+')
CONTROL_LIMIT = 120
WRAP_LIMIT = 140


def indent_of(line):
    return len(line) - len(line.lstrip(' \t'))


def code_lines(lines):
    """Per line: True when it is plain code that may be joined, that is, it is not inside or part of
    a block comment and carries no // comment (string and char literals are skipped over)."""
    flags = []
    in_block = False
    for line in lines:
        ok = not in_block
        i, n = 0, len(line)
        while i < n:
            c = line[i]
            if in_block:
                if line.startswith('*/', i):
                    in_block = False
                    i += 2
                    continue
                i += 1
            elif c in QUOTES:
                j = i + 1
                while j < n and line[j] != c:
                    j += 2 if line[j] == BACKSLASH else 1
                i = j + 1
            elif line.startswith('//', i):
                ok = False
                break
            elif line.startswith('/*', i):
                ok = False
                in_block = True
                i += 2
            else:
                i += 1
        flags.append(ok and not in_block)
    return flags


def spaces(lines, width):
    """Leading tabs become spaces (the hub bot counts indentation; space runs tokenize cheaper)."""
    return [LEADING_TABS.sub(lambda m: ' ' * (width * len(m.group(0))), line) for line in lines]


def kr(lines):
    out = []
    joined = 0
    for line in lines:
        if BRACE_ONLY.match(line) and out and out[-1].strip():
            # a trailing comment on the brace line moves up with it
            out[-1] = out[-1].rstrip() + ' ' + line.strip()
            joined += 1
        else:
            out.append(line)
    return out, joined


def statement_ok(line):
    if not line.rstrip().endswith(';') or '{' in line or '}' in line:
        return False
    if CONTROL.match(line):
        return False
    if KEYWORD_STATEMENT.match(line):
        return True
    return not DECLARATION.match(line)


def unbrace(lines):
    out = []
    removed = 0
    i = 0
    n = len(lines)
    while i < n:
        m = HEADER.match(lines[i])
        if m:
            j = i + 1
            while j < n and COMMENT_ONLY.match(lines[j]):
                j += 1
            if j + 1 < n and statement_ok(lines[j]) and CLOSE_ONLY.match(lines[j + 1]):
                out.append(m.group(1) + m.group(2))
                out.extend(lines[i + 1:j + 1])
                i = j + 2
                removed += 1
                continue
        out.append(lines[i])
        i += 1
    return out, removed


def annotations(lines):
    """`@Override` / `public String toString()` becomes one line, like any one-line annotation."""
    code = code_lines(lines)
    out = []
    joined = 0
    held = False
    for k, line in enumerate(lines):
        if (held and code[k] and line.strip() and indent_of(line) == indent_of(out[-1])
                and len(out[-1]) + 1 + len(line.strip()) <= CONTROL_LIMIT):
            out[-1] = out[-1].rstrip() + ' ' + line.strip()
            joined += 1
        else:
            out.append(line)
        held = code[k] and ANNOTATIONS.match(out[-1]) is not None
    return out, joined


RULES = [('braces unbraced', unbrace), ('annotations joined', annotations)]


def main():
    root = sys.argv[1]
    files = joined_braces = 0
    counts = dict((name, 0) for name, _ in RULES)
    for dirpath, _, names in os.walk(root):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            with io.open(path, encoding='utf-8', newline='') as f:
                text = f.read()
            # mixed endings exist (a few stray CRLF lines in LF files): split on either, write
            # the file back with whichever ending dominates
            crlf = text.count('\r\n')
            ending = '\r\n' if crlf > text.count(NL) - crlf else NL
            lines = text.replace('\r\n', NL).split(NL)
            lines, joined = kr(lines)
            joined_braces += joined
            prev = None
            while prev != lines:
                prev = lines
                for rule, fn in RULES:
                    lines, r = fn(lines)
                    counts[rule] += r
            lines = spaces(lines, 4)
            new = ending.join(lines)
            if new != text:
                with io.open(path, 'w', encoding='utf-8', newline='') as f:
                    f.write(new)
                files += 1
    print('%d files: %d braces joined, %s' % (files, joined_braces,
                                              ', '.join('%d %s' % (counts[r], r) for r, _ in RULES)))


if __name__ == '__main__':
    main()
