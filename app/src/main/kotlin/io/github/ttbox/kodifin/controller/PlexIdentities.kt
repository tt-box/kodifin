package io.github.ttbox.kodifin.controller

import android.os.Build
import io.github.ttbox.kodifin.plex.PlexIdentity

/**
 * Identité Plex de cette installation : nom et modèle de l'appareil Android affichés dans
 * les « appareils autorisés » de plex.tv. Aucune autre donnée n'est transmise.
 */
fun androidPlexIdentity(installationId: String): PlexIdentity = PlexIdentity(
    installationId,
    deviceName = Build.MODEL,
    device = Build.MODEL,
    platformVersion = Build.VERSION.RELEASE,
)
