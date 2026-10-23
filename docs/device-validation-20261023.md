# 実機検証 — 2026-10-23

## 環境

Galaxy S26 Ultra SM-S948Q、CP2A.260605.016.S948QOPU1BZI9、SDK37。Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8、API102対応Vector。

端末・SystemUI・ホームの再起動は行わず、対象カメラアプリのforce-stop/openで確認した。

## 写真プレビュー

- 背面通常写真: native要求[10,30]時は暗所の実測約15fps。ON時は要求[60,60]、sensor frame duration約16.7ms、実測約59fps。起動直後や負荷によって約52〜59fpsの範囲も観測した。
- 前面通常写真: 要求[60,60]でもsensor frame duration約33.3ms、実測約30fps。前面の60fps動作保証はしない。
- 撮影用request mapを変更しないことをコードで検査。写真の保存経路も確認した。

## 通常動画

- FHD: 1920×1080、r_frame_rate=120/1、平均約119.4fps、398frames/約3.334s。audio trackあり。
- UHD: 3840×2160、r_frame_rate=120/1、平均約119.4fps、405frames/約3.393s。audio trackあり。
- 120からFHD30へ戻した録画: 平均約29.84fps、audio trackあり。
- OFF・再起動後はnative menuから120が消え、30/60の候補へ戻る。

初期の試作では通常動画のJPEG snapshot streamが120fps sessionを壊した。size utilityにnullを返す方式もpresenterでNPEを生んだため撤去し、presenterの準備後にsnapshot streamだけを除外した。さらに通常request schedulerではvideo trackの開始通知が来ず保存されなかった。純正プロ動画120がProSlowMotionVideoMakerを使うことを実機ログで特定し、maker selectorで同じschedulerを選ぶことで解決した。通常30/60へ戻すと標準のmakerへ戻る。

## Expert RAW

snapshotの表示を待ってからレンズを切り替える経路を確認。画面録画のフレームで、切り替え前の映像→blur→新レンズの映像への復帰を確認した。複数レンズの連続指定とOFFでの通常動作、背景への遷移を確認した。native presenterが先にrequestする経路はpresenter側で受ける必要があり、呼び出し元のinliningも考慮した。

## 超広角ライト

超広角0.6倍を選択した状態で、写真のOFF/AUTO/ONのflash選択が操作可能。通常動画で0.6倍を維持したままライトONとなり、previewの照明変化とLEDのbrightnessが0から31になることを確認した。テスト後は動画ライトをOFFへ戻した。native cameraの温度制限は変更していない。

## UI・同期・保護

専用M3画面とCamera Assistantの4スイッチを確認。専用アプリ→Camera Assistant、Camera Assistant→専用アプリの両方向の反映を確認。5言語のresource key整合、license catalogを検証した。通常のforce-stopを繰り返しても保護は発動していない。

試験用の写真・動画・画面録画は個別に識別して除去する。ユーザーの既存メディアをまとめて削除しない。

## 追加機能の確認

- フラッシュ時はSamsungのlens-info metadataから現在位置を取り、native focusLensPos requestとAF OFFを併用。AEのプリキャプチャは純正のまま進める。実機ではAF OFF中の32フレームで距離0.81037277、位置1317が変わらず、撮影完了でAF復元を確認した。通常AF OFFだけでは位置が変わったため採用していない。復帰では標準距離・native position・元のAF modeをすべて復元する。
- Quick Panelは純正FlashlightTileのKnox・温度・電池残量判定を維持し、カメラが所有するtorchへ要求を転送。ON/OFF/ON、パネルを閉じて開き直した後のOFFでLEDの点灯値31/0/31/0を確認。Cameraを離れた場合は0に戻った。Providerの操作は送信UIDとpackageを照合し、3秒のleaseと1.5秒の要求期限を使う。画素やカメラハンドルは共有しない。
- Camera2のhidden IDの表示フィルターだけを外す案では、metadataを取得できてもnative connectで拒否されることを確認した。実装は、公開logical cameraのphysical output routingを使う方式へ変更した。metadata、カメラ権限、HALの解像度・fpsは偽装しない。
- 一般アプリUIDでnative physical ID 5/6/7へ出力を結び、各60フレームを取得した。望遠6では3648x2736 JPEG（2944426 bytes、focal=7.0、physical=[6]）、望遠7では4080x3060 JPEG（203602 bytes、focal=18.6、physical=[7]）を取得した。画像はテストアプリのメモリだけで扱い、写真ライブラリには保存していない。RAW、高fps・高解像度のすべての組み合わせは未確認。
- 通常動画でFHD120選択後に追加機能をOFFにしてcold startし、エラー画面を出さずFHD30へ復帰することを確認。無効化後に初期セッションが120を先に読み込まないよう、native設定の読み取り時点で変換する。プロ動画用の設定は対象にしない。
