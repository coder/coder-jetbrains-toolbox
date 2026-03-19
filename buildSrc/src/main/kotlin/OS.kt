enum class OS {
    WINDOWS,
    LINUX,
    MAC,
    OTHER;

    companion object {
        fun current(): OS? = from(System.getProperty("os.name"))

        private fun from(os: String?): OS? = when {
            os.isNullOrBlank() -> OTHER
            os.contains("win", true) -> WINDOWS
            os.contains("nix", true) || os.contains("nux", true) || os.contains("aix", true) -> LINUX
            os.contains("mac", true) || os.contains("darwin", true) -> MAC
            else -> OTHER
        }
    }
}
