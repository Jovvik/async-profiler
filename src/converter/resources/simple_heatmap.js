class DataBuffer {
    data;
    pos = 0;
    constructor(encodedData) {
        this.data = encodedData;
    }

    hasMore() {
        return this.pos < this.data.length;
    }

    nextVarInt() {
        let res = 0;
        let shift = 0;
        let b;
        do {
            b = this.byteAt(this.pos++);
            res |= (b & 0x1F) << shift
            shift += 5;
        } while (b >= 0x20)
        return res;
    }

    nextSignedVarInt() {
        let r = this.nextVarInt()
        let s = r & 1;
        r >>>= 1;
        return s === 0 ? r : -r;
    }

    byteAt(pos) {
        return this.data.charCodeAt(pos) - 63;
    }

    int30(pos) {
        return (this.byteAt(pos++) << 0)
            | (this.byteAt(pos++) << 6)
            | (this.byteAt(pos++) << 12)
            | (this.byteAt(pos++) << 18)
            | (this.byteAt(pos) << 24);
    }

    int24(pos) {
        return (this.byteAt(pos++) << 0)
            | (this.byteAt(pos++) << 6)
            | (this.byteAt(pos++) << 12)
            | (this.byteAt(pos) << 18);
    }

    int36(pos) {
        return (this.byteAt(pos++) << 0)
            | (this.byteAt(pos++) << 6)
            | (this.byteAt(pos++) << 12)
            | (this.byteAt(pos++) << 18)
            | (this.byteAt(pos++) << 24)
            | (this.byteAt(pos++) << 30);
    }

    varInt(pos) {
        let res = 0;
        let shift = 0;
        let b;
        do {
            b = this.byteAt(pos++);
            res |= (b & 0x1F) << shift
            shift += 5;
        } while (b >= 0x20)
        return res;
    }

}

class StackDecodingContext extends DataBuffer {
    stackIds;
    leafs;

    root = new Map();
    methodsCount = new Map();
    currentLevel = 0;
    maxLevel = 0;
    found = 0;

    constructor(encodedData, pos) {
        super(encodedData);
        this.pos = pos;

        let totalSize = this.nextVarInt();
        this.root.c = totalSize;
        if (totalSize === 0) {
            this.leafs = [];
            this.stackIds =[];
            return;
        }

        let stackCount = this.nextVarInt();

        this.stackIds = new Array(stackCount);
        this.leafs = new Array(stackCount);

        let prevStackId = 0;
        for (let index = 0; index < stackCount; index++) {
            prevStackId += this.nextVarInt() + 1
            this.stackIds[index] = prevStackId;
            this.leafs[index] = this.root;
            this.maxLevel = Math.max(this.maxLevel, globalStacks[prevStackId - 1].length);
        }
    }

    trim() {
        let left = 0;
        for (let right = 0; right < this.stackIds.length; right++) {
            if (this.stackIds[right] !== null) {
                this.stackIds[left] = this.stackIds[right];
                this.leafs[left] = this.leafs[right];
                left++;
            }
        }
        this.stackIds.splice(left);
        this.leafs.splice(left);
    }

    loadIteration(nextStack) {
        let currentStackId = this.stackIds[nextStack];
        if (currentStackId === null) {
            return true;
        }

        let currentStack = globalStacks[currentStackId - 1];
        if (this.currentLevel >= currentStack.length) {
            return true;
        }

        let methodId = currentStack[this.currentLevel];
        let count = this.methodsCount.get(methodId)
        this.methodsCount.set(methodId, count === undefined ? 1 : count + 1);
        let currentLeaf = this.leafs[nextStack];
        let nextLeaf = currentLeaf.get(methodId);
        if (nextLeaf !== undefined) {
            this.leafs[nextStack] = nextLeaf;
            return false;
        }
        nextLeaf = new Map();
        let total = this.nextVarInt();
        nextLeaf.c = total;

        currentLeaf.set(methodId, nextLeaf);

        if (total === 1) {
            for (let i = this.currentLevel + 1; i < currentStack.length; i++) {
                methodId = currentStack[i];
                currentLeaf = nextLeaf;
                nextLeaf = new Map();
                nextLeaf.c = total;
                currentLeaf.set(methodId, nextLeaf);
            }

            return true;
        }

        this.leafs[nextStack] = nextLeaf;
        return false;
    }

    loadLevel() {
        let nullsCount = 0;
        for (let nextStack = 0; nextStack < this.stackIds.length; nextStack++) {
            if (this.loadIteration(nextStack)) {
                this.stackIds[nextStack] = null;
                nullsCount++;
            }
        }

        if (nullsCount > this.stackIds.length / 2) {
            this.trim();
        }

        this.currentLevel++;
    }

    load(maxLevel) {
        while (this.stackIds.length !== 0 && maxLevel >= this.currentLevel) {
            this.loadLevel();
        }
        return this.stackIds.length !== 0;
    }

    performSearch(r) {
        this.found = 0;
        for (let [k, v] of this.methodsCount) {
            if (r.test(title(k))) {
                this.found += v;
            }
        }
        return this.found;
    }
}

class HeatmapCollection extends DataBuffer {

    prevZoomStartPos;
    zoomToPositions;
    zoomToGroupSize;
    zoomToStackDecoding;
    maxZoom;
    minZoom;
    zoom;

    constructor(encodedData) {
        super(encodedData)
        this.prevZoomStartPos = this.data.length - 5;
        this.maxZoom = this.int30(this.prevZoomStartPos)
        this.minZoom = this.maxZoom + 1
        this.zoomToPositions = new Array(this.maxZoom + 1)
        this.zoomToGroupSize = new Array(this.maxZoom + 1)
        this.zoomToStackDecoding = new Array(this.maxZoom + 1)
        for (let i = 0; i <= this.maxZoom; i++) {
            this.zoomToStackDecoding[i] = new Map();
        }
    }

    setZoom(zoom) {
        this.zoom = zoom;
    }

    heatmap() {
        let zoom = this.zoom;
        while (this.minZoom > zoom) {
            let pos = this.prevZoomStartPos;
            this.minZoom--;

            this.prevZoomStartPos = pos - this.int30(pos - 5) - 5;

            let count = this.int30(pos - 10);
            let groupSize = this.int30(pos - 15);
            let positions = new Array(count);
            pos -= 20;

            let maxValue = 0;
            for (let i = count - 1; i >= 0; i--) {
                pos = pos - this.int30(pos) - 5;
                positions[i] = pos + 5;
                maxValue = Math.max(maxValue, this.varInt(pos + 5));
            }

            positions.max = maxValue;
            this.zoomToPositions[this.minZoom] = positions;
            this.zoomToGroupSize[this.minZoom] = groupSize;
        }
        return this.zoomToPositions[zoom];
    }

    context(i, zoom) {
        let stackDecodings = this.zoomToStackDecoding[zoom];
        let decodingContext = stackDecodings.get(i);
        if (decodingContext === undefined) {
            decodingContext = new StackDecodingContext(this.data, this.zoomToPositions[zoom][i]);
            stackDecodings.set(i, decodingContext);
        }
        return decodingContext;
    }

    collectFramesImpl(from, to, zoom) {
        if (from > to) {
            return [];
        }
        let result = new Array(to - from + 1);
        let r = 0;
        for (let i = from; i <= to; i++) {
            result[r++] = this.context(i, zoom);
        }

        return result;
    }

    collectFramesForZoom(from, to, zoom) {
        if (zoom < this.maxZoom) {
            let groupSize = this.zoomToGroupSize[zoom + 1];
            let fromNextZoom = Math.floor((from - 1) / groupSize) + 1;
            let toNextZoom = Math.floor((to - (groupSize - 1)) / groupSize);
            if (fromNextZoom <= toNextZoom) {
                let head = this.collectFramesImpl(from, fromNextZoom * groupSize, zoom);
                let middle = this.collectFramesForZoom(fromNextZoom, toNextZoom, zoom + 1);
                let tail = this.collectFramesImpl(toNextZoom * groupSize, to, zoom);
                return middle.concat(head, tail);
            }
        }

        return this.collectFramesImpl(from, to, zoom);
    }

    collectFrames(from, to) {
        this.heatmap(); // to ensure cached

        return this.collectFramesForZoom(from, to, this.zoom);
    }

    totalAt(pos) {
        return this.varInt(pos)
    }

    foundAt(index) {
        return this.context(index, this.zoom).found;
    }

    performSearchAt(index, regex) {
        let c = this.context(index, this.zoom);
        c.load(c.maxLevel);
        return c.performSearch(regex);
    }

}

class LzChunk {
    data;

    copyWithNext(element) {
        let result = new LzChunk();
        result.data = Array.from(this.data);
        result.data.push(element);
        return result;
    }
}

class Lz78Data {
    data;
    lz78;

    constructor(data) {
        this.data = data;

        let empty = new LzChunk();
        empty.data = [];
        this.lz78 = [empty];
    }

    readArray() {
        let size = this.data.nextVarInt();
        let result = new Array(size);
        let index = 0;
        while (index < size) {
            let subTreeId = this.data.nextVarInt();
            let subTree = this.lz78[subTreeId];
            for (let e of subTree.data) {
                result[index++] = e;
            }
            if (index < size) {
                let e = this.data.nextVarInt();
                result[index++] = e;
                this.lz78.push(subTree.copyWithNext(e));
            }
        }
        return result;
    }
}

class Queue {
    data = new Array(1024);
    first = 0;
    afterLast = 0;

    push(e) {
        let a = this.afterLast;
        let d = this.data;
        d[a++] = e;
        if (a >= this.data.length) {
            a = 0;
        }
        if (a === this.first) {
            let d2 = new Array(d.length * 2);
            let i = 0;
            for (let p = a; p < d.length; p++) {
                d2[i++] = d[p];
            }
            for (let p = 0; p < a; p++) {
                d2[i++] = d[p];
            }
            this.data = d2;
            this.first = 0;
            this.afterLast = i;
        } else {
            this.afterLast = a;
        }
    }

    shift() {
        let i = this.first;
        let r = this.data[i++];
        if (i >= this.data.length) {
            this.first = 0;
        } else {
            this.first = i;
        }
        return r;
    }

    size() {
        let r = this.afterLast - this.first;
        if (r < 0) {
            return r + this.data.length;
        }
        return r;
    }
}

function decodeGlobalStacks(data) {
    let count = data.nextVarInt();
    let result = new Array(count);

    let lz = new Lz78Data(data);
    for (let i = 0; i < count; i++) {
        result[i] = lz.readArray();
    }
    return result;
}

function claimHtml(id) {
    let e = document.getElementById(id);
    let r = e.innerHTML;
    e.remove();
    return r;
}

const sq = 5;
let sqW = sq;
let sqH = sq;
let startMs = /*startMs:*/0;
let ticksPerSecond = /*ticksPerSecond:*/1;

let executionsHeatmap = new HeatmapCollection(claimHtml("executionsHeatmap"));
let globalStacks = decodeGlobalStacks(new DataBuffer(claimHtml("globalStacks")));
let methods = new DataBuffer(claimHtml("methods"));
let cpool = [/*cpool:*/];

let currentHeatmap = executionsHeatmap;
let currentHeatmapType = 'executions';

let heatHeight = 60;

let heatLastSample = -1;
let heatActiveSample1 = -1;
let heatActiveSample2 = -1;
let heatDiffStart = -1;
let heatDiffEnd = -1;
let highlightStart = -1;
let highlightEnd = -1;

const heatCanvas = document.getElementById('heatmap-canvas');
const heatStatus = document.getElementById('status');
const heatCanvasWrapper = document.getElementById('heatmap-canvas-wrapper');
const heatCanvasContainer = document.getElementById('heatmap-canvas-container');

let heatCanvasWidth = 1000;
let heatCanvasHeight = 300;
let heatC;
let prevDx = -1000000000;

let searchExecutedCount = 0;
let searchMax = 0;

heatCanvas.style.width = '100px';
heatCanvas.style.height = '300px';

const timeOptions = {
    year: '2-digit',
    month: '2-digit',
    day: '2-digit',
    timeZoneName: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3
};

const timeOptionsShort = {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3
};

function methodInfo(methodIndex, out) {
    if (methodIndex === -1) {
        out.className = '';
        out.methodName = 'all';
        out.location = 0;
        out.type = 3;
        return;
    }

    methodIndex--;

    let methodAndClass = methods.int36(methodIndex * 12);
    let locationAndType = methods.int36(methodIndex * 12 + 6)
    let className = (methodAndClass & 0x3FFFF) - 1;
    let methodName = (methodAndClass >>> 18) - 1;

    out.className = cpool[className];
    out.methodName = cpool[methodName];
    out.location = locationAndType >>> 4;
    out.type = locationAndType & 15;
}

function title(methodIndex) {
    if (methodIndex === -1) {
        return 'all';
    }

    methodIndex--;

    let methodAndClass = methods.int36(methodIndex * 12);
    let className = (methodAndClass & 0x3FFFF) - 1;
    let methodName = (methodAndClass >>> 18) - 1;
    return cpool[className] + "." + cpool[methodName];
}

let bgInterval = null;
let bgTasks = [];

function addTask(id, iteration, afterFrame) {
    for (let t of bgTasks) {
        if (t.id === id) {
            t.f = iteration;
            t.a = afterFrame;
            return;
        }
    }

    bgTasks.push({id: id, f: iteration, a: afterFrame});
    if (bgTasks.length === 1) {
        bgInterval = setInterval(function() {
            let start = performance.now();
            let i = 0;
            let overflow = false;
            do {
                let task = bgTasks[i];
                if (task.f()) {
                    i++;
                } else {
                    bgTasks.splice(i, 1);
                    if (bgTasks.length === 0) {
                        clearInterval(bgInterval);
                        return;
                    }
                }

                if (i >= bgTasks.length) {
                    i = 0;
                    overflow = true;
                }
            } while(performance.now() - start < 10);

            if (overflow) {
                i = bgTasks.length;
            }
            for (let q = 0; q < i; q++) {
                bgTasks[q].a();
            }
        })
    }
}

function renderTraces(from, to) {
    let collected = currentHeatmap.collectFrames(from, to);
    let frames = new Queue();
    let root = new Map();
    root.c = 0;
    root.x = 0;
    root.m = -1;
    let roots = [root];
    let levelsCount = 0;
    for (let ctx of collected) {
        let from = ctx.root;
        root.c += from.c;
        levelsCount = Math.max(ctx.maxLevel, levelsCount);
        frames.push(ctx);
        frames.push(from);
        frames.push(root);
    }

    addTask('flame', function () {return false;}, function () {});

    if (levelsCount === 0) {
        levels = [[{left: 0, width: 0, color: getColor(3), title: title(-1)}]];
        render(levels[0][0], 0);
        return;
    }

    levels = [];
    for (let i = 0; i < levelsCount; i++) {
        levels.push([]);
    }
    let currentLevel = 0;
    let levelToRedraw = 0;
    let methodInfoOut = {};

    addTask('flame', function () {
        let level = levels[currentLevel];
        let framesCount = frames.size() / 3;
        for (let frame = 0; frame < framesCount; frame++) {
            let ctx = frames.shift();
            let from = frames.shift();
            let to = frames.shift();

            ctx.load(currentLevel);
            for (let [methodId, childFrom] of from) {
                let childTo = to.get(methodId);
                if (childTo === undefined) {
                    childTo = new Map();
                    childTo.c = 0;
                    to.set(methodId, childTo);
                }

                childTo.c += childFrom.c;
                frames.push(ctx);
                frames.push(childFrom);
                frames.push(childTo);
            }
        }

        let nextRoots = [];
        for (let root of roots) {
            let x = root.x;
            methodInfo(root.m, methodInfoOut);
            let title;
            if (methodInfoOut.className === '') {
                title = methodInfoOut.methodName;
            } else {
                title = methodInfoOut.className + "." + methodInfoOut.methodName;
            }
            let color = getColor(palette[methodInfoOut.type]);
            level.push({left: x, width: root.c, color: color, title: title});

            let nextFrames = [];
            for (let [methodId, frame] of root) {
                frame.m = methodId;
                nextFrames.push(frame);
            }
            nextFrames.sort((k1, k2) => k2.c - k1.c);
            for (let frame of nextFrames) {
                frame.x = x;
                x += frame.c;
                nextRoots.push(frame);
            }
            t++;
        }
        level.sort((k1, k2) => k1.left - k2.left);

        roots = nextRoots;
        currentLevel++;
        if (currentLevel < levelsCount) {
            return true;
        }
        if (canvasHeight !== levelsCount * 16) {
            canvasHeight = levelsCount * 16;
            canvas.style.height = canvasHeight + 'px';
            canvas.height = canvasHeight * (devicePixelRatio || 1);
            c = canvas.getContext('2d');
            if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
            c.font = document.body.style.font;
        }
        render(levels[0][0], 0);
        return false;
    },
    function () {
        if (canvasHeight !== 4096) {
            canvasHeight = 4096;
            canvas.style.height = canvasHeight + 'px';
            canvas.height = canvasHeight * (devicePixelRatio || 1);
            c = canvas.getContext('2d');
            if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
            c.font = document.body.style.font;
        }
        render(levels[0][0], 0, levelToRedraw);
        levelToRedraw = currentLevel;
    });
}

/*
function renderDiff(from, to, baseFrom, baseTo) {
    let collected = currentHeatmap.collectFrames(from, to);
    let collectedBase = currentHeatmap.collectFrames(baseFrom, baseTo);
    let frames = new Queue();
    let root = new Map();
    root.c = 0;
    root.x = 0;
    root.m = -1;
    root.b = 0;
    let roots = [root];
    let levelsCount = 0;
    for (let i = 0; i < collected.length; i++) {
        let ctx = collected[i];
        let ctxBase = collectedBase[i];

        let from = ctx.root;
        let fromBase = ctxBase.root;
        root.c += from.c;
        root.b += fromBase.c;
        levelsCount = Math.max(ctx.maxLevel, levelsCount);
        frames.push(ctx);
        frames.push(ctxBase);
        frames.push(from);
        frames.push(fromBase);
        frames.push(root);
    }

    levels = [];
    for (let i = 0; i < levelsCount; i++) {
        levels.push([]);
    }
    let currentLevel = 0;
    let levelToRedraw = 0;
    let methodInfoOut = {};

    addTask('flame', function () {
            let level = levels[currentLevel];
            let framesCount = frames.size() / 3;
            for (let frame = 0; frame < framesCount; frame++) {
                let ctx = frames.shift();
                let ctxBase = frames.shift();
                let from = frames.shift();
                let fromBase = frames.shift();
                let to = frames.shift();

                ctx.load(currentLevel);
                for (let [methodId, childFrom] of from) {
                    let childFromBase = fromBase.get(methodId);
                    let childTo = to.get(methodId);
                    if (childTo === undefined) {
                        childTo = new Map();
                        childTo.c = 0;
                        to.set(methodId, childTo);
                    }

                    childTo.c += childFrom.c;
                    if (childFromBase !== undefined) {
                        childTo.b += childFromBase.c;
                    }
                    frames.push(ctx);
                    frames.push(ctxBase);
                    frames.push(childFrom);
                    frames.push(childFromBase);
                    frames.push(childTo);
                }
            }

            let nextRoots = [];
            for (let root of roots) {
                let x = root.x;
                methodInfo(root.m, methodInfoOut);
                let title;
                if (methodInfoOut.className === '') {
                    title = methodInfoOut.methodName;
                } else {
                    title = methodInfoOut.className + "." + methodInfoOut.methodName;
                }
                let color = getColor(palette[methodInfoOut.type]);
                level.push({left: x, width: root.c, color: color, title: title});

                let nextFrames = [];
                for (let [methodId, frame] of root) {
                    frame.m = methodId;
                    nextFrames.push(frame);
                }
                nextFrames.sort((k1, k2) => k2.c - k1.c);
                for (let frame of nextFrames) {
                    frame.x = x;
                    x += frame.c;
                    nextRoots.push(frame);
                }
                t++;
            }
            level.sort((k1, k2) => k1.left - k2.left);

            roots = nextRoots;
            currentLevel++;
            if (currentLevel < levelsCount) {
                return true;
            }
            if (canvasHeight !== levelsCount * 16) {
                canvasHeight = levelsCount * 16;
                canvas.style.height = canvasHeight + 'px';
                canvas.height = canvasHeight * (devicePixelRatio || 1);
                c = canvas.getContext('2d');
                if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
                c.font = document.body.style.font;
            }
            render(levels[0][0], 0);
            return false;
        },
        function () {
            if (canvasHeight !== 4096) {
                canvasHeight = 4096;
                canvas.style.height = canvasHeight + 'px';
                canvas.height = canvasHeight * (devicePixelRatio || 1);
                c = canvas.getContext('2d');
                if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
                c.font = document.body.style.font;
            }
            render(levels[0][0], 0, levelToRedraw);
            levelToRedraw = currentLevel;
        });
}
 */

function redrawHeatSamplesImpl(from, to, dx) {
    let m = (devicePixelRatio || 1);
    let cw = sqW * m;
    let ch = sqH * m;

    let heatmap = currentHeatmap.heatmap()
    let patternDraw = !!pattern;
    for (let index = from; index <= to; index++) {
        let useFound = patternDraw && searchExecutedCount > index;
        let maxValue = useFound ? searchMax : heatmap.max;
        let value = useFound ? currentHeatmap.foundAt(index) : currentHeatmap.totalAt(heatmap[index]);
        let ratio = value / maxValue;

        let color;
        if (useFound) {
            if (ratio < 0.8) {
                let value = Math.round(255 - ratio * 255 / 0.8);
                color = 'rgb(' + value + ',' + value + ',255)';
            } else {
                let value = Math.round(255 - (ratio - 0.8) * 100 / 0.2);
                color = 'rgb(0,0,' + value + ')';
            }
        } else {
            if (ratio < 0.8) {
                let value = Math.round(255 - ratio * 255 / 0.8);
                color = 'rgb(255,' + value + ',' + value + ')';
            } else {
                let value = Math.round(255 - (ratio - 0.8) * 100 / 0.2);
                color = 'rgb('+ value + ',0,0)';
            }
        }

        heatC.fillStyle = color;

        let x = Math.floor(index / heatHeight);
        let y = index % heatHeight;
        heatC.fillRect(x * cw - dx, y * ch, cw, ch);
    }
}

function redrawHeatSamples(from, to) {
    let dx = (heatCanvasContainer.scrollLeft - 200) | 0;
    let m = (devicePixelRatio || 1);
    dx *= m;
    let cw = sqW * m;
    from = Math.max(from, heatHeight * Math.floor(dx / cw));
    to = Math.min(to, heatHeight * Math.ceil((dx + heatCanvasWidth) / cw));
    redrawHeatSamplesImpl(from, to, dx);
}

function redrawHeatMap() {
    redrawHeatSamples(0, (1 + currentHeatmap.heatmap().length / heatHeight | 0) * heatHeight);
}

function fillCanvasWithEvents(height, xgroup, zoom) {
    heatHeight = height;
    currentHeatmap.setZoom(zoom);

    sqW = sq * xgroup;

    const cw = Math.ceil(currentHeatmap.heatmap().length / height) * sqW;
    const ch = 300;
    sqH = Math.floor(ch / height);

    heatCanvasWrapper.style.width = cw + 'px';
    heatCanvasWrapper.style.height = (ch + 20) + 'px';
    heatCanvasWrapper.width = cw * (devicePixelRatio || 1);
    heatCanvasWrapper.height = (ch + 20) * (devicePixelRatio || 1);

    let w = heatCanvasContainer.offsetWidth + 400;
    heatCanvasWidth = w * (devicePixelRatio || 1);
    heatCanvasHeight = ch;
    heatCanvas.width = heatCanvasWidth;
    heatCanvas.height = ch * (devicePixelRatio || 1);
    heatCanvas.style.width = w + 'px';
    heatCanvas.style.height = heatCanvasHeight + 'px';

    heatC = heatCanvas.getContext('2d');
    heatC.font = document.body.style.font;

    redrawHeatMap();

}

function search(r) {
    if (r && (r = prompt('Enter regexp to search:', '')) === null) {
        return;
    }
    searchExecutedCount = 0;
    searchMax = 0;
    if (r === '') {
        pattern = undefined;
        redrawHeatMap();
        addTask('search', function () {
            return false;
        }, function (){});
        return;
    }
    let renderFrom = 0;
    let heatmap = currentHeatmap;
    let count = heatmap.heatmap().length;
    function renderFunc() {
        redrawHeatSamples(renderFrom, searchExecutedCount - 1);
        renderFrom = searchExecutedCount;
    }
    pattern = r ? RegExp(r) : undefined;
    addTask('search', function () {
        let amount = heatmap.performSearchAt(searchExecutedCount++, pattern);
        if (searchMax < amount) {
            renderFrom = 0;
            searchMax = amount;
        }
        if (searchExecutedCount >= count) {
            renderFunc();

            const matched = render(root, rootLevel);
            document.getElementById('matchval').textContent = pct(matched, root.width) + '%';
            document.getElementById('match').style.display = r ? 'inline-block' : 'none';

            return false;
        }
        return true;
    }, renderFunc);

}

function renderHeatmap() {
    let dx = heatCanvasContainer.scrollLeft - 200;
    heatCanvas.style.transform = 'translate(' + dx + 'px, 0px)';
    fillCanvasWithEvents(60, 1, 1);
}

document.getElementById('search').onclick = function() {
    search(true);
}

document.getElementById('reset').onclick = function() {
    search(false);
}

window.onkeydown = function(event) {
    if (event.ctrlKey && event.keyCode === 70) {
        event.preventDefault();
        search(true);
    } else if (event.keyCode === 27) {
        search(false);
    }
}

heatCanvasContainer.addEventListener('scroll', function() {
    let dx = (heatCanvasContainer.scrollLeft - 200);
    let m = (devicePixelRatio || 1);
    let cw = sqW * m;
    heatCanvas.style.transform = 'translate(' + (heatCanvasContainer.scrollLeft - 200) + 'px, 0px)';
    dx = (dx * m) | 0;
    let delta = Math.abs(dx - prevDx);
    if (delta >= heatCanvasWidth || searchExecutedCount !== 0) {
        redrawHeatMap();
    } else {
        heatC.drawImage(heatCanvas, prevDx - dx, 0);
        if (prevDx < dx) {
            let from = heatHeight * Math.floor((prevDx + heatCanvasWidth) / cw);
            let to = heatHeight * Math.ceil((dx + heatCanvasWidth) / cw);
            redrawHeatSamplesImpl(from, to, dx);
        } else {
            let from = heatHeight * Math.floor(dx / cw);
            let to = heatHeight * Math.ceil(prevDx / cw);
            redrawHeatSamplesImpl(from, to, dx);
        }
    }
    prevDx = dx;
});

document.getElementById('heatmap-height-line').onclick = function() {
    let zoom = currentHeatmap.zoom + 1;
    if (zoom > currentHeatmap.maxZoom) {
        zoom = 0;
    }

    //fillCanvasWithEvents(zoom > 1 ? 1 : 60, zoom === 3 ? 10 : 1, zoom);
    fillCanvasWithEvents(60,  1, zoom);
}

function callHighlightRedraw(start, end, suffix) {
    let left = document.getElementById('left' + suffix);
    let leftMiddle = document.getElementById('leftMiddle' + suffix);
    let middle = document.getElementById('middle' + suffix);
    let rightMiddle = document.getElementById('rightMiddle' + suffix);
    let right = document.getElementById('right' + suffix);

    let x1 = Math.floor(start / heatHeight);
    let y1 = start % heatHeight;
    let x2 = Math.floor(end / heatHeight);
    let y2 = end % heatHeight;

    let veryStartX = x1 * sqW;
    let veryStartY = y1 * sqH;
    let veryEndX = x2 * sqW;

    if (x1 === x2) {
        left.style.display = 'none';
        right.style.display = 'none';

        for (let b of [middle, leftMiddle, rightMiddle]) {
            b.style.left = veryStartX + 'px';
            b.style.top = veryStartY + 'px';
            b.style.width = sqW + 'px';
            b.style.height = (y2 - y1 + 1) * sqH + 'px';
            b.style.display = 'block';
        }
        middle.style.width = sqW + 1 + 'px';
        middle.style.height = (y2 - y1 + 1) * sqH - 1 + 'px';
    } else {
        left.style.left = veryStartX + 'px';
        left.style.top = veryStartY + 'px';
        left.style.width = sqW + 'px';
        left.style.height = (heatHeight - y1) * sqH - 1 + 'px';
        left.style.display = 'block';

        leftMiddle.style.left = veryStartX + 'px';
        leftMiddle.style.top = '0px';
        leftMiddle.style.width = sqW + 'px';
        leftMiddle.style.height = y1 * sqH + 'px';
        leftMiddle.style.display = 'block';

        rightMiddle.style.left = veryEndX + 'px';
        rightMiddle.style.top = (y2 + 1) * sqH + 'px';
        rightMiddle.style.width = sqW - 1 + 'px';
        rightMiddle.style.height = (heatHeight - y2 - 1) * sqH + 'px';
        rightMiddle.style.display = 'block';

        right.style.left = veryEndX + 1 + 'px';
        right.style.top = '0px';
        right.style.width = sqW - 1 + 'px';
        right.style.height = (y2 + 1) * sqH - 1 + 'px';
        right.style.display = 'block';

        if (x2 - x1 === 1) {
            middle.style.display = 'none';
        } else {
            middle.style.left = veryStartX + sqW + 1 + 'px';
            middle.style.top = '0px';
            middle.style.width = (x2 - x1 - 1) * sqW + 'px';
            middle.style.height = heatHeight * sqH - 1 + 'px';
            middle.style.display = 'block';
        }
    }
}

function callSelectionRedraw(sample, shiftPressed) {
    if (shiftPressed && heatActiveSample1 !== -1) {
        highlightStart = Math.min(heatActiveSample1, sample);
        highlightEnd = Math.max(heatActiveSample1, sample);
    } else {
        highlightStart = highlightEnd = sample;
    }

    callHighlightRedraw(highlightStart, highlightEnd, 'Selection');
}

function callActiveRedraw(sample, shiftPressed) {
    if (shiftPressed && heatActiveSample1 !== -1) {
        heatActiveSample2 = sample;
    } else {
        heatActiveSample1 = heatActiveSample2 = sample;
    }

    let minSelected = Math.min(heatActiveSample1, heatActiveSample2);
    let maxSelected = Math.max(heatActiveSample1, heatActiveSample2);

    callHighlightRedraw(minSelected, maxSelected, 'Active');
    renderTraces(minSelected, maxSelected);
}

window.addEventListener('keydown', function (e) {
    if (e.key === 'Shift') {
        callSelectionRedraw(heatLastSample, true);
    }
});

window.addEventListener('keyup', function (e) {
    if (e.key === 'Shift') {
        callSelectionRedraw(heatLastSample, false);
    }
});

heatCanvas.onmousemove = function (event) {
    let x = Math.floor((event.offsetX + heatCanvasContainer.scrollLeft - 200) / sqW);
    let y = Math.floor(event.offsetY / sqH);
    if (y >= heatHeight) {
        y = heatHeight - 1;
    }
    heatLastSample = x * heatHeight + y;
    callSelectionRedraw(heatLastSample, event.shiftKey);
}

heatCanvas.onclick = function (event) {
    let x = Math.floor((event.offsetX + heatCanvasContainer.scrollLeft - 200) / sqW);
    let y = Math.floor(event.offsetY / sqH);
    let sample = x * heatHeight + y;

    callActiveRedraw(sample, event.shiftKey);
};