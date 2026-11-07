package io.github.ttbox.kodifin.model

/**
 * Média en cours, décrit pour être relu par un autre appareil (transfert de la lecture).
 * [url] est lisible tel quel par un lecteur du réseau local ; elle peut porter l'accès au serveur
 * (jeton Jellyfin, identifiants Kodi) puisque l'appareil cible doit pouvoir la télécharger.
 */
data class TransferableMedia(
    val url: String,
    val title: String? = null,
    val mimeType: String? = null,
    val positionMs: Long = 0,
    /**
     * Variante convertie par le serveur (H.264 / AAC), pour un lecteur qui ne lit pas l'original ;
     * elle démarre déjà à [positionMs] (une conversion à la volée ne se parcourt pas toujours).
     */
    val transcodedUrl: String? = null,
)

/** L'appareil sait décrire le média en cours pour qu'un autre le reprenne. */
interface SupportsTransferOut {
    /** `null` : rien en lecture, ou média non transférable (flux protégé, entrée TV…). */
    suspend fun currentMedia(): TransferableMedia?
}
