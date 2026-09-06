package com.rohit.bulkipoapply

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object IpoApiClient {
    const val BASE_URL = "https://webbackend.cdsc.com.np/api/meroShare"

    fun clientId(boid: String): String {
        val code = boid.substring(3, 8)
        val ids = "19000:1287,20600:1315,13200:128,12300:129,17200:130,22300:2155,21800:2136,11900:131,17500:201,14700:133,23200:2170,19100:1298,15000:135,20700:1314,15600:132,20900:1318,19500:1292,11700:137,13300:139,13400:140,12000:141,14500:142,11300:143,14900:144,20300:1311,19800:1305,10800:145,17600:153,21900:2137,11100:134,12200:151,11200:146,16200:147,18000:681,20500:1317,22900:2164,19600:1297,10100:138,17700:148,22800:2162,17400:149,13100:150,20000:1308,20800:1316,19900:1306,23100:2167,23300:2169,17900:402,22000:2140,20100:1309,18700:1271,18200:1182,14300:154,15200:156,16300:168,12400:195,10700:157,13800:158,16100:159,14100:155,21400:1327,22200:2156,16700:160,18900:1281,13600:161,21600:1329,19700:1295,21100:1325,12500:199,15900:163,16800:198,15100:166,10400:164,20400:1320,23400:2171,15700:167,15500:169,23500:2182,16400:165,15300:170,11500:171,13700:174,10600:173,10200:172,17300:162,11000:175,11800:176,21200:1324,17000:177,21300:1328,13900:178,16000:136,12600:179,22600:2161,14800:180,15400:152,16900:181,12800:182,18600:1270,19400:1293,16600:183,23000:2165,16500:184,22100:2142,21500:1326,21700:2134,18100:1080,14400:185,15800:186,22400:2157,11600:187,12700:188,18400:1189,19200:1294,18500:1196,18800:1274,12900:189,20200:1310,10900:190,14600:191,13000:192,14000:193,21000:1319,14200:194,19300:1296,17800:370,22500:2158,18300:1186,22700:2163,11400:196,17100:197,13500:200"
        val hit = ids.split(",").firstOrNull { it.startsWith("$code:") }
        return hit?.substringAfter(":") ?: throw RuntimeException("Unknown DP code $code")
    }

    fun request(method: String, url: String, payload: String?, token: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Cache-Control", "no-cache")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Origin", "https://meroshare.cdsc.com.np")
        c.setRequestProperty("Pragma", "no-cache")
        c.setRequestProperty("Referer", "https://meroshare.cdsc.com.np/")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", token)
        if (payload != null) {
            c.doOutput = true
            c.outputStream.use { it.write(payload.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
        return text
    }

    fun login(account: JSONObject): String {
        val demat = account.getString("demat")
        val body = JSONObject()
            .put("clientId", account.optString("clientId").ifBlank { clientId(demat) })
            .put("username", account.optString("username").ifBlank { demat.takeLast(8) })
            .put("password", account.getString("password"))
            .toString()
        val res = request("POST", "$BASE_URL/auth/", body, null)
        val json = JSONObject(res.ifBlank { "{}" })
        val token = json.optString("token").ifBlank { json.optString("authorization") }
        if (token.isBlank()) throw RuntimeException("Login failed")
        return token
    }

    fun issuePayload() = "{\"filterFieldParams\":[{\"key\":\"companyIssue.companyISIN.script\",\"alias\":\"Scrip\"},{\"key\":\"companyIssue.companyISIN.company.name\",\"alias\":\"Company Name\"},{\"key\":\"companyIssue.assignedToClient.name\",\"value\":\"\",\"alias\":\"Issue Manager\"}],\"page\":1,\"size\":10,\"searchRoleViewConstants\":\"VIEW_APPLICABLE_SHARE\",\"filterDateParams\":[{\"key\":\"minIssueOpenDate\",\"condition\":\"\",\"alias\":\"\",\"value\":\"\"},{\"key\":\"maxIssueCloseDate\",\"condition\":\"\",\"alias\":\"\",\"value\":\"\"}]}"

    fun fetchIssues(account: JSONObject): JSONArray {
        val token = login(account)
        val response = try {
            request("POST", "$BASE_URL/companyShare/applicableIssue/", issuePayload(), token)
        } catch (e: Exception) {
            Thread.sleep(800)
            request("POST", "$BASE_URL/companyShare/applicableIssue/", issuePayload(), token)
        }
        return JSONObject(response).optJSONArray("object") ?: JSONArray()
    }
}
