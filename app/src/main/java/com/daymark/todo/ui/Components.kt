package com.daymark.todo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Spacing and shape tokens shared by every screen, so alignment and rhythm are the same everywhere. */
internal object Ui {
    val Gutter=16.dp          // screen side padding
    val Section=20.dp         // gap between groups
    val Item=12.dp            // gap between list items / fields
    val Tight=8.dp            // gap inside a group
    val Field=RoundedCornerShape(14.dp)
    val Card=RoundedCornerShape(18.dp)
    val WhatsApp=Color(0xFF1FA855)
    const val SearchDebounceMillis=300L
}

/** One text field style for the whole app. */
@Composable internal fun AppTextField(value:String,onChange:(String)->Unit,label:String,modifier:Modifier=Modifier,
    singleLine:Boolean=true,minLines:Int=1,keyboard:KeyboardOptions=KeyboardOptions.Default,placeholder:String?=null,
    error:Boolean=false,supporting:String?=null,enabled:Boolean=true,fill:Boolean=true) {
    OutlinedTextField(value,onChange,(if(fill) Modifier.fillMaxWidth() else Modifier).then(modifier),enabled=enabled,label={Text(label)},
        placeholder=placeholder?.let {{Text(it)}},singleLine=singleLine,minLines=if(singleLine) 1 else minLines,
        keyboardOptions=keyboard,isError=error,supportingText=supporting?.let {{Text(it)}},shape=Ui.Field)
}

/**
 * Search box that reports the typed text only after [Ui.SearchDebounceMillis] of silence, so lists query once per
 * pause rather than once per keystroke. [committed] is the value currently applied; an external reset to "" clears the box.
 */
@Composable internal fun DebouncedSearchField(committed:String,placeholder:String,maxLength:Int,onSearch:(String)->Unit,modifier:Modifier=Modifier) {
    var text by remember {mutableStateOf(committed)}
    var lastSent by remember {mutableStateOf(committed)}
    LaunchedEffect(committed) {if(committed!=lastSent) {text=committed;lastSent=committed}}
    LaunchedEffect(text) {
        if(text==lastSent) return@LaunchedEffect
        delay(Ui.SearchDebounceMillis)
        lastSent=text;onSearch(text)
    }
    OutlinedTextField(value=text,onValueChange={text=it.take(maxLength)},modifier=modifier.fillMaxWidth(),singleLine=true,shape=Ui.Field,
        placeholder={Text(placeholder,maxLines=1,overflow=TextOverflow.Ellipsis)},
        leadingIcon={Icon(DaymarkIcons.Search,null)},
        trailingIcon={if(text.isNotEmpty()) TipIconButton(DaymarkIcons.Close,"Clear search",{text="";lastSent="";onSearch("")})},
        colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)))
}

@Composable internal fun SectionLabel(text:String,modifier:Modifier=Modifier) {
    Text(text,modifier,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
}

/** Label on the left, value right-aligned: keeps amounts and dates in one column. */
@Composable internal fun KeyValueRow(label:String,value:String,modifier:Modifier=Modifier,emphasis:Color?=null) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Ui.Item)) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.SemiBold,color=emphasis ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun StatusPill(text:String,tint:Color,modifier:Modifier=Modifier) {
    Surface(modifier,color=tint.copy(alpha=.12f),shape=CircleShape) {
        Text(text,Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=tint,maxLines=1)
    }
}

/** "Load more" footer for appended lists: button when more rows exist, spinner while fetching. */
@Composable internal fun LoadMoreFooter(visible:Boolean,loading:Boolean,label:String,onClick:()->Unit) {
    if(!visible && !loading) return
    Box(Modifier.fillMaxWidth().padding(vertical=Ui.Tight),contentAlignment=Alignment.Center) {
        if(loading) DaymarkLoader(Modifier.size(24.dp),label="Loading")
        else OutlinedButton(onClick=onClick,shape=Ui.Field,modifier=Modifier.heightIn(min=48.dp)) {Text(label)}
    }
}

/** Standard dialog: consistent title, content padding and button placement. */
@Composable internal fun AppDialog(title:String,dismiss:()->Unit,confirmLabel:String?=null,onConfirm:(()->Unit)?=null,
    dismissLabel:String="Close",confirmEnabled:Boolean=true,content:@Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest=dismiss,shape=RoundedCornerShape(24.dp),title={Text(title,fontWeight=FontWeight.Bold)},
        text={Column(verticalArrangement=Arrangement.spacedBy(Ui.Item),content=content)},
        confirmButton={if(confirmLabel!=null && onConfirm!=null) Button(onClick=onConfirm,enabled=confirmEnabled,shape=Ui.Field) {Text(confirmLabel)}},
        dismissButton={TextButton(onClick=dismiss) {Text(dismissLabel)}})
}

@Composable internal fun AppCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,borderTint:Color?=null,content:@Composable ColumnScope.()->Unit) {
    val colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)
    val border=BorderStroke(1.dp,(borderTint ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha=if(borderTint!=null) .3f else .6f))
    if(onClick!=null) Card(onClick,modifier.fillMaxWidth(),shape=Ui.Card,colors=colors,border=border,content={Column(Modifier.padding(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Tight),content=content)})
    else Card(modifier.fillMaxWidth(),shape=Ui.Card,colors=colors,border=border,content={Column(Modifier.padding(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Tight),content=content)})
}

/** Long-press (or hover) hint for any icon-only control, so every icon explains itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Tip(text:String,content:@Composable () -> Unit) {
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip {Text(text)}},state=rememberTooltipState(),content=content)
}

@Composable internal fun TipIconButton(icon:androidx.compose.ui.graphics.vector.ImageVector,description:String,onClick:()->Unit,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current,enabled:Boolean=true) {
    Tip(description) {IconButton(onClick=onClick,modifier=modifier,enabled=enabled) {Icon(icon,description,tint=tint)}}
}
