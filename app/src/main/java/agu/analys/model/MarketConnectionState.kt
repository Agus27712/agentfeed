package agu.analys.model

sealed interface MarketConnectionState {
    object Connected : MarketConnectionState
    object Loading : MarketConnectionState
    data class ConnectionLost(
        val title: String = "Koneksi Pasar Terputus",
        val reason: String = "Gagal terhubung ke engine harga live. Data live dan analisis akan dihentikan sampai koneksi kembali."
    ) : MarketConnectionState
}
