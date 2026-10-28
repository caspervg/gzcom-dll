// Recover Itanium C++ RTTI/vtables and optionally transfer virtual method names
// from a symbolized build of the same program.
// @category C++
// @keybinding
// @menupath Tools.Recover Itanium RTTI
// @toolbar

import java.util.*;
import java.util.regex.Pattern;

import ghidra.app.script.GhidraScript;
import ghidra.app.util.demangler.DemangledObject;
import ghidra.app.util.demangler.DemanglerUtil;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.DuplicateNameException;

public class RecoverItaniumRtti extends GhidraScript {
    private record Table(Address header, Address addressPoint, long offsetToTop, List<Address> slots) {}
    private record Type(String encoded, String name, Address info, String kind,
                        List<String> bases, List<Table> tables) {}
    private record SlotRole(String symbol, String comment) {}

    @Override
    public void run() throws Exception {
        Program source = askProgram("Symbolized source program", true);
        if (source == null) return;
        try {
            Pattern filter = Pattern.compile(askString("Class filter", "Java regex (.* recovers all classes)", ".*"));
            boolean apply = askYesNo("Recover Itanium RTTI", "Apply recovered labels and virtual method names?\nChoose No for a preview.");
            Map<String, Symbol> sourceVtables = symbols(source, "__ZTV");
            Map<String, Address> targetNames = typeNames(currentProgram);
            Map<Long, String> typeInfoVtables = typeInfoVtables(currentProgram);
            println("Target: " + currentProgram.getName() + " (" +
                    (currentProgram.getDefaultPointerSize() * 8) + "-bit), Itanium name candidates=" + targetNames.size());
            println("Known ABI typeinfo vtables=" + typeInfoVtables.size());
            Map<Long, List<Address>> nameRefs = pointerReferences(currentProgram,
                    targetNames.values());
            println("Pointers to candidate names=" + nameRefs.values().stream().mapToInt(List::size).sum());
            List<Type> recovered = new ArrayList<>();
            for (var entry : targetNames.entrySet()) {
                monitor.checkCancelled();
                String className = demangleType(currentProgram, entry.getKey());
                if (!filter.matcher(className).matches()) continue;
                Type type = readType(currentProgram, entry.getValue(), className, entry.getKey(), nameRefs,
                        typeInfoVtables);
                if (type != null) recovered.add(type);
            }
            println("Filtered typeinfo records=" + recovered.size());
            Map<Long, List<Address>> infoRefs = pointerReferences(currentProgram,
                    recovered.stream().map(Type::info).toList());
            List<Type> types = new ArrayList<>();
            for (Type bare : recovered) {
                Type type = new Type(bare.encoded(), bare.name(), bare.info(), bare.kind(), bare.bases(),
                        findTables(currentProgram, bare.info(), 4096, infoRefs));
                if (type.tables().isEmpty()) continue;
                types.add(type);
            }
            int classes = 0, tables = 0, names = 0;
            Map<Address, LinkedHashSet<SlotRole>> claimedNames = new HashMap<>();

            for (Type type : types) {
                monitor.checkCancelled();
                List<Table> sourceTables = readSourceTables(source, sourceVtables.get(type.encoded()));
                println("\n" + type.name() + "  typeinfo=" + type.info() + "  " + type.kind());
                for (String base : type.bases()) println("  base " + base);
                if (sourceTables.isEmpty()) println("  WARNING: no symbolized source vtable found");

                if (apply) {
                    label(type.info(), "typeinfo_" + safe(type.name()));
                    for (int i = 0; i < type.tables().size(); i++) {
                        label(type.tables().get(i).header(), "vtable_" + safe(type.name()) + (i == 0 ? "" : "_secondary_" + i));
                        document(type, type.tables().get(i), i, source,
                                matchingTable(sourceTables, i, type.tables().get(i)) != null);
                    }
                }

                for (int ti = 0; ti < type.tables().size(); ti++) {
                    Table target = type.tables().get(ti);
                    Table old = matchingTable(sourceTables, ti, target);
                    if (old == null && ti < sourceTables.size())
                        println("  WARNING: table " + ti + " slot count differs (target=" + target.slots().size() +
                                ", source=" + sourceTables.get(ti).slots().size() + "); using vfuncNN names");
                    int count = target.slots().size();
                    println("  table " + ti + " address-point=" + target.addressPoint() +
                            " offset-to-top=" + target.offsetToTop() + " slots=" + target.slots().size());
                    for (int slot = 0; slot < count; slot++) {
                        Function src = old == null ? null : source.getFunctionManager().getFunctionAt(old.slots().get(slot));
                        Function dst = currentProgram.getFunctionManager().getFunctionAt(target.slots().get(slot));
                        String method = src == null || isDefault(src.getSymbol()) ? null : src.getName();
                        println("    [" + slot + "] " + target.slots().get(slot) +
                                (method == null ? "  <unknown>" : "  " + method + "?"));
                        if (apply) {
                            String unique = method == null
                                    ? safe(type.name()) + "__" + (ti == 0 ? "" : "secondary" + ti + "_") +
                                            String.format("vfunc%02d", slot)
                                    : safe(type.name()) + "__" + safe(method) + "?";
                            String role = type.name() + " " + (ti == 0 ? "primary" : "secondary " + ti) +
                                    String.format("[%02d] ", slot) +
                                    (method == null ? String.format("vfunc%02d", slot) : method + "?");
                            Address slotAddress = target.addressPoint().add((long)slot * currentProgram.getDefaultPointerSize());
                            slotLabel(slotAddress, safe(type.name()) + "__" +
                                    (ti == 0 ? "" : "s" + ti + "_") + String.format("vslot%02d", slot));
                            slotComment(slotAddress, method == null ? "unknown" : method + "?");
                            if (dst != null) {
                                nameSlot(dst, new SlotRole(unique, role), claimedNames, ti, slot);
                                names++;
                            }
                        }
                    }
                }
                classes++;
                tables += type.tables().size();
            }
            println("\nRecovered " + classes + " classes, " + tables + " vtables" +
                    (apply ? ", and " + names + " function names. Save the program to keep them." : ". Preview only."));
        } finally {
            source.release(this);
        }
    }

    private Table matchingTable(List<Table> source, int index, Table target) {
        if (index >= source.size()) return null;
        Table candidate = source.get(index);
        return candidate.slots().size() == target.slots().size() ? candidate : null;
    }

    private void rename(Function function, String name, int table, int slot) throws Exception {
        try {
            function.setName(name, SourceType.USER_DEFINED);
        } catch (DuplicateNameException e) {
            function.setName(name + "__table" + table + "_slot" + slot, SourceType.USER_DEFINED);
        }
    }

    private void nameSlot(Function function, SlotRole desired, Map<Address, LinkedHashSet<SlotRole>> claimed,
                          int table, int slot) throws Exception {
        Address address = function.getEntryPoint();
        LinkedHashSet<SlotRole> roles = claimed.computeIfAbsent(address, ignored -> new LinkedHashSet<>());
        boolean first = roles.isEmpty();
        SlotRole previous = first ? null : roles.iterator().next();
        if (!roles.add(desired)) return;
        if (first) {
            if (function.getName().startsWith("shared_vfunc_")) alias(address, desired.symbol(), table, slot);
            else if (isReplaceable(function.getSymbol())) rename(function, desired.symbol(), table, slot);
            return;
        }

        String primary = function.getName();
        if (isGeneratedName(primary) && !primary.startsWith("shared_vfunc_"))
            rename(function, "shared_vfunc_" + safe(address.toString()), table, slot);
        alias(address, previous.symbol(), table, slot);
        alias(address, desired.symbol(), table, slot);
        StringBuilder comment = new StringBuilder();
        for (SlotRole role : roles) {
            if (!comment.isEmpty()) comment.append('\n');
            comment.append(role.comment());
        }
        sharedComment(address, comment.toString());
    }

    private void alias(Address address, String name, int table, int slot) throws Exception {
        for (Symbol symbol : currentProgram.getSymbolTable().getSymbols(address))
            if (symbol.getName().equals(name)) return;
        currentProgram.getSymbolTable().createLabel(address, name, SourceType.USER_DEFINED);
    }

    private Map<String, Symbol> symbols(Program program, String prefix) {
        Map<String, Symbol> out = new TreeMap<>();
        SymbolIterator it = program.getSymbolTable().getAllSymbols(true);
        while (it.hasNext()) {
            Symbol s = it.next();
            if (s.getName().startsWith(prefix)) out.putIfAbsent(s.getName().substring(prefix.length()), s);
        }
        return out;
    }

    private Map<String, Address> typeNames(Program program) {
        Map<String, Address> out = new TreeMap<>();
        for (var entry : symbols(program, "__ZTS").entrySet())
            out.put(entry.getKey(), entry.getValue().getAddress());
        DataIterator it = program.getListing().getDefinedData(true);
        while (it.hasNext()) {
            Data data = it.next();
            Object value = data.getValue();
            if (value instanceof String text && looksLikeTypeName(text))
                out.putIfAbsent(text, data.getAddress());
        }
        return out;
    }

    private boolean looksLikeTypeName(String text) {
        return text.length() > 1 && text.length() < 1024 && text.indexOf(' ') < 0 &&
                (Character.isDigit(text.charAt(0)) || text.charAt(0) == 'N');
    }

    private Type readType(Program program, Address nameAddress, String className, String encoded,
                          Map<Long, List<Address>> refsByValue, Map<Long, String> typeInfoVtables) throws Exception {
        int ptr = program.getDefaultPointerSize();
        Address info = null;
        String kind = "";
        for (Address ref : refsByValue.getOrDefault(key(program, nameAddress), List.of())) {
            Address candidate = ref.subtract(ptr);
            if (!readable(program, candidate, 2 * ptr)) continue;
            Address vptr = pointer(program, candidate);
            String candidateKind = typeInfoVtables.get(key(program, vptr));
            if (candidateKind == null) candidateKind = pointedSymbol(program, candidate);
            if (candidateKind.contains("class_type_info")) {
                info = candidate;
                kind = candidateKind;
                break;
            }
        }
        if (info == null) return null;
        List<String> bases = new ArrayList<>();
        if (kind.contains("__si_class_type_info")) {
            Address field = info.add(2L * ptr);
            if (readable(program, field, ptr)) bases.add(typeName(program, pointer(program, field)));
            else bases.add("<unreadable base metadata>");
        } else if (kind.contains("__vmi_class_type_info")) {
            Address countField = info.add(2L * ptr + 4);
            if (!readable(program, countField, 4)) return null;
            long count = Integer.toUnsignedLong(program.getMemory().getInt(countField));
            if (count > 1024) return null;
            Address p = info.add(2L * ptr + 8);
            for (int i = 0; i < count; i++, p = p.add(2L * ptr)) {
                if (!readable(program, p, 2 * ptr)) {
                    bases.add("<unreadable base metadata at " + p + ">");
                    break;
                }
                Address base = pointer(program, p);
                long flags = signedPointer(program, p.add(ptr));
                bases.add(typeName(program, base) + " offset=" + (flags >> 8) +
                        ((flags & 1) != 0 ? " virtual" : "") + ((flags & 2) != 0 ? " public" : " non-public"));
            }
        }
        return new Type(encoded, className, info, shortKind(kind), bases, List.of());
    }

    private Map<Long, String> typeInfoVtables(Program program) {
        Map<Long, String> out = new HashMap<>();
        SymbolIterator it = program.getSymbolTable().getAllSymbols(true);
        while (it.hasNext()) {
            Symbol symbol = it.next();
            String name = symbol.getName(true);
            if (name.contains("__class_type_infoE") || name.contains("__si_class_type_infoE") ||
                    name.contains("__vmi_class_type_infoE"))
                out.put(key(program, symbol.getAddress()), name);
        }
        return out;
    }

    private List<Table> findTables(Program program, Address info, int maxSlots,
                                   Map<Long, List<Address>> refsByValue) throws Exception {
        int ptr = program.getDefaultPointerSize();
        List<Table> out = new ArrayList<>();
        for (Address typeSlot : refsByValue.getOrDefault(key(program, info), List.of())) {
            if (!program.getMemory().contains(typeSlot) || typeSlot.getOffset() % ptr != 0) continue;
            Address header = typeSlot.subtract(ptr);
            if (!readable(program, header, 2 * ptr)) continue;
            long top = signedPointer(program, header);
            if (Math.abs(top) > 0x1000000L) continue;
            Address point = typeSlot.add(ptr);
            List<Address> slots = readSlots(program, point, maxSlots);
            if (!slots.isEmpty()) out.add(new Table(header, point, top, slots));
        }
        out.sort(Comparator.comparing(Table::header));
        return out;
    }

    private Map<Long, List<Address>> pointerReferences(Program program, Collection<Address> targets)
            throws Exception {
        Set<Long> wanted = new HashSet<>();
        for (Address target : targets) wanted.add(key(program, target));
        Map<Long, List<Address>> out = new HashMap<>();
        int size = program.getDefaultPointerSize();
        for (MemoryBlock block : program.getMemory().getBlocks()) {
            if (!block.isInitialized()) continue;
            long start = block.getStart().getOffset();
            long aligned = start + Math.floorMod(-start, size);
            for (Address p = block.getStart().getNewAddress(aligned);
                 p.add(size - 1L).compareTo(block.getEnd()) <= 0; p = p.add(size)) {
                monitor.checkCancelled();
                long value = size == 8 ? program.getMemory().getLong(p)
                        : Integer.toUnsignedLong(program.getMemory().getInt(p));
                if (wanted.contains(value)) out.computeIfAbsent(value, ignored -> new ArrayList<>()).add(p);
            }
        }
        return out;
    }

    private long key(Program program, Address address) {
        return program.getDefaultPointerSize() == 8 ? address.getOffset() : address.getOffset() & 0xffffffffL;
    }

    private List<Table> readSourceTables(Program program, Symbol vtable) throws Exception {
        if (vtable == null) return List.of();
        int ptr = program.getDefaultPointerSize();
        Address start = vtable.getAddress();
        Symbol next = nextVtable(program, start);
        Address end = next == null ? start.add(0x10000) : next.getAddress();
        Address info = pointer(program, start.add(ptr));
        List<Table> out = new ArrayList<>();
        for (Address p = start; p.add(ptr).compareTo(end) < 0; p = p.add(ptr)) {
            monitor.checkCancelled();
            if (!readable(program, p, 2 * ptr)) break;
            if (pointer(program, p.add(ptr)).equals(info)) {
                Address point = p.add(2L * ptr);
                Address limit = nextHeader(program, point, end, info);
                out.add(new Table(p, point, signedPointer(program, p), readSlots(program, point,
                        (int)((limit.subtract(point)) / ptr))));
                p = limit.subtract(ptr);
            }
        }
        return out;
    }

    private Address nextHeader(Program program, Address start, Address end, Address info) throws Exception {
        int ptr = program.getDefaultPointerSize();
        for (Address p = start; p.add(ptr).compareTo(end) < 0; p = p.add(ptr))
            if (!readable(program, p, 2 * ptr)) return p;
            else if (pointer(program, p.add(ptr)).equals(info)) return p;
        return end;
    }

    private Symbol nextVtable(Program program, Address after) {
        Symbol best = null;
        SymbolIterator it = program.getSymbolTable().getAllSymbols(true);
        while (it.hasNext()) {
            Symbol s = it.next();
            if (s.getName().startsWith("__ZTV") && s.getAddress().compareTo(after) > 0 &&
                    (best == null || s.getAddress().compareTo(best.getAddress()) < 0)) best = s;
        }
        return best;
    }

    private List<Address> readSlots(Program program, Address start, int limit) throws Exception {
        List<Address> out = new ArrayList<>();
        Memory memory = program.getMemory();
        for (int i = 0; i < limit; i++) {
            Address slot = start.add((long)i * program.getDefaultPointerSize());
            if (!readable(program, slot, program.getDefaultPointerSize())) break;
            Address target = pointer(program, slot);
            if (target.getOffset() == 0 || !isCode(program, target)) break;
            out.add(target);
        }
        return out;
    }

    private boolean isCode(Program program, Address address) {
        return program.getFunctionManager().getFunctionAt(address) != null ||
                (program.getMemory().getBlock(address) != null && program.getMemory().getBlock(address).isExecute());
    }

    private String typeName(Program program, Address info) throws Exception {
        Address nameField = info.add(program.getDefaultPointerSize());
        if (info.getOffset() == 0 || !readable(program, nameField, program.getDefaultPointerSize()))
            return info + " (unreadable RTTI)";
        Address name = pointer(program, nameField);
        Symbol s = program.getSymbolTable().getPrimarySymbol(name);
        if (s != null && s.getName().startsWith("__ZTS"))
            return demangleType(program, s.getName().substring(5));
        return info.toString();
    }

    private String pointedSymbol(Program program, Address at) throws Exception {
        Address target = pointer(program, at);
        String fallback = target.toString();
        for (Symbol symbol : program.getSymbolTable().getSymbols(target)) {
            String name = symbol.getName(true);
            if (name.contains("class_type_info")) return name;
            fallback = name;
        }
        return fallback;
    }

    private Address pointer(Program program, Address at) throws Exception {
        if (!readable(program, at, program.getDefaultPointerSize()))
            throw new MemoryAccessException("Unreadable pointer at " + at);
        long value = program.getDefaultPointerSize() == 8
                ? program.getMemory().getLong(at)
                : Integer.toUnsignedLong(program.getMemory().getInt(at));
        return program.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long signedPointer(Program program, Address at) throws Exception {
        if (!readable(program, at, program.getDefaultPointerSize()))
            throw new MemoryAccessException("Unreadable pointer at " + at);
        return program.getDefaultPointerSize() == 8
                ? program.getMemory().getLong(at)
                : program.getMemory().getInt(at);
    }

    private boolean readable(Program program, Address start, int length) {
        MemoryBlock block = program.getMemory().getBlock(start);
        if (block == null || !block.isInitialized()) return false;
        try {
            return block.contains(start.add(length - 1L));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String demangleType(Program program, String encoded) {
        int digits = 0;
        while (digits < encoded.length() && Character.isDigit(encoded.charAt(digits))) digits++;
        if (digits > 0) {
            try {
                int length = Integer.parseInt(encoded.substring(0, digits));
                if (digits + length == encoded.length()) return encoded.substring(digits);
            } catch (NumberFormatException ignored) {
                // Fall through to Ghidra's demangler.
            }
        }
        try {
            DemangledObject d = DemanglerUtil.demangle(program, "_ZTI" + encoded);
            String text = d == null ? encoded : d.getSignature();
            return text.replaceFirst("(?i)^.*?typeinfo(?: name)? for\\s+", "")
                    .replaceFirst("(?i)[\\s:_`']*typeinfo[\\s:_`']*$", "");
        } catch (Exception ignored) {
            return encoded;
        }
    }

    private void document(Type type, Table table, int index, Program source, boolean namesInferred) {
        StringBuilder text = new StringBuilder("Itanium vtable: ").append(type.name()).append(" [")
                .append(index == 0 ? "primary" : "secondary " + index).append("]\n")
                .append("RTTI: ").append(type.info()).append(" (").append(type.kind()).append(")\n")
                .append("Address point: ").append(table.addressPoint()).append(" | top: ")
                .append(table.offsetToTop()).append(" | slots: ").append(table.slots().size()).append("\n");
        if (!type.bases().isEmpty()) text.append("Bases: ").append(String.join(", ", type.bases())).append("\n");
        text.append("Names: ").append(namesInferred ? source.getName() + " slot match | ? = inferred"
                : "unavailable | vfuncNN = unknown");
        plateComment(table.header(), text.toString());
        rttiComment(table.header().add(currentProgram.getDefaultPointerSize()), "RTTI -> " + type.info());
    }

    private void plateComment(Address address, String text) {
        String old = currentProgram.getListing().getComment(CommentType.PLATE, address);
        if (old == null || old.startsWith("Recovered Itanium C++ ABI ") || old.startsWith("Itanium vtable: "))
            currentProgram.getListing().setComment(address, CommentType.PLATE, text);
    }

    private void slotComment(Address address, String text) {
        String old = currentProgram.getListing().getComment(CommentType.EOL, address);
        if (old == null || old.equals("unknown") || old.endsWith("?") ||
                old.startsWith("Vtable address point; virtual slot 0") || old.matches("^\\[\\d+] .*"))
            currentProgram.getListing().setComment(address, CommentType.EOL, text);
    }

    private void slotLabel(Address address, String name) throws Exception {
        Symbol primary = currentProgram.getSymbolTable().getPrimarySymbol(address);
        if (primary != null && primary.getSource() != SourceType.DEFAULT && !primary.getName().matches(".+__(?:s\\d+_)?vslot\\d+"))
            return;
        for (Symbol symbol : currentProgram.getSymbolTable().getSymbols(address)) {
            if (symbol.getName().equals(name)) {
                symbol.setPrimary();
                return;
            }
        }
        Symbol label = currentProgram.getSymbolTable().createLabel(address, name, SourceType.USER_DEFINED);
        label.setPrimary();
    }

    private void sharedComment(Address address, String text) {
        String old = currentProgram.getListing().getComment(CommentType.REPEATABLE, address);
        if (old == null || old.startsWith("Shared vtable implementation.") || isRoleComment(old))
            currentProgram.getListing().setComment(address, CommentType.REPEATABLE, text);
    }

    private void rttiComment(Address address, String text) {
        String old = currentProgram.getListing().getComment(CommentType.EOL, address);
        if (old == null || old.startsWith("Itanium RTTI pointer -> ") || old.startsWith("RTTI -> "))
            currentProgram.getListing().setComment(address, CommentType.EOL, text);
    }

    private boolean isRoleComment(String text) {
        for (String line : text.split("\\R"))
            if (!line.matches(".+ (?:primary|secondary \\d+)\\[\\d+] .+")) return false;
        return true;
    }

    private String shortKind(String kind) {
        int i = kind.lastIndexOf("__");
        return i < 0 ? kind : kind.substring(i);
    }

    private boolean isDefault(Symbol symbol) {
        return symbol == null || symbol.getSource() == SourceType.DEFAULT || symbol.getName().startsWith("FUN_") ||
                symbol.getName().startsWith("thunk_FUN_");
    }

    private boolean isReplaceable(Symbol symbol) {
        return isDefault(symbol) || symbol.getName().contains("typeinfo_for_") ||
                symbol.getName().contains("_typeinfo__") || isGeneratedName(symbol.getName());
    }

    private boolean isGeneratedName(String name) {
        return name.startsWith("shared_vfunc_") ||
                name.matches(".+__(?:secondary\\d+_)?vfunc\\d+(?:__table\\d+_slot\\d+)?") ||
                (name.contains("__") && name.endsWith("?"));
    }

    private void label(Address address, String name) throws Exception {
        Symbol old = currentProgram.getSymbolTable().getPrimarySymbol(address);
        if (old == null || old.getSource() == SourceType.DEFAULT)
            currentProgram.getSymbolTable().createLabel(address, name, SourceType.USER_DEFINED);
        else if (old.getName().contains("typeinfo_for_") || old.getName().endsWith("_typeinfo") ||
                old.getName().contains("_typeinfo_secondary_"))
            old.setName(name, SourceType.USER_DEFINED);
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_$]", "_").replaceAll("_+", "_");
    }
}
