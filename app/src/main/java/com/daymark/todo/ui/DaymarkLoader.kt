package com.daymark.todo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Vector motion only; no GIF, bitmap frames or animation dependency. */
@Composable
fun DaymarkLoader(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary,label:String="Loading") {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scope=rememberCoroutineScope()
    val motionScale=scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val moving=lifecycle.isAtLeast(Lifecycle.State.STARTED) && motionScale>0f
    val angle=if(moving) {
        val transition=rememberInfiniteTransition(label="Daymark loading")
        val rotation by transition.animateFloat(0f,360f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="Loading orbit")
        rotation
    } else 0f
    Canvas(modifier.size(28.dp).semantics {
        contentDescription=label
        progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
    }) {
        val width=size.minDimension*.09f
        val inset=width/2
        val ring=androidx.compose.ui.geometry.Size(size.width-width,size.height-width)
        val origin=androidx.compose.ui.geometry.Offset(inset,inset)
        drawArc(color.copy(alpha=.14f),0f,360f,false,origin,ring,style=Stroke(width,cap=StrokeCap.Round))
        rotate(angle) {
            drawArc(color, -90f,245f,false,origin,ring,style=Stroke(width,cap=StrokeCap.Round))
            drawCircle(color,radius=width*.65f,center=androidx.compose.ui.geometry.Offset(size.width/2,inset))
        }
    }
}
