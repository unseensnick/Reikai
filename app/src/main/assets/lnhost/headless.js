// LN plugin host runtime (headless).
//
// Runs inside a QuickJS engine (com.dokar.quickjs) with NO WebView and NO Activity, so novel
// sources work off-thread (background updates / downloads). Provides the `require()` shim lnreader
// plugins expect (see refs/lnreader-main/src/plugins/pluginManager.ts) and bridges fetch / storage /
// log to Kotlin through host functions bound by LnPluginHost.kt:
//   __lnLog(level, message)            -- sync
//   __lnGetStorage(pluginId, key)      -- sync, returns string|null
//   __lnSetStorage(pluginId, key, val) -- sync, val null deletes
//   __lnFetch(url, optsJson)           -- async, returns a JSON FetchResponse string
//
// Plugin source is downloaded by Kotlin (LnPluginLoader) and passed into __lnLoadPlugin. All HTTP
// routes through Kotlin OkHttp (so the CloudflareInterceptor / Flaresolverr keep applying); native
// fetch is deliberately never exposed to plugins.

(function () {
  'use strict';

  // QuickJS has no browser globals; plugins (and the cheerio/dayjs bundles) expect a handful.
  globalThis.window = globalThis;
  globalThis.self = globalThis;

  if (typeof globalThis.console === 'undefined') {
    var mkLog = function (level) {
      return function () { __lnLog(level, Array.prototype.map.call(arguments, String).join(' ')); };
    };
    globalThis.console = {
      log: mkLog('info'), info: mkLog('info'), warn: mkLog('warn'),
      error: mkLog('error'), debug: mkLog('debug'),
    };
  }

  function log(level, message) {
    try { __lnLog(level, String(message)); } catch (_) { /* ignore */ }
  }

  if (typeof globalThis.URLSearchParams === 'undefined') {
    // QuickJS has no URLSearchParams; the WebView host used the browser's native one. Plugins
    // construct it from a record (`new URLSearchParams({novelId: id})`) or pairs, not just a
    // string, so a string-only shim silently drops every param (novelbin's chapter URL 400s,
    // search 404s). Mirror the WHATWG init types and the method surface plugins use.
    var USP = function (init) {
      this._p = [];
      if (typeof init === 'string') {
        var self = this;
        init.replace(/^\?/, '').split('&').forEach(function (kv) {
          if (!kv) return;
          var i = kv.indexOf('=');
          var k = i < 0 ? kv : kv.slice(0, i);
          var v = i < 0 ? '' : kv.slice(i + 1);
          self._p.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
        });
      } else if (Array.isArray(init)) {
        for (var j = 0; j < init.length; j++) this._p.push([String(init[j][0]), String(init[j][1])]);
      } else if (init && typeof init === 'object') {
        for (var key in init) {
          if (Object.prototype.hasOwnProperty.call(init, key)) this._p.push([key, String(init[key])]);
        }
      }
    };
    USP.prototype.append = function (k, v) { this._p.push([String(k), String(v)]); };
    USP.prototype.set = function (k, v) {
      k = String(k); v = String(v);
      var found = false; var out = [];
      for (var i = 0; i < this._p.length; i++) {
        if (this._p[i][0] === k) { if (!found) { out.push([k, v]); found = true; } } else out.push(this._p[i]);
      }
      if (!found) out.push([k, v]);
      this._p = out;
    };
    USP.prototype['delete'] = function (k) { k = String(k); this._p = this._p.filter(function (e) { return e[0] !== k; }); };
    USP.prototype.get = function (k) {
      k = String(k);
      for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) return this._p[i][1];
      return null;
    };
    USP.prototype.getAll = function (k) { k = String(k); return this._p.filter(function (e) { return e[0] === k; }).map(function (e) { return e[1]; }); };
    USP.prototype.has = function (k) { k = String(k); return this._p.some(function (e) { return e[0] === k; }); };
    USP.prototype.forEach = function (fn, thisArg) { this._p.forEach(function (e) { fn.call(thisArg, e[1], e[0], this); }, this); };
    USP.prototype.keys = function () { return this._p.map(function (e) { return e[0]; }); };
    USP.prototype.values = function () { return this._p.map(function (e) { return e[1]; }); };
    USP.prototype.entries = function () { return this._p.slice(); };
    USP.prototype[Symbol.iterator] = function () { return this._p.slice()[Symbol.iterator](); };
    USP.prototype.toString = function () {
      return this._p.map(function (e) { return encodeURIComponent(e[0]) + '=' + encodeURIComponent(e[1]); }).join('&');
    };
    globalThis.URLSearchParams = USP;
  }

  if (typeof globalThis.URL === 'undefined') {
    // Minimal WHATWG URL. lnreader's RN runtime ships react-native-url-polyfill; QuickJS has
    // nothing, and the ReadNovelFull/Madara families do `new URL(href, site)` to resolve relative
    // links (crash: "URL is not defined"). Covers the read surface plugins use plus base
    // resolution. Not spec-complete: no IDNA, userinfo, or port normalization.
    var URLP = function (url, base) {
      var resolved = URLP._resolve(String(url), base != null ? String(base) : null);
      var m = /^([^:/?#]+):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(resolved);
      if (!m) throw new TypeError('Invalid URL: ' + url);
      this.protocol = m[1].toLowerCase() + ':';
      var hostport = m[2];
      var colon = hostport.lastIndexOf(':');
      this.hostname = colon < 0 ? hostport : hostport.slice(0, colon);
      this.port = colon < 0 ? '' : hostport.slice(colon + 1);
      this.host = hostport;
      this.origin = this.protocol + '//' + this.host;
      this.pathname = m[3] || '/';
      this.search = m[4] || '';
      this.hash = m[5] || '';
      this.searchParams = new URLSearchParams(this.search);
    };
    URLP.prototype.toString = function () {
      var s = this.searchParams.toString();
      return this.protocol + '//' + this.host + this.pathname + (s ? '?' + s : '') + this.hash;
    };
    Object.defineProperty(URLP.prototype, 'href', {
      get: function () { return this.toString(); }, enumerable: true,
    });
    // RFC 3986 reference resolution: absolute as-is, else resolve against base.
    URLP._resolve = function (url, base) {
      if (/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) return url;
      if (!base) throw new TypeError('Invalid base URL for: ' + url);
      var b = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(base);
      if (!b) throw new TypeError('Invalid base URL: ' + base);
      var scheme = b[1], authority = b[2], basePath = b[3] || '/';
      if (url.indexOf('//') === 0) return scheme + ':' + url;
      if (url.charAt(0) === '/') return scheme + '://' + authority + url;
      if (url.charAt(0) === '?') return scheme + '://' + authority + basePath + url;
      if (url.charAt(0) === '#') return scheme + '://' + authority + basePath + (b[4] || '') + url;
      var dir = basePath.slice(0, basePath.lastIndexOf('/') + 1);
      var out = [];
      (dir + url).split('/').forEach(function (seg) {
        if (seg === '.') return;
        if (seg === '..') { if (out.length > 1) out.pop(); return; }
        out.push(seg);
      });
      return scheme + '://' + authority + out.join('/');
    };
    globalThis.URL = URLP;
  }

  if (typeof globalThis.FormData === 'undefined') {
    var FD = function () { this._p = []; };
    FD.prototype.append = function (k, v) { this._p.push([String(k), typeof v === 'string' ? v : String(v)]); };
    FD.prototype.entries = function () { return this._p.slice(); };
    globalThis.FormData = FD;
  }

  if (typeof globalThis.TextEncoder === 'undefined') {
    var TE = function () {};
    TE.prototype.encode = function (s) {
      var u = unescape(encodeURIComponent(String(s)));
      var a = new Uint8Array(u.length);
      for (var i = 0; i < u.length; i++) a[i] = u.charCodeAt(i);
      return a;
    };
    globalThis.TextEncoder = TE;
    var TD = function () {};
    TD.prototype.decode = function (b) {
      var a = new Uint8Array(b); var s = '';
      for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
      return decodeURIComponent(escape(s));
    };
    globalThis.TextDecoder = TD;
  }

  // -- @libs/fetch shim -------------------------------------------------------
  // Plugins expect a Response-like object. We synthesize the methods plugins actually use.
  // No .blob(): QuickJS has no Blob and novel sources are text-only; a plugin calling .blob()
  // would throw (surfaced by the Phase 0 compatibility sweep).

  function makeResponse(payload) {
    var headersObj = payload.headers || {};
    return {
      // Final URL after redirects. The Madara plugin family reads response.url to detect
      // Cloudflare/captcha redirects; without it they crash on undefined.split('/').
      url: payload.url || '',
      status: payload.status,
      statusText: payload.statusText || '',
      ok: payload.status >= 200 && payload.status < 300,
      headers: {
        get: function (name) { return headersObj[name.toLowerCase()] || null; },
        has: function (name) { return Object.prototype.hasOwnProperty.call(headersObj, name.toLowerCase()); },
        forEach: function (fn) { Object.keys(headersObj).forEach(function (k) { fn(headersObj[k], k); }); },
      },
      text: function () { return Promise.resolve(payload.body); },
      json: function () { return Promise.resolve(JSON.parse(payload.body)); },
    };
  }

  // lnreader's makeInit defaults (refs/lnreader-main/src/plugins/helpers/fetch.ts). Sending these
  // browser-like headers is the difference between many servers answering and stonewalling a
  // non-browser request, which is why sources that work in the lnreader app failed here. User-Agent
  // is supplied by the app's UserAgentInterceptor; Accept-Encoding is left to OkHttp (transparent gzip).
  var DEFAULT_HEADERS = {
    'Accept': '*/*', 'Accept-Language': '*', 'Sec-Fetch-Mode': 'cors',
    'Cache-Control': 'max-age=0', 'Connection': 'keep-alive',
  };

  async function fetchApi(url, init) {
    var normalized = init ? Object.assign({}, init) : {};
    // Merge the defaults under the plugin's headers (plugin wins, case-insensitively).
    var merged = Object.assign({}, DEFAULT_HEADERS);
    var provided = normalized.headers || {};
    Object.keys(provided).forEach(function (k) {
      Object.keys(merged).forEach(function (d) { if (d.toLowerCase() === k.toLowerCase()) delete merged[d]; });
      merged[k] = provided[k];
    });
    normalized.headers = merged;
    // FormData posts go out as real multipart/form-data (what browsers and lnreader's RN fetch send),
    // not urlencoded: some WordPress admin-ajax endpoints (e.g. Novel Updates' nd_getchapters) only
    // answer multipart and reply "0" otherwise. The Kotlin bridge builds the multipart body from the
    // field pairs. URLSearchParams stays urlencoded (that's its own content type).
    if (normalized.body instanceof FormData) {
      normalized.multipart = Array.from(normalized.body.entries()).map(function (e) {
        return [e[0], typeof e[1] === 'string' ? e[1] : String(e[1])];
      });
      delete normalized.body;
    } else if (normalized.body instanceof URLSearchParams) {
      normalized.body = normalized.body.toString();
      normalized.headers = Object.assign({}, normalized.headers || {});
      var hasCT = Object.keys(normalized.headers).some(function (h) { return h.toLowerCase() === 'content-type'; });
      if (!hasCT) normalized.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
    var raw = JSON.parse(await __lnFetch(url, JSON.stringify(normalized)));
    if (raw && raw.error) throw new Error(raw.error);
    return makeResponse(raw);
  }

  async function fetchText(url, init) {
    var res = await fetchApi(url, init);
    if (!res.ok) return '';
    return await res.text();
  }

  // gRPC-web client. protobufjs (vendored) parses the plugin's .proto string and encodes/decodes
  // messages; we add the 5-byte gRPC-web frame and round-trip the binary through the Kotlin bridge
  // as base64 (QuickJS has no Blob/FileReader, so we can't use lnreader's transport). WuxiaWorld.
  async function fetchProto(protoInit, url, init) {
    var pb = globalThis.protobuf;
    if (!pb) throw new Error('protobufjs not loaded');
    var b64 = pb.util.base64;
    var root = pb.parse(protoInit.proto).root;
    var ReqT = root.lookupType(protoInit.requestType);
    var verr = ReqT.verify(protoInit.requestData || {});
    if (verr) throw new Error('Invalid Proto: ' + verr);
    var msg = ReqT.encode(protoInit.requestData || {}).finish();
    // gRPC-web frame: 1 flag byte (uncompressed) + 4-byte big-endian length, then the message.
    var framed = new Uint8Array(5 + msg.length);
    framed[1] = (msg.length >>> 24) & 0xff; framed[2] = (msg.length >>> 16) & 0xff;
    framed[3] = (msg.length >>> 8) & 0xff; framed[4] = msg.length & 0xff;
    framed.set(msg, 5);

    var normalized = init ? Object.assign({}, init) : {};
    normalized.method = 'POST';
    normalized.binary = true;
    normalized.bodyBase64 = b64.encode(framed, 0, framed.length);
    delete normalized.body;

    var raw = JSON.parse(await __lnFetch(url, JSON.stringify(normalized)));
    if (raw && raw.error) throw new Error(raw.error);
    var enc = raw.bodyBase64 || '';
    var resp = new Uint8Array(b64.length(enc));
    b64.decode(enc, resp, 0);
    if (resp.length < 5) throw new Error('Empty gRPC-web response');
    var rlen = (resp[1] << 24) | (resp[2] << 16) | (resp[3] << 8) | resp[4];
    var RespT = root.lookupType(protoInit.responseType);
    return RespT.toObject(RespT.decode(resp.subarray(5, 5 + rlen)), {
      longs: String, enums: String, bytes: String, defaults: true, arrays: true, objects: true,
    });
  }

  // -- @libs/storage shim -----------------------------------------------------
  // The three scopes (storage/local/session) map to the same Kotlin SharedPreferences, keyed by
  // prefix. Backed by __lnGetStorage/__lnSetStorage.

  function makeStorage(pluginId, kind) {
    var prefix = kind + ':';
    return {
      get: function (key) { var r = __lnGetStorage(pluginId, prefix + key); return (r === null || r === undefined) ? null : r; },
      set: function (key, value) { __lnSetStorage(pluginId, prefix + key, String(value)); },
      delete: function (key) { __lnSetStorage(pluginId, prefix + key, null); },
    };
  }

  // -- @libs/* data constants -------------------------------------------------

  var NovelStatus = Object.freeze({
    Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus',
  });
  var FilterTypes = Object.freeze({
    TextInput: 'TextInput', Picker: 'Picker', Checkbox: 'Checkbox', Switch: 'Switch',
    XCheckbox: 'XCheckbox', ExcludableCheckboxGroup: 'ExcludableCheckboxGroup',
  });
  var defaultCover = 'https://github.com/LNReader/lnreader-sources/blob/main/icons/no-cover.jpg?raw=true';
  function isUrlAbsolute(url) { return /^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(url); }
  function utf8ToBytes(s) { return Array.from(new TextEncoder().encode(s)); }
  function bytesToUtf8(b) { return new TextDecoder().decode(new Uint8Array(b)); }
  var urlencode = {
    encode: function (s) { return encodeURIComponent(String(s)); },
    decode: function (s) { return decodeURIComponent(String(s)); },
  };
  // @libs/aes is unimplemented; throws on use. No smoke-test source needs it.
  var aesStub = new Proxy({}, {
    get: function (_t, prop) { return function () { throw new Error('@libs/aes not implemented (' + String(prop) + ')'); }; },
  });

  // -- the require() resolver -------------------------------------------------

  function makeRequire(pluginId) {
    var packages = {
      'cheerio': globalThis.cheerio,
      'htmlparser2': globalThis.htmlparser2,
      'dayjs': globalThis.dayjs,
      'protobufjs': globalThis.protobuf,
      'urlencode': urlencode,
      '@libs/novelStatus': { NovelStatus: NovelStatus },
      '@libs/fetch': { fetchApi: fetchApi, fetchText: fetchText, fetchProto: fetchProto },
      '@libs/isAbsoluteUrl': { isUrlAbsolute: isUrlAbsolute },
      '@libs/filterInputs': { FilterTypes: FilterTypes },
      '@libs/defaultCover': { defaultCover: defaultCover },
      '@libs/aes': aesStub,
      '@libs/utils': { utf8ToBytes: utf8ToBytes, bytesToUtf8: bytesToUtf8 },
    };
    return function _require(name) {
      if (name === '@libs/storage') {
        return {
          storage: makeStorage(pluginId, 'storage'),
          localStorage: makeStorage(pluginId, 'local'),
          sessionStorage: makeStorage(pluginId, 'session'),
        };
      }
      var pkg = packages[name];
      if (pkg === undefined) {
        log('warn', 'require: unknown module "' + name + '" requested by plugin ' + pluginId);
        return undefined;
      }
      return pkg;
    };
  }

  // -- plugin registry --------------------------------------------------------

  var plugins = new Map(); // pluginId -> Plugin instance

  // No-op storage trio for the discovery pass: doesn't touch Kotlin so no SharedPreferences file is
  // allocated for a scope we'd abandon.
  var NOOP = { get: function () { return null; }, set: function () {}, delete: function () {} };
  var NOOP_STORAGE = { storage: NOOP, localStorage: NOOP, sessionStorage: NOOP };

  function loadPlugin(pluginId, rawCode, iconUrl, lang) {
    try {
      // Pass 1: discover the plugin's intrinsic id with @libs/storage shadowed by no-ops, so plugins
      // that read storage at load time (royalroad, webnovel) don't write to a temporary scope.
      var canonicalId = pluginId;
      try {
        var baseReq = makeRequire(pluginId);
        var discoverReq = function (name) { return name === '@libs/storage' ? NOOP_STORAGE : baseReq(name); };
        var dm = { exports: {} };
        var df = new Function('require', 'module', 'exports', rawCode + '\nreturn module.exports.default || module.exports;');
        var discovered = df(discoverReq, dm, dm.exports);
        if (discovered && discovered.id) canonicalId = discovered.id;
      } catch (e) {
        log('warn', 'plugin id discovery failed for ' + pluginId + ': ' + (e && e.message || e));
      }

      // Pass 2: real load. Storage scope is canonicalId; registry key is always plugin.id.
      var req = makeRequire(canonicalId);
      var m = { exports: {} };
      var fn = new Function('require', 'module', 'exports', rawCode + '\nreturn module.exports.default || module.exports;');
      var plugin = fn(req, m, m.exports);
      if (!plugin || !plugin.id) throw new Error('plugin did not export a default with .id');
      plugins.set(plugin.id, plugin);
      log('info', 'loaded plugin ' + plugin.id + ' v' + (plugin.version || '?'));
      return {
        id: plugin.id,
        name: plugin.name,
        version: plugin.version,
        site: plugin.site,
        // lang and iconUrl are injected from Kotlin (lnreader registry); plugin.* is a cheap fallback.
        lang: lang || plugin.lang || null,
        iconUrl: iconUrl || null,
        // Pass the plugin's filter schema through unmodified; the host doesn't interpret it.
        filters: plugin.filters || null,
      };
    } catch (e) {
      log('error', 'loadPlugin failed: ' + (e && e.stack ? e.stack : e));
      throw e;
    }
  }

  // -- method dispatch --------------------------------------------------------
  // Returns the result envelope as a JSON string (resolved by LnPluginHost.callMethod via a global).

  async function callMethod(pluginId, method, argsJson) {
    try {
      var plugin = plugins.get(pluginId);
      if (!plugin) throw new Error('plugin not loaded: ' + pluginId);
      if (typeof plugin[method] !== 'function') throw new Error('method ' + method + ' missing on ' + pluginId);
      var args = JSON.parse(argsJson || '[]');
      var result = await plugin[method].apply(plugin, args);
      return JSON.stringify({ ok: true, value: result === undefined ? null : result });
    } catch (e) {
      var msg = (e && e.message) ? e.message : String(e);
      log('error', 'callMethod ' + method + ' on ' + pluginId + ' failed: ' + (e && e.stack ? e.stack : msg));
      return JSON.stringify({ ok: false, error: msg });
    }
  }

  // -- expose to Kotlin -------------------------------------------------------

  globalThis.__lnLoadPlugin = loadPlugin;
  globalThis.__lnCallMethod = callMethod;
  log('info', 'lnhost headless runtime ready; vendor present: cheerio=' + !!globalThis.cheerio
    + ' htmlparser2=' + !!globalThis.htmlparser2 + ' dayjs=' + !!globalThis.dayjs
    + ' protobuf=' + (typeof globalThis.protobuf !== 'undefined'));
})();
