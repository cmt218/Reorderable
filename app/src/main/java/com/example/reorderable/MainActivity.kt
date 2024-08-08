package com.example.reorderable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.reorderable.ui.theme.ReorderableTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReorderableTheme {
                val state = rememberLazyListState()
                ReorderLazyColumn(state = state) {
                    for (i in 1..20) {
                        if (i % 2 == 0) {
                            fixedItem("$i") {
                                Text(
                                    text = "fixed $i",
                                    modifier = Modifier
                                        .height(32.dp)
                                        .fillMaxWidth()
                                )
                            }
                        } else {
                            reorderItem("$i") {
                                Text(
                                    text = "reorderable $i",
                                    modifier = Modifier
                                        .height(32.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ReorderableTheme {
        Greeting("Android")
    }
}