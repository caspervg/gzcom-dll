// Name the Windows implementations of verified interface slots.
//
//   dry   <plan.tsv> ...   report what would change (default), only shared and skipped slots in detail
//   list  <plan.tsv> ...   the same with every slot listed
//   apply <plan.tsv> ...   make the changes
//
// Plans come from `python scripts/vtables.py plan <iface>`, which only emits
// slots whose spec is confirmed and passes `check`. Per slot this script:
//   - comments instead of renaming when the body is shared with another
//     vtable (MSVC folds identical functions), since one name would be wrong
//     for every other role
//   - never touches a function that already has a non-default name
//   - creates the Function when the slot target has none yet
//   - sets a __thiscall prototype and a provenance plate comment
// See docs/RecoverInterfaceVtables.md.
//@category SC4

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;

import java.nio.file.*;
import java.util.*;

public class ApplyInterfaceNames extends GhidraScript {
    private boolean apply;
    private boolean verbose;
    private Map<Long, List<Long>> pointerSites;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) {
            println("usage: dry|apply <plan.tsv> ...");
            return;
        }
        apply = args[0].equals("apply");
        verbose = !args[0].equals("dry");
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < args.length; i++)
            for (String line : Files.readAllLines(Paths.get(args[i])))
                if (!line.isBlank() && !line.startsWith("#")) rows.add(line.split("\t", -1));
        Set<Long> wanted = new HashSet<>();
        for (String[] r : rows) wanted.add(Long.decode(r[2]));
        pointerSites = dataPointerSites(wanted);

        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Map<String, Integer>> perIface = new TreeMap<>();
        Set<Long> labelled = new HashSet<>();
        for (String[] r : rows) {
            String action = slot(r);
            totals.merge(action.split(" ")[0], 1, Integer::sum);
            perIface.computeIfAbsent(r[7], k -> new TreeMap<>()).merge(action, 1, Integer::sum);
            long vftable = Long.decode(r[0]);
            // A table at a non-zero this-adjust is a secondary subobject table,
            // MSVC's Class::`vftable'{for `Iface'}, not the class's own vtable.
            boolean secondary = r.length > 9 && !r[9].equals("0");
            if (labelled.add(vftable)) label(vftable, r[3], secondary ? "vtable_" + r[7] : "vtable");
        }
        perIface.forEach((iface, counts) -> println(String.format("%-28s %s", iface, counts)));
        println((apply ? "applied " : "dry run ") + totals);
    }

    /** Columns: vftable, slot, impl, class, method, returns, params, iface, spec. */
    private String slot(String[] r) throws Exception {
        long vftable = Long.decode(r[0]);
        int slot = Integer.parseInt(r[1]);
        Address impl = toAddr(Long.decode(r[2]));
        String cls = r[3], method = r[4], iface = r[7], spec = r[8];
        String role = String.format("%s::%s (%s slot %d, vftable %08x)", cls, method, iface, slot, vftable);

        // The slot itself is one pointer. Another pointer is either a derived
        // class inheriting the method (its table at the same slot index starts
        // with the same QueryInterface), which keeps this name correct, or an
        // unrelated vtable sharing a folded body, which gets a comment instead.
        List<Long> others = new ArrayList<>(pointerSites.getOrDefault(impl.getOffset(), List.of()));
        others.remove(Long.valueOf(vftable + 4L * slot));
        long qi = entryOf(vftable);
        boolean inherited = !others.isEmpty() && others.stream().allMatch(p -> entryOf(p - 4L * slot) == qi);
        if (!others.isEmpty() && !inherited) {
            if (verbose) println(String.format("%-7s %s %s, body shared by %d vtables", "shared", impl, role,
                    others.size() + 1));
            if (apply) appendPlate(impl, "Shared body (MSVC folded identical functions). Role: " + role + ". Source: " + spec);
            return "shared";
        }
        Function f = getFunctionAt(impl);
        String target = cls + "::" + method;
        String action = f == null ? "create" : f.getSymbol().getSource() == SourceType.DEFAULT ? "rename"
                : f.getName(true).equals(target) ? "update" : "skip";
        if (verbose || action.equals("skip")) println(String.format("%-7s %s %s%s", action, impl, role,
                action.equals("skip") ? ", already named " + f.getName(true) : ""));
        if (!apply || action.equals("skip")) return action;

        if (f == null) f = createFunction(impl, null);
        if (f == null) {
            println("        could not create a function here");
            return "failed";
        }
        f.setParentNamespace(namespace(cls));
        f.setName(method, SourceType.USER_DEFINED);
        f.setCallingConvention("__thiscall");
        DataType ret = type(r[5]);
        f.setReturnType(r[5].trim().equals("void") ? ret : byValue(ret, 4), SourceType.USER_DEFINED);
        List<Variable> params = new ArrayList<>();
        String[] ps = r[6].isEmpty() ? new String[0] : r[6].split("\\|");
        for (int i = 0; i < ps.length; i++) {
            String p = ps[i].trim();
            int size = 4;
            int at = p.lastIndexOf('@'); // size of a by-value type Ghidra may not know
            if (at > 0) {
                size = Integer.parseInt(p.substring(at + 1));
                p = p.substring(0, at).trim();
            }
            int cut = p.lastIndexOf(' ');
            String type = cut < 0 ? p : p.substring(0, cut);
            String name = cut < 0 ? "" : p.substring(cut + 1);
            // param_N is Ghidra's default-name pattern. Ghidra renumbers it and
            // can silently drop parameters, so never pass it through.
            if (name.isEmpty() || name.matches("param_\\d+") || name.contains("*") || name.contains("&")) {
                type = p;
                name = "arg" + (i + 1);
            }
            params.add(new ParameterImpl(name, byValue(type(type), size), currentProgram));
        }
        f.replaceParameters(params, Function.FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true, SourceType.USER_DEFINED);
        appendPlate(impl, role + ". Verified by scripts/vtables.py check. Source: " + spec);
        if (verbose) println("        " + f.getPrototypeString(false, false));
        return action;
    }

    private void label(long vftable, String cls, String name) throws Exception {
        Address a = toAddr(vftable);
        for (Symbol s : currentProgram.getSymbolTable().getSymbols(a))
            if (s.getSource() != SourceType.DEFAULT) {
                if (verbose) println("label   " + a + " keeps " + s.getName(true));
                return;
            }
        if (verbose) println("label   " + a + " " + cls + "::" + name);
        if (apply) currentProgram.getSymbolTable().createLabel(a, name, namespace(cls), SourceType.USER_DEFINED);
    }

    /** The database uses plain namespaces for classes (cSC4PathFinder), so match that. */
    private Namespace namespace(String cls) throws Exception {
        SymbolTable st = currentProgram.getSymbolTable();
        Namespace ns = st.getNamespace(cls, currentProgram.getGlobalNamespace());
        return ns != null ? ns : st.createNameSpace(currentProgram.getGlobalNamespace(), cls, SourceType.USER_DEFINED);
    }

    private void appendPlate(Address a, String text) {
        Listing listing = currentProgram.getListing();
        String old = listing.getComment(CodeUnit.PLATE_COMMENT, a);
        if (old != null && old.contains(text)) return;
        listing.setComment(a, CodeUnit.PLATE_COMMENT, old == null ? text : old + "\n" + text);
    }

    /**
     * Slot 0 of the table at `table`, followed through a plain JMP thunk or an
     * adjustor thunk (SUB/ADD ECX,K then JMP), or -1 when unreadable. A class's
     * primary and secondary tables then agree on one QueryInterface.
     */
    private long entryOf(long table) {
        try {
            long v = currentProgram.getMemory().getInt(toAddr(table)) & 0xffffffffL;
            Instruction i = currentProgram.getListing().getInstructionAt(toAddr(v));
            if (i != null && (i.getMnemonicString().equals("SUB") || i.getMnemonicString().equals("ADD"))
                    && i.getDefaultOperandRepresentation(0).equals("ECX"))
                i = i.getNext();
            if (i != null && i.getMnemonicString().equals("JMP") && i.getFlows().length == 1)
                return i.getFlows()[0].getOffset();
            return v;
        } catch (Exception e) {
            return -1;
        }
    }

    private Map<Long, List<Long>> dataPointerSites(Set<Long> wanted) throws Exception {
        Map<Long, List<Long>> sites = new HashMap<>();
        Memory mem = currentProgram.getMemory();
        for (MemoryBlock b : mem.getBlocks()) {
            if (b.isExecute() || !b.isInitialized()) continue;
            byte[] bytes = new byte[(int) b.getSize()];
            b.getBytes(b.getStart(), bytes);
            for (int i = 0; i + 4 <= bytes.length; i += 4) {
                long v = (bytes[i] & 0xffL) | (bytes[i + 1] & 0xffL) << 8 | (bytes[i + 2] & 0xffL) << 16 | (bytes[i + 3] & 0xffL) << 24;
                if (wanted.contains(v)) sites.computeIfAbsent(v, k -> new ArrayList<>()).add(b.getStart().getOffset() + i);
            }
        }
        return sites;
    }

    /** An unknown type (void from type()) passed by value becomes undefinedN of its stack size. */
    private DataType byValue(DataType t, int size) {
        if (!(t instanceof VoidDataType)) return t;
        return size <= 8 ? Undefined.getUndefinedDataType(size)
                : new ArrayDataType(Undefined1DataType.dataType, size, 1);
    }

    private DataType type(String t) {
        t = t.trim().replaceAll("\\bconst\\b", "").replaceAll("\\s+", " ").trim();
        if (t.endsWith("*") || t.endsWith("&")) {
            return new PointerDataType(type(t.substring(0, t.length() - 1)), currentProgram.getDataTypeManager());
        }
        switch (t) {
            case "bool": return BooleanDataType.dataType;
            case "void": return VoidDataType.dataType;
            case "char": return CharDataType.dataType;
            case "float": return FloatDataType.dataType;
            case "double": return DoubleDataType.dataType;
            case "uint32_t": return UnsignedIntegerDataType.dataType;
            case "int32_t": return IntegerDataType.dataType;
            case "uint16_t": return UnsignedShortDataType.dataType;
            case "int16_t": return ShortDataType.dataType;
            case "uint8_t": return ByteDataType.dataType;
            case "int8_t": return SignedByteDataType.dataType;
            case "uint64_t": return UnsignedLongLongDataType.dataType;
            case "int64_t": return LongLongDataType.dataType;
        }
        List<DataType> found = new ArrayList<>();
        currentProgram.getDataTypeManager().findDataTypes(t, found);
        // Unknown class types become void so a pointer to them is still a pointer.
        return found.isEmpty() ? VoidDataType.dataType : found.get(0);
    }
}
