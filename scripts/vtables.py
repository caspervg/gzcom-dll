"""Turn Ghidra vtable exports into reviewed interface specs, headers and checks.

    python scripts/vtables.py align <iface>...     re/extract/*.yaml -> re/vtables/<iface>.yaml
    python scripts/vtables.py scaffold <iface>     re/vtables/<iface>.yaml -> gzcom-dll/include/<iface>.h
    python scripts/vtables.py check [<iface>...]   compile probes, compare with the spec (all specs by default)
    python scripts/vtables.py plan <iface>...      re/plans/<iface>.tsv for scripts/ghidra/ApplyInterfaceNames.java
    python scripts/vtables.py selftest

The Windows game decides slot order and extent. The Mac build supplies names
and parameter types, but it predates 1.1.641, so its order is only a prior.
See docs/RecoverInterfaceVtables.md.
"""

import itertools
import math
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
EXTRACT = ROOT / "re" / "extract"
SPECS = ROOT / "re" / "vtables"
INCLUDE = ROOT / "gzcom-dll" / "include"

# gcc spellings from the Mac demangler -> the fixed-width names the headers use.
SCALARS = {
    "unsigned long long": "uint64_t", "long long": "int64_t",
    "unsigned long": "uint32_t", "long": "int32_t",
    "unsigned int": "uint32_t", "int": "int32_t",
    "unsigned short": "uint16_t", "short": "int16_t",
    "unsigned char": "uint8_t", "signed char": "int8_t",
}
FOUR_BYTE = {"int32_t", "uint32_t", "int16_t", "uint16_t", "int8_t", "uint8_t",
             "bool", "char", "float", "wchar_t"}
EIGHT_BYTE = {"int64_t", "uint64_t", "double"}
STL = {r"std::vector": "SC4Vector", r"std::list": "SC4List", r"__gnu_cxx::hash_set": "SC4HashSet"}
STL_HEADERS = {"SC4Vector": "SC4Vector.h", "SC4List": "SC4List.h", "SC4HashSet": "SC4HashSet.h"}
HUMAN_KEYS = ("name", "params", "returns", "const", "status", "virtual_name", "note")


# ---------------------------------------------------------------- parsing

def split_top(text, sep=","):
    """Split on sep outside of <> and () nesting."""
    parts, depth, cur = [], 0, ""
    for ch in text:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == sep and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts]


def normalize_type(t):
    t = re.sub(r"\s+", " ", t.strip())
    for gcc, fixed in SCALARS.items():
        t = re.sub(r"(?<![\w])" + gcc + r"(?![\w])", fixed, t)
    t = re.sub(r"\s*([*&])", r"\1", t)
    # The game's containers come from Maxis's old STL, with its own layout and
    # the game allocator. Modern std:: types compile but corrupt memory, so map
    # them to the SC4 replacements. Anything left in std:: fails `check`.
    for std, sc4 in STL.items():
        t = re.sub(std + r"<\s*([^,<>]+?)\s*,.*?>\s*>", sc4 + r"<\1>", t)
    return t


def parse_demangled(text):
    """'undefined A::B(unsigned long,void * *) const' -> (leaf, [params], const)."""
    if not text:
        return None, [], False
    const = text.endswith(" const")
    if const:
        text = text[: -len(" const")]
    depth = 0
    for i in range(len(text) - 1, -1, -1):
        depth += text[i] == ")"
        depth -= text[i] == "("
        if depth == 0:
            break
    name, args = text[:i], text[i + 1 : -1]
    name = name.split(" ")[-1] if not re.search(r"operator\W", name) else name[name.find(" ") + 1 :]
    leaf = name.split("::")[-1]
    params = [] if args in ("", "void") else [normalize_type(p) for p in split_top(args)]
    return leaf, params, const


def param_bytes(params):
    """Stack bytes a __thiscall callee pops, or None when unknown or variadic."""
    total = 0
    for p in params:
        if p == "...":
            return None
        if p.endswith(("*", "&")):
            total += 4
        elif p.replace("const ", "").replace(" const", "") in EIGHT_BYTE:
            total += 8
        elif p.replace("const ", "").replace(" const", "") in FOUR_BYTE:
            total += 4
        else:
            return None  # class or enum by value: size unknown here
    return total


def expected_ret(params):
    if "..." in params:
        return 0  # variadic methods are __cdecl, the caller cleans up
    return param_bytes(params)


# ------------------------------------------------------------- MSVC order

def msvc_order(names):
    """Slot order modern MSVC gives declarations: same-name overloads are pulled
    up to the first one's slot, in reverse declaration order."""
    out, seen = [], set()
    for i, n in enumerate(names):
        if n in seen:
            continue
        seen.add(n)
        out.extend(reversed([j for j, m in enumerate(names) if m == n]))
    return out


def declaration_order(names):
    """Inverse of msvc_order: the declaration order (as indices into names,
    which are in slot order) that makes modern MSVC emit exactly this slot
    order. Returns None if an overload group is not contiguous, which no
    declaration order can reproduce."""
    out, seen = [], set()
    for i, n in enumerate(names):
        if n in seen:
            continue
        seen.add(n)
        group = [j for j, m in enumerate(names) if m == n]
        if group != list(range(group[0], group[0] + len(group))):
            return None
        out.extend(reversed(group))
    return out


# ------------------------------------------------------------------ align

def load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def mac_slots(mac):
    impls = [i for i in mac.get("implementers") or [] if i.get("slots")]
    impls = sorted(impls, key=lambda i: any(s.get("pure") for s in i["slots"]) and not i.get("derived"))
    if not impls:
        raise SystemExit(f"{mac['interface']}: no Mac implementer")
    primary = impls[0]
    # An abstract implementer leaves slots pure. The exporter lists concrete
    # subclasses under derived:, which fill those slots at the same offset.
    derived = {d["index"]: d for sub in (primary.get("derived") or [])[:1] for d in sub["slots"]}
    slots = []
    for s in primary["slots"][: mac["slot_count"]]:
        pure = bool(s.get("pure"))
        if pure and s["index"] in derived:
            s = derived[s["index"]]
        leaf, params, const = parse_demangled(s.get("demangled"))
        # Ghidra's demangled signature drops the method's const. The mangled
        # name keeps it as the K in __ZNK.
        const = const or (s.get("mangled") or "").startswith("__ZNK")
        slots.append({
            "index": s["index"], "name": leaf, "params": params, "const": const,
            "ret_guess": s.get("ret_guess"), "impl": s.get("impl"),
            "callees": s.get("callees") or [], "vcalls": s.get("vcalls") or [],
            "insns": s.get("insns"), "strings": s.get("strings") or [], "pure": pure,
        })
    disagree = []
    for other in impls[1:]:
        for a, b in zip(primary["slots"], other["slots"]):
            la, lb = parse_demangled(a.get("demangled"))[0], parse_demangled(b.get("demangled"))[0]
            if la != lb:
                disagree.append(f"slot {a['index']}: {primary['class']}::{la} vs {other['class']}::{lb}")
    return primary, impls, slots, disagree


def win_tables(win, mac_count, base):
    tables = []
    for qi in win.get("qi_candidates") or []:
        for t in qi.get("tables") or []:
            # Pure tables no longer than the base belong to the base interfaces.
            chain = [c for c in t.get("pure_chain") or [] if c["len"] > base]
            fits = [c for c in chain if c["len"] <= t["run_len"]]
            if chain:
                pure = max(fits or chain, key=lambda c: c["len"])
                extent, source = pure["len"], "pure-table"
                t = {**t, "pure_table": pure["table"]}
            elif t["adjust"] != 0:
                extent, source = t["run_len"], "subtable-run"
            elif t["run_len"] <= mac_count:
                extent, source = t["run_len"], "primary-run"
            else:
                extent, source = mac_count, "mac-prior"
            tables.append({**t, "qi": qi["qi"], "extent": extent, "extent_source": source,
                           "slots": t["slots"][:extent]})
    return tables


def base_table(tables):
    """Derived classes that keep the implementer's QI also reuse most of its
    slot implementations. The implementer's own table is the one whose
    implementations the other tables share most."""
    def shared(t):
        return sum(1 for o in tables if o is not t for a, b in zip(t["slots"], o["slots"])
                   if a["impl"] == b["impl"])
    return max(tables, key=lambda t: (shared(t), t["store_count"]))


def edges(slots):
    """Slot-to-slot calls: direct calls between implementations, plus calls
    through the vtable, which is how one overload usually wraps another."""
    by_impl = {s["impl"]: s["index"] for s in slots if s.get("impl")}
    direct = {(s["index"], by_impl[c]) for s in slots for c in s.get("callees") or [] if c in by_impl}
    virtual = {(s["index"], k) for s in slots for k in s.get("vcalls") or [] if k < len(slots) and k != s["index"]}
    return direct | virtual


def pair_score(m, w):
    score = 0.0
    exp = expected_ret(m["params"])
    if exp is not None and w.get("ret_n") is not None:
        score += 3 if exp == w["ret_n"] else -5
    score += 4 * len(set(m["strings"]) & set(w.get("strings") or []))
    if w.get("name") and w["name"].split("::")[-1] == m["name"]:
        score += 6
    if m.get("insns") and w.get("insns"):
        score -= abs(math.log(m["insns"] / w["insns"]))
    return score


def mapping_score(mapping, mac, win, mac_edges, win_edges):
    """mapping: mac index -> win index."""
    s = sum(pair_score(mac[i], win[j]) for i, j in mapping.items())
    for a, b in mac_edges:
        if a in mapping and b in mapping:
            s += 4 if (mapping[a], mapping[b]) in win_edges else -2
    return s


def align(iface):
    mac = load(EXTRACT / f"{iface}.mac.yaml")
    win = load(EXTRACT / f"{iface}.win.yaml")
    primary, impls, mslots, disagree = mac_slots(mac)
    base = mac.get("base_slots") or 0
    notes = list(disagree)
    if mac.get("non_pure_slots"):
        notes.append(f"Mac interface vtable has non-pure slots {mac['non_pure_slots']}")

    if not win.get("qi_candidates"):
        raise SystemExit(f"{iface}: no Windows implementer. No QueryInterface in SimCity 4.exe tests "
                         f"{win['gziid']:#010x}, so the class was not linked into the game.")
    tables = win_tables(win, mac["slot_count"], base)
    for t in tables:
        for w in t["slots"]:
            w["pure"] = w.get("impl") == win.get("pure_stub")
    if not tables:
        raise SystemExit(f"{iface}: no Windows vftable found for the Mac base offsets")
    # A table whose extent is measured beats one that falls back to the Mac
    # count. Otherwise the primary table of a class that implements the
    # interface at another offset can win (cSC4CrimeSimulator for PropRequester).
    rank = {"pure-table": 0, "subtable-run": 1, "primary-run": 1, "mac-prior": 2}
    best = min(rank[o["extent_source"]] for o in tables)
    t = base_table([o for o in tables if rank[o["extent_source"]] == best])
    if len(tables) > 1:
        notes.append(f"{len(tables)} Windows tables share the QI, used the implementer's own 0x{t['vftable']:08x}")
    extents = {o["extent"] for o in tables if o["extent_source"] == "pure-table"}
    if len(extents) > 1:
        notes.append(f"Windows pure tables disagree on the slot count: {sorted(extents)}")
    result = align_table(mslots, list(t["slots"]), base)
    notes += result["notes"]
    if t["extent_source"] == "mac-prior":
        notes.append("primary Windows table runs past the Mac slot count, so slots added after the Mac build would be missed")

    old = load(SPECS / f"{iface}.yaml") if (SPECS / f"{iface}.yaml").exists() else {}
    human = {s["win"]: s.get("human") for s in old.get("slots") or [] if s.get("win") is not None and s.get("human")}

    slots = []
    for row in result["rows"]:
        m, w = row["mac"], row["win"]
        slot = {
            "win": w["index"] if w else None,
            "mac": m["index"] if m else None,
            "origin": "both" if m and w else ("win-only" if w else "mac-only"),
            "name": m["name"] if m else f"Unknown{w['index']}",
            "params": m["params"] if m else (["uint32_t"] * ((w.get("ret_n") or 0) // 4)),
            "const": m["const"] if m else False,
            "returns": None,
            "ret_guess": m.get("ret_guess") if m else None,
            "param_bytes": expected_ret(m["params"]) if m else None,
            "ret_n": w.get("ret_n") if w else None,
            "mac_impl": m.get("impl") if m else None,
            "win_impl": w.get("impl") if w else None,
            "win_name": w.get("name") if w else None,
            "evidence": row["evidence"],
            "status": row["status"],
        }
        if slot["win"] in human:
            slot["human"] = human[slot["win"]]
        slots.append(slot)

    spec = {
        "interface": iface,
        "gziid": f"0x{win['gziid']:08x}",
        "base": (mac.get("bases") or [None])[0],
        "base_slots": base,
        "extent": {"win": t["extent"], "win_source": t["extent_source"], "win_run": t["run_len"],
                   "mac": mac["slot_count"]},
        "mac": {"program": mac["program"], "vtable": f"0x{mac['vtable']:08x}",
                "implementers": [{"class": i["class"], "base_offset": i["base_offset"]} for i in impls]},
        "win": {"program": win["program"], "qi": f"0x{t['qi']:08x}", "vftable": f"0x{t['vftable']:08x}",
                "adjust": t["adjust"],
                "pure_table": f"0x{t['pure_table']:08x}" if t.get("pure_table") else None},
        "notes": notes,
        "slots": slots,
    }
    SPECS.mkdir(parents=True, exist_ok=True)
    with open(SPECS / f"{iface}.yaml", "w", encoding="utf-8") as f:
        f.write("# Layout spec. Machine fields are rewritten by `vtables.py align`.\n"
                "# Put corrections in a slot's `human:` map, which align keeps.\n")
        yaml.safe_dump(spec, f, sort_keys=False, width=120)
    counts = {}
    for s in slots:
        counts[s["status"]] = counts.get(s["status"], 0) + 1
    print(f"{iface}: {len(slots)} slots {counts} -> {SPECS / (iface + '.yaml')}")
    for n in notes:
        print(f"  note: {n}")


def align_table(mslots, wslots, base):
    notes = []
    wext = wslots
    pred = list(range(base)) + [base + i for i in msvc_order([s["name"] for s in mslots[base:]])]
    pmac = [mslots[i] for i in pred]

    if len(pmac) != len(wext):
        # ponytail: gaps are placed by a plain edit-distance alignment and the
        # whole table is sent to review. Add anchor-bounded alignment when a
        # real interface needs it.
        notes.append(f"Mac has {len(pmac)} slots, Windows {len(wext)}: version drift, review the whole table")
        rows = needleman_wunsch(pmac, wext)
        for r in rows:
            r["status"], r["evidence"] = "review", ["drift"]
        return {"rows": rows, "score": 0, "notes": notes}

    mapping = {m["index"]: w["index"] for m, w in zip(pmac, wext)}
    mac_edges, win_edges = edges(mslots), edges(wslots)
    tie_groups = set()
    groups = {}
    for s in mslots[base:]:
        groups.setdefault(s["name"], []).append(s["index"])
    for name, members in groups.items():
        if len(members) < 2:
            continue
        positions = sorted(mapping[i] for i in members)
        scored = []
        for perm in itertools.permutations(sorted(members, key=mapping.get)):  # first = MSVC prediction, kept on ties
            trial = {**mapping, **dict(zip(perm, positions))}
            scored.append((mapping_score(trial, mslots, {w["index"]: w for w in wslots}, mac_edges, win_edges), perm))
        scored.sort(key=lambda x: -x[0])
        mapping.update(zip(scored[0][1], positions))
        if len(scored) > 1 and scored[0][0] - scored[1][0] < 1.0:
            tie_groups.add(name)
            notes.append(f"overload group {name}: no evidence separates the orders, review")

    wmap = {w["index"]: w for w in wslots}
    table_ok = True
    rows = []
    for m in mslots:
        w = wmap[mapping[m["index"]]]
        exp = expected_ret(m["params"])
        ev = []
        if exp is not None and w.get("ret_n") is not None:
            ev.append("ret_n" if exp == w["ret_n"] else "ret_n-mismatch")
        if w.get("name") and w["name"].split("::")[-1] == m["name"]:
            ev.append("win_name")
        if set(m["strings"]) & set(w.get("strings") or []):
            ev.append("strings")
        if any((a == m["index"] or b == m["index"]) and (mapping[a], mapping[b]) in win_edges for a, b in mac_edges
               if a in mapping and b in mapping):
            ev.append("call_edges")
        if m.get("pure") and w.get("pure"):
            ev.append("pure_both")
        if "ret_n-mismatch" in ev:
            table_ok = False
        rows.append({"mac": m, "win": w, "evidence": ev})

    for r in rows:
        m, ev = r["mac"], r["evidence"]
        soft = {"win_name", "strings", "call_edges"} & set(ev)
        in_group = len(groups.get(m["name"], [])) > 1 and m["index"] >= base
        if "pure_both" in ev:
            r["status"] = "confirmed"
        elif "ret_n-mismatch" in ev:
            r["status"] = "review"
        elif in_group and m["name"] in tie_groups:
            r["status"] = "review"
        elif "ret_n" in ev and (soft or (table_ok and not in_group)):
            r["status"] = "confirmed"
        elif "ret_n" in ev or soft:
            r["status"] = "probable"
        else:
            r["status"] = "review"
    rows.sort(key=lambda r: r["win"]["index"])
    score = mapping_score(mapping, mslots, wmap, mac_edges, win_edges)
    return {"rows": rows, "score": score, "notes": notes}


def needleman_wunsch(mac, win, gap=-4.0):
    n, k = len(mac), len(win)
    dp = [[0.0] * (k + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i * gap
    for j in range(1, k + 1):
        dp[0][j] = j * gap
    for i in range(1, n + 1):
        for j in range(1, k + 1):
            dp[i][j] = max(dp[i - 1][j - 1] + pair_score(mac[i - 1], win[j - 1]),
                           dp[i - 1][j] + gap, dp[i][j - 1] + gap)
    rows, i, j = [], n, k
    while i or j:
        if i and j and dp[i][j] == dp[i - 1][j - 1] + pair_score(mac[i - 1], win[j - 1]):
            rows.append({"mac": mac[i - 1], "win": win[j - 1]}); i -= 1; j -= 1
        elif i and dp[i][j] == dp[i - 1][j] + gap:
            rows.append({"mac": mac[i - 1], "win": None}); i -= 1
        else:
            rows.append({"mac": None, "win": win[j - 1]}); j -= 1
    return rows[::-1]


# ------------------------------------------------------------- scaffolding

def merged(slot):
    s = dict(slot)
    s.update(slot.get("human") or {})
    return s


def scaffold(iface):
    spec = load(SPECS / f"{iface}.yaml")
    header = INCLUDE / f"{iface}.h"
    if header.exists():
        raise SystemExit(f"{header} exists. Scaffold only creates new headers.")
    slots = [merged(s) for s in spec["slots"] if s.get("win") is not None]
    blocked = [s for s in slots if s["status"] in ("review", "conflict")]
    blocked += [s for s in slots if s["win"] >= spec["base_slots"] and not s.get("returns")]
    if blocked:
        for s in blocked:
            print(f"  slot {s['win']} {s['name']}: status={s['status']} returns={s.get('returns')}")
        raise SystemExit("resolve these slots (status, returns) in the spec's human: maps first")
    own = sorted((s for s in slots if s["win"] >= spec["base_slots"]), key=lambda s: s["win"])
    names = [s.get("virtual_name") or s["name"] for s in own]
    order = declaration_order(names)
    if order is None:
        raise SystemExit("an overload group is split across the table. Give one member a virtual_name.")

    types = set()
    for s in own:
        for t in s["params"] + [s["returns"]]:
            types.update(re.findall(r"\b(c[A-Z]\w+)", t))
    types.discard(iface)
    known = {p.stem for p in INCLUDE.glob("*.h")}
    lines = [
        "/*",
        " * gzcom-dll - an open-source DLL Plugin SDK for SimCity 4",
        " *",
        f" * {iface}.h",
        " *",
        " * This library is free software; you can redistribute it and/or",
        " * modify it under the terms of the GNU Lesser General Public",
        " * License as published by the Free Software Foundation, under",
        " * version 2.1 of the License, or (at your option) any later version.",
        " *",
        " * This library is distributed in the hope that it will be useful,",
        " * but WITHOUT ANY WARRANTY; without even the implied warranty of",
        " * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU",
        " * Lesser General Public License for more details.",
        " *",
        " * You should have received a copy of the GNU Lesser General Public",
        " * License along with this library; if not, see <https://www.gnu.org/licenses/>.",
        " */",
        "",
        "#pragma once",
        f'#include "{spec["base"]}.h"',
        "",
    ]
    for t in sorted(types):
        lines.append(f"class {t};")
    if types:
        lines.append("")
    used = " ".join(t for s in own for t in s["params"] + [s["returns"]])
    for name, inc in STL_HEADERS.items():
        if name + "<" in used:
            lines.insert(lines.index(f'#include "{spec["base"]}.h"') + 1, f'#include "{inc}"')
    lines += [f"static const uint32_t GZIID_{iface} = {spec['gziid']};", "",
              f"// Slot order verified against SimCity 4.exe 1.1.641 by scripts/vtables.py check.",
              f"// Overloads are declared in reverse slot order because MSVC reverses them.",
              f"class {iface} : public {spec['base']}", "{", "public:"]
    for i in order:
        s = own[i]
        params = ", ".join(p for p in s["params"])
        const = " const" if s.get("const") else ""
        lines.append(f"\tvirtual {s['returns']} {s.get('virtual_name') or s['name']}({params}){const} = 0;")
    lines += ["};", ""]
    header.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {header}")


# ------------------------------------------------------------------ check

def vcvarsall():
    vswhere = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / \
        "Microsoft Visual Studio/Installer/vswhere.exe"
    root = subprocess.run([str(vswhere), "-latest", "-products", "*", "-requires",
                           "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property",
                           "installationPath"], capture_output=True, text=True).stdout.strip()
    path = Path(root) / "VC/Auxiliary/Build/vcvarsall.bat"
    if not path.exists():
        raise SystemExit("vcvarsall.bat not found. Install Visual Studio with the C++ workload.")
    return path


def probe_sources(spec):
    """Two translation units: call-site probes (one per slot, the slot MSVC
    picks shows up in the listing) and return-type asserts. They compile
    separately so a wrong return type cannot hide the slot order."""
    iface = spec["interface"]
    head = [f'#include "{(INCLUDE / (iface + ".h")).as_posix()}"', "#include <type_traits>",
            "#include <utility>", ""]
    probes, returns = list(head), list(head)
    for s in map(merged, spec["slots"]):
        if s.get("win") is None:
            continue
        fixed = [p for p in s["params"] if p != "..."]
        args = "".join(f", {p} a{i}" for i, p in enumerate(fixed))
        call = ", ".join(f"a{i}" for i in range(len(fixed)))
        cv = "const " if s.get("const") else ""
        probes.append(f'extern "C" void probe_{s["win"]}({cv}{iface}* p{args}) {{ p->{s["name"]}({call}); }}')
        if s.get("returns"):
            decl = ", ".join(f"std::declval<{p}>()" for p in fixed)
            returns.append(f"static_assert(std::is_same_v<decltype(std::declval<{cv}{iface}&>().{s['name']}({decl})), "
                           f"{s['returns']}>, \"slot {s['win']} {s['name']} must return {s['returns']}\");")
    return "\n".join(probes) + "\n", "\n".join(returns) + "\n"


def parse_probe_asm(asm):
    """probe index -> vtable slot the probe calls through."""
    slots = {}
    for m in re.finditer(r"^_probe_(\d+) PROC(.*?)^_probe_\1 ENDP", asm, re.M | re.S):
        body = m.group(2)
        hit = re.search(r"\b(?:call|jmp)\s+DWORD PTR \[\w+(?:\+(\w+))?\]", body)
        if not hit:
            reg = re.search(r"\b(?:call|jmp)\s+(e[a-z]{2})\s*$", body, re.M)
            if reg:
                loads = re.findall(r"mov\s+" + reg.group(1) + r", DWORD PTR \[\w+(?:\+(\w+))?\]", body)
                off = (loads[-1] or "0") if loads else None
            else:
                off = None
        else:
            off = hit.group(1) or "0"
        if off is None:
            continue
        value = int(off[:-1], 16) if off.lower().endswith("h") else int(off)
        slots[int(m.group(1))] = value // 4
    return slots


def parse_layout(report, iface):
    m = re.search(r"(?<!\w)" + re.escape(iface) + r"::\$vftable@:\s*\n(.*?)(?:\n\s*\n|\Z)", report, re.S)
    if not m:
        return None
    return re.findall(r"^\s*(\d+)\s*\|\s*&?(\S+)", m.group(1), re.M)


def check(ifaces):
    specs = [load(SPECS / f"{i}.yaml") for i in ifaces] if ifaces else \
        [load(p) for p in sorted(SPECS.glob("*.yaml"))]
    vc = vcvarsall()
    failures = 0
    for spec in specs:
        iface = spec["interface"]
        if not (INCLUDE / f"{iface}.h").exists():
            print(f"skip {iface}: no header yet")
            continue
        with tempfile.TemporaryDirectory() as work:
            probes, returns = probe_sources(spec)
            Path(work, "probe.cpp").write_text(probes, encoding="utf-8")
            Path(work, "returns.cpp").write_text(returns, encoding="utf-8")
            env = f'call "{vc}" x86 >nul 2>&1 && cd /d "{work}" && cl /nologo /std:c++20 /EHsc '
            # cmd /s keeps the quoted paths intact, Python's list quoting does not.
            r = subprocess.run(f'cmd /s /c "{env}/c /O2 /FAs /Faprobe.asm /d1reportSingleClassLayout{iface} probe.cpp"',
                               capture_output=True, text=True)
            rr = subprocess.run(f'cmd /s /c "{env}/Zs returns.cpp"', capture_output=True, text=True)
            problems = [l.split(": ", 2)[-1] for l in rr.stdout.splitlines() if "error" in l]
            slots = [merged(s) for s in spec["slots"] if s.get("win") is not None]
            for s in slots:
                if any("std::" in t or "__gnu_cxx" in t for t in s["params"] + [s.get("returns") or ""]):
                    problems.append(f"slot {s['win']} {s['name']} uses an STL type, which does not match the game's layout")
            for n, line in enumerate((INCLUDE / f"{iface}.h").read_text(errors="replace").splitlines(), 1):
                if "virtual" in line and re.search(r"\bstd::|__gnu_cxx", line):
                    problems.append(f"{iface}.h:{n} uses an STL type in a virtual, which does not match the game's layout")
            asm_path = Path(work) / "probe.asm"
            if r.returncode != 0 or not asm_path.exists():
                problems += [l for l in r.stdout.splitlines() if "error" in l] or ["compile failed:\n" + r.stdout[-2000:]]
            else:
                got = parse_probe_asm(asm_path.read_text(errors="replace"))
                for s in slots:
                    if got.get(s["win"]) != s["win"]:
                        problems.append(f"slot {s['win']}: {s['name']}({', '.join(s['params'])}) "
                                        f"compiles to slot {got.get(s['win'])}")
                layout = parse_layout(r.stdout, iface)
                if layout is not None and len(layout) != spec["extent"]["win"]:
                    problems.append(f"header declares {len(layout)} slots, the game has {spec['extent']['win']}")
            if problems:
                failures += 1
                print(f"FAIL {iface}")
                for p in problems:
                    print(f"  {p}")
            else:
                print(f"ok   {iface}: {len(slots)} slots match {spec['win']['vftable']}")
    return failures


# ------------------------------------------------------------------- plan

PLANS = ROOT / "re" / "plans"


def header_param_names(iface):
    """(method, param types without spaces) -> parameter names, read from the header."""
    names = {}
    text = (INCLUDE / f"{iface}.h").read_text(errors="replace")
    for m in re.finditer(r"virtual\s+[^;(]*?\b(operator\S+|\w+)\s*\(([^)]*)\)[^;]*=\s*0", text):
        types, idents = [], []
        for p in split_top(m.group(2)):
            if p in ("", "void", "..."):
                continue
            tail = re.match(r"(.*?[\w>*&\s])\s*\b([A-Za-z_]\w*)$", p)
            if tail and not tail.group(2) in ("const", "int32_t", "uint32_t") and \
                    re.search(r"[\w>*&]\s+[A-Za-z_]\w*$", p):
                types.append(tail.group(1))
                idents.append(tail.group(2))
            else:
                types.append(p)
                idents.append("")
        names[(m.group(1), tuple(t.replace(" ", "") for t in types))] = idents
    return names


def plan(iface):
    """Write re/plans/<iface>.tsv for scripts/ghidra/ApplyInterfaceNames.java.
    Only confirmed slots of a spec that passes check, and never a pure slot."""
    if check([iface]):
        raise SystemExit(f"{iface}: check fails, no plan")
    spec = load(SPECS / f"{iface}.yaml")
    win = load(EXTRACT / f"{iface}.win.yaml")
    cls = spec.get("win_class") or spec["mac"]["implementers"][0]["class"]
    names = {**header_param_names(spec["base"]), **header_param_names(iface)}
    rows, skipped = [], []
    for s in map(merged, spec["slots"]):
        if s.get("win") is None or not s.get("win_impl"):
            continue
        if s["status"] != "confirmed" or s["win_impl"] == win.get("pure_stub"):
            skipped.append(f"{s['win']} {s['name']} ({'pure' if s['win_impl'] == win.get('pure_stub') else s['status']})")
            continue
        fixed = [p for p in s["params"] if p != "..."]
        idents = names.get((s["name"], tuple(p.replace(" ", "") for p in fixed)), [])
        # Ghidra needs a size for a by-value type it doesn't know (enums,
        # SC4Rect). With one such parameter, RET n minus the others gives it.
        unknown = [i for i, p in enumerate(fixed) if param_bytes([p]) is None]
        known = sum(param_bytes([p]) for p in fixed if param_bytes([p]) is not None)
        size = (s["ret_n"] - known) if len(unknown) == 1 and s.get("ret_n") else 4
        params = "|".join((f"{p} {idents[i]}".strip() if i < len(idents) else p) +
                          (f"@{size}" if i in unknown else "") for i, p in enumerate(fixed))
        returns = s.get("returns") or ("bool" if s["name"] == "QueryInterface" else
                                      "uint32_t" if s["name"] in ("AddRef", "Release") else "void")
        rows.append("\t".join([spec["win"]["vftable"], str(s["win"]), f"0x{s['win_impl']:08x}", cls, s["name"],
                               returns, params, iface, f"re/vtables/{iface}.yaml", str(spec["win"]["adjust"])]))
    PLANS.mkdir(parents=True, exist_ok=True)
    out = PLANS / f"{iface}.tsv"
    out.write_text("# vftable\tslot\timpl\tclass\tmethod\treturns\tparams\tiface\tspec\tadjust\n" + "\n".join(rows) + "\n",
                   encoding="utf-8")
    print(f"{iface}: {len(rows)} slots -> {out}" + (f", skipped {', '.join(skipped)}" if skipped else ""))


# --------------------------------------------------------------- selftest

def selftest():
    assert parse_demangled("undefined cRZString::IsEqual(char const *,unsigned long,bool) const") == \
        ("IsEqual", ["char const*", "uint32_t", "bool"], True)
    assert parse_demangled("undefined A::QueryInterface(unsigned long,void * *)") == \
        ("QueryInterface", ["uint32_t", "void**"], False)
    assert parse_demangled("undefined A::operator=(cIGZString const &)")[0] == "operator="
    assert parse_demangled("undefined A::Sprintf(char const *,...)")[1] == ["char const*", "..."]
    assert normalize_type("std::vector<unsigned long,std::allocator<unsigned long> > const &") == \
        "SC4Vector<uint32_t> const&"
    assert normalize_type("std::list<cISC4Lot *,std::allocator<cISC4Lot *> > &") == "SC4List<cISC4Lot*>&"
    assert param_bytes(["char const*", "uint32_t", "bool"]) == 12
    assert param_bytes(["double"]) == 8
    assert param_bytes(["cRZPoint"]) is None
    assert expected_ret(["char const*", "..."]) == 0
    # cIGZString's FromChar pair: declared (p), (p, len) and emitted reversed.
    assert msvc_order(["FromChar", "FromChar", "ToChar"]) == [1, 0, 2]
    assert msvc_order(["A", "B", "A", "C", "B"]) == [2, 0, 4, 1, 3]
    # The game's IsEqual order (char, ptr, ref) needs the declarations (ref, ptr, char).
    assert declaration_order(["X", "IsEqual", "IsEqual", "IsEqual"]) == [0, 3, 2, 1]
    assert declaration_order(["A", "B", "A"]) is None
    for names in (["A", "B", "B", "C"], ["Q", "R", "R", "R", "S", "S"]):
        decl = declaration_order(names)
        assert [decl[i] for i in msvc_order([names[i] for i in decl])] == list(range(len(names)))
    asm = ("_probe_4 PROC\n\tmov eax, DWORD PTR [ecx]\n\tjmp DWORD PTR [eax+16]\n_probe_4 ENDP\n"
           "_probe_0 PROC\n\tmov edx, DWORD PTR [eax]\n\tjmp DWORD PTR [edx]\n_probe_0 ENDP\n"
           "_probe_9 PROC\n\tmov eax, DWORD PTR [edx+36]\n\tcall eax\n_probe_9 ENDP\n")
    assert parse_probe_asm(asm) == {4: 4, 0: 0, 9: 9}
    # A slot added after the Mac build lands as an unpaired Windows slot.
    mac = [{"index": i, "name": n, "params": p, "strings": []} for i, (n, p) in
           enumerate([("A", []), ("B", ["uint32_t"]), ("C", ["uint32_t", "uint32_t"])])]
    win = [{"index": i, "ret_n": r} for i, r in enumerate([0, 4, 12, 8])]
    rows = needleman_wunsch(mac, win)
    assert [(r["mac"] and r["mac"]["name"], r["win"] and r["win"]["index"]) for r in rows] == \
        [("A", 0), ("B", 1), (None, 2), ("C", 3)]
    print("selftest ok")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd, rest = sys.argv[1], sys.argv[2:]
    if cmd == "align":
        for i in rest:
            align(i)
    elif cmd == "scaffold":
        scaffold(rest[0])
    elif cmd == "plan":
        for i in rest:
            plan(i)
    elif cmd == "check":
        sys.exit(1 if check(rest) else 0)
    elif cmd == "selftest":
        selftest()
    else:
        raise SystemExit(__doc__)
