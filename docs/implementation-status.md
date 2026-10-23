# 作業状態 — 2026-10-23

録画アプリ0.3.5を完了した後、カメラ側の作業を再開した。

## 完了

- Galaxy Camera Tools（dev.ryuya.galaxycamera）の新規プロジェクト。SDK 37、libxposed API 102、専用M3設定画面と日英韓中2種の表示、ライセンス画面。
- 写真のpreview mapのみ60fpsを要求するAndroid 17プロファイル。温度、通常写真モード、Maker type、出力サイズと固定60fpsのcapabilityを検査し、SENSOR_TIMESTAMPで実測する。
- 通常動画への背面FHD/UHD120fps候補・設定command対応。対応するnative enumとrecording feature mapのみを使用する。
- Camera Assistantへのnative設定部品とproviderによる双方向同期。
- Expert RAWのnative PREVIEW_CHANGE snapshot callbackへレンズ切り替えをつなぐ。連続指定は最後の対象へまとめ、失敗時600msで通常動作へ戻す。
- ホストごとの異常終了履歴と2分/3回の保護。通常の強制停止をカウントしない。
- releaseビルド、6件の単体テスト、lintRelease成功。release署名と16KiB alignment検証。55 runtime componentsのライセンス照合成功。
- 署名済み試作release APKを実機へインストール済み。

## 操作待ち

実機操作の調整質問を送信済み。返答前にカメラを起動・撮影していない。Vectorの有効化とscope登録、Camera/Assistant/Expert RAWのプロセス読み込み直し、各機能の実機試験が必要。未確認のfpsやアニメーションを動作確認済みと報告しない。SystemUI・ホーム・本体の再起動は不要。

実機APKはCamera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8。/system/cameradata/camera-feature.xmlでも背面FHD/UHD120の対応を確認した。8K120のnative定義は存在しない。別比率、HD、前面の120はこの版では追加しない。

## 残作業

実機での初期化契約・プレビュー60の実測、120fps動画をffprobe/フレーム時刻で検証、Expert RAWの連続切替・OFF・失敗復帰、Assistantの表示と同期、UIの実機確認。必要なら修正後に再度releaseを署名する。GitHubリポジトリはまだ作成していない。既存アプリと同じ公開ルールで管理し、外部向け説明を用意してから配布する。

SamsungのAPK、逆コンパイル結果、capability dumpはAdaptive Bar側evidence/camera17にある非公開のローカル資料だけを使い、新リポジトリへ同梱しない。
