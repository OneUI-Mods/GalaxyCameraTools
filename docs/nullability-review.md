# Kotlin nullable契約の再照合（2026-10-31）

移行前Javaと比較し、正常なnullや省略入力を検証関数へ渡す前に`!!`で停止する経路を修正した。
グローバルに例外を無視したり、認証・入力検証を取り除いたりする修正ではない。

- 許可されたカメラからのzoom_curves_publishで引数が省略された場合、StockZoomCurves.decodeの既存のJSONExceptionと、それを変換するIllegalArgumentExceptionへ到達させる。カメラ以外のUIDや呼び出し元を許可する変更ではない。
- Android16/17の専用AOSPエミュレータだけに、com.sec.android.app.cameraというテスト専用のホストを入れ、署名済みrelease APKの実際のContentProviderへBinder通信する。Samsungカメラの実行や撮影を試すものではない。
- 共通の独立フィクスチャは[HomePod Bridgeの開発用ソース](https://github.com/OneUI-Mods/HomePodBridge/tree/v0.1.6-experimental/tools/fixtures/null-contracts)に収録する。実機へテスト専用ホストを入れない。

## 検証の範囲

修正前の署名済みrelease APKで、各該当経路のNullPointerExceptionをAndroid16/17の両方で再現した。
RootHalInputは無効なソース名を渡し、音声・ソケット・rootホスト作成前の既存ガードに到達するかを確認する。
独立したproviderテストは、テストプロセスのBinder呼出元情報だけを一時設定し、終了時に復元する。インストールされたアプリや外部IPCの認証を変更しない。
実機での再生・録画・撮影、全機能の組み合わせ、Android16のSamsung実機の動作保証とは別の確認である。
