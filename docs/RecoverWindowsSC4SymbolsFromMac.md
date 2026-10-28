# RecoverWindowsSC4SymbolsFromMac reference

Last updated: 2026-08-14

## Usage

1. Put
   [`RecoverWindowsSC4SymbolsFromMac.java`](../scripts/ghidra/RecoverWindowsSC4SymbolsFromMac.java)
   in a Ghidra script directory and refresh the Script Manager.
2. In one CodeBrowser window, open:
   - Windows `SimCity 4.exe` 1.1.641, x86-32;
   - the symbolized Mac x86-32 SC4 program.
3. Make the Windows program current. The script always writes, if approved, to
   the current program. Since v3 it refuses to run if the roles are reversed:
   the target must be a Portable Executable without `__ZTV*` symbols, and the
   source must be the Mach-O build that has them. Pointer size cannot catch
   this — both builds are 32-bit — and with the Mac program current the
   analysis silently returns zero for everything while writes land in the wrong
   database.
4. Run `RecoverWindowsSC4SymbolsFromMac`.
5. Select the Mac source by its full project path. If it is the only other open
   program, it is selected automatically. Source and target cannot be the same
   program.
6. Select the `gzcom-dll/include` directory when prompted. This is optional but
   strongly recommended: it supplies the GZIID allowlist and the declared
   virtual-method order, which are the two strongest evidence sources the
   script has. Cancelling falls back to the older CMP-only heuristic, which
   silently misses ids and admits noise.
7. Enter a Java class regex. Use `.*` for a full run; use a narrow expression
   such as `.*cRZString.*` while checking a heuristic.

   Filter on **concrete** classes. A `cIGZ*` interface is pure-virtual — every
   slot of its Mac vtable points at `___cxa_pure_virtual` — so it has no
   `QueryInterface` body to fingerprint and is skipped by design. Filtering on
   interface names yields `Mac QI-vtable classes=0`, which looks like a failure
   but is correct behaviour.
8. Choose whether to enable database-free BSim matching. It is read-only but
   materially increases runtime. The current vector budget is 10,000.
9. Review the complete analysis report and the final **Apply candidates**
   summary.
10. Choose **No** at **Apply conservative Windows annotations?** for a preview.
   No names, labels, comments, types, functions, or properties are changed.
11. Rerun and choose **Yes** only after verifying the selected programs and
    candidate set. Apply runs in one Ghidra transaction and rolls back on an
    unhandled error.
12. Review Listing/Symbol Tree changes before saving. The script never saves
    the Windows program automatically.
13. If apply created functions at former `LAB_*` vtable targets, rerun the
    analysis. The second run can use those functions for strings and BSim.

## Run logs

Every run writes a timestamped log to:

```text
%USERPROFILE%\ghidra_scripts\logs\RecoverWindowsSC4Symbols-<yyyyMMdd-HHmmss>.log
```

The path is printed at the top of the console output. Each run gets its own
file so runs stay comparable rather than overwriting each other, and every log
is stamped with `SCRIPT_VERSION` — bump that constant whenever a heuristic or
threshold changes, otherwise logs from different behaviour cannot be compared.

The log mirrors the console verbatim and adds records the console suppresses:

| Record | Meaning |
|---|---|
| `CLASS\|...` | One per analysed class, ignoring the console's report cap: confidence, score, margin, shared/missing/extra ids, table address, slot count, and the name/string/BSim/header/order anchor counts. |
| `NOCANDIDATE\|...` | A Mac class with ids that matched no Windows candidate at all — the clearest signal that Windows-side id extraction missed something. |
| `RENAME\|...` | Each applied primary name with the evidence and confidence behind it, so a wrong name can be traced to the rule that produced it. |
| `=== RUN SUMMARY ===` | Flat `key=value` totals: allowlist size, header class counts, per-confidence match counts, proposals, and how many proposals had header order behind them. |
| `=== APPLY SUMMARY ===` | Flat `key=value` apply counters, written only when apply was accepted. |

The summary blocks are deliberately flat so two runs can be diffed directly to
see what a change actually moved:

```bash
diff <(sed -n '/RUN SUMMARY/,/END RUN/p' old.log) \
     <(sed -n '/RUN SUMMARY/,/END RUN/p' new.log)
```

Read the console in this order:

1. selected Mac and Windows program paths/languages;
2. QI class counts and confidence;
3. chosen and alternate Windows vtables;
4. existing-name, string, BSim, and order anchors;
5. shared/folded implementation roles;
6. missing and creatable vtable functions;
7. apply counters.

`vtable labels added/promoted=0` on a later run commonly means the labels are
already present. Check `unique-class vtables` and `vtable labels already
present` before treating zero as a failure.

## Goal and scope

The script uses the symbolized Mac x86-32 build as an intent/name oracle and
the stripped Windows x86-32 build as the implementation authority. It recovers
Windows class, vtable, virtual-method, shared-implementation, and selected data
names without assuming GCC and MSVC emitted identical code or vtable order.

Primary goals:

- use GZCOM `QueryInterface` IDs to bridge class identities across builds;
- find Windows vtables and their real Windows-derived extents;
- match virtual methods with several independent evidence sources;
- keep compiler-folded and inherited implementations associated with every
  prospective role;
- include executable targets Ghidra still represents as `LAB_*`;
- preserve manually recovered Windows symbols and comments;
- mark inferred identities visibly;
- make database edits optional, transactional, reviewable, and unsaved.

Non-goals:

- do not copy Mac addresses, layouts, calling conventions, bytes, function
  boundaries, or slot offsets to Windows;
- do not use header method counts as Windows vtable extents (header *order* is
  used as a method anchor; the extent still comes from Windows memory);
- do not treat raw same-slot order or BSim alone as proof;
- do not overwrite meaningful manual work;
- do not automatically transfer signatures, parameters, structures, member
  offsets, constructors, or non-unique global data;
- do not save the Ghidra program automatically.

## Binary and ABI roles

### Mac source

The source is the non-stripped x86-32 Mac build using Itanium ABI vtables. It
supplies:

- `__ZTV*` class/vtable symbols;
- demangled class and method names;
- primary-vtable order as a hypothesis;
- `QueryInterface` implementations and their GZIID comparisons;
- distinctive strings and BSim signatures.

### Windows target

The target is 32-bit MSVC `SimCity 4.exe` 1.1.641. It supplies all final facts:

- addresses and function boundaries;
- executable and initialized-memory regions;
- vtable addresses, extents, and slot positions;
- thunks and folded implementation addresses;
- existing manual names/comments;
- calling conventions, structures, and patch facts for later manual work.

The old Win32 GZCOM headers remain useful reference material but are not an
emitted-table specification. Concrete tables can contain inherited
`cIGZUnknown::QueryInterface`, `AddRef`, `Release`, and other base entries not
included in a basic declared-method count.

## Recovery algorithm

### Phase 0: header ground truth

When the `gzcom-dll/include` directory is supplied, the script parses:

- every `NAME = 0x...` constant in the range `[0x1000, 0xffffffff]`, giving an
  allowlist of real interface/class IDs (about 390 from the current headers);
- each class's declared pure-virtual methods **in order**, with its base list.

Both are ground truth that no amount of binary analysis can reconstruct
reliably. ID extraction without the allowlist cannot tell a GZIID from a struct
displacement, and cannot recover an ID that MSVC folded a `DEC` into.

Doc comments are stripped before parsing because they contain braces and
semicolons. A declaration may wrap across lines, and the base clause is
optional — `cIGZUnknown` declares none, and if it fails to parse then no class
resolves at all.

### Phase 1: Mac class fingerprints

1. Find Mac `__ZTV*` symbols matching the class regex.
2. Demangle each class name.
3. Read the Itanium primary vtable after its two-word header.
4. Resolve the first slot through thunks and adjustor thunks.
5. Keep tables whose first named method is `QueryInterface`.
6. Extract interface IDs from that function (see below).
7. Keep classes with at least one nontrivial ID.

ID extraction scans `CMP`, `SUB`, `ADD`, and `XOR`. It deliberately excludes
`PUSH` and `MOV`: those are call sites passing an ID *to* `QueryInterface`, and
including them would make every caller look like a QI implementation. In
`SimCity 4.exe`, `GZIID_cIGZWin` appears at over 40 `PUSH` sites against only 6
`CMP` sites.

Each immediate is normalised onto its canonical allowlist ID, tolerating a
small upward fold. MSVC lowers an ID chain to a binary search and folds an
adjacent `DEC` into the following constant, so the literal can sit just below
the ID it stands for. `cSC4OccupantManager::QueryInterface` at `0049d910`
tests `kcIGZMessageTarget2` (`0x452294aa`) as `DEC EAX; SUB EAX,0x452294a9`.
Folding resolves upwards only, and an exact hit always wins: GZCOM assigns a
class's interface and class IDs one apart (`GZIID_cIGZWinSlider` `0x21325207`
versus `kcGZWinSlider` `0x21325208`), and ten such pairs exist.

`riid == 1` (`cIGZUnknown`) compiles to `DEC EAX` and carries no immediate, so
it is never observed. It is trivial for matching strength either way.

Without an allowlist the script falls back to `CMP`-only extraction with a
`0x1000` plausibility floor. This is lossy in both directions and is retained
only so the script still runs unaided.

### Phase 2: Windows QI candidates

1. Scan non-external, non-thunk Windows functions.
2. Extract IDs exactly as in Phase 1, **unfiltered**.
3. Keep functions sharing at least one nontrivial ID with the Mac side.
4. Rank every Windows candidate against each Mac class fingerprint.

Step 2 is deliberately unfiltered. Restricting extraction to IDs some Mac class
already had made every Windows ID set a subset of the Mac side, so `exact` and
`extra` could never observe a Windows-only ID and `confirmed` was reachable on
evidence that was not exact at all.

Current score:

```text
sum(40 / Mac-frequency(ID)) for each shared nontrivial ID
+ 4 * shared IDs
- 10 * missing IDs
- 3 * extra IDs
+ 20 if the complete fingerprints are identical
```

Rare interface IDs contribute more than common IDs.

Class confidence:

| Class | Condition |
|---|---|
| `confirmed` | Unique Mac fingerprint; exact Windows fingerprint; at least two shared nontrivial IDs; exactly one exact Windows candidate. |
| `strong` | Unique Mac fingerprint; at least two strong shared IDs; few missing IDs; score margin at least 8. |
| `single-id` | Unique exact one-ID fingerprint with score margin at least 8. |
| `family` | Several Mac classes share the same fingerprint; extra method evidence is required. |
| `probable` | At least one nontrivial shared ID, but insufficient stability for application. |
| `weak` | No useful nontrivial match. |

A different existing Windows function name is neutral evidence. MSVC can fold
identical implementations used by unrelated classes, and prior analysis may
have selected only one valid role as the primary name.

Each Mac class picks its best Windows candidate independently, so several
classes can claim one folded `QueryInterface`. After scoring, only the
strongest claimant on each Windows function keeps its confidence; the rest are
demoted to `probable`, which is below the bar for applying names or creating
functions. This matters because near-identical QI bodies are common — `004a74e0`
is byte-for-byte identical to `0049d910` apart from a single immediate.

### Phase 3: Windows vtable discovery

1. Raw-scan non-executable initialized Windows memory for aligned pointers to
   the selected Windows QI function.
2. Require the candidate start to contain at least three executable pointers,
   corresponding to the normal QI/AddRef/Release prefix.
3. Read slots until:
   - a pointer is unreadable;
   - a pointer is null;
   - a target is non-executable;
   - another known QI target appears after slot zero;
   - the 256-slot safety cap is reached.
4. Accept executable targets even when Ghidra has no `Function` there. This
   keeps `LAB_*` targets in the vtable extent.
5. Rank competing tables by method anchors:
   - exact existing-name anchor: 100;
   - shared-string anchor: 40;
   - BSim anchor: 20;
   - same-slot anchor count breaks remaining ties.
6. Resolve a table when:
   - it is the only non-family candidate; or
   - it is the unique best candidate with at least one positive anchor.
7. Require at least two positive anchors for a family match.

Windows memory determines Windows table extent. Mac slot count and header
method count do not.

### Phase 4: method anchors

Evidence is accumulated from strongest to weakest.

#### Header declaration order

The headers state slot order outright, which no amount of binary analysis
recovers as reliably.

The headers declare only pure-virtual **interfaces** (all 217 of them; not one
declares an override-style concrete method), while the classes reaching this
point are concrete implementations. The two never share a name, so matching on
the class name alone finds nothing. The bridge is the QI fingerprint: each id
maps back to the `GZIID_<interface>` constant that defined it, naming an
interface the class implements. `cRZString` reaches `cIGZString` through
`GZIID_cIGZString` (`0x89b7dc8`), never by name.

A class usually implements several interfaces and only its first base shares
the primary vtable, so the candidate is chosen by agreement with the **Mac**
vtable's own symbol names — evidence independent of anything on the Windows
side. At least three slots must agree, no slot may contradict, and the winner
must be unique.

1. Resolve each candidate's expected order by walking its **first** base to
   `cIGZUnknown`, then appending its own declarations. Only the first base
   shares the primary vtable on either ABI, so later bases are excluded rather
   than concatenated.
2. Require the chain to be fully known and rooted at `cIGZUnknown`. That root
   is what guarantees slots 0-2 are `QueryInterface`/`AddRef`/`Release`. A class
   whose base is unknown, or which declares no base of its own (`cIGZKeyboard`),
   is rejected rather than guessed at.
3. Require the observed Windows table to be at least as long as the declared
   order.
4. Reject the entire mapping if any existing Windows name contradicts it. A
   contradiction means the class identity or base chain is wrong, and applying
   the remainder would spray confident wrong names across the whole table.

GZCOM interfaces declare no virtual destructor, so declaration order is vtable
order on both ABIs and the Itanium `D1`/`D0` pair never intrudes. Of the 217
classes parsed from the current headers, 216 resolve; `cIGZWin` yields 147
slots, `cIGZVariant` 127.

Note this uses header order, never header *counts*, as table extent — a
concrete class may legitimately have more slots than its interface declares.

#### Existing method names

- Compare leaf method names instead of class namespaces.
- Require the leaf name to occur uniquely in each table.
- Resolve thunks before comparing implementations.
- Ignore generated/default names, `_purecall`, `__purecall`, inferred names
  ending in `?`, and all names under `OOAnalyzer::`.
- Agreement is positive evidence; disagreement is neutral.

Existing appropriate Windows names are valuable cross-checks. A mismatching
name is not a penalty because one implementation can serve several classes or
roles.

#### Shared strings

1. Resolve thunks and group repeated slots by implementation.
2. Collect strings referenced by every Mac and Windows implementation.
3. For each Mac implementation, choose the unique Windows implementation with
   the greatest total length of shared strings.
4. Require a unique best result and total shared-string weight at least 8.
5. Preserve source and destination slot lists so many-to-one reuse remains
   visible.

A unique source/destination implementation is `string`; reused or ambiguous
slot membership is `string?`.

#### BSim

- Generate database-free Ghidra BSim signatures.
- Skip methods already anchored by name or string.
- Resolve thunks and group duplicate table entries by implementation.
- Accept only the unique best candidate satisfying:
  - similarity at least `0.72`;
  - significance at least `10.0`;
  - similarity margin over runner-up at least `0.04`.
- Limit generation to 10,000 functions per run.
- Calibrate the observed score distribution using known name/string pairs.

BSim is supporting evidence. It can justify an explicitly inferred role but
not an exact semantic name by itself.

#### Anchor-bounded order

Raw order is not copied between ABIs. It is used only inside regions bounded by
stable anchors:

1. Treat slot 2 as the end of the QI/AddRef/Release prefix.
2. Add unique name, string, and BSim pairs as stable anchors.
3. Sort anchors by Mac and Windows slot.
4. Between consecutive anchors, require an identical
   `WindowsSlot - MacSlot` delta.
5. Map only slots inside that constant-delta interval.
6. Do not extrapolate beyond the last anchor.
7. Do not replace positive evidence with order inference.

This handles ABI-specific inserted or removed regions without assuming global
same-index order.

### Phase 5: shared implementations and thunks

Ghidra only recognises a pure `JMP` as a thunk. MSVC multiple-inheritance
adjustor thunks adjust `this` first (`SUB ECX,0x4; JMP real`), so Ghidra leaves
them as ordinary functions — `SimCity 4.exe` contains over a thousand, many
named `QueryInterface`, for example `004abd00`. The script therefore resolves
adjustor thunks itself: an optional `SUB`/`ADD` followed by an unconditional
jump and nothing else, up to 8 hops. Anything longer is a real function that
happens to end in a tail call and is left alone.

Without this, every adjustor thunk counted as its own implementation, which
defeats the folding detection this script is built around and points string and
BSim matching at two-instruction bodies carrying no evidence.

- Store roles against the resolved implementation address.
- Retain the original vtable target in role records and comments.
- Keep repeated slots as repeated roles even when they resolve to one body.
- Treat several class roles on one body as compiler reuse, not contradiction.
- Keep meaningful manual primary names.
- Add per-role aliases instead of choosing an arbitrary last-processed role as
  the primary name.

Report markers:

| Marker | Meaning |
|---|---|
| `G` | GZIID/QI identity. |
| `=` | Existing-name agreement. |
| `H` | Header declaration order. |
| `~` | Unique string evidence. |
| `~?` | Reused/ambiguous string evidence. |
| `b` | BSim evidence. |
| `?` | Order-only or otherwise prospective role. |

### Phase 6: missing `LAB_*` functions

An executable vtable target without a Ghidra function still contributes to
table extent and slot comments. During optional apply the script creates a
function only when:

- the class/table proposal is `confirmed`, `strong`, or `single-id`;
- no function begins at the target;
- no existing function contains the target;
- a defined instruction begins exactly at the target;
- the target primary symbol is replaceable.

Creation uses Ghidra's native `CreateFunctionCmd` with `ANALYSIS` source. The
new function is not assigned a semantic name merely because it was created.
Strings and BSim become available on the next run.

### Phase 7: naming

| Evidence/state | Applied form |
|---|---|
| Stable GZIID `QueryInterface` | Exact Mac method name. |
| Header declaration order | Exact `Class::Method` from the header. |
| Unique existing-name anchor | Exact method name. |
| Unique shared-string anchor | Exact method name. |
| Reused/ambiguous string anchor | `WindowsClass::Method?`. |
| BSim-only anchor | `WindowsClass::Method?`. |
| Anchor-bounded order | `WindowsClass::MethodAtMappedMacSlot?`. |
| Stable slot with no Mac mapping | `WindowsClass::vfuncNN`. |
| Shared implementation | Per-class role aliases; primary name only when unambiguous and replaceable. |

For inferred names, the recovered Windows class supplies the owner and the
mapped Mac slot supplies only the leaf method name. This avoids presenting an
inherited Mac namespace as confirmed Windows ownership.

The literal `?` suffix is accepted by Ghidra 12.1.2. It means the method
identity is inferred and should be checked. Such names remain replaceable so
stronger evidence can upgrade them later.

`vfuncNN` uses the actual Windows slot number. It is preferable to leaving a
stable class vtable full of `FUN_*` targets when no method mapping exists.

## Replaceable and protected symbols

Replaceable:

- `DEFAULT` symbols;
- generated `ANALYSIS` or `USER_DEFINED` names such as `FUN_*`, `LAB_*`,
  `DAT_*`, `PTR_*`, `UNK_*`, `undefined_*`, `thunk_FUN_*`, and `s_*`;
- inferred names ending in `?`;
- every qualified name beginning with `OOAnalyzer::`, regardless of source
  type.

A `USER_DEFINED FUN_*` is generated-looking state, not protected manual work.
OOAnalyzer names are wholly disposable because they came from an earlier
external analysis pass.

Protected:

- meaningful non-placeholder USER_DEFINED or IMPORTED names;
- manual comments outside script-owned blocks;
- non-analysis aliases colliding with a proposed name.

When a same-address analysis label collides with a function rename, the script
removes the disposable alias and atomically calls `setNameAndNamespace` on the
function symbol. A protected collision is skipped and counted instead of
aborting the whole apply.

## Vtable labels and comments

A stable unique-class QI match is sufficient to add or promote
`Class::vtable`, even when few methods have individual identities. Shared,
family, or tied tables receive candidate comments without a misleading
single-class primary label.

Compact plate comment:

```text
SC4Mac vtable: cSC4PropDeveloper
QI: strong | GZIID=[0x..., 0x...]
Slots: 12 | stop=<boundary>
Anchors: name=1 string=1 BSim=1 order?=6
```

Compact slot comment:

```text
SC4Mac slot: Win[03] <- Mac[03] cSC4PropDeveloper::Init? | order
```

Implementation repeatable comment:

```text
SC4Mac roles:
string cFoo::Method <- cFoo[04]
order? cBar::Other <- cBar[09]
```

The redundant `Vtable roles:` heading is omitted. Script-owned text is kept in
an updateable block when a manual comment already exists:

```text
[SC4Mac]
...
[/SC4Mac]
```

Old script-owned blocks are replaced with the current compact layout;
unrelated manual comment text is preserved.

## Data-name transfer

1. Find strings occurring exactly once in each build.
2. Require a useful named Mac data symbol.
3. Propose that name for the corresponding Windows address.
4. Protect a meaningful existing Windows name.
5. Record the unique string in an EOL provenance comment.

This intentionally yields few labels. Common strings are weak cross-build
anchors.

## Apply safety and idempotency

No writes occur before the final confirmation. If accepted:

- all changes occur in one explicit Ghidra transaction;
- an unhandled exception rolls the transaction back;
- the program is not saved;
- meaningful manual names and comments are preserved;
- duplicate disposable aliases are handled locally;
- exact existing labels count as already present;
- inferred and generated names remain upgradeable;
- counters distinguish candidates, already-present data, additions, rejected
  tables, ambiguous implementations, comments, and protected skips.

Repeated runs are expected. A later run should mainly add evidence enabled by
newly created functions or upgrade earlier `?`/placeholder names. Apply output
counts additions during that run, not the total recovered database state.

## Current status

Implemented:

- explicit two-program selection with full project paths;
- GZIID/QI fingerprint discovery, scoring, and confidence classes;
- raw Windows vtable discovery with Windows-derived extent;
- executable `LAB_*` participation and safe optional function creation;
- existing-name, unique-string, BSim, and bounded-order anchors;
- thunk-aware and compiler-folding-aware implementation grouping;
- stable `Class::vtable` labels and ambiguous candidate comments;
- exact, `Class::Method?`, and `Class::vfuncNN` naming;
- per-role aliases for shared implementations;
- protection of meaningful manual work;
- replacement of generated USER_DEFINED names and all `OOAnalyzer::` output;
- duplicate-name collision handling;
- unique-string data proposals;
- compact owned provenance comments;
- self-checks for QI scoring, interpolation, repeated slots, namespace
  parsing, inferred names, placeholder classification, and rename collisions.

Representative full-run observations:

- 10,000 BSim vectors;
- more than one million BSim comparisons;
- roughly 250 resolved class-vtable proposals;
- more than 1,100 prospective Windows implementations;
- 211 vtable labels and 234 function names added in an early apply;
- thousands of compact comments;
- dozens of folded/shared implementation role groups.

Validation state:

- the script compiles against Ghidra 12.1.2;
- only existing `DemanglerUtil.demangle` deprecation warnings remain;
- QI, vtable, string, BSim, reporting, transaction, and earlier apply behavior
  have been exercised on the SC4 programs;
- the latest `Class::Method?`/`Class::vfuncNN` writer and broader placeholder
  policy have compile/self-check coverage and still need a complete runtime
  apply/review;
- persistence of earlier changes depends on whether the Windows program was
  manually saved after apply.

## Verified binary facts

Checked against `/Windows/WINDOWS_SimCity 4.exe` 1.1.641 (image base
`0x400000`, 25778 functions) on 2026-08-14:

- **MSVC RTTI is not a usable shortcut.** Only 59 `.?AV...@@` type descriptors
  exist, nearly all STL/exception; the only SC4 ones are `cIGZExceptionBase`
  and `cParseError@nSCRes`. GZCOM vtables carry no
  `RTTICompleteObjectLocator` — at the `cSC4OccupantManager` vtable
  `00a8f7f0`, the word at vtable-4 is `0x00000000`. Any approach assuming MSVC
  RTTI gives exact vtable starts or class names is dead on arrival, and the
  QI-fingerprint bridge is therefore necessary rather than merely convenient.
- **Table boundaries were cleanly separated in the sampled region.** The
  `cSC4OccupantManager` table is bounded by a null dword before it and
  non-code `.rdata` pointers after it, both already caught by the existing stop
  rules. Vtables were not packed adjacently there.
- Mac Itanium emits multiple destructor bodies per class
  (`~cSC4OccupantFilter` at `00079348` and `00079368`) against MSVC's single
  scalar deleting destructor. This does not affect GZCOM interfaces, which
  declare no virtual destructor.

## Known limitations

- Without the header allowlist, ID extraction is `CMP`-only: it misses
  `SUB`/`ADD`-encoded IDs entirely and admits memory-operand displacements as
  phantom IDs.
- The fold tolerance assumes a single folded `DEC`. A longer arithmetic chain
  would move an immediate further from its ID than the window allows.
- Header order applies only to classes whose first-base chain is fully known and
  rooted at `cIGZUnknown`, and only to the primary vtable; secondary
  (multiple-inheritance) tables are not covered.
- Header order assumes the checked-in headers match this build of the game.
- Common or reused GZIIDs can produce a family/probable result.
- A shared QI implementation can lead to several candidate tables.
- The three-executable-entry start rule assumes the normal GZCOM prefix.
- BSim quality varies across compilers and optimization levels; thresholds are
  empirical.
- The 10,000-vector cap can leave later classes without BSim coverage.
- Newly created functions require another run before strings/BSim use them.
- Constant-delta interpolation cannot solve arbitrary internal reordering.
- Destructors, adjustor thunks, covariant-return thunks, and secondary bases can
  create several valid entry points for one role.
- Existing Ghidra function bodies can be wrong; the script refuses overlaps
  but does not repair every bad body.
- Unique-string data matching deliberately ignores common data.
- Signatures, calling conventions, structures, and member offsets remain
  manual Windows-confirmation work.
- `?` and `vfuncNN` names are navigation aids, not reviewed header evidence.

## Potential next steps

1. Run the latest writer on a narrow class such as `cSC4PropDeveloper` and
   verify exact, `?`, `vfuncNN`, vtable-label, and owned-comment behavior.
2. Run a complete apply, rerun after function creation, and compare string,
   BSim, and primary-name counts.
3. Sample false positives and tune BSim similarity, significance, and margin
   from known-anchor calibration.
4. Add optional CSV/JSON reporting for class, table, slot, implementation,
   proposed role, evidence, and confidence if console review becomes limiting.
5. Add Windows PCode, call-graph, and distinctive-constant evidence where
   strings/BSim are weak.
6. Recognize trivial implementations such as constant getters, `return 0`,
   purecall, and destructor thunks without allowing them to dominate matches.
7. Improve secondary-vtable and adjustor-thunk ownership reporting.
8. Consider an optional in-run second analysis pass after creating functions
   only if rerunning manually proves too costly.
9. Add targeted constructor, destructor, global, and static-data recovery using
   Windows xrefs and unique constants.
10. Recover prototypes and class layouts only after confirming Windows calling
    convention, `this`, parameters, and field offsets.
11. Feed manually confirmed names/signatures into the GZCOM headers and a
    reviewed symbol archive.
12. Replace the deprecated demangler API when required by a future Ghidra.
13. Add a captured small report regression fixture only if heuristic changes
    become frequent.

## Decision summary

- Mac symbols explain intent; Windows establishes implementation facts.
- GZIID/QI is the primary class bridge.
- Windows memory establishes Windows vtable extent and slot targets.
- Exact-name agreement and unique strings are the strongest method anchors.
- BSim is supporting evidence with explicit thresholds.
- Order is used only between stable constant-delta anchors.
- Name disagreement is neutral because MSVC can reuse implementations.
- Shared code keeps multiple role aliases instead of a false single identity.
- Stable inferred identities use `Method?`; stable unknown slots use
  `vfuncNN`.
- Correct QI identity is enough for a unique vtable label/comment even when
  individual methods remain unknown.
- Manual work is protected; generated and OOAnalyzer output is replaceable.
- Writes are optional, transactional, compactly documented, and never saved
  automatically.
