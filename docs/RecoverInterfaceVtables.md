# RecoverInterfaceVtables reference

This pipeline turns a GZCOM interface that the SDK can name but not use into a
header whose vtable matches `SimCity 4.exe` 1.1.641. It is aimed at interfaces
like `cISC4AnimationTickManager`, which `GZServPtrs.h` already hands out as a
service pointer without ever declaring a method.

Two binaries feed it, and they are trusted for different things:

- The symbolized Mac x86-32 build supplies method names and exact parameter
  types. It predates 1.1.641, so a method can be missing or have a different
  signature there. Its slot order is a starting guess, nothing more.
- The Windows game supplies the slot order and the slot count that actually
  shipped. MSVC also orders overloads differently from gcc, which is another
  reason the Mac order cannot be copied.

The final header is never trusted on prediction. `vtables.py check` compiles
it with MSVC and reads back the slot each method lands in.

## Usage

```text
# 1. Mac export (read-only). GZIID from GZServPtrs.h or the implementer's QueryInterface.
run ExportInterfaceVtable.java on /Mac OS X/OldMacVersions/MacOS_x86-32-cpu0x3
    args: mac <repo>\re\extract cISC4AnimationTickManager=0x86ad10ed

# 2. Windows export (read-only). Reads the Mac export for the GZIID and base offsets.
run ExportInterfaceVtable.java on /Windows/WINDOWS_SimCity 4.exe
    args: win <repo>\re\extract cISC4AnimationTickManager

# 3. Align, review, scaffold, check.
python scripts/vtables.py align cISC4AnimationTickManager
#    edit re/vtables/cISC4AnimationTickManager.yaml: human: maps for returns and any review slots
python scripts/vtables.py scaffold cISC4AnimationTickManager
python scripts/vtables.py check            # every spec in re/vtables
python scripts/vtables.py selftest

# 4. Name the Windows functions (persistent, so dry run first).
python scripts/vtables.py plan cISC4AnimationTickManager      # re/plans/<iface>.tsv
run ApplyInterfaceNames.java on /Windows/WINDOWS_SimCity 4.exe
    args: dry   <repo>\re\plans\cISC4AnimationTickManager.tsv ...   # counts, plus skipped slots
    args: list  <plan> ...                                          # every slot
    args: apply <plan> ...
```

The Ghidra script runs from the Script Manager or through the MCP bridge
(`run_ghidra_script` with the program path and args). Both modes accept several
interfaces at once. `vtables.py` needs PyYAML and a Visual Studio install with
the x86 C++ tools.

Files:

| Path | Owner | Content |
|---|---|---|
| `re/extract/<iface>.mac.yaml` | Ghidra script | Raw Mac facts. Regenerated, never edited. |
| `re/extract/<iface>.win.yaml` | Ghidra script | Raw Windows facts. Regenerated, never edited. |
| `re/vtables/<iface>.yaml` | `align` plus review | The layout spec. `align` rewrites machine fields and keeps each slot's `human:` map. |

## Mac export

1. Read the interface's own vtable from `__ZTV<len><iface>`. GCC emits it for
   the out-of-line interface constructor, so it exists even though no object
   of the interface type does. Every entry is `___cxa_pure_virtual`, and the
   count is the Mac slot count including the base. Any entry that is not pure
   is reported in `non_pure_slots`.
2. Read the base from the `__si_class_type_info` or `__vmi_class_type_info`
   record at `__ZTI<len><iface>`. The base's own `__ZTV` gives the number of
   inherited slots (3 for `cIGZUnknown`).
3. Find implementers by scanning every `__ZTI*` record for one that lists the
   interface as a direct base. The base offset picks the matching group in the
   implementer's `__ZTV`, the one whose offset-to-top is minus that offset.
   When the implementer is abstract (`cSC4AutomataController`), its pure
   slots are named from up to three concrete subclasses, listed under
   `derived:`.
4. For each slot, follow thunks to the implementation and read its mangled
   `__ZN`/`__ZNK` label. Ghidra keeps these as secondary labels, next to
   `.eh` duplicates that are skipped. The demangled signature gives the name
   and exact parameter types. Ghidra's demangled text drops the method's
   `const`, so `vtables.py` reads it from the `K` in `__ZNK` instead.
5. Record the decompiler's return-type guess, the instruction count, direct
   callees, the slots the body calls through a vtable, and up to three
   strings. Mangled names do not encode return types, and the decompiler
   guesses are poor (`AddRef` comes out as `void`), so returns are always
   reviewed by hand.

## Windows export

1. Find the implementer's `QueryInterface`. MSVC encodes the riid test three
   ways, and all three occur:
   - `CMP EAX,G`. Only an exact match counts here. A bare `CMP` against G-1 is
     a different interface (`0xfac0e219` is `cIS3DTextureBindingFactory`, one
     below `cIS3DMaterialManager`).
   - `SUB EAX,G-1` after a folded `DEC`.
   - A subtraction chain (`DEC`, `SUB a`, `JZ`, `SUB b-a`, `JZ`) where the
     immediate is G minus the previous ID tested. The previous ID is one of
     the implementer's other interface IDs, which the Mac export lists.
   Candidates must end in `RET 8`. A `QueryInterface` that Ghidra never made
   into a function (`cSC4PathFinder`, `cS3DMaterialManager`) is still found:
   its entry is the nearest earlier address a vtable points at.
2. Find every vtable whose first entry points at that `QueryInterface`, or at
   an adjustor thunk (`SUB ECX,K` then `JMP`) whose `K` equals a Mac base
   offset. MSVC places the bases in the same order as gcc, and interfaces hold
   only a vptr, so the offsets agree.
3. Work out the extent:
   - **pure-table:** the implementer's constructor still stores the
     interface's own pure-virtual table (every entry `005d4a10`) just before
     the concrete one. This is the best source, and it is how a slot added
     after the Mac build shows up. Tables no longer than the base are the
     base's and are ignored.
   - **subtable-run:** a secondary table ends where the next one starts.
     Tables sit back to back in `.rdata` with no RTTI or zero word between
     them, so a run stops at the first later address that some instruction
     stores with `MOV [mem],address`, the way a constructor installs a vtable.
   - **primary-run:** a primary table that stops at or before the Mac count.
   - **mac-prior:** a primary table that runs on into the concrete class's own
     virtuals. The Mac count is used, and `align` adds a note that appended
     slots would be missed.
4. For each slot, record the implementation (through adjustor thunks), the
   `RET n`, the existing Windows name, strings, callees and vtable calls.
   `RET n` is left empty for a tail call, which says nothing about its own
   arguments.

## Align

`vtables.py align` pairs Mac slots with Windows slots and grades each pair.

1. When several Windows tables match, a table with a measured extent
   (pure-table, then a table run) beats one that falls back to the Mac count.
   Otherwise the primary table of a class that has the interface at another
   offset can win, as `cSC4CrimeSimulator`'s did for `cISC4PropRequester`.
   Among equals, the implementer's own table is the one whose implementations
   the others reuse most, since derived classes keep the implementer's
   `QueryInterface`.
   No `QueryInterface` at all means the class was not linked into the game
   (`cIGZRealtimeDebugDisplay`), and `align` stops with that message.
2. The Mac order is turned into the order MSVC would produce: same-name
   overloads move to the first one's slot, in reverse declaration order.
3. Inside each overload group, every permutation is scored and the best one
   wins. On a tie the MSVC prediction is kept and the group goes to review.
4. Evidence per pair:
   - `ret_n`: the Windows `RET n` equals the parameter bytes from the Mac
     types. A mismatch puts the slot in review, because it means either a
     misalignment or a signature that changed after the Mac build.
   - `win_name`: an existing Windows name with the same leaf.
   - `strings`: a shared string reference.
   - `call_edges`: a call between two slots that exists on both builds,
     either direct or through the vtable. Overloads that differ only in
     pointer versus reference have identical `RET n`, and this is usually the
     only thing that separates them, because one overload wraps the other.
   - `pure_both`: the slot is pure on both builds, which pins it by position.
5. Status:
   - `confirmed`: `ret_n` agrees and there is soft evidence, or the slot is
     not overloaded and the whole table agrees on `RET n`.
   - `probable`: only `ret_n` or only soft evidence.
   - `review`: a `RET n` mismatch, an undecided overload group, or a gap.

When the Mac and Windows counts differ, the slots are paired by an
edit-distance alignment. Unpaired Windows slots become `win-only` with an
`UnknownNN` name and `uint32_t` parameters adding up to `RET n`, which keeps
the stack balanced. The whole table goes to review.

## Containers and other library types

The game was built against Maxis's old STL. Its containers have a different
layout from both gcc's and modern MSVC's, and they allocate through the game's
allocator. A modern `std::vector&` in an interface header compiles and passes
the slot check, then corrupts memory at runtime. So:

- `align` maps the Mac `std::vector`, `std::list` and `__gnu_cxx::hash_set` to
  `SC4Vector`, `SC4List` and `SC4HashSet`, and `scaffold` includes them.
- Types without an SC4 equivalent (`std::deque`, `std::map`, nested library
  classes) become an opaque forward declaration or `void*` with a comment.
- `check` fails on any `std::` or `__gnu_cxx` type left in a spec or in a
  header's virtual declarations.

Game strings are `cRZString` objects. Interfaces take them as `cIGZString`, or
as `SC4String` where the repo already does.

## Review

Corrections go in the slot's `human:` map, which `align` keeps across reruns.
It accepts `name`, `params`, `returns`, `const`, `status`, `virtual_name` and
`note`. Every own slot needs `returns` before scaffolding. A function-pointer
parameter goes in as a typedef inside the interface class
(`cIGZRegistry::EnumCallback`), because the probe generator cannot spell a
function-pointer parameter inline.

## Scaffold

`vtables.py scaffold` writes a new header (it never overwrites one). The
declaration order is the inverse of the modern MSVC rule, worked out from the
Windows slot order, so it does not depend on the Mac order being right. An
overload group that is split across the Windows table cannot be produced by
any declaration order. That needs a distinct `virtual_name` for one member,
plus a hand-written inline forwarder, as with `cIGZWin::SetSizeFromPoint`.

## Check

`vtables.py check` compiles two translation units per spec with MSVC x86:

- One call-site probe per slot, `p->Method(args)` with arguments of the spec's
  exact types. Overload resolution picks the method exactly as real code
  would, and the `CALL`/`JMP [reg+N]` in the listing gives the slot. A const
  method is called through a const pointer, so a missing `const` fails to
  compile.
- One `static_assert` per known return type, compiled with `/Zs` so a wrong
  return type cannot hide the slot results.

`/d1reportSingleClassLayout` also counts the header's slots against the spec's
extent, which catches declarations the game does not have.

## Naming the Windows functions

Once a spec passes `check`, its slots can be named in the Windows database.
This is a persistent edit, so it runs as a dry run first and only after the
plan has been reviewed. A slot is renamed when all of these hold:

- its spec status is `confirmed`, and it is not a pure slot
- its body belongs to this class. MSVC folds identical bodies (`/OPT:ICF`),
  so a one-line getter can serve hundreds of vtables (`00949ade` sits in 336).
  A shared body keeps its name and gets a plate comment naming the role
  instead. A body that only appears again in tables whose slot 0 reaches the
  same `QueryInterface` is inherited by derived classes, not folded, so it is
  still named (the `cSC4AutomataController` subclasses).
- its current name is a Ghidra default. Manual names are never overwritten,
  OOAnalyzer output included.

`vtables.py plan` writes only slots that pass the first test and only for a
spec whose `check` passes. It takes the parameter names from the header.
`ApplyInterfaceNames.java` does the rest of the gating in Ghidra.

Details that matter when applying:

- Functions go in a plain namespace named after the Mac implementer, the
  convention already used in the database (`cSC4PathFinder`). An interface
  with several implementers needs the Windows table's own class, set with a
  top-level `win_class` in the spec when it differs from the Mac primary.
- The vtable gets a `Class::vtable` label, or `Class::vtable_<iface>` for a
  secondary table at a non-zero adjust (MSVC's `Class::`vftable'{for
  `Iface'}`). An existing manual label is kept.
- Executable slot targets often have no Function yet and are created first.
- Prototypes use `__thiscall`. Parameter names must not look like `param_N`.
  Ghidra treats that pattern as a default name and renumbers the list, which
  silently drops parameters.
- Each renamed function gets a plate comment with the interface, slot, vtable
  and spec path, so the provenance survives in the database.

## Verified results

Checked on 2026-10-28.

Eighteen interfaces that the SDK could obtain but not use now have headers
that pass `check`. Their GZIIDs come from `GZServPtrs.h` or from the Mac
`QueryInterface`. No version drift turned up in any of them, and every
overload group followed the reverse rule.

| Interface | Slots | Windows vftable | Notes |
|---|---|---|---|
| `cIGZClipboardManager` | 21 | `00acfee0` | |
| `cIGZDrawSystem` | 11 | `00ac1668` | |
| `cIGZRegistry` | 8 | `00acfdac` | callback typedef |
| `cIGZWWWSystem` | 32 | `00acce48` | adjust 4, subtraction-chain QI |
| `cIGZWinIMEServer` | 18 | `00adbf38` | data getters return `void*` |
| `cIHitDebugInfo` | 11 | `00ad2b04` | subtraction-chain QI |
| `cIHitMan` | 47 | `00ad2968` | adjust 28 |
| `cIS3DMaterialManager` | 6 | `00abd3f4` | undefined QI `00800c00` |
| `cISC4AEMSEvents` | 10 | `00a9325c` | adjust 4 |
| `cISC4AnimationTickManager` | 17 | `00a86760` | pilot |
| `cISC4AudioScapeManager` | 18 | `00a94340` | |
| `cISC4AutomataController` | 35 | `00a95d90` | abstract, 2 pure slots |
| `cISC4COMSerializer` | 10 | `00aa268c` | adjust 8, saves plugin objects |
| `cISC4NetworkToolUI` | 17 | `00aa9140` | |
| `cISC4PathFinder` | 33 | `00ab2150` | undefined QI `006d5d20` |
| `cISC4PropRequester` | 7 | `00ab2600` | three implementers, adjust 8 |
| `cISC4ViewManager` | 6 | `00ab8150` | implemented by `cSC4WinMapView` |
| `cISCExemplarCache` | 12 | `00aad9d8` | adjust 28 |

Two service interfaces could not be done. `cIGZRealtimeDebugDisplay` is not
linked into the Windows game, and `cIGZTestService` has no Mac symbols.

- `cISC4AnimationTickManager`: 17 slots on both builds (3 inherited plus 14).
  Windows `QueryInterface` `00447e60`, vftable `00a86760`, extent from the
  table run. All 17 slots are confirmed and the generated header passes
  `check`. `FindTickableObject` decompiles to an 8-byte return on Mac, but
  that is EDX left over from a hashtable iterator. It returns `bool`.
- `cIGZString`, used as the fixture: the pipeline picks `cRZString`'s table
  `00a80810` out of four that share `0090e1d1`. The header on master passes
  `check` in all 28 slots.
  - Slots 9 and 10 hold the reference and pointer `IsEqual` overloads, in
    that order. Windows slot 9 is `MOV EAX,[ECX]; JMP [EAX+0x28]`, a wrapper
    that forwards to slot 10. On Mac the reference overload is the wrapper
    (`CALL [ECX+0x20]` into the pointer overload), so the shared source puts
    the reference overload at 9. That is also plain full reversal of the Mac
    order (pointer, reference, char), like every other overload group.
  - `FromChar` (both), `Copy` and `Resize` return `void`. Both builds leave
    only bookkeeping values in EAX, and upstream commit `e3daf13` made the
    same change. `scripts/check-cRZBaseString.ps1` predated both findings and
    now expects this layout too.
  - The two overloads only differ for a null pointer, since a pointer and a
    reference are passed the same way.
- Windows has one shared pure-virtual stub at `005d4a10`, in 5673 slots and
  66 tables made only of it. Constructors do not always keep the interface's
  own store. `cRZString` keeps only the `cIGZUnknown` one.

## Known limitations

- The drift path (Mac and Windows counts differ) has selftest coverage but has
  not met a real interface yet. It sends the whole table to review instead of
  anchoring the alignment.
- A primary table with no surviving pure-table store falls back to the Mac
  count and cannot see slots appended after the Mac build.
- Only direct implementers are found, plus subclasses of an abstract one. An
  interface reached only through an intermediate base class needs that class
  added to the search.
- The subtraction-chain search needs the previous ID in the chain to be one
  the Mac `QueryInterface` also tests. An ID added after the Mac build would
  hide the chain. Emulating the chain would remove that limit.
- The pure-table chain is often empty even where a constructor stores one
  (`cGZRegistry`, `cGZDrawSystem`). The table run gave the right extent there.
- Vtable calls are collected without data flow, so calls through other
  interfaces add noise. The scoring tolerates it, but it can inflate
  `call_edges` on otherwise unremarkable slots.
- The proposal's consumer call-site evidence (a Mac caller and its Windows
  twin calling the same slot) is not implemented. It would be the strongest
  signal for overload groups where neither overload wraps the other.
- A by-value class parameter has no known size, so its slot gets no `RET n`
  check.
