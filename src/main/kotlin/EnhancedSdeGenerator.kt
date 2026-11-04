import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.bufferedWriter
import kotlin.io.path.copyTo
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.useLines

class EnhancedSdeGenerator(private val json: Json) {

    fun generate(
        inputDirectory: Path,
        repackagedVolumes: Map<Int, Int>,
        outputDirectory: Path,
        zipTarget: Path
    ) {
        val files = Files.walk(inputDirectory).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "jsonl" }.toList()
        }
        val filesByName = files.associateBy { it.nameWithoutExtension }

        fun readNames(name: String, property: String): Map<Long, String> =
            filesByName.getValue(name).useLines { lines ->
                lines.filter { it.isNotBlank() }.map { json.parseToJsonElement(it) }.associate { item ->
                    item["_key"].asLong() to item[property]["en"].asString()
                }
            }

        fun transform(name: String, block: (JsonElement) -> JsonObject) {
            val source = filesByName.getValue(name)
            outputDirectory.resolve(source.fileName).bufferedWriter().use { writer ->
                source.useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        writer.write(json.encodeToString(block(json.parseToJsonElement(line))))
                        writer.newLine()
                    }
                }
            }
        }

        val systemNamesById = readNames("mapSolarSystems", "name")
        val corporationNamesById = readNames("npcCorporations", "name")
        val operationNamesById = readNames("stationOperations", "operationName")
        val celestialNamesById = mutableMapOf<Long, String>()

        transform("mapStars") { item ->
            val solarSystemID = item["solarSystemID"].asLong()
            val name = systemNamesById[solarSystemID]!!
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("mapPlanets") { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName ${getRomanNumerals(item["celestialIndex"].asLong().toInt())}"
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("mapMoons") { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName - Moon ${item["orbitIndex"].asLong()}"
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("mapAsteroidBelts") { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName - Asteroid Belt ${item["orbitIndex"].asLong()}"
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("mapStargates") { item ->
            val solarSystemID = item["destination"]["solarSystemID"].asLong()
            val name = "Stargate (${systemNamesById[solarSystemID]!!})"
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("npcStations") { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val corporationName = corporationNamesById[item["ownerID"].asLong()]!!
            val operationName = if (item.getOrNull("useOperationName")?.jsonPrimitive?.booleanOrNull == true) {
                operationNamesById[item["operationID"].asLong()]!!
            } else {
                null
            }
            val stationName = listOfNotNull(corporationName, operationName).joinToString(" ")
            val name = "$orbitName - $stationName"
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }

        transform("types") { item ->
            val typeId = item["_key"].jsonPrimitive.int
            val repackagedVolume = repackagedVolumes[typeId]
            item.jsonObject.update {
                if (repackagedVolume != null) {
                    it["repackagedVolume"] = JsonPrimitive(repackagedVolume)
                }
            }
        }

        val modifiedFiles = setOf(
            "mapStars.jsonl",
            "mapPlanets.jsonl",
            "mapMoons.jsonl",
            "mapAsteroidBelts.jsonl",
            "mapStargates.jsonl",
            "npcStations.jsonl",
            "types.jsonl",
        )
        files.filter { it.name !in modifiedFiles }.forEach { path ->
            path.copyTo(outputDirectory.resolve(path.fileName), overwrite = true)
        }

        zipTarget.parent.toFile().mkdirs()
        ZipOutputStream(Files.newOutputStream(zipTarget)).use { zip ->
            Files.walk(outputDirectory).use { files ->
                files.filter { it.isRegularFile() }.forEach { file ->
                    zip.putNextEntry(ZipEntry(outputDirectory.relativize(file).toString()))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    private operator fun JsonElement.get(key: String): JsonElement = jsonObject[key]!!

    private fun JsonElement.getOrNull(key: String): JsonElement? = jsonObject[key]

    private fun JsonElement.asString(): String = jsonPrimitive.content

    private fun JsonElement.asLong(): Long = asString().toLong()

    private fun JsonObject.update(block: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
        return JsonObject(jsonObject.toMutableMap().apply(block))
    }

    private fun getRomanNumerals(number: Int): String {
        require(number in 1..3999) { "Number must be between 1 and 3999" }

        val values = listOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = listOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")

        var remaining = number
        return buildString {
            for (i in values.indices) {
                while (remaining >= values[i]) {
                    append(symbols[i])
                    remaining -= values[i]
                }
            }
        }
    }
}
