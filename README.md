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



# roomSpeed について
- roomSpeedBot.pl インバイトに応じるbot。
- roomSpeed.pl サーバが知ってる流速を集計するbot。
- 流速のビューの作成は https://lemmy.juggler.jp/post/882 を参照

# 部屋別メッセージ数の集計のためのインデクス
create index x_events_message_speed on events(room_id,origin_server_ts) 
where type='m.room.message';

# 部屋別メッセージ数を集計するビュー
CREATE OR REPLACE VIEW room_speed as 
 SELECT events.room_id,
    count(*) AS speed
   FROM events
  WHERE events.type = 'm.room.message'::text AND events.origin_server_ts::double precision >= (date_part('epoch'::text, CURRENT_TIMESTAMP) * 1000::double precision - 86400000::double precision)
  GROUP BY events.room_id;

# 集計して部屋の公開エイリアスと結合
select speed,canonical_alias from room_speed 
left join room_stats_state on room_stats_state.room_id = room_speed.room_id 
where speed>0 order by speed desc;
