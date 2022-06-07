import one.jfr.Dictionary;
import one.jfr.*;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SimpleHeatmap extends ResourceProcessor {

    private static final int UNKNOWN_ID = -1;
    private static final byte[] UNKNOWN_METHOD_NAME = "<UnknownMethod>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNKNOWN_CLASS_NAME = "<UnknownClass>".getBytes(StandardCharsets.UTF_8);
    private static final StackTrace UNKNOWN_STACK = new StackTrace(new long[]{UNKNOWN_ID}, new byte[]{3}, new int[]{0});
    private static final MethodRef UNKNOWN_METHOD_REF = new MethodRef(UNKNOWN_ID, UNKNOWN_ID, UNKNOWN_ID);
    private static final ClassRef UNKNOWN_CLASS_REF = new ClassRef(UNKNOWN_ID);

    private final List<ExecutionSample> executions = new ArrayList<>();

    private final String title;

    private Dictionary<StackTrace> stacks;
    private Dictionary<MethodRef> methodRefs;
    private Dictionary<ClassRef> classRefs;
    private Dictionary<byte[]> symbols;

    private long startMs;
    private long startTicks;
    private long ticksPerSec;

    public SimpleHeatmap(String title) {
        this.title = title;
    }

    public void addEvent(Event event) {
        if (event instanceof ExecutionSample) {
            executions.add((ExecutionSample) event);
            return;
        }
        if (event instanceof AllocationSample) {
            //allocations.add((AllocationSample) event);
            return;
        }
        if (event instanceof ContendedLock) {
            //locks.add((ContendedLock) event);
            return;
        }
        throw new IllegalArgumentException("Unknown event: " + event);
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
        Collections.sort(executions);

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

        long duration = ms(executions.get(executions.size() - 1).time);
        Block[] blocks = new Block[(int) (duration / blockDurationMs) + 1];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new Block(rootMethodId, -2);
        }

        for (ExecutionSample execution : executions) {
            long timeMs = ms(execution.time);
            Block block = blocks[(int) (timeMs / blockDurationMs)];
            block.totalCount++;

            int[] stackTrace = stackTraces.get(execution.stackTraceId);

            if (stackTrace == null) {
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

            for (int methodId : stackTrace) {
                block = block.getOrCreate(methodId, execution.stackTraceId);
                block.totalCount++;
            }
            block.selfCount++;
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

    // MUTATES BLOCKS!
    private static Block sum(List<Block> blocks) {
        Block globalTo = blocks.get(0);
        for (Block globalFrom : blocks.subList(1, blocks.size())) {
            final ArrayDeque<Block> currentResults = new ArrayDeque<>();
            currentResults.push(globalTo);
            currentResults.push(globalFrom);

            do {
                final Block nextFrom = currentResults.pop();
                final Block nextTo = currentResults.pop();
                nextTo.totalCount += nextFrom.totalCount;
                nextTo.selfCount += nextFrom.selfCount;

                nextFrom.forEach(new Dictionary.Visitor<Block>() {
                    @Override
                    public void visit(long key, Block from) {
                        Block to = nextTo.get(from.methodId);
                        if (to == null) {
                            nextTo.putNew(from.methodId, from);
                        } else {
                            currentResults.push(to);
                            currentResults.push(from);
                        }
                    }
                });
            } while (!currentResults.isEmpty());
        }

        return globalTo;
    }

    private static List<Block> sum(final List<Block> blocks, final int blocksInBatch) {
        final List<Block> result = new ArrayList<>(blocks.size() / blocksInBatch + 1);
        for (int i = 0; i < blocks.size(); ) {
            int next = Math.min(i + blocksInBatch, blocks.size());
            result.add(sum(blocks.subList(i, next)));
            i = next;
        }
        return result;
    }

    private void preprocessAndWriteHeads(Output out, Block block, EvaluationContext ctx) {
        int pos = out.pos();
        out.writeVar(block.totalCount);
        if (block.totalCount == 0) {
            out.writeBack(pos);
            return;
        }

        ArrayDeque<Block> stack = ctx.blockDeque;
        stack.add(block);

        Block[] heads = ctx.tmp;
        int count = 0;

        while (!stack.isEmpty()) {
            Block current = stack.removeLast();
            current.used = false;
            if (current.children == null) {
                if (heads.length == count) {
                    ctx.tmp = heads = Arrays.copyOf(ctx.tmp, count * 2);
                }
                heads[count++] = current;
            } else {
                current.forEach(ctx.addToQueue);
            }
        }

        Arrays.sort(heads, 0, count, ctx.STACK_COMPARATOR);
        if (heads.length <= count * 2) {
            ctx.tmp = heads = Arrays.copyOf(ctx.tmp, count * 2);
        }
        out.writeVar(count);

        int childrenDelta = count;
        for (int i = 0; i < count; i++) {
            heads[i + childrenDelta] = block;
        }

        int prevStackId = 0;
        for (int i = 0; i < count; i++) {
            Block head = heads[i];
            int stackId = ctx.usedStacks.index(head.stackId);
            out.writeVar(stackId - prevStackId - 1);
            prevStackId = stackId;
        }

        int level = 0;
        int nullCount = 0;
        while (nullCount < count) {
            for (int i = 0; i < count; i++) {
                Block head = heads[i];
                if (head == null) {
                    continue;
                }
                Block parent = heads[i + childrenDelta];

                int[] methods = ctx.stackTraces.get(head.stackId);
                if (methods.length <= level) {
                    heads[i] = null;
                    nullCount++;
                    continue;
                }

                int methodId = methods[level];
                Block child = parent.get(methodId);
                assert child != null;
                if (!child.used) {
                    child.used = true;
                    out.writeVar(child.totalCount);
                    if (child.totalCount == 1) {
                        heads[i] = null;
                        nullCount++;
                        continue;
                    }
                }

                heads[i + childrenDelta] = child;
            }
            if (nullCount >= count / 2) {
                for (int left = 0, right = 0; right < count; right++) {
                    if (heads[right] != null) {
                        heads[left + childrenDelta] = heads[right + childrenDelta];
                        heads[left] = heads[right];
                        left++;
                    }
                }

                count -= nullCount;
                nullCount = 0;
            }

            level++;
        }

        out.writeBack(pos);
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

    private void printHeatmap(Output out, EvaluationContext evaluationContext) {

        int pos = out.pos();

        pos = writeBlocks(out, evaluationContext, pos, 0);      // 20ms
        pos = writeBlocks(out, evaluationContext, pos, 50);     // 1s
        pos = writeBlocks(out, evaluationContext, pos, 60);     // 1m
        pos = writeBlocks(out, evaluationContext, pos, 60);     // 1h

        int maxZoom = 3;
        out.write30(maxZoom);
    }

    private int writeBlocks(Output out, EvaluationContext context, int pos, int batchSize) {
        if (batchSize != 0) {
            context.blocks = sum(context.blocks, batchSize);
        }
        for (Block block : context.blocks) {
            preprocessAndWriteHeads(out, block, context);
        }
        out.write30(batchSize);
        out.write30(context.blocks.size());
        return out.writeBack(pos);
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
        int[] stackIds = new int[evaluationContext.usedStacks.size()];
        evaluationContext.usedStacks.orderedKeys(stackIds);

        int nextId = 0;
        LzNode root = new LzNode(nextId++);
        LzNode next = new LzNode(nextId++);

        out.writeVar(stackIds.length);
        for (int stackId : stackIds) {
            LzNode current = root;
            int[] stack = evaluationContext.stackTraces.get(stackId);
            out.writeVar(stack.length);

            for (int method : stack) {
                int prevId = current.id;
                current = current.putIfAbsent(method, next);
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

    private static class Block {

        boolean used;
        int totalCount = 0;
        int selfCount = 0;
        final int methodId;
        final int stackId;

        Object children;

        Block(int methodId, int stackId) {
            this.methodId = methodId;
            this.stackId = stackId;
        }

        public Block getOrCreate(int newMethodId, int stackTraceId) {
            if (children == null) {
                Block child = new Block(newMethodId, stackTraceId);
                this.children = child;
                return child;
            }
            if (children.getClass() == Block.class) {
                Block old = (Block) children;
                if (old.methodId == newMethodId) {
                    return old;
                }
                Block child = new Block(newMethodId, stackTraceId);
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
                    Block child = new Block(newMethodId, stackTraceId);
                    children[index] = child;
                    return child;
                }
                if (children.length < 16) {
                    children = Arrays.copyOf(children, children.length * 2);
                    this.children = children;

                    Block child = new Block(newMethodId, stackTraceId);
                    children[index] = child;
                    return child;
                }
                Dictionary<Block> newChildren = new Dictionary<>(children.length * 2);
                for (Block block : children) {
                    newChildren.put(block.methodId, block);
                }
                Block child = new Block(newMethodId, stackTraceId);
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
            Block child = new Block(newMethodId, stackTraceId);
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

        public void putNew(int newMethodId, Block child) {
            if (children == null) {
                this.children = child;
                return;
            }
            if (children.getClass() == Block.class) {
                this.children = new Block[]{(Block) children, child};
                return;
            }
            if (children.getClass().isArray()) {
                Block[] children = (Block[]) this.children;
                int index = 0;
                for (Block block : children) {
                    if (block == null) {
                        break;
                    }
                    index++;
                }
                if (index < children.length) {
                    children[index] = child;
                    return;
                }
                if (children.length < 16) {
                    children = Arrays.copyOf(children, children.length * 2);
                    this.children = children;
                    children[index] = child;
                    return;
                }
                Dictionary<Block> newChildren = new Dictionary<>(children.length * 2);
                for (Block block : children) {
                    newChildren.put(block.methodId, block);
                }
                newChildren.put(newMethodId, child);
                this.children = newChildren;
                return;
            }

            @SuppressWarnings("unchecked")
            Dictionary<Block> children = (Dictionary<Block>) this.children;
            children.put(newMethodId, child);
        }

        public void forEach(Dictionary.Visitor<Block> blockVisitor) {
            if (children == null) {
                return;
            }
            if (children.getClass() == Block.class) {
                Block old = (Block) children;
                blockVisitor.visit(old.methodId, old);
                return;
            }
            if (children.getClass().isArray()) {
                Block[] children = (Block[]) this.children;
                for (Block block : children) {
                    if (block == null) {
                        break;
                    }
                    blockVisitor.visit(block.methodId, block);
                }
                return;
            }

            @SuppressWarnings("unchecked")
            Dictionary<Block> children = (Dictionary<Block>) this.children;
            children.forEach(blockVisitor);
        }

        public int size() {
            if (children == null) {
                return 0;
            }
            if (children.getClass() == Block.class) {
                return 1;
            }
            if (children.getClass().isArray()) {
                Block[] children = (Block[]) this.children;
                return children.length;
            }

            @SuppressWarnings("unchecked")
            Dictionary<Block> children = (Dictionary<Block>) this.children;
            return children.size();
        }

        @Override
        public String toString() {
            final StringBuilder children = new StringBuilder();
            forEach(new Dictionary.Visitor<Block>() {
                @Override
                public void visit(long key, Block value) {
                    children.append("'").append(key).append("': ").append(value).append(",");
                }
            });
            if (children.length() > 0) {
                children.setLength(children.length() - 1);
            }
            return "{'c': " + totalCount + ", 'children':{" + children + "}}";
        }
    }

    private static class EvaluationContext {
        final Index<Method> methods;
        final byte[][] symbols;
        final ArrayDeque<Block> blockDeque = new ArrayDeque<>(10 * 1024);
        final IndexInt usedStacks = new IndexInt();
        final Dictionary<int[]> stackTraces;

        List<Block> blocks;
        Block[] tmp = new Block[10 * 1024];

        final Dictionary.Visitor<Block> addToQueue = new Dictionary.Visitor<Block>() {
            @Override
            public void visit(long key, Block value) {
                blockDeque.addLast(value);
            }
        };

        final Comparator<? super Block> STACK_COMPARATOR = new Comparator<Block>() {
            @Override
            public int compare(Block o1, Block o2) {
                // TODO optimize?
                return Long.compare(usedStacks.index(o1.stackId), usedStacks.index(o2.stackId));
            }
        };

        private EvaluationContext(List<Block> blocks, Index<Method> methods, Dictionary<int[]> stackTraces, byte[][] symbols) {
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

        int pos();

        int writeBack(int prevPos);

        PrintStream asPrintableStream();

        void print(long x);
        void print(String x);
    }

    public static class HtmlOut implements Output {

        private final PrintStream out;
        private int pos = 0;

        public HtmlOut(PrintStream out) {
            this.out = out;
        }

        private void nextByte(int ch) {
            out.append((char) ch);
            pos++;
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
            pos += data.length();
        }

        @Override
        public int pos() {
            return pos;
        }

        @Override
        public int writeBack(int prevPos) {
            write30(pos - prevPos);
            return pos;
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
