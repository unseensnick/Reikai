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
  "use strict";

  // QuickJS has no browser globals; plugins (and the cheerio/dayjs bundles) expect a handful.
  globalThis.window = globalThis;
  globalThis.self = globalThis;

  if (typeof globalThis.console === "undefined") {
    var mkLog = function (level) {
      return function () {
        __lnLog(level, Array.prototype.map.call(arguments, String).join(" "));
      };
    };
    globalThis.console = {
      log: mkLog("info"),
      info: mkLog("info"),
      warn: mkLog("warn"),
      error: mkLog("error"),
      debug: mkLog("debug"),
    };
  }

  function log(level, message) {
    try {
      __lnLog(level, String(message));
    } catch (_) {
      /* ignore */
    }
  }

  if (typeof globalThis.URLSearchParams === "undefined") {
    // QuickJS has no URLSearchParams; the WebView host used the browser's native one. Plugins
    // construct it from a record (`new URLSearchParams({novelId: id})`) or pairs, not just a
    // string, so a string-only shim silently drops every param (novelbin's chapter URL 400s,
    // search 404s). Mirror the WHATWG init types and the method surface plugins use.
    var USP = function (init) {
      this._p = [];
      if (typeof init === "string") {
        var self = this;
        init
          .replace(/^\?/, "")
          .split("&")
          .forEach(function (kv) {
            if (!kv) return;
            var i = kv.indexOf("=");
            var k = i < 0 ? kv : kv.slice(0, i);
            var v = i < 0 ? "" : kv.slice(i + 1);
            self._p.push([
              decodeURIComponent(k.replace(/\+/g, " ")),
              decodeURIComponent(v.replace(/\+/g, " ")),
            ]);
          });
      } else if (Array.isArray(init)) {
        for (var j = 0; j < init.length; j++)
          this._p.push([String(init[j][0]), String(init[j][1])]);
      } else if (init && typeof init === "object") {
        for (var key in init) {
          if (Object.prototype.hasOwnProperty.call(init, key))
            this._p.push([key, String(init[key])]);
        }
      }
    };
    USP.prototype.append = function (k, v) {
      this._p.push([String(k), String(v)]);
    };
    USP.prototype.set = function (k, v) {
      k = String(k);
      v = String(v);
      var found = false;
      var out = [];
      for (var i = 0; i < this._p.length; i++) {
        if (this._p[i][0] === k) {
          if (!found) {
            out.push([k, v]);
            found = true;
          }
        } else out.push(this._p[i]);
      }
      if (!found) out.push([k, v]);
      this._p = out;
    };
    USP.prototype["delete"] = function (k) {
      k = String(k);
      this._p = this._p.filter(function (e) {
        return e[0] !== k;
      });
    };
    USP.prototype.get = function (k) {
      k = String(k);
      for (var i = 0; i < this._p.length; i++)
        if (this._p[i][0] === k) return this._p[i][1];
      return null;
    };
    USP.prototype.getAll = function (k) {
      k = String(k);
      return this._p
        .filter(function (e) {
          return e[0] === k;
        })
        .map(function (e) {
          return e[1];
        });
    };
    USP.prototype.has = function (k) {
      k = String(k);
      return this._p.some(function (e) {
        return e[0] === k;
      });
    };
    USP.prototype.forEach = function (fn, thisArg) {
      this._p.forEach(function (e) {
        fn.call(thisArg, e[1], e[0], this);
      }, this);
    };
    USP.prototype.keys = function () {
      return this._p.map(function (e) {
        return e[0];
      });
    };
    USP.prototype.values = function () {
      return this._p.map(function (e) {
        return e[1];
      });
    };
    USP.prototype.entries = function () {
      return this._p.slice();
    };
    USP.prototype[Symbol.iterator] = function () {
      return this._p.slice()[Symbol.iterator]();
    };
    USP.prototype.toString = function () {
      return this._p
        .map(function (e) {
          return encodeURIComponent(e[0]) + "=" + encodeURIComponent(e[1]);
        })
        .join("&");
    };
    globalThis.URLSearchParams = USP;
  }

  if (typeof globalThis.URL === "undefined") {
    // Minimal WHATWG URL. lnreader's RN runtime ships react-native-url-polyfill; QuickJS has
    // nothing, and the ReadNovelFull/Madara families do `new URL(href, site)` to resolve relative
    // links (crash: "URL is not defined"). Covers the read surface plugins use plus base
    // resolution. Not spec-complete: no IDNA, userinfo, or port normalization.
    var URLP = function (url, base) {
      var resolved = URLP._resolve(
        String(url),
        base != null ? String(base) : null,
      );
      var m = /^([^:/?#]+):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(
        resolved,
      );
      if (!m) throw new TypeError("Invalid URL: " + url);
      this.protocol = m[1].toLowerCase() + ":";
      var hostport = m[2];
      var colon = hostport.lastIndexOf(":");
      this.hostname = colon < 0 ? hostport : hostport.slice(0, colon);
      this.port = colon < 0 ? "" : hostport.slice(colon + 1);
      this.host = hostport;
      this.origin = this.protocol + "//" + this.host;
      this.pathname = m[3] || "/";
      this.search = m[4] || "";
      this.hash = m[5] || "";
      this.searchParams = new URLSearchParams(this.search);
    };
    URLP.prototype.toString = function () {
      var s = this.searchParams.toString();
      return (
        this.protocol +
        "//" +
        this.host +
        this.pathname +
        (s ? "?" + s : "") +
        this.hash
      );
    };
    Object.defineProperty(URLP.prototype, "href", {
      get: function () {
        return this.toString();
      },
      enumerable: true,
    });
    // RFC 3986 reference resolution: absolute as-is, else resolve against base.
    URLP._resolve = function (url, base) {
      if (/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) return url;
      if (!base) throw new TypeError("Invalid base URL for: " + url);
      var b =
        /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(
          base,
        );
      if (!b) throw new TypeError("Invalid base URL: " + base);
      var scheme = b[1],
        authority = b[2],
        basePath = b[3] || "/";
      if (url.indexOf("//") === 0) return scheme + ":" + url;
      if (url.charAt(0) === "/") return scheme + "://" + authority + url;
      if (url.charAt(0) === "?")
        return scheme + "://" + authority + basePath + url;
      if (url.charAt(0) === "#")
        return scheme + "://" + authority + basePath + (b[4] || "") + url;
      var dir = basePath.slice(0, basePath.lastIndexOf("/") + 1);
      var out = [];
      (dir + url).split("/").forEach(function (seg) {
        if (seg === ".") return;
        if (seg === "..") {
          if (out.length > 1) out.pop();
          return;
        }
        out.push(seg);
      });
      return scheme + "://" + authority + out.join("/");
    };
    globalThis.URL = URLP;
  }

  if (typeof globalThis.FormData === "undefined") {
    var FD = function () {
      this._p = [];
    };
    FD.prototype.append = function (k, v) {
      this._p.push([String(k), typeof v === "string" ? v : String(v)]);
    };
    FD.prototype.entries = function () {
      return this._p.slice();
    };
    globalThis.FormData = FD;
  }

  if (typeof globalThis.TextEncoder === "undefined") {
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
      var a = new Uint8Array(b);
      var s = "";
      for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
      return decodeURIComponent(escape(s));
    };
    globalThis.TextDecoder = TD;
  }

  // QuickJS has no btoa/atob (the WebView host inherited them from the browser). A few plugins
  // (wtrlab, komga, fictioneer custom transforms) base64 binary strings directly. Latin1 in/out,
  // matching the browser contract.
  if (typeof globalThis.btoa === "undefined") {
    var B64_CHARS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    globalThis.btoa = function (input) {
      var str = String(input);
      var output = "";
      for (var i = 0; i < str.length; i += 3) {
        var hasB1 = i + 1 < str.length;
        var hasB2 = i + 2 < str.length;
        var b0 = str.charCodeAt(i) & 0xff;
        var b1 = hasB1 ? str.charCodeAt(i + 1) & 0xff : 0;
        var b2 = hasB2 ? str.charCodeAt(i + 2) & 0xff : 0;
        output += B64_CHARS.charAt(b0 >> 2);
        output += B64_CHARS.charAt(((b0 & 0x03) << 4) | (b1 >> 4));
        output += hasB1
          ? B64_CHARS.charAt(((b1 & 0x0f) << 2) | (b2 >> 6))
          : "=";
        output += hasB2 ? B64_CHARS.charAt(b2 & 0x3f) : "=";
      }
      return output;
    };
    globalThis.atob = function (input) {
      var str = String(input).replace(/[^A-Za-z0-9+/]/g, "");
      var output = "";
      for (var i = 0; i < str.length; i += 4) {
        var e0 = B64_CHARS.indexOf(str.charAt(i));
        var e1 = B64_CHARS.indexOf(str.charAt(i + 1));
        output += String.fromCharCode((e0 << 2) | (e1 >> 4));
        if (i + 2 < str.length) {
          var e2 = B64_CHARS.indexOf(str.charAt(i + 2));
          output += String.fromCharCode(((e1 & 0x0f) << 4) | (e2 >> 2));
          if (i + 3 < str.length) {
            var e3 = B64_CHARS.indexOf(str.charAt(i + 3));
            output += String.fromCharCode(((e2 & 0x03) << 6) | e3);
          }
        }
      }
      return output;
    };
  }

  // Node's Buffer, enough for the base64 / hex / utf8 conversions LN plugins use (decoding an obfuscated
  // chapter body, hashing, etc.). Backed by a Uint8Array with an encoding-aware toString.
  if (typeof globalThis.Buffer === "undefined") {
    var hexToBytes = function (hex) {
      var clean = String(hex).replace(/[^0-9a-fA-F]/g, "");
      var bytes = new Uint8Array(clean.length >> 1);
      for (var i = 0; i < bytes.length; i++) {
        bytes[i] = parseInt(clean.substr(i * 2, 2), 16);
      }
      return bytes;
    };
    var bytesToHex = function (bytes) {
      var out = "";
      for (var i = 0; i < bytes.length; i++) {
        out += ("0" + bytes[i].toString(16)).slice(-2);
      }
      return out;
    };
    var Buf = function () {};
    Buf.from = function (data, enc) {
      var bytes;
      if (data instanceof Uint8Array) bytes = new Uint8Array(data);
      else if (Array.isArray(data)) bytes = Uint8Array.from(data);
      else if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
      else if (enc === "base64") bytes = base64ToBytes(String(data));
      else if (enc === "hex") bytes = hexToBytes(String(data));
      else bytes = new TextEncoder().encode(String(data));
      bytes.toString = function (e) {
        if (e === "base64") return bytesToBase64(this);
        if (e === "hex") return bytesToHex(this);
        return new TextDecoder().decode(this);
      };
      return bytes;
    };
    Buf.isBuffer = function (o) {
      return o instanceof Uint8Array;
    };
    Buf.concat = function (list) {
      var total = (list || []).reduce(function (n, b) {
        return n + b.length;
      }, 0);
      var out = new Uint8Array(total);
      var off = 0;
      (list || []).forEach(function (b) {
        out.set(b, off);
        off += b.length;
      });
      return Buf.from(out);
    };
    globalThis.Buffer = Buf;
  }

  // Minimal Blob for plugins that wrap bytes/text (QuickJS ships none). Enough for text() /
  // arrayBuffer() / size / type; no streaming.
  if (typeof globalThis.Blob === "undefined") {
    var Bl = function (parts, opts) {
      var chunks = [];
      (parts || []).forEach(function (p) {
        if (typeof p === "string") chunks.push(new TextEncoder().encode(p));
        else if (p instanceof ArrayBuffer) chunks.push(new Uint8Array(p));
        else if (p instanceof Uint8Array) chunks.push(p);
      });
      var total = chunks.reduce(function (n, c) {
        return n + c.length;
      }, 0);
      var bytes = new Uint8Array(total);
      var off = 0;
      chunks.forEach(function (c) {
        bytes.set(c, off);
        off += c.length;
      });
      this._bytes = bytes;
      this.size = total;
      this.type = (opts && opts.type) || "";
    };
    Bl.prototype.text = function () {
      return Promise.resolve(new TextDecoder().decode(this._bytes));
    };
    Bl.prototype.arrayBuffer = function () {
      return Promise.resolve(this._bytes.buffer);
    };
    globalThis.Blob = Bl;
  }

  // QuickJS has no Headers. Plugins (mtlnovel family, readfrom, novelyra) build request headers with
  // `new Headers({...})` and pass it as init.headers; without this they crash "'Headers' is not
  // defined". WHATWG-ish: case-insensitive names, append concatenates, the method surface plugins use.
  if (typeof globalThis.Headers === "undefined") {
    var H = function (init) {
      this._h = {};
      if (init) {
        var self = this;
        if (init instanceof H) {
          init.forEach(function (v, k) {
            self.append(k, v);
          });
        } else if (Array.isArray(init)) {
          for (var i = 0; i < init.length; i++)
            this.append(init[i][0], init[i][1]);
        } else if (typeof init === "object") {
          for (var k in init) {
            if (Object.prototype.hasOwnProperty.call(init, k))
              this.append(k, init[k]);
          }
        }
      }
    };
    H.prototype.append = function (k, v) {
      k = String(k).toLowerCase();
      this._h[k] =
        this._h[k] != null ? this._h[k] + ", " + String(v) : String(v);
    };
    H.prototype.set = function (k, v) {
      this._h[String(k).toLowerCase()] = String(v);
    };
    H.prototype.get = function (k) {
      k = String(k).toLowerCase();
      return this._h[k] != null ? this._h[k] : null;
    };
    H.prototype.has = function (k) {
      return Object.prototype.hasOwnProperty.call(
        this._h,
        String(k).toLowerCase(),
      );
    };
    H.prototype["delete"] = function (k) {
      delete this._h[String(k).toLowerCase()];
    };
    H.prototype.forEach = function (fn, thisArg) {
      var self = this;
      Object.keys(this._h).forEach(function (k) {
        fn.call(thisArg, self._h[k], k, self);
      });
    };
    H.prototype.keys = function () {
      return Object.keys(this._h);
    };
    H.prototype.values = function () {
      var self = this;
      return Object.keys(this._h).map(function (k) {
        return self._h[k];
      });
    };
    H.prototype.entries = function () {
      var self = this;
      return Object.keys(this._h).map(function (k) {
        return [k, self._h[k]];
      });
    };
    globalThis.Headers = H;
  }

  // QuickJS has no timers. Plugins (inoveltranslation, novelfire, the readnovelfull family) use
  // setTimeout for rate-limit politeness sleeps and retry backoffs, so the delay must be honored or
  // the source gets hammered and blocked. __lnDelay (a Kotlin async binding) suspends the engine job
  // pump for the real (capped) delay; `await new Promise(r => setTimeout(r, n))` then waits n ms.
  if (typeof globalThis.setTimeout === "undefined") {
    var _timers = {};
    var _tid = 1;
    globalThis.setTimeout = function (fn, ms) {
      var id = _tid++;
      _timers[id] = true;
      var delayMs = typeof ms === "number" && ms > 0 ? ms : 0;
      globalThis.__lnDelay(delayMs).then(function () {
        if (_timers[id]) {
          delete _timers[id];
          try {
            fn();
          } catch (e) {
            log("error", "setTimeout cb: " + ((e && e.message) || e));
          }
        }
      });
      return id;
    };
    globalThis.clearTimeout = function (id) {
      delete _timers[id];
    };
    globalThis.setInterval = function () {
      return 0;
    }; // no repeat loop headlessly
    globalThis.clearInterval = function () {};
  }

  // QuickJS (dokar build) ships no Intl. RLIB references it at load time and crashes "'Intl' is not
  // defined". Minimal stub: enough for plugins that format dates/numbers for display. Not locale-aware
  // (returns the default string form), so verify any plugin that depends on localized output.
  if (typeof globalThis.Intl === "undefined") {
    globalThis.Intl = {
      DateTimeFormat: function (_l, opts) {
        return {
          format: function (d) {
            return new Date(d).toString();
          },
          resolvedOptions: function () {
            return opts || {};
          },
        };
      },
      NumberFormat: function (_l, opts) {
        return {
          format: function (n) {
            return String(n);
          },
          resolvedOptions: function () {
            return opts || {};
          },
        };
      },
      Collator: function () {
        return {
          compare: function (a, b) {
            a = String(a);
            b = String(b);
            return a < b ? -1 : a > b ? 1 : 0;
          },
        };
      },
    };
  }

  // -- @libs/fetch shim -------------------------------------------------------
  // Plugins expect a Response-like object. We synthesize the methods plugins actually use.
  // No .blob(): QuickJS has no Blob and novel sources are text-only; a plugin calling .blob()
  // would throw (surfaced by the Phase 0 compatibility sweep).

  function makeResponse(payload) {
    var headersObj = payload.headers || {};
    // arrayBuffer / blob read bytes: an explicit binary body (bodyBase64, e.g. fetchProto) if present,
    // else the UTF-8 bytes of the text body. Text sources still read through the charset-aware text()
    // path, so this never disturbs them (no charset-guessing binary fetch; that is deliberately parked).
    function bodyBytes() {
      if (payload.bodyBase64 != null) return base64ToBytes(payload.bodyBase64);
      return new TextEncoder().encode(payload.body || "");
    }
    return {
      // Final URL after redirects. The Madara plugin family reads response.url to detect
      // Cloudflare/captcha redirects; without it they crash on undefined.split('/').
      url: payload.url || "",
      status: payload.status,
      statusText: payload.statusText || "",
      ok: payload.status >= 200 && payload.status < 300,
      headers: {
        get: function (name) {
          return headersObj[name.toLowerCase()] || null;
        },
        has: function (name) {
          return Object.prototype.hasOwnProperty.call(
            headersObj,
            name.toLowerCase(),
          );
        },
        forEach: function (fn) {
          Object.keys(headersObj).forEach(function (k) {
            fn(headersObj[k], k);
          });
        },
        keys: function () {
          return Object.keys(headersObj);
        },
        values: function () {
          return Object.keys(headersObj).map(function (k) {
            return headersObj[k];
          });
        },
        entries: function () {
          return Object.keys(headersObj).map(function (k) {
            return [k, headersObj[k]];
          });
        },
      },
      text: function () {
        return Promise.resolve(payload.body);
      },
      json: function () {
        return Promise.resolve(JSON.parse(payload.body));
      },
      arrayBuffer: function () {
        return Promise.resolve(bodyBytes().buffer);
      },
      blob: function () {
        return Promise.resolve(
          new globalThis.Blob([bodyBytes()], {
            type: headersObj["content-type"] || "",
          }),
        );
      },
    };
  }

  // lnreader's makeInit defaults (refs/lnreader-main/src/plugins/helpers/fetch.ts). Sending these
  // browser-like headers is the difference between many servers answering and stonewalling a
  // non-browser request, which is why sources that work in the lnreader app failed here. User-Agent
  // is supplied by the app's UserAgentInterceptor; Accept-Encoding is left to OkHttp (transparent gzip).
  var DEFAULT_HEADERS = {
    Accept: "*/*",
    "Accept-Language": "*",
    "Sec-Fetch-Mode": "cors",
    "Cache-Control": "max-age=0",
    Connection: "keep-alive",
  };

  async function fetchApi(url, init) {
    var normalized = init ? Object.assign({}, init) : {};
    // Merge the defaults under the plugin's headers (plugin wins, case-insensitively).
    var merged = Object.assign({}, DEFAULT_HEADERS);
    var provided = normalized.headers || {};
    // A plugin may pass a Headers instance; flatten it to a plain object (its data lives in _h).
    if (provided instanceof globalThis.Headers) {
      var po = {};
      provided.forEach(function (v, k) {
        po[k] = v;
      });
      provided = po;
    }
    Object.keys(provided).forEach(function (k) {
      Object.keys(merged).forEach(function (d) {
        if (d.toLowerCase() === k.toLowerCase()) delete merged[d];
      });
      merged[k] = provided[k];
    });
    normalized.headers = merged;
    // FormData posts go out as real multipart/form-data (what browsers and lnreader's RN fetch send),
    // not urlencoded: some WordPress admin-ajax endpoints (e.g. Novel Updates' nd_getchapters) only
    // answer multipart and reply "0" otherwise. The Kotlin bridge builds the multipart body from the
    // field pairs. URLSearchParams stays urlencoded (that's its own content type).
    if (normalized.body instanceof FormData) {
      normalized.multipart = Array.from(normalized.body.entries()).map(
        function (e) {
          return [e[0], typeof e[1] === "string" ? e[1] : String(e[1])];
        },
      );
      delete normalized.body;
    } else if (normalized.body instanceof URLSearchParams) {
      normalized.body = normalized.body.toString();
      normalized.headers = Object.assign({}, normalized.headers || {});
      var hasCT = Object.keys(normalized.headers).some(function (h) {
        return h.toLowerCase() === "content-type";
      });
      if (!hasCT)
        normalized.headers["Content-Type"] =
          "application/x-www-form-urlencoded";
    }
    var raw = JSON.parse(await __lnFetch(url, JSON.stringify(normalized)));
    if (raw && raw.error) throw new Error(raw.error);
    return makeResponse(raw);
  }

  async function fetchText(url, init) {
    var res = await fetchApi(url, init);
    if (!res.ok) return "";
    return await res.text();
  }

  // Some plugins (ixdzs8, rainofsnow) call the global fetch() directly instead of @libs/fetch. Route
  // it through the same Kotlin OkHttp bridge, NOT native fetch (which would bypass the Cloudflare /
  // Flaresolverr interceptors), matching the "fetch is never exposed natively" rule above.
  globalThis.fetch = fetchApi;

  // gRPC-web client. protobufjs (vendored) parses the plugin's .proto string and encodes/decodes
  // messages; we add the 5-byte gRPC-web frame and round-trip the binary through the Kotlin bridge
  // as base64 (QuickJS has no Blob/FileReader, so we can't use lnreader's transport). WuxiaWorld.
  async function fetchProto(protoInit, url, init) {
    var pb = globalThis.protobuf;
    if (!pb) throw new Error("protobufjs not loaded");
    var b64 = pb.util.base64;
    var root = pb.parse(protoInit.proto).root;
    var ReqT = root.lookupType(protoInit.requestType);
    var verr = ReqT.verify(protoInit.requestData || {});
    if (verr) throw new Error("Invalid Proto: " + verr);
    var msg = ReqT.encode(protoInit.requestData || {}).finish();
    // gRPC-web frame: 1 flag byte (uncompressed) + 4-byte big-endian length, then the message.
    var framed = new Uint8Array(5 + msg.length);
    framed[1] = (msg.length >>> 24) & 0xff;
    framed[2] = (msg.length >>> 16) & 0xff;
    framed[3] = (msg.length >>> 8) & 0xff;
    framed[4] = msg.length & 0xff;
    framed.set(msg, 5);

    var normalized = init ? Object.assign({}, init) : {};
    normalized.method = "POST";
    normalized.binary = true;
    normalized.bodyBase64 = b64.encode(framed, 0, framed.length);
    delete normalized.body;

    var raw = JSON.parse(await __lnFetch(url, JSON.stringify(normalized)));
    if (raw && raw.error) throw new Error(raw.error);
    var enc = raw.bodyBase64 || "";
    var resp = new Uint8Array(b64.length(enc));
    b64.decode(enc, resp, 0);
    if (resp.length < 5) throw new Error("Empty gRPC-web response");
    var rlen = (resp[1] << 24) | (resp[2] << 16) | (resp[3] << 8) | resp[4];
    var RespT = root.lookupType(protoInit.responseType);
    return RespT.toObject(RespT.decode(resp.subarray(5, 5 + rlen)), {
      longs: String,
      enums: String,
      bytes: String,
      defaults: true,
      arrays: true,
      objects: true,
    });
  }

  // -- @libs/storage shim -----------------------------------------------------
  // The three scopes (storage/local/session) map to the same Kotlin SharedPreferences, keyed by
  // prefix. Backed by __lnGetStorage/__lnSetStorage. Values are stored as lnreader's StoredItem
  // envelope ({created, value, expires}, JSON-encoded) so booleans / arrays / objects round-trip
  // with their type (refs/lnreader-main/src/plugins/helpers/storage.ts) instead of stringifying.

  function makeStorage(pluginId, kind) {
    var prefix = kind + ":";
    return {
      set: function (key, value, expires) {
        var item = {
          created: new Date(),
          value: value,
          expires: expires instanceof Date ? expires.getTime() : expires,
        };
        __lnSetStorage(pluginId, prefix + key, JSON.stringify(item));
      },
      get: function (key, raw) {
        var stored = __lnGetStorage(pluginId, prefix + key);
        if (stored === null || stored === undefined) return undefined;
        var item;
        try {
          item = JSON.parse(stored);
        } catch (e) {
          return stored;
        } // legacy plain value
        if (item && typeof item === "object" && "value" in item) {
          if (item.expires && Date.now() > item.expires) {
            __lnSetStorage(pluginId, prefix + key, null);
            return undefined;
          }
          return raw ? item : item.value;
        }
        return item;
      },
      delete: function (key) {
        __lnSetStorage(pluginId, prefix + key, null);
      },
    };
  }

  // -- @libs/* data constants -------------------------------------------------

  var NovelStatus = Object.freeze({
    Unknown: "Unknown",
    Ongoing: "Ongoing",
    Completed: "Completed",
    Licensed: "Licensed",
    PublishingFinished: "Publishing Finished",
    Cancelled: "Cancelled",
    OnHiatus: "On Hiatus",
  });
  // Member NAMES must mirror LNReader's FilterTypes enum: compiled plugins look a type up by name
  // (`i.FilterTypes.CheckboxGroup`), so a missing/renamed member resolves to undefined and that filter
  // silently never renders. The string VALUES are Reikai's own convention, matched by the value branches
  // in NovelSourceFilterSheet, so they must stay in sync with that sheet, not with upstream's values.
  var FilterTypes = Object.freeze({
    TextInput: "TextInput",
    Picker: "Picker",
    CheckboxGroup: "Checkbox",
    Switch: "Switch",
    XCheckbox: "XCheckbox",
    ExcludableCheckboxGroup: "ExcludableCheckboxGroup",
  });
  var defaultCover =
    "https://github.com/LNReader/lnreader-sources/blob/main/icons/no-cover.jpg?raw=true";
  function isUrlAbsolute(url) {
    return /^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(url);
  }
  function utf8ToBytes(s) {
    return Array.from(new TextEncoder().encode(s));
  }
  function bytesToUtf8(b) {
    return new TextDecoder().decode(new Uint8Array(b));
  }
  // base64 <-> bytes over the Latin1 atob/btoa shims, for Response.arrayBuffer / Blob / Buffer.
  function base64ToBytes(b64) {
    var bin = globalThis.atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }
  function bytesToBase64(bytes) {
    var bin = "";
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return globalThis.btoa(bin);
  }
  var urlencode = {
    encode: function (s) {
      return encodeURIComponent(String(s));
    },
    decode: function (s) {
      return decodeURIComponent(String(s));
    },
  };
  // @libs/aes is backed by the @noble/ciphers vendor bundle (globalThis.nobleCiphers). The stub is
  // a fallback that throws a clear error if the bundle somehow didn't load.
  var aesStub = new Proxy(
    {},
    {
      get: function (_t, prop) {
        return function () {
          throw new Error("@libs/aes not available (" + String(prop) + ")");
        };
      },
    },
  );
  var aesLib =
    typeof globalThis.nobleCiphers !== "undefined" &&
    globalThis.nobleCiphers.gcm
      ? { gcm: globalThis.nobleCiphers.gcm }
      : aesStub;

  // -- the require() resolver -------------------------------------------------

  function makeRequire(pluginId) {
    var packages = {
      cheerio: globalThis.cheerio,
      htmlparser2: globalThis.htmlparser2,
      dayjs: globalThis.dayjs,
      protobufjs: globalThis.protobuf,
      urlencode: urlencode,
      buffer: { Buffer: globalThis.Buffer },
      "@libs/novelStatus": { NovelStatus: NovelStatus },
      "@libs/fetch": {
        fetchApi: fetchApi,
        fetchText: fetchText,
        fetchProto: fetchProto,
      },
      "@libs/isAbsoluteUrl": { isUrlAbsolute: isUrlAbsolute },
      "@libs/filterInputs": { FilterTypes: FilterTypes },
      "@libs/defaultCover": { defaultCover: defaultCover },
      // Runtime constants module (NovelStatus + defaultCover values); some plugins (novelfire)
      // import from here rather than the @libs aliases. @/types/plugin is types-only (erased at
      // compile) so it never reaches require.
      "@/types/constants": {
        NovelStatus: NovelStatus,
        defaultCover: defaultCover,
      },
      "@libs/aes": aesLib,
      "@libs/utils": { utf8ToBytes: utf8ToBytes, bytesToUtf8: bytesToUtf8 },
    };
    return function _require(name) {
      if (name === "@libs/storage") {
        return {
          storage: makeStorage(pluginId, "storage"),
          localStorage: makeStorage(pluginId, "local"),
          sessionStorage: makeStorage(pluginId, "session"),
        };
      }
      var pkg = packages[name];
      if (pkg === undefined) {
        log(
          "warn",
          'require: unknown module "' +
            name +
            '" requested by plugin ' +
            pluginId,
        );
        return undefined;
      }
      return pkg;
    };
  }

  // -- plugin registry --------------------------------------------------------

  var plugins = new Map(); // pluginId -> Plugin instance

  // No-op storage trio for the discovery pass: doesn't touch Kotlin so no SharedPreferences file is
  // allocated for a scope we'd abandon.
  var NOOP = {
    get: function () {
      return null;
    },
    set: function () {},
    delete: function () {},
  };
  var NOOP_STORAGE = {
    storage: NOOP,
    localStorage: NOOP,
    sessionStorage: NOOP,
  };

  function loadPlugin(pluginId, rawCode, iconUrl, lang) {
    try {
      // Pass 1: discover the plugin's intrinsic id with @libs/storage shadowed by no-ops, so plugins
      // that read storage at load time (royalroad, webnovel) don't write to a temporary scope.
      var canonicalId = pluginId;
      try {
        var baseReq = makeRequire(pluginId);
        var discoverReq = function (name) {
          return name === "@libs/storage" ? NOOP_STORAGE : baseReq(name);
        };
        var dm = { exports: {} };
        var df = new Function(
          "require",
          "module",
          "exports",
          rawCode + "\nreturn module.exports.default || module.exports;",
        );
        var discovered = df(discoverReq, dm, dm.exports);
        if (discovered && discovered.id) canonicalId = discovered.id;
      } catch (e) {
        log(
          "warn",
          "plugin id discovery failed for " +
            pluginId +
            ": " +
            ((e && e.message) || e),
        );
      }

      // Pass 2: real load. Storage scope is canonicalId; registry key is always plugin.id.
      var req = makeRequire(canonicalId);
      var m = { exports: {} };
      var fn = new Function(
        "require",
        "module",
        "exports",
        rawCode + "\nreturn module.exports.default || module.exports;",
      );
      var plugin = fn(req, m, m.exports);
      if (!plugin || !plugin.id)
        throw new Error("plugin did not export a default with .id");
      plugins.set(plugin.id, plugin);
      log(
        "info",
        "loaded plugin " + plugin.id + " v" + (plugin.version || "?"),
      );
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
        // Per-plugin settings schema (login/base-url/toggles). Plugins read the saved values via
        // @libs/storage; the Kotlin side renders this and writes values back into that scope.
        pluginSettings: plugin.pluginSettings || null,
      };
    } catch (e) {
      log("error", "loadPlugin failed: " + (e && e.stack ? e.stack : e));
      throw e;
    }
  }

  // -- method dispatch --------------------------------------------------------
  // Returns the result envelope as a JSON string (resolved by LnPluginHost.callMethod via a global).

  async function callMethod(pluginId, method, argsJson) {
    try {
      var plugin = plugins.get(pluginId);
      if (!plugin) throw new Error("plugin not loaded: " + pluginId);
      if (typeof plugin[method] !== "function")
        throw new Error("method " + method + " missing on " + pluginId);
      var args = JSON.parse(argsJson || "[]");
      var result = await plugin[method].apply(plugin, args);
      return JSON.stringify({
        ok: true,
        value: result === undefined ? null : result,
      });
    } catch (e) {
      var msg = e && e.message ? e.message : String(e);
      log(
        "error",
        "callMethod " +
          method +
          " on " +
          pluginId +
          " failed: " +
          (e && e.stack ? e.stack : msg),
      );
      return JSON.stringify({ ok: false, error: msg });
    }
  }

  // -- expose to Kotlin -------------------------------------------------------

  globalThis.__lnLoadPlugin = loadPlugin;
  globalThis.__lnCallMethod = callMethod;
  log(
    "info",
    "lnhost headless runtime ready; vendor present: cheerio=" +
      !!globalThis.cheerio +
      " htmlparser2=" +
      !!globalThis.htmlparser2 +
      " dayjs=" +
      !!globalThis.dayjs +
      " protobuf=" +
      (typeof globalThis.protobuf !== "undefined"),
  );
})();
