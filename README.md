# Galaxy Camera Tools

Samsung CameraとExpert RAWの撮影画面を調整する、API 102向けXposedモジュールです。

- 通常の写真モードで、対応するプレビュー映像を60fpsにします。
- 通常の動画モードへ、カメラが対応する120fpsの選択肢を追加します。
- Expert RAWのレンズ切り替えを、純正のぼかしとフェードにつなぎます。
- 専用アプリとCamera Assistantの設定が連動します。すべて個別に切り替えられます。

## 対応環境

初期実験版の対象はGalaxy S26 Ultra（SM-S948Q）、Android 17 / One UI 9 BZI9です。Samsung Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8を対象にしています。対応するroot環境とAPI 102対応Vectorが必要です。別のファームウェアやアプリ版では撮影処理を変更しません。Android 16の既存モジュールを置き換えるものではありません。

120fpsはハードウェアとSamsungの録画処理が対応する組み合わせに限ります。8Kなどへ120fpsの表示だけを追加することはありません。

## 使い方

署名済みrelease APKをインストールし、Vectorでモジュールを有効にします。対象にカメラ、Camera Assistant、Expert RAWを選び、対象アプリを強制停止して開き直してください。設定の初期値はすべてOFFです。カメラ設定を変えた後も、撮影を終えて強制停止・再起動すると録画の選択肢を更新できます。端末本体やSystemUIの再起動は不要です。

モジュール操作後に短時間で異常終了を繰り返した場合、そのアプリへの変更を停止します。状態タブから保護を解除し、対象アプリを開き直して再試行できます。

## 開発

`./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease`

配布は[リリース手順](docs/releasing.md)を参照してください。Samsungアプリのバイナリやコードは同梱していません。プロジェクトはMIT、依存ライブラリの表記はアプリ内の検索できるライセンス画面に掲載しています。
