package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.model.HttpStatusException
import io.github.jmorille.zapette.model.RedirectRefusedException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Catégorie d'échec de connexion. */
enum class Problem {
    UNKNOWN_HOST, REFUSED, TIMEOUT, CREDENTIALS, CERTIFICATE, TLS, REDIRECT, UNEXPECTED_RESPONSE, UNREACHABLE, HTTP, OTHER,
}

/** Échec classé : [title] dit ce qui s'est passé, [advice] ce qu'on peut faire. */
data class Diagnosis(val title: String, val advice: String?, val problem: Problem = Problem.OTHER)

/**
 * Classe une erreur de connexion (logique pure, testée par table). Toute la chaîne des causes
 * est examinée : les protocoles enveloppent souvent l'erreur réseau dans une `RemoteException`.
 */
fun diagnose(error: Throwable): Diagnosis {
    val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
    val messages = chain.mapNotNull { it.message }.joinToString(" | ")

    chain.has<HttpStatusException>()?.let { http ->
        return when (http.code) {
            401, 403 -> Diagnosis(
                "Identifiants refusés (HTTP ${http.code})",
                "Vérifiez l'utilisateur et le mot de passe, ou les droits de ce compte sur le serveur.",
                Problem.CREDENTIALS,
            )
            404 -> Diagnosis(
                "Réponse inattendue (HTTP 404)",
                "Le service attendu n'est pas à cette adresse : vérifiez le type d'appareil, le port et le chemin de base.",
                Problem.UNEXPECTED_RESPONSE,
            )
            else -> Diagnosis(
                "Erreur du serveur (HTTP ${http.code})",
                "Le serveur a répondu mais a refusé la requête ; consultez son journal.",
                Problem.HTTP,
            )
        }
    }
    chain.has<RedirectRefusedException>()?.let {
        return Diagnosis(
            "Redirection refusée",
            "Le serveur renvoie vers une autre adresse ; saisissez directement l'adresse finale (hôte, port, HTTPS).",
            Problem.REDIRECT,
        )
    }
    // Certificat : chaîne non reconnue (auto-signé, autorité inconnue) ou nom d'hôte différent.
    if (chain.has<CertPathValidatorException>() != null || chain.has<CertificateException>() != null ||
        chain.has<SSLPeerUnverifiedException>() != null
    ) {
        return Diagnosis(
            "Certificat invalide ou non reconnu",
            "Certificat auto-signé ? Choisissez le mode « HTTPS – certificat non vérifié » après avoir comparé l'empreinte.",
            Problem.CERTIFICATE,
        )
    }
    chain.has<SSLHandshakeException>()?.let {
        return if ("certificat" in messages.lowercase() || "certificate" in messages.lowercase() || "trust anchor" in messages.lowercase()) {
            Diagnosis(
                "Certificat invalide ou non reconnu",
                "Certificat auto-signé ? Choisissez le mode « HTTPS – certificat non vérifié » après avoir comparé l'empreinte.",
                Problem.CERTIFICATE,
            )
        } else {
            tlsFailure()
        }
    }
    chain.has<SSLException>()?.let { return tlsFailure() }
    chain.has<UnknownHostException>()?.let {
        return Diagnosis(
            "Nom introuvable",
            "Ce nom n'est pas résolu sur ce réseau : utilisez l'adresse IP de l'appareil ou vérifiez le DNS local.",
            Problem.UNKNOWN_HOST,
        )
    }
    if (chain.has<NoRouteToHostException>() != null || chain.has<PortUnreachableException>() != null ||
        UNREACHABLE.any { it in messages }
    ) {
        return unreachable()
    }
    chain.has<ConnectException>()?.let {
        return Diagnosis(
            "Connexion refusée (port fermé)",
            "L'appareil répond mais rien n'écoute sur ce port : vérifiez le port et que le service (contrôle à distance) est activé.",
            Problem.REFUSED,
        )
    }
    if (chain.has<SocketTimeoutException>() != null || chain.has<TimeoutCancellationException>() != null ||
        chain.any { it is InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true }
    ) {
        return timeout()
    }
    if (chain.has<SerializationException>() != null || UNEXPECTED.any { it in messages.lowercase() }) {
        return Diagnosis(
            "Réponse inattendue",
            "Un autre service répond à cette adresse : vérifiez le type d'appareil, le port et le chemin de base.",
            Problem.UNEXPECTED_RESPONSE,
        )
    }
    return Diagnosis(error.message ?: "Connexion impossible (${error.javaClass.simpleName})", null, Problem.OTHER)
}

private inline fun <reified T : Throwable> List<Throwable>.has(): T? = firstOrNull { it is T } as T?

/** Délai global du test dépassé (aucune exception levée). */
fun timeoutDiagnosis(): Diagnosis = timeout()

private fun timeout() = Diagnosis(
    "Délai dépassé",
    "L'appareil ne répond pas à temps : est-il allumé ? Augmentez le délai de connexion si le réseau est lent.",
    Problem.TIMEOUT,
)

private fun unreachable() = Diagnosis(
    "Hôte injoignable",
    "Aucune route vers l'appareil : isolation des clients Wi-Fi, VLAN ou sous-réseau différent, ou appareil éteint.",
    Problem.UNREACHABLE,
)

private fun tlsFailure() = Diagnosis(
    "Échec de la négociation TLS",
    "Le serveur ne parle peut-être pas HTTPS sur ce port : essayez le mode HTTP ou le port HTTPS du serveur.",
    Problem.TLS,
)

/** Messages Android/Linux d'un hôte ou réseau injoignable (enveloppés dans une ConnectException). */
private val UNREACHABLE = listOf("EHOSTUNREACH", "ENETUNREACH", "No route to host", "Network is unreachable")

/** Messages des protocoles signalant un service qui n'est pas celui attendu. */
private val UNEXPECTED = listOf("réponse inattendue", "ne répond pas comme", "réponse invalide")
