package pl.bydgoszcz.technikum.hackaton25

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.bydgoszcz.technikum.hackaton25.ui.theme.Hackaton25Theme
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.*
import android.content.Context
import android.util.Base64
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.room.*
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.spec.PKCS8EncodedKeySpec
import androidx.compose.ui.platform.LocalContext
import java.security.spec.InvalidKeySpecException
import java.security.SignatureException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Entity
data class TrustedKey(
    @PrimaryKey val publicKey: String
)

@Dao
interface TrustedKeyDao {
    @Query("SELECT * FROM TrustedKey")
    fun getAll(): List<TrustedKey>

    @Insert
    fun insert(key: TrustedKey)
}

@Database(entities = [TrustedKey::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trustedKeyDao(): TrustedKeyDao
}

class MainActivity : ComponentActivity() {
    private lateinit var keyPair: KeyPair

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyPair = getOrCreateKeyPair(this)
        setContent {
            Hackaton25Theme {
                MainScreen(keyPair)
            }
        }
    }

    private fun getOrCreateKeyPair(context: Context): KeyPair {
        val sharedPreferences = EncryptedSharedPreferences.create(
            "key_store",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val publicKeyString = sharedPreferences.getString("publicKey", null)
        val privateKeyString = sharedPreferences.getString("privateKey", null)

        return if (publicKeyString != null && privateKeyString != null) {
            val publicKey = Base64.decode(publicKeyString, Base64.DEFAULT)
            val privateKey = Base64.decode(privateKeyString, Base64.DEFAULT)
            KeyPair(
                KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey)),
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKey))
            )
        } else {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()

            with(sharedPreferences.edit()) {
                putString("publicKey", Base64.encodeToString(keyPair.public.encoded, Base64.DEFAULT))
                putString("privateKey", Base64.encodeToString(keyPair.private.encoded, Base64.DEFAULT))
                apply()
            }
            keyPair
        }
    }
}

@Composable
fun MainScreen(keyPair: KeyPair) {
    var inputText by remember { mutableStateOf("") }
    var outputText by remember { mutableStateOf("") }
    var loadText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val db = Room.databaseBuilder(
        context,
        AppDatabase::class.java, "database-name"
    ).build()
    val trustedKeyDao = db.trustedKeyDao()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Enter message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = {
                outputText = Base64.encodeToString(keyPair.public.encoded, Base64.DEFAULT)
            }) {
                Text("Export Public Key")
            }
            Button(onClick = {
                val currentDateTime = Calendar.getInstance().time.toString()
                val message = "$currentDateTime $inputText"
                val signature = Signature.getInstance("SHA256withRSA").apply {
                    initSign(keyPair.private)
                    update(message.toByteArray())
                }.sign()
                outputText = Base64.encodeToString(message.toByteArray(), Base64.DEFAULT) + ":" + Base64.encodeToString(signature, Base64.DEFAULT)
            }) {
                Text("Generate Signed Message")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = outputText,
            onValueChange = {},
            label = { Text("Output") },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            clipboardManager.setText(AnnotatedString(outputText))
        }) {
            Text("Copy to Clipboard")
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = loadText,
            onValueChange = { loadText = it },
            label = { Text("Load Public Key or Signed Message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cleanedInput = loadText.replace("\\s".toRegex(), "").replace("\\n".toRegex(), "")
                    val parts = cleanedInput.split(":")
                    if (parts.size == 2) {
                        val messageBytes = Base64.decode(parts[0], Base64.DEFAULT)
                        val signatureBytes = Base64.decode(parts[1], Base64.DEFAULT)
                        val keys = trustedKeyDao.getAll()
                        var verified = false
                        var decodedMessage = ""

                        for (key in keys) {
                            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(key.publicKey, Base64.DEFAULT)))
                            val signature = Signature.getInstance("SHA256withRSA")
                            signature.initVerify(publicKey)
                            signature.update(messageBytes)
                            if (signature.verify(signatureBytes)) {
                                verified = true
                                decodedMessage = String(messageBytes)
                                break
                            }
                        }

                        withContext(Dispatchers.Main) {
                            statusText = if (verified) {
                                "Signature verified. Decoded message: $decodedMessage"
                            } else {
                                "Signature verification failed"
                            }
                        }
                    } else {
                        // Check if the input is a valid public key
                        try {
                            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(cleanedInput, Base64.DEFAULT)))
                            trustedKeyDao.insert(TrustedKey(publicKey = cleanedInput))
                            withContext(Dispatchers.Main) {
                                statusText = "Public key imported successfully"
                            }
                        } catch (e: InvalidKeySpecException) {
                            withContext(Dispatchers.Main) {
                                statusText = "Invalid input format"
                            }
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        statusText = "Invalid Base64 input: ${e.message}"
                    }
                } catch (e: InvalidKeySpecException) {
                    withContext(Dispatchers.Main) {
                        statusText = "Invalid key specification: ${e.message}"
                    }
                } catch (e: SignatureException) {
                    withContext(Dispatchers.Main) {
                        statusText = "Signature error: ${e.message}"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusText = "An unexpected error occurred: ${e.message}"
                    }
                }
            }
        }) {
            Text("Load")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = statusText)
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    Hackaton25Theme {
        MainScreen(KeyPairGenerator.getInstance("RSA").generateKeyPair())
    }
}