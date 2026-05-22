// LN plugin host bootstrap.
//
// Runs inside an Android WebView loaded with bootstrap.html.
// Provides the `require()` shim lnreader plugins expect (see refs/lnreader-main/src/plugins/pluginManager.ts)
// and bridges fetch / storage / log calls to Kotlin via window.LnHostBridge (@JavascriptInterface).
//
// Plugin source code is downloaded by Kotlin (LnPluginLoader.kt) and passed in via callMethod's loadPlugin path.
// We deliberately do NOT expose native window.fetch to plugins; all HTTP routes through Kotlin OkHttp.

(function () {
  'use strict';

  // -- bridge handle ----------------------------------------------------------

  const bridge = window.LnHostBridge;
  if (!bridge) {
    // The host always injects this before loading the page. If it's missing we're being
    // opened standalone (e.g. devtools); fail loud so the absence is obvious.
    console.error('LnHostBridge missing; plugin host cannot operate');
  }

  function log(level, message) {
    try { bridge && bridge.log(level, String(message)); } catch (_) { /* ignore */ }
  }

  // -- pending Promise registry (fetches resolved by Kotlin callbacks) --------

  const pendingFetches = new Map(); // callbackId -> { resolve, reject }
  let nextCallbackId = 1;
  function newCallbackId() { return 'cb_' + (nextCallbackId++); }

  // -- @libs/fetch shim -------------------------------------------------------
  //
  // Plugins expect a Response-like object back. We synthesize one with the
  // methods plugins actually use (.text / .json / .blob / .ok / .status / .headers).

  function makeResponse(payload) {
    // payload: { status, statusText, headers: Record<string,string>, body: string }
    const headersObj = payload.headers || {};
    return {
      status: payload.status,
      statusText: payload.statusText || '',
      ok: payload.status >= 200 && payload.status < 300,
      headers: {
        get: (name) => headersObj[name.toLowerCase()] || null,
        has: (name) => Object.prototype.hasOwnProperty.call(headersObj, name.toLowerCase()),
        forEach: (fn) => Object.entries(headersObj).forEach(([k, v]) => fn(v, k)),
      },
      text: async () => payload.body,
      json: async () => JSON.parse(payload.body),
      blob: async () => new Blob([payload.body], { type: headersObj['content-type'] || 'text/plain' }),
    };
  }

  async function fetchApi(url, init) {
    // Normalize body: FormData → application/x-www-form-urlencoded since the Kotlin bridge
    // accepts only string bodies. URLSearchParams handled the same way. (Scribblehub's
    // parseNovel POSTs a FormData; without this conversion JSON.stringify(formData) = '{}'.)
    const normalized = init ? Object.assign({}, init) : {};
    if (normalized.body instanceof FormData || normalized.body instanceof URLSearchParams) {
      const params = new URLSearchParams();
      for (const [k, v] of normalized.body.entries()) {
        params.append(k, typeof v === 'string' ? v : String(v));
      }
      normalized.body = params.toString();
      normalized.headers = Object.assign({}, normalized.headers || {});
      const hasCT = Object.keys(normalized.headers).some(h => h.toLowerCase() === 'content-type');
      if (!hasCT) normalized.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
    const callbackId = newCallbackId();
    const promise = new Promise((resolve, reject) => {
      pendingFetches.set(callbackId, { resolve, reject });
    });
    bridge.fetch(url, JSON.stringify(normalized), callbackId);
    const raw = await promise; // resolved by window.__lnhost.resolveFetch
    if (raw && raw.error) {
      throw new Error(raw.error);
    }
    return makeResponse(raw);
  }

  async function fetchText(url, init, encoding) {
    // We ignore the encoding arg for the spike; Kotlin always decodes as UTF-8.
    // If a plugin needs a non-UTF-8 site we'll add an `encoding` field to the request.
    const res = await fetchApi(url, init);
    if (!res.ok) return '';
    return await res.text();
  }

  async function fetchProto(/* protoInit, url, init */) {
    throw new Error('fetchProto not implemented in spike');
  }

  // -- @libs/storage shim -----------------------------------------------------

  function makeStorage(pluginId, kind) {
    // kind: 'storage' | 'local' | 'session'. We map all three to the same Kotlin-backed
    // SharedPreferences for the spike; differentiate via key prefix.
    const prefix = kind + ':';
    return {
      get(key) {
        const raw = bridge.getStorage(pluginId, prefix + key);
        return raw === null || raw === undefined ? null : raw;
      },
      set(key, value) { bridge.setStorage(pluginId, prefix + key, String(value)); },
      delete(key) { bridge.setStorage(pluginId, prefix + key, null); },
      // lnreader's Storage class also exposes getAllKeys; rare. Out of scope for spike.
    };
  }

  // -- @libs/* data constants -------------------------------------------------

  const NovelStatus = Object.freeze({
    Unknown: 'Unknown',
    Ongoing: 'Ongoing',
    Completed: 'Completed',
    Licensed: 'Licensed',
    PublishingFinished: 'Publishing Finished',
    Cancelled: 'Cancelled',
    OnHiatus: 'On Hiatus',
  });

  const FilterTypes = Object.freeze({
    TextInput: 'TextInput',
    Picker: 'Picker',
    Checkbox: 'Checkbox',
    Switch: 'Switch',
    XCheckbox: 'XCheckbox',
    ExcludableCheckboxGroup: 'ExcludableCheckboxGroup',
  });

  const defaultCover = 'https://github.com/LNReader/lnreader-sources/blob/main/icons/no-cover.jpg?raw=true';

  function isUrlAbsolute(url) {
    return /^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(url);
  }

  function utf8ToBytes(s) {
    return Array.from(new TextEncoder().encode(s));
  }
  function bytesToUtf8(b) {
    return new TextDecoder().decode(new Uint8Array(b));
  }

  // urlencode shim: lnreader plugins use `import { encode, decode } from 'urlencode'`.
  // Map to URI-component primitives.
  const urlencode = {
    encode: (s, charset) => encodeURIComponent(String(s)),
    decode: (s, charset) => decodeURIComponent(String(s)),
  };

  // @libs/aes stub: throws on use; novelbin doesn't need it.
  const aesStub = new Proxy({}, {
    get(_t, prop) {
      return () => { throw new Error('@libs/aes not implemented in spike (called: ' + String(prop) + ')'); };
    },
  });

  // -- the require() resolver -------------------------------------------------

  function makeRequire(pluginId) {
    // Vendor globals are present (set by IIFE bundles or UMD).
    const packages = {
      'cheerio': globalThis.cheerio,
      'htmlparser2': globalThis.htmlparser2,
      'dayjs': globalThis.dayjs,
      'urlencode': urlencode,
      '@libs/novelStatus': { NovelStatus },
      '@libs/fetch': { fetchApi, fetchText, fetchProto },
      '@libs/isAbsoluteUrl': { isUrlAbsolute },
      '@libs/filterInputs': { FilterTypes },
      '@libs/defaultCover': { defaultCover },
      '@libs/aes': aesStub,
      '@libs/utils': { utf8ToBytes, bytesToUtf8 },
    };
    return function _require(name) {
      if (name === '@libs/storage') {
        return {
          storage: makeStorage(pluginId, 'storage'),
          localStorage: makeStorage(pluginId, 'local'),
          sessionStorage: makeStorage(pluginId, 'session'),
        };
      }
      const pkg = packages[name];
      if (pkg === undefined) {
        log('warn', 'require: unknown module "' + name + '" requested by plugin ' + pluginId);
        return undefined;
      }
      return pkg;
    };
  }

  // -- plugin registry --------------------------------------------------------

  const plugins = new Map(); // pluginId -> Plugin instance

  function loadPlugin(pluginId, rawCode) {
    try {
      const req = makeRequire(pluginId);
      const module = { exports: {} };
      const fn = new Function('require', 'module', 'exports',
        rawCode + '\nreturn module.exports.default || module.exports;');
      const plugin = fn(req, module, module.exports);
      if (!plugin || !plugin.id) {
        throw new Error('plugin did not export a default with .id');
      }
      plugins.set(pluginId, plugin);
      log('info', 'loaded plugin ' + plugin.id + ' v' + (plugin.version || '?'));
      return { id: plugin.id, name: plugin.name, version: plugin.version, site: plugin.site, icon: plugin.icon };
    } catch (e) {
      log('error', 'loadPlugin failed: ' + (e && e.stack ? e.stack : e));
      throw e;
    }
  }

  // -- method dispatch --------------------------------------------------------

  async function callMethod(pluginId, method, argsJson, callbackId) {
    try {
      const plugin = plugins.get(pluginId);
      if (!plugin) throw new Error('plugin not loaded: ' + pluginId);
      if (typeof plugin[method] !== 'function') throw new Error('method ' + method + ' missing on ' + pluginId);
      const args = JSON.parse(argsJson || '[]');
      const result = await plugin[method].apply(plugin, args);
      bridge.resolveResult(callbackId, JSON.stringify({ ok: true, value: result === undefined ? null : result }));
    } catch (e) {
      const msg = e && e.message ? e.message : String(e);
      log('error', 'callMethod ' + method + ' on ' + pluginId + ' failed: ' + (e && e.stack ? e.stack : msg));
      bridge.resolveResult(callbackId, JSON.stringify({ ok: false, error: msg }));
    }
  }

  function resolveFetch(callbackId, responseJson) {
    const pending = pendingFetches.get(callbackId);
    if (!pending) {
      log('warn', 'resolveFetch: no pending callback ' + callbackId);
      return;
    }
    pendingFetches.delete(callbackId);
    try {
      const payload = JSON.parse(responseJson);
      pending.resolve(payload);
    } catch (e) {
      pending.reject(e);
    }
  }

  // -- expose to Kotlin -------------------------------------------------------

  window.__lnhost = {
    loadPlugin,
    callMethod,
    resolveFetch,
  };

  log('info', 'lnhost bootstrap ready; vendor present: cheerio=' + !!globalThis.cheerio
    + ' htmlparser2=' + !!globalThis.htmlparser2
    + ' dayjs=' + !!globalThis.dayjs);
})();
