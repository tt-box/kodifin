package io.github.ttbox.kodifin.ui.remote

import androidx.annotation.StringRes
import io.github.ttbox.kodifin.R
import io.github.ttbox.kodifin.model.Capability

/** Disposition de la télécommande selon la place disponible (logique pure, testée sur JVM). */
enum class RemoteLayout {
    /** Téléphone : une page par onglet, balayage horizontal. */
    PHONE,

    /** Tablette en portrait : télécommande à gauche, volet à onglets à droite. */
    TABLET_PORTRAIT,

    /** Tablette en paysage : lecture en cours | pavé directionnel | file d'attente ou bibliothèque. */
    TABLET_LANDSCAPE,
}

/** Pages de la télécommande. [shortRes] : libellé d'onglet ; [labelRes] : description complète. */
enum class RemoteTab(@param:StringRes val shortRes: Int, @param:StringRes val labelRes: Int) {
    REMOTE(R.string.remote_tab_remote, R.string.remote_tab_remote),
    NOW_PLAYING(R.string.remote_tab_now_playing_short, R.string.remote_tab_now_playing),
    INFO(R.string.remote_tab_info_short, R.string.remote_tab_info),
    QUEUE(R.string.remote_tab_queue_short, R.string.remote_tab_queue),
    LIBRARY(R.string.remote_tab_library_short, R.string.remote_tab_library),
    ;

    /** Volet de contenu à charger quand la page est affichée. */
    val panel: RemotePanel?
        get() = when (this) {
            REMOTE -> null
            NOW_PLAYING -> RemotePanel.DETAILS
            // Fiche chargée à chaque changement de média, volet ouvert ou non.
            INFO -> null
            QUEUE -> RemotePanel.QUEUE
            LIBRARY -> RemotePanel.LIBRARY
        }
}

object RemoteLayouts {
    /** Largeur à partir de laquelle on passe en multi-volets (classe de fenêtre « medium »). */
    const val MEDIUM_WIDTH_DP = 600f

    /** Largeur « expanded » : trois volets côte à côte. */
    const val EXPANDED_WIDTH_DP = 840f

    /** En deçà, la hauteur ne permet pas d'empiler bandeau et pavé : disposition téléphone. */
    const val MIN_TABLET_HEIGHT_DP = 480f

    fun forSize(widthDp: Float, heightDp: Float): RemoteLayout = when {
        widthDp < MEDIUM_WIDTH_DP || heightDp < MIN_TABLET_HEIGHT_DP -> RemoteLayout.PHONE
        // Grande tablette tenue en portrait (ex. 876 × 1350 dp) : deux colonnes, pas trois.
        widthDp >= EXPANDED_WIDTH_DP && widthDp > heightDp -> RemoteLayout.TABLET_LANDSCAPE
        else -> RemoteLayout.TABLET_PORTRAIT
    }

    /** Onglets du téléphone : la télécommande, puis les pages que l'appareil sait alimenter. */
    fun phoneTabs(capabilities: Set<Capability>, features: RemoteFeatures): List<RemoteTab> = buildList {
        add(RemoteTab.REMOTE)
        if (Capability.PLAYBACK in capabilities || features.hasPlaybackDetails) add(RemoteTab.NOW_PLAYING)
        if (features.itemInfo) add(RemoteTab.INFO)
        if (features.queue || features.series) add(RemoteTab.QUEUE)
        if (features.library) add(RemoteTab.LIBRARY)
    }

    /**
     * Onglets du volet latéral. En portrait, la colonne de gauche porte déjà le bandeau et
     * le transport : « Lecture en cours » n'y figure que s'il a des détails (pistes, modes…).
     * En paysage, la colonne de gauche affiche ces détails, sauf s'ils ont été déplacés dans
     * le volet ([detailsInPane]) : ils y deviennent alors l'onglet « Lecture ». L'onglet « Fiche »
     * (résumé, distribution… du média en cours) suit, si l'appareil fournit des fiches.
     */
    fun sideTabs(layout: RemoteLayout, features: RemoteFeatures, detailsInPane: Boolean = false): List<RemoteTab> = buildList {
        val details = layout == RemoteLayout.TABLET_PORTRAIT || (layout == RemoteLayout.TABLET_LANDSCAPE && detailsInPane)
        if (details && features.hasPlaybackDetails) add(RemoteTab.NOW_PLAYING)
        if (features.itemInfo) add(RemoteTab.INFO)
        if (features.queue || features.series) add(RemoteTab.QUEUE)
        if (features.library) add(RemoteTab.LIBRARY)
    }

    /** Volets réellement affichés, transmis au ViewModel pour ne charger que ceux-là. */
    fun visiblePanels(
        layout: RemoteLayout,
        selected: RemoteTab?,
        features: RemoteFeatures,
        detailsInPane: Boolean = false,
    ): Set<RemotePanel> =
        buildSet {
            selected?.panel?.let(::add)
            if (layout == RemoteLayout.TABLET_LANDSCAPE && !detailsInPane && features.hasPlaybackDetails) add(RemotePanel.DETAILS)
        }
}
