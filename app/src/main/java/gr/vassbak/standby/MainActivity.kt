package gr.vassbak.standby

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Ρυθμίσεις: άδειες, φορά οθόνης, νυχτερινή λειτουργία, ενεργοποίηση. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var corosStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(title("StandBy"))
        col.addView(text(
            "Όταν το κινητό φορτίζει, δείχνει ρολόι, καιρό, μπαταρία, ραντεβού, πρόγραμμα COROS και ειδοποιήσεις. " +
                "Πάτα ένα πλακίδιο για να ανοίξει σε όλη την οθόνη, ξανά πάτημα για επιστροφή. Διπλό πάτημα στο κενό για έξοδο. Όσο τρέχει πλοήγηση Google Maps, δείχνει τις οδηγίες.\n\n" +
                "Βήματα:\n1. Δώσε τις άδειες.\n2. Πάτα «Ενεργοποίηση» και διάλεξε StandBy.\n" +
                "3. Στην ίδια οθόνη όρισε να ξεκινά «κατά τη φόρτιση».\n4. Πρόσβαση στις ειδοποιήσεις & COROS (προαιρετικά)."
        ))

        status = text("")
        col.addView(status)

        col.addView(button("1. Άδειες (ημερολόγιο & τοποθεσία)") {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        })
        col.addView(button("2. Ενεργοποίηση προφύλαξης οθόνης") {
            try { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        })
        col.addView(button("3. Πρόσβαση στις ειδοποιήσεις") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("Δοκιμή τώρα") {
            startActivity(Intent(this, PreviewActivity::class.java))
        })

        // ---------- Αυτόματη εκκίνηση ----------
        col.addView(title("Όταν σβήνει η οθόνη", 18f))
        col.addView(text("Ανοίγει το StandBy κάθε φορά που σβήνει η οθόνη (π.χ. στη βάση του αυτοκινήτου). " +
            "Αν τη σβήσεις εσύ με το κουμπί ενώ φαίνεται το StandBy, μένει σβηστή. Η οθόνη ανοιχτή καταναλώνει μπαταρία."))
        val autoGroup = RadioGroup(this)
        val autoOptions = listOf(
            Prefs.AUTO_OFF to "Ποτέ",
            Prefs.AUTO_CHARGING to "Μόνο όταν φορτίζει",
            Prefs.AUTO_NAV to "Μόνο όταν τρέχει πλοήγηση Google Maps",
            Prefs.AUTO_ALWAYS to "Πάντα"
        )
        for ((value, label) in autoOptions) {
            autoGroup.addView(RadioButton(this).apply {
                id = 3000 + value
                text = label
                textSize = 16f
                isChecked = Prefs.autoMode(this@MainActivity) == value
            })
        }
        autoGroup.setOnCheckedChangeListener { _, id ->
            Prefs.get(this).edit().putInt(Prefs.KEY_AUTO, id - 3000).apply()
            if (id - 3000 != Prefs.AUTO_OFF) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                }
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Δώσε την άδεια «Εμφάνιση πάνω από άλλες εφαρμογές»", Toast.LENGTH_LONG).show()
                }
            }
            StandbyService.sync(this)
            updateStatus()
        }
        col.addView(autoGroup)
        col.addView(Switch(this).apply {
            text = "Άνοιγμα όταν ανάβει η οθόνη στον φορτιστή (π.χ. με διπλό ταπ)"
            textSize = 16f
            isChecked = Prefs.wakeOnCharging(this@MainActivity)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on ->
                Prefs.get(this@MainActivity).edit().putBoolean(Prefs.KEY_WAKE_CHARGING, on).apply()
                if (on && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Δώσε την άδεια «Εμφάνιση πάνω από άλλες εφαρμογές»", Toast.LENGTH_LONG).show()
                }
                StandbyService.sync(this@MainActivity)
            }
        })
        col.addView(Switch(this).apply {
            text = "Σβήσιμο στην τσέπη (αισθητήρας εγγύτητας): σβήνει όταν καλυφθεί, ανάβει όταν βγει"
            textSize = 16f
            isChecked = Prefs.pocket(this@MainActivity)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on ->
                Prefs.get(this@MainActivity).edit().putBoolean(Prefs.KEY_POCKET, on).apply()
            }
        })
        col.addView(text("Για να ανάβει με διπλό ταπ, ενεργοποίησε στις ρυθμίσεις του κινητού το «Διπλό πάτημα για ενεργοποίηση οθόνης»."))
        col.addView(button("Άδεια «Εμφάνιση πάνω από άλλες εφαρμογές»") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        })

        // ---------- COROS ----------
        col.addView(title("COROS", 18f))
        col.addView(text("Τα στοιχεία του λογαριασμού COROS. Ο κωδικός δεν αποθηκεύεται, μόνο το hash του."))
        val p = Prefs.get(this)
        val email = EditText(this).apply {
            hint = "Email COROS"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(p.getString(Prefs.COROS_EMAIL, ""))
        }
        val pwd = EditText(this).apply {
            hint = if (Coros.configured(this@MainActivity)) "Κωδικός (αποθηκευμένος)" else "Κωδικός COROS"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(email)
        col.addView(pwd)
        val region = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val eu = RadioButton(this).apply { id = 2001; text = "Ευρώπη"; textSize = 16f }
        val us = RadioButton(this).apply { id = 2002; text = "Παγκόσμιο"; textSize = 16f }
        region.addView(eu); region.addView(us)
        if (p.getString(Prefs.COROS_REGION, "eu") == "us") us.isChecked = true else eu.isChecked = true
        col.addView(region)
        corosStatus = text("")
        col.addView(button("Σύνδεση COROS") {
            val e = email.text.toString().trim()
            val pw = pwd.text.toString()
            val ed = p.edit().putString(Prefs.COROS_EMAIL, e)
                .putString(Prefs.COROS_REGION, if (us.isChecked) "us" else "eu")
                .remove(Prefs.COROS_TOKEN)
            if (pw.isNotEmpty()) ed.putString(Prefs.COROS_PWD_MD5, Coros.md5(pw))
            ed.apply()
            pwd.setText("")
            corosStatus.text = "Σύνδεση…"
            Thread {
                val err = Coros.login(this)
                val msg = if (err == null) {
                    val list = try { Coros.schedule(this, 7) } catch (_: Exception) { null }
                    if (list == null) "✅ Συνδέθηκε, αλλά το πρόγραμμα δεν διαβάστηκε"
                    else "✅ Συνδέθηκε. Προπονήσεις 7 ημερών: ${list.size}" +
                        list.joinToString("") { "\n• ${it.day.takeLast(2)}/${it.day.drop(4).take(2)} ${it.name}" }
                } else "❌ $err"
                runOnUiThread { corosStatus.text = msg; Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }.start()
        })
        col.addView(corosStatus)

        col.addView(title("Φορά οθόνης", 18f))
        val prefs = Prefs.get(this)
        val group = RadioGroup(this)
        val options = listOf(
            90 to "Οριζόντια — φορτιστής δεξιά",
            270 to "Οριζόντια — φορτιστής αριστερά",
            0 to "Ακολουθεί την περιστροφή του κινητού"
        )
        for ((value, label) in options) {
            group.addView(RadioButton(this).apply {
                id = 1000 + value
                text = label
                textSize = 16f
                isChecked = Prefs.rotation(this@MainActivity) == value
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(Prefs.KEY_ROTATION, id - 1000).apply()
        }
        col.addView(group)

        col.addView(Switch(this).apply {
            text = "Νυχτερινή λειτουργία (23:00–07:00, κόκκινα & χαμηλωμένα)"
            textSize = 16f
            isChecked = Prefs.nightMode(this@MainActivity)
            setPadding(0, pad, 0, 0)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.KEY_NIGHT, on).apply() }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        try { StandbyService.sync(this) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        updateStatus()
    }

    private fun updateStatus() {
        fun ok(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val cal = if (ok(Manifest.permission.READ_CALENDAR)) "✅" else "❌"
        val loc = if (ok(Manifest.permission.ACCESS_COARSE_LOCATION)) "✅" else "❌ (θα δείχνει Αθήνα)"
        val notif = if (NotifListener.enabled(this)) "✅" else "❌ (χρειάζεται και για τις οδηγίες Maps)"
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        status.text = "Ημερολόγιο: $cal\nΤοποθεσία: $loc\nΕιδοποιήσεις: $notif\nΕμφάνιση πάνω από εφαρμογές: $overlay"
    }

    private fun title(s: String, size: Float = 26f) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor("#FFB340"))
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, 8, 0, 8)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
