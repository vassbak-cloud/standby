package gr.vassbak.standby

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Ρυθμίσεις: άδειες, φορά οθόνης, νυχτερινή λειτουργία, ενεργοποίηση. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(title("StandBy"))
        col.addView(text(
            "Όταν το κινητό φορτίζει, δείχνει ρολόι, ημερομηνία, καιρό, μπαταρία και τα επόμενα ραντεβού.\n\n" +
                "Βήματα:\n1. Δώσε τις άδειες.\n2. Πάτα «Ενεργοποίηση» και διάλεξε StandBy.\n" +
                "3. Στην ίδια οθόνη όρισε να ξεκινά «κατά τη φόρτιση»."
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
        col.addView(button("Δοκιμή τώρα") {
            startActivity(Intent(this, PreviewActivity::class.java))
        })

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
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        updateStatus()
    }

    private fun updateStatus() {
        fun ok(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val cal = if (ok(Manifest.permission.READ_CALENDAR)) "✅" else "❌"
        val loc = if (ok(Manifest.permission.ACCESS_COARSE_LOCATION)) "✅" else "❌ (θα δείχνει Αθήνα)"
        status.text = "Ημερολόγιο: $cal\nΤοποθεσία: $loc"
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
