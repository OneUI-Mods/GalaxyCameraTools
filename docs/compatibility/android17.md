# Android 17 / BZI9プロファイル

対象：SM-S948Q、CP2A.260605.016.S948QOPU1BZI9。アプリversionNameも完全一致で照合する。対象外の版には適用しない。

## 写真

MakerBase.setPublicSetting / setSessionKeysの完了後、PhotoMakerBase.createBuilderConfig / startPreviewRepeating / MakerBase.applySettingsの開始前に実際のmPreviewRequestBuilderMapを検査する。vendor helperへの呼び出しがinliningされた場合にも、preview builderの最終値を整える。still/record mapは変更しない。通常写真MODE_CUSTOM_SETTING=0、Maker type=1、shooting mode=1/2、固定60fps、実際のpreview sizeのSurfaceTexture最小フレーム時間を照合する。capabilityはcacheし、Androidの熱状態はイベントで更新する。Samsung overHeatHintが正の場合にも退避したglobal/physical fpsを復元し、native制限へ戻す。

## 動画

nativeのl9.a2解像度enum、e5 feature map、te.c0通常動画候補、d8.rのcommandと設定対応、g9.qの選択表示をつなぐ。value=trueでpro_videoまたはvideo対応の120fps経路のみを対象にする。背面UHD120には既存のPro側commandを表示に使い、MODE_CUSTOM_SETTING=1の間だけ通常動画の設定キーへ渡す。encoder・sensor mode・bitrateの表は純正を使う。未報告の解像度enumを生成しない。

## Expert RAW

6.0.02.8のCommonEngine.switchCameraを、既存PREVIEW_CHANGE snapshot callbackの後へ受け渡す。前後・比率などの純正アニメーションが既にある場合は二重に要求しない。最大600msのsnapshot待ちと例外時のfallbackを設け、撮影中・背景化・破棄は待ちを取り消す。連続選択は最後のレンズへまとめる。UIの独自コピーや追加Surfaceを作らない。レンズの画角変化では既存snapshot viewとpreview SurfaceViewの表示変形を使う。終了・中止時にscale/pivot/clip/frame-rate要求を復元する。Cameraから受け取るのは検証済みの数値zoom curveのみで、同じfirmwareのキャッシュだけを読み出す。

## Camera Assistant

5.0.00.7のa1 SettingsFragmentへnative CameraSwitchPreferenceを挿入する。SamsungのテーマとPreferenceScreenを使用し、純正の設定値には保存しない。module providerはホストのcaller packageとUIDを照合し、変更を許可するのはmodule UIとAssistantだけ。

## 写真のズーム描画

ZoomView.H2のtable-based zoomは、frame timeをsmoothstepで補間した純正数値曲線を使う。durationは純正の1.35倍、下限320ms・上限1100msとする。120Hzパネルでもnative requestは最大約60回/秒にまとめる。Camera2の変更は純正のzoom Handlerへ1件だけ送り、後続フレームは最新値へ置き換える。最終値の適用後にnativeの終了処理を呼ぶ。世代変更・clear・OFFでqueued処理を取り消し、OFFでは対応するnative curve位置から再開する。

SUPER_STEADYではH2内のanimation bypassだけを、選択処理の同期scope中に解除する。touch/capabilityチェックや撮影可能な解像度・fps・録画stateは変えない。水平ロックも同じcategoryを使う。native ValueAnimator経路は開始時に同じ時間補正と加減速を適用し、native listenerの終了処理を保つ。純正カメラのアニメーションは独立設定に分離し、旧FPS設定から一度だけ初期値を移す。

Expert RAWも同じ時間補正を使う。frameごとのcurve duration走査とRect生成を避ける。CameraとRAWの曲線cacheは取得時点のfirmwareを照合する。

## 常時処理の軽量化

プレビュー結果の追加hookは1つにまとめ、診断値とピント用sampleへ配る。通常のfps統計は200msごとのmetadata取得と5秒ごとの通知、flash用のmetadataは100msごとにし、保持に入った後のリクエスト制御は従来どおり行う。ズーム診断は1.5秒の窓だけを集計する。フィールド・メソッドの探索はClassValueでcacheし、Motionが繰り返し使うhandleは開始時に確保する。未使用のhook extra mapを遅延生成し、同じstatusのworker taskを重ねて送らない。CPU削減率はこれらの件数だけから推定しない。

## 低負荷プレビュー

vb.g.aでproducer bufferを選び、gd.i.pのPhoto deferred profileも同じ既存a2 enumへ合わせる。vb.g.aとvb.c.kのsetupのみをdeoptimizeする。logical 20のSurfaceTexture出力に存在し、同じ比率・短いframe durationを持つ最大1440px辺の候補を選ぶ。非対応時・前面・他モードではnativeのままにする。OFFは次回のカメラ初期化でnative profileへ戻る。

## 0.1.3の写真表示

写真FPSとmotionが両方ON、対応する背面preview、高温でない場合だけPhotoPreviewTransition17を使う。Cameraのpreview Surfaceを1回取得し、表示のscaleとfadeをframe timeで更新する。最終倍率は純正workerへ1回要求する。コピーの期限250ms、表示復元の期限2秒を持ち、Surface変更・OFF・clearでは元のscale/pivot/clip/frame-rateへ戻す。撮像が最終倍率に移った後のOFFで古い倍率を再送しない。その他の経路は上記0.1.2のrequest補間を使う。詳しくは[0.1.3の検証](../device-validation-0.1.3.md)を参照。
