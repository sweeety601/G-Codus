package com.example.gcodusadmin

import android.app.DatePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.util.concurrent.Executors

class AdminMainActivity : AppCompatActivity() {
    private val tokenStore by lazy { TokenStore(this) }
    private var github: GitHubClient? = null
    private var repo: AdminRepository? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val bg = 0xFF0D0E13.toInt()
    private val surface = 0xFF171923.toInt()
    private val ink = 0xFFF5F5F7.toInt()
    private val muted = ink
    private val accent = 0xFF8A63E8.toInt()
    private val portraitExecutor = Executors.newFixedThreadPool(4)
    private val portraitCache = android.util.LruCache<String, Bitmap>(72)
    private var pendingPortrait: ByteArray? = null
    private var pendingPortraitTarget: TextView? = null
    private var pendingPortraitPreview: ImageView? = null

    private val portraitPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val target = pendingPortraitTarget
        runBackground({ repo!!.readPortrait(uri, contentResolver) }, { bytes ->
            pendingPortrait = bytes
            target?.text = "Портрет выбран • WebP • " + (bytes.size / 1024) + " KB"
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { pendingPortraitPreview?.setImageBitmap(it) }
        }, { e -> toast("Ошибка изображения: " + (e.message ?: "неизвестная ошибка")) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loading = vertical()
        loading.addView(title("G-Codus Admin"))
        loading.addView(label("Проверка GitHub…", muted, 14f))
        setContentView(wrap(loading))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        val savedToken = tokenStore.get()
        if (savedToken.isNullOrBlank()) { showTokenScreen(); return }
        runBackground({ GitHubClient(savedToken).also { it.testToken() } }, { c ->
            github = c; repo = AdminRepository(c); showHome()
        }, { e ->
            tokenStore.clear(); github = null; repo = null
            showTokenScreen("Не удалось подключить GitHub: " + (e.message ?: "неизвестная ошибка"))
        })
    }

    private fun showTokenScreen(errorMessage: String? = null) {
        val box = vertical()
        box.addView(title("Подключение к GitHub"))
        if (!errorMessage.isNullOrBlank()) box.addView(label(errorMessage, 0xFFFF6B6B.toInt(), 14f, true))
        box.addView(label("G-Codus Admin v2.0.12", muted, 14f))
        box.addView(space(10))
        box.addView(label("Fine-grained token: Repository access → G-Codus → Contents: Read and write.", muted, 14f))
        val input = EditText(this).apply {
            hint = "github_pat_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ink); setHintTextColor(ink); setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(surface)
        }
        box.addView(input, lp())
        val save = button("Подключить GitHub")
        box.addView(save, lp())
        save.setOnClickListener {
            val token = input.text.toString().trim()
            if (token.isBlank()) { toast("Вставь токен"); return@setOnClickListener }
            save.isEnabled = false
            runBackground({ GitHubClient(token).also { it.testToken(); tokenStore.save(token) } }, {
                save.isEnabled = true; github = it; repo = AdminRepository(it); showHome()
            }, { error -> save.isEnabled = true; toast(error.message ?: "Ошибка подключения") })
        }
        setContentView(wrap(box))
    }

    private fun showHome() {
        val box = vertical()
        box.addView(title("G-Codus Admin"))
        box.addView(label("Редактор онлайн-базы G-Codus • v2.0.12", muted, 14f))
        box.addView(bigButton("Добавить персонажа в базу").also { it.setOnClickListener { chooseGame { game -> showCharacterEditor(game, null) } } }, lp(0, 70))
        box.addView(bigButton("Редактировать базу данных персонажей").also { it.setOnClickListener { showCharacterDatabase() } }, lp(0, 70))
        box.addView(bigButton("График баннеров").also { it.setOnClickListener { chooseGame { game -> showBannerTypes(game) } } }, lp(0, 70))
        box.addView(space(14))
        box.addView(button("Переподключить GitHub").also { it.setOnClickListener { tokenStore.clear(); showTokenScreen() } }, lp())
        setContentView(wrap(box))
    }

    private fun chooseGame(onSelected: (GameMeta) -> Unit) {
        val names = GameCatalog.games.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Выбери игру").setItems(names) { _, which -> onSelected(GameCatalog.games[which]) }.show()
    }

    private fun showCharacterEditor(game: GameMeta, existing: AdminCharacter?) {
        val r = repo ?: return
        runBackground({ r.loadCharacters(game) }, { list ->
            val id = existing?.id ?: r.nextId(game, list)
            pendingPortrait = null
            pendingPortraitTarget = null
            pendingPortraitPreview = null
            val box = vertical()
            box.addView(title(if (existing == null) "Новый персонаж" else "Редактирование персонажа"))
            box.addView(label("ID закреплён автоматически", muted, 13f))
            box.addView(field("ID", id, enabled = false))
            val name = field("Имя", existing?.name.orEmpty()); box.addView(name)
            val elements = linkedSetOf<String>(); list.map { it.element }.filter { it.isNotBlank() }.forEach { elements.add(it) }
            if (elements.isEmpty()) defaultElements(game).forEach { elements.add(it) }
            box.addView(label("Стихия", muted, 12f)); val elementSpinner = spinner(elements.toList(), existing?.element); box.addView(elementSpinner, lp())
            val rarities = (list.map { it.rarity.toString() } + listOf("4", "5")).distinct().sorted()
            box.addView(label("Редкость", muted, 12f)); val raritySpinner = spinner(rarities, existing?.rarity?.toString()); box.addView(raritySpinner, lp())
            box.addView(label("Текущий портрет", ink, 12f))
            val portraitPreview = ImageView(this).apply {
                setBackgroundColor(surface)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Текущий портрет персонажа"
            }
            box.addView(portraitPreview, LinearLayout.LayoutParams(-1, dp(220)).apply {
                setMargins(0, dp(6), 0, dp(8))
            })
            loadCurrentPortrait(id, portraitPreview)

            val portrait = button(if (existing == null) "Загрузить портрет" else "Заменить портрет")
            box.addView(portrait, lp())
            portrait.setOnClickListener {
                pendingPortraitTarget = portrait
                pendingPortraitPreview = portraitPreview
                portraitPicker.launch("image/*")
            }
            val save = bigButton("Сохранить в GitHub"); box.addView(save, lp(0, 58))
            save.setOnClickListener {
                val nm = name.text.toString().trim(); if (nm.isBlank()) { toast("Введи имя"); return@setOnClickListener }
                val el = elementSpinner.selectedItem?.toString().orEmpty(); val rar = raritySpinner.selectedItem?.toString()?.toIntOrNull() ?: 5
                save.isEnabled = false
                val updated = existing?.let { old -> list.map { if (it.id == old.id) AdminCharacter(old.id, nm, el, rar) else it } } ?: (list + AdminCharacter(id, nm, el, rar))
                val portraitBytes = pendingPortrait
                runBackground({
                    r.saveCharacters(game, updated)
                    if (portraitBytes != null) {
                        val path = "images/" + id + ".webp"
                        val oldSha = github!!.getFileSha(path)
                        github!!.putFile(path, portraitBytes, oldSha, "Admin: portrait " + id + " " + nm)
                    }
                }, { save.isEnabled = true; toast("Сохранено: " + id); showCharacterDatabase() }, { e -> save.isEnabled = true; toast("Ошибка сохранения: " + (e.message ?: "неизвестная ошибка")) })
            }
            addBack(box); setContentView(wrap(box))
        }, { e -> toast("Ошибка чтения базы: " + (e.message ?: "неизвестная ошибка")) })
    }

    private fun showCharacterDatabase() {
        val r = repo ?: return
        runBackground({ r.loadAllCharacters() }, { all ->
            val box = vertical()
            val header = horizontal()
            header.addView(title("База персонажей"), LinearLayout.LayoutParams(0, -2, 1f))
            val refresh = button("Обновить")
            header.addView(refresh, LinearLayout.LayoutParams(dp(120), dp(48)))
            refresh.setOnClickListener { showCharacterDatabase() }
            box.addView(header)

            val search = EditText(this).apply {
                hint = "Поиск по имени или ID"
                setTextColor(ink)
                setHintTextColor(ink)
                setSingleLine(true)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundColor(surface)
            }
            box.addView(search, lp())

            val scroll = makeScroll()
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(24))
                setBackgroundColor(bg)
            }
            scroll.addView(grid, ScrollView.LayoutParams(-1, -2))
            box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

            fun openCharacter(c: AdminCharacter) {
                GameCatalog.games.firstOrNull { g -> c.id.startsWith(g.idPrefix + ".") }
                    ?.let { game -> showCharacterEditor(game, c) }
            }

            fun makeCharacterCard(c: AdminCharacter): LinearLayout {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = roundedDrawable(surface, 16f)
                    isClickable = true
                    setOnClickListener { openCharacter(c) }
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(bg)
                    contentDescription = c.name
                }
                card.addView(image, LinearLayout.LayoutParams(-1, dp(118)).apply {
                    setMargins(0, 0, 0, dp(6))
                })
                card.addView(TextView(this).apply {
                    text = c.name
                    setTextColor(ink)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, dp(38)))
                card.addView(TextView(this).apply {
                    text = c.id + " • ★" + c.rarity
                    setTextColor(ink)
                    textSize = 11f
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, dp(22)))
                loadCurrentPortrait(c.id, image)
                return card
            }

            fun render(query: String) {
                grid.removeAllViews()
                val filtered = all.filter {
                    query.isBlank() ||
                        it.name.contains(query, true) ||
                        it.id.contains(query, true)
                }.sortedBy { it.id }

                if (filtered.isEmpty()) {
                    grid.addView(label("Ничего не найдено", ink, 14f))
                    return
                }

                filtered.chunked(3).forEach { chunk ->
                    val row = horizontal().apply {
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    chunk.forEach { c ->
                        row.addView(
                            makeCharacterCard(c),
                            LinearLayout.LayoutParams(0, dp(190), 1f).apply {
                                setMargins(dp(3), 0, dp(3), 0)
                            }
                        )
                    }
                    repeat(3 - chunk.size) {
                        row.addView(Space(this), LinearLayout.LayoutParams(0, dp(190), 1f))
                    }
                    grid.addView(row)
                }
            }

            search.addTextChangedListener(SimpleTextWatcher { render(it) })
            render("")
            addBack(box)
            setContentView(wrap(box))
        }, { e -> toast("Ошибка базы: " + (e.message ?: "неизвестная ошибка")) })
    }

    private fun showBannerTypes(game: GameMeta) {
        val box = vertical(); box.addView(title(game.name + " — график баннеров"))
        val confirmed = bigButton("Редактировать график подтверждённых баннеров"); box.addView(confirmed, lp(0, 64)); confirmed.setOnClickListener { showBannerList(game, true) }
        val leaks = bigButton("Редактировать график неподтверждённых баннеров"); box.addView(leaks, lp(0, 64)); leaks.setOnClickListener { showBannerList(game, false) }
        addBack(box); setContentView(wrap(box))
    }

    private fun showBannerList(game: GameMeta, confirmed: Boolean) {
        val r = repo ?: return
        runBackground({ r.loadBanners(game, confirmed).first }, { rows ->
            val box = vertical(); box.addView(title(if (confirmed) "Подтверждённые баннеры" else "Неподтверждённые баннеры"))
            rows.forEach { row -> val b = bigButton(row.phase + "\n" + row.startDate + " → " + row.endDate); box.addView(b, lp(0, 72)); b.setOnClickListener { showBannerEditor(game, confirmed, row) } }
            val add = bigButton("Добавить новый"); box.addView(add, lp(0, 64)); add.setOnClickListener { showBannerEditor(game, confirmed, null) }; addBack(box); setContentView(wrap(box))
        }, { e -> toast("Ошибка графика: " + (e.message ?: "неизвестная ошибка")) })
    }

    private fun showBannerEditor(game: GameMeta, confirmed: Boolean, existing: BannerRow?) {
        val r = repo ?: return
        runBackground({ r.loadCharacters(game) }, { chars ->
            val box = vertical(); box.addView(title(if (existing == null) "Новый баннер" else "Редактирование баннера"))
            val phase = field("Версия и фаза", existing?.phase.orEmpty())
            val start = field("Дата начала", existing?.startDate.orEmpty()).apply {
                isFocusable = false
                isCursorVisible = false
                isClickable = true
            }
            val end = field("Дата окончания", existing?.endDate.orEmpty()).apply {
                isFocusable = false
                isCursorVisible = false
                isClickable = true
            }
            box.addView(phase); box.addView(start); box.addView(end)
            start.setOnClickListener { pickDate(start) }
            end.setOnClickListener { pickDate(end) }
            box.addView(label("Персонажи", muted, 12f)); val selected = existing?.characters?.toMutableSet() ?: mutableSetOf(); val selectedView = bigButton("Выбрано: " + selected.size); box.addView(selectedView, lp(0, 58))
            selectedView.setOnClickListener { val labels = chars.map { it.id + " • " + it.name + " • ★" + it.rarity }.toTypedArray(); val checked = BooleanArray(chars.size) { selected.contains(chars[it].id) }; AlertDialog.Builder(this).setTitle("Персонажи").setMultiChoiceItems(labels, checked) { _, which, isChecked -> if (isChecked) selected.add(chars[which].id) else selected.remove(chars[which].id); selectedView.text = "Выбрано: " + selected.size }.setPositiveButton("Готово", null).show() }
            val save = bigButton("Сохранить в GitHub"); box.addView(save, lp(0, 58)); save.setOnClickListener { val row = BannerRow(phase.text.toString().trim(), start.text.toString().trim(), end.text.toString().trim(), selected.toMutableList(), existing?.fourStars ?: mutableListOf()); if (row.phase.isBlank() || row.startDate.isBlank() || row.endDate.isBlank()) { toast("Заполни версию и обе даты"); return@setOnClickListener }; runBackground({ val all = r.loadBanners(game, confirmed).first.toMutableList(); val idx = existing?.let { old -> all.indexOfFirst { it.phase == old.phase && it.startDate == old.startDate && it.endDate == old.endDate } } ?: -1; if (idx >= 0) all[idx] = row else all.add(row); r.saveBanners(game, confirmed, all) }, { toast("Баннер сохранён"); showBannerList(game, confirmed) }, { e -> toast("Ошибка сохранения: " + (e.message ?: "неизвестная ошибка")) }) }
            addBack(box); setContentView(wrap(box))
        }, { e -> toast("Ошибка чтения персонажей: " + (e.message ?: "неизвестная ошибка")) })
    }

    private fun loadCurrentPortrait(id: String, image: ImageView) {
        portraitCache.get(id)?.let {
            image.setImageBitmap(it)
            return
        }
        portraitExecutor.execute {
            try {
                val url = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/images/" + id + ".webp"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "G-Codus-Admin/2.0")
                val bitmap = connection.inputStream.use { input -> BitmapFactory.decodeStream(input) }
                connection.disconnect()
                if (bitmap != null) {
                    portraitCache.put(id, bitmap)
                    runOnUiThread {
                        if (!isFinishing) image.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
                // A character can exist in the database before its portrait is uploaded.
            }
        }
    }

    private fun pickDate(target: EditText) { val now = LocalDate.now(); DatePickerDialog(this, { _, y, m, d -> target.setText(String.format("%04d-%02d-%02d", y, m + 1, d)) }, now.year, now.monthValue - 1, now.dayOfMonth).show() }
    private fun defaultElements(game: GameMeta): List<String> = when (game.idPrefix) { "1" -> listOf("Fusion", "Glacio", "Electro", "Aero", "Spectro", "Havoc"); "2" -> listOf("Pyro", "Hydro", "Anemo", "Electro", "Cryo", "Geo", "Dendro"); "3" -> listOf("Physical", "Fire", "Ice", "Lightning", "Wind", "Quantum", "Imaginary"); "4" -> listOf("Physical", "Arts", "Electric", "Cryo", "Fire", "Wind"); else -> listOf("Physical", "Fire", "Ice", "Electric", "Ether") }
    private fun runBackground(work: () -> Unit, ok: () -> Unit) { executor.execute { try { work(); runOnUiThread(ok) } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Ошибка") } } } }
    private fun <T> runBackground(work: () -> T, ok: (T) -> Unit, fail: (Exception) -> Unit) { executor.execute { try { val value = work(); runOnUiThread { ok(value) } } catch (e: Exception) { runOnUiThread { fail(e) } } } }
    private fun addBack(box: LinearLayout) {
        val back = button("← Назад").also { it.setOnClickListener { showHome() } }
        box.addView(back, 0, lp(120, 48))
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(bg) }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(bg) }
    private fun wrap(v: View): FrameLayout = FrameLayout(this).apply { addView(v, FrameLayout.LayoutParams(-1, -1)); setBackgroundColor(bg) }
    private fun title(t: String) = TextView(this).apply { text = t; setTextColor(ink); textSize = 24f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(12)) }
    private fun label(t: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply { text = t; setTextColor(color); textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(8)) }
    private fun field(hint: String, value: String = "", enabled: Boolean = true) = EditText(this).apply { this.hint = hint; setText(value); isEnabled = enabled; setTextColor(ink); setHintTextColor(muted); setSingleLine(true); setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(surface) }
    private fun spinner(items: List<String>, selected: String?) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(
            this@AdminMainActivity,
            android.R.layout.simple_spinner_item,
            items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(ink)
                view.textSize = 15f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(ink)
                view.setBackgroundColor(surface)
                view.textSize = 15f
                return view
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        selected?.let {
            val i = items.indexOf(it)
            if (i >= 0) setSelection(i)
        }
    }
    private fun button(text: String) = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(ink); setBackgroundColor(surface) }
    private fun bigButton(text: String) = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(ink); setTextSize(15f); setBackgroundColor(surface); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private fun space(h: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(if (w == 0) -1 else dp(w), if (h == 0) -2 else dp(h)).apply { setMargins(0, dp(5), 0, dp(5)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("github", Context.MODE_PRIVATE)
    fun get(): String? = prefs.getString("token", null)
    fun save(token: String) { prefs.edit().putString("token", token).apply() }
    fun clear() { prefs.edit().remove("token").apply() }
}

class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChanged(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) {}
}