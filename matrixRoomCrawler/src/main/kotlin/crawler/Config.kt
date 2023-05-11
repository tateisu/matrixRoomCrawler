package crawler

import util.decodeUtf8
import util.isTruth
import java.io.File
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.TimeUnit

class Config(
    var verbose: Boolean = false,
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.72 Safari/537.36",

    var botServerPrefix: String = "https://matrix.juggler.jp/_matrix/client/r0",
    var adminApiPrefix: String = "https://matrix.juggler.jp/_synapse/admin/v1",
    var mediaPrefix: String = "https://matrix.juggler.jp/_matrix/media/r0/download/",

    var botUser: String = "(not specified)",
    var botPassword: String = "(not specified)",

    var botAccessToken: String = "",

    var cacheExpireHours: Int = 1,
    var httpTimeoutMs: Long = TimeUnit.SECONDS.toMillis(300L),
    var cacheDir: String = "cache",
    var outputDir: String = "web/public",
    var dumpRooms: Boolean = false,

    var fallbackWebUI: String = "https://matrix-element.juggler.jp/",
) {
    val servers = TreeSet<String>()
    val rooms = TreeSet<String>()

    private fun parseKeyValue(name: String, value: String) {
        when (name) {
            "verbose" -> verbose = value.isTruth()
            "userAgent" -> userAgent = value
            "botServerPrefix" -> botServerPrefix = value
            "mediaPrefix" -> mediaPrefix = value
            "botUser" -> botUser = value
            "botPassword" -> botPassword = value
            "botAccessToken" -> botAccessToken = value
            "cacheExpireHours" -> cacheExpireHours = value.toInt()
            "httpTimeoutMs" -> httpTimeoutMs = value.toLong()
            "cacheDir" -> cacheDir = value
            "outputDir" -> outputDir = value
            "server" -> servers.add(value)
            "room" -> rooms.add(value)
            "dumpRooms" -> dumpRooms = value.isTruth()
            "fallbackWebUI" -> fallbackWebUI = value
            else -> error("unsupported config name: $name")
        }
    }

    private fun parseFile(filePath: String) {
        val reComment = """;;.*""".toRegex()
        val reCols = """\A(\S+)\s+(.*)""".toRegex()
        var hasError = false
        FileInputStream(File(filePath))
            .use { it.readAllBytes() }
            .decodeUtf8()
            .split("\n")
            .forEachIndexed { rawIndex, rawLine ->
                val lineNum = rawIndex + 1
                val line = rawLine
                    .replace(reComment, "")
                    .trim()
                try {
                    if (line.isNotEmpty()) {
                        val gr = reCols.find(line)?.groupValues
                            ?: error("not 'name value' format. $line")
                        parseKeyValue(name = gr[1], value = gr[2])
                    }
                } catch (ex: Throwable) {
                    println("$filePath $lineNum : ${ex.message}")
                    hasError = true
                }
            }
        if (hasError) error("$filePath parse failed.")
    }

    /**
     * コマンドライン引数を解釈する
     * -v,--verbose
     * -c,--config filename
     *
     * 設定ファイルが指定されていたらそれも読む
     */
    fun parseArgs(
        args: Array<String>,
        configFileDefault: String? = null
    ): List<String> {
        var configFile = configFileDefault
        val remainArgs = buildList {
            var i = 0
            while (i < args.size) {
                var arg = args[i++]
                when {
                    // -- だけの引数があると、残りの引数はオプションを解釈しない
                    arg == "--" -> {
                        addAll(args.copyOfRange(i, args.size))
                        break
                    }

                    // - で始まらまるなら多分オプション指定
                    arg.elementAtOrNull(0) == '-' -> {

                        // = で分割されているかもしれない
                        val splitValue = when (val splitPos = arg.indexOf('=')) {
                            -1 -> null
                            else -> {
                                val originalArg = arg
                                arg = arg.substring(0, splitPos)
                                originalArg.substring(splitPos + 1)
                            }
                        }

                        fun getValue() =
                            splitValue
                                ?: args.elementAtOrNull(i++)
                                ?: error("no value after option $arg")

                        when (arg) {
                            "-v", "--verbose" -> verbose = true
                            "-c", "--config" -> configFile = getValue()
                            else -> error("unknown option $arg")
                        }
                    }

                    // 普通の引数
                    else -> add(arg)
                }
            }
        }

        configFile?.let { parseFile(it) }

        return remainArgs
    }
}
