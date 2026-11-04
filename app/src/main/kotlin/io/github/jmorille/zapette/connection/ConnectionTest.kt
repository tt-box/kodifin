package io.github.jmorille.zapette.connection

import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.RemoteController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Résultat du bouton « Tester la connexion ». */
sealed interface ConnectionTestResult {
    val message: String

    data class Success(override val message: String) : ConnectionTestResult
    data class Failure(override val message: String) : ConnectionTestResult
}

/**
 * Crée un contrôleur pour [device], se connecte, puis se déconnecte, sans rien enregistrer.
 * Le contrôleur est toujours libéré, même en cas d'échec ou de délai dépassé.
 */
suspend fun testConnection(
    device: Device,
    timeoutMs: Long = 10_000,
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
        val connected = withTimeoutOrNull(timeoutMs) {
            controller.connect()
            true
        }
        return if (connected == true) {
            ConnectionTestResult.Success("Connexion réussie à ${device.name} (${device.kind.label})")
        } else {
            ConnectionTestResult.Failure("${device.name} ne répond pas (délai de ${timeoutMs / 1000} s dépassé)")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ConnectionTestResult.Failure(e.message ?: "Connexion impossible (${e.javaClass.simpleName})")
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
