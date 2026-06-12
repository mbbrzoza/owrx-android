package pl.sp8mb.owrx.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import pl.sp8mb.owrx.ui.theme.OwrxTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OwrxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Text(
                        text = "OWRX Mobile — szkielet (M0)",
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}
