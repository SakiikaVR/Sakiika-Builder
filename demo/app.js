/*
 * デモの実行エンジン。spec.js の定義からフォームを組み立て、ブリッジを呼び、
 * 結果を表示します。ファイルブラウザーとリフレクション実験台だけは
 * 手書きのパネルです。
 */
(function () {
  'use strict';

  const $ = (sel) => document.querySelector(sel);
  const el = (tag, props, children) => {
    const node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach((k) => {
        if (k === 'class') { node.className = props[k]; }
        else if (k === 'text') { node.textContent = props[k]; }
        else if (k.startsWith('on')) { node.addEventListener(k.slice(2), props[k]); }
        else { node.setAttribute(k, props[k]); }
      });
    }
    (children || []).forEach((child) => node.appendChild(child));
    return node;
  };

  const bridge = window.Android;
  const online = !!(bridge && bridge.available);

  // ------------------------------------------------------------ 結果表示

  const resultBox = $('#result');
  const lastCall = $('#lastCall');

  function showResult(label, value, isError) {
    lastCall.textContent = label;
    resultBox.className = isError ? 'err' : '';
    resultBox.textContent = format(value);
    resultBox.scrollTop = 0;
  }

  function format(value) {
    if (value === undefined) { return '(undefined)'; }
    if (typeof value === 'string') { return value; }
    try {
      return JSON.stringify(value, null, 2);
    } catch (e) {
      return String(value);
    }
  }

  /** data URL は全文を出すと数百 KB になるので畳む。 */
  function condense(value) {
    if (!value || typeof value !== 'object') { return value; }
    const copy = Array.isArray(value) ? value.slice() : Object.assign({}, value);
    Object.keys(copy).forEach((key) => {
      const v = copy[key];
      if (typeof v === 'string' && v.length > 400) {
        copy[key] = v.slice(0, 120) + ' …(' + v.length + ' 文字を省略)';
      } else if (v && typeof v === 'object') {
        copy[key] = condense(v);
      }
    });
    return copy;
  }

  $('#clearResult').addEventListener('click', () => {
    resultBox.textContent = '';
    lastCall.textContent = '';
  });

  $('#copyResult').addEventListener('click', async () => {
    const text = resultBox.textContent;
    if (online) {
      try {
        await bridge.clipboard.write({ text });
        await bridge.ui.toast({ text: 'コピーしました' });
        return;
      } catch (e) { /* クリップボードモジュールが無効なら下へ */ }
    }
    try { await navigator.clipboard.writeText(text); } catch (e) { /* 無視 */ }
  });

  // -------------------------------------------------------- イベントログ

  const eventLog = $('#eventLog');
  let eventCount = 0;
  let firstEvent = true;

  function logEvent(channel, payload) {
    eventCount++;
    $('#eventCount').textContent = eventCount + ' 件';
    if (firstEvent) { eventLog.textContent = ''; firstEvent = false; }
    const time = new Date().toLocaleTimeString('ja-JP', { hour12: false });
    const line = time + '  ' + channel + '  ' + format(condense(payload)).replace(/\n\s*/g, ' ');
    eventLog.textContent = line + '\n' + eventLog.textContent;
    // 行が増えすぎるとスクロールが重くなるので上限を設ける。
    const lines = eventLog.textContent.split('\n');
    if (lines.length > 200) { eventLog.textContent = lines.slice(0, 200).join('\n'); }
  }

  $('#clearEvents').addEventListener('click', () => {
    eventLog.textContent = '';
    eventCount = 0;
    firstEvent = true;
    $('#eventCount').textContent = '0 件';
  });

  // チャンネル名を先に知らなくても全部拾えるよう emit をラップする。
  if (window.__sakiika && typeof window.__sakiika.emit === 'function') {
    const original = window.__sakiika.emit.bind(window.__sakiika);
    window.__sakiika.emit = function (channel, payload) {
      logEvent(channel, payload);
      original(channel, payload);
    };
  }

  // ---------------------------------------------------------- フォーム値

  function readField(spec, node) {
    switch (spec.kind) {
      case 'check':
        return node.checked;
      case 'number': {
        if (node.value === '') { return undefined; }
        const num = Number(node.value);
        return Number.isNaN(num) ? undefined : num;
      }
      case 'json': {
        const raw = node.value.trim();
        if (!raw) { return undefined; }
        try {
          return JSON.parse(raw);
        } catch (e) {
          throw new Error(spec.label + ': JSON として読めません（' + e.message + '）');
        }
      }
      default: {
        const raw = node.value;
        return raw === '' ? undefined : raw;
      }
    }
  }

  function buildField(name, spec) {
    const id = 'f_' + Math.random().toString(36).slice(2, 9);
    let input;
    if (spec.kind === 'check') {
      input = el('input', { type: 'checkbox', id });
      input.checked = !!spec.value;
      const wrap = el('div', { class: 'field check' }, [input, el('label', { for: id, text: spec.label })]);
      return { wrap, input, spec, name };
    }
    if (spec.kind === 'select') {
      input = el('select', { id });
      spec.options.forEach((opt) => {
        const o = el('option', { value: opt, text: opt });
        if (opt === spec.value) { o.selected = true; }
        input.appendChild(o);
      });
    } else if (spec.kind === 'json') {
      input = el('textarea', { id, rows: '3' });
      input.value = spec.value == null ? '' : spec.value;
    } else if (spec.kind === 'number') {
      input = el('input', { type: 'number', id });
      if (spec.step != null) { input.step = spec.step; }
      if (spec.min != null) { input.min = spec.min; }
      if (spec.max != null) { input.max = spec.max; }
      input.value = spec.value == null ? '' : spec.value;
    } else {
      input = el('input', { type: 'text', id });
      input.value = spec.value == null ? '' : spec.value;
    }
    const cls = (spec.kind === 'json' || (spec.value && String(spec.value).length > 24))
      ? 'field wide' : 'field';
    const wrap = el('div', { class: cls }, [el('label', { for: id, text: spec.label }), input]);
    return { wrap, input, spec, name };
  }

  // ------------------------------------------------------------ カード描画

  function buildCard(moduleName, item) {
    const head = el('div', { class: 'card-head' }, [
      el('strong', { text: item.title || item.m }),
      el('code', { text: 'Android.' + moduleName + '.' + item.m + '()' })
    ]);
    if (item.perm) { head.appendChild(el('span', { class: 'badge perm', text: '要 ' + item.perm })); }

    const card = el('div', { class: 'card' }, [head]);
    if (item.desc) { card.appendChild(el('p', { class: 'card-desc', text: item.desc })); }
    if (item.note) { card.appendChild(el('p', { class: 'card-desc', text: '⚠ ' + item.note })); }

    const fields = [];
    if (item.args) {
      const grid = el('div', { class: 'fields' });
      Object.keys(item.args).forEach((name) => {
        const built = buildField(name, item.args[name]);
        fields.push(built);
        grid.appendChild(built.wrap);
      });
      card.appendChild(grid);
    }

    const output = el('div', { class: 'inline-result' });
    const button = el('button', { class: 'run', text: '実行' });

    button.addEventListener('click', async () => {
      let args;
      try {
        args = {};
        fields.forEach((f) => {
          const value = readField(f.spec, f.input);
          if (value !== undefined) { args[f.name] = value; }
        });
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = e.message;
        return;
      }
      const label = moduleName + '.' + item.m;
      button.disabled = true;
      button.textContent = '実行中…';
      output.className = 'inline-result';
      output.textContent = '…';
      try {
        const value = await callBridge(moduleName, item.m, fields.length ? args : undefined);
        const condensed = condense(value);
        output.className = 'inline-result ok';
        output.textContent = format(condensed);
        showResult(label, condensed, false);
        maybeShowImage(card, value);
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = (e.code ? '[' + e.code + '] ' : '') + e.message;
        showResult(label, (e.code ? '[' + e.code + '] ' : '') + e.message, true);
      } finally {
        button.disabled = false;
        button.textContent = '実行';
      }
    });

    card.appendChild(el('div', { class: 'actions' }, [button]));
    card.appendChild(output);
    return card;
  }

  /** dataUrl が返ってきたら画像として見せる（キャプチャやアイコン用）。 */
  function maybeShowImage(card, value) {
    const url = value && typeof value === 'object' ? value.dataUrl : null;
    if (!url) { return; }
    let img = card.querySelector('img.preview');
    if (!img) {
      img = el('img', { class: 'preview' });
      img.style.marginTop = '10px';
      img.style.maxWidth = '100%';
      img.style.borderRadius = '9px';
      card.appendChild(img);
    }
    img.src = url;
  }

  async function callBridge(moduleName, method, args) {
    if (!online) {
      throw new Error('Android アプリ内でのみ動きます（さきいかビルダーでビルドしてください）');
    }
    const mod = bridge[moduleName];
    if (!mod) {
      throw new Error('モジュール ' + moduleName + ' はこのビルドで無効です');
    }
    return mod[method](args);
  }

  // ------------------------------------------------------ ファイルブラウザー

  function buildFileBrowser() {
    const card = el('div', { class: 'card' });
    card.appendChild(el('div', { class: 'card-head' }, [
      el('strong', { text: 'ファイルブラウザー' }),
      el('code', { text: 'fs.list / fs.read' })
    ]));
    card.appendChild(el('p', {
      class: 'card-desc',
      text: 'いま許されている範囲を実際に歩けます。フォルダーを押すと降りて、ファイルを押すと中身を読みます。'
    }));

    const pathLine = el('div', { class: 'fb-path' });
    const list = el('div', { class: 'fb-list' });
    const output = el('div', { class: 'inline-result' });

    let current = '';

    const up = el('button', { class: 'ghost small', text: '↑ 上へ' });
    const refresh = el('button', { class: 'ghost small', text: '再読み込み' });
    const chooseRoot = el('button', { class: 'ghost small', text: 'フォルダーを選ぶ' });

    up.addEventListener('click', () => {
      const parts = current.split('/').filter(Boolean);
      parts.pop();
      go(parts.join('/'));
    });
    refresh.addEventListener('click', () => go(current));
    chooseRoot.addEventListener('click', async () => {
      try {
        const r = await callBridge('fs', 'chooseRoot');
        showResult('fs.chooseRoot', r, false);
        go('');
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = e.message;
      }
    });

    async function go(path) {
      current = path || '';
      pathLine.textContent = '/' + current;
      list.textContent = '';
      output.textContent = '';
      try {
        const res = await callBridge('fs', 'list', { path: current });
        if (!res.entries.length) {
          list.appendChild(el('p', { class: 'card-desc', text: '（空のフォルダー）' }));
        }
        res.entries
          .slice()
          .sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name, 'ja'))
          .forEach((entry) => {
            const row = el('button', { class: 'fb-row' }, [
              el('span', { class: 'icon', text: entry.isDir ? '📁' : '📄' }),
              el('span', { class: 'name', text: entry.name }),
              el('span', { class: 'size', text: entry.isDir ? '' : humanSize(entry.size) })
            ]);
            row.addEventListener('click', () => {
              const next = current ? current + '/' + entry.name : entry.name;
              if (entry.isDir) { go(next); } else { peek(next); }
            });
            list.appendChild(row);
          });
      } catch (e) {
        list.appendChild(el('p', { class: 'card-desc', text: (e.code ? '[' + e.code + '] ' : '') + e.message }));
      }
    }

    async function peek(path) {
      output.className = 'inline-result';
      output.textContent = '読み込み中…';
      try {
        const stat = await callBridge('fs', 'stat', { path });
        if (stat.size > 400000) {
          output.className = 'inline-result err';
          output.textContent = '大きすぎるので表示しません（' + humanSize(stat.size) + '）';
          return;
        }
        const text = await callBridge('fs', 'read', { path });
        output.className = 'inline-result ok';
        output.textContent = String(text).slice(0, 4000);
        showResult('fs.read ' + path, stat, false);
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = (e.code ? '[' + e.code + '] ' : '') + e.message;
      }
    }

    card.appendChild(el('div', { class: 'actions' }, [up, refresh, chooseRoot]));
    card.appendChild(pathLine);
    card.appendChild(list);
    card.appendChild(output);
    if (online) { go(''); }
    return card;
  }

  function humanSize(bytes) {
    if (bytes == null || bytes < 0) { return ''; }
    const units = ['B', 'KB', 'MB', 'GB'];
    let value = bytes;
    let i = 0;
    while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
    return (i === 0 ? value : value.toFixed(1)) + ' ' + units[i];
  }

  // -------------------------------------------------- リフレクション実験台

  const REFLECT_PRESETS = [
    {
      name: 'Android のバージョン定数を読む',
      form: { op: 'getStatic', cls: 'android.os.Build$VERSION', member: 'RELEASE', args: '[]' }
    },
    {
      name: 'システムサービス（Vibrator）を掴む',
      form: { op: 'service', cls: '', member: 'vibrator', args: '[]' }
    },
    {
      name: 'WindowManager に何があるか一覧',
      form: { op: 'describe', cls: 'android.view.WindowManager', member: '', args: '[]' }
    },
    {
      name: 'Math.max(3, 9) を呼ぶ',
      form: { op: 'staticCall', cls: 'java.lang.Math', member: 'max', args: '[{"type":"int","value":3},{"type":"int","value":9}]' }
    },
    {
      name: 'File を作って絶対パスを聞く',
      form: { op: 'new', cls: 'java.io.File', member: '', args: '["/storage/emulated/0"]' }
    },
    {
      name: 'Settings.Secure.ANDROID_ID の値',
      form: { op: 'getStatic', cls: 'android.provider.Settings$Secure', member: 'ANDROID_ID', args: '[]' }
    }
  ];

  function buildReflectPanel() {
    const panel = el('div', { class: 'panel', id: 'panel-reflect' });
    panel.appendChild(el('p', {
      class: 'panel-intro',
      text: 'Java のリフレクション越しに、Android のどの API でも直接呼べます。'
        + 'JSON にできない戻り値はハンドル {"__ref": n} になり、そのまま次の呼び出しの ref に渡せます。'
    }));

    const card = el('div', { class: 'card' });
    card.appendChild(el('div', { class: 'card-head' }, [
      el('strong', { text: '呼び出し' }),
      el('code', { text: 'Android.reflect.*' })
    ]));

    const opField = buildField('op', {
      kind: 'select',
      label: '操作',
      options: ['staticCall', 'call', 'new', 'getStatic', 'get', 'setStatic', 'set',
        'service', 'describe', 'enumConstants', 'context', 'activity', 'handles', 'releaseAll'],
      value: 'staticCall'
    });
    const clsField = buildField('class', { kind: 'text', label: 'クラス名', value: 'java.lang.Math' });
    const memberField = buildField('member', { kind: 'text', label: 'メソッド / フィールド / サービス名', value: 'max' });
    const refField = buildField('ref', { kind: 'number', label: 'ハンドル番号 (call/get 用)', value: '' });
    const argsField = buildField('args', {
      kind: 'json',
      label: '引数（配列。型を固定するなら {"type":"int","value":3}）',
      value: '[{"type":"int","value":3},{"type":"int","value":9}]'
    });
    const uiField = buildField('onUiThread', { kind: 'check', label: 'UI スレッドで実行する', value: false });

    const grid = el('div', { class: 'fields' });
    [opField, clsField, memberField, refField, argsField, uiField]
      .forEach((f) => grid.appendChild(f.wrap));
    card.appendChild(grid);

    const output = el('div', { class: 'inline-result' });
    const run = el('button', { class: 'run', text: '実行' });

    run.addEventListener('click', async () => {
      const op = opField.input.value;
      let args;
      try {
        args = JSON.parse(argsField.input.value || '[]');
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = '引数の JSON が読めません: ' + e.message;
        return;
      }
      const payload = { onUiThread: uiField.input.checked };
      const cls = clsField.input.value.trim();
      const member = memberField.input.value.trim();
      const ref = refField.input.value === '' ? null : Number(refField.input.value);

      if (cls) { payload.class = cls; }
      if (ref !== null) { payload.ref = ref; }
      if (Array.isArray(args) && args.length) { payload.args = args; }

      if (op === 'staticCall' || op === 'call') { payload.method = member; }
      else if (op === 'getStatic' || op === 'get' || op === 'setStatic' || op === 'set') { payload.field = member; }
      else if (op === 'service') { payload.name = member; }

      if (op === 'setStatic' || op === 'set') {
        payload.value = Array.isArray(args) ? args[0] : args;
        delete payload.args;
      }

      run.disabled = true;
      output.className = 'inline-result';
      output.textContent = '…';
      try {
        const value = await callBridge('reflect', op, payload);
        output.className = 'inline-result ok';
        output.textContent = format(value);
        showResult('reflect.' + op, value, false);
        // 返ってきたハンドルはすぐ次の呼び出しに使えるようにしておく。
        if (value && typeof value === 'object' && value.__ref != null) {
          refField.input.value = value.__ref;
        }
      } catch (e) {
        output.className = 'inline-result err';
        output.textContent = (e.code ? '[' + e.code + '] ' : '') + e.message;
        showResult('reflect.' + op, e.message, true);
      } finally {
        run.disabled = false;
      }
    });

    card.appendChild(el('div', { class: 'actions' }, [run]));
    card.appendChild(output);
    panel.appendChild(card);

    const presets = el('div', { class: 'card' });
    presets.appendChild(el('div', { class: 'card-head' }, [el('strong', { text: 'すぐ試せる例' })]));
    presets.appendChild(el('p', { class: 'card-desc', text: '押すと上のフォームに値が入ります。' }));
    const buttons = el('div', { class: 'actions' });
    REFLECT_PRESETS.forEach((preset) => {
      const b = el('button', { class: 'ghost small', text: preset.name });
      b.addEventListener('click', () => {
        opField.input.value = preset.form.op;
        clsField.input.value = preset.form.cls;
        memberField.input.value = preset.form.member;
        argsField.input.value = preset.form.args;
        refField.input.value = '';
        window.scrollTo({ top: card.offsetTop - 120, behavior: 'smooth' });
      });
      buttons.appendChild(b);
    });
    presets.appendChild(buttons);
    panel.appendChild(presets);
    return panel;
  }

  // -------------------------------------------------------------- 組み立て

  const tabs = $('#tabs');
  const panels = $('#panels');
  const panelNodes = {};

  function addTab(key, label, node) {
    const button = el('button', { type: 'button', role: 'tab', text: label });
    button.setAttribute('aria-selected', 'false');
    button.addEventListener('click', () => select(key));
    tabs.appendChild(button);
    panels.appendChild(node);
    panelNodes[key] = { button, node };
  }

  function select(key) {
    Object.keys(panelNodes).forEach((k) => {
      const entry = panelNodes[k];
      const on = k === key;
      entry.button.setAttribute('aria-selected', on ? 'true' : 'false');
      entry.node.classList.toggle('active', on);
    });
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
  }

  SPEC.forEach((group) => {
    const panel = el('div', { class: 'panel', id: 'panel-' + group.module });
    if (group.intro) { panel.appendChild(el('p', { class: 'panel-intro', text: group.intro })); }
    const enabled = !online || !!bridge[group.module];
    if (online && !enabled) {
      panel.appendChild(el('div', {
        class: 'warn',
        text: 'このビルドでは ' + group.module + ' モジュールが無効です。さきいかビルダーで有効にして再ビルドしてください。'
      }));
    }
    if (group.browser) { panel.appendChild(buildFileBrowser()); }
    group.items.forEach((item) => panel.appendChild(buildCard(group.module, item)));
    addTab(group.module, group.icon + ' ' + group.label, panel);
  });

  addTab('reflect', '🧪 リフレクション', buildReflectPanel());

  // ---------------------------------------------------------- ヘッダー表示

  const buildline = $('#buildline');
  if (online) {
    const b = bridge.build || {};
    buildline.textContent = [
      b.appName,
      'v' + b.versionName,
      b.packageName,
      'ファイル: ' + (b.fileAccess || '?'),
      'モジュール ' + Object.keys(bridge.modules || {}).length + ' 個'
    ].join(' / ');
  } else {
    buildline.textContent = 'ブラウザーで表示中 — ネイティブ機能は使えません';
    document.body.insertBefore(el('div', {
      class: 'warn',
      text: 'これは Android アプリとしてビルドしたときに動くデモです。'
        + 'いまはブラウザーで開いているため、実行ボタンはエラーになります。'
    }), $('#tabs').nextSibling);
  }

  // ------------------------------------------------------------ テーマ切替

  const themeButton = $('#themeToggle');

  function paint(dark) {
    document.body.classList.toggle('dark', dark);
    themeButton.textContent = dark ? '☀️' : '🌙';
  }

  async function initTheme() {
    let dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (online && bridge.ui) {
      try { dark = await bridge.ui.isDark(); } catch (e) { /* 既定値のまま */ }
    }
    paint(dark);
  }

  themeButton.addEventListener('click', async () => {
    const dark = !document.body.classList.contains('dark');
    paint(dark);
    if (online && bridge.ui) {
      // ステータスバーとナビゲーションバーも一緒に切り替える。
      try { await bridge.ui.setDark({ dark }); } catch (e) { /* 無視 */ }
    }
  });

  // 端末のダークモード設定が変わったら追随する。
  if (online) {
    bridge.on('app.configChanged', (payload) => {
      if (bridge.build && bridge.build.theme !== 'auto') { return; }
      if (typeof payload.systemDark === 'boolean') { paint(payload.systemDark); }
    });
  }

  initTheme();
  select(SPEC[0].module);
})();
