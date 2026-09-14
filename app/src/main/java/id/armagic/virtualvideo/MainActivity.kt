package id.armagic.virtualvideo

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import id.armagic.virtualvideo.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_SHIZUKU) {
                return@OnRequestPermissionResultListener
            }

            updateShizukuStatus()

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startInspect()
            } else {
                binding.resultText.text =
                    "SHIZUKU PERMISSION: DENIED\n\nIzinkan Vir Vid 8 di Shizuku lalu tekan INSPECT lagi."
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

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.scanButton.setOnClickListener {
            requestOrInspect()
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
                "Shizuku: AKTIF • GRANTED • UID " + uid
            } else {
                "Shizuku: AKTIF • BELUM DIIZINKAN • UID " + uid
            }
    }

    private fun requestOrInspect() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        if (!alive) {
            binding.resultText.text =
                "Shizuku belum aktif.\n\nJalankan Shizuku lalu kembali ke Vir Vid 8."
            updateShizukuStatus()
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!granted) {
            runCatching {
                Shizuku.requestPermission(REQUEST_SHIZUKU)
            }.onFailure { error ->
                binding.resultText.text =
                    "Gagal meminta permission Shizuku:\n" +
                        error.javaClass.simpleName + ": " + error.message
            }
            return
        }

        startInspect()
    }

    private fun startInspect() {
        binding.scanButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.resultText.text =
            "INSPECTING CAMERAOPT...\n\nMembaca Binder descriptor, class, fields transaction, dan lokasi framework."

        thread(name = "cameraopt-inspector") {
            val report = buildReport()

            runOnUiThread {
                binding.resultText.text = report
                binding.progressBar.visibility = View.GONE
                binding.scanButton.isEnabled = true
                updateShizukuStatus()
            }
        }
    }

    private fun buildReport(): String {
        val reflection = inspectClasses()
        val shell = runShell(buildShellScript())
        val combined = reflection + "\n" + shell.output + "\n" + shell.error
        val interesting = extractInteresting(combined)

        return buildString {
            appendLine("=== VIR VID 8 • CAMERAOPT BINDER INSPECTOR ===")
            appendLine("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
            appendLine(
                "Android: " + android.os.Build.VERSION.RELEASE +
                    " • API " + android.os.Build.VERSION.SDK_INT
            )
            appendLine("Shizuku UID: " + runCatching { Shizuku.getUid() }.getOrDefault(-1))
            appendLine()

            appendLine("=== IMPORTANT FINDINGS ===")
            if (interesting.isEmpty()) {
                appendLine("NONE")
            } else {
                interesting.forEach { line -> appendLine(line) }
            }

            appendLine()
            appendLine(reflection)

            appendLine()
            appendLine("=== SHIZUKU CAMERAOPT INSPECTION ===")
            appendLine("Exit: " + shell.exitCode)

            if (shell.output.isNotBlank()) {
                appendLine(shell.output.trim())
            }

            if (shell.error.isNotBlank()) {
                appendLine()
                appendLine("--- STDERR ---")
                appendLine(shell.error.trim())
            }

            appendLine()
            appendLine("=== DONE ===")
        }
    }

    private fun buildShellScript(): String {
        val dollar = "$"

        return listOf(
            "echo '=== SERVICE ENTRY ==='",
            "service list 2>&1 | grep -i cameraopt || true",
            "service check cameraopt 2>&1 || true",
            "",
            "echo '=== STANDARD BINDER INTERFACE_TRANSACTION ==='",
            "service call cameraopt 1598968902 2>&1 || true",
            "",
            "echo '=== DUMPSYS CAMERAOPT ==='",
            "dumpsys cameraopt 2>&1 | head -n 220 || true",
            "",
            "echo '=== CANDIDATE FILES ==='",
            "for d in /system/framework /system_ext/framework /product/framework /vendor/framework /system_ext/priv-app /product/priv-app /vendor/app; do",
            "  [ -d \"" + dollar + "d\" ] || continue",
            "  find \"" + dollar + "d\" -maxdepth 3 -type f \\( -iname '*cameraopt*' -o -iname '*camera*opt*' -o -iname '*miui*camera*' \\) 2>/dev/null | head -n 120",
            "done",
            "",
            "echo '=== INTERFACE STRING LOCATIONS ==='",
            "for d in /system/framework /system_ext/framework /product/framework /vendor/framework; do",
            "  [ -d \"" + dollar + "d\" ] || continue",
            "  grep -R -a -l -m 1 'com.miui.cameraopt.ICameraOptManager' \"" + dollar + "d\" 2>/dev/null | head -n 80",
            "done",
            "",
            "echo '=== PACKAGE PATH HINTS ==='",
            "pm list packages -f 2>&1 | grep -Ei 'cameraopt|miui.*camera|camera.*miui' | head -n 100 || true"
        ).joinToString("\n")
    }

    private fun inspectClasses(): String {
        val dollar = "$"

        val candidates = listOf(
            "com.miui.cameraopt.ICameraOptManager",
            "com.miui.cameraopt.ICameraOptManager" + dollar + "Stub",
            "com.miui.cameraopt.ICameraOptManager" + dollar + "Stub" + dollar + "Proxy",
            "com.miui.cameraopt.MiuiCameraManager",
            "com.miui.cameraopt.CameraOptManager"
        )

        return buildString {
            appendLine("=== JAVA REFLECTION ===")

            for (className in candidates) {
                appendLine()
                appendLine("[" + className + "]")

                val clazz = runCatching { Class.forName(className) }.getOrNull()

                if (clazz == null) {
                    appendLine("CLASS: NOT EXPOSED TO APP CLASSLOADER")
                    continue
                }

                appendLine("CLASS: FOUND")
                appendLine("SUPER: " + (clazz.superclass?.name ?: "-"))

                val interfaces = clazz.interfaces
                    .joinToString(", ") { it.name }
                    .ifBlank { "-" }

                appendLine("INTERFACES: " + interfaces)

                val fields: List<Field> = runCatching {
                    clazz.declaredFields.toList().sortedBy { it.name }
                }.getOrElse { emptyList() }

                if (fields.isNotEmpty()) {
                    appendLine("FIELDS:")

                    fields.take(160).forEach { field ->
                        val staticValue =
                            if (Modifier.isStatic(field.modifiers)) {
                                runCatching {
                                    field.isAccessible = true
                                    field.get(null)?.toString()
                                }.getOrNull()
                            } else {
                                null
                            }

                        var line = "  " + field.type.simpleName + " " + field.name

                        if (staticValue != null) {
                            line += " = " + staticValue
                        }

                        appendLine(line)
                    }
                }

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

                if (methods.isNotEmpty()) {
                    appendLine("METHODS:")

                    methods.take(180).forEach { method ->
                        val args = method.parameterTypes.joinToString(", ") { it.simpleName }

                        appendLine(
                            "  " + method.returnType.simpleName +
                                " " + method.name + "(" + args + ")"
                        )
                    }
                }
            }
        }
    }

    private fun extractInteresting(text: String): List<String> {
        val keys = listOf(
            "descriptor",
            "transaction_",
            "cameraopt",
            "virtual",
            "inject",
            "external",
            "source",
            "surface",
            "stream",
            "open",
            "close"
        )

        return text
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    keys.any { key -> line.lowercase().contains(key) }
            }
            .filterNot { it.startsWith("package:") }
            .distinct()
            .take(120)
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

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()

            ShellResult(exitCode, stdout, stderr)
        } catch (error: Throwable) {
            ShellResult(
                -1,
                "",
                error.javaClass.name + ": " + error.message
            )
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
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
        private const val REQUEST_SHIZUKU = 8008
    }
}
