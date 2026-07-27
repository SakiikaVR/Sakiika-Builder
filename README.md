# さきいかビルダー

HTML フォルダーを指定するだけで、Android アプリ（署名済み APK）を作る Windows 向けツールです。

**利用者の PC に Java・Android SDK・Gradle は要りません。** コンパイル済みの Android
ランタイムを実行ファイルに埋め込んであり、マニフェスト生成から署名までをすべて
内蔵の処理で行います。**ビルドは数十ミリ秒**で終わります。

- **APK と AAB の両方に対応**（AAB は Google Play へのアップロード用）
- できるアプリは Java 製の WebView アプリで、JavaScript から Android API に触れます
- リフレクション経由で**任意の Android API** を JS から呼べます
- 権限は選択式。ファイルアクセスは「オフ」から「ファイルマネージャー相当」まで 6 段階
- スプラッシュスクリーンの無効化に対応

```
┌──────────────────────┐        ┌──────────────────────────────┐        ┌──────────────┐
│ さきいかビルダー      │  JSON  │ sakiika.exe                  │  APK   │ Android 端末 │
│ (WinUI 3 / C#)       │ ─────► │ ランタイム内蔵・外部ツール不要 │ ─────► │              │
└──────────────────────┘        └──────────────────────────────┘        └──────────────┘
```

---

## 1. 必要なもの

**アプリを作る人（利用者）**

| もの | 必要か |
|---|---|
| Java / JDK | 不要 |
| Android SDK / Android Studio | 不要 |
| Gradle | 不要 |
| adb | 任意（USB インストールに使うだけ。APK を手でコピーしてもよい） |

`sakiika doctor` で状態を確認できます。

**さきいかビルダー自体を作る人（開発者）**

| もの | 用途 |
|---|---|
| Rust (stable) | エンジンのビルド |
| .NET SDK 9 | GUI のビルド |
| Android SDK build-tools 34+ / platforms | **ランタイムテンプレートの生成のみ** |
| JDK 17 以上 | 同上 |

---

## 2. ビルド

```powershell
cd sakiika
.\build.ps1              # エンジン + GUI（テンプレートが無ければ自動で生成）
.\build.ps1 -Template    # テンプレートも作り直す（SDK と JDK が必要）
.\build.ps1 -EngineOnly  # エンジンだけ
.\build.ps1 -Demo        # デモアプリの APK も作る
```

できあがるもの:

- `target\release\sakiika.exe` — ビルドエンジン（CLI）。ランタイム内蔵で単体動作します
- `ui\SakiikaBuilder\bin\x64\Release\net9.0-windows10.0.19041.0\...\SakiikaBuilder.exe` — GUI
- GUI の出力フォルダーには `sakiika.exe` が一緒にコピーされます。この 2 つを同じフォルダーに置けばどこでも動きます

---

## 3. 仕組み

重い処理は**リリースごとに 1 回だけ**行い、利用者のビルドからは完全に取り除いてあります。

### 開発時に 1 回（`sakiika devtemplate`）

```
aapt2 compile  res/**                -> コンパイル済みリソース
aapt2 link     + テンプレートマニフェスト -> resources.arsc + R.java
javac          ランタイム全体           -> .class
d8             .class                 -> classes.dex
             ↓
   core/prebuilt/template.apk（約 100 KB）＋ template-ids.json
             ↓
   cargo build で実行ファイルに埋め込み
```

### 利用者のビルド（数十ミリ秒）

```
1. 埋め込みテンプレートを展開
2. AndroidManifest.xml を生成      … APK はバイナリ AXML、AAB は protobuf
3. HTML と設定を assets へ格納      … 設定は config.json として実行時に読む
4. アイコン層を差し替え             … PNG も自前で生成
5. APK を書き出して署名             … 4 バイト整列 + APK Signature Scheme v2/v3
6. AAB を書き出して署名             … protobuf リソース + JAR 署名（PKCS#7）
```

つまり `aapt2` / `javac` / `d8` / `aapt add` / `zipalign` / `apksigner` / `keytool` /
`jarsigner` の全てを内蔵実装で置き換えています。

| 置き換えた対象 | 実装 |
|---|---|
| `javac` + `d8` | プリビルド済み `classes.dex` を埋め込み |
| `aapt2`（APK のマニフェスト） | [core/src/axml.rs](core/src/axml.rs) — バイナリ AXML ライター |
| `aapt2`（AAB のマニフェスト） | [core/src/pbxml.rs](core/src/pbxml.rs) + [core/src/protobuf.rs](core/src/protobuf.rs) — protobuf ライター |
| `aapt2`（リソース） | プリビルド済み `resources.arsc` と `resources.pb` を再利用 |
| `aapt add` + `zipalign` | [core/src/apk.rs](core/src/apk.rs) — 整列付き ZIP ライター |
| `apksigner` + `keytool` | [core/src/sign.rs](core/src/sign.rs) — 純 Rust の APK v2/v3 署名 |
| `jarsigner`（AAB 用） | [core/src/jarsign.rs](core/src/jarsign.rs) — MANIFEST.MF / .SF / PKCS#7 |
| AAB の組み立て | [core/src/aab.rs](core/src/aab.rs) |
| PNG ツール | [core/src/png.rs](core/src/png.rs) — RGBA PNG エンコーダー |

検証は本物のツールで行っています。

| 確認内容 | 使ったツール | 結果 |
|---|---|---|
| APK 署名 | `apksigner verify` | v2・v3 とも検証通過 |
| APK の整列 | `zipalign -c -p 4` | 整列 OK |
| APK のマニフェスト | `aapt2 dump badging` | 正しく解釈 |
| AAB の構造 | `bundletool validate` | 通過 |
| AAB から APK を生成 | `bundletool build-apks` | 成功。生成された APK も正常 |
| AAB の署名 | `jarsigner -verify` | 検証通過（自己署名の警告のみ） |

### この方式による制約

| 項目 | 内容 |
|---|---|
| 最低 Android バージョン | **8.0（API 26）以上**。アダプティブアイコンと v2 署名の要件です |
| 署名鍵 | EC P-256（自己署名）。`.jks` は読めません。鍵は PEM で保存・再利用します |
| ウィンドウ背景色 | 描画前の一瞬だけテンプレート固定色。アプリ内の色は実行時に反映されます（スプラッシュを無効にすればこの一瞬も出ません） |
| アイコン | アダプティブアイコン。`--icon` で PNG を指定でき、指定しなければ既定のアイコンが入ります。ランチャーは 108dp のうち中央 72dp しか表示しないため、画像は 2/3 に縮めて中央寄せしています |
| モジュールの取捨選択 | 中身は共通で、実行時に有効・無効を切り替えます（サイズは数十 KB の差） |
| AAB のモジュール構成 | base モジュールのみ。Dynamic Feature Module には対応しません |

---

## 3.5 APK と AAB の使い分け

| | APK | AAB |
|---|---|---|
| 端末に直接インストール | できる | **できない** |
| Google Play へのアップロード | 新規アプリは不可 | **こちら** |
| 署名方式 | APK Signature Scheme v2 + v3 | JAR 署名（PKCS#7） |
| マニフェスト | バイナリ AXML | protobuf |
| リソーステーブル | `resources.arsc` | `resources.pb` |

AAB は「端末ごとに最適化した APK を Google Play 側で作るための入れ物」なので、
そのままではインストールできません。手元で確認したい場合は `--format both` で
両方作るか、`bundletool build-apks` で APK に変換してください。

Google Play にアップロードするときは、さきいかビルダーが作った鍵（`sakiika-key.pem`）が
**アップロード鍵**になります。Play App Signing に登録したあとは、この鍵を失うと
更新できなくなるので必ず保管してください。

---

## 4. 使い方（GUI）

`SakiikaBuilder.exe` を起動し、上から順に埋めていきます。

1. **アプリの中身** — HTML が入ったフォルダーと開始ファイル
2. **名前とバージョン** — アプリ名、パッケージ名、versionCode
3. **見た目と起動** — テーマ、画面の向き、背景色、アイコン、**スプラッシュのオン/オフ**
4. **ファイルへのアクセス** — 6 段階から選択
5. **使う権限** — 60 件以上からチェック（「必要な分を推測」で機能に応じて自動選択）
6. **JavaScript から使える機能** — モジュールの取捨選択、リフレクションのオン/オフ
7. **WebView の挙動**
8. **出力と署名**

「APK をビルド」で完成。adb があれば「ビルドして端末に入れる」も使えます。
設定は JSON として保存・読み込みできます。

---

## 5. 使い方（CLI）

```powershell
# 雛形を作る
sakiika init --web www --name "テストアプリ" --package com.example.test

# 設定ファイルからビルド
sakiika build sakiika.json

# 設定ファイルなしで一発ビルド
sakiika quick --web .\www --name "テストアプリ" --package com.example.test `
              --file-access folder_pick --permissions "INTERNET,CAMERA,VIBRATE" `
              --no-splash --icon-background "#0F9D58" --out-dir .\out

# 出力形式を選ぶ（既定は apk）
sakiika quick --web .\www --name "テストアプリ" --package com.example.test --format aab
sakiika quick --web .\www --name "テストアプリ" --package com.example.test --format both

# 一覧系
sakiika doctor            # 動作状態
sakiika permissions       # 選べる権限
sakiika modules           # JS ブリッジのモジュール
sakiika levels            # ファイルアクセスレベル
sakiika schema            # 設定ファイルの雛形 (JSON)

# 端末へ（adb があるとき）
sakiika install .\out\テストアプリ-1.0-debug.apk
```

`--json` を付けると 1 行 1 JSON で出力します（GUI がこれを読んでいます）。

開発者向け:

```powershell
sakiika devtemplate       # ランタイムテンプレートを作り直す（SDK と JDK が必要）
sakiika resign <apk>      # 既存 APK を組み直して署名し直す（署名機構の検証用）
```

---

## 6. ファイルアクセスの 6 段階

| レベル | できること | 権限 | ユーザーの操作 |
|---|---|---|---|
| `off` | 何もしない（`fs` が無効になります） | なし | なし |
| `app_private` | アプリ専用領域だけ（`files/` `cache/` `external/`） | なし | なし |
| `folder_pick` | ユーザーが選んだ 1 フォルダーの配下だけ | なし | 初回にフォルダー選択（次回以降も保持） |
| `documents` | 上記＋その場でファイル/フォルダーを追加選択 | なし | 都度選択 |
| `media_only` | 画像・動画・音声の読み取りだけ | `READ_MEDIA_*` | 実行時ダイアログ |
| `full_manager` | 全ストレージ（ファイルマネージャー相当） | `MANAGE_EXTERNAL_STORAGE` | 設定画面で手動許可 |

`folder_pick` / `documents` は SAF（Storage Access Framework）を使うので**権限が一切要りません**。
広い権限が必要なのは `media_only` と `full_manager` だけです。

`full_manager` は Google Play で用途の審査対象になります。ファイルマネージャーのような
アプリ以外では `folder_pick` を選んでください。

---

## 7. JavaScript から使える API

ビルド時に `bridge.js` が `assets/__sakiika/bridge.js` として書き出され、**各 HTML の
`<head>` に `<script>` タグが自動挿入されます**。自分で読み込む必要はありません。

すべて Promise を返します。同期版が必要なら `Android.sync.<module>.<method>()`
（呼び出し中 JS が止まります）。

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

### モジュール一覧

| モジュール | 内容 |
|---|---|
| `Android.sys` | 端末情報・ビルド指紋・画面・バッテリー・メモリ・ストレージ・ハード対応表 |
| `Android.ui` | トースト・バイブ・バー色・ダークモード・明るさ・向き・共有・ネイティブダイアログ |
| `Android.perm` | 権限の確認と要求、特殊権限の設定画面 |
| `Android.fs` | ファイル読み書き・一覧・検索・ピッカー・メディア一覧 |
| `Android.prefs` | 永続キー・バリューストア（型を保持） |
| `Android.clipboard` | クリップボードの読み書き |
| `Android.net` | 回線状態・Wi-Fi・HTTP リクエスト（CORS 制約なし）・ダウンロード・DNS |
| `Android.intent` | 任意 Intent の発行、URL/電話/SMS/メール/カレンダー/アラーム |
| `Android.sensor` | 加速度・ジャイロ・磁気・照度・近接・気圧・歩数などの購読 |
| `Android.location` | 位置の取得と追跡、2 点間距離 |
| `Android.media` | 撮影・録画・録音・再生・読み上げ・音量・ライト・画面キャプチャ |
| `Android.notify` | 通知の表示、進捗通知、チャンネル管理 |
| `Android.content` | ContentResolver 汎用クエリ（連絡先・SMS・通話履歴・カレンダー・メディア・システム設定） |
| `Android.pkg` | インストール済みアプリの列挙・起動・アイコン取得 |
| `Android.biometric` | 指紋・顔認証 |
| `Android.reflect` | **任意の Java / Android API をリフレクション経由で呼ぶ** |

各モジュールのメソッド名は `Android.modules` で実行時に確認できます。

### イベント

センサーや位置情報など、継続的に届くものはイベントで受けます。

```js
const stop = Android.on("sensor.accelerometer", e => {
  console.log(e.x, e.y, e.z);
});
await Android.sensor.start({ type: "accelerometer", intervalMs: 200 });
// …
stop();
await Android.sensor.stop({ type: "accelerometer" });
```

主なチャンネル: `sensor.<種別>` / `location.update` / `net.progress` / `app.resume` /
`app.pause` / `app.pageLoaded` / `app.configChanged` / `perm.result` / `media.completed`

`Android.once(channel, fn)` と `Android.waitFor(channel, timeoutMs)` もあります。

### リフレクション — 「すべての API」の入口

```js
// システムサービスを掴んで、生の API を呼ぶ
const vib = await Android.reflect.service({ name: "vibrator" });
await Android.reflect.call({
  ref: vib.__ref,
  method: "vibrate",
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

JSON にできない戻り値は `{"__ref": 7, "class": "...", "toString": "..."}` という
ハンドルになり、そのまま次の呼び出しの `ref` に渡せます。不要になったら
`Android.reflect.release({ref})`。

引数は素の JSON でも書けますが、オーバーロードを確実に選ぶなら
`{"type": "long", "value": 50}` のように型を明示します。UI スレッドで動かす必要が
ある API には `onUiThread: true` を付けます。

---

## 8. デモアプリ

`demo/` に、ほぼ全機能を実際に試せるアプリが入っています。

```powershell
.\build.ps1 -Demo
```

- モジュールごとのタブ、各機能が入力欄つきのカードになっていて、押すとその場で実行
- 結果は整形 JSON で表示、`dataUrl` が返るものは画像としてプレビュー
- 実際に歩けるファイルブラウザー（選んだアクセスレベルの範囲を体感できます）
- リフレクション実験台（プリセット付き）
- イベントログ（センサーや位置情報が流れてくる様子が見えます）

`demo/index.html` はブラウザーでも開けますが、その場合ネイティブ機能は使えず警告が出ます。

---

## 9. 生成される成果物の中身

APK:

```
AndroidManifest.xml          選んだ権限・向き・テーマ・FileProvider（自前生成の AXML）
resources.arsc               テンプレート由来のリソーステーブル
res/mipmap-*/…               アイコン層（差し替え済み）
classes.dex                  WebView ホスト + ブリッジ + 全モジュール
assets/…                     HTML フォルダーの中身そのまま
assets/__sakiika/bridge.js   JS 側のブリッジ
assets/__sakiika/config.json 実行時に読む設定
（署名は APK Signing Block として格納。META-INF は使いません）
```

AAB:

```
BundleConfig.pb              バンドル設定
base/manifest/AndroidManifest.xml   同じ内容を protobuf で
base/resources.pb            protobuf のリソーステーブル
base/res/…                   アイコン層（差し替え済み）
base/dex/classes.dex
base/assets/…                HTML と設定
META-INF/MANIFEST.MF         JAR 署名
META-INF/SAKIIKA.SF
META-INF/SAKIIKA.EC          PKCS#7（ECDSA P-256）
```

Java 側の構成（パッケージは `net.sakiika.runtime` 固定。アプリの ID とは独立です）:

| クラス | 役割 |
|---|---|
| `SakiikaApplication` | 起動最初期に `config.json` を読み込む |
| `MainActivity` | WebView のホスト。権限ダイアログと Intent の結果をバックグラウンドスレッドへ橋渡し |
| `Bridge` | JS から見える唯一のオブジェクト。同期 `invoke` と非同期 `invokeAsync` |
| `ApiModule` の派生 | 各モジュールの実装 |
| `Cfg` | `config.json` から読んだ設定 |
| `ShareProvider` | AndroidX を使わない最小の FileProvider（キャッシュ配下の読み取り専用） |

署名鍵は指定しなければ出力フォルダーに `sakiika-key.pem` を作り、次回以降も再利用します。
**Android は同じ証明書でないと上書き更新できないため、この鍵は消さずに保管してください。**

---

## 10. スプラッシュスクリーンの無効化について

Android 12 以降、スプラッシュ画面は OS が必ず出す仕組みになっており、完全に消す API は
ありません。オフにしたときは次の 3 つを同時に行い、実質的に見えないようにしています。

1. テーマに `windowDisablePreview=true`（Android 11 以前の起動プレビューを消す）
2. `values-v31` / `values-night-v31` で `windowSplashScreenAnimationDuration=0`
3. 起動直後に `getSplashScreen().setOnExitAnimationListener(view -> view.remove())`

---

## 11. 困ったとき

**`doctor` が「ランタイムが埋め込まれていません」と言う**
→ 配布物が壊れています。開発者なら `.\build.ps1 -Template` で作り直してください。

**minSdk を 26 未満にできない**
→ アダプティブアイコンと v2 署名の要件です。Android 8.0 未満に対応する必要がある場合、
この方式では作れません。

**ビルドは通るがアプリが真っ白**
→ 開始ファイル名を確認。`chrome://inspect` で WebView をデバッグできます
（「Chrome から遠隔デバッグする」が有効なとき）。

**`fs` が `no_root` を返す**
→ アクセスレベルが `folder_pick` / `documents` のときは、先に
`Android.fs.chooseRoot()` を呼んでフォルダーを選んでもらう必要があります。

**`content.query` が `permission_denied`**
→ そのプロバイダーに対応する権限（`READ_CONTACTS` など）をビルド時に有効にし、
`Android.perm.request` で実行時許可を取ってください。

**インストールで「アプリがインストールされていません」と出る**
→ 前に別の鍵で署名した同じパッケージ名のアプリが入っている可能性があります。
一度アンインストールしてから入れ直してください。

**`devtemplate` が SDK を見つけられない**
→ `$env:SAKIIKA_SDK` に Android SDK のルート（`build-tools` と `platforms` が
あるフォルダー）を設定してください。

**GUI のビルドで「ファイルがロックされています」**
→ 起動中の `SakiikaBuilder.exe` を閉じてから再実行してください
（`build.ps1` は自動で閉じようとします）。

---

## 12. リポジトリ構成

```
sakiika/
├── build.ps1                    まとめてビルドするスクリプト
├── core/                        Rust エンジン
│   ├── assets/
│   │   ├── icon-source.png      アイコンの元画像
│   │   ├── icon-foreground.png  アダプティブアイコンの前景（2/3 に縮めて中央寄せ）
│   │   └── icon-legacy.png      旧形式の正方アイコン
│   ├── prebuilt/
│   │   ├── template.apk         プリビルド済みランタイム（埋め込まれる）
│   │   ├── template-proto.zip   AAB 用の protobuf リソース
│   │   └── template-ids.json    リソース ID とアイコンのエントリ名
│   ├── src/
│   │   ├── main.rs              CLI
│   │   ├── fastbuild.rs         利用者向けビルド（外部ツール不要）
│   │   ├── pipeline.rs          テンプレート生成（開発者向け・SDK 必須）
│   │   ├── apk.rs               整列付き ZIP リーダー/ライター
│   │   ├── axml.rs              バイナリ AXML ライター（APK 用）
│   │   ├── pbxml.rs             protobuf XML ライター（AAB 用）
│   │   ├── protobuf.rs          protobuf の最小ライター
│   │   ├── aab.rs               App Bundle の組み立て
│   │   ├── manifest.rs          AndroidManifest の組み立て（両形式で共通）
│   │   ├── sign.rs              APK Signature Scheme v2/v3 + PKCS#7
│   │   ├── jarsign.rs           JAR 署名（AAB 用）
│   │   ├── png.rs               PNG エンコーダー
│   │   ├── project.rs           テンプレート生成物・設定・アセット
│   │   ├── config.rs            設定モデル
│   │   ├── catalog.rs           権限とモジュールのカタログ
│   │   └── toolchain.rs         SDK / JDK / adb の検出
│   └── templates/
│       ├── bridge.js            JS 側のブリッジ
│       └── java/                Android 側のソース
├── tools/make-icons.ps1         元画像から各用途のアイコンを書き出す
├── ui/SakiikaBuilder/           WinUI 3 GUI
│   └── Assets/                  GUI のヘッダー画像とウィンドウ/exe アイコン
└── demo/                        全機能を試せるデモアプリ
```

アイコンを差し替えるときは `core/assets/icon-source.png` を置き換えて
`tools\make-icons.ps1` を実行し、テンプレートを作り直します。

```powershell
.\tools\make-icons.ps1 -Source .\core\assets\icon-source.png -Root .
.\build.ps1 -Template
```
