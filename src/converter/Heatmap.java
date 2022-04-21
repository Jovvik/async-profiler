/*
 * Copyright 2020 Andrei Pangin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import one.jfr.*;
import one.jfr.Dictionary;
import one.jfr.event.AllocationSample;
import one.jfr.event.ContendedLock;
import one.jfr.event.Event;
import one.jfr.event.ExecutionSample;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Heatmap {
    public String title = "Heatmap";

    private final JfrReader jfr;

    private final Root globalFrames = new Root();

    private final SampleCollection executions = new SampleCollection();
    private final SampleCollection allocations = new SampleCollection();
    private final SampleCollection locks = new SampleCollection();
    private final LinkedHashMap<Method, Method> methods = new LinkedHashMap<>();

    public Heatmap(JfrReader jfr) {
        this.jfr = jfr;
    }

    public void addSample(Event event) {

        SampleCollection collection;
        if (event instanceof ExecutionSample) {
            collection = executions;
            // not putTiny to have all events stored in the same way
            collection.putInt(((ExecutionSample) event).threadState);
        } else if (event instanceof AllocationSample) {
            if (true) return;
            collection = allocations;
            collection.putInt(((AllocationSample) event).classId);
        } else if (event instanceof ContendedLock) {
            collection = locks;
            collection.putInt(((ContendedLock) event).classId);
        } else {
            return;
        }

        int traceIndex = getTraceRemap(event);

        collection.putInt(traceIndex);
        collection.putInt(event.tid);
        collection.putInt((event.time - jfr.startTicks) * 1000 / jfr.ticksPerSec);
        collection.putInt(event.value());
    }

    private Method getMethod(long method, byte type, int location) {
        Method result = new Method(method, type, location);
        return getMethod(result);
    }

    private Method getMethod(Method method) {
        Method old = methods.get(method);
        if (old != null) {
            return old;
        }
        if (finish) {
            throw new IllegalStateException();
        }
        method.index = methods.size();
        methods.put(method, method);
        return method;
    }

    private int getTraceRemap(Event event) {
        Root globalFrame = globalFrames;

        int stackTraceId = event.stackTraceId;
        Integer remap = globalFrame.remap.get(stackTraceId);
        if (remap == null) {
            remap = globalFrame.remap.size();
            globalFrame.remap.put(stackTraceId, remap);
        }
        return remap;
    }

    public void dump(final PrintStream out) {
        time("evaluate", new Runnable() {
            @Override
            public void run() {
                globalFrames.evaluate();
            }
        });

        final String globalStacks = time("globalStacks", new PerfCallable<String>() {
            @Override
            public String call() {
                return globalStacks();
            }
        });
        // note: global stacks calculated first
        final String stacks = time("stacks", new PerfCallable<String>() {
            @Override
            public String call() {
                return stacks();
            }
        });

        List<String> symbolsOut = new ArrayList<>();
        final String methods = dumpMethods(symbolsOut);
        final StringBuilder constants = new StringBuilder();
        for (String s : symbolsOut) {
            constants.append("\"").append(s).append("\",");
        }

        final Map<String, String> values = time("mapOf", new PerfCallable<Map<String, String>>() {
            @Override
            public Map<String, String> call() {
                return mapOf(
                        "title", title,
                        "height", "300",
                        "reverse", "true",
                        "ticksPerSecond", 1000 + "",
                        "startSeconds", TimeUnit.NANOSECONDS.toSeconds(jfr.startNanos) + "",
                        "durationSeconds", TimeUnit.NANOSECONDS.toSeconds(jfr.endNanos + TimeUnit.SECONDS.toNanos(1) - 1) - TimeUnit.NANOSECONDS.toSeconds(jfr.startNanos) + "",
                        "executionsHeatmap", executions.toString(),
                        "allocationsHeatmap", allocations.toString(),
                        "locksHeatmap", locks.toString(),
                        "methods", methods,
                        "globalStacks", globalStacks,
                        "stacks", stacks,
                        "cpool", constants.toString()
                );
            }
        });
        System.out.println("Results: ");
        System.out.println("executions: " + size(executions.offset()));
        System.out.println("globalStacks: " + size(globalStacks.length()));
        System.out.println("stacks: " + size(stacks.length()));
        System.out.println("methods: " + size(methods.length()));
        System.out.println("cpool: " + size(constants.length()));
        time("dump", new Runnable() {
            @Override
            public void run() {
                dump(out, getResource("/heatmap.html"), values);
            }
        });
    }

    private void dump(PrintStream out, String template, Map<String, String> values) {
        for (int index = 0; ; ) {
            int next = template.indexOf("${", index);
            if (next == -1) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, next);

            int limit = template.indexOf("}", next + 2);
            String var = template.substring(next + 2, limit);
            if (var.startsWith("resource:")) {
                dump(out, getResource(var.substring("resource:".length())), values);
            } else {
                String value = values.get(var);
                out.append(value);
            }

            index = limit + 1;
        }
    }

    private static String size(int size) {
        if (size < 1024) {
            return size + "b";
        }
        size /= 1024;
        if (size < 1024) {
            return size + "kb";
        }
        size /= 1024;
        return size + "mb";
    }

    private static String time(long nano) {
        if (nano < 1000) {
            return nano + "ns";
        }
        nano /= 1000;
        if (nano < 1000) {
            return nano + "\u03BCs";
        }
        nano /= 1000;
        if (nano < 1000) {
            return nano + "ms";
        }
        nano /= 1000;
        return nano + "s";
    }

    private String dumpMethods(List<String> out) {
        out.add("");
        Dictionary<Integer> remap = new Dictionary<>();
        SampleCollection collection = new SampleCollection();
        for (Method method : methods.values()) {
            MethodRef methodRef = jfr.methods.get(method.method);
            if (methodRef == null) {
                collection.put30bit(0);
                collection.put30bit(3);
            } else {

                long classId = methodRef.cls;
                ClassRef classRef = jfr.classes.get(classId);
                int classIndex;
                int nameIndex;
                if (classRef == null) {
                    classIndex = 0;
                } else {
                    Integer remapId = remap.putIfAbsent(classRef.name, out.size());
                    if (remapId == null) {
                        byte[] className = jfr.symbols.get(classRef.name);
                        if (className == null) {
                            classIndex = 0;
                        } else {
                            classIndex = out.size();
                            out.add(new String(className, StandardCharsets.UTF_8));
                        }
                    } else {
                        classIndex = remapId;
                    }
                }

                Integer remapId = remap.putIfAbsent(methodRef.name, out.size());
                if (remapId == null) {
                    byte[] methodName = jfr.symbols.get(methodRef.name);
                    if (methodName == null) {
                        nameIndex = 0;
                    } else {
                        nameIndex = out.size();
                        out.add(new String(methodName, StandardCharsets.UTF_8));
                    }
                } else {
                    nameIndex = remapId;
                }

                collection.put30bit(classIndex);
                collection.put30bit(nameIndex);
            }
            collection.put36bit(method.location);
            collection.put6bit(Method.remapType(method.type));
        }
        return collection.toString();
    }

    private static String getResource(String name) {
        try (InputStream stream = Heatmap.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("No resource found");
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int length; (length = stream.read(buffer)) != -1; ) {
                result.write(buffer, 0, length);
            }
            return result.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("Can't load resource with name " + name);
        }
    }

    private static Map<String, String> mapOf(String... strings) {
        Map<String, String> result = new HashMap<>(strings.length / 2);
        for (int i = 0; i < strings.length; i += 2) {
            result.put(strings[i], strings[i + 1]);
        }
        return result;
    }

    private String globalStacks() {
        SampleCollection collection = new SampleCollection();
        collection.putInt(1);

        MethodTree tree = new MethodTree(0);
        int index = 0;

        MethodTree current = tree;

        for (Root globalFrame : Collections.singleton(globalFrames)) {
            if (globalFrame == null) {
                collection.putInt(0);
                continue;
            }

            collection.putInt(1);

            ArrayDeque<Frame> frames = new ArrayDeque<>();
            ArrayDeque<Integer> methods = new ArrayDeque<>();

            frames.push(globalFrame);
            int totalFrames = 0;
            methods.push(getMethod(Method.ROOT).index);
            finish = true;

            while (!frames.isEmpty()) {
                Frame frame = frames.pop();
                int method = methods.pop();
                long token = (((long)method) << 32) | frame.size();

                if (!current.containsKey(token)) {
                    collection.putInt(current.index);
                    collection.putInt(method);  // delta compression?
                    collection.putInt(frame.size());    // encode 1 into method?
                    current.put(token, new MethodTree(++index));
                    current = tree;
                } else {
                    current = current.get(token);
                }

                for (Map.Entry<Integer, Frame> entry : frame.entrySet()) {
                    frames.push(entry.getValue());
                    totalFrames++;
                    methods.push(entry.getKey());
                }
            }
            collection.putInt(current.index);
            System.out.println(totalFrames);
        }

        return collection.toString();
    }

    private String stacks() {
        SampleCollection stacks = new SampleCollection();
        List<Integer> stackOffsets = new ArrayList<>();

        for (Root globalFrame : Collections.singleton(globalFrames)) {
            for (int stackTraceId : globalFrame.remap.keySet()) {
                boolean trace = stackOffsets.size() == 278;
                stackOffsets.add(stacks.offset());

                if (trace) {
                    System.out.println("stackOffsets " + (stacks.offset() + 397805));
                }

                StackTrace stackTrace = jfr.stackTraces.get(stackTraceId);
                if (stackTrace == null) {
                    Frame child = globalFrame.child(getMethod(Method.BROKEN_STACK).index);
                    stacks.putInt(1);
                    stacks.putInt(child.index);
                    if (child.index == 0) {
                        stacks.putInt(1);
                    }
                    continue;
                }

                Frame currentFrame = globalFrame;

                long[] methods = stackTrace.methods;
                byte[] types = stackTrace.types;
                int[] locations = stackTrace.locations;
                if (trace) {
                    System.out.println("Stack length: " + methods.length);
                }
                stacks.putInt(methods.length);
                int zeroCount = 0;
                for (int i = 0; i < methods.length; i++) {
                    Method method = getMethod(methods[i], types[i], locations[i]);
                    currentFrame = currentFrame.child(method.index);
                    if (currentFrame.index == 0) {
                        if (zeroCount == 0) {
                            stacks.putInt(0);
                        }
                        zeroCount++;
                    } else {
                        if (zeroCount != 0) {
                            stacks.putInt(zeroCount);
                            zeroCount = 0;
                        }
                        stacks.putInt(currentFrame.index);
                    }
                }
                if (zeroCount != 0) {
                    stacks.putInt(zeroCount);
                }
            }
        }

        SampleCollection offsets = new SampleCollection();
        int offset = Collections.singleton(globalFrames).size() * 5;   // 5 bytes per record
        for (Root globalFrame : Collections.singleton(globalFrames)) {
            offsets.put30bit(offset);
            offset += globalFrame.remap.size() * 5;
        }

        System.out.println("offset " + offset);

        for (int stackOffset : stackOffsets) {
            offsets.put30bit(offset + stackOffset);
        }

        return SampleCollection.toString(offsets, stacks);
    }

    private static class SampleCollection {

        private static final int BUFFER_SIZE = 64 * 1024;

        private final List<byte[]> rope = new ArrayList<>();
        private byte[] current;
        int pos = BUFFER_SIZE;

        void put6bit(int v) {
            nextByte((byte) (v + 63)); // start from ? (+63) in ascii
        }

        void put30bit(int v) {
            for (int i = 0; i < 5; i++) {
                put6bit(v & 0x3F);
                v >>>= 6;
            }
        }

        void put36bit(long v) {
            for (int i = 0; i < 6; i++) {
                put6bit((int) (v & 0x3F));
                v >>>= 6;
            }
        }

        void putInt(int v) {
            if (v < 0) {
                throw new IllegalArgumentException(v + "");
            }
            while (v > 0x1F) {
                put6bit((v & 0x1F) | 0x20);
                v >>= 5;
            }
            put6bit(v);
        }

        void putInt(long v) {
            if (v < 0) {
                throw new IllegalArgumentException(v + "");
            }
            while (v > 0x1F) {
                put6bit(((int) v & 0x1F) | 0x20);
                v >>= 5;
            }
            put6bit((int) v);
        }

        int offset() {
            return (rope.size() - 1) * BUFFER_SIZE + pos;
        }

        public static String toString(SampleCollection... collections) {
            int size = 0;
            for (SampleCollection collection : collections) {
                size += collection.offset();
            }

            StringBuilder result = new StringBuilder(size);
            for (SampleCollection collection : collections) {
                collection.toString(result);
            }

            return result.toString();
        }

        void toString(StringBuilder out) {
            if (rope.isEmpty()) {
                return;
            }
            for (byte[] bytes : rope.subList(0, rope.size() - 1)) {
                for (byte b : bytes) {
                    out.append((char) b);   // TODO or allocate a string ?
                }
            }
            for (int i = 0; i < pos; i++) {
                out.append((char)current[i]);
            }
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder(offset());
            toString(result);
            return result.toString();
        }

        private void nextByte(byte b) {
            if (pos >= BUFFER_SIZE) {
                pos = 0;
                current = new byte[BUFFER_SIZE];
                rope.add(current);
            }
            current[pos++] = b;
        }
    }

    static class Method {
        static final Method BROKEN_STACK = new Method( -1, 4, 0);
        static final Method ROOT = new Method(-2, 4, 0);

        final long method;
        final int type;
        final int location;
        final int hash;

        int index;

        Method(long method, int type, int location) {
            this.method = method;
            this.type = type;
            this.location = location;

            hash = (int)(method >>> 32) ^ (int)(method) ^ location;
        }

        static int remapType(int type) {
            /*
                FIXME load it from jfr
                FRAME_INTERPRETED  = 0,
                FRAME_JIT_COMPILED = 1,
                FRAME_INLINED      = 2,
                FRAME_NATIVE       = 3,
                FRAME_CPP          = 4,
                FRAME_KERNEL       = 5,
             */
            switch (type) {
                case 0:
                case 1:
                    return 0;
                case 2:
                    return 1;
                default:
                case 3:
                    return 4;
                case 4:
                    return 3;
                case 5:
                    return 2;
            }
        }

        @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
        @Override
        public boolean equals(Object o) {
            Method m = (Method) o;
            return m.method == method && m.location == location;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    class Root extends Frame {

        LinkedHashMap<Integer, Integer> remap = new LinkedHashMap<>();

        Root() {
            super(0);
        }

        void evaluate() {
            for (int stackTraceId : remap.keySet()) {

                StackTrace stackTrace = jfr.stackTraces.get(stackTraceId);
                Frame currentFrame = this;  // root
                if (stackTrace == null) {
                    currentFrame.child(getMethod(Method.BROKEN_STACK).index);
                    continue;
                }

                long[] methods = stackTrace.methods;
                byte[] types = stackTrace.types;
                int[] locations = stackTrace.locations;
                for (int i = 0; i < methods.length; i++) {
                    Method method = getMethod(methods[i], types[i], locations[i]);
                    currentFrame = currentFrame.child(method.index);
                }
            }
        }
    }

    static boolean finish = false;

    static class Frame extends LinkedHashMap<Integer, Frame> {
        final int index;

        Frame(int index) {
            this.index = index;
        }

        Frame child(Integer frame) {
            Frame child = get(frame);
            if (child == null) {
                if (finish) {
                    throw new IllegalStateException();
                }
                child = new Frame(size());
                put(frame, child);
            }
            return child;
        }
    }

    static class MethodTree extends HashMap<Long, MethodTree> {
        final int index;

        MethodTree(int index) {
            this.index = index;
        }
    }

    private interface PerfCallable<T> {
        T call();
    }

    private static <T> T time(String name, PerfCallable<T> callable) {
        long time = System.nanoTime();
        try {
            return callable.call();
        } finally {
            System.out.println(name + ": " + time(System.nanoTime() - time));
        }
    }

    private static void time(String name, Runnable runnable) {
        long time = System.nanoTime();
        try {
            runnable.run();
        } finally {
            System.out.println(name + ": " + time(System.nanoTime() - time));
        }
    }

}
