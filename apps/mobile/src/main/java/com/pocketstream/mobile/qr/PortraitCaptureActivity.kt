package com.pocketstream.mobile.qr

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.pocketstream.mobile.R

/**
 * Custom QR code capture activity locked to Portrait orientation.
 * Displays a continuous background scanning progress indicator while camera is active,
 * and immediately transitions to a detected progress state the moment a QR code is recognized.
 */
class PortraitCaptureActivity : AppCompatActivity() {

    private lateinit var capture: CaptureManager
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var pbScanning: ProgressBar
    private lateinit var tvScanningStatus: TextView
    private lateinit var layoutScanningOverlay: View
    private lateinit var tvDetectedMessage: TextView
    private var isQrProcessed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_portrait_capture)

        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        pbScanning = findViewById(R.id.pbScanning)
        tvScanningStatus = findViewById(R.id.tvScanningStatus)
        layoutScanningOverlay = findViewById(R.id.layoutScanningOverlay)
        tvDetectedMessage = findViewById(R.id.tvDetectedMessage)

        findViewById<Button>(R.id.btnCancelScan)?.setOnClickListener {
            finish()
        }

        // Subclass CaptureManager to hook the exact moment a QR code is detected
        capture = object : CaptureManager(this, barcodeView) {
            override fun returnResult(rawResult: BarcodeResult?) {
                if (isQrProcessed) return
                isQrProcessed = true

                runOnUiThread {
                    tvScanningStatus.text = "QR code detected! Connecting to TV..."
                    layoutScanningOverlay.visibility = View.VISIBLE
                    tvDetectedMessage.text = "QR Code Detected! Pairing..."
                }

                // Show the detected progress feedback briefly before returning result to MainActivity
                barcodeView.postDelayed({
                    val intent = resultIntent(rawResult, null)
                    setResult(RESULT_OK, intent)
                    closeAndFinish()
                }, 300)
            }
        }

        capture.initializeFromIntent(intent, savedInstanceState)
        capture.decode()
    }

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}
