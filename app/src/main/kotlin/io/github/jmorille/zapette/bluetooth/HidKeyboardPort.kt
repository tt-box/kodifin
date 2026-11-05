package io.github.jmorille.zapette.bluetooth

import kotlinx.coroutines.flow.StateFlow

/** Appareil Bluetooth hôte (la TV) : nom affiché et adresse. */
data class HidHost(val name: String, val address: String)

/** Disponibilité du mode « clavier Bluetooth » sur ce téléphone ou cette tablette. */
enum class HidAvailability {
    /** Android < 9 ou profil HID Device désactivé par le fabricant. */
    UNSUPPORTED,

    /** Pas de Bluetooth sur l'appareil. */
    NO_BLUETOOTH,

    /** Permission « Appareils à proximité » non accordée. */
    NO_PERMISSION,
    BLUETOOTH_OFF,
    READY,
}

/** État de la liaison HID, poussé par la pile Bluetooth. */
data class HidLinkState(
    /** L'application est déclarée comme clavier auprès de la pile Bluetooth. */
    val registered: Boolean = false,
    val connecting: HidHost? = null,
    val connected: HidHost? = null,
    val error: String? = null,
)

/** Accès au profil Bluetooth HID Device (isolé pour les tests JVM). */
interface HidKeyboardPort {
    val link: StateFlow<HidLinkState>
    fun availability(): HidAvailability

    /** Déclare l'application comme clavier ; sans effet si déjà fait. */
    fun start()

    /** Retire la déclaration et libère le profil. */
    fun stop()
    fun bondedHosts(): List<HidHost>
    fun connect(address: String)
    fun disconnect()

    /** `false` si le rapport n'a pas pu être remis (pas d'hôte connecté). */
    fun send(report: HidReport): Boolean
}
