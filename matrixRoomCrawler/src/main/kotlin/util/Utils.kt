@file:Suppress("unused")
package util

import crawler.config
import crawler.verbose
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*

fun String.isTruth() = when {
	this == "" -> false
	this == "0" -> false
	this.startsWith("f", ignoreCase = true) -> false
	this.startsWith("t", ignoreCase = true) -> true
	this == "on" -> true
	else -> true
}

fun String.decodeHtmlEntities(): String = StringEscapeUtils.unescapeHtml4(this)

// split CharSequence to Unicode codepoints
fun CharSequence.eachCodePoint(block: (Int) -> Unit) {
	val end = length
	var i = 0
	while (i < end) {
		val c1 = get(i++)
		if (Character.isHighSurrogate(c1) && i < length) {
			val c2 = get(i)
			if (Character.isLowSurrogate(c2)) {
				i++
				block(Character.toCodePoint(c1, c2))
				continue
			}
		}
		block(c1.toInt())
	}
}

// split codepoint to UTF-8 bytes
fun codePointToUtf8(cp: Int, block: (Int) -> Unit) {
	// incorrect codepoint
	if (cp < 0 || cp > 0x10FFFF) codePointToUtf8('?'.toInt(), block)

	if (cp >= 128) {
		if (cp >= 2048) {
			if (cp >= 65536) {
				block(0xF0.or(cp.shr(18)))
				block(0x80.or(cp.shr(12).and(0x3f)))
			} else {
				block(0xE0.or(cp.shr(12)))
			}
			block(0x80.or(cp.shr(6).and(0x3f)))
		} else {
			block(0xC0.or(cp.shr(6)))
		}
		block(0x80.or(cp.and(0x3f)))
	} else {
		block(cp)
	}
}

private const val hexString = "0123456789ABCDEF"

private val encodePercentSkipChars by lazy {
	HashSet<Int>().apply {
		('0'..'9').forEach { add(it.toInt()) }
		('A'..'Z').forEach { add(it.toInt()) }
		('a'..'z').forEach { add(it.toInt()) }
		add('-'.toInt())
		add('_'.toInt())
		add('.'.toInt())
	}
}

fun String.encodePercent(): String =
	StringBuilder(length).also { sb ->
		eachCodePoint { cp ->
			if (encodePercentSkipChars.contains(cp)) {
				sb.append(cp.toChar())
			} else {
				codePointToUtf8(cp) { b ->
					sb.append('%')
						.append(hexString[b shr 4])
						.append(hexString[b and 15])
				}
			}
		}
	}.toString()

// same as x?.let{ dst.add(it) }
fun <T> T.addTo(dst: ArrayList<T>) = dst.add(this)

fun <E : List<*>> E?.notEmpty(): E? =
	if (this?.isNotEmpty() == true) this else null

fun <E : Map<*, *>> E?.notEmpty(): E? =
	if (this?.isNotEmpty() == true) this else null

fun <T : CharSequence> T?.notEmpty(): T? =
	if (this?.isNotEmpty() == true) this else null

fun ByteArray.digestSha256() =
	MessageDigest.getInstance("SHA-256")?.let {
		it.update(this@digestSha256)
		it.digest()
	}!!

fun ByteArray.encodeBase64UrlSafe(): String {
	val bytes = Base64.getUrlEncoder().encode(this)
	return StringBuilder(bytes.size).apply {
		for (b in bytes) {
			val c = b.toChar()
			if (c != '=') append(c)
		}
	}.toString()
}

fun ByteArray.decodeUtf8() = toString(Charsets.UTF_8)
fun String.encodeUtf8() = toByteArray(Charsets.UTF_8)

fun String.escapeUrl() = URLEncoder.encode(this, "utf-8")!!

inline fun <reified T> Any?.castOrThrow(name:String,block: T.() -> Unit){
	if (this !is T) error("type mismatch. $name is ${T::class.qualifiedName}")
	block()
}

// ?????????????????????????????????????????????????????????
@Suppress("unused")
inline fun <reified T : Any> Any?.cast(): T? = this as? T

@Suppress("unused")
inline fun <reified T : Any> Any.castNotNull(): T = this as T

fun <T : Comparable<T>> minComparable(a: T, b: T): T = if (a <= b) a else b
fun <T : Comparable<T>> maxComparable(a: T, b: T): T = if (a >= b) a else b

fun <T : Any> MutableCollection<T>.removeFirst(check: (T) -> Boolean): T? {
	val it = iterator()
	while (it.hasNext()) {
		val item = it.next()
		if (check(item)) {
			it.remove()
			return item
		}
	}
	return null
}

fun loadFile(file: File) = FileInputStream(file).use { it.readBytes() }

fun saveFile(file: File, data: ByteArray) {
	val tmpFile = File(file.absolutePath + ".tmp")
	FileOutputStream(tmpFile).use { it.write(data) }
	file.delete()
	if (!tmpFile.renameTo(file)) error("$file: rename failed.")
}


private val reFileNameBadChars = """[\\/:*?"<>|-]+""".toRegex()
fun String.sanitizeFileChars() = reFileNameBadChars.replace(this, "-")

private val reAccessToken = """access_token=[^&]+""".toRegex()
fun String.hideAccessToken() = replace(reAccessToken,"access_token=xxx")
fun showUrl(method:HttpMethod, url:String){
	println("${method.value} ${ url.hideAccessToken() }")
}

fun clearCache(cacheDir:File){
	if(verbose) println("clearCache.")
	cacheDir.list()?.forEach { name->
		File(cacheDir,name).takeIf { it.isFile }?.delete()
	}
}

private val cacheExpire  by lazy{ config.cacheExpireHours.toLong() * 3600000L }

suspend fun HttpClient.cachedGetBytes(cacheDir:File,url: String, headers: Map<String, String> = HashMap(),silent:Boolean =false): ByteArray {
	val cacheFile = File(cacheDir,  url.hideAccessToken().sanitizeFileChars())
	if (System.currentTimeMillis() - cacheFile.lastModified() <= cacheExpire) {
		if(!silent) println("GET(cached) ${url.hideAccessToken()}")
		return loadFile(cacheFile)
	}

	if(!silent) showUrl(HttpMethod.Get,url)

	return get<HttpResponse>(url) {
		headers.entries.forEach {
			header(it.key, it.value)
		}
	}.let { res ->
		when (res.status) {
			HttpStatusCode.OK ->
				res.receive<ByteArray>()
					.also { saveFile(cacheFile, it) }
			else -> {
				cacheFile.delete()
				error("get failed. ${url.hideAccessToken()} ${res.status}")
			}
		}
	}
}



suspend fun HttpResponse.getContentString():String{
	var content = try {
		receive<ByteArray>().decodeUtf8()
	} catch (_: Throwable) {
		null
	}

	return when (status) {
		HttpStatusCode.OK -> content!!
		else -> {
			try {
				content?.decodeJsonObject()?.string("error")?.notEmpty()?.let { content = it }
			} catch (_: Throwable) {
			}
			error("request failed. $status ${request.method} ${request.url} $content")
		}
	}
}
suspend fun HttpResponse.getContentBytes():ByteArray{

	val content = try {
		receive<ByteArray>()
	} catch (_: Throwable) {
		null
	}

	return when (status) {
		HttpStatusCode.OK -> content!!
		else -> {
			try{
				var sv =content!!.decodeUtf8()
				try {
					sv.decodeJsonObject()?.string("error")?.notEmpty()?.let { sv = it }
				}catch(_:Throwable){

				}
				error("request failed. $status ${request.method} ${request.url} content=$sv")
			} catch (_: Throwable) {
				error("request failed. $status ${request.method} ${request.url} content=(${content?.size?.toString()} bytes.)")
			}
		}
	}
}



suspend fun HttpClient.cachedGetString(cacheDir:File,url: String, headers: Map<String, String> = HashMap() ): String =
	cachedGetBytes(cacheDir,url,headers).decodeUtf8()

