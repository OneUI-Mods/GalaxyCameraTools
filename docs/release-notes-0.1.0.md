# Galaxy Camera Tools 0.1.0 実験版

Samsung Camera・Camera Assistant・Expert RAWを調整する新しいモジュールです。写真プレビューの60fps、通常動画のFHD・UHD120fps、超広角ライト、フラッシュ時のピント固定を個別に設定できます。Camera Assistant内からも同じ設定を変更できます。

カメラ使用中のクイックパネルのライト操作と、Expert RAWの滑らかなレンズ切り替えを追加しました。一般のCamera2アプリでは、Vectorで選んだアプリに背面の追加レンズを公開できます。

Galaxy S26 Ultra（SM-S948Q）、Android 17 / One UI 9 BZI9向けです。Samsung Camera 17.0.00.43、Camera Assistant 5.0.00.7、Expert RAW 6.0.02.8、API 102対応Vectorとroot環境が必要です。

署名済みrelease APKをインストールして、READMEにある対象をVectorで選択してください。初期状態はすべてOFFです。対象アプリを強制停止して開き直し、SystemUIを対象にする場合はSystemUIも再読み込みしてください。一般カメラアプリの追加レンズは、カメラ権限を許可してからアプリを開き直して使用します。

120fpsは背面FHD・UHDの対応する16:9設定に限ります。8K・HD・前面・特殊な縦横比には追加しません。写真プレビューは端末側の処理や暗所・温度により要求したfpsに届かない場合があります。一般アプリの追加レンズも、すべてのアプリ・RAW・特殊な撮影方式の動作を保証するものではありません。使用できない解像度やfpsを作る機能ではありません。
