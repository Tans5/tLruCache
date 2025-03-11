package com.tans.tlrucache.demo

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tans.tlrucache.memory.LruByteArrayPool

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bytesPool = LruByteArrayPool(1024L)

        // Get a bytearray, it's size is 10
        val value = bytesPool.get(10)
        val byteArray = value.value

        // Use byteArray to do something.
        // ...

        // When finish using byteArray, recycle it.
        bytesPool.put(value)

        bytesPool.release()
    }
}