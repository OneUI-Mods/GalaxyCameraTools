# 0.1.2の検証 — 2026-10-24

対象: SM-S948Q / Android 17 / One UI 9 BZI9、Samsung Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8、Vector API 102。前回の記録は[0.1.1](device-validation-20261024.md)を参照。

## 動きと設定

純正Photoのtable-based zoomを、元のカーブにsmoothstepの時間補正を加えて実行した。1→3倍は336→454ms、3→5倍は272→367ms、5→1倍は592→799ms、1→0.6倍は144→320ms。native zoom workerへ送るのは最大約60回/秒、未処理の更新は最新1件とし、最終値が適用されてからnativeの終了処理を呼ぶ。queued値の置換・cancel・実行中の追加・例外の停止をJUnitで検証した。

SUPER_STEADYのcategoryはH2内でanimationを省略する。選択処理のscope中だけこの分岐を解除し、通常動画のSuper Steadyと水平ロックで0.6/1/2倍の補間・完了を確認した。純正の解像度・fps・録画状態の判定は残す。UHD120で手ブレ補正を有効にできるとする更新ではない。

Expert RAWはexactな倍率ペアのcurveを先に探し、なければ広いcurveの区間を使う。Camera側と同じ選択順を使い、長いcurveの切り出しがexact pairを上書きしない。1→3→5→1→0.6→1倍でnative snapshot/new previewの受け渡し、途中でホームへ戻った場合の復元を確認した。

純正のmotion設定をFPS設定から分離した。Providerの初回作成時に旧FPS値を一度だけ移し、その後は独立して保存する。Camera Assistantの設定変更がProviderへ反映されること、専用アプリ側からの変更がAssistantへ戻ること、追加項目が表示されることを確認した。

スーパー手ブレ補正ONのFHD30で実際に録画しながら0.6→1→2→0.6→1倍を操作した。320/432msの補間と完了を確認し、保存した6.912秒の1920×1080/30fps HEVCと48kHz stereo AACを読み出した。水平ロックへ切り替えた後の0.6/1/2倍も補間と完了を確認した。試験メディアは作成したIDだけを削除し、録画のサイズ・fpsと補正OFFを試験前の状態へ戻した。

## 負荷対策

- callbackの追加hookを1本にまとめた。通常fps統計は200msごとのmetadata取得・5秒ごとの通知、flash用は100msごと。ズーム診断は1.5秒の窓に限定する。
- 反射探索をClassValueでcacheし、Motionで使うhandleは開始時に解決する。未使用のextra map生成、同じstatusの重複enqueue、毎フレームのRect生成とcurve durationの再走査を減らす。
- Native側の小さいsetup callのinliningを考慮し、connectCamDeviceのsetupだけをdeoptimizeする。画像処理ループやrepeating request loopはdeoptimizeしない。
- 背面通常写真のlow-load previewはOFF=1920×1440、ON=1440×1080を実機で確認した。native deferred profileとproducer bufferの両方を合わせ、縦横比を保つ。映像の画素数は2,764,800→1,555,200、43.75%減る。OFFで元のサイズへ戻る。保存したJPEGは4000×3000で、decodeも成功した。

CPUはFPS追加設定OFF、同じ約16.7fpsの暗い室内で、/procの各threadのuser+system ticksを約7秒ずつ比較した。Cameraアプリprocessはlow-load OFF=8.08%、ON=7.90%（1 core=100%）。単一の短い順次測定であり、差は小さい。CPU削減率の達成や端末全体の発熱改善とは記載しない。Camera HAL、ISP、GPU、他processの負荷を含む測定でもない。

## 60fps経路の状態

preview builderへ適用するタイミングを、setPublicSetting/setSessionKeysの完了後、createBuilderConfig・startPreviewRepeating・applySettingsの開始前へ変更した。global/登録済みphysicalのFPSを退避し、native thermal hint時は復元する。still/record mapは変更しない。登録されていないphysical IDへは書き込まない（[AOSP Builder実装](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/CaptureRequest.java)参照）。

3倍方向などで約57〜59fpsを観測した。一方、5倍側・戻り側・その後の切り替えでは、要求・結果が[60,60]でも約30fpsへ落ちる状態が残った。SurfaceFlingerのpresent履歴とTotalCaptureResult.SENSOR_TIMESTAMPの両方を使ったが、集計窓は同一ではないので各数値を1対1で比較しない。初期設定ログと実際のbuffer sizeも照合した。

Android thermal statusとは別にSamsungのoverHeatHint=1/2が出る場合もあり、その場合はnativeの[10,30]と低照度の約15〜19fpsへ戻ることを確認した。しかし、冷却後のcameraHeat=0でも30fps低下を再現した。温度保護だけが原因という仮説は棄却した。全レンズ・全経路60fpsは未達成である。

previewのtransient bit、capture intent、zoom jump hint、HDR、shooting modeを変える比較では低下を解消できなかったか、熱条件が揃わず有効性を判断できなかった。これらの試験的overrideは最終版からすべて除去した。native preview target scheduleもmainPreview=1であり、preview targetだけが半分に間引かれる状態は確認しなかった。残る低下をHALの不変の制限と断定せず、未解決事項として残す。

## 検証と配布

JUnit 19件、release build、lintReleaseを実行する。release署名、SDK 37、API 102、scope、test Activity混入、55 runtime依存ライセンス、16KiB alignmentはtools/package_release.pyで検査する。試験の写真・動画・端末ログ・Samsung APK・逆コンパイル結果は公開せず、端末の試験メディアは作成時のIDを照合して個別に削除する。
