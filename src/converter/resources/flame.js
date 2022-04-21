// Copyright 2020 Andrei Pangin
// Licensed under the Apache License, Version 2.0.
'use strict';
var root, rootLevel, px, pattern;
var reverse = ${reverse};
var levels = Array(0);

const canvas = document.getElementById('canvas');
const hl = document.getElementById('hl');
const status = document.getElementById('status');

var canvasWidth = canvas.offsetWidth;
var canvasHeight = canvas.offsetHeight;
canvas.style.width = canvasWidth + 'px';
canvas.style.height = canvasHeight + 'px';
canvas.width = canvasWidth * (devicePixelRatio || 1);
canvas.height = canvasHeight * (devicePixelRatio || 1);

var c = canvas.getContext('2d');
if (devicePixelRatio) c.scale(devicePixelRatio, devicePixelRatio);
c.font = document.body.style.font;

const palette = [
    [0x50e150, 30, 30, 30],
    [0x50bebe, 30, 30, 30],
    [0xe17d00, 30, 30,  0],
    [0xc8c83c, 30, 30, 10],
    [0xe15a5a, 30, 40, 40],
];

function getColor(p) {
    const v = Math.random();
    return '#' + (p[0] + ((p[1] * v) << 16 | (p[2] * v) << 8 | (p[3] * v))).toString(16);
}

function f(level, left, width, type, title) {
    levels[level].push({left: left, width: width, color: getColor(palette[type]), title: title});
}

function samples(n) {
    return n === 1 ? '1 sample' : n.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',') + ' samples';
}

function pct(a, b) {
    return a >= b ? '100' : (100 * a / b).toFixed(2);
}

function findFrame(frames, x) {
    let left = 0;
    let right = frames.length - 1;

    while (left <= right) {
        const mid = (left + right) >>> 1;
        const f = frames[mid];

        if (f.left > x) {
            right = mid - 1;
        } else if (f.left + f.width <= x) {
            left = mid + 1;
        } else {
            return f;
        }
    }

    if (frames[left] && (frames[left].left - x) * px < 0.5) return frames[left];
    if (frames[right] && (x - (frames[right].left + frames[right].width)) * px < 0.5) return frames[right];

    return null;
}

function search(r) {
    if (r && (r = prompt('Enter regexp to search:', '')) === null) {
        return;
    }

    pattern = r ? RegExp(r) : undefined;
    const matched = render(root, rootLevel);
    document.getElementById('matchval').textContent = pct(matched, root.width) + '%';
    document.getElementById('match').style.display = r ? 'inline-block' : 'none';
}

function render(newRoot, newLevel) {
    if (root) {
        c.fillStyle = '#ffffff';
        c.fillRect(0, 0, canvasWidth, canvasHeight);
    }

    root = newRoot || levels[0][0];
    rootLevel = newLevel || 0;
    px = canvasWidth / root.width;

    const x0 = root.left;
    const x1 = x0 + root.width;
    const marked = [];

    function mark(f) {
        return marked[f.left] >= f.width || (marked[f.left] = f.width);
    }

    function totalMarked() {
        let total = 0;
        let left = 0;
        Object.keys(marked).sort(function(a, b) { return a - b; }).forEach(function(x) {
            if (+x >= left) {
                total += marked[x];
                left = +x + marked[x];
            }
        });
        return total;
    }

    function drawFrame(f, y, alpha) {
        if (f.left < x1 && f.left + f.width > x0) {
            c.fillStyle = pattern && f.title.match(pattern) && mark(f) ? '#ee00ee' : f.color;
            c.fillRect((f.left - x0) * px, y, f.width * px, 15);

            if (f.width * px >= 21) {
                const chars = Math.floor(f.width * px / 7);
                const title = f.title.length <= chars ? f.title : f.title.substring(0, chars - 2) + '..';
                c.fillStyle = '#000000';
                c.fillText(title, Math.max(f.left - x0, 0) * px + 3, y + 12, f.width * px - 6);
            }

            if (alpha) {
                c.fillStyle = 'rgba(255, 255, 255, 0.5)';
                c.fillRect((f.left - x0) * px, y, f.width * px, 15);
            }
        }
    }

    for (let h = 0; h < levels.length; h++) {
        const y = reverse ? h * 16 : canvasHeight - (h + 1) * 16;
        const frames = levels[h];
        for (let i = 0; i < frames.length; i++) {
            drawFrame(frames[i], y, h < rootLevel);
        }
    }

    return totalMarked();
}

canvas.onmousemove = function(event) {
    const h = Math.floor((reverse ? event.offsetY : (canvasHeight - event.offsetY)) / 16);
    if (h >= 0 && h < levels.length) {
        const f = findFrame(levels[h], event.offsetX / px + root.left);
        if (f) {
            hl.style.left = (Math.max(f.left - root.left, 0) * px + canvas.offsetLeft) + 'px';
            hl.style.width = (Math.min(f.width, root.width) * px) + 'px';
            hl.style.top = ((reverse ? h * 16 : canvasHeight - (h + 1) * 16) + canvas.offsetTop) + 'px';
            hl.firstChild.textContent = f.title;
            hl.style.display = 'block';
            canvas.title = f.title + '\n(' + samples(f.width) + ', ' + pct(f.width, levels[0][0].width) + '%)';
            canvas.style.cursor = 'pointer';
            canvas.onclick = function() {
                if (f !== root) {
                    render(f, h);
                    canvas.onmousemove(event);
                }
            };
            status.textContent = 'Function: ' + canvas.title;
            return;
        }
    }
    canvas.onmouseout(event);
}

canvas.onmouseout = function(event) {
    hl.style.display = 'none';
    status.textContent = '\xa0';
    canvas.title = '';
    canvas.style.cursor = '';
    canvas.onclick = undefined;
}

document.getElementById('reverse').onclick = function() {
    reverse = !reverse;
    render();
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

