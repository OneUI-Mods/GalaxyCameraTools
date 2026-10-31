# Kotlinへの移行（2026-10-30）

Android側の実装、JVMテスト、実機用テストコードをKotlinへ移しました。ビルドはAndroid Gradle Pluginの組み込みKotlin / JVM 17を使用します。既存のパッケージ名、設定キー、provider authority、module entry、署名の識別は引き継ぎます。SDK・対象OS・機能の対応範囲を、この移行だけで拡大したものではありません。

C++・Goのソースは変更していません。JNIを持つアプリは、移行前のJava宣言とコンパイル後のクラスからメソッド名・JVM descriptor・static指定を比較しています。

## 動作の維持

- Android 16と17の既存の分岐・検証済み契約を維持します。`docs/compatibility/android16-source.json`がある場合、そのJavaパスとハッシュは過去の固定コミットの記録です。現在のKotlinソースへ書き換えません。
- Javaの数値変換、不変コレクション、nullを受ける復旧経路、recordのコンストラクタ補正・文字列表現を維持します。永続化した設定や確認用識別値を別の形式に変更しません。
- 配布用APKへ検証用Activityや実機テストを含めません。デスクトップ専用のDEX調査ツールはAndroidアプリの実装対象に含めません。

## 確認

```sh
./gradlew :app:compileReleaseKotlin :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

既存JVMテスト22件とrelease lintを確認しています。実機用コードはコンパイルの確認と、端末上での実行を区別します。この初期ビルド確認の段階では実機操作は未実施でした。後続の実機確認と区別し、最新の範囲は下記を参照してください。

Kotlinの整形は`ktfmt --kotlinlang-style`を使用します。生成コードを更新した場合も整形とビルドを確認してください。配布時は既存のリリース手順を使い、同じ鍵で署名したrelease variantを検証します。移行ブランチのビルド成果物を、そのまま公開リリースとして扱わないでください。

## エミュレータによる再調査（2026-10-30）

Google APIsのarm64エミュレータ、Android 16（API 36）とAndroid 17（API 37）で、rootを使わずに確認しました。このエミュレータ確認段階では、実機と既存のADB接続は操作していません。

- 設定・ライセンスなど、このアプリに存在する通常Activityの起動、タブの切り替え、Activity再生成を確認。
- 通常表示、ダーク表示＋文字サイズ150%、release variantでのダーク表示＋文字サイズ200%を確認。ライセンス一覧があるアプリでは検索・一覧の再配置・詳細への移動も実行。
- release variantの試験にはローカル専用の試験署名コピーを使用。配布用の署名や公開APKとは区別しています。検証用runnerは別APKです。
- 移行前コミットからJava版を別worktreeでビルドし、同じエミュレータの通常表示とJVMテストで比較。
- 実際の設定スイッチ1個を変更し、プロセス終了後の保持を確認。同じ設定をJava版で変更してからKotlin版へ上書き更新し、値が残ることも確認し、最後に元へ戻しました。全設定の組合せを網羅した試験ではありません。
- 再調査で判明したライセンス一覧初回表示のクラッシュを修正。Androidから渡されるnullの再利用ビューを受け取れるようにしました。

このエミュレータ試験の結果は、rootなしでアプリ側の画面と設定が動くことを確認したものです。Samsung SystemUI／One UIホーム／Home Up／Camera／Camera Assistant、Vectorのフック実行、root権限、実際の録画・通話音声・物理デバイスの経路は検証していません。Kotlin化前と全機能が同一と断定するものではありません。

## 解除後のAndroid 17実機確認（2026-10-30）

継続使用中と同じ鍵の署名済みrelease版を上書きし、メイン画面が前面に表示されることをUIツリーで確認しました。ライセンス一覧の表示も確認しています。機能スイッチの変更を目的としたON／OFF操作はせず、全アプリの設定を確認前後で退避・照合しました。

Camera Assistantのみをアプリ再起動し、純正設定内のGalaxy Camera Tools項目の表示を確認しました。設定値を変更せず、カメラでの撮影・fps・切り替えアニメーションの測定は行っていません。

今回のGUI確認中はOS・system_server・SystemUIを再起動せず、録画開始・音声再生も行っていません。初期のビルド確認や先行するSystemUIの読み込み直しとは別の確認段階です。画面が表示できることだけで全機能を確認済みとは扱いません。Android 16はエミュレータと既存契約までで、Android 16実機の再試験は行っていません。

## 2026-10-31 実行時の意味の再照合（0.1.5-experimental）

元のJavaソースとの照合で以下を修正した。

- Camera2のメタデータ用キー3か所で、Integer.class / Float.classがKotlinのprimitive Classへ変わっていた。CameraMetadataTypesでboxed Classを明示し、フラッシュのレンズ位置、熱情報、ズーム比の型を元へ戻した。
- Expert RAWのスナップショット通知proxyで、引数なしのtoString/hashCodeに渡されるnullのargs配列を強制展開していた。Android依存のないSnapshotCallbackを実際のproxy生成に使い、Objectメソッドと本来の通知を分離した。
- PhotoZoomTiming17からpreviewSurface()の正常なnullをコンストラクタへ渡す前に強制展開していた。コンストラクタがIllegalArgumentExceptionを返して既存の純正フォールバックへ進む元の経路を復元した。

型契約2件・実proxy呼び出し4件の回帰テストを追加。撮影画像や音声を使わないAndroid16/17上の検証と、実機での読み込み確認は、署名済み配布版の完成後に追記する。全撮影モード・fpsの測定はこの静的照合だけでは確認できない。

### 修正版の検証結果

- Camera ToolsのJVM試験28件（新規6件）、release Lint、依存ライセンス57件の照合、署名・16KiBアライメント・配布構造検査が成功。
- Android16（API36）とAndroid17（API37）の専用エミュレータで、署名済みrelease APKのクラスを隔離したapp_processから直接読み込んだ。Camera2の実Key生成、実proxyの引数なしObjectメソッドと通知、プレビューnull時にIllegalArgumentExceptionへ戻るコンストラクタ経路を両方で確認した。検証DEXは配布APKへ入れていない。
- Android17のGalaxyへ同じ署名のreleaseを上書きし、インストール済みAPKのSHA-256が配布APKと一致した。純正カメラ、Expert RAW、Camera Assistantだけを読み込み直し、設定接続・写真プレビューのfps要求・熱情報取得・READY状態を確認。設定差分0、3ホストともクラッシュ保護非停止・クラッシュ回数0。OS/system_server/SystemUIは再起動せず、撮影・録画・音声再生は行っていない。
- 全16アプリのrelease組み立て・Lintと、合計578件のJVM試験が成功。元のJavaのnullable return・boxed Class・proxy・JNI等を再照合した追加の確定修正は本アプリの5か所。全撮影モードのfps、映像の自然さ、音声入出力、Android16のSamsung実機動作は今回未測定。
