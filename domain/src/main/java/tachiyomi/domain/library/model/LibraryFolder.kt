package tachiyomi.domain.library.model

data class LibraryFolder(
    val id: Long,
    val name: String,
    val categoryId: Long,
)
