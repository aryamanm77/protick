(function (global) {
    'use strict';

    function detectBasePath() {
        if (typeof global.QBANK_BASE_PATH === 'string') {
            return normalizeBase(global.QBANK_BASE_PATH);
        }
        var meta = document.querySelector('meta[name="qbank-base-path"]');
        if (meta && meta.content) {
            return normalizeBase(meta.content);
        }
        var scripts = document.getElementsByTagName('script');
        for (var i = 0; i < scripts.length; i++) {
            var src = scripts[i].getAttribute('src') || '';
            var idx = src.indexOf('/qbank-api.js');
            if (idx >= 0) {
                return normalizeBase(src.substring(0, idx));
            }
        }
        var p = global.location.pathname || '/qbank';
        if (p.endsWith('/index.html') || p.endsWith('/student.html')) {
            return normalizeBase(p.substring(0, p.lastIndexOf('/')));
        }
        if (p === '/' || p === '') {
            return '/qbank';
        }
        return normalizeBase(p.endsWith('/') ? p.slice(0, -1) : p);
    }

    function normalizeBase(v) {
        if (!v || v === '/') return '';
        var out = v.trim();
        if (!out.startsWith('/')) out = '/' + out;
        if (out.endsWith('/')) out = out.slice(0, -1);
        return out;
    }

    var BASE = detectBasePath();
    var TOKEN_KEY = 'qbank_staff_token';

    function getToken() {
        try { return global.sessionStorage.getItem(TOKEN_KEY); } catch (e) { return null; }
    }

    function setToken(token) {
        try {
            if (token) global.sessionStorage.setItem(TOKEN_KEY, token);
            else global.sessionStorage.removeItem(TOKEN_KEY);
        } catch (e) {}
    }

    function readCookie(name) {
        var m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
        return m ? decodeURIComponent(m[1]) : null;
    }

    async function request(path, options) {
        var opts = Object.assign({ credentials: 'same-origin' }, options || {});
        var headers = Object.assign({}, opts.headers || {});
        var tok = opts.bearerToken !== undefined ? opts.bearerToken : getToken();
        if (tok) {
            headers['Authorization'] = 'Bearer ' + tok;
        }
        var xsrf = readCookie('XSRF-TOKEN');
        if (xsrf && opts.method && opts.method.toUpperCase() !== 'GET') {
            headers['X-XSRF-TOKEN'] = xsrf;
        }
        if (opts.json !== undefined) {
            headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(opts.json);
            delete opts.json;
        }
        opts.headers = headers;

        var url = BASE + '/api/v1' + path;
        var resp = await fetch(url, opts);
        var contentType = resp.headers.get('content-type') || '';

        if (!resp.ok) {
            var errMsg = 'HTTP ' + resp.status;
            var errCode = 'HTTP_' + resp.status;
            try {
                if (contentType.includes('application/json')) {
                    var errBody = await resp.json();
                    errMsg = errBody.message || errMsg;
                    errCode = errBody.code || errCode;
                } else {
                    var txt = await resp.text();
                    if (txt) errMsg = txt;
                }
            } catch (ignored) {}
            var err = new Error(errMsg);
            err.status = resp.status;
            err.code = errCode;
            throw err;
        }

        if (opts.responseType === 'blob') {
            return await resp.blob();
        }
        if (resp.status === 204) {
            return null;
        }
        if (contentType.includes('application/json')) {
            return await resp.json();
        }
        return await resp.text();
    }

    global.QBankApi = {
        basePath: BASE,
        getToken: getToken,
        setToken: setToken,
        request: request
    };
})(window);
