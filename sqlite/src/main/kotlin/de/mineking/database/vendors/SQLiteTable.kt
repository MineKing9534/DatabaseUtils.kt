package de.mineking.database.vendors

import de.mineking.database.*
import de.mineking.database.vendors.SQLiteConnection.Companion.logger
import org.jdbi.v3.core.kotlin.useHandleUnchecked
import org.jdbi.v3.core.result.ResultIterable
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import org.jdbi.v3.core.statement.Update
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import kotlin.reflect.KClass
import kotlin.reflect.KType

class SQLiteTable<T: Any>(
	type: KClass<*>,
	structure: TableStructure<T>,
	instance: () -> T
) : TableImplementation<T>(type, structure, instance) {
	override fun createTable() {
		val columns = structure.getAllColumns().filter { it.shouldCreate() }

		fun <C> formatColumn(column: ColumnData<T, C>): String {
			val root = column.getRootColumn()
			return """
				"${ column.name }" ${ column.mapper.getType(column, column.table, column.type).sqlName }
				${ if (root.property.hasDatabaseAnnotation<AutoGenerate>()) " default ${ root.property.getDatabaseAnnotation<AutoGenerate>()?.generator?.takeIf { it.isNotBlank() } ?: structure.manager.autoGenerate(column) } }" else "" }
				${ root.property.takeIf { !it.returnType.isMarkedNullable }?.let { " not null" } ?: "" }
			""".replace("\n", "").replace("\t", "")
		}

		val keys = structure.getKeys()
		val unique = columns.filter { it.getRootColumn().property.hasDatabaseAnnotation<Unique>() }.groupBy { it.getRootColumn().property.getDatabaseAnnotation<Unique>()!!.name }

		structure.manager.driver.useHandleUnchecked { it.createUpdate("""
			create table if not exists ${ structure.name } (
				${ columns.joinToString { formatColumn(it) } }
				${ if (keys.isNotEmpty()) ", primary key(${ keys.joinToString { "\"${ it.name }\"${ if (it.getRootColumn().property.hasDatabaseAnnotation<AutoIncrement>()) " autoincrement" else "" }" } })" else "" }
				${ if (unique.isNotEmpty()) ", ${ unique.map { "unique(${ it.value.joinToString { "\"${ it.name }\"" } })" }.joinToString() }" else "" }
			)
		""".replace("\n", "").replace("\t", "")).execute() }

		structure.manager.driver.useHandleUnchecked { it.createQuery("select * from ${ structure.name } limit 1").execute { supplier, context ->
			val meta = supplier.get().resultSet.metaData
			if (columns.size != meta.columnCount) logger.warn("Number of columns in code and database do not match (Code: ${ columns.size }, Database: ${ meta.columnCount })")
			else for (i in 1 .. meta.columnCount) {
				val name = meta.getColumnName(i)
				val column = structure.getColumnFromDatabase(name)

				if (column == null) logger.warn("Column $name from database not found in code")
			}
		} }
	}


	override fun selectRowCount(where: Where): Int {
		val sql = """
			select count(*) from ${ structure.name }
			${ createJoinList(structure.columns).joinToString(" ") }
			${ where.format(structure) }
		""".trim().replace("\\s+".toRegex(), " ")

		return structure.manager.execute { it.createQuery(sql)
			.bindMap(where.values(structure))
			.mapTo(Int::class.java)
			.first()
		}
	}

	private fun createJoinList(columns: Collection<DirectColumnData<*, *>>, prefix: Array<String> = emptyArray()): List<String> {
		val temp = columns.filter { it.reference != null }.filter { !it.type.isArray() }

		if (temp.isEmpty()) return emptyList()
		return temp.flatMap { listOf("""
			left join ${ it.reference!!.structure.name } 
			as "${ (prefix + it.name).joinToString(".") }" 
			on ${ (
				unsafeNode("\"${(prefix + it.name).joinToString(".")}\".\"${ it.reference!!.structure.getKeys().first().name }\"")
						isEqualTo
						unsafeNode("\"${prefix.joinToString(".").takeIf { it.isNotBlank() } ?: structure.name}\".\"${ it.name }\"")
				).get(structure) }
		""") + createJoinList(it.reference!!.structure.columns, prefix + it.name) }
	}

	private fun createSelect(columns: String, where: Where, order: Order?, limit: Int?, offset: Int?): String = """
		select $columns
		from ${ structure.name }
		${ createJoinList(structure.columns.reversed()).joinToString(" ") }
		${ where.format(structure) } 
		${ order?.format() ?: "" } 
		${ limit?.let { "limit $it" } ?: "" }
		${ offset?.let { "offset $it" } ?: "" } 
	""".trim().replace("\\s+".toRegex(), " ")

	override fun select(vararg columns: String, where: Where, order: Order?, limit: Int?, offset: Int?): QueryResult<T> {
		fun createColumnList(columns: Collection<ColumnData<*, *>>, prefix: Array<String> = emptyArray()): List<Pair<String, String>> {
			if (columns.isEmpty()) return emptyList()
			return columns
				.filterIsInstance<DirectColumnData<*, *>>()
				.filter { it.reference != null }
				.filter { !it.type.isArray() }
				.flatMap { createColumnList(it.reference!!.structure.getAllColumns(), prefix + it.name) } +
					columns.map { (prefix.joinToString(".").takeIf { it.isNotBlank() } ?: structure.name) to it.name }
		}

		val columnList = createColumnList(
			if (columns.isEmpty()) structure.getAllColumns()
			else columns.map { parseColumnSpecification(it, structure).column }.toSet()
		)

		val sql = createSelect(columnList.joinToString { "\"${ it.first }\".\"${ it.second }\" as \"${ it.first }.${ it.second }\"" }, where, order, limit, offset)
		return object : SimpleQueryResult<T> {
			override fun <O> execute(handler: (ResultIterable<T>) -> O): O = structure.manager.execute { handler(it.createQuery(sql)
				.bindMap(where.values(structure))
				.map { set, _ ->
					val instance = instance()
					parseResult(ReadContext(instance, structure, set, columnList.map { "${ it.first }.${ it.second }" }))
					instance
				}
			) }
		}
	}

	override fun <C> select(target: Node, type: KType, where: Where, order: Order?, limit: Int?, offset: Int?): QueryResult<C> {
		val column = target.columnContext(structure)
		val mapper = structure.manager.getTypeMapper<C, Any>(type, column?.column?.getRootColumn()?.property) ?: throw IllegalArgumentException("No suitable TypeMapper found")

		fun createColumnList(columns: List<ColumnData<*, *>>, prefix: Array<String> = emptyArray()): List<Pair<String, String>> {
			if (columns.isEmpty()) return emptyList()
			return columns
				.filterIsInstance<DirectColumnData<*, *>>()
				.filter { it.reference != null }
				.flatMap { createColumnList(it.reference!!.structure.getAllColumns(), prefix + it.name
				) } +
					(columns + columns.flatMap { if (it is DirectColumnData) it.getChildren() else emptyList() }).map { (prefix.joinToString(".").takeIf { it.isNotBlank() } ?: structure.name) to it.name }
		}

		val columnList = createColumnList(column?.column?.let { listOf(it) } ?: emptyList())

		val sql = createSelect((columnList.map { "\"${ it.first }\".\"${ it.second }\" as \"${ it.first }.${ it.second }\"" } + "(${ target.format(structure) }) as \"value\"").joinToString(), where, order, limit, offset)
		return object : SimpleQueryResult<C> {
			override fun <O> execute(handler: (ResultIterable<C>) -> O): O = structure.manager.execute { handler(it.createQuery(sql)
				.bindMap(target.values(structure, column?.column))
				.bindMap(where.values(structure))
				.map { set, _ -> mapper.read(column?.column?.getRootColumn(), type, ReadContext(it, structure, set, columnList.map { "${ it.first }.${ it.second }" } + "value", autofillPrefix = { it != "value" }, shouldRead = false), "value") }
			) }
		}
	}

	private fun executeUpdate(update: Update, obj: T) {
		val columns = structure.getAllColumns()

		columns.forEach {
			fun <C> createArgument(column: ColumnData<T, C>) = column.mapper.write(column, structure, column.type, column.get(obj))
			update.bind(it.name, createArgument(it))
		}

		update.execute { stmt, ctx -> ctx.use {
			val statement = stmt.get()
			val set = statement.resultSet

			if (set.next()) parseResult(ReadContext(obj, structure, set, columns.filter { it.getRootColumn().reference == null }.map { it.name }, autofillPrefix = { false }))
		} }
	}

	private fun <T> createResult(function: () -> T): UpdateResult<T> {
		return try {
			UpdateResult(function(), null, uniqueViolation = false, notNullViolation = false)
		} catch (e: UnableToExecuteStatementException) {
			val sqlException = e.cause as SQLiteException
			val result = UpdateResult<T>(null, sqlException, sqlException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE || sqlException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY, sqlException.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL)

			if (!result.uniqueViolation && !result.notNullViolation) throw e
			result
		}
	}

	override fun update(obj: T): UpdateResult<T> {
		if (obj is DataObject<*>) obj.beforeWrite()
		val identity = identifyObject(obj)

		val columns = structure.getAllColumns().filter { !it.getRootColumn().key }

		val sql = """
			update ${ structure.name }
			set ${columns.joinToString { "\"${ it.name }\" = :${ it.name }" }}
			${ identity.format(structure) }
			returning *
		""".trim().replace("\\s+".toRegex(), " ")

		return createResult {
			structure.manager.execute { executeUpdate(it.createUpdate(sql).bindMap(identity.values(structure)), obj) }
			if (obj is DataObject<*>) obj.afterRead()
			obj
		}
	}

	override fun update(column: String, value: Node, where: Where): UpdateResult<Int > {
		val spec = parseColumnSpecification(column, structure)

		require(spec.context.isEmpty()) { "Cannot update reference, update in the table directly" }
		require(!spec.column.getRootColumn().key) { "Cannot update key" }

		val sql = """
			update ${ structure.name } 
			set ${ spec.build(false) } = ${ value.format(structure) }
			${ where.format(structure) } 
		""".trim().replace("\\s+".toRegex(), " ")

		return createResult { structure.manager.execute { it.createUpdate(sql)
			.bindMap(value.values(structure, spec.column))
			.bindMap(where.values(structure))
			.execute()
		} }
	}

	override fun insert(obj: T): UpdateResult<T> {
		if (obj is DataObject<*>) obj.beforeWrite()

		val columns = structure.getAllColumns().filter {
			if (!it.getRootColumn().autogenerate) true
			else {
				val value = it.get(obj)
				value != 0 && value != null
			}
		}

		val sql = """
			insert into ${ structure.name }
			(${ columns.joinToString { "\"${ it.name }\"" } })
			values(${ columns.joinToString { ":${ it.name }" } }) 
			returning *
		""".trim().replace("\\s+".toRegex(), " ")

		return createResult {
			structure.manager.execute { executeUpdate(it.createUpdate(sql), obj) }
			if (obj is DataObject<*>) obj.afterRead()
			obj
		}
	}

	override fun upsert(obj: T): UpdateResult<T> {
		if (obj is DataObject<*>) obj.beforeWrite()

		val insertColumns = structure.getAllColumns().filter {
			if (!it.getRootColumn().autogenerate) true
			else {
				val value = it.get(obj)
				value != 0 && value != null
			}
		}

		val updateColumns = structure.getAllColumns().filter { !it.getRootColumn().key }


		val sql = """
			insert into ${ structure.name }
			(${ insertColumns.joinToString { "\"${ it.name }\"" } })
			values(${ insertColumns.joinToString { ":${ it.name }" } }) 
			on conflict (${ structure.getKeys().joinToString { "\"${ it.name }\"" } }) do update set
			${ updateColumns.joinToString { "\"${ it.name }\" = :${ it.name }" } }
			returning *
		""".trim().replace("\\s+".toRegex(), " ")

		return createResult {
			structure.manager.execute { executeUpdate(it.createUpdate(sql), obj) }
			if (obj is DataObject<*>) obj.afterRead()
			obj
		}
	}

	/**
	 * Does not support reference conditions (because postgres doesn't allow join in delete)
	 */
	override fun delete(where: Where): Int {
		val sql = "delete from ${ structure.name } ${ where.format(structure) }"
		return structure.manager.execute { it.createUpdate(sql)
			.bindMap(where.values(structure))
			.execute()
		}
	}
}