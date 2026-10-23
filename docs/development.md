# 開発者向けガイド

利用者向けの説明は[README](../README.md)にあります。公開先は[GalaxyCameraTools](https://github.com/OneUI-Mods/GalaxyCameraTools)です。

## ソースとビルド

Android SDKのコンパイル対象は37です。JDK・Gradle・依存関係の版は、リポジトリ内のGradle設定とwrapperを基準にしてください。

```sh
./gradlew :app:assembleRelease
```

配布にはrelease variantを使い、配布鍵による署名とAPKの構造を検証します。debug APKやテスト専用画面を含むAPKを配布しません。署名鍵・パスワード・端末ログ・外部アプリのバイナリをGitへ追加しないでください。

## 設計・検証

未確認のOS・端末・対象アプリの版を対応済みと記載しないでください。実測・実機確認と、コード上で準備した互換処理を区別して記録します。READMEは機能、導入、更新、制約を説明し、内部API・フック・測定値はこのディレクトリに記録します。

## ドキュメント一覧

- [署名・配布手順](releasing.md)
- [Android 16の互換性](compatibility/android16.md)
- [Android 17の互換性](compatibility/android17.md)

## 現在の機能と実機記録

- [実装の範囲](implementation-status.md)
- [0.1.0の実機での検証と制限](device-validation-20261023.md)
- [0.1.1のレンズ切り替え検証](device-validation-20261024.md)

Camera2の追加レンズはVectorが選択したクライアントのプロセス内で扱います。共有の権限判定やcameraserverのバイナリは変更しません。入力したphysical camera IDは、サービスへの接続では親のlogical camera IDへ戻し、出力のコピーへphysical IDを付けます。アプリの公開device IDとnative requestのlogical IDを混同しないように分けています。API1や未確認のセッション方式への互換性は保証しません。

署名・ソースZIP・SHA-256の作成は`tools/package_release.py --source-ref HEAD`を使います。検証は`:app:testDebugUnitTest :app:lintRelease`です。検証用APKは別プロジェクトとし、配布APKにActivityやバイナリを混入させません。
