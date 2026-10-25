package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class PanoramaMode {
    FOLLOW_GLOBAL,
    FORCE_ON,
    FORCE_OFF,
    ;

    fun resolve(global: Boolean): Boolean = when (this) {
        FOLLOW_GLOBAL -> global
        FORCE_ON -> true
        FORCE_OFF -> false
    }

    fun getLabelRes(): StringResource = when (this) {
        FOLLOW_GLOBAL -> MR.strings.panorama_mode_follow_global
        FORCE_ON -> MR.strings.panorama_mode_forced_on
        FORCE_OFF -> MR.strings.panorama_mode_forced_off
    }
}
