//! Catalogue of Android permissions the GUI offers as checkboxes.
//!
//! `id` is the short constant name (what a project file stores), `manifest` is
//! the fully-qualified name written into AndroidManifest.xml. `runtime` marks
//! the dangerous permissions that additionally need a request at run time —
//! the generated `perm` bridge module knows how to ask for exactly those.

#[derive(Debug, Clone, Copy)]
pub struct Permission {
    pub id: &'static str,
    pub manifest: &'static str,
    /// Needs requestPermissions() at run time, not just a manifest entry.
    pub runtime: bool,
    pub group: &'static str,
    pub desc_ja: &'static str,
    /// Only declared when targeting at least this API level (0 = always).
    pub min_sdk: u32,
    /// Declared with android:maxSdkVersion when the platform retired it.
    pub max_sdk: u32,
    /// Not grantable by a normal app store install (needs adb/system role).
    pub special: bool,
}

const fn p(
    id: &'static str,
    manifest: &'static str,
    runtime: bool,
    group: &'static str,
    desc_ja: &'static str,
) -> Permission {
    Permission { id, manifest, runtime, group, desc_ja, min_sdk: 0, max_sdk: 0, special: false }
}

const fn p_sdk(
    id: &'static str,
    manifest: &'static str,
    runtime: bool,
    group: &'static str,
    desc_ja: &'static str,
    min_sdk: u32,
    max_sdk: u32,
) -> Permission {
    Permission { id, manifest, runtime, group, desc_ja, min_sdk, max_sdk, special: false }
}

const fn p_special(
    id: &'static str,
    manifest: &'static str,
    group: &'static str,
    desc_ja: &'static str,
) -> Permission {
    Permission {
        id,
        manifest,
        runtime: false,
        group,
        desc_ja,
        min_sdk: 0,
        max_sdk: 0,
        special: true,
    }
}

pub const PERMISSIONS: &[Permission] = &[
    // ---- ネットワーク ----
    p("INTERNET", "android.permission.INTERNET", false, "ネットワーク", "インターネット接続（http/https 通信）"),
    p("ACCESS_NETWORK_STATE", "android.permission.ACCESS_NETWORK_STATE", false, "ネットワーク", "接続状態の取得（オンライン判定・回線種別）"),
    p("ACCESS_WIFI_STATE", "android.permission.ACCESS_WIFI_STATE", false, "ネットワーク", "Wi-Fi 情報の取得（SSID・電波強度）"),
    p("CHANGE_WIFI_STATE", "android.permission.CHANGE_WIFI_STATE", false, "ネットワーク", "Wi-Fi のオン/オフ切り替え"),
    p("CHANGE_NETWORK_STATE", "android.permission.CHANGE_NETWORK_STATE", false, "ネットワーク", "ネットワーク設定の変更"),
    p_sdk("NEARBY_WIFI_DEVICES", "android.permission.NEARBY_WIFI_DEVICES", true, "ネットワーク", "付近の Wi-Fi 機器の検出", 33, 0),

    // ---- 位置情報 ----
    p("ACCESS_COARSE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", true, "位置情報", "おおまかな位置（ネットワーク測位）"),
    p("ACCESS_FINE_LOCATION", "android.permission.ACCESS_FINE_LOCATION", true, "位置情報", "正確な位置（GPS）"),
    p_sdk("ACCESS_BACKGROUND_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION", true, "位置情報", "バックグラウンドでの位置取得", 29, 0),

    // ---- カメラ・マイク ----
    p("CAMERA", "android.permission.CAMERA", true, "カメラ・マイク", "カメラの使用（撮影・プレビュー）"),
    p("RECORD_AUDIO", "android.permission.RECORD_AUDIO", true, "カメラ・マイク", "マイクからの録音"),
    p("MODIFY_AUDIO_SETTINGS", "android.permission.MODIFY_AUDIO_SETTINGS", false, "カメラ・マイク", "音量・音声ルーティングの変更"),
    p("FLASHLIGHT", "android.permission.FLASHLIGHT", false, "カメラ・マイク", "ライト（トーチ）の点灯"),

    // ---- ストレージ ----
    p_sdk("READ_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", true, "ストレージ", "共有ストレージの読み取り（〜Android 12）", 0, 32),
    p_sdk("WRITE_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", true, "ストレージ", "共有ストレージの書き込み（〜Android 10）", 0, 29),
    p_sdk("READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_IMAGES", true, "ストレージ", "画像の読み取り（Android 13+）", 33, 0),
    p_sdk("READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VIDEO", true, "ストレージ", "動画の読み取り（Android 13+）", 33, 0),
    p_sdk("READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_AUDIO", true, "ストレージ", "音声の読み取り（Android 13+）", 33, 0),
    p_sdk("READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", true, "ストレージ", "ユーザーが選んだ写真のみ（Android 14+）", 34, 0),
    p_special("MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE", "ストレージ", "全ファイルへのアクセス（ファイルマネージャー相当・設定画面で許可）"),

    // ---- 連絡先・通話・SMS ----
    p("READ_CONTACTS", "android.permission.READ_CONTACTS", true, "連絡先・通話", "連絡先の読み取り"),
    p("WRITE_CONTACTS", "android.permission.WRITE_CONTACTS", true, "連絡先・通話", "連絡先の書き込み"),
    p("GET_ACCOUNTS", "android.permission.GET_ACCOUNTS", true, "連絡先・通話", "端末のアカウント一覧"),
    p("READ_PHONE_STATE", "android.permission.READ_PHONE_STATE", true, "連絡先・通話", "電話状態の取得（通話中判定・SIM 情報）"),
    p("CALL_PHONE", "android.permission.CALL_PHONE", true, "連絡先・通話", "発信（ダイヤラーを経由せず直接）"),
    p("READ_CALL_LOG", "android.permission.READ_CALL_LOG", true, "連絡先・通話", "通話履歴の読み取り"),
    p("SEND_SMS", "android.permission.SEND_SMS", true, "連絡先・通話", "SMS の送信"),
    p("READ_SMS", "android.permission.READ_SMS", true, "連絡先・通話", "SMS の読み取り"),
    p("RECEIVE_SMS", "android.permission.RECEIVE_SMS", true, "連絡先・通話", "SMS の受信通知"),

    // ---- カレンダー ----
    p("READ_CALENDAR", "android.permission.READ_CALENDAR", true, "カレンダー", "カレンダーの読み取り"),
    p("WRITE_CALENDAR", "android.permission.WRITE_CALENDAR", true, "カレンダー", "カレンダーの書き込み"),

    // ---- センサー・身体 ----
    p("BODY_SENSORS", "android.permission.BODY_SENSORS", true, "センサー", "心拍などの身体センサー"),
    p("HIGH_SAMPLING_RATE_SENSORS", "android.permission.HIGH_SAMPLING_RATE_SENSORS", false, "センサー", "200Hz を超える高頻度サンプリング"),
    p_sdk("ACTIVITY_RECOGNITION", "android.permission.ACTIVITY_RECOGNITION", true, "センサー", "歩数・行動認識", 29, 0),

    // ---- Bluetooth ----
    p_sdk("BLUETOOTH", "android.permission.BLUETOOTH", false, "Bluetooth", "Bluetooth の利用（〜Android 11）", 0, 30),
    p_sdk("BLUETOOTH_ADMIN", "android.permission.BLUETOOTH_ADMIN", false, "Bluetooth", "Bluetooth の設定変更（〜Android 11）", 0, 30),
    p_sdk("BLUETOOTH_SCAN", "android.permission.BLUETOOTH_SCAN", true, "Bluetooth", "付近の Bluetooth 機器のスキャン（Android 12+）", 31, 0),
    p_sdk("BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_CONNECT", true, "Bluetooth", "ペアリング済み機器への接続（Android 12+）", 31, 0),
    p_sdk("BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_ADVERTISE", true, "Bluetooth", "自機の Bluetooth 広告（Android 12+）", 31, 0),

    // ---- 通知・バックグラウンド ----
    p_sdk("POST_NOTIFICATIONS", "android.permission.POST_NOTIFICATIONS", true, "通知・常駐", "通知の表示（Android 13+）", 33, 0),
    p("VIBRATE", "android.permission.VIBRATE", false, "通知・常駐", "バイブレーション"),
    p("WAKE_LOCK", "android.permission.WAKE_LOCK", false, "通知・常駐", "画面/CPU のスリープ防止"),
    p("FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE", false, "通知・常駐", "フォアグラウンドサービスの実行"),
    p_sdk("FOREGROUND_SERVICE_DATA_SYNC", "android.permission.FOREGROUND_SERVICE_DATA_SYNC", false, "通知・常駐", "データ同期用フォアグラウンドサービス（Android 14+）", 34, 0),
    p("RECEIVE_BOOT_COMPLETED", "android.permission.RECEIVE_BOOT_COMPLETED", false, "通知・常駐", "起動完了時の自動実行"),
    p_sdk("SCHEDULE_EXACT_ALARM", "android.permission.SCHEDULE_EXACT_ALARM", false, "通知・常駐", "正確な時刻のアラーム（Android 12+）", 31, 0),

    // ---- 端末・その他 ----
    p("NFC", "android.permission.NFC", false, "端末", "NFC の読み書き"),
    p("USE_BIOMETRIC", "android.permission.USE_BIOMETRIC", false, "端末", "生体認証（指紋・顔）"),
    p("USE_FINGERPRINT", "android.permission.USE_FINGERPRINT", false, "端末", "指紋認証（旧 API）"),
    p("BATTERY_STATS", "android.permission.BATTERY_STATS", false, "端末", "バッテリー統計の参照"),
    p("SET_WALLPAPER", "android.permission.SET_WALLPAPER", false, "端末", "壁紙の変更"),
    p("INSTALL_SHORTCUT", "com.android.launcher.permission.INSTALL_SHORTCUT", false, "端末", "ホーム画面へのショートカット作成"),
    p("REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_INSTALL_PACKAGES", false, "端末", "APK のインストール要求"),
    p("QUERY_ALL_PACKAGES", "android.permission.QUERY_ALL_PACKAGES", false, "端末", "インストール済みアプリの列挙"),
    p_special("SYSTEM_ALERT_WINDOW", "android.permission.SYSTEM_ALERT_WINDOW", "端末", "他アプリの上に表示（設定画面で許可）"),
    p_special("WRITE_SETTINGS", "android.permission.WRITE_SETTINGS", "端末", "システム設定の変更（設定画面で許可）"),
    p_special("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "端末", "バッテリー最適化の除外を要求"),
    p_special("PACKAGE_USAGE_STATS", "android.permission.PACKAGE_USAGE_STATS", "端末", "アプリ使用状況の統計（設定画面で許可）"),
];

pub fn find(id: &str) -> Option<&'static Permission> {
    PERMISSIONS.iter().find(|p| p.id == id)
}

pub fn groups() -> Vec<&'static str> {
    let mut g: Vec<&'static str> = Vec::new();
    for p in PERMISSIONS {
        if !g.contains(&p.group) {
            g.push(p.group);
        }
    }
    g
}

/// Bridge modules and the permissions each one really needs to be useful.
/// The GUI uses this to offer "この機能を使うのに足りない権限を追加する".
pub struct ModuleInfo {
    pub name: &'static str,
    pub desc_ja: &'static str,
    pub wants: &'static [&'static str],
}

pub const MODULES: &[ModuleInfo] = &[
    ModuleInfo { name: "sys", desc_ja: "端末情報・ビルド情報・画面・バッテリー・ロケール", wants: &[] },
    ModuleInfo { name: "ui", desc_ja: "トースト・バイブ・バー色・ダークモード・明るさ・向き・共有", wants: &["VIBRATE"] },
    ModuleInfo { name: "perm", desc_ja: "権限の確認とリクエスト", wants: &[] },
    ModuleInfo { name: "fs", desc_ja: "ファイル読み書き・一覧・SAF フォルダー選択", wants: &[] },
    ModuleInfo { name: "prefs", desc_ja: "永続キー・バリューストア", wants: &[] },
    ModuleInfo { name: "clipboard", desc_ja: "クリップボードの読み書き", wants: &[] },
    ModuleInfo { name: "net", desc_ja: "回線状態と CORS 制約のない HTTP リクエスト", wants: &["INTERNET", "ACCESS_NETWORK_STATE"] },
    ModuleInfo { name: "intent", desc_ja: "任意 Intent の発行・アプリ連携・URL/電話/メール", wants: &[] },
    ModuleInfo { name: "sensor", desc_ja: "加速度・ジャイロ・照度・近接・磁気などの購読", wants: &[] },
    ModuleInfo { name: "location", desc_ja: "現在位置の取得と追跡", wants: &["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"] },
    ModuleInfo { name: "media", desc_ja: "撮影・録音・音声再生・読み上げ (TTS)", wants: &["CAMERA", "RECORD_AUDIO"] },
    ModuleInfo { name: "notify", desc_ja: "通知の表示とチャンネル管理", wants: &["POST_NOTIFICATIONS"] },
    ModuleInfo { name: "content", desc_ja: "ContentResolver 汎用クエリ（連絡先・メディア・カレンダー等）", wants: &["READ_CONTACTS"] },
    ModuleInfo { name: "pkg", desc_ja: "インストール済みアプリの列挙と起動", wants: &["QUERY_ALL_PACKAGES"] },
    ModuleInfo { name: "biometric", desc_ja: "生体認証によるロック解除", wants: &["USE_BIOMETRIC"] },
    ModuleInfo { name: "reflect", desc_ja: "Java リフレクション経由で任意の Android API を呼ぶ", wants: &[] },
];

pub fn all_module_names() -> Vec<&'static str> {
    MODULES.iter().map(|m| m.name).collect()
}
