package com.rohit.ipoapply

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private val base = "https://webbackend.cdsc.com.np/api/meroShare"
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("ipo", MODE_PRIVATE) }
    private var loadingIpo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome(issues: JSONArray? = null) {
        page()
        title("IPO Apply")

        val list = saved()
        title("Saved accounts")
        if (list.length() == 0) text("No saved accounts yet")
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            button("${a.optString("name", a.optString("demat"))}\n${a.optString("username", a.optString("demat").takeLast(8))}") {
                showAccount(i)
            }
        }
        button("Add account") { showAdd() }
        if (list.length() > 0) button("Delete accounts") { showDeleteAccounts() }

        title("Available IPOs")
        val visibleIssues = issues ?: cachedIssues()
        if (visibleIssues != null) {
            if (visibleIssues.length() == 0) text("No IPOs available")
            for (i in 0 until visibleIssues.length()) {
                val issue = visibleIssues.getJSONObject(i)
                val scrip = issue.optString("scrip")
                text(
                    "$scrip\n${issue.optString("companyName")}\n${issue.optString("shareTypeName")} ${issue.optString("shareGroupName")}\n${issue.optString("subGroup")}\n${issue.optString("issueOpenDate")}\n${issue.optString("issueCloseDate")}\n${issue.optString("statusName")}"
                )
                button("Apply $scrip") { showApply(issue.toString()) }
            }
        } else {
            text(
                if (list.length() == 0) "Add an account first, then refresh IPO"
                else if (loadingIpo) "Checking for IPOs..."
                else "Tap Refresh IPO to check latest IPOs"
            )
        }
        if (list.length() > 0) button("Refresh IPO list") { loadIssuesHome() }
        msg("")
    }

    private fun showDeleteAccounts() {
        val list = saved()
        page()
        title("Choose accounts to delete")
        val checks = ArrayList<CheckBox>()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val cb = CheckBox(this)
            cb.text = "${a.optString("name", a.optString("demat"))}\n${a.optString("username", a.optString("demat").takeLast(8))}"
            root.addView(cb)
            checks.add(cb)
        }
        button("Select all") { for (c in checks) c.isChecked = true }
        button("Clear selection") { for (c in checks) c.isChecked = false }
        button("Delete selected accounts") {
            val next = JSONArray()
            for (i in 0 until list.length()) if (!checks[i].isChecked) next.put(list.getJSONObject(i))
            if (next.length() == list.length()) {
                msg("Select at least one account")
            } else {
                prefs.edit().putString("accounts", next.toString()).apply()
                showHome()
            }
        }
        button("Back") { showHome() }
        msg("")
    }

    private fun showAdd() {
        page()
        title("Add MeroShare account")
        val boid = input("16 digit BOID")
        val pass = input("MeroShare password", true)
        eye(pass, "password")
        button("Continue") {
            val b = boid.text.toString().filter { it.isDigit() }
            val p = pass.text.toString().trim()
            if (b.length != 16 || p.isBlank()) {
                msg("Enter a 16 digit BOID and password")
            } else {
                loginAndBanks(b, p)
            }
        }

        button("Back") { showHome() }
        msg("")
    }

    private fun showAccount(index: Int) {
        val list = saved()
        if (index >= list.length()) {
            showHome()
            return
        }
        val a = list.getJSONObject(index)
        page()
        title(a.optString("name", "Account"))
        text("Username: ${a.optString("username", a.optString("demat").takeLast(8))}")
        text("BOID: ${a.optString("demat", a.optString("boid"))}")
        text("Bank: ${a.optString("bankName")}")
        secret("CRN", a.optString("crn"))
        secret("PIN", a.optString("pin"))
        button("Delete") {
            val next = JSONArray()
            for (i in 0 until list.length()) if (i != index) next.put(list.getJSONObject(i))
            prefs.edit().putString("accounts", next.toString()).apply()
            showHome()
        }
        button("Back") { showHome() }
        msg("")
    }

    private fun loginAndBanks(boid: String, pass: String) {
        work("Signing in and finding banks") {
            val token = login(boid, pass)
            val detail = JSONObject(request("GET", "$base/ownDetail/", null, token).body)
            val banks = JSONArray(request("GET", "$base/bank/", null, token).body)
            ui { showBanks(detail.optString("name", boid), boid, pass, token, banks) }
        }
    }

    private fun showBanks(
        name: String,
        boid: String,
        pass: String,
        token: String,
        banks: JSONArray
    ) {
        page()
        title("Choose bank")
        if (banks.length() == 0) text("No banks found for this account")
        for (i in 0 until banks.length()) {
            val bank = banks.getJSONObject(i)
            button(bank.optString("name")) {
                loadAccounts(name, boid, pass, token, bank)
            }
        }
        button("Back") { showHome() }
        msg("Choose the bank linked to your CRN")
    }

    private fun loadAccounts(
        name: String,
        boid: String,
        pass: String,
        token: String,
        bank: JSONObject
    ) {
        work("Finding bank accounts") {
            val accounts = JSONArray(request("GET", "$base/bank/${bank.getInt("id")}", null, token).body)
            ui { showAccounts(name, boid, pass, token, bank, accounts) }
        }
    }

    private fun showAccounts(
        name: String,
        boid: String,
        pass: String,
        token: String,
        bank: JSONObject,
        accounts: JSONArray
    ) {
        page()
        title("Choose bank account")
        if (accounts.length() == 0) text("No bank accounts found")
        for (i in 0 until accounts.length()) {
            val account = accounts.getJSONObject(i)
            button("${account.optString("accountNumber")}\n${account.optString("accountTypeName")}") {
                showCrn(name, boid, pass, token, bank, account)
            }
        }
        button("Back") { showHome() }
        msg("Choose the account for IPO payment")
    }

    private fun showCrn(
        name: String,
        boid: String,
        pass: String,
        token: String,
        bank: JSONObject,
        account: JSONObject
    ) {
        page()
        title("Finish account setup")
        text("Bank: ${bank.optString("name")}")
        text("Account: ${account.optString("accountNumber")}")
        val crn = input("CRN number")
        val pin = input("Transaction PIN", true)
        eye(pin, "PIN")
        button("Save account") {
            val value = crn.text.toString().trim()
            val p = pin.text.toString().trim()
            if (value.isBlank() || p.isBlank()) {
                msg("Enter CRN and transaction PIN")
            } else {
                save(name.trim(), boid.trim(), pass.trim(), token.trim(), value, p, bank, account)
                showHome()
            }
        }
        button("Back") { showHome() }
        msg("")
    }

    private fun save(
        name: String,
        boid: String,
        pass: String,
        token: String,
        crn: String,
        pin: String,
        bank: JSONObject,
        account: JSONObject
    ) {
        val list = saved()
        val n = name.trim()
        val b = boid.trim()
        val p = pass.trim()
        val t = token.trim()
        val c = crn.trim()
        val pinValue = pin.trim()
        list.put(
            JSONObject()
                .put("name", n.ifBlank { b })
                .put("boid", b)
                .put("demat", b)
                .put("clientId", clientId(b))
                .put("username", b.takeLast(8))
                .put("boidSuffix", b.takeLast(8))
                .put("password", p)
                .put("token", t)
                .put("crn", c)
                .put("pin", pinValue)
                .put("bankId", bank.getInt("id"))
                .put("bankName", bank.optString("name").trim())
                .put("accountNumber", account.optString("accountNumber").trim())
                .put("customerId", account.optInt("id"))
                .put("accountBranchId", account.optInt("accountBranchId"))
                .put("accountTypeId", account.optInt("accountTypeId"))
        )
        prefs.edit().putString("accounts", list.toString()).apply()
    }

    private fun loadIssuesHome() {
        val list = saved()
        if (list.length() == 0) {
            msg("Add an account before refreshing IPO")
            return
        }
        if (loadingIpo) {
            msg("IPO refresh already running")
            return
        }
        loadingIpo = true
        prefs.edit().remove("issues").apply()
        showHome()
        work("Checking IPOs") {
            var err = ""
            for (i in 0 until list.length()) {
                try {
                    val issues = loadIssuesWithAccount(list, i)
                    ui {
                        loadingIpo = false
                        showHome(issues)
                    }
                    return@work
                } catch (e: Exception) {
                    err = e.message ?: "Error"
                }
            }
            ui {
                loadingIpo = false
                status.text = err.ifBlank { "Unable to load IPO" }
            }
        }
    }

    private fun loadIssuesWithAccount(list: JSONArray, index: Int): JSONArray {
        val a = list.getJSONObject(index)
        var token = a.optString("token")
        if (token.isNotBlank()) {
            try {
                return fetchIssues(token)
            } catch (_: Exception) {
            }
        }
        token = login(a)
        a.put("token", token)
        list.put(index, a)
        prefs.edit().putString("accounts", list.toString()).apply()
        return fetchIssues(token)
    }

    private fun fetchIssues(token: String): JSONArray {
        val issues = JSONObject(request2("POST", "$base/companyShare/applicableIssue/", issuePayload(), token).body)
            .optJSONArray("object") ?: JSONArray()
        prefs.edit().putString("issues", issues.toString()).apply()
        return issues
    }

    private fun showApply(issueText: String) {
        val list = saved()
        if (list.length() == 0) {
            msg("Add an account first")
            return
        }
        page()
        val issue = JSONObject(issueText)
        val scrip = issue.optString("scrip")
        title("Apply $scrip")
        text(issue.optString("companyName"))
        button("Back") { showHome() }
        work("Checking saved accounts") {
            val rows = JSONArray()
            val id = issue.getInt("companyShareId")
            for (i in 0 until list.length()) {
                val a = list.getJSONObject(i)
                val row = JSONObject().put("index", i).put("name", a.optString("name", a.optString("demat")))
                try {
                    val token = login(a)
                    val detail = JSONObject(request("GET", "$base/ownDetail/", null, token).body)
                    row.put("demat", detail.getString("demat"))
                    row.put("boid", detail.getString("boid"))
                    row.put("token", token)
                    val seen = issueFor(token, id)
                    val action = seen.optString("action")
                    if (action.isNotBlank()) {
                        row.put("ok", false)
                        row.put("message", "Already applied ($action)")
                    } else {
                        val check = JSONObject(request("GET", "$base/applicantForm/customerType/$id/${detail.getString("demat")}", null, token).body)
                        val m = check.optString("message")
                        row.put("ok", m.contains("can apply", true))
                        row.put("message", if (m.contains("can apply", true)) "Ready to apply" else m.ifBlank { "Cannot apply" })
                    }
                } catch (e: Exception) {
                    row.put("ok", false)
                    row.put("message", e.message ?: "Error")
                }
                rows.put(row)
            }
            ui { showApplyList(issueText, rows) }
        }
    }

    private fun issueFor(token: String, id: Int): JSONObject {
        val issues = JSONObject(request2("POST", "$base/companyShare/applicableIssue/", issuePayload(), token).body)
            .optJSONArray("object") ?: JSONArray()
        for (i in 0 until issues.length()) {
            val issue = issues.getJSONObject(i)
            if (issue.optInt("companyShareId") == id) return issue
        }
        return JSONObject()
    }

    private fun showApplyList(issueText: String, rows: JSONArray) {
        page()
        val issue = JSONObject(issueText)
        title("Apply ${issue.optString("scrip")}")
        text(issue.optString("companyName"))
        val checks = ArrayList<CheckBox>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val cb = CheckBox(this)
            cb.text = "${row.optString("name")}\n${row.optString("message")}"
            cb.isEnabled = row.optBoolean("ok")
            cb.isChecked = row.optBoolean("ok")
            root.addView(cb)
            checks.add(cb)
        }
        button("Select all") { for (c in checks) if (c.isEnabled) c.isChecked = true }
        button("Clear selection") { for (c in checks) if (c.isEnabled) c.isChecked = false }
        button("Review selected accounts") {
            val picked = JSONArray()
            for (i in 0 until rows.length()) if (checks[i].isChecked && rows.getJSONObject(i).optBoolean("ok")) picked.put(rows.getJSONObject(i))
            if (picked.length() == 0) msg("Select at least one account") else confirmApply(issueText, picked)
        }
        button("Back") { showHome() }
        msg("")
    }

    private fun confirmApply(issueText: String, rows: JSONArray) {
        page()
        val issue = JSONObject(issueText)
        title("Review application")
        text(issue.optString("scrip"))
        text(issue.optString("companyName"))
        for (i in 0 until rows.length()) text(rows.getJSONObject(i).optString("name"))
        button("Submit application") { applyIssue(issueText, rows) }
        button("Back") { showApplyList(issueText, rows) }
        msg("${rows.length()} selected")
    }

    private fun applyIssue(issueText: String, rows: JSONArray) {
        work("Submitting application") {
            val issue = JSONObject(issueText)
            val id = issue.getInt("companyShareId")
            val scrip = issue.optString("scrip")
            val out = StringBuilder()
            val list = saved()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val a = list.getJSONObject(row.getInt("index"))
                val name = a.optString("name", a.optString("boid"))
                try {
                    val token = row.optString("token").ifBlank { login(a) }
                    val active = JSONObject(request("GET", "$base/active/$id", null, token).body)
                    val payload = JSONObject()
                        .put("demat", row.getString("demat"))
                        .put("boid", row.getString("boid"))
                        .put("accountNumber", a.getString("accountNumber"))
                        .put("customerId", a.getInt("customerId"))
                        .put("accountBranchId", a.getInt("accountBranchId"))
                        .put("accountTypeId", a.getInt("accountTypeId"))
                        .put("appliedKitta", active.optString("minUnit", "10"))
                        .put("crnNumber", a.getString("crn"))
                        .put("transactionPIN", a.getString("pin"))
                        .put("companyShareId", id.toString())
                        .put("bankId", a.getInt("bankId").toString())
                        .toString()
                    val res = JSONObject(request("POST", "$base/applicantForm/share/apply", payload, token).body)
                    out.append(name).append(" - ").append(scrip).append(" - ").append(res.optString("message", "done")).append("\n")
                } catch (e: Exception) {
                    out.append(name).append(" - ").append(scrip).append(" - ").append(e.message ?: "error").append("\n")
                }
            }
            ui {
                page()
                title("Application result")
                text(out.toString())
                button("Done") { showHome() }
                msg("")
            }
        }
    }

    private fun saved() = JSONArray(prefs.getString("accounts", "[]") ?: "[]")

    private fun cachedIssues(): JSONArray? {
        val s = prefs.getString("issues", "") ?: ""
        return if (s.isBlank()) null else JSONArray(s)
    }

    private fun login(a: JSONObject): String {
        val body = JSONObject()
            .put("clientId", a.optString("clientId").ifBlank { clientId(a.getString("demat")) })
            .put("username", a.optString("username").ifBlank { a.getString("demat").takeLast(8) })
            .put("password", a.getString("password"))
            .toString()
        val r = request("POST", "$base/auth/", body, null)
        val t = r.token ?: JSONObject(r.body.ifBlank { "{}" }).optString("token")
            .ifBlank { JSONObject(r.body.ifBlank { "{}" }).optString("authorization") }
        if (t.isBlank()) throw RuntimeException("Login failed. Check BOID and password")
        return t
    }

    private fun login(boid: String, pass: String): String {
        val body = JSONObject()
            .put("clientId", clientId(boid))
            .put("username", boid.takeLast(8))
            .put("password", pass)
            .toString()
        val r = request("POST", "$base/auth/", body, null)
        val t = r.token ?: JSONObject(r.body.ifBlank { "{}" }).optString("token")
            .ifBlank { JSONObject(r.body.ifBlank { "{}" }).optString("authorization") }
        if (t.isBlank()) throw RuntimeException("Login failed. Check BOID and password")
        return t
    }

    private fun request2(method: String, url: String, payload: String?, token: String?): Res {
        return try {
            request(method, url, payload, token)
        } catch (e: RuntimeException) {
            Thread.sleep(900)
            request(method, url, payload, token)
        }
    }

    private fun issuePayload() = "{\"filterFieldParams\":[{\"key\":\"companyIssue.companyISIN.script\",\"alias\":\"Scrip\"},{\"key\":\"companyIssue.companyISIN.company.name\",\"alias\":\"Company Name\"},{\"key\":\"companyIssue.assignedToClient.name\",\"value\":\"\",\"alias\":\"Issue Manager\"}],\"page\":1,\"size\":10,\"searchRoleViewConstants\":\"VIEW_APPLICABLE_SHARE\",\"filterDateParams\":[{\"key\":\"minIssueOpenDate\",\"condition\":\"\",\"alias\":\"\",\"value\":\"\"},{\"key\":\"maxIssueCloseDate\",\"condition\":\"\",\"alias\":\"\",\"value\":\"\"}]}"

    private fun clientId(boid: String): String {
        val code = boid.substring(3, 8)
        val ids = "19000:1287,20600:1315,13200:128,12300:129,17200:130,22300:2155,21800:2136,11900:131,17500:201,14700:133,23200:2170,19100:1298,15000:135,20700:1314,15600:132,20900:1318,19500:1292,11700:137,13300:139,13400:140,12000:141,14500:142,11300:143,14900:144,20300:1311,19800:1305,10800:145,17600:153,21900:2137,11100:134,12200:151,11200:146,16200:147,18000:681,20500:1317,22900:2164,19600:1297,10100:138,17700:148,22800:2162,17400:149,13100:150,20000:1308,20800:1316,19900:1306,23100:2167,23300:2169,17900:402,22000:2140,20100:1309,18700:1271,18200:1182,14300:154,15200:156,16300:168,12400:195,10700:157,13800:158,16100:159,14100:155,21400:1327,22200:2156,16700:160,18900:1281,13600:161,21600:1329,19700:1295,21100:1325,12500:199,15900:163,16800:198,15100:166,10400:164,20400:1320,23400:2171,15700:167,15500:169,23500:2182,16400:165,15300:170,11500:171,13700:174,10600:173,10200:172,17300:162,11000:175,11800:176,21200:1324,17000:177,21300:1328,13900:178,16000:136,12600:179,22600:2161,14800:180,15400:152,16900:181,12800:182,18600:1270,19400:1293,16600:183,23000:2165,16500:184,22100:2142,21500:1326,21700:2134,18100:1080,14400:185,15800:186,22400:2157,11600:187,12700:188,18400:1189,19200:1294,18500:1196,18800:1274,12900:189,20200:1310,10900:190,14600:191,13000:192,14000:193,21000:1319,14200:194,19300:1296,17800:370,22500:2158,18300:1186,22700:2163,11400:196,17100:197,13500:200"
        val hit = ids.split(",").firstOrNull { it.startsWith("$code:") }
        return hit?.substringAfter(":") ?: throw RuntimeException("Unknown DP code $code")
    }

    private fun request(method: String, url: String, payload: String?, token: String?): Res {
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
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", token)
        if (payload != null) {
            c.doOutput = true
            c.outputStream.use { it.write(payload.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RuntimeException(cleanError(text, code))
        return Res(text, c.getHeaderField("Authorization") ?: c.getHeaderField("authorization"))
    }

    private fun cleanError(text: String, code: Int): String {
        return try {
            JSONObject(text).optString("message").ifBlank { "HTTP $code" }
        } catch (_: Exception) {
            text.ifBlank { "HTTP $code" }
        }
    }

    private fun page() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun title(s: String) = text(s)

    private fun text(s: String) {
        val v = TextView(this)
        v.text = s
        root.addView(v)
    }

    private fun input(hint: String, secret: Boolean = false): EditText {
        val v = EditText(this)
        v.hint = hint
        v.setSingleLine(true)
        if (secret) v.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(v)
        return v
    }

    private fun eye(v: EditText, label: String) {
        val b = Button(this)
        fun hidden() = v.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD
        b.text = "Show $label"
        b.setOnClickListener {
            val isHidden = hidden()
            v.inputType = if (isHidden) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            v.setSelection(v.text.length)
            b.text = if (isHidden) "Hide $label" else "Show $label"
        }
        root.addView(b)
    }

    private fun secret(label: String, value: String) {
        val v = TextView(this)
        v.text = "$label: ${"*".repeat(value.length.coerceAtLeast(4))}"
        root.addView(v)
        val b = Button(this)
        b.text = "Show $label"
        b.setOnClickListener {
            val shown = v.text.toString().endsWith(value)
            v.text = if (shown) "$label: ${"*".repeat(value.length.coerceAtLeast(4))}" else "$label: $value"
            b.text = if (shown) "Show $label" else "Hide $label"
        }
        root.addView(b)
    }

    private fun button(s: String, click: () -> Unit) {
        val b = Button(this)
        b.text = s
        b.setOnClickListener { click() }
        root.addView(b)
    }

    private fun msg(s: String) {
        status = TextView(this)
        status.text = s
        root.addView(status)
    }

    private fun work(doing: String, block: () -> Unit) {
        msg(doing)
        val spin = ProgressBar(this)
        root.addView(spin)
        Thread {
            try {
                block()
            } catch (e: Exception) {
                ui {
                    root.removeView(spin)
                    status.text = e.message ?: "Error"
                }
            }
        }.start()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private data class Res(val body: String, val token: String?)
}
