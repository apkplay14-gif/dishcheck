package ua.starlink.reader.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import javax.net.SocketFactory

/** Стан підключення до мережі Starlink. */
enum class LinkState {
    /** Телефон не в Wi-Fi. */
    NO_WIFI,

    /** Wi-Fi є, але тарілка на 192.168.100.1 не відповідає. */
    WIFI_NO_DISH,

    /** Тарілка відповідає. */
    READY,
}

object NetUtil {

    /**
     * Повертає Wi-Fi мережу. Прив'язка сокетів саме до неї потрібна тому, що Android
     * може лишити мобільний інтернет активним і відправити запит на 192.168.100.1
     * через стільникову мережу, де його ніхто не почує.
     */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val active = cm.activeNetwork
        if (active != null && isWifi(cm, active)) return active

        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { isWifi(cm, it) }
    }

    private fun isWifi(cm: ConnectivityManager, network: Network): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun socketFactory(context: Context): SocketFactory =
        wifiNetwork(context)?.socketFactory ?: SocketFactory.getDefault()

    /** Швидка TCP-перевірка, чи слухає порт. */
    fun reachable(factory: SocketFactory, host: String, port: Int, timeoutMs: Int = 1500): Boolean =
        try {
            factory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }

    fun linkState(context: Context): LinkState {
        val network = wifiNetwork(context) ?: return LinkState.NO_WIFI
        val factory = network.socketFactory
        val dishUp = reachable(factory, StarlinkClient.DISH_HOST, StarlinkClient.DISH_PORT)
        return if (dishUp) LinkState.READY else LinkState.WIFI_NO_DISH
    }
}
