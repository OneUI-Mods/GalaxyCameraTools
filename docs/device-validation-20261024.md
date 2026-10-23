# レンズ切り替えの検証 — 2026-10-24

対象はSM-S948Q / Android 17 / One UI 9 BZI9、Samsung Camera 17.0.00.43、Expert RAW 6.0.02.8。0.1.0の撮影・録画機能の検証は[前回の記録](device-validation-20261023.md)に分ける。

## 純正カメラのイージング

通常写真のtable-based zoomを、純正の数値カーブからChoreographerのframe timeで補間する方式へ変更した。各フレームの現在値だけを更新するため、フレーム落ちの後に未処理の設定値を連打しない。元のdurationと両端のzoomを保持し、native runnerのgenerationを更新して旧stepを無効にする。

実機で1→3倍は336ms、3→5倍は272ms、5→1倍は592ms、1→0.6倍は144msの純正曲線を取得した。描画同期の完了と、映像プレビューのfpsは別に評価する。測定はPhotoMakerのTotalCaptureResultに含まれるSENSOR_TIMESTAMPの間隔を用いる。ズーム開始後1.5秒の集計であり、動画ファイルのフレーム数やセンサーの全読み出しを直接測った結果ではない。

preview requestへ60fpsを要求しても、切り替え経路によって約30fpsになる状態が残る。CONTROL_AE_TARGET_FPS_RANGEの要求値・結果値がともに[60,60]でも成立するため、metadataの値だけで60fps達成と判断しない。preview-only transient actionのzoom bit除去と、一時的なVIDEO_RECORD capture intentを比較したが、5倍側・戻り側の低下は解消しなかった。これらの試験的変更と初期session preview builderの上書きは、最終実装から取り除いた。保存写真・録画のbuilder、温度保護は変更しない。

最終構成ではSurfaceFlingerの対象preview BLAST layerの実present時刻も照合した。静止中約30.0fps、3倍約30.0fps、5倍約30.0fps、1倍へ戻る区間約29.4fpsで、callback側の結果と整合した。この条件では静止中も30fpsであり、「切り替えだけが30fps」「全レンズで常時60fps」を達成済みとは記載しない。端末のthermal statusは0だった。初期sessionの上書き有無を比べても改善せず、既存のpreview requestの経路を維持した。0.1.0の別条件で約59fpsだった記録は、今回の条件へ流用しない。

候補版でnative generationフィールドをLongとして扱った際にCameraのJava例外が1回発生した。実機のintフィールドをNumberとして読み、setIntで更新するよう修正済み。通常のforce-stopやAPK更新は異常終了の履歴へ加えない。

## Expert RAW

純正Cameraが実行時に生成したカーブの数値だけを、呼び出し元を確認するProviderへ共有する。firmwareが一致するキャッシュのみを使い、RAWのレンズIDと倍率を対応させる。Samsungのクラス・画像・曲線の固定配列は同梱しない。共有がまだない場合は純正のPathInterpolatorに相当するfallbackを使う。

既に要求されたPREVIEW_CHANGE snapshotがあるケースも、その取得完了へ接続する。別のnative animationや既存callbackを重ねて置き換えない。取得済みsnapshotとincoming SurfaceViewの表示scaleから画角を変え、新レンズのpreview readyに合わせてフェードする。RAW撮影request、crop、保存設定は変更しない。

1→3、3→5、5→1、1→0.6、0.6→1倍の5経路で、純正曲線の使用、新previewへの移行、表示の復元を実機ログと画面録画で確認した。新レンズ準備を含む受け渡しは約0.48〜0.71秒だった。snapshotを拡大・縮小する表示は、純正Cameraがlogical camera内で行うライブ映像の連続ズームと完全に同じではない。レンズ準備が遅い場合の待ち時間も残る。

## 復帰・設定

純正写真とExpert RAWの各スイッチを専用アプリからOFF/ONし、保存値と実行ログを照合した。OFFでは追加のzoom motionが起動せず、ONでは起動する。確認後は元のON設定へ戻した。他の5設定も保持した。

純正Cameraでは切り替え中にホームへ戻した後の再起動、RAWでは1→5倍の途中でホームへ戻してpreview ready前の表示復元を確認した。その後に開き直し、1→0.6→1倍で新previewへの復帰も確認した。RAW純正UIが切り替え中の入力を受け取らないケースは、追加のqueued requestが来たことの証明には使っていない。今回の最終候補で新しいJava crashはなく、Cameraのguard countは前述の候補版の1回から増えていない。RAWのguardは停止していない。

## ビルドと配布

release variant、JUnit 11件、lintReleaseを検証する。署名・API 102 metadata・scope・SDK 37・配布用Activity・依存ライセンス55件・16KiB alignmentはtools/package_release.pyで照合する。配布は署名済みrelease APK、指定commitのsource ZIP、SHA-256一覧のみ。実機画像・録画・端末ログ・Samsung APK・逆コンパイル結果は公開しない。
