package id.armagic.virtualvideo

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import id.armagic.virtualvideo.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener

            updateShizukuStatus()

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                binding.resultText.text =
                    "SHIZUKU PERMISSION: DENIED\n\nIzinkan Vir Vid 7 di Shizuku lalu tekan SCAN lagi."
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            runOnUiThread { updateShizukuStatus() }
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            runOnUiThread { updateShizukuStatus() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.scanButton.setOnClickListener {
            requestOrScan()
        }

        updateShizukuStatus()
    }

    private fun updateShizukuStatus() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        if (!alive) {
            binding.shizukuStatusText.text = "Shizuku: TIDAK AKTIF"
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)

        binding.shizukuStatusText.text =
            if (granted) {
                "Shizuku: AKTIF • GRANTED • UID $uid"
            } else {
                "Shizuku: AKTIF • BELUM DIIZINKAN • UID $uid"
            }
    }

    private fun requestOrScan() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            binding.resultText.text =
                "Shizuku belum aktif.\n\nJalankan Shizuku dulu, lalu kembali ke Vir Vid 7."
            updateShizukuStatus()
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!granted) {
            runCatching {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
            }.onFailure {
                binding.resultText.text =
                    "Gagal meminta permission Shizuku:\n${it.javaClass.simpleName}: ${it.message}"
            }
            return
        }

        startScan()
    }

    private fun startScan() {
        binding.scanButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.resultText.text =
            "SCANNING...\n\ncameraopt\ncaptctrl\nframework/vendor classes\nbinder descriptors"

        thread(name = "vendor-camera-scan") {
            val report = buildFullReport()

            runOnUiThread {
                binding.resultText.text = report
                binding.progressBar.visibility = android.view.View.GONE
                binding.scanButton.isEnabled = true
                updateShizukuStatus()
            }
        }
    }

    private fun buildFullReport(): String {
        val reflectionReport = scanCandidateClasses()

        val shellScript = """
            echo '=== SERVICE MATCHES ==='
            service list 2>&1 | grep -Ei 'camera|cameraopt|captctrl|mediatek|miui' || true

            echo
            echo '=== CAMERAOPT ==='
            service check cameraopt 2>&1 || true
            dumpsys cameraopt 2>&1 | head -n 180 || true

            echo
            echo '=== CAPTCTRL ==='
            service check captctrl 2>&1 || true
            dumpsys captctrl 2>&1 | head -n 180 || true

            echo
            echo '=== RELEVANT PACKAGES ==='
            pm list packages 2>&1 | grep -Ei 'camera|mediatek|miui' | head -n 120 || true

            echo
            echo '=== RELEVANT PROPERTIES ==='
            getprop 2>&1 | grep -Ei 'camera|mediatek|mtk|miui' | head -n 140 || true
        """.trimIndent()

        val shellResult = runShell(shellScript)

        val highValue = findHighValueLines(
            reflectionReport + "\n" + shellResult.output + "\n" + shellResult.error
        )

        return buildString {
            appendLine("=== VIR VID 7 • VENDOR CAMERA API SCAN ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} • API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Shizuku UID: ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
            appendLine()

            appendLine("=== HIGH-VALUE MATCHES ===")
            if (highValue.isEmpty()) {
                appendLine("NONE")
            } else {
                highValue.forEach { appendLine(it) }
            }

            appendLine()
            appendLine(reflectionReport)

            appendLine()
            appendLine("=== SHIZUKU SHELL SCAN ===")
            appendLine("Exit: ${shellResult.exitCode}")
            if (shellResult.output.isNotBlank()) {
                appendLine(shellResult.output.trim())
            }
            if (shellResult.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shellResult.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun scanCandidateClasses(): String {
        val candidates = listOf(
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager",
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager\\$Stub",
            "com.xiaomi.camera.CameraManager",
            "com.xiaomi.camera.CameraOptManager",
            "com.mediatek.capctrl.aidl.IMtkCapCtrl",
            "com.mediatek.capctrl.aidl.IMtkCapCtrl\\$Stub",
            "com.mediatek.camera.common.device.CameraDeviceManager",
            "com.mediatek.camera.common.device.v2.Camera2DeviceManager"
        )

        return buildString {
            appendLine("=== FRAMEWORK / VENDOR CLASS REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[$className]")

                val clazz = runCatching {
                    Class.forName(className)
                }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP")
                    continue
                }

                appendLine("CLASS: FOUND")

                val methods: List<Method> = runCatching {
                    clazz.declaredMethods
                        .toList()
                        .sortedWith(
                            compareBy<Method>(
                                { it.name.lowercase() },
                                { it.parameterCount }
                            )
                        )
                }.getOrElse { emptyList() }

                if (methods.isEmpty()) {
                    appendLine("METHODS: NONE / HIDDEN")
                    continue
                }

                methods.take(120).forEach { method ->
                    appendLine(
                        "METHOD: ${method.returnType.simpleName} " +
                            "${method.name}(" +
                            method.parameterTypes.joinToString(", ") { it.simpleName } +
                            ")"
                    )
                }

                if (methods.size > 120) {
                    appendLine("... ${methods.size - 120} method lain dipotong")
                }
            }
        }
    }

    private fun findHighValueLines(text: String): List<String> {
        val keywords = listOf(
            "inject",
            "virtual",
            "external",
            "replace",
            "redirect",
            "override",
            "setsource",
            "set_source",
            "inputsurface",
            "input_surface",
            "setsurface",
            "set_surface",
            "streamsource",
            "stream_source"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keywords.any { keyword ->
                        line.lowercase().contains(keyword)
                    }
            }
            .distinct()
            .take(80)
            .toList()
    }

    private fun runShell(script: String): ShellResult {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )

            method.isAccessible = true

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", script),
                null,
                null
            ) as Process

            val stdout = BufferedReader(
                InputStreamReader(process.inputStream)
            ).readText()

            val stderr = BufferedReader(
                InputStreamReader(process.errorStream)
            ).readText()

            val exit = process.waitFor()

            ShellResult(
                exitCode = exit,
                output = stdout,
                error = stderr
            )
        } catch (t: Throwable) {
            ShellResult(
                exitCode = -1,
                output = "",
                error = "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 7007
    }
}
