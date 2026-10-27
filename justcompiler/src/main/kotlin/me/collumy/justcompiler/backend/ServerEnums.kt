package me.collumy.justcompiler.backend

interface IdentifiableEnum {
    val id: String
}

open class EnumIdResolver<T>(values: Array<T>) where T : Enum<T>, T : IdentifiableEnum {
    private val byId: Map<String, T> = values.associateBy { it.id.lowercase() }
    fun fromId(id: String): T? = byId[id.lowercase()]
}

enum class TriggerType(override val id: String) : IdentifiableEnum {
    EVENT("event"), FUNCTION("function"), PROCESS("process");
    companion object : EnumIdResolver<TriggerType>(entries.toTypedArray())
}

enum class VariableScope(override val id: String) : IdentifiableEnum {
    LINE("line"), LOCAL("local"), GAME("game"), SAVE("save");
    companion object : EnumIdResolver<VariableScope>(entries.toTypedArray())
}

enum class TextParsing(override val id: String) : IdentifiableEnum {
    PLAIN("plain"), LEGACY("legacy"), MINI("minimessage"), JSON("json");
    companion object : EnumIdResolver<TextParsing>(entries.toTypedArray())
}

enum class SoundSource(override val id: String) : IdentifiableEnum {
    MASTER("master"),
    MUSIC("music"),
    RECORD("record"),
    WEATHER("weather"),
    BLOCK("block"),
    HOSTILE("hostile"),
    NEUTRAL("neutral"),
    PLAYER("player"),
    AMBIENT("ambient"),
    VOICE("voice");

    companion object : EnumIdResolver<SoundSource>(entries.toTypedArray())
}

enum class ServerType(override val id: String) : IdentifiableEnum {
    ANY("any"),
    TEXT("text"),
    NUMBER("number"),
    LOCATION("location"),
    VECTOR("vector"),
    ITEM("item"),
    SOUND("sound"),
    PARTICLE("particle"),
    POTION("potion"),
    LIST("array"),
    MAP("map"),
    VARIABLE("variable");

    companion object : EnumIdResolver<ServerType>(entries.toTypedArray())
}

enum class ActionSelection(override val id: String) : IdentifiableEnum {
    CURRENT("current"),

    // Выборки игроков (Player Selection)
    DEFAULT("default"),
    KILLER_PLAYER("killer_player"),
    DAMAGER_PLAYER("damager_player"),
    SHOOTER_PLAYER("shooter_player"),
    VICTIM_PLAYER("victim_player"),
    RANDOM_PLAYER("random_player"),
    ALL_PLAYERS("all_players"),

    // Выборки сущностей (Entity Selection)
    DEFAULT_ENTITY("default_entity"),
    KILLER_ENTITY("killer_entity"),
    DAMAGER_ENTITY("damager_entity"),
    SHOOTER_ENTITY("shooter_entity"),
    PROJECTILE("projectile"),
    VICTIM_ENTITY("victim_entity"),
    RANDOM_ENTITY("random_entity"),
    ALL_ENTITIES("all_entities"),
    ALL_MOBS("all_mobs"),
    LAST_ENTITY("last_entity");

    companion object : EnumIdResolver<ActionSelection>(entries.toTypedArray())
}

enum class GameValueSelection(override val id: String) : IdentifiableEnum {
    DEFAULT("default"),
    CURRENT("current"),

    DEFAULT_ENTITY("default_entity"),
    KILLER("killer_entity"),
    DAMAGER("damager_entity"),
    VICTIM("victim_entity"),
    SHOOTER("shooter_entity"),
    PROJECTILE("projectile"),
    LAST("last_entity");

    companion object : EnumIdResolver<GameValueSelection>(entries.toTypedArray())
}