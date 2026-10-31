package com.my7.network

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.location.Location
import android.location.LocationManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity(), android.location.LocationListener {
    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val permissionRequest = 1001
    private val dashboardUrl = BuildConfig.DASHBOARD_URL
    @Volatile private var latestLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isTrustedDashboard(url)) requestDiagnosticPermissions()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                return !isTrustedDashboard(target.toString())
            }
        }

        if (isConfiguredUrl()) {
            webView.addJavascriptInterface(NetworkBridge(), "AndroidNetwork")
            webView.loadUrl(dashboardUrl)
        } else {
            webView.loadData(
                "<html><body style='font:16px sans-serif;padding:24px'><h2>Configure dashboard URL</h2><p>Set DASHBOARD_URL in android/app/build.gradle to your GitHub Pages URL, then rebuild the app.</p></body></html>",
                "text/html", "UTF-8"
            )
        }
    }

    private fun isConfiguredUrl(): Boolean =
        dashboardUrl.startsWith("https://") && !dashboardUrl.contains("YOUR_GITHUB_USERNAME")

    private fun isTrustedDashboard(url: String?): Boolean {
        if (url == null || !isConfiguredUrl()) return false
        return try {
            val expected = URL(dashboardUrl)
            val actual = URL(url)
            expected.protocol == "https" && actual.protocol == "https" &&
                expected.host.equals(actual.host, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun requestDiagnosticPermissions() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), permissionRequest)
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
            providers.forEach { provider ->
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 5000L, 0f, this)
                    val last = lm.getLastKnownLocation(provider)
                    if (last != null && (latestLocation == null || last.time > latestLocation!!.time)) latestLocation = last
                }
            }
        } catch (_: SecurityException) { }
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            startLocationUpdates()
            webView.evaluateJavascript("window.dispatchEvent(new Event('focus'));", null)
        }
    }

    inner class NetworkBridge {
        @JavascriptInterface
        fun getDiagnostics(): String {
            return try { collectDiagnostics().toString() }
            catch (e: Exception) { JSONObject().put("error", e.message ?: "Unable to collect diagnostics").toString() }
        }

        @JavascriptInterface
        fun lookupTower(apiKey: String) {
            if (apiKey.isBlank()) {
                sendTowerResult(JSONObject().put("ok", false).put("error", "API key is required"))
                return
            }
            executor.execute {
                val result = try { lookupOpenCellId(apiKey) }
                catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Tower lookup failed") }
                sendTowerResult(result)
            }
        }
    }

    private fun sendTowerResult(result: JSONObject) {
        runOnUiThread {
            val encoded = JSONObject.quote(result.toString())
            webView.evaluateJavascript("window.onTowerLookupResult && window.onTowerLookupResult($encoded);", null)
        }
    }

    private fun collectDiagnostics(): JSONObject {
        val out = JSONObject()
        out.put("manufacturer", Build.MANUFACTURER)
        out.put("model", Build.MODEL)
        out.put("androidVersion", Build.VERSION.RELEASE)
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val operatorNumeric = try { tm.networkOperator ?: "" } catch (_: SecurityException) { "" }
        out.put("operatorName", try { tm.networkOperatorName ?: "" } catch (_: SecurityException) { "" })
        out.put("mcc", operatorNumeric.takeIf { it.length >= 5 }?.substring(0, 3) ?: JSONObject.NULL)
        out.put("mnc", operatorNumeric.takeIf { it.length >= 5 }?.substring(3) ?: JSONObject.NULL)
        out.put("dataNetworkTypeName", networkTypeName(try { tm.dataNetworkType } catch (_: SecurityException) { 0 }))
        out.put("dataStateName", try {
            when (tm.dataState) {
                TelephonyManager.DATA_CONNECTED -> "Connected"
                TelephonyManager.DATA_CONNECTING -> "Connecting"
                TelephonyManager.DATA_DISCONNECTED -> "Disconnected"
                TelephonyManager.DATA_SUSPENDED -> "Suspended"
                else -> "Unknown"
            }
        } catch (_: SecurityException) { "Permission restricted" })

        val cells = JSONArray()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                tm.allCellInfo?.forEach { info -> cells.put(cellToJson(info)) }
            } catch (_: SecurityException) { }
        }
        out.put("cells", cells)
        out.put("location", getPhoneLocation())
        return out
    }

    private fun getPhoneLocation(): JSONObject {
        val result = JSONObject()
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return result.put("available", false)
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                            else listOf(LocationManager.NETWORK_PROVIDER)
            var best: Location? = latestLocation
            for (provider in providers) {
                val candidate = try { lm.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
                if (candidate != null && (best == null || candidate.accuracy < best!!.accuracy)) best = candidate
            }
            if (best != null) {
                result.put("available", true)
                result.put("latitude", best!!.latitude)
                result.put("longitude", best!!.longitude)
                result.put("accuracy", best!!.accuracy)
                result.put("timestamp", best!!.time)
            } else result.put("available", false)
        } catch (_: Exception) {
            result.put("available", false)
        }
        return result
    }

    private fun cellToJson(info: CellInfo): JSONObject {
        val o = JSONObject()
        o.put("registered", info.isRegistered)
        o.put("radio", "Unknown")
        o.put("cellId", JSONObject.NULL)
        o.put("tac", JSONObject.NULL)
        o.put("pci", JSONObject.NULL)
        o.put("arfcn", JSONObject.NULL)
        o.put("mcc", JSONObject.NULL)
        o.put("mnc", JSONObject.NULL)
        o.put("rsrp", JSONObject.NULL)
        o.put("rsrq", JSONObject.NULL)
        o.put("rssi", JSONObject.NULL)
        o.put("sinr", JSONObject.NULL)
        o.put("asuLevel", try { info.cellSignalStrength.asuLevel } catch (_: Exception) { -1 })

        when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                val s = info.cellSignalStrength
                o.put("radio", "LTE")
                o.put("cellId", safeInt(id.ci)); o.put("tac", safeInt(id.tac))
                o.put("pci", safeInt(id.pci)); o.put("arfcn", safeInt(id.earfcn))
                o.put("mcc", id.mccString ?: JSONObject.NULL); o.put("mnc", id.mncString ?: JSONObject.NULL)
                o.put("rsrp", safeInt(s.rsrp)); o.put("rsrq", safeInt(s.rsrq))
                o.put("rssi", safeInt(s.rssi)); o.put("sinr", safeInt(s.rssnr))
            }
            is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val s = info.cellSignalStrength as CellSignalStrengthNr
                o.put("radio", "NR / 5G")
                o.put("cellId", id.nci.toString()); o.put("tac", safeInt(id.tac))
                o.put("pci", safeInt(id.pci)); o.put("arfcn", safeInt(id.nrarfcn))
                o.put("mcc", id.mccString ?: JSONObject.NULL); o.put("mnc", id.mncString ?: JSONObject.NULL)
                o.put("rsrp", safeInt(s.ssRsrp)); o.put("rsrq", safeInt(s.ssRsrq))
                o.put("sinr", safeInt(s.ssSinr))
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                val s = info.cellSignalStrength
                o.put("radio", "WCDMA / 3G")
                o.put("cellId", safeInt(id.cid)); o.put("tac", safeInt(id.lac))
                o.put("pci", safeInt(id.psc)); o.put("arfcn", safeInt(id.uarfcn))
                o.put("mcc", id.mccString ?: JSONObject.NULL); o.put("mnc", id.mncString ?: JSONObject.NULL)
                o.put("rssi", safeInt(s.dbm))
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                val s = info.cellSignalStrength
                o.put("radio", "GSM / 2G")
                o.put("cellId", safeInt(id.cid)); o.put("tac", safeInt(id.lac))
                o.put("pci", safeInt(id.bsic)); o.put("arfcn", safeInt(id.arfcn))
                o.put("mcc", id.mccString ?: JSONObject.NULL); o.put("mnc", id.mncString ?: JSONObject.NULL)
                o.put("rssi", safeInt(s.dbm))
            }
            is CellInfoCdma -> {
                val id = info.cellIdentity
                o.put("radio", "CDMA")
                o.put("cellId", safeInt(id.basestationId)); o.put("tac", safeInt(id.networkId))
                o.put("pci", safeInt(id.systemId))
                o.put("rssi", safeInt(info.cellSignalStrength.dbm))
            }
        }
        return o
    }

    private fun safeInt(value: Int): Any =
        if (value == Int.MAX_VALUE || value == Int.MIN_VALUE || value < 0) JSONObject.NULL else value

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE / 4G"
        TelephonyManager.NETWORK_TYPE_NR -> "NR / 5G"
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA / 3G"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS / 3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE / 2G"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS / 2G"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM / 2G"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
        else -> "Other ($type)"
    }

    private fun lookupOpenCellId(apiKey: String): JSONObject {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val info = tm.allCellInfo?.firstOrNull { it.isRegistered } ?: tm.allCellInfo?.firstOrNull()
            ?: return JSONObject().put("ok", false).put("error", "Android did not return a serving cell. Check permissions and Location Services.")
        val cell = cellToJson(info)
        val radio = when (cell.optString("radio")) {
            "LTE" -> "LTE"
            "NR / 5G" -> "NR"
            "WCDMA / 3G" -> "UMTS"
            "GSM / 2G" -> "GSM"
            "CDMA" -> "CDMA"
            else -> return JSONObject().put("ok", false).put("error", "Unsupported or unknown radio type.")
        }
        val mcc = cell.optString("mcc")
        val mnc = cell.optString("mnc")
        val lac = cell.optString("tac")
        val cid = cell.optString("cellId")
        if (listOf(mcc, mnc, lac, cid).any { it.isBlank() || it == "null" || it == "Unavailable" }) {
            return JSONObject().put("ok", false).put("error", "Serving cell is missing MCC, MNC, TAC/LAC or Cell ID.")
        }
        val params = mapOf("key" to apiKey, "radio" to radio, "mcc" to mcc, "mnc" to mnc, "lac" to lac, "cellid" to cid, "format" to "json")
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val url = URL("https://opencellid.org/cell/get?$query")
        val connection = (url.openConnection() as HttpURLConnection)
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.requestMethod = "GET"
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return JSONObject().put("ok", false).put("error", "OpenCelliD returned HTTP $code: ${body.take(180)}")
            val data = JSONObject(body)
            if (!data.has("lat") || !data.has("lon")) return JSONObject().put("ok", false).put("error", "No matching cell coordinates found in OpenCelliD.")
            return JSONObject()
                .put("ok", true)
                .put("lat", data.optDouble("lat"))
                .put("lon", data.optDouble("lon"))
                .put("range", if (data.has("range")) data.opt("range") else JSONObject.NULL)
                .put("samples", if (data.has("samples")) data.opt("samples") else JSONObject.NULL)
                .put("source", "OpenCelliD")
        } finally { connection.disconnect() }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    override fun onDestroy() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) { }
        executor.shutdown()
        webView.removeJavascriptInterface("AndroidNetwork")
        webView.destroy()
        super.onDestroy()
    }
}
