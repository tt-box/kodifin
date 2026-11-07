package io.github.ttbox.kodifin.testing

import io.github.ttbox.kodifin.model.RemoteError
import io.github.ttbox.kodifin.model.RemoteException
import io.github.ttbox.kodifin.model.UnsupportedAction
import io.github.ttbox.kodifin.model.UnsupportedActionException
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Utilitaires de test partagés entre modules (dépendance
 * `testImplementation(testFixtures(project(":core:model")))`).
 */

/**
 * Requête suivante reçue par le serveur, attendue au plus [timeoutSeconds] : un test qui
 * attend une requête jamais émise échoue au lieu de bloquer indéfiniment.
 */
fun MockWebServer.awaitRequest(timeoutSeconds: Long = 1): RecordedRequest =
    assertNotNull(takeRequest(timeoutSeconds, TimeUnit.SECONDS), "aucune requête reçue en $timeoutSeconds s")

/** Vérifie qu'aucune requête n'arrive pendant [timeoutMs]. */
fun MockWebServer.assertNoRequest(timeoutMs: Long = 200) {
    assertNull(takeRequest(timeoutMs, TimeUnit.MILLISECONDS), "requête inattendue reçue")
}

/** Libère les threads et connexions d'un client créé pour un test. */
fun OkHttpClient.release() {
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
}

/**
 * Exige une [RemoteException] dont la raison typée vaut [expected] ; renvoie l'exception.
 * Fonction `inline` : le bloc peut appeler des fonctions suspendues.
 */
inline fun assertFailsWithReason(expected: RemoteError, block: () -> Unit): RemoteException {
    val e = assertFailsWith<RemoteException>(block = block)
    assertEquals(expected, e.reason)
    return e
}

/** Exige une [UnsupportedActionException] portant l'action [expected] ; renvoie l'exception. */
inline fun assertUnsupported(expected: UnsupportedAction, block: () -> Unit): UnsupportedActionException {
    val e = assertFailsWith<UnsupportedActionException>(block = block)
    assertEquals(expected, e.action)
    return e
}

/**
 * Exige une exception de type [T] dont le message contient [fragment] (insensible à la casse).
 * Réservé aux exceptions dont le message est technique (journaux) : pour une [RemoteException],
 * préférer [assertFailsWithReason].
 * Fonction `inline` : le bloc peut appeler des fonctions suspendues.
 */
inline fun <reified T : Throwable> assertFailsWithMessage(fragment: String, block: () -> Unit): T {
    val e = assertFailsWith<T>(block = block)
    val message = e.message.orEmpty()
    assertTrue(message.contains(fragment, ignoreCase = true), "message « $message » sans « $fragment »")
    return e
}
