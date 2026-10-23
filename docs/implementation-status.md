# 作業状態 — 2026-10-24

Galaxy Camera Tools 0.1.1-experimental。SDK 37、libxposed API 102。公開リポジトリ: https://github.com/OneUI-Mods/GalaxyCameraTools

## 実装

- 通常写真のpreview request mapへ60fpsを要求。still/record mapは変更しない。Maker type、撮影モード、stream min duration、fixed60 capability、温度を検査する。
- 通常動画の背面FHD/UHD120。native feature mapを検査し、native menu・command対応、high-speed request schedulerの選択、JPEG snapshot streamの除外を組み合わせる。30/60に戻す場合はnative maker selectionへ戻る。
- Expert RAWはsnapshot取得後に画角を拡大・縮小して新レンズへ受け渡す。Cameraの実行時zoom curveを数値としてProviderへ保存し、RAWで同じ区間を使う。曲線がなければ純正のPathInterpolatorを使う。RAWの撮影request/crop設定は変更しない。連続入力は最新対象へまとめ、600msのsnapshot失敗復帰・2秒の表示復帰上限・背景遷移時の解除を備える。
- 通常写真のtable-based zoomはChoreographerのframe timeから純正曲線を補間する。古い遅延stepのgenerationを無効化し、未処理の値を連打しない。OFF時は残りのnative curveへ返し、他モードのrunnerは変更しない。
- 超広角ライトはnative FLASH_IN_WIDE_LENSの読み取りと機能判定のみを切り替える。モジュールの設定以外にライト用の設定値を保存しない。温度保護などのnative判定は変更しない。
- Camera Assistantのnative preference categoryと7個のswitch。providerで専用アプリと双方向同期。Androidのpackage visibilityに対応する、送信UIDを照合した明示的なreceiverとURI grant。
- フラッシュのピント固定はnative lens positionを取得してAFと位置を保持し、撮影終了・キャンセル・背景遷移・OFFで元の値へ復帰する。
- Quick Panelのライトは純正の安全判定を残し、短いleaseを持つProvider経由でCamera側へ転送する。
- 一般Camera2アプリは選択されたプロセス内でphysical camera 5/6/7を親のlogical cameraと出力設定へ結び付ける。共有権限やHALのcapabilityは変更しない。
- ホストごとの確定した異常終了履歴のみを数える2分/3回の保護。通常のforce-stop、更新、低メモリ終了は数えない。

## 検証

[実機検証記録](device-validation-20261023.md)を参照。release build、単体テスト11件、lintRelease、55 runtime componentのlicense catalog、release署名と16KiB alignmentを検証。debug APKやテスト用Activityを配布しない。

## 対応範囲

SM-S948Q / Android 17 / One UI 9 / BZI9、Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8を個別の契約として識別する。他環境では撮影処理を変更しない。Android 16の既存アプリ・既存profileは変更していない。

8K120のnative定義は存在せず、HD・前面・別比率の120も追加しない。前面写真では60fps要求が通っても実測30fpsになる場合があり、背面の結果を前面の動作保証に流用しない。

SamsungのAPK、逆コンパイル結果、capability dump、実機画像と動画は非公開のローカル資料であり、公開リポジトリや配布ZIPへ含めない。
