package com.example.soundtest

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Screen()
            }
        }
    }
}

@Composable
fun Screen() {
    val context = LocalContext.current

    // 음악 플레이어 준비 (raw 폴더의 calm_music.mp3를 반복 재생)
    val player = remember {
        MediaPlayer.create(context, R.raw.calm_music).apply { isLooping = true }
    }
    // 앱 꺼질 때 플레이어 정리
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var direction by remember { mutableStateOf("가운데") }

    // 쏠림 정도: -1 = 완전 왼쪽, 0 = 가운데, +1 = 완전 오른쪽
    var target by remember { mutableFloatStateOf(0f) }
    val strength = 0.6f   // 한쪽으로 얼마나 쏠릴지 (0.6이면 대략 7:3)

    // target이 바뀌면 0.8초 동안 부드럽게 따라감
    val balance by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800),
        label = "balance"
    )

    // 쏠림 값을 왼쪽/오른쪽 볼륨으로 바꾸기
    val leftVol = 1f - maxOf(0f, balance)    // 오른쪽으로 쏠리면 왼쪽이 작아짐
    val rightVol = 1f - maxOf(0f, -balance)  // 왼쪽으로 쏠리면 오른쪽이 작아짐

    // 화면이 바뀔 때마다 실제 플레이어에 볼륨 적용
    SideEffect {
        player.setVolume(leftVol, rightVol)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "곧 방향: $direction", fontSize = 40.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "왼쪽 ${(leftVol * 100).toInt()}%   |   오른쪽 ${(rightVol * 100).toInt()}%",
            fontSize = 28.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(onClick = {
            if (isPlaying) player.pause() else player.start()
            isPlaying = !isPlaying
        }) {
            Text(if (isPlaying) "음악 멈추기" else "음악 재생", fontSize = 24.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row {
            Button(onClick = { direction = "왼쪽"; target = -strength }) {
                Text("좌회전", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(onClick = { direction = "가운데"; target = 0f }) {
                Text("직진", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(20.dp))
            Button(onClick = { direction = "오른쪽"; target = strength }) {
                Text("우회전", fontSize = 24.sp)
            }
        }
    }
}