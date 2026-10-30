package mihon.feature.common.utils

import dev.icerock.moko.resources.StringResource
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.i18n.MR

fun MigrationFlag.getLabel(): StringResource {
    return when (this) {
        MigrationFlag.EPISODE -> MR.strings.episodes
        MigrationFlag.CATEGORY -> MR.strings.categories
        MigrationFlag.TRACK -> MR.strings.track
        MigrationFlag.CUSTOM_COVER -> MR.strings.manga_cover
        MigrationFlag.NOTES -> MR.strings.notes
        MigrationFlag.REMOVE_DOWNLOAD -> MR.strings.migrationConfigScreen_removeDownloadsTitle
        MigrationFlag.EXTRA -> MR.strings.extra
    }
}
