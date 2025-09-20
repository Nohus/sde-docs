import java.nio.file.Path
import kotlin.io.path.name

object CustomNames {

    fun getItemName(file: Path): String {
        return when (val name = file.name.substringBefore(".")) {
            "agentsInSpace" -> "agentInSpace"
            "ancestries" -> "ancestry"
            "categories" -> "category"
            "corporationActivities" -> "corporationActivity"
            "dogmaAttributeCategories" -> "dogmaAttributeCategory"
            "masteries" -> "mastery"
            "typeBonus" -> "typeBonus"
            else -> name.removeSuffix("s")
        }
    }
}
