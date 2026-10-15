package com.daymark.todo

import com.daymark.todo.core.finishSignOut
import com.daymark.todo.identity.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.*
import java.io.Closeable
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.*
import javax.net.ssl.*

/** Real HTTPS responses stall at headers/body; no external service or trust bypass. */
class HttpCancellationTest {
    companion object {
        private lateinit var directory: java.nio.file.Path
        private lateinit var serverContext: SSLContext
        private lateinit var client: OkHttpClient
        @BeforeClass @JvmStatic fun certificate() {
            directory=Files.createTempDirectory("daymark-https-fixture-")
            val keytool=java.nio.file.Path.of(System.getProperty("java.home"),"bin","keytool").toString()
            val process=ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048",
                "-storetype","PKCS12","-keystore",directory.resolve("fixture.p12").toString(),"-storepass","fixture-password",
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2","-noprompt").redirectErrorStream(true).start()
            val output=process.inputStream.bufferedReader().readText()
            check(process.waitFor()==0) {output}
            val keys=KeyStore.getInstance("PKCS12").apply {Files.newInputStream(directory.resolve("fixture.p12")).use {load(it,"fixture-password".toCharArray())}}
            val managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(keys,"fixture-password".toCharArray())}
            val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(keys)}
            serverContext=SSLContext.getInstance("TLS").apply {init(managers.keyManagers,null,null)}
            val clientContext=SSLContext.getInstance("TLS").apply {init(null,trust.trustManagers,null)}
            client=OkHttpClient.Builder().sslSocketFactory(clientContext.socketFactory,trust.trustManagers.filterIsInstance<X509TrustManager>().single())
                .protocols(listOf(Protocol.HTTP_1_1)).followRedirects(false).followSslRedirects(false).build()
        }
        @AfterClass @JvmStatic fun cleanup() {directory.toFile().deleteRecursively();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    private class HttpsFixture(private val mode:String):Closeable {
        private val server=serverContext.serverSocketFactory.createServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
        private val executor=Executors.newSingleThreadExecutor {r -> Thread(r,"https-fixture").apply {isDaemon=true}}
        val accepted=CountDownLatch(1)
        val disconnected=CountDownLatch(1)
        @Volatile private var socket:SSLSocket?=null
        val url="https://localhost:${server.localPort}"
        init {
            executor.submit {
                try {
                    socket=(server.accept() as SSLSocket).apply {soTimeout=10_000;startHandshake()}
                    val peer=socket!!
                    val reader=peer.inputStream.bufferedReader()
                    while(true) {val line=reader.readLine() ?: break;if(line.isEmpty()) break}
                    val response=when(mode) {
                        "body" -> "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nContent-Type: application/json\r\n\r\n{"
                        "redirect" -> "HTTP/1.1 302 Found\r\nLocation: $url/elsewhere\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                        "success" -> "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}"
                        else -> ""
                    }
                    if(response.isNotEmpty()) {peer.outputStream.write(response.toByteArray());peer.outputStream.flush()}
                    accepted.countDown()
                    if(mode in setOf("body","headers")) reader.read()
                } finally {socket?.close();disconnected.countDown()}
            }
        }
        override fun close() {server.close();socket?.close();executor.shutdownNow()}
    }
    private class Sessions:AuthSessionStorage {
        var retained:Session?=Session("owner","owner@example.com","access","refresh",System.currentTimeMillis()+3600000)
        override fun session()=retained
        override fun sessionForRevocation()=retained
        override fun save(session:Session) {retained=session}
        override fun clear() {retained=null}
        override fun markOpened() {}
        lateinit var cloud:CloudConfig
        override fun config()=cloud
    }

    @Test fun deadlineDuringHeaderStallCancelsSocketAndCompletesLocalCleanup()=runBlocking {
        HttpsFixture("headers").use {fixture ->
            val sessions=Sessions().apply {cloud=CloudConfig(fixture.url,"public")}
            val start=System.nanoTime()
            val result=runCatching {
                try {withTimeout(1000) {AuthClient(sessions,HttpTokenTransport(client)).revokeCurrentSession()}}
                finally {finishSignOut({sessions.clear()})}
            }
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertNull(sessions.retained)
            assertTrue("Local cleanup was delayed by network I/O",(System.nanoTime()-start)/1_000_000<3000)
            assertEquals(0L,fixture.accepted.count)
            assertTrue("Socket was not cancelled",fixture.disconnected.await(2,TimeUnit.SECONDS))
        }
    }

    @Test fun deadlineAlsoCancelsBlockingResponseBodyRead()=runBlocking {
        HttpsFixture("body").use {fixture ->
            val result=runCatching {withTimeout(1000) {HttpTokenTransport(client).request(CloudConfig(fixture.url,"public"),"/rest/v1/test","GET",null,null)}}
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertEquals(0L,fixture.accepted.count)
            assertTrue(fixture.disconnected.await(2,TimeUnit.SECONDS))
        }
    }

    @Test fun transportStillParsesHttpsJsonAndRejectsRedirects()=runBlocking {
        HttpsFixture("success").use {fixture ->
            assertTrue(HttpTokenTransport(client).request(CloudConfig(fixture.url,"public"),"/test","GET",null,null).jsonObject["ok"]!!.jsonPrimitive.boolean)
        }
        HttpsFixture("redirect").use {fixture ->
            val result=runCatching {HttpTokenTransport(client).request(CloudConfig(fixture.url,"public"),"/test","GET",null,null)}
            assertEquals(302,(result.exceptionOrNull() as ApiException).status)
        }
    }
}
