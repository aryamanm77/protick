(function (global) {
    'use strict';

    var LETTERS = ['A', 'B', 'C', 'D', 'E'];

    /**
     * Accessible, offline-resilient, embeddable Student Exam View component.
     * Enforces:
     * - 1 question per screen, plain text + line breaks (XSS-safe textContent)
     * - A-D options, single selection, click selected option to clear (Unanswered)
     * - Question strip, Prev/Next navigation, keyboard shortcuts (ArrowLeft/Right, A-D / 1-4)
     * - A-/A+ font zoom and high-contrast toggle
     * - Server-authoritative timer synchronized via heartbeat + extra time awareness
     * - Offline localStorage answer buffering with automatic resync on reconnect
     * - ZERO question IDs, module names, marks, hints, or answers displayed
     */
    function mount(container, options) {
        if (!container) throw new Error('Container element is required');
        var opts = Object.assign({
            basePath: (global.QBankApi && global.QBankApi.basePath) || '/qbank',
            studentToken: null,
            paperPayload: null,
            responseSink: null,
            onFinish: null
        }, options || {});

        var paper = opts.paperPayload;
        var baseUrl = (opts.basePath || '').replace(/\/$/, '') + '/api/v1';
        var storageKey = 'qbank_offline_q_' + paper.sessionId + '_' + paper.rollNumber;

        var state = {
            currentIdx: Math.max(0, Math.min(paper.lastQuestionIndex || 0, Math.max(0, paper.questions.length - 1))),
            selections: Object.assign({}, paper.savedSelections || {}),
            dirtyQueue: loadDirtyQueue(),
            fontScale: paper.fontScale || 1.0,
            highContrast: !!paper.highContrast,
            effectiveEndMs: Date.parse(paper.effectiveEndAt),
            clockOffsetMs: Date.parse(paper.serverNow) - Date.now(),
            syncState: 'SYNCED',
            finished: false
        };

        // Re-apply any locally buffered answers that hadn't synced before crash/refresh
        Object.keys(state.dirtyQueue).forEach(function (pos) {
            state.selections[pos] = state.dirtyQueue[pos].selectedOptionIndex;
        });

        applyAccessibility();
        renderShell();
        renderQuestion();
        flushDirtyQueue();

        var timerId = global.setInterval(updateClockTick, 1000);
        var heartbeatId = global.setInterval(sendHeartbeatAndSync, 15000);
        global.addEventListener('keydown', onKeyDown);

        function cleanup() {
            global.clearInterval(timerId);
            global.clearInterval(heartbeatId);
            global.removeEventListener('keydown', onKeyDown);
        }

        function applyAccessibility() {
            document.documentElement.style.setProperty('--qb-font-scale', String(state.fontScale));
            if (state.highContrast) document.body.classList.add('qb-high-contrast');
            else document.body.classList.remove('qb-high-contrast');
        }

        function loadDirtyQueue() {
            try {
                var raw = global.localStorage.getItem(storageKey);
                return raw ? JSON.parse(raw) : {};
            } catch (e) { return {}; }
        }

        function persistDirtyQueue() {
            try {
                if (Object.keys(state.dirtyQueue).length === 0) {
                    global.localStorage.removeItem(storageKey);
                } else {
                    global.localStorage.setItem(storageKey, JSON.stringify(state.dirtyQueue));
                }
            } catch (e) {}
        }

        async function apiCall(path, method, jsonBody) {
            var headers = { 'Content-Type': 'application/json' };
            if (opts.studentToken) {
                headers['Authorization'] = 'Bearer ' + opts.studentToken;
            }
            var resp = await fetch(baseUrl + path, {
                method: method,
                headers: headers,
                credentials: 'same-origin',
                body: jsonBody ? JSON.stringify(jsonBody) : undefined
            });
            if (!resp.ok) {
                var errTxt = 'HTTP ' + resp.status;
                try {
                    var j = await resp.json();
                    errTxt = j.message || errTxt;
                } catch (ignored) {}
                var err = new Error(errTxt);
                err.status = resp.status;
                throw err;
            }
            return resp.json();
        }

        async function saveChoice(position, optionIndex) {
            state.selections[position] = optionIndex;
            state.dirtyQueue[String(position)] = {
                questionPosition: position,
                selectedOptionIndex: optionIndex,
                clientTimestamp: new Date().toISOString()
            };
            persistDirtyQueue();
            state.syncState = 'SAVING';
            updateSyncBadge();

            if (typeof opts.responseSink === 'function') {
                try { opts.responseSink(state.dirtyQueue[String(position)]); } catch (ignored) {}
            }
            await flushDirtyQueue();
        }

        async function flushDirtyQueue() {
            if (state.finished) return;
            var keys = Object.keys(state.dirtyQueue);
            if (keys.length === 0) {
                state.syncState = 'SYNCED';
                updateSyncBadge();
                return;
            }
            var batch = keys.map(function (k) { return state.dirtyQueue[k]; });
            try {
                var ack = await apiCall('/student/responses', 'POST', {
                    answers: batch,
                    lastQuestionIndex: state.currentIdx
                });
                keys.forEach(function (k) { delete state.dirtyQueue[k]; });
                persistDirtyQueue();
                if (ack && ack.serverNow) {
                    state.clockOffsetMs = Date.parse(ack.serverNow) - Date.now();
                }
                if (ack && ack.effectiveEndAt) {
                    state.effectiveEndMs = Date.parse(ack.effectiveEndAt);
                }
                state.syncState = 'SYNCED';
            } catch (e) {
                if (e.status === 401 || e.status === 403 || e.status === 409) {
                    state.syncState = 'BLOCKED: ' + e.message;
                } else {
                    state.syncState = 'OFFLINE (saved locally)';
                }
            }
            updateSyncBadge();
        }

        async function sendHeartbeatAndSync() {
            if (state.finished) return;
            if (Object.keys(state.dirtyQueue).length > 0) {
                await flushDirtyQueue();
            }
            try {
                var hb = await apiCall('/student/heartbeat', 'POST', {
                    currentQuestionIndex: state.currentIdx
                });
                if (hb && hb.serverNow) state.clockOffsetMs = Date.parse(hb.serverNow) - Date.now();
                if (hb && hb.effectiveEndAt) state.effectiveEndMs = Date.parse(hb.effectiveEndAt);
                if (Object.keys(state.dirtyQueue).length === 0) state.syncState = 'SYNCED';
            } catch (e) {
                if (e.status === 401 || e.status === 403) {
                    state.syncState = 'LOCKED: ' + e.message;
                } else {
                    state.syncState = 'OFFLINE (saved locally)';
                }
            }
            updateSyncBadge();
        }

        function updateClockTick() {
            var el = container.querySelector('#qb-stu-timer');
            if (!el) return;
            var serverNowMs = Date.now() + state.clockOffsetMs;
            var remainingSec = Math.max(0, Math.floor((state.effectiveEndMs - serverNowMs) / 1000));
            var h = Math.floor(remainingSec / 3600);
            var m = Math.floor((remainingSec % 3600) / 60);
            var s = remainingSec % 60;
            el.textContent = pad2(h) + ':' + pad2(m) + ':' + pad2(s);
            if (remainingSec <= 300) {
                el.style.borderColor = '#b91c1c';
                el.style.color = '#b91c1c';
            }
            if (remainingSec === 0 && !state.finished) {
                finishSession(true);
            }
        }

        async function finishSession(autoTriggered) {
            if (state.finished) return;
            if (!autoTriggered) {
                var answered = Object.keys(state.selections).filter(function (k) {
                    return state.selections[k] !== null && state.selections[k] !== undefined;
                }).length;
                if (!global.confirm('Submit exam now? Answered: ' + answered + ' / ' + paper.questions.length)) {
                    return;
                }
            }
            await flushDirtyQueue();
            try {
                await apiCall('/student/finish', 'POST', { lastQuestionIndex: state.currentIdx });
            } catch (e) {
                if (!autoTriggered) {
                    global.alert('Unable to submit online: ' + e.message);
                    return;
                }
            }
            state.finished = true;
            cleanup();
            try { global.localStorage.removeItem(storageKey); } catch (ignored) {}
            container.textContent = '';
            var doneCard = document.createElement('div');
            doneCard.className = 'qb-container';
            var box = document.createElement('div');
            box.className = 'qb-card';
            var h2 = document.createElement('h2');
            h2.textContent = 'Exam Submitted';
            var p = document.createElement('p');
            p.textContent = 'Your responses for Roll ' + paper.rollNumber + ' (Version ' + paper.assignedVersion + ') have been recorded.';
            box.appendChild(h2);
            box.appendChild(p);
            doneCard.appendChild(box);
            container.appendChild(doneCard);
            if (typeof opts.onFinish === 'function') opts.onFinish();
        }

        function onKeyDown(e) {
            if (state.finished || e.target.tagName === 'INPUT') return;
            if (e.key === 'ArrowLeft' || e.key === 'p' || e.key === 'P') {
                navigate(-1);
            } else if (e.key === 'ArrowRight' || e.key === 'n' || e.key === 'N') {
                navigate(1);
            } else {
                var idx = ['a', 'b', 'c', 'd'].indexOf(e.key.toLowerCase());
                if (idx < 0) idx = ['1', '2', '3', '4'].indexOf(e.key);
                var q = paper.questions[state.currentIdx];
                if (idx >= 0 && q && idx < q.options.length) {
                    saveChoice(q.position, idx);
                    renderQuestion();
                }
            }
        }

        function navigate(delta) {
            var next = state.currentIdx + delta;
            if (next >= 0 && next < paper.questions.length) {
                state.currentIdx = next;
                renderQuestion();
            }
        }

        function renderShell() {
            container.textContent = '';

            var bar = document.createElement('div');
            bar.className = 'qb-student-bar';

            var left = document.createElement('div');
            var strong = document.createElement('strong');
            strong.textContent = paper.rollNumber + ' · ' + paper.fullName + ' (Version ' + paper.assignedVersion + ')';
            left.appendChild(strong);

            var right = document.createElement('div');
            right.className = 'qb-nav';

            var syncSpan = document.createElement('span');
            syncSpan.id = 'qb-stu-sync';
            syncSpan.className = 'badge badge-ok';
            syncSpan.textContent = 'SYNCED';

            var zoomOut = document.createElement('button');
            zoomOut.className = 'btn btn-sm';
            zoomOut.type = 'button';
            zoomOut.textContent = 'A-';
            zoomOut.onclick = function () {
                state.fontScale = Math.max(0.85, +(state.fontScale - 0.1).toFixed(2));
                applyAccessibility();
            };

            var zoomIn = document.createElement('button');
            zoomIn.className = 'btn btn-sm';
            zoomIn.type = 'button';
            zoomIn.textContent = 'A+';
            zoomIn.onclick = function () {
                state.fontScale = Math.min(1.8, +(state.fontScale + 0.1).toFixed(2));
                applyAccessibility();
            };

            var hcBtn = document.createElement('button');
            hcBtn.className = 'btn btn-sm';
            hcBtn.type = 'button';
            hcBtn.textContent = 'Contrast';
            hcBtn.onclick = function () {
                state.highContrast = !state.highContrast;
                applyAccessibility();
            };

            var timer = document.createElement('span');
            timer.id = 'qb-stu-timer';
            timer.className = 'qb-timer';
            timer.textContent = '--:--:--';

            var finishBtn = document.createElement('button');
            finishBtn.className = 'btn btn-danger btn-sm';
            finishBtn.type = 'button';
            finishBtn.textContent = 'Submit Exam';
            finishBtn.onclick = function () { finishSession(false); };

            right.appendChild(syncSpan);
            right.appendChild(zoomOut);
            right.appendChild(zoomIn);
            right.appendChild(hcBtn);
            right.appendChild(timer);
            right.appendChild(finishBtn);

            bar.appendChild(left);
            bar.appendChild(right);

            var main = document.createElement('div');
            main.className = 'qb-container';

            var stripCard = document.createElement('div');
            stripCard.className = 'qb-card';
            var strip = document.createElement('div');
            strip.id = 'qb-stu-strip';
            strip.className = 'qb-qstrip';
            stripCard.appendChild(strip);

            var qCard = document.createElement('div');
            qCard.id = 'qb-stu-qcard';
            qCard.className = 'qb-card';

            main.appendChild(stripCard);
            main.appendChild(qCard);

            container.appendChild(bar);
            container.appendChild(main);
            updateClockTick();
        }

        function updateSyncBadge() {
            var el = container.querySelector('#qb-stu-sync');
            if (!el) return;
            el.textContent = state.syncState;
            el.className = 'badge ' + (state.syncState === 'SYNCED' ? 'badge-ok' : 'badge-warn');
        }

        function renderQuestion() {
            var strip = container.querySelector('#qb-stu-strip');
            var card = container.querySelector('#qb-stu-qcard');
            if (!strip || !card) return;

            strip.textContent = '';
            paper.questions.forEach(function (item, i) {
                var pill = document.createElement('button');
                pill.type = 'button';
                pill.className = 'qb-qpill';
                var sel = state.selections[item.position];
                if (sel !== null && sel !== undefined) pill.classList.add('answered');
                if (i === state.currentIdx) pill.classList.add('current');
                pill.textContent = String(item.position);
                pill.setAttribute('aria-label', 'Question ' + item.position);
                pill.onclick = function () {
                    state.currentIdx = i;
                    renderQuestion();
                };
                strip.appendChild(pill);
            });

            var q = paper.questions[state.currentIdx];
            card.textContent = '';

            var qHdr = document.createElement('div');
            qHdr.style.marginBottom = '10px';
            qHdr.style.fontWeight = '700';
            qHdr.textContent = 'Question ' + q.position + ' of ' + paper.questions.length;
            card.appendChild(qHdr);

            var stem = document.createElement('div');
            stem.className = 'qb-stem';
            stem.textContent = q.text;
            card.appendChild(stem);

            var optsDiv = document.createElement('div');
            optsDiv.className = 'qb-options';
            var currentSel = state.selections[q.position];

            q.options.forEach(function (optText, idx) {
                var label = document.createElement('label');
                label.className = 'qb-opt' + (currentSel === idx ? ' selected' : '');

                var radio = document.createElement('input');
                radio.type = 'radio';
                radio.name = 'q_' + q.position;
                radio.checked = currentSel === idx;
                radio.onclick = function () {
                    var nextVal = currentSel === idx ? null : idx;
                    saveChoice(q.position, nextVal);
                    renderQuestion();
                };

                var letter = document.createElement('strong');
                letter.textContent = LETTERS[idx] + '.';

                var txt = document.createElement('span');
                txt.className = 'qb-opt-text';
                txt.textContent = optText;

                label.appendChild(radio);
                label.appendChild(letter);
                label.appendChild(txt);
                optsDiv.appendChild(label);
            });
            card.appendChild(optsDiv);

            var nav = document.createElement('div');
            nav.className = 'qb-row';
            nav.style.justifyContent = 'space-between';
            nav.style.marginTop = '18px';
            nav.style.marginBottom = '0';

            var prevBtn = document.createElement('button');
            prevBtn.type = 'button';
            prevBtn.className = 'btn';
            prevBtn.textContent = 'Previous';
            prevBtn.disabled = state.currentIdx === 0;
            prevBtn.onclick = function () { navigate(-1); };

            var clearBtn = document.createElement('button');
            clearBtn.type = 'button';
            clearBtn.className = 'btn';
            clearBtn.textContent = 'Clear Choice';
            clearBtn.disabled = currentSel === null || currentSel === undefined;
            clearBtn.onclick = function () {
                saveChoice(q.position, null);
                renderQuestion();
            };

            var nextBtn = document.createElement('button');
            nextBtn.type = 'button';
            nextBtn.className = 'btn btn-primary';
            nextBtn.textContent = state.currentIdx >= paper.questions.length - 1 ? 'Review / Finish' : 'Next';
            nextBtn.onclick = function () {
                if (state.currentIdx >= paper.questions.length - 1) finishSession(false);
                else navigate(1);
            };

            nav.appendChild(prevBtn);
            nav.appendChild(clearBtn);
            nav.appendChild(nextBtn);
            card.appendChild(nav);
        }

        function pad2(n) {
            return n < 10 ? '0' + n : String(n);
        }

        return { destroy: cleanup };
    }

    global.QBankStudentView = { mount: mount };
})(window);
