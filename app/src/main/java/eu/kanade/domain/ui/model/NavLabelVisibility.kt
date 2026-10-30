package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class NavLabelVisibility(val titleRes: StringResource) {
    ALWAYS(MR.strings.pref_bottom_nav_labels_always),
    SELECTED(MR.strings.pref_bottom_nav_labels_selected),
    NEVER(MR.strings.pref_bottom_nav_labels_never),
}
