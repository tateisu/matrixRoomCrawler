package crawler

import util.decodeUtf8
import util.isTruth
import java.io.File
import java.io.FileInputStream
import java.util.*

class Config(
    var verbose: Boolean = false,
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.72 Safari/537.36",

    var botServerPrefix: String = "https://matrix.juggler.jp/_matrix/client/r0",
    var mediaPrefix :String ="https://matrix.juggler.jp/_matrix/media/r0/download/",

    var botUser: String = "(not specified)",
    var botPassword: String = "(not specified)",

    var botAccessToken :String ="",

    var cacheExpireHours: Int = 1,
    var httpTimeoutMs: Long = 30000,
    var cacheDir :String = "cache",
    var outputDir:String = "web/public",
){
    val servers = TreeSet<String>()
}

fun parseConfig(filePath: String): Config {

    val reComment = """;;.*""".toRegex()
    val reCols = """\A(\S+)\s+(.*)""".toRegex()

    val dst = Config()
    var hasError = false

    fun parseLine(line: String) {
        if (line.isEmpty()) return
        val gr = reCols.find(line)?.groupValues
            ?: error("not 'name value' format. $line")
        val name = gr[1]
        val value = gr[2]
        when (name) {
            "verbose" -> dst.verbose = value.isTruth()
            "userAgent" -> dst.userAgent = value
            "botServerPrefix" -> dst.botServerPrefix = value
            "mediaPrefix" -> dst.mediaPrefix = value
            "botUser" -> dst.botUser = value
            "botPassword" -> dst.botPassword = value
            "botAccessToken"-> dst.botAccessToken = value
            "cacheExpireHours" -> dst.cacheExpireHours = value.toInt()
            "httpTimeoutMs" -> dst.httpTimeoutMs = value.toLong()
            "cacheDir" -> dst.cacheDir = value
            "outputDir" -> dst.outputDir = value
            "server" -> dst.servers.add( value)
            else -> error("unsupported config name: $name")
        }
    }

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
                parseLine(line)
            } catch (ex: Throwable) {
                println("$filePath $lineNum : ${ex.message}")
                hasError = true
            }
        }
    if (hasError) error("$filePath parse failed.")

    return dst
}
