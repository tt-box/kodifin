package io.github.jmorille.zapette.ui.remote

import io.github.jmorille.zapette.model.Capability

/** Disposition de la télécommande selon la place disponible (logique pure, testée sur JVM). */
enum class RemoteLayout {
    /** Téléphone : une page par onglet, balayage horizontal. */
    PHONE,

    /** Tablette en portrait : télécommande à gauche, volet à onglets à droite. */
    TABLET_PORTRAIT,

    /** Tablette en paysage : lecture en cours | pavé directionnel | file d'attente ou bibliothèque. */
    TABLET_LANDSCAPE,
}

/** Pages de la télécommande. [short] : libellé d'onglet ; [label] : description complète. */
enum class RemoteTab(val short: String, val label: String) {
    REMOTE("Télécommande", "Télécommande"),
    NOW_PLAYING("Lecture", "Lecture en cours"),
    QUEUE("File", "File d'attente"),
    LIBRARY("Bibliothèque", "Bibliothèque"),
    ;

    /** Volet de contenu à charger quand la page est affichée. */
    val panel: RemotePanel?
        get() = when (this) {
            REMOTE -> null
            NOW_PLAYING -> RemotePanel.DETAILS
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
        if (features.queue) add(RemoteTab.QUEUE)
        if (features.library) add(RemoteTab.LIBRARY)
    }

    /**
     * Onglets du volet latéral. En portrait, la colonne de gauche porte déjà le bandeau et
     * le transport : « Lecture en cours » n'y figure que s'il a des détails (pistes, modes…).
     * En paysage, le volet de gauche affiche toujours ces détails.
     */
    fun sideTabs(layout: RemoteLayout, features: RemoteFeatures): List<RemoteTab> = buildList {
        if (layout == RemoteLayout.TABLET_PORTRAIT && features.hasPlaybackDetails) add(RemoteTab.NOW_PLAYING)
        if (features.queue) add(RemoteTab.QUEUE)
        if (features.library) add(RemoteTab.LIBRARY)
    }

    /** Volets réellement affichés, transmis au ViewModel pour ne charger que ceux-là. */
    fun visiblePanels(layout: RemoteLayout, selected: RemoteTab?, features: RemoteFeatures): Set<RemotePanel> =
        buildSet {
            selected?.panel?.let(::add)
            if (layout == RemoteLayout.TABLET_LANDSCAPE && features.tracks) add(RemotePanel.DETAILS)
        }
}
