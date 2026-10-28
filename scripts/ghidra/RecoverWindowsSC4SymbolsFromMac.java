// Preview cross-binary SC4 class, vtable, method, and data symbol matches.
// An optional end-of-run transaction applies conservative Windows annotations.
// @category C++
// @keybinding
// @menupath Tools.Preview Windows SC4 Symbols From Mac
// @toolbar

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

import generic.jar.ResourceFile;
import generic.lsh.vector.*;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.signature.SignatureResult;
import ghidra.app.script.GhidraScript;
import ghidra.app.services.ProgramManager;
import ghidra.app.util.demangler.DemangledObject;
import ghidra.app.util.demangler.DemanglerUtil;
import ghidra.features.bsim.query.GenSignatures;
import ghidra.framework.model.DomainFile;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.xml.SpecXmlUtils;
import ghidra.xml.NonThreadedXmlPullParserImpl;
import ghidra.xml.XmlPullParser;

public class RecoverWindowsSC4SymbolsFromMac extends GhidraScript {
    private static final int MAX_VTABLE_SLOTS = 256;
    private static final int MAX_ORDER_LINES = 16;
    private static final int MAX_CLASS_REPORTS = 200;
    private static final int MAX_PROBABLE_REPORTS = 40;
    private static final int MAX_STRING_REPORTS = 40;
    private static final int MAX_NAME_ANCHORS = 16;
    private static final int MAX_SHARED_ROLE_REPORTS = 40;
    private static final int MAX_BSIM_VECTORS = 10000;
    private static final int MAX_BSIM_ANCHORS = 24;
    private static final double MIN_BSIM_SIMILARITY = 0.72;
    private static final double MIN_BSIM_SIGNIFICANCE = 10.0;
    private static final double MIN_BSIM_MARGIN = 0.04;

    // MSVC lowers a QueryInterface id chain to a binary search and folds an
    // adjacent DEC/INC into the following constant, so the literal in the
    // instruction stream can differ from the real id by a small delta. See
    // cSC4OccupantManager::QueryInterface at 0049d910, where the immediate
    // 0x452294a9 stands for GZIID 0x452294aa after a preceding DEC EAX.
    // GZIIDs are sparse 32-bit values, so this window collides with nothing.
    private static final long GZID_FOLD_TOLERANCE = 2;

    // Ids appear in compares and in the arithmetic MSVC substitutes for them.
    // PUSH/MOV are deliberately excluded: those are call sites passing an id to
    // QueryInterface, not implementations testing one. Including them would
    // make every caller look like a QueryInterface candidate.
    private static final Set<String> ID_BEARING_MNEMONICS =
            Set.of("CMP", "SUB", "ADD", "XOR");

    private static final int MAX_ADJUSTOR_HOPS = 8;

    // How many Mac vtable slots must agree with a declared interface order
    // before it is trusted as this class's primary base. A concrete class
    // implements several interfaces and only its first base shares the primary
    // vtable, so a couple of incidental matches must not be enough.
    private static final int MIN_HEADER_AGREEMENT = 3;

    // Stamped into every run log so a log can be tied to the build that made
    // it. Bump this whenever heuristics or thresholds change, otherwise logs
    // from different behaviour become impossible to compare.
    private static final String SCRIPT_VERSION = "v5 (2026-08-14: GZIID allowlist, " +
            "header order via GZIID->interface bridge + operator parsing + table scoring, " +
            "adjustor thunks, mutual-best QI, program-role guard)";

    private record Slot(Address target, Function function) {}
    private record Vtable(Address addressPoint, List<Slot> slots, boolean truncated,
                          String extentStop) {}
    private record MacClass(String name, Function queryInterface, Set<Long> ids, Vtable primary) {}
    private record WindowsCandidate(Function function, Set<Long> ids) {}
    private record ClassMatch(MacClass mac, WindowsCandidate windows, double score,
                              int shared, int missing, int extra, String confidence,
                              double margin) {}
    private record NamedString(Address address, String name, String text) {}
    private record NameAnchor(int macSlot, int windowsSlot, String method) {}
    private record StringAnchor(List<Integer> macSlots, List<Integer> windowsSlots,
                                Set<String> strings) {
        int macSlot() { return macSlots.get(0); }
        int windowsSlot() { return windowsSlots.get(0); }
        boolean uniqueMacSlot() { return macSlots.size() == 1; }
        boolean uniqueWindowsSlot() { return windowsSlots.size() == 1; }
    }
    private record BsimAnchor(List<Integer> macSlots, List<Integer> windowsSlots,
                              double similarity, double significance, double margin) {
        int macSlot() { return macSlots.get(0); }
        int windowsSlot() { return windowsSlots.get(0); }
        boolean uniqueMacSlot() { return macSlots.size() == 1; }
        boolean uniqueWindowsSlot() { return windowsSlots.size() == 1; }
    }
    private record BsimScore(int windowsSlot, double similarity, double significance) {}
    private record BsimComparison(double similarity, double significance) {}
    private record SlotMapping(int macSlot, int windowsSlot) {}
    private record TableEvidence(Address start, Vtable table, List<NameAnchor> anchors,
                                 List<StringAnchor> stringAnchors, List<BsimAnchor> bsimAnchors,
                                 int sameSlotAnchors, String bsimStatus,
                                 List<HeaderAnchor> headerAnchors) {}
    private record ProposedRole(String owner, List<Integer> slots, String method, String evidence,
                                String confidence, Address slotTarget) {}
    private record ClassProposal(ClassMatch match, TableEvidence table,
                                 List<SlotMapping> order) {}
    private record DataProposal(NamedString source, NamedString target) {}
    private record SlotNote(int rank, String text) {}
    private record ProgramChoice(Program program, String label) {
        @Override public String toString() { return label; }
    }
    private record HeaderClass(String name, List<String> bases, List<String> virtuals) {}
    private record HeaderAnchor(int slot, String method) {}

    private static class ApplyStats {
        int functionCandidates;
        int functionNames;
        int roleLabels;
        int ambiguousImplementations;
        int commentOnlyImplementations;
        int vtableSlotTargets;
        int missingFunctionTargets;
        int functionsCreated;
        int functionCreationSkipped;
        int vtableGroups;
        int vtableUnique;
        int vtableAlreadyPresent;
        int vtableAmbiguous;
        int vtableCandidateComments;
        int vtableRejected;
        int vtableLabels;
        int dataLabels;
        int comments;
        int protectedItems;
    }

    private final Map<Long, Map<String, ProposedRole>> proposedRoles = new HashMap<>();
    private final Map<Function, Set<String>> referencedStringCache = new HashMap<>();
    private final List<ClassProposal> classProposals = new ArrayList<>();
    private final List<DataProposal> dataProposals = new ArrayList<>();
    private BsimMatcher bsimMatcher;

    // Ground-truth ids parsed from the gzcom-dll headers, mapped to the constant
    // name that defined them. Empty when no header directory was supplied, in
    // which case id extraction falls back to the older CMP-only heuristic.
    private final Map<Long, String> knownGzids = new TreeMap<>(Long::compareUnsigned);
    private final Map<Function, Set<Long>> comparisonIdCache = new HashMap<>();

    // Declared virtual-method order per class, parsed from the same headers.
    // GZCOM interfaces declare no virtual destructor, so declaration order is
    // vtable order on both ABIs and the Itanium D1/D0 pair never intrudes.
    private final Map<String, HeaderClass> headerClasses = new TreeMap<>();

    private java.io.PrintWriter logWriter;
    private File logFile;

    @Override
    public void run() throws Exception {
        openLog();
        try {
            runAnalysis();
        } finally {
            closeLog();
        }
    }

    private void runAnalysis() throws Exception {
        selfCheck();
        println("RecoverWindowsSC4SymbolsFromMac " + SCRIPT_VERSION +
                " -- ANALYZE, THEN OPTIONAL APPLY");
        println("No changes occur unless the final apply prompt is accepted.");
        if (logFile != null) println("Run log: " + logFile);
        println("");

        Program mac = chooseMacProgram();
        if (mac == null) return;
        if (!verifyProgramRoles(mac, currentProgram)) return;
        loadKnownGzids();
        Pattern classFilter = Pattern.compile(askString(
                "Class filter", "Java regex (.* examines every recovered class)", ".*"));
        warnProgramSelection(mac, currentProgram);
        reportUserDefinedFunNames(currentProgram);
        if (askYesNo("Optional BSim method matching",
                "Generate database-free BSim signatures for unresolved vtable methods?\n" +
                "This is read-only but can be slow; use a narrow class filter for the first run.")) {
            try {
                bsimMatcher = new BsimMatcher(mac, currentProgram);
                println("BSim: enabled (similarity >= " + MIN_BSIM_SIMILARITY +
                        ", significance >= " + MIN_BSIM_SIGNIFICANCE +
                        ", runner-up margin >= " + MIN_BSIM_MARGIN + ")\n");
            } catch (Exception e) {
                println("BSim: unavailable: " + e.getMessage() + "\n");
            }
        } else {
            println("BSim: disabled\n");
        }

        try {
            monitor.setMessage("Reading symbolized Mac QI vtables");
            List<MacClass> macClasses = readMacClasses(mac, classFilter);
            Set<Long> knownIds = new HashSet<>();
            Map<Long, Integer> idFrequency = new HashMap<>();
            Map<String, Integer> fingerprintFrequency = new HashMap<>();
            for (MacClass clazz : macClasses) {
                for (long id : nonTrivial(clazz.ids())) {
                    knownIds.add(id);
                    idFrequency.merge(id, 1, Integer::sum);
                }
                fingerprintFrequency.merge(fingerprint(clazz.ids()), 1, Integer::sum);
            }

            println("Mac QI-vtable classes=" + macClasses.size() +
                    ", distinct nontrivial GZIIDs=" + knownIds.size());
            monitor.setMessage("Scanning Windows functions for known GZIIDs");
            List<WindowsCandidate> candidates = readWindowsCandidates(currentProgram, knownIds);
            println("Windows functions comparing known GZIIDs=" + candidates.size());

            List<ClassMatch> matches = matchClasses(macClasses, candidates, idFrequency,
                    fingerprintFrequency);
            long confirmed = matches.stream().filter(m -> m.confidence().equals("confirmed")).count();
            long strong = matches.stream().filter(m -> m.confidence().equals("strong")).count();
            long single = matches.stream().filter(m -> m.confidence().equals("single-id")).count();
            long family = matches.stream().filter(m -> m.confidence().equals("family")).count();
            long probable = matches.stream().filter(m -> m.confidence().equals("probable")).count();
            println("QI matches: confirmed=" + confirmed + ", strong=" + strong +
                    ", single-id=" + single + ", family=" + family +
                    ", probable=" + probable + "\n");
            println("Method-name rule: exact agreement is positive evidence; a different Windows name " +
                    "is neutral because implementations may be shared.\n");
            reportOracle(matches);

            List<ClassMatch> detailed = matches.stream()
                    .filter(m -> !m.confidence().equals("probable") &&
                            !m.confidence().equals("weak"))
                    .toList();
            Set<Address> matchedTargets = new HashSet<>();
            for (ClassMatch match : detailed)
                matchedTargets.add(match.windows().function().getEntryPoint());
            monitor.setMessage("Finding Windows vtables for strong QI matches");
            Map<Long, List<Address>> pointerRefs = pointerReferences(currentProgram, matchedTargets);
            Set<Long> qiTargets = queryInterfaceTargets(currentProgram, candidates);

            int reported = 0;
            for (int i = 0; i < detailed.size(); i++) {
                monitor.checkCancelled();
                if (reportClass(mac, currentProgram, detailed.get(i), pointerRefs, qiTargets,
                        i < MAX_CLASS_REPORTS)) reported++;
            }
            if (detailed.size() > MAX_CLASS_REPORTS)
                println("\nDetailed class output limited to " + MAX_CLASS_REPORTS +
                        "; all " + detailed.size() + " matches were analyzed. " +
                        "Use a narrow class filter to print a later class.");
            if (reported < detailed.size())
                println("\nFamily-QI classes without positive method anchors omitted=" +
                        (detailed.size() - reported));

            reportProbableMatches(matches);
            reportSharedRoleCandidates(currentProgram);

            monitor.setMessage("Matching unique named strings");
            reportNamedStringData(mac, currentProgram);
            if (bsimMatcher != null) bsimMatcher.reportStats();
            logRunSummary(mac, currentProgram, classFilter, macClasses.size(),
                    candidates.size(), matches);
            offerApply(currentProgram, classFilter);
        } finally {
            if (bsimMatcher != null) bsimMatcher.close();
        }
    }

    /**
     * Open a per-run log beside the script, under ~/ghidra_scripts/logs.
     *
     * <p>Every run gets its own timestamped file so runs stay comparable
     * instead of overwriting each other. Console output is mirrored verbatim;
     * the log additionally carries records the console suppresses for
     * readability, which is what makes a log worth analysing after the fact.
     * Failing to open a log never blocks the run.
     */
    private void openLog() {
        try {
            File directory = new File(System.getProperty("user.home"), "ghidra_scripts/logs");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                    .format(new Date());
            logFile = new File(directory, "RecoverWindowsSC4Symbols-" + stamp + ".log");
            logWriter = new java.io.PrintWriter(new java.io.BufferedWriter(
                    new java.io.FileWriter(logFile, java.nio.charset.StandardCharsets.UTF_8)), true);
            log("# RecoverWindowsSC4SymbolsFromMac run log");
            log("# version: " + SCRIPT_VERSION);
            log("# started: " + new Date());
        } catch (Exception failed) {
            logWriter = null;
            logFile = null;
        }
    }

    private void closeLog() {
        if (logWriter == null) return;
        log("# finished: " + new Date());
        logWriter.flush();
        logWriter.close();
        logWriter = null;
    }

    /** Write to the run log only, bypassing the console. */
    private void log(String message) {
        if (logWriter != null) logWriter.println(message);
    }

    /** Mirror everything the script prints into the run log. */
    @Override
    public void println(String message) {
        super.println(message);
        log(message);
    }

    /**
     * Parse GZCOM interface ids out of the gzcom-dll headers. These are the only
     * ground truth available for which 32-bit constants are real ids: without
     * them, id extraction cannot tell a GZIID from a struct displacement, and
     * cannot recover an id that MSVC folded a DEC into (see GZID_FOLD_TOLERANCE).
     * Optional -- cancelling leaves the older heuristic in place.
     */
    private void loadKnownGzids() {
        File directory;
        try {
            directory = askDirectory("gzcom-dll include directory (optional)", "Use");
        } catch (Exception cancelled) {
            println("GZIID allowlist: not supplied; falling back to the CMP-only heuristic.");
            println("  Extraction will miss SUB/ADD-encoded ids and admit displacement noise.\n");
            return;
        }
        if (directory == null || !directory.isDirectory()) return;

        Pattern constant = Pattern.compile(
                "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*0[xX]([0-9a-fA-F]+)\\s*[;,]");
        int files = collectGzids(directory, constant, 0);
        println("GZIID allowlist: " + knownGzids.size() + " ids from " + files +
                " header files under " + directory);
        long rooted = headerClasses.keySet().stream()
                .filter(name -> !headerVtableOrder(name).isEmpty()).count();
        println("Header vtable order: " + headerClasses.size() + " classes parsed, " +
                rooted + " resolve to a cIGZUnknown-rooted slot order");
        if (knownGzids.isEmpty())
            println("  WARNING: no constants parsed; check the directory.");
        if (!headerClasses.isEmpty() && rooted == 0)
            println("  WARNING: no class resolved; the header layout may have changed " +
                    "and the parser needs revisiting.");
        println("");
    }

    private int collectGzids(File directory, Pattern constant, int depth) {
        if (depth > 8) return 0;
        File[] entries = directory.listFiles();
        if (entries == null) return 0;
        int files = 0;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                files += collectGzids(entry, constant, depth + 1);
                continue;
            }
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".h") && !name.endsWith(".hpp")) continue;
            files++;
            try {
                String text = new String(java.nio.file.Files.readAllBytes(entry.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = constant.matcher(text);
                while (matcher.find()) {
                    long value = Long.parseUnsignedLong(matcher.group(2), 16);
                    // Only 32-bit values are candidate ids; ignore byte/word flags
                    // and masks, which collide with ordinary immediates.
                    if (value > 0xffffffffL || value < 0x1000L) continue;
                    knownGzids.putIfAbsent(value, matcher.group(1));
                }
                parseHeaderClasses(text);
            } catch (Exception ignored) {
                // Unreadable header; the remaining files still contribute.
            }
        }
        return files;
    }

    /**
     * Map an observed immediate onto a known id, compensating for MSVC folding a
     * DEC/INC into the constant. Returns null when the value is not a known id.
     */
    /**
     * Record each class's declared virtual methods in order, with its base list.
     * A deliberately shallow line parser: GZCOM interface headers are plain
     * single-inheritance declarations of pure virtuals, and anything more
     * involved than that is skipped rather than guessed at.
     */
    private void parseHeaderClasses(String raw) {
        // The base clause is optional: cIGZUnknown itself declares none, and
        // without it the whole chain fails to root and no order resolves.
        Pattern declaration = Pattern.compile(
                "^\\s*class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::([^{;]*))?\\{?\\s*$");
        // The operator alternative is load-bearing. A plain identifier match
        // requires a name immediately before "(", which "operator=(" does not
        // have, so operators were dropped silently -- shifting every later slot
        // by one and producing a wrong but entirely plausible order.
        Pattern virtualMethod = Pattern.compile(
                "^\\s*virtual\\b[^;]*?\\b(operator\\s*[^\\s(]+|[A-Za-z_][A-Za-z0-9_]*)\\s*\\("
                        + ".*?=\\s*0\\s*;",
                Pattern.DOTALL);
        // Doc comments carry braces and semicolons that would corrupt brace
        // depth and statement boundaries, so remove them before parsing.
        String text = raw.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            java.util.regex.Matcher header = declaration.matcher(lines[i]);
            if (!header.find()) continue;
            String name = header.group(1);
            List<String> bases = new ArrayList<>();
            if (header.group(2) != null)
                for (String base : header.group(2).split(","))
                    for (String word : base.trim().split("\\s+"))
                        if (!word.equals("public") && !word.equals("protected") &&
                                !word.equals("private") && !word.equals("virtual") &&
                                !word.isBlank()) { bases.add(word); break; }

            List<String> virtuals = new ArrayList<>();
            int depth = 0;
            boolean entered = false;
            StringBuilder pending = new StringBuilder();
            for (int line = i; line < lines.length; line++) {
                int before = depth;
                depth += count(lines[line], '{') - count(lines[line], '}');
                if (!entered && depth > 0) entered = true;
                if (entered && depth <= 0) break;
                // Only record at class scope; nested enums and structs push
                // deeper and must not contribute slots.
                if (before != 1 && depth != 1) { pending.setLength(0); continue; }
                // Accumulate only once a virtual declaration has started, so a
                // preceding access specifier cannot break the anchored match,
                // and so declarations wrapped across lines still resolve.
                if (pending.length() == 0 && !lines[line].contains("virtual")) continue;
                pending.append(lines[line]).append(' ');
                if (lines[line].contains(";")) {
                    java.util.regex.Matcher method = virtualMethod.matcher(pending.toString());
                    // Normalise "operator =" to "operator=" so it compares equal
                    // to the demangled Mac symbol name.
                    if (method.find())
                        virtuals.add(method.group(1).replaceAll("\\s+", ""));
                    pending.setLength(0);
                }
            }
            if (!virtuals.isEmpty())
                headerClasses.putIfAbsent(name, new HeaderClass(name, bases, virtuals));
        }
    }

    private int count(String text, char target) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == target) total++;
        return total;
    }

    /**
     * Expected primary-vtable order for a class: its first base's layout,
     * then its own declarations. Only the first base shares the primary vtable
     * on both ABIs, so later bases are excluded rather than concatenated.
     * Returns empty unless the chain is fully known and rooted at cIGZUnknown,
     * which is what guarantees slots 0-2 are QueryInterface/AddRef/Release.
     */
    private List<String> headerVtableOrder(String className) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = className;
        Deque<HeaderClass> chain = new ArrayDeque<>();
        while (current != null && seen.add(current)) {
            HeaderClass entry = headerClasses.get(current);
            if (entry == null) return List.of();
            chain.push(entry);
            if (entry.name().equals("cIGZUnknown")) break;
            current = entry.bases().isEmpty() ? null : entry.bases().get(0);
        }
        if (chain.isEmpty() || !chain.peek().name().equals("cIGZUnknown")) return List.of();
        for (HeaderClass entry : chain) order.addAll(entry.virtuals());
        return order;
    }

    private Long canonicalGzid(long value) {
        // An exact hit is never a fold, and checking it first is load-bearing:
        // GZCOM assigns a class's interface id and class id one apart (for
        // example GZIID_cIGZWinSlider 0x21325207 and kcGZWinSlider 0x21325208),
        // so a nearest-match search that did not prefer the exact value would
        // silently collapse ten such pairs onto their lower member.
        if (knownGzids.containsKey(value)) return value;
        // Fold only upwards. "DEC EAX; SUB EAX,K" tests riid == K + 1, so a
        // folded immediate is always below the id it stands for. Accepting
        // downward folds as well would double the false-match surface with no
        // evidence for it, and would misread the higher member of an adjacent
        // id pair as a fold of the lower one.
        for (long distance = 1; distance <= GZID_FOLD_TOLERANCE; distance++)
            if (knownGzids.containsKey(value + distance)) return value + distance;
        return null;
    }

    private Program chooseMacProgram() throws Exception {
        ProgramManager manager = state.getTool() == null ? null :
                state.getTool().getService(ProgramManager.class);
        if (manager == null) {
            println("ERROR: no Program Manager service. Run this from a CodeBrowser window.");
            return null;
        }

        List<ProgramChoice> choices = Arrays.stream(manager.getAllOpenPrograms())
                .filter(program -> program != currentProgram)
                .map(program -> new ProgramChoice(program, programLabel(program)))
                .sorted(Comparator.comparing(ProgramChoice::label))
                .toList();
        if (choices.isEmpty()) {
            println("ERROR: open the symbolized Mac x86-32 program in this CodeBrowser, then rerun.");
            return null;
        }

        println("Windows target: " + programLabel(currentProgram));
        ProgramChoice choice = choices.size() == 1 ? choices.get(0) : askChoice(
                "Symbolized Mac source", "Open program (full project path)", choices, choices.get(0));
        if (choice.program() == currentProgram)
            throw new IllegalStateException("Mac source and Windows target must be different programs");
        return choice.program();
    }

    private String programLabel(Program program) {
        DomainFile file = program.getDomainFile();
        String path = file == null ? "<no project path>" : file.getPathname();
        return path + " | " + program.getExecutableFormat() + " | " +
                program.getLanguageID() + " | " + (program.getDefaultPointerSize() * 8) + "-bit";
    }

    private void reportOracle(List<ClassMatch> matches) {
        Optional<ClassMatch> oracle = matches.stream()
                .filter(m -> m.mac().name().endsWith("cGZUIScriptService"))
                .findFirst();
        if (oracle.isEmpty()) {
            println("Oracle cGZUIScriptService: NOT FOUND\n");
            return;
        }
        ClassMatch match = oracle.get();
        println("Oracle cGZUIScriptService: FOUND [" +
                match.confidence().toUpperCase(Locale.ROOT) + "] -> " +
                match.windows().function().getEntryPoint() + " " +
                qualified(match.windows().function()) + "\n");
    }

    private void reportProbableMatches(List<ClassMatch> matches) {
        List<ClassMatch> probable = matches.stream()
                .filter(m -> m.confidence().equals("probable"))
                .limit(MAX_PROBABLE_REPORTS)
                .toList();
        if (probable.isEmpty()) return;
        println("\nProbable QI candidates (compact; no slot-order report):");
        for (ClassMatch match : probable) {
            println(String.format(Locale.ROOT,
                    "  %-42s -> %s %-32s score=%.1f margin=%.1f IDs=%s",
                    shorten(match.mac().name(), 42), match.windows().function().getEntryPoint(),
                    shorten(qualified(match.windows().function()), 32), match.score(), match.margin(),
                    ids(match.mac().ids())));
        }
        long total = matches.stream().filter(m -> m.confidence().equals("probable")).count();
        if (total > probable.size())
            println("  ... probable report truncated at " + MAX_PROBABLE_REPORTS + " entries");
    }

    /**
     * Refuse to run with the two programs in the wrong roles.
     *
     * <p>The script writes to `currentProgram`, so if the Mac program happens to
     * be the active one, every write lands in the wrong database while the
     * analysis silently produces nothing -- Itanium `__ZTV*` symbols do not
     * exist in a PE, so every count comes back zero and the run looks merely
     * unproductive rather than misdirected. Pointer size cannot catch this
     * because both builds are 32-bit, so check the things that actually differ.
     */
    private boolean verifyProgramRoles(Program mac, Program windows) throws Exception {
        boolean targetIsPe = windows.getExecutableFormat().contains("Portable Executable");
        boolean sourceIsMacho = mac.getExecutableFormat().contains("Mach-O");
        boolean sourceHasItanium = hasItaniumVtables(mac);
        boolean targetHasItanium = hasItaniumVtables(windows);
        if (targetIsPe && (sourceIsMacho || sourceHasItanium) && !targetHasItanium) return true;

        println("ERROR: the two programs are in the wrong roles.");
        println("  Windows target (the current program, which receives all writes):");
        println("    " + programLabel(windows) + " | __ZTV symbols=" + targetHasItanium);
        println("  Mac source (read-only oracle):");
        println("    " + programLabel(mac) + " | __ZTV symbols=" + sourceHasItanium);
        println("");
        if (targetHasItanium && !targetIsPe)
            println("  The current program looks like the symbolized Mac build. Make the " +
                    "Windows SimCity 4.exe the current program, then rerun.");
        else if (!targetIsPe)
            println("  The current program is not a Portable Executable.");
        else
            println("  The selected source does not look like the symbolized Mac build.");
        println("  Nothing was analyzed and nothing was written.");
        log("ABORT|reason=program-roles|target=" + windows.getDomainFile().getPathname() +
                "|source=" + mac.getDomainFile().getPathname());
        return false;
    }

    private boolean hasItaniumVtables(Program program) throws Exception {
        SymbolIterator it = program.getSymbolTable().getAllSymbols(true);
        int examined = 0;
        while (it.hasNext() && examined++ < 400000) {
            monitor.checkCancelled();
            if (it.next().getName().startsWith("__ZTV")) return true;
        }
        return false;
    }

    private void warnProgramSelection(Program mac, Program windows) {
        println("Mac source: " + programLabel(mac));
        println("Windows target: " + programLabel(windows));
        if (mac.getDefaultPointerSize() != 4)
            println("WARNING: expected the symbolized 32-bit Mac program.");
        if (windows.getDefaultPointerSize() != 4)
            println("WARNING: expected the 32-bit Windows program.");
        if (mac == windows)
            println("WARNING: source and target are the same program.");
        println("");
    }

    private void reportUserDefinedFunNames(Program program) throws Exception {
        List<Function> functions = new ArrayList<>();
        FunctionIterator iterator = program.getFunctionManager().getFunctions(true);
        while (iterator.hasNext()) {
            monitor.checkCancelled();
            Function function = iterator.next();
            Symbol symbol = function.getSymbol();
            if (symbol != null && symbol.getSource() == SourceType.USER_DEFINED &&
                    symbol.getName().startsWith("FUN_"))
                functions.add(function);
        }
        println("USER_DEFINED placeholder functions named FUN_*=" + functions.size() +
                " (replaceable)");
        for (Function function : functions)
            println("  " + function.getEntryPoint() + " " + qualified(function));
        println("");
    }

    private List<MacClass> readMacClasses(Program program, Pattern filter) throws Exception {
        List<Symbol> symbols = new ArrayList<>();
        SymbolIterator it = program.getSymbolTable().getAllSymbols(true);
        while (it.hasNext()) {
            Symbol symbol = it.next();
            if (symbol.getName().startsWith("__ZTV") && program.getMemory().contains(symbol.getAddress()))
                symbols.add(symbol);
        }
        symbols.sort(Comparator.comparing(Symbol::getAddress));

        Map<String, MacClass> bestByClass = new TreeMap<>();
        for (Symbol symbol : symbols) {
            monitor.checkCancelled();
            String className = vtableClassName(program, symbol);
            if (!filter.matcher(className).matches()) continue;
            Vtable table = readItaniumPrimaryVtable(program, symbol.getAddress());
            if (table == null || table.slots().size() < 3) continue;
            Function qi = resolveThunk(table.slots().get(0).function());
            if (qi == null || !qi.getName().contains("QueryInterface")) continue;
            Set<Long> ids = comparisonIds(program, qi);
            if (nonTrivial(ids).isEmpty()) continue;
            MacClass candidate = new MacClass(className, qi, ids, table);
            MacClass old = bestByClass.get(className);
            if (old == null || candidate.ids().size() > old.ids().size())
                bestByClass.put(className, candidate);
        }
        return new ArrayList<>(bestByClass.values());
    }

    private Vtable readItaniumPrimaryVtable(Program program, Address header) throws Exception {
        int ptr = program.getDefaultPointerSize();
        if (!readable(program, header, 2 * ptr)) return null;
        long top = signedPointer(program, header);
        if (Math.abs(top) > 0x1000000L) return null;
        return readCodeTable(program, header.add(2L * ptr), Set.of());
    }

    private List<WindowsCandidate> readWindowsCandidates(Program program, Set<Long> knownIds)
            throws Exception {
        List<WindowsCandidate> out = new ArrayList<>();
        FunctionIterator functions = program.getFunctionManager().getFunctions(true);
        while (functions.hasNext()) {
            monitor.checkCancelled();
            Function function = functions.next();
            if (function.isExternal() || function.isThunk()) continue;
            // Scan unfiltered. Restricting extraction to ids some Mac class
            // already has made every Windows id set a subset of the Mac side,
            // so `exact` and `extra` could never see a Windows-only id and
            // `confirmed` was reachable on evidence that was not exact at all.
            Set<Long> ids = comparisonIds(program, function);
            if (intersects(nonTrivial(ids), knownIds))
                out.add(new WindowsCandidate(function, ids));
        }
        return out;
    }

    private List<ClassMatch> matchClasses(List<MacClass> classes,
                                          List<WindowsCandidate> candidates,
                                          Map<Long, Integer> idFrequency,
                                          Map<String, Integer> fingerprintFrequency) throws Exception {
        List<ClassMatch> out = new ArrayList<>();
        for (MacClass clazz : classes) {
            monitor.checkCancelled();
            List<WindowsCandidate> possible = candidates.stream()
                    .filter(c -> intersects(nonTrivial(clazz.ids()), nonTrivial(c.ids())))
                    .toList();
            if (possible.isEmpty()) {
                // Logged rather than dropped silently: a Mac class with ids but
                // no Windows candidate is the clearest signal that extraction
                // missed something on the Windows side.
                log("NOCANDIDATE|mac=" + clazz.name() + "|ids=" + ids(clazz.ids()));
                continue;
            }
            List<WindowsCandidate> ranked = new ArrayList<>(possible);
            ranked.sort(Comparator.comparingDouble(
                    (WindowsCandidate c) -> score(clazz.ids(), c.ids(), idFrequency)).reversed());
            WindowsCandidate best = ranked.get(0);
            double bestScore = score(clazz.ids(), best.ids(), idFrequency);
            double second = ranked.size() > 1
                    ? score(clazz.ids(), ranked.get(1).ids(), idFrequency) : Double.NEGATIVE_INFINITY;
            int shared = intersection(clazz.ids(), best.ids()).size();
            int missing = difference(clazz.ids(), best.ids()).size();
            int extra = difference(best.ids(), clazz.ids()).size();
            int sharedNonTrivial = intersection(nonTrivial(clazz.ids()), nonTrivial(best.ids())).size();
            boolean exact = clazz.ids().equals(best.ids());
            long exactWindows = candidates.stream().filter(c -> c.ids().equals(clazz.ids())).count();
            boolean uniqueMac = fingerprintFrequency.getOrDefault(fingerprint(clazz.ids()), 0) == 1;
            double margin = second == Double.NEGATIVE_INFINITY ? 999.0 : bestScore - second;
            String confidence;
            if (!uniqueMac)
                confidence = "family";
            else if (exact && sharedNonTrivial >= 2 && exactWindows == 1)
                confidence = "confirmed";
            else if ((exact && sharedNonTrivial >= 2 && margin >= 8.0) ||
                    (sharedNonTrivial >= 2 && missing <= 1 && margin >= 8.0))
                confidence = "strong";
            else if (exact && sharedNonTrivial == 1 && margin >= 8.0)
                confidence = "single-id";
            else if (sharedNonTrivial >= 1)
                confidence = "probable";
            else
                confidence = "weak";
            out.add(new ClassMatch(clazz, best, bestScore, shared, missing, extra,
                    confidence, margin));
        }
        out = demoteNonMutualMatches(out);
        out.sort(Comparator.comparingInt((ClassMatch m) -> confidenceRank(m.confidence()))
                .thenComparing(Comparator.comparingDouble(ClassMatch::score).reversed())
                .thenComparing(m -> m.mac().name()));
        return out;
    }

    /**
     * Each Mac class picks its best Windows candidate independently, so nothing
     * stopped several classes from claiming one folded QueryInterface at full
     * confidence. Keep the strongest claimant on each Windows function and
     * demote the rest to `probable`, which is below the bar for applying names
     * or creating functions.
     */
    private List<ClassMatch> demoteNonMutualMatches(List<ClassMatch> matches) {
        Map<Address, ClassMatch> bestByWindows = new HashMap<>();
        for (ClassMatch match : matches) {
            Address entry = match.windows().function().getEntryPoint();
            ClassMatch held = bestByWindows.get(entry);
            if (held == null || match.score() > held.score() ||
                    (match.score() == held.score() &&
                            match.mac().name().compareTo(held.mac().name()) < 0))
                bestByWindows.put(entry, match);
        }
        List<ClassMatch> out = new ArrayList<>(matches.size());
        int demoted = 0;
        for (ClassMatch match : matches) {
            ClassMatch winner = bestByWindows.get(match.windows().function().getEntryPoint());
            if (winner == match || confidenceRank(match.confidence()) >= confidenceRank("probable")) {
                out.add(match);
                continue;
            }
            demoted++;
            out.add(new ClassMatch(match.mac(), match.windows(), match.score(), match.shared(),
                    match.missing(), match.extra(), "probable", match.margin()));
        }
        if (demoted > 0)
            println("Non-mutual QI matches demoted to probable=" + demoted +
                    " (several Mac classes claimed one Windows implementation)");
        return out;
    }

    private double score(Set<Long> mac, Set<Long> windows, Map<Long, Integer> frequency) {
        double score = 0;
        for (long id : intersection(nonTrivial(mac), nonTrivial(windows)))
            score += 40.0 / frequency.getOrDefault(id, 1);
        int shared = intersection(mac, windows).size();
        int missing = difference(mac, windows).size();
        int extra = difference(windows, mac).size();
        score += shared * 4.0 - missing * 10.0 - extra * 3.0;
        if (mac.equals(windows)) score += 20.0;
        return score;
    }

    private boolean reportClass(Program mac, Program windows, ClassMatch match,
                                Map<Long, List<Address>> pointerRefs,
                                Set<Long> qiTargets, boolean verbose) throws Exception {
        MacClass clazz = match.mac();
        Function winQi = match.windows().function();
        List<Address> refs = pointerRefs.getOrDefault(addressKey(windows, winQi.getEntryPoint()), List.of());
        List<Address> starts = refs.stream().filter(a -> looksLikeVtableStart(windows, a)).toList();
        // Name and string anchors are cheap; BSim is not. Rank on the cheap
        // evidence first and generate signatures only for the tables still in
        // contention, so the vector budget is not spent on candidates that
        // lose the ranking anyway.
        List<TableEvidence> tables = new ArrayList<>();
        for (Address start : starts) {
            Vtable table = readCodeTable(windows, start, qiTargets);
            List<NameAnchor> anchors = nameAnchors(clazz.primary(), table);
            List<StringAnchor> strings = findStringAnchors(mac, windows, clazz.primary(), table);
            List<HeaderAnchor> header = headerAnchors(clazz.primary(), table, match, anchors);
            tables.add(new TableEvidence(start, table, anchors, strings, List.of(),
                    sameSlotAnchors(anchors, strings, List.of()), "pending", header));
        }
        tables.sort(tableEvidenceOrder());

        int contenders = bsimContenderCount(tables);
        for (int i = 0; i < contenders; i++) {
            monitor.checkCancelled();
            TableEvidence evidence = tables.get(i);
            boolean bsimStarted = bsimMatcher != null && !bsimMatcher.budgetExhausted();
            List<BsimAnchor> bsim = findBsimAnchors(mac, windows, clazz.primary(),
                    evidence.table(), evidence.anchors(), evidence.stringAnchors());
            String bsimStatus = bsimMatcher == null ? "disabled" : !bsimStarted ?
                    "skipped: vector budget reached" : bsimMatcher.budgetExhausted() ?
                    "partial: vector budget reached" : "complete";
            tables.set(i, new TableEvidence(evidence.start(), evidence.table(),
                    evidence.anchors(), evidence.stringAnchors(), bsim,
                    sameSlotAnchors(evidence.anchors(), evidence.stringAnchors(), bsim),
                    bsimStatus, evidence.headerAnchors()));
        }
        for (int i = contenders; i < tables.size(); i++) {
            TableEvidence evidence = tables.get(i);
            tables.set(i, new TableEvidence(evidence.start(), evidence.table(),
                    evidence.anchors(), evidence.stringAnchors(), List.of(),
                    evidence.sameSlotAnchors(), "not ranked: outscored before BSim", evidence.headerAnchors()));
        }
        tables.sort(tableEvidenceOrder());

        if (match.confidence().equals("family") &&
                (tables.isEmpty() || tableEvidenceScore(tables.get(0)) == 0))
            return false;

        if (verbose) {
            Symbol winSymbol = winQi.getSymbol();
            println("\n[" + match.confidence().toUpperCase(Locale.ROOT) + "] " + clazz.name());
            println("  QI IDs: " + ids(clazz.ids()));
            println("  Mac: " + clazz.queryInterface().getEntryPoint() + " " +
                    qualified(clazz.queryInterface()));
            println("  Win: " + winQi.getEntryPoint() + " " + qualified(winQi) +
                    " | symbol=" + protection(winSymbol));
            println(String.format(Locale.ROOT,
                    "  score=%.1f margin=%.1f shared=%d missing=%d extra=%d",
                    match.score(), match.margin(), match.shared(), match.missing(), match.extra()));
        }

        if (tables.isEmpty()) {
            if (verbose)
                println("  Windows primary vtable: not found by raw QI-pointer scan");
            logClassRecord(match, 0, null, List.of(), false);
            return true;
        }

        TableEvidence best = tables.get(0);
        boolean uniqueBest = tables.size() == 1 ||
                tableEvidenceScore(best) > tableEvidenceScore(tables.get(1)) ||
                (tableEvidenceScore(best) == tableEvidenceScore(tables.get(1)) &&
                        best.sameSlotAnchors() > tables.get(1).sameSlotAnchors());
        if (verbose) {
            String selection = tables.size() == 1 ? "only candidate" :
                    uniqueBest ? "unique by method anchors" : "tied by method anchors";
            println("  Windows vtable candidates=" + tables.size() + " | best=" + selection);
            reportTable(mac, windows, clazz.primary(), best, match);
            for (int i = 1; i < Math.min(tables.size(), 5); i++) {
                TableEvidence alternate = tables.get(i);
                Symbol symbol = windows.getSymbolTable().getPrimarySymbol(alternate.start());
                println("    alternate " + alternate.start() + " | " + symbolText(symbol) +
                        " | slots=" + alternate.table().slots().size() +
                        " | stop=" + alternate.table().extentStop() +
                        " | anchors=name:" + alternate.anchors().size() +
                        ",string:" + alternate.stringAnchors().size() +
                        ",bsim:" + alternate.bsimAnchors().size() +
                        " (" + alternate.bsimStatus() + ", same-slot=" +
                        alternate.sameSlotAnchors() + ")");
            }
            if (tables.size() > 5)
                println("    ... alternate vtable list truncated at 4");
        }

        boolean tableResolved = (tables.size() == 1 &&
                !match.confidence().equals("family")) ||
                (uniqueBest && positiveAnchorCount(best) >=
                        (match.confidence().equals("family") ? 2 : 1));
        List<SlotMapping> order = List.of();
        if (tableResolved) {
            order = supportedOrderMappings(clazz.primary(), best.table(),
                    best.anchors(), best.stringAnchors(), best.bsimAnchors());
            classProposals.add(new ClassProposal(match, best, order));
            collectProposedRoles(windows, match, clazz.primary(), best.table(),
                    best.anchors(), best.stringAnchors(), best.bsimAnchors());
        }
        logClassRecord(match, tables.size(), best, order, tableResolved);
        return true;
    }

    /**
     * One pipe-delimited record per analysed class, written to the log only.
     *
     * <p>Console class output is capped for readability, which makes it useless
     * for judging whether a threshold change helped. These records are emitted
     * for every class regardless of that cap, so successive runs can be
     * compared field by field.
     */
    private void logClassRecord(ClassMatch match, int tableCount, TableEvidence best,
                                List<SlotMapping> order, boolean resolved) {
        if (logWriter == null) return;
        List<String> fields = new ArrayList<>();
        fields.add("CLASS");
        fields.add("mac=" + match.mac().name());
        fields.add("conf=" + match.confidence());
        fields.add(String.format(Locale.ROOT, "score=%.1f", match.score()));
        fields.add(String.format(Locale.ROOT, "margin=%.1f", match.margin()));
        fields.add("shared=" + match.shared());
        fields.add("missing=" + match.missing());
        fields.add("extra=" + match.extra());
        fields.add("macids=" + match.mac().ids().size());
        fields.add("winqi=" + match.windows().function().getEntryPoint());
        fields.add("tables=" + tableCount);
        if (best == null) {
            fields.add("table=none");
        } else {
            fields.add("table=" + best.start());
            fields.add("slots=" + best.table().slots().size());
            fields.add("name=" + best.anchors().size());
            fields.add("string=" + best.stringAnchors().size());
            fields.add("bsim=" + best.bsimAnchors().size());
            fields.add("header=" + best.headerAnchors().size());
            fields.add("headeriface=" + (best.headerAnchors().isEmpty() ? "none"
                    : best.headerAnchors().get(0).method().replaceFirst("::.*$", "")));
            fields.add("headercands=" + headerInterfaceCandidates(match).size());
            fields.add("order=" + order.size());
            fields.add("bsimstatus=" + best.bsimStatus());
            fields.add("stop=" + best.table().extentStop());
        }
        fields.add("resolved=" + resolved);
        log(String.join("|", fields));
    }

    /**
     * Key=value run summary written to the log only. Deliberately flat and
     * greppable so two runs can be diffed directly to see what a heuristic
     * change actually moved.
     */
    private void logRunSummary(Program mac, Program windows, Pattern classFilter,
                               int macClasses, int windowsCandidates,
                               List<ClassMatch> matches) {
        if (logWriter == null) return;
        log("");
        log("=== RUN SUMMARY ===");
        log("version=" + SCRIPT_VERSION);
        log("mac.program=" + mac.getDomainFile().getPathname());
        log("windows.program=" + windows.getDomainFile().getPathname());
        log("class.filter=" + classFilter.pattern());
        log("gziid.allowlist=" + knownGzids.size());
        log("header.classes=" + headerClasses.size());
        log("header.rooted=" + headerClasses.keySet().stream()
                .filter(name -> !headerVtableOrder(name).isEmpty()).count());
        log("bsim.enabled=" + (bsimMatcher != null));
        log("mac.qi.classes=" + macClasses);
        log("windows.qi.candidates=" + windowsCandidates);
        for (String confidence : List.of("confirmed", "strong", "single-id", "family",
                "probable", "weak"))
            log("match." + confidence + "=" + matches.stream()
                    .filter(m -> m.confidence().equals(confidence)).count());
        log("class.proposals=" + classProposals.size());
        log("implementations=" + proposedRoles.size());
        log("data.proposals=" + dataProposals.size());
        long headerBacked = classProposals.stream()
                .filter(p -> !p.table().headerAnchors().isEmpty()).count();
        log("proposals.with.header.order=" + headerBacked);
        log("=== END RUN SUMMARY ===");
        log("");
    }

    private Comparator<TableEvidence> tableEvidenceOrder() {
        return Comparator.comparingInt((TableEvidence e) -> tableEvidenceScore(e)).reversed()
                .thenComparing(Comparator.comparingInt(TableEvidence::sameSlotAnchors).reversed())
                .thenComparing(TableEvidence::start);
    }

    private int sameSlotAnchors(List<NameAnchor> anchors, List<StringAnchor> strings,
                                List<BsimAnchor> bsim) {
        return (int)anchors.stream().filter(a -> a.macSlot() == a.windowsSlot()).count() +
                (int)strings.stream().filter(a -> a.uniqueMacSlot() && a.uniqueWindowsSlot() &&
                        a.macSlot() == a.windowsSlot()).count() +
                (int)bsim.stream().filter(a -> a.uniqueMacSlot() && a.uniqueWindowsSlot() &&
                        a.macSlot() == a.windowsSlot()).count();
    }

    /**
     * How many tables still need BSim after cheap ranking. Only tables that
     * could still win are worth signatures: if one table already leads on name
     * and string evidence, BSim cannot overturn it, because BSim contributes
     * less per anchor than either.
     */
    private int bsimContenderCount(List<TableEvidence> tables) {
        if (bsimMatcher == null || tables.isEmpty()) return 0;
        if (tables.size() == 1) return 1;
        int leader = tableEvidenceScore(tables.get(0));
        int contenders = 1;
        while (contenders < tables.size() &&
                tableEvidenceScore(tables.get(contenders)) == leader) contenders++;
        // A single unbeaten leader still needs BSim for its own method anchors.
        return contenders;
    }

    private int tableEvidenceScore(TableEvidence evidence) {
        // Header evidence scores as a flat bonus rather than per slot: it
        // arrives dozens of slots at a time and would otherwise swamp every
        // other anchor. A table that fits the declared order is strong evidence
        // that it is the right table, but it is one fact, not many.
        return evidence.anchors().size() * 100 + evidence.stringAnchors().size() * 40 +
                evidence.bsimAnchors().size() * 20 +
                (evidence.headerAnchors().isEmpty() ? 0 : 80);
    }

    private int positiveAnchorCount(TableEvidence evidence) {
        Set<String> pairs = new HashSet<>();
        // A corroborated header order counts as a positive anchor; it already
        // required three agreeing Mac slots and no contradiction, which is
        // stronger than any single string or BSim hit.
        if (!evidence.headerAnchors().isEmpty()) pairs.add("header");
        for (NameAnchor anchor : evidence.anchors())
            pairs.add(anchor.macSlot() + ":" + anchor.windowsSlot());
        for (StringAnchor anchor : evidence.stringAnchors())
            pairs.add(anchor.macSlot() + ":" + anchor.windowsSlot());
        for (BsimAnchor anchor : evidence.bsimAnchors())
            pairs.add(anchor.macSlot() + ":" + anchor.windowsSlot());
        return pairs.size();
    }

    private void reportHeaderOrder(Vtable macTable, TableEvidence evidence, ClassMatch match) {
        List<String> candidates = headerInterfaceCandidates(match);
        if (candidates.isEmpty()) {
            println("    header declaration order: no candidate interface " +
                    "(no GZIID mapped to a parsed header class)");
            return;
        }
        List<HeaderAnchor> anchors = evidence.headerAnchors();
        StringBuilder detail = new StringBuilder();
        for (String candidate : candidates) {
            List<String> order = headerVtableOrder(candidate);
            int agreement = headerAgreementWithMac(macTable, order);
            if (!detail.isEmpty()) detail.append(", ");
            detail.append(candidate).append(" decl=").append(order.size())
                    .append(" macAgree=").append(agreement < 0 ? "contradicts" : agreement)
                    .append(evidence.table().slots().size() < order.size() ? " tooShort" : "");
        }
        println("    header declaration order: " + (anchors.isEmpty() ? "not applied" :
                "applied " + anchors.size() + " slots") + " | candidates: " + detail);
    }

    private void reportTable(Program mac, Program windows, Vtable source,
                             TableEvidence evidence, ClassMatch match) {
        Symbol symbol = windows.getSymbolTable().getPrimarySymbol(evidence.start());
        println("  Windows vtable: " + evidence.start() + " | " + symbolText(symbol) +
                " | slots=" + evidence.table().slots().size() +
                (evidence.table().truncated() ? "+" : "") +
                " | stop=" + evidence.table().extentStop());
        reportKnownPrefix(evidence.table());
        reportHeaderOrder(source, evidence, match);
        reportNameAnchors(source, evidence.table(), evidence.anchors());
        reportStringMethodEvidence(source, evidence.table(), evidence.stringAnchors());
        reportBsimMethodEvidence(source, evidence.table(), evidence.bsimAnchors(),
                evidence.bsimStatus());
        List<SlotMapping> order = supportedOrderMappings(source, evidence.table(),
                evidence.anchors(), evidence.stringAnchors(), evidence.bsimAnchors());
        reportOrderDiagnostic(source, evidence.table(), order);
    }

    private void reportKnownPrefix(Vtable table) {
        String[] roles = { "QueryInterface", "AddRef", "Release" };
        for (int i = 0; i < Math.min(roles.length, table.slots().size()); i++) {
            Slot slot = table.slots().get(i);
            println(String.format("    [%02d] %-14s %s %s", i, roles[i], slot.target(),
                    slot.function() == null ? "<no function>" : qualified(slot.function())));
        }
    }

    private List<NameAnchor> nameAnchors(Vtable mac, Vtable windows) {
        Map<String, List<Integer>> macSlots = namedSlots(mac);
        Map<String, List<Integer>> windowsSlots = namedSlots(windows);
        List<NameAnchor> out = new ArrayList<>();
        for (String method : macSlots.keySet()) {
            List<Integer> left = macSlots.get(method);
            List<Integer> right = windowsSlots.get(method);
            if (right != null && left.size() == 1 && right.size() == 1)
                out.add(new NameAnchor(left.get(0), right.get(0), method));
        }
        out.sort(Comparator.comparingInt(NameAnchor::macSlot));
        return out;
    }

    private Map<String, List<Integer>> namedSlots(Vtable table) {
        Map<String, List<Integer>> out = new HashMap<>();
        for (int slot = 3; slot < table.slots().size(); slot++) {
            String name = meaningfulMethodName(table.slots().get(slot).function());
            if (name != null) out.computeIfAbsent(name, ignored -> new ArrayList<>()).add(slot);
        }
        return out;
    }

    private String meaningfulMethodName(Function function) {
        if (function == null) return null;
        Function resolved = resolveThunk(function);
        String qualified = qualified(function);
        String resolvedQualified = resolved == null ? null : qualified(resolved);
        String name = meaningfulName(qualified) ? qualified :
                meaningfulName(resolvedQualified) ? resolvedQualified : null;
        return name == null ? null : leafName(name);
    }

    private boolean meaningfulName(String name) {
        if (name == null || name.startsWith("OOAnalyzer::") || isPlaceholder(name)) return false;
        String leaf = leafName(name);
        return !leaf.equals("_purecall") && !leaf.equals("__purecall");
    }

    private void reportNameAnchors(Vtable mac, Vtable windows, List<NameAnchor> anchors) {
        println("    existing method-name anchors=" + anchors.size());
        for (NameAnchor anchor : anchors.stream().limit(MAX_NAME_ANCHORS).toList()) {
            Slot source = mac.slots().get(anchor.macSlot());
            Slot target = windows.slots().get(anchor.windowsSlot());
            println(String.format("      Mac[%02d] %-30s -> Win[%02d] %s %s",
                    anchor.macSlot(), shorten(qualified(source.function()), 30),
                    anchor.windowsSlot(), target.target(), qualified(target.function())));
        }
        if (anchors.size() > MAX_NAME_ANCHORS)
            println("      ... name-anchor list truncated at " + MAX_NAME_ANCHORS);
    }

    private List<StringAnchor> findStringAnchors(Program mac, Program windows,
                                                 Vtable source, Vtable target) {
        Map<Long, List<Integer>> sourceSlotsByTarget = slotsByTarget(mac, source, 3);
        Map<Long, List<Integer>> slotsByTarget = slotsByTarget(windows, target, 3);
        List<StringAnchor> found = new ArrayList<>();
        for (List<Integer> sourceSlots : sourceSlotsByTarget.values()) {
            int sourceSlot = sourceSlots.get(0);
            Function sourceFunction = resolveThunk(source.slots().get(sourceSlot).function());
            if (sourceFunction == null) continue;
            Set<String> left = referencedStringCache.computeIfAbsent(sourceFunction,
                    f -> referencedStrings(mac, f));
            if (left.isEmpty()) continue;
            int bestSlot = -1;
            int bestWeight = 0;
            int ties = 0;
            Set<String> bestShared = Set.of();
            for (List<Integer> targetSlots : slotsByTarget.values()) {
                int targetSlot = targetSlots.get(0);
                Function targetFunction = resolveThunk(target.slots().get(targetSlot).function());
                if (targetFunction == null) continue;
                Set<String> right = referencedStringCache.computeIfAbsent(targetFunction,
                        f -> referencedStrings(windows, f));
                Set<String> shared = intersection(left, right);
                int weight = shared.stream().mapToInt(String::length).sum();
                if (weight > bestWeight) {
                    bestSlot = targetSlot;
                    bestWeight = weight;
                    bestShared = shared;
                    ties = 1;
                } else if (weight > 0 && weight == bestWeight) {
                    ties++;
                }
            }
            if (bestSlot < 0 || ties != 1 || bestWeight < 8) continue;
            long key = implementationKey(windows, target.slots().get(bestSlot));
            found.add(new StringAnchor(List.copyOf(sourceSlots),
                    List.copyOf(slotsByTarget.get(key)), bestShared));
        }
        found.sort(Comparator.comparingInt(StringAnchor::macSlot));
        return found;
    }

    private void reportStringMethodEvidence(Vtable source, Vtable target,
                                            List<StringAnchor> anchors) {
        println("    shared-string implementation anchors=" + anchors.size());
        for (StringAnchor anchor : anchors) {
            Slot from = source.slots().get(anchor.macSlot());
            Slot to = target.slots().get(anchor.windowsSlot());
            long targetUses = anchors.stream()
                    .filter(other -> other.windowsSlot() == anchor.windowsSlot()).count();
            println(String.format("      Mac%s %s -> Win%s %s %s | %s%s",
                    slotList(anchor.macSlots()), qualified(from.function()),
                    slotList(anchor.windowsSlots()),
                    to.target(), qualified(to.function()), summarizeStrings(anchor.strings()),
                    targetUses > 1 ? " | many-to-one x" + targetUses : ""));
        }
    }

    private Map<Long, List<Integer>> slotsByTarget(Program program, Vtable table, int firstSlot) {
        Map<Long, List<Integer>> out = new LinkedHashMap<>();
        for (int slot = firstSlot; slot < table.slots().size(); slot++) {
            Slot entry = table.slots().get(slot);
            long key = implementationKey(program, entry);
            out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(slot);
        }
        return out;
    }

    private long implementationKey(Program program, Slot slot) {
        Function implementation = resolveThunk(slot.function());
        return addressKey(program, implementation == null ? slot.target() :
                implementation.getEntryPoint());
    }

    private String slotList(List<Integer> slots) {
        return slots.stream().map(slot -> String.format("%02d", slot)).toList().toString();
    }

    private List<BsimAnchor> findBsimAnchors(Program mac, Program windows,
                                             Vtable source, Vtable target,
                                             List<NameAnchor> names,
                                             List<StringAnchor> strings)
            throws ghidra.util.exception.CancelledException {
        if (bsimMatcher == null || bsimMatcher.budgetExhausted()) return List.of();
        Set<Long> usedSources = new HashSet<>();
        Set<Long> usedTargets = new HashSet<>();
        for (NameAnchor anchor : names) {
            usedSources.add(implementationKey(mac, source.slots().get(anchor.macSlot())));
            usedTargets.add(implementationKey(windows,
                    target.slots().get(anchor.windowsSlot())));
        }
        for (StringAnchor anchor : strings) {
            usedSources.add(implementationKey(mac, source.slots().get(anchor.macSlot())));
            usedTargets.add(implementationKey(windows,
                    target.slots().get(anchor.windowsSlot())));
        }
        for (NameAnchor anchor : names)
            bsimMatcher.calibrate(source.slots().get(anchor.macSlot()).function(),
                    target.slots().get(anchor.windowsSlot()).function());
        for (StringAnchor anchor : strings)
            bsimMatcher.calibrate(source.slots().get(anchor.macSlot()).function(),
                    target.slots().get(anchor.windowsSlot()).function());

        Map<Long, List<Integer>> allSourceSlots = slotsByTarget(mac, source, 3);
        Map<Long, List<Integer>> allTargetSlots = slotsByTarget(windows, target, 3);
        Map<Long, Integer> targetSlots = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Integer>> entry : allTargetSlots.entrySet()) {
            if (usedTargets.contains(entry.getKey())) continue;
            int slot = entry.getValue().get(0);
            Function function = resolveThunk(target.slots().get(slot).function());
            if (function == null) continue;
            targetSlots.put(entry.getKey(), slot);
        }

        List<BsimAnchor> out = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> sourceEntry : allSourceSlots.entrySet()) {
            if (usedSources.contains(sourceEntry.getKey()) || bsimMatcher.budgetExhausted())
                continue;
            int macSlot = sourceEntry.getValue().get(0);
            Function sourceFunction = resolveThunk(source.slots().get(macSlot).function());
            if (sourceFunction == null) continue;
            BsimScore best = null;
            double secondSimilarity = Double.NEGATIVE_INFINITY;
            for (int windowsSlot : targetSlots.values()) {
                Function targetFunction = resolveThunk(target.slots().get(windowsSlot).function());
                BsimComparison comparison = bsimMatcher.compare(sourceFunction, targetFunction);
                if (comparison == null) continue;
                BsimScore score = new BsimScore(windowsSlot, comparison.similarity(),
                        comparison.significance());
                if (best == null || score.similarity() > best.similarity() ||
                        (score.similarity() == best.similarity() &&
                                score.significance() > best.significance())) {
                    if (best != null) secondSimilarity = Math.max(secondSimilarity, best.similarity());
                    best = score;
                } else {
                    secondSimilarity = Math.max(secondSimilarity, score.similarity());
                }
            }
            if (best == null) continue;
            double margin = secondSimilarity == Double.NEGATIVE_INFINITY
                    ? 1.0 : best.similarity() - secondSimilarity;
            if (best.similarity() >= MIN_BSIM_SIMILARITY &&
                    best.significance() >= MIN_BSIM_SIGNIFICANCE &&
                    margin >= MIN_BSIM_MARGIN) {
                long targetKey = implementationKey(windows,
                        target.slots().get(best.windowsSlot()));
                out.add(new BsimAnchor(List.copyOf(sourceEntry.getValue()),
                        List.copyOf(allTargetSlots.get(targetKey)), best.similarity(),
                        best.significance(), margin));
            }
        }
        out.sort(Comparator.comparingInt(BsimAnchor::macSlot));
        return out;
    }

    private void reportBsimMethodEvidence(Vtable source, Vtable target,
                                          List<BsimAnchor> anchors, String status) {
        if (bsimMatcher == null) return;
        println("    BSim anchors=" + anchors.size() +
                (status.equals("complete") ? "" : " (" + status + ")"));
        for (BsimAnchor anchor : anchors.stream().limit(MAX_BSIM_ANCHORS).toList()) {
            Slot from = source.slots().get(anchor.macSlot());
            Slot to = target.slots().get(anchor.windowsSlot());
            long targetUses = anchors.stream()
                    .filter(other -> other.windowsSlot() == anchor.windowsSlot()).count();
            println(String.format(Locale.ROOT,
                    "      Mac%s %s -> Win%s %s %s | sim=%.3f sig=%.1f margin=%.3f%s",
                    slotList(anchor.macSlots()), qualified(from.function()),
                    slotList(anchor.windowsSlots()),
                    to.target(), qualified(to.function()), anchor.similarity(),
                    anchor.significance(), anchor.margin(),
                    targetUses > 1 ? " | many-to-one x" + targetUses : ""));
        }
        if (anchors.size() > MAX_BSIM_ANCHORS)
            println("      ... BSim-anchor list truncated at " + MAX_BSIM_ANCHORS);
    }

    private List<SlotMapping> supportedOrderMappings(Vtable mac, Vtable windows,
                                                     List<NameAnchor> names,
                                                     List<StringAnchor> strings,
                                                     List<BsimAnchor> bsim) {
        List<SlotMapping> anchors = new ArrayList<>();
        anchors.add(new SlotMapping(2, 2)); // QI/AddRef/Release establish the common prefix.
        for (NameAnchor anchor : names)
            anchors.add(new SlotMapping(anchor.macSlot(), anchor.windowsSlot()));
        for (StringAnchor anchor : strings)
            if (anchor.uniqueMacSlot() && anchor.uniqueWindowsSlot())
                if (strings.stream().filter(other ->
                        other.windowsSlot() == anchor.windowsSlot()).count() == 1)
                    anchors.add(new SlotMapping(anchor.macSlot(), anchor.windowsSlot()));
        for (BsimAnchor anchor : bsim)
            if (anchor.uniqueMacSlot() && anchor.uniqueWindowsSlot())
                if (bsim.stream().filter(other ->
                        other.windowsSlot() == anchor.windowsSlot()).count() == 1)
                    anchors.add(new SlotMapping(anchor.macSlot(), anchor.windowsSlot()));
        return interpolateAnchors(anchors, mac.slots().size(), windows.slots().size());
    }

    private List<SlotMapping> interpolateAnchors(List<SlotMapping> input,
                                                 int macSize, int windowsSize) {
        List<SlotMapping> anchors = input.stream().distinct()
                .sorted(Comparator.comparingInt(SlotMapping::macSlot)
                        .thenComparingInt(SlotMapping::windowsSlot))
                .toList();

        Set<Integer> positiveMac = new HashSet<>();
        Set<Integer> positiveWindows = new HashSet<>();
        for (SlotMapping anchor : anchors) {
            positiveMac.add(anchor.macSlot());
            positiveWindows.add(anchor.windowsSlot());
        }
        Set<String> seen = new HashSet<>();
        List<SlotMapping> out = new ArrayList<>();
        for (int i = 1; i < anchors.size(); i++) {
            SlotMapping left = anchors.get(i - 1);
            SlotMapping right = anchors.get(i);
            int leftDelta = left.windowsSlot() - left.macSlot();
            int rightDelta = right.windowsSlot() - right.macSlot();
            if (leftDelta != rightDelta || right.macSlot() <= left.macSlot()) continue;
            for (int macSlot = left.macSlot() + 1; macSlot < right.macSlot(); macSlot++) {
                int windowsSlot = macSlot + leftDelta;
                if (macSlot < 3 || windowsSlot < 3 || macSlot >= macSize ||
                        windowsSlot >= windowsSize || positiveMac.contains(macSlot) ||
                        positiveWindows.contains(windowsSlot)) continue;
                String key = macSlot + ":" + windowsSlot;
                if (seen.add(key)) out.add(new SlotMapping(macSlot, windowsSlot));
            }
        }
        return out;
    }

    private void reportOrderDiagnostic(Vtable mac, Vtable windows, List<SlotMapping> mappings) {
        println("    anchor-bounded order roles (? = inferred only between stable anchors)=" +
                mappings.size());
        int lines = 0;
        for (SlotMapping mapping : mappings) {
            if (lines++ == MAX_ORDER_LINES) break;
            Slot source = mac.slots().get(mapping.macSlot());
            Slot target = windows.slots().get(mapping.windowsSlot());
            String sourceName = source.function() == null ? source.target().toString() : qualified(source.function());
            String targetName = target.function() == null ? "<no function>" : qualified(target.function());
            println(String.format("      Mac[%02d] %s? -> Win[%02d] %s %s",
                    mapping.macSlot(), sourceName, mapping.windowsSlot(), target.target(),
                    shorten(targetName, 38)));
        }
        if (mappings.size() > MAX_ORDER_LINES)
            println("      ... order diagnostic truncated at " + MAX_ORDER_LINES + " slots");
    }

    /**
     * Slot-to-method mapping taken straight from the class's header declaration.
     *
     * <p>Unlike every other anchor this needs no Mac correspondence at all: the
     * header states the order directly. It is only used when the observed table
     * is long enough to hold the declared methods and when no existing Windows
     * name contradicts it -- a contradiction means the class identity or the
     * base chain is wrong, and applying the rest of the mapping would spray
     * confident wrong names across the whole table.
     */
    /**
     * Which header interface describes this class's primary vtable.
     *
     * <p>The headers declare only pure-virtual interfaces, while the classes
     * reaching this point are concrete implementations, so the two never share
     * a name and matching on the class name alone finds nothing. The bridge is
     * the QI fingerprint: each id maps back to the `GZIID_<interface>` constant
     * that defined it, naming an interface the class implements.
     *
     * <p>A class usually implements several, and only its first base shares the
     * primary vtable, so the candidate is chosen by agreement with the Mac
     * vtable's own method names -- evidence that is independent of anything on
     * the Windows side.
     */
    private List<String> headerInterfaceCandidates(ClassMatch match) {
        Set<String> names = new LinkedHashSet<>();
        if (!headerVtableOrder(match.mac().name()).isEmpty()) names.add(match.mac().name());
        for (long id : nonTrivial(match.mac().ids())) {
            String constant = knownGzids.get(id);
            if (constant == null) continue;
            int underscore = constant.indexOf('_');
            if (underscore < 0) continue;
            String candidate = constant.substring(underscore + 1);
            if (!headerVtableOrder(candidate).isEmpty()) names.add(candidate);
        }
        return new ArrayList<>(names);
    }

    /**
     * Agreement between a declared order and the Mac vtable's own symbol names:
     * the count of slots that match, or -1 if any slot contradicts.
     */
    private int headerAgreementWithMac(Vtable macTable, List<String> order) {
        int agreed = 0;
        int limit = Math.min(order.size(), macTable.slots().size());
        for (int slot = 3; slot < limit; slot++) {
            String observed = meaningfulMethodName(macTable.slots().get(slot).function());
            if (observed == null) continue;
            if (!observed.equals(order.get(slot))) return -1;
            agreed++;
        }
        return agreed;
    }

    /**
     * Slot-to-method mapping taken from the class's declared interface.
     *
     * <p>Only applied when a single candidate interface is corroborated by the
     * Mac vtable, the observed Windows table is long enough to hold the
     * declared methods, and no existing Windows name contradicts it. A
     * contradiction means the identity or base chain is wrong, and applying the
     * remainder would spray confident wrong names across the whole table.
     */
    private List<HeaderAnchor> headerAnchors(Vtable macTable, Vtable target, ClassMatch match,
                                             List<NameAnchor> names) {
        String chosen = null;
        List<String> chosenOrder = List.of();
        int best = -1;
        boolean tied = false;
        for (String candidate : headerInterfaceCandidates(match)) {
            List<String> order = headerVtableOrder(candidate);
            if (order.size() < 3 || target.slots().size() < order.size()) continue;
            int agreement = headerAgreementWithMac(macTable, order);
            if (agreement < MIN_HEADER_AGREEMENT) continue;
            if (agreement > best) {
                best = agreement;
                chosen = candidate;
                chosenOrder = order;
                tied = false;
            } else if (agreement == best) {
                tied = true;
            }
        }
        if (chosen == null || tied) return List.of();
        for (NameAnchor anchor : names) {
            if (anchor.windowsSlot() >= chosenOrder.size()) continue;
            if (!chosenOrder.get(anchor.windowsSlot()).equals(leafName(anchor.method())))
                return List.of();
        }
        List<HeaderAnchor> out = new ArrayList<>();
        for (int slot = 3; slot < chosenOrder.size(); slot++)
            out.add(new HeaderAnchor(slot, chosen + "::" + chosenOrder.get(slot)));
        return out;
    }

    private void collectProposedRoles(Program windows, ClassMatch match, Vtable mac,
                                      Vtable target, List<NameAnchor> names,
                                      List<StringAnchor> strings, List<BsimAnchor> bsim) {
        MacClass clazz = match.mac();
        for (HeaderAnchor anchor : headerAnchors(mac, target, match, names)) {
            Address targetAddress = target.slots().get(anchor.slot()).target();
            addProposedRole(windows, targetAddress, new ProposedRole(clazz.name(),
                    List.of(anchor.slot()), anchor.method(), "header",
                    match.confidence(), targetAddress));
        }
        for (NameAnchor anchor : names) {
            Address targetAddress = target.slots().get(anchor.windowsSlot()).target();
            addProposedRole(windows, targetAddress,
                    role(clazz, anchor.macSlot(), mac.slots().get(anchor.macSlot()).function(),
                            "name", match.confidence(), targetAddress));
        }
        for (StringAnchor anchor : strings) {
            Address targetAddress = target.slots().get(anchor.windowsSlot()).target();
            // A Windows implementation claimed by more than one Mac method is
            // ambiguous even when each anchor is individually unique. Slot
            // comments already accounted for this; role evidence did not, so a
            // many-to-one match could still apply an exact primary name.
            long targetUses = strings.stream()
                    .filter(other -> other.windowsSlot() == anchor.windowsSlot()).count();
            String evidence = anchor.uniqueMacSlot() && anchor.uniqueWindowsSlot() &&
                    targetUses == 1 ? "string" : "string?";
            addProposedRole(windows, targetAddress,
                    role(clazz, anchor.macSlots(), mac.slots().get(anchor.macSlot()).function(),
                            evidence, match.confidence(), targetAddress));
        }
        for (BsimAnchor anchor : bsim) {
            Address targetAddress = target.slots().get(anchor.windowsSlot()).target();
            addProposedRole(windows, targetAddress,
                    role(clazz, anchor.macSlots(), mac.slots().get(anchor.macSlot()).function(),
                            "bsim", match.confidence(), targetAddress));
        }
        for (SlotMapping mapping : supportedOrderMappings(mac, target, names, strings, bsim)) {
            Address targetAddress = target.slots().get(mapping.windowsSlot()).target();
            addProposedRole(windows, targetAddress,
                    role(clazz, List.of(mapping.macSlot()),
                            mac.slots().get(mapping.macSlot()).function(),
                            "order", match.confidence(), targetAddress));
        }
    }

    private ProposedRole role(MacClass clazz, int slot, Function function, String evidence,
                              String confidence, Address slotTarget) {
        return role(clazz, List.of(slot), function, evidence, confidence, slotTarget);
    }

    private ProposedRole role(MacClass clazz, List<Integer> slots, Function function,
                              String evidence, String confidence, Address slotTarget) {
        return new ProposedRole(clazz.name(), slots, qualified(function), evidence, confidence,
                slotTarget);
    }

    private void addProposedRole(Program windows, Address address, ProposedRole role) {
        String key = role.owner() + "\u0000" + role.slots() + "\u0000" + role.method();
        Function slotFunction = windows.getFunctionManager().getFunctionAt(address);
        Function implementation = resolveThunk(slotFunction);
        Address implementationAddress = implementation == null ? address :
                implementation.getEntryPoint();
        proposedRoles.computeIfAbsent(addressKey(windows, implementationAddress),
                        ignored -> new TreeMap<>())
                .merge(key, role, (oldRole, newRole) ->
                        evidenceRank(newRole.evidence()) > evidenceRank(oldRole.evidence())
                                ? newRole : oldRole);
    }

    private void reportSharedRoleCandidates(Program windows) {
        List<Map.Entry<Long, Map<String, ProposedRole>>> sameRole = proposedRoles.entrySet().stream()
                .filter(entry -> roleGroups(entry.getValue().values()).values().stream()
                        .anyMatch(group -> group.size() > 1))
                .sorted((left, right) -> Long.compareUnsigned(left.getKey(), right.getKey()))
                .toList();
        println("\nWindows implementations shared by the same prospective Mac method=" +
                sameRole.size());
        int count = 0;
        for (Map.Entry<Long, Map<String, ProposedRole>> entry : sameRole) {
            if (count++ == MAX_SHARED_ROLE_REPORTS) break;
            List<ProposedRole> roles = roleGroups(entry.getValue().values()).values().stream()
                    .filter(group -> group.size() > 1).flatMap(Collection::stream).toList();
            reportRoleEntry(windows, entry.getKey(), roles);
        }
        if (sameRole.size() > MAX_SHARED_ROLE_REPORTS)
            println("  ... same-role report truncated at " +
                    MAX_SHARED_ROLE_REPORTS + " entries");

        List<Map.Entry<Long, Map<String, ProposedRole>>> folded = proposedRoles.entrySet().stream()
                .filter(entry -> roleGroups(entry.getValue().values()).size() > 1)
                .filter(entry -> positivelyAnchoredMethodCount(entry.getValue().values()) >= 2)
                .sorted((left, right) -> Long.compareUnsigned(left.getKey(), right.getKey()))
                .toList();
        println("\nWindows implementations with different prospective roles " +
                "and independent positive evidence=" + folded.size());
        count = 0;
        for (Map.Entry<Long, Map<String, ProposedRole>> entry : folded) {
            if (count++ == MAX_SHARED_ROLE_REPORTS) break;
            reportRoleEntry(windows, entry.getKey(), new ArrayList<>(entry.getValue().values()));
        }
        if (folded.size() > MAX_SHARED_ROLE_REPORTS)
            println("  ... folded-role report truncated at " +
                    MAX_SHARED_ROLE_REPORTS + " entries");
    }

    private Map<String, List<ProposedRole>> roleGroups(Collection<ProposedRole> roles) {
        Map<String, List<ProposedRole>> out = new TreeMap<>();
        for (ProposedRole role : roles)
            out.computeIfAbsent(role.method(), ignored -> new ArrayList<>()).add(role);
        return out;
    }

    private long positivelyAnchoredMethodCount(Collection<ProposedRole> roles) {
        return roleGroups(roles).values().stream()
                .filter(group -> group.stream().anyMatch(role ->
                        !role.evidence().equals("order")))
                .count();
    }

    private void reportRoleEntry(Program windows, long key, List<ProposedRole> roles) {
        Address address = windows.getAddressFactory().getDefaultAddressSpace().getAddress(key);
        Function function = windows.getFunctionManager().getFunctionAt(address);
        println("  " + address + " " + qualified(function) + " | roles=" + roles.size());
        List<ProposedRole> sorted = new ArrayList<>(roles);
        sorted.sort(Comparator.comparing(ProposedRole::method)
                .thenComparing(ProposedRole::owner)
                .thenComparing(role -> role.slots().toString()));
        for (int i = 0; i < Math.min(sorted.size(), 12); i++) {
            ProposedRole role = sorted.get(i);
            println("    " + evidenceMarker(role.evidence()) + " " + role.owner() +
                    slotList(role.slots()) + " " + role.method() +
                    (role.slotTarget().equals(address) ? "" : " | via " + role.slotTarget()));
        }
        if (sorted.size() > 12) println("    ... role list truncated at 12");
    }

    private int evidenceRank(String evidence) {
        return switch (evidence) {
            case "gziid" -> 5;
            case "name" -> 4;
            case "header" -> 3;
            case "string", "bsim" -> 2;
            default -> 1;
        };
    }

    private String evidenceMarker(String evidence) {
        return switch (evidence) {
            case "gziid" -> "G";
            case "name" -> "=";
            case "header" -> "H";
            case "string" -> "~";
            case "string?" -> "~?";
            case "bsim" -> "b";
            default -> "?";
        };
    }

    private void offerApply(Program windows, Pattern classFilter) throws Exception {
        Set<Address> stableSlotTargets = stableVtableSlotTargets();
        List<Address> missingTargets = stableSlotTargets.stream()
                .filter(address -> windows.getFunctionManager().getFunctionAt(address) == null)
                .toList();
        List<Address> creatableTargets = missingTargets.stream()
                .filter(address -> canCreateVtableFunction(windows, address))
                .toList();
        println("\nApply candidates: class-vtable proposals=" + classProposals.size() +
                ", function implementations=" + proposedRoles.size() +
                ", data=" + dataProposals.size() +
                ", missing vtable functions=" + missingTargets.size() +
                " (creatable=" + creatableTargets.size() + ")");
        for (Address address : creatableTargets.stream().limit(40).toList())
            println("  create function " + address + " | " +
                    symbolText(windows.getSymbolTable().getPrimarySymbol(address)));
        if (creatableTargets.size() > 40)
            println("  ... function-creation preview truncated at 40 entries");
        boolean apply = askYesNo("Apply conservative Windows annotations?",
                "Target: " + windows.getDomainFile().getPathname() + "\n" +
                "Class filter: " + classFilter.pattern() + "\n\n" +
                "Apply placeholder-only names, create missing LAB_* vtable functions, " +
                "non-primary folded-role labels, and SC4Mac comments?\n" +
                "Inferred Mac method names end in ?; unmapped stable slots use Class::vfuncNN.\n" +
                "Shared implementations keep per-role aliases instead of an arbitrary primary name.\n" +
                "Meaningful USER_DEFINED names are protected; FUN_*, LAB_*, and the entire " +
                        "OOAnalyzer namespace are replaceable.\n" +
                "Manual comments are preserved; SC4Mac blocks are appended beside them.\n" +
                "The program will not be saved automatically.");
        if (!apply) {
            println("\nPreview complete. No program changes were made.");
            return;
        }

        int transaction = windows.startTransaction("Apply SC4 Mac cross-build annotations");
        boolean commit = false;
        ApplyStats stats = new ApplyStats();
        try {
            applyVtableProposals(windows, stats);
            applyMissingVtableFunctions(windows, stableSlotTargets.size(),
                    missingTargets.size(), creatableTargets, stats);
            applyFunctionProposals(windows, stats);
            applyDataProposals(windows, stats);
            commit = true;
        } catch (Exception e) {
            println("\nApply failed; transaction rolled back: " + e.getMessage());
            throw e;
        } finally {
            windows.endTransaction(transaction, commit);
        }
        println("\nApply complete: function implementations=" + stats.functionCandidates +
                ", primary names=" + stats.functionNames +
                ", folded/protected role labels=" + stats.roleLabels +
                ", ambiguous implementations=" + stats.ambiguousImplementations +
                ", comment-only implementations=" + stats.commentOnlyImplementations +
                ", stable vtable slot targets=" + stats.vtableSlotTargets +
                ", missing functions=" + stats.missingFunctionTargets +
                ", functions created=" + stats.functionsCreated +
                ", function creation skipped=" + stats.functionCreationSkipped +
                ", vtable groups=" + stats.vtableGroups +
                ", unique-class vtables=" + stats.vtableUnique +
                ", vtable labels already present=" + stats.vtableAlreadyPresent +
                ", vtable labels added/promoted=" + stats.vtableLabels +
                ", ambiguous/family vtables=" + stats.vtableAmbiguous +
                ", candidate comments added/updated=" + stats.vtableCandidateComments +
                ", vtable rejected=" + stats.vtableRejected +
                ", data labels=" + stats.dataLabels +
                ", comments=" + stats.comments +
                ", protected/skipped=" + stats.protectedItems);
        println("Changes are in the Windows program database. Review them, then save manually.");
        log("");
        log("=== APPLY SUMMARY ===");
        log("apply.functionCandidates=" + stats.functionCandidates);
        log("apply.functionNames=" + stats.functionNames);
        log("apply.roleLabels=" + stats.roleLabels);
        log("apply.ambiguousImplementations=" + stats.ambiguousImplementations);
        log("apply.commentOnlyImplementations=" + stats.commentOnlyImplementations);
        log("apply.vtableSlotTargets=" + stats.vtableSlotTargets);
        log("apply.missingFunctionTargets=" + stats.missingFunctionTargets);
        log("apply.functionsCreated=" + stats.functionsCreated);
        log("apply.functionCreationSkipped=" + stats.functionCreationSkipped);
        log("apply.vtableGroups=" + stats.vtableGroups);
        log("apply.vtableUnique=" + stats.vtableUnique);
        log("apply.vtableAlreadyPresent=" + stats.vtableAlreadyPresent);
        log("apply.vtableLabels=" + stats.vtableLabels);
        log("apply.vtableAmbiguous=" + stats.vtableAmbiguous);
        log("apply.vtableCandidateComments=" + stats.vtableCandidateComments);
        log("apply.vtableRejected=" + stats.vtableRejected);
        log("apply.dataLabels=" + stats.dataLabels);
        log("apply.comments=" + stats.comments);
        log("apply.protectedItems=" + stats.protectedItems);
        log("=== END APPLY SUMMARY ===");
    }

    private void applyVtableProposals(Program windows, ApplyStats stats) throws Exception {
        Map<Long, List<ClassProposal>> byTable = new TreeMap<>(Long::compareUnsigned);
        for (ClassProposal proposal : classProposals)
            byTable.computeIfAbsent(addressKey(windows, proposal.table().start()),
                    ignored -> new ArrayList<>()).add(proposal);

        for (List<ClassProposal> group : byTable.values()) {
            stats.vtableGroups++;
            List<ClassProposal> stable = group.stream().filter(this::stableClassMatch).toList();
            Set<String> names = stable.stream().map(p -> p.match().mac().name())
                    .collect(TreeSet::new, Set::add, Set::addAll);
            if (names.size() != 1) {
                stats.vtableAmbiguous++;
                ClassProposal first = group.get(0);
                Set<String> candidates = group.stream()
                        .map(p -> p.match().mac().name() + " [" +
                                p.match().confidence() + "]")
                        .collect(TreeSet::new, Set::add, Set::addAll);
                String candidateText = String.join(", ", candidates.stream().limit(8).toList());
                if (candidates.size() > 8)
                    candidateText += ", ... +" + (candidates.size() - 8);
                String plate = "SC4Mac vtable candidates:\n" +
                        "Classes: " + candidateText + "\n" +
                        "Slots: " + first.table().table().slots().size() + " | stop=" +
                        first.table().table().extentStop() + "\n" +
                        "Reason: QI/table shared or class identity ambiguous";
                if (setOwnedComment(windows, CommentType.PLATE, first.table().start(),
                        "SC4Mac vtable", plate, stats))
                    stats.vtableCandidateComments++;
                continue;
            }
            stats.vtableUnique++;
            String className = names.iterator().next();
            ClassProposal proposal = stable.stream()
                    .filter(p -> p.match().mac().name().equals(className)).findFirst().orElseThrow();
            Address tableAddress = proposal.table().start();
            boolean present = hasNamespacedLabel(windows, tableAddress, className, "vtable");
            if (present) stats.vtableAlreadyPresent++;
            if (ensureNamespacedLabel(windows, tableAddress, className, "vtable", stats))
                stats.vtableLabels++;
            else if (!present) stats.vtableRejected++;

            TableEvidence evidence = proposal.table();
            String plate = "SC4Mac vtable: " + className + "\n" +
                    "QI: " + proposal.match().confidence() + " | GZIID=" +
                    ids(proposal.match().mac().ids()) + "\n" +
                    "Slots: " + evidence.table().slots().size() + " | stop=" +
                    evidence.table().extentStop() + "\n" +
                    "Anchors: name=" + evidence.anchors().size() +
                    " string=" + evidence.stringAnchors().size() +
                    " BSim=" + evidence.bsimAnchors().size() +
                    " order?=" + proposal.order().size();
            setOwnedComment(windows, CommentType.PLATE, tableAddress,
                    "SC4Mac vtable", plate, stats);
            applySlotComments(windows, proposal, stats);
        }
    }

    private boolean stableClassMatch(ClassProposal proposal) {
        return switch (proposal.match().confidence()) {
            case "confirmed", "strong", "single-id" -> true;
            default -> false;
        };
    }

    private Set<Address> stableVtableSlotTargets() {
        Set<Address> targets = new TreeSet<>();
        for (ClassProposal proposal : classProposals) {
            if (!stableClassMatch(proposal)) continue;
            for (Slot slot : proposal.table().table().slots()) targets.add(slot.target());
        }
        return targets;
    }

    private boolean canCreateVtableFunction(Program program, Address address) {
        FunctionManager functions = program.getFunctionManager();
        if (functions.getFunctionAt(address) != null ||
                functions.getFunctionContaining(address) != null) return false;
        if (program.getListing().getInstructionAt(address) == null) return false;
        return replaceablePrimary(program.getSymbolTable().getPrimarySymbol(address));
    }

    private void applyMissingVtableFunctions(Program windows, int slotTargetCount,
                                             int missingTargetCount,
                                             List<Address> creatableTargets,
                                             ApplyStats stats) throws Exception {
        stats.vtableSlotTargets = slotTargetCount;
        stats.missingFunctionTargets = missingTargetCount;
        stats.functionCreationSkipped = missingTargetCount - creatableTargets.size();
        for (Address address : creatableTargets) {
            monitor.checkCancelled();
            if (!canCreateVtableFunction(windows, address)) {
                stats.functionCreationSkipped++;
                continue;
            }
            CreateFunctionCmd command = new CreateFunctionCmd(null, address, null,
                    SourceType.ANALYSIS);
            if (command.applyTo(windows, monitor) && command.getFunction() != null)
                stats.functionsCreated++;
            else
                stats.functionCreationSkipped++;
        }
    }

    private void applySlotComments(Program windows, ClassProposal proposal, ApplyStats stats) {
        MacClass clazz = proposal.match().mac();
        Vtable mac = clazz.primary();
        TableEvidence evidence = proposal.table();
        Map<Integer, SlotNote> notes = new TreeMap<>();

        for (HeaderAnchor anchor : evidence.headerAnchors())
            putSlotNote(notes, anchor.slot(), 5, "SC4Mac slot: Win[" +
                    String.format("%02d", anchor.slot()) + "] " +
                    shorten(anchor.method(), 88) + " | header declaration order");
        for (NameAnchor anchor : evidence.anchors()) {
            String method = qualified(mac.slots().get(anchor.macSlot()).function());
            putSlotNote(notes, anchor.windowsSlot(), 4,
                    slotNote(anchor.windowsSlot(), List.of(anchor.macSlot()), method, "name", false));
        }
        for (StringAnchor anchor : evidence.stringAnchors()) {
            String method = qualified(mac.slots().get(anchor.macSlot()).function());
            long targetUses = evidence.stringAnchors().stream().filter(other ->
                    other.windowsSlot() == anchor.windowsSlot()).count();
            boolean uncertain = !anchor.uniqueMacSlot() || !anchor.uniqueWindowsSlot() ||
                    targetUses > 1;
            for (int slot : anchor.windowsSlots())
                putSlotNote(notes, slot, 3,
                        slotNote(slot, anchor.macSlots(), method, "string", uncertain));
        }
        for (BsimAnchor anchor : evidence.bsimAnchors()) {
            String method = qualified(mac.slots().get(anchor.macSlot()).function());
            long targetUses = evidence.bsimAnchors().stream().filter(other ->
                    other.windowsSlot() == anchor.windowsSlot()).count();
            boolean uncertain = !anchor.uniqueMacSlot() || !anchor.uniqueWindowsSlot() ||
                    targetUses > 1;
            String source = String.format(Locale.ROOT, "BSim %.3f/%.1f",
                    anchor.similarity(), anchor.significance());
            for (int slot : anchor.windowsSlots())
                putSlotNote(notes, slot, 2,
                        slotNote(slot, anchor.macSlots(), method, source, uncertain));
        }
        for (SlotMapping mapping : proposal.order()) {
            String method = qualified(mac.slots().get(mapping.macSlot()).function());
            putSlotNote(notes, mapping.windowsSlot(), 1,
                    slotNote(mapping.windowsSlot(), List.of(mapping.macSlot()), method,
                            "order", true));
        }

        int ptr = windows.getDefaultPointerSize();
        for (Map.Entry<Integer, SlotNote> entry : notes.entrySet()) {
            Address address = evidence.start().add((long)entry.getKey() * ptr);
            setOwnedComment(windows, CommentType.EOL, address, "SC4Mac slot:",
                    entry.getValue().text(), stats);
        }
    }

    private String slotNote(int windowsSlot, List<Integer> macSlots, String method,
                            String evidence, boolean uncertain) {
        return "SC4Mac slot: Win[" + String.format("%02d", windowsSlot) + "] <- Mac" +
                slotList(macSlots) + " " + shorten(method, 88) + (uncertain ? "?" : "") +
                " | " + evidence;
    }

    private void putSlotNote(Map<Integer, SlotNote> notes, int slot, int rank, String text) {
        SlotNote old = notes.get(slot);
        if (old == null || rank > old.rank()) notes.put(slot, new SlotNote(rank, text));
    }

    private void applyFunctionProposals(Program windows, ApplyStats stats) throws Exception {
        Map<Long, Map<String, ProposedRole>> all = new TreeMap<>(Long::compareUnsigned);
        for (Map.Entry<Long, Map<String, ProposedRole>> entry : proposedRoles.entrySet())
            all.put(entry.getKey(), new TreeMap<>(entry.getValue()));

        for (ClassProposal proposal : classProposals) {
            if (!stableClassMatch(proposal)) continue;
            MacClass clazz = proposal.match().mac();
            Function windowsQi = proposal.match().windows().function();
            Function implementation = resolveThunk(windowsQi);
            Address implementationAddress = implementation == null ? windowsQi.getEntryPoint() :
                    implementation.getEntryPoint();
            ProposedRole role = new ProposedRole(clazz.name(), List.of(0),
                    qualified(clazz.queryInterface()), "gziid",
                    proposal.match().confidence(), windowsQi.getEntryPoint());
            mergeRole(all, addressKey(windows, implementationAddress), role);

            Set<Integer> mapped = mappedWindowsSlots(proposal);
            for (int slot = 1; slot < proposal.table().table().slots().size(); slot++) {
                if (mapped.contains(slot)) continue;
                Address target = proposal.table().table().slots().get(slot).target();
                Function slotFunction = windows.getFunctionManager().getFunctionAt(target);
                Function resolved = resolveThunk(slotFunction);
                Address resolvedAddress = resolved == null ? target : resolved.getEntryPoint();
                mergeRole(all, addressKey(windows, resolvedAddress), new ProposedRole(
                        clazz.name(), List.of(slot), clazz.name() + "::vfunc" +
                        String.format("%02d", slot), "slot", proposal.match().confidence(), target));
            }
        }

        for (Map.Entry<Long, Map<String, ProposedRole>> entry : all.entrySet()) {
            stats.functionCandidates++;
            Address address = windows.getAddressFactory().getDefaultAddressSpace()
                    .getAddress(entry.getKey());
            Collection<ProposedRole> roles = entry.getValue().values();
            Map<String, List<ProposedRole>> groups = roleGroups(roles);
            if (groups.size() > 1) stats.ambiguousImplementations++;
            Function function = windows.getFunctionManager().getFunctionAt(address);
            boolean named = false;
            if (function != null && groups.size() == 1) {
                Map.Entry<String, List<ProposedRole>> group = groups.entrySet().iterator().next();
                String desired = appliedPrimaryName(group.getKey(), group.getValue());
                if (desired != null && !desired.startsWith("<") &&
                        renameFunctionQualified(windows, function, desired, stats)) {
                    stats.functionNames++;
                    // Every applied name recorded with the evidence behind it,
                    // so a wrong name found later can be traced to the rule
                    // that produced it rather than guessed at.
                    ProposedRole strongest = group.getValue().stream()
                            .max(Comparator.comparingInt(r -> evidenceRank(r.evidence())))
                            .orElse(null);
                    log("RENAME|addr=" + address + "|name=" + desired +
                            "|evidence=" + (strongest == null ? "?" : strongest.evidence()) +
                            "|conf=" + (strongest == null ? "?" : strongest.confidence()) +
                            "|roles=" + entry.getValue().size());
                }
                named = qualified(function).equals(desired);
            }
            boolean labeled = false;
            for (Map.Entry<String, List<ProposedRole>> group : groups.entrySet()) {
                if (!aliasEvidence(group.getValue())) continue;
                for (String desired : appliedAliasNames(group.getKey(), group.getValue())) {
                    if (desired.startsWith("<") || (named && groups.size() == 1 &&
                            qualified(function).equals(desired))) continue;
                    if (ensureQualifiedAlias(windows, address, desired, stats)) {
                        stats.roleLabels++;
                        labeled = true;
                    }
                }
            }
            if (!named && !labeled) stats.commentOnlyImplementations++;
            setOwnedComment(windows, CommentType.REPEATABLE, address, "SC4Mac roles:",
                    roleComment(roles), stats);
        }
    }

    private Set<Integer> mappedWindowsSlots(ClassProposal proposal) {
        Set<Integer> out = new HashSet<>();
        out.add(0);
        for (HeaderAnchor anchor : proposal.table().headerAnchors())
            out.add(anchor.slot());
        for (NameAnchor anchor : proposal.table().anchors()) out.add(anchor.windowsSlot());
        for (StringAnchor anchor : proposal.table().stringAnchors()) out.addAll(anchor.windowsSlots());
        for (BsimAnchor anchor : proposal.table().bsimAnchors()) out.addAll(anchor.windowsSlots());
        for (SlotMapping mapping : proposal.order()) out.add(mapping.windowsSlot());
        return out;
    }

    private String appliedPrimaryName(String desired, List<ProposedRole> roles) {
        if (desired.startsWith("<")) return desired;
        if (primaryNameEvidence(roles) || roles.stream().anyMatch(role ->
                stableRole(role) && role.evidence().equals("name"))) return desired;
        ProposedRole strongest = roles.stream().filter(this::stableRole)
                .max(Comparator.comparingInt(role -> evidenceRank(role.evidence()))).orElse(null);
        if (strongest == null) return null;
        if (strongest.evidence().equals("slot")) return desired;
        Set<String> inferred = roles.stream().filter(this::stableRole)
                .map(this::inferredRoleName).collect(TreeSet::new, Set::add, Set::addAll);
        return inferred.size() == 1 ? inferred.iterator().next() : uncertain(desired);
    }

    private Set<String> appliedAliasNames(String desired, List<ProposedRole> roles) {
        if (primaryNameEvidence(roles) || roles.stream().anyMatch(role ->
                stableRole(role) && role.evidence().equals("name"))) return Set.of(desired);
        return roles.stream().filter(this::stableRole).map(this::inferredRoleName)
                .collect(TreeSet::new, Set::add, Set::addAll);
    }

    private String inferredRoleName(ProposedRole role) {
        if (role.evidence().equals("slot")) return role.method();
        return uncertain(role.owner() + "::" + leafName(role.method()));
    }

    private String uncertain(String name) {
        return name.endsWith("?") ? name : name + "?";
    }

    private boolean primaryNameEvidence(List<ProposedRole> roles) {
        // A header mapping names the method outright, so it carries an exact
        // name rather than an inferred one -- headerAnchors already refused to
        // emit anything for a table that contradicts a known Windows name.
        return roles.stream().anyMatch(role -> stableRole(role) &&
                (role.evidence().equals("gziid") || role.evidence().equals("string") ||
                        role.evidence().equals("header")));
    }

    private boolean aliasEvidence(List<ProposedRole> roles) {
        return roles.stream().anyMatch(this::stableRole);
    }

    private boolean stableRole(ProposedRole role) {
        return role.confidence().equals("confirmed") || role.confidence().equals("strong") ||
                role.confidence().equals("single-id");
    }

    private void mergeRole(Map<Long, Map<String, ProposedRole>> roles, long address,
                           ProposedRole role) {
        String key = role.owner() + "\u0000" + role.slots() + "\u0000" + role.method();
        roles.computeIfAbsent(address, ignored -> new TreeMap<>())
                .merge(key, role, (oldRole, newRole) ->
                        evidenceRank(newRole.evidence()) > evidenceRank(oldRole.evidence())
                                ? newRole : oldRole);
    }

    private String roleComment(Collection<ProposedRole> roles) {
        StringBuilder text = new StringBuilder("SC4Mac roles:");
        int lines = 0;
        for (Map.Entry<String, List<ProposedRole>> entry : roleGroups(roles).entrySet()) {
            if (lines++ == 12) {
                text.append("\n... +").append(roleGroups(roles).size() - 12);
                break;
            }
            List<ProposedRole> group = entry.getValue();
            ProposedRole strongest = group.stream().max(Comparator.comparingInt(role ->
                    evidenceRank(role.evidence()))).orElseThrow();
            List<String> owners = group.stream()
                    .map(role -> role.owner() + slotList(role.slots()) +
                            (stableRole(role) ? "" : "/" + role.confidence() + "?"))
                    .distinct().sorted().toList();
            String ownerText = String.join(", ", owners.stream().limit(6).toList());
            if (owners.size() > 6) ownerText += ", ... +" + (owners.size() - 6);
            text.append("\n").append(evidenceLabel(strongest.evidence())).append(" ")
                    .append(entry.getKey()).append(" <- ").append(ownerText);
        }
        return text.toString();
    }

    private String evidenceLabel(String evidence) {
        return switch (evidence) {
            case "gziid" -> "GZIID";
            case "name" -> "name";
            case "header" -> "header";
            case "string" -> "string";
            case "string?" -> "string?";
            case "bsim" -> "BSim";
            default -> "order?";
        };
    }

    private void applyDataProposals(Program windows, ApplyStats stats) throws Exception {
        for (DataProposal proposal : dataProposals) {
            String sourceName = proposal.source().name().replaceFirst("^Global::", "");
            String label = safeLabel(sourceName);
            if (ensureFlatLabel(windows, proposal.target().address(), label, stats))
                stats.dataLabels++;
            String comment = "SC4Mac data: " + sourceName + " | unique string \"" +
                    shorten(proposal.source().text().replaceAll("\\s+", " "), 72) + "\"";
            setOwnedComment(windows, CommentType.EOL, proposal.target().address(),
                    "SC4Mac data:", comment, stats);
        }
    }

    private boolean renameFunctionQualified(Program program, Function function, String desired,
                                            ApplyStats stats) throws Exception {
        if (qualified(function).equals(desired)) return false;
        Symbol symbol = function.getSymbol();
        if (!replaceablePrimary(symbol)) {
            stats.protectedItems++;
            return false;
        }
        List<String> parts = splitCppScopes(desired);
        if (parts.isEmpty()) return false;
        String name = parts.get(parts.size() - 1);
        if (!validSymbolComponent(name)) {
            stats.protectedItems++;
            return false;
        }
        Namespace namespace = getOrCreateNamespacePath(program,
                parts.subList(0, parts.size() - 1));
        if (namespace == null) {
            stats.protectedItems++;
            return false;
        }
        for (Symbol existing : program.getSymbolTable().getSymbols(function.getEntryPoint())) {
            if (existing == symbol || !existing.getName().equals(name) ||
                    !existing.getParentNamespace().equals(namespace)) continue;
            if (!replaceableRenameCollision(existing.getSource(), existing.getSymbolType()) ||
                    !existing.delete()) {
                stats.protectedItems++;
                return false;
            }
        }
        try {
            symbol.setNameAndNamespace(name, namespace, SourceType.ANALYSIS);
            return true;
        } catch (DuplicateNameException ignored) {
            stats.protectedItems++;
            return false;
        }
    }

    private boolean replaceableRenameCollision(SourceType source, SymbolType type) {
        return source == SourceType.ANALYSIS && type == SymbolType.LABEL;
    }

    private boolean ensureQualifiedAlias(Program program, Address address, String desired,
                                         ApplyStats stats) throws Exception {
        for (Symbol symbol : program.getSymbolTable().getSymbols(address))
            if (symbol.getName(true).equals(desired)) return false;
        List<String> parts = splitCppScopes(desired);
        if (parts.isEmpty()) return false;
        String name = parts.get(parts.size() - 1);
        if (!validSymbolComponent(name)) {
            stats.protectedItems++;
            return false;
        }
        Namespace namespace = getOrCreateNamespacePath(program,
                parts.subList(0, parts.size() - 1));
        if (namespace == null) {
            stats.protectedItems++;
            return false;
        }
        program.getSymbolTable().createLabel(address, name, namespace, SourceType.ANALYSIS);
        return true;
    }

    private boolean hasNamespacedLabel(Program program, Address address, String namespaceName,
                                       String name) {
        Namespace namespace = findNamespacePath(program, splitCppScopes(namespaceName));
        if (namespace == null) return false;
        for (Symbol symbol : program.getSymbolTable().getSymbols(address))
            if (symbol.getName().equals(name) &&
                    symbol.getParentNamespace().equals(namespace)) return true;
        return false;
    }

    private boolean ensureNamespacedLabel(Program program, Address address, String namespaceName,
                                          String name, ApplyStats stats) throws Exception {
        Symbol primary = program.getSymbolTable().getPrimarySymbol(address);
        List<String> parts = splitCppScopes(namespaceName);
        Namespace namespace = findNamespacePath(program, parts);
        if (namespace != null) {
            for (Symbol symbol : program.getSymbolTable().getSymbols(address)) {
                if (symbol.getName().equals(name) &&
                        symbol.getParentNamespace().equals(namespace)) {
                    if (symbol.isPrimary()) return false;
                    if (!replaceablePrimary(primary)) {
                        stats.protectedItems++;
                        return false;
                    }
                    symbol.setPrimary();
                    return true;
                }
            }
        }
        if (!replaceablePrimary(primary)) {
            stats.protectedItems++;
            return false;
        }
        namespace = getOrCreateNamespacePath(program, parts);
        if (namespace == null) {
            stats.protectedItems++;
            return false;
        }
        Symbol label = program.getSymbolTable().createLabel(address, name, namespace,
                SourceType.ANALYSIS);
        label.setPrimary();
        return true;
    }

    private boolean ensureFlatLabel(Program program, Address address, String name,
                                    ApplyStats stats) throws Exception {
        if (name.isBlank()) return false;
        Symbol primary = program.getSymbolTable().getPrimarySymbol(address);
        Namespace global = program.getGlobalNamespace();
        for (Symbol symbol : program.getSymbolTable().getSymbols(address)) {
            if (symbol.getName().equals(name) && symbol.getParentNamespace().equals(global)) {
                if (symbol.isPrimary()) return false;
                if (!replaceablePrimary(primary)) {
                    stats.protectedItems++;
                    return false;
                }
                symbol.setPrimary();
                return true;
            }
        }
        if (!replaceablePrimary(primary)) {
            stats.protectedItems++;
            return false;
        }
        Symbol label = program.getSymbolTable().createLabel(address, name, global,
                SourceType.ANALYSIS);
        label.setPrimary();
        return true;
    }

    private Namespace getOrCreateNamespacePath(Program program, List<String> parts)
            throws Exception {
        Namespace parent = program.getGlobalNamespace();
        SymbolTable symbols = program.getSymbolTable();
        for (String part : parts) {
            Namespace namespace = symbols.getNamespace(part, parent);
            if (namespace == null) {
                if (!validSymbolComponent(part)) return null;
                namespace = symbols.createNameSpace(parent, part, SourceType.ANALYSIS);
            }
            parent = namespace;
        }
        return parent;
    }

    private Namespace findNamespacePath(Program program, List<String> parts) {
        Namespace parent = program.getGlobalNamespace();
        for (String part : parts) {
            Namespace namespace = program.getSymbolTable().getNamespace(part, parent);
            if (namespace == null) return null;
            parent = namespace;
        }
        return parent;
    }

    private List<String> splitCppScopes(String value) {
        List<String> parts = new ArrayList<>();
        int angle = 0;
        int paren = 0;
        int bracket = 0;
        int start = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '<') angle++;
            else if (c == '>' && angle > 0) angle--;
            else if (c == '(') paren++;
            else if (c == ')' && paren > 0) paren--;
            else if (c == '[') bracket++;
            else if (c == ']' && bracket > 0) bracket--;
            if (c == ':' && value.charAt(i + 1) == ':' && angle == 0 && paren == 0 &&
                    bracket == 0) {
                parts.add(value.substring(start, i));
                start = ++i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private boolean validSymbolComponent(String value) {
        if (value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++)
            if (SymbolUtilities.isInvalidChar(value.charAt(i))) return false;
        return true;
    }

    private String safeLabel(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_$]", "_").replaceAll("_+", "_");
        if (safe.isBlank()) return "";
        if (Character.isDigit(safe.charAt(0))) safe = "_" + safe;
        return safe.length() <= 120 ? safe : safe.substring(0, 120);
    }

    private boolean replaceablePrimary(Symbol symbol) {
        if (symbol == null) return true;
        return replaceableName(symbol.getSource(), symbol.getName(true));
    }

    private boolean replaceableName(SourceType source, String name) {
        return name.startsWith("OOAnalyzer::") || source == SourceType.DEFAULT || isPlaceholder(name) &&
                (source == SourceType.ANALYSIS || source == SourceType.USER_DEFINED);
    }

    private boolean setOwnedComment(Program program, CommentType type, Address address,
                                    String prefix, String text, ApplyStats stats) {
        String old = program.getListing().getComment(type, address);
        String desired = text;
        if (old != null && !old.startsWith(prefix)) {
            String open = "[SC4Mac]\n";
            String close = "\n[/SC4Mac]";
            String block = open + text + close;
            int start = old.indexOf(open);
            int end = start < 0 ? -1 : old.indexOf(close, start + open.length());
            if (start < 0) desired = old + "\n\n" + block;
            else if (end >= 0)
                desired = old.substring(0, start) + block +
                        old.substring(end + close.length());
            else {
                stats.protectedItems++;
                return false;
            }
        }
        if (desired.equals(old)) return false;
        program.getListing().setComment(address, type, desired);
        stats.comments++;
        return true;
    }

    private void reportNamedStringData(Program mac, Program windows) throws Exception {
        Map<String, List<NamedString>> source = namedStrings(mac, true);
        Map<String, List<NamedString>> target = namedStrings(windows, false);
        List<String> texts = source.keySet().stream()
                .filter(target::containsKey)
                .filter(s -> source.get(s).size() == 1 && target.get(s).size() == 1)
                .sorted()
                .toList();
        println("\nUnique named-string data anchors=" + texts.size());
        for (String text : texts) {
            NamedString from = source.get(text).get(0);
            NamedString to = target.get(text).get(0);
            dataProposals.add(new DataProposal(from, to));
        }
        int count = 0;
        for (DataProposal proposal : dataProposals) {
            if (count++ == MAX_STRING_REPORTS) break;
            NamedString from = proposal.source();
            NamedString to = proposal.target();
            String text = from.text();
            Symbol targetSymbol = windows.getSymbolTable().getPrimarySymbol(to.address());
            println("  " + from.name() + " -> " + to.address() + " " + symbolText(targetSymbol) +
                    " | \"" + shorten(text.replaceAll("\\s+", " "), 70) + "\"");
        }
        if (texts.size() > MAX_STRING_REPORTS)
            println("  ... string report truncated at " + MAX_STRING_REPORTS + " entries");
    }

    private Map<String, List<NamedString>> namedStrings(Program program, boolean requireUsefulName)
            throws Exception {
        Map<String, List<NamedString>> out = new HashMap<>();
        DataIterator it = program.getListing().getDefinedData(true);
        while (it.hasNext()) {
            monitor.checkCancelled();
            Data data = it.next();
            Object value = data.getValue();
            if (!(value instanceof String text) || text.length() < 8) continue;
            Symbol symbol = program.getSymbolTable().getPrimarySymbol(data.getAddress());
            String name = symbol == null ? "<unnamed>" : symbol.getName(true);
            if (requireUsefulName && !usefulSourceDataName(symbol)) continue;
            out.computeIfAbsent(text, ignored -> new ArrayList<>())
                    .add(new NamedString(data.getAddress(), name, text));
        }
        return out;
    }

    private boolean usefulSourceDataName(Symbol symbol) {
        if (symbol == null || symbol.getSource() == SourceType.DEFAULT) return false;
        String name = symbol.getName();
        return !name.startsWith("__ZTS") && !name.startsWith("__ZTI") &&
                !name.startsWith("__ZTV") && !isPlaceholder(name);
    }

    private Set<String> referencedStrings(Program program, Function function) {
        Set<String> out = new TreeSet<>();
        InstructionIterator it = program.getListing().getInstructions(function.getBody(), true);
        while (it.hasNext()) {
            Instruction instruction = it.next();
            for (Reference reference : instruction.getReferencesFrom()) {
                Data data = program.getListing().getDataContaining(reference.getToAddress());
                if (data != null && data.getValue() instanceof String text && text.length() >= 4)
                    out.add(text);
            }
        }
        return out;
    }

    /**
     * Extract the interface ids a function tests.
     *
     * <p>With a header allowlist loaded this scans every id-bearing mnemonic and
     * normalises each immediate onto its canonical id, which recovers the ids
     * MSVC hid behind SUB/ADD and rejects displacements that merely look like
     * ids. Without an allowlist it degrades to the original CMP-only heuristic,
     * which is lossy but cannot be made safer unaided.
     *
     * <p>Results are cached: the Windows scan now runs unfiltered so that exact
     * and extra id comparisons see a function's real id set, which means each
     * function would otherwise be re-scanned per candidate class.
     */
    private Set<Long> comparisonIds(Program program, Function function) {
        Set<Long> cached = comparisonIdCache.get(function);
        if (cached != null) return cached;

        Set<Long> out = new TreeSet<>(Long::compareUnsigned);
        boolean allowlist = !knownGzids.isEmpty();
        InstructionIterator it = program.getListing().getInstructions(function.getBody(), true);
        while (it.hasNext()) {
            Instruction instruction = it.next();
            String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
            boolean bearing = allowlist ? ID_BEARING_MNEMONICS.contains(mnemonic)
                    : mnemonic.startsWith("CMP");
            if (!bearing) continue;
            for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                for (Object object : instruction.getOpObjects(operand)) {
                    if (!(object instanceof Scalar scalar) || scalar.bitLength() > 32) continue;
                    long value = scalar.getUnsignedValue() & 0xffffffffL;
                    if (allowlist) {
                        Long canonical = canonicalGzid(value);
                        if (canonical != null) out.add(canonical);
                    } else if (plausibleGzid(value)) {
                        out.add(value);
                    }
                }
            }
        }
        // riid == 1 (cIGZUnknown) compiles to DEC EAX and carries no immediate,
        // so it can never be observed. It is trivial for matching either way.
        comparisonIdCache.put(function, out);
        return out;
    }

    private boolean plausibleGzid(long value) {
        return value == 1 || Long.compareUnsigned(value, 0x1000L) >= 0;
    }

    private Map<Long, List<Address>> pointerReferences(Program program, Collection<Address> targets)
            throws Exception {
        Set<Long> wanted = new HashSet<>();
        for (Address target : targets) wanted.add(addressKey(program, target));
        Map<Long, List<Address>> out = new HashMap<>();
        int size = program.getDefaultPointerSize();
        for (MemoryBlock block : program.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.isExecute()) continue;
            long start = block.getStart().getOffset();
            long aligned = start + Math.floorMod(-start, size);
            long last = block.getEnd().getOffset() - size + 1;
            for (long offset = aligned; Long.compareUnsigned(offset, last) <= 0; offset += size) {
                monitor.checkCancelled();
                Address address = block.getStart().getAddressSpace().getAddress(offset);
                long value = size == 8 ? program.getMemory().getLong(address)
                        : Integer.toUnsignedLong(program.getMemory().getInt(address));
                if (wanted.contains(value))
                    out.computeIfAbsent(value, ignored -> new ArrayList<>()).add(address);
            }
        }
        return out;
    }

    private Set<Long> queryInterfaceTargets(Program program, List<WindowsCandidate> candidates) {
        Set<Long> out = new HashSet<>();
        for (WindowsCandidate candidate : candidates)
            out.add(addressKey(program, candidate.function().getEntryPoint()));
        FunctionIterator it = program.getFunctionManager().getFunctions(true);
        while (it.hasNext()) {
            Function function = it.next();
            if (function.getName().contains("QueryInterface"))
                out.add(addressKey(program, function.getEntryPoint()));
        }
        return out;
    }

    private boolean looksLikeVtableStart(Program program, Address address) {
        int ptr = program.getDefaultPointerSize();
        if (!readable(program, address, 3 * ptr)) return false;
        try {
            return isCode(program, pointer(program, address)) &&
                    isCode(program, pointer(program, address.add(ptr))) &&
                    isCode(program, pointer(program, address.add(2L * ptr)));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Vtable readCodeTable(Program program, Address start, Set<Long> qiTargets) throws Exception {
        List<Slot> slots = new ArrayList<>();
        int ptr = program.getDefaultPointerSize();
        boolean truncated = false;
        String extentStop = start + " unknown";
        for (int i = 0; i < MAX_VTABLE_SLOTS; i++) {
            Address slotAddress = start.add((long)i * ptr);
            if (!readable(program, slotAddress, ptr)) {
                extentStop = slotAddress + " unreadable";
                break;
            }
            Address target = pointer(program, slotAddress);
            if (!isCode(program, target)) {
                extentStop = slotAddress + (target.getOffset() == 0 ? " null" :
                        " non-code->" + target);
                break;
            }
            if (i > 0 && qiTargets.contains(addressKey(program, target))) {
                extentStop = slotAddress + " next-QI->" + target;
                break;
            }
            slots.add(new Slot(target, program.getFunctionManager().getFunctionAt(target)));
            if (i == MAX_VTABLE_SLOTS - 1) {
                truncated = true;
                extentStop = start.add((long)MAX_VTABLE_SLOTS * ptr) + " cap";
            }
        }
        return new Vtable(start, slots, truncated, extentStop);
    }

    /**
     * Resolve a vtable entry to the implementation it really reaches.
     *
     * <p>Ghidra only recognises a pure JMP as a thunk. MSVC multiple-inheritance
     * adjustor thunks adjust `this` first ("SUB ECX,0x4; JMP real"), so Ghidra
     * leaves them as ordinary functions -- there are over a thousand of them in
     * SimCity 4.exe, many named QueryInterface. Treating each as its own
     * implementation defeats the folding detection this script is built around,
     * and points string/BSim matching at two-instruction bodies that carry no
     * evidence.
     */
    private Function resolveThunk(Function function) {
        Function current = function;
        for (int hop = 0; hop < MAX_ADJUSTOR_HOPS && current != null; hop++) {
            Function next = current.isThunk() ? current.getThunkedFunction(true)
                    : adjustorTarget(current);
            if (next == null || next.equals(current)) return current;
            current = next;
        }
        return current;
    }

    /**
     * Follow an adjustor thunk: an optional `this` adjustment followed by an
     * unconditional jump, and nothing else. The narrow shape is deliberate --
     * anything longer is a real function that happens to end in a tail call.
     */
    private Function adjustorTarget(Function function) {
        Program program = function.getProgram();
        InstructionIterator it = program.getListing().getInstructions(function.getBody(), true);
        Address target = null;
        int instructions = 0;
        while (it.hasNext()) {
            if (++instructions > 3) return null;
            Instruction instruction = it.next();
            String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
            if (mnemonic.equals("JMP")) {
                Address[] flows = instruction.getFlows();
                if (flows.length != 1) return null;
                target = flows[0];
                if (it.hasNext()) return null; // JMP must be the last instruction.
                break;
            }
            if (!mnemonic.equals("SUB") && !mnemonic.equals("ADD")) return null;
        }
        if (target == null) return null;
        Function resolved = program.getFunctionManager().getFunctionAt(target);
        return resolved == null || resolved.equals(function) ? null : resolved;
    }

    private boolean isCode(Program program, Address address) {
        MemoryBlock block = program.getMemory().getBlock(address);
        return program.getFunctionManager().getFunctionAt(address) != null ||
                (block != null && block.isExecute());
    }

    private String vtableClassName(Program program, Symbol symbol) {
        try {
            DemangledObject demangled = DemanglerUtil.demangle(program, symbol.getName());
            if (demangled != null) {
                String text = demangled.getSignature();
                text = text.replaceFirst("(?i)^.*?vtable for\\s+", "");
                if (!text.equals(demangled.getSignature())) return cleanClassName(text);
            }
        } catch (Exception ignored) {
            // Use the Itanium type-name fallback below.
        }
        String encoded = symbol.getName().substring("__ZTV".length());
        try {
            DemangledObject demangled = DemanglerUtil.demangle(program, "_ZTI" + encoded);
            if (demangled != null)
                return cleanClassName(demangled.getSignature()
                        .replaceFirst("(?i)^.*?typeinfo for\\s+", ""));
        } catch (Exception ignored) {
            // Keep the encoded name if Ghidra cannot demangle it.
        }
        return encoded;
    }

    private String cleanClassName(String name) {
        return name.replaceFirst("(?i)(?:::|\\s)+(?:typeinfo|vtable)$", "").trim();
    }

    private Address pointer(Program program, Address address) throws Exception {
        int ptr = program.getDefaultPointerSize();
        long value = ptr == 8 ? program.getMemory().getLong(address)
                : Integer.toUnsignedLong(program.getMemory().getInt(address));
        return program.getAddressFactory().getDefaultAddressSpace().getAddress(value);
    }

    private long signedPointer(Program program, Address address) throws Exception {
        return program.getDefaultPointerSize() == 8 ? program.getMemory().getLong(address)
                : program.getMemory().getInt(address);
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

    private long addressKey(Program program, Address address) {
        return program.getDefaultPointerSize() == 8
                ? address.getOffset() : address.getOffset() & 0xffffffffL;
    }

    private int confidenceRank(String confidence) {
        return switch (confidence) {
            case "confirmed" -> 0;
            case "strong" -> 1;
            case "single-id" -> 2;
            case "family" -> 3;
            case "probable" -> 4;
            default -> 5;
        };
    }

    private String protection(Symbol symbol) {
        if (symbol == null) return "unnamed";
        return symbolText(symbol) + (replaceablePrimary(symbol) ? " [replaceable]" : " [protected]");
    }

    private String symbolText(Symbol symbol) {
        return symbol == null ? "<unnamed>" : symbol.getName(true) + " (" + symbol.getSource() + ")";
    }

    private boolean isPlaceholder(String name) {
        String leaf = leafName(name);
        return leaf.matches("(?:FUN|DAT|LAB|PTR|UNK|undefined)_[0-9A-Fa-f]+") ||
                leaf.matches("(?:thunk_)?FUN_[0-9A-Fa-f]+") ||
                leaf.matches("(?:meth|cls)_0x[0-9A-Fa-f]+") || leaf.startsWith("s_") ||
                leaf.endsWith("?");
    }

    private String leafName(String name) {
        int separator = name.lastIndexOf("::");
        return separator < 0 ? name : name.substring(separator + 2);
    }

    private String qualified(Function function) {
        return function == null ? "<no function>" : function.getSymbol().getName(true);
    }

    private String ids(Set<Long> ids) {
        return ids.stream().map(id -> String.format("0x%08x", id)).toList().toString();
    }

    private String fingerprint(Set<Long> ids) {
        return ids(ids);
    }

    private Set<Long> nonTrivial(Set<Long> ids) {
        Set<Long> out = new HashSet<>(ids);
        out.remove(1L);
        return out;
    }

    private <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    private <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private <T> boolean intersects(Set<T> a, Set<T> b) {
        for (T value : a) if (b.contains(value)) return true;
        return false;
    }

    private String summarizeStrings(Set<String> strings) {
        return strings.stream().limit(2)
                .map(s -> "\"" + shorten(s.replaceAll("\\s+", " "), 48) + "\"")
                .toList().toString();
    }

    private String shorten(String text, int length) {
        return text.length() <= length ? text : text.substring(0, Math.max(0, length - 3)) + "...";
    }

    private final class BsimMatcher implements AutoCloseable {
        private final LSHVectorFactory vectorFactory = new WeightedLSHCosineVectorFactory();
        private final Map<Function, LSHVector> vectors = new HashMap<>();
        private final Set<Function> failed = new HashSet<>();
        private final Set<String> calibrationPairs = new HashSet<>();
        private final List<BsimComparison> calibration = new ArrayList<>();
        private DecompInterface macDecompiler;
        private DecompInterface windowsDecompiler;
        private long comparisons;

        BsimMatcher(Program mac, Program windows) throws Exception {
            ResourceFile weights = GenSignatures.getWeightsFile(
                    mac.getLanguageID(), windows.getLanguageID());
            if (weights == null)
                throw new IllegalStateException("no BSim weights for " + mac.getLanguageID() +
                        " versus " + windows.getLanguageID());
            try (InputStream input = weights.getInputStream()) {
                XmlPullParser parser = new NonThreadedXmlPullParserImpl(input,
                        "SC4 BSim weights", SpecXmlUtils.getXmlHandler(), false);
                vectorFactory.readWeights(parser);
            }
            try {
                macDecompiler = openDecompiler(mac);
                windowsDecompiler = openDecompiler(windows);
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private DecompInterface openDecompiler(Program program) {
            DecompInterface decompiler = new DecompInterface();
            decompiler.setOptions(new DecompileOptions());
            decompiler.toggleSyntaxTree(false);
            decompiler.setSignatureSettings(vectorFactory.getSettings());
            if (!decompiler.openProgram(program)) {
                String message = decompiler.getLastMessage();
                decompiler.dispose();
                throw new IllegalStateException("cannot open BSim decompiler for " +
                        program.getName() + ": " + message);
            }
            return decompiler;
        }

        BsimComparison compare(Function macFunction, Function windowsFunction)
                throws ghidra.util.exception.CancelledException {
            LSHVector left = vector(macFunction, macDecompiler);
            LSHVector right = vector(windowsFunction, windowsDecompiler);
            if (left == null || right == null) return null;
            VectorCompare details = new VectorCompare();
            double similarity = left.compare(right, details);
            double significance = vectorFactory.calculateSignificance(details);
            comparisons++;
            if (!Double.isFinite(similarity) || !Double.isFinite(significance)) return null;
            return new BsimComparison(similarity, significance);
        }

        void calibrate(Function macFunction, Function windowsFunction)
                throws ghidra.util.exception.CancelledException {
            Function left = resolveThunk(macFunction);
            Function right = resolveThunk(windowsFunction);
            if (left == null || right == null) return;
            String key = left.getEntryPoint() + "->" + right.getEntryPoint();
            if (!calibrationPairs.add(key)) return;
            BsimComparison comparison = compare(left, right);
            if (comparison != null) calibration.add(comparison);
        }

        private LSHVector vector(Function function, DecompInterface decompiler)
                throws ghidra.util.exception.CancelledException {
            Function resolved = resolveThunk(function);
            if (resolved == null || failed.contains(resolved)) return null;
            LSHVector cached = vectors.get(resolved);
            if (cached != null) return cached;
            // Propagate cancellation instead of returning null: swallowing it
            // produced a run with silently zero BSim anchors that still went on
            // to offer apply, with no indication the evidence was incomplete.
            monitor.checkCancelled();
            if (budgetExhausted()) return null;
            if ((vectors.size() + failed.size()) % 50 == 0)
                monitor.setMessage("Generating BSim vectors " +
                        (vectors.size() + failed.size()) + "/" + MAX_BSIM_VECTORS);
            SignatureResult result = decompiler.generateSignatures(
                    resolved, false, 10, monitor);
            if (result == null || result.features == null || result.features.length == 0) {
                failed.add(resolved);
                return null;
            }
            LSHVector built = vectorFactory.buildVector(result.features);
            vectors.put(resolved, built);
            return built;
        }

        boolean budgetExhausted() {
            return vectors.size() + failed.size() >= MAX_BSIM_VECTORS;
        }

        void reportStats() {
            println("\nBSim stats: vectors=" + vectors.size() + ", failed=" + failed.size() +
                    ", comparisons=" + comparisons +
                    (budgetExhausted() ? ", vector budget reached=" + MAX_BSIM_VECTORS : ""));
            if (!calibration.isEmpty()) {
                List<Double> similarities = calibration.stream()
                        .map(BsimComparison::similarity).sorted().toList();
                List<Double> significances = calibration.stream()
                        .map(BsimComparison::significance).sorted().toList();
                long passing = calibration.stream().filter(score ->
                        score.similarity() >= MIN_BSIM_SIMILARITY &&
                                score.significance() >= MIN_BSIM_SIGNIFICANCE).count();
                double medianSimilarity = similarities.get(similarities.size() / 2);
                println(String.format(Locale.ROOT,
                        "BSim known-anchor calibration: pairs=%d pass=%d median-sim=%.3f " +
                                "median-sig=%.1f",
                        calibration.size(), passing,
                        medianSimilarity,
                        significances.get(significances.size() / 2)));
                // Calibration pairs are known-good matches. If they score below
                // the acceptance threshold, BSim cannot separate true from false
                // matches on this compiler pair at all, and every comparison is
                // wasted time rather than weak evidence.
                if (passing == 0 && medianSimilarity < MIN_BSIM_SIMILARITY)
                    println("BSim WARNING: known-good pairs score below the " +
                            MIN_BSIM_SIMILARITY + " threshold (median " +
                            String.format(Locale.ROOT, "%.3f", medianSimilarity) +
                            "). GCC and MSVC output is too dissimilar here; BSim is " +
                            "contributing nothing. Consider disabling it to save runtime.");
            }
        }

        @Override
        public void close() {
            closeDecompiler(macDecompiler);
            closeDecompiler(windowsDecompiler);
            macDecompiler = null;
            windowsDecompiler = null;
        }

        private void closeDecompiler(DecompInterface decompiler) {
            if (decompiler == null) return;
            decompiler.closeProgram();
            decompiler.dispose();
        }
    }

    private void selfCheck() {
        Set<Long> a = Set.of(1L, 0x12345678L, 0xabcdef01L);
        Set<Long> b = Set.of(1L, 0x12345678L, 0xabcdef01L);
        Set<Long> c = Set.of(1L, 0x12345678L);
        Map<Long, Integer> frequencies = Map.of(0x12345678L, 1, 0xabcdef01L, 1);
        if (score(a, b, frequencies) <= score(a, c, frequencies))
            throw new AssertionError("QI fingerprint scoring self-check failed");
        if (!cleanClassName("cGZWin::typeinfo").equals("cGZWin") ||
                meaningfulName("FUN_00900000"))
            throw new AssertionError("symbol-name normalization self-check failed");
        List<SlotMapping> stable = interpolateAnchors(
                List.of(new SlotMapping(2, 2), new SlotMapping(7, 7),
                        new SlotMapping(100, 93), new SlotMapping(128, 121)), 200, 200);
        if (!stable.contains(new SlotMapping(3, 3)) ||
                !stable.contains(new SlotMapping(101, 94)) ||
                stable.contains(new SlotMapping(99, 99)))
            throw new AssertionError("anchor-bounded order self-check failed");
        StringAnchor reused = new StringAnchor(List.of(3, 6), List.of(4), Set.of("x"));
        if (reused.uniqueMacSlot() || !reused.uniqueWindowsSlot() ||
                !slotList(reused.macSlots()).equals("[03, 06]"))
            throw new AssertionError("reused-slot self-check failed");
        if (!splitCppScopes("ns::Box<A::B>::Method").equals(
                List.of("ns", "Box<A::B>", "Method")))
            throw new AssertionError("C++ scope parsing self-check failed");
        ProposedRole bsimA = new ProposedRole("A", List.of(1), "cX::F", "bsim",
                "strong", null);
        ProposedRole bsimB = new ProposedRole("B", List.of(1), "cX::F", "bsim",
                "single-id", null);
        ProposedRole orderB = new ProposedRole("B", List.of(1), "cX::F", "order",
                "strong", null);
        ProposedRole slotB = new ProposedRole("B", List.of(2), "B::vfunc02", "slot",
                "strong", null);
        if (!aliasEvidence(List.of(bsimA)) || !aliasEvidence(List.of(orderB)) ||
                !appliedPrimaryName("cX::F", List.of(orderB)).equals("B::F?") ||
                !appliedPrimaryName("cX::F", List.of(bsimA, bsimB)).equals("cX::F?") ||
                !appliedAliasNames("cX::F", List.of(bsimA, bsimB)).equals(
                        Set.of("A::F?", "B::F?")) ||
                !appliedPrimaryName("B::vfunc02", List.of(slotB)).equals("B::vfunc02"))
            throw new AssertionError("inferred-role naming self-check failed");
        if (!replaceableName(SourceType.USER_DEFINED, "FUN_00900000") ||
                !replaceableName(SourceType.USER_DEFINED,
                        "OOAnalyzer::cls_0x99922f::meth_0x99922f") ||
                !replaceableName(SourceType.IMPORTED, "OOAnalyzer::unsuccessful_tool_output") ||
                meaningfulName("OOAnalyzer::cls_0x99922f::meth_0x99922f") ||
                meaningfulName("OOAnalyzer::unsuccessful_tool_output") ||
                meaningfulName("cFoo::Bar?") ||
                !replaceableName(SourceType.ANALYSIS, "cFoo::Bar?") ||
                replaceableName(SourceType.USER_DEFINED, "cFoo::Bar"))
            throw new AssertionError("USER_DEFINED placeholder self-check failed");
        if (!replaceableRenameCollision(SourceType.ANALYSIS, SymbolType.LABEL) ||
                replaceableRenameCollision(SourceType.USER_DEFINED, SymbolType.LABEL) ||
                replaceableRenameCollision(SourceType.ANALYSIS, SymbolType.FUNCTION))
            throw new AssertionError("rename-collision self-check failed");
        selfCheckGzidFolding();
        selfCheckHeaderParsing();
    }

    /**
     * Exercises the header shapes that actually occur in gzcom-dll: a root class
     * with no base clause, a doc comment between the access specifier and the
     * first method, a declaration wrapped across lines, and a nested enum whose
     * members must not become slots.
     */
    private void selfCheckHeaderParsing() {
        Map<String, HeaderClass> saved = new TreeMap<>(headerClasses);
        try {
            headerClasses.clear();
            parseHeaderClasses(String.join("\n",
                    "class cIGZUnknown",
                    "{",
                    "\tpublic:",
                    "\t\t/** @brief casts {the} object; returns bool */",
                    "\t\tvirtual bool QueryInterface(uint32_t riid, void** ppvObj) = 0;",
                    "\t\tvirtual uint32_t AddRef(void) = 0;",
                    "\t\tvirtual uint32_t Release(void) = 0;",
                    "};",
                    "class cIGZProbe : public cIGZUnknown",
                    "{",
                    "\tpublic:",
                    "\t\tenum tFlag : uint32_t { Alpha = 0x1, Beta = 0x2 };",
                    "\t\tvirtual bool Init(void) = 0;",
                    "\t\tvirtual cIGZProbe& operator=(cIGZProbe const& other) = 0;",
                    "\t\tvirtual bool Wrapped(",
                    "\t\t\tint32_t first,",
                    "\t\t\tint32_t second) = 0;",
                    "};"));
            if (!headerClasses.containsKey("cIGZUnknown"))
                throw new AssertionError("a class with no base clause must still parse");
            // operator= must occupy its slot: dropping it silently shifts every
            // later method by one and yields a wrong but plausible order.
            List<String> order = headerVtableOrder("cIGZProbe");
            if (!order.equals(List.of("QueryInterface", "AddRef", "Release",
                    "Init", "operator=", "Wrapped")))
                throw new AssertionError("header vtable order self-check failed: " + order);
            if (!headerVtableOrder("cIGZUnrelated").isEmpty())
                throw new AssertionError("unknown classes must not resolve");
        } finally {
            headerClasses.clear();
            headerClasses.putAll(saved);
        }
    }

    /**
     * Verified against SimCity 4.exe 1.1.641: cSC4OccupantManager::QueryInterface
     * at 0049d910 tests GZIID 0x452294aa (kcIGZMessageTarget2) as
     * "DEC EAX; SUB EAX,0x452294a9" -- the immediate is one below the real id.
     */
    private void selfCheckGzidFolding() {
        Map<Long, String> saved = new TreeMap<>(Long::compareUnsigned);
        saved.putAll(knownGzids);
        try {
            knownGzids.clear();
            if (canonicalGzid(0x452294a9L) != null)
                throw new AssertionError("empty allowlist must not canonicalise");
            knownGzids.put(0x452294aaL, "kcIGZMessageTarget2");
            knownGzids.put(0xe4fda3d4L, "GZIID_cIGZSerializable");
            if (!Long.valueOf(0x452294aaL).equals(canonicalGzid(0x452294a9L)) ||
                    !Long.valueOf(0x452294aaL).equals(canonicalGzid(0x452294aaL)) ||
                    !Long.valueOf(0xe4fda3d4L).equals(canonicalGzid(0xe4fda3d4L)))
                throw new AssertionError("GZIID fold-tolerance self-check failed");
            if (canonicalGzid(0x452294a9L - GZID_FOLD_TOLERANCE - 1) != null ||
                    canonicalGzid(0x12340000L) != null)
                throw new AssertionError("GZIID tolerance window is too wide");
            // GZCOM assigns interface and class ids one apart, so an exact hit
            // must win over a neighbour or ten such pairs collapse onto their
            // lower member. Verified pair: GZIID_cIGZWinSlider / kcGZWinSlider.
            knownGzids.put(0x21325207L, "GZIID_cIGZWinSlider");
            knownGzids.put(0x21325208L, "kcGZWinSlider");
            if (!Long.valueOf(0x21325207L).equals(canonicalGzid(0x21325207L)) ||
                    !Long.valueOf(0x21325208L).equals(canonicalGzid(0x21325208L)))
                throw new AssertionError("adjacent-id exact-match self-check failed");
            // Folds resolve upwards only; a value above every known id is not
            // a fold of the one below it.
            if (canonicalGzid(0x21325209L) != null)
                throw new AssertionError("GZIID folding must not resolve downwards");
        } finally {
            knownGzids.clear();
            knownGzids.putAll(saved);
        }
    }
}
