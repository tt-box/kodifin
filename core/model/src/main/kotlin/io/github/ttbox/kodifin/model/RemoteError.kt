package io.github.ttbox.kodifin.model

/**
 * Raison typée d'un échec de communication, portée par [RemoteException].
 *
 * Les protocoles ne rédigent plus de phrases : ils choisissent une raison et ses paramètres
 * (hôte, code, nom de service), et l'application la traduit dans la langue de l'utilisateur.
 * Le `message` des exceptions ne sert plus qu'aux journaux.
 *
 * Ajouter un cas oblige l'application à le traduire (le `when` qui les traduit est exhaustif).
 */
sealed interface RemoteError {
    // --- Réseau et transport ---

    /** Appareil ou serveur injoignable (connexion impossible). */
    data class Unreachable(val host: String? = null) : RemoteError

    /** Échange coupé en cours : réponse interrompue, WebSocket ou session fermée. */
    data class Interrupted(val host: String? = null) : RemoteError

    /** L'appareil n'a pas répondu dans le délai. */
    data object Timeout : RemoteError

    /** Réponse plus grosse que la limite acceptée (protection mémoire). */
    data class ResponseTooLarge(val host: String, val maxBytes: Long) : RemoteError

    /** Adresse (hôte, port, URL) inutilisable. */
    data class InvalidAddress(val address: String? = null) : RemoteError

    /** Négociation TLS impossible. */
    data class TlsFailed(val host: String? = null) : RemoteError

    /** Redirection vers un autre hôte refusée (les jetons ne doivent pas fuiter). */
    data object RedirectRefused : RemoteError

    /** Statut HTTP d'échec. */
    data class Http(val code: Int) : RemoteError

    // --- Protocole ---

    /**
     * Réponse illisible, incomplète, ou d'un autre service que celui attendu.
     * [service] : nom du produit attendu (« Kodi », « plex.tv »…), sans traduction.
     */
    data class UnexpectedResponse(val service: String? = null) : RemoteError

    /**
     * L'appareil a refusé la requête et donne lui-même la raison ([detail], affichée telle
     * quelle : c'est un texte de l'appareil, pas de l'application). [code] : code d'erreur du
     * protocole s'il existe (JSON-RPC, UPnP, ADB, Cast).
     */
    data class DeviceRefused(val detail: String? = null, val code: String? = null) : RemoteError

    // --- Authentification ---

    /** Identifiants refusés : il faut se reconnecter. */
    data object CredentialsRejected : RemoteError

    /** Jeton absent, expiré ou utilisateur disparu : il faut se reconnecter. */
    data object SessionExpired : RemoteError

    /** Mot de passe demandé par l'appareil. */
    data object PasswordRequired : RemoteError

    /** Mot de passe refusé. */
    data object PasswordRejected : RemoteError

    /** Code saisi (PIN, code d'appairage) refusé. */
    data object CodeRejected : RemoteError

    /** Code non validé à temps : recommencer. */
    data object CodeExpired : RemoteError

    /** Action réservée à un administrateur du serveur. */
    data object AdminRequired : RemoteError

    // --- État de l'appareil ---

    /** Rien n'est en cours de lecture. */
    data object NothingPlaying : RemoteError

    /** Aucun client (lecteur) contrôlable sur le serveur, ou client disparu. */
    data object NoControllableClient : RemoteError

    /** Élément (file d'attente, épisode, session) introuvable. */
    data object ItemNotFound : RemoteError

    /** Session de diffusion terminée par l'appareil. */
    data object SessionEnded : RemoteError

    /** Réglage à faire sur l'appareil ou le téléphone, avec un conseil précis. */
    data class SetupRequired(val setup: Setup) : RemoteError
}

/** Réglages connus dont l'application sait expliquer la marche à suivre. */
enum class Setup {
    /** Roku : Paramètres › Système › Paramètres système avancés › Contrôle par applications mobiles › Accès réseau. */
    ROKU_NETWORK_ACCESS,

    /** Fire TV / Android TV : débogage réseau activé et clé de l'application acceptée sur le téléviseur. */
    ADB_DEBUGGING,

    /** Android TV : fenêtre « Associer l'appareil avec un code » ouverte pendant l'appairage. */
    ADB_PAIRING_WINDOW,

    /** AirPlay : récepteur injoignable, ou compatible AirPlay 2 seulement. */
    AIRPLAY_1_REQUIRED,

    /** Jellyfin / Emby : vérifier le port et le chemin de base (ex. /jellyfin). */
    MEDIA_SERVER_BASE_PATH,

    /** Quick Connect désactivé ou absent sur le serveur. */
    QUICK_CONNECT_DISABLED,

    /** Chromecast : services Google Play absents ou Google Cast indisponible. */
    GOOGLE_CAST,
}

/**
 * Action non prise en charge, portée par [UnsupportedActionException]. L'application en tire
 * le libellé (« Volume absolu non pris en charge par Roku »).
 */
sealed interface UnsupportedAction {
    data object PlayUrl : UnsupportedAction
    data object Volume : UnsupportedAction
    data object Seek : UnsupportedAction
    data object TextInput : UnsupportedAction
    data object Search : UnsupportedAction
    data class Key(val key: RemoteKey) : UnsupportedAction
    data class Command(val command: UiCommand) : UnsupportedAction
    data class Power(val action: PowerAction) : UnsupportedAction

    /** Méthode du serveur absente de sa version (JSON-RPC Kodi…). */
    data class ServerMethod(val method: String) : UnsupportedAction

    /** Type de média que le lecteur ne sait pas lire. */
    data class MediaType(val type: String) : UnsupportedAction

    /** Caractères que la saisie de texte ne sait pas transmettre. */
    data class Characters(val characters: String) : UnsupportedAction

    /** Élément de bibliothèque qui ne se parcourt pas. */
    data object Browse : UnsupportedAction

    /** Élément de bibliothèque qui ne se lit pas. */
    data object Play : UnsupportedAction

    /** Élément qu'on ne peut pas ajouter à la file d'attente. */
    data object Enqueue : UnsupportedAction

    /** Retirer un élément de la file d'attente ou la vider (impossible pour un client distant). */
    data object EditQueue : UnsupportedAction

    /** Nettoyage de la bibliothèque (Jellyfin/Emby : fait lors de l'analyse). */
    data object CleanLibrary : UnsupportedAction

    /** Le média en cours n'a pas de sous-titres. */
    data object NoSubtitles : UnsupportedAction

    /** Le média en cours n'a pas de pistes audio sélectionnables. */
    data object NoAudioTracks : UnsupportedAction

    /** Le média en cours n'a pas de chapitres. */
    data object NoChapters : UnsupportedAction

    /** Mode de lecture (répétition, aléatoire) refusé par le lecteur. */
    data object PlayMode : UnsupportedAction
}
