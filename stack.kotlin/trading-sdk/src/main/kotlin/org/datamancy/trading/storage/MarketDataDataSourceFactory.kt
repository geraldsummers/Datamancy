package org.datamancy.trading.storage

import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

object MarketDataDataSourceFactory {
    fun fromEnvironment(
        serviceName: String,
        defaultHost: String = "market-postgres",
        defaultPort: Int = 5432,
        defaultDatabase: String = "datamancy",
        defaultUser: String = "pipeline_user",
        defaultPassword: String = ""
    ): DataSource {
        val host = System.getenv("POSTGRES_HOST") ?: defaultHost
        val port = (System.getenv("POSTGRES_PORT") ?: defaultPort.toString()).toInt()
        val database = System.getenv("POSTGRES_DB") ?: defaultDatabase
        val user = System.getenv("POSTGRES_USER") ?: defaultUser
        val password = System.getenv("POSTGRES_PASSWORD") ?: defaultPassword
        val dataSource = PGSimpleDataSource().apply {
            serverNames = arrayOf(host)
            portNumbers = intArrayOf(port)
            databaseName = database
            this.user = user
            this.password = password
        }
        dataSource.connection.use { connection ->
            verifyCanonicalMarketDataDatabase(
                connection = connection,
                verificationKey = "$serviceName:$host:$port/$database:$user",
                descriptor = "$serviceName market-data connection $host:$port/$database as $user"
            )
        }
        return dataSource
    }
}
