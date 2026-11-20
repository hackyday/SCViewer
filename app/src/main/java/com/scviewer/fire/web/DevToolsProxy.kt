package com.scviewer.fire.web

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Proxy TCP → socket DevTools del WebView (SOLO build debug).
 *
 * Il WebSocket DevTools del WebView vive su `localabstract:webview_devtools_remote_<pid>`:
 * raggiungerlo richiede `adb forward`, che non è sempre disponibile (sessione
 * di test senza adb). Questo proxy espone lo stesso socket su una porta TCP
 * della LAN dello stick, così gli agenti di test parlano direttamente con
 * Chrome DevTools Protocol via WiFi: `http://<ip-stick>:9224/json`.
 *
 * Nessuna autenticazione: SOLO debug, rete domestica dell'utente. In release
 * la classe non viene mai avviata (guard sul chiamante in App.onCreate).
 */
object DevToolsProxy {

    private const val TAG = "com.scviewer.fire.debug"
    private const val PORT = 9224

    @Volatile private var server: ServerSocket? = null

    fun startIfDebug() {
        if (server != null) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            thread(name = "devtools-proxy", isDaemon = true) {
                while (!ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (e: IOException) {
                        break
                    }
                    thread(name = "devtools-proxy-conn", isDaemon = true) {
                        relay(client)
                    }
                }
            }
            Log.i(TAG, "DevToolsProxy attivo su :$PORT (socket webview_devtools_remote_${Process.myPid()})")
        } catch (t: Throwable) {
            Log.w(TAG, "DevToolsProxy non avviato", t)
        }
    }

    private fun relay(client: Socket) {
        val local = try {
            LocalSocket().apply {
                connect(LocalSocketAddress(
                    "webview_devtools_remote_${Process.myPid()}",
                    LocalSocketAddress.Namespace.ABSTRACT
                ))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DevToolsProxy: connessione al socket webview fallita", t)
            runCatching { client.close() }
            return
        }
        try {
            val t1 = thread(isDaemon = true) { pump(client.getInputStream(), local.outputStream) }
            val t2 = thread(isDaemon = true) { pump(local.inputStream, client.getOutputStream()) }
            // Quando uno dei due versi muore, chiudi tutto.
            t1.join()
            t2.interrupt()
        } catch (_: Throwable) {
        } finally {
            runCatching { client.close() }
            runCatching { local.close() }
        }
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream) {
        val buf = ByteArray(16384)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                return
            }
            if (n < 0) return
            try {
                output.write(buf, 0, n)
                output.flush()
            } catch (_: Throwable) {
                return
            }
        }
    }
}
