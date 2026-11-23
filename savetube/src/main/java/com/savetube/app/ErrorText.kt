package com.savetube.app

/**
 * Turns raw parser errors into something readable: yt-dlp's messages are long and full of links
 * that cannot be tapped, and the usual causes have a simple fix.
 */
fun friendlyError(raw: String?): String? {
    val text = raw ?: return null
    val lower = text.lowercase()
    return when {
        "confirm your age" in lower || "age-restricted" in lower || "age restricted" in lower ->
            tr(
                "Видео с возрастным ограничением: YouTube показывает его только вошедшим в аккаунт, а Savetube в аккаунт не входит.",
                "Age-restricted video: YouTube only shows it to signed-in accounts, and Savetube never signs in."
            )
        "not a bot" in lower ->
            tr(
                "YouTube принял соединение за автоматическое. Отключите VPN или переключитесь между Wi-Fi и мобильным интернетом и повторите.",
                "YouTube thinks this connection is automated. Turn off any VPN or switch between Wi-Fi and mobile data, then try again."
            )
        "private video" in lower ->
            tr("Приватное видео: доступно только владельцу и приглашённым.", "Private video: only its owner and invited accounts can see it.")
        else -> text.replace(Regex("""\s*https?://\S+"""), "").replace(Regex("""\s+"""), " ").trim().take(240)
    }
}
