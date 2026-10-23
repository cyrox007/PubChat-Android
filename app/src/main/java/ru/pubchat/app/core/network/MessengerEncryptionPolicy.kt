package ru.pubchat.app.core.network

import org.json.JSONObject

/** Server-owned encryption mode; Android currently implements only *ordinary* plaintext transport. */
enum class ConversationSystemPolicy(val wire: String) {
    DISABLED("disabled"), OPTIONAL("optional"), REQUIRED("required");
    companion object { fun parse(raw: String) = entries.firstOrNull { it.wire == raw } }
}

enum class ConversationEffectiveMode(val wire: String) {
    PLAINTEXT("plaintext"), E2EE("e2ee");
    companion object { fun parse(raw: String) = entries.firstOrNull { it.wire == raw } }
}

data class MessengerEncryptionStatus(
    val systemPolicy: ConversationSystemPolicy,
    val effectiveMode: ConversationEffectiveMode,
    val cryptoReady: Boolean,
) {
    val canUseLegacyTransport: Boolean get() = effectiveMode == ConversationEffectiveMode.PLAINTEXT

    val explanation: String get() = if (canUseLegacyTransport) {
        "Обычная переписка без E2EE: текст доступен серверу PubChat."
    } else {
        if (cryptoReady) "Защищённая отправка E2EE доступна после проверки ключей устройств. " +
            "При ошибке проверок отправка блокируется без перехода к открытому тексту."
        else "Сервер не подтвердил готовность E2EE. Отправка заблокирована без перехода к открытому тексту."
    }
}

/** Strictly parse authoritative response. An unknown or absent mode must never allow plaintext. */
internal fun parseMessengerEncryptionStatus(body: String): MessengerEncryptionStatus {
    val root = JSONObject(body)
    require(root.getString("status") == "ok") { "Сервер не подтвердил политику шифрования" }
    val policy = root.getJSONObject("encryption")
    val system = ConversationSystemPolicy.parse(policy.getString("system_policy"))
        ?: error("Неизвестная системная политика шифрования")
    val effective = ConversationEffectiveMode.parse(policy.getString("effective_mode"))
        ?: error("Неизвестный режим шифрования диалога")
    require(
        (system != ConversationSystemPolicy.REQUIRED || effective == ConversationEffectiveMode.E2EE) &&
        (system != ConversationSystemPolicy.DISABLED || effective == ConversationEffectiveMode.PLAINTEXT)
    ) { "Противоречивый ответ сервера о шифровании" }
    return MessengerEncryptionStatus(
        systemPolicy = system,
        effectiveMode = effective,
        cryptoReady = policy.getBoolean("crypto_ready"),
    )
}

internal fun requirePlaintextMessengerTransport(status: MessengerEncryptionStatus) {
    check(status.canUseLegacyTransport) {
        "Защищённый E2EE-диалог: Android не поддерживает зашифрованную отправку. Открытая отправка запрещена."
    }
}
