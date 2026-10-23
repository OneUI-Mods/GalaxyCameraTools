# 配布手順

SDK 37、JDK 21を使います。`tools/package_release.py --source-ref <commit>` はrelease variantをビルドし、対象パッケージ・SDK・テストActivity混入・API 102 metadata・scope・旧API参照を検査します。runtime依存関係とライセンス一覧を照合し、16KiBにalignして署名を検証します。ソースZIPは指定commitから生成します。署名ファイルはGit管理外の `.signing/release.keystore`、alias `galaxycamera-release`、パスワードは `.signing/password` です。鍵やパスワードをログ・Git・配布物へ入れません。

APKとソースZIP、SHA256SUMSだけを配布します。SamsungのAPK・逆コンパイル結果・実機画像・ログは配布しません。初期の実験版はGitHub prereleaseにします。説明は対応環境、使い方、必要な再読み込み、既知の制限を外部向けに記載します。既存tagは移動しません。
