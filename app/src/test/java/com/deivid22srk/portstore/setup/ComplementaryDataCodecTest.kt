package com.deivid22srk.portstore.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do codec do repositório de dados complementares: encode/decode,
 * validação de schema/versão e detecção de arquivo corrompido — JVM puro.
 */
class ComplementaryDataCodecTest {

    private val item = ComplementaryRepoPayload(
        id = "port-db",
        name = "Port DB",
        url = "https://raw.githubusercontent.com/deivid22srk/port-db/HEAD/data/games.json",
        baseUrl = "https://raw.githubusercontent.com/deivid22srk/port-db/HEAD/",
        enabled = true,
        sortIndex = 0,
        gameCount = 42,
        cachedBody = """{"site":{},"games":[]}""",
        cachedAt = 1_700_000_000_000,
    )

    @Test
    fun `roundtrip completo preserva os dados`() {
        val text = ComplementaryDataCodec.encode(1_700_000_001_000, listOf(item))
        val file = ComplementaryDataCodec.decode(text)

        assertEquals(ComplementaryDataFormat.SCHEMA_NAME, file.schema)
        assertEquals(ComplementaryDataFormat.CURRENT_VERSION, file.version)
        assertEquals(1_700_000_001_000, file.exportedAt)
        assertEquals(1, file.itemCount)
        val decoded = file.items.single()
        assertEquals("port-db", decoded.id)
        assertEquals("Port DB", decoded.name)
        assertEquals(42, decoded.gameCount)
        assertEquals(item.cachedBody, decoded.cachedBody)
    }

    @Test
    fun `vários itens mantêm a contagem`() {
        val items = (0 until 5).map { i -> item.copy(id = "repo-$i", name = "Repo $i") }
        val file = ComplementaryDataCodec.decode(ComplementaryDataCodec.encode(0L, items))
        assertEquals(5, file.itemCount)
        assertEquals(5, file.items.size)
    }

    @Test
    fun `json aleatório é rejeitado como arquivo inválido`() {
        assertError(ComplementaryDataError.INVALID_FILE) {
            ComplementaryDataCodec.decode("""{"hello":"world"}""")
        }
    }

    @Test
    fun `texto malformado é rejeitado como arquivo inválido`() {
        assertError(ComplementaryDataError.INVALID_FILE) {
            ComplementaryDataCodec.decode("não sou json")
        }
    }

    @Test
    fun `schema diferente é rejeitado`() {
        assertError(ComplementaryDataError.INVALID_FILE) {
            ComplementaryDataCodec.decode(
                """{"schema":"outro-app","version":1,"exportedAt":0,"itemCount":0,"items":[]}""",
            )
        }
    }

    @Test
    fun `versão futura é incompatível`() {
        assertError(ComplementaryDataError.INCOMPATIBLE_VERSION) {
            ComplementaryDataCodec.decode(
                """{"schema":"portstore-complementary-data","version":999,"exportedAt":0,"itemCount":0,"items":[]}""",
            )
        }
    }

    @Test
    fun `versão 1 atual é aceita`() {
        val file = ComplementaryDataCodec.decode(
            """{"schema":"portstore-complementary-data","version":1,"exportedAt":7,"itemCount":0,"items":[]}""",
        )
        assertEquals(7, file.exportedAt)
    }

    @Test
    fun `contagem divergente indica corrupção`() {
        assertError(ComplementaryDataError.CORRUPTED_FILE) {
            ComplementaryDataCodec.decode(
                """{"schema":"portstore-complementary-data","version":1,"exportedAt":0,"itemCount":3,"items":[]}""",
            )
        }
    }

    @Test
    fun `item sem id ou nome indica corrupção`() {
        assertError(ComplementaryDataError.CORRUPTED_FILE) {
            ComplementaryDataCodec.decode(
                """{"schema":"portstore-complementary-data","version":1,"exportedAt":0,"itemCount":1,"items":[""" +
                    """{"id":"","name":"X","url":"https://ok"}]}""",
            )
        }
        assertError(ComplementaryDataError.CORRUPTED_FILE) {
            ComplementaryDataCodec.decode(
                """{"schema":"portstore-complementary-data","version":1,"exportedAt":0,"itemCount":1,"items":[""" +
                    """{"id":"a","name":"","url":"https://ok"}]}""",
            )
        }
    }

    private fun assertError(expected: ComplementaryDataError, block: () -> Unit) {
        try {
            block()
            throw AssertionError("esperava ComplementaryDataException(${expected.name})")
        } catch (e: ComplementaryDataException) {
            assertEquals(expected, e.error)
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            throw AssertionError("erro inesperado: $e", e)
        }
    }

    @Test
    fun `resumo recebe nomes dos repositórios`() {
        val file = ComplementaryDataCodec.decode(ComplementaryDataCodec.encode(1L, listOf(item, item.copy(id = "r2", name = "R2"))))
        val summary = ComplementarySummary(
            version = file.version,
            itemCount = file.itemCount,
            exportedAt = file.exportedAt,
            repoNames = file.items.map { it.name },
        )
        assertTrue(summary.repoNames.containsAll(listOf("Port DB", "R2")))
        assertEquals(2, summary.itemCount)
    }
}
