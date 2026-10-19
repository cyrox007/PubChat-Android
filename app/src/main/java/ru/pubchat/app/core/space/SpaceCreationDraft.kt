package ru.pubchat.app.core.space

/**
 * Mirrors backend SpaceCreateRequest contract. The server remains authoritative
 * for permissions, active category assignments and the visibility/join policy.
 */
data class SpaceCreationDraft(
    val name: String,
    val description: String = "",
    val purpose: String = "community",
    val visibility: String = "public",
    val joinPolicy: String = "open",
    val memberLimit: Int = 250,
    val country: String = "",
    val region: String = "",
    val tagsInput: String = "",
    val categorySlugs: List<String> = emptyList(),
) {
    val tags: List<String> get() = tagsInput.split(',').map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    fun validationError(activeCategorySlugs: Set<String>? = null): String? {
        if (name.trim().replace(Regex("\\s+"), " ").length !in 3..80)
            return "Название должно содержать от 3 до 80 символов."
        if (description.length > 500) return "Описание: не более 500 символов."
        if (purpose !in setOf("community", "conversation", "meet_people", "games", "local"))
            return "Неизвестный формат пространства."
        if (visibility !in setOf("public", "unlisted", "private"))
            return "Неизвестная видимость пространства."
        if (joinPolicy !in setOf("open", "request", "invite"))
            return "Неизвестные правила вступления."
        if (visibility == "private" && joinPolicy != "invite")
            return "В закрытое пространство можно вступить только по приглашению."
        if (memberLimit !in 2..5000) return "Лимит участников: от 2 до 5000."
        if (country.length > 100 || region.length > 100)
            return "Страна и регион: не более 100 символов каждый."
        if (tags.size > 8 || tags.any { it.length > 40 })
            return "Укажите до 8 тегов, каждый до 40 символов."
        if (categorySlugs.size > 3 || categorySlugs.distinct().size != categorySlugs.size ||
            categorySlugs.any { !it.matches(Regex("^[a-z][a-z0-9-]{0,39}$")) })
            return "Выберите не более трёх действующих категорий."
        if (activeCategorySlugs != null && categorySlugs.any { it !in activeCategorySlugs })
            return "Список категорий устарел. Загрузите категории заново."
        return null
    }
}
