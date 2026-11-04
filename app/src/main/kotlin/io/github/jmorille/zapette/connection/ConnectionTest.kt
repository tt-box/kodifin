package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.model.DescribesServer
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.RemoteController
import io.github.jmorille.zapette.model.forDevice
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/** Résultat du bouton « Tester la connexion ». */
sealed interface ConnectionTestResult {
    val message: String

    /** Empreinte SHA-256 du certificat présenté (HTTPS), à comparer avec celle du serveur. */
    val certificateSha256: String?

    data class Success(override val message: String, override val certificateSha256: String? = null) : ConnectionTestResult

    data class Failure(
        override val message: String,
        /** Conseil associé au diagnostic, ou `null`. */
        val advice: String? = null,
        val problem: Problem = Problem.OTHER,
        override val certificateSha256: String? = null,
    ) : ConnectionTestResult
}

/**
 * Client HTTP dérivé pour [device] : délai de connexion et mode TLS propres à l'appareil. En mode
 * non vérifié, l'empreinte enregistrée ([DeviceOptions.TLS_PIN]) est imposée à chaque connexion.
 */
fun OkHttpClient.forDevice(device: Device): OkHttpClient =
    forDevice(DeviceOptions.timeoutSeconds(device), DeviceOptions.insecureTls(device), DeviceOptions.tlsPin(device))

/** Délai global du test : plusieurs requêtes s'enchaînent (détection, authentification, session). */
fun testTimeoutMs(device: Device): Long = DeviceOptions.timeoutSeconds(device) * 2_000L

/**
 * Crée un contrôleur pour [device], se connecte, lit la version du serveur si le contrôleur
 * sait la décrire ([DescribesServer]), puis se déconnecte, sans rien enregistrer. Le contrôleur
 * est toujours libéré, même en cas d'échec ou de délai dépassé. Les échecs sont classés par
 * [diagnose] (titre + conseil).
 *
 * [fingerprint] lit l'empreinte du certificat présenté (HTTPS) ; elle est jointe au succès en
 * mode non vérifié (pour « Faire confiance à ce certificat ») et à l'échec dû au certificat,
 * y compris un certificat différent de celui auquel on avait fait confiance.
 */
suspend fun testConnection(
    device: Device,
    timeoutMs: Long = testTimeoutMs(device),
    fingerprint: suspend (Device) -> String? = { null },
    create: suspend (Device) -> RemoteController,
): ConnectionTestResult {
    val controller = try {
        create(device)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ConnectionTestResult.Failure(e.message ?: "Configuration invalide")
    }
    try {
        val description = withTimeoutOrNull(timeoutMs) {
            controller.connect()
            // Une version illisible n'invalide pas une connexion réussie.
            Described((controller as? DescribesServer)?.let { describeSafely(it) })
        }
        return if (description != null) {
            val text = description.text
                ?.let { "Connexion réussie : $it" }
                ?: "Connexion réussie à ${device.name} (${device.kind.label})"
            val sha = if (DeviceOptions.insecureTls(device)) safeFingerprint(fingerprint, device) else null
            ConnectionTestResult.Success(text, sha)
        } else {
            val d = timeoutDiagnosis()
            ConnectionTestResult.Failure("${device.name} : ${d.title} (${timeoutMs / 1000} s)", d.advice, d.problem)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val d = diagnose(e)
        val certificateProblem = d.problem == Problem.CERTIFICATE || d.problem == Problem.CERTIFICATE_CHANGED
        val sha = if (certificateProblem) safeFingerprint(fingerprint, device) else null
        return ConnectionTestResult.Failure(d.title, d.advice, d.problem, sha)
    } finally {
        withContext(NonCancellable) {
            withTimeoutOrNull(3_000) {
                try {
                    controller.disconnect()
                } catch (_: Exception) {
                    // La connexion d'essai est abandonnée de toute façon.
                }
            }
        }
    }
}

private class Described(val text: String?)

private suspend fun describeSafely(c: DescribesServer): String? = try {
    c.serverDescription()?.takeIf { it.isNotBlank() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private suspend fun safeFingerprint(fingerprint: suspend (Device) -> String?, device: Device): String? = try {
    fingerprint(device)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
