import one.jfr.*;
import one.jfr.event.AllocationSample;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SimpleHeatmap extends ResourceProcessor {

    private static final int UNKNOWN_ID = -1;
    private static final byte[] UNKNOWN_METHOD_NAME = "<UnknownMethod>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNKNOWN_CLASS_NAME = "<UnknownClass>".getBytes(StandardCharsets.UTF_8);
    private static final StackTrace UNKNOWN_STACK = new StackTrace(new long[]{UNKNOWN_ID}, new byte[]{3}, new int[]{0});
    private static final MethodRef UNKNOWN_METHOD_REF = new MethodRef(UNKNOWN_ID, UNKNOWN_ID, UNKNOWN_ID);
    private static final ClassRef UNKNOWN_CLASS_REF = new ClassRef(UNKNOWN_ID);

    private final List<Event> samples = new ArrayList<>();

    private final String title;
    private final boolean alloc;

    private Dictionary<StackTrace> stacks;
    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private long startMs;
    private long startTicks;
    private long ticksPerSec;

    public SimpleHeatmap(String title, boolean alloc) {
        this.title = title;
        this.alloc = alloc;
    }

    public void addEvent(Event event) {
        if (alloc) {
            if (event instanceof AllocationSample) {
                samples.add(event);
            }
        } else {
            if (event instanceof ExecutionSample) {
                samples.add(event);
            }
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
        Collections.sort(samples);

        final Index<byte[]> symbols = new Index<byte[]>() {
            @Override
            protected int hashCode(byte[] key) {
                return Arrays.hashCode(key);
            }
        };
        symbols.preallocate(this.symbols.size());
        Method rootMethod = new Method(symbols.index("all".getBytes()), symbols.index(new byte[0]));

        final Index<Method> methodIndex = new Index<>();

        int rootMethodId = methodIndex.index(rootMethod);

        Dictionary<int[]> stackTraces = new Dictionary<>();

        long durationExecutions = samples.isEmpty() ? 0 : ms(samples.get(samples.size() - 1).time);
        SampleBlock[] blocks = new SampleBlock[(int) (durationExecutions / blockDurationMs) + 1];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new SampleBlock();
        }

        for (Event execution : samples) {
            long timeMs = ms(execution.time);
            SampleBlock block = blocks[(int) (timeMs / blockDurationMs)];
            block.stacks.add(execution.stackTraceId);

            int[] stackTrace = stackTraces.get(execution.stackTraceId);

            if (stackTrace != null) {
                continue;
            }

            StackTrace originalTrace = stacks.getOrDefault(execution.stackTraceId, UNKNOWN_STACK);
            stackTrace = new int[originalTrace.methods.length];

            for (int i = originalTrace.methods.length - 1; i >= 0; i--) {
                long methodId = originalTrace.methods[i];
                byte type = originalTrace.types[i];
                int location = originalTrace.locations[i];

                MethodRef methodRef = methodRefs.getOrDefault(methodId, UNKNOWN_METHOD_REF);
                ClassRef classRef = classRefs.getOrDefault(methodRef.cls, UNKNOWN_CLASS_REF);
                int className = symbols.index(this.symbols.getOrDefault(classRef.name, UNKNOWN_CLASS_NAME));
                int methodName = symbols.index(this.symbols.getOrDefault(methodRef.name, UNKNOWN_METHOD_NAME));

                Method method = new Method(className, methodName, location, type);
                stackTrace[originalTrace.methods.length - 1 - i] = methodIndex.index(method);
            }

            stackTraces.put(execution.stackTraceId, stackTrace);
        }

        byte[][] symbolBytes = new byte[symbols.size()][];
        symbols.orderedKeys(symbolBytes);

        return new EvaluationContext(
                Arrays.asList(blocks),
                methodIndex,
                stackTraces,
                symbolBytes
        );
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
        printHeatmap(out, evaluationContext);

        tail = printTill(out, tail, "/*globalStacks:*/");
        printGlobalStacks(out, evaluationContext);

        tail = printTill(out, tail, "/*methods:*/");
        printMethods(out, evaluationContext);

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
        printConstantPool(out, evaluationContext);

        tail = printTill(out, tail, "/*end if heatmap js*/");

        tail = printTill(out, tail, "/*frames:*/");
        out.print(tail);

    }

    private void printHeatmap(Output out, EvaluationContext context) {
        int maxZoom = 3;
        out.writeVar(maxZoom);

        int nextId = 0;
        LzNode root = new LzNode(nextId++);
        LzNode next = new LzNode(nextId++);

        out.writeVar(context.blocks.size());
        for (SampleBlock block : context.blocks) {
            out.writeVar(block.stacks.size);

            for (int i = 0; i < block.stacks.size; i++) {
                int stackId = block.stacks.list[i];
                int[] stack = context.stackTraces.get(stackId);
                out.writeVar(stack.length);

                LzNode current = root;

                for (int method : stack) {
                    int prevId = current.id;
                    current = current.putIfAbsent(method + 1, next);
                    if (current == null) {
                        current = root;
                        next = new LzNode(nextId++);
                        out.writeVar(prevId);
                        out.writeVar(method);
                    }
                }

                if (current != root) {
                    out.writeVar(current.id);
                }
            }
        }
    }

    private void printConstantPool(Output out, EvaluationContext evaluationContext) {
        for (byte[] symbol : evaluationContext.symbols) {
            out.write("\"");
            out.write(new String(symbol, StandardCharsets.UTF_8));
            out.write("\",");
        }
    }

    private void printMethods(Output out, EvaluationContext evaluationContext) {
        Method[] methods = new Method[evaluationContext.methods.size()];
        evaluationContext.methods.orderedKeys(methods);

        compressMethods(out, methods);
    }

    private void printGlobalStacks(Output out, EvaluationContext evaluationContext) {
    }

    private static String printTill(Output out, String tail, String till) {
        return printTill(out.asPrintableStream(), tail, till);
    }

    private static class Method {

        final int className;
        final int methodName;
        final int location;
        final byte type;

        Method(int className, int methodName) {
            this(className, methodName, 0, (byte) 3);
        }

        Method(int className, int methodName, int location, byte type) {
            this.className = className;
            this.methodName = methodName;
            this.location = location;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Method method = (Method) o;

            if (className != method.className) return false;
            if (methodName != method.methodName) return false;
            if (location != method.location) return false;
            return type == method.type;
        }

        @Override
        public int hashCode() {
            int result = className;
            result = 31 * result + methodName;
            result = 31 * result + location;
            result = 31 * result + (int) type;
            return result;
        }
    }

    private static class LzNode extends Dictionary<LzNode> {
        final int id;

        private LzNode(int id) {
            this.id = id;
        }
    }

    private static class SampleBlock {
        IntList stacks = new IntList();
    }

    private static class Block {

        final Block parent;
        final int methodId;

        int stackId = -2;

        Object children;

        Block(Block parent, int methodId) {
            this.parent = parent;
            this.methodId = methodId;
        }

        public Block getOrCreate(Block parent, int newMethodId) {
            if (children == null) {
                Block child = new Block(parent, newMethodId);
                this.children = child;
                return child;
            }
            if (children.getClass() == Block.class) {
                Block old = (Block) children;
                if (old.methodId == newMethodId) {
                    return old;
                }
                Block child = new Block(parent, newMethodId);
                this.children = new Block[]{old, child};
                return child;
            }
            if (children.getClass().isArray()) {
                Block[] children = (Block[]) this.children;
                int index = 0;
                for (Block block : children) {
                    if (block == null) {
                        break;
                    }
                    if (block.methodId == newMethodId) {
                        return block;
                    }
                    index++;
                }
                if (index < children.length) {
                    Block child = new Block(parent, newMethodId);
                    children[index] = child;
                    return child;
                }
                if (children.length < 16) {
                    children = Arrays.copyOf(children, children.length * 2);
                    this.children = children;

                    Block child = new Block(parent, newMethodId);
                    children[index] = child;
                    return child;
                }
                Dictionary<Block> newChildren = new Dictionary<>(children.length * 2);
                for (Block block : children) {
                    newChildren.put(block.methodId, block);
                }
                Block child = new Block(parent, newMethodId);
                newChildren.put(newMethodId, child);
                this.children = newChildren;
                return child;
            }

            @SuppressWarnings("unchecked")
            Dictionary<Block> children = (Dictionary<Block>) this.children;
            Block old = children.get(newMethodId);
            if (old != null) {
                return old;
            }
            Block child = new Block(parent, newMethodId);
            children.put(newMethodId, child);
            return child;
        }

        public Block get(int methodId) {
            if (children == null) {
                return null;
            }
            if (children.getClass() == Block.class) {
                Block old = (Block) children;
                return old.methodId == methodId ? old : null;
            }
            if (children.getClass().isArray()) {
                Block[] children = (Block[]) this.children;
                for (Block block : children) {
                    if (block == null) {
                        break;
                    }
                    if (block.methodId == methodId) {
                        return block;
                    }
                }
                return null;
            }

            @SuppressWarnings("unchecked")
            Dictionary<Block> children = (Dictionary<Block>) this.children;
            return children.get(methodId);
        }
    }

    private static class EvaluationContext {
        final Index<Method> methods;
        final IndexInt methodsReindex = new IndexInt();
        final byte[][] symbols;
        final IndexInt usedStacks = new IndexInt();
        final Dictionary<int[]> stackTraces;

        List<SampleBlock> blocks;

        private EvaluationContext(List<SampleBlock> blocks, Index<Method> methods, Dictionary<int[]> stackTraces, byte[][] symbols) {
            this.blocks = blocks;
            this.methods = methods;
            this.stackTraces = stackTraces;
            this.symbols = symbols;
        }
    }

    private interface Output {
        void writeVar(long v);

        void write30(int v);

        void write36(long v);

        void write(String data);

        PrintStream asPrintableStream();

        void print(long x);
        void print(String x);
    }

    public static class HtmlOut implements Output {

        private final PrintStream out;

        public HtmlOut(PrintStream out) {
            this.out = out;
        }

        private void nextByte(int ch) {
            out.append((char) ch);
        }

        private void write6(int v) {
            nextByte(v + 63); // start from ? (+63) in ascii
        }

        @Override
        public void writeVar(long v) {
            if (v < 0) {
                throw new IllegalArgumentException(v + "");
            }
            while (v > 0x1F) {
                write6(((int) v & 0x1F) | 0x20);
                v >>= 5;
            }
            write6((int) v);
        }

        @Override
        public void write30(int v) {
            if ((v & 0xC0000000) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 5; i++) {
                write6(v & 0x3F);
                v >>>= 6;
            }
        }

        @Override
        public void write36(long v) {
            if ((v & 0xFFFFFFF000000000L) != 0) {
                throw new IllegalArgumentException("Value " + v + " is out of bounds");
            }
            for (int i = 0; i < 6; i++) {
                write6((int) (v & 0x3F));
                v >>>= 6;
            }
        }

        @Override
        public void write(String data) {
            out.append(data);
        }

        @Override
        public void print(long x) {
            out.print(x);
        }

        @Override
        public void print(String x) {
            out.print(x);
        }

        @Override
        public PrintStream asPrintableStream() {
            return out;
        }
    }

}
