package io.github.jmorille.zapette

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.jmorille.zapette.data.DeviceExport
import io.github.jmorille.zapette.data.ImportPolicy
import io.github.jmorille.zapette.data.ImportReport
import io.github.jmorille.zapette.data.InvalidExportException
import io.github.jmorille.zapette.data.QrPayload
import io.github.jmorille.zapette.model.Device
import io.github.jmorille.zapette.model.DeviceKind
import io.github.jmorille.zapette.ui.transfer.TransferDependencies
import io.github.jmorille.zapette.ui.transfer.TransferViewModel
import io.github.jmorille.zapette.ui.transfer.qrMatrix
import io.github.jmorille.zapette.ui.transfer.readTextLimited
import io.github.jmorille.zapette.ui.transfer.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private class Deps : TransferDependencies {
        override val devices = MutableStateFlow<List<Device>>(emptyList())
        val imports = mutableListOf<Pair<String, ImportPolicy>>()

        override suspend fun export(ids: Collection<String>?, prettyPrint: Boolean) =
            DeviceExport.encode(devices.value.filter { ids == null || it.id in ids }, prettyPrint)

        override suspend fun import(text: String, policy: ImportPolicy): ImportReport {
            imports += text to policy
            DeviceExport.decode(QrPayload.decode(text))
            return ImportReport(added = 1)
        }
    }

    private val deps = Deps()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun vm(initial: String? = null): TransferViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { TransferViewModel(deps, initial, dispatcher) } })[TransferViewModel::class]

    private fun device(i: Int) = Device("d$i", "Appareil $i", DeviceKind.KODI, "10.0.0.$i", extras = mapOf("wol_mac" to "AA:BB:CC:DD:EE:%02X".format(i)))

    @Test
    fun `qr code de la sélection, et trop dense pour beaucoup d'appareils`() = runTest(dispatcher) {
        deps.devices.value = listOf(device(1), device(2))
        val vm = vm(initial = "d2")
        advanceUntilIdle()
        val qr = vm.state.value.qr!!
        assertEquals(1, qr.count)
        assertEquals(listOf("d2"), DeviceExport.decode(QrPayload.decode(qr.payload!!)).devices.map { it.id })

        vm.select(null)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.qr!!.count)

        // Beaucoup d'appareils aux noms variés : le QR code serait illisible.
        deps.devices.value = (1..200).map { device(it % 250).copy(id = "id-$it-${it * 7919}", name = "Nom ${it * 104729}") }
        advanceUntilIdle()
        assertNull(vm.state.value.qr!!.payload)

        // Appareil sélectionné disparu : retour à « tous ».
        deps.devices.value = listOf(device(1))
        vm.select("d1")
        advanceUntilIdle()
        deps.devices.value = emptyList()
        advanceUntilIdle()
        assertNull(vm.state.value.selectedId)
        assertNull(vm.state.value.qr)
    }

    @Test
    fun `import - politique, rapport et erreurs`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.setReplaceExisting(true)
        vm.importText(DeviceExport.encode(listOf(device(1))))
        advanceUntilIdle()
        assertEquals(ImportPolicy.REPLACE_EXISTING, deps.imports.single().second)
        assertEquals("Import : 1 ajouté(s), 0 mis à jour, 0 ignoré(s), 0 invalide(s)", vm.state.value.message)
        assertFalse(vm.state.value.isError)

        vm.importText("n'importe quoi")
        advanceUntilIdle()
        assertTrue(vm.state.value.isError)

        vm.importFrom { throw IOException("disque") }
        advanceUntilIdle()
        assertEquals("Lecture impossible : disque", vm.state.value.message)

        vm.dismissMessage()
        vm.importFrom { null } // presse-papiers vide
        advanceUntilIdle()
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `export - écrit le JSON indenté`() = runTest(dispatcher) {
        deps.devices.value = listOf(device(1))
        val vm = vm()
        advanceUntilIdle()
        var written: String? = null
        vm.exportTo { written = it }
        advanceUntilIdle()
        assertEquals(1, DeviceExport.decode(written!!).devices.size)
        assertTrue('\n' in written!!)
        assertEquals("1 appareil(s) exporté(s), sans mot de passe ni jeton.", vm.state.value.message)
        assertNotNull(DeviceExport.decode(vm.shareText()))
    }

    @Test
    fun `qr code généré relu par ZXing`() {
        val payload = QrPayload.encode(DeviceExport.encode((1..12).map(::device)))
        val matrix = qrMatrix(payload)
        val source = RGBLuminanceSource(matrix.width, matrix.height, matrix.toArgb())
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true)).text
        assertEquals(payload, text)
    }

    @Test
    fun `lecture bornée d'un fichier`() {
        assertEquals("abc", ByteArrayInputStream("abc".toByteArray()).readTextLimited(3))
        try {
            ByteArrayInputStream(ByteArray(10)).readTextLimited(9)
            fail("fichier trop gros accepté")
        } catch (_: InvalidExportException) {
        }
    }
}
