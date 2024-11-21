package ee.nekoko.revolver1s

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun hexStringToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun swapEveryTwoCharacters(input: String): String {
    return buildString {
        for (i in input.indices step 2) {
            append(input.getOrNull(i + 1)?.toString() ?: "")
            append(input[i])
        }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var sharedPreferences: SharedPreferences
    private var isPlaying = true
    private var intervalInMilliSeconds: Long = 0
    private lateinit var intervalInput: EditText
    private lateinit var saveButton: Button
    private lateinit var resultText: TextView
    private lateinit var nextSwitch: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var simCheckboxesContainer: LinearLayout
    private var handler = Handler(Looper.getMainLooper())
    private var simSlots: Int = 0
    private val simSlotIds = mutableMapOf<String, Int>()
    private var _seService: SEService? = null
    private val lock = Mutex()
    private var runnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupSharedPreferences()
        setupFabClickListener()
        initializeSimCheckboxes()
        startRecurringTimer()
        enqueueSwitch()
    }

    private fun initializeViews() {
        intervalInput = findViewById(R.id.intervalInput)
        saveButton = findViewById(R.id.saveButton)
        resultText = findViewById(R.id.resultText)
        nextSwitch = findViewById(R.id.nextSwitch)
        fab = findViewById(R.id.fab)
        simCheckboxesContainer = findViewById(R.id.simCheckboxesContainer)
    }

    private fun setupSharedPreferences() {
        sharedPreferences = getSharedPreferences("eSimPreferences", MODE_PRIVATE)
        intervalInMilliSeconds = sharedPreferences.getLong("interval", 1)
        intervalInput.setText(intervalInMilliSeconds.toString())
        resultText.text = "Switching eSIM every $intervalInMilliSeconds milliseconds."
    }

    private fun setupFabClickListener() {
        fab.setOnClickListener {
            isPlaying = !isPlaying
            updateFABIcon()
            if (isPlaying) {
                enqueueSwitch()
            }
        }

        saveButton.setOnClickListener {
            val inputText = intervalInput.text.toString()
            if (inputText.isNotEmpty()) {
                intervalInMilliSeconds = inputText.toLong()
                if (intervalInMilliSeconds >= 1) {
                    resultText.text = "Switching eSIM every $intervalInMilliSeconds milliseconds."
                    sharedPreferences.edit().putLong("interval", intervalInMilliSeconds).apply()
                    enqueueSwitch()
                } else {
                    showToast("Please enter a number greater than or equal to 1.")
                }
            } else {
                showToast("Please enter an interval.")
            }
        }
    }

    private fun initializeSimCheckboxes() {
        val subscriptionManager = SubscriptionManager.from(applicationContext)
        simSlots = subscriptionManager.getActiveSubscriptionInfoCountMax()
        for (i in 1..simSlots) {
            val checkBox = CheckBox(this).apply {
                text = "SIM$i"
                id = View.generateViewId()
                isChecked = sharedPreferences.getBoolean("SIM$i", true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            simSlotIds["SIM$i"] = checkBox.id
            simCheckboxesContainer.addView(checkBox)
        }
    }

    private fun enqueueSwitch() {
        if (_seService == null) {
            CoroutineScope(Dispatchers.IO).launch {
                lock.withLock {
                    try {
                        _seService = SEService(applicationContext, { it.run() }) {
                            CoroutineScope(Dispatchers.Main).launch {
                                Log.d("TAG", "SE service is connected!")
                                listReaders(_seService!!)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TAG", "Error connecting to SEService", e)
                    }
                }
            }
        } else {
            listReaders(_seService!!)
        }
    }

    private fun listReaders(seService: SEService) {
        Log.d("TAG", "ListReaders Task executed at: ${System.currentTimeMillis()}")
        if (seService.isConnected) {
            seService.readers.forEach { reader ->
                try {
                    reader.closeSessions()
                    val session: Session = reader.openSession()
                    session.closeChannels()
                    val atr = session.getATR()
                    Log.i("TAG", "${reader.name} ATR: $atr Session: $session")
                    val chan = session.openLogicalChannel(hexStringToByteArray("A0000005591010FFFFFFFF8900000100"))!!
                    Log.i("TAG", "${reader.name} Opened Channel")
                    val response: ByteArray = chan.getSelectResponse()!!
                    Log.i("TAG", "Opened logical channel: ${response.toHex()}")
                    val resp1 = chan.transmit(hexStringToByteArray("81E2910006BF3E035C015A"))
                    Log.i("TAG", "Transmit Response: ${resp1.toHex()}")
                    if (resp1[0] == 0xbf.toByte()) {
                        switchToNext(chan, reader.name)
                        chan.close()
                        session.closeChannels()
                        session.close()
                    } else {
                        Log.e("TAG", "${reader.name} No EID Found")
                    }
                } catch (e: Exception) {
                    Log.e("TAG", "Error with reader ${reader.name}", e)
                }
            }
        }
    }

    private fun switchToNext(chan: Channel, name: String) {
        val notificationResponse = transmitContinued(chan, "81e2910003bf2800")
        Log.e("TAG", "notificationResponse: $notificationResponse")
        val pendingDeleteList = mutableListOf<Triple<String, String, Pair<String, String>>>()
        var index = notificationResponse.indexOf("bf2f")
        while (index != -1) {
            index += 4
            val blockLengthHex = notificationResponse.substring(index, index + 2)
            val blockLength = blockLengthHex.toInt(16) * 2
            index += 2
            val blockData = notificationResponse.substring(index, index + blockLength)
            index += blockLength

            var blockIndex = 0
            var _number = ""
            var number = ""
            var code = ""
            var smdp = ""
            var iccid = ""
            while (blockIndex < blockData.length) {
                when (blockData.substring(blockIndex, blockIndex + 2)) {
                    "80" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        _number = blockData.substring(blockIndex - 4, blockIndex + length)
                        number = blockData.substring(blockIndex, blockIndex + length)
                        blockIndex += length
                    }
                    "81" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        code = blockData.substring(blockIndex, blockIndex + length)
                        blockIndex += length
                    }
                    "0c" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        smdp = blockData.substring(blockIndex, blockIndex + length)
                        blockIndex += length
                    }
                    "5a" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) *  2
                        blockIndex += 2
                        iccid = blockData.substring(blockIndex, blockIndex + length)
                        blockIndex += length
                    }
                    else -> break
                }
            }
            if (code == "0640" || code == "0520") {
                pendingDeleteList.add(Triple(_number, iccid, Pair(smdp, number)))
            }
            index = notificationResponse.indexOf("bf2f", index)
        }

        pendingDeleteList.forEach { pendingDelete ->
            val deleteResponse = transmitContinued(chan,
                "81e29100" +
                        (pendingDelete.first.length / 2 + 3).toString(16).padStart(2, '0') +
                        "BF30" +
                        (pendingDelete.first.length / 2).toString(16).padStart(2, '0') +
                        pendingDelete.first
            )
            Log.i("MainActivity", "Deleting #${pendingDelete.third.second} [${pendingDelete.third.first}] Delete Response: $deleteResponse")
        }

        val hexString = transmitContinued(chan, "81E2910008BF2D055C035A9F70")
        Log.e("MainActivity", hexString)
        val parts = hexString.split("e310")
        val result = mutableListOf<Pair<String, Boolean>>()
        parts.drop(1).forEach { part ->
            val lengthByteIndex = part.indexOf("5a") + 2
            if (lengthByteIndex >= 2) {
                val lengthByteHex = part.substring(lengthByteIndex, lengthByteIndex + 2)
                val length = lengthByteHex.toInt(16)
                val s = part.substring(lengthByteIndex + 2, lengthByteIndex + 2 + (length * 2))
                val tIndex = part.indexOf("9f7001")
                val t = (part.substring(tIndex + 7, tIndex + 8) == "1")
                result.add(Pair(s, t))
                Log.i("MainActivity", "S: $s T: $t")
            }
        }

        if (result.size > 1) {
            var switchTo = result[0].first
            var isNext = false
            for (row in result) {
                if (isNext) {
                    switchTo = row.first
                    break
                }
                if (row.second) isNext = true
            }
            Log.w("MainActivity", "Switching To: ${swapEveryTwoCharacters(switchTo)}")
            sharedPreferences.edit().putString("next_${name}", swapEveryTwoCharacters(switchTo)).apply()
            transmitContinued(chan, "81e2910014bf3111a00c5a0a" + switchTo + "810100")
        }
    }

    private fun transmitContinued(chan: Channel, apdu: String): String {
        Log.i("TAG", "Transmit: $apdu")
        var txResp: ByteArray = chan.transmit(hexStringToByteArray(apdu))
        var combinedResp = txResp.sliceArray(0 until txResp.size - 2)
        while (txResp[txResp.size - 2].toInt() == 0x6a) {
            txResp = chan.transmit(byteArrayOf(0x81.toByte(), 0xc0.toByte(), 0, 0, txResp.last()))
            Log.e("TAG", txResp.toHex())
            combinedResp += txResp.sliceArray(0 until txResp.size - 2)
        }
        Log.i("TAG", "Transmit Response: ${combinedResp.toHex()}")
        return combinedResp.toHex()
    }

    private fun updateFABIcon() {
        fab.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun startRecurringTimer() {
        runnable = object : Runnable {
            override fun run() {
                val currentTime = System.currentTimeMillis()
                val timeRemaining = ((sharedPreferences.getLong("nextSwitch", currentTime) - currentTime) / 1000)
                nextSwitch.text = if (isPlaying) "Next switch in $timeRemaining seconds" else "Switching paused."

                for (i in 1..simSlots) {
                    simSlotIds["SIM$i"]?.let { id ->
                        val simSlotN: CheckBox = findViewById(id)
                        if (sharedPreferences.getBoolean("SIM$i", true) != simSlotN.isChecked) {
                            sharedPreferences.edit().putBoolean("SIM$i", simSlotN.isChecked).apply()
                        }
                        simSlotN.text = "SIM$i: ${sharedPreferences.getString("next_SIM$i", "Pending Switch")}"
                    }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable!!)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(runnable!!)
    }

    override fun onResume() {
        super.onResume()
        startRecurringTimer()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}