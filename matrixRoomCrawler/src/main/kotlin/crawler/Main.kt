@file:Suppress("MemberVisibilityCanBePrivate")

package crawler

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import util.JsonArray
import util.JsonObject
import util.cachedGetBytes
import util.cachedGetString
import util.decodeJsonObject
import util.encodeUtf8
import util.escapeUrl
import util.getContentString
import util.jsonObject
import util.notEmpty
import util.saveFile
import util.showUrl
import util.toJsonArray
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.*
import javax.imageio.ImageIO
import kotlin.collections.set

lateinit var config: Config

val verbose by lazy { config.verbose }
val cacheDir by lazy { File("cache").apply { mkdirs() } }
val dataDir by lazy { File("web/public").apply { mkdirs() } }
val mediaDir by lazy { File(dataDir, "avatar").apply { mkdirs() } }
val dataFile by lazy { File(dataDir, "data.json") }


fun createHttpClient(block: HttpClientConfig<*>.() -> Unit = {}) =
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

        block()
    }

fun JsonObject.encodeQuery() =
    this.entries.sortedBy { it.key }
        .joinToString("&") { it.key.escapeUrl() + "=" + it.value.toString().escapeUrl() }

val reMxcUrl = """\Amxc://([^/]+)/([^/?&#]+)""".toRegex()
val reRoomServer = """:([^:]+)\z""".toRegex()

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

val reRoomSpec = """\A#([^#:!@]+):([^:]+)""".toRegex()

class Main(
    val client: HttpClient,
    val client2: HttpClient,
) {
    // アクセストークン。設定ファイルから供給されるか、ログインして得られるか
    var botAccessToken = config.botAccessToken

    // matrixApiで最後に得た内容。JSONパース失敗時に使う
    var lastContent = ""

    // matrixのAPIを呼び出す
    suspend fun matrixApi(
        method: HttpMethod,
        path: String,
        form: JsonObject? = null,
        useAdminApi: Boolean = false,
    ): JsonObject {
        var url = "${if (useAdminApi) config.adminApiPrefix else config.botServerPrefix}$path"

        val headers = HashMap<String, String>()
        if (botAccessToken.isNotEmpty()) {
            headers["Authorization"] = "Bearer $botAccessToken"
        }


        lastContent = when (method) {
            HttpMethod.Get -> {
                if (form?.isNotEmpty() == true)
                    url = "$url${if (url.any { it == '?' }) "&" else "?"}${form.encodeQuery()}"
                client.cachedGetString(cacheDir, url, headers)
            }
            HttpMethod.Post -> {
                showUrl(method, url)
                client.submitForm()
                client.request(url) {
                    this.method = method
                    header("Content-Type", "application/json")
                    headers.entries.forEach { header(it.key, it.value) }
                    setBody(form?.toString()?.encodeUtf8() ?: ByteArray(0))
                }.getContentString()
            }
            else -> error("matrixApi: unsupported http method $method")
        }
        return lastContent.decodeJsonObject()
    }

    fun chooseRoomInfo(roomId: String, map2: HashMap<String, JsonObject>): JsonObject {
        if (map2.size == 1) return map2.values.first()
        val roomServer = """:([^:]+)\z""".toRegex().find(roomId)?.groupValues?.elementAtOrNull(1)
        if (roomServer != null) {
            map2.entries.find { it.key == roomServer }?.let { return it.value }
        }
        return map2.values.first()
    }

    val ignoreServers = setOf(
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

    suspend fun getAvatarImage(item: JsonObject) {
        val mxcPair = item.string("avatar_url")?.decodeMxcUrl()
            ?: return
        val (site, code) = mxcPair

        if (ignoreServers.contains(site)) return

        item["avatarUrlHttp"] = "/avatar/${site}/${code}"
        val saveFile = File(File(mediaDir, site).also { it.mkdirs() }, code)

        // 変換済のファイルがあるなら何もしない
        if (saveFile.exists()) return

        val fromUrl = "${config.mediaPrefix}${site}/${code}"

        val bytes = try {
            client.cachedGetBytes(cacheDir, fromUrl, silent = true)
        } catch (ex: Throwable) {
            println(ex.message)
            return
        }

        val image1 = ByteArrayInputStream(bytes).use {
            @Suppress("BlockingMethodInNonBlockingContext")
            ImageIO.read(it)
        }
        if (image1 == null) {
            println("$fromUrl ImageIO.read() returns null")
            // たぶんWebPかSVGなのでそのまま保存する
            saveFile(saveFile, bytes)
        } else {
            @Suppress("BlockingMethodInNonBlockingContext")
            ImageIO.write(image1.resize(64, 64), "png", saveFile)
        }
    }


    val cachePublicRooms = HashMap<String, List<JsonObject>>()

    suspend fun getPublicRooms(site: String): List<JsonObject> {
        var list = cachePublicRooms[site]
        if (list == null) {
            list = ArrayList()
            cachePublicRooms[site] = list
            // pagination token
            var pageToken = ""
            while (true) {
                val params = jsonObject("server" to site, "limit" to 3000)
                if (pageToken.isNotEmpty()) params["since"] = pageToken

                val root = try {
                    matrixApi(HttpMethod.Get, "/publicRooms", params)
                } catch (ex: Throwable) {
                    if (ex.message?.startsWith("get failed. ") == true) {
                        println(ex.message)
                        break
                    }
                    throw ex
                }

                if (pageToken.isEmpty())
                    println("$site total_room_count_estimate=${root.long("total_room_count_estimate")}")

                val chunk = root.jsonArray("chunk")!!.objectList()
                println("$site list.size=${chunk.size}")
                list.addAll(chunk)
                pageToken = root.string("next_batch").notEmpty() ?: break
            }
        }
        return list
    }

    suspend fun run() {
        println("cacheDir=${cacheDir.canonicalPath}")
        println("dataDir=${dataDir.canonicalPath}")

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

/*
    admin api を呼び出す前に、DBを操作してログインユーザにadmin=1を設定したり、rate limitをオーバライドしたりする
    $ psql -U matrix1 matrix1
    \x
    select * from users where name like '%tateisu%';
    update users set admin=1 where name='@room-list-crawler:matrix.juggler.jp';
    insert into ratelimit_override values ('@room-list-crawler:matrix.juggler.jp', 0, 0);
*/
//        var nextBatch :String? = null
//        var roomCount = 0
//        while(true){
//            val form = jsonObject()
//            nextBatch?.let{ form["from"]=it}
//            val root = matrixApi(HttpMethod.Get,"/rooms",form=form,useAdminApi=true)
//            val rooms = root.jsonArray("rooms")?.objectList()
//            if(rooms!=null){
//                roomCount += rooms.size
//                for( room in rooms){
//                    if( room.boolean("public")==true){
//                        println("${room.string("canonical_alias") ?: room.string("room_id")} ${room.long("state_events")}")
//                    }
//                }
//            }
//            nextBatch = root.string("next_batch") ?: break
//        }
//        println("roomCount=$roomCount")
//        return

        // rooms[room_id][via_server] = jsonobject
        val roomsMap = HashMap<String, HashMap<String, JsonObject>>()
        fun addRoom(room: JsonObject, viaServer: String) {
            val roomId = room.string("room_id")
                ?.notEmpty() ?: return

            var map2 = roomsMap[roomId]
            if (map2 == null) {
                map2 = HashMap()
                roomsMap[roomId] = map2
            }
            map2[viaServer] = room
        }

        // 指定されたサーバリストを順に
        val servers = HashSet<String>()
        config.servers.forEach { server ->
            getPublicRooms(server).forEach {
                servers.add(server)
                addRoom(it, server)
            }
        }

        // 指定されたルームリストを順に
        val extraServers = HashSet<String>()
        for (roomSpec in config.rooms) {
            val gr = reRoomSpec.find(roomSpec)?.groupValues ?: error("can't find room $roomSpec")
            val site = gr[2]
            val root = matrixApi(
                HttpMethod.Post,
                "/publicRooms?server=$site",
                jsonObject(
                    "limit" to 20,
                    "filter" to jsonObject("generic_search_term" to roomSpec)
                )
            )
            val room = root.jsonArray("chunk")?.objectList()?.find { it.string("canonical_alias") == roomSpec }
            if (room == null) {
                println("room $roomSpec not found! $lastContent")
            } else {
                println("room $roomSpec found!")
                extraServers.add(site)
                addRoom(room, site)
            }
        }

        // ある部屋を複数のサーバで見かけたなら重複しないようにする
        val rooms = roomsMap.entries
            .map { entry -> chooseRoomInfo(entry.key, entry.value) }
            .sortedByDescending { it.int("num_joined_members") }

        val serverWebUi = JsonObject()

        suspend fun getServerWebUI(server: String?): String {
            server ?: return config.fallbackWebUI
            serverWebUi.string(server)?.let { return it }
            return if (server == "matrix.org") {
                "https://app.element.io/"
            } else {
                val str = try {
                    val checkUrl = "https://$server/"
                    client2.get(checkUrl).let { res ->
                        val location = res.headers[HttpHeaders.Location]
                        println("$server $checkUrl res=${res.status} location=${location}")
                        when {
                            res.status == HttpStatusCode.OK -> "https://$server/"
                            location == null -> config.fallbackWebUI
                            location.startsWith("/") -> "https://$server$location"
                            else -> location
                        }
                    }
                } catch (ex: Throwable) {
                    // connection problem?
                    ex.printStackTrace()
                    config.fallbackWebUI
                }
                if (str.endsWith("/")) str else "$str/"
            }.also { result ->
                serverWebUi[server] = result
                println("getServerWebUI $server $result")
            }
        }

        // Webページでの表示に合わせた調整
        rooms.forEach { item ->
            val roomAlias = item.string("canonical_alias")?.notEmpty() ?: item.string("room_id").notEmpty()!!
            item["canonical_alias"] = roomAlias

            val server = reRoomServer.find(roomAlias)?.groupValues?.elementAtOrNull(1)
            item["linkWebUI"] = getServerWebUI(server) + "#/room/" + roomAlias

            item["world_readable_int"] = if (item.boolean("world_readable")!!) 1 else 0
            item["guest_can_join_int"] = if (item.boolean("guest_can_join")!!) 1 else 0

            getAvatarImage(item)
        }

        if (config.dumpRooms) {
            // コンソールに表示
            rooms.forEach { item ->
                val avatarUrl = item.string("avatarUrlHttp") ?: item.string("avatar_url")

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

                println("jm=$numJoinedMembers wr=$worldReadable gj=$guestCanJoin na=$name ca=$canonicalAlias id=$roomId to=$topic av=$avatarUrl")
            }
        }

        val tzTokyo = TimeZone.getTimeZone("Asia/Tokyo")!!
        val c = Calendar.getInstance(tzTokyo)
        val strNow =
            "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DATE)}T${c.get(Calendar.HOUR_OF_DAY)}-${
                c.get(Calendar.MINUTE)
            }-${c.get(Calendar.SECOND)}JST"

        val result = jsonObject {
            put("updatedAt", strNow)
            put("rooms", JsonArray(rooms))
            put("servers", servers.sorted().toJsonArray())
            put("extraServers", extraServers.sorted().toJsonArray())
            put("serverWebUi", serverWebUi)
        }

        saveFile(dataFile, result.toString().encodeUtf8())
    }
}

fun main(args: Array<String>) = runBlocking {
    config = parseConfig(args.firstOrNull() ?: "config.txt")
    createHttpClient().use { client ->
        createHttpClient { followRedirects = false }.use { client2 ->
            Main(client, client2).run()
        }
    }
}
