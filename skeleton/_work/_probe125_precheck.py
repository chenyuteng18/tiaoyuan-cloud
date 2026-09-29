# -*- coding: utf-8 -*-
import importlib.util, sys, io
from pathlib import Path
spec = importlib.util.spec_from_file_location(
    "rv125", "verification/125_band_refetch_reverse_verification.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

SKEL = m.SKEL
texts = {rel: (SKEL / rel).resolve().read_text(encoding="utf-8", newline="") for rel in m._file_rels()}
V22 = (SKEL / m.F_V22).resolve().read_text(encoding="utf-8", newline="")
seg = m.extract_cov_segment(V22)
print("=== 锚点预检 ===")
bad = 0
for tag, kind, rel, anchor, _r, _e in m.INJECTIONS:
    n = texts[rel].count(anchor) if kind == "file" else seg.count(anchor)
    if n != 1: bad += 1
    print(f"  {'OK ' if n==1 else 'BAD'} n={n} [{kind}] {tag}")
print("bad anchors:", bad)
print()
print("=== 污染态自检（应全部为 False）===")
for rel in m._file_rels():
    print(" ", rel, m._looks_injected(texts[rel], rel))
print()
print("=== 活库基线自证 ===")
print("  live_matches_source =", m.live_matches_source())
ps = m.live_prosrc()
print("  (5c) 门禁在:", m.LIVE_GATE_OLD in ps)
print("  归属行在 :", m.LIVE_OWNER_OLD.rstrip("\n") in ps)
print("  (5b) 词表在:", m.LIVE_VOCAB_OLD in ps)
print("  归因分支在:", m.LIVE_REPORT_OLD.split(" THEN")[0] in ps)
print("  prosrc 长度:", len(ps))
