package ee.nekoko.revolver

import android.content.Context
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import android.util.Log
import androidx.appcompat.app.AppCompatActivity.MODE_PRIVATE
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
fun hexStringToByteArray(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have an even length" }

    return ByteArray(hex.length / 2) { i ->
        val index = i * 2
        hex.substring(index, index + 2).toInt(16).toByte()
    }
}
fun swapEveryTwoCharacters(input: String): String {
    val result = StringBuilder()

    // Iterate through the string in steps of 2
    for (i in 0 until input.length step 2) {
        // Ensure that we don't go out of bounds
        if (i + 1 < input.length) {
            // Swap characters at position i and i+1
            result.append(input[i + 1])
            result.append(input[i])
        } else {
            // If the string has an odd length, append the last character as is
            result.append(input[i])
        }
    }
    return result.toString()
}

class SwitchWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    var _seService: SEService? = null

    override fun doWork(): Result {
        val lock = Mutex()
        var service: SEService? = null
        val callback = {
            runBlocking {
                lock.withLock {
                    Log.d("TAG", "SE service is connected!")
                    _seService = service
                    listReaders(service!!)
                }
            }
        }

        if (_seService == null) {
            runBlocking {
                lock.withLock {
                    try {
                        service = SEService(applicationContext, { it.run() }, callback)
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            listReaders(_seService!!)
        }

        val sharedPreferences = applicationContext.getSharedPreferences("eSimPreferences", MODE_PRIVATE)
        val intervalInSeconds = sharedPreferences.getLong("interval", 10 * 60)
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SwitchWorker>().setInitialDelay(intervalInSeconds, TimeUnit.SECONDS).build()
        WorkManager.getInstance(applicationContext).enqueue(oneTimeWorkRequest)

        val nextSwitchTime = System.currentTimeMillis() + intervalInSeconds * 1000
        val editor = sharedPreferences.edit()
        editor.putLong("nextSwitch", nextSwitchTime)
        editor.apply()
        return Result.success()
    }

    fun transmitContinued(chan: Channel, apdu: String): String {
        Log.i(TAG,"Transmit: ${apdu}")
        var txResp: ByteArray = chan.transmit(hexStringToByteArray(apdu))
        var combinedResp = txResp.sliceArray(0 until txResp.size - 2)
        while (txResp[txResp.size - 2].toInt() == 0x6a) {
            txResp = chan.transmit(byteArrayOf(0x81.toByte(), 0xc0.toByte(), 0, 0, txResp.last()))
            Log.e(TAG, txResp.toHex())
            combinedResp += txResp.sliceArray(0 until txResp.size - 2)
        }
        Log.i(TAG,"Transmit Response: ${combinedResp.toHex()}")
        return combinedResp.toHex()
    }


    fun switchToNext(chan: Channel, name: String) {


        // '81e2910014bf3111a00c5a0a 984474560000309140f1 81 01 01'

        // notifications: '81e2910003bf2800'
        // response:
        // 81e2910003bf2800
        var notificationResponse = transmitContinued(chan, "81e2910003bf2800")
        Log.e(TAG, "notificationResponse: $notificationResponse")
        var index = notificationResponse.indexOf("bf2f")
        var pendingDeleteList =mutableListOf<Triple<String, String, Pair<String, String>>>()
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
                        val lengthHex = blockData.substring(blockIndex, blockIndex + 2)
                        val length = lengthHex.toInt(16) * 2
                        blockIndex += 2
                        _number = blockData.substring(blockIndex - 4, blockIndex + length)
                        number = blockData.substring(blockIndex, blockIndex + length)
                        // println("80 Tag - L: $length, Number: $_number ($number)")
                        blockIndex += length
                    }
                    "81" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        code = blockData.substring(blockIndex, blockIndex + length)
                        // println("81 Tag - L: $length, Code: $code")
                        blockIndex += length
                    }
                    "0c" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        smdp = blockData.substring(blockIndex, blockIndex + length)
                        // println("0C Tag - L: $length, SMDP: $smdp")
                        blockIndex += length
                    }
                    "5a" -> {
                        blockIndex += 2
                        val length = blockData.substring(blockIndex, blockIndex + 2).toInt(16) * 2
                        blockIndex += 2
                        iccid = blockData.substring(blockIndex, blockIndex + length)
                        // println("5A Tag - L: $length, ICCID: $iccid")
                        blockIndex += length
                    }
                    else -> {
                        // println("Unknown tag encountered, stopping parsing")
                        break
                    }
                }
            }
            if (code == "0640" || code == "0520") {
                pendingDeleteList.add(Triple(_number, iccid, Pair(smdp, number)))
            }

            // Look for the next `bf2f` block
            index = notificationResponse.indexOf("bf2f", index)
        }

        for(pendingDelete in pendingDeleteList) {
            val deleteResponse = transmitContinued(chan,
                "81e29100" +
                        (pendingDelete.first.length / 2 + 3).toString(16).padStart(2, '0')
                        + "BF30" +
                        (pendingDelete.first.length / 2).toString(16).padStart(2, '0')
                        + pendingDelete.first
            )
            Log.i(TAG, "Deleting #${pendingDelete.third.second} [${pendingDelete.third.first}] Delete Response: $deleteResponse")
        }


        // delete a notification:
        // 81e2910006 bf30 03 80 01 22


        val hexString = transmitContinued(chan, "81E2910008BF2D055C035A9F70")
        Log.e(TAG, hexString)
        val parts = hexString.split("e310")
        val result = mutableListOf<Pair<String, Boolean>>()
        for (part in parts.drop(1)) {
            val lengthByteIndex = part.indexOf("5a") + 2
            if (lengthByteIndex >= 2) {
                val lengthByteHex = part.substring(lengthByteIndex, lengthByteIndex + 2)
                val length = lengthByteHex.toInt(16)
                val s = part.substring(lengthByteIndex + 2, lengthByteIndex + 2 + (length * 2))
                val tIndex = part.indexOf("9f7001")
                val t = (part.substring(tIndex + 7, tIndex + 8) == "1")
                result.add(Pair(s, t))
                Log.i(TAG,"S: $s T: $t")
            }
        }

        if (result.size > 1) {
            var switchTo = result[0].first
            var isNext = false
            for(row in result) {
                if (isNext) {
                    switchTo = row.first
                    break
                }
                if (row.second) isNext = true
            }
            Log.w(TAG, "Switching To: ${swapEveryTwoCharacters(switchTo)}")
            val sharedPreferences = applicationContext.getSharedPreferences("eSimPreferences", MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString("next_${name}", swapEveryTwoCharacters(switchTo))
            editor.apply()
            transmitContinued(chan, "81e2910014bf3111a00c5a0a" + switchTo + "810101")
        }


        /*
        bf28820167a0820163





        bf 2f [length] 80 [length] [number] 81 [02] [0780 / 0640 / 0520?] 0c [length] [smdp] 5a0a [iccid]
        bf2f 1c 80 01 13 81 02 0780 0c 07 736d64702e696f 5a0a 984474560000309140f1
        bf2f1c800114810207800c07736d64702e696f 5a0a984474560000309161f5
        bf2f1c800115810207800c07736d64702e696f5a0a984474560000309102f7
        bf2f1c800116810207800c07736d64702e696f5a0a984474560000309143f8
       bf2f2580011c810205200c107273702e74727570686f6e652e636f6d5a0a984474560000309140f1
        bf2f2580011d810206400c107273702e74727570686f6e652e636f6d5a0a984474560000300175f8
        bf2f2580011e810205200c107273702e74727570686f6e652e636f6d5a0a984474560000300175f8
        bf2f1c80011f810207800c07736d64702e696f5a0a984474560000302818f7
        bf2f25800120810206400c107273702e74727570686f6e652e636f6d5a0a984474560000302818f7
        bf2f25800121810205200c107273702e74727570686f6e652e636f6d5a0a984474560000302818f7
         */


    }

    fun listReaders(seService: SEService) {
        val sharedPreferences = applicationContext.getSharedPreferences("eSimPreferences", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        Log.d(TAG, "ListReaders Task executed at: ${System.currentTimeMillis()}")
        if (seService.isConnected) {
            for (reader in seService.readers) {
                if (sharedPreferences.getBoolean(reader.name, true) == false) {
                    Log.d(TAG, "Skipping ${reader.name}")
                    continue
                }
                try {
                    reader.closeSessions()
                    val session: Session = reader.openSession()
                    session.closeChannels()

                    val atr = session.getATR()
                    Log.i(TAG, reader.name + " ATR: " + atr + " Session: " + session)
                    val chan = session.openLogicalChannel(hexStringToByteArray("A0000005591010FFFFFFFF8900000100"))!!
                    Log.i(TAG, reader.name + " Opened Channel")
                    val response: ByteArray = chan.getSelectResponse()!!
                    Log.i(TAG,"Opened logical channel: ${response}")
                    val resp1 = chan.transmit(hexStringToByteArray("81E2910006BF3E035C015A"))
                    Log.i(TAG,"Transmit Response: ${resp1.toHex()}")
                    if (resp1[0] == 0xbf.toByte()) {
                        try {
                            Log.i(
                                TAG,
                                "Slot: ${reader.name} EID: ${
                                    resp1.toHex().substring(10, 10 + 32)
                                }"
                            )
                            switchToNext(chan, reader.name)
                            chan.close()
                            session.closeChannels()
                            session.close()
                        } catch (e: Exception) {
                            Log.e(TAG,"Error when switching", e)
                        }
                    } else {
                        Log.e(TAG,"Slot: ${reader.name} No EID Found")
                        editor.putString("next_${reader.name}", "No EID Found")
                        editor.apply()
                    }
                } catch (e: SecurityException) {
                    Log.e(
                        TAG,
                        "Slot ${reader.name} ARA-M not supported"
                    )
                    editor.putString("next_${reader.name}", "ARA-M not supported")
                    editor.apply()
                    // throw e
                } catch (e: IOException) {
                    Log.e(
                        TAG,
                        "Slot ${reader.name} Card unavailable"
                    )
                    editor.putString("next_${reader.name}", "Card unavailable")
                    editor.apply()
                } catch (e: NullPointerException) {
                    Log.e(
                        TAG,
                        "Slot ${reader.name} failed. Unable to open a connection", e
                    )
                    editor.putString("next_${reader.name}", "Unable to open a connection")
                    editor.apply()
                    // throw e
                } catch (e: NoSuchElementException) {
                    Log.e(
                        TAG,
                        "Slot ${reader.name} failed: NoSuchElementException [EX]"
                    )
                    editor.putString("next_${reader.name}", "NoSuchElementException")
                    editor.apply()
                    // throw e
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Slot ${reader.name} failed. [EX]"
                    )
                    editor.putString("next_${reader.name}", "Failed")
                    editor.apply()
                }
            }
        }
    }
    companion object {
        private val TAG = SwitchWorker::class.java.name
    }
}