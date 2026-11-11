#!/usr/bin/env python3
"""Periksa kebersihan teks berkas Markdown.

Yang diperiksa:
  1. BOM di awal berkas (harus tidak ada)
  2. Akhir baris CRLF (harus LF)
  3. Berkas bukan UTF-8 valid
  4. Aksara CJK: Mandarin (Han), Hiragana, Katakana, Hangul
  5. Mojibake umum: 'â€', 'Ã©', 'ï¿½', U+FFFD

Pemakaian:
    python3 tools/check_md_text.py             # semua .md di worktree
    python3 tools/check_md_text.py README.md  # berkas tertentu

Catatan: pemindaian default memakai worktree, BUKAN `git ls-files`, supaya dokumen
baru yang belum di-stage tetap terperiksa sebelum commit. Folder `.git`, `build`,
`node_modules`, dan `.gradle` dilewati.

Keluar dengan kode 1 kalau ada temuan, supaya bisa dipakai di CI dan pre-commit.
"""

from __future__ import annotations

import sys
import unicodedata
from pathlib import Path

RANGES = [
    ("Han (Mandarin)", 0x4E00, 0x9FFF),
    ("Han (Mandarin, ext A)", 0x3400, 0x4DBF),
    ("Hiragana", 0x3040, 0x309F),
    ("Katakana", 0x30A0, 0x30FF),
    ("Hangul", 0xAC00, 0xD7AF),
    ("Tanda baca CJK", 0x3000, 0x303F),
    ("Bentuk lebar penuh", 0xFF00, 0xFFEF),
]

MOJIBAKE = ("â€", "Ã©", "Ã¢", "ï¿½", "\ufffd")

SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", ".idea", "captures"}


def all_markdown() -> list[Path]:
    out: list[Path] = []
    for path in Path(".").rglob("*.md"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.is_file():
            out.append(path)
    return sorted(out)


def check(path: Path) -> list[str]:
    problems: list[str] = []
    raw = path.read_bytes()

    if raw.startswith(b"\xef\xbb\xbf"):
        problems.append("BOM di awal berkas")
    if b"\r\n" in raw:
        problems.append("akhir baris CRLF (harus LF)")

    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        problems.append(f"bukan UTF-8 valid: {exc}")
        return problems

    for lineno, line in enumerate(text.splitlines(), 1):
        for name, low, high in RANGES:
            hits = [c for c in line if low <= ord(c) <= high]
            if hits:
                sample = "".join(sorted(set(hits)))[:12]
                problems.append(f"{lineno}: aksara {name} {sample!r} :: {line.strip()[:70]}")
        for token in MOJIBAKE:
            if token in line:
                problems.append(f"{lineno}: mojibake {token!r} :: {line.strip()[:70]}")
        for ch in line:
            if unicodedata.category(ch) == "Co":  # private use area
                problems.append(f"{lineno}: karakter area privat U+{ord(ch):04X}")
                break
    return problems


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv[1:]] or all_markdown()
    total = 0
    for path in paths:
        problems = check(path)
        if problems:
            total += len(problems)
            print(f"{path}:")
            for p in problems:
                print(f"  - {p}")
    if total:
        print(f"\n{total} temuan. Perbaiki dulu sebelum commit.")
        return 1
    print(f"Bersih: {len(paths)} berkas .md tanpa BOM/CRLF/CJK/mojibake.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
