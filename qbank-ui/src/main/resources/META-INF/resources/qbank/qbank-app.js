(function (global) {
    'use strict';

    var Api = global.QBankApi;
    var root = document.getElementById('qb-content');
    var nav = document.getElementById('qb-header-nav');
    var flash = document.getElementById('qb-flash');
    var modalHost = document.getElementById('qb-modal-host');

    var app = {
        authMode: 'standalone',
        setupRequired: false,
        user: null,
        activeTab: null
    };

    function clearEl(el) { el.textContent = ''; }

    function setFlash(msg, kind) {
        clearEl(flash);
        if (!msg) return;
        var d = document.createElement('div');
        d.className = 'qb-alert ' + (kind === 'ok' ? 'qb-alert-ok' : kind === 'warn' ? 'qb-alert-warn' : 'qb-alert-err');
        d.textContent = msg;
        flash.appendChild(d);
    }

    function el(tag, attrs, text) {
        var node = document.createElement(tag);
        if (attrs) {
            Object.keys(attrs).forEach(function (k) {
                if (k === 'className') node.className = attrs[k];
                else if (k === 'onclick') node.onclick = attrs[k];
                else node.setAttribute(k, attrs[k]);
            });
        }
        if (text !== undefined && text !== null) node.textContent = String(text);
        return node;
    }

    function btn(text, cls, onClick) {
        var b = el('button', { type: 'button', className: 'btn ' + (cls || '') }, text);
        b.onclick = onClick;
        return b;
    }

    function openModal(title, bodyBuilder) {
        clearEl(modalHost);
        var backdrop = el('div', { className: 'qb-modal-backdrop' });
        var dialog = el('div', { className: 'qb-modal' });
        dialog.appendChild(el('h3', null, title));
        bodyBuilder(dialog, closeModal);
        backdrop.appendChild(dialog);
        modalHost.appendChild(backdrop);
    }

    function closeModal() { clearEl(modalHost); }

    async function bootstrap() {
        clearEl(root);
        root.appendChild(el('div', { className: 'qb-card' }, 'Loading QBank...'));
        var lastErr = null;
        for (var attempt = 0; attempt < 3; attempt++) {
            try {
                var info = await Api.request('/auth/info');
                app.authMode = info.authMode;
                app.setupRequired = !!info.setupRequired;
                app.user = info.user || null;

                if (app.user) {
                    app.activeTab = app.user.role;
                }
                renderNav();
                renderBody();
                return;
            } catch (e) {
                lastErr = e;
                if (attempt < 2) {
                    await new Promise(function (resolve) { setTimeout(resolve, 1200); });
                }
            }
        }
        renderNav();
        clearEl(root);
        setFlash(lastErr ? lastErr.message : 'Unable to reach QBank server', 'err');
        var errCard = el('div', { className: 'qb-card', style: 'max-width:460px;margin:30px auto;' });
        errCard.appendChild(el('h2', null, 'Server Waking Up'));
        errCard.appendChild(el('p', null, lastErr ? lastErr.message : 'Service unavailable'));
        errCard.appendChild(btn('Retry', 'btn-primary', bootstrap));
        root.appendChild(errCard);
    }

    function renderNav() {
        clearEl(nav);
        if (!app.user) return;
        nav.appendChild(el('span', { className: 'badge' }, app.user.displayName + ' (' + app.user.role + ')'));

        if (app.authMode === 'standalone') {
            nav.appendChild(btn('Change Password', 'btn-sm', showChangePasswordModal));
            nav.appendChild(btn('Logout', 'btn-sm', async function () {
                try { await Api.request('/auth/logout', { method: 'POST' }); } catch (ignored) {}
                Api.setToken(null);
                app.user = null;
                bootstrap();
            }));
        }
    }

    function renderBody() {
        setFlash('');
        clearEl(root);

        if (app.setupRequired) {
            renderSetupWizard();
            return;
        }
        if (!app.user) {
            if (app.authMode === 'host') {
                root.appendChild(el('div', { className: 'qb-card' }, 'Sign in via your host portal session to access QBank.'));
            } else {
                renderLoginForm();
            }
            return;
        }
        if (app.user.mustChangePassword && app.authMode === 'standalone') {
            renderForcePasswordChange();
            return;
        }

        switch (app.user.role) {
            case 'HOD': renderHodView(); break;
            case 'COE': renderCoeView(); break;
            case 'ADMIN': renderAdminView(); break;
            default: root.appendChild(el('div', { className: 'qb-card' },
                    'This portal is dedicated to HOD and COE workflows. Signed-in role "' + app.user.role + '" has no workspace here.'));
        }
    }

    function renderSetupWizard() {
        var card = el('div', { className: 'qb-card', style: 'max-width:480px;margin:30px auto;' });
        card.appendChild(el('h2', null, 'Initial Admin Setup'));
        card.appendChild(el('p', null, 'Create the primary Admin account to set up HOD & COE accounts and subjects.'));

        var form = el('form');
        var uIn = el('input', { type: 'text', placeholder: 'admin', autocomplete: 'username' });
        var nIn = el('input', { type: 'text', placeholder: 'System Administrator' });
        var pIn = el('input', { type: 'password', placeholder: 'Minimum 8 characters', autocomplete: 'new-password' });
        var subBtn = el('button', { type: 'submit', className: 'btn btn-primary' }, 'Create Admin Account');

        form.appendChild(field('Username', uIn));
        form.appendChild(field('Full Name', nIn));
        form.appendChild(field('Password', pIn));
        form.appendChild(subBtn);
        form.onsubmit = async function (ev) {
            ev.preventDefault();
            try {
                await Api.request('/auth/setup-admin', {
                    method: 'POST',
                    json: { username: uIn.value, displayName: nIn.value, password: pIn.value }
                });
                app.setupRequired = false;
                renderBody();
                setFlash('Admin account created. Sign in to continue.', 'ok');
            } catch (e) { setFlash(e.message, 'err'); }
        };
        card.appendChild(form);
        root.appendChild(card);
    }

    function renderLoginForm() {
        var card = el('div', { className: 'qb-card', style: 'max-width:440px;margin:30px auto;' });
        card.appendChild(el('h2', null, 'Staff Sign-In'));
        card.appendChild(el('p', { style: 'margin:0 0 12px;color:var(--qb-muted);font-size:0.9em;' },
                'HOD: Excel Upload · COE: Paper Generation · Admin: Users & Subjects'));
        var form = el('form');
        var uIn = el('input', { type: 'text', autocomplete: 'username' });
        var pIn = el('input', { type: 'password', autocomplete: 'current-password' });
        form.appendChild(field('Username', uIn));
        form.appendChild(field('Password', pIn));
        form.appendChild(el('button', { type: 'submit', className: 'btn btn-primary' }, 'Sign In'));
        form.onsubmit = async function (ev) {
            ev.preventDefault();
            try {
                var res = await Api.request('/auth/login', {
                    method: 'POST',
                    bearerToken: null,
                    json: { username: uIn.value, password: pIn.value }
                });
                Api.setToken(res.token);
                app.user = res.user;
                renderNav();
                renderBody();
            } catch (e) { setFlash(e.message, 'err'); }
        };
        card.appendChild(form);
        root.appendChild(card);
    }

    function renderForcePasswordChange() {
        var card = el('div', { className: 'qb-card', style: 'max-width:420px;margin:30px auto;' });
        card.appendChild(el('h2', null, 'Change Temporary Password'));
        var cur = el('input', { type: 'password' });
        var nw = el('input', { type: 'password' });
        card.appendChild(field('Current Password', cur));
        card.appendChild(field('New Password (8+ chars)', nw));
        card.appendChild(btn('Update Password', 'btn-primary', async function () {
            try {
                await Api.request('/auth/change-password', {
                    method: 'POST',
                    json: { currentPassword: cur.value, newPassword: nw.value }
                });
                app.user.mustChangePassword = false;
                renderBody();
            } catch (e) { setFlash(e.message, 'err'); }
        }));
        root.appendChild(card);
    }

    function showChangePasswordModal() {
        openModal('Change Password', function (dlg, close) {
            var cur = el('input', { type: 'password' });
            var nw = el('input', { type: 'password' });
            dlg.appendChild(field('Current Password', cur));
            dlg.appendChild(field('New Password', nw));
            var r = el('div', { className: 'qb-row', style: 'margin-top:12px;' });
            r.appendChild(btn('Save', 'btn-primary', async function () {
                try {
                    await Api.request('/auth/change-password', { method: 'POST', json: { currentPassword: cur.value, newPassword: nw.value } });
                    close();
                    setFlash('Password updated', 'ok');
                } catch (e) { setFlash(e.message, 'err'); }
            }));
            r.appendChild(btn('Cancel', '', close));
            dlg.appendChild(r);
        });
    }

    function field(labelTxt, inputEl) {
        var f = el('div', { className: 'qb-field', style: 'margin-bottom:10px;' });
        f.appendChild(el('label', null, labelTxt));
        f.appendChild(inputEl);
        return f;
    }

    function openNewSubjectModal(onCreated) {
        openModal('Create Subject', function (dlg, close) {
            var cIn = el('input', { type: 'text', placeholder: 'CS101' });
            var nIn = el('input', { type: 'text', placeholder: 'Algorithms & Data Structures' });
            var eIn = el('input', { type: 'number', value: '120', min: '1' });
            var mIn = el('input', { type: 'number', value: '1', min: '1' });
            dlg.appendChild(field('Subject Code', cIn));
            dlg.appendChild(field('Subject Name', nIn));
            dlg.appendChild(field('Expected Questions / Module', eIn));
            dlg.appendChild(field('Marks / Question', mIn));
            var r = el('div', { className: 'qb-row', style: 'margin-top:12px;' });
            var saveBtn = btn('Create Subject', 'btn-primary', async function () {
                saveBtn.disabled = true;
                try {
                    var created = await Api.request('/subjects', {
                        method: 'POST',
                        json: {
                            code: cIn.value,
                            name: nIn.value,
                            expectedQuestionsPerModule: Number(eIn.value) || 120,
                            marksPerQuestion: Number(mIn.value) || 1
                        }
                    });
                    close();
                    if (onCreated) await onCreated(created);
                    setFlash('Created subject ' + created.code + ' — ' + created.name, 'ok');
                } catch (e) { setFlash(e.message, 'err'); }
                finally { saveBtn.disabled = false; }
            });
            r.appendChild(saveBtn);
            r.appendChild(btn('Cancel', '', close));
            dlg.appendChild(r);
        });
    }

    /* ================= HOD VIEW ================= */
    async function renderHodView() {
        var subjects = await Api.request('/subjects');
        var card = el('div', { className: 'qb-card' });
        card.appendChild(el('h2', null, 'HOD Excel Upload'));

        if (subjects.length === 0) {
            card.appendChild(el('div', { className: 'qb-alert qb-alert-warn' },
                    'No active subjects yet. Click "+ Subject" to create one.'));
        }

        var selSub = el('select');
        subjects.forEach(function (s) {
            selSub.appendChild(el('option', { value: s.id }, s.code + ' — ' + s.name));
        });

        var selMod = el('select');
        selMod.appendChild(el('option', { value: 'MULTI' }, 'Multi-Sheet (Module 1..5 / 1A..5B)'));
        for (var m = 1; m <= 5; m++) {
            selMod.appendChild(el('option', { value: String(m) }, 'Module ' + m + ' (Full)'));
            selMod.appendChild(el('option', { value: String(m * 10 + 1) }, 'Module ' + m + ' — 1st Half (Part A)'));
            selMod.appendChild(el('option', { value: String(m * 10 + 2) }, 'Module ' + m + ' — 2nd Half (Part B)'));
        }

        var fileIn = el('input', { type: 'file', accept: '.xlsx,.xls' });

        var row = el('div', { className: 'qb-row' });
        row.appendChild(field('Subject', selSub));
        row.appendChild(field('Mode', selMod));
        row.appendChild(field('Excel File (.xlsx / .xls)', fileIn));
        card.appendChild(row);

        var actions = el('div', { className: 'qb-row' });
        var prevBtn = btn('Preview', 'btn-primary', async function () {
            if (!selSub.value || !fileIn.files[0]) { setFlash('Select subject and file', 'err'); return; }
            var fd = new FormData();
            fd.append('file', fileIn.files[0]);
            var mode = selMod.value;
            var path = mode === 'MULTI'
                    ? '/hod/subjects/' + selSub.value + '/import-multi/preview'
                    : '/hod/subjects/' + selSub.value + '/modules/' + mode + '/preview';
            prevBtn.disabled = true;
            try {
                var res = await Api.request(path, { method: 'POST', body: fd });
                var list = Array.isArray(res) ? res : [res];
                setFlash('', 'ok');
                renderHodPreviews(previewBox, list, function () { loadModules(selSub.value, modBox); });
            } catch (e) { setFlash(e.message, 'err'); }
            finally { prevBtn.disabled = false; }
        });
        actions.appendChild(prevBtn);
        actions.appendChild(btn('Template', '', function () {
            downloadBinary('/hod/template?multi=true', 'qbank-5module-template.xlsx');
        }));
        actions.appendChild(btn('+ Subject', '', function () {
            openNewSubjectModal(async function () { await renderBody(); });
        }));
        card.appendChild(actions);

        var modBox = el('div', { className: 'qb-grid-5', style: 'margin-top:12px;' });
        var previewBox = el('div', { style: 'margin-top:16px;' });
        card.appendChild(modBox);
        root.appendChild(card);
        root.appendChild(previewBox);

        selSub.onchange = function () { loadModules(selSub.value, modBox); };
        if (selSub.value) loadModules(selSub.value, modBox);
    }

    function formatModuleUnit(u) {
        var n = Number(u);
        if (n >= 1 && n <= 5) return 'Module ' + n;
        var base = Math.floor(n / 10);
        var part = n % 10;
        if (base >= 1 && base <= 5 && (part === 1 || part === 2)) {
            return 'Module ' + base + ' (' + (part === 1 ? '1st Half' : '2nd Half') + ')';
        }
        return 'Module ' + n;
    }

    function formatModuleShort(u) {
        var n = Number(u);
        if (n >= 1 && n <= 5) return 'M' + n;
        var base = Math.floor(n / 10);
        var part = n % 10;
        if (base >= 1 && base <= 5 && (part === 1 || part === 2)) {
            return 'M' + base + (part === 1 ? 'A' : 'B');
        }
        return 'M' + n;
    }

    function moduleUnitWeight(u) {
        var n = Number(u);
        return (n >= 1 && n <= 5) ? 2 : (Math.floor(n / 10) >= 1 ? 1 : 0);
    }

    function renderModuleStatusCards(mods, targetEl) {
        clearEl(targetEl);
        var byNum = {};
        (mods || []).forEach(function (md) { byNum[md.moduleNumber] = md; });
        for (var m = 1; m <= 5; m++) {
            var full = byNum[m];
            var h1 = byNum[m * 10 + 1];
            var h2 = byNum[m * 10 + 2];
            var box = el('div', { className: 'qb-stat-box' });
            box.appendChild(el('strong', null, 'Module ' + m));
            var usingHalves = (h1 && h1.status === 'READY') || (h2 && h2.status === 'READY');
            if (usingHalves && (!full || full.status !== 'READY')) {
                var r1 = h1 && h1.status === 'READY';
                var r2 = h2 && h2.status === 'READY';
                var w1 = el('div', { style: 'margin-top:6px;' });
                w1.appendChild(el('span', { className: 'badge ' + (r1 ? 'badge-ok' : 'badge-warn') },
                        'A: ' + (r1 ? '✓ ' : '○ ') + (h1 ? h1.questionCount : 0)));
                var w2 = el('div', { style: 'margin-top:4px;' });
                w2.appendChild(el('span', { className: 'badge ' + (r2 ? 'badge-ok' : 'badge-warn') },
                        'B: ' + (r2 ? '✓ ' : '○ ') + (h2 ? h2.questionCount : 0)));
                box.appendChild(w1);
                box.appendChild(w2);
            } else {
                var ready = full && full.status === 'READY';
                var wrapStatus = el('div', { style: 'margin-top:6px;' });
                wrapStatus.appendChild(el('span', { className: 'badge ' + (ready ? 'badge-ok' : 'badge-warn') },
                        (ready ? '✓ READY · ' : '○ EMPTY · ') + (full ? full.questionCount : 0)));
                box.appendChild(wrapStatus);
            }
            targetEl.appendChild(box);
        }
        return byNum;
    }

    async function loadModules(subjectId, targetEl) {
        clearEl(targetEl);
        if (!subjectId) return;
        var mods = await Api.request('/subjects/' + subjectId + '/modules');
        renderModuleStatusCards(mods, targetEl);
    }

    function rowStatusBadgeClass(status) {
        if (status === 'VALID') return 'badge-ok';
        if (status === 'WARNING') return 'badge-warn';
        if (status === 'ERROR') return 'badge-err';
        return '';
    }

    function sortedPreviewIssues(issues) {
        var rank = { ERROR: 0, WARNING: 1, INFO: 2 };
        return (issues || []).slice().sort(function (a, b) {
            var ra = rank[a.severity] !== undefined ? rank[a.severity] : 3;
            var rb = rank[b.severity] !== undefined ? rank[b.severity] : 3;
            return ra !== rb ? ra - rb : (a.sourceRow || 0) - (b.sourceRow || 0);
        });
    }

    function formatOptionsSummary(options) {
        if (!options || !options.length) return '(no options parsed)';
        return options.map(function (opt, idx) {
            return String.fromCharCode(65 + idx) + ') ' + opt;
        }).join(' | ');
    }

    function openHodRowEditModal(preview, previewIdx, row, previews, host, onConfirmed) {
        openModal('Edit Row ' + row.sourceRow + ' (' + formatModuleUnit(preview.moduleNumber) + ')', function (dlg, close) {
            var qIn = el('textarea', { rows: '3', style: 'width:100%' });
            qIn.value = row.questionText || '';
            dlg.appendChild(field('Question Text', qIn));

            var baseOpts = (row.options && row.options.length > 0) ? row.options : ['', '', '', ''];
            var optCount = Math.max(4, Math.min(5, baseOpts.length));
            var optInputs = [];
            for (var i = 0; i < optCount; i++) {
                var oIn = el('input', { type: 'text', style: 'width:100%' });
                oIn.value = baseOpts[i] || '';
                optInputs.push(oIn);
                dlg.appendChild(field('Option ' + String.fromCharCode(65 + i), oIn));
            }

            var corrSel = el('select');
            for (var j = 0; j < optCount; j++) {
                corrSel.appendChild(el('option', { value: String(j) }, 'Option ' + String.fromCharCode(65 + j)));
            }
            if (row.correctIndex !== null && row.correctIndex !== undefined && row.correctIndex >= 0 && row.correctIndex < optCount) {
                corrSel.value = String(row.correctIndex);
            }
            dlg.appendChild(field('Correct Answer', corrSel));

            var btnRow = el('div', { className: 'qb-row', style: 'margin-top:12px;' });
            var saveBtn = btn('Save & Revalidate', 'btn-primary', async function () {
                var opts = optInputs.map(function (inp) { return inp.value.trim(); });
                if (opts.length === 5 && !opts[4]) {
                    opts = opts.slice(0, 4);
                }
                var corrIdx = parseInt(corrSel.value, 10);
                if (isNaN(corrIdx) || corrIdx >= opts.length) corrIdx = 0;
                saveBtn.disabled = true;
                try {
                    var updated = await Api.request('/hod/previews/' + preview.previewId + '/rows/' + row.sourceRow, {
                        method: 'PUT',
                        json: {
                            questionText: qIn.value.trim(),
                            options: opts,
                            correctIndex: corrIdx,
                            excluded: false
                        }
                    });
                    previews[previewIdx] = updated;
                    close();
                    renderHodPreviews(host, previews, onConfirmed);
                    setFlash('Row ' + row.sourceRow + ' updated and revalidated.', 'ok');
                } catch (e) { setFlash(e.message, 'err'); }
                finally { saveBtn.disabled = false; }
            });
            btnRow.appendChild(saveBtn);
            btnRow.appendChild(btn('Cancel', '', close));
            dlg.appendChild(btnRow);
        });
    }

    function renderHodPreviews(host, previews, onConfirmed) {
        clearEl(host);
        if (!previews || previews.length === 0) return;

        if (previews.length > 1) {
            var readyPreviews = previews.filter(function (pr) { return pr.canConfirmDirectly; });
            var topCard = el('div', { className: 'qb-card' });
            topCard.appendChild(el('strong', null, previews.length + ' sheets loaded · ' + readyPreviews.length + ' ready · '));
            var confirmAllBtn = btn('Confirm All (' + readyPreviews.length + ')', 'btn-primary', async function () {
                if (readyPreviews.length === 0) return;
                confirmAllBtn.disabled = true;
                var confirmedMsgs = [];
                try {
                    for (var i = 0; i < readyPreviews.length; i++) {
                        var target = readyPreviews[i];
                        var c = await Api.request('/hod/previews/' + target.previewId + '/confirm', { method: 'POST' });
                        confirmedMsgs.push(formatModuleShort(c.moduleNumber) + ': ' + c.questionCount);
                        var pos = previews.indexOf(target);
                        if (pos >= 0) previews.splice(pos, 1);
                    }
                    await onConfirmed();
                    renderHodPreviews(host, previews, onConfirmed);
                    setFlash('Confirmed: ' + confirmedMsgs.join(', '), 'ok');
                } catch (e) {
                    await onConfirmed();
                    renderHodPreviews(host, previews, onConfirmed);
                    setFlash((confirmedMsgs.length ? 'Confirmed ' + confirmedMsgs.join(', ') + ' before error: ' : '') + e.message, 'err');
                } finally {
                    confirmAllBtn.disabled = false;
                }
            });
            if (readyPreviews.length === 0) confirmAllBtn.disabled = true;
            topCard.appendChild(confirmAllBtn);
            host.appendChild(topCard);
        }

        previews.forEach(function (p, previewIdx) {
            var card = el('div', { className: 'qb-card' });
            var hdrRow = el('div', { className: 'qb-row', style: 'justify-content:space-between;align-items:center;' });
            hdrRow.appendChild(el('h3', { style: 'margin:0;' }, formatModuleUnit(p.moduleNumber) + ' (' + p.sheetName + ')'));
            hdrRow.appendChild(el('span', { className: 'badge ' + (p.canConfirmDirectly ? 'badge-ok' : 'badge-err') },
                    p.canConfirmDirectly ? '✓ READY' : '✕ ' + p.errorCount + ' ERROR(S)'));
            card.appendChild(hdrRow);

            card.appendChild(el('p', null,
                    'Valid: ' + p.validCount + ' | Errors: ' + p.errorCount + ' | Warnings: ' + p.warningCount
                    + ' | Duplicates: ' + (p.duplicateSkippedCount || 0) + ' | Excluded: ' + (p.excludedCount || 0)
                    + ' | Expected: ' + p.expectedCount));

            var issues = sortedPreviewIssues(p.issues);
            if (issues.length > 0) {
                var ul = el('ul');
                issues.slice(0, 25).forEach(function (iss) { ul.appendChild(el('li', null, '[' + iss.severity + '] ' + iss.message)); });
                card.appendChild(ul);
            }

            var actRow = el('div', { className: 'qb-row' });
            var confirmBtn = btn('Confirm', 'btn-primary', async function () {
                confirmBtn.disabled = true;
                try {
                    var c = await Api.request('/hod/previews/' + p.previewId + '/confirm', { method: 'POST' });
                    previews.splice(previewIdx, 1);
                    renderHodPreviews(host, previews, onConfirmed);
                    await onConfirmed();
                    setFlash(formatModuleUnit(c.moduleNumber) + ' READY: ' + c.questionCount + ' questions', 'ok');
                } catch (e) { setFlash(e.message, 'err'); }
                finally { confirmBtn.disabled = false; }
            });
            if (!p.canConfirmDirectly) {
                confirmBtn.disabled = true;
                confirmBtn.title = 'Fix or exclude error rows first';
            }
            actRow.appendChild(confirmBtn);
            actRow.appendChild(btn('Discard', 'btn-sm', async function () {
                try {
                    await Api.request('/hod/previews/' + p.previewId, { method: 'DELETE' });
                } catch (ignored) {}
                previews.splice(previewIdx, 1);
                renderHodPreviews(host, previews, onConfirmed);
            }));
            card.appendChild(actRow);

            var wrap = el('div', { className: 'qb-table-wrap', style: 'margin-top:12px;' });
            var tbl = el('table');
            var thr = el('tr');
            ['Row', 'Status', 'Question & Options', 'Correct', 'Issues', 'Actions'].forEach(function (h) { thr.appendChild(el('th', null, h)); });
            tbl.appendChild(thr);

            (p.rows || []).forEach(function (r) {
                var tr = el('tr');
                tr.appendChild(el('td', null, r.sourceRow));
                var tdStatus = el('td');
                tdStatus.appendChild(el('span', { className: 'badge ' + rowStatusBadgeClass(r.status) }, r.status));
                tr.appendChild(tdStatus);

                var tdQ = el('td');
                tdQ.appendChild(el('div', null, r.questionText || ''));
                tdQ.appendChild(el('div', { style: 'font-size:0.85em;color:var(--qb-muted);margin-top:2px;' },
                        formatOptionsSummary(r.options)));
                tr.appendChild(tdQ);

                tr.appendChild(el('td', null, r.correctIndex !== null && r.correctIndex !== undefined && r.correctIndex >= 0
                        ? String.fromCharCode(65 + r.correctIndex) : 'MISSING'));
                tr.appendChild(el('td', null, (r.issues || []).map(function (iss) { return iss.message; }).join('; ')));

                var tdAct = el('td');
                if (r.status !== 'DUPLICATE_SKIPPED') {
                    tdAct.appendChild(btn('Edit', 'btn-sm', function () {
                        openHodRowEditModal(p, previewIdx, r, previews, host, onConfirmed);
                    }));
                }
                if (r.status === 'ERROR' || r.status === 'WARNING' || r.status === 'EXCLUDED') {
                    var exBtn = btn(r.excluded ? 'Include' : 'Exclude', 'btn-sm', async function () {
                        exBtn.disabled = true;
                        try {
                            var updated = await Api.request('/hod/previews/' + p.previewId + '/rows/' + r.sourceRow, {
                                method: 'PUT',
                                json: { questionText: r.questionText, options: r.options, correctIndex: r.correctIndex, excluded: !r.excluded }
                            });
                            previews[previewIdx] = updated;
                            renderHodPreviews(host, previews, onConfirmed);
                        } catch (e) { setFlash(e.message, 'err'); }
                        finally { exBtn.disabled = false; }
                    });
                    tdAct.appendChild(exBtn);
                }
                tr.appendChild(tdAct);
                tbl.appendChild(tr);
            });
            wrap.appendChild(tbl);
            card.appendChild(wrap);
            host.appendChild(card);
        });
    }

    /* ================= COE VIEW ================= */
    async function renderCoeView() {
        var subjects = await Api.request('/subjects');
        var card = el('div', { className: 'qb-card' });
        card.appendChild(el('h2', null, 'COE Paper Generation'));

        if (subjects.length === 0) {
            card.appendChild(el('div', { className: 'qb-alert qb-alert-warn' },
                    'No active subjects yet. Click "+ Subject" to create one.'));
        }

        var selSub = el('select');
        subjects.forEach(function (s) { selSub.appendChild(el('option', { value: s.id }, s.code + ' — ' + s.name)); });
        var titleIn = el('input', { type: 'text', placeholder: 'Exam Title (optional)' });
        var selType = el('select');
        selType.appendChild(el('option', { value: 'INTERNAL' }, 'INTERNAL (30 marks)'));
        selType.appendChild(el('option', { value: 'SEE' }, 'SEE (50 marks, M1-M5)'));
        var dateIn = el('input', { type: 'date', value: new Date().toISOString().slice(0, 10) });
        var rotSel = el('select');
        for (var ro = 0; ro <= 4; ro++) {
            rotSel.appendChild(el('option', { value: String(ro) }, String(ro)));
        }

        var r1 = el('div', { className: 'qb-row' });
        r1.appendChild(field('Subject', selSub));
        r1.appendChild(field('Exam Title', titleIn));
        r1.appendChild(field('Exam Type', selType));
        r1.appendChild(field('Exam Date', dateIn));
        r1.appendChild(field('Rotation', rotSel));
        card.appendChild(r1);

        // Module unit selectors (Off / Full 1.0 / 1st Half 0.5 / 2nd Half 0.5 per M1..M5)
        var modSelectors = [];
        var modWeightBadge = el('span', { className: 'badge', style: 'margin-left:auto;' }, '3.0 modules');
        var modChkRow = el('div', { className: 'qb-row', style: 'align-items:center;flex-wrap:wrap;gap:10px;' });
        modChkRow.appendChild(el('strong', null, 'Modules:'));
        for (var m = 1; m <= 5; m++) {
            var uSel = el('select', { style: 'min-width:118px;padding:5px 8px;' });
            uSel.appendChild(el('option', { value: '' }, 'Off'));
            uSel.appendChild(el('option', { value: String(m) }, 'Full (1.0)'));
            uSel.appendChild(el('option', { value: String(m * 10 + 1) }, '1st Half (0.5)'));
            uSel.appendChild(el('option', { value: String(m * 10 + 2) }, '2nd Half (0.5)'));
            uSel.value = (m <= 3) ? String(m) : '';
            modSelectors.push({ modNum: m, sel: uSel });
            var wrapLbl = el('label', { style: 'display:flex;align-items:center;gap:6px;font-weight:500;' });
            wrapLbl.appendChild(document.createTextNode('M' + m));
            wrapLbl.appendChild(uSel);
            modChkRow.appendChild(wrapLbl);
        }
        modChkRow.appendChild(modWeightBadge);
        card.appendChild(modChkRow);

        var modStatusGrid = el('div', { className: 'qb-grid-5', style: 'margin-bottom:12px;' });
        var blueprintBox = el('div', { className: 'qb-stat-box', style: 'margin-bottom:12px;' }, 'Select a subject to preview module readiness & live question breakdown.');
        card.appendChild(modStatusGrid);
        card.appendChild(blueprintBox);

        var now = new Date();
        var mStartDefault = toLocalDatetimeValue(new Date(now.getTime()));
        var mEndDefault = toLocalDatetimeValue(new Date(now.getTime() + 3600000));
        var mStartIn = el('input', { type: 'datetime-local', value: mStartDefault });
        var mEndIn = el('input', { type: 'datetime-local', value: mEndDefault });
        var aftChk = el('input', { type: 'checkbox' });
        var aStartIn = el('input', { type: 'datetime-local', value: toLocalDatetimeValue(new Date(now.getTime() + 7200000)) });
        var aEndIn = el('input', { type: 'datetime-local', value: toLocalDatetimeValue(new Date(now.getTime() + 10800000)) });

        var r2 = el('div', { className: 'qb-row' });
        r2.appendChild(field('Morning Start', mStartIn));
        r2.appendChild(field('Morning End', mEndIn));
        var aftLbl = el('label', { style: 'display:flex;align-items:center;gap:6px;' });
        aftLbl.appendChild(aftChk);
        aftLbl.appendChild(document.createTextNode('Also generate Afternoon session'));
        r2.appendChild(aftLbl);
        r2.appendChild(field('Afternoon Start', aStartIn));
        r2.appendChild(field('Afternoon End', aEndIn));
        card.appendChild(r2);

        function selectedModules() {
            if (selType.value === 'SEE') return [1, 2, 3, 4, 5];
            return modSelectors
                    .map(function (x) { return Number(x.sel.value); })
                    .filter(function (v) { return v > 0; });
        }

        function syncModuleSelectionMode() {
            var isSee = selType.value === 'SEE';
            modSelectors.forEach(function (x) {
                if (isSee) x.sel.value = String(x.modNum);
                x.sel.disabled = isSee;
            });
            updateSelectedWeightBadge();
        }

        function updateSelectedWeightBadge() {
            var chosen = selectedModules();
            var halfUnits = chosen.reduce(function (acc, u) { return acc + moduleUnitWeight(u); }, 0);
            modWeightBadge.textContent = (halfUnits / 2).toFixed(1) + ' module(s)';
        }

        var cachedModsByNum = {};
        async function refreshCoeBlueprint() {
            clearEl(modStatusGrid);
            clearEl(blueprintBox);
            updateSelectedWeightBadge();
            if (!selSub.value) {
                blueprintBox.textContent = 'No active subject selected.';
                return;
            }
            try {
                var mods = await Api.request('/subjects/' + selSub.value + '/modules');
                cachedModsByNum = renderModuleStatusCards(mods, modStatusGrid);

                var chosen = selectedModules();
                if (chosen.length === 0) {
                    blueprintBox.textContent = 'Select at least 1 module or half-module for INTERNAL blueprint.';
                    return;
                }
                var bp = await Api.request('/coe/blueprint', {
                    method: 'POST',
                    json: {
                        subjectId: Number(selSub.value),
                        examType: selType.value,
                        modules: chosen,
                        rotationOffset: Number(rotSel.value)
                    }
                });
                blueprintBox.appendChild(el('strong', null, bp.summaryText));
                var minPerModNote = [];
                var hasShortage = false;
                var multiplier = aftChk.checked ? 8 : 4;
                Object.keys(bp.moduleCounts || {}).forEach(function (mKey) {
                    var u = Number(mKey);
                    var reqPerSet = bp.moduleCounts[mKey];
                    var reqTotal = reqPerSet * multiplier;
                    var modInfo = cachedModsByNum[u];
                    var avail = 0;
                    var ready = false;
                    if (modInfo && modInfo.status === 'READY' && modInfo.questionCount > 0) {
                        avail = modInfo.questionCount;
                        ready = true;
                    } else if (u >= 1 && u <= 5) {
                        var h1 = cachedModsByNum[u * 10 + 1];
                        var h2 = cachedModsByNum[u * 10 + 2];
                        if (h1 && h1.status === 'READY' && h1.questionCount > 0 && h2 && h2.status === 'READY' && h2.questionCount > 0) {
                            avail = h1.questionCount + h2.questionCount;
                            ready = true;
                        }
                    }
                    var ok = ready && avail >= reqTotal;
                    if (!ok) hasShortage = true;
                    minPerModNote.push(formatModuleShort(u) + ': need ' + reqTotal + ' (' + multiplier + '×' + reqPerSet + '), have ' + avail + (ok ? ' OK' : ' SHORT'));
                });
                blueprintBox.appendChild(el('div', {
                    style: 'margin-top:4px;font-size:0.9em;color:' + (hasShortage ? 'var(--qb-danger)' : 'var(--qb-ok)')
                }, minPerModNote.join(' · ')));
            } catch (e) {
                blueprintBox.textContent = 'Blueprint unavailable: ' + e.message;
            }
        }

        selSub.onchange = refreshCoeBlueprint;
        selType.onchange = function () { syncModuleSelectionMode(); refreshCoeBlueprint(); };
        rotSel.onchange = refreshCoeBlueprint;
        aftChk.onchange = refreshCoeBlueprint;
        modSelectors.forEach(function (x) { x.sel.onchange = refreshCoeBlueprint; });

        var coeActRow = el('div', { className: 'qb-row' });
        var genBtn = btn('Generate', 'btn-primary', async function () {
            if (!selSub.value) { setFlash('Create and select a subject first', 'err'); return; }
            var modules = selectedModules();
            if (modules.length === 0) { setFlash('Select at least one module', 'err'); return; }
            genBtn.disabled = true;
            try {
                await Api.request('/coe/exams/generate', {
                    method: 'POST',
                    json: {
                        subjectId: Number(selSub.value),
                        title: titleIn.value.trim() || null,
                        examType: selType.value,
                        modules: modules,
                        examDate: dateIn.value,
                        rotationOffset: Number(rotSel.value),
                        includeAfternoon: aftChk.checked,
                        morningStartAt: new Date(mStartIn.value).toISOString(),
                        morningEndAt: new Date(mEndIn.value).toISOString(),
                        afternoonStartAt: aftChk.checked ? new Date(aStartIn.value).toISOString() : null,
                        afternoonEndAt: aftChk.checked ? new Date(aEndIn.value).toISOString() : null
                    }
                });
                setFlash('4 disjoint sets generated (zero-knowledge metadata only).', 'ok');
                refreshCoeSessions();
            } catch (e) { setFlash(e.message, 'err'); }
            finally { genBtn.disabled = false; }
        });
        coeActRow.appendChild(genBtn);
        coeActRow.appendChild(btn('+ Subject', '', function () {
            openNewSubjectModal(async function () { await renderBody(); });
        }));
        card.appendChild(coeActRow);
        root.appendChild(card);

        var sessionsCard = el('div', { className: 'qb-card' });
        sessionsCard.appendChild(el('h3', null, 'Exam Sessions'));
        var sessionsHost = el('div');
        sessionsCard.appendChild(sessionsHost);
        root.appendChild(sessionsCard);

        async function refreshCoeSessions() {
            clearEl(sessionsHost);
            var sessions = await Api.request('/coe/sessions');
            if (sessions.length === 0) {
                sessionsHost.appendChild(el('p', { style: 'color:var(--qb-muted);' }, 'No exam sessions generated yet.'));
                return;
            }
            sessions.forEach(function (item) {
                var sBox = el('div', { className: 'qb-stat-box', style: 'margin-bottom:10px;' });
                var topRow = el('div', { className: 'qb-row', style: 'justify-content:space-between;align-items:center;margin-bottom:4px;' });
                topRow.appendChild(el('strong', null, item.subjectCode + ' — ' + item.title + ' [' + item.slot + '] (' + (item.examType || '') + ' · ' + (item.examDate || '') + ')'));
                var statusCls = item.status === 'LIVE' || item.status === 'FROZEN' ? 'badge-ok' : item.status === 'DRAFT' ? 'badge-warn' : '';
                topRow.appendChild(el('span', { className: 'badge ' + statusCls }, item.status));
                sBox.appendChild(topRow);

                var sMeta = el('div', { style: 'margin:4px 0;font-size:0.92em;' },
                        'Session #' + item.sessionId + ' | Selected Set: ' + (item.chosenSetNumber ? ('Set ' + item.chosenSetNumber + ' (FROZEN)') : 'Pending Selection')
                        + ' | Window: ' + formatInstant(item.scheduledStartAt) + ' -> ' + formatInstant(item.scheduledEndAt));
                sBox.appendChild(sMeta);

                var actRow = el('div', { className: 'qb-row', style: 'margin-top:8px;margin-bottom:0;' });
                if (item.status === 'DRAFT') {
                    actRow.appendChild(btn('Approve', 'btn-primary btn-sm', function () {
                        showSetSelectionModal(item, false, refreshCoeSessions);
                    }));
                } else {
                    actRow.appendChild(btn('View Sets', 'btn-sm', function () {
                        showSetSelectionModal(item, true, refreshCoeSessions);
                    }));
                }
                if (item.status === 'FROZEN') {
                    var startBtn = btn('Start', 'btn-primary btn-sm', async function () {
                        startBtn.disabled = true;
                        try {
                            await Api.request('/coe/sessions/' + item.sessionId + '/start', { method: 'POST' });
                            setFlash('Session #' + item.sessionId + ' LIVE.', 'ok');
                            refreshCoeSessions();
                        } catch (e) { setFlash(e.message, 'err'); }
                        finally { startBtn.disabled = false; }
                    });
                    actRow.appendChild(startBtn);
                }
                if (item.status === 'LIVE' || item.status === 'FROZEN') {
                    var stopBtn = btn('Stop', 'btn-danger btn-sm', async function () {
                        if (!global.confirm('Stop ' + item.subjectCode + ' [' + item.slot + ']? Active sessions will be finalized.')) return;
                        stopBtn.disabled = true;
                        try {
                            await Api.request('/coe/sessions/' + item.sessionId + '/stop', { method: 'POST' });
                            setFlash('Session #' + item.sessionId + ' ENDED.', 'ok');
                            refreshCoeSessions();
                        } catch (e) { setFlash(e.message, 'err'); }
                        finally { stopBtn.disabled = false; }
                    });
                    actRow.appendChild(stopBtn);
                }
                if (item.slot === 'MORNING' && !item.hasAfternoon && (item.status === 'DRAFT' || item.status === 'FROZEN')) {
                    var aftBtn = btn('+ Afternoon', 'btn-sm', async function () {
                        aftBtn.disabled = true;
                        try {
                            await Api.request('/coe/exams/' + item.examId + '/afternoon', {
                                method: 'POST',
                                json: { startAt: new Date(aStartIn.value).toISOString(), endAt: new Date(aEndIn.value).toISOString() }
                            });
                            setFlash('Afternoon session generated with disjoint Set cards.', 'ok');
                            refreshCoeSessions();
                        } catch (e) { setFlash(e.message, 'err'); }
                        finally { aftBtn.disabled = false; }
                    });
                    actRow.appendChild(aftBtn);
                }
                sBox.appendChild(actRow);
                sessionsHost.appendChild(sBox);
            });
        }

        syncModuleSelectionMode();
        refreshCoeBlueprint();
        refreshCoeSessions();
    }

    async function showSetSelectionModal(sessionItem, readOnly, onDone) {
        var sessionId = sessionItem.sessionId;
        var cards = await Api.request('/coe/sessions/' + sessionId + '/sets');
        openModal((readOnly ? 'Paper Sets — ' : 'Select Set — ') + sessionItem.subjectCode + ' [' + sessionItem.slot + ']', function (dlg, close) {
            var grid = el('div', { className: 'qb-grid-4', style: 'margin-bottom:14px;' });
            cards.forEach(function (c) {
                var box = el('div', { className: 'qb-stat-box' });
                box.appendChild(el('strong', null, 'Set ' + c.setNumber + ' '));
                var badgeCls = c.status === 'SELECTED' ? 'badge-ok' : c.status === 'GENERATED' ? 'badge-warn' : '';
                box.appendChild(el('span', { className: 'badge ' + badgeCls }, c.status));
                box.appendChild(el('div', { style: 'margin:6px 0;' }, c.questionCount + ' questions'));
                if (!readOnly && c.status === 'GENERATED') {
                    var revBtn = btn('Approve', 'btn-primary btn-sm', async function () {
                        revBtn.disabled = true;
                        try {
                            var meta = await Api.request('/coe/sessions/' + sessionId + '/sets/' + c.setNumber + '/approval-preview');
                            close();
                            openModal('Approve & Freeze Set ' + meta.setNumber, function (dlg2, close2) {
                                dlg2.appendChild(el('p', null, meta.subjectCode + ' — ' + meta.examTitle + ' [' + meta.slot + ']'));
                                dlg2.appendChild(el('p', null, 'Set ' + meta.setNumber + ' · ' + meta.questionCount + ' questions · ' + meta.totalMarks + ' marks'));
                                dlg2.appendChild(el('div', { className: 'qb-alert qb-alert-warn' },
                                        'Freezing seals Versions A-D and archives Sets ' + [1,2,3,4].filter(function (n) { return n !== meta.setNumber; }).join(', ') + '.'));
                                var r = el('div', { className: 'qb-row' });
                                var freezeBtn = btn('Approve', 'btn-primary', async function () {
                                    freezeBtn.disabled = true;
                                    try {
                                        await Api.request('/coe/sessions/' + sessionId + '/approve', {
                                            method: 'POST',
                                            json: { setNumber: meta.setNumber, expectedVersion: meta.version }
                                        });
                                        close2();
                                        setFlash('Set ' + meta.setNumber + ' FROZEN (Versions A-D sealed).', 'ok');
                                        onDone();
                                    } catch (e) { setFlash(e.message, 'err'); }
                                    finally { freezeBtn.disabled = false; }
                                });
                                r.appendChild(freezeBtn);
                                r.appendChild(btn('Cancel', '', close2));
                                dlg2.appendChild(r);
                            });
                        } catch (e) { setFlash(e.message, 'err'); }
                        finally { revBtn.disabled = false; }
                    });
                    box.appendChild(revBtn);
                }
                grid.appendChild(box);
            });
            dlg.appendChild(grid);
            dlg.appendChild(btn('Close', '', close));
        });
    }

    /* ================= ADMIN VIEW (Minimal Account & Subject Setup) ================= */
    async function renderAdminView() {
        try {
            root.appendChild(el('div', { className: 'qb-alert qb-alert-warn' },
                    'Admin Setup Only — HOD Excel Upload appears after Logout + Sign In as an HOD user. COE Paper Generation appears after Sign In as a COE user.'));

            if (app.authMode === 'standalone') {
                var uCard = el('div', { className: 'qb-card' });
                uCard.appendChild(el('h2', null, 'HOD & COE User Management'));
                var uNameIn = el('input', { type: 'text', placeholder: 'hod_cs' });
                var uDispIn = el('input', { type: 'text', placeholder: 'HOD Computer Science' });
                var uRoleSel = el('select');
                ['HOD', 'COE', 'ADMIN'].forEach(function (r) {
                    uRoleSel.appendChild(el('option', { value: r }, r));
                });
                var uPwIn = el('input', { type: 'password', placeholder: 'Min 8 chars' });

                var uRow = el('div', { className: 'qb-row' });
                uRow.appendChild(field('Username', uNameIn));
                uRow.appendChild(field('Full Name', uDispIn));
                uRow.appendChild(field('Role', uRoleSel));
                uRow.appendChild(field('Temporary Password', uPwIn));
                var createUserBtn = btn('Create User', 'btn-primary', async function () {
                    createUserBtn.disabled = true;
                    try {
                        var created = await Api.request('/admin/users', {
                            method: 'POST',
                            json: {
                                username: uNameIn.value,
                                displayName: uDispIn.value,
                                role: uRoleSel.value,
                                temporaryPassword: uPwIn.value
                            }
                        });
                        renderBody();
                        var hint = created.role === 'HOD' ? ' — Logout & Sign In as "' + created.username + '" for Excel Upload.'
                                : created.role === 'COE' ? ' — Logout & Sign In as "' + created.username + '" for Paper Generation.'
                                : '';
                        setFlash('Created ' + created.role + ' user: ' + created.username + hint, 'ok');
                    } catch (e) { setFlash(e.message, 'err'); }
                    finally { createUserBtn.disabled = false; }
                });
                uRow.appendChild(createUserBtn);
                uCard.appendChild(uRow);

                var users = await Api.request('/admin/users');
                if (users.length > 0) {
                    var uWrap = el('div', { className: 'qb-table-wrap', style: 'margin-top:12px;' });
                    var uTbl = el('table');
                    var uHdr = el('tr');
                    ['Username', 'Full Name', 'Role', 'Status', 'Actions'].forEach(function (h) { uHdr.appendChild(el('th', null, h)); });
                    uTbl.appendChild(uHdr);
                    users.forEach(function (usr) {
                        var tr = el('tr');
                        tr.appendChild(el('td', null, usr.username));
                        tr.appendChild(el('td', null, usr.displayName));
                        tr.appendChild(el('td', null, usr.role));
                        var stateTxt = usr.active ? 'ACTIVE' : 'DISABLED';
                        if (usr.lockedUntil && new Date(usr.lockedUntil).getTime() > Date.now()) stateTxt += ' (LOCKED)';
                        if (usr.mustChangePassword) stateTxt += ' · Temp PW';
                        tr.appendChild(el('td', null, stateTxt));
                        var tdAct = el('td');
                        if (!app.user || usr.username !== app.user.username) {
                            tdAct.appendChild(btn(usr.active ? 'Disable' : 'Activate', 'btn-sm', async function () {
                                try {
                                    await Api.request('/admin/users/' + usr.id, {
                                        method: 'PUT',
                                        json: { active: !usr.active, clearLockout: true }
                                    });
                                    renderBody();
                                } catch (e) { setFlash(e.message, 'err'); }
                            }));
                        }
                        if (usr.failedLoginAttempts > 0 || usr.lockedUntil) {
                            tdAct.appendChild(btn('Unlock', 'btn-sm', async function () {
                                try {
                                    await Api.request('/admin/users/' + usr.id, {
                                        method: 'PUT',
                                        json: { active: usr.active, clearLockout: true }
                                    });
                                    renderBody();
                                } catch (e) { setFlash(e.message, 'err'); }
                            }));
                        }
                        tr.appendChild(tdAct);
                        uTbl.appendChild(tr);
                    });
                    uWrap.appendChild(uTbl);
                    uCard.appendChild(uWrap);
                }
                root.appendChild(uCard);
            }

            // Subjects Management
            var sCard = el('div', { className: 'qb-card' });
            sCard.appendChild(el('h3', null, 'Subjects'));
            var cIn = el('input', { type: 'text', placeholder: 'CS101' });
            var nIn = el('input', { type: 'text', placeholder: 'Programming' });
            var eIn = el('input', { type: 'number', value: '120' });
            var mIn = el('input', { type: 'number', value: '1' });
            var rS = el('div', { className: 'qb-row' });
            rS.appendChild(field('Code', cIn));
            rS.appendChild(field('Name', nIn));
            rS.appendChild(field('Expected / Module', eIn));
            rS.appendChild(field('Marks', mIn));
            var addSubBtn = btn('Add Subject', 'btn-primary', async function () {
                addSubBtn.disabled = true;
                try {
                    var createdSub = await Api.request('/subjects', {
                        method: 'POST', json: { code: cIn.value, name: nIn.value, expectedQuestionsPerModule: Number(eIn.value), marksPerQuestion: Number(mIn.value) || 1 }
                    });
                    renderBody();
                    setFlash('Added subject ' + createdSub.code, 'ok');
                } catch (e) { setFlash(e.message, 'err'); }
                finally { addSubBtn.disabled = false; }
            });
            rS.appendChild(addSubBtn);
            sCard.appendChild(rS);

            var subjects = await Api.request('/subjects?includeArchived=true');
            if (subjects.length > 0) {
                var sWrap = el('div', { className: 'qb-table-wrap', style: 'margin-top:12px;' });
                var sTbl = el('table');
                var sHdr = el('tr');
                ['Code', 'Name', 'Expected / Module', 'Marks', 'Status', 'Actions'].forEach(function (h) { sHdr.appendChild(el('th', null, h)); });
                sTbl.appendChild(sHdr);
                subjects.forEach(function (sub) {
                    var tr = el('tr');
                    tr.appendChild(el('td', null, sub.code));
                    tr.appendChild(el('td', null, sub.name));
                    tr.appendChild(el('td', null, String(sub.expectedQuestionsPerModule)));
                    tr.appendChild(el('td', null, String(sub.marksPerQuestion)));
                    tr.appendChild(el('td', null, sub.archived ? 'ARCHIVED' : 'ACTIVE'));
                    var tdSubAct = el('td');
                    tdSubAct.appendChild(btn(sub.archived ? 'Activate' : 'Archive', 'btn-sm', async function () {
                        try {
                            await Api.request('/admin/subjects/' + sub.id, {
                                method: 'PUT',
                                json: {
                                    name: sub.name,
                                    expectedQuestionsPerModule: sub.expectedQuestionsPerModule,
                                    marksPerQuestion: sub.marksPerQuestion,
                                    archived: !sub.archived
                                }
                            });
                            renderBody();
                        } catch (e) { setFlash(e.message, 'err'); }
                    }));
                    tr.appendChild(tdSubAct);
                    sTbl.appendChild(tr);
                });
                sWrap.appendChild(sTbl);
                sCard.appendChild(sWrap);
            }
            root.appendChild(sCard);
        } catch (e) {
            setFlash(e.message, 'err');
        }
    }

    async function downloadBinary(path, filename) {
        try {
            var blob = await Api.request(path, { responseType: 'blob' });
            var url = global.URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = filename;
            a.click();
            global.URL.revokeObjectURL(url);
        } catch (e) { setFlash(e.message, 'err'); }
    }

    function formatInstant(v) {
        if (v === null || v === undefined || v === '') return '-';
        var d = typeof v === 'number' ? new Date(v > 1e12 ? v : v * 1000) : new Date(v);
        return isNaN(d.getTime()) ? String(v) : d.toLocaleString();
    }

    function toLocalDatetimeValue(d) {
        var off = d.getTimezoneOffset();
        return new Date(d.getTime() - off * 60000).toISOString().slice(0, 16);
    }

    bootstrap();
})(window);
