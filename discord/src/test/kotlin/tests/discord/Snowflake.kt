package tests.discord

import de.mineking.database.*
import de.mineking.database.vendors.postgres.PostgresMappers
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.ISnowflake
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import setup.*

data class SnowflakeDao(
	@AutoIncrement @Key @Column val id: Int = 0,
	@Column val snowflake: ISnowflake? = null,
)

class SnowflakeTest {
	val connection = createConnection()
	val table: Table<SnowflakeDao>

	val guilds = listOf(
		createSnowflake<Guild>(1),
		createSnowflake<Guild>(2)
	)
	init {
		//Use STRING mappers because they are more likely to fail
		connection.registerDiscordStringMappers(createJDA(guilds), PostgresMappers.STRING, PostgresMappers.ENUM)
		table = connection.getTable(name = "snowflake_test") { SnowflakeDao() }

		table.recreate()

		table.insert(SnowflakeDao(snowflake = guilds[0]))

		connection.driver.setSqlLogger(ConsoleSqlLogger)
	}

	@Test
	fun selectAll() {
		val result = table.select().list()
		assertEquals(1, result.size)
		assertEquals(guilds[0], result[0].snowflake)
	}

	@Test
	fun selectColumn() {
		assertEquals(guilds[0], table.selectValue(property(SnowflakeDao::snowflake)).first())
	}

	@Test
	fun selectCondition() {
		assertEquals(1, table.selectRowCount(where = property(SnowflakeDao::snowflake) isEqualTo value(guilds[0])))
		assertEquals(0, table.selectRowCount(where = property(SnowflakeDao::snowflake) isEqualTo value(guilds[1])))
	}
}