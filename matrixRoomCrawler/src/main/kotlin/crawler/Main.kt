package crawler

import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import util.*
import java.awt.Image
import java.io.File
import javax.imageio.ImageIO

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream


lateinit var config: Config

val verbose by lazy { config.verbose }

private val cacheDir by lazy { File("cache").apply { mkdirs() } }
private val dataDir by lazy { File("web/public").apply { mkdirs() } }
private val mediaDir by lazy { File(dataDir,"avatar").apply { mkdirs() } }
private val dataFile by lazy { File(dataDir,"data.json")}

fun JsonObject.encodeQuery() =
    this.entries.sortedBy { it.key }
        .joinToString("&") { it.key.escapeUrl() + "=" + it.value.toString().escapeUrl() }


val reMxcUrl = """\Amxc://([^/]+)/([^/?&#]+)""".toRegex()

fun String.decodeMxcUrl(): Pair<String, String>? {
    reMxcUrl.find(this)?.groupValues?.let { gr ->
        return Pair(gr[1], gr[2])
    }
    return null
}

fun BufferedImage.resize(w: Int, h: Int): BufferedImage =
    BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        .also { dst ->
            val g2d = dst.createGraphics()
            g2d.drawImage(this.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
            g2d.dispose()
        }


class Main(
    private val client: HttpClient
) {
    // アクセストークン。設定ファイルから供給されるか、ログインして得られるか
    private var botAccessToken = config.botAccessToken

    // matrixApiで最後に得た内容。JSONパース失敗時に使う
    private var lastContent = ""

    // matrixのAPIを呼び出す
    private suspend fun matrixApi(
        method: HttpMethod,
        path: String,
        form: JsonObject? = null
    ): JsonObject {

        var url = "${config.botServerPrefix}$path"

        if (botAccessToken.isNotEmpty()) url = "$url?access_token=${botAccessToken.escapeUrl()}"

        lastContent = when (method) {
            HttpMethod.Get -> {
                if (form != null) url = "$url${if (url.any { it == '?' }) "&" else "?"}${form.encodeQuery()}"
                client.cachedGetString(cacheDir, url)
            }
            HttpMethod.Post -> {
                showUrl(method, url)
                client.request<HttpResponse>(url) {
                    this.method = method
                    header("Content-Type", "application/json")
                    this.body = form.toString().encodeUtf8()
                }.getContentString()
            }
            else -> error("matrixApi: unsupported http method $method")
        }
        return lastContent.decodeJsonObject()
    }

    private fun chooseRoomInfo(roomId: String, map2: HashMap<String, JsonObject>): JsonObject {
        if (map2.size == 1) return map2.values.first()
        val roomServer = """:([^:]+)\z""".toRegex().find(roomId)?.groupValues?.elementAtOrNull(1)
        if (roomServer != null) {
            map2.entries.find { it.key == roomServer }?.let { return it.value }
        }
        return map2.values.first()
    }

    private val ignoreServers = setOf(
        "bousse.fr",
        "chat.cryptochat.io",
        "disroot.org",
        "synapse.travnewmatic.com",
        "dorfbrunnen.eu",
        "hispagatos.org",
        "ldbco.de",
        "librezale.eus",
        "privy.ws",
        "synapse.keyvan.pw",
        "synapse.travnewmatic.com",
        "ubports.chat",
        "mux.re",
        "chat.cryptochat.io",
        "matrix.intelsway.info",
        "horsein.space",
    )

    private suspend fun getAvatarImage(item:JsonObject){
        val mxcPair = item.string("avatar_url")?.decodeMxcUrl()
            ?: return
        val(site,code)=mxcPair

        if( ignoreServers.contains(site) ) return

        item["avatarUrlHttp"] = "/avatar/${site}/${code}"
        val saveFile = File(File(mediaDir, site).also { it.mkdirs() }, code)

        // 変換済のファイルがあるなら何もしない
        if( saveFile.exists()) return

        val fromUrl = "${config.mediaPrefix}${site}/${code}"

        val bytes = try {
            client.cachedGetBytes(cacheDir, fromUrl,silent = true)
        }catch(ex:Throwable){
            println(ex.message)
            return
        }

        val image1 = ByteArrayInputStream(bytes).use {
            @Suppress("BlockingMethodInNonBlockingContext")
            ImageIO.read(it)
        }
        if(image1 == null){
            println("$fromUrl ImageIO.read() returns null")
            // たぶんWebPかSVGなのでそのまま保存する
            saveFile(saveFile,bytes)
        }else{
            ImageIO.write(image1.resize(64, 64), "png", saveFile)
        }
    }

    suspend fun run() {
        // アクセストークンがなければログインする
        if (botAccessToken.isEmpty()) {
            val root = matrixApi(
                HttpMethod.Post,
                "/login",
                jsonObject("type" to "m.login.password", "user" to config.botUser, "password" to config.botPassword)
            )
            botAccessToken = root.string("access_token").notEmpty()
                ?: error("login failed. $lastContent")
            println("login succeeded. token=$botAccessToken")
        }

        // rooms[room_id][via_server] = jsonobject
        val roomsMap = HashMap<String, HashMap<String, JsonObject>>()

        // 指定されたサーバリストを順に
        for (server in config.servers) {
            // pagination token
            var pageToken = ""
            while (true) {
                val params = jsonObject("server" to server, "limit" to 3000)
                if (pageToken.isNotEmpty()) params["since"] = pageToken
                val root = matrixApi(HttpMethod.Get, "/publicRooms", params)
                println("$server total_room_count_estimate=${root.long("total_room_count_estimate")}")
                val list = root.jsonArray("chunk")!!.objectList()
                println("$server list.size=${list.size}")
                for (item in list) {
                    val roomId = item.string("room_id")?.notEmpty() ?: continue
                    var map2 = roomsMap[roomId]
                    if (map2 == null) {
                        map2 = HashMap()
                        roomsMap[roomId] = map2
                    }
                    map2[server] = item
                }
                pageToken = root.string("next_batch").notEmpty() ?: break
            }
        }

        // ある部屋を複数のサーバで見かけたなら重複しないようにする
        val rooms = roomsMap.entries
            .map { entry -> chooseRoomInfo(entry.key, entry.value) }
            .sortedByDescending { it.int("num_joined_members") }

        // Webページでの表示に合わせた調整
        rooms.forEach { item ->
            item["world_readable_int"] = if(item.boolean("world_readable")!!) 1 else 0
            item["guest_can_join_int"] = if(item.boolean("guest_can_join")!!) 1 else 0
            getAvatarImage(item)
        }

        if(false){
            // コンソールに表示
            rooms.forEach { item ->
                val avatar_url = item.string("avatarUrlHttp") ?: item.string("avatar_url")

                val roomId = item.string("room_id")!!
                val name = item.string("name")
                val topic = item.string("topic")
                val canonicalAlias = item.string("canonical_alias") ?: item.string("room_id")

                // num_joined_members	integer	Required. The number of members joined to the room.
                val numJoinedMembers = item.int("num_joined_members")

                // world_readable	boolean	Required. Whether the room may be viewed by guest users without joining.
                val worldReadable = item.boolean("world_readable")

                // guest_can_join	boolean	Required. Whether guest users may join the room and participate in item. If they can, they will be subject to ordinary power level rules like any other user.
                val guestCanJoin = item.boolean("guest_can_join")

                println("jm=$numJoinedMembers wr=$worldReadable gj=$guestCanJoin na=$name ca=$canonicalAlias id=$roomId to=$topic av=$avatar_url")
            }
        }


        saveFile(dataFile,JsonArray(rooms).toString().encodeUtf8())
    }
}

fun main(args: Array<String>) {

    config = parseConfig(args.firstOrNull() ?: "config.txt")
    println("cacheDir=${cacheDir.canonicalPath}")
    println("dataDir=${dataDir.canonicalPath}")

    HttpClient {
        // エラーレスポンスで例外を出さない
        expectSuccess = false

        install(UserAgent) {
            agent = config.userAgent
        }

        install(HttpTimeout) {
            val t = config.httpTimeoutMs
            requestTimeoutMillis = t
            connectTimeoutMillis = t
            socketTimeoutMillis = t
        }
//	install(HttpCookies) {
//		// Will keep an in-memory map with all the cookies from previous requests.
//		storage = AcceptAllCookiesStorage()
//
////		// Will ignore Set-Cookie and will send the specified cookies.
////		storage = ConstantCookiesStorage(Cookie("cookie1", "value"), Cookie("cookie2", "value"))
//	}

//	install(ContentEncoding) {
//		gzip()
//		deflate()
//	}
    }.use { client ->
        runBlocking {
            Main(client).run()
        }
    }
}

