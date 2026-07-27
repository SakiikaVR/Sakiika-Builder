# JavaScript API リファレンス

さきいかビルダーで作ったアプリの中では、`window.Android` から Android の機能を呼べます。

ビルド時に `bridge.js` が `assets/__sakiika/bridge.js` として書き出され、**各 HTML の
`<head>` に `<script>` タグが自動挿入されます**。自分で読み込む必要はありません。

すべてのメソッドは Promise を返します。同期版が必要なら `Android.sync.<module>.<method>()`
を使えますが、呼び出し中は JS が止まります。

```html
<script>
  // ブラウザーで開いたときに落ちないよう、存在チェックを入れておくと安全です
  if (window.Android && Android.available) {
    await Android.ui.toast({ text: "こんにちは" });
    const info = await Android.sys.info();
    console.log(info.model, info.sdkInt);
  }
</script>
```

## 共通の仕組み

| プロパティ / メソッド | 内容 |
|---|---|
| `Android.available` | 生成アプリの中なら `true`。ブラウザーでは `false` |
| `Android.build` | アプリ名・パッケージ名・バージョン・ファイルアクセスレベル・宣言済み権限 |
| `Android.modules` | このビルドに含まれるモジュールとメソッド名の一覧 |
| `Android.sync.<module>.<method>()` | 同期版(JS が止まります) |
| `Android.call(module, method, args)` | 一覧に無いメソッドを直接呼ぶ |
| `Android.on(channel, fn)` | イベント購読。戻り値は解除用の関数 |
| `Android.once(channel, fn)` | 1 回だけ受け取って解除 |
| `Android.off(channel, fn?)` | 解除(`fn` 省略でそのチャンネル全部) |
| `Android.waitFor(channel, timeoutMs)` | 次のイベントを Promise で待つ |
| `Android.activeChannels()` | 購読中のチャンネル一覧 |

失敗すると Promise が reject され、`err.code` に理由の種別が入ります。

| `err.code` | 意味 |
|---|---|
| `bad_request` / `bad_args` | 引数が足りない・形式が違う |
| `permission_denied` | 権限が許可されていない |
| `unsupported` | この端末では使えない機能 |
| `disabled` | ビルド設定で無効にされている |
| `wrong_level` | ファイルアクセスレベルが足りない |
| `no_root` | フォルダーがまだ選ばれていない |
| `not_found` / `too_large` / `is_a_dir` | ファイル操作の失敗 |
| `timeout` / `interrupted` | 待機の打ち切り |
| `unknown_module` / `unknown_method` | 名前の間違い、または無効なモジュール |

---

## `Android.sys` — 端末とアプリ

| メソッド | 内容 |
|---|---|
| `info()` | メーカー・機種・Android バージョン・ABI・エミュレータ判定 |
| `build()` | ROM のフィンガープリントとセキュリティパッチ |
| `screen()` | 解像度・密度・リフレッシュレート・向き・システムのダークモード |
| `battery()` | 残量・充電状態・電圧・温度・省電力モード |
| `locale()` | 言語・国・タイムゾーン・24 時間表示か |
| `memory()` | 端末と JVM の空き容量 |
| `storage()` | 内部/共有ストレージの容量とアプリ用パス |
| `features()` | カメラ・GPS・NFC・各種センサーの有無 |
| `uptime()` | 起動からの経過時間と現在時刻 |
| `androidId()` | 端末ごとの識別子(工場出荷リセットで変わります) |
| `app()` | パッケージ名・バージョン・インストール日時 |

## `Android.ui` — 画面と手触り

| メソッド | 引数の例 |
|---|---|
| `toast({text, duration})` | `duration` は `short` / `long` |
| `vibrate({ms, amplitude})` / `vibrate({pattern, repeat})` | `pattern` は待ち・振動を交互に並べた ms の配列 |
| `isDark()` / `setDark({dark})` | ステータスバーとナビゲーションバーも切り替わります |
| `setBarColor({status, navigation, lightIcons})` | |
| `setFullscreen({on})` / `keepScreenOn({on})` | |
| `getBrightness()` / `setBrightness({value})` | `value` は 0〜1、`-1` でシステム追従 |
| `setOrientation({mode})` | `portrait` / `landscape` / `sensor` / `locked` / `unspecified` |
| `share({text, subject, uri, mime, title})` | 共有シートを開きます |
| `alert` / `confirm` / `prompt` / `pick` | OS 標準のダイアログ。結果を待てます |
| `setTitle({title})` / `reload()` / `exit()` | |

## `Android.perm` — 権限

| メソッド | 内容 |
|---|---|
| `declared()` | マニフェストにある権限と、いま許可されているか |
| `check({permissions})` | 短縮名(`CAMERA`)でも完全名でも可 |
| `request({permissions, timeoutMs})` | OS のダイアログを出して結果を待つ |
| `shouldExplain({permissions})` | 一度断られたが完全拒否ではない状態の判定 |
| `specialState()` | 全ファイルアクセス・重ね表示・通知など、ダイアログでは取れないものの状態 |
| `openSpecial({kind})` | 上記の設定画面を開いて、戻ったら状態を返す |
| `openSettings()` | このアプリの設定画面 |

`openSpecial` の `kind`: `allFiles` / `overlay` / `writeSettings` /
`batteryOptimization` / `usageStats` / `notifications` / `exactAlarm` / `installUnknownApps`

> ビルド時に選んだ権限だけが要求できます。宣言していない権限は必ず失敗します。

## `Android.fs` — ファイル

アクセスレベルの範囲内でだけ動きます。パスはレベルによって意味が変わります。

- `app_private` … `files/` `cache/` `external/` から始まる相対パス
- `folder_pick` / `documents` … 選んだフォルダーからの相対パス
- `full_manager` … `/storage/emulated/0/...` などの絶対パス、`shared/` も使えます

| メソッド | 内容 |
|---|---|
| `level()` / `roots()` / `root()` | 使える範囲と起点の確認 |
| `chooseRoot()` / `forgetRoot()` | フォルダーを選んでもらう(選択は次回起動後も保持) |
| `list({path, hidden})` / `tree({path, depth})` | 一覧・再帰的な構造 |
| `stat({path})` / `exists({path})` / `du({path, depth})` | |
| `read({path, encoding})` / `readBase64({path})` | `encoding` は `utf8` / `shift_jis` / `base64` など |
| `write({path, data, encoding})` / `append(...)` | |
| `mkdir` / `delete({path, recursive})` / `rename({path, name})` | |
| `copy({from, to})` / `move({from, to})` | |
| `search({path, name, depth, limit})` | 名前の部分一致 |
| `pickFile` / `pickFiles` / `pickFolder` / `createFile` | OS のピッカー |
| `readUri({uri})` / `writeUri({uri, data})` | ピッカーで得た `content://` を読み書き |
| `media({type, limit})` | MediaStore から画像・動画・音声・ダウンロードを列挙 |
| `shareFile({path})` | 他アプリにファイルを渡す |

## `Android.prefs` / `Android.clipboard`

| メソッド | 内容 |
|---|---|
| `prefs.set({key, value})` / `get({key, fallback})` | 数値やオブジェクトも型を保って往復します |
| `prefs.has` / `keys` / `all` / `remove` / `clear` | |
| `clipboard.write({text, label})` / `read()` / `hasText()` / `clear()` | `file://` では DOM の API が使えないため |

## `Android.net` — 通信

| メソッド | 内容 |
|---|---|
| `status()` | 回線種別・従量制か・実効帯域・DNS |
| `wifi()` / `telephony()` / `interfaces()` | |
| `request({url, method, headers, body, responseType})` | **CORS の制約を受けません** |
| `download({url, path})` | ファイルに直接保存し、途中経過を `net.progress` で流します |
| `resolve({host})` / `ping({host, timeoutMs})` | |

> `file://` のページは origin が null なので、外部への `fetch()` はほぼ塞がれます。
> `net.request` は Java 側で通信するため、その制約を受けません。

## `Android.intent` — アプリ連携

| メソッド | 内容 |
|---|---|
| `start(...)` / `startForResult(...)` | 任意の Intent。`action` `uri` `mime` `extras` `flags` を指定 |
| `broadcast(...)` / `startService(...)` | |
| `canHandle(...)` / `resolveAll(...)` | 受け取れるアプリを調べる |
| `openUrl` / `dial` / `call` / `sms` / `email` | |
| `openSettings({action})` / `openApp({package})` / `openStore({package})` | |
| `pickContact()` / `addCalendarEvent(...)` / `setAlarm(...)` / `openTimer(...)` | |
| `parseUri({uri})` | `intent:` URI を解析 |

`extras` は素の JSON でも書けますが、受け取り側が型に厳しいときは
`{"type": "long", "value": 1234}` のように明示できます。

## `Android.sensor` — センサー

```js
const stop = Android.on("sensor.accelerometer", e => console.log(e.x, e.y, e.z));
await Android.sensor.start({ type: "accelerometer", rate: "ui", intervalMs: 200 });
// …
stop();
await Android.sensor.stop({ type: "accelerometer" });
```

| メソッド | 内容 |
|---|---|
| `list()` | 搭載センサー(メーカー・分解能・消費電流つき) |
| `read({type, timeoutMs})` | 購読せず 1 サンプルだけ |
| `start({type, rate, intervalMs})` / `stop({type})` / `stopAll()` / `active()` | |

`type`: `accelerometer` `gyroscope` `magnetometer` `light` `proximity` `pressure`
`gravity` `linearAcceleration` `rotationVector` `stepCounter` `stepDetector`
`temperature` `humidity` `heartRate` `gameRotationVector` `significantMotion`

## `Android.location` — 位置情報

| メソッド | 内容 |
|---|---|
| `isEnabled()` / `providers()` | |
| `last()` | キャッシュされた位置(即返ります) |
| `current({timeoutMs, provider})` | 新しい測位を待つ |
| `watch({minTimeMs, minDistanceM})` / `stopWatch()` | `location.update` に流れます |
| `distance({lat1, lon1, lat2, lon2})` | 測地線での距離と方位 |

初回呼び出し時に権限ダイアログを自動で出します。

## `Android.media` — カメラ・マイク・音

| メソッド | 内容 |
|---|---|
| `cameras()` / `torch({on, cameraId})` | 前面/背面・フラッシュ |
| `capturePhoto({thumbnail})` / `captureVideo({maxSeconds})` | カメラアプリを開いて結果を受け取る |
| `startRecording()` / `stopRecording({asBase64})` | m4a で保存 |
| `play({path, volume, loop})` / `stop()` / `beep({ms, volume})` | |
| `speak({text, locale, rate, pitch})` / `stopSpeak()` / `voices()` | 端末の音声合成 |
| `volume()` / `setVolume({stream, percent, showUi})` | |
| `snapshot({quality})` | WebView の表示を PNG の data URL に |
| `scanBarcode({mode})` | 外部のスキャナーアプリに委譲 |

## `Android.notify` — 通知

| メソッド | 内容 |
|---|---|
| `enabled()` | 通知が有効か |
| `show({id, title, text, bigText})` | Android 13+ では初回に権限を自動要求 |
| `progress({id, max, value, indeterminate})` | 同じ ID で呼び直すと進みます |
| `cancel({id})` / `cancelAll()` / `active()` | |
| `channels()` / `createChannel(...)` / `deleteChannel(...)` | |

## `Android.content` — ContentResolver

連絡先・SMS・通話履歴・カレンダー・メディア・システム設定は、すべて ContentProvider です。

| メソッド | 内容 |
|---|---|
| `shortcuts()` | `contacts` `smsInbox` `calls` `events` `images` など省略名の一覧 |
| `query({uri, projection, selection, args, sort, limit, offset})` | 汎用クエリ |
| `insert` / `update` / `delete` | |
| `contacts({search, limit})` | 名前と電話番号を結合して返す整形済み版 |
| `type({uri})` / `settingsGet({namespace, key})` / `settingsList({namespace})` | |

## `Android.pkg` — 他のアプリ

| メソッド | 内容 |
|---|---|
| `list({search, system, launchableOnly, limit})` | Android 11+ では `QUERY_ALL_PACKAGES` が必要 |
| `info({package})` / `icon({package, size})` / `isInstalled({package})` | アイコンは PNG の data URL |
| `launch` / `openDetails` / `uninstall` / `install({path})` / `self()` | |

## `Android.biometric` — 生体認証

| メソッド | 内容 |
|---|---|
| `available()` | ハードの有無と登録状況 |
| `deviceSecure()` | 画面ロックが設定されているか |
| `authenticate({title, subtitle, description, cancel})` | 指紋・顔で確認 |

> ページ内のロック解除に使うものです。端末そのものの保護ではありません。

## `Android.reflect` — 任意の Android API

curated なモジュールに無いものは、Java リフレクション越しに直接呼べます。

```js
// システムサービスを掴んで生の API を呼ぶ
const vib = await Android.reflect.service({ name: "vibrator" });
await Android.reflect.call({
  ref: vib.__ref, method: "vibrate",
  args: [{ type: "long", value: 50 }]
});

// static フィールドを読む
const release = await Android.reflect.getStatic({
  class: "android.os.Build$VERSION", field: "RELEASE"
});

// 何が呼べるか調べる
const api = await Android.reflect.describe({
  class: "android.view.WindowManager", filter: "display"
});
```

| メソッド | 内容 |
|---|---|
| `staticCall({class, method, args})` / `call({ref, method, args})` | |
| `new({class, args})` | インスタンス生成 |
| `getStatic` / `get` / `setStatic` / `set` | フィールドの読み書き |
| `service({name})` / `context()` / `activity()` | よく使う起点 |
| `describe({class \| ref, filter, inherited})` | メソッド・フィールドの一覧 |
| `classOf` / `instanceOf` / `enumConstants` / `arrayOf` / `toStringOf` | |
| `handles()` / `release({ref})` / `releaseAll()` | ハンドルの管理 |

JSON にできない戻り値は `{"__ref": 7, "class": "...", "toString": "..."}` というハンドルになり、
そのまま次の呼び出しの `ref` に渡せます。不要になったら `release` してください。

引数は素の JSON でも書けますが、オーバーロードを確実に選ぶなら
`{"type": "long", "value": 50}` のように型を明示します。型名には `int` `long` `float`
`double` `boolean` `char` `byte` `short` と完全修飾クラス名が使えます。
UI スレッドで動かす必要がある API には `onUiThread: true` を付けます。

リフレクションはビルド設定でオフにできます。オフにすると `reflect` モジュールが
アプリに入りません。

---

## イベントのチャンネル

| チャンネル | 発生タイミング |
|---|---|
| `sensor.<種別>` | センサーの値。`sensor.<種別>.accuracy` で精度変化 |
| `location.update` / `location.provider` | 位置の更新 / プロバイダーの有効・無効 |
| `net.progress` | `net.download` の途中経過 |
| `app.resume` / `app.pause` | アプリの前面・背面 |
| `app.pageLoaded` | ページの読み込み完了 |
| `app.configChanged` | ダークモードや画面の向きの変化 |
| `perm.result` | 権限ダイアログの結果 |
| `media.completed` | `media.play` の再生終了 |
| `biometric.failed` | 認証の試行が失敗(まだ再試行できる状態) |

`window` には `sakiika-ready` イベントも飛びます。ブリッジの用意ができた合図です。
