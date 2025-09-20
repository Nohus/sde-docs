sealed interface JsonType {
    data object NullType : JsonType
    data object BooleanType : JsonType
    data class NumberType(
        val isIntegerOnly: Boolean,
        val min: Double,
        val max: Double,
    ) : JsonType
    data class StringType(
        val formats: Set<String> = setOf(),
        val enumCandidates: Set<String> = setOf(),
        val isNotEnum: Boolean,
        val minLength: Int,
        val maxLength: Int
    ) : JsonType
    data class ArrayType(
        var items: JsonType,
        var minItems: Int,
        var maxItems: Int,
    ) : JsonType
    data class ObjectType(
        val properties: MutableMap<String, JsonType> = mutableMapOf(),
        val requiredCounts: MutableMap<String, Int> = mutableMapOf(),
        var totalSeen: Int = 0,
    ) : JsonType
}
