# matrixRoomList

## これは何
- 複数のMatrixサーバの公開部屋リストを巡回して部屋リストを保存するkotlinコード
- Netlify経由で公開するWebページ
のセットです。

# Webページ
- Netlifyで公開する
- 元データは https://github.com/tateisu/matrixRoomList-web に置く
- pushして少し待つと https://matrix-room-list-jp.netlify.app/ で見れる

# クローラー
- kotlinで書いた(このリポジトリ)
- 設定ファイルにサーバリストを書くと巡回する
- botがログインしたサーバのAPIを経由する


