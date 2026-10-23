# Android 17 / BZI9プロファイル

対象：SM-S948Q、CP2A.260605.016.S948QOPU1BZI9。アプリversionNameも完全一致で照合する。対象外の版には適用しない。

## 写真

Camera 17.0.00.43のMakerBase.setPublicSetting / setSessionKeysの同期scopeで所有Makerを保持し、SemCaptureRequest.eのうちmPreviewRequestBuilderMapと同一のmapだけへCONTROL_AE_TARGET_FPS_RANGEを適用する。静止画・動画用mapには適用しない。通常写真MODE_CUSTOM_SETTING=0、Maker type=1、shooting mode=1/2、固定60fps範囲、実際のpreview sizeのSurfaceTexture最小フレーム時間、温度を照合する。実測はTotalCaptureResultのSENSOR_TIMESTAMPで集計する。

## 動画

nativeのl9.a2解像度enum、e5 feature map、te.c0通常動画候補、d8.rのcommandと設定対応、g9.qの選択表示をつなぐ。value=trueでpro_videoまたはvideo対応の120fps経路のみを対象にする。背面UHD120には既存のPro側commandを表示に使い、MODE_CUSTOM_SETTING=1の間だけ通常動画の設定キーへ渡す。encoder・sensor mode・bitrateの表は純正を使う。未報告の解像度enumを生成しない。

## Expert RAW

6.0.02.8のCommonEngine.switchCameraを、既存PREVIEW_CHANGE snapshot callbackの後へ受け渡す。前後・比率などの純正アニメーションが既にある場合は二重に要求しない。最大600msのsnapshot待ちと例外時のfallbackを設け、撮影中・背景化・破棄は待ちを取り消す。連続選択は最後のレンズへまとめる。UIの独自コピーや追加Surfaceを作らない。

## Camera Assistant

5.0.00.7のa1 SettingsFragmentへnative CameraSwitchPreferenceを挿入する。SamsungのテーマとPreferenceScreenを使用し、純正の設定値には保存しない。module providerはホストのcaller packageとUIDを照合し、変更を許可するのはmodule UIとAssistantだけ。
