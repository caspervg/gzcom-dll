# RecoverItaniumRtti reference

Last updated: 2026-08-13

## Usage

1. Put [`RecoverItaniumRtti.java`](../scripts/ghidra/RecoverItaniumRtti.java) in
   a Ghidra script directory and refresh the Script Manager.
2. Open the stripped Mac binary to modify and make it the current CodeBrowser
   program.
3. Make the symbolized build of the same program available in the project.
   Prefer the same architecture and build when transferring method names.
4. Run `RecoverItaniumRtti` and choose that symbolized build at the
   **Symbolized source program** prompt.
5. Enter a Java regular expression at **Class filter**. Use `.*` for every
   class or a narrow expression such as `.*cGZWin.*` while testing.
6. Choose **No** at the apply prompt for the first run. Review recovered class
   names, RTTI kinds, bases, table counts, slot counts, and warnings.
7. Rerun and choose **Yes** only when the source and target are correct. The
   script adds labels, method names, and comments to the current target.
8. Review the Listing and Symbol Tree. Save the target program manually if the
   result is useful; the script does not save it.

Expected console ending:

```text
Recovered <classes> classes, <vtables> vtables. Preview only.
```

or, after apply:

```text
Recovered <classes> classes, <vtables> vtables, and <names> function names.
Save the program to keep them.
```

If recovery reports zero records, first check the selected target, source,
class filter, pointer width, target strings, and target pointers to candidate
names. The diagnostic counts printed before recovery distinguish these cases.

## Goal and scope

The script reconstructs Itanium C++ ABI RTTI and vtable relationships in a
mostly stripped Mac executable. A symbolized build of the same program supplies
candidate virtual method names.

It aims to recover:

- class and typeinfo identities;
- single- and multiple-inheritance metadata;
- primary and secondary vtables;
- links from vtable slots to executable functions;
- inferred virtual method roles;
- shared-function roles and compact provenance comments.

It does not recover complete C++ declarations, signatures, parameter types,
calling conventions, object layouts, or member offsets. It also does not prove
that two architecture-specific vtables have identical method order.

## ABI assumptions

The supported Mac binaries use the Itanium C++ ABI family. The relevant table
shape is:

```text
vtable header:
  [0] offset-to-top
  [1] typeinfo pointer
address point:
  [0] first virtual function
  [1] second virtual function
  ...
```

The script takes pointer width and endianness from each loaded Ghidra program,
so the same discovery code can operate on Mac x86-32, x86-64, and AArch64.
Function order, secondary tables, thunks, and destructor variants still belong
to the particular compiler and build.

Handled RTTI kinds:

- `__class_type_info`;
- `__si_class_type_info`;
- `__vmi_class_type_info`.

## Discovery algorithm

1. Collect source `__ZTV*` symbols by their encoded type name.
2. Collect target type-name candidates from:
   - target `__ZTS*` symbols;
   - defined strings resembling Itanium type encodings, including nested
     `N...` names and length-prefixed digit forms.
3. Raw-scan aligned target memory for pointer-sized references to candidate
   type-name strings. This does not depend on Ghidra having created xrefs.
4. Treat the pointer-sized field immediately before each name pointer as a
   possible typeinfo object start.
5. Validate the first typeinfo field against:
   - known ABI typeinfo-vtable addresses; or
   - a pointed symbol containing `class_type_info`.
6. Decode the base type for `__si_class_type_info`.
7. Decode base count, base pointers, and offset/flags for
   `__vmi_class_type_info` when readable.
8. Raw-scan target memory for pointers to each validated typeinfo object.
9. For each reference, interpret the preceding pointer-sized word as a signed
   offset-to-top and reject implausible values.
10. Treat the following address as a vtable address point.
11. Read executable pointers until a null, unreadable, or non-code value.
12. Recover primary and secondary tables independently.

All pointer and integer reads are guarded by memory-block and readability
checks. A malformed or unmapped candidate is skipped instead of terminating
the run. This specifically prevents an unreadable RTTI name pointer from
aborting the entire scan.

## Source-to-target method transfer

The source is used for names; the target determines addresses and table
contents.

For each recovered target table:

1. Find the source `__ZTV*` with the same encoded class name.
2. Split source primary and secondary tables using their Itanium headers.
3. Pair source and target tables by table index.
4. Transfer method names by slot only when the paired table slot counts are
   exactly equal.
5. Append `?` to every position-derived method name because equal size does not
   prove equal semantic order across architectures or compiler versions.
6. If no equal-size source table exists, assign stable slot fallbacks rather
   than inventing a method identity.

The exact-count rule avoids using header method counts. GZCOM interfaces often
contain inherited entries such as `QueryInterface`, `AddRef`, and `Release`
that are easy to omit in a manual count. It is conservative: a valid table with
one ABI-specific insertion will receive `vfuncNN` roles instead of shifted
method names.

## Names and comments

Representative labels:

```text
typeinfo_cGZFramework
vtable_cGZFramework
vtable_cGZFramework_secondary_1
cGZFramework__Init?
cGZFramework__vfunc01
```

The `?` suffix means the semantic name came from positional inference and must
be checked. `vfuncNN` means the class/table relationship is strong but no
source method name was safely mapped. The slot number is the real target-table
slot number.

Each address-point slot receives a target-specific slot label and short EOL
comment. The vtable plate comment records only the decisive provenance:

```text
RTTI vtable: cGZFramework
typeinfo=<address> kind=__si_class_type_info
table=primary offset-to-top=0 slots=<count>
source=<program> names=inferred
```

When several slots or classes point to one implementation, the script keeps
all roles:

- a neutral `shared_vfunc_<address>` primary name may be used;
- per-class/per-slot aliases remain attached;
- a repeatable implementation comment lists the roles.

This avoids letting the last processed class erase the identities of a folded
or inherited implementation.

## Safety decisions

- The current program is always the target; the selected program is only the
  symbol source.
- Target addresses and executable-memory tests come from the target.
- The default regex is `.*`; the regex is a scope control, not a requirement
  for recognizing GZCOM classes.
- Unreadable memory is skipped locally.
- Method names are positional only for equal-size table pairs and retain `?`.
- Unequal table pairs use `vfuncNN` fallbacks.
- Existing comments are kept when possible; script-generated text is compact
  and repeatable.
- Duplicate symbols receive table/slot disambiguation instead of aborting.
- The program is never saved automatically.

The script currently applies directly after the confirmation prompt; unlike
the Windows transfer script, it does not wrap all changes in its own explicit
transaction. Use preview first and rely on Ghidra review/undo before saving.

## Current status

Implemented:

- pointer-width-independent RTTI/name/pointer scanning;
- ABI typeinfo-vtable validation;
- guarded reads for unmapped or partially readable records;
- class, single-inheritance, and multiple-inheritance decoding;
- primary and secondary vtable discovery;
- symbolized-source lookup by encoded class name;
- equal-count positional naming with `?`;
- `Class__vfuncNN` fallback naming;
- shared implementation aliases and role comments;
- compact vtable and slot comments.

The initial zero-result failures were traced to relying too heavily on already
defined symbols/xrefs and to unsafe pointer reads. Raw aligned pointer scans,
defined-string candidates, ABI-vtable validation, and guarded reads now cover
those cases.

Validation state:

- the script compiles against Ghidra 12.1.2;
- only the existing `DemanglerUtil.demangle` deprecation warning remains;
- it has been exercised on SC4 Mac targets;
- x86-64 and AArch64 still deserve separate full-result audits, especially for
  multiple inheritance and secondary-table boundaries.

## Known limitations

- A target must retain recognizable type-name strings or `__ZTS*` symbols.
- A typeinfo record must retain a usable ABI typeinfo-vtable relationship.
- A table stops at the first unreadable, null, or non-code target; unusual
  entries can shorten it.
- Exact table size is required before method names transfer by order.
- Destructors, covariant-return thunks, adjustor thunks, and construction
  vtables may produce several entry points or tables for one semantic class.
- Shared machine code cannot have several primary function names; aliases and
  comments carry the additional roles.
- A `?` or `vfuncNN` name is a navigation aid, not evidence sufficient for a
  public header without review.
- No function signatures or class layouts are transferred.

## Potential next steps

1. Run and compare complete x86-32, x86-64, and AArch64 reports.
2. Audit secondary tables and nonzero offset-to-top values on known
   multiple-inheritance classes.
3. Replace equal-count-only mapping with stable-anchor interpolation if enough
   named target methods exist to justify it.
4. Recognize construction vtables and virtual-table-table groups explicitly.
5. Improve adjustor/covariant thunk role reporting.
6. Add an owned comment block if repeated native-script runs begin mixing with
   substantial manual comments.
7. Add a captured small RTTI fixture only if heuristic changes become frequent
   enough to justify maintaining it.
8. Replace the deprecated demangler call when Ghidra removes the current API.

## Decision summary

- Itanium RTTI supplies class and inheritance identity.
- Target memory supplies target vtables and target functions.
- The symbolized source supplies method-name hypotheses only.
- Equal table size permits positional inference, not certainty.
- `?` exposes inferred identities; `vfuncNN` exposes unknown identities.
- Shared implementations keep multiple aliases/roles.
- Preview, review, and manual save remain the operator-controlled boundary.
