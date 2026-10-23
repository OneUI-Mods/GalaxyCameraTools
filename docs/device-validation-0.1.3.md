# 0.1.3の検証 — 2026-10-24

対象は0.1.2と同じSM-S948Q / Android 17 / One UI 9 BZI9、Samsung Camera 17.0.00.43。前回の記録は[0.1.2](device-validation-0.1.2.md)を参照。

## 撮像と描画を分ける

Photoのレンズ変更では、AEの要求・結果が[60,60]、露光が約10ms、Samsungのheat hintが0でも、5倍側・戻り側・超広角側でSENSOR_TIMESTAMPの間隔が約33.3msになる。nativeのイージングをOFFにしても再現した。今回も撮像側の全経路60fpsは達成していない。

単独のCamera2プローブで、公開logical cameraと登録済みphysicalの5倍カメラを使い、1440×1080 YUVを消費する条件では約60fps / 16.67msを確認した。JPEGやRAW出力を構成に追加した比較でも約60fpsだった。これは純正Photoの全出力・内部処理を再現した試験ではなく、純正の30fpsを不変のセンサー制限と断定する根拠にはしない。

HDR preview、ZSL、初期化fps、独自recording fps、adaptive lens、frame duration、dynamic FPS、stream use caseを比較したが、純正Photoの低下を安定して解消できなかった。温度条件が揃わない結果は区別した。これらの試験的overrideや設定ファイル分岐は配布版からすべて除去した。温度保護・保存写真の処理・未登録physical IDへの設定は変更しない。

## 表示の受け渡し

写真60fpsとレンズmotionが両方ONの対応する背面Photoで、切り替え直前のSurfaceViewをPixelCopyで1回取得する。最大辺1280pxの1枚を使い、毎フレームの画像取得や新しいCamera2セッションは行わない。最終倍率を純正workerへ1回要求し、元の数値曲線と0.1.2の時間補正をChoreographerのframe timeで表示へ適用する。

拡大は直前画像を拡大し、切り替え先の要求を持つ連続した結果を待って新映像へfadeする。縮小は直前画像とぼかした周辺を使い、新映像を現在の画角に合わせて拡大してから等倍へ戻す。画像の端はGPUのalpha maskでなじませ、cover全体のfadeは一度だけ合成する。[RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)を使い、アニメーション中の画像コピーは行わない。

これは撮像のフレーム補間ではない。被写体が動く場合、直前画像の期間はその動きが止まって見える。結果のrequest倍率が一致しても新レンズの画質・露出・AFが完全に安定したと保証するものではない。新映像との露出差や、切り替え直後の精細さの差は残り得る。

コピー待ちは250ms、表示の受け渡し全体は2秒で打ち切る。取得失敗・Surfaceの変更・割り当て失敗は従来のnative requestによる補間へ戻す。OFF、世代変更、clearで表示・scale・pivot・clip・frame-rate要求を復元する。撮像が最終倍率へ移った後のOFFでは、古い途中倍率を再要求しない。高温時は新しい表示経路を使わず、途中で熱状態が変わった場合も終了する。動画・Expert RAWの撮影経路は変更しない。

## 実機での描画と復元

各操作前にgfxinfoをresetし、開始時のmonotonic timestampと同じ窓のIntendedVsyncを照合した。5倍への367msに22描画、5→1倍799msに48描画、1→0.6倍と0.6→1倍320msにそれぞれ19描画を保持し、各窓の連続描画間隔の中央値は約16.67ms、25ms超の間隔は0だった。3倍の窓では一時的に120HzのUI描画も記録されており、UI全体とズームのsample回数を同一視しない。

短い各gfxinfo集計のGPU 95パーセンタイルは3〜7ms、99パーセンタイルは3〜9msだった。戻り側の集計には1フレームのjankがあった。録画でも切り替え中の描画を確認した。被写体の撮像fpsは別のSENSOR_TIMESTAMP集計であり、こちらは約30fpsの経路が残る。短時間の順次試験で、長時間使用の発熱や端末全体の消費電力を改善したとする測定ではない。

途中で上スワイプしてホームへ移り、カメラへ戻ったときの全画面previewと操作UIの復元を確認した。FPS OFFではdisplay coverを使わず、従来のrequest補間へ戻る。motion OFFでは独自curve実行も行わない。ONへ戻すと新経路が再び動く。保存した設定は試験後に復元する。

途中のシャッター操作は純正のzoom-transition guardで拒否され、画像は作成されなかった。完了後の撮影は4000×3000 JPEGとして保存された。確認用メディアは作成したIDを照合して個別に削除する。SystemUIや端末本体は再起動しない。

## 検証と配布

JUnit 22件、lintRelease、release buildを実行する。幾何のテストでは、縮小中の旧画像と新映像の画角、画面に空白を作らない倍率、最終倍率での等倍復元を検証する。署名・SDK 37・API 102・scope・55 runtime dependencyのlicense・16KiB alignment・test Activity混入はtools/package_release.pyで検査する。

端末画像・録画・ログ・メーカーのバイナリ・逆コンパイル資料・検証用プローブを公開ソースやAPKへ含めない。
