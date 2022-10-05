import one.jfr.*;
import one.jfr.Dictionary;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SimpleHeatmap extends ResourceProcessor {

    public static enum Type {
        CPU,
        ALLOC,
        LOCK
    }

    private static final int UNKNOWN_ID = -1;
    private static final String UNKNOWN_METHOD_NAME = "<UnknownMethod>";
    private static final String UNKNOWN_CLASS_NAME = "<UnknownClass>";
    private static final StackTrace UNKNOWN_STACK = new StackTrace(new long[]{UNKNOWN_ID}, new byte[]{3}, new int[]{0});
    private static final MethodRef UNKNOWN_METHOD_REF = new MethodRef(UNKNOWN_ID, UNKNOWN_ID, UNKNOWN_ID);
    private static final ClassRef UNKNOWN_CLASS_REF = new ClassRef(UNKNOWN_ID);

    private final List<Event> samples = new ArrayList<>();

    private final String title;
    private final Type type;

    private Dictionary<StackTrace> stacks;
    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private long startMs;
    private long startTicks;
    private long ticksPerSec;

    public SimpleHeatmap(String title, Type type) {
        this.title = title;
        this.type = type;
    }

    public void addEvent(Event event) {
        switch (type) {
            case CPU:
                if (event instanceof ExecutionSample) {
                    samples.add(event);
                }
                break;
            case ALLOC:
                if (event instanceof AllocationSample) {
                    samples.add(event);
                }
                break;
            case LOCK:
                if (event instanceof ContendedLock) {
                    samples.add(event);
                }
                break;
        }
    }

    public void finish(
            Dictionary<StackTrace> stacks,
            Dictionary<MethodRef> methodRefs,
            Dictionary<ClassRef> classRefs,
            Dictionary<byte[]> symbols,
            long startMs,
            long startTicks,
            long ticksPerSec
    ) {
        this.stacks = stacks;
        this.methodRefs = methodRefs;
        this.classRefs = classRefs;
        this.symbols = symbols;
        this.startMs = startMs;
        this.startTicks = startTicks;
        this.ticksPerSec = ticksPerSec;
    }

    private long ms(long ticks) {
        return (ticks - startTicks) * 1000 / ticksPerSec;
    }

    private EvaluationContext evaluate(long blockDurationMs) {
        System.out.println("Evaluation started");
        Collections.sort(samples);

        final Index<String> symbols = new Index<>();
        symbols.preallocate(this.symbols.size());
        Method rootMethod = new Method(symbols.index("all"), symbols.index(""));

        final Index<Method> methodIndex = new Index<>();

        methodIndex.index(rootMethod);

        Dictionary<int[]> stackTraces = new Dictionary<>();

        long durationExecutions = samples.isEmpty() ? 0 : ms(samples.get(samples.size() - 1).time);
        SampleBlock[] blocks = new SampleBlock[(int) (durationExecutions / blockDurationMs) + 1];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new SampleBlock();
        }

        Index<int[]> stackTracesRemap = new Index<int[]>() {
            @Override
            protected boolean equals(int[] k1, int[] k2) {
                return Arrays.equals(k1, k2);
            }

            @Override
            protected int hashCode(int[] key) {
                return Arrays.hashCode(key) * 0x5bd1e995;
            }
        };

        int procent = 0;
        int pp = 0;

        for (Event execution : samples) {
            if (pp * 100 / samples.size() != procent) {
                procent = pp * 100 / samples.size();
                System.out.println(procent + "%");
            }
            pp++;

            long timeMs = ms(execution.time);
            SampleBlock block = blocks[(int) (timeMs / blockDurationMs)];

            int[] stackTrace = stackTraces.get((long)execution.extra() << 32 | execution.stackTraceId);

            if (stackTrace != null) {
                block.stacks.add(stackTracesRemap.index(stackTrace));
                continue;
            }

            StackTrace originalTrace = stacks.getOrDefault(execution.stackTraceId, UNKNOWN_STACK);
            stackTrace = new int[originalTrace.methods.length + (type == Type.CPU ? 0 : 1)];

            for (int i = originalTrace.methods.length - 1; i >= 0; i--) {
                long methodId = originalTrace.methods[i];
                byte type = originalTrace.types[i];
                int location = originalTrace.locations[i];

                MethodRef methodRef = methodRefs.getOrDefault(methodId, UNKNOWN_METHOD_REF);
                ClassRef classRef = classRefs.getOrDefault(methodRef.cls, UNKNOWN_CLASS_REF);

                byte[] classNameBytes = this.symbols.get(classRef.name);
                byte[] methodNameBytes = this.symbols.get(methodRef.name);

                String classNameString = classNameBytes == null ? UNKNOWN_CLASS_NAME : convertClassName(classNameBytes);
                String methodNameString = methodNameBytes == null ? UNKNOWN_METHOD_NAME : new String(methodNameBytes);

                int className = symbols.index(classNameString);
                int methodName = symbols.index(methodNameString);

                int index = originalTrace.methods.length - 1 - i;
                Method method = new Method(className, methodName, location, type, index == 0);
                stackTrace[index] = methodIndex.index(method);
            }
            if (type != Type.CPU) {
                ClassRef classRef = classRefs.getOrDefault(execution.extra(), UNKNOWN_CLASS_REF);
                byte[] classNameBytes = this.symbols.get(classRef.name);
                String classNameString = classNameBytes == null ? UNKNOWN_CLASS_NAME : convertClassName(classNameBytes);
                int className = symbols.index(classNameString);
                int methodName = symbols.index("");
                byte type = 2;
                if (this.type == Type.ALLOC) {
                    type = ((AllocationSample)execution).tlabSize == 0 ? (byte) 3 : (byte) 2;
                }
                stackTrace[originalTrace.methods.length] = methodIndex.index(new Method(className, methodName, 0, type, false));
            }

            stackTraces.put((long)execution.extra() << 32 | execution.stackTraceId, stackTrace);
            block.stacks.add(stackTracesRemap.index(stackTrace));
        }


        System.out.println("Unique: " + stackTracesRemap.size() + "/" + stackTraces.size());

        int[][] stacks = new int[stackTracesRemap.size()][];
        stackTracesRemap.orderedKeys(stacks);

        String[] symbolBytes = new String[symbols.size()];
        symbols.orderedKeys(symbolBytes);

        return new EvaluationContext(
                Arrays.asList(blocks),
                methodIndex,
                stacks,
                symbolBytes
        );
    }

    private String convertClassName(byte[] className) {
        if (className.length == 0) {
            return "";
        }
        int arrayDepth = 0;
        while (className[arrayDepth] == '[') {
            arrayDepth++;
        }

        StringBuilder sb = new StringBuilder(toJavaClassName(className, arrayDepth));
        while (arrayDepth-- > 0) {
            sb.append("[]");
        }
        return sb.toString();
    }

    private String toJavaClassName(byte[] symbol, int start) {
        switch (symbol[start]) {
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'I':
                return "int";
            case 'J':
                return "long";
            case 'Z':
                return "boolean";
            case 'F':
                return "float";
            case 'D':
                return "double";
            case 'L':
                return new String(symbol, start + 1, symbol.length - start - 2, StandardCharsets.UTF_8).replace('/', '.');
            default:
                return new String(symbol, start, symbol.length - start, StandardCharsets.UTF_8).replace('/', '.');
        }
    }

    private void compressMethods(Output out, Method[] methods) {
        for (Method method : methods) {
            out.write36((long) method.methodName << 18L | method.className);
            out.write36((long) method.location << 4L | method.type);
        }
    }

    public void dump(PrintStream stream) {
        Output out = new HtmlOut(stream);

        EvaluationContext evaluationContext = evaluate(20);

        String tail = getResource("/flame.html");

        tail = printTill(out, tail, "/*height:*/300");
        out.print(300);

        tail = printTill(out, tail, "/*if heatmap css:*/");
        tail = printTill(out, tail, "/*end if heatmap css*/");

        tail = printTill(out, tail, "/*if heatmap html:*/");

        tail = printTill(out, tail, "/*executionsHeatmap:*/");
        int was = out.pos();
        printHeatmap(out, evaluationContext);
        System.out.println((out.pos() - was) / 1024);

        tail = printTill(out, tail, "/*globalStacks:*/");
        printGlobalStacks();

        tail = printTill(out, tail, "/*methods:*/");
        was = out.pos();
        printMethods(out, evaluationContext);
        System.out.println((out.pos() - was) / 1024);

        tail = printTill(out, tail, "/*end if heatmap html*/");
        tail = printTill(out, tail, "/*title:*/");
        out.print(title);

        tail = printTill(out, tail, "/*if flamegraph html:*/");
        tail = skipTill(tail, "/*end if flamegraph html*/");

        tail = printTill(out, tail, "/*reverse:*/false");
        out.print("true");

        tail = printTill(out, tail, "/*depth:*/0");
        out.print(0);

        tail = printTill(out, tail, "/*if flamegraph js:*/");
        tail = skipTill(tail, "/*end if flamegraph js*/");

        tail = printTill(out, tail, "/*if heatmap js:*/");
        tail = printTill(out, tail, "/*ticksPerSecond:*/1");
        out.print(ticksPerSec);

        tail = printTill(out, tail, "/*startMs:*/0");
        out.print(startMs);

        tail = printTill(out, tail, "/*cpool:*/");
        was = out.pos();
        printConstantPool(out, evaluationContext);
        System.out.println((out.pos() - was) / 1024);

        tail = printTill(out, tail, "/*end if heatmap js*/");

        tail = printTill(out, tail, "/*frames:*/");
        out.print(tail);

    }

    // 37
    // 28


    // 84.4
    // 60

    private void printHeatmap(Output out, EvaluationContext context) {
        int maxZoom = 3;
        out.writeVar(maxZoom);

        IndexInt starts = new IndexInt();
        starts.index(context.methods.size() + 1);

        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];
                starts.index(stack[0]);// use ends???
            }
        }

        int[] startsOut = new int[starts.size()];
        starts.orderedKeys(startsOut);
        out.writeVar(startsOut.length);

        System.out.println("Start method count: " + startsOut.length);
        for (int method : startsOut) {
            System.out.println("Start method: " + method);
            out.writeVar(method);
        }

        Histogram histogram = new Histogram();

        int was = out.pos();
        out.writeVar(context.blocks.size());
        System.out.println("context.blocks.size() " + context.blocks.size());
        for (SampleBlock block : context.blocks) {
            out.writeTinyVar(block.stacks.size);
        }
        System.out.println("stack sizes " + (out.pos() - was) / 1024);

        int synonymsCount = 32 * 32 * 32;   // 3 6-bits var-int symbols

        int nextId = synonymsCount;
        LzNode root = new LzNode(nextId++);
        LzNode next = new LzNode(nextId++);
        List<LzNode> allNodes = new ArrayList<>();
        allNodes.add(root);

        IntList list = new IntList();

        for (SampleBlock block : context.blocks) {
            for (int i = 0; i < block.stacks.size; i++) {
                LzNode current = root;
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];

                for (int methodId : stack) {
                    LzNode prev = current;
                    current = current.putIfAbsent(methodId, next);
                    if (current == null) {
                        allNodes.add(next);
                        prev.count++;
                        current = root;
                        next = new LzNode(nextId++);
                    }
                }
                current.count++;
                list.add(current.id);
            }
        }

        Collections.sort(allNodes, new Comparator<LzNode>() {
            @Override
            public int compare(LzNode o1, LzNode o2) {
                return Integer.compare(o2.count, o1.count);
            }
        });

        was = out.pos();
        IndexInt synonyms = new IndexInt();
        int synonymsDelta = 0;
        if (allNodes.size() < synonymsCount) {
            int oldSynonymsCount = synonymsCount;
            synonymsCount = allNodes.size();
            synonymsDelta = oldSynonymsCount - synonymsCount;
        }

        out.writeVar(synonymsCount);
        System.out.println("synonymsCount " + synonymsCount);
        for (LzNode node : allNodes.subList(0, synonymsCount)) {
            synonyms.index(node.id - synonymsDelta);
            out.writeVar(node.id - synonymsDelta);
        }
        System.out.println("synonyms " + (out.pos() - was) / 1024);

        was = out.pos();
        out.writeVar(list.size);
        System.out.println("tails count " + list.size);
        for (int i = 0; i < list.size; i++) {
            int tailId = list.list[i] - synonymsDelta;
            int index = synonyms.index(tailId, -1);
            out.writeVar(index == -1 ? tailId : index - 1);
        }
        System.out.println("tails " + (out.pos() - was) / 1024);

        nextId = synonymsCount;
        root = new LzNode(nextId++);
        next = new LzNode(nextId++);

        was = out.pos();
        int b = 0;
        for (SampleBlock block : context.blocks) {
            boolean debug = b < 50;
            for (int i = 0; i < block.stacks.size; i++) {
                if (debug) {
                    System.out.println(b + " debug " + i);
                }
                LzNode current = root;
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces[stackId - 1];
                for (int methodId : stack) {
                    if (debug) {
                        System.out.println(b + " MethodId " + methodId);
                    }
                    int prevId = current.id;
                    current = current.putIfAbsent(methodId, next);
                    if (current == null) {
                        current = root;
                        next = new LzNode(nextId++);
                        int index = synonyms.index(prevId, -1);
                        out.writeVar(index == -1 ? prevId : index - 1);
                        out.writeVar(methodId);
                        if (debug) {
                            System.out.println(b + " Dump " + (index == -1 ? prevId : index - 1) + " " + methodId);
                        }
                    }
                }
                if (debug) {
                    int index = synonyms.index(current.id, -1);
                    System.out.println(b + " Ends with " + (index == -1 ? current.id : index - 1));
                    System.out.println();
                }
                b++;
            }
        }

        out.writeVar(0);
        out.writeVar(context.methods.size() + 1);
        System.out.println("bodies " + (out.pos() - was) / 1024);

        histogram.print();
    }

    private void printConstantPool(Output out, EvaluationContext evaluationContext) {
        for (String symbol : evaluationContext.symbols) {
            out.write("\"");
            out.write(symbol);
            out.write("\",");
        }
    }

    private void printMethods(Output out, EvaluationContext evaluationContext) {
        Method[] methods = new Method[evaluationContext.methods.size()];
        evaluationContext.methods.orderedKeys(methods);

        compressMethods(out, methods);
    }

    private void printGlobalStacks() {
    }

    private static String printTill(Output out, String tail, String till) {
        return printTill(out.asPrintableStream(), tail, till);
    }

    private static class Method {

        final int className;
        final int methodName;
        final int location;
        final byte type;
        final boolean start;

        Method(int className, int methodName) {
            this(className, methodName, 0, (byte) 3, true);
        }

        Method(int className, int methodName, int location, byte type, boolean start) {
            this.className = className;
            this.methodName = methodName;
            this.location = location;
            this.type = type;
            this.start = start;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Method method = (Method) o;

            if (className != method.className) return false;
            if (methodName != method.methodName) return false;
            if (location != method.location) return false;
            if (type != method.type) return false;
            return start == method.start;
        }

        @Override
        public int hashCode() {
            int result = className;
            result = 31 * result + methodName;
            result = 31 * result + location;
            result = 31 * result + (int) type;
            result = 31 * result + (start ? 1 : 0);
            return result;
        }
    }

    private static class LzNode extends Dictionary<LzNode> {
        int id;
        int count;

        private LzNode(int id) {
            this.id = id;
        }

        public boolean rename(IndexInt synonyms) {
            int newId = synonyms.shift(id);
            if (newId != 0) {
                id = newId;
                return true;
            }
            return false;
        }
    }

    private static class SampleBlock {
        IntList stacks = new IntList();
    }

    private static class EvaluationContext {
        final Index<Method> methods;
        final int[][] stackTraces;
        final String[] symbols;

        List<SampleBlock> blocks;

        private EvaluationContext(List<SampleBlock> blocks, Index<Method> methods, int[][] stackTraces, String[] symbols) {
            this.blocks = blocks;
            this.methods = methods;
            this.stackTraces = stackTraces;
            this.symbols = symbols;
        }
    }

    private interface Output {
        void writeTinyVar(int v);
        void writeVar(long v);

        void write36(long v);

        void write(String data);

        PrintStream asPrintableStream();

        void print(long x);
        void print(String x);

        int pos();
    }

    public static class HtmlOut implements Output {

        private final PrintStream out;

        private int subByte = -1;

        private int pos = 0;

        public HtmlOut(PrintStream out) {
            this.out = out;
        }

        private void nextByte(int ch) {
            pos++;
            out.append((char) ch);
        }

        private void writeByte(int v) {
            if (subByte != -1) {
                writeSubByte(0);
            }
            nextByte(v + 63); // start from ? (+63) in ascii
        }

        private void writeSubByte(int v) {
            if (subByte == -1) {
                subByte = v;
            } else {
                nextByte(((subByte << 3) | v) + 63);
                subByte = -1;
            }
        }

        @Override
        public void writeTinyVar(int v) {
            if (v < 0) {
                throw new IllegalArgumentException(v + "");
            }
            while (v > 3) {
                writeSubByte(( v & 3) | 4);
                v >>>= 2;
            }
            writeSubByte(v);
        }

        @Override
        public void writeVar(long v) {
            if (v < 0) {
                throw new IllegalArgumentException(v + "");
            }
            while (v > 0x1F) {
                writeByte(((int) v & 0x1F) | 0x20);
                v >>= 5;
            }
            writeByte((int) v);
        }

        @Override
        public void write36(long v) {
            if ((v & 0xFFFFFFF000000000L) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 6; i++) {
                writeByte((int) (v & 0x3F));
                v >>>= 6;
            }
        }

        @Override
        public void write(String data) {
            out.append(data);
            pos += data.length();
        }

        @Override
        public void print(long x) {
            out.print(x);
            pos += 8;
        }

        @Override
        public void print(String x) {
            out.print(x);
            pos += x.length();
        }

        @Override
        public int pos() {
            return pos;
        }

        @Override
        public PrintStream asPrintableStream() {
            return out;
        }
    }


    private static class Histogram {
        Map<String, int[]> data = new TreeMap<>();

        void add(int value, String name) {
            int[] histogram = data.get(name);
            if (histogram == null) {
                histogram = new int[1000];
                data.put(name, histogram);
            }

            value = value + 1;
            if (value >= histogram.length) {
                histogram[0]++;
            } else {
                histogram[value]++;
            }
        }

        void print() {
            for (Map.Entry<String, int[]> kv : data.entrySet()) {
                String name = kv.getKey();
                int[] histogram = kv.getValue();

                int count = 0;
                for (int i = 0; i < histogram.length; i++) {
                    if (histogram[i] != 0) {
                        count = i + 1;
                    }
                }
                System.out.println("Histogram " + name + ": ");
                for (int i = 1; i < count; i++) {
                    System.out.printf("%4d: %d\n", i - 1, histogram[i]);
                }
                System.out.printf(">999: %d\n", histogram[0]);
                System.out.println();
            }
        }
    }

}
