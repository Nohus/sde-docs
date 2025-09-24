import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.useLines
import kotlin.io.path.writeLines

class EnhancedSdeGenerator(private val json: Json) {

    fun generate(inputDirectory: Path, outputDirectory: Path, zipTarget: Path) {
        val sde: List<Pair<Path, List<JsonElement>>> = readSde(inputDirectory)

        val systemNamesById = sde.getItems("mapSolarSystems").associate { item ->
            item["_key"].asLong() to item["name"]["en"].asString()
        }
        val corporationNamesById = sde.getItems("npcCorporations").associate { item ->
            item["_key"].asLong() to item["name"]["en"].asString()
        }
        val operationNamesById = sde.getItems("stationOperations").associate { item ->
            item["_key"].asLong() to item["operationName"]["en"].asString()
        }
        val celestialNamesById = mutableMapOf<Long, String>()

        val starItems = sde.getItems("mapStars").map { item ->
            val solarSystemID = item["solarSystemID"].asLong()
            val name = systemNamesById[solarSystemID]!!
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("mapStars.jsonl").writeLines(starItems)

        val planetItems = sde.getItems("mapPlanets").map { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName ${getRomanNumerals(item["celestialIndex"].asLong().toInt())}"
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("mapPlanets.jsonl").writeLines(planetItems)

        val moonItems = sde.getItems("mapMoons").map { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName - Moon ${item["orbitIndex"].asLong()}"
            celestialNamesById += item["_key"].asLong() to name
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("mapMoons.jsonl").writeLines(moonItems)

        val asteroidBeltItems = sde.getItems("mapAsteroidBelts").map { item ->
            val orbitName = celestialNamesById[item["orbitID"].asLong()]!!
            val customName = item.getOrNull("name")?.get("en")?.asString()
            val name = customName ?: "$orbitName - Asteroid Belt ${item["orbitIndex"].asLong()}"
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("mapAsteroidBelts.jsonl").writeLines(asteroidBeltItems)

        val stargateItems = sde.getItems("mapStargates").map { item ->
            val solarSystemID = item["destination"]["solarSystemID"].asLong()
            val name = "Stargate (${systemNamesById[solarSystemID]!!})"
            item.jsonObject.update {
                it["name"] = JsonObject(mapOf("en" to JsonPrimitive(name)))
            }
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("mapStargates.jsonl").writeLines(stargateItems)

        val stationItems = sde.getItems("npcStations").map { item ->
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
        }.map { json.encodeToString(it) }
        outputDirectory.resolve("npcStations.jsonl").writeLines(stationItems)

        val modifiedFiles = setOf(
            "mapStars.jsonl",
            "mapPlanets.jsonl",
            "mapMoons.jsonl",
            "mapAsteroidBelts.jsonl",
            "mapStargates.jsonl",
            "npcStations.jsonl"
        )
        sde
            .filter { it.first.name !in modifiedFiles }
            .forEach { (path, items) ->
                outputDirectory.resolve(path.fileName)
                    .writeLines(items.map { json.encodeToString(it) })
            }

        zipTarget.parent.toFile().mkdirs()
        ZipOutputStream(Files.newOutputStream(zipTarget)).use { zip ->
            Files.walk(outputDirectory).filter { it.isRegularFile() }.forEach { file ->
                zip.putNextEntry(ZipEntry(outputDirectory.relativize(file).toString()))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
    }

    private operator fun JsonElement.get(key: String): JsonElement = jsonObject[key]!!

    private fun JsonElement.getOrNull(key: String): JsonElement? = jsonObject[key]

    private fun JsonElement.asString(): String = jsonPrimitive.content

    private fun JsonElement.asLong(): Long = asString().toLong()

    private fun List<Pair<Path, List<JsonElement>>>.getItems(name: String): List<JsonElement> {
        return single { it.first.nameWithoutExtension == name }.second
    }

    private fun JsonObject.update(block: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
        return JsonObject(jsonObject.toMutableMap().apply(block))
    }

    private fun readSde(inputDirectory: Path): List<Pair<Path, List<JsonElement>>> {
        return Files.walk(inputDirectory).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "jsonl" }
                .map { path ->
                    val items = path.useLines { lines ->
                        lines.mapNotNull { line ->
                            if (line.isBlank()) return@mapNotNull null
                            json.parseToJsonElement(line)
                        }.toList()
                    }
                    path to items
                }
                .toList()
        }
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
