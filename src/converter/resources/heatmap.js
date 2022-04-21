class DataBuffer {
    constructor(encodedData) {
        this.data = encodedData;
        this.pos = 0;
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

    byteAt(pos) {
        return this.data.charCodeAt(pos) - 63;
    }

    intAt(pos) {
        return (this.byteAt(pos++) << 0)
            | (this.byteAt(pos++) << 6)
            | (this.byteAt(pos++) << 12)
            | (this.byteAt(pos++) << 18)
            | (this.byteAt(pos) << 24);
    }

    seek(pos) {
        this.pos = pos;
    }
}

class Frame {
    methodIndex;
    self = 0;
    total = 0;
    children = {};  // extremely faster then array


    constructor(methodIndex) {
        this.methodIndex = methodIndex;
    }

    nextChild(methodIndex) {
        let c = this.children[methodIndex];
        if (c !== undefined) {
            return c;
        }
        return this.children[methodIndex] = new Frame(methodIndex);
    }
}

class GlobalFrame {
    methodIndex;
    children;
    index;

    constructor(methodIndex, size) {
        this.methodIndex = methodIndex;
        this.children = Array(size);
        this.index = size;
    }

    append(frame) {
        this.children[--this.index] = frame;
    }
}

class Root extends Frame {

    constructor() {
        super(-1);
    }

    static _combine(target, source) {
        target.total += source.total;
        target.self += source.self;
        Object.values(source.children).forEach(s => this._combine(target.nextChild(s.methodIndex), s));
    }

    static combine(roots) {
        let root = new Root();
        for (let frame of roots) {
            this._combine(root, frame);
        }
        return root;
    }

}

class Sample {
    events
    eventOffsets = []

    _integralValue = undefined
    _integralTraces = undefined

    constructor(events) {
        this.events = events;
    }

    addEvent(offset) {
        this.eventOffsets.push(offset)
    }

    get integralValue() {
        if (this._integralValue !== undefined) {
            return this._integralValue;
        }
        let value = 0;
        for (let e of this.eventOffsets) {
            value += this.events[e + 4];
        }
        return this._integralValue = value;
    }

    get integralTraces() {
        if (this._integralTraces !== undefined) {
            return this._integralTraces;
        }

        let root = new Root();

        for (let e of this.eventOffsets) {
            // let threadState = this.events[e + 0];
            let stackIndex = this.events[e + 1];
            // let threadId = this.events[e + 2];
            // let ticks = this.events[e + 3];
            let value = this.events[e + 4];

            let globalFrameIndex = 0; // TODO

            let indexesOffset = stacks.data.intAt(globalFrameIndex * 5);
            let stackOffset = stacks.data.intAt(indexesOffset + stackIndex * 5);

            stacks.data.seek(stackOffset);

            let stackSize = stacks.data.nextVarInt();

            let frame = root;
            let globalFrame = globalStacks[globalFrameIndex];

            // TODO only the Flying Spaghetti Monster knows why stacks are inverted...
            let childrenStack = [];
            for (let s = 0; s < stackSize; s++) {
                childrenStack.push(globalFrame = globalFrame.children[stacks.nextVarInt()]);
            }

            for (let s = 0; s < stackSize; s++) {
                frame.total += value;
                let child = childrenStack.pop();
                frame = frame.nextChild(child.methodIndex);
            }

            frame.total += value;
            frame.self += value;
        }

        return this._integralTraces = root;
    }

}

function frameType(frame) {
    if (frame.methodIndex === -1) {
        return 3;
    }
    return methods.byteAt(frame.methodIndex * 17 + 16);
}

function frameTitle(frame) {
    if (frame.methodIndex === -1) {
        return 'all';
    }

    if (frame !== root) {
        let classIndex = methods.intAt(frame.methodIndex * 17);
        let methodIndex = methods.intAt(frame.methodIndex * 17 + 5);
        return cpool[classIndex] + "." + cpool[methodIndex];
    }
}

function renderNewRoot(root) {
    levels = [];

    let stack = [{frame: root, x: 0, level: 0}];
    while (stack.length > 0) {
        let current = stack.pop();
        let level = levels[current.level];
        let x = current.x;
        let frame = current.frame;
        if (level === undefined) {
            level = [];
            levels[current.level] = level;
        }
        level.push({left: x, width: frame.total, color: getColor(palette[frameType(frame)]), title: frameTitle(frame)});

        x += frame.self;
        Object.values(frame.children).forEach(child => {
            stack.push({frame: child, x: x, level: current.level + 1});
            x += child.total;
        });
    }

    for (let level of levels) {
        level.sort((k1,k2) => k1.left - k2.left);
    }

    canvasHeight = levels.length * 16;
    canvas.style.height = canvasHeight + 'px';
    canvas.height = canvasHeight * (devicePixelRatio || 1);
    c = canvas.getContext('2d');
    if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
    c.font = document.body.style.font;
    render(levels[0][0], 0);
}

function renderDiff(root, baseline) {
    levels = [];

    let stack = [];
    stack.push(baseline)
    stack.push({frame: root, x: 0, level: 0});
    while (stack.length > 0) {
        let current = stack.pop();
        let base = stack.pop();
        let level = levels[current.level];
        let x = current.x;
        let frame = current.frame;
        if (level === undefined) {
            level = [];
            levels[current.level] = level;
        }

        let wasTotal = base == null ? 0 : base.total
        let nowTotal = frame.total
        let title = frameTitle(frame) + " (" + nowTotal + "/" + wasTotal +")";
        let color = wasTotal === 0 ? "#BAA551" : (wasTotal >= nowTotal ? "#99aaff" : "#ffaa99");

        level.push({left: x, width: frame.total, color: color, title: title});

        x += frame.self;
        Object.values(frame.children).forEach(child => {
            stack.push(base == null ? null : base.children[child.methodIndex]);
            stack.push({frame: child, x: x, level: current.level + 1});
            x += child.total;
        });
    }

    for (let level of levels) {
        level.sort((k1,k2) => k1.left - k2.left);
    }

    canvasHeight = levels.length * 16;
    canvas.style.height = canvasHeight + 'px';
    canvas.height = canvasHeight * (devicePixelRatio || 1);
    c = canvas.getContext('2d');
    if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
    c.font = document.body.style.font;
    render(levels[0][0], 0);
}

// TODO find the way to skip this unpack
function decodeEvents(encodedData) {
    let data = new DataBuffer(encodedData);
    let result = [];
    while (data.hasMore()) {
        result.push(data.nextVarInt());
    }
    return result;
}

function decodeMethods(encodedData) {
    return new DataBuffer(encodedData);
}

class LzChunk {
    nextChunk = null;
    data;

    copyWithNext(method, frameSize) {
        let result = new LzChunk();

        let copyFrom = this;
        let copyTo = result;

        while (copyFrom.nextChunk !== null) {
            copyTo.data = copyFrom.data;
            copyTo = copyTo.nextChunk = new LzChunk();
            copyFrom = copyFrom.nextChunk;
        }

        if (copyFrom.data.length >= 64) {
            copyTo.data = copyFrom.data;
            copyTo = copyTo.nextChunk = new LzChunk();
            copyTo.data = [method, frameSize];
        } else {
            copyTo.data = Array.from(copyFrom.data)
            copyTo.data.push(method, frameSize);
        }

        return result;
    }
}

class Lz78Data {
    data;
    lz78;
    currentChunk = null;
    pos = 0;

    constructor(data) {
        this.data = data;

        let empty = new LzChunk();
        empty.data = [];
        this.lz78 = [empty];
    }

    nextVarInt() {
        if (this.currentChunk === null) {
            let index = this.data.nextVarInt()
            if (this.data.hasMore()) {
                let nextMethod = this.data.nextVarInt();
                let nextFrameSize = this.data.nextVarInt();

                this.currentChunk = this.lz78[index].copyWithNext(nextMethod, nextFrameSize);
                this.lz78.push(this.currentChunk);
            } else {
                this.currentChunk = this.lz78[index];
            }
        }

        let result = this.currentChunk.data[this.pos++];

        if (this.pos >= this.currentChunk.data.length) {
            this.currentChunk = this.currentChunk.nextChunk;
            this.pos = 0;
        }
        return result;
    }
}

class RleData {
    data;
    zeroCount = 0;
    constructor(data) {
        this.data = new DataBuffer(data);
    }

    nextVarInt() {
        if (this.zeroCount > 0) {
            this.zeroCount--;
            return 0;
        }
        let r = this.data.nextVarInt();
        if (r !== 0) {
            return r;
        }

        this.zeroCount = this.data.nextVarInt() - 1;
        return 0;
    }
}

function decodeGlobalStack(lz78data) {
    if (lz78data.nextVarInt() === 0) {
        return null;
    }

    let data = new Lz78Data(lz78data);
    let queue = [null];
    let root;

    while (queue.length > 0) {
        let parent = queue.pop();

        let methodIndex = data.nextVarInt();
        let childrenCount = data.nextVarInt();

        let frame = new GlobalFrame(methodIndex, childrenCount);

        if (parent !== null) {
            parent.append(frame);
        } else {
            root = frame;
        }

        for (let i = 0; i < childrenCount; i++) {
            queue.push(frame);
        }
    }

    return root;
}

function decodeGlobalStacks(encodedData) {
    let data = new DataBuffer(encodedData);
    let count = data.nextVarInt();
    let result = Array(count);
    for (let i = 0; i < count; i++) {
        result[i] = decodeGlobalStack(data);
    }
    return result;
}

function claimHtml(id) {
    let e = document.getElementById(id);
    let r = e.innerHTML;
    e.remove();
    return r;
}

const sq = 8;
var startSeconds = ${startSeconds};
var durationSeconds = ${durationSeconds};
var ticksPerSecond = ${ticksPerSecond};
var cpool = [${cpool}];

var executionsHeatmap = decodeEvents(claimHtml("executionsHeatmap"));
var stacks = new RleData(claimHtml("stacks"));
var allocationsHeatmap = decodeEvents("${allocationsHeatmap}");
var locksHeatmap = decodeEvents("${locksHeatmap}");
var globalStacks = decodeGlobalStacks(claimHtml("globalStacks"))
var methods = decodeMethods(claimHtml("methods"))

var currentHeatmap = executionsHeatmap;
var currentHeatmapType = 'executions';

var heatHeight = 60;
var heatSamples = [];

var heatSelectedSample1 = null;
var heatSelectedSample2 = null;
var heatDiffSampleStart = null;
var heatDiffSampleEnd = null;

const heatCanvas = document.getElementById('heatmap-canvas');
const heatStatus = document.getElementById('status');

var heatCanvasWidth = heatCanvas.offsetWidth;
var heatC;
var heatCanvasHeight = heatCanvas.offsetHeight;

heatCanvas.style.width = heatCanvasWidth + 'px';
heatCanvas.style.height = heatCanvasHeight + 'px';
heatCanvas.width = heatCanvasWidth * (devicePixelRatio || 1);
heatCanvas.height = heatCanvasHeight * (devicePixelRatio || 1);

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

function redrawHeatSamples() {

    heatC.fillStyle = '#ffffff';
    heatC.fillRect(0, 0, heatCanvasWidth, heatCanvasHeight);

    let maxValue = 0;
    for (let s = 0; s < heatSamples.length; s++) {
        if (heatSamples[s] !== undefined) {
            maxValue = Math.max(maxValue, heatSamples[s].integralValue);
        }
    }

    let fromSample = heatSelectedSample1 == null ? 0 : Math.min(heatSelectedSample2, heatSelectedSample1);
    let toSample = heatSelectedSample1 == null ? heatSamples.length : Math.max(heatSelectedSample2, heatSelectedSample1);

    for (let s = 0; s < heatSamples.length; s++) {
        let value = heatSamples[s] === undefined ? 0 : heatSamples[s].integralValue;
        value = Math.round(255 - (value / maxValue) * 255);

        let color;
        if (fromSample === toSample && heatSelectedSample1 === s) {
            color = '#9CF195';
        } else if (heatDiffSampleStart != null && heatDiffSampleEnd != null) {
            if (fromSample === toSample && heatDiffSampleStart === s) {
                color = '#9C95F1';
            } else if ((s >= heatDiffSampleStart && s <= heatDiffSampleEnd) && fromSample !== toSample) {
                color = 'rgba(256,' + (64 + value / 2) + ',' + (128 + value / 2) + ')';
            } else if ((s < fromSample || s > toSample) && fromSample !== toSample) {
                color = 'rgba(128,' + value / 2 + ',' + value / 2 + ')';
            } else {
                color = 'rgb(255,' + value + ',' + value + ')';
            }
        } else if ((s < fromSample || s > toSample) && fromSample !== toSample) {
            color = 'rgba(128,' + value / 2 + ',' + value / 2 + ')';
        } else {
            color = 'rgb(255,' + value + ',' + value + ')';
        }
        heatC.fillStyle = color;

        let x = Math.floor(s / heatHeight);
        let y = s % heatHeight;
        heatC.fillRect(x * sq, y * sq, sq, sq);
    }
}

function collectRoots(fromSample, toSample) {
    let roots = [];
    for (let ss = fromSample; ss <= toSample; ss++) {
        if (heatSamples[ss] === undefined) {
            continue;
        }
        roots.push(heatSamples[ss].integralTraces);
    }
    return Root.combine(roots);
}

function fillCanvasWithEvents(events, height, ticksPerSample) {
    heatHeight = height;
    let maxTick = 0;

    for (let e = 0; e < events.length; e += 5) {
        let tick = events[e + 3];
        maxTick = Math.max(maxTick, tick);
    }

    heatSamples = Array(Math.floor(maxTick / ticksPerSample));
    for (let e = 0; e < events.length; e += 5) {
        let tick = events[e + 3];
        let index = Math.floor(tick / ticksPerSample);
        if (heatSamples[index] === undefined) {
            heatSamples[index] = new Sample(events);
        }
        heatSamples[index].addEvent(e);
    }

    const cw = Math.ceil(heatSamples.length / height) * sq;
    const ch = heatCanvas.offsetHeight;

    heatCanvas.style.width = cw + 'px';
    heatCanvas.width = cw * (devicePixelRatio || 1);
    heatCanvas.height = ch * (devicePixelRatio || 1);
    heatC = heatCanvas.getContext('2d');
    if (devicePixelRatio) heatC.scale(devicePixelRatio, devicePixelRatio);
    heatC.font = document.body.style.font;

    redrawHeatSamples();

    heatCanvas.onmousemove = function (event) {
        const x = Math.floor(event.offsetX / sq);
        const y = Math.floor(event.offsetY / sq);
        const sample = x * heatHeight + y;

        if (sample >= 0 && sample < heatSamples.length) {
            const timeSeconds = startSeconds + (sample * ticksPerSample / ticksPerSecond);
            heatCanvas.title = new Date(timeSeconds * 1000).toLocaleTimeString(undefined, timeOptionsShort);
            heatCanvas.onclick = function (event) {
                if (event.ctrlKey && heatSelectedSample1 != null) {
                    let fromSample = Math.min(heatSelectedSample1, heatSelectedSample2);
                    let toSample = Math.max(heatSelectedSample1, heatSelectedSample2);
                    let sampleDiffSize = toSample - fromSample;
                    if ((sample + sampleDiffSize >= fromSample) && sample <= toSample) {
                        return;
                    }
                    heatDiffSampleStart = sample;
                    heatDiffSampleEnd = sample + sampleDiffSize;

                    redrawHeatSamples();
                    renderDiff(collectRoots(fromSample, toSample), collectRoots(heatDiffSampleStart, heatDiffSampleEnd));
                    return;
                }
                heatDiffSampleStart = heatDiffSampleEnd = null;

                if (event.shiftKey && heatSelectedSample1 != null) {
                    heatSelectedSample2 = sample;
                } else {
                    heatSelectedSample1 = heatSelectedSample2 = sample;
                }
                redrawHeatSamples();

                let fromSample = Math.min(heatSelectedSample1, heatSelectedSample2);
                let toSample = Math.max(heatSelectedSample1, heatSelectedSample2);

                renderNewRoot(collectRoots(fromSample, toSample));
            };
            heatStatus.textContent = new Date(timeSeconds * 1000).toLocaleDateString(undefined, timeOptions);
        }
    }

}

function renderHeatmap() {
    fillCanvasWithEvents(currentHeatmap, 60, ticksPerSecond);
}
