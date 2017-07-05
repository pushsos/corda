package net.corda.node.utilities

import co.paralleluniverse.strands.Strand
import com.zaxxer.hikari.HikariDataSource
import java.io.Closeable
import java.io.PrintWriter
import java.sql.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger


class CordaDatabase(initDataSource: HikariDataSource) : javax.sql.DataSource, Closeable {

    companion object {
        private val threadLocalConnection = ThreadLocal<Connection>()
        private val threadLocalDataSource = ThreadLocal<CordaDatabase>()
        private val databaseToInstance = ConcurrentHashMap<CordaDatabase, HikariDataSource>()
        var cordaDatabase: CordaDatabase
            get() = threadLocalDataSource.get() ?: throw IllegalStateException("Was expecting to find database set on current strand: ${Strand.currentStrand()} ${Thread.currentThread().id}")
            set(value) {
                threadLocalDataSource.set(value)
            }
        fun currentOrNull(): Connection? = threadLocalConnection.get()
        // dataSource stored as a member instance for now
        // val dataSource: HikariDataSource
        // get() = databaseToInstance[cordaDatabase]!!
    }
    /** Storing in companion object */
    var dataSource = initDataSource

    init {
        cordaDatabase = this
        databaseToInstance[this] = initDataSource
        println(" proxy init thread=${Thread.currentThread().id} pool=${dataSource.poolName} obj=${this}")
    }

    override fun getParentLogger(): Logger {
        return dataSource.parentLogger
    }

    override fun setLoginTimeout(seconds: Int) {
        dataSource.loginTimeout = seconds
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean {
        return dataSource.isWrapperFor(iface)
    }

    override fun getLogWriter(): PrintWriter {
        return dataSource.logWriter
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        return dataSource.unwrap(iface)
    }

    override fun getConnection(): Connection {
        println(" proxy connect thread=${Thread.currentThread().id } pool=${dataSource.poolName}")
        val connection = dataSource.connection
        threadLocalConnection.set(connection)
        return connection
    }

    override fun getConnection(username: String?, password: String?): Connection {
        println(" proxy connect thread=${Thread.currentThread().id } pool=${dataSource.poolName}")
        val connection = dataSource.getConnection(username,password)
        threadLocalConnection.set(connection)
        return connection
    }

    override fun getLoginTimeout(): Int {
        return dataSource.loginTimeout
    }

    override fun setLogWriter(out: PrintWriter?) {
        dataSource.logWriter = out
    }

    override fun close() {
        println(" proxy close thread=${Thread.currentThread().id} pool=${dataSource.poolName} obj=${this}")
        dataSource.close()

    }
}
