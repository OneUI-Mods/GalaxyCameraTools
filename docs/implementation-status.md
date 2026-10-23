# 作業状態 — 2026-10-24

Galaxy Camera Tools 0.1.3-experimental。SDK 37、libxposed API 102。公開リポジトリ: https://github.com/OneUI-Mods/GalaxyCameraTools

## 実装

- 通常写真のpreview request mapへ60fpsを要求。still/record mapは変更しない。Maker type、撮影モード、stream min duration、fixed60 capability、温度を検査する。
- 通常動画の背面FHD/UHD120。native feature mapを検査し、native menu・command対応、high-speed request schedulerの選択、JPEG snapshot streamの除外を組み合わせる。30/60に戻す場合はnative maker selectionへ戻る。
- Expert RAWはsnapshot取得後に画角を拡大・縮小して新レンズへ受け渡す。Cameraの実行時zoom curveを数値としてProviderへ保存し、RAWで同じ区間を使う。曲線がなければ純正のPathInterpolatorを使う。RAWの撮影request/crop設定は変更しない。連続入力は最新対象へまとめ、600msのsnapshot失敗復帰・2秒の表示復帰上限・背景遷移時の解除を備える。
- 通常写真・通常動画・スーパー手ブレ補正・水平ロックのtable-based zoomはChoreographerのframe timeから純正曲線を補間する。時間を1.35倍（320〜1100ms）にし、見える加減速を加える。カメラ要求は純正workerへ1件だけ渡し、最新値を優先する。古い遅延stepのgenerationを無効化し、未処理の値を連打しない。OFF時は残りのnative curveへ返し、他モードのrunnerは変更しない。
- 写真FPSとmotionが両方ONの対応する背面写真では、1回のpreview copyと表示変形でレンズ変更を60fps描画する。撮像の最終倍率を1回要求し、連続した結果を待って映像を受け渡す。被写体の動きのフレーム補間ではなく、実撮像30fpsの経路は残る。失敗・高温では従来の補間へ戻す。
- 背面写真の低負荷プレビューを独立設定として追加。対応する小さいbuffer sizeとdeferred profileを合わせ、保存写真の解像度は維持する。
- 超広角ライトはnative FLASH_IN_WIDE_LENSの読み取りと機能判定のみを切り替える。モジュールの設定以外にライト用の設定値を保存しない。温度保護などのnative判定は変更しない。
- Camera Assistantのnative preference categoryと9個のswitch。providerで専用アプリと双方向同期。Androidのpackage visibilityに対応する、送信UIDを照合した明示的なreceiverとURI grant。
- フラッシュのピント固定はnative lens positionを取得してAFと位置を保持し、撮影終了・キャンセル・背景遷移・OFFで元の値へ復帰する。
- Quick Panelのライトは純正の安全判定を残し、短いleaseを持つProvider経由でCamera側へ転送する。
- 一般Camera2アプリは選択されたプロセス内でphysical camera 5/6/7を親のlogical cameraと出力設定へ結び付ける。共有権限やHALのcapabilityは変更しない。
- ホストごとの確定した異常終了履歴のみを数える2分/3回の保護。通常のforce-stop、更新、低メモリ終了は数えない。

## 検証

[0.1.0の実機検証記録](device-validation-20261023.md)と[0.1.1のレンズ切り替え検証](device-validation-20261024.md)、[0.1.2の検証](device-validation-0.1.2.md)、[0.1.3の検証](device-validation-0.1.3.md)を参照。release build、単体テスト22件、lintRelease、55 runtime componentのlicense catalog、release署名と16KiB alignmentを検証。debug APKやテスト用Activityを配布しない。

## 対応範囲

SM-S948Q / Android 17 / One UI 9 / BZI9、Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8を個別の契約として識別する。他環境では撮影処理を変更しない。Android 16の既存アプリ・既存profileは変更していない。

8K120のnative定義は存在せず、HD・前面・別比率の120も追加しない。前面写真では60fps要求が通っても実測30fpsになる場合がある。背面のレンズ切り替えでも一部は30fps台であり、描画同期の完了を映像60fpsの達成と混同しない。

SamsungのAPK、逆コンパイル結果、capability dump、実機画像と動画は非公開のローカル資料であり、公開リポジトリや配布ZIPへ含めない。
