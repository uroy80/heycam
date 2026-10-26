package heycam.core.data

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteStatement
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema v1 as committed in core/data/schemas matches the compiled database, and the bundled SQLite really provides
 * FTS5 with the trigram tokenizer and Room's sync triggers. A schema change without a new exported version (and a
 * Migration plus its test) fails here.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var rig: DataTestRig

    @Before fun setUp() { rig = DataTestRig(temp.root) }
    @After fun tearDown() = rig.close()

    private suspend fun <T> read(sql: String, row: (SQLiteStatement) -> T): List<T> =
        rig.db.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement -> buildList { while (statement.step()) add(row(statement)) } }
        }

    @Test
    fun exportedSchemaMatchesTheCompiledDatabase() = runTest {
        val exported = File("schemas/heycam.core.data.db.HeycamDatabase/1.json")
        assertThat(exported.isFile).isTrue()
        val database = Json.parseToJsonElement(exported.readText()).jsonObject.getValue("database").jsonObject
        assertThat(database.getValue("version").jsonPrimitive.int).isEqualTo(1)
        val hash = database.getValue("identityHash").jsonPrimitive.content
        assertThat(read("SELECT identity_hash FROM room_master_table") { it.getText(0) }).containsExactly(hash)
    }

    @Test
    fun fullTextIndexUsesTrigramsWithSyncTriggers() = runTest {
        val sql = read("SELECT sql FROM sqlite_master WHERE name = 'page_text_fts'") { it.getText(0) }.single()
        assertThat(sql).contains("USING FTS5")
        assertThat(sql).contains("tokenize=`trigram`")
        assertThat(sql).contains("content=`page_text`")
        val triggers = read("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'page_text'") { it.getText(0) }
        assertThat(triggers).hasSize(4)
        assertThat(read("PRAGMA foreign_keys") { it.getLong(0) }).containsExactly(1L)
    }
}
