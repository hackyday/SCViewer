package com.scviewer.fire.data

import android.net.Uri

/**
 * Normalizzazione dei link salvati (cronologia, preferiti, "Riprendi").
 *
 * PROBLEMA: finora gli URL venivano salvati assoluti, con il dominio del
 * momento in cui la pagina era stata visitata. Quando lo streamingcommunity
 * cambia dominio (sync AstraeLabs: es. .build -> .day) i link salvati
 * puntavano al vecchio dominio morto e cronologia / riprendi smettevano
 * di funzionare.
 *
 * SOLUZIONE: si salva solo il PATH+QUERY (es. "/it/titles/1234-slug");
 * alla RIAPERTURA il path viene rimontato sul dominio corrente.
 * Gli URL assoluti già presenti nei dati degli utenti (versioni <= 1.2.2)
 * vengono riscritti al volo alla lettura, quindi la migrazione è automatica.
 */
object SiteUrl {

    /**
     * Parte "site-relative" di un URL del sito: path + query, senza scheme
     * né host. Restituisce null per URL esterni/strani che non devono finire
     * nei salvataggi (es. "about:blank", javascript:, vuoti).
     *
     * Es. "https://streamingcommunityz.day/it/watch/123?e=5"
     *     -> "/it/watch/123?e=5"
     */
    fun relativeOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            // Già relativo (o path nudo): accettabile se inizia con "/"
            return if (trimmed.startsWith("/")) trimmed else null
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        // Pagina vuota / radice fuori sito: inutile salvare solo "/"
        if (path.isBlank() || path == "/") return null
        val q = uri.encodedQuery
        return if (q.isNullOrBlank()) path else "$path?$q"
    }

    /**
     * Rimonta un link salvato (relativo NUOVO o assoluto LEGACY) sul dominio
     * corrente. Restituisce null se il link non è recuperabile.
     *
     * - "/it/titles/1-slug"           -> "$domain/it/titles/1-slug"
     * - "https://vecchio.dom/it/..."  -> "$domain/it/..."   (migrazione legacy)
     * - link di un altro sito (es. player) -> null (non si tocca)
     */
    fun resolve(saved: String?, currentDomain: String): String? {
        if (saved.isNullOrBlank()) return null
        val domain = currentDomain.trimEnd('/')
        if (domain.isBlank()) return null

        val rel = relativeOf(saved) ?: return null

        // Se il relativo proveniva da un URL assoluto ma il path NON è una
        // pagina del sito (nessun prefisso noto), non rimontare: potrebbe
        // essere una risorsa esterna salvata per errore.
        // I path del sito iniziano sempre con /it/ (o locale senza lingua su
        // vecchi dump: /titles/, /watch/).
        val isSitePath = rel.startsWith("/it/") ||
                rel.startsWith("/titles/") ||
                rel.startsWith("/watch/") ||
                rel.startsWith("/iframe/")
        if (!isSitePath && saved.startsWith("http")) {
            // URL assoluto non-riconosciuto come pagina del sito: riscrivi lo
            // stesso se l'host era uno dei domini storici del sito, altrimenti null.
            val host = runCatching { Uri.parse(saved.trim()).host?.lowercase() }.getOrNull()
            val known = host != null && (host.contains("streamingcommunity") ||
                    host.contains("vixcloud") || host.contains("scwcdn"))
            if (!known) return null
        }
        return "$domain$rel"
    }
}
