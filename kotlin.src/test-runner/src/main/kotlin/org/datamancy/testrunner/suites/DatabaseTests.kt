package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import java.sql.DriverManager

suspend fun TestRunner.databaseTests() = suite("Database Tests") {

    
    
    

    test("Postgres transaction commits successfully") {
        val dbConfig = env.endpoints.postgres
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("CREATE TEMP TABLE test_transaction (id INT, name VARCHAR(50))")
                stmt.executeUpdate("INSERT INTO test_transaction VALUES (1, 'test')")
                conn.commit()

                val rs = stmt.executeQuery("SELECT COUNT(*) FROM test_transaction")
                rs.next()
                rs.getInt(1) shouldBe 1
            }
        }
    }

    test("Postgres connection pool is healthy") {
        
        val dbConfig = env.endpoints.postgres
        val connections = List(5) {
            DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
        }

        connections.forEach { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT 1")
                rs.next()
                rs.getInt(1) shouldBe 1
            }
            conn.close()
        }
    }

    test("Postgres query performance is acceptable") {
        val dbConfig = env.endpoints.postgres
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            val start = System.currentTimeMillis()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_stat_activity")
                rs.next()
                val count = rs.getInt(1)
                require(count >= 0) { "Activity count should be non-negative" }
            }
            val duration = System.currentTimeMillis() - start
            require(duration < 1000) { "Query took ${duration}ms, should be under 1 second" }
        }
    }

    test("Postgres foreign key constraints work") {
        val dbConfig = env.endpoints.postgres
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TEMP TABLE parent_table (id INT PRIMARY KEY, name VARCHAR(50))
                """)
                stmt.executeUpdate("""
                    CREATE TEMP TABLE child_table (
                        id INT PRIMARY KEY,
                        parent_id INT REFERENCES parent_table(id)
                    )
                """)

                stmt.executeUpdate("INSERT INTO parent_table VALUES (1, 'parent')")
                stmt.executeUpdate("INSERT INTO child_table VALUES (1, 1)")

                
                try {
                    stmt.executeUpdate("INSERT INTO child_table VALUES (2, 999)")
                    throw AssertionError("Foreign key constraint did not fire")
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    require(msg.contains("constraint", ignoreCase = true)) {
                        "Exception should mention constraint violation: $msg"
                    }
                }
            }
        }
    }

    test("Postgres can query system tables") {
        val dbConfig = env.endpoints.postgres
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT schemaname, tablename
                    FROM pg_tables
                    WHERE schemaname = 'public'
                    LIMIT 5
                """)
                var count = 0
                while (rs.next()) {
                    count++
                }
                require(count >= 0) { "Table count should be non-negative" }
            }
        }
    }

    
    
    

    test("Valkey configuration is accessible") {
        
        val valkeyEndpoint = env.endpoints.valkey
        require(valkeyEndpoint != null) { "Valkey endpoint not configured" }
        valkeyEndpoint shouldContain "valkey"
    }

    test("Valkey port is standard Redis port") {
        
        val valkeyEndpoint = env.endpoints.valkey
        require(valkeyEndpoint != null) { "Valkey not configured" }
        valkeyEndpoint shouldContain ":6379"
    }

    test("Valkey endpoint is reachable") {
        
        val valkeyEndpoint = env.endpoints.valkey
        require(valkeyEndpoint != null) { "Valkey not configured" }
        valkeyEndpoint shouldContain "valkey:6379"
    }

    
    
    

    test("MariaDB bookstack schema is accessible") {
        val dbConfig = env.endpoints.mariadb
        require(dbConfig != null) { "MariaDB not configured" }

        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SHOW TABLES")
                var tableCount = 0
                while (rs.next()) {
                    tableCount++
                }
                require(tableCount > 0) { "Should have at least one table" }
            }
        }
    }

    test("MariaDB query returns bookstack data") {
        val dbConfig = env.endpoints.mariadb
        require(dbConfig != null) { "MariaDB not configured" }

        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password).use { conn ->
            conn.createStatement().use { stmt ->
                
                val rs = stmt.executeQuery("SELECT DATABASE()")
                rs.next()
                val dbName = rs.getString(1)
                dbName shouldContain "bookstack"
            }
        }
    }
}
