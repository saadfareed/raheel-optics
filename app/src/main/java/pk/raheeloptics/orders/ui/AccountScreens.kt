package pk.raheeloptics.orders.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pk.raheeloptics.orders.RaheelApp
import pk.raheeloptics.orders.R
import pk.raheeloptics.orders.core.*
import java.time.*

@Composable internal fun AccountScreen(state:UiState,model:OrdersViewModel) {
    var forgot by rememberSaveable {mutableStateOf(false)}
    if(state.codeEmail!=null) {
        EmailCodeScreen(state,model)
        return
    }
    if(forgot) {
        ForgotPasswordScreen(state,model) {model.dismissMessage();forgot=false}
        return
    }
    var register by rememberSaveable {mutableStateOf(false)}
    var email by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    var signupError by remember {mutableStateOf<String?>(null)}
    var privacy by remember {mutableStateOf(false)}
    if(privacy) PrivacyDialog {privacy=false}
    val emailError=email.takeIf {it.isNotBlank() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it.trim())}?.let {"Enter a valid email address."}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(contentPadding=PaddingValues(horizontal=Ui.Section,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(Ui.Section),
            horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.imePadding().safeDrawingPadding()) {
            item {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {BrandLogo(Modifier.size(88.dp).padding(8.dp))}
                Text(LocalBrand.current.config.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium)
                Text(LocalBrand.current.config.tagline,color=Muted,style=MaterialTheme.typography.bodyMedium)
            }}
            item {AppCard {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected=!register,onClick={register=false;signupError=null;confirmation=""},shape=SegmentedButtonDefaults.itemShape(0,2),enabled=!state.busy) {Text("Sign in")}
                    SegmentedButton(selected=register,onClick={register=true;signupError=null},shape=SegmentedButtonDefaults.itemShape(1,2),enabled=!state.busy) {Text("Create account")}
                }
                Spacer(Modifier.height(4.dp))
                Text(if(register) "Create your shop account" else "Welcome back",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(if(register) "Keep orders and customers in sync on every phone." else "Sign in to see your shop orders on every phone.",color=Muted,style=MaterialTheme.typography.bodyMedium)
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AppTextField(email,{email=it.trim().take(254)},"Email",keyboard=KeyboardOptions(keyboardType=KeyboardType.Email),error=emailError!=null,supporting=emailError)
                PasswordField(password,{password=it.take(128);signupError=null},"Password")
                if(register) PasswordField(confirmation,{confirmation=it.take(128);signupError=null},"Confirm password",signupError)
                if(register && signupError==null) Text("Use at least 12 characters.",color=Muted,style=MaterialTheme.typography.bodySmall)
                Button(onClick={if(register) {
                    signupError=runCatching {RegistrationRules.validate(password,confirmation)}.exceptionOrNull()?.message
                    if(signupError==null) model.register(email,password)
                } else model.login(email,password)},enabled=!state.busy && state.config.configured && email.isNotBlank() && emailError==null && password.isNotBlank(),
                    shape=Ui.Field,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(register) "Create account" else "Sign in")}
                TextButton(onClick={model.dismissMessage();forgot=true},enabled=!state.busy,modifier=Modifier.align(Alignment.End)) {Text("Forgot password?")}
            }}
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f));Text("  or  ",color=Muted,style=MaterialTheme.typography.labelMedium);HorizontalDivider(Modifier.weight(1f))
            }}
            item {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                OutlinedButton(onClick=model::continueAsGuest,enabled=!state.busy,shape=Ui.Field,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("Continue as guest")}
                Text("Guest orders stay on this phone and move to your account when you sign in.",color=Muted,style=MaterialTheme.typography.bodySmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }}
            if(!state.config.configured) item {Text("Cloud sign-in is not available yet. You can work as a guest.",color=Muted,style=MaterialTheme.typography.bodySmall)}
            item {TextButton(onClick={privacy=true}) {Text("Privacy")}}
        }
    }
}

@Composable
fun SplashScreen() {
    val app=LocalContext.current.applicationContext as RaheelApp
    val appearance by app.branding.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalBrand provides appearance) {
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val compact=maxHeight<400.dp
        Column(Modifier.fillMaxWidth().heightIn(min=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            val splashLogo=R.drawable.raheel_optics_logo
            Image(painterResource(splashLogo),LocalBrand.current.config.name+" logo",Modifier.size(if(compact) 48.dp else 112.dp))
            Spacer(Modifier.height(if(compact) 8.dp else 24.dp))
            Text(LocalBrand.current.config.name,color=MaterialTheme.colorScheme.onBackground,fontWeight=FontWeight.Bold,style=if(compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(LocalBrand.current.config.tagline,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(if(compact) 12.dp else 32.dp))
            AppLoader(color=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(28.dp))
        }
        }
    }
    }
}

@Composable
internal fun EmailCodeScreen(state:UiState,model:OrdersViewModel) {
    var code by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    val reset=state.codeIsReset
    androidx.activity.compose.BackHandler {if(!state.busy) model.cancelCode()}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item {TextButton(onClick=model::cancelCode,enabled=!state.busy) {Text("Back")}}
            item {BrandLogo(Modifier.size(64.dp))}
            item {Text(if(reset) "Reset password" else "Confirm your email",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineLarge)}
            item {Text("Enter the 6-digit code we sent to ${state.codeEmail}.",color=Muted)}
            state.message?.let {item {Message(it,model::dismissMessage)}}
            item {OutlinedTextField(code,{code=it.filter(Char::isDigit).take(10)},label={Text("Code")},singleLine=true,enabled=!state.busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth().testTag("email-code"))}
            if(reset) item {OutlinedTextField(password,{password=it},label={Text("New password (12+ characters)")},singleLine=true,enabled=!state.busy,
                visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())}
            item {Button(onClick={model.verifyCode(code,if(reset) password else null)},enabled=!state.busy && code.length>=6 && (!reset || password.length>=12),
                modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(state.busy) "Checking…" else if(reset) "Set password and sign in" else "Confirm and sign in")}}
            item {TextButton(onClick=model::resendCode,enabled=!state.busy) {Text("Send a new code")}}
        }
    }
}

@Composable
internal fun ForgotPasswordScreen(state:UiState,model:OrdersViewModel,back:()->Unit) {
    var email by rememberSaveable {mutableStateOf("")}
    androidx.activity.compose.BackHandler {if(!state.busy) back()}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item {TextButton(onClick=back,enabled=!state.busy) {Text("Back to sign in")}}
            item {BrandLogo(Modifier.size(64.dp))}
            item {Text("Forgot password",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineLarge)}
            item {Text("Enter your account email. We’ll send you a link to reset your password.",color=Muted)}
            state.message?.let {item {Message(it,model::dismissMessage)}}
            item {OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,enabled=!state.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())}
            item {Button(onClick={model.reset(email)},enabled=!state.busy && state.config.configured && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(state.busy) "Sending…" else "Email me a code")}}
            if(!state.config.configured) item {Text("Service is being set up. Please try again later.",color=Muted)}
        }
    }
}
