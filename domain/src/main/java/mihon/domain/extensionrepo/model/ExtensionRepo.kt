package mihon.domain.extensionrepo.model

data class ExtensionRepo(
    val baseUrl: String,
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
    val isVisible: Boolean,
    val author: String? = null,
    val discord: String? = null,
    val icon: String? = null,
)

// Well-known repo signing key fingerprints, used to select bundled repo icons
// cuong-tran's key (Yūzōnō)
const val YUZONO_SIGNATURE = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
// Keiyoushi's key
const val KEIYOUSHI_SIGNATURE = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
// SalmanBappi's key
const val SALMANBAPPI_SIGNATURE = "c7ebe223044970f2f9738f600dc25c180d3ed03994e088aaf5709338c57b93af"
