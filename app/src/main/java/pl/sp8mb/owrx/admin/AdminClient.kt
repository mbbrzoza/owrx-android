package pl.sp8mb.owrx.admin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Client for the OpenWebRX admin web panel (/settings). There is no JSON API —
 * the panel is plain HTML forms — so forms are parsed generically with jsoup,
 * edited field-by-field in the app and POSTed back unchanged otherwise.
 */
class AdminClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    basicAuthUser: String? = null,
    basicAuthPassword: String? = null,
) {
    data class FormField(
        val name: String,
        val label: String,
        val type: String,        // text, number, checkbox, select, hidden, password
        val value: String,
        val checked: Boolean = false,
        val options: List<Pair<String, String>> = emptyList(), // value to label
    )

    data class FormPage(
        val title: String,
        val action: String,
        val fields: List<FormField>,
        /** sub-page links found next to the form, e.g. profiles of a device */
        val links: List<Pair<String, String>>, // path to label
    )

    private val cookies = HashMap<String, Cookie>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { this@AdminClient.cookies[it.name] = it }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()
        })
        .apply {
            if (!basicAuthUser.isNullOrEmpty()) {
                val cred = okhttp3.Credentials.basic(basicAuthUser, basicAuthPassword ?: "")
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", cred).build())
                }
            }
        }
        .build()

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    /** Logs in to the admin panel; throws with a message on failure. */
    fun login() {
        val body = FormBody.Builder()
            .add("user", username)
            .add("password", password)
            .build()
        val resp = http.newCall(Request.Builder().url(url("/login")).post(body).build()).execute()
        resp.use {
            // success = redirect away from /login with a session cookie
            if (it.code !in 300..399 || cookies.isEmpty()) {
                throw IllegalStateException("Logowanie admina nieudane (HTTP ${it.code})")
            }
        }
    }

    private fun get(path: String): Document {
        val resp = http.newCall(Request.Builder().url(url(path)).build()).execute()
        resp.use {
            if (it.code in 300..399) {
                val loc = it.header("Location") ?: ""
                if (loc.contains("login")) throw IllegalStateException("Sesja wygasła — zaloguj ponownie")
                return get(loc)
            }
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code} dla $path")
            return Jsoup.parse(it.body!!.string(), url(path))
        }
    }

    /** SDR devices listed on /settings/sdr. */
    fun listDevices(): List<Pair<String, String>> {
        val doc = get("/settings/sdr")
        return doc.select("a[href*=/settings/sdr/]")
            .map { it.attr("href").substringAfter(baseUrl.trimEnd('/')) to it.text().trim() }
            .filter { (href, label) -> label.isNotEmpty() && !href.endsWith("/settings/sdr") }
            .distinctBy { it.first }
    }

    /** Parse any settings page into a generic editable form. */
    fun loadForm(path: String): FormPage {
        val doc = get(path)
        val form = doc.selectFirst("form")
            ?: return FormPage(doc.title(), path, emptyList(), pageLinks(doc, path))
        val fields = ArrayList<FormField>()
        for (el in form.select("input, select, textarea")) {
            val name = el.attr("name")
            if (name.isEmpty()) continue
            val label = form.selectFirst("label[for=${el.attr("id")}]")?.text()
                ?: el.closest(".form-group")?.selectFirst("label")?.text()
                ?: name
            when (el.tagName()) {
                "select" -> fields.add(
                    FormField(
                        name = name,
                        label = label,
                        type = "select",
                        value = el.select("option[selected]").firstOrNull()?.attr("value")
                            ?: el.select("option").firstOrNull()?.attr("value") ?: "",
                        options = el.select("option").map { it.attr("value") to it.text() },
                    )
                )
                "textarea" -> fields.add(FormField(name, label, "text", el.text()))
                else -> {
                    val type = el.attr("type").ifEmpty { "text" }
                    if (type == "submit" || type == "button") continue
                    fields.add(
                        FormField(
                            name = name,
                            label = label,
                            type = type,
                            value = el.attr("value"),
                            checked = el.hasAttr("checked"),
                        )
                    )
                }
            }
        }
        val action = form.attr("action").ifEmpty { path }
            .removePrefix(baseUrl.trimEnd('/'))
        return FormPage(doc.title(), action, fields, pageLinks(doc, path))
    }

    private fun pageLinks(doc: Document, currentPath: String): List<Pair<String, String>> =
        doc.select("a[href*=/settings/]")
            .map { it.attr("abs:href").substringAfter(baseUrl.trimEnd('/')) to it.text().trim() }
            .filter { (href, label) ->
                label.isNotEmpty() && href != currentPath &&
                    (href.contains("/profile/") || href.endsWith("/newprofile"))
            }
            .distinctBy { it.first }

    /** POST edited fields back. Checkbox semantics: include only when checked. */
    fun submitForm(action: String, fields: List<FormField>) {
        val body = FormBody.Builder().apply {
            for (f in fields) {
                when (f.type) {
                    "checkbox" -> if (f.checked) add(f.name, f.value.ifEmpty { "on" })
                    else -> add(f.name, f.value)
                }
            }
        }.build()
        val resp = http.newCall(Request.Builder().url(url(action)).post(body).build()).execute()
        resp.use {
            if (!it.isSuccessful && it.code !in 300..399) {
                throw IllegalStateException("Zapis nieudany (HTTP ${it.code})")
            }
        }
    }
}
