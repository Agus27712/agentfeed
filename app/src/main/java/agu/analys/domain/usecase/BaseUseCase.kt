package agu.analys.domain.usecase

import kotlinx.coroutines.flow.Flow

/**
 * Kontrak dasar untuk Use Case sinkron/asinkron satu kali jalan (one-shot execution).
 */
interface UseCase<in Params, out Type> {
    suspend fun execute(params: Params): Type
}

/**
 * Kontrak dasar untuk Use Case berbasis aliran data (stream) menggunakan Kotlin Flow.
 */
interface FlowUseCase<in Params, out Type> {
    fun execute(params: Params): Flow<Type>
}

/**
 * Representasi parameter kosong jika Use Case tidak membutuhkan parameter input.
 */
object NoParams
