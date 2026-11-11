package agu.analys.service

object PairNarrative {
    data class Ctx(
        val label: String,
        val ecosystem: String,
        val narrative: String
    )

    fun forBase(base: String): Ctx {
        val b = base.trim().uppercase()
        return when (b) {
            "BTC" -> Ctx(
                label = "Bitcoin — patokan arah seluruh pasar",
                ecosystem = "Driver utama sentimen kripto",
                narrative = "Kalau BTC gerak kuat, hampir semua pair IDR ikut bergoyang."
            )
            "ETH" -> Ctx(
                label = "Ethereum — L1 smart contract terbesar",
                ecosystem = "Ikut BTC + aliran DeFi/L2 (Arbitrum, Base, dll)",
                narrative = "ETH sering jadi jembatan risk-on setelah BTC stabil."
            )
            "SOL" -> Ctx(
                label = "Solana — L1 cepat, ekosistem meme/DeFi/NFT aktif",
                ecosystem = "Cenderung risk-on; sensitif sentimen meme + throughput network",
                narrative = "Naik sering karena hype ekosistem (meme, DeFi, atau narrative biaya murah/cepat), bukan cuma chart."
            )
            "BNB" -> Ctx(
                label = "BNB — token ekosistem Binance / BNB Chain",
                ecosystem = "Ikut BTC + sentimen CEX/Binance",
                narrative = "Gerakan BNB sering terkait aktivitas on-chain BNB Chain dan berita exchange."
            )
            "XRP" -> Ctx(
                label = "XRP — fokus pembayaran / lintas negara",
                ecosystem = "Sensitif berita regulasi & kemitraan payment",
                narrative = "Bukan murni ikut BTC; sering loncat karena headline legal/partnership."
            )
            "ADA" -> Ctx(
                label = "Cardano — L1 berbasis riset",
                ecosystem = "Alt L1; gerak lebih lambat vs SOL/ETH",
                narrative = "Narasinya upgrade & adopsi, jarang pure meme pump."
            )
            "DOGE", "SHIB", "PEPE", "FLOKI", "BONK", "WIF" -> Ctx(
                label = "Koin meme — didorong sosial & spekulasi",
                ecosystem = "Sangat sensitif risk-on BTC + hype medsos/KOL",
                narrative = "Receh/meme biasanya ikut angin: volume sosial naik → volatilitas meledak."
            )
            "MATIC", "POL" -> Ctx(
                label = "Polygon — scaling Ethereum",
                ecosystem = "Ikut ETH + narasi L2",
                narrative = "Gerak sering selaras ekosistem Ethereum."
            )
            "AVAX" -> Ctx(
                label = "Avalanche — L1 subnet / DeFi",
                ecosystem = "Alt L1 risk-on, korelasi BTC & musim DeFi",
                narrative = "Naik biasanya saat risk appetite alt L1 meningkat."
            )
            "SUI", "APT", "NEAR" -> Ctx(
                label = "Alt L1 / rantai modular",
                ecosystem = "Kompetisi L1; ikut siklus risk-on alt",
                narrative = "Sering soal narasi tech + rotasi modal dari BTC/ETH."
            )
            "UNI", "AAVE", "MKR", "PENDLE" -> Ctx(
                label = "Token DeFi / infrastruktur",
                ecosystem = "Ikut ETH + TVL / fee on-chain",
                narrative = "Naik saat musim DeFi atau utilitas on-chain ramai."
            )
            "FET", "RENDER", "TAO", "RNDR" -> Ctx(
                label = "Narasi AI / compute",
                ecosystem = "Sektor tematik AI; bisa lepas sementara dari BTC",
                narrative = "Gerak sering karena hype sektor AI, bukan cuma teknikal pair."
            )
            "TRX" -> Ctx(
                label = "TRON — fokus stablecoin & transfer murah",
                ecosystem = "Aktivitas on-chain USDT",
                narrative = "Stabilitas transfer & aliran stablecoin lebih relevan dari hype meme."
            )
            else -> Ctx(
                label = "Altcoin di pair IDR Tokocrypto",
                ecosystem = "Umumnya ikut BTC; receh lebih volatil & sensitif volume lokal",
                narrative = "Cek dulu: ikut BTC, ikut sektor, atau spekulasi volume Tokocrypto saja."
            )
        }
    }
}
