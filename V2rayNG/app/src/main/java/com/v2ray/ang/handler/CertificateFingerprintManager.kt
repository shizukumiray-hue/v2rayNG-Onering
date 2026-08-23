package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CertSha256Request
import com.v2ray.ang.dto.CertSha256Result
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import libv2ray.Libv2ray

object CertificateFingerprintManager {
    private const val TIMEOUT_MS = 5000L

    fun fetchForManualFill(profile: ProfileItem): String? {
        val request = buildRequest(profile) ?: return null
        
        // Try to fetch using the libv2ray API with reflection to handle missing methods
        try {
            val methodName = if (profile.configType == EConfigType.HYSTERIA2) {
                "fetchQuicCertSha256"
            } else {
                "fetchTlsCertSha256"
            }
            
            // Check if the method exists in Libv2ray
            val method = Libv2ray.javaClass.getMethod(methodName, String::class.java)
            val result = fetch(
                if (profile.configType == EConfigType.HYSTERIA2) "quic" else "tls",
                request
            ) { jsonRequest ->
                method.invoke(null, jsonRequest) as String
            }

            return result
                ?.takeIf { it.error.isBlank() }
                ?.sha256
                ?.takeIf { it.isNotBlank() }
        } catch (e: NoSuchMethodException) {
            LogUtil.w(AppConfig.TAG, "Certificate fingerprint fetch API not available in libv2ray (Onering build)")
            return null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Certificate fingerprint fetch failed", e)
            return null
        }
    }

    private fun buildRequest(profile: ProfileItem): CertSha256Request? {
        if (!isFetchable(profile)) return null

        val server = profile.server?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it > 0 } ?: AppConfig.DEFAULT_PORT

        return CertSha256Request(
            address = resolveDialAddress(server),
            port = port,
            serverName = inferServerName(profile),
            timeoutMs = TIMEOUT_MS,
        )
    }

    private fun isFetchable(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.HYSTERIA2 || profile.security == AppConfig.TLS
    }

    private fun fetch(
        type: String,
        request: CertSha256Request,
        fetcher: (String) -> String,
    ): CertSha256Result? {
        return try {
            JsonUtil.fromJsonSafe(fetcher(JsonUtil.toJson(request)), CertSha256Result::class.java)
        } catch (e: UnsatisfiedLinkError) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 API missing in libv2ray", e)
            null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Fetch $type cert SHA-256 failed", e)
            null
        }
    }

    private fun resolveDialAddress(server: String): String {
        if (Utils.isPureIpAddress(server) || !Utils.isDomainName(server)) return server

        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6, false)
        return HttpUtil.resolveHostToIP(server, preferIpv6)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: server
    }

    private fun inferServerName(profile: ProfileItem): String? {
        val sni = profile.sni?.takeIf { it.isNotBlank() }
        return sni?.takeUnless { Utils.isPureIpAddress(it) }
    }
}
