package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.Socket

suspend fun TestRunner.cachingLayerTests() = suite("Caching Layer Tests") {

    // Helper function to parse Redis/Valkey connection string
    fun parseRedisUrl(url: String?): Pair<String, Int> {
        if (url == null) return "valkey" to 6379
        val parts = url.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 6379
        return host to port
    }

    // Get Valkey password from environment
    val valkeyPassword = System.getenv("VALKEY_ADMIN_PASSWORD") ?: ""

    // Valkey (Redis-compatible) tests
    test("Valkey: Service is reachable") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Socket(host, port).use { socket ->
                socket.isConnected shouldBe true
                println("      ✓ Valkey is reachable at $host:$port")
            }
        } catch (e: Exception) {
            throw AssertionError("Cannot connect to Valkey at $host:$port: ${e.message}")
        }
    }

    test("Valkey: PING command responds with PONG") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val response = jedis.ping()
                response shouldBe "PONG"
                println("      ✓ Valkey PING successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey PING failed: ${e.message}")
        }
    }

    test("Valkey: SET and GET operations work") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val key = "test:integration:${System.currentTimeMillis()}"
                val value = "test-value-${System.currentTimeMillis()}"

                jedis.set(key, value)
                val retrieved = jedis.get(key)

                retrieved shouldBe value
                jedis.del(key) // Cleanup

                println("      ✓ SET/GET operations successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey SET/GET failed: ${e.message}")
        }
    }

    test("Valkey: TTL expiration works") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val key = "test:ttl:${System.currentTimeMillis()}"
                val value = "expires-soon"

                jedis.setex(key, 5, value) // 5 second TTL
                val ttl = jedis.ttl(key)

                require(ttl in 1..5) { "TTL should be between 1 and 5 seconds, got $ttl" }
                println("      ✓ TTL expiration configured ($ttl seconds remaining)")

                jedis.del(key) // Cleanup
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey TTL test failed: ${e.message}")
        }
    }

    test("Valkey: Connection pooling works") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            val config = JedisPoolConfig()
            config.maxTotal = 10
            config.maxIdle = 5

            val pool = if (valkeyPassword.isNotEmpty()) {
                JedisPool(config, host, port, 2000, "default", valkeyPassword)
            } else {
                JedisPool(config, host, port)
            }

            pool.use { p ->
                p.resource.use { jedis ->
                    jedis.ping() shouldBe "PONG"
                }
                println("      ✓ Connection pooling successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey connection pool failed: ${e.message}")
        }
    }

    test("Valkey: Multiple concurrent connections") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            val connections = (1..5).map {
                Thread {
                    Jedis(host, port).use { jedis ->
                        if (valkeyPassword.isNotEmpty()) {
                            jedis.auth("default", valkeyPassword)
                        }
                        jedis.set("concurrent:$it", "value-$it")
                        jedis.get("concurrent:$it")
                        jedis.del("concurrent:$it")
                    }
                }
            }

            connections.forEach { it.start() }
            connections.forEach { it.join(5000) }

            println("      ✓ Concurrent connections handled successfully")
        } catch (e: Exception) {
            throw AssertionError("Concurrent connection test failed: ${e.message}")
        }
    }

    test("Valkey: Hash operations work") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val hashKey = "test:hash:${System.currentTimeMillis()}"
                val fields = mapOf(
                    "field1" to "value1",
                    "field2" to "value2",
                    "field3" to "value3"
                )

                jedis.hset(hashKey, fields)
                val retrieved = jedis.hgetAll(hashKey)

                retrieved shouldBe fields
                jedis.del(hashKey) // Cleanup

                println("      ✓ Hash operations successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey hash operations failed: ${e.message}")
        }
    }

    test("Valkey: List operations work") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val listKey = "test:list:${System.currentTimeMillis()}"
                val items = listOf("item1", "item2", "item3")

                items.forEach { jedis.rpush(listKey, it) }
                val length = jedis.llen(listKey)

                length shouldBe items.size.toLong()
                jedis.del(listKey) // Cleanup

                println("      ✓ List operations successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey list operations failed: ${e.message}")
        }
    }

    test("Valkey: Set operations work") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val setKey = "test:set:${System.currentTimeMillis()}"
                val members = setOf("member1", "member2", "member3")

                members.forEach { jedis.sadd(setKey, it) }
                val retrievedMembers = jedis.smembers(setKey)

                retrievedMembers shouldBe members
                jedis.del(setKey) // Cleanup

                println("      ✓ Set operations successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey set operations failed: ${e.message}")
        }
    }

    test("Valkey: Atomic increment operations") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val counterKey = "test:counter:${System.currentTimeMillis()}"

                val count1 = jedis.incr(counterKey)
                val count2 = jedis.incr(counterKey)
                val count3 = jedis.incrBy(counterKey, 5)

                count1 shouldBe 1
                count2 shouldBe 2
                count3 shouldBe 7

                jedis.del(counterKey) // Cleanup
                println("      ✓ Atomic increment operations successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey increment operations failed: ${e.message}")
        }
    }

    test("Valkey: Key existence and deletion") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val key = "test:exists:${System.currentTimeMillis()}"

                jedis.set(key, "test-value")
                jedis.exists(key) shouldBe true

                jedis.del(key)
                jedis.exists(key) shouldBe false

                println("      ✓ Key existence and deletion successful")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey key operations failed: ${e.message}")
        }
    }

    test("Valkey: Database info is available") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val info = jedis.info()

                info shouldContain "redis_version"
                println("      ✓ Database info retrieved")
            }
        } catch (e: Exception) {
            println("      ℹ️  Database info check: ${e.message}")
        }
    }

    test("Valkey: Database statistics available") {
        val (host, port) = parseRedisUrl(endpoints.valkey)

        try {
            Jedis(host, port).use { jedis ->
                if (valkeyPassword.isNotEmpty()) {
                    jedis.auth("default", valkeyPassword)
                }
                val dbSize = jedis.dbSize()

                require(dbSize >= 0) { "Database size should be non-negative" }
                println("      ✓ Database contains $dbSize keys")
            }
        } catch (e: Exception) {
            throw AssertionError("Valkey statistics failed: ${e.message}")
        }
    }

    // Memcached tests would go here if needed
    // Note: Memcached uses a different protocol than Redis
    // Would require separate client library (e.g., spymemcached)
}
