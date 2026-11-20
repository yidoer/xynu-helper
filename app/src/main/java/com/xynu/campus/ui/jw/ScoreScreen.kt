package com.xynu.campus.ui.jw

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.data.model.ScoreItem
import com.xynu.campus.ui.common.AppTopBar
import com.xynu.campus.ui.common.EmptyHint
import com.xynu.campus.ui.common.ErrorHint
import com.xynu.campus.ui.common.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        if (state.loggedIn && state.terms.isEmpty()) {
            vm.loadTerms()
        }
    }

    Scaffold(
        containerColor = Color(0xFFF5F6FA),
        topBar = {
            AppTopBar(
                title = "成绩查询",
                onBack = onBack
            )
        }
    ) { padding ->
        when {
            !state.loggedIn -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🔒", fontSize = 40.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("请先登录教务系统", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedLogin) { Text("去登录") }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                // 学期选择（横滑；选中项自动滚到可见区）
                if (state.terms.isNotEmpty()) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val selectedIndex = state.terms.indexOfFirst {
                        it.xnm == state.selectedTerm?.xnm && it.xqm == state.selectedTerm?.xqm
                    }.coerceAtLeast(0)
                    LaunchedEffect(selectedIndex, state.terms.size) {
                        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
                    }
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.terms, key = { it.xnm + it.xqm }) { term ->
                            val selected = state.selectedTerm?.xnm == term.xnm && state.selectedTerm?.xqm == term.xqm
                            FilterChip(
                                selected = selected,
                                onClick = { if (!selected) vm.selectTerm(term) },
                                label = { Text(term.label, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                when {
                    state.loadingScores -> LoadingBox(Modifier.weight(1f), "正在查询成绩…")
                    state.scoreError != null -> ErrorHint(state.scoreError!!, onRetry = { state.selectedTerm?.let { vm.loadScores(it) } }, modifier = Modifier.weight(1f))
                    state.scores.isEmpty() -> EmptyHint("该学期暂无成绩", Modifier.weight(1f))
                    else -> ScoreList(state.scores, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ScoreList(scores: List<ScoreItem>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(scores, key = { it.courseName + it.semester + it.score }) { s ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.courseName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1C1E))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildList {
                                if (s.credit.isNotBlank()) add("学分 ${s.credit}")
                                if (s.courseNature.isNotBlank()) add(s.courseNature)
                            }.joinToString(" · "),
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93)
                        )
                        if (s.remark.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text("备注: ${s.remark}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // 成绩
                    val scoreText = s.score
                    val isPass = scoreText.toFloatOrNull()?.let { it >= 60 } ?: (scoreText != "不及格" && scoreText.isNotBlank())
                    val color = when {
                        scoreText.contains("优秀") || scoreText.contains("良好") || scoreText.toFloatOrNull()?.let { it >= 90 } == true -> Color(0xFF2E7D32)
                        !isPass -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFB3261E)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (scoreText.isBlank()) "—" else scoreText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                        if (s.gpa.isNotBlank() && s.gpa != "0") {
                            Text("GPA ${s.gpa}", fontSize = 10.sp, color = Color(0xFF8E8E93))
                        }
                    }
                }
            }
        }
    }
}