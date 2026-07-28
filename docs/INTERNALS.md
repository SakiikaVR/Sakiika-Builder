# 内部構造

利用者の PC に Java も Android SDK も要らないのは、**重い処理をリリース時に 1 回だけ**
済ませてあるからです。ここではその作り方と、内蔵実装の中身を説明します。

## 二段構えのビルド

### 開発時に 1 回 — `sakiika devtemplate`

ここだけ Android SDK と JDK を使います。

```
aapt2 compile  res/**                    -> コンパイル済みリソース
aapt2 link     + テンプレートマニフェスト  -> resources.arsc + R.java
aapt2 link     --proto-format            -> resources.pb（AAB 用）
javac          ランタイム全体              -> .class
d8             .class                    -> classes.dex
        ↓
core/prebuilt/template.apk        リソーステーブル + res/** + classes.dex
core/prebuilt/template-proto.zip  protobuf のリソース
core/prebuilt/template-ids.json   リソース ID とアイコンのエントリ名
        ↓
cargo build --release で実行ファイルに埋め込み（include_bytes!）
```

### 利用者のビルド — 数十ミリ秒

```
1. 埋め込みテンプレートを展開
2. AndroidManifest.xml を生成      APK はバイナリ AXML、AAB は protobuf
3. HTML と設定を assets へ格納      設定は config.json として実行時に読む
4. アイコン層を差し替え             背景の単色 PNG はその場で生成
5. APK を書き出して署名             4 バイト整列 + APK Signature Scheme v2/v3
6. AAB を書き出して署名             protobuf リソース + JAR 署名（PKCS#7）
```

## 置き換えた Android SDK ツール

| 元のツール | 内蔵実装 | ファイル |
|---|---|---|
| `javac` + `d8` | プリビルド済み `classes.dex` を埋め込み | — |
| `aapt2`（APK のマニフェスト） | バイナリ AXML ライター | [`core/src/axml.rs`](../core/src/axml.rs) |
| `aapt2`（AAB のマニフェスト） | protobuf ライター | [`core/src/pbxml.rs`](../core/src/pbxml.rs) / [`core/src/protobuf.rs`](../core/src/protobuf.rs) |
| `aapt2`（リソース） | プリビルド済みテーブルを再利用 | — |
| `aapt add` + `zipalign` | 整列付き ZIP ライター | [`core/src/apk.rs`](../core/src/apk.rs) |
| `apksigner` + `keytool` | APK Signature Scheme v2/v3 | [`core/src/sign.rs`](../core/src/sign.rs) |
| `jarsigner`（AAB 用） | MANIFEST.MF / .SF / PKCS#7 | [`core/src/jarsign.rs`](../core/src/jarsign.rs) |
| PNG ツール | RGBA PNG エンコーダー | [`core/src/png.rs`](../core/src/png.rs) |

暗号は純 Rust のクレート（`p256` / `sha2` / `x509-cert`）だけを使っており、
ビルドに C コンパイラも要りません。

### 実装で引っかかった点

- **`aapt2 link -A` は Windows で ZIP 内にバックスラッシュを書く。** `assets/sub\page.html`
  のようになり、AssetManager が `/` でしか引けないためネストしたアセットが読めなくなる。
  アセットの追加は自前の ZIP ライターに任せている。
- **aapt2 は `mipmap-xxhdpi` を `mipmap-xxhdpi-v4` にリネームする。** 決め打ちのパスに書くと
  差し替えではなく重複追加になるため、実際のエントリ名を `template-ids.json` に記録している。
- **証明書の KeyUsage に `digitalSignature` が必要。** CA 用の `keyCertSign` だけだと
  `jarsigner` が「Key usage restricted」で拒否する。APK の v2 署名は KeyUsage を見ないため、
  AAB 対応で初めて露見した。自己署名の leaf 証明書として作っている。
- **Java パッケージは `net.sakiika.runtime` 固定。** dex を作り直さずに済ませるため、
  アプリの ID（マニフェストの `package`）とは独立させ、マニフェストでは完全修飾名で参照する。
  設定値も `Cfg` の定数ではなく `assets/__sakiika/config.json` から実行時に読む。

## 生成される成果物

### APK

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

### AAB

```
BundleConfig.pb
base/manifest/AndroidManifest.xml   同じ内容を protobuf で
base/resources.pb
base/res/…
base/dex/classes.dex
base/assets/…
META-INF/MANIFEST.MF                JAR 署名
META-INF/SAKIIKA.SF
META-INF/SAKIIKA.EC                 PKCS#7（ECDSA P-256）
```

## Android 側の構成

パッケージは `net.sakiika.runtime` 固定です。

| クラス | 役割 |
|---|---|
| `SakiikaApplication` | 起動最初期に `config.json` を読み込む |
| `MainActivity` | WebView のホスト。権限ダイアログと Intent の結果をバックグラウンドスレッドへ橋渡し |
| `Bridge` | JS から見える唯一のオブジェクト。同期 `invoke` と非同期 `invokeAsync` |
| `ApiModule` の派生 | 各モジュールの実装（16 個） |
| `Cfg` | `config.json` から読んだ設定 |
| `ShareProvider` | AndroidX を使わない最小の FileProvider（キャッシュ配下の読み取り専用） |

すべてのモジュールが dex に入っており、どれを JS に見せるかは実行時に決まります。
これは dex をプロジェクトごとに作り直さないための設計で、サイズの差は数十 KB です。

## この方式による制約

| 項目 | 内容 |
|---|---|
| 最低 Android バージョン | **8.0（API 26）以上**。アダプティブアイコンと v2 署名の要件 |
| 署名鍵 | EC P-256（自己署名）。`.jks` は読めず、PEM で保存・再利用。証明書も同じ PEM に保存し、ビルド間でバイト単位に同一を保つ |
| ウィンドウ背景色 | 描画前の一瞬だけテンプレート固定色。アプリ内の色は実行時に反映（スプラッシュを無効にすればこの一瞬も出ない） |
| AAB のモジュール構成 | base モジュールのみ。Dynamic Feature Module には未対応 |
| アイコン | ランチャーは 108dp のうち中央 72dp しか表示しないため、画像を 2/3 に縮めて中央寄せしている |

## スプラッシュスクリーンの無効化

半透明ウィンドウのアクティビティには、Android 12 以降のシステムスプラッシュも
旧来の起動プレビューも表示されません。そこでスプラッシュをオフにしたときは、
アクティビティのテーマをフレームワーク公開スタイル
`@android:style/Theme.Translucent.NoTitleBar`（ID `0x01030010`、全バージョンで不変）へ
差し替えます。テンプレートのリソーステーブルに手を入れずマニフェスト生成だけで済み、
スプラッシュは一切表示されなくなります。WebView の背景は実行時に設定色で塗るため、
描画前の白フラッシュも出ません。

ただし Android 8.0/8.1 は「半透明のアクティビティは向きを固定できない」仕様で
クラッシュするため、差し替えは **minSdk 28 以上、または画面の向きが固定でない**
ビルドに限ります。条件を満たさない場合は従来どおり次の 3 つで実質的に見えなくします。

1. テーマに `windowDisablePreview=true`（Android 11 以前の起動プレビューを消す）
2. `values-v31` / `values-night-v31` で `windowSplashScreenAnimationDuration=0`
3. 起動直後に `getSplashScreen().setOnExitAnimationListener(view -> view.remove())`

## アイコンを差し替える

`core/assets/icon-source.png` を置き換えて、生成スクリプトとテンプレート再生成を回します。

```powershell
.\tools\make-icons.ps1 -Source .\core\assets\icon-source.png -Root .
.\build.ps1 -Template
```

スクリプトは 5 つの用途に書き出します。

| 生成物 | 用途 |
|---|---|
| `core/assets/icon-foreground.png` | アダプティブアイコンの前景（432px、2/3 に縮めて中央寄せ） |
| `core/assets/icon-legacy.png` | 旧形式の正方アイコン（192px、API 26 以上では未使用） |
| `ui/SakiikaBuilder/Assets/icon.png` | GUI のヘッダー |
| `ui/SakiikaBuilder/Assets/icon.ico` | ウィンドウ・タスクバー・exe（16〜256px の 7 サイズ） |
| `demo/icon.png` | デモアプリのヘッダー |

余白色（アダプティブアイコンの背景）は元画像の縁の平均色を出力するので、その値を
`core/src/config.rs` の `icon_background` の既定値にしておくと継ぎ目が出ません。

## GUI のビルドについて

Windows App SDK の標準ビルドは Visual Studio 付属の MSBuild タスクで `resources.pri` を
作りますが、これは .NET SDK には含まれません。`resources.pri` が無いと WinUI が自分の
テーマリソースを解決できず起動時に落ちるため、Windows SDK の `makepri.exe` を直接呼ぶ
生成処理を [`ui/SakiikaBuilder/tools/make-pri.ps1`](../ui/SakiikaBuilder/tools/make-pri.ps1)
に用意し、ビルドに組み込んでいます。

出力フォルダーをそのまま索引付けすると、`Microsoft.UI.Xaml.Controls.pri` が宣言している
リソースと同名の実体 PNG が衝突します。そのためステージング用フォルダーに「アプリ自身の
`.xbf`」と「参照している `.pri`」だけを集めてから索引付けしています。

ヘッダー画像とウィンドウアイコンは `ms-appx:///` ではなくファイルパスから読み込みます。
アンパッケージのアプリで `ms-appx` を解決するには PRI が必要で、その PRI を自前生成して
いる以上、画像の読み込みを PRI から独立させておくほうが確実だからです。

## リポジトリ構成

```
Sakiika-Builder/
├── build.ps1                    まとめてビルドするスクリプト
├── tools/make-icons.ps1         元画像から各用途のアイコンを書き出す
├── assets/                      README 用のロゴとスクリーンショット
├── docs/
│   ├── JS-API.md                JavaScript API リファレンス
│   └── INTERNALS.md             このファイル
├── core/                        Rust エンジン
│   ├── assets/                  アイコンの元画像と派生画像
│   ├── prebuilt/                プリビルド済みランタイム（埋め込まれる）
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
├── ui/SakiikaBuilder/           WinUI 3 GUI
└── demo/                        全機能を試せるデモアプリ
```

## 検証方法

自前実装が本物として通用するかは、実際のツールで確認しています。

```powershell
$bt = "C:\AndroidSDK\build-tools\35.0.0"

# APK
& "$bt\apksigner.bat" verify --verbose .\out\app.apk    # v2/v3 とも true になること
& "$bt\zipalign.exe" -c -v -p 4 .\out\app.apk           # Verification succesful
& "$bt\aapt2.exe" dump badging .\out\app.apk            # パッケージ名・権限・ラベル
& "$bt\aapt2.exe" dump xmltree .\out\app.apk --file AndroidManifest.xml

# AAB（bundletool は https://github.com/google/bundletool/releases から）
java -jar bundletool.jar validate --bundle=.\out\app.aab
java -jar bundletool.jar build-apks --bundle=.\out\app.aab --output=out.apks --mode=universal
jarsigner -verify .\out\app.aab                         # 自己署名の警告のみが出る状態
```

`sakiika resign <apk>` は既存 APK を組み直して署名し直すコマンドで、ZIP ライターと
署名部分だけを切り離して確かめるのに使えます。
