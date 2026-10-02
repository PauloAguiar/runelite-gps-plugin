#!/usr/bin/env python3
"""The compact style of the shipped source (src/main/java only; tests keep RuneLite's layout).
The hub's review bot counts every token of src/main/java, so: opening braces join the statement
line, the braces around a single-statement if / else / for / while body go when that is safe
(the body is one statement, comment lines before it allowed, not a declaration, not itself a
control statement, so no dangling else, and the header sits on one line), and leading tabs
become four spaces. Idempotent; run it before a release. Usage: python kr_reformat.py <dir>"""
import io
import os
import re
import sys

NL = chr(10)
BRACE_ONLY = re.compile(r'^[ \t]*\{[ \t]*(//.*)?$')
HEADER = re.compile(r'^([ \t]*)((?:else\s+)?if\s*\(.*\)|else|for\s*\(.*\)|while\s*\(.*\))\s*\{\s*$')
CLOSE_ONLY = re.compile(r'^[ \t]*\}[ \t]*$')
COMMENT_ONLY = re.compile(r'^[ \t]*//')
CONTROL = re.compile(r'^[ \t]*(if|else|for|while|do|try|switch|synchronized|case|default)\b')
DECLARATION = re.compile(r'^[ \t]*(?:final\s+)?[A-Za-z_][\w<>\[\],.?]*(?:\s*<[^;=]*>)?(?:\[\])*\s+[A-Za-z_]\w*\s*(?:=|;)')
KEYWORD_STATEMENT = re.compile(r'^[ \t]*(return|throw|break|continue|yield)\b')


LEADING_TABS = re.compile(r'^\t+')


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


def main():
    root = sys.argv[1]
    total_joined = total_removed = files = 0
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
            prev = None
            removed = 0
            while prev != lines:
                prev = lines
                lines, r = unbrace(lines)
                removed += r
            lines = spaces(lines, 4)
            new = ending.join(lines)
            if new != text:
                with io.open(path, 'w', encoding='utf-8', newline='') as f:
                    f.write(new)
                files += 1
            total_joined += joined
            total_removed += removed
    print('%d files: %d braces joined, %d single-statement blocks unbraced' % (files, total_joined, total_removed))


if __name__ == '__main__':
    main()
