package com.example.gcodusadmin

import android.app.DatePickerDialog
import android.content.Context
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
    private val muted = 0xFFA5A7B1.toInt()
    private val accent = 0xFF8A63E8.toInt()
    private var root: LinearLayout? = null

    private var pendingPortrait: ByteArray? = null
    private var pendingPortraitTarget: TextView? = null

    private val portraitPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val target = pendingPortraitTarget
        runBackground({
            repo!!.readPortrait(uri, contentResolver)
        }, { bytes ->
            pendingPortrait = bytes
            target?.text = "Портрет выбран • WebP • " + (bytes.size / 1024) + " KB"
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
        if (!connect()) {
            showTokenScreen()
        } else {
            showHome()
        }
    }

    private fun connect(): Boolean {
        val token = tokenStore.get() ?: return false
        github = GitHubClient(token)
        repo = AdminRepository(github!!)
        return true
    }

    private fun showTokenScreen() {
        val box = vertical()
        box.addView(title("Подключение к GitHub"))
        box.addView(label("G-Codus Admin напрямую изменяет репозиторий sweeety601/G-Codus.", muted, 14f))
        box.addView(space(10))
        box.addView(label("Создай GitHub Fine-grained token с правом Contents: Read and write и вставь его ниже.", muted, 14f))
        val input = EditText(this).apply {
            hint = "github_pat_..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(surface)
        }
        box.addView(input, lp())
        val save = button("Подключить GitHub")
        box.addView(save, lp())
        save.setOnClickListener {
            val token = input.text.toString().trim()
            if (token.isBlank()) { toast("Вставь токен"); return@setOnClickListener }
            save.isEnabled = false
            runBackground({
                val c = GitHubClient(token)
                c.testToken()
                tokenStore.save(token)
            }, {
                save.isEnabled = true
                connect()
                showHome()
            }, { error ->
                save.isEnabled = true
                toast("GitHub не принят: " + error.message)
            })
        }
        setContentView(wrap(box))
    }

    private fun showHome() {
        val box = vertical()
        val header = horizontal()
        header.addView(title("G-Codus Admin"), LinearLayout.LayoutParams(0, -2, 1f))
        val settings = button("GitHub")
        header.addView(settings, LinearLayout.LayoutParams(dp(100), dp(48)))
        settings.setOnClickListener { showTokenSettings() }
        box.addView(header)
        box.addView(label("Редактор онлайн-базы G-Codus", muted, 14f))
        box.addView(space(18))

        val add = bigButton("Добавить персонажа в базу")
        box.addView(add, lp(0, 64))
        add.setOnClickListener { chooseGame("Добавить персонажа") { showCharacterEditor(it, null) } }

        val edit = bigButton("Редактировать базу данных персонажей")
        box.addView(edit, lp(0, 64))
        edit.setOnClickListener { showCharacterDatabase() }

        val banners = bigButton("График баннеров")
        box.addView(banners, lp(0, 64))
        banners.setOnClickListener { chooseGame("График баннеров") { showBannerTypes(it) } }

        setContentView(wrap(box))
    }

    private fun showTokenSettings() {
        val box = vertical()
        box.addView(title("GitHub"))
        box.addView(label("Токен хранится в Android Keystore и используется только для доступа к G-Codus.", muted, 14f))
        box.addView(space(12))
        val clear = button("Переподключить GitHub")
        box.addView(clear, lp())
        clear.setOnClickListener {
            tokenStore.clear()
            github = null
            repo = null
            showTokenScreen()
        }
        setContentView(wrap(box))
    }

    private fun chooseGame(screenTitle: String, onSelect: (GameMeta) -> Unit) {
        val box = vertical()
        box.addView(title(screenTitle))
        GameCatalog.games.forEach { game ->
            val b = bigButton(game.name)
            box.addView(b, lp(0, 58))
            b.setOnClickListener { onSelect(game) }
        }
        addBack(box)
        setContentView(wrap(box))
    }

    private fun showCharacterEditor(game: GameMeta, existing: AdminCharacter?) {
        val r = repo ?: return
        runBackground({ r.loadCharacters(game) }, { list ->
            val id = existing?.id ?: r.nextId(game, list)
            pendingPortrait = null
            val box = vertical()
            box.addView(title(if (existing == null) "Новый персонаж" else "Редактирование персонажа"))
            box.addView(label("ID закреплён автоматически", muted, 13f))
            val idView = field("ID", id, enabled = false)
            val name = field("Имя", existing?.name.orEmpty())
            box.addView(idView); box.addView(name)

            val elements = linkedSetOf<String>()
            list.map { it.element }.filter { it.isNotBlank() }.forEach { elements.add(it) }
            if (elements.isEmpty()) defaultElements(game).forEach { elements.add(it) }
            box.addView(label("Стихия", muted, 12f))
            val elementSpinner = spinner(elements.toList(), existing?.element)
            box.addView(elementSpinner, lp())

            val rarities = (list.map { it.rarity.toString() } + listOf("4","5")).distinct().sorted()
            box.addView(label("Редкость", muted, 12f))
            val raritySpinner = spinner(rarities, existing?.rarity?.toString())
            box.addView(raritySpinner, lp())

            val portrait = button(if (existing == null) "Загрузить портрет" else "Заменить портрет")
            box.addView(portrait, lp())
            portrait.setOnClickListener {
                pendingPortraitTarget = portrait
                portraitPicker.launch("*/*")
            }

            box.addView(space(10))
            val save = bigButton("Сохранить в GitHub")
            box.addView(save, lp(0, 58))
            save.setOnClickListener {
                val nm = name.text.toString().trim()
                if (nm.isBlank()) { toast("Введи имя"); return@setOnClickListener }
                val el = elementSpinner.selectedItem?.toString().orEmpty()
                val rar = raritySpinner.selectedItem?.toString()?.toIntOrNull() ?: 5
                save.isEnabled = false
                val updated = existing?.let { old ->
                    list.map { if (it.id == old.id) AdminCharacter(old.id, nm, el, rar) else it }
                } ?: (list + AdminCharacter(id, nm, el, rar))
                val portraitBytes = pendingPortrait
                runBackground({
                    r.saveCharacters(game, updated)
                    if (portraitBytes != null) {
                        val path = "images/" + id + ".webp"
                        val oldFile = try { github!!.getFile(path) } catch (_: Exception) { null }
                        github!!.putFile(path, portraitBytes, oldFile?.sha, "Admin: portrait " + id + " " + nm)
                    }
                }, {
                    toast("Сохранено: " + id)
                    showCharacterDatabase()
                }, { e ->
                    save.isEnabled = true
                    toast("Ошибка сохранения: " + e.message)
                })
            }
            addBack(box)
            setContentView(wrap(box))
        }, { e -> toast("Ошибка чтения базы: " + e.message) })
    }

    private fun showCharacterDatabase() {
        val r = repo ?: return
        runBackground({ r.loadAllCharacters() }, { all ->
            val box = vertical()
            val header = horizontal()
            header.addView(title("База персонажей"), LinearLayout.LayoutParams(0,-2,1f))
            val refresh = button("Обновить")
            header.addView(refresh, LinearLayout.LayoutParams(dp(120), dp(48)))
            refresh.setOnClickListener { showCharacterDatabase() }
            box.addView(header)

            val search = EditText(this).apply {
                hint = "Поиск по имени или ID"
                setTextColor(ink); setHintTextColor(muted); setSingleLine()
                setPadding(dp(14),dp(10),dp(14),dp(10)); setBackgroundColor(surface)
            }
            box.addView(search, lp())
            val listBox = vertical()
            box.addView(listBox)
            fun render(query: String) {
                listBox.removeAllViews()
                all.filter {
                    query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
                }.sortedBy { it.id }.forEach { c ->
                    val card = bigButton(c.id + "   " + c.name + "   ★" + c.rarity)
                    listBox.addView(card, lp(0, 56))
                    card.setOnClickListener {
                        val game = GameCatalog.games.firstOrNull { g -> c.id.startsWith(g.idPrefix + ".") }
                        if (game != null) showCharacterEditor(game, c)
                    }
                }
                if (listBox.childCount == 0) listBox.addView(label("Ничего не найдено", muted, 14f))
            }
            search.addTextChangedListener(SimpleTextWatcher { render(it) })
            render("")
            addBack(box)
            setContentView(wrap(box))
        }, { e -> toast("Ошибка базы: " + e.message) })
    }

    private fun showBannerTypes(game: GameMeta) {
        val box = vertical()
        box.addView(title(game.name + " — график баннеров"))
        val confirmed = bigButton("Редактировать график подтверждённых баннеров")
        box.addView(confirmed, lp(0, 64))
        confirmed.setOnClickListener { showBannerList(game, true) }
        val leaks = bigButton("Редактировать график неподтверждённых баннеров")
        box.addView(leaks, lp(0, 64))
        leaks.setOnClickListener { showBannerList(game, false) }
        addBack(box)
        setContentView(wrap(box))
    }

    private fun showBannerList(game: GameMeta, confirmed: Boolean) {
        val r = repo ?: return
        runBackground({ r.loadBanners(game, confirmed).first }, { rows ->
            val box = vertical()
            box.addView(title(game.name + " — " + if (confirmed) "Подтверждённые" else "Leaks"))
            if (rows.isEmpty()) box.addView(label("График пока пуст.", muted, 14f))
            rows.forEachIndexed { index, b ->
                val card = horizontal()
                val info = vertical()
                info.addView(label(b.phase, ink, 16f, true))
                info.addView(label(b.startDate + " → " + b.endDate, muted, 13f))
                info.addView(label("Персонажи: " + b.characters.joinToString(", "), muted, 12f))
                card.addView(info, LinearLayout.LayoutParams(0,-2,1f))
                val edit = button("Редактировать")
                card.addView(edit, LinearLayout.LayoutParams(dp(125), dp(52)))
                card.setBackgroundColor(surface)
                card.setPadding(dp(10),dp(10),dp(6),dp(10))
                box.addView(card, lp())
                edit.setOnClickListener { showBannerEditor(game, confirmed, rows, index) }
                box.addView(space(8))
            }
            val add = bigButton("Добавить новый")
            box.addView(add, lp(0, 58))
            add.setOnClickListener { showBannerEditor(game, confirmed, rows, null) }
            addBack(box)
            setContentView(wrap(box))
        }, { e -> toast("Ошибка графика: " + e.message) })
    }

    private fun showBannerEditor(game: GameMeta, confirmed: Boolean, rows: MutableList<BannerRow>, index: Int?) {
        val r = repo ?: return
        runBackground({ r.loadCharacters(game) }, { chars ->
            val existing = index?.let { rows[it] }
            val box = vertical()
            box.addView(title(if (existing == null) "Новый баннер" else "Редактирование баннера"))

            val phase = field("Версия и фаза", existing?.phase.orEmpty())
            box.addView(phase)
            val start = dateField("Дата начала", existing?.startDate ?: "")
            val end = dateField("Дата окончания", existing?.endDate ?: "")
            box.addView(start); box.addView(end)

            val selected5 = mutableListOf<String>()
            val selected4 = mutableListOf<String>()
            if (existing != null) {
                selected5.addAll(existing.characters)
                selected4.addAll(existing.fourStars)
            }

            val charButton = bigButton(selectionLabel("Персонажи", selected5, chars))
            box.addView(charButton, lp(0, 64))
            charButton.setOnClickListener {
                chooseCharacters(chars, selected5, "Персонажи", true) {
                    charButton.text = selectionLabel("Персонажи", selected5, chars)
                }
            }

            val fourButton = bigButton(selectionLabel("4★ в баннере", selected4, chars))
            box.addView(fourButton, lp(0, 64))
            fourButton.setOnClickListener {
                chooseCharacters(chars, selected4, "4★ в баннере", false) {
                    fourButton.text = selectionLabel("4★ в баннере", selected4, chars)
                }
            }

            val save = bigButton("Сохранить в GitHub")
            box.addView(save, lp(0,58))
            save.setOnClickListener {
                val p = phase.text.toString().trim()
                if (p.isBlank() || start.text.isNullOrBlank() || end.text.isNullOrBlank()) {
                    toast("Заполни фазу и обе даты"); return@setOnClickListener
                }
                val value = BannerRow(p, start.text.toString(), end.text.toString(), selected5.toMutableList(), selected4.toMutableList())
                if (index == null) rows.add(value) else rows[index] = value
                save.isEnabled = false
                runBackground({ r.saveBanners(game, confirmed, rows) }, {
                    toast("График сохранён")
                    showBannerList(game, confirmed)
                }, { e ->
                    save.isEnabled = true
                    toast("Ошибка: " + e.message)
                })
            }
            addBack(box)
            setContentView(wrap(box))
        }, { e -> toast("Ошибка персонажей: " + e.message) })
    }

    private fun chooseCharacters(
        chars: List<AdminCharacter>,
        selected: MutableList<String>,
        titleText: String,
        allRarities: Boolean,
        onDone: () -> Unit
    ) {
        val search = EditText(this).apply {
            hint = "Поиск по имени или ID"
            setTextColor(ink); setHintTextColor(muted); setSingleLine()
        }
        val listBox = vertical()
        val scroll = ScrollView(this).apply { addView(listBox) }
        val wrap = vertical()
        wrap.addView(search, lp())
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))

        fun render(q: String) {
            listBox.removeAllViews()
            chars.filter {
                !(!allRarities && it.rarity != 4) &&
                (q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true))
            }.forEach { c ->
                val cb = CheckBox(this).apply {
                    text = c.id + "  " + c.name + "  ★" + c.rarity
                    isChecked = selected.contains(c.id)
                    setTextColor(ink)
                    setPadding(dp(6),dp(7),dp(6),dp(7))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) { if (!selected.contains(c.id)) selected.add(c.id) }
                        else selected.remove(c.id)
                    }
                }
                listBox.addView(cb)
            }
        }
        search.addTextChangedListener(SimpleTextWatcher { render(it) })
        render("")
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(wrap)
            .setPositiveButton("Готово") { _, _ -> onDone() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dateField(labelText: String, value: String): EditText {
        val e = field(labelText, value)
        e.isFocusable = false
        e.isClickable = true
        e.setOnClickListener {
            val now = runCatching { LocalDate.parse(e.text.toString()) }.getOrElse { LocalDate.now() }
            DatePickerDialog(this, { _, y, m, d ->
                e.setText(String.format("%04d-%02d-%02d", y, m + 1, d))
            }, now.year, now.monthValue - 1, now.dayOfMonth).show()
        }
        return e
    }

    private fun spinner(values: List<String>, selected: String?): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        val idx = values.indexOf(selected)
        if (idx >= 0) s.setSelection(idx)
        return s
    }

    private fun field(hint: String, value: String, enabled: Boolean = true): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            isEnabled = enabled
            setTextColor(ink); setHintTextColor(muted)
            setSingleLine(true)
            setPadding(dp(14),dp(10),dp(14),dp(10))
            setBackgroundColor(surface)
            layoutParams = lp(0, 54)
            (parent as? ViewGroup)?.addView(this)
        }

    private fun selectionLabel(prefix: String, ids: List<String>, chars: List<AdminCharacter>): String {
        if (ids.isEmpty()) return prefix + ": не выбрано"
        val names = ids.mapNotNull { id -> chars.firstOrNull { it.id == id }?.name ?: id }
        return prefix + ": " + names.joinToString(", ")
    }

    private fun defaultElements(game: GameMeta): List<String> = when (game.key) {
        "wuwa" -> listOf("Fusion","Glacio","Aero","Electro","Spectro","Havoc")
        "genshin" -> listOf("Pyro","Hydro","Anemo","Electro","Cryo","Geo","Dendro")
        "starrail" -> listOf("Physical","Fire","Ice","Lightning","Wind","Quantum","Imaginary")
        "endfield" -> listOf("Physical","Thermal","Cryo","Electric","Nature")
        else -> listOf("Fire","Ice","Electric","Ether","Physical")
    }

    private fun addBack(box: LinearLayout) {
        val back = button("← Назад")
        box.addView(space(8))
        box.addView(back, lp())
        back.setOnClickListener { showHome() }
    }

    private fun wrap(content: View): ScrollView = ScrollView(this).apply {
        setBackgroundColor(bg)
        setPadding(dp(14),0,dp(14),dp(24))
        addView(content)
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(0,dp(10),0,0)
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun title(value: String) = TextView(this).apply {
        text = value; textSize = 24f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        setPadding(0,dp(8),0,dp(4))
    }

    private fun label(value: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0,dp(4),0,dp(4))
    }

    private fun button(value: String) = Button(this).apply {
        text = value; setTextColor(ink); textSize = 13f
        isAllCaps = false
    }

    private fun bigButton(value: String) = Button(this).apply {
        text = value; setTextColor(ink); textSize = 15f
        isAllCaps = false
        setBackgroundColor(accent)
    }

    private fun space(h: Int) = Space(this).apply { layoutParams = lp(0,h) }

    private fun lp(w: Int = -1, h: Int = -2) = LinearLayout.LayoutParams(
        if (w <= 0) -1 else dp(w),
        if (h == -2) LinearLayout.LayoutParams.WRAP_CONTENT else dp(h)
    ).apply { setMargins(0,dp(5),0,dp(5)) }

    private fun <T> runBackground(work: () -> T, done: (T) -> Unit, fail: ((Exception) -> Unit)? = null) {
        executor.execute {
            try {
                val value = work()
                runOnUiThread { done(value) }
            } catch (e: Exception) {
                runOnUiThread { fail?.invoke(e) ?: toast(e.message ?: "Ошибка") }
            }
        }
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    class SimpleTextWatcher(private val block: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { block(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}