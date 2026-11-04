package io.github.jmorille.zapette.cast

/**
 * Ports minces au-dessus du SDK Google Cast / MediaRouter : [CastController] ne dépend que de
 * ces interfaces et se teste avec des faux ; le câblage réel est dans [SdkCastPlatform].
 */

/** Route MediaRouter compatible Cast, réduite à ce qui sert à la retrouver. */
data class CastRoute(val id: String, val host: String?, val deviceId: String?)

/** Lecteur distant (RemoteMediaClient). */
interface CastMediaPort {
    val title: String?
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long

    fun togglePlayback()
    fun stop()
    fun next()
    fun previous()
    fun seek(positionMs: Long)

    /**
     * Charge et lance un média avec les [options] (style des sous-titres, plafond de débit) ;
     * lève [io.github.jmorille.zapette.model.RemoteException] si refusé.
     */
    suspend fun load(url: String, contentType: String, title: String, options: CastLoadOptions)

    /** Applique le style des sous-titres au média en cours (RemoteMediaClient.setTextTrackStyle). */
    fun setTextTrackStyle(style: CastSubtitleStyle)
}

/** Session Cast (CastSession). */
interface CastSessionPort {
    /** Identifiant de l'appareil Cast de la session. */
    val deviceId: String?
    val isConnected: Boolean
    val isConnecting: Boolean
    var volume: Double
    var isMute: Boolean

    /** `null` si aucun lecteur n'est actif. */
    val media: CastMediaPort?
}

/**
 * Événements du SessionManager. Le SDK les diffuse pour TOUTES les sessions (un autre écran peut
 * piloter un autre Chromecast) : chacun porte l'appareil concerné, `null` s'il est inconnu.
 */
interface CastSessionEvents {
    fun started(session: CastSessionPort)

    /** Échec d'ouverture ou de reprise de la session de l'appareil [deviceId]. */
    fun failed(deviceId: String?, message: String)

    /** Fin de la session de l'appareil [deviceId] (arrêt, coupure réseau, autre émetteur…). */
    fun ended(deviceId: String?) {}
}

/** Accès au SessionManager et au MediaRouter (thread principal). */
interface CastPlatform {
    /** Initialise le SDK si besoin ; lève RemoteException s'il est indisponible. */
    suspend fun ready()
    fun routes(): List<CastRoute>
    fun currentSession(): CastSessionPort?
    fun selectRoute(route: CastRoute)
    fun endCurrentSession()

    /** Écoute les ouvertures de session jusqu'à la fermeture du résultat. */
    fun listen(events: CastSessionEvents): AutoCloseable
}
