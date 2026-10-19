package ru.pubchat.app.core.auth

/** Mirror /identity/v2 RegisterRequest limits before a request is sent.
 * The backend remains authoritative for validation and account policy.
 */
object AuthFormRules {
    private val handlePattern = Regex("^[A-Za-z0-9_.]{3,32}$")

    fun validHandle(handle: String): Boolean = handlePattern.matches(handle.trim())

    fun canLogin(identifier: String, password: String): Boolean =
        identifier.trim().isNotEmpty() && identifier.length <= 255 &&
            password.isNotEmpty() && password.length <= 128

    fun canRegister(handle: String, displayName: String, password: String): Boolean =
        validHandle(handle) &&
            displayName.trim().length <= 80 &&
            password.length in 8..128

    fun registrationHint(handle: String, displayName: String, password: String): String? = when {
        handle.isNotEmpty() && !validHandle(handle) ->
            "Логин: 3–32 символа, латиница, цифры, точка или подчёркивание."
        displayName.trim().length > 80 -> "Имя: не более 80 символов."
        password.isNotEmpty() && password.length !in 8..128 ->
            "Пароль должен содержать от 8 до 128 символов."
        else -> null
    }
}
