package com.mondns.app

/**
 * Preset profil device populer untuk Device & GPU Spoof feature.
 *
 * Kegunaan: banyak game mobile menggunakan Build.MODEL dan glGetString(GL_RENDERER)
 * untuk menentukan graphics quality preset (Low / Medium / High / Ultra).
 * Dengan menggunakan profil flagship, game bisa unlock quality tier yang lebih tinggi
 * pada hardware yang sebenarnya mampu.
 *
 * GPU yang di-spoof adalah nilai GL_RENDERER dan GL_VENDOR —
 * tidak mengubah performa GPU asli, hanya nama yang dilaporkan ke app.
 */
data class DeviceProfile(
    val label: String,           // Nama ditampilkan di UI
    val chipset: String,         // Info chipset untuk user
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val board: String,
    val fingerprint: String,
    val gpuRenderer: String,
    val gpuVendor: String
)

object DeviceProfiles {

    val CUSTOM = DeviceProfile(
        label = "Disable",
        chipset = "",
        manufacturer = "", brand = "", model = "", device = "",
        product = "", hardware = "", board = "", fingerprint = "",
        gpuRenderer = "", gpuVendor = ""
    )

    val ALL: List<DeviceProfile> = listOf(
        CUSTOM,

        // ── Snapdragon 8 Gen 3 ─────────────────────────────────────────────────
        DeviceProfile(
            label = "Samsung Galaxy S24 Ultra",
            chipset = "Snapdragon 8 Gen 3 · Adreno 750",
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S928B",
            device = "e3q",
            product = "e3qxxx",
            hardware = "qcom",
            board = "pineapple",
            fingerprint = "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXS1AXK1:user/release-keys",
            gpuRenderer = "Adreno (TM) 750",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "OnePlus 12",
            chipset = "Snapdragon 8 Gen 3 · Adreno 750",
            manufacturer = "OnePlus",
            brand = "OnePlus",
            model = "CPH2573",
            device = "waffle",
            product = "CPH2573",
            hardware = "qcom",
            board = "pineapple",
            fingerprint = "OnePlus/CPH2573/waffle:14/UP1A.231005.007/T.R1.202401161702:user/release-keys",
            gpuRenderer = "Adreno (TM) 750",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "Xiaomi 14 Pro",
            chipset = "Snapdragon 8 Gen 3 · Adreno 750",
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "24112RN95G",
            device = "shennong",
            product = "shennong",
            hardware = "qcom",
            board = "pineapple",
            fingerprint = "Xiaomi/shennong/shennong:14/UKQ1.230917.001/OS1.0.22.0.UNCMIXM:user/release-keys",
            gpuRenderer = "Adreno (TM) 750",
            gpuVendor = "Qualcomm"
        ),

        // ── Snapdragon 8 Gen 2 ─────────────────────────────────────────────────
        DeviceProfile(
            label = "Samsung Galaxy S23 Ultra",
            chipset = "Snapdragon 8 Gen 2 · Adreno 740",
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S918B",
            device = "dm3q",
            product = "dm3qxxx",
            hardware = "qcom",
            board = "kalama",
            fingerprint = "samsung/dm3qxxx/dm3q:13/TP1A.220624.014/S918BXXS2BWI1:user/release-keys",
            gpuRenderer = "Adreno (TM) 740",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "ASUS ROG Phone 7",
            chipset = "Snapdragon 8 Gen 2 · Adreno 740",
            manufacturer = "asus",
            brand = "asus",
            model = "ASUS_AI2205",
            device = "AI2205",
            product = "WW_AI2205",
            hardware = "qcom",
            board = "kalama",
            fingerprint = "asus/WW_AI2205/AI2205:13/TKQ1.220924.001/35.0210.0210.275:user/release-keys",
            gpuRenderer = "Adreno (TM) 740",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "OnePlus 11",
            chipset = "Snapdragon 8 Gen 2 · Adreno 740",
            manufacturer = "OnePlus",
            brand = "OnePlus",
            model = "CPH2449",
            device = "salami",
            product = "CPH2449",
            hardware = "qcom",
            board = "kalama",
            fingerprint = "OnePlus/CPH2449/OP594BL1:13/TP1A.220624.014/T.R3.202302062200:user/release-keys",
            gpuRenderer = "Adreno (TM) 740",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "Xiaomi 13 Pro",
            chipset = "Snapdragon 8 Gen 2 · Adreno 740",
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "2210132C",
            device = "nuwa",
            product = "nuwa",
            hardware = "qcom",
            board = "kalama",
            fingerprint = "Xiaomi/nuwa/nuwa:13/TKQ1.220905.001/V14.0.8.0.TMACNXM:user/release-keys",
            gpuRenderer = "Adreno (TM) 740",
            gpuVendor = "Qualcomm"
        ),

        // ── Snapdragon 8 Gen 1 ─────────────────────────────────────────────────
        DeviceProfile(
            label = "Samsung Galaxy S22 Ultra",
            chipset = "Snapdragon 8 Gen 1 · Adreno 730",
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S908B",
            device = "b0q",
            product = "b0qxxx",
            hardware = "qcom",
            board = "waipio",
            fingerprint = "samsung/b0qxxx/b0q:13/TP1A.220624.014/S908BXXU2BWI3:user/release-keys",
            gpuRenderer = "Adreno (TM) 730",
            gpuVendor = "Qualcomm"
        ),
        DeviceProfile(
            label = "ASUS ROG Phone 6",
            chipset = "Snapdragon 8 Gen 1 · Adreno 730",
            manufacturer = "asus",
            brand = "asus",
            model = "ASUS_AI2201",
            device = "AI2201",
            product = "WW_AI2201",
            hardware = "qcom",
            board = "waipio",
            fingerprint = "asus/WW_AI2201/AI2201:12/SKQ1.220303.001/32.0810.0810.208:user/release-keys",
            gpuRenderer = "Adreno (TM) 730",
            gpuVendor = "Qualcomm"
        ),

        // ── Dimensity 9200 ─────────────────────────────────────────────────────
        DeviceProfile(
            label = "Vivo X90 Pro+",
            chipset = "Dimensity 9200 · Mali-G715 MC11",
            manufacturer = "vivo",
            brand = "vivo",
            model = "V2227A",
            device = "V2227A",
            product = "V2227A",
            hardware = "mt6985",
            board = "mt6985",
            fingerprint = "vivo/V2227A/V2227A:13/TP1A.220624.014/compile18.0:user/release-keys",
            gpuRenderer = "Mali-G715 MC11",
            gpuVendor = "ARM"
        )
    )

    /** Kembalikan profil berdasarkan label, atau CUSTOM kalau tidak ditemukan. */
    fun findByLabel(label: String): DeviceProfile =
        ALL.firstOrNull { it.label == label } ?: CUSTOM
}
