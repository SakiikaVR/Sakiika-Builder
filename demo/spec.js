/*
 * デモの中身の定義。
 *
 * 1 つの項目が 1 つのブリッジ呼び出しに対応します。app.js がこの定義から
 * 入力フォームと実行ボタンを自動生成するので、機能を増やすときはここに
 * 追記するだけで済みます。
 *
 *   m     : メソッド名（Android.<module>.<m> を呼ぶ）
 *   title : 見出し（省略時はメソッド名）
 *   desc  : 説明
 *   perm  : この機能に必要な権限（バッジ表示用）
 *   args  : 入力欄の定義。省略すると引数なしで呼ぶ
 *   note  : 実行前に出す注意書き
 */

const t = (label, value, opts) => Object.assign({ kind: 'text', label, value }, opts);
const n = (label, value, opts) => Object.assign({ kind: 'number', label, value }, opts);
const c = (label, value) => ({ kind: 'check', label, value: !!value });
const s = (label, options, value) => ({ kind: 'select', label, options, value: value || options[0] });
const j = (label, value) => ({ kind: 'json', label, value });

const SPEC = [
  // ------------------------------------------------------------------ sys
  {
    module: 'sys',
    icon: '📱',
    label: '端末',
    intro: '端末とアプリについて分かること。どれも権限なしで読めます。',
    items: [
      { m: 'info', title: '端末情報', desc: 'メーカー・機種・Android バージョン・ABI・エミュレータ判定' },
      { m: 'build', title: 'ビルド指紋', desc: 'ROM のフィンガープリントとセキュリティパッチ' },
      { m: 'screen', title: '画面', desc: '解像度・密度・リフレッシュレート・向き・システムのダークモード' },
      { m: 'battery', title: 'バッテリー', desc: '残量・充電状態・電圧・温度・省電力モード' },
      { m: 'locale', title: 'ロケール', desc: '言語・国・タイムゾーン・24時間表示かどうか' },
      { m: 'memory', title: 'メモリ', desc: '端末と JVM の空き容量' },
      { m: 'storage', title: 'ストレージ', desc: '内部/共有ストレージの容量とアプリ用パス' },
      { m: 'features', title: 'ハードウェア対応表', desc: 'カメラ・GPS・NFC・各種センサーの有無' },
      { m: 'uptime', title: '稼働時間', desc: '起動からの経過時間と現在時刻' },
      { m: 'androidId', title: 'ANDROID_ID', desc: '端末ごとの識別子（工場出荷リセットで変わります）' },
      { m: 'app', title: 'このアプリ', desc: 'パッケージ名・バージョン・インストール日時' }
    ]
  },

  // ------------------------------------------------------------------- ui
  {
    module: 'ui',
    icon: '🎨',
    label: '画面/UI',
    intro: 'ネイティブの見た目と手触り。ダークモードの切替もここです。',
    items: [
      {
        m: 'toast', title: 'トースト', desc: '画面下に短いメッセージを出します',
        args: { text: t('メッセージ', 'さきいかビルダーから、こんにちは'), duration: s('長さ', ['short', 'long']) }
      },
      {
        m: 'vibrate', title: 'バイブレーション', desc: 'ms 単位。pattern を使うと波形になります',
        perm: 'VIBRATE',
        args: { ms: n('長さ (ms)', 60), amplitude: n('強さ 1-255 (-1で既定)', -1) }
      },
      {
        m: 'vibrate', title: 'バイブ（パターン）', desc: '待ち,振動,待ち,振動… の順に ms を並べます',
        perm: 'VIBRATE',
        args: { pattern: j('パターン', '[0, 120, 80, 120, 80, 300]'), repeat: n('繰り返し開始位置 (-1で無し)', -1) }
      },
      { m: 'isDark', title: 'ダークモードか', desc: '現在の状態を取得' },
      { m: 'setDark', title: 'ダークモード切替', desc: 'ステータスバーとナビゲーションバーも一緒に変わります', args: { dark: c('ダークにする', true) } },
      {
        m: 'setBarColor', title: 'バーの色', desc: 'ステータスバー / ナビゲーションバーの色とアイコンの明暗',
        args: { status: t('ステータスバー', '#1E6FD9'), navigation: t('ナビゲーションバー', '#1E6FD9'), lightIcons: c('アイコンを白にする', true) }
      },
      { m: 'setFullscreen', title: '全画面', desc: 'バーを隠して没入モードにします', args: { on: c('全画面にする', true) } },
      { m: 'keepScreenOn', title: '画面を消さない', desc: 'スリープを抑止します', perm: 'WAKE_LOCK', args: { on: c('消さない', true) } },
      { m: 'getBrightness', title: '明るさを取得', desc: 'ウィンドウとシステムの両方' },
      { m: 'setBrightness', title: '明るさを設定', desc: '0〜1。-1 でシステム追従に戻します', args: { value: n('値', 0.6, { step: 0.05, min: -1, max: 1 }) } },
      { m: 'setOrientation', title: '画面の向き', desc: '固定したり解除したり', args: { mode: s('向き', ['portrait', 'landscape', 'sensor', 'locked', 'unspecified']) } },
      { m: 'alert', title: 'ネイティブ警告', desc: 'OS 標準のダイアログ', args: { title: t('タイトル', 'おしらせ'), message: t('本文', 'これは Android のダイアログです') } },
      { m: 'confirm', title: 'はい/いいえ', desc: '選択結果が返ります', args: { title: t('タイトル', '確認'), message: t('本文', '続けますか？'), ok: t('OK ボタン', 'はい'), cancel: t('キャンセル', 'いいえ') } },
      { m: 'prompt', title: '文字入力', desc: '入力された文字列が返ります', args: { title: t('タイトル', '名前'), message: t('説明', 'お名前をどうぞ'), value: t('初期値', '') } },
      { m: 'pick', title: 'リスト選択', desc: '選ばれた index と値が返ります', args: { title: t('タイトル', '好きなもの'), items: j('選択肢', '["さきいか", "たこわさ", "枝豆"]') } },
      { m: 'share', title: '共有シート', desc: '他のアプリにテキストを渡します', args: { text: t('本文', 'さきいかビルダーで作りました'), subject: t('件名', 'テスト'), title: t('シートの題', '共有') } },
      { m: 'setTitle', title: 'タスク名', desc: '最近使ったアプリ一覧での表示名', args: { title: t('タイトル', 'デモ実行中') } },
      { m: 'reload', title: 'ページ再読み込み', desc: 'WebView をリロードします' }
    ]
  },

  // ----------------------------------------------------------------- perm
  {
    module: 'perm',
    icon: '🔑',
    label: '権限',
    intro: 'ビルド時に選んだ権限だけが要求できます。ここに出ない権限は、さきいかビルダー側で有効にしてから再ビルドしてください。',
    items: [
      { m: 'declared', title: '宣言済みの権限', desc: 'マニフェストにある権限と、いま許可されているか' },
      { m: 'check', title: '許可状況を確認', desc: 'カンマ区切りではなく配列で渡します', args: { permissions: j('権限', '["CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION"]') } },
      { m: 'request', title: '権限を要求', desc: 'OS のダイアログが出ます', args: { permissions: j('権限', '["CAMERA"]') } },
      { m: 'shouldExplain', title: '説明が必要か', desc: '一度断られたが完全拒否ではない状態の判定', args: { permissions: j('権限', '["CAMERA"]') } },
      { m: 'specialState', title: '特殊権限の状態', desc: '全ファイルアクセス・重ね表示・通知など、ダイアログでは取れないもの' },
      {
        m: 'openSpecial', title: '特殊権限の設定画面を開く', desc: '戻ってきたら状態を返します',
        args: { kind: s('種類', ['allFiles', 'overlay', 'writeSettings', 'batteryOptimization', 'usageStats', 'notifications', 'exactAlarm', 'installUnknownApps']) }
      },
      { m: 'openSettings', title: 'アプリ設定を開く', desc: 'このアプリの設定画面へ' }
    ]
  },

  // ------------------------------------------------------------------- fs
  {
    module: 'fs',
    icon: '📁',
    label: 'ファイル',
    intro: 'ビルド時に選んだアクセスレベルの範囲でだけ動きます。下のファイルブラウザーが一番手早く確認できます。',
    browser: true,
    items: [
      { m: 'level', title: 'アクセスレベル', desc: 'このアプリに許されている範囲' },
      { m: 'roots', title: '使えるルート', desc: 'アクセスできる起点フォルダーの一覧' },
      { m: 'root', title: '現在のルート', desc: '基準になっている場所' },
      { m: 'chooseRoot', title: 'フォルダーを選ぶ', desc: 'OS のフォルダー選択画面。選んだ場所は次回起動後も覚えています', note: 'アクセスレベルが「フォルダー選択」「都度選択」のときだけ使えます' },
      { m: 'list', title: '一覧', desc: '空文字でルート直下', args: { path: t('パス', ''), hidden: c('隠しファイルも', false) } },
      { m: 'tree', title: 'ツリー', desc: '再帰的に構造を取得', args: { path: t('パス', ''), depth: n('深さ', 2) } },
      { m: 'stat', title: '詳細', desc: 'サイズ・更新日時・MIME', args: { path: t('パス', 'test.txt') } },
      { m: 'write', title: '書き込み', desc: '無ければ作ります', args: { path: t('パス', 'sakiika-test.txt'), data: t('内容', 'さきいかビルダーのテスト') } },
      { m: 'append', title: '追記', desc: '末尾に足します', args: { path: t('パス', 'sakiika-test.txt'), data: t('追記内容', '\n追記行') } },
      { m: 'read', title: '読み込み', desc: 'テキストとして読みます', args: { path: t('パス', 'sakiika-test.txt'), encoding: s('文字コード', ['utf8', 'shift_jis', 'base64']) } },
      { m: 'mkdir', title: 'フォルダー作成', desc: '途中のフォルダーも作ります', args: { path: t('パス', 'sakiika-demo/sub') } },
      { m: 'copy', title: 'コピー', desc: '', args: { from: t('元', 'sakiika-test.txt'), to: t('先', 'sakiika-demo/copy.txt') } },
      { m: 'move', title: '移動', desc: 'コピーしてから元を消します', args: { from: t('元', 'sakiika-demo/copy.txt'), to: t('先', 'sakiika-demo/moved.txt') } },
      { m: 'rename', title: '名前変更', desc: 'パス区切りは使えません', args: { path: t('パス', 'sakiika-test.txt'), name: t('新しい名前', 'renamed.txt') } },
      { m: 'delete', title: '削除', desc: 'フォルダーは recursive が必要', args: { path: t('パス', 'sakiika-demo'), recursive: c('中身ごと', true) } },
      { m: 'search', title: '名前で検索', desc: '部分一致', args: { path: t('起点', ''), name: t('含む文字', 'test'), depth: n('深さ', 4) } },
      { m: 'du', title: '容量集計', desc: '配下のバイト数とファイル数', args: { path: t('パス', ''), depth: n('深さ', 6) } },
      { m: 'pickFile', title: 'ファイルを選ぶ', desc: 'OS のファイル選択画面', args: { mime: j('MIME', '["*/*"]') } },
      { m: 'pickFiles', title: '複数選ぶ', desc: '複数選択を許可します', args: { mime: j('MIME', '["image/*"]') } },
      { m: 'pickFolder', title: 'フォルダーを選ぶ（都度）', desc: 'setAsRoot を true にすると基準フォルダーとして覚えます', args: { setAsRoot: c('ルートとして記憶', false) } },
      { m: 'createFile', title: '保存ダイアログ', desc: '保存先を選んで書き込みます', args: { name: t('ファイル名', 'sakiika.txt'), data: t('内容', 'ダイアログから保存しました') } },
      { m: 'readUri', title: 'URI を読む', desc: 'ピッカーで得た content:// を読みます', args: { uri: t('URI', ''), encoding: s('文字コード', ['utf8', 'base64']) } },
      { m: 'media', title: 'メディア一覧', desc: 'MediaStore から取得', perm: 'READ_MEDIA_IMAGES', args: { type: s('種類', ['images', 'video', 'audio', 'downloads']), limit: n('件数', 20) } },
      { m: 'shareFile', title: 'ファイルを共有', desc: '他アプリに渡します', args: { path: t('パス', 'sakiika-test.txt') } }
    ]
  },

  // ---------------------------------------------------------------- prefs
  {
    module: 'prefs',
    icon: '💾',
    label: '保存',
    intro: 'ネイティブ側の永続ストレージ。数値やオブジェクトも型を保ったまま出入りします。',
    items: [
      { m: 'set', title: '保存', desc: '値は JSON として解釈します', args: { key: t('キー', 'greeting'), value: j('値', '{"text":"やあ","count":1}') } },
      { m: 'get', title: '取得', desc: '', args: { key: t('キー', 'greeting') } },
      { m: 'has', title: 'あるか', desc: '', args: { key: t('キー', 'greeting') } },
      { m: 'keys', title: 'キー一覧', desc: '' },
      { m: 'all', title: '全部', desc: '' },
      { m: 'remove', title: '削除', desc: '', args: { key: t('キー', 'greeting') } },
      { m: 'clear', title: '全消去', desc: '' }
    ]
  },

  // ------------------------------------------------------------ clipboard
  {
    module: 'clipboard',
    icon: '📋',
    label: 'クリップボード',
    intro: 'ブラウザーのクリップボード API は file:// では制限されるので、ネイティブ経由で読み書きします。',
    items: [
      { m: 'write', title: '書き込み', desc: '', args: { text: t('内容', 'さきいかビルダー') } },
      { m: 'read', title: '読み取り', desc: 'Android 10+ ではフォーカスが必要です' },
      { m: 'hasText', title: 'テキストがあるか', desc: '' },
      { m: 'clear', title: '消去', desc: '' }
    ]
  },

  // ------------------------------------------------------------------ net
  {
    module: 'net',
    icon: '🌐',
    label: '通信',
    intro: 'file:// のページは CORS で外部 fetch がほぼ塞がれます。net.request なら Java 側で通信するので通ります。',
    items: [
      { m: 'status', title: '接続状態', desc: '回線種別・従量制か・実効帯域', perm: 'ACCESS_NETWORK_STATE' },
      { m: 'wifi', title: 'Wi-Fi', desc: 'SSID や電波強度（詳細には位置情報権限が必要）', perm: 'ACCESS_WIFI_STATE' },
      { m: 'telephony', title: 'モバイル回線', desc: 'キャリア名・SIM の状態', perm: 'READ_PHONE_STATE' },
      { m: 'interfaces', title: 'ネットワークIF', desc: '端末のインターフェースと IP' },
      {
        m: 'request', title: 'HTTP リクエスト', desc: 'CORS の制約を受けません',
        perm: 'INTERNET',
        args: {
          url: t('URL', 'https://api.github.com/zen'),
          method: s('メソッド', ['GET', 'POST', 'PUT', 'DELETE', 'HEAD']),
          headers: j('ヘッダー', '{"Accept":"text/plain"}'),
          body: t('本文', ''),
          responseType: s('受け取り方', ['text', 'json', 'base64'])
        }
      },
      { m: 'download', title: 'ダウンロード', desc: 'ファイルに直接保存し、途中経過をイベントで流します', perm: 'INTERNET', args: { url: t('URL', 'https://www.google.com/robots.txt'), path: t('保存名', 'robots.txt') } },
      { m: 'resolve', title: 'DNS 解決', desc: '', perm: 'INTERNET', args: { host: t('ホスト', 'example.com') } },
      { m: 'ping', title: '到達確認', desc: 'ICMP ではなく到達可否の判定です', perm: 'INTERNET', args: { host: t('ホスト', 'example.com'), timeoutMs: n('待ち時間 (ms)', 3000) } }
    ]
  },

  // --------------------------------------------------------------- intent
  {
    module: 'intent',
    icon: '🔗',
    label: 'アプリ連携',
    intro: '任意の Intent を投げられます。他のアプリの機能はほぼここから使えます。',
    items: [
      { m: 'openUrl', title: 'URL を開く', desc: '既定のブラウザーへ', args: { url: t('URL', 'https://developer.android.com') } },
      { m: 'dial', title: '電話をかける準備', desc: 'ダイヤラーに番号を入れて開きます', args: { number: t('番号', '0312345678') } },
      { m: 'sms', title: 'SMS を書く', desc: '本文まで入れて SMS アプリを開きます', args: { number: t('番号', '09012345678'), body: t('本文', 'テスト送信') } },
      { m: 'email', title: 'メールを書く', desc: '', args: { to: j('宛先', '["test@example.com"]'), subject: t('件名', 'テスト'), body: t('本文', 'さきいかビルダーより') } },
      { m: 'openSettings', title: '設定画面を開く', desc: 'android.settings.* のアクション名', args: { action: t('アクション', 'WIFI_SETTINGS') } },
      { m: 'openStore', title: 'ストアを開く', desc: '', args: { package: t('パッケージ', 'com.android.chrome') } },
      { m: 'setAlarm', title: 'アラームを登録', desc: '時計アプリに登録します', args: { hour: n('時', 7), minute: n('分', 30), message: t('ラベル', '起きる') } },
      { m: 'openTimer', title: 'タイマー', desc: '', args: { seconds: n('秒', 180), message: t('ラベル', 'カップ麺') } },
      { m: 'addCalendarEvent', title: 'カレンダーに追加', desc: 'カレンダーアプリの入力画面が開きます', args: { title: t('件名', '打ち合わせ'), location: t('場所', 'オンライン'), description: t('メモ', 'さきいかビルダーのデモ') } },
      { m: 'pickContact', title: '連絡先を選ぶ', desc: '選択結果の URI が返ります' },
      {
        m: 'start', title: '任意の Intent', desc: 'action / uri / mime / extras を自由に指定できます',
        args: {
          action: t('アクション', 'VIEW'),
          uri: t('URI', 'geo:35.681,139.767?q=東京駅'),
          mime: t('MIME', ''),
          chooser: t('選択シートの題（空で無し）', 'アプリを選ぶ'),
          extras: j('extras', '{}')
        }
      },
      { m: 'resolveAll', title: '受け取れるアプリ一覧', desc: 'この Intent を処理できるアプリを列挙', args: { action: t('アクション', 'SEND'), mime: t('MIME', 'text/plain') } },
      { m: 'parseUri', title: 'intent: URI を解析', desc: '', args: { uri: t('URI', 'intent://example.com#Intent;scheme=https;end') } }
    ]
  },

  // --------------------------------------------------------------- sensor
  {
    module: 'sensor',
    icon: '📡',
    label: 'センサー',
    intro: '購読するとイベント欄に流れ続けます。使い終わったら停止してください（電池を食います）。',
    items: [
      { m: 'list', title: '搭載センサー一覧', desc: 'メーカー・分解能・消費電流つき' },
      { m: 'read', title: '1 回だけ読む', desc: '購読せず 1 サンプルだけ取ります', args: { type: s('種類', ['accelerometer', 'gyroscope', 'magnetometer', 'light', 'proximity', 'pressure', 'gravity', 'linearAcceleration', 'rotationVector', 'stepCounter']) } },
      {
        m: 'start', title: '購読開始', desc: 'イベント欄に流れます',
        args: {
          type: s('種類', ['accelerometer', 'gyroscope', 'magnetometer', 'light', 'proximity', 'pressure', 'gravity', 'linearAcceleration', 'rotationVector', 'stepCounter']),
          rate: s('サンプリング', ['ui', 'normal', 'game', 'fastest']),
          intervalMs: n('通知間隔の下限 (ms)', 200)
        }
      },
      { m: 'stop', title: '購読停止', desc: '', args: { type: s('種類', ['accelerometer', 'gyroscope', 'magnetometer', 'light', 'proximity', 'pressure', 'gravity', 'linearAcceleration', 'rotationVector', 'stepCounter']) } },
      { m: 'active', title: '購読中の一覧', desc: '' },
      { m: 'stopAll', title: '全部停止', desc: '' }
    ]
  },

  // ------------------------------------------------------------- location
  {
    module: 'location',
    icon: '📍',
    label: '位置情報',
    intro: '初回呼び出し時に権限ダイアログが出ます。屋内では GPS が掴めないことがあります。',
    items: [
      { m: 'isEnabled', title: '位置情報がオンか', desc: 'GPS とネットワーク測位それぞれ' },
      { m: 'providers', title: 'プロバイダー一覧', desc: '' },
      { m: 'last', title: '最後に分かった位置', desc: 'キャッシュなので即返ります', perm: 'ACCESS_FINE_LOCATION' },
      { m: 'current', title: '今の位置を測る', desc: '新しい測位を待ちます', perm: 'ACCESS_FINE_LOCATION', args: { timeoutMs: n('待ち時間 (ms)', 20000) } },
      { m: 'watch', title: '追跡開始', desc: 'イベント欄に流れます', perm: 'ACCESS_FINE_LOCATION', args: { minTimeMs: n('最短間隔 (ms)', 3000), minDistanceM: n('最短距離 (m)', 0) } },
      { m: 'stopWatch', title: '追跡停止', desc: '' },
      { m: 'distance', title: '2 点間の距離', desc: '測地線での距離と方位', args: { lat1: n('緯度1', 35.681236, { step: 0.000001 }), lon1: n('経度1', 139.767125, { step: 0.000001 }), lat2: n('緯度2', 34.702485, { step: 0.000001 }), lon2: n('経度2', 135.495951, { step: 0.000001 }) } }
    ]
  },

  // ---------------------------------------------------------------- media
  {
    module: 'media',
    icon: '🎥',
    label: 'カメラ/音',
    intro: 'カメラ・マイク・スピーカー・読み上げ・ライト。権限は必要になった時点で自動的に要求します。',
    items: [
      { m: 'cameras', title: 'カメラ一覧', desc: '前面/背面・フラッシュの有無' },
      { m: 'capturePhoto', title: '写真を撮る', desc: 'カメラアプリを開き、結果を受け取ります', perm: 'CAMERA', args: { thumbnail: c('サムネイルだけ受け取る', false) } },
      { m: 'captureVideo', title: '動画を撮る', desc: '', perm: 'CAMERA', args: { maxSeconds: n('最大秒数 (0で無制限)', 10) } },
      { m: 'torch', title: 'ライト', desc: 'フラッシュを点灯/消灯', args: { on: c('点ける', true) } },
      { m: 'startRecording', title: '録音開始', desc: 'アプリの外部データ領域に m4a で保存します', perm: 'RECORD_AUDIO' },
      { m: 'stopRecording', title: '録音停止', desc: '', args: { asBase64: c('base64 でも受け取る', false) } },
      { m: 'play', title: '音を再生', desc: 'パスか URI を指定', args: { path: t('パス/URI', ''), volume: n('音量 0-1', 1, { step: 0.1, min: 0, max: 1 }), loop: c('繰り返す', false) } },
      { m: 'stop', title: '再生停止', desc: '' },
      { m: 'beep', title: 'ビープ音', desc: 'ToneGenerator の音', args: { ms: n('長さ (ms)', 200), volume: n('音量 1-100', 80) } },
      { m: 'speak', title: '読み上げ', desc: '端末の音声合成エンジンを使います', args: { text: t('読ませる文', 'さきいかビルダーで作ったアプリです'), locale: t('言語タグ', 'ja-JP'), rate: n('速さ', 1, { step: 0.1 }), pitch: n('高さ', 1, { step: 0.1 }) } },
      { m: 'stopSpeak', title: '読み上げ停止', desc: '' },
      { m: 'voices', title: '音声の一覧', desc: 'インストール済みの声' },
      { m: 'volume', title: '音量を取得', desc: '各ストリームの現在値と最大値' },
      { m: 'setVolume', title: '音量を設定', desc: '', args: { stream: s('ストリーム', ['music', 'ring', 'alarm', 'notification', 'system', 'voice']), percent: n('%', 50), showUi: c('OS の音量UIを出す', true) } },
      { m: 'snapshot', title: '画面キャプチャ', desc: 'この WebView を PNG にします' },
      { m: 'scanBarcode', title: 'QR を読む', desc: '外部のスキャナーアプリに委譲します' }
    ]
  },

  // --------------------------------------------------------------- notify
  {
    module: 'notify',
    icon: '🔔',
    label: '通知',
    intro: 'Android 13 以降は通知にも実行時権限が必要です。初回に自動で要求します。',
    items: [
      { m: 'enabled', title: '通知が有効か', desc: '' },
      { m: 'show', title: '通知を出す', desc: '', perm: 'POST_NOTIFICATIONS', args: { id: n('ID', 1), title: t('タイトル', 'さきいかビルダー'), text: t('本文', 'デモからの通知です'), bigText: t('展開時の本文', '長い本文もここに入れられます。') } },
      { m: 'progress', title: '進捗つき通知', desc: '同じ ID で呼び直すと進みます', perm: 'POST_NOTIFICATIONS', args: { id: n('ID', 2), title: t('タイトル', 'ダウンロード中'), max: n('最大', 100), value: n('現在', 40), indeterminate: c('不定', false) } },
      { m: 'cancel', title: '通知を消す', desc: '', args: { id: n('ID', 1) } },
      { m: 'cancelAll', title: '全部消す', desc: '' },
      { m: 'channels', title: 'チャンネル一覧', desc: '' },
      { m: 'createChannel', title: 'チャンネル作成', desc: 'importance は 0〜4', args: { id: t('ID', 'demo.channel'), name: t('名前', 'デモ通知'), importance: n('重要度', 3), description: t('説明', 'デモ用のチャンネル') } },
      { m: 'active', title: '表示中の通知', desc: '' }
    ]
  },

  // -------------------------------------------------------------- content
  {
    module: 'content',
    icon: '🗂️',
    label: 'コンテンツ',
    intro: '連絡先・SMS・通話履歴・カレンダー・メディアは全部 ContentProvider です。ここから直接クエリできます（対応する権限が必要）。',
    items: [
      { m: 'shortcuts', title: '使える省略名', desc: 'content:// を書かずに済む名前の一覧' },
      { m: 'contacts', title: '連絡先（整形済み）', desc: '名前と電話番号を結合して返します', perm: 'READ_CONTACTS', args: { search: t('検索', ''), limit: n('件数', 30) } },
      { m: 'query', title: '汎用クエリ', desc: 'どのプロバイダーでも同じ形で読めます', perm: 'READ_CONTACTS', args: { uri: t('プロバイダー', 'contacts'), projection: j('列 (空で全部)', '[]'), selection: t('条件', ''), sort: t('並び順', ''), limit: n('件数', 20) } },
      { m: 'query', title: 'SMS を読む', desc: '受信箱を新しい順に', perm: 'READ_SMS', args: { uri: t('プロバイダー', 'smsInbox'), sort: t('並び順', 'date DESC'), limit: n('件数', 10) } },
      { m: 'query', title: '通話履歴', desc: '', perm: 'READ_CALL_LOG', args: { uri: t('プロバイダー', 'calls'), sort: t('並び順', 'date DESC'), limit: n('件数', 10) } },
      { m: 'query', title: 'カレンダーの予定', desc: '', perm: 'READ_CALENDAR', args: { uri: t('プロバイダー', 'events'), limit: n('件数', 10) } },
      { m: 'type', title: 'MIME を調べる', desc: '', args: { uri: t('プロバイダー', 'images') } },
      { m: 'settingsGet', title: 'システム設定を読む', desc: '', args: { namespace: s('名前空間', ['system', 'secure', 'global']), key: t('キー', 'screen_brightness') } },
      { m: 'settingsList', title: '設定を全部読む', desc: 'キー名が分からないときに', args: { namespace: s('名前空間', ['system', 'secure', 'global']) } }
    ]
  },

  // ------------------------------------------------------------------ pkg
  {
    module: 'pkg',
    icon: '📦',
    label: 'アプリ',
    intro: 'Android 11 以降、他アプリの列挙には QUERY_ALL_PACKAGES が必要です。無い場合は少数しか見えません。',
    items: [
      { m: 'self', title: 'このアプリの情報', desc: '' },
      { m: 'list', title: 'インストール済み一覧', desc: '', perm: 'QUERY_ALL_PACKAGES', args: { search: t('検索', ''), system: c('システムアプリも', false), launchableOnly: c('起動できるものだけ', true), limit: n('件数', 50) } },
      { m: 'info', title: 'アプリの詳細', desc: '', args: { package: t('パッケージ', 'com.android.settings') } },
      { m: 'icon', title: 'アイコンを取得', desc: 'PNG の data URL で返ります', args: { package: t('パッケージ', 'com.android.settings'), size: n('サイズ px', 96) } },
      { m: 'isInstalled', title: '入っているか', desc: '', args: { package: t('パッケージ', 'com.android.chrome') } },
      { m: 'launch', title: '起動する', desc: '', args: { package: t('パッケージ', 'com.android.settings') } },
      { m: 'openDetails', title: '設定画面を開く', desc: '', args: { package: t('パッケージ', 'com.android.settings') } },
      { m: 'uninstall', title: 'アンインストール要求', desc: 'OS の確認画面が出ます', args: { package: t('パッケージ', '') } }
    ]
  },

  // ------------------------------------------------------------ biometric
  {
    module: 'biometric',
    icon: '☝️',
    label: '生体認証',
    intro: '指紋や顔での本人確認。ページ内のロック解除に使えます（端末そのものの保護ではありません）。',
    items: [
      { m: 'available', title: '使えるか', desc: 'ハードの有無と登録状況' },
      { m: 'deviceSecure', title: '画面ロック設定', desc: 'PIN やパターンが設定されているか' },
      { m: 'authenticate', title: '認証する', desc: '', perm: 'USE_BIOMETRIC', args: { title: t('タイトル', '本人確認'), subtitle: t('サブタイトル', 'デモの認証です'), description: t('説明', '指紋または顔で認証してください'), cancel: t('キャンセル', 'やめる') } }
    ]
  }
];
