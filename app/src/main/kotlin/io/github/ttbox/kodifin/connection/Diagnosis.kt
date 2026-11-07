package io.github.ttbox.kodifin.connection

import androidx.annotation.StringRes
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.CertificatePinMismatchException
import io.github.ttbox.kodifin.model.HttpStatusException
import io.github.ttbox.kodifin.model.RedirectRefusedException
import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.UnsupportedActionException
import io.github.ttbox.kodifin.text.UiText
import io.github.ttbox.kodifin.text.text
import io.github.ttbox.kodifin.text.toUiText
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

    /** Certificat différent de celui auquel l'utilisateur avait fait confiance (empreinte épinglée). */
    CERTIFICATE_CHANGED,
}

/** Échec classé : [title] dit ce qui s'est passé, [advice] ce qu'on peut faire. */
data class Diagnosis(val title: UiText, val advice: UiText?, val problem: Problem = Problem.OTHER) {
    constructor(@StringRes title: Int, @StringRes advice: Int, problem: Problem) :
        this(UiText.Res(title), UiText.Res(advice), problem)
}

/**
 * Classe une erreur de connexion (logique pure, testée par table). Toute la chaîne des causes
 * est examinée : les protocoles enveloppent souvent l'erreur réseau dans une `RemoteException`.
 * Les exceptions supprimées le sont aussi : OkHttp essaie chaque adresse de l'hôte et ne lève
 * que la dernière erreur, les précédentes (ex. certificat refusé) y étant jointes.
 */
fun diagnose(error: Throwable): Diagnosis {
    val chain = causes(error)
    val messages = chain.mapNotNull { it.message }.joinToString(" | ")

    chain.has<CertificatePinMismatchException>()?.let { pin ->
        return Diagnosis(
            UiText.Res(R.string.diag_certificate_changed_title, pin.actual),
            UiText.Res(R.string.diag_certificate_changed_advice, pin.expected),
            Problem.CERTIFICATE_CHANGED,
        )
    }

    chain.has<HttpStatusException>()?.let { http ->
        return when (http.code) {
            401, 403 -> Diagnosis(
                UiText.Res(R.string.diag_credentials_title, http.code),
                UiText.Res(R.string.diag_credentials_advice),
                Problem.CREDENTIALS,
            )
            404 -> Diagnosis(R.string.diag_not_found_title, R.string.diag_not_found_advice, Problem.UNEXPECTED_RESPONSE)
            else -> Diagnosis(
                UiText.Res(R.string.diag_http_title, http.code),
                UiText.Res(R.string.diag_http_advice),
                Problem.HTTP,
            )
        }
    }
    chain.has<RedirectRefusedException>()?.let {
        return Diagnosis(R.string.diag_redirect_title, R.string.diag_redirect_advice, Problem.REDIRECT)
    }
    // Certificat : chaîne non reconnue (auto-signé, autorité inconnue) ou nom d'hôte différent.
    if (chain.has<CertPathValidatorException>() != null || chain.has<CertificateException>() != null ||
        chain.has<SSLPeerUnverifiedException>() != null
    ) {
        return certificateInvalid()
    }
    chain.has<SSLHandshakeException>()?.let {
        return if ("certificat" in messages.lowercase() || "certificate" in messages.lowercase() || "trust anchor" in messages.lowercase()) {
            certificateInvalid()
        } else {
            tlsFailure()
        }
    }
    chain.has<SSLException>()?.let { return tlsFailure() }
    chain.has<UnknownHostException>()?.let {
        return Diagnosis(R.string.diag_unknown_host_title, R.string.diag_unknown_host_advice, Problem.UNKNOWN_HOST)
    }
    if (chain.has<NoRouteToHostException>() != null || chain.has<PortUnreachableException>() != null ||
        UNREACHABLE.any { it in messages }
    ) {
        return unreachable()
    }
    chain.has<ConnectException>()?.let {
        return Diagnosis(R.string.diag_refused_title, R.string.diag_refused_advice, Problem.REFUSED)
    }
    val reason = chain.firstNotNullOfOrNull { (it as? RemoteException)?.reason }
    if (chain.has<SocketTimeoutException>() != null || chain.has<TimeoutCancellationException>() != null ||
        chain.any { it is InterruptedIOException && it.message?.contains("timeout", ignoreCase = true) == true } ||
        reason == RemoteError.Timeout
    ) {
        return timeout()
    }
    if (chain.has<SerializationException>() != null || reason is RemoteError.UnexpectedResponse) {
        return Diagnosis(R.string.diag_unexpected_title, R.string.diag_unexpected_advice, Problem.UNEXPECTED_RESPONSE)
    }
    when (reason) {
        is RemoteError.TlsFailed -> return tlsFailure()
        RemoteError.CredentialsRejected, RemoteError.SessionExpired, RemoteError.PasswordRejected ->
            return Diagnosis(reason.text, UiText.Res(R.string.diag_credentials_advice), Problem.CREDENTIALS)
        is RemoteError.Unreachable -> return unreachable()
        else -> Unit
    }
    // Autre raison typée (ou réglage précis à faire) : son texte dit déjà quoi faire.
    val cause = chain.firstOrNull { it is RemoteException || it is UnsupportedActionException } ?: error
    return Diagnosis(cause.toUiText(), null, Problem.OTHER)
}

private inline fun <reified T : Throwable> List<Throwable>.has(): T? = firstOrNull { it is T } as T?

/** Causes et exceptions supprimées, en largeur, sans cycle et en nombre borné. */
private fun causes(error: Throwable): List<Throwable> {
    val out = mutableListOf<Throwable>()
    val queue = ArrayDeque(listOf(error))
    while (queue.isNotEmpty() && out.size < MAX_CAUSES) {
        val e = queue.removeFirst()
        if (out.any { it === e }) continue
        out += e
        e.cause?.let(queue::addLast)
        e.suppressed.forEach(queue::addLast)
    }
    return out
}

private const val MAX_CAUSES = 20

/** Délai global du test dépassé (aucune exception levée). */
fun timeoutDiagnosis(): Diagnosis = timeout()

private fun timeout() = Diagnosis(R.string.diag_timeout_title, R.string.diag_timeout_advice, Problem.TIMEOUT)

private fun unreachable() = Diagnosis(R.string.diag_unreachable_title, R.string.diag_unreachable_advice, Problem.UNREACHABLE)

private fun tlsFailure() = Diagnosis(R.string.diag_tls_title, R.string.diag_tls_advice, Problem.TLS)

private fun certificateInvalid() =
    Diagnosis(R.string.diag_certificate_title, R.string.diag_certificate_advice, Problem.CERTIFICATE)

/** Messages Android/Linux d'un hôte ou réseau injoignable (enveloppés dans une ConnectException). */
private val UNREACHABLE = listOf("EHOSTUNREACH", "ENETUNREACH", "No route to host", "Network is unreachable")

