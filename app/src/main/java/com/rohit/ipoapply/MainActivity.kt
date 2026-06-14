package com.rohit.bulkipoapply

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL
import java.io.IOException
import java.util.Locale

class MainActivity : Activity() {
    private val base = "https://webbackend.cdsc.com.np/api/meroShare"
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("ipo", MODE_PRIVATE) }
    private var loadingIpo = false
    private var busy = false
    private val backStack = ArrayList<() -> Unit>()
    private val tokens = HashMap<String, String>()

    private data class SelectionHeader(val note: TextView, val mark: TextView)
    private data class KittaControls(
        val card: LinearLayout,
        val detail: TextView,
        val controls: LinearLayout,
        val value: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                handleBackPressed()
            }
        }
        showHome()
        if (saved().length() > 0) loadIssuesHome()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                handleBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackPressed() {
        if (backStack.isEmpty()) {
            finish()
        } else {
            backStack.removeAt(backStack.size - 1).invoke()
        }
    }

    private fun showHome(issues: JSONArray? = null, ipoError: String = "") {
        backStack.clear()
        page()
        appTitle("Bulk IPO Apply")

        val list = saved()
        section("Accounts", if (list.length() == 0) "" else "${list.length()} saved")
        if (list.length() == 0) {
            empty("No saved accounts yet")
            button("Add account") { open({ showHome() }) { showAdd() } }
        } else {
            row("Manage accounts", "${list.length()} saved accounts") {
                open { showSavedAccounts() }
            }
        }

        val visibleIssues = issues ?: cachedIssues()
        sectionAction(
            "Available IPOs",
            "",
            !loadingIpo
        ) { loadIssuesHome() }
        if (visibleIssues != null) {
            if (visibleIssues.length() == 0) empty("No IPOs available")
            for (i in 0 until visibleIssues.length()) {
                val issue = visibleIssues.getJSONObject(i)
                ipoCard(issue)
            }
        } else {
            if (loadingIpo) loadingBlock("Checking latest IPOs", "Waiting for Response")
            else if (ipoError.isNotBlank()) issueStateBlock("Unable to refresh IPOs", ipoError, true)
            else empty(if (list.length() == 0) "Add an account first, then refresh IPO" else "Tap refresh to check latest IPOs")
        }
        msg("")
    }

    private fun showSavedAccounts() {
        val list = saved()
        page()
        title("Accounts")
        if (list.length() == 0) empty("No saved accounts yet")
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            row(a.optString("name", a.optString("demat")), a.optString("username", a.optString("demat").takeLast(8))) {
                open({ showSavedAccounts() }) { showAccount(i) }
            }
        }
        button("Add account") { open({ showSavedAccounts() }) { showAdd() } }
        if (list.length() > 0) button("Delete accounts", secondary = true) { open({ showSavedAccounts() }) { showDeleteAccounts() } }
        msg("")
    }

    private fun open(back: () -> Unit = { showHome() }, screen: () -> Unit) {
        backStack.add(back)
        screen()
    }

    private fun showDeleteAccounts() {
        val list = saved()
        page()
        title("Choose accounts to delete")
        val selected = ArrayList<Boolean>()
        val cards = ArrayList<LinearLayout>()
        val notes = ArrayList<TextView>()
        var toggle: Button? = null
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val card = selectAccountCard(a, false)
            val note = card.getChildAt(1) as TextView
            selected.add(false)
            cards.add(card)
            notes.add(note)
            val cardIndex = cards.size - 1
            card.setOnClickListener {
                selected[cardIndex] = !selected[cardIndex]
                syncDeleteCard(cards[cardIndex], notes[cardIndex], selected[cardIndex])
                toggle?.text = if (selected.isNotEmpty() && selected.all { it }) "Clear selection" else "Select all"
            }
        }
        toggle = selectToggle(selected, cards, notes, true)
        button("Delete selected accounts", danger = true) {
            val next = JSONArray()
            var count = 0
            for (i in 0 until list.length()) if (!selected[i]) next.put(list.getJSONObject(i))
            for (i in 0 until selected.size) if (selected[i]) count++
            if (next.length() == list.length()) {
                error("Select at least one account")
            } else {
                confirmDelete(count) {
                    prefs.edit().putString("accounts", next.toString()).apply()
                    showSavedAccounts()
                }
            }
        }
        msg("")
    }

    private fun showAdd() {
        page()
        title("Add MeroShare account")
        val boid = input("16 digit BOID")
        val pass = input("MeroShare password", true)
        button("Continue") {
            val b = boid.text.toString().filter { it.isDigit() }
            val p = pass.text.toString().trim()
            if (b.length != 16 || p.isBlank()) {
                error("Enter a 16 digit BOID and password")
            } else {
                loginAndBanks(b, p)
            }
        }

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
        field("Username", a.optString("username", a.optString("demat").takeLast(8)))
        field("BOID", a.optString("demat", a.optString("boid")))
        field("Bank", a.optString("bankName"))
        secretField("CRN", a.optString("crn"))
        secretField("PIN", a.optString("pin"))
        button("Edit CRN / PIN", secondary = true) {
            open({ showAccount(index) }) { showEditAccount(index) }
        }
        button("Delete", danger = true) {
            val next = JSONArray()
            for (i in 0 until list.length()) if (i != index) next.put(list.getJSONObject(i))
            confirmDelete(1) {
                prefs.edit().putString("accounts", next.toString()).apply()
                showSavedAccounts()
            }
        }
        msg("")
    }

    private fun showEditAccount(index: Int) {
        val list = saved()
        if (index >= list.length()) {
            showSavedAccounts()
            return
        }
        val a = list.getJSONObject(index)
        page()
        title("Edit account")
        field("Username", a.optString("username", a.optString("demat").takeLast(8)))
        field("BOID", a.optString("demat", a.optString("boid")))
        field("Bank", a.optString("bankName"))
        val crn = input("CRN number")
        crn.setText(a.optString("crn"))
        crn.setSelection(crn.text.length)
        val pin = input("Transaction PIN", true)
        pin.setText(a.optString("pin"))
        pin.setSelection(pin.text.length)
        button("Save changes") {
            val crnValue = crn.text.toString().trim()
            val pinValue = pin.text.toString().trim()
            if (crnValue.isBlank() || pinValue.isBlank()) {
                error("Enter CRN and transaction PIN")
            } else {
                updateAccountEditableInfo(index, crnValue, pinValue)
                if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                showAccount(index)
            }
        }
        msg("")
    }

    private fun updateAccountEditableInfo(index: Int, crn: String, pin: String) {
        val list = saved()
        val next = JSONArray()
        for (i in 0 until list.length()) {
            val account = JSONObject(list.getJSONObject(i).toString())
            if (i == index) {
                account.put("crn", crn.trim())
                account.put("pin", pin.trim())
            }
            next.put(account)
        }
        prefs.edit().putString("accounts", next.toString()).apply()
    }

    private fun confirmDelete(count: Int, yes: () -> Unit) {
        val many = count > 1
        AlertDialog.Builder(this)
            .setTitle(if (many) "Delete accounts?" else "Delete account?")
            .setMessage(
                if (many) "This will remove $count saved accounts from this device."
                else "This will remove this saved account from this device."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> yes() }
            .show()
    }

    private fun loginAndBanks(boid: String, pass: String) {
        work("Setting up your account") {
            val token = login(boid, pass)
            val detail = JSONObject(request("GET", "$base/ownDetail/", null, token).body)
            val banks = JSONArray(request("GET", "$base/bank/", null, token).body)
            ui { open({ showAdd() }) { showBanks(detail.optString("name", boid), boid, pass, token, banks) } }
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
        text("Choose the bank linked to your CRN")
        if (banks.length() == 0) text("No banks found for this account")
        if (banks.length() == 1) {
            loadAccounts(name, boid, pass, token, banks, banks.getJSONObject(0)) { showAdd() }
            return
        }
        for (i in 0 until banks.length()) {
            val bank = banks.getJSONObject(i)
            row(bank.optString("name"), "Use this bank") {
                loadAccounts(name, boid, pass, token, banks, bank)
            }
        }
        msg("")
    }

    private fun loadAccounts(
        name: String,
        boid: String,
        pass: String,
        token: String,
        banks: JSONArray,
        bank: JSONObject,
        back: () -> Unit = { showBanks(name, boid, pass, token, banks) }
    ) {
        work("Loading linked bank accounts") {
            val accounts = JSONArray(request("GET", "$base/bank/${bank.getInt("id")}", null, token).body)
            ui {
                if (accounts.length() == 1) {
                    open(back) { showCrn(name, boid, pass, token, bank, accounts.getJSONObject(0)) }
                } else {
                    open(back) { showAccounts(name, boid, pass, token, bank, accounts) }
                }
            }
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
        text("Choose the account for IPO payment")
        if (accounts.length() == 0) text("No bank accounts found")
        for (i in 0 until accounts.length()) {
            val account = accounts.getJSONObject(i)
            row(account.optString("accountNumber"), account.optString("accountTypeName")) {
                open({ showAccounts(name, boid, pass, token, bank, accounts) }) { showCrn(name, boid, pass, token, bank, account) }
            }
        }
        msg("")
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
        field("Bank", bank.optString("name"))
        field("Account", account.optString("accountNumber"))
        val crn = input("CRN number")
        val pin = input("Transaction PIN", true)
        button("Save account") {
            val value = crn.text.toString().trim()
            val p = pin.text.toString().trim()
            if (value.isBlank() || p.isBlank()) {
                error("Enter CRN and transaction PIN")
            } else {
                save(name.trim(), boid.trim(), pass.trim(), value, p, bank, account)
                backStack.clear()
                backStack.add { showHome() }
                showSavedAccounts()
            }
        }
        msg("")
    }

    private fun save(
        name: String,
        boid: String,
        pass: String,
        crn: String,
        pin: String,
        bank: JSONObject,
        account: JSONObject
    ) {
        val list = saved()
        val n = name.trim()
        val b = boid.trim()
        val p = pass.trim()
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
            error("Add an account before refreshing IPO")
            return
        }
        if (loadingIpo) {
            msg("IPO refresh already running")
            return
        }
        loadingIpo = true
        prefs.edit().remove("issues").apply()
        showHome()
        Thread refresh@{
            var err = ""
            for (i in 0 until list.length()) {
                try {
                    val issues = loadIssuesWithAccount(list, i)
                    ui {
                        loadingIpo = false
                        showHome(issues)
                    }
                    return@refresh
                } catch (e: Exception) {
                    err = friendly(e)
                    if (isNetwork(e)) break
                }
            }
            ui {
                loadingIpo = false
                showHome(ipoError = err.ifBlank { "Unable to load IPO" })
            }
        }.start()
    }

    private fun loadIssuesWithAccount(list: JSONArray, index: Int): JSONArray {
        return fetchIssues(list.getJSONObject(index))
    }

    private fun fetchIssues(a: JSONObject): JSONArray {
        try {
            return fetchIssuesWithToken(token(a))
        } catch (e: Exception) {
            if (isNetwork(e)) throw e
            forget(a)
        }
        return fetchIssuesWithToken(login(a, fresh = true))
    }

    private fun fetchIssuesWithToken(token: String): JSONArray {
        val issues = JSONObject(request2("POST", "$base/companyShare/applicableIssue/", issuePayload(), token).body)
            .optJSONArray("object") ?: JSONArray()
        prefs.edit().putString("issues", issues.toString()).apply()
        return issues
    }

    private fun showApply(issueText: String) {
        val list = saved()
        if (list.length() == 0) {
            error("Add an account first")
            return
        }
        page()
        val issue = JSONObject(issueText)
        val scrip = issue.optString("scrip")
        val id = issue.getInt("companyShareId")
        title("Apply $scrip")
        text(clean(issue.optString("companyName")))
        val header = selectionSection("Accounts", "Checking saved accounts")
        header.mark.visibility = View.GONE
        val screen = root
        val selected = ArrayList<Boolean>()
        val cards = ArrayList<LinearLayout>()
        val notes = ArrayList<TextView>()
        val rows = ArrayList<JSONObject>()
        var review: Button? = null
        var done = 0
        var firstError = ""
        header.mark.setOnClickListener {
            toggleSelection(selected, cards, notes, false)
            syncSelectionHeader(header, selected, done, list.length())
        }
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val preview = JSONObject()
                .put("index", i)
                .put("name", a.optString("name", a.optString("demat")))
                .put("username", a.optString("username", a.optString("demat").takeLast(8)))
                .put("message", "Checking")
            val card = applyAccountCard(preview, false)
            val note = card.getChildAt(1) as TextView
            Thread {
                val row = checkApplyRow(id, a, i)
                ui {
                    if (root !== screen) return@ui
                    updateApplyCard(card, row)
                    if (row.optBoolean("ok")) {
                        val cardIndex = cards.size
                        selected.add(true)
                        cards.add(card)
                        notes.add(note)
                        rows.add(row)
                        card.isClickable = true
                        card.setOnClickListener {
                            selected[cardIndex] = !selected[cardIndex]
                            syncApplyCard(cards[cardIndex], notes[cardIndex], selected[cardIndex])
                            syncSelectionHeader(header, selected, done, list.length())
                        }
                        syncApplyCard(card, note, true)
                        header.mark.visibility = View.VISIBLE
                        review?.visibility = View.VISIBLE
                    } else {
                        card.isClickable = false
                        val message = row.optString("message")
                        if (firstError.isBlank() && message.contains("network", true)) firstError = message
                        syncApplyStatus(card, note, message)
                    }
                    done++
                    syncSelectionHeader(header, selected, done, list.length())
                    if (done == list.length()) {
                        if (rows.isEmpty()) {
                            if (firstError.isBlank()) msg("") else error(firstError)
                        } else {
                            msg("")
                        }
                    }
                }
            }.start()
        }
        review = Button(this)
        review.text = "Apply selected accounts"
        styleButton(review)
        review.setOnClickListener {
            val picked = JSONArray()
            val snapshot = JSONArray()
            for (i in 0 until rows.size) {
                snapshot.put(rows[i])
                if (selected[i]) picked.put(rows[i])
            }
            if (picked.length() == 0) {
                error("Select at least one account")
            } else {
                open({ showApplyList(issueText, snapshot) }) { confirmApply(issueText, picked) }
            }
        }
        add(review, bottom = 8)
        review.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        msg("")
    }

    private fun checkApplyRow(id: Int, a: JSONObject, index: Int): JSONObject {
        val row = JSONObject()
            .put("index", index)
            .put("name", a.optString("name", a.optString("demat")))
            .put("username", a.optString("username", a.optString("demat").takeLast(8)))
        try {
            fillApplyRow(row, token(a), id)
        } catch (_: Exception) {
            try {
                forget(a)
                fillApplyRow(row, login(a, fresh = true), id)
            } catch (e2: Exception) {
                row.put("ok", false)
                row.put("message", friendly(e2))
            }
        }
        return row
    }

    private fun fillApplyRow(row: JSONObject, token: String, id: Int) {
        val detail = JSONObject(request("GET", "$base/ownDetail/", null, token).body)
        val demat = detail.getString("demat")
        row.put("demat", demat)
        row.put("boid", detail.optString("boid"))
        row.put("username", detail.optString("username").ifBlank { demat.takeLast(8) })
        row.put("token", token)
        val seen = issueFor(token, id)
        if (isAlreadyAppliedAction(seen.optString("action"))) {
            row.put("ok", false)
            row.put("message", "Already applied")
        } else {
            val check = JSONObject(request("GET", "$base/applicantForm/customerType/$id/$demat", null, token).body)
            val m = check.optString("message")
            row.put("ok", m.contains("can apply", true))
            row.put("message", if (m.contains("can apply", true)) "Ready to apply" else m.ifBlank { "Cannot apply" })
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

    private fun isAlreadyAppliedAction(action: String): Boolean {
        return action.equals("inProcess", true) || action.equals("edit", true)
    }

    private fun showApplyList(issueText: String, rows: JSONArray) {
        page()
        val issue = JSONObject(issueText)
        title("Apply ${issue.optString("scrip")}")
        text(clean(issue.optString("companyName")))
        val header = selectionSection("Accounts", "${rows.length()} checked")
        val selected = ArrayList<Boolean>()
        val cards = ArrayList<LinearLayout>()
        val notes = ArrayList<TextView>()
        val eligible = ArrayList<Int>()
        var blocked = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optBoolean("ok")) {
                val card = applyAccountCard(row, true)
                val note = card.getChildAt(1) as TextView
                selected.add(true)
                cards.add(card)
                notes.add(note)
                val cardIndex = cards.size - 1
                card.setOnClickListener {
                    selected[cardIndex] = !selected[cardIndex]
                    syncApplyCard(cards[cardIndex], notes[cardIndex], selected[cardIndex])
                    syncSelectionHeader(header, selected, rows.length(), rows.length())
                }
                eligible.add(i)
            } else {
                blocked++
                applyAccountCard(row, false)
            }
        }
        if (cards.isNotEmpty()) {
            header.mark.setOnClickListener {
                toggleSelection(selected, cards, notes, false)
                syncSelectionHeader(header, selected, rows.length(), rows.length())
            }
            syncSelectionHeader(header, selected, rows.length(), rows.length())
            button("Apply selected accounts") {
                val picked = JSONArray()
                for (i in 0 until eligible.size) if (selected[i]) picked.put(rows.getJSONObject(eligible[i]))
                if (picked.length() == 0) error("Select at least one account") else open({ showApplyList(issueText, rows) }) { confirmApply(issueText, picked) }
            }
        } else {
            header.mark.visibility = View.GONE
            empty(if (blocked == 0) "No saved accounts found" else "All saved accounts are already applied or cannot apply")
        }
        msg("")
    }

    private fun confirmApply(issueText: String, rows: JSONArray) {
        page()
        val issue = JSONObject(issueText)
        title("Review application")
        reviewIssueCard(issue, rows.length())
        section("Selected accounts", "${rows.length()} selected")
        val kittaCards = ArrayList<KittaControls>()
        var openKittaIndex = -1
        fun setKittaOpen(index: Int) {
            openKittaIndex = if (openKittaIndex == index) -1 else index
            for (i in 0 until kittaCards.size) syncKittaCard(kittaCards[i], i == openKittaIndex)
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val startKitta = row.optInt("appliedKitta", defaultKitta(issue))
            row.put("appliedKitta", normalizedKitta(startKitta))
            val card = accountKittaCard(row)
            val cardIndex = kittaCards.size
            card.card.setOnClickListener { setKittaOpen(cardIndex) }
            kittaCards.add(card)
        }
        button("Submit application") { applyIssue(issueText, rows) }
        msg("")
    }

    private fun applyIssue(issueText: String, rows: JSONArray) {
        work("Applying for selected accounts") {
            val issue = JSONObject(issueText)
            val id = issue.getInt("companyShareId")
            val scrip = issue.optString("scrip")
            val results = JSONArray()
            val list = saved()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val a = list.getJSONObject(row.getInt("index"))
                val name = a.optString("name", a.optString("boid"))
                try {
                    var token = row.optString("token").ifBlank { token(a) }
                    var active = try {
                        JSONObject(request("GET", "$base/active/$id", null, token).body)
                    } catch (e: Exception) {
                        if (isNetwork(e)) throw e
                        forget(a)
                        token = login(a, fresh = true)
                        JSONObject(request("GET", "$base/active/$id", null, token).body)
                    }
                    val payload = JSONObject()
                        .put("demat", row.getString("demat"))
                        .put("boid", row.getString("boid"))
                        .put("accountNumber", a.getString("accountNumber"))
                        .put("customerId", a.getInt("customerId"))
                        .put("accountBranchId", a.getInt("accountBranchId"))
                        .put("accountTypeId", a.getInt("accountTypeId"))
                        .put("appliedKitta", row.optInt("appliedKitta", defaultKitta(active)).toString())
                        .put("crnNumber", a.getString("crn"))
                        .put("transactionPIN", a.getString("pin"))
                        .put("companyShareId", id.toString())
                        .put("bankId", a.getInt("bankId").toString())
                        .toString()
                    val res = try {
                        JSONObject(request("POST", "$base/applicantForm/share/apply", payload, token).body)
                    } catch (e: Exception) {
                        if (isNetwork(e) || !isAuthProblem(e)) throw e
                        forget(a)
                        token = login(a, fresh = true)
                        JSONObject(request("POST", "$base/applicantForm/share/apply", payload, token).body)
                    }
                    results.put(
                        JSONObject()
                            .put("name", name)
                            .put("username", a.optString("username", a.optString("demat").takeLast(8)))
                            .put("ok", true)
                            .put("message", res.optString("message", "Applied successfully"))
                    )
                } catch (e: Exception) {
                    results.put(
                        JSONObject()
                            .put("name", name)
                            .put("username", a.optString("username", a.optString("demat").takeLast(8)))
                            .put("ok", false)
                            .put("message", friendly(e))
                    )
                }
            }
            ui {
                backStack.add { showHome() }
                page()
                title("Application result")
                resultSummary(issue, results)
                for (i in 0 until results.length()) {
                    resultCard(results.getJSONObject(i))
                }
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

    private fun key(a: JSONObject) = a.optString("demat").ifBlank { a.optString("boid").ifBlank { a.optString("username") } }

    private fun token(a: JSONObject): String {
        val k = key(a)
        return tokens[k] ?: login(a, fresh = true)
    }

    private fun remember(a: JSONObject, token: String) {
        tokens[key(a)] = token
    }

    private fun remember(boid: String, token: String) {
        tokens[boid] = token
    }

    private fun forget(a: JSONObject) {
        tokens.remove(key(a))
    }

    private fun login(a: JSONObject, fresh: Boolean = false): String {
        if (!fresh) tokens[key(a)]?.let { return it }
        val body = JSONObject()
            .put("clientId", a.optString("clientId").ifBlank { clientId(a.getString("demat")) })
            .put("username", a.optString("username").ifBlank { a.getString("demat").takeLast(8) })
            .put("password", a.getString("password"))
            .toString()
        val r = request("POST", "$base/auth/", body, null)
        val t = r.token ?: JSONObject(r.body.ifBlank { "{}" }).optString("token")
            .ifBlank { JSONObject(r.body.ifBlank { "{}" }).optString("authorization") }
        if (t.isBlank()) throw RuntimeException("Login failed. Check BOID and password")
        remember(a, t)
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
        remember(boid, t)
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
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
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
        root.setPadding(dp(16), dp(30), dp(16), dp(28))
        root.setBackgroundColor(Color.rgb(246, 248, 250))
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(246, 248, 250))
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)
    }

    private fun appTitle(s: String) {
        val v = TextView(this)
        v.text = s
        v.textSize = 30f
        v.setTextColor(Color.rgb(18, 20, 24))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        add(v, bottom = 20)
    }

    private fun section(s: String, note: String = ""): TextView? {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val h = TextView(this)
        h.text = s
        h.textSize = 21f
        h.setTextColor(Color.rgb(20, 22, 26))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var noteView: TextView? = null
        if (note.isNotBlank()) {
            val n = TextView(this)
            n.text = note
            n.textSize = 13f
            n.setTextColor(Color.rgb(99, 105, 113))
            n.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            box.addView(n)
            noteView = n
        }
        add(box, top = 14, bottom = 9)
        return noteView
    }

    private fun selectionSection(s: String, note: String): SelectionHeader {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val h = TextView(this)
        h.text = s
        h.textSize = 21f
        h.setTextColor(Color.rgb(20, 22, 26))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val n = TextView(this)
        n.text = note
        n.textSize = 13f
        n.setTextColor(Color.rgb(99, 105, 113))
        n.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        box.addView(n, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, dp(10), 0)
        })
        val mark = TextView(this)
        mark.text = ""
        mark.textSize = 14f
        mark.gravity = Gravity.CENTER
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        mark.isClickable = true
        box.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            setMargins(0, 0, dp(15), 0)
        })
        val header = SelectionHeader(n, mark)
        syncSelectionHeader(header, ArrayList<Boolean>(), 0, 0)
        add(box, top = 14, bottom = 9)
        return header
    }

    private fun syncSelectionHeader(header: SelectionHeader, selected: ArrayList<Boolean>, checked: Int, total: Int) {
        val selectedCount = selected.count { it }
        header.note.text = when {
            total > 0 && checked < total -> "$checked of $total checked"
            total > 0 -> "$selectedCount selected"
            else -> "Checking saved accounts"
        }
        val all = selected.isNotEmpty() && selected.all { it }
        header.mark.text = if (all) "\u2713" else ""
        header.mark.setTextColor(if (all) Color.WHITE else Color.rgb(20, 94, 72))
        header.mark.background = if (all) {
            bg(Color.rgb(20, 94, 72), Color.rgb(20, 94, 72), 7)
        } else {
            bg(Color.TRANSPARENT, Color.rgb(20, 94, 72), 7)
        }
    }

    private fun toggleSelection(
        selected: ArrayList<Boolean>,
        cards: ArrayList<LinearLayout>,
        notes: ArrayList<TextView>,
        deleteMode: Boolean
    ) {
        val next = !(selected.isNotEmpty() && selected.all { it })
        for (i in 0 until selected.size) {
            selected[i] = next
            if (deleteMode) syncDeleteCard(cards[i], notes[i], next) else syncApplyCard(cards[i], notes[i], next)
        }
    }

    private fun sectionAction(s: String, note: String = "", showAction: Boolean, click: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val h = TextView(this)
        h.text = s
        h.textSize = 21f
        h.setTextColor(Color.rgb(20, 22, 26))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (note.isNotBlank()) {
            val n = TextView(this)
            n.text = note
            n.textSize = 13f
            n.setTextColor(Color.rgb(99, 105, 113))
            n.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            box.addView(n, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        if (showAction) {
            refreshButton(R.drawable.ic_refresh_1, click).also {
                box.addView(it, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    setMargins(0, 0, dp(8), 0)
                })
            }
        } else {
            val spacer = View(this)
            box.addView(spacer, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        add(box, top = 14, bottom = 9)
    }

    private fun refreshButton(res: Int, click: () -> Unit): ImageView {
        val b = ImageView(this)
        b.setImageResource(res)
        b.setColorFilter(Color.rgb(20, 94, 72))
        b.setPadding(dp(8), dp(8), dp(8), dp(8))
        b.background = press(Color.rgb(246, 250, 248), Color.rgb(190, 207, 201), 12, Color.rgb(231, 242, 237))
        b.isClickable = true
        b.setOnClickListener { click() }
        return b
    }

    private fun title(s: String) {
        val v = TextView(this)
        v.text = s
        v.textSize = 25f
        v.setTextColor(Color.rgb(22, 24, 28))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        add(v, top = 4, bottom = 12)
    }

    private fun text(s: String) {
        val v = TextView(this)
        v.text = s
        v.textSize = 15f
        v.setTextColor(Color.rgb(55, 58, 64))
        v.setLineSpacing(dp(3).toFloat(), 1f)
        add(v, bottom = 10)
    }

    private fun block(head: String, body: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(15), dp(13), dp(15), dp(13))
        box.background = bg(Color.WHITE, Color.rgb(211, 218, 226), 12)
        val h = TextView(this)
        h.text = head
        h.textSize = 16f
        h.setTextColor(Color.rgb(20, 22, 26))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h)
        val b = TextView(this)
        b.text = body
        b.textSize = 14f
        b.setTextColor(Color.rgb(75, 79, 86))
        b.setLineSpacing(dp(2).toFloat(), 1f)
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, 0)
        })
        add(box, bottom = 8)
    }

    private fun reviewIssueCard(issue: JSONObject, selectedCount: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(15), dp(14), dp(15), dp(14))
        box.background = bg(Color.WHITE, Color.rgb(190, 207, 201), 12)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val scrip = TextView(this)
        scrip.text = issue.optString("scrip", "IPO")
        scrip.textSize = 22f
        scrip.setTextColor(Color.rgb(18, 77, 60))
        scrip.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(scrip)
        val company = TextView(this)
        company.text = clean(issue.optString("companyName"))
        company.textSize = 14f
        company.setTextColor(Color.rgb(55, 58, 64))
        company.setLineSpacing(dp(2).toFloat(), 1f)
        left.addView(company, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), dp(8), 0)
        })
        top.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = TextView(this)
        count.text = "$selectedCount selected"
        count.textSize = 12f
        count.setTextColor(Color.WHITE)
        count.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        count.setPadding(dp(9), dp(4), dp(9), dp(4))
        count.background = bg(Color.rgb(20, 94, 72), Color.rgb(20, 94, 72), 12)
        top.addView(count)
        box.addView(top)
        add(box, bottom = 10)
    }

    private fun accountSummaryCard(nameText: String, usernameText: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(15), dp(12), dp(15), dp(12))
        box.background = bg(Color.WHITE, Color.rgb(211, 218, 226), 12)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = nameText
        name.textSize = 16f
        name.setTextColor(Color.rgb(20, 22, 26))
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(name)
        val user = TextView(this)
        user.text = usernameText
        user.textSize = 14f
        user.setTextColor(Color.rgb(88, 92, 99))
        left.addView(user, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(box, bottom = 8)
    }

    private fun accountKittaCard(row: JSONObject): KittaControls {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(15), dp(12), dp(15), dp(12))
        box.background = press(Color.WHITE, Color.rgb(211, 218, 226), 12)
        box.isClickable = true

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = row.optString("name")
        name.textSize = 16f
        name.setTextColor(Color.rgb(20, 22, 26))
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(name)
        val detail = TextView(this)
        detail.text = accountKittaDetail(row)
        detail.textSize = 14f
        detail.setTextColor(Color.rgb(88, 92, 99))
        left.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        top.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val hint = TextView(this)
        hint.text = "Change"
        hint.textSize = 12f
        hint.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        hint.setTextColor(Color.rgb(20, 94, 72))
        hint.setPadding(dp(9), dp(4), dp(9), dp(4))
        hint.background = bg(Color.rgb(236, 248, 243), Color.rgb(190, 207, 201), 10)
        top.addView(hint)
        box.addView(top)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.visibility = View.GONE
        val minus = stepButton("-")
        val value = TextView(this)
        value.text = "${row.optInt("appliedKitta", 10)}"
        value.textSize = 22f
        value.gravity = Gravity.CENTER
        value.setTextColor(Color.rgb(18, 77, 60))
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        val plus = stepButton("+")
        controls.addView(minus, LinearLayout.LayoutParams(dp(48), dp(44)))
        controls.addView(value, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(10), 0, dp(10), 0)
        })
        controls.addView(plus, LinearLayout.LayoutParams(dp(48), dp(44)))
        box.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(12), 0, 0)
        })

        fun update(delta: Int) {
            val current = row.optInt("appliedKitta", 10)
            val next = normalizedKitta(current + delta)
            row.put("appliedKitta", next)
            value.text = next.toString()
            detail.text = accountKittaDetail(row)
        }
        minus.setOnClickListener { update(-10) }
        plus.setOnClickListener { update(10) }

        add(box, bottom = 8)
        return KittaControls(box, detail, controls, value)
    }

    private fun syncKittaCard(card: KittaControls, open: Boolean) {
        card.controls.visibility = if (open) View.VISIBLE else View.GONE
        card.card.background = if (open) {
            bg(Color.rgb(236, 248, 243), Color.rgb(20, 94, 72), 12)
        } else {
            press(Color.WHITE, Color.rgb(211, 218, 226), 12)
        }
    }

    private fun accountKittaDetail(row: JSONObject): String {
        return "${row.optString("username")} - ${row.optInt("appliedKitta", 10)} kitta"
    }

    private fun stepButton(label: String): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 22f
        b.isAllCaps = false
        b.minHeight = dp(44)
        b.setPadding(0, 0, 0, dp(2))
        b.setTextColor(Color.WHITE)
        b.background = press(Color.rgb(20, 94, 72), Color.rgb(17, 78, 61), 12, Color.rgb(17, 78, 61))
        return b
    }

    private fun defaultKitta(issue: JSONObject): Int {
        return normalizedKitta(issue.optInt("minUnit", 10))
    }

    private fun normalizedKitta(value: Int): Int {
        val safe = value.coerceAtLeast(10)
        return ((safe + 9) / 10) * 10
    }

    private fun resultSummary(issue: JSONObject, results: JSONArray) {
        var ok = 0
        for (i in 0 until results.length()) if (results.getJSONObject(i).optBoolean("ok")) ok++
        val failed = results.length() - ok
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(15), dp(14), dp(15), dp(14))
        box.background = bg(Color.WHITE, Color.rgb(190, 207, 201), 12)
        val statusText = when {
            failed == 0 -> "All applications completed"
            ok == 0 -> "No applications completed"
            else -> "$ok completed, $failed need attention"
        }
        val statusColor = if (failed == 0) Color.rgb(20, 94, 72) else Color.rgb(116, 78, 42)
        val h = TextView(this)
        h.text = statusText
        h.textSize = 18f
        h.setTextColor(statusColor)
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h)
        val b = TextView(this)
        b.text = "${issue.optString("scrip", "IPO")} - ${clean(issue.optString("companyName"))}"
        b.textSize = 14f
        b.setTextColor(Color.rgb(75, 79, 86))
        b.setLineSpacing(dp(2).toFloat(), 1f)
        box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, 0)
        })
        add(box, bottom = 10)
    }

    private fun resultCard(result: JSONObject) {
        val ok = result.optBoolean("ok")
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(15), dp(13), dp(15), dp(13))
        box.background = if (ok) {
            bg(Color.rgb(247, 252, 249), Color.rgb(181, 218, 196), 12)
        } else {
            bg(Color.rgb(255, 249, 240), Color.rgb(231, 198, 151), 12)
        }
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = result.optString("name")
        name.textSize = 16f
        name.setTextColor(Color.rgb(20, 22, 26))
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(name)
        val detail = TextView(this)
        detail.text = if (ok) {
            result.optString("username")
        } else {
            clean(result.optString("message").ifBlank { "Failed" })
        }
        detail.textSize = 13f
        detail.setTextColor(if (ok) Color.rgb(88, 92, 99) else Color.rgb(116, 78, 42))
        detail.setLineSpacing(dp(2).toFloat(), 1f)
        left.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val mark = TextView(this)
        mark.text = if (ok) "\u2713" else "!"
        mark.textSize = if (ok) 22f else 20f
        mark.gravity = Gravity.CENTER
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        mark.setTextColor(if (ok) Color.rgb(20, 94, 72) else Color.rgb(154, 38, 43))
        mark.background = null
        box.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)))
        add(box, bottom = 8)
    }

    private fun ipoCard(issue: JSONObject) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(15), dp(15), dp(15), dp(15))
        box.background = bg(Color.WHITE, Color.rgb(190, 207, 201), 12)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val names = LinearLayout(this)
        names.orientation = LinearLayout.VERTICAL
        val scrip = TextView(this)
        scrip.text = issue.optString("scrip", "IPO")
        scrip.textSize = 24f
        scrip.setTextColor(Color.rgb(18, 77, 60))
        scrip.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        names.addView(scrip)
        val company = TextView(this)
        company.text = clean(issue.optString("companyName"))
        company.textSize = 15f
        company.setTextColor(Color.rgb(38, 42, 48))
        company.setLineSpacing(dp(2).toFloat(), 1f)
        names.addView(company, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), dp(8), 0)
        })
        top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip(issue.optString("shareTypeName", "IPO"), true))
        box.addView(top)

        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        chips.addView(chip(issue.optString("shareGroupName", "Shares"), false))
        chips.addView(chip(issue.optString("subGroup", "Applicant group"), false))
        box.addView(chips)

        val dates = LinearLayout(this)
        dates.orientation = LinearLayout.HORIZONTAL
        dates.setPadding(0, dp(2), 0, 0)
        dates.addView(infoCell("Opens", shortDate(issue.optString("issueOpenDate"))), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gap = View(this)
        dates.addView(gap, LinearLayout.LayoutParams(dp(12), 1))
        dates.addView(infoCell("Closes", shortDate(issue.optString("issueCloseDate"))), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(dates)
        box.addView(space(9))

        val apply = Button(this)
        apply.text = "Apply ${issue.optString("scrip", "IPO")}"
        styleButton(apply)
        apply.setOnClickListener { open { showApply(issue.toString()) } }
        box.addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(box, bottom = 13)
    }

    private fun chip(text: String, strong: Boolean): TextView {
        val v = TextView(this)
        v.text = text.ifBlank { "-" }
        v.textSize = if (strong) 13f else 12f
        v.setTextColor(if (strong) Color.WHITE else Color.rgb(49, 57, 65))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        v.setPadding(dp(9), dp(4), dp(9), dp(4))
        v.background = if (strong) bg(Color.rgb(20, 94, 72), Color.rgb(20, 94, 72), 12)
        else bg(Color.rgb(240, 243, 246), Color.rgb(220, 225, 231), 12)
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, dp(9), dp(7), dp(8))
        v.layoutParams = p
        return v
    }

    private fun infoCell(label: String, value: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(11), dp(9), dp(11), dp(9))
        box.background = bg(Color.rgb(249, 251, 250), Color.rgb(225, 232, 229), 10)
        val l = TextView(this)
        l.text = label
        l.textSize = 11f
        l.setTextColor(Color.rgb(100, 107, 114))
        box.addView(l)
        val v = TextView(this)
        v.text = value.ifBlank { "-" }
        v.textSize = 13f
        v.setTextColor(Color.rgb(31, 36, 42))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        v.setLineSpacing(dp(1).toFloat(), 1f)
        box.addView(v)
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, 0, 0, dp(8))
        box.layoutParams = p
        return box
    }

    private fun space(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(h))
        return v
    }

    private fun clean(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun shortDate(s: String): String {
        return try {
            val d = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US).parse(s)
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(d!!)
        } catch (_: Exception) {
            clean(s)
        }
    }

    private fun empty(s: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.background = bg(Color.rgb(250, 251, 253), Color.rgb(224, 228, 234), 12)
        val dot = TextView(this)
        dot.text = "i"
        dot.gravity = Gravity.CENTER
        dot.textSize = 12f
        dot.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        dot.setTextColor(Color.rgb(86, 94, 104))
        dot.background = bg(Color.rgb(235, 239, 244), Color.rgb(216, 222, 230), 10)
        box.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))
        val v = TextView(this)
        v.text = s
        v.textSize = 15f
        v.setTextColor(Color.rgb(83, 90, 99))
        box.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        add(box, bottom = 8)
    }

    private fun loadingBlock(head: String, body: String) {
        issueStateBlock(head, body, false)
    }

    private fun issueStateBlock(head: String, body: String, bad: Boolean) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(14), dp(13), dp(14), dp(13))
        box.background = bg(
            if (bad) Color.rgb(255, 248, 248) else Color.WHITE,
            if (bad) Color.rgb(232, 190, 194) else Color.rgb(205, 219, 214),
            12
        )
        if (bad) {
            val mark = TextView(this)
            mark.text = "!"
            mark.gravity = Gravity.CENTER
            mark.textSize = 16f
            mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            mark.setTextColor(Color.rgb(125, 35, 40))
            mark.background = bg(Color.rgb(255, 238, 239), Color.rgb(232, 190, 194), 17)
            box.addView(mark, LinearLayout.LayoutParams(dp(34), dp(34)))
        } else {
            val spin = ProgressBar(this)
            box.addView(spin, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
        val copy = LinearLayout(this)
        copy.orientation = LinearLayout.VERTICAL
        val h = TextView(this)
        h.text = head
        h.textSize = 16f
        h.setTextColor(if (bad) Color.rgb(125, 35, 40) else Color.rgb(20, 94, 72))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        copy.addView(h)
        val b = TextView(this)
        b.text = body
        b.textSize = 14f
        b.setTextColor(if (bad) Color.rgb(125, 55, 58) else Color.rgb(88, 96, 102))
        copy.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(2), 0, 0)
        })
        box.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(12), 0, 0, 0)
        })
        add(box, bottom = 8)
    }

    private fun field(label: String, value: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(11), dp(14), dp(11))
        box.background = bg(Color.WHITE, Color.rgb(218, 224, 231), 12)
        val l = TextView(this)
        l.text = label
        l.textSize = 12f
        l.setTextColor(Color.rgb(105, 110, 118))
        box.addView(l)
        val v = TextView(this)
        v.text = value.ifBlank { "-" }
        v.textSize = 16f
        v.setTextColor(Color.rgb(25, 28, 33))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        add(box, bottom = 8)
    }

    private fun statusRow(head: String, body: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        box.background = bg(Color.rgb(249, 250, 252), Color.rgb(218, 222, 228), 12)
        val h = TextView(this)
        h.text = head
        h.textSize = 16f
        h.setTextColor(Color.rgb(76, 82, 90))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(h)
        val b = TextView(this)
        b.text = body.ifBlank { "Not available" }
        b.textSize = 14f
        b.setTextColor(Color.rgb(116, 78, 42))
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        box.addView(b)
        add(box, bottom = 8)
    }

    private fun applyAccountCard(row: JSONObject, selectable: Boolean): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(15), dp(13), dp(15), dp(13))
        val username = row.optString("username").ifBlank {
            row.optString("boid").ifBlank { row.optString("demat").takeLast(8) }
        }
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = row.optString("name")
        name.textSize = 16f
        name.setTextColor(Color.rgb(20, 22, 26))
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(name)
        val user = TextView(this)
        user.text = username
        user.textSize = 14f
        user.setTextColor(Color.rgb(88, 92, 99))
        left.addView(user, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        card.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val note = TextView(this)
        note.textSize = if (selectable) 14f else 13f
        note.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        note.gravity = if (selectable) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.RIGHT
        if (selectable) {
            card.addView(note, LinearLayout.LayoutParams(dp(26), dp(26)))
        } else {
            note.setPadding(dp(10), 0, 0, 0)
            note.maxWidth = dp(158)
            note.maxLines = 2
            card.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (selectable) {
            card.isClickable = true
            syncApplyCard(card, note, true)
        } else {
            syncApplyStatus(card, note, row.optString("message"))
        }
        add(card, bottom = 8)
        return card
    }

    private fun selectAccountCard(a: JSONObject, selected: Boolean): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(15), dp(13), dp(15), dp(13))
        card.isClickable = true
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = a.optString("name", a.optString("demat"))
        name.textSize = 16f
        name.setTextColor(Color.rgb(20, 22, 26))
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(name)
        val user = TextView(this)
        user.text = a.optString("username", a.optString("demat").takeLast(8))
        user.textSize = 14f
        user.setTextColor(Color.rgb(88, 92, 99))
        left.addView(user, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        card.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val note = TextView(this)
        note.textSize = 14f
        note.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        note.gravity = Gravity.CENTER
        card.addView(note, LinearLayout.LayoutParams(dp(26), dp(26)))
        syncDeleteCard(card, note, selected)
        add(card, bottom = 8)
        return card
    }

    private fun syncDeleteCard(card: LinearLayout, note: TextView, selected: Boolean) {
        if (selected) {
            card.background = bg(Color.rgb(255, 246, 246), Color.rgb(154, 38, 43), 12)
            note.text = "\u2713"
            note.setTextColor(Color.WHITE)
            note.background = bg(Color.rgb(154, 38, 43), Color.rgb(154, 38, 43), 7)
        } else {
            card.background = press(Color.WHITE, Color.rgb(199, 207, 217), 12)
            note.text = ""
            note.setTextColor(Color.rgb(88, 92, 99))
            note.background = bg(Color.TRANSPARENT, Color.rgb(164, 174, 186), 7)
        }
    }

    private fun syncApplyCard(card: LinearLayout, note: TextView, selected: Boolean) {
        note.textSize = 14f
        note.gravity = Gravity.CENTER
        note.setPadding(0, 0, 0, 0)
        note.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        if (selected) {
            card.background = press(Color.rgb(236, 248, 243), Color.rgb(20, 94, 72), 12)
            note.text = "\u2713"
            note.setTextColor(Color.WHITE)
            note.background = bg(Color.rgb(20, 94, 72), Color.rgb(20, 94, 72), 7)
        } else {
            card.background = press(Color.WHITE, Color.rgb(199, 207, 217), 12)
            note.text = ""
            note.setTextColor(Color.rgb(88, 92, 99))
            note.background = bg(Color.TRANSPARENT, Color.rgb(164, 174, 186), 7)
        }
    }

    private fun syncApplyStatus(card: LinearLayout, note: TextView, message: String) {
        note.textSize = 13f
        note.gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        note.setPadding(dp(10), 0, 0, 0)
        note.maxWidth = dp(158)
        note.maxLines = 2
        note.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        when {
            message.equals("Already applied", true) -> {
                card.background = bg(Color.rgb(247, 252, 249), Color.rgb(181, 218, 196), 12)
                note.text = "\u2713 Already applied"
                note.setTextColor(Color.rgb(20, 112, 69))
                note.background = null
            }
            message.equals("Checking", true) -> {
                card.background = bg(Color.WHITE, Color.rgb(199, 207, 217), 12)
                note.text = "Checking..."
                note.setTextColor(Color.rgb(88, 92, 99))
                note.background = null
            }
            else -> {
                card.background = bg(Color.rgb(255, 249, 240), Color.rgb(231, 198, 151), 12)
                note.text = when {
                    message.contains("network", true) -> "Network error"
                    message.length > 26 -> "Not available"
                    else -> message.ifBlank { "Not available" }
                }
                note.setTextColor(Color.rgb(116, 78, 42))
                note.background = null
            }
        }
    }

    private fun updateApplyCard(card: LinearLayout, row: JSONObject) {
        val left = card.getChildAt(0) as LinearLayout
        val name = left.getChildAt(0) as TextView
        val user = left.getChildAt(1) as TextView
        name.text = row.optString("name")
        user.text = row.optString("username").ifBlank {
            row.optString("boid").ifBlank { row.optString("demat").takeLast(8) }
        }
    }

    private fun row(head: String, body: String, click: () -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(15), dp(13), dp(12), dp(13))
        box.background = press(Color.WHITE, Color.rgb(199, 207, 217), 12)
        box.isClickable = true
        box.setOnClickListener { click() }
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val h = TextView(this)
        h.text = head
        h.textSize = 16f
        h.setTextColor(Color.rgb(20, 22, 26))
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(h)
        val b = TextView(this)
        b.text = body
        b.textSize = 14f
        b.setTextColor(Color.rgb(88, 92, 99))
        left.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = TextView(this)
        arrow.text = "\u203a"
        arrow.textSize = 25f
        arrow.setTextColor(Color.rgb(126, 134, 144))
        arrow.gravity = Gravity.CENTER
        box.addView(arrow, LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.MATCH_PARENT))
        add(box, bottom = 8)
    }

    private fun input(hint: String, secret: Boolean = false): EditText {
        val v = EditText(this)
        v.hint = hint
        v.setSingleLine(true)
        v.textSize = 16f
        v.minHeight = dp(52)
        v.setPadding(dp(13), 0, dp(13), 0)
        v.setTextColor(Color.rgb(20, 22, 26))
        v.setHintTextColor(Color.rgb(125, 130, 138))
        v.background = bg(Color.WHITE, Color.rgb(190, 197, 207), 12)
        v.inputType = InputType.TYPE_CLASS_TEXT
        if (secret) {
            v.transformationMethod = PasswordTransformationMethod.getInstance()
            v.setOnClickListener {
                v.transformationMethod = if (v.transformationMethod == null) PasswordTransformationMethod.getInstance() else null
                v.setSelection(v.text.length)
            }
        }
        add(v, bottom = 8)
        return v
    }

    private fun secretField(label: String, value: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(14), dp(11), dp(14), dp(11))
        box.background = press(Color.WHITE, Color.rgb(218, 224, 231), 12)
        box.isClickable = true
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val l = TextView(this)
        l.text = label
        l.textSize = 12f
        l.setTextColor(Color.rgb(105, 110, 118))
        left.addView(l)
        val v = TextView(this)
        fun mask() = "*".repeat(value.length.coerceAtLeast(4))
        v.text = mask()
        v.textSize = 16f
        v.setTextColor(Color.rgb(25, 28, 33))
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        left.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(3), 0, 0)
        })
        box.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val hint = TextView(this)
        hint.text = "Tap"
        hint.textSize = 12f
        hint.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        hint.setTextColor(Color.rgb(92, 100, 110))
        box.addView(hint)
        val toggle = View.OnClickListener {
            val shown = v.text.toString() == value
            v.text = if (shown) mask() else value
        }
        box.setOnClickListener(toggle)
        v.setOnClickListener(toggle)
        add(box, bottom = 8)
    }

    private fun button(s: String, secondary: Boolean = false, danger: Boolean = false, click: () -> Unit) {
        val b = Button(this)
        b.text = s
        styleButton(b, secondary, danger)
        b.setOnClickListener { click() }
        add(b, bottom = 8)
    }

    private fun selectToggle(checks: ArrayList<CheckBox>) {
        val b = Button(this)
        fun active() = checks.filter { it.isEnabled }
        fun allSelected() = active().let { it.isNotEmpty() && it.all { c -> c.isChecked } }
        fun sync() {
            b.text = if (allSelected()) "Clear selection" else "Select all"
        }
        sync()
        styleButton(b, secondary = true)
        b.setOnClickListener {
            val clear = allSelected()
            for (c in active()) c.isChecked = !clear
            sync()
        }
        for (c in checks) c.setOnCheckedChangeListener { _, _ -> sync() }
        add(b, bottom = 8)
    }

    private fun selectToggle(
        selected: ArrayList<Boolean>,
        cards: ArrayList<LinearLayout>,
        notes: ArrayList<TextView>,
        deleteMode: Boolean
    ): Button {
        val b = Button(this)
        fun allSelected() = selected.isNotEmpty() && selected.all { it }
        fun sync() {
            b.text = if (allSelected()) "Clear selection" else "Select all"
        }
        sync()
        styleButton(b, secondary = true)
        b.setOnClickListener {
            val next = !allSelected()
            for (i in 0 until selected.size) {
                selected[i] = next
                if (deleteMode) syncDeleteCard(cards[i], notes[i], next) else syncApplyCard(cards[i], notes[i], next)
            }
            sync()
        }
        add(b, bottom = 8)
        return b
    }

    private fun check(): CheckBox {
        val c = CheckBox(this)
        c.textSize = 15f
        c.setTextColor(Color.rgb(35, 38, 43))
        c.setPadding(dp(8), dp(8), dp(8), dp(8))
        c.background = bg(Color.WHITE, Color.rgb(220, 224, 230), 10)
        add(c, bottom = 8)
        return c
    }

    private fun msg(s: String) {
        showStatus(s, false)
    }

    private fun error(s: String) {
        showStatus(s, true)
    }

    private fun showStatus(s: String, bad: Boolean) {
        if (!::status.isInitialized || status.parent !== root) {
            status = TextView(this)
            status.textSize = 14f
            status.setPadding(dp(13), dp(11), dp(13), dp(11))
            add(status, top = 4, bottom = 8)
        }
        status.text = s
        status.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
        if (bad) {
            status.setTextColor(Color.rgb(125, 35, 40))
            status.background = bg(Color.rgb(255, 246, 246), Color.rgb(232, 190, 194), 12)
        } else {
            status.setTextColor(Color.rgb(80, 86, 94))
            status.background = bg(Color.rgb(249, 250, 252), Color.rgb(224, 228, 234), 12)
        }
    }

    private fun work(doing: String, block: () -> Unit) {
        if (busy) {
            msg("Please wait...")
            return
        }
        busy = true
        msg(doing)
        val spin = ProgressBar(this)
        add(spin, bottom = 8)
        Thread {
            try {
                block()
            } catch (e: Exception) {
                ui {
                    error(friendly(e))
                }
            } finally {
                busy = false
                ui { (spin.parent as? ViewGroup)?.removeView(spin) }
            }
        }.start()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun friendly(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "Network Error"
            is IOException -> "Network Error"
            else -> e.message ?: "Error"
        }
    }

    private fun isNetwork(e: Exception) = e is UnknownHostException || e is IOException

    private fun isAuthProblem(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase(Locale.US)
        return m.contains("token") ||
            m.contains("auth") ||
            m.contains("unauthorized") ||
            m.contains("expired") ||
            m.contains("401") ||
            m.contains("403")
    }

    private fun styleButton(b: Button, secondary: Boolean = false, danger: Boolean = false) {
        b.isAllCaps = false
        b.textSize = 14f
        b.minHeight = dp(48)
        b.setPadding(dp(10), 0, dp(10), 0)
        when {
            danger -> {
                b.setTextColor(Color.WHITE)
                b.background = press(Color.rgb(154, 38, 43), Color.rgb(132, 32, 37), 12, Color.rgb(132, 32, 37))
            }
            secondary -> {
                b.setTextColor(Color.rgb(45, 49, 55))
                b.background = press(Color.rgb(239, 241, 245), Color.rgb(218, 222, 228), 12, Color.rgb(229, 233, 238))
            }
            else -> {
                b.setTextColor(Color.WHITE)
                b.background = press(Color.rgb(20, 94, 72), Color.rgb(17, 78, 61), 12, Color.rgb(17, 78, 61))
            }
        }
    }

    private fun add(v: View, top: Int = 0, bottom: Int = 0) {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.setMargins(0, dp(top), 0, dp(bottom))
        root.addView(v, p)
    }

    private fun press(fill: Int, stroke: Int, radius: Int, pressedFill: Int = Color.rgb(242, 245, 247)): Drawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), bg(pressedFill, stroke, radius))
        s.addState(intArrayOf(), bg(fill, stroke, radius))
        return s
    }

    private fun bg(fill: Int, stroke: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radius).toFloat()
        d.setStroke(dp(1), stroke)
        return d
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private data class Res(val body: String, val token: String?)
}
