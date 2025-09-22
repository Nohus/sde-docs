import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.copyTo
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.useLines
import kotlin.io.path.writeText
import kotlin.system.exitProcess

suspend fun main() {
    val json = Json { prettyPrint = true }
    val baseUrl = "https://developers.eveonline.com/static-data/tranquility/"

    println("Checking for latest SDE version")
    val (buildNumber, releaseDate) = getLatestSdeBuildNumberAndTimestamp(baseUrl)
    val formattedReleaseDate = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm")
        .withZone(ZoneOffset.UTC)
        .format(releaseDate)
    println("Latest SDE build number: $buildNumber, released on $formattedReleaseDate")

    val sdeUrl = "${baseUrl}eve-online-static-data-${buildNumber}-jsonl.zip"
    val inputDirectory = Path.of("input").also { it.toFile().mkdirs() }
    val schemaOutputDirectory = Path.of("output/schema").also { it.toFile().mkdirs() }
    val docsOutputDirectory = Path.of("sde-docs/docs/schema/").also { it.toFile().mkdirs() }
    val builtFile = Path.of("sde-docs/docs/built.md")

    println("Downloading and extracting SDE files")
    downloadAndExtractZip(sdeUrl, inputDirectory)

    println("Generating schemas")
    val schemas = inferSchemasInDirectory(inputDirectory)

    println("Generating code snippets")
    coroutineScope {
        schemas.entries.forEach { (path, schema) ->
            launch(Dispatchers.Default) {
                val file = schemaOutputDirectory.resolve(path.nameWithoutExtension + ".schema.json")
                file.writeText(json.encodeToString(JsonElement.serializer(), schema))
                generateCodeSnippets(file)
            }
        }
    }

    println("Generating documentation")
    DocumentationGenerator.generate(schemas, docsOutputDirectory)

    builtFile.writeText("This version is based on SDE version `$buildNumber`, which was released on **$formattedReleaseDate**.")
}

private fun getLatestSdeBuildNumberAndTimestamp(baseUrl: String): Pair<String, Instant> {
    val latestUrl = "${baseUrl}latest.jsonl"
    URL(latestUrl).openStream().bufferedReader().use { reader ->
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val element = json.parseToJsonElement(line)
            if (element is JsonObject && element["_key"]?.jsonPrimitive?.content == "sde") {
                val buildNumber = element["buildNumber"]?.jsonPrimitive?.content
                val releaseDate = element["releaseDate"]?.jsonPrimitive?.content
                if (buildNumber != null && releaseDate != null) return buildNumber to Instant.parse(releaseDate)
            }
        }
    }
    throw IllegalStateException("Could not find SDE build number")
}

private fun downloadAndExtractZip(url: String, destinationDirectory: Path) {
    val tempFile = File.createTempFile("download", ".zip")
    URL(url).openStream().use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    ZipInputStream(tempFile.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val file = destinationDirectory.resolve(entry.name)
            if (entry.isDirectory) {
                file.toFile().mkdirs()
            } else {
                file.parent?.toFile()?.mkdirs()
                file.toFile().outputStream().use { output ->
                    zip.copyTo(output)
                }
            }
            entry = zip.nextEntry
        }
    }
    tempFile.delete()
}

private fun generateCodeSnippets(schemaPath: Path) {
    val itemName = CustomNames.getItemName(schemaPath).replaceFirstChar { it.titlecase(Locale.ROOT) }
    val commands = listOf(
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang kotlin --framework kotlinx --package model --acronym-style camel --out sde-docs/snippets/$itemName.kt",
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang py --out sde-docs/snippets/$itemName.py",
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang cs --out sde-docs/snippets/$itemName.cs",
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang go --package model --out sde-docs/snippets/$itemName.go",
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang php --acronym-style camel --out sde-docs/snippets/$itemName.php",
        "quicktype --src output/schema/${schemaPath.name} --src-lang schema --lang ts --acronym-style camel --out sde-docs/snippets/$itemName.ts",
    )

    commands.forEach { command ->
        try {
            val process = ProcessBuilder("bash", "-c", command)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                exitProcess(exitCode)
            }
        } catch (e: Exception) {
            System.err.println("Failed to execute command through bash: ${e.message}")
        }
    }
    schemaPath.copyTo(Path.of("sde-docs/snippets/${itemName}.schema.json"), overwrite = true)
}

private val json = Json {
    allowStructuredMapKeys = true
}

private suspend fun inferSchemasInDirectory(inputDirectory: Path): Map<Path, JsonObject> {
    val schemasByFile = ConcurrentHashMap<Path, JsonObject>()
    coroutineScope {
        Files.walk(inputDirectory).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "jsonl" }
                .forEach { path ->
                    launch(Dispatchers.Default) {
                        schemasByFile[path] = inferSchemaFromJsonl(path)
                    }
                }
        }
    }
    return schemasByFile
}

private fun inferSchemaFromJsonl(file: Path): JsonObject {
    val schemaBuilder = mutableMapOf<String, JsonElement>()
    schemaBuilder[$$"$schema"] = JsonPrimitive("https://json-schema.org/draft/2020-12/schema")

    var mergedType: JsonType? = null
    file.useLines { lines ->
        for (line in lines) {
            if (line.isBlank()) continue
            val element = json.parseToJsonElement(line)
            val type = inferTypeFromElement(element)
            mergedType = if (mergedType == null) type else mergeTypes(mergedType, type)
        }
    }
    requireNotNull(mergedType) { "File $file has no elements" }

    applyTypeToSchema(schemaBuilder, mergedType)
    return JsonObject(schemaBuilder)
}

private fun inferTypeFromElement(element: JsonElement, key: String? = null): JsonType = when (element) {
    is JsonNull -> JsonType.NullType
    is JsonPrimitive -> {
        when {
            element.isString -> {
                val text = element.content
                val isTextValidEnum = text.length < 30
                        && text.all { it.isLetter() }
                        && (key == null || key !in listOf("de", "en", "es", "fr", "ja", "ko", "ru", "zh"))
                val formats = detectFormats(text)
                JsonType.StringType(
                    formats = formats,
                    enumCandidates = if (isTextValidEnum) setOf(text) else emptySet(),
                    isNotEnum = !isTextValidEnum,
                    minLength = text.length,
                    maxLength = text.length
                )
            }
            element.booleanOrNull != null -> JsonType.BooleanType
            element.longOrNull != null -> JsonType.NumberType(isIntegerOnly = true, min = element.longOrNull!!.toDouble(), max = element.longOrNull!!.toDouble())
            element.doubleOrNull != null -> JsonType.NumberType(isIntegerOnly = false, min = element.doubleOrNull!!, max = element.doubleOrNull!!)
            else -> throw RuntimeException("Unexpected primitive type: $element")
        }
    }
    is JsonArray -> {
        var itemType: JsonType? = null
        var minItems = Int.MAX_VALUE
        var maxItems = 0
        val size = element.size
        require(size > 0) { "Array cannot have a non-positive size" }
        element.forEach { element ->
            val type = inferTypeFromElement(element)
            itemType = if (itemType == null) type else mergeTypes(itemType, type)
        }
        minItems = minOf(minItems, size)
        maxItems = maxOf(maxItems, size)
        JsonType.ArrayType(itemType!!, minItems, maxItems)
    }
    is JsonObject -> {
        val objectType = JsonType.ObjectType()
        objectType.totalSeen = 1
        for ((key, value) in element) {
            objectType.properties[key] = inferTypeFromElement(value, key)
            objectType.requiredCounts[key] = if (value !is JsonNull) 1 else 0
        }
        objectType
    }
}

private fun mergeTypes(a: JsonType, b: JsonType): JsonType = when {
    a is JsonType.NullType && b is JsonType.NullType -> a
    a is JsonType.BooleanType && b is JsonType.BooleanType -> a
    a is JsonType.NumberType && b is JsonType.NumberType -> JsonType.NumberType(
        isIntegerOnly = a.isIntegerOnly && b.isIntegerOnly,
        min = listOf(a.min, b.min).min(),
        max = listOf(a.max, b.max).max()
    )
    a is JsonType.StringType && b is JsonType.StringType -> {
        val formats = (a.formats + b.formats).toMutableSet()
        val enumsCombined = a.enumCandidates + b.enumCandidates
        val isNotEnum = a.isNotEnum || b.isNotEnum || enumsCombined.size > 20
        val minLength = listOf(a.minLength, b.minLength).min()
        val maxLength = listOf(a.maxLength, b.maxLength).max()
        JsonType.StringType(formats, enumsCombined.takeIf { !isNotEnum } ?: setOf(), isNotEnum,minLength, maxLength)
    }
    a is JsonType.ArrayType && b is JsonType.ArrayType -> JsonType.ArrayType(
        items = mergeTypes(a.items, b.items),
        minItems = listOf(a.minItems, b.minItems).min(),
        maxItems = listOf(a.maxItems, b.maxItems).max()
    )
    a is JsonType.ObjectType && b is JsonType.ObjectType -> {
        val o = JsonType.ObjectType()
        o.totalSeen = a.totalSeen + b.totalSeen
        val keys = (a.properties.keys + b.properties.keys).toSortedSet()
        for (k in keys) {
            val av = a.properties[k]
            val bv = b.properties[k]
            when {
                av != null && bv != null -> o.properties[k] = mergeTypes(av, bv)
                av != null -> o.properties[k] = av
                bv != null -> o.properties[k] = bv
            }
            val aReq = a.requiredCounts[k] ?: 0
            val bReq = b.requiredCounts[k] ?: 0
            o.requiredCounts[k] = aReq + bReq
        }
        o
    }
    else -> error("Unexpected types: $a, $b")
}

private fun applyTypeToSchema(target: MutableMap<String, JsonElement>, type: JsonType) {
    when (type) {
        is JsonType.NullType -> target["type"] = JsonPrimitive("null")
        is JsonType.BooleanType -> target["type"] = JsonPrimitive("boolean")
        is JsonType.NumberType -> {
            target["type"] = JsonPrimitive(if (type.isIntegerOnly) "integer" else "number")
            if (type.isIntegerOnly) {
                target["minimum"] = JsonPrimitive(type.min.toInt())
                target["maximum"] = JsonPrimitive(type.max.toInt())
            } else {
                target["minimum"] = JsonPrimitive(type.min)
                target["maximum"] = JsonPrimitive(type.max)
            }
        }
        is JsonType.StringType -> {
            target["type"] = JsonPrimitive("string")
            if (type.formats.isNotEmpty()) target["format"] = JsonPrimitive(type.formats.first())
            val isEnum = type.enumCandidates.isNotEmpty() && !type.isNotEnum
            if (isEnum) {
                target["enum"] = JsonArray(type.enumCandidates.map { JsonPrimitive(it) })
            } else {
                target["minLength"] = JsonPrimitive(type.minLength)
                target["maxLength"] = JsonPrimitive(type.maxLength)
            }
        }
        is JsonType.ArrayType -> {
            target["type"] = JsonPrimitive("array")
            val itemsMap = mutableMapOf<String, JsonElement>()
            applyTypeToSchema(itemsMap, type.items)
            target["items"] = JsonObject(itemsMap)
            target["minItems"] = JsonPrimitive(type.minItems)
            target["maxItems"] = JsonPrimitive(type.maxItems)
        }
        is JsonType.ObjectType -> {
            target["type"] = JsonPrimitive("object")
            val props = mutableMapOf<String, JsonElement>()
            val required = mutableListOf<String>()
            val total = type.totalSeen.coerceAtLeast(1)
            for ((k, v) in type.properties) {
                val m = mutableMapOf<String, JsonElement>()
                applyTypeToSchema(m, v)
                props[k] = JsonObject(m)
                val count = type.requiredCounts[k] ?: 0
                if (count == total) required += k
            }
            target["properties"] = JsonObject(props)
            if (required.isNotEmpty()) target["required"] = JsonArray(required.map { JsonPrimitive(it) })
        }
    }
}

private fun detectFormats(text: String): Set<String> {
    val formats = mutableSetOf<String>()
    if (DATE_TIME_REGEX.matches(text)) formats += "date-time"
    else if (DATE_REGEX.matches(text)) formats += "date"
    return formats
}

private val DATE_TIME_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})?)?\$")
private val DATE_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}\$")
