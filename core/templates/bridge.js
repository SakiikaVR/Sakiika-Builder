/*
 * さきいかビルダー — JavaScript ブリッジ
 *
 * ビルド時に assets/__sakiika/bridge.js として書き出され、各 HTML の <head> に
 * 自動で挿入されます。手で読み込む必要はありません。
 *
 *   await Android.ui.toast({text: "やあ"});
 *   const info = await Android.sys.info();
 *   Android.on("sensor.accelerometer", e => console.log(e.x, e.y, e.z));
 *
 * すべてのメソッドは Promise を返します。同期版が必要なときは Android.sync を
 * 使ってください（呼び出し中は JS が止まります）。
 */
(function (global) {
  "use strict";

  var native = global.__SakiikaNative;
  if (!native) {
    // Opened in a desktop browser rather than the generated app: stub things out
    // so a page can still be developed and tested outside Android.
    console.warn("[さきいか] ネイティブブリッジがありません（Android アプリ外で実行中）");
    global.__sakiika = { settle: function () {}, emit: function () {} };
    var missing = function (name) {
      return function () {
        return Promise.reject(new Error(
          name + " は Android アプリ内でのみ使えます（さきいかビルダーでビルドしてください）"));
      };
    };
    global.__GLOBAL__ = new Proxy({
      available: false,
      build: null,
      on: function () { return function () {}; },
      off: function () {},
      ready: Promise.resolve(false),
      sync: {}
    }, {
      get: function (target, prop) {
        if (prop in target) { return target[prop]; }
        return new Proxy({}, { get: function (_t, method) { return missing(String(prop) + "." + String(method)); } });
      }
    });
    return;
  }

  var nextCall = 1;
  var pending = Object.create(null);
  var listeners = Object.create(null);

  var internal = {
    /** Called from Java when an async bridge call finishes. */
    settle: function (callId, envelope) {
      var entry = pending[callId];
      if (!entry) { return; }
      delete pending[callId];
      if (envelope && envelope.ok) {
        entry.resolve(envelope.value);
      } else {
        entry.reject(toError(envelope, entry.label));
      }
    },

    /** Called from Java to push an event. */
    emit: function (channel, payload) {
      var handlers = listeners[channel];
      if (!handlers || !handlers.length) { return; }
      // Copy first: a handler may unsubscribe itself.
      handlers.slice().forEach(function (fn) {
        try {
          fn(payload, channel);
        } catch (e) {
          console.error("[さきいか] " + channel + " のハンドラーが例外を投げました", e);
        }
      });
    },

    pendingCount: function () { return Object.keys(pending).length; }
  };
  global.__sakiika = internal;

  function toError(envelope, label) {
    var info = (envelope && envelope.error) || {};
    var err = new Error((label ? label + ": " : "") + (info.message || "不明なエラー"));
    err.code = info.code || "internal";
    err.bridgeCall = label;
    if (info.stack) { err.javaStack = info.stack; }
    return err;
  }

  function callAsync(module, method, args) {
    var callId = nextCall++;
    var label = module + "." + method;
    return new Promise(function (resolve, reject) {
      pending[callId] = { resolve: resolve, reject: reject, label: label };
      try {
        native.invokeAsync(callId, module, method, args === undefined ? "" : JSON.stringify(args));
      } catch (e) {
        delete pending[callId];
        reject(new Error(label + " を呼び出せませんでした: " + e));
      }
    });
  }

  function callSync(module, method, args) {
    var raw = native.invoke(module, method, args === undefined ? "" : JSON.stringify(args));
    var envelope;
    try {
      envelope = JSON.parse(raw);
    } catch (e) {
      throw new Error(module + "." + method + " の応答を解釈できませんでした: " + raw);
    }
    if (!envelope.ok) { throw toError(envelope, module + "." + method); }
    return envelope.value;
  }

  var descriptor;
  try {
    descriptor = JSON.parse(native.describe());
  } catch (e) {
    descriptor = {};
    console.error("[さきいか] モジュール一覧の取得に失敗しました", e);
  }

  var buildInfo = null;
  try {
    buildInfo = JSON.parse(native.buildInfo());
  } catch (e) {
    console.warn("[さきいか] ビルド情報を読めませんでした", e);
  }

  var api = {
    /** True inside a generated app. Check this before using anything else. */
    available: true,

    /** App name, package, version, file-access level, declared permissions. */
    build: buildInfo,

    /** Module → method names, as compiled into this APK. */
    modules: descriptor,

    /** Blocking variants of every method. Freezes JS until Java answers. */
    sync: {},

    /**
     * Subscribes to an event channel. Returns an unsubscribe function.
     *   const stop = Android.on("location.update", p => …);
     */
    on: function (channel, handler) {
      if (typeof handler !== "function") {
        throw new TypeError("Android.on(channel, handler): handler は関数です");
      }
      (listeners[channel] || (listeners[channel] = [])).push(handler);
      return function () { api.off(channel, handler); };
    },

    /** Fires once, then unsubscribes. */
    once: function (channel, handler) {
      var stop = api.on(channel, function (payload, ch) {
        stop();
        handler(payload, ch);
      });
      return stop;
    },

    /** Removes one handler, or every handler for the channel. */
    off: function (channel, handler) {
      if (!listeners[channel]) { return; }
      if (!handler) { delete listeners[channel]; return; }
      listeners[channel] = listeners[channel].filter(function (fn) { return fn !== handler; });
      if (!listeners[channel].length) { delete listeners[channel]; }
    },

    /** Resolves once the next event arrives on the channel. */
    waitFor: function (channel, timeoutMs) {
      return new Promise(function (resolve, reject) {
        var timer = null;
        var stop = api.on(channel, function (payload) {
          if (timer) { clearTimeout(timer); }
          stop();
          resolve(payload);
        });
        if (timeoutMs) {
          timer = setTimeout(function () {
            stop();
            reject(new Error(channel + " のイベントが " + timeoutMs + "ms 以内に来ませんでした"));
          }, timeoutMs);
        }
      });
    },

    /** Escape hatch for a method the facade does not know about. */
    call: callAsync,
    callSync: callSync,

    /** Channels with at least one subscriber right now. */
    activeChannels: function () { return Object.keys(listeners); }
  };

  Object.keys(descriptor).forEach(function (moduleName) {
    var asyncModule = {};
    var syncModule = {};
    (descriptor[moduleName] || []).forEach(function (method) {
      asyncModule[method] = function (args) { return callAsync(moduleName, method, args); };
      syncModule[method] = function (args) { return callSync(moduleName, method, args); };
    });
    // Unknown method names should say so rather than be undefined.
    api[moduleName] = new Proxy(asyncModule, {
      get: function (target, prop) {
        if (prop in target) { return target[prop]; }
        if (typeof prop === "symbol") { return undefined; }
        return function () {
          return Promise.reject(new Error(
            moduleName + "." + String(prop) + " はありません。使えるのは: "
            + (descriptor[moduleName] || []).join(", ")));
        };
      }
    });
    api.sync[moduleName] = syncModule;
  });

  api.ready = Promise.resolve(true);

  global.__GLOBAL__ = api;
  // Fires after the facade exists, so a page can just listen for it.
  try {
    global.dispatchEvent(new Event("sakiika-ready"));
  } catch (e) {
    /* Event constructor missing on very old WebViews; ignore. */
  }
})(window);
